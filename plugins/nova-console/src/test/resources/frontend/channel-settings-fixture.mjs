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
    revenueVisible: true, disabled: [], menuHidden: []}], commands: [
    {name: '直播报告', disableable: true}, {name: '菜单', disableable: false},
    {name: '启用命令', disableable: false}, {name: '禁用命令', disableable: false}],
    subscriptions: [1, 2].map(uid => ({platform: 'q', num: 100, streamerUid: uid, users: [uid], type: 'live'})), incomplete: []});
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
const failures = [];
async function test(problem, run) {
  console.log('检查用户问题：' + problem);
  try { await run(); console.log('PASS ' + problem); }
  catch (error) { failures.push(problem); console.log('FAIL ' + problem + ': ' + error.stack); }
}
await test('甲通道金额保存在甲的配置，乙仍是默认，页面不调用旧状态写口', async () => {
  await push.reload(); await open(1);
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
await push.leave();
console.log('跑了 3 格，红 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
