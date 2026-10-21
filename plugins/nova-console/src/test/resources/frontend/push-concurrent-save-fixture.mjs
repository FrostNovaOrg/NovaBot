import assert from 'node:assert/strict';
import {register} from 'node:module';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
import {installPushDom} from './push-test-dom.mjs';
const {idNode} = installPushDom();

const initial = () => [1, 2].map(uid => ({platform: 'p', uid, enabled: true,
  targets: [{platform: 'q', type: 1, num: uid, enabled: true, messages: []}]}));
let disk = initial();
const copy = value => JSON.parse(JSON.stringify(value));
const version = user => JSON.stringify(user);
const conflict = '这位主播的配置刚被别人改过，请重新载入';
const requests = [];
const ok = payload => Promise.resolve({status: 200, json: async () => copy(payload)});
globalThis.fetch = (path, options = {}) => {
  const url = String(path).replace('/config/api', '');
  requests.push({url, method: options.method || 'GET'});
  if (url === '/datasource') {
    if (options.method === 'POST') {
      disk = JSON.parse(JSON.parse(options.body).content);
      return ok({success: true});
    }
    return ok({success: true, content: JSON.stringify(disk)});
  }
  if (url === '/datasource/users') return ok({success: true, users: disk.map(user => ({
    platform: user.platform, uid: user.uid, enabled: user.enabled,
    targets: user.targets.map(({platform, type, num, enabled}) => ({platform, type, num, enabled, messages: []})),
  }))});
  const match = /^\/datasource\/users\/p\/(\d+)(\/version|\/delete)?$/.exec(url);
  if (match) {
    const uid = Number(match[1]);
    const user = disk.find(user => user.uid === uid);
    if (!user) return ok({success: false, missing: true});
    if (options.method === 'POST') {
      const body = JSON.parse(options.body);
      if (body.version !== version(user)) return ok({success: false, conflict: true, message: conflict});
      disk[disk.indexOf(user)] = body.user;
      return ok({success: true, version: version(body.user), message: '已保存'});
    }
    return ok({success: true, user, version: version(user)});
  }
  if (url === '/state') return ok({sessions: [], incomplete: []});
  return ok({success: true});
};
const first = await import('../../../main/resources/config-ui-pages/push.js?first');
const second = await import('../../../main/resources/config-ui-pages/push.js?second');
async function open(page, uid) {
  await page.showPush(String(uid), '');
  // Wait for the detail request and its resulting state, including synchronous page implementations.
  const deadline = Date.now() + 5000;
  while (page.pushEntries().find(user => user.uid === uid)?._summary) {
    assert.ok(Date.now() < deadline, 'detail load timed out');
    await new Promise(resolve => setImmediate(resolve));
  }
}
const tests = [];
let checks = 0;
async function test(name, run) {
  checks++;
  try { await run(); console.log('PASS ' + name); }
  catch (error) { tests.push(name); console.log('FAIL ' + name + ': ' + error.message); }
}
await test('two pages editing different streamers preserve both changes', async () => {
  disk = initial();
  await first.reload();
  await second.reload();
  await open(first, 1);
  await open(second, 2);
  first.pushEntries().find(user => user.uid === 1).enabled = false;
  second.pushEntries().find(user => user.uid === 2).enabled = false;
  await first.save();
  await second.save();
  assert.deepEqual(disk.map(user => user.enabled), [false, false]);
});
await test('a conflict retains only the failed streamer draft and saves the other', async () => {
  disk = initial();
  await first.reload();
  await open(first, 1);
  await open(first, 2);
  first.pushEntries().forEach(user => { user.enabled = false; });
  disk[0].targets[0].enabled = false;
  await first.save();
  assert.deepEqual(disk.map(user => user.enabled), [true, false]);
  assert.equal(first.changeCount(), 1);
  assert.match(idNode('status-text').textContent, /1/);
  assert.match(idNode('status-text').textContent, /重新载入/);
});
await test('returning to the foreground detects a version change', async () => {
  disk = initial();
  await first.reload();
  await open(first, 1);
  location.hash = '#/push/1';
  await first.refresh({sub: '1', tail: ''});
  disk[0].enabled = false;
  await Promise.all((document.listeners.visibilitychange || []).map(fn => fn()));
  assert.match(idNode('push-conflict').textContent, /这位主播的配置刚被别人改过/);
  assert.ok(idNode('push-conflict').children.some(child => child.textContent === '重新载入'));
});
await test('reload confirms discarded edits and leaves other streamer drafts intact', async () => {
  disk = initial();
  await first.reload();
  await open(first, 1);
  await open(first, 2);
  first.pushEntries().forEach(user => { user.enabled = false; });
  await first.showPush('1', '');
  disk[0].targets[0].enabled = false;
  await Promise.all((document.listeners.visibilitychange || []).map(fn => fn()));
  const button = idNode('push-conflict').children[0];
  const reloading = button.click();
  const dialog = document.body.querySelector('.ask');
  assert.equal(dialog.hidden, false);
  assert.equal(first.changeCount(), 2);
  await dialog.dispatchEvent({type: 'click', target: dialog.querySelector('[data-act="no"]')});
  await reloading;
  assert.equal(first.changeCount(), 2);
  const accepted = button.click();
  await dialog.dispatchEvent({type: 'click', target: dialog.querySelector('[data-act="yes"]')});
  await accepted;
  assert.equal(first.changeCount(), 1);
  assert.equal(first.pushEntries().find(user => user.uid === 1).enabled, true);
  assert.equal(first.pushEntries().find(user => user.uid === 2).enabled, false);
  assert.equal(idNode('push-conflict').hidden, true);
});
await test('the directory loads all streamers and only the selected detail', async () => {
  disk = initial();
  await first.leave();
  requests.length = 0;
  await first.reload();
  assert.equal(first.pushEntries().length, 2);
  assert.deepEqual(requests.map(request => request.url), ['/datasource/users']);
  await first.showPush('2', '');
  assert.deepEqual(requests.map(request => request.url), ['/datasource/users', '/datasource/users/p/2']);
  assert.equal(first.changeCount(), 0);
});
await test('reloading a streamer removed elsewhere preserves other drafts', async () => {
  disk = initial();
  await first.reload();
  await open(first, 1);
  await open(first, 2);
  first.pushEntries().find(user => user.uid === 2).enabled = false;
  location.hash = '#/push/1';
  await first.refresh({sub: '1', tail: ''});
  disk.splice(0, 1);
  await Promise.all((document.listeners.visibilitychange || []).map(fn => fn()));
  await idNode('push-conflict').children[0].click();
  assert.deepEqual(first.pushEntries().map(user => user.uid), [2]);
  assert.equal(first.changeCount(), 1);
  assert.equal(location.hash, '#/push');
});
console.log('Tests run: ' + checks + ', Failures: ' + tests.length);
console.log('跑了 ' + checks + ' 格，红 ' + tests.length + ' 格');
process.exitCode = tests.length ? 1 : 0;
