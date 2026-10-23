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
let sessionScenario = null;
let recentScenario = null;
let slotsScenario = null;
let revenueKeys = ['gift_value', 'super_chat_value', 'guard_value'];
const trendBuckets = Array.from({length: 25}, (_, i) => ({
  start: Date.parse('2026-10-12T06:00:00+08:00') - i * 604800000,
  end: Date.parse('2026-10-19T06:00:00+08:00') - i * 604800000,
  label: 'week ' + i, sessions: i === 2 ? 0 : 2, durationSeconds: i === 1 ? 0 : 7200,
  revenue: 100, messages: 20, audience: 1, follows: 6,
  starts: i === 2 ? [] : [Date.parse('2026-10-12T06:00:00+08:00') + 100 - i * 604800000],
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
  if (String(url).includes('/sessions?') && sessionScenario) {
    const params = new URL(url, 'https://example.test').searchParams;
    const from = Number(params.get('dateFrom') || params.get('from') || 0), to = Number(params.get('dateTo') || params.get('to') || Number.MAX_SAFE_INTEGER);
    const starts = params.has('starts') ? params.get('starts').split(',').map(Number) : null;
    const items = sessionScenario.filter(item => item.startTime >= from && item.startTime < to && (!starts || starts.includes(item.startTime)));
    return response({success: true, sessions: {items, total: items.length, page: 1, pages: 1, metrics: [], revenueKeys}});
  }
  if (String(url).includes('/sessions?')) return response({success: true, sessions: {items: [1792101600100, 1792101600200].map(startTime => ({platform: 'sample', uid: 1, startTime, durationSeconds: 60, metrics: {}, title: String(startTime)})), total: 2, page: 1, pages: 1, metrics: []}});
  if (String(url).includes('/start-slots?')) return response({success: true, startSlots: slotsScenario || {cells: [], topRevenue: [], topMessages: []}});
  if (String(url).includes('/fans?')) return response({success: true, fans: {points: [{at: 1792101600000, value: 10}, {at: 1792101600100, value: 12}], change: 2, partial: true}});
  if (String(url).includes('/audience?')) return response(audienceResponse);
  if (String(url).includes('/insights?')) {
    if (insightsFailure) return response({success: false, message: 'archive unavailable'});
    if (String(url).includes('days=90')) return new Promise(resolve => pending.push(resolve));
    return response({success: true, insights: {from: 1792015200000, sessions: 1, durationSeconds: 600,
      metricsKnown: true, revenue: 0, messages: 0, audience: null, interactionAudience: null,
      composition: null, coverage: {missing: 1}, monitoring: {recording: true, watchedSeconds: 540000, elapsedSeconds: 540000, observableDays: 6.3, rangeDays: 7}, daily: [
        {date: '2026-10-15', sessions: 0, revenue: 0}, {date: '2026-10-16', sessions: 1, revenue: 0}],
      ...compositionScenario}});
  }
  if (String(url).endsWith('/status')) return response({});
  if (String(url).includes('/live?')) {
    if (livePendingResponse) return new Promise(resolve => { resolveLive = resolve; });
    return response(liveSnapshot || {success: true, living: false, metrics: {}, revenue: null, messages: null});
  }
  return response({success: true, platform: 'sample', uid: 1, configured: true, living: livePendingResponse, overview: {}, sessions: {items: recentScenario || [1792101600100, 1792101600200].map(startTime => ({platform: 'sample', uid: 1, startTime, durationSeconds: 60, metrics: {chat_total: 37, other_total: 999}, title: String(startTime)})), messageKey: sessionMessageKey, metrics: [], revenueKeys}});
};
const page = await import('../../../main/resources/config-ui-pages/streamers.js');
const flatten = n => n ? [n, ...n.children.flatMap(flatten)] : [];
const base = '#/streamers/sample/1';
const drawer = () => flatten(document.body).find(n => n.className === 'sc-drawer');
const load = async hash => { location.hash = hash; await page.refresh(); };
const textOf = node => flatten(node).map(n => n.textContent + n.innerHTML).join(' ');
const coverageHints = () => flatten(drawer()).filter(n => n.tagName === 'td' && n.className === 'sd-trend-coverage').map(n => n.children[0].title).join(' ');
const scopeLines = () => nodes.get('sd-scope').querySelector('.sd-scope-content').children.map(n => n.textContent);
const sessionRows = () => flatten(drawer()).filter(n => n.className === 'sd-session-row');
const sessionMetric = (row, label) => {
  assert.ok(row, '须列出目标场次卡');
  const name = flatten(row).find(n => n.tagName === 'dt' && n.textContent === label);
  assert.ok(name, '须显示' + label);
  return name.parentNode.children.find(n => n.tagName === 'dd').textContent;
};
// 长范围跨年时，范围行省去年份会让起止日期看起来倒置。
await check('360天全页范围明确显示跨年起止', async () => {
  compositionScenario = {from: Date.parse('2025-10-22T06:00:00+08:00'), to: Date.parse('2026-10-16T06:10:00+08:00')};
  await load(base + '?days=360');
  assert.match(textOf(nodes.get('sd-toolbar')), /本期统计：2025\/10\/22 06:00 — 2026\/10\/16 06:10（不含结束时刻）/, '全页范围须显示跨年起止');
  compositionScenario = null;
});
// 日期框必须改变实际请求和展示结果，清除后回到全部归档。
await check('场次日期筛选按06点统计日生效并可清除', async () => {
  const start = Date.parse('2026-10-15T06:00:00+08:00');
  sessionScenario = [-1, 0, 86400000 - 1, 86400000].map((offset, index) => ({platform: 'sample', uid: 1,
    startTime: start + offset, endTime: start + offset + 60000, durationSeconds: 60, title: 'session ' + index, metrics: {}}));
  const original = base + '?panel=sessions&source=dashboard';
  await load(original);
  const inputs = flatten(drawer()).filter(n => n.tagName === 'input' && n.type === 'date');
  assert.equal(inputs.length, 2, '场次抽屉须有起止日期框');
  inputs[0].value = inputs[1].value = '2026-10-15';
  await flatten(drawer()).find(n => n.tagName === 'button' && n.textContent === '筛选').fire('click');
  await page.refresh();
  let params = new URLSearchParams(location.hash.split('?')[1]);
  assert.equal(Number(params.get('dateFrom')), start, '筛选起点为06点');
  assert.equal(Number(params.get('dateTo')), start + 86400000, '截止日包含完整统计日');
  const links = flatten(drawer()).filter(n => n.href?.startsWith(base + '/') && /\/\d+$/.test(n.href));
  assert.deepEqual(links.map(n => Number(n.href.split('/').at(-1))), [start, start + 86400000 - 1], '只列出所选统计日开播场次');
  const clear = flatten(drawer()).find(n => n.textContent === '清除筛选');
  assert.ok(clear, '须提供清除筛选');
  if (clear.href) location.hash = clear.href; else await clear.fire('click');
  await page.refresh();
  assert.equal(flatten(drawer()).filter(n => n.href?.startsWith(base + '/') && /\/\d+$/.test(n.href)).length, 4, '清除后恢复原集合');
  const selected = base + '?panel=sessions&source=calendar&sourceDay=2026-10-15&from=' + (start - 1) + '&to=' + (start + 1) + '&starts=' + [start - 1, start].join(',');
  await load(selected);
  const selectedInputs = flatten(drawer()).filter(n => n.tagName === 'input' && n.type === 'date');
  selectedInputs[0].value = selectedInputs[1].value = '2026-10-15';
  await flatten(drawer()).find(n => n.tagName === 'button' && n.textContent === '筛选').fire('click'); await page.refresh();
  assert.equal(flatten(drawer()).filter(n => n.className === 'sd-session-row').length, 1, '日期与来源集合取交集');
  await load(location.hash + '&page=2');
  location.hash = flatten(drawer()).find(n => n.textContent === '清除筛选').href; await page.refresh();
  assert.equal(sessionRows().length, 4, '清除后恢复全部归档');
  const clearedParams = new URLSearchParams(location.hash.split('?')[1]);
  for (const key of ['dateFrom', 'dateTo', 'from', 'to', 'starts', 'source', 'sourceDay', 'page']) {
    assert.equal(clearedParams.has(key), false, '清除后地址不保留' + key);
  }
  sessionScenario = null;
});
// 缺失名单属于未知人数，不能把它显示成无人到场。
await check('场次缺名单显示未知到场人数', async () => {
  sessionScenario = [
    {platform: 'sample', uid: 1, startTime: 1792101600100, durationSeconds: 60, title: 'unknown', audience: null, metrics: {}},
    {platform: 'sample', uid: 1, startTime: 1792101600200, durationSeconds: 60, title: 'known', audience: 0, metrics: {}},
  ];
  await load(base + '?panel=sessions');
  assert.equal(sessionMetric(sessionRows().find(n => n.href.endsWith('/1792101600100')), '到场人数'), '—', '缺名单那场到场人数须为—');
  assert.equal(sessionMetric(sessionRows().find(n => n.href.endsWith('/1792101600200')), '到场人数'), '0 人', '已知空名单仍显示0人');
  sessionScenario = null;
});
// 收入目录非空时，快照缺少收入键按零；没有快照仍未知。
await check('场次流水区分有快照零收入与空对象、null及缺失指标', async () => {
  sessionScenario = [
    {platform: 'sample', uid: 1, startTime: 1792101600100, metrics: {gift_value: 12.5}},
    {platform: 'sample', uid: 1, startTime: 1792101600200, metrics: {danmu_count: 2}},
    {platform: 'sample', uid: 1, startTime: 1792101600300, metrics: {gift_value: 0}},
    {platform: 'sample', uid: 1, startTime: 1792101600400, metrics: {}},
    {platform: 'sample', uid: 1, startTime: 1792101600500, metrics: null},
    {platform: 'sample', uid: 1, startTime: 1792101600600},
  ];
  try {
    await load(base + '?panel=sessions');
    assert.equal(sessionMetric(sessionRows()[0], '流水'), '12.50 元', '仅有礼物收入也须显示流水');
    assert.equal(sessionMetric(sessionRows()[1], '流水'), '0.00 元', '有快照没收到礼物须显示零');
    assert.equal(sessionMetric(sessionRows()[2], '流水'), '0.00 元', '已知零收入须显示0.00元');
    assert.equal(sessionMetric(sessionRows()[3], '流水'), '—', '空指标对象须显示未知');
    assert.equal(sessionMetric(sessionRows()[4], '流水'), '—', 'null快照须显示—');
    assert.equal(sessionMetric(sessionRows()[5], '流水'), '—', '缺少快照须显示—');
  } finally { sessionScenario = null; }
});
// 同笔收入的消耗与盈亏不能再次加进最近场次流水。
await check('最近场次流水只合计收入键', async () => {
  recentScenario = [{platform: 'sample', uid: 1, startTime: 1792101600100,
    metrics: {gift_value: 12.5, super_chat_value: 20, guard_value: 30, gift_paid: 100, box_profit: -5}}];
  try {
    page.leave();
    await load(base);
    const row = flatten(nodes.get('sd-recent')).find(n => n.className === 'sd-recent-row');
    assert.match(textOf(row), /流水 62\.50 元/, '流水不得重复计入观众消耗与盲盒盈亏：' + textOf(row));
  } finally { recentScenario = null; }
});
// 抓住最近归档与场次卡采用不同零收入规则的情况。
await check('最近归档有收入目录且快照缺键显示零，没有快照显示未知', async () => {
  recentScenario = [
    {platform: 'sample', uid: 1, startTime: 1792101600100, metrics: {danmu_count: 2}},
    {platform: 'sample', uid: 1, startTime: 1792101600200, metrics: {}},
    {platform: 'sample', uid: 1, startTime: 1792101600300, metrics: {}},
  ];
  try {
    page.leave();
    await load(base);
    const rows = flatten(nodes.get('sd-recent')).filter(n => n.className === 'sd-recent-row');
    assert.match(textOf(rows[0]), /流水 0\.00 元/, '没收到礼物须显示零');
    assert.match(textOf(rows[1]), /流水 —/, '空指标对象须显示未知');
    assert.match(textOf(rows[2]), /流水 —/, '没有快照须显示—');
  } finally { recentScenario = null; }
});
// 接口把无快照下发为空对象；抓住场次卡与最近归档将未知流水写成零的情况。
await check('接口空指标与弹幕快照在场次卡和最近归档区分未知与零', async () => {
  const items = [
    {platform: 'sample', uid: 1, startTime: 1792101600100, metrics: {}},
    {platform: 'sample', uid: 1, startTime: 1792101600200, metrics: {danmu_count: 5}},
  ];
  sessionScenario = items;
  recentScenario = items;
  try {
    await load(base + '?panel=sessions');
    assert.equal(sessionMetric(sessionRows()[0], '流水'), '—', '场次卡空指标对象须显示未知');
    assert.equal(sessionMetric(sessionRows()[1], '流水'), '0.00 元', '场次卡弹幕快照须显示零收入');
    page.leave();
    await load(base);
    const rows = flatten(nodes.get('sd-recent')).filter(n => n.className === 'sd-recent-row');
    assert.match(textOf(rows[0]), /流水 —/, '最近归档空指标对象须显示未知');
    assert.match(textOf(rows[1]), /流水 0\.00 元/, '最近归档弹幕快照须显示零收入');
  } finally { sessionScenario = null; recentScenario = null; }
});
// 提示必须落在中断那场卡上，正常场次不能混入中断原因。
await check('场次卡仅为中断场次标明原因', async () => {
  sessionScenario = [
    {platform: 'sample', uid: 1, startTime: 1792101600100, interrupted: true, endReasonText: '程序停止', metrics: {}},
    {platform: 'sample', uid: 1, startTime: 1792101600200, interrupted: false, endReasonText: '正常下播', metrics: {}},
  ];
  try {
    await load(base + '?panel=sessions');
    assert.ok(textOf(sessionRows()[0]).includes('程序停止'), '中断那场须显示结束原因');
    assert.ok(!textOf(sessionRows()[1]).includes('正常下播'), '正常那场不显示结束原因');
    assert.ok(!textOf(sessionRows()[1]).includes('程序停止'), '正常那场不混入中断原因');
  } finally { sessionScenario = null; }
});
// 未筛选时不出清除入口和来源，旧书签按所选场次说明来源。
await check('未筛选场次不显示清除入口与来源', async () => {
  await load(base + '?panel=sessions');
  assert.ok(!textOf(drawer()).includes('清除筛选'), '没有筛选不显示清除入口');
  assert.ok(!textOf(drawer()).includes('筛选来源：'), '没有来源不显示来源行');
});
// 只有场次集合的旧地址也不能冒充全部归档。
await check('旧书签场次集合显示所选场次来源', async () => {
  sessionScenario = [1792101600100, 1792101600200].map(startTime => ({platform: 'sample', uid: 1, startTime, metrics: {}}));
  try {
    await load(base + '?panel=sessions&starts=1792101600100');
    assert.match(textOf(drawer()), /筛选来源：所选场次 · 1 个场次/, '旧书签须说明所选场次与场数');
    assert.ok(textOf(drawer()).includes('清除筛选'), 'starts筛选也须能清除');
  } finally { sessionScenario = null; }
});
// 日历下钻须留下可刷新恢复的日期、来源和场数。
await check('日历下钻展示所选日期来源与场数', async () => {
  await load(base);
  const day = flatten(nodes.get('sd-calendar')).find(n => n.dataset.date === '2026-10-16');
  await day.fire('click');
  await flatten(nodes.get('sd-calendar')).find(n => n.className.includes('sc-calendar-action')).fire('click');
  await page.refresh();
  assert.equal(new URLSearchParams(location.hash.split('?')[1]).get('source'), 'calendar', '地址须保留日历来源');
  assert.match(textOf(drawer()), /筛选来源：开播日历.*2026[/-]10[/-]16.*2 个场次/, '来源须说明日期与场数');
});
// 直播时长已有固定列，选择同一指标后不应再出现一列同名列。
await check('直播时长趋势明细没有重复表头', async () => {
  await load(base + '?panel=trend&metric=durationSeconds');
  const headers = flatten(drawer()).filter(n => n.tagName === 'th').map(n => n.textContent);
  assert.equal(headers.filter(name => name === '直播时长').length, 1, '直播时长表头只能出现一次');
});
// 移动说明后，所有未比较项和原因应仍在卡头展开区，口径区不重复。
await check('卡头对比说明保留全部未比较项与原因', async () => {
  compositionScenario = {comparisons: {durationSeconds: {reasonCode: 'SMALL', reason: '本期或上期少于 2 场'},
    revenue: {reasonCode: 'ZERO', reason: '上期为 0'}, audience: {reasonCode: 'SMALL', reason: '本期或上期少于 2 场'}}, comparisonNote: '名单完整性未知'};
  const expected = model.comparisonExplanation(compositionScenario);
  await load(base); await page.refresh();
  const head = flatten(nodes.get('sd-insights')).find(n => n.className === 'sc-section-head');
  const disclosure = flatten(head).find(n => n.tagName === 'details');
  assert.ok(disclosure, '本期表现卡头须有对比说明折叠');
  assert.match(textOf(disclosure), /对比说明 · 3 项未比较/);
  for (const group of expected.groups) {
    assert.ok(textOf(disclosure).includes(group.reason));
    for (const metric of group.metrics) assert.ok(textOf(disclosure).includes(metric));
    assert.ok(!textOf(nodes.get('sd-scope')).includes(group.reason), '统计口径不重复原因');
  }
  assert.ok(textOf(disclosure).includes(expected.note));
  compositionScenario = null;
});
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
// 起点快照不足时不能把范围内首张快照说成边界快照。
await check('粉丝净增快照不足时说明实际起算时刻', async () => {
  try {
    for (const partial of [true, false]) {
      const fromAt = 1792015200000;
      compositionScenario = {fans: {change: 5, partial, fromAt, toAt: 1792101600000}};
      await load(base);
      const cell = flatten(nodes.get('sd-insights')).find(n => n.attrs['aria-label'] === '粉丝净增');
      const note = cell.children[2];
      assert.equal(note.className, 'sc-kpi-delta', '粉丝说明须放在值下的比较行');
      assert.equal(cell.children.length, 3, '粉丝格不应另挂底部说明');
      if (partial) {
        assert.ok(!note.textContent.includes('按区间边界有效快照计算'), '缺起点快照时不得声称按边界快照计算');
        assert.ok(note.textContent.includes('可用快照未覆盖整个范围'), '须说明快照覆盖不足');
        assert.ok(note.textContent.includes('从 ' + model.insightsTime(fromAt) + ' 起算'), '须显示实际起算时刻');
      } else assert.equal(note.textContent, '按区间边界有效快照计算');
    }
  } finally { compositionScenario = null; }
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
  const links = flatten(drawer()).filter(n => n.tagName === 'a').map(n => n.href);
  for (const start of [1792101600100, 1792101600200]) assert.ok(links.includes(model.sessionHash('sample', 1, start)), '须列出所选场次链接');
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
await check('五项指标沿用较上期判法，涨跌用独立颜色且持平无色', async () => {
  compositionScenario = {comparisons: {durationSeconds: {changeRatio: 0}, revenue: {changeRatio: .12},
    messages: {changeRatio: -.05}, audience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'},
    interactionAudience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'}}};
  await page.refresh();
  const all = flatten(nodes.get('sd-insights'));
  const lines = all.filter(n => n.className.includes('sc-kpi-delta') && n.textContent.startsWith('较上期'));
  assert.deepEqual(lines.map(n => [n.textContent, n.style.color]), [
    ['较上期持平', ''], ['较上期 +12%', 'var(--trend-up)'], ['较上期 −5%', 'var(--trend-down)']]);
  assert.equal(all.filter(n => n.className === 'sc-kpi' && n.attrs.role === 'button').length, 6);
});
await check('对比说明默认折叠，按原因列指标，展开后显示参考说明', async () => {
  compositionScenario.comparisonNote = '6.0 之前开播的场次名单完整性未知，观众数对比仅供参考';
  await page.refresh();
  const details = flatten(nodes.get('sd-insights')).find(n => n.className === 'sd-comparison');
  assert.ok(details, '必须有可展开的说明');
  assert.ok(!details.open && !('open' in details.attrs), '说明默认折叠');
  assert.equal(details.children[0].tagName, 'summary');
  assert.equal(details.children[0].textContent, '对比说明 · 2 项未比较');
  const text = flatten(details).map(n => n.textContent).join(' ');
  assert.match(text, /上期为 0[：:]观众、互动观众/);
  assert.match(text, /6.0 之前开播.*仅供参考/);
});
await check('只有旧名单参考说明时仍可展开，无说明时清除旧内容', async () => {
  compositionScenario = {comparisons: Object.fromEntries(
    ['durationSeconds', 'revenue', 'messages', 'audience', 'interactionAudience'].map(key => [key, {changeRatio: 0}])), comparisonNote: '6.0 之前开播的场次名单完整性未知，观众数对比仅供参考'};
  await page.refresh();
  assert.ok(!flatten(nodes.get('sd-insights')).some(n => n.className === 'sd-comparison'), '全部比较时不显示未比较折叠');
  assert.ok(textOf(nodes.get('sd-scope')).includes('仅供参考'), '旧名单参考说明仍可查看');
  compositionScenario = {comparisons: {audience: {changeRatio: 0}}};
  await page.refresh();
  assert.ok(!flatten(nodes.get('sd-scope')).some(n => n.textContent.includes('仅供参考')), '无说明时不留旧说明');
});
await check('完整名单的观众与互动观众都显示较上期及独立涨跌色', async () => {
  for (const sign of [1, -1]) {
    compositionScenario = {audience: 112, interactionAudience: 95, coverage: {complete: 2},
      comparisons: {audience: {changeRatio: sign * .12}, interactionAudience: {changeRatio: sign * -.05}}};
    await page.refresh();
    const cells = flatten(nodes.get('sd-insights')).filter(n => n.className === 'sc-kpi');
    for (const [label, text, color] of [
      ['观众', sign > 0 ? '较上期 +12%' : '较上期 −12%', sign > 0 ? 'var(--trend-up)' : 'var(--trend-down)'],
      ['互动观众', sign > 0 ? '较上期 −5%' : '较上期 +5%', sign > 0 ? 'var(--trend-down)' : 'var(--trend-up)'],
    ]) {
      const cell = cells.find(n => n.attrs['aria-label'] === label);
      assert.ok(cell, label + '格应存在');
      assert.ok(cell.children.some(n => n.className.includes('sc-kpi-delta') && n.textContent === text && n.style.color === color),
        label + '格缺少较上期文字或涨跌颜色');
    }
  }
});
await check('观众页显示人数六列及范围分组排序分页链接', async () => {
  location.hash = '#/streamers/sample/1/audience';
  await page.refresh();
  const card = nodes.get('sd-audience');
  assert.ok(card, '观众页应创建表格容器');
  const all = [...flatten(card), ...flatten(nodes.get('sd-toolbar'))];
  const text = all.map(n => n.textContent).join(' ');
  assert.match(text, /本期全部观众 45/);
  assert.match(text, /回访 25/);
  assert.match(text, /30\.50/);
  assert.match(text, /首次 2026/);
  assert.doesNotMatch(textOf(card), /名单：完整 2 场/, '名单表不重复显示总览口径');
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
  assert.deepEqual(flatten(nodes.get('sd-toolbar')).filter(n => /^\d+天$/.test(n.textContent)).map(n => n.textContent),
    ['7天', '30天', '90天', '180天', '360天']);
  assert.ok(!flatten(nodes.get('sd-audience')).some(n => /^\d+天$/.test(n.textContent)));
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
await check('点击昵称展开本期场次开播时间标题，再点收起', async () => {
  const all = flatten(nodes.get('sd-audience'));
  const button = all.find(n => n.tagName === 'a' && n.textContent === 'viewer');
  assert.ok(button, '昵称应可点击');
  const expanded = all.find(n => n.tagName === 'tr' && n.hidden === true);
  assert.ok(expanded, '场次初始应收起');
  await button.fire('click');
  assert.equal(expanded.hidden, false, '点击后展开');
  assert.equal(button.attrs['aria-expanded'], 'true');
  const visits = flatten(expanded).filter(n => n.tagName === 'a');
  assert.equal(visits.length, 2);
  assert.match(visits[0].textContent, /2026.*latest title/);
  assert.ok(visits[0].href.endsWith('/1792101600000'));
  await button.fire('click');
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
  const empty = flatten(drawer()).find(n => n.tagName === 'tr' && n.children[0]?.tagName === 'td' && n.children[1]?.textContent === '0');
  assert.ok(empty); await empty.fire('click'); await empty.fire('keydown', {key: 'Enter'});
  assert.equal(location.hash, before, '空周期不能打开场次');
  assert.notEqual(empty.attrs.role, 'link');
});
// 空周期的行不能伪装成可用链接。
await check('空周期明细行不打开场次抽屉', async () => {
  await load(base + '?panel=trend&period=week');
  const before = location.hash;
  const row = flatten(drawer()).find(n => n.tagName === 'tr' && n.children[0]?.tagName === 'td' && n.children[1]?.textContent === '0');
  assert.ok(row); await row.fire('click'); await row.fire('keydown', {key: 'Enter'});
  assert.equal(location.hash, before, '空周期行不能打开场次');
  assert.notEqual(row.attrs.role, 'link');
});
// 图和表须保留各桶原值，翻页每次只展示十个周期。
await check('周期明细逐桶显示场次时长和指标且每页十条', async () => {
  await load(base + '?panel=trend&period=week');
  const rows = flatten(drawer()).filter(n => n.tagName === 'tr' && n.children[0]?.tagName === 'td');
  assert.equal(rows.length, 10, '每页应为十条');
  const labels = ['10/12–10/18', '10/05–10/11', '09/28–10/04', '09/21–09/27', '09/14–09/20',
    '09/07–09/13', '08/31–09/06', '08/24–08/30', '08/17–08/23', '08/10–08/16'];
  for (const [i, row] of rows.entries()) {
    const bucket = trendBuckets.at(-1 - i);
    assert.equal(textOf(row.children[0]).trim(), labels[i]);
    assert.equal(row.children[1].textContent, model.fmtMetric(bucket.sessions), '明细场次须来自本桶');
    assert.equal(row.children[2].textContent, model.fmtDuration(bucket.durationSeconds), '明细时长须来自本桶');
    assert.equal(row.children[3].textContent, '100.00 元', '明细指标须来自本桶');
  }
  assert.match(textOf(drawer()), /共 25 条 · 第 1\/3 页/);
});
// 起点在06点之前也与图使用同一自然日；逐根读取图上标签，避免夹具自设另一套规则。
await check('周期表与图上同一根桶的标签一致', async () => {
  const older = trendBuckets.splice(0, trendBuckets.length - 1);
  const bucket = trendBuckets.at(-1), saved = bucket.start;
  try {
    for (const period of ['week', 'month']) {
      for (const start of [saved, Date.parse('2026-10-12T00:40:00+08:00')]) {
        bucket.start = start;
        page.leave(); await load(base + '?panel=trend&period=' + period);
        const row = flatten(drawer()).find(n => n.tagName === 'tr' && n.children[0]?.tagName === 'td');
        const chartLabels = flatten(drawer()).find(n => n.className === 'sc-chart-labels');
        assert.equal(textOf(row.children[0]).trim(), chartLabels.children.at(-1).textContent, '同一桶的表和图标签');
      }
    }
  } finally { bucket.start = saved; trendBuckets.unshift(...older); }
});
// 收藏链接须恢复隐藏选项，切换后也要写回地址。
await check('趋势收藏恢复隐藏设置并可取消', async () => {
  await load(base + '?panel=trend&period=day&hideOff=1');
  let input = flatten(drawer()).find(n => n.type === 'checkbox');
  assert.ok(input?.checked, '收藏链接须恢复隐藏复选框');
  input.checked = false; await input.fire('change');
  assert.equal(new URLSearchParams(location.hash.split('?')[1]).has('hideOff'), false);
});
// 周期与指标仅属于趋势；关闭趋势后总览仍保留隐藏设置，默认值省略。
await check('名单省略趋势指标参数并保留全页隐藏设置', async () => {
  page.leave();
  await load(base + '?panel=trend&period=day&metric=follows&mode=hourly&hideOff=1');
  const audience = flatten(nodes.get('sd-period-audience')).find(n => n.tagName === 'a');
  for (const key of ['period', 'metric', 'mode'])
    assert.equal(new URLSearchParams(audience.href.split('?')[1]).has(key), false, '名单链接不得携带' + key);
  assert.equal(model.parseStreamersHash(audience.href).hideOff, '1', '名单入口保留全页隐藏设置');
  await flatten(drawer()).find(n => n.className.includes('sc-drawer-close')).fire('click');
  for (const key of ['period', 'metric', 'mode'])
    assert.equal(new URLSearchParams(location.hash.split('?')[1]).has(key), false);
  assert.equal(model.parseStreamersHash(location.hash).hideOff, '1', '关闭后保留全页隐藏设置');
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
// 截断百分比保留可见缺口；不能将两个重叠缺口相加，也不能把未知名单写成完整。
await check('有效时长覆盖截断且零分母未知', () => {
  assert.equal(typeof model.durationCoverage, 'function', '须提供时长覆盖格式');
  assert.equal(model.durationCoverage({durationSeconds: 3600, effectiveSeconds: 3570}), '99.16%');
  assert.equal(model.durationCoverage({durationSeconds: 16260, effectiveSeconds: 16244}), '99.90%');
  assert.equal(model.durationCoverage({durationSeconds: 3600, effectiveSeconds: 3600}), '100%');
  assert.equal(model.durationCoverage({durationSeconds: 0, effectiveSeconds: 0}), '—');
});
await check('场次覆盖行分别显示四种名单词且保留缺口小标', async () => {
  sessionScenario = ['complete', 'anonymous', 'unknown', 'missing'].map((listQuality, i) => ({platform: 'sample', uid: 1,
    startTime: 1792101600000 + i, durationSeconds: 3600, effectiveSeconds: 3570,
    maintenanceGapSeconds: 30, roomOutageSeconds: 20, listQuality, metrics: {}}));
  try {
    await load(base + '?panel=sessions');
    for (const [i, word] of ['名单完整', '名单含匿名', '名单完整性未知', '无名单'].entries())
      assert.match(textOf(sessionRows()[i]), new RegExp('时长 99\\.16% · ' + word));
    assert.match(textOf(sessionRows()[0]), /缺 30 秒/);
  } finally { sessionScenario = null; }
});
// 缺口和名单是独立维度，标签不能全部显示或全部隐藏。
await check('已记录按缺口和名单独立判断', async () => {
  for (const gap of [true, false]) {
    compositionScenario = {sessions: 2, durationSeconds: 7200, capture: {effectiveSeconds: gap ? 7170 : 7200, gapSeconds: gap ? 30 : 0, gapSessions: gap ? 1 : 0}, coverage: {complete: gap ? 2 : 1, missing: gap ? 0 : 1}};
    page.leave(); await load(base);
    const cards = flatten(nodes.get('sd-insights-content')).filter(n => n.className === 'sc-kpi');
    for (const [label, expected] of [['流水', gap], ['弹幕', gap], ['观众', !gap], ['互动观众', !gap], ['新增关注', gap], ['每小时流水', gap], ['每小时弹幕', gap], ['直播时长', false], ['场次', false], ['粉丝净增', false]]) {
      const card = cards.find(n => n.children[0].textContent === label);
      assert.ok(card, label);
      assert.equal(flatten(card.children[0]).some(n => n.textContent === '已记录'), expected, label);
    }
  }
  compositionScenario = null;
});
await check('趋势覆盖逐桶读取且折叠条使用全页范围', async () => {
  compositionScenario = {sessions: 3, durationSeconds: 10800, capture: {effectiveSeconds: 10770, gapSeconds: 30, gapSessions: 1}, coverage: {complete: 2, missing: 1}};
  Object.assign(trendBuckets.at(-1), {effectiveSeconds: 7170, coverage: {complete: 1, anonymous: 1}});
  page.leave(); await load(base);
  try {
    for (const period of ['day', 'week', 'month']) {
      await load(base + '?panel=trend&period=' + period);
      assert.match(textOf(drawer()), /已记录数据 · 时长覆盖 99\.72% · 名单完整 2\/3 场 · 有缺口 1 场/);
      const row = flatten(drawer()).find(n => n.tagName === 'tr' && n.children[0]?.tagName === 'td');
      assert.equal(row.children.at(-1).children[0].textContent, '缺 50%');
      assert.match(row.children.at(-1).children[0].title, /采集 99\.58%.*名单完整 1\/2 场/);
    }
  } finally { compositionScenario = null; }
});
// 独立观察范围不能借用全页场数与覆盖，也不能在零缺口时显示尾段。
await check('观众使用全页覆盖而节奏使用独立观察范围', async () => {
  compositionScenario = {sessions: 2, durationSeconds: 7200, capture: {effectiveSeconds: 7200, gapSeconds: 0, gapSessions: 0}, coverage: {complete: 2}};
  slotsScenario = {sessions: 5, durationSeconds: 18000, capture: {effectiveSeconds: 17940, gapSeconds: 60, gapSessions: 2}, coverage: {complete: 3, unknown: 2}, cells: [], topRevenue: [], topMessages: []};
  try {
    page.leave(); await load(base + '?panel=audience');
    let summary = flatten(drawer()).find(n => n.tagName === 'summary' && n.textContent.startsWith('已记录数据'));
    assert.equal(summary?.textContent, '已记录数据 · 时长覆盖 100% · 名单完整 2/2 场');
    await load(base + '?panel=start-slots');
    summary = flatten(drawer()).find(n => n.tagName === 'summary' && n.textContent.startsWith('已记录数据'));
    assert.equal(summary?.textContent, '已记录数据 · 时长覆盖 99.66% · 名单完整 3/5 场 · 有缺口 2 场');
  } finally { compositionScenario = null; slotsScenario = null; }
});
// 六小时的当日须显示全覆盖，不能把未到来的十八小时算成缺口。
await check('今天六小时监看完整显示监测100%', async () => {
  const bucket = trendBuckets.at(-1);
  bucket.monitoring = {recording: true, watchedSeconds: 21600, elapsedSeconds: 21600}; bucket.status = 'off';
  try {
    page.leave(); await load(base + '?panel=trend&period=day');
    assert.match(coverageHints(), /监测 100% · 确认没播/);
    for (const period of ['week', 'month']) {
      await load(base + '?panel=trend&period=' + period);
      assert.doesNotMatch(coverageHints(), /监测 .* · 确认没播/);
    }
  } finally { delete bucket.monitoring; delete bucket.status; }
});
// 未记录与没有监看到是两回事，未知不能写成百分之零。
await check('监测未知与零覆盖分开显示', async () => {
  const bucket = trendBuckets.at(-1);
  try {
    for (const recording of [false, true]) {
      bucket.monitoring = {recording, watchedSeconds: 0, elapsedSeconds: 21600}; bucket.status = 'unknown';
      page.leave(); await load(base + '?panel=trend&period=day');
      assert.ok(coverageHints().includes(recording ? '监测 0.00% · 说不准' : '监测未知 · 说不准'));
    }
  } finally { delete bucket.monitoring; delete bucket.status; }
});
// 趋势三种状态词使用日历的闭集；近全覆盖仍要截断，不能四舍五入成100%。
await check('监测状态词及百分比向下截断', async () => {
  const bucket = trendBuckets.at(-1);
  assert.equal(model.monitoringCoverage({recording: true, watchedSeconds: 21599.999, elapsedSeconds: 21600}), '99.99%');
  try {
    for (const [status, word] of [['recorded', '有直播'], ['off', '确认没播'], ['unknown', '说不准']]) {
      bucket.monitoring = {recording: true, watchedSeconds: 999999, elapsedSeconds: 1000000}; bucket.status = status;
      page.leave(); await load(base + '?panel=trend&period=day');
      assert.ok(coverageHints().includes('监测 99.99% · ' + word));
    }
  } finally { delete bucket.monitoring; delete bucket.status; }
});
// 两期独立读取；可观察天数不能补齐，缺口不足一分钟须保留秒，未知不编造天数。
await check('统计口径分别显示两期监测和可观察天数', async () => {
  compositionScenario = {monitoring: {recording: true, watchedSeconds: 540000, elapsedSeconds: 540000, observableDays: 3.3, rangeDays: 7},
    previous: {monitoring: {recording: true, watchedSeconds: 539941, elapsedSeconds: 540000, observableDays: null, rangeDays: 7}}};
  try {
    page.leave(); await load(base);
    let scope = textOf(nodes.get('sd-scope'));
    assert.ok(scope.includes('本期：3.3 / 7 天可观察 · 监测覆盖 100% · 监测缺口 0 秒'));
    assert.ok(scope.includes('上期：未知 / 7 天可观察 · 监测覆盖 99.98% · 监测缺口 59 秒'));
    compositionScenario.previous.monitoring.watchedSeconds = 536340;
    page.leave(); await load(base);
    assert.ok(textOf(nodes.get('sd-scope')).includes('监测缺口 1 时 1 分'));
    compositionScenario.monitoring.recording = false;
    compositionScenario.previous.monitoring.recording = false;
    page.leave(); await load(base);
    scope = textOf(nodes.get('sd-scope'));
    assert.ok(scope.includes('本期：监测未知（本机没有监看记录）'));
    assert.ok(scope.includes('上期：监测未知（本机没有监看记录）'));
  } finally { compositionScenario = null; }
});
// 没缺口且名单齐全时，99%或未知仍要标已记录；全覆盖则所有标签都应消失。
await check('已记录同时反映监测不足且粉丝净增除外', async () => {
  try {
    for (const [recording, watchedSeconds, expected] of [[true, 9900, true], [false, 0, true], [true, 10000, false]]) {
      compositionScenario = {sessions: 2, durationSeconds: 7200, capture: {effectiveSeconds: 7200, gapSeconds: 0, gapSessions: 0},
        coverage: {complete: 2}, monitoring: {recording, watchedSeconds, elapsedSeconds: 10000}};
      page.leave(); await load(base);
      const cards = flatten(nodes.get('sd-insights-content')).filter(n => n.className === 'sc-kpi');
      for (const label of ['直播时长', '场次', '流水', '弹幕', '新增关注', '每小时流水', '每小时弹幕', '观众', '互动观众', '粉丝净增']) {
        const card = cards.find(n => n.children[0].textContent === label);
        assert.ok(card, label);
        assert.equal(flatten(card.children[0]).some(n => n.textContent === '已记录'), label === '粉丝净增' ? false : expected, label);
      }
    }
  } finally { compositionScenario = null; }
});
// 统计口径的两种缺口都保留整秒，小于一秒不能冒充零。
for (const [gap, expected] of [[59, '59 秒'], [60, '1 分'], [3.217, '3 秒'],
  [0.001, '不足 1 秒'], [3599, '59 分 59 秒'], [7199, '1 时 59 分 59 秒'], [0, '0 秒']]) {
  for (const kind of ['monitoring', 'capture']) await check('统计口径' + kind + '缺口 ' + gap, async () => {
    const monitoring = {recording: true, elapsedSeconds: 10000, watchedSeconds: 10000 - gap, rangeDays: 7};
    compositionScenario = {monitoring, previous: {monitoring}, capture: {gapSeconds: gap, gapSessions: 1}};
    try {
      page.leave(); await load(base);
      const lines = scopeLines();
      const prefix = kind === 'monitoring' ? '监测缺口 ' : '缺口合计 ';
      const actual = lines.filter(line => line.includes(prefix)).map(line => line.split(prefix)[1].split(' · ')[0]);
      assert.deepEqual(actual, kind === 'monitoring' ? [expected, expected] : [expected], prefix + '得到' + JSON.stringify(actual) + '，应为' + expected);
    } finally { compositionScenario = null; }
  });
}
// 首屏和换范围均不得在计数未到时闪出可点击的全部归档。
await check('归档入口读取中不可点击，成功与失败后恢复链接', async () => {
  const link = () => flatten(nodes.get('sd-body')).find(n => n.className.includes('sd-archive-link'));
  const waitForRequest = async count => {
    const deadline = Date.now() + 20000;
    while (pending.length < count) {
      if (Date.now() > deadline) throw new Error('归档请求未发出');
      await new Promise(resolve => setImmediate(resolve));
    }
  };
  const initial = () => { assert.equal(link().textContent, '正在读取归档场数…'); assert.ok(!link().href, '读取时没有href'); };
  let loading;
  try {
    page.leave(); loading = load(base + '?days=90');
    await waitForRequest(1); initial();
  } finally {
    pending.splice(0).forEach(resolve => resolve(response({success: true, insights: {from: 100, to: 200, sessions: 2, daily: []}})));
    await loading;
  }
  assert.equal(link().textContent, '查看 2 场归档'); assert.ok(link().href);
  await load(base + '?days=30');
  try {
    loading = load(base + '?days=90'); await waitForRequest(1); initial();
  } finally {
    pending.splice(0).forEach(resolve => resolve(response({success: false, message: 'unavailable'})));
    await loading;
  }
  assert.equal(link().textContent, '查看全部归档'); assert.ok(link().href);
  const state = model.parseStreamersHash(link().href);
  for (const key of ['from', 'to', 'dateFrom', 'dateTo', 'source']) assert.equal(state[key], undefined);
});
// 地址历史改变开关时，已画出的静态入口必须刷新，不能只重画趋势。
await check('后退前进同步名单与历史节奏入口的隐藏日期参数', async () => {
  page.leave(); await load(base + '?days=30');
  const links = ['查看本期名单', '查看历史节奏'].map(label =>
    flatten(nodes.get('sd-body')).find(n => n.tagName === 'a' && n.textContent === label));
  assert.ok(links.every(Boolean));
  for (const hideOff of ['1', undefined, '1', undefined]) {
    await load(base + '?days=30' + (hideOff ? '&hideOff=1' : ''));
    for (const link of links) {
      assert.ok(flatten(nodes.get('sd-body')).includes(link), '保留已画出的入口');
      assert.equal(model.parseStreamersHash(link.href).hideOff, hideOff, link.textContent + '地址参数');
    }
  }
});
// 平台目录为空属于未知；目录非空且快照缺键才是未发生的零收入。
for (const place of ['recent', 'sessions']) await check(place + '无收入目录显示未知，有目录缺键显示零', async () => {
  const saved = revenueKeys;
  const items = [{platform: 'sample', uid: 1, startTime: 1792101600100, metrics: {danmu_count: 2}}];
  sessionScenario = recentScenario = items;
  try {
    for (const [keys, expected] of [[[], '—'], [['gift_value'], '0.00 元']]) {
      revenueKeys = keys; page.leave(); await load(base + (place === 'sessions' ? '?panel=sessions' : ''));
      if (place === 'sessions') assert.equal(sessionMetric(sessionRows()[0], '流水'), expected);
      else {
        const row = flatten(nodes.get('sd-recent')).find(n => n.className === 'sd-recent-row');
        assert.equal(textOf(row).split('流水 ')[1].split(' · ')[0], expected);
      }
    }
  } finally { revenueKeys = saved; sessionScenario = recentScenario = null; }
});

// 周期、指标、分页和范围切换不能把折叠条移回控件前或重复插入。
await check('折叠条切按周及其他控件后仍唯一且在控件后', async () => {
  page.leave(); await load(base + '?panel=trend&period=day');
  const week = flatten(drawer()).find(n => n.textContent === '按统计周');
  await load(week.href);
  const verify = () => {
    const body = flatten(drawer()).find(n => n.className === 'sc-drawer-body');
    const bars = flatten(body).filter(n => n.className === 'sd-recording');
    assert.equal(bars.length, 1, '折叠条只能有一个');
    const controls = body.children.findIndex(n => n.className === 'sd-trend-controls');
    assert.equal(body.children.indexOf(bars[0]), controls + 1, '折叠条须紧跟趋势控件');
    return bars[0];
  };
  verify().open = true;
  for (const [key, value] of [['metric', 'messages'], ['page', '2'], ['period', 'month'], ['days', '7']]) {
    const params = new URLSearchParams(location.hash.split('?')[1]); params.set(key, value);
    await load(base + '?' + params); assert.ok(!verify().open, '切换后沿用重新折叠的行为');
  }
  for (const panel of ['audience', 'start-slots']) {
    await load(base + '?panel=' + panel);
    const body = flatten(drawer()).find(n => n.className === 'sc-drawer-body');
    const bars = flatten(body).filter(n => n.className === 'sd-recording');
    assert.equal(bars.length, 1, panel + '折叠条只能有一个');
    assert.equal(body.children.indexOf(bars[0]), panel === 'audience' ? 0 : 1, panel + '折叠条须跟在控件后');
    assert.ok(!body.children.some(n => n.className.includes('sd-detail-scope')), '范围统一在抽屉标题下显示');
    assert.ok(flatten(drawer()).some(n => n.className === 'sd-drawer-context' && n.textContent.includes('Asia/Shanghai')), '标题下须保留范围');
  }
});
// 使用不同的本期、上期边界，防止对比范围误用本期或漏掉一端。
await check('统计口径头两行完整使用本期和上期接口边界', async () => {
  compositionScenario = {from: Date.parse('2026-10-10T06:00:00+08:00'), to: Date.parse('2026-10-16T12:34:00+08:00'),
    previous: {from: Date.parse('2026-10-03T06:00:00+08:00'), to: Date.parse('2026-10-10T06:00:00+08:00')}};
  try {
    page.leave(); await load(base + '?days=7');
    const scope = nodes.get('sd-scope'); scope.open = true;
    const lines = scopeLines();
    console.log('SCOPE_SAMPLE: ' + lines.join(' | '));
    assert.deepEqual(lines.slice(0, 2), [
      '全页范围：2026/10/10 06:00 — 2026/10/16 12:34（不含结束时刻） · Asia/Shanghai',
      '对比范围：2026/10/03 06:00 — 2026/10/10 06:00（不含结束时刻）'], '须分别显示本期和上期起止');
    // 回包没有上期时，不能把已经读完的统计范围继续写成正在读取。
    compositionScenario.previous = null;
    page.leave(); await load(base + '?days=7');
    assert.equal(scopeLines()[1], '对比范围：—', '无上期回包后须显示破折号');
  } finally { compositionScenario = null; }
});
// 读不到整页数据时，统计口径头两行不能停在读取中，须写明与工具条那行一样的失败原因。
await check('读取失败后统计口径头两行写明原因不写读取中', async () => {
  insightsFailure = true;
  try {
    page.leave(); await load(base + '?days=7');
    const lines = scopeLines();
    console.log('SCOPE_ERROR_SAMPLE: ' + lines.slice(0, 2).join(' | '));
    assert.ok(textOf(nodes.get('sd-toolbar')).includes('读取失败：archive unavailable'), '工具条那行须写失败原因');
    assert.ok(!lines[0].includes('正在读取'), '全页范围不得写正在读取，实际：' + lines[0]);
    assert.ok(!lines[1].includes('正在读取'), '对比范围不得写正在读取，实际：' + lines[1]);
    assert.equal(lines[0], '全页范围：读取失败：archive unavailable', '全页范围须写与工具条一样的失败原因');
    assert.equal(lines[1], '对比范围：读取失败：archive unavailable', '对比范围须写与工具条一样的失败原因');
  } finally { insightsFailure = false; }
});
// 接口给了上期但缺起止时刻时，对比范围须与没有上期一样写破折号，不能落进读取中。
await check('上期缺起止时刻时对比范围写破折号', async () => {
  try {
    for (const previous of [{from: Date.parse('2026-10-03T06:00:00+08:00')},
      {to: Date.parse('2026-10-10T06:00:00+08:00')}, {}]) {
      compositionScenario = {from: Date.parse('2026-10-10T06:00:00+08:00'), to: Date.parse('2026-10-16T12:34:00+08:00'), previous};
      page.leave(); await load(base + '?days=7');
      const lines = scopeLines();
      console.log('SCOPE_PREVIOUS_SAMPLE: ' + lines[1]);
      assert.ok(!lines[1].includes('正在读取'), '上期缺起止时刻不得写正在读取，实际：' + lines[1]);
      assert.equal(lines[1], '对比范围：—', '上期缺起止时刻须写破折号');
      assert.match(lines[0], /2026\/10\/10 06:00/, '全页范围仍照旧显示本期起止');
    }
  } finally { compositionScenario = null; }
});
// 模拟东京的本地时区，抓住当前卡比场次列表多一小时及缺少日期的情况。
await check('Asia/Tokyo查看直播卡仍按东八区带日期显示', async () => {
  const saved = process.env.TZ;
  process.env.TZ = 'Asia/Tokyo';
  const at = Date.parse('2026-10-16T10:09:08+08:00');
  liveSnapshot = {success: true, living: true, since: at, dataAt: at + 60000, checkedAt: at + 120000, metrics: {}};
  sessionScenario = [{platform: 'sample', uid: 1, startTime: at, durationSeconds: 60, metrics: {}}];
  try {
    assert.equal(new Date(at).getHours(), 11, '测试环境须在东京');
    page.leave(); await load(base + '?panel=sessions');
    const liveText = textOf(nodes.get('sd-live'));
    assert.ok(liveText.includes('开播于 2026/10/16 10:09'), '开播于须为东八区日期与时分：' + liveText);
    assert.ok(liveText.includes('数据截至 2026/10/16 10:10'), '数据截至须带日期');
    assert.ok(liveText.includes('检查于 10:11:08'), '检查于须为东八区时分秒');
    assert.ok(textOf(sessionRows()[0]).includes('2026/10/16 10:09'), '场次列表须与直播卡对齐');
  } finally {
    if (saved === undefined) delete process.env.TZ; else process.env.TZ = saved;
    liveSnapshot = sessionScenario = null;
  }
});
// 同样的缺口在最近归档与场次列表都须使用分秒，不能保留整串秒数。
for (const [gap, expected] of [[66, '缺 1 分 6 秒'], [3599, '缺 59 分 59 秒']]) {
  await check('场次与最近归档小标格式 ' + gap, async () => {
    sessionScenario = recentScenario = [{platform: 'sample', uid: 1, startTime: 1792101600100,
      maintenanceGapSeconds: gap, durationSeconds: 7200, metrics: {}}];
    try {
      page.leave(); await load(base + '?panel=sessions');
      const badges = flatten(sessionRows()[0]).filter(n => n.className === 'sd-gap-badge');
      const recentBadges = flatten(nodes.get('sd-recent')).filter(n => n.className === 'sd-gap-badge');
      console.log('GAP_SAMPLE: ' + gap + ' => ' + badges[0]?.textContent);
      assert.equal(badges[0]?.textContent, expected, '场次小标须使用分秒');
      assert.equal(recentBadges[0]?.textContent, expected, '最近归档须使用相同小标');
    } finally { sessionScenario = recentScenario = null; }
  });
}

page.leave();
for (const failure of failures) console.log('FAIL: ' + failure);
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
