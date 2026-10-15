import {register} from 'node:module';
import assert from 'node:assert/strict';
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
const audienceResponse = {success: true, audience: {group: 'all', sort: 'attendance',
  counts: {all: 45, first: 20, returning: 25, interaction: 12}, coverage: {complete: 2},
  page: 1, pages: 3, total: 45, items: [{name: 'viewer', attendance: 2, revenue: 30.5,
    firstSeen: 1792015200000, lastSeen: 1792101600000, interaction: true,
    visits: [{startTime: 1792101600000, title: 'latest title'}, {startTime: 1792015200000, title: 'first title'}]}]}};
globalThis.fetch = async url => {
  requests.push(String(url));
  if (String(url).includes('/calendar?')) return response({success: true, calendar: {items: [{date: '2026-10-16', weekday: 5, start: 1792101600000, end: 1792188000000, status: 'recorded', effectiveSeconds: 600, starts: [1792101600100, 1792101600200]}]}});
  if (String(url).includes('/sessions?')) return response({success: true, sessions: {items: [1792101600100, 1792101600200].map(startTime => ({platform: 'sample', uid: 1, startTime, durationSeconds: 60, metrics: {}, title: String(startTime)})), total: 2, page: 1, pages: 1, metrics: []}});
  if (String(url).includes('/start-slots?')) return response({success: true, startSlots: {cells: [], topRevenue: [], topMessages: []}});
  if (String(url).includes('/fans?')) return response({success: true, fans: {points: [{at: 1792101600000, value: 10}, {at: 1792101600100, value: 12}], change: 2, partial: true}});
  if (String(url).includes('/audience?')) return response(audienceResponse);
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
const flatten = n => n ? [n, ...n.children.flatMap(flatten)] : [];
const base = '#/streamers/sample/1';
const drawer = () => flatten(document.body).find(n => n.className === 'sc-drawer');
const load = async hash => { location.hash = hash; await page.refresh(); };
// 旧收藏必须保留入口，否则从外部进入会丢失明细。
await check('收藏的旧链接恢复总览与明细', async () => {
  for (const panel of ['sessions', 'start-slots', 'audience']) {
    await load(base + '/' + panel);
    assert.ok(nodes.get('sd-trend'), '旧链接应显示总览趋势卡');
    assert.ok(drawer(), '旧链接应打开抽屉：' + panel);
    assert.ok(requests.some(url => url.includes('/' + panel + '?')));
  }
  await load(base + '/trend');
  assert.equal(nodes.get('sd-trend')?.scrolled, true, '旧趋势链接应定位趋势卡');
  assert.equal(drawer(), undefined);
});
// 范围必须作用于全部本期卡片，不能混用不同范围的数字。
await check('180天同步指标趋势观众日历', async () => {
  await load(base);
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
  assert.ok(day, '有直播日期应可点击'); await day.fire('click'); await page.refresh();
  assert.ok(requests.some(url => url.includes('/sessions?') && new URL(url, 'https://example.test').searchParams.get('starts') === '1792101600100,1792101600200'), '场次请求应带当天starts');
  const content = flatten(drawer()).map(n => n.innerHTML).join(' ');
  assert.match(content, /1792101600100/); assert.match(content, /1792101600200/);
});
// 指标不能打开错误的明细；前三格只切换并定位趋势。
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
  assert.equal(nodes.get('sd-trend').scrolled, true);
  assert.equal(flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select').value, 'messages');
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
  const dots = flatten(nodes.get('sd-trend')).filter(n => n.className.includes('sc-point'));
  assert.equal(dots.length, 1, '未播不画，已播零值保留');
  const select = flatten(nodes.get('sd-trend')).find(n => n.tagName === 'select');
  select.value = 'audience'; await select.fire('change');
  assert.equal(flatten(nodes.get('sd-trend')).filter(n => n.className.includes('sc-point')).length, 0, '缺失观众值不能画成零');
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
await check('六格沿用较上期判法，红涨绿跌持平无色', async () => {
  compositionScenario = {comparisons: {durationSeconds: {changeRatio: 0}, revenue: {changeRatio: .12},
    messages: {changeRatio: -.05}, audience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'},
    interactionAudience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'}}};
  await page.refresh();
  const all = flatten(nodes.get('sd-insights'));
  const lines = all.filter(n => n.className.includes('sc-kpi-delta') && n.textContent.startsWith('较上期') && n.textContent !== '较上期暂无数据');
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
    assert.equal(flatten(nodes.get('sd-audience')).find(n => n.textContent.startsWith('第 '))?.textContent,
      '第 1 / 2,942 页 · 共 58,840 人', '页脚数字应带千分位');
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
  const next = flatten(nodes.get('sd-audience')).find(n => n.textContent === '下一页');
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
page.leave();
for (const failure of failures) console.log('FAIL: ' + failure);
console.log('跑了 ' + checks + ' 格，失败 ' + failures.length + ' 格');
process.exitCode = failures.length ? 1 : 0;
