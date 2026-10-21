import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {register} from 'node:module';
import {installPushDom} from './push-test-dom.mjs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
installPushDom();
const push = await import('../../../main/resources/config-ui-pages/push.js');
const {defaultMessages} = await import('../../../main/resources/config-ui-pages/push-model.js');
const source = readFileSync(new URL('../../../main/resources/config-ui-pages/setup-streamer.js', import.meta.url), 'utf8');
// 保存函数位于模块末尾；执行原函数，并接入真实推送页草稿及保存逻辑。
const saveSource = source.slice(source.indexOf('async function saveStreamer()'));
const copy = value => JSON.parse(JSON.stringify(value));
const initial = () => [{platform: 'p', uid: 2, enabled: true, targets: [], extra: null}];
let disk, requests, beforePost, reject;
const listKeys = ['dynamicBlockWords', 'wordCloudBlockWords', 'wordCloudExcludeUids'];
const fields = (value, keys) => Object.fromEntries(keys.filter(key => Object.hasOwn(value, key)).map(key => [key, value[key]]));
const summary = user => ({...fields(user, ['platform', 'uid', 'enabled', 'roomId']),
  targets: (user.targets || []).map(target => ({...fields(target, ['platform', 'type', 'num', 'enabled']),
    messages: (target.messages || []).map(message => ({...fields(message, ['handler', 'enabled']),
      _custom: !!message.params && Object.keys(message.params).length > 0}))}))});
const version = user => JSON.stringify(user);
const response = payload => ({status: 200, json: async () => copy(payload)});
globalThis.fetch = async (path, options = {}) => {
  const url = String(path).replace('/config/api', '');
  requests.push({url, method: options.method || 'GET', body: options.body && JSON.parse(options.body)});
  if (options.method === 'POST') {
    if (beforePost) { const run = beforePost; beforePost = null; run(); }
    if (reject) return {...response(typeof reject === 'string' ? {success: false, message: reject} : reject), status: 400};
    const body = JSON.parse(options.body);
    if (url === '/datasource') disk = JSON.parse(body.content);
    else if (url === '/datasource/users') {
      if (disk.some(user => user.platform === body.user.platform && user.uid === body.user.uid)) {
        return response({success: false, message: '这位主播已经添加过了'});
      }
      disk.push(body.user);
      return response({success: true, version: version(body.user)});
    } else {
      const user = disk.find(user => url === '/datasource/users/p/' + user.uid);
      if (!user || body.version !== version(user)) {
        return response({success: false, conflict: true, missing: !user, message: '这位主播的配置刚被别人改过，请重新载入'});
      }
      // 保存接口仅为这三个字段保留缺失键；Java 接口测试同时验证该约定。
      for (const key of listKeys) if (!Object.hasOwn(body.user, key) && Object.hasOwn(user, key)) body.user[key] = user[key];
      for (const key of listKeys) {
        if (Array.isArray(body.user[key]) && body.user[key].some(value => typeof value === 'string' && !value.trim())) {
          return {...response({success: false, message: '名单不能含空行'}), status: 400};
        }
      }
      disk[disk.indexOf(user)] = body.user;
      return response({success: true, version: version(body.user)});
    }
    return response({success: true});
  }
  if (url === '/datasource') return response({success: true, content: JSON.stringify(disk)});
  if (url === '/datasource/users') return response({success: true, users: disk.map(summary)});
  const user = disk.find(user => url === '/datasource/users/p/' + user.uid);
  if (user) return response({success: true, user, version: version(user)});
  if (url === '/state') return response({sessions: [], incomplete: []});
  return response({success: true});
};
function wizard(uid = 3) {
  const messages = [];
  const draft = {streamer: {platform: 'p', uid, uname: '新增主播'}, targets: ['g1']};
  const targets = [{key: 'g1', sender: 'qq', type: 1, num: 111},
    {key: 'g2', sender: 'qq', type: 1, num: 222}];
  const names = ['store', 'draft', 'options', 'pushEntries', 'setPushEntries', 'api',
    'serializePush', 'say', 'markPushSaved', 'reload', 'renderStreamers', 'defaultMessages',
    'markDirty', 'seen', 'acceptAddedPushUser', 'trackSetupVersions'];
  const values = [{handlerList: []}, draft, targets,
    push.pushEntries, push.setPushEntries, async (url, opts) => (await fetch('/config/api' + url, opts)).json(),
    push.serializePush, text => messages.push(text), push.markPushSaved, push.reload,
    () => {}, defaultMessages, () => {}, {}, push.acceptAddedPushUser, push.trackSetupVersions];
  return {save: new Function(...names, saveSource + '\nreturn saveStreamer;')(...values), messages, draft};
}
async function reset(users = initial()) {
  disk = users; requests = []; beforePost = null; reject = null;
  await push.leave();
  await push.reload();
}
async function openB() {
  await push.showPush('2', '');
  const deadline = Date.now() + 10000;
  while (push.pushEntries().find(user => user.uid === 2)?._summary) {
    assert.ok(Date.now() < deadline, '主播详情应完成加载');
    await new Promise(resolve => setImmediate(resolve));
  }
}
let checks = 0;
const failures = [];
async function test(name, run) {
  checks++;
  console.log('检查用户问题：' + name);
  try { await run(); console.log('PASS ' + name); }
  catch (error) { failures.push(name); console.log('FAIL ' + name + ': ' + error.message); }
}
await test('跑向导添加 C 后，B 的未保存改动和改动计数仍在，随后能单独保存', async () => {
  await reset(); await openB();
  push.pushEntries().find(user => user.uid === 2).enabled = false;
  const b = push.pushEntries().find(user => user.uid === 2);
  assert.equal(await wizard().save(), true);
  assert.equal(disk.find(user => user.uid === 3).enabled, true);
  assert.equal(disk.find(user => user.uid === 2).enabled, true);
  assert.equal(push.pushEntries().find(user => user.uid === 2), b);
  assert.equal(b.enabled, false);
  assert.equal(push.changeCount(), 1);
  await push.save();
  assert.equal(disk.find(user => user.uid === 2).enabled, false);
  assert.equal(push.changeCount(), 0);
});
await test('向导提交前别人刚保存 B，添加 C 后那次保存仍在', async () => {
  await reset();
  beforePost = () => { disk[0].extra = '刚刚保存'; };
  assert.equal(await wizard().save(), true);
  assert.equal(disk.find(user => user.uid === 2).extra, '刚刚保存');
  assert.ok(disk.some(user => user.uid === 3));
  assert.equal(requests.some(request => request.url === '/datasource'), false);
  assert.ok(requests.some(request => request.url === '/datasource/users' && request.method === 'POST'));
});
await test('空列表能显示且向导能加上第一位主播', async () => {
  await reset([]);
  assert.deepEqual(push.pushEntries(), []);
  assert.equal(await wizard().save(), true);
  assert.deepEqual(disk.map(user => user.uid), [3]);
  assert.equal(push.changeCount(), 0);
});
await test('向导保存失败时保留 B 草稿，且不把 C 当作已保存', async () => {
  await reset(); await openB();
  push.pushEntries()[0].enabled = false;
  reject = '保存失败';
  const flow = wizard();
  assert.equal(await flow.save(), false);
  assert.equal(push.changeCount(), 1);
  assert.deepEqual(push.pushEntries().map(user => user.uid), [2]);
  assert.ok(flow.messages.some(text => text.includes('保存失败')));
});
await test('向导重复添加明确提示已经添加，不覆盖现有主播', async () => {
  await reset();
  const flow = wizard(2);
  assert.equal(await flow.save(), false);
  assert.ok(flow.messages.includes('这位主播已经添加过了'));
  assert.deepEqual(disk, initial());
});
// 照向导提示退回改选目标后，应能保存新目标，并继续保留别人的草稿。
await test('向导退回改选 g2 后能重存，连续重存使用最新版本', async () => {
  await reset(); await openB();
  push.pushEntries().find(user => user.uid === 2).enabled = false;
  const flow = wizard();
  assert.equal(await flow.save(), true);
  const firstVersion = version(disk.find(user => user.uid === 3));
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), true);
  assert.deepEqual(disk.find(user => user.uid === 3).targets.map(target => target.num), [222]);
  assert.deepEqual(push.pushEntries().find(user => user.uid === 3).targets.map(target => target.num), [222]);
  assert.equal(push.changeCount(), 1);
  assert.equal(push.pushEntries().find(user => user.uid === 2).enabled, false);
  const secondVersion = version(disk.find(user => user.uid === 3));
  flow.draft.targets = ['g1'];
  assert.equal(await flow.save(), true);
  const writes = requests.filter(request => request.method === 'POST');
  assert.deepEqual(writes.map(request => request.url), ['/datasource/users',
    '/datasource/users/p/3', '/datasource/users/p/3']);
  assert.equal(writes[1].body.version, firstVersion);
  assert.equal(writes[2].body.version, secondVersion);
  assert.equal(push.changeCount(), 1);
});
// 返回后别人已经修改这位主播时，应提示重新载入，不能覆盖那次保存。
await test('向导重存版本冲突时保留盘上内容和上次版本', async () => {
  await reset();
  const flow = wizard();
  assert.equal(await flow.save(), true);
  const firstVersion = version(disk.find(user => user.uid === 3));
  disk.find(user => user.uid === 3).extra = '别人保存的内容';
  const before = copy(disk);
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), false);
  assert.ok(flow.messages.some(text => text.includes('重新载入')));
  assert.deepEqual(disk, before);
  assert.equal(requests.at(-1).body.version, firstVersion);
  assert.equal(await flow.save(), false);
  assert.equal(requests.at(-1).body.version, firstVersion);
});
// 向导新增后立即在推送页编辑，应能保存，无需重新载入获取版本。
await test('向导刚加的主播在推送页修改后能直接保存', async () => {
  await reset();
  assert.equal(await wizard().save(), true);
  const addedVersion = version(disk.find(user => user.uid === 3));
  const requestCount = requests.length;
  push.pushEntries().find(user => user.uid === 3).enabled = false;
  await push.save();
  assert.equal(disk.find(user => user.uid === 3).enabled, false);
  assert.equal(push.changeCount(), 0);
  const afterSave = requests.slice(requestCount);
  assert.equal(afterSave.some(request => request.url === '/datasource/users/p/3' && request.method === 'GET'), false);
  const writes = afterSave.filter(request => request.method === 'POST');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].url, '/datasource/users/p/3');
  assert.equal(writes[0].method, 'POST');
  assert.equal(writes[0].body.version, addedVersion);
});
await test('推送页保存自己刚加的 C 后，向导能退回重存', async () => {
  await reset();
  const flow = wizard();
  assert.equal(await flow.save(), true);
  push.pushEntries().find(user => user.uid === 3).enabled = false;
  await push.save();
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), true);
  assert.equal(disk.find(user => user.uid === 3).targets[0].num, 222);
});
await test('C 删除后向导能在同一页重新添加', async () => {
  await reset();
  const flow = wizard();
  assert.equal(await flow.save(), true);
  disk = disk.filter(user => user.uid !== 3);
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), true);
  assert.equal(disk.find(user => user.uid === 3).targets[0].num, 222);
});
await test('向导重存 g2 后，推送页旧的 g1 草稿保存必须冲突', async () => {
  await reset();
  const flow = wizard();
  assert.equal(await flow.save(), true);
  push.pushEntries().find(user => user.uid === 3).enabled = false;
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), true);
  await push.save();
  assert.equal(disk.find(user => user.uid === 3).targets[0].num, 222);
  assert.equal(disk.find(user => user.uid === 3).enabled, true);
  assert.ok(push.changeCount() > 0);
});
await test('向导重存目标时保留在推送页保存的三份屏蔽名单', async () => {
  await reset();
  const flow = wizard();
  assert.equal(await flow.save(), true);
  const lists = {dynamicBlockWords: ['回放'], wordCloudBlockWords: ['原神启动'], wordCloudExcludeUids: ['123']};
  Object.assign(push.pushEntries().find(user => user.uid === 3), lists);
  await push.save();
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), true);
  for (const [key, value] of Object.entries(lists)) assert.deepEqual(disk.find(user => user.uid === 3)[key], value);
});
// 抓的问题：推送页重载只剩摘要时，向导再存把盘上名单抹掉。
await test('重新载入摘要后向导重存仍保留三份名单，显式空数组才能清空', async () => {
  await reset();
  const flow = wizard();
  assert.equal(await flow.save(), true);
  const lists = {dynamicBlockWords: ['回放'], wordCloudBlockWords: ['原神启动'], wordCloudExcludeUids: ['123']};
  Object.assign(push.pushEntries().find(user => user.uid === 3), lists);
  await push.save();
  await push.reload();
  const brief = push.pushEntries().find(user => user.uid === 3);
  for (const key of listKeys) assert.equal(Object.hasOwn(brief, key), false);
  flow.draft.targets = ['g2'];
  assert.equal(await flow.save(), true);
  for (const key of listKeys) {
    assert.equal(Object.hasOwn(requests.at(-1).body.user, key), false);
    assert.deepEqual(disk.find(user => user.uid === 3)[key], lists[key]);
  }
  Object.assign(push.pushEntries().find(user => user.uid === 3), Object.fromEntries(listKeys.map(key => [key, []])));
  assert.equal(await flow.save(), true);
  for (const key of listKeys) assert.deepEqual(disk.find(user => user.uid === 3)[key], []);
});

// 抓的问题：只多敲回车就算改动，或行末、中间的空行导致保存失败。
await test('三个框都丢弃空白行，只有回车不算改动，真实词条能保存', async () => {
  for (const existing of [false, true]) {
    const lists = {dynamicBlockWords: ['回放'], wordCloudBlockWords: ['原神启动'], wordCloudExcludeUids: ['123']};
    await reset([{...initial()[0], ...(existing ? lists : {})}]);
    await openB();
    for (const input of document.querySelector('#push-right').querySelectorAll('textarea')) {
      input.value += '\n  \n';
      await input.dispatchEvent({type: 'input', target: input});
    }
    assert.equal(push.changeCount(), 0, '只有回车不能算未保存改动');
    for (const input of document.querySelector('#push-right').querySelectorAll('textarea')) {
      const key = input.getAttribute('data-block-list');
      input.value = '  ' + lists[key][0] + '  \r\n\r\n  \r\n456\r\n';
      await input.dispatchEvent({type: 'input', target: input});
      assert.deepEqual(push.pushEntries()[0][key], [lists[key][0], '456']);
    }
    assert.equal(push.changeCount(), 1);
    await push.save();
    assert.equal(push.changeCount(), 0, '空行不应导致保存被拒绝');
    for (const key of listKeys) assert.deepEqual(disk[0][key], [lists[key][0], '456']);
  }
});

// 抓的问题：打完词回车或中间空一行，提交空串导致服务端拒绝保存。
await test('行末回车和中间空行不进入提交名单', async () => {
  await reset(); await openB();
  const lists = {dynamicBlockWords: ['回放', '唱歌'], wordCloudBlockWords: ['原神启动', '星穹铁道'], wordCloudExcludeUids: ['123', '456']};
  for (const input of document.querySelector('#push-right').querySelectorAll('textarea')) {
    const key = input.getAttribute('data-block-list');
    input.value = '  ' + lists[key][0] + '  \r\n\r\n  \r\n' + lists[key][1] + '\r\n';
    await input.dispatchEvent({type: 'input', target: input});
  }
  await push.save();
  const submitted = requests.findLast(request => request.method === 'POST').body.user;
  for (const key of listKeys) assert.deepEqual(submitted[key], lists[key]);
  assert.equal(push.changeCount(), 0);
  for (const key of listKeys) assert.deepEqual(disk[0][key], lists[key]);
});

// 抓的问题：手改名单为字符串、null 或数字后，主播设置区整个画不出来。
await test('非数组名单按空框显示，三个框和其他设置始终可用', async () => {
  for (const value of ['回放', null, 123]) {
    await reset([{...initial()[0], ...Object.fromEntries(listKeys.map(key => [key, value]))}]);
    await openB();
    const inputs = document.querySelector('#push-right').querySelectorAll('textarea');
    assert.equal(inputs.length, 3);
    for (const input of inputs) assert.equal(input.value, '');
    assert.equal(push.changeCount(), 0, '显示坏值不应擅自改文件');
  }
});

await test('主播页面读改存名单，切换页面保留草稿，冲突后保留输入，修改通道不清空名单', async () => {
  const lists = {dynamicBlockWords: ['回放'], wordCloudBlockWords: ['原神启动'], wordCloudExcludeUids: ['123']};
  await reset([{...initial()[0], ...lists, targets: [{platform: 'qq', type: 1, num: 111, enabled: true, messages: []}]}]);
  await openB();
  const inputs = document.querySelector('#push-right').querySelectorAll('textarea');
  assert.equal(inputs.length, 3);
  for (const input of inputs) {
    const key = input.getAttribute('data-block-list');
    assert.equal(input.value, lists[key].join('\n'));
    input.value += '\n456';
    await input.dispatchEvent({type: 'input', target: input});
  }
  assert.equal(push.changeCount(), 1);
  await push.showPush('', '');
  await push.showPush('2', '');
  assert.ok(document.querySelector('#push-right').querySelectorAll('textarea').every(input => input.value.includes('456')));
  disk[0].extra = '别人修改';
  await push.save();
  assert.equal(push.changeCount(), 1);
  assert.ok(document.querySelector('#push-conflict').textContent.includes('重新载入'));
  for (const key of Object.keys(lists)) assert.deepEqual(push.pushEntries()[0][key], [...lists[key], '456']);
  await reset([{...initial()[0], ...lists, targets: [{platform: 'qq', type: 1, num: 111, enabled: true, messages: []}]}]);
  await openB();
  push.pushEntries()[0].targets[0].enabled = false;
  await push.save();
  for (const [key, value] of Object.entries(lists)) assert.deepEqual(disk[0][key], value);
  await openB();
  const changed = {dynamicBlockWords: ['新词', '另一个词'], wordCloudBlockWords: ['新屏蔽词'], wordCloudExcludeUids: ['456']};
  for (const input of document.querySelector('#push-right').querySelectorAll('textarea')) {
    input.value = changed[input.getAttribute('data-block-list')].join('\n');
    await input.dispatchEvent({type: 'input', target: input});
  }
  await push.save();
  for (const [key, value] of Object.entries(changed)) assert.deepEqual(disk[0][key], value);
  assert.equal(push.changeCount(), 0);
});
await test('非法名单保存失败后说明哪份第几条，并保留可修正的草稿', async () => {
  await reset(); await openB();
  const input = document.querySelector('#push-right').querySelectorAll('textarea')[2];
  input.value = 'abc';
  await input.dispatchEvent({type: 'input', target: input});
  reject = {success: false, message: '推送配置有误，已拒绝保存',
    issues: ['第 1 个主播（p, uid 2）的词云排除名单（wordCloudExcludeUids）第 1 条必须为正整数']};
  const before = copy(disk);
  await push.save();
  const status = document.querySelector('#status-text').textContent;
  assert.ok(status.includes('wordCloudExcludeUids') && status.includes('第 1 条'), status);
  assert.deepEqual(disk, before);
  assert.equal(push.changeCount(), 1);
});
await push.leave();
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
