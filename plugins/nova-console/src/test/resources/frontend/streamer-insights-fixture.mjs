import {register} from 'node:module';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {enhance, installCharts} from './streamers-dom.mjs';
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
  assert.match(empty.title, /当天没有开播记录/);
  assert.equal(zero.draw, true);
  assert.match(zero.title, /2026-10-16.*0\.00.*1 场/);
  const unknown = model.insightsPoint({date: '2026-10-17', sessions: 1, audience: null}, 'audience');
  assert.equal(unknown.draw, false);
  assert.match(unknown.title, /暂无数据/);
});

const nodes = new Map();
function element(tag) {
  const n = {tagName: tag, children: [], style: {setProperty(k, v) { this[k] = String(v); }},
    attrs: {}, listeners: {}, textContent: '', _html: ''};
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
  return enhance(n);
}
for (const id of ['sv-list', 'sv-detail', 'sv-session', 'sd-back', 'sd-head', 'sd-body']) {
  element('div').id = id;
}
globalThis.document = {hidden: false, querySelector: s => nodes.get(s.replace(/^#/, '')) || null,
  getElementById: id => nodes.get(id) || null, createElement: element,
  head: element('head'), body: element('body'), addEventListener() {}};
const observers = installCharts(document);
document.body.appendChild(nodes.get('sd-body'));
globalThis.location = {hash: '#/streamers/sample/1'};
let intervals = 0, cleared = 0;
globalThis.setInterval = () => ++intervals;
globalThis.clearInterval = () => cleared++;
const requests = [];
const pending = [];
const response = json => ({status: 200, ok: true, json: async () => json});
let compositionScenario = null;
let insightsFailure = false;
let livePendingResponse = false;
let liveSnapshot = null;
let resolveLive;
let sessionMessageKey = null;
const trendBuckets = Array.from({length: 25}, (_, i) => ({
  start: 1791736800000 - i * 604800000, end: 1792341600000 - i * 604800000,
  label: 'week ' + i, sessions: i === 2 ? 0 : 2, durationSeconds: i === 1 ? 0 : 7200,
  revenue: 100, messages: 20, audience: 1, follows: 6, starts: i === 2 ? [] : [1791736800100 - i * 604800000],
})).reverse();
const audienceResponse = {success: true, audience: {group: 'all', sort: 'attendance',
  counts: {all: 45, first: 20, returning: 25, interaction: 12}, coverage: {complete: 2},
  page: 1, pages: 3, total: 45, items: [{name: 'viewer', attendance: 2, revenue: 30.5,
    firstSeen: 1792015200000, lastSeen: 1792101600000, interaction: true,
    visits: [{startTime: 1792101600000, title: 'latest title'}, {startTime: 1792015200000, title: 'first title'}]}]}};
globalThis.fetch = async url => {
  requests.push(String(url));
  if (String(url).includes('/trend?')) return response({success: true, trend: {buckets: trendBuckets}});
  if (String(url).includes('/calendar?')) return response({success: true, calendar: {items: [{date: '2026-10-16', weekday: 5, start: 1792101600000, end: 1792188000000, status: 'recorded', effectiveSeconds: 600, starts: [1792101600100, 1792101600200]}]}});
  if (String(url).includes('/sessions?')) return response({success: true, sessions: {items: [1792101600100, 1792101600200].map(startTime => ({platform: 'sample', uid: 1, startTime, durationSeconds: 60, metrics: {}, title: String(startTime)})), total: 2, page: 1, pages: 1, metrics: []}});
  if (String(url).includes('/start-slots?')) return response({success: true, startSlots: {cells: [], topRevenue: [], topMessages: []}});
  if (String(url).includes('/fans?')) return response({success: true, fans: {points: [{at: 1792101600000, value: 10}, {at: 1792101600100, value: 12}], change: 2, partial: true}});
  if (String(url).includes('/audience?')) return response(audienceResponse);
  if (String(url).includes('/insights?')) {
    if (insightsFailure) return response({success: false, message: 'archive unavailable'});
    if (String(url).includes('days=90')) return new Promise(resolve => pending.push(resolve));
    return response({success: true, insights: {from: 1792015200000, sessions: 1, durationSeconds: 600,
      metricsKnown: true, revenue: 0, messages: 0, audience: null, interactionAudience: null,
      composition: null, coverage: {missing: 1}, daily: [
        {date: '2026-10-15', sessions: 0, revenue: 0}, {date: '2026-10-16', sessions: 1, revenue: 0}],
      ...compositionScenario}});
  }
  if (String(url).endsWith('/status')) return response({});
  if (String(url).includes('/live?')) {
    if (livePendingResponse) return new Promise(resolve => { resolveLive = resolve; });
    return response(liveSnapshot || {success: true, living: false, metrics: {}, revenue: null, messages: null});
  }
  return response({success: true, platform: 'sample', uid: 1, configured: true, living: livePendingResponse, overview: {}, sessions: {items: [1792101600100, 1792101600200].map(startTime => ({platform: 'sample', uid: 1, startTime, durationSeconds: 60, metrics: {chat_total: 37, other_total: 999}, title: String(startTime)})), messageKey: sessionMessageKey, metrics: []}});
};
const page = await import('../../../main/resources/config-ui-pages/streamers.js');
const flatten = n => n ? [n, ...n.children.flatMap(flatten)] : [];
const base = '#/streamers/sample/1';
const drawer = () => flatten(document.body).find(n => n.className === 'sc-drawer');
const load = async hash => { location.hash = hash; await page.refresh(); };
const textOf = node => flatten(node).map(n => n.textContent + n.innerHTML).join(' ');
// 最近归档的弹幕必须跟随场次接口的指标规则，不能取另一个计数。
await check('最近场次预览按场次接口messageKey读取弹幕', async () => {
  for (const [key, expected] of [['chat_total', '37'], ['other_total', '999'], [null, '—']]) {
    sessionMessageKey = key;
    await load(base);
    const rows = flatten(nodes.get('sd-recent')).filter(n => n.className === 'sd-recent-row');
    assert.equal(rows.length, 2);
    for (const row of rows) assert.ok(textOf(row).includes('弹幕 ' + expected), '最近场次弹幕须读取messageKey指定的值：' + expected);
  }
  sessionMessageKey = null;
});
// 重画指标和趋势时，卡片标题及操作提示不能消失。
await check('范围和指标切换后保留卡片标题与趋势提示', async () => {
  await load(base);
  for (const days of [7, 180]) {
    await load(base + '?days=' + days);
    const trend = nodes.get('sd-trend');
    const header = flatten(trend).find(n => n.className === 'sc-section-head');
    assert.ok(header, '趋势卡应保留分区标题');
    const select = flatten(trend).find(n => n.tagName === 'select');
    select.value = 'messages'; await select.fire('change');
    assert.ok(flatten(trend).includes(header), '换指标应保留原标题区');
    assert.match(textOf(trend), /按统计日汇总/, '指标行应显示汇总周期');
    assert.doesNotMatch(flatten(trend).find(n => n.className === 'sc-section-hint').textContent, /按统计日|点击/);
    for (const id of ['sd-insights', 'sd-period-audience', 'sd-calendar-card']) {
      assert.ok(flatten(nodes.get(id)).some(n => n.className === 'sc-section-head'), id + '应使用同样的分区标题');
    }
  }
});
// 各卡日期提示必须随全页范围一起更新，不能显示空白或上期区间。
await check('各卡显示当前范围与简写起止日期', async () => {
  compositionScenario = {from: Date.parse('2026-04-20T06:00:00+08:00'), to: Date.parse('2026-10-16T12:00:00+08:00')};
  await load(base + '?days=180');
  assert.match(textOf(nodes.get('sd-insights')), /近 180 天（含今天）· 较上期为再往前的 180 天/);
  for (const id of ['sd-trend', 'sd-period-audience', 'sd-calendar-card', 'sd-recent-card']) {
    const hint = flatten(nodes.get(id)).find(n => n.className === 'sc-section-hint');
    assert.ok(hint, id + '应显示区间提示');
    assert.match(hint.textContent, /4[ /月-]+20.*06:00.*10[ /月-]+16.*12:00/, id + '应显示本期区间');
  }
  compositionScenario = {from: Date.parse('2026-10-10T06:00:00+08:00'), to: Date.parse('2026-10-16T12:00:00+08:00')};
  await load(base + '?days=7');
  assert.match(textOf(nodes.get('sd-insights')), /近 7 天（含今天）· 较上期为再往前的 7 天/);
  assert.match(textOf(nodes.get('sd-calendar-card')), /10[ /月-]+10.*06:00/);
  compositionScenario = {from: Date.parse('2025-10-22T06:00:00+08:00'), to: Date.parse('2026-10-16T12:00:00+08:00')};
  await load(base + '?days=360');
  assert.match(textOf(nodes.get('sd-calendar-card')), /2025\/10\/22 06:00.*2026\/10\/16 12:00/, '跨年区间应显示年份');
  compositionScenario = null;
});
// 桌面图也应与示例一样高，不能只在手机缩成180px。
await check('总览趋势图在宽窄屏均为180px', () => {
  const source = readFileSync(new URL('../../../main/resources/config-ui-pages/streamers.js', import.meta.url), 'utf8');
  const heights = [...source.matchAll(/#sd-trend \.sc-chart svg\{height:(\d+)px\}/g)].map(match => Number(match[1]));
  assert.ok(heights.length && heights.every(height => height === 180), '总览趋势图高度应统一180px');
});
// 点环和两段时，应直接进入本期的对应名单，键盘也能进入。
await check('观众环与首次回访分段打开对应名单', async () => {
  compositionScenario = {audience: 45, composition: {firstRecorded: 20, returning: 25}};
  for (const [cls, group, event] of [['sc-audience-ring', 'all', 'click'], ['sc-first', 'first', 'click'], ['sc-returning', 'returning', 'keydown']]) {
    await load(base + '?days=180');
    const target = flatten(nodes.get('sd-audience-summary')).find(n => n.className.split(' ').includes(cls));
    assert.ok(target, '应显示' + cls);
    assert.ok(target.href || target.attrs.tabindex === '0', cls + '应支持键盘');
    await target.fire(event, {key: 'Enter'});
    if (target.href) location.hash = target.href;
    const state = model.parseStreamersHash(location.hash);
    assert.deepEqual([state.panel, state.group, state.days], ['audience', group, 180], cls + '应进入对应名单');
    await page.refresh(); assert.ok(drawer());
  }
  compositionScenario = null;
});
// 粉丝差值有单位，但它由快照计算，不应给出任何较上期提示或颜色。
await check('粉丝净增带人数单位且不参加较上期', async () => {
  for (const change of [2, -2, 0, null]) {
    compositionScenario = {fans: change == null ? null : {change, partial: true, fromAt: 1792015200000, toAt: 1792101600000}, comparisons: {fans: {changeRatio: .5}}};
    await load(base);
    const cell = flatten(nodes.get('sd-insights')).find(n => n.attrs['aria-label'] === '粉丝净增');
    assert.equal(cell.children[1].textContent, change == null ? '暂无数据' : (change > 0 ? '+' : change < 0 ? '−' : '') + Math.abs(change) + ' 人', '粉丝差值应带人');
    assert.ok(!textOf(cell).includes('较上期'), '粉丝格不得出现较上期提示');
    assert.ok(!cell.children[2].style.color, '粉丝格不得使用较上期涨跌色');
    assert.match(cell.title, change == null ? /快照不足两次/ : /快照未覆盖整个范围/);
  }
  compositionScenario = null;
});
// 旧收藏必须保留入口，否则从外部进入会丢失明细。
await check('收藏的旧链接恢复总览与明细', async () => {
  for (const panel of ['sessions', 'start-slots', 'audience']) {
    await load(base + '/' + panel);
    assert.ok(nodes.get('sd-trend'), '旧链接应显示总览趋势卡');
    assert.ok(drawer(), '旧链接应打开抽屉：' + panel);
    assert.ok(requests.some(url => url.includes('/' + panel + '?')));
  }
  await load(base + '/trend');
  assert.ok(drawer(), '旧趋势链接应打开明细');
});
// 范围必须作用于全部本期卡片，不能混用不同范围的数字。
await check('180天同步指标趋势观众日历', async () => {
  await load(base);
  const metric = flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select');
  metric.value = 'revenue'; await metric.fire('change');
  const live = nodes.get('sd-live'), beforeIntervals = intervals, beforeCleared = cleared;
  const range = flatten(nodes.get('sd-toolbar') || nodes.get('sd-insights')).find(n => n.href?.includes('days=180'));
  assert.ok(range, '工具条应提供180天'); requests.length = 0;
  await load(range.href);
  for (const endpoint of ['insights', 'audience', 'calendar']) assert.ok(requests.some(url => url.includes('/' + endpoint + '?days=180')), endpoint + '应带days=180');
  assert.ok(flatten(nodes.get('sd-trend')).some(n => n.className.includes('sc-line')), '趋势应使用本期daily');
  assert.equal(nodes.get('sd-live'), live); assert.equal(intervals, beforeIntervals); assert.equal(cleared, beforeCleared);
});
// 日历场次使用starts，防止跨日长场或同日多场被误选。
await check('日历按starts列出所选场次', async () => {
  await load(base); requests.length = 0;
  const day = flatten(nodes.get('sd-calendar') || nodes.get('sd-body')).find(n => n.dataset.date === '2026-10-16');
  assert.ok(day, '有直播日期应可点击');
  const before = location.hash;
  await day.fire('click');
  assert.equal(location.hash, before, '日历点一天只选中');
  const action = flatten(nodes.get('sd-calendar')).find(n => n.className.includes('sc-calendar-action'));
  await action.fire('click'); await page.refresh();
  assert.ok(requests.some(url => url.includes('/sessions?') && new URL(url, 'https://example.test').searchParams.get('starts') === '1792101600100,1792101600200'), '场次请求应带当天starts');
  const content = flatten(drawer()).map(n => n.innerHTML).join(' ');
  assert.match(content, /1792101600100/); assert.match(content, /1792101600200/);
});
// 指标必须打开对应的趋势或名单明细。
await check('指标点击分派到对应趋势与抽屉', async () => {
  for (const [label, panel] of [['观众', 'audience'], ['互动观众', 'audience'], ['粉丝净增', 'fans']]) {
    await load(base);
    const cell = flatten(nodes.get('sd-insights')).find(n => n.attrs['aria-label'] === label);
    assert.ok(cell, label + '应可点击'); await cell.fire('click'); await page.refresh();
    assert.equal(model.parseStreamersHash(location.hash).panel, panel, label + '应打开' + panel + '抽屉'); assert.ok(drawer());
    if (panel === 'fans') { assert.ok(flatten(drawer()).some(n => n.className.includes('sc-line')), '粉丝抽屉应画曲线'); assert.ok(flatten(drawer()).some(n => n.textContent.includes('净增 2')), '粉丝抽屉应显示净增'); }
  }
  await load(base);
  await flatten(nodes.get('sd-insights')).find(n => n.attrs['aria-label'] === '弹幕').fire('click');
  await page.refresh();
  assert.equal(model.parseStreamersHash(location.hash).panel, 'trend');
  assert.equal(flatten(drawer()).find(n => n.tagName === 'select').value, 'messages');
});
// 离页后轮询、抽屉和图表监听都必须释放。
await check('离页释放直播抽屉与尺寸监听', async () => {
  await load(base + '?panel=fans&days=30');
  assert.ok(drawer(), '离页前应有抽屉'); assert.ok(observers.some(o => !o.disconnected));
  const before = cleared; page.leave(); assert.ok(cleared > before);
  assert.equal(drawer(), undefined, '离页应关闭抽屉');
  assert.ok(observers.every(o => o.disconnected), '离页应断开尺寸监听');
});
// panel收藏需要恢复分组排序页码，关闭后也应清理地址。
await check('带panel地址刷新恢复抽屉', async () => {
  requests.length = 0; await load(base + '?panel=audience&days=180&group=returning&sort=revenue&page=2');
  assert.ok(drawer(), 'panel地址应打开抽屉'); assert.ok(nodes.get('sd-trend'));
  assert.ok(requests.some(url => url.endsWith('/audience?days=180&group=returning&sort=revenue&page=2')));
  await flatten(drawer()).find(n => n.className.includes('sc-drawer-close')).fire('click');
  assert.equal(model.parseStreamersHash(location.hash).panel, undefined);
  assert.equal(model.parseStreamersHash(location.hash).days, 180);
});
await check('切换与后退重读本期卡片，保留本场节点和十五秒刷新', async () => {
  await page.refresh();
  const live = nodes.get('sd-live');
  const beforeIntervals = intervals, beforeCleared = cleared;
  requests.length = 0;
  location.hash = '#/streamers/sample/1?days=7';
  await page.refresh();
  assert.equal(nodes.get('sd-live'), live);
  assert.equal(intervals, beforeIntervals);
  assert.equal(cleared, beforeCleared);
  assert.equal(requests.length, 3);
  assert.match(requests[0], /insights\?days=7/);
  const card = nodes.get('sd-insights');
  assert.ok(card);
  assert.ok(flatten(nodes.get('sd-toolbar')).some(n => n.href?.includes('days=7') && n.attrs['aria-current'] === 'page'));
  const revenueSelect = flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select');
  revenueSelect.value = 'revenue'; await revenueSelect.fire('change');
  const dots = flatten(nodes.get('sd-trend')).filter(n => n.className.includes('sc-chart-hit'));
  assert.equal(dots.length, 1, '未播不画，已播零值保留');
  const select = flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select');
  select.value = 'audience'; await select.fire('change');
  assert.equal(flatten(nodes.get('sd-trend')).filter(n => n.className.includes('sc-chart-hit')).length, 0, '缺失观众值不能画成零');
  const text = flatten(card).map(n => n.textContent + n.innerHTML).join('');
  assert.match(text, /暂无数据/);
  assert.match(flatten(nodes.get('sd-scope')).map(n => n.textContent).join(' '), /没有名单 1 场/);
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
  assert.ok(flatten(nodes.get('sd-toolbar')).some(n => n.href?.includes('days=7') && n.attrs['aria-current'] === 'page'));
  assert.match(flatten(card).map(n => n.textContent + n.innerHTML).join(''), /流水/, '晚到的数据不得覆盖已选范围的指标');
});
await check('本期构成暂无数据时，悬停提示说明实际缺少名单的原因', async () => {
  const scenarios = [
    {name: '本期无名单、范围前有名单', audience: null, firstRecordedAt: Date.parse('2026-09-01T06:00:00+08:00'),
      title: '所选时段内没有带名单的场次，无法区分首次记录与回访'},
    {name: '本期有名单、范围前无名单', audience: 1, firstRecordedAt: Date.parse('2026-10-16T06:00:00+08:00'),
      title: '名单从 10 月 16 日起才开始记录，此前来过的观众无法区分'},
    {name: '从未记录名单', audience: null, firstRecordedAt: null,
      title: '尚未记录到带名单的场次'},
  ];
  const titles = [];
  for (const scenario of scenarios) {
    compositionScenario = {audience: scenario.audience, firstRecordedAt: scenario.firstRecordedAt};
    await page.refresh();
    const composition = flatten(nodes.get('sd-audience-summary')).find(n => n.textContent === '本期构成：暂无数据');
    assert.ok(composition, scenario.name + '应显示暂无数据');
    titles.push(composition.title);
    console.log(scenario.name + '：' + composition.title);
  }
  assert.deepEqual(titles, scenarios.map(scenario => scenario.title), '本期无名单时不得归因为历史记录起点');
});
await check('五项指标沿用较上期判法，红涨绿跌持平无色', async () => {
  compositionScenario = {comparisons: {durationSeconds: {changeRatio: 0}, revenue: {changeRatio: .12},
    messages: {changeRatio: -.05}, audience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'},
    interactionAudience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'}}};
  await page.refresh();
  const all = flatten(nodes.get('sd-insights'));
  const lines = all.filter(n => n.className.includes('sc-kpi-delta') && n.textContent.startsWith('较上期'));
  assert.deepEqual(lines.map(n => [n.textContent, n.style.color]), [
    ['较上期持平', ''], ['较上期 +12%', 'var(--err)'], ['较上期 −5%', 'var(--ok)']]);
  assert.equal(all.filter(n => n.className === 'sc-kpi' && n.attrs.role === 'button').length, 6);
});
await check('对比说明默认折叠，按原因列指标，展开后显示参考说明', async () => {
  compositionScenario.comparisonNote = '6.0 之前开播的场次名单完整性未知，观众数对比仅供参考';
  await page.refresh();
  const details = nodes.get('sd-scope');
  assert.ok(details, '必须有可展开的说明');
  assert.ok(!details.open && !('open' in details.attrs), '说明默认折叠');
  assert.equal(details.children[0].tagName, 'summary');
  assert.equal(details.children[0].textContent, '统计口径');
  const text = flatten(details).map(n => n.textContent).join(' ');
  assert.match(text, /上期为 0[：:]观众、互动观众/);
  assert.match(text, /6.0 之前开播.*仅供参考/);
});
await check('只有旧名单参考说明时仍可展开，无说明时清除旧内容', async () => {
  compositionScenario = {comparisons: Object.fromEntries(
    ['durationSeconds', 'revenue', 'messages', 'audience', 'interactionAudience'].map(key => [key, {changeRatio: 0}])), comparisonNote: '6.0 之前开播的场次名单完整性未知，观众数对比仅供参考'};
  await page.refresh();
  assert.equal(flatten(nodes.get('sd-scope')).find(n => n.tagName === 'summary')?.textContent,
    '统计口径', '全部比较时摘要不显示零项');
  compositionScenario = {comparisons: {audience: {changeRatio: 0}}};
  await page.refresh();
  assert.ok(!flatten(nodes.get('sd-scope')).some(n => n.textContent.includes('仅供参考')), '无说明时不留旧说明');
});
await check('完整名单的观众与互动观众都显示较上期，涨红跌绿', async () => {
  for (const sign of [1, -1]) {
    compositionScenario = {audience: 112, interactionAudience: 95, coverage: {complete: 2},
      comparisons: {audience: {changeRatio: sign * .12}, interactionAudience: {changeRatio: sign * -.05}}};
    await page.refresh();
    const cells = flatten(nodes.get('sd-insights')).filter(n => n.className === 'sc-kpi');
    for (const [label, text, color] of [
      ['观众', sign > 0 ? '较上期 +12%' : '较上期 −12%', sign > 0 ? 'var(--err)' : 'var(--ok)'],
      ['互动观众', sign > 0 ? '较上期 −5%' : '较上期 +5%', sign > 0 ? 'var(--ok)' : 'var(--err)'],
    ]) {
      const cell = cells.find(n => n.attrs['aria-label'] === label);
      assert.ok(cell, label + '格应存在');
      assert.ok(cell.children.some(n => n.className.includes('sc-kpi-delta') && n.textContent === text && n.style.color === color),
        label + '格缺少较上期文字或涨跌颜色');
    }
  }
});
await check('观众页显示人数五列及范围分组排序分页链接', async () => {
  location.hash = '#/streamers/sample/1/audience';
  await page.refresh();
  const card = nodes.get('sd-audience');
  assert.ok(card, '观众页应创建表格容器');
  const all = [...flatten(card), ...flatten(nodes.get('sd-toolbar'))];
  const text = all.map(n => n.textContent).join(' ');
  assert.match(text, /本期全部观众 45 人/);
  assert.match(text, /回访 25 人/);
  assert.match(text, /30\.50/);
  assert.match(text, /首次 2026/);
  assert.match(text, /名单：完整 2 场/);
  const links = all.filter(n => n.tagName === 'a');
  for (const query of [
    'days=30&group=returning&sort=attendance&page=1',
    'days=30&group=all&sort=revenue&page=1',
    'days=30&group=all&sort=recent&page=1',
    'days=30&group=all&sort=attendance&page=2']) {
    assert.ok(links.some(n => { const target = model.parseStreamersHash(n.href); return query.split('&').every(part => { const [key, value] = part.split('='); return String(target[key]) === value; }); }), '应能切换 ' + query);
  }
  assert.ok(requests.some(url => url.includes('/audience?days=30&group=all&sort=attendance&page=1')));
});
// 全页只有一个范围入口，抽屉使用该范围。
await check('观众范围统一放在总览工具条', async () => {
  await page.refresh();
  assert.deepEqual(flatten(nodes.get('sd-toolbar')).filter(n => /^近 \d+ 天$/.test(n.textContent)).map(n => n.textContent),
    ['近 7 天', '近 30 天', '近 90 天', '近 180 天', '近 360 天']);
  assert.ok(!flatten(nodes.get('sd-audience')).some(n => /^近 \d+ 天$/.test(n.textContent)));
});
// 页脚人数与分组人数的数字格式必须一致。
await check('观众页脚页数和人数带千分位', async () => {
  const data = audienceResponse.audience;
  const saved = {...data};
  try {
    Object.assign(data, {page: 1, pages: 2942, total: 58840});
    await page.refresh();
    assert.equal(flatten(nodes.get('sd-audience')).find(n => n.textContent.startsWith('共 '))?.textContent,
      '共 58,840 条 · 第 1/2,942 页', '页脚数字应带千分位');
  } finally { Object.assign(data, saved); }
});
await check('点击昵称展开本期场次开播时间标题，再点收起', () => {
  const all = flatten(nodes.get('sd-audience'));
  const button = all.find(n => n.tagName === 'button' && n.textContent === 'viewer');
  assert.ok(button, '昵称应可点击');
  const expanded = all.find(n => n.tagName === 'tr' && n.hidden === true);
  assert.ok(expanded, '场次初始应收起');
  button.listeners.click();
  assert.equal(expanded.hidden, false, '点击后展开');
  assert.equal(button.attrs['aria-expanded'], 'true');
  const visits = flatten(expanded).filter(n => n.tagName === 'a');
  assert.equal(visits.length, 2);
  assert.match(visits[0].textContent, /2026.*latest title/);
  assert.ok(visits[0].href.endsWith('/1792101600000'));
  button.listeners.click();
  assert.equal(expanded.hidden, true, '再次点击收起');
  assert.equal(button.attrs['aria-expanded'], 'false');
});
await check('观众筛选链接保留范围分组排序页码并可恢复', async () => {
  await load(base + '?panel=audience&days=7&group=returning&sort=revenue&page=2');
  const state = model.parseStreamersHash(location.hash);
  assert.deepEqual([state.days, state.group, state.sort, state.page], [7, 'returning', 'revenue', 2]);
  assert.ok(requests.some(url => url.endsWith('/audience?days=7&group=returning&sort=revenue&page=2')));
  const next = flatten(nodes.get('sd-audience')).find(n => n.textContent === '下一页 ›');
  const parsed = model.parseStreamersHash(next.href);
  assert.deepEqual([parsed.days, parsed.group, parsed.sort, parsed.page], [7, 'returning', 'revenue', 2]);
});
await check('点击流水与最近表头后请求带当前排序', async () => {
  for (const [sort, label] of [['revenue', '本期流水'], ['recent', '最近到场']]) {
    location.hash = '#/streamers/sample/1/audience';
    await page.refresh();
    const link = flatten(nodes.get('sd-audience')).find(n => n.tagName === 'a' && n.textContent.startsWith(label));
    assert.ok(link, '应有排序表头');
    requests.length = 0;
    location.hash = link.href;
    await page.refresh();
    assert.ok(requests.some(url => url.includes('/audience?') && url.includes('sort=' + sort)),
      '表头排序必须进入请求：' + sort);
  }
});
// 三种每小时指标都按本桶时长计算，零时长和未知总量不产生数值。
await check('流水弹幕关注每小时均使用本桶直播时长', () => {
  assert.equal(typeof model.trendValue, 'function', '需要统一的每小时数值判定');
  for (const metric of ['revenue', 'messages', 'follows']) {
    assert.equal(model.trendValue({[metric]: 12, durationSeconds: 7200}, metric, 'hourly'), 6);
    assert.equal(model.trendValue({[metric]: 12, durationSeconds: 0}, metric, 'hourly'), null);
    assert.equal(model.trendValue({[metric]: null, durationSeconds: 7200}, metric, 'hourly'), null);
  }
});
// 收藏、周期切换与翻页必须恢复同一张趋势明细。
await check('趋势收藏与旧链接都打开抽屉并恢复控件', async () => {
  for (const hash of [base + '?panel=trend&period=month&metric=follows&mode=hourly&page=2', base + '/trend']) {
    await load(hash);
    assert.ok(drawer(), '趋势链接应打开抽屉');
    if (hash.includes('panel=')) {
      const select = flatten(drawer()).find(n => n.attrs['aria-label'] === '趋势指标');
      assert.equal(select?.value, 'follows', '收藏应恢复新增关注');
      assert.match(textOf(drawer()), /第 2\/3 页/);
      assert.ok(requests.some(url => url.includes('/trend?period=month&days=30')));
    }
  }
});
// 每小时必须除以本桶时长；零时长不能画零或无穷值。
await check('每小时按桶时长计算并禁用不支持的指标', async () => {
  await load(base + '?panel=trend&period=week&metric=revenue&mode=hourly');
  assert.ok(drawer(), '应有趋势抽屉');
  const bars = flatten(drawer()).filter(n => n.className.trim() === 'sc-bar');
  assert.ok(bars.some(n => /50\.00.*元\/小时/.test(n.attrs['aria-label'])), '100元除以2小时应为50元/小时');
  assert.ok(!bars.some(n => n.attrs['aria-label'].startsWith('week 1 ·')), '零时长不应画有效值');
  assert.match(textOf(drawer()), /—/);
  await load(base + '?panel=trend&period=week&metric=audience&mode=hourly');
  assert.ok(!flatten(drawer()).some(n => n.textContent === '每小时'), '观众不显示数值切换');
  assert.ok(flatten(drawer()).filter(n => n.className.trim() === 'sc-bar').some(n => / · 1 人$/.test(n.attrs['aria-label'])), '不支持每小时的指标仍显示总量');
});
// 周图与行链接必须使用该桶的完整半开区间。
await check('周图和周期行打开对应起止的场次', async () => {
  for (const kind of ['chart', 'row']) {
    await load(base + '?panel=trend&period=week&metric=revenue');
    assert.ok(drawer(), '应有趋势抽屉');
    const mark = flatten(drawer()).find(n => kind === 'chart' ? n.className.trim() === 'sc-bar' : n.tagName === 'tr' && n.attrs.tabindex === '0');
    assert.ok(mark, '应能点' + kind); await mark.fire('click');
    const state = model.parseStreamersHash(location.hash);
    assert.equal(state.panel, 'sessions');
    const expected = kind === 'chart' ? trendBuckets[0] : trendBuckets.at(-1);
    assert.deepEqual([Number(state.from), Number(state.to)], [expected.start, expected.end]);
    assert.equal(state.starts, expected.starts.join(','), '下钻须限定桶内场次');
    requests.length = 0; await page.refresh();
    assert.ok(requests.some(url => url.includes('/sessions?') && url.includes('from=' + expected.start) && url.includes('to=' + expected.end)));
  }
});
// 周期与页码链接在地址中保存，换周期重置页码，空周期不可下钻。
await check('趋势周期分页保留地址且空周期不可下钻', async () => {
  await load(base + '?panel=trend&period=week&metric=messages&mode=hourly&days=180');
  const next = flatten(drawer()).find(n => n.textContent === '下一页 ›');
  assert.ok(next, '周期明细应可分页'); await load(next.href);
  assert.equal(model.parseStreamersHash(location.hash).page, 2);
  const month = flatten(drawer()).find(n => n.textContent === '按统计月');
  await load(month.href);
  const state = model.parseStreamersHash(location.hash);
  assert.deepEqual([state.period, state.page, state.metric, state.mode, state.days], ['month', 1, 'messages', 'hourly', 180]);
  assert.ok(requests.some(url => url.endsWith('/trend?period=month&days=180')));
  const before = location.hash;
  const empty = flatten(drawer()).find(n => n.tagName === 'tr' && n.children[0]?.textContent === 'week 2');
  assert.ok(empty); await empty.fire('click'); await empty.fire('keydown', {key: 'Enter'});
  assert.equal(location.hash, before, '空周期不能打开场次');
  assert.notEqual(empty.attrs.role, 'link');
});
// 空周期的行不能伪装成可用链接。
await check('空周期明细行不打开场次抽屉', async () => {
  await load(base + '?panel=trend&period=week');
  const before = location.hash;
  const row = flatten(drawer()).find(n => n.tagName === 'tr' && n.children[0]?.textContent === 'week 2');
  assert.ok(row); await row.fire('click'); await row.fire('keydown', {key: 'Enter'});
  assert.equal(location.hash, before, '空周期行不能打开场次');
  assert.notEqual(row.attrs.role, 'link');
});
// 图和表须保留各桶原值，翻页每次只展示十个周期。
await check('周期明细逐桶显示场次时长和指标且每页十条', async () => {
  await load(base + '?panel=trend&period=week');
  const rows = flatten(drawer()).filter(n => n.tagName === 'tr' && n.children[0]?.tagName === 'td');
  assert.equal(rows.length, 10, '每页应为十条');
  for (const [i, row] of rows.entries()) {
    const bucket = trendBuckets.at(-1 - i);
    assert.equal(row.children[0].textContent, bucket.label);
    assert.equal(row.children[1].textContent, model.fmtMetric(bucket.sessions), '明细场次须来自本桶');
    assert.equal(row.children[2].textContent, model.fmtDuration(bucket.durationSeconds), '明细时长须来自本桶');
    assert.equal(row.children[3].textContent, '100.00 元', '明细指标须来自本桶');
  }
  assert.match(textOf(drawer()), /共 25 条 · 第 1\/3 页/);
});
// 收藏链接须恢复隐藏选项，切换后也要写回地址。
await check('趋势收藏恢复隐藏设置并可取消', async () => {
  await load(base + '?panel=trend&period=day&hideOff=1');
  let input = flatten(drawer()).find(n => n.type === 'checkbox');
  assert.ok(input?.checked, '收藏链接须恢复隐藏复选框');
  input.checked = false; await input.fire('change');
  assert.equal(new URLSearchParams(location.hash.split('?')[1]).has('hideOff'), false);
});
// 非趋势抽屉不能带入趋势选择，默认趋势选项也不应污染地址。
await check('名单和关闭后的地址不带趋势参数且默认值省略', async () => {
  await load(base + '?panel=trend&period=day&metric=follows&mode=hourly&hideOff=1');
  const audience = flatten(nodes.get('sd-period-audience')).find(n => n.tagName === 'a');
  for (const key of ['period', 'metric', 'mode', 'hideOff'])
    assert.equal(new URLSearchParams(audience.href.split('?')[1]).has(key), false, '名单链接不得携带' + key);
  await flatten(drawer()).find(n => n.className.includes('sc-drawer-close')).fire('click');
  for (const key of ['period', 'metric', 'mode', 'hideOff'])
    assert.equal(new URLSearchParams(location.hash.split('?')[1]).has(key), false);
  await load(base);
  const select = flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select');
  select.value = 'revenue'; await select.fire('change');
  await flatten(nodes.get('sd-trend')).find(n => n.textContent === '总量').fire('click');
  const hide = flatten(nodes.get('sd-trend')).find(n => n.type === 'checkbox');
  hide.checked = false; await hide.fire('change');
  const detail = flatten(nodes.get('sd-trend')).find(n => n.textContent === '查看明细');
  for (const key of ['period', 'metric', 'mode', 'hideOff'])
    assert.equal(new URLSearchParams(detail.href.split('?')[1]).has(key), false, '默认趋势参数应省略' + key);
  requests.length = 0; await load(base + '/trend');
  assert.ok(requests.some(url => url.includes('/trend?period=week&')), '旧趋势链接应按周打开');
});
// 卡片与抽屉按日图只选中读数，须经按钮进入对应一天。
await check('按日图先选读数再查看当天场次', async () => {
  for (const hash of [base, base + '?panel=trend&period=day']) {
    await load(hash);
    const host = hash === base ? nodes.get('sd-trend') : drawer();
    if (hash === base) {
      const select = flatten(host).find(n => n.tagName === 'select');
      select.value = 'revenue'; await select.fire('change');
    }
    const dot = flatten(host).find(n => n.className.trim() === 'sc-chart-hit');
    const action = flatten(host).find(n => n.textContent === '查看当天场次 →');
    assert.ok(action?.disabled, '未选日期时按钮不可用');
    const before = location.hash;
    await dot.fire('click'); assert.equal(location.hash, before, '点图只选择，不跳走');
    assert.ok(!action.disabled); await action.fire('click');
    const state = model.parseStreamersHash(location.hash);
    assert.equal(state.panel, 'sessions');
    const expected = hash === base ? {start: Date.parse('2026-10-16T06:00:00+08:00'), end: Date.parse('2026-10-17T06:00:00+08:00')} : trendBuckets[0];
    assert.deepEqual([Number(state.from), Number(state.to)], [expected.start, expected.end]);
  }
});
// 卡片需提供六指标、每小时与隐藏空日，并能打开明细。
await check('趋势卡六指标切换总量每小时隐藏空日及明细', async () => {
  compositionScenario = {follows: 6, daily: [
    {date: '2026-10-15', sessions: 0, follows: 0, durationSeconds: 0},
    {date: '2026-10-16', sessions: 1, follows: 6, durationSeconds: 7200}]};
  await load(base + '?days=7');
  let select = flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select');
  assert.deepEqual(select.children.map(n => n.textContent), ['流水', '弹幕', '观众', '新增关注', '直播时长', '场次']);
  select.value = 'follows'; await select.fire('change');
  await flatten(nodes.get('sd-trend')).find(n => n.textContent === '每小时').fire('click');
  assert.ok(flatten(nodes.get('sd-trend')).some(n => /3\.00 人\/小时/.test(n.attrs['aria-label'] || '')), '关注也应按本桶时长算每小时');
  const checkbox = flatten(nodes.get('sd-trend')).find(n => n.type === 'checkbox');
  assert.ok(checkbox && !checkbox.checked, '隐藏默认不勾');
  checkbox.checked = true; await checkbox.fire('change');
  assert.match(textOf(nodes.get('sd-trend')), /已隐藏 1 个未播日/);
  const detail = flatten(nodes.get('sd-trend')).find(n => n.textContent === '查看明细');
  assert.equal(model.parseStreamersHash(detail.href).panel, 'trend');
  compositionScenario = {follows: null}; await load(base + '?days=30');
  const follows = flatten(nodes.get('sd-more')).find(n => n.className === 'sc-kpi' && textOf(n).includes('新增关注'));
  assert.ok(follows && textOf(follows).includes('—'), '平台没有关注规则应显示破折号');
  compositionScenario = null;
});
// 读取归档失败后不能继续让各卡显示读取中。
await check('概况失败后卡片日期提示说明失败', async () => {
  insightsFailure = true;
  try {
    await load(base + '?days=180');
    for (const id of ['sd-insights', 'sd-trend', 'sd-period-audience', 'sd-calendar-card', 'sd-recent-card']) {
      const hint = flatten(nodes.get(id)).find(n => n.className === 'sc-section-hint');
      assert.match(hint.textContent, /读取失败/, id + '应说明读取失败');
    }
    assert.doesNotMatch(textOf(nodes.get('sd-toolbar')), /正在读取/);
  } finally { insightsFailure = false; }
});
// 首次直播响应未到不能显示三个空指标与空时间。
await check('首次直播响应前显示快照读取提示', async () => {
  page.leave(); livePendingResponse = true;
  const loading = load('#/streamers/sample/2');
  try {
    await new Promise((resolve, reject) => {
      const deadline = Date.now() + 10000;
      const poll = () => resolveLive ? resolve() : Date.now() > deadline ? reject(new Error('live request missing')) : setImmediate(poll);
      poll();
    });
    assert.match(textOf(nodes.get('sd-live')), /正在读取当前直播快照/);
    assert.doesNotMatch(textOf(nodes.get('sd-live')), /暂无数据|数据截至/);
  } finally {
    livePendingResponse = false;
    resolveLive?.(response({success: true, living: false, metrics: {}, revenue: null, messages: null}));
    await loading;
  }
});
// 平台可把别的原始项声明为弹幕，摘要须使用服务端算出的结果。
await check('直播弹幕采用服务端规则结果', async () => {
  try {
    liveSnapshot = {success: true, living: true, since: 1792101600000, revenue: null,
      messages: 7, metrics: {chat_total: 7, danmu_count: 999}};
    await load(base);
    const values = flatten(nodes.get('sd-live')).find(n => n.className === 'sd-live-metrics').children.map(n => n.children[1].textContent);
    assert.equal(values[1], '7 条', '弹幕须采用服务端字段');
  } finally { liveSnapshot = null; }
});
// 收入规则只能在服务端由插件声明，页面不能保留第二份金额键表。
await check('直播卡采用服务端流水且没有平台收入键', () => {
  const source = readFileSync(new URL('../../../main/resources/config-ui-pages/streamers.js', import.meta.url), 'utf8');
  for (const key of ['gift_value', 'super_chat_value', 'guard_value', 'danmu_count']) assert.ok(!source.includes(key), '页面不应识别' + key);
  assert.match(source, /live\?\.revenue/);
});
page.leave();
for (const failure of failures) console.log('FAIL: ' + failure);
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
