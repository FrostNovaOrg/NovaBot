import assert from 'node:assert/strict';
import {register} from 'node:module';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

// All identities and names are synthetic.
class Node {
  constructor() { this.html = ''; this.checked = false; this.listeners = {}; this.nodes = {}; }
  set innerHTML(value) { this.html = value; this.text = ''; }
  get innerHTML() { return this.html; }
  set textContent(value) { this.text = value; this.html = ''; }
  get textContent() { return this.text; }
  querySelector(key) { return this.nodes[key] ||= new Node(); }
  addEventListener(name, callback) { this.listeners[name] = callback; }
}
globalThis.document = {querySelector: () => null};
globalThis.location = {hash: '#/recap-preview/test/910000001'};
const start = Date.parse('2026-10-01T01:00:00Z');
const requests = [];
let pending, responseFailure = false;
globalThis.fetch = async url => {
  requests.push(url);
  if (url === '/config/api/recap-preview') return {status: 200, json: async () => ({success: true, streamers: [
    {platform: 'test', uid: 910000001, uname: '合成主播', sessionCount: 7, latestStart: start},
  ]})};
  if (pending) await pending.promise;
  return {status: 200, json: async () => responseFailure
    ? {success: false, message: '补齐观众账本没成功，请稍后再试。'}
    : {success: true, sessions: [{startTime: start, durationSeconds: 3660,
      level1: {message: '第一档正文\n礼物 ¥269 · ' + (url.includes('=true') ? '观众甲' : '观**'), notes: ['记录还不到 10 场']},
      level2: {message: '第二档正文 <合成内容>', notes: ['这条规律 3 场内说过，这次没说']},
    }]}};
};

const page = await import('../../../main/resources/config-ui-pages/recap-preview.js');
const box = new Node();
page.render(box);
const names = box.querySelector('#rp-names'), content = box.querySelector('#rp-content'), list = box.querySelector('#rp-list');
let checks = 0;
async function check(fn) { await fn(); checks++; }

await check(async () => {
  let resolve;
  pending = {promise: new Promise(done => { resolve = done; })};
  const loading = page.refresh();
  assert.match(content.textContent, /正在生成样稿/);
  resolve(); pending = null;
  await loading;
  assert.match(list.innerHTML, /合成主播/);
  assert.match(list.innerHTML, /7 场/);
  assert.match(list.innerHTML, /#\/recap-preview\/test\/910000001/);
});
await check(() => {
  assert.match(content.innerHTML, /第 1 档/);
  assert.match(content.innerHTML, /第 2 档/);
  assert.match(content.innerHTML, /第一档正文\n/);
  assert.match(content.innerHTML, /第二档正文 &lt;合成内容&gt;/);
  assert.match(content.innerHTML, /注：记录还不到 10 场/);
  assert.match(content.innerHTML, /注：这条规律 3 场内说过/);
  assert.match(content.innerHTML, /上午场 · 1 小时 1 分钟/);
  assert.match(content.innerHTML, /金额跟着这条通道/);
  assert.doesNotMatch(content.innerHTML, /观众甲/);
});
await check(async () => {
  names.checked = true;
  await names.listeners.change({target: names});
  assert.match(requests.at(-1), /showNicknames=true/);
  assert.match(content.innerHTML, /观众甲/);
});
await check(async () => {
  page.leave();
  assert.equal(content.innerHTML, '');
  await page.refresh();
  assert.equal(names.checked, false);
  assert.match(requests.findLast(url => url.includes('showNicknames')), /showNicknames=false/);
  assert.doesNotMatch(content.innerHTML, /观众甲/);
});
await check(async () => {
  // A response that arrives after leaving must not restore the original names.
  let resolve;
  pending = {promise: new Promise(done => { resolve = done; })};
  names.checked = true;
  const task = names.listeners.change({target: names});
  page.leave(); resolve(); pending = null;
  await task;
  assert.equal(content.innerHTML, '');
  await page.refresh();
  assert.doesNotMatch(content.innerHTML, /观众甲/);
});
await check(async () => {
  responseFailure = true;
  await page.refresh();
  assert.match(content.textContent, /补齐观众账本/);
  assert.match(content.textContent, /稍后再试/);
});
await check(async () => {
  responseFailure = false;
  location.hash = '#/recap-preview';
  const before = requests.filter(url => url.includes('showNicknames')).length;
  await page.refresh();
  assert.match(content.textContent, /请选择一位主播/);
  assert.equal(requests.filter(url => url.includes('showNicknames')).length, before);
});
await check(async () => {
  location.hash = '#/home';
  const before = requests.length;
  await page.refresh();
  assert.equal(requests.length, before, '其他页面的整体刷新不应读取样稿');
});
page.leave();
console.log(`跑了 ${checks} 格，全部通过`);
