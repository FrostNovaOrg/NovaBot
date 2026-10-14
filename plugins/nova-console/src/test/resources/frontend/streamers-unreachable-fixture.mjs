/**
 * 连不上服务器时，主播页的出错提示是中文，不露浏览器的英文原话
 *
 * 请求根本没到服务器时，浏览器只抛一个 TypeError，原话随浏览器而异（Failed to fetch、Load failed……）。
 * 这里在假 DOM 上真跑主播页，让假 fetch 照浏览器那样抛，量三处：
 * 正在直播卡的刷新、某一场报告图的取图、宿主 api() 的拒绝原因。
 * 服务器真回了带 message 的错误时，那句话照常显示——这一格是对照，防止修法把服务器的原话也一并换掉。
 */
import {register} from 'node:module';
import assert from 'node:assert/strict';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

let checks = 0;
const failures = [];
async function check(name, fn) {
  checks++;
  try { await fn(); console.log('PASS: ' + name); }
  catch (e) { failures.push(name + '：' + e.message); }
}

const nodes = new Map();
function element(tag) {
  const n = {tagName: tag, children: [], style: {}, attrs: {}, listeners: {}, textContent: '', _html: ''};
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
  n.addEventListener = (k, fn) => n.listeners[k] = fn;
  n.classList = {add() {}, remove() {}, toggle() {}, contains() { return false; }};
  Object.defineProperty(n, 'firstChild', {get() { return n.children[0]; }});
  return n;
}
for (const id of ['sv-list', 'sv-detail', 'sv-session', 'sd-back', 'sd-head', 'sd-tabs', 'sd-body',
  'sx-back', 'sx-title', 'sx-body']) {
  element('div').id = id;
}
globalThis.document = {hidden: false, querySelector: s => nodes.get(s.replace(/^#/, '')) || null,
  getElementById: id => nodes.get(id) || null, createElement: element,
  head: element('head'), body: element('body'), addEventListener() {}};
globalThis.location = {hash: '#/streamers/sample/1', reload() {}};
globalThis.setInterval = () => 1;
globalThis.clearInterval = () => {};

const abortError = () => Object.assign(new Error('This operation was aborted'), {name: 'AbortError'});
const response = (json, status = 200) => ({status, ok: status >= 200 && status < 300, json: async () => json});
/**
 * 这一趟取 /live 与取报告图时怎么答：'down' 照浏览器那样抛 TypeError，'error' 回 500 带一句话，
 * 'abort' 回包头到了、读正文时被中止，'html' 回包头到了、正文不是 JSON
 */
let mode = 'down';
globalThis.fetch = async url => {
  const text = String(url);
  if (text.includes('/live?') || text.endsWith('/report') || text.endsWith('/probe')) {
    if (mode === 'down') throw new TypeError('Failed to fetch');
    // 回包头已到、读正文时被中止：浏览器在 json() 上抛 AbortError
    if (mode === 'abort') return {status: 200, ok: true, json: async () => { throw abortError(); }};
    // 回包头已到、正文不是 JSON（反向代理的错误页）：json() 抛 SyntaxError
    if (mode === 'html') return {status: 502, ok: false, json: async () => JSON.parse('<html>')};
    return response({success: false, message: '直播数据暂不可用：样例原因'}, 500);
  }
  if (text.includes('/insights?')) return response({success: true, insights: {metricsKnown: false, daily: []}});
  if (text.endsWith('/status')) return response({});
  return response({success: true, platform: 'sample', uid: 1, configured: true, living: true,
    since: Date.now() - 60000, overview: {}});
};

const page = await import('../../../main/resources/config-ui-pages/streamers.js');
const core = await import('../../../../../../core/nova-core/src/main/resources/config-ui/core.js');
const flatten = n => [n, ...n.children.flatMap(flatten)];
const textOf = n => flatten(n).map(one => one.textContent + one.innerHTML).join('\n');

await check('连不上服务器：正在直播卡的刷新失败提示是中文，不含 Failed to fetch', async () => {
  mode = 'down';
  location.hash = '#/streamers/sample/1';
  await page.refresh();
  const card = nodes.get('sd-live');
  assert.ok(card, '正在直播卡应当画出来');
  const error = flatten(card).find(one => one.className === 'live-error');
  assert.ok(error, '应当有一行刷新失败的提示');
  console.log('正在直播卡：' + error.textContent);
  assert.doesNotMatch(error.textContent, /Failed to fetch/);
  assert.match(error.textContent, /^刷新失败：无法连接到服务器/);
});

await check('服务器回了带 message 的错误：正在直播卡照常显示那句话', async () => {
  mode = 'error';
  page.leave();
  location.hash = '#/streamers/sample/1';
  await page.refresh();
  const error = flatten(nodes.get('sd-live')).find(one => one.className === 'live-error');
  assert.ok(error, '应当有一行刷新失败的提示');
  console.log('正在直播卡：' + error.textContent);
  assert.match(error.textContent, /^刷新失败：直播数据暂不可用：样例原因/);
});

await check('连不上服务器：某一场的报告图取图失败提示是中文，不含 Failed to fetch', async () => {
  mode = 'down';
  page.leave();
  location.hash = '#/streamers/sample/1/1792015200000';
  await page.refresh();
  const text = textOf(nodes.get('sx-body'));
  console.log('报告图：' + text.replace(/\s+/g, ' ').trim());
  assert.doesNotMatch(text, /Failed to fetch/);
  assert.match(text, /无法连接到服务器/);
});

await check('连不上服务器：宿主 api() 拒绝时的原因是中文，不含 Failed to fetch', async () => {
  mode = 'down';
  let reason = null;
  await core.api('/probe').catch(e => { reason = e; });
  assert.ok(reason, 'api() 应当拒绝');
  console.log('api()：' + reason.message);
  assert.doesNotMatch(reason.message, /Failed to fetch/);
  assert.equal(reason.message, '无法连接到服务器');
});

await check('收到回包头后被中止：api() 原样抛出 AbortError，不改成「内容无法识别」', async () => {
  mode = 'abort';
  let reason = null;
  await core.api('/probe').catch(e => { reason = e; });
  assert.ok(reason, 'api() 应当拒绝');
  console.log('api()：' + reason.name + '：' + reason.message);
  assert.equal(reason.name, 'AbortError');
  assert.doesNotMatch(reason.message, /无法识别/);
});

await check('回的不是 JSON：api() 拒绝时的原因是中文，带上状态码', async () => {
  mode = 'html';
  let reason = null;
  await core.api('/probe').catch(e => { reason = e; });
  assert.ok(reason, 'api() 应当拒绝');
  console.log('api()：' + reason.message);
  assert.equal(reason.message, '服务器返回的内容无法识别（HTTP 502）');
});

page.leave();
for (const failure of failures) console.log('FAIL: ' + failure);
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
