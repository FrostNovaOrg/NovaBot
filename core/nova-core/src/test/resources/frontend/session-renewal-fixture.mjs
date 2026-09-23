/**
 * 服务端换了一把会话之后，界面接住回包里的新 CSRF 令牌
 *
 * 改密码、开关二次验证办成之后，当前这一把会话被换成新的：新 Cookie 由那一趟的 Set-Cookie 落下，
 * 新令牌在回包里，旧令牌从那一刻起不再管用。接令牌那一手收在 core.js 的 api 里，
 * 这里真跑那一份：假 fetch 之外，api 与 store 都是产品码。
 *
 * 每格自带反向那一半：没带令牌、令牌是空串或不是字符串的回包，不许把手上那一份冲掉；
 * 401 照旧整页重载、不碰令牌。只量一个方向的话，「见回包就换」与「从来不换」各有一半格子照样绿。
 *
 * 各格自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 SessionRenewalFrontendTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

const failures = [];
let checks = 0;

async function ask(name, fn) {
  checks++;
  try {
    await fn();
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) {
    throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
  }
}

// —— 假 fetch：按顺序吐预设的回包，记下每一趟带出去的请求头 ——

const sent = [];
let replies = [];
let reloads = 0;

globalThis.fetch = (url, opt) => {
  sent.push({url, headers: (opt && opt.headers) || {}});
  const reply = replies.shift() || {status: 200, body: {}};
  return Promise.resolve({status: reply.status, json: () => Promise.resolve(reply.body)});
};
globalThis.location = {reload: () => { reloads++; }};

const coreUrl = new URL('../../../main/resources/config-ui/core.js', import.meta.url).href;
const storeUrl = new URL('../../../main/resources/config-ui/store.js', import.meta.url).href;

let core = null;
let store = null;
try {
  core = await import(coreUrl);
  store = (await import(storeUrl)).store;
} catch (err) {
  failures.push('载入 core.js／store.js 就抛了：' + err.message);
}

/** 回到「手上拿着旧令牌、一趟请求都还没发」 */
function reset(queue) {
  if (!core) throw new Error('core.js 没载入，无从量起');
  sent.length = 0;
  replies = queue;
  reloads = 0;
  store.csrfToken = '旧令牌';
}

await ask('① 回包带新令牌：手上那一份换成新的，下一趟写请求带的就是它', async () => {
  reset([
    {status: 200, body: {success: true, message: '密码已改', csrfToken: '新令牌'}},
    {status: 200, body: {success: true}},
  ]);
  await core.api('/auth/password/change', {method: 'POST'});
  await core.api('/values', {method: 'POST'});
  same(sent[0].headers['X-CSRF-Token'], '旧令牌', '换之前那一趟带的令牌');
  same(store.csrfToken, '新令牌', '换会话的回包之后 store.csrfToken');
  same(sent[1].headers['X-CSRF-Token'], '新令牌', '下一趟写请求带的令牌');
});

await ask('② 回包没带令牌：手上那一份不动', async () => {
  reset([{status: 200, body: {success: false, message: '验证码不正确'}}]);
  await core.api('/auth/totp/disable', {method: 'POST'});
  same(store.csrfToken, '旧令牌', '没带令牌的回包之后 store.csrfToken');
});

await ask('③ 回包里的令牌是空串或不是字符串：不拿它冲掉手上那一份', async () => {
  reset([
    {status: 200, body: {success: true, csrfToken: ''}},
    {status: 200, body: {success: true, csrfToken: 42}},
    {status: 200, body: null},
  ]);
  await core.api('/a', {method: 'POST'});
  await core.api('/b', {method: 'POST'});
  await core.api('/c', {method: 'POST'});
  same(store.csrfToken, '旧令牌', '空串、数字、空回包之后 store.csrfToken');
});

await ask('④ 401 照旧整页重载，回包里带什么都不收', async () => {
  reset([{status: 401, body: {success: false, csrfToken: '不该收'}}]);
  core.api('/status');
  await new Promise(resolve => setTimeout(resolve, 0));
  same(reloads, 1, '401 之后整页重载次数');
  same(store.csrfToken, '旧令牌', '401 之后 store.csrfToken');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) {
  console.log('  红：' + line);
}
process.exit(failures.length ? 1 : 0);
