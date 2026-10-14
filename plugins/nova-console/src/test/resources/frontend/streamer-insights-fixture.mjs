import {register} from 'node:module';
import assert from 'node:assert/strict';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));
const model = await import('../../../main/resources/config-ui-pages/streamers-model.js');
let checks = 0;
const failures = [];
async function check(name, fn) {
  checks++;
  try { await fn(); console.log('PASS: ' + name); }
  catch (e) { failures.push(name + '：' + e.message); }
}
await check('选中近七天后按地址恢复，缺省三十天', () => {
  const hash = model.detailHash('sample', '1', 'overview', 'days=7');
  assert.equal(model.parseStreamersHash(hash).days, 7);
  assert.equal(model.parseStreamersHash('#/streamers/sample/1').days, 30);
  assert.equal(model.parseStreamersHash('#/streamers/sample/1?days=8').days, 30);
});
await check('没有场次不画柱并提示，有场次数值零仍画柱', () => {
  const empty = model.insightsPoint({date: '2026-10-15', sessions: 0, revenue: 0}, 'revenue');
  const zero = model.insightsPoint({date: '2026-10-16', sessions: 1, revenue: 0}, 'revenue');
  assert.equal(empty.draw, false);
  assert.match(empty.title, /这天没有记录到开播/);
  assert.equal(zero.draw, true);
  assert.match(zero.title, /2026-10-16.*0\.00.*1 场/);
  const unknown = model.insightsPoint({date: '2026-10-17', sessions: 1, audience: null}, 'audience');
  assert.equal(unknown.draw, false);
  assert.match(unknown.title, /暂无数据/);
});

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
for (const id of ['sv-list', 'sv-detail', 'sv-session', 'sd-back', 'sd-head', 'sd-tabs', 'sd-body']) {
  element('div').id = id;
}
globalThis.document = {hidden: false, querySelector: s => nodes.get(s.replace(/^#/, '')) || null,
  getElementById: id => nodes.get(id) || null, createElement: element,
  head: element('head'), body: element('body'), addEventListener() {}};
globalThis.location = {hash: '#/streamers/sample/1'};
let intervals = 0, cleared = 0;
globalThis.setInterval = () => ++intervals;
globalThis.clearInterval = () => cleared++;
const requests = [];
const pending = [];
const response = json => ({status: 200, ok: true, json: async () => json});
let compositionScenario = null;
globalThis.fetch = async url => {
  requests.push(String(url));
  if (String(url).includes('/insights?')) {
    if (String(url).includes('days=90')) return new Promise(resolve => pending.push(resolve));
    return response({success: true, insights: {from: 1792015200000, sessions: 1, durationSeconds: 600,
      metricsKnown: true, revenue: 0, messages: 0, audience: null, interactionAudience: null,
      composition: null, coverage: {missing: 1}, daily: [
        {date: '2026-10-15', sessions: 0, revenue: 0}, {date: '2026-10-16', sessions: 1, revenue: 0}],
      ...compositionScenario}});
  }
  if (String(url).endsWith('/status')) return response({});
  if (String(url).includes('/live?')) return response({success: true, living: false, metrics: {}});
  return response({success: true, platform: 'sample', uid: 1, configured: true, living: false, overview: {}});
};
const page = await import('../../../main/resources/config-ui-pages/streamers.js');
const flatten = n => [n, ...n.children.flatMap(flatten)];
await check('切换与后退只重读概况新卡，保留本场节点和十五秒刷新', async () => {
  await page.refresh();
  const live = nodes.get('sd-live');
  const beforeIntervals = intervals, beforeCleared = cleared;
  requests.length = 0;
  location.hash = '#/streamers/sample/1?days=7';
  await page.refresh();
  assert.equal(nodes.get('sd-live'), live);
  assert.equal(intervals, beforeIntervals);
  assert.equal(cleared, beforeCleared);
  assert.equal(requests.length, 1);
  assert.match(requests[0], /insights\?days=7/);
  const card = nodes.get('sd-insights');
  assert.ok(card);
  const links = flatten(card).filter(n => n.tagName === 'a');
  assert.ok(links.some(n => n.href.endsWith('?days=7') && n.attrs['aria-current'] === 'page'));
  const chart = flatten(card).map(n => n.innerHTML).join('');
  assert.equal((chart.match(/<rect class="c-bar/g) || []).length, 1);
  assert.match(chart, /这天没有记录到开播/);
  const text = flatten(card).map(n => n.textContent + n.innerHTML).join('');
  assert.match(text, /暂无数据/);
  assert.match(text, /没有名单 1 场/);
  requests.length = 0;
  location.hash = '#/streamers/sample/1';
  await page.refresh();
  assert.match(requests[0], /days=30/);
  assert.equal(nodes.get('sd-live'), live);
});
await check('慢请求晚到时不能覆盖后来选中的范围', async () => {
  location.hash = '#/streamers/sample/1?days=90';
  const slow = page.refresh();
  location.hash = '#/streamers/sample/1?days=7';
  await page.refresh();
  pending.splice(0).forEach(resolve => resolve(response({success: true, insights: {metricsKnown: false, daily: []}})));
  await slow;
  const card = nodes.get('sd-insights');
  assert.ok(flatten(card).some(n => n.href?.endsWith('?days=7') && n.attrs['aria-current'] === 'page'));
  assert.match(flatten(card).map(n => n.textContent + n.innerHTML).join(''), /流水/, '晚到的数据不得覆盖已选范围的指标');
});
await check('本期构成暂无数据时，悬停提示说明实际缺少名单的原因', async () => {
  const scenarios = [
    {name: '本期无名单、范围前有名单', audience: null, firstRecordedAt: Date.parse('2026-09-01T06:00:00+08:00'),
      title: '这段时间没有带名单的场次，分不出首次记录与回访'},
    {name: '本期有名单、范围前无名单', audience: 1, firstRecordedAt: Date.parse('2026-10-16T06:00:00+08:00'),
      title: '名单从 10 月 16 日才开始记，这之前来过的观众分不出来'},
    {name: '从未记录名单', audience: null, firstRecordedAt: null,
      title: '尚未记录到带名单的场次'},
  ];
  const titles = [];
  for (const scenario of scenarios) {
    compositionScenario = {audience: scenario.audience, firstRecordedAt: scenario.firstRecordedAt};
    await page.refresh();
    const composition = flatten(nodes.get('sd-insights')).find(n => n.textContent === '本期构成：暂无数据');
    assert.ok(composition, scenario.name + '应显示暂无数据');
    titles.push(composition.title);
    console.log(scenario.name + '：' + composition.title);
  }
  assert.deepEqual(titles, scenarios.map(scenario => scenario.title), '本期无名单时不得归因为历史记录起点');
});
page.leave();
for (const failure of failures) console.log('FAIL: ' + failure);
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
