// 运行引导页脚本，检查断网与代理错误提示、凭据重试与正常打开页面的结果。
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {strict as assert} from 'node:assert';

const html = readFileSync(new URL('../../../main/resources/config-ui-pages/napcat-bootstrap.html', import.meta.url), 'utf8');
// 引导脚本在 body 里；head 里那一段只在首帧前设主题与配色，不归这里量
const script = html.slice(html.indexOf('</head>')).match(/<script>\s*([\s\S]*?)<\/script>/)?.[1];
if (!script) throw new Error('没有找到 NapCat 引导页脚本');

async function bootstrap({offline, invalid = 0, checkResponse} = {}) {
  const nodes = new Map();
  const element = () => ({textContent: '', className: '', href: '', appendChild() {},
    addEventListener() {}, classList: {add() {}, remove() {}}});
  const node = selector => {
    if (!nodes.has(selector)) nodes.set(selector, element());
    return nodes.get(selector);
  };
  const requests = [];
  const stored = new Map();
  let mounted = false;
  let checks = 0;
  const context = {
    TypeError,
    document: {querySelector: node, getElementById: id => node('#' + id),
      createElement: element, body: element()},
    localStorage: {setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key)},
    NapCatResume: {start() { mounted = true; }},
    fetch: async (url, options) => {
      const path = String(url).replace('/novabot/config', '');
      requests.push({path, options});
      if (path === offline) throw new TypeError('Failed to fetch');
      if (path === '/api/auth/state') return {json: async () => ({csrfToken: 'sample-csrf'})};
      if (path.startsWith('/api/napcat/credential')) {
        const credential = path.endsWith('?renew=true') ? 'renewed-credential' : 'sample-credential';
        return {json: async () => ({success: true, credential})};
      }
      if (path === '/napcat/api/auth/check') {
        checks++;
        return checkResponse ? checkResponse(checks) : {json: async () => ({code: checks <= invalid ? 1 : 0})};
      }
      throw new Error('意外请求：' + path);
    },
  };
  let rejection;
  try {
    await runInNewContext(script.replaceAll('{{nova-prefix:js}}', '/novabot'), context);
  } catch (error) {
    rejection = error;
  }
  return {msg: node('#msg').textContent, step: node('#steps').textContent,
    title: node('#title').textContent, requests, stored, mounted, rejection};
}

const failures = [];
let checks = 0;
async function check(name, run) {
  checks++;
  try { await run(); } catch (error) { failures.push(name + '：' + error.message); }
}

// 抓取凭据断网后停在第一步，以及验证断网被说成凭据无效的问题。
for (const [name, offline, invalid, count] of [
  ['取凭据断网不再卡在第一步', '/api/napcat/credential', 0, 2],
  ['验证凭据断网不当成凭据无效', '/napcat/api/auth/check', 0, 3],
  ['重新取凭据断网显示连接失败', '/api/napcat/credential?renew=true', 1, 4],
  ['读取登录状态断网保留连接失败提示', '/api/auth/state', 0, 1],
]) {
  await check(name, async () => {
    const result = await bootstrap({offline, invalid});
    assert.ok(result.msg.includes('无法连接 NovaBot 控制台。'),
      '没有连接失败提示；提示=' + JSON.stringify(result.msg) + '，步骤=' + JSON.stringify(result.step));
    assert.equal(result.step, '');
    assert.equal(result.rejection, undefined, '不能留下未处理的拒绝');
    assert.equal(result.requests.length, count, '断网后不再换取凭据');
    assert.equal(result.mounted, false);
    if (offline === '/napcat/api/auth/check') {
      assert.ok(result.stored.has('token'), '网络失败不清除已有凭据');
    }
  });
}
await check('正常凭据直接打开 NapCat', async () => {
  const result = await bootstrap();
  assert.equal(result.rejection, undefined);
  assert.equal(result.mounted, true);
  assert.equal(result.requests.length, 3);
  assert.equal(result.stored.get('token'), JSON.stringify('sample-credential'));
  assert.equal(result.requests[1].options.headers['X-CSRF-Token'], 'sample-csrf');
  assert.equal(result.requests[2].options.headers.Authorization, 'Bearer sample-credential');
});
await check('凭据确实无效时仍只换取一次', async () => {
  const result = await bootstrap({invalid: 1});
  assert.equal(result.rejection, undefined);
  assert.equal(result.mounted, true);
  assert.equal(result.requests.length, 5);
  assert.equal(result.requests[3].path, '/api/napcat/credential?renew=true');
});
// 抓的用户故障：NapCat 两次拒绝凭据，引导页却没有提示核对 Token 和二次验证密钥。
await check('两次凭据无效仍清除凭据并说明原因', async () => {
  const result = await bootstrap({invalid: 2});
  assert.ok(result.msg.includes('两次获取的凭据均未被 NapCat 接受'));
  assert.ok(result.msg.includes('NapCat Token') && result.msg.includes('二次验证密钥'));
  assert.equal(result.stored.has('token'), false);
  assert.equal(result.mounted, false);
  assert.equal(result.requests.length, 5);
});

// 抓的用户故障：NapCat 短暂返回 502，引导页没重试就放弃打开界面。
await check('第一次返回 502，换取凭据后打开 NapCat', async () => {
  const result = await bootstrap({
    checkResponse: attempt => attempt === 1
      ? new Response('<!doctype html><title>502 Bad Gateway</title>', {status: 502})
      : Response.json({code: 0}),
  });
  assert.equal(result.mounted, true, '暂时的 502 恢复后没打开 NapCat');
  assert.deepEqual(result.requests.slice(2).map(({path}) => path), [
    '/napcat/api/auth/check', '/api/napcat/credential?renew=true', '/napcat/api/auth/check',
  ]);
  assert.equal(result.requests[4].options.headers.Authorization, 'Bearer renewed-credential');
  assert.equal(result.stored.get('token'), JSON.stringify('renewed-credential'));
});

// 抓的用户故障：反向代理没配，引导页却叫用户去查 Token。
for (const [name, status, body] of [
  ['NovaBot 返回 403 JSON', 403, '{"success":false,"message":"请求校验失败，请刷新页面后重试"}'],
  ['网页服务器返回 404 HTML', 404, '<!doctype html><title>404 Not Found</title>'],
]) {
  await check(name + ' 时提示检查反向代理', async () => {
    const result = await bootstrap({checkResponse: () => new Response(body, {status})});
    assert.ok(result.msg.includes('/config/napcat/') && result.msg.includes('反向代理')
      && result.msg.includes('附录 C'), '没有反向代理提示；提示=' + JSON.stringify(result.msg));
    assert.ok(!result.msg.includes('Token'), '提示误指向 Token');
    assert.equal(result.requests.length, 5, '两次验证都没有 NapCat 回复后才提示检查代理');
    assert.equal(result.requests[3].path, '/api/napcat/credential?renew=true');
    assert.equal(result.requests[4].path, '/napcat/api/auth/check');
    assert.equal(result.stored.has('token'), false);
    assert.equal(result.mounted, false);
  });
}

// 抓的用户故障：验证时控制台登录已过期，引导页却叫用户去查反向代理。
for (const expiredAttempt of [1, 2]) {
  await check('第 ' + expiredAttempt + ' 次验证返回 401 时提示重新登录', async () => {
    const result = await bootstrap({
      checkResponse: attempt => attempt === expiredAttempt
        ? new Response(null, {status: 401})
        : Response.json({code: 1}),
    });
    assert.ok(result.msg.includes('控制台登录已失效') && result.msg.includes('刷新页面重新登录'),
      '没有登录失效提示；提示=' + JSON.stringify(result.msg));
    assert.equal(result.requests.length, expiredAttempt === 1 ? 3 : 5, '401 后不再换取凭据');
    assert.equal(result.stored.has('token'), false);
    assert.equal(result.mounted, false);
  });
}

// 抓的用户故障：已有一次明确的 NapCat 拒绝，引导页却叫用户去配反向代理。
for (const rejectedAttempt of [1, 2]) {
  await check('第 ' + rejectedAttempt + ' 次是 NapCat 拒绝时提示核对凭据', async () => {
    const result = await bootstrap({
      checkResponse: attempt => attempt === rejectedAttempt
        ? Response.json({code: 1})
        : new Response('<!doctype html><title>502 Bad Gateway</title>', {status: 502}),
    });
    assert.ok(result.msg.includes('NapCat Token') && result.msg.includes('二次验证密钥'));
    assert.equal(result.requests.length, 5);
    assert.equal(result.mounted, false);
  });
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exitCode = failures.length ? 1 : 0;
