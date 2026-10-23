import {enhance, installCharts} from './streamers-dom.mjs';
/**
 * 主播详情在后台刷新失败时，只说一句「刷新失败，稍后自动重试」
 *
 * 页面上的数据还在，网络抖一下就在页脚冒一行红字、拼上浏览器或服务器的原话，读起来像整页坏了。
 * 这里在假 DOM 上真跑主播页：先让详情与正在直播卡各成功读到一次，再让请求失败，量三处：
 * 详情重取失败时页脚那句话与它的样式、页面上已画出的数据还在不在，
 * 正在直播卡每 15 秒那一趟失败时的提示（连不上与服务器回错两种）。
 */
import {register} from 'node:module';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const SHORT = '刷新失败，稍后自动重试';
let checks = 0;
const failures = [];
async function check(name, fn) {
  checks++;
  try { await fn(); console.log('PASS: ' + name); }
  catch (e) { failures.push(name + '：' + e.message); }
}

const nodes = new Map();
function element(tag) {
  const n = {tagName: tag, children: [], style: {}, attrs: {}, listeners: {}, textContent: '', _html: '', className: ''};
  Object.defineProperty(n, 'id', {get() { return this._id; }, set(v) { this._id = v; nodes.set(v, this); }});
  Object.defineProperty(n, 'innerHTML', {get() { return this._html; }, set(v) {
    this._html = v;
    const remove = c => { if (c.id) nodes.delete(c.id); c.children.forEach(remove); };
    this.children.forEach(remove); this.children = [];
  }});
  n.appendChild = c => { n.children.push(c); c.parentNode = n; return c; };
  n.insertBefore = c => { n.children.unshift(c); c.parentNode = n; return c; };
  n.remove = () => { nodes.delete(n.id); if (n.parentNode) n.parentNode.children = n.parentNode.children.filter(c => c !== n); };
  n.setAttribute = (k, v) => n.attrs[k] = v;
  n.getAttribute = k => n.attrs[k];
  n.removeAttribute = k => { delete n.attrs[k]; };
  n.addEventListener = (k, fn) => n.listeners[k] = fn;
  n.classList = {add() {}, remove() {}, toggle() {}, contains() { return false; }};
  Object.defineProperty(n, 'firstChild', {get() { return n.children[0]; }});
  return enhance(n);
}
for (const id of ['sv-list', 'sv-detail', 'sv-session', 'sd-back', 'sd-head', 'sd-body',
  'sx-back', 'sx-title', 'sx-body', 'status-text', 'save', 'discard', 'change-count']) {
  element('div').id = id;
}
const head = element('head');
globalThis.document = {hidden: false, querySelector: s => nodes.get(s.replace(/^#/, '')) || null,
  getElementById: id => nodes.get(id) || null, createElement: element,
  documentElement: {classList: {toggle() {}, add() {}, remove() {}, contains() { return false; }}},
  head, body: element('body'), addEventListener() {}};
installCharts(document);
globalThis.location = {hash: '#/streamers/sample/1', reload() {}};
// 正在直播卡的定时刷新：记下那一趟，由夹具自己按时点它
let tick = null;
globalThis.setInterval = fn => { tick = fn; return 1; };
globalThis.clearInterval = () => { tick = null; };

const response = (json, status = 200) => ({status, ok: status >= 200 && status < 300, json: async () => json});
/** 'ok' 一切照常；'down' 照浏览器那样抛 TypeError；'error' 回 500 带一句服务器的话 */
let mode = 'ok';
globalThis.fetch = async url => {
  const text = String(url);
  if (mode === 'down') throw new TypeError('Failed to fetch');
  if (mode === 'error') return response({success: false, message: '样例服务器原因'}, 500);
  if (text.includes('/live?')) return response({success: true, living: true, since: Date.now() - 60000,
    checkedAt: Date.now(), dataAt: Date.now(), metrics: {}, series: {}});
  if (text.includes('/insights?')) return response({success: true, insights: {metricsKnown: false, daily: []}});
  if (text.endsWith('/status')) return response({});
  return response({success: true, platform: 'sample', uid: 1, uname: '样例主播', configured: true, living: true,
    since: Date.now() - 60000, overview: {}});
};

const page = await import('../../../main/resources/config-ui-pages/streamers.js');
const flatten = n => [n, ...n.children.flatMap(flatten)];
const textOf = n => flatten(n).map(one => one.textContent + one.innerHTML).join('\n');
const status = () => nodes.get('status-text');

await check('详情重取失败：页脚只说那句短话，不带原话、不标红，已画出的数据还在', async () => {
  mode = 'ok';
  await page.refresh();
  assert.match(textOf(nodes.get('sd-head')), /样例主播/, '第一次应当画出主播名');
  mode = 'down';
  await page.refresh();
  console.log('页脚：' + status().textContent + '（' + status().className + '）');
  assert.equal(status().textContent, SHORT);
  assert.doesNotMatch(status().className, /\berr\b/, '后台刷新失败不该用错误色');
  assert.match(textOf(nodes.get('sd-head')), /样例主播/, '已画出的主播资料不该被抹掉');
  assert.doesNotMatch(textOf(nodes.get('sd-body')), /加载失败/, '已画出的详情不该被换成一句失败');
});

await check('正在直播卡定时刷新失败：只说那句短话', async () => {
  mode = 'ok';
  page.leave();
  await page.refresh();
  assert.ok(tick, '正在直播卡应当挂上定时刷新');
  await tick();
  mode = 'down';
  await tick();
  const error = flatten(nodes.get('sd-live')).find(one => one.className === 'live-error');
  assert.ok(error, '应当有一行刷新失败的提示');
  console.log('正在直播卡：' + error.textContent);
  assert.ok(error.textContent.startsWith(SHORT), '应以那句短话开头：' + error.textContent);
  assert.doesNotMatch(error.textContent, /无法连接|Failed to fetch/);
});

await check('服务器回了带 message 的错误：提示照样是那句短话，不拼服务器的话', async () => {
  mode = 'error';
  await tick();
  const error = flatten(nodes.get('sd-live')).find(one => one.className === 'live-error');
  assert.ok(error, '应当有一行刷新失败的提示');
  assert.ok(error.textContent.startsWith(SHORT), '应以那句短话开头：' + error.textContent);
  assert.doesNotMatch(error.textContent, /样例服务器原因/);
});

await check('刷新失败那一行不用错误色', async () => {
  const css = readFileSync(new URL('../../../main/resources/config-ui-pages/streamers.js', import.meta.url), 'utf8');
  assert.match(css, /\.live-error/, '主播页样式里应当有 .live-error 那一条');
  const rules = css.split('}').filter(rule => /\.live-error/.test(rule.split('{')[0]));
  for (const rule of rules) assert.doesNotMatch(rule, /var\(--err/, '这条把刷新失败标成了红字：' + rule.trim());
});

page.leave();
for (const failure of failures) console.log('FAIL: ' + failure);
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
