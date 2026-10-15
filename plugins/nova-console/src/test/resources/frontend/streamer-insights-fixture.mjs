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
const audienceResponse = {success: true, audience: {group: 'all', sort: 'attendance',
  counts: {all: 45, first: 20, returning: 25, interaction: 12}, coverage: {complete: 2},
  page: 1, pages: 3, total: 45, items: [{name: 'viewer', attendance: 2, revenue: 30.5,
    firstSeen: 1792015200000, lastSeen: 1792101600000, interaction: true,
    visits: [{startTime: 1792101600000, title: 'latest title'}, {startTime: 1792015200000, title: 'first title'}]}]}};
globalThis.fetch = async url => {
  requests.push(String(url));
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
  assert.ok(flatten(nodes.get('sd-tabs')).some(n => n.href?.endsWith('/audience?days=7&group=all&sort=attendance&page=1')), '切到观众应保留刚选的天数');
  const card = nodes.get('sd-insights');
  assert.ok(card);
  const links = flatten(card).filter(n => n.tagName === 'a');
  assert.ok(links.some(n => n.href.endsWith('?days=7') && n.attrs['aria-current'] === 'page'));
  const chart = flatten(card).map(n => n.innerHTML).join('');
  assert.equal((chart.match(/<rect class="c-bar/g) || []).length, 1);
  assert.match(chart, /当天没有开播记录/);
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
    const composition = flatten(nodes.get('sd-insights')).find(n => n.textContent === '本期构成：暂无数据');
    assert.ok(composition, scenario.name + '应显示暂无数据');
    titles.push(composition.title);
    console.log(scenario.name + '：' + composition.title);
  }
  assert.deepEqual(titles, scenarios.map(scenario => scenario.title), '本期无名单时不得归因为历史记录起点');
});
await check('五项下方显示较上期，红涨绿跌持平无色，粉丝不比', async () => {
  compositionScenario = {comparisons: {durationSeconds: {changeRatio: 0}, revenue: {changeRatio: .12},
    messages: {changeRatio: -.05}, audience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'},
    interactionAudience: {reasonCode: 'PREVIOUS_ZERO', reason: '上期为 0'}}};
  await page.refresh();
  const all = flatten(nodes.get('sd-insights'));
  const lines = all.filter(n => n.tagName === 'small' && n.textContent.startsWith('较上期'));
  assert.deepEqual(lines.map(n => [n.textContent, n.style.color]), [
    ['较上期持平', ''], ['较上期 +12%', 'var(--err)'], ['较上期 −5%', 'var(--ok)']]);
  assert.equal(all.filter(n => n.className === 'stat').length, 6);
});
await check('对比说明默认折叠，按原因列指标，展开后显示参考说明', async () => {
  compositionScenario.comparisonNote = '6.0 之前开播的场次名单完整性未知，观众数对比仅供参考';
  await page.refresh();
  const all = flatten(nodes.get('sd-insights'));
  const details = all.find(n => n.tagName === 'details');
  assert.ok(details, '必须有可展开的说明');
  assert.ok(!details.open && !('open' in details.attrs), '说明默认折叠');
  assert.equal(details.children[0].tagName, 'summary');
  assert.equal(details.children[0].textContent, '对比说明 · 2 项未比较');
  const text = flatten(details).map(n => n.textContent).join(' ');
  assert.match(text, /上期为 0[：:]观众、互动观众/);
  assert.match(text, /6.0 之前开播.*仅供参考/);
});
await check('只有旧名单参考说明时仍可展开，无说明时隐藏折叠入口', async () => {
  compositionScenario = {comparisons: {audience: {changeRatio: 0}}, comparisonNote: '6.0 之前开播的场次名单完整性未知，观众数对比仅供参考'};
  await page.refresh();
  assert.ok(flatten(nodes.get('sd-insights')).some(n => n.textContent === '对比说明 · 0 项未比较'));
  compositionScenario = {comparisons: {audience: {changeRatio: 0}}};
  await page.refresh();
  assert.ok(!flatten(nodes.get('sd-insights')).some(n => n.tagName === 'details'));
});
await check('完整名单的观众与互动观众都显示较上期，涨红跌绿', async () => {
  for (const sign of [1, -1]) {
    compositionScenario = {audience: 112, interactionAudience: 95, coverage: {complete: 2},
      comparisons: {audience: {changeRatio: sign * .12}, interactionAudience: {changeRatio: sign * -.05}}};
    await page.refresh();
    const cells = flatten(nodes.get('sd-insights')).filter(n => n.className === 'stat');
    for (const [label, text, color] of [
      ['观众', sign > 0 ? '较上期 +12%' : '较上期 −12%', sign > 0 ? 'var(--err)' : 'var(--ok)'],
      ['互动观众', sign > 0 ? '较上期 −5%' : '较上期 +5%', sign > 0 ? 'var(--ok)' : 'var(--err)'],
    ]) {
      const cell = cells.find(n => n.innerHTML.includes('>' + label + '</div>'));
      assert.ok(cell, label + '格应存在');
      assert.ok(cell.children.some(n => n.tagName === 'small' && n.textContent === text && n.style.color === color),
        label + '格缺少较上期文字或涨跌颜色');
    }
  }
});
await check('观众页显示人数五列及范围分组排序分页链接', async () => {
  location.hash = '#/streamers/sample/1/audience';
  await page.refresh();
  const card = nodes.get('sd-audience');
  assert.ok(card, '观众页应创建表格容器');
  assert.ok(flatten(nodes.get('sd-tabs').children[0]).some(n => n.href?.includes('days=7')), '范围切换应在页签上方');
  const all = [...flatten(card), ...flatten(nodes.get('sd-tabs'))];
  const text = all.map(n => n.textContent).join(' ');
  assert.match(text, /本期全部观众 45 人/);
  assert.match(text, /回访 25 人/);
  assert.match(text, /30\.50/);
  assert.match(text, /首次 2026/);
  assert.match(text, /名单：完整 2 场/);
  const links = all.filter(n => n.tagName === 'a');
  for (const query of ['days=7&group=all&sort=attendance&page=1',
    'days=30&group=returning&sort=attendance&page=1',
    'days=30&group=all&sort=revenue&page=1',
    'days=30&group=all&sort=recent&page=1',
    'days=30&group=all&sort=attendance&page=2']) {
    assert.ok(links.some(n => n.href.endsWith(query)), '应能切换 ' + query);
  }
  assert.ok(requests.some(url => url.includes('/audience?days=30&group=all&sort=attendance&page=1')));
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
await check('经场次趋势切回观众保留范围分组排序页码', async () => {
  const original = '#/streamers/sample/1/audience?days=7&group=returning&sort=revenue&page=2';
  location.hash = original;
  await page.refresh();
  assert.equal(flatten(nodes.get('sd-tabs')).find(n => n.textContent === '观众').href, original,
    '观众页签链接保留当前条件');
  for (const pane of ['sessions', 'trend']) {
    const link = flatten(nodes.get('sd-tabs')).find(n => n.textContent === (pane === 'sessions' ? '场次' : '趋势'));
    location.hash = link.href;
    await page.refresh();
    const back = flatten(nodes.get('sd-tabs')).find(n => n.textContent === '观众');
    assert.equal(back.href, original, '切回观众应保留全部筛选条件');
    requests.length = 0;
    location.hash = back.href;
    await page.refresh();
    assert.ok(requests.some(url => url.endsWith('/audience?days=7&group=returning&sort=revenue&page=2')),
      '恢复后请求沿用原范围分组排序页码');
  }
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
