import assert from 'node:assert/strict';
import {register} from 'node:module';
import {readFileSync} from 'node:fs';
import {bracedFrom} from '../../../../../../core/nova-core/src/test/resources/frontend/layout-dom.mjs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.style = {}; this.ownText = ''; }
  setAttribute(name, value) { this[name] = String(value); }
  appendChild(node) { node.parentNode = this; this.children.push(node); return node; }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(node => node !== this); this.parentNode = null; }
  set textContent(value) { this.children = []; this.ownText = String(value); }
  get textContent() { return this.ownText + this.children.map(node => node.textContent).join(''); }
  set innerHTML(value) { if (value === '') { this.textContent = ''; return; } throw new Error('档案文字不能交给网页解析器：' + value); }
}
globalThis.document = {createElement: tag => new Element(tag), querySelector: () => null};
const {store} = await import('../../../../../../core/nova-core/src/main/resources/config-ui/store.js');
const {mountProfile} = await import('../../../main/resources/config-ui-pages/streamers-profile.js');
const identity = {platform: 'bilibili', uid: '990000000101'};
const stamp = Date.parse('2026-10-20T00:00:00Z');
const full = {tier: 2, tierSetAt: stamp, concerns: [{text: '<img src=x onerror=alert(1)>', setAt: stamp + 1000}],
  exclusions: ['slot_compare', 'newcomer_speak_return', 'regulars_absent', 'pk_share'].map(type => ({type, setAt: stamp + 2000})),
  paused: true, pauseSetAt: stamp + 3000, status: 'READY'};
const walk = node => [node, ...node.children.flatMap(walk)];
let payload = {success: true, profile: full};
let failure = null, status = 200, gate = null;
const requests = [];
globalThis.fetch = async (url, options) => {
  requests.push({url: String(url), method: options?.method || 'GET'});
  const response = {status, json: async () => payload};
  if (failure) throw failure;
  if (gate) return gate;
  return response;
};
let checks = 0;
async function check(name, run) {
  checks++;
  try { await run(); console.log('通过：' + name); }
  catch (error) { console.error('失败：' + name + '：' + error.message); process.exitCode = 1; }
  finally { payload = {success: true, profile: full}; failure = null; gate = null; status = 200; }
}
// 私人设置要完整可见，而且原话即使像标签也只显示文字。
await check('管理员和成员都看到设置与各自时间，页面只读且原话安全显示', async () => {
  for (const role of ['ADMIN', 'MEMBER']) {
    store.role = role;
    const host = new Element('div'); const view = mountProfile(host, identity); await view.ready;
    for (const value of ['主播档案', '第 2 档', '是否暂停：是', '2026', '10', '20', '08:00', '<img src=x onerror=alert(1)>', '开播时间', '新人开口', '老观众', 'PK'])
      assert.ok(host.textContent.includes(value), value);
    assert.equal(walk(host).filter(n => /input|button|textarea|select|img/.test(n.tagName)).length, 0);
    assert.equal(walk(host).filter(n => n.tagName === 'p' && n.textContent.includes('2026')).length, 7);
    assert.equal(requests.at(-1).url, '/config/api/streamers/bilibili/990000000101/profile');
    assert.equal(requests.at(-1).method, 'GET');
    view.destroy();
  }
});
// 没设置过的字段不能凭空显示一个设置日期。
await check('空档案显示默认档位、未设置时间和空条目', async () => {
  payload = {success: true, profile: {tier: 1, paused: false, concerns: [], exclusions: [], status: 'READY'}};
  const host = new Element('div'); await mountProfile(host, identity).ready;
  for (const word of ['第 1 档', '是否暂停：否', '尚未设置', '暂无']) assert.ok(host.textContent.includes(word));
});
// 坏件不能看起来像已经恢复发送的正常档案。
await check('档案读不出明确显示自动发送暂停', async () => {
  payload = {success: true, profile: {...full, status: 'UNREADABLE'}};
  const host = new Element('div'); await mountProfile(host, identity).ready;
  assert.match(host.textContent, /档案读不出/); assert.match(host.textContent, /已停发/);
});
// 权限拒绝或网络故障不能显示旧值，也不暴露后台原始错误。
await check('无权限和断网都不露出私人设置', async () => {
  for (const mode of ['denied', 'offline']) {
    payload = {success: false, message: '内部错误原话'}; status = 404;
    failure = mode === 'offline' ? new TypeError('内部错误原话') : null;
    const host = new Element('div'); await mountProfile(host, identity).ready;
    assert.match(host.textContent, /档案暂时看不到/); assert.doesNotMatch(host.textContent, /回访|第 2 档|内部错误原话/);
  }
});
// 切到另一位主播之后，较晚回来的私人设置不能串到新页面。
await check('离开后到达的档案回包不写入另一位主播页面', async () => {
  let resolveOld;
  gate = new Promise(resolve => { resolveOld = resolve; });
  const host = new Element('div'); const old = mountProfile(host, identity); old.destroy();
  gate = null; payload = {success: true, profile: {...full, concerns: [{text: '第二位主播', setAt: stamp}]}};
  await mountProfile(host, {...identity, uid: '990000000102'}).ready;
  resolveOld({status: 200, json: async () => ({success: true, profile: full})}); await old.ready;
  assert.match(host.textContent, /第二位主播/); assert.doesNotMatch(host.textContent, /<img/);
  assert.equal(requests.at(-1).url, '/config/api/streamers/bilibili/990000000102/profile');
});
// 损坏后的恢复入口必须真的重置并刷新，不能只把警告藏起来。
await check('核对恢复按钮提交当前主播并显示恢复后的空档案', async () => {
  for (const role of ['ADMIN', 'MEMBER']) {
    store.role = role;
    payload = {success: true, profile: {...full, status: 'UNREADABLE'}};
    const host = new Element('div'); await mountProfile(host, identity).ready;
    const button = walk(host).find(node => node.tagName === 'button' && node.textContent.includes('档案已核对，恢复'));
    assert.ok(button, '缺少核对恢复按钮');
    payload = {success: true, profile: {tier: 1, paused: false, concerns: [], exclusions: [], status: 'READY'}};
    await button.onclick();
    assert.equal(requests.at(-1).method, 'POST');
    assert.equal(requests.at(-1).url, '/config/api/streamers/bilibili/990000000101/profile/recover');
    assert.match(host.textContent, /第 1 档/); assert.match(host.textContent, /是否暂停：否/);
    assert.doesNotMatch(host.textContent, /读不出|已停发|核对|<img/);
  }
});
// 提交失败时不能把档案画成恢复成功，按钮应允许重试。
await check('恢复失败保留停发提示和重试入口', async () => {
  payload = {success: true, profile: {...full, status: 'UNREADABLE'}};
  const host = new Element('div'); await mountProfile(host, identity).ready;
  const button = walk(host).find(node => node.tagName === 'button'); assert.ok(button);
  failure = new TypeError('offline'); await button.onclick();
  assert.match(host.textContent, /已停发/); assert.match(host.textContent, /恢复失败/); assert.equal(button.disabled, false);
});
// 暂时故障不应诱导用户清空一份仍然完好的档案。
await check('暂时读不出提示重试而不提供重置按钮', async () => {
  payload = {success: true, profile: {...full, status: 'TEMPORARILY_UNREADABLE'}};
  const host = new Element('div'); await mountProfile(host, identity).ready;
  assert.match(host.textContent, /暂时读不出/); assert.match(host.textContent, /停发/);
  assert.equal(walk(host).filter(node => node.tagName === 'button').length, 0);
});
// 主播页必须实际挂载档案，而非只在单独测试组件时显示。
await check('主播详情页实际挂上当前主播的档案', async () => {
  const source = readFileSync(new URL('../../../main/resources/config-ui-pages/streamers.js', import.meta.url), 'utf8');
  const code = bracedFrom(source, 'function renderHead(');
  const host = new Element('div'); let mounted;
  const deps = {profileView: null, assistantEntry: null, $: () => host,
    el: tag => new Element(tag), detail: {...identity, uname: '示例主播'}, where: identity,
    chip: () => new Element('span'), dashboardHash: () => '#/streamers', mountAssistantEntry: () => ({destroy() {}}),
    mountProfile: (...args) => { mounted = mountProfile(...args); return mounted; }};
  new Function(...Object.keys(deps), code + '\nreturn renderHead;')(...Object.values(deps))();
  assert.ok(mounted, '主播详情未挂载档案'); await mounted.ready;
  assert.match(host.textContent, /主播档案/); assert.match(host.textContent, /第 2 档/);
  assert.equal(requests.at(-1).url, '/config/api/streamers/bilibili/990000000101/profile');
});
console.log('跑了 ' + checks + ' 格');
