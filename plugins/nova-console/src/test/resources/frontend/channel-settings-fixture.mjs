import assert from 'node:assert/strict';
import {register} from 'node:module';
import {installPushDom} from './push-test-dom.mjs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
const {idNode} = installPushDom();
const copy = value => JSON.parse(JSON.stringify(value));
const initial = () => [1, 2].map(uid => ({platform: 'p', uid, enabled: true,
  targets: [{platform: 'q', type: 1, num: 100, enabled: true, messages: []}]}));
let disk = initial();
const version = user => JSON.stringify(user);
const requests = [];
let sessionDisabled = [];
const quietByUid = {1: true, 2: false};
let quietGlobal;
const response = payload => ({status: 200, json: async () => copy(payload)});
globalThis.fetch = async (path, options = {}) => {
  const url = String(path).replace('/config/api', '');
  requests.push({url, method: options.method || 'GET'});
  if (url === '/datasource/users') return response({success: true, users: disk});
  const match = /^\/datasource\/users\/p\/(\d+)(\/version)?$/.exec(url);
  if (match) {
    const user = disk.find(user => user.uid === Number(match[1]));
    if (options.method === 'POST') {
      const body = JSON.parse(options.body);
      if (body.version !== version(user)) return response({success: false, conflict: true, message: '配置已改过，请重新载入'});
      disk[disk.indexOf(user)] = body.user;
      return response({success: true, version: version(body.user)});
    }
    return response({success: true, user, version: version(user)});
  }
  if (url === '/state') return response({sessions: [{platform: 'q', typeCode: 1, num: 100,
    revenueVisible: true, disabled: sessionDisabled, menuHidden: [], quietActive: true,
    channels: [{streamerPlatform: 'p', streamerUid: 1, quietActive: quietByUid[1]},
      {streamerPlatform: 'p', streamerUid: 2, quietActive: quietByUid[2]}]}], commands: [
    {name: '直播报告', category: '数据查询', disableable: true, channelScoped: true},
    {name: '天气', category: '天气查询', disableable: true, channelScoped: false},
    {name: '菜单', category: '命令管理', disableable: false},
    {name: '启用命令', category: '命令管理', disableable: false}, {name: '禁用命令', category: '命令管理', disableable: false}],
    subscriptions: [1, 2].map(uid => ({platform: 'q', num: 100, streamerUid: uid, users: [uid], type: 'live'})), incomplete: [],
    quietGlobal});
  return response({success: true});
};
const push = await import('../../../main/resources/config-ui-pages/push.js');
async function open(uid) {
  await push.showPush(String(uid), 'q|1|100');
  const deadline = Date.now() + 10000;
  while (push.pushEntries().find(user => user.uid === uid)?._summary) {
    assert.ok(Date.now() < deadline, '通道详情应完成加载');
    await new Promise(resolve => setImmediate(resolve));
  }
}
function input(label) {
  return idNode('push-right').querySelectorAll('input').find(node => node.getAttribute('aria-label') === label);
}
function text(node) {
  return node.textContent + (node.innerHTML || '').replace(/<[^>]*>/g, '') + (node.children || []).map(text).join('');
}
const failures = [];
async function test(problem, run) {
  console.log('检查用户问题：' + problem);
  try { await run(); console.log('PASS ' + problem); }
  catch (error) { failures.push(problem); console.log('FAIL ' + problem + ': ' + error.stack); }
}
await test('甲通道金额保存在甲的配置，乙仍是默认，页面不调用旧状态写口', async () => {
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await open(1);
  assert.ok(input('直播报告'), '载入运行状态后应列出主播命令');
  const revenue = input('直播收益等金额');
  assert.ok(revenue, '金额开关应显示');
  assert.equal(revenue.checked, false, '旧群金额设置不应作为通道的值');
  revenue.checked = true; await revenue.dispatchEvent({type: 'change'});
  assert.equal(disk[0].targets[0].revenueVisible, undefined);
  await push.save();
  assert.equal(disk[0].targets[0].revenueVisible, true);
  assert.equal(disk[1].targets[0].revenueVisible, undefined);
  assert.equal(requests.some(request => request.method === 'POST' && request.url.startsWith('/state/')), false);
  assert.equal(input('菜单'), undefined);
  assert.equal(input('启用命令'), undefined);
  assert.equal(input('禁用命令'), undefined);
  assert.equal(input('天气'), undefined);
  assert.equal(idNode('push-right').textContent.includes('命令管理'), false);
});
await test('金额拨回默认后草稿消失，避免无意写入设置', async () => {
  await push.reload(); await open(2);
  const revenue = input('直播收益等金额');
  revenue.checked = true; await revenue.dispatchEvent({type: 'change'});
  assert.ok(push.changeCount() > 0);
  const back = input('直播收益等金额');
  back.checked = false; await back.dispatchEvent({type: 'change'});
  assert.equal(push.changeCount(), 0);
  await open(1);
});
await test('QQ 先关过甲命令时，页面保存保留草稿并提示重新载入', async () => {
  await open(1);
  const revenue = input('直播收益等金额');
  revenue.checked = false; await revenue.dispatchEvent({type: 'change'});
  disk[0].targets[0].disabledCommands = ['直播报告'];
  await push.save();
  assert.equal(disk[0].targets[0].revenueVisible, true);
  assert.deepEqual(disk[0].targets[0].disabledCommands, ['直播报告']);
  assert.ok(push.changeCount() > 0);
  assert.match(idNode('push-conflict').textContent, /重新载入/);
});
await test('同群甲通道正在静音时显示静音中，乙通道不静音时不显示', async () => {
  disk = initial();
  disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
  disk[1].targets[0].quietHours = {mode: 'off'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await open(1);
  assert.ok(text(idNode('push-right')).includes('00:00–23:59 · 静音中'), '甲应显示服务端给出的通道静音状态');
  await open(2);
  assert.equal(text(idNode('push-right')).includes('· 静音中'), false, '乙不能使用群或甲的静音状态');
});
await test('群里关了插件命令时通道行显示计数，再关本通道的直播报告后计数增加', async () => {
  disk = initial();
  disk[1].targets[0].disabledCommands = ['直播报告'];
  sessionDisabled = ['天气'];
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.ok(text(idNode('push-right')).includes('已禁用 1 条命令'), '插件命令应计入通道行');
  await push.showPush('2');
  assert.ok(text(idNode('push-right')).includes('已禁用 2 条命令'), '通道和群插件命令应合计');
  await open(2);
  assert.equal(input('天气'), undefined, '通道命令开关仍只列主播命令');
  assert.equal(text(idNode('push-right')).includes('但这些命令已不存在'), false, '群插件命令不能当作失效的通道开关');
});
await test('通道明明在静音，主播页的通道行一眼看不出，以为推送坏了', async () => {
  const rowQuiet = () => {
    const rows = idNode('push-right').querySelectorAll('.rowcard');
    assert.equal(rows.length, 1, '主播页应列出这位主播的那一条通道');
    return rows.some(row => text(row).includes('静音中'));
  };
  disk = initial(); sessionDisabled = [];
  disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
  disk[1].targets[0].quietHours = {mode: 'off'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.ok(rowQuiet(), '服务端判甲通道正在静音，通道行应出「静音中」');
  await push.showPush('2');
  assert.equal(rowQuiet(), false, '乙通道不在静音，通道行不应出「静音中」');
  // 草稿改了静音档位：服务端那份判定说的是已存的档位，与通道页摘要同样不再采用
  push.pushEntries().find(user => user.uid === 1).targets[0].quietHours = {mode: 'off'};
  await push.showPush('1');
  assert.equal(rowQuiet(), false, '未保存的静音档位不能配上已存档位的「静音中」');
  // 没设静音时段：服务端判不在静音
  disk = initial(); quietByUid[1] = false;
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.equal(rowQuiet(), false, '没设静音时段时通道行不应出「静音中」');
  quietByUid[1] = true;
});
function channelRowQuiet() {
  const rows = idNode('push-right').querySelectorAll('.rowcard');
  assert.equal(rows.length, 1, '主播页应列出这位主播的那一条通道');
  return rows.some(row => text(row).includes('静音中'));
}
await test('通道设了自己的时段但此刻不在时段里，通道行却挂着静音中', async () => {
  disk = initial(); sessionDisabled = []; quietByUid[1] = false;
  disk[0].targets[0].quietHours = {mode: 'custom', start: '00:00', end: '23:59'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.equal(channelRowQuiet(), false, '服务端判甲通道此刻不在静音，设了时段也不应出「静音中」');
  quietByUid[1] = true;
});
await test('通道跟随全局、全局正在静音，通道行看不出在静音', async () => {
  disk = initial(); sessionDisabled = []; quietByUid[1] = true;
  quietGlobal = {start: '00:00', end: '23:59'};
  await push.reload();
  push.setRuntimeState(await (await fetch('/config/api/state')).json());
  await push.showPush('1');
  assert.ok(channelRowQuiet(), '服务端判跟随全局的甲通道正在静音，通道行应出「静音中」');
  quietGlobal = undefined;
});
await push.leave();
console.log('跑了 8 格，红 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
