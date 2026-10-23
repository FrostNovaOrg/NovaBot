import assert from 'node:assert/strict';
import {register} from 'node:module';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

class Element extends EventTarget {
  constructor(tag) {
    super();
    this.tagName = tag;
    this.children = [];
    this.attrs = {};
    this.dataset = {};
    this.style = {setProperty(key, value) { this[key] = value; }};
    this.className = '';
    this.ownText = '';
    this.rect = {width: 640, height: 180, left: 0};
    this.classList = {add: (...names) => { this.className += ' ' + names.join(' '); }};
  }
  append(...nodes) {
    for (const node of nodes) {
      const child = typeof node === 'string' ? Object.assign(new Element('#text'), {textContent: node}) : node;
      child.parentNode = this;
      this.children.push(child);
    }
  }
  appendChild(node) { this.append(node); return node; }
  replaceChildren(...nodes) { this.children.forEach(node => { node.parentNode = null; }); this.children = []; this.append(...nodes); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(node => node !== this); this.parentNode = null; }
  set textContent(value) { this.ownText = String(value); this.replaceChildren(); }
  get textContent() { return this.ownText + this.children.map(node => node.textContent).join(''); }
  setAttribute(key, value) { this.attrs[key] = String(value); }
  getAttribute(key) { return this.attrs[key] ?? null; }
  contains(node) { return this === node || this.children.some(child => child.contains(node)); }
  get isConnected() { return this === document.body || !!this.parentNode?.isConnected; }
  getBoundingClientRect() {
    if (this.tagName === 'span' && this.parentNode?.className.split(' ').includes('sc-chart-labels'))
      return {...this.rect, width: this.textContent.length * 7};
    return this.rect;
  }
  focus() { document.activeElement = this; this.dispatchEvent(new Event('focus')); }
  showModal() { this.open = true; }
  close() { this.open = false; this.dispatchEvent(new Event('close')); }
  fire(type, fields = {}) { const event = new Event(type, {cancelable: true}); Object.assign(event, fields); this.dispatchEvent(event); return event; }
}
globalThis.document = {
  createElement: tag => new Element(tag),
  createElementNS: (_, tag) => new Element(tag),
  body: new Element('body'),
  activeElement: null,
};
const observers = [];
globalThis.ResizeObserver = class {
  constructor(callback) { this.callback = callback; observers.push(this); }
  observe(target) { this.target = target; }
  disconnect() { this.disconnected = true; }
  resize(width, height) {
    this.target.rect = {width, height, left: 0};
    this.callback([{target: this.target, contentRect: this.target.rect}]);
  }
};
const ui = await import('../../../main/resources/config-ui-pages/streamers-components.js');
const walk = node => [node, ...node.children.flatMap(walk)];
const byClass = (node, name) => walk(node).filter(item => item.className.split(' ').includes(name));
const host = () => { const node = new Element('div'); document.body.append(node); return node; };
const points = [
  {start: 100, end: 200, label: '10/13', value: 4},
  {start: 200, end: 300, label: '10/14', value: null, status: 'off'},
  {start: 300, end: 400, label: '10/15', value: 0},
  {start: 400, end: 500, label: '10/16', value: -2},
];
let checks = 0, failures = 0;
async function check(name, fn) {
  checks++;
  try { await fn(); process.stdout.write('绿 ' + name + '\n'); }
  catch (error) { failures++; process.stdout.write('红 ' + name + '：' + error.message + '\n'); }
  finally { ui.releaseComponents(); }
}

// 纵轴全数加单位会换行；读数仍须保留调用方提供的精度和单位。
await check('纵轴大数简写且读数保留单位', () => {
  const chart = ui.createChart(host(), {points: [{...points[0], value: 87745.8}], format: value => value.toFixed(2) + ' 元'});
  assert.equal(byClass(chart.element, 'sc-chart-scale')[0].children[0].textContent, '8.8万', '大数刻度应简写成一位小数的万');
  assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('87745.80 元'), '刻度格式不能改掉读数精度与单位');
});
// 粉丝变化不能被零起点压平，也不能出现半个人的纵轴刻度。
await check('粉丝图按数据范围起轴且刻度为整数', () => {
  const chart = ui.createChart(host(), {fitRange: true, integerTicks: true,
    points: [2001, 2004, 2002].map((value, i) => ({start: i, end: i + 1, value}))});
  const ticks = byClass(chart.element, 'sc-chart-scale')[0].children.map(n => n.textContent);
  assert.ok(ticks.every(tick => !tick.includes('.')), '人数刻度不能带小数');
  assert.ok(Number(ticks.at(-1).replaceAll(',', '')) >= 2000, '粉丝纵轴应靠近实际范围');
  const dots = byClass(chart.element, 'sc-point').map(n => Number(n.getAttribute('cy')));
  assert.ok(Math.max(...dots) - Math.min(...dots) > 50, '三人的变化应清楚可见');
});

// 缺测不能变成零点，否则曲线会凭空跌到零；真实的零必须保留。
await check('缺测断线且真实零保留', () => {
  const chart = ui.createChart(host(), {points});
  const dots = byClass(chart.element, 'sc-chart-hit');
  assert.equal(dots.length, 3, '有效点数量');
  assert.equal(byClass(chart.element, 'sc-point').length, 3, '普通曲线的每个有效快照都有可见标记');
  dots[0].fire('pointerenter');
  assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('10/13'), '普通曲线保留悬停读数');
  assert.equal(byClass(chart.element, 'sc-line')[0].getAttribute('d').match(/M/g).length, 2, '缺测处分段');
  const zero = byClass(chart.element, 'sc-zero')[0];
  assert.equal(dots[1].getAttribute('cy'), zero.getAttribute('y1'), '真实零在零线上');
  assert.ok(Number(dots[2].getAttribute('cy')) > Number(dots[1].getAttribute('cy')), '负值位于零线下');
  for (const numbers of [[2, 4], [-2, -4], [0, 0]]) {
    const one = ui.createChart(host(), {points: numbers.map((value, i) => ({start: i, end: i + 1, value}))});
    const baseline = Number(byClass(one.element, 'sc-zero')[0].getAttribute('y1'));
    assert.ok(baseline >= 14 && baseline <= 154, '零线在画布内');
    for (const point of byClass(one.element, 'sc-chart-hit')) {
      const coordinate = Number(point.getAttribute('cy'));
      assert.ok(coordinate >= 14 && coordinate <= 154, '数值在画布内');
    }
  }
});
// 隔天有数时只有移动指令，透明命中圈不能代替可见的数据点。
await check('按日点选图隔天有数时每个有效日都有可见标记', () => {
  for (const length of [7, 30, 1]) {
    const records = Array.from({length}, (_, i) => ({
      start: i * 86400000, end: (i + 1) * 86400000, value: i % 2 ? null : i - 2,
    }));
    const chart = ui.createChart(host(), {points: records, onSelect() {}});
    const assertVisible = () => {
      const hits = byClass(chart.element, 'sc-chart-hit');
      const visible = byClass(chart.element, 'sc-point').filter(mark =>
        mark.getAttribute('visibility') !== 'hidden' && Number(mark.getAttribute('r')) > 0);
      assert.equal(hits.length, records.filter(point => point.value != null).length);
      for (const hit of hits) assert.ok(visible.some(mark =>
        mark.getAttribute('cx') === hit.getAttribute('cx') && mark.getAttribute('cy') === hit.getAttribute('cy')),
      '每个有效日都应有可见标记：' + hit.dataset.index);
      const path = byClass(chart.element, 'sc-line')[0].getAttribute('d');
      assert.equal((path.match(/M/g) || []).length, hits.length, '空日仍须断线');
      assert.ok(!path.includes('L'), '不能跨空日连线');
    };
    assertVisible();
    if (length > 1) {
      chart.svg.fire('click', {clientX: 0});
      chart.svg.fire('keydown', {key: 'ArrowRight'});
      assertVisible();
    }
    observers.at(-1).resize(360, 220);
    assertVisible();
  }
});
// 看 30 天时，孤立一天没有线段帮助辨认，密集图上的小点几乎看不见。
await check('31 个日期中的孤立日半径为 3，相连点仍为 1.7', () => {
  const records = Array.from({length: 31}, (_, i) => ({
    start: i * 86400000, end: (i + 1) * 86400000, label: String(i + 1),
    value: i === 14 || i === 16 ? null : i,
  }));
  for (const onSelect of [undefined, () => {}]) {
    const chart = ui.createChart(host(), {points: records, onSelect});
    const hits = byClass(chart.element, 'sc-chart-hit');
    const dots = byClass(chart.element, 'sc-point').filter(dot => dot.getAttribute('cx') != null);
    const isolatedX = hits.find(hit => hit.dataset.index === '15').getAttribute('cx');
    assert.equal(dots.find(dot => dot.getAttribute('cx') === isolatedX).getAttribute('r'), '3', '孤立日应保持可见');
    if (!onSelect) {
      const connectedX = hits.find(hit => hit.dataset.index === '1').getAttribute('cx');
      assert.equal(dots.find(dot => dot.getAttribute('cx') === connectedX).getAttribute('r'), '1.7', '相连点保留密集尺寸');
    }
  }
});
// 日期轴压缩后，点选仍须钻取原始日期，且不得修改调用方数组。
await check('隐藏未播后点选原日期', () => {
  let selected;
  const before = JSON.stringify(points);
  const chart = ui.createChart(host(), {points, hideOff: true, onSelect: interval => { selected = interval; }});
  const dots = byClass(chart.element, 'sc-chart-hit');
  dots[1].fire('click');
  byClass(chart.element, 'sc-chart-action')[0].fire('click');
  assert.deepEqual(selected, {start: 300, end: 400});
  assert.equal(Number(dots[1].getAttribute('cx')) - Number(dots[0].getAttribute('cx')),
    Number(dots[2].getAttribute('cx')) - Number(dots[1].getAttribute('cx')), '压缩后等间距');
  assert.equal(JSON.stringify(points), before);
});
// 隐藏日期时，缺测不能继续占位或让折线断开，真实零与原日期仍须保留。
await check('隐藏未播与缺测后连续且点选原日期', () => {
  const records = [
    {start: 100, end: 200, label: '10/11', value: 4, status: 'recorded'},
    {start: 200, end: 300, label: '10/12', value: null, status: 'recorded'},
    {start: 300, end: 400, label: '10/13', value: 8, status: 'off'},
    {start: 400, end: 500, label: '10/14', value: 0, status: 'recorded'},
    {start: 500, end: 600, label: '10/15', value: null, status: 'unknown'},
    {start: 600, end: 700, label: '10/16', value: -2, status: 'recorded'},
  ];
  const before = JSON.stringify(records);
  const selected = [];
  const chart = ui.createChart(host(), {points: records, hideOff: true, onSelect: value => selected.push(value)});
  const path = byClass(chart.element, 'sc-line')[0].getAttribute('d');
  assert.equal((path.match(/M/g) || []).length, 1, '隐藏缺测后折线连续');
  const dots = byClass(chart.element, 'sc-chart-hit');
  assert.equal(dots.length, 3, '只保留有效值且排除未播');
  assert.equal(Number(dots[1].getAttribute('cx')) - Number(dots[0].getAttribute('cx')),
    Number(dots[2].getAttribute('cx')) - Number(dots[1].getAttribute('cx')), '缺测不占位且等间距');
  assert.equal(dots[1].getAttribute('cy'), byClass(chart.element, 'sc-zero')[0].getAttribute('y1'), '真实零仍在零线上');
  assert.deepEqual(byClass(chart.element, 'sc-chart-labels')[0].children.map(node => node.textContent), ['10/11', '10/14', '10/16']);
  dots.forEach(dot => { dot.fire('click'); byClass(chart.element, 'sc-chart-action')[0].fire('click'); });
  assert.deepEqual(selected, [{start: 100, end: 200}, {start: 400, end: 500}, {start: 600, end: 700}]);
  assert.equal(JSON.stringify(records), before);
});
// 卡片缩放须重画坐标，否则文字和图形一起挤压。
await check('尺寸变化重画且离页释放', () => {
  const chart = ui.createChart(host(), {points});
  const observer = observers.at(-1);
  const before = byClass(chart.element, 'sc-chart-hit')[2].getAttribute('cx');
  observer.resize(360, 220);
  assert.equal(chart.svg.getAttribute('viewBox'), '0 0 360 220');
  assert.equal(chart.svg.getAttribute('preserveAspectRatio'), 'xMidYMid meet');
  assert.notEqual(byClass(chart.element, 'sc-chart-hit')[2].getAttribute('cx'), before, '横坐标重新计算');
  ui.releaseComponents();
  assert.equal(observer.disconnected, true, '尺寸监听已断开');
});
// 周月柱图的负值要在零线下，缺测不能伪装成有值的柱。
await check('周月柱图与键盘选择', () => {
  for (const period of ['week', 'month']) {
    let selected;
    const chart = ui.createChart(host(), {points, period, onSelect: interval => { selected = interval; }});
    const bars = byClass(chart.element, 'sc-bar');
    assert.equal(bars.length, 3);
    bars[2].fire('keydown', {key: 'Enter'});
    assert.deepEqual(selected, {start: 400, end: 500});
    assert.equal(bars[2].getAttribute('y'), byClass(chart.element, 'sc-zero')[0].getAttribute('y1'));
    bars[0].fire('pointerenter');
    assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('01/01'));
  }
});
// 两根柱的标签本可放下，首尾对齐却会误判重叠、漏掉末根。
for (const period of ['week', 'month']) await check(period + '窄图两根柱都有居中标签', () => {
  const chart = ui.createChart(host(), {period, points: [
    {start: Date.parse('2026-09-17T06:00:00+08:00'), end: Date.parse('2026-10-01T06:00:00+08:00'), value: 1},
    {start: Date.parse('2026-10-01T06:00:00+08:00'), end: Date.parse('2026-10-17T06:00:00+08:00'), value: 2},
  ]});
  observers.at(-1).resize(233, 180);
  const labels = byClass(chart.element, 'sc-chart-labels')[0].children;
  assert.equal(labels.length, 2, '两根柱应各有一个标签');
  const bars = byClass(chart.element, 'sc-bar');
  labels.forEach((label, i) => assert.equal(parseFloat(label.style.left),
    (Number(bars[i].getAttribute('x')) + Number(bars[i].getAttribute('width')) / 2) / 233 * 100));
});
// 周月原标签仍是整周期，显示和下钻必须采用接口已截过的起止。
for (const [period, intervals, expected] of [
  ['week', [['2026-09-17', '2026-09-21', '2026-09-14'], ['2026-10-12', '2026-10-16', '2026-10-12']], ['09/17–09/20', '10/12–10/16']],
  ['month', [['2026-09-17', '2026-10-01', '2026-09-01'], ['2026-10-01', '2026-10-16', '2026-10-01']], ['09/17–09/30', '10/01–10/16']],
]) await check(period + '首尾标签与下钻使用截过的范围', () => {
  const selected = [];
  const records = intervals.map(([start, end, date], i) => ({
    start: Date.parse(start + 'T06:00:00+08:00'),
    end: Date.parse(end + (i ? 'T15:00:00+08:00' : 'T06:00:00+08:00')),
    label: date, date, value: i + 1, sessions: 1,
  }));
  const chart = ui.createChart(host(), {period, points: records, onSelect: value => selected.push(value)});
  assert.deepEqual(byClass(chart.element, 'sc-chart-labels')[0].children.map(node => node.textContent), expected, '标签不能使用整周期起点');
  byClass(chart.element, 'sc-bar').forEach(bar => bar.fire('click'));
  assert.deepEqual(selected, records.map(({start, end}) => ({start, end})), '下钻不能越出截过的范围');
});
// 提示和读屏不能沿用整周的 label，否则会声称包含范围外的日期。
await check('首周柱提示与读屏使用截过的完整日期', () => {
  const chart = ui.createChart(host(), {period: 'week', points: [{
    start: Date.parse('2026-09-17T06:00:00+08:00'), end: Date.parse('2026-09-21T06:00:00+08:00'),
    label: '2026-09-14 ~ 09-20', value: 1,
  }]});
  const bar = byClass(chart.element, 'sc-bar')[0];
  assert.equal(bar.children[0].textContent, '2026-09-17 ~ 09-20 · 1', '悬停提示不能使用整周起点');
  assert.equal(bar.getAttribute('aria-label'), '2026-09-17 ~ 09-20 · 1', '读屏不能使用整周起点');
});
// 先读到选中日期的数值，点击操作按钮才进入场次。
await check('按日点选和左右键只更新读数由按钮下钻', () => {
  const selected = [];
  const chart = ui.createChart(host(), {points, onSelect: value => selected.push(value)});
  const action = byClass(chart.element, 'sc-chart-action')[0];
  assert.ok(action?.disabled, '未选日期时按钮不可用');
  assert.equal(byClass(chart.element, 'sc-chart-readout')[0].textContent, '点击图表选择日期');
  const dots = byClass(chart.element, 'sc-chart-hit');
  dots[0].fire('click');
  assert.equal(selected.length, 0, '一点就跳走，看不到当天数值');
  assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('10/13'));
  dots[0].fire('keydown', {key: 'ArrowRight'});
  assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('10/14'), '左右键应能选择空日');
  dots[0].fire('keydown', {key: 'ArrowRight'});
  assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('10/15'));
  assert.equal(selected.length, 0);
  action.fire('click');
  assert.deepEqual(selected, [{start: 300, end: 400}]);
});
// 键盘选择后应直接到操作按钮，日期点不能增加 Tab 停留次数。
await check('按日图键盘选择后Enter聚焦查看按钮且点不占Tab位', () => {
  const chart = ui.createChart(host(), {points: [{...points[0], sessions: 1}, {...points[2], sessions: 0}], onSelect() {}});
  const action = byClass(chart.element, 'sc-chart-action')[0];
  chart.svg.focus();
  chart.svg.fire('keydown', {key: 'ArrowRight'});
  chart.svg.fire('keydown', {key: 'Enter'});
  assert.ok(document.activeElement === action, '选好日期按Enter应聚焦查看按钮');
  assert.equal(chart.svg.getAttribute('tabindex'), '0');
  for (const dot of byClass(chart.element, 'sc-chart-hit')) assert.notEqual(dot.getAttribute('tabindex'), '0', '日期点不应占Tab位');
  chart.svg.focus();
  chart.svg.fire('keydown', {key: 'ArrowRight'});
  chart.svg.fire('keydown', {key: 'Enter'});
  assert.ok(document.activeElement === chart.svg, '空日按钮不可用时焦点留在图上');
});
// 全空数据只显示空态，不能把粉丝曲线或周月柱图的末期日期当作有效读数。
for (const [name, options] of [
  ['按日图', {hideOff: true, onSelect() {}}],
  ['粉丝图', {}],
  ['周柱图', {period: 'week', onSelect() {}}],
  ['月柱图', {period: 'month', onSelect() {}}],
]) await check(name + '没有有效点时只显示空态', () => {
  const records = [[{...points[0], value: null}], [{...points[0], value: null}, {...points[1], value: null}], []];
  if (options.hideOff) records.push([{...points[0], status: 'off'}]);
  for (const input of records) {
    const chart = ui.createChart(host(), {...options, points: input});
    assert.equal(chart.element.textContent, '暂无有效图表数据', '全空数据只能显示空态');
    assert.equal(byClass(chart.element, 'sc-chart-readout').length, 0, '全空数据不能显示读数行');
    assert.equal(byClass(chart.element, 'sc-chart-action').length, 0, '全空数据不能显示查看按钮');
  }
});
// 没直播的日子仍可读数，但不能打开空场次列表。
for (const period of ['day', 'week', 'month']) await check(({day: '空日按钮不可下钻', week: '空周柱不可下钻', month: '空月柱不可下钻'})[period], () => {
    const selected = [];
    const chart = ui.createChart(host(), {period, points: [{...points[0], sessions: 0}], onSelect: value => selected.push(value)});
    const mark = byClass(chart.element, period === 'day' ? 'sc-chart-hit' : 'sc-bar')[0];
    mark.fire('click'); mark.fire('keydown', {key: 'Enter'});
    if (period === 'day') {
      const action = byClass(chart.element, 'sc-chart-action')[0];
      assert.ok(action?.disabled, '没场次的日子按钮不可用'); action.fire('click');
    }
    assert.equal(selected.length, 0, '空周期不可下钻');
});
const calendar = {provableFrom: 250, items: [
  {date: '2026-10-13', weekday: 2, start: 100, end: 200, status: 'unknown', durationSeconds: 0, effectiveSeconds: 0, gapSeconds: 40, starts: []},
  {date: '2026-10-14', weekday: 3, start: 200, end: 300, status: 'off', durationSeconds: 0, effectiveSeconds: 0, gapSeconds: 0, starts: []},
  {date: '2026-10-15', weekday: 4, start: 300, end: 400, status: 'recorded', durationSeconds: 100, effectiveSeconds: 100, gapSeconds: 0, starts: [310]},
  {date: '2026-10-16', weekday: 5, start: 400, end: 500, status: 'recorded', durationSeconds: 920, effectiveSeconds: 900, gapSeconds: 20, starts: [410, 430], current: true},
]};
// 防止日历显示直播总时长，读数与读屏都应使用扣除缺口后的有效时长。
await check('日历三小时直播半小时断线显示有效2.5小时与覆盖83.33%', () => {
  const node = ui.createCalendar(host(), {items: [{...calendar.items[2],
    durationSeconds: 10800, effectiveSeconds: 9000, gapSeconds: 1800}]});
  assert.ok(byClass(node, 'sc-calendar-readout')[0].textContent.includes('有效 2.5 小时'), '应显示有效 2.5 小时');
  assert.ok(byClass(node, 'sc-calendar-day')[0].getAttribute('aria-label').includes('有效 2.5 小时'), '读屏应显示有效 2.5 小时');
  assert.ok(byClass(node, 'sc-calendar-quality')[0].textContent.includes('缺口 30 分 · 覆盖 83.33% · 仅使用已记录数据'),
    '应显示缺口 30 分 · 覆盖 83.33% · 仅使用已记录数据');
});
// 防止百分比四舍五入到100%，掩盖仍存在的一秒缺口。
await check('日历十小时直播一秒缺口覆盖99.99%不进位', () => {
  const node = ui.createCalendar(host(), {items: [{...calendar.items[2],
    durationSeconds: 36000, effectiveSeconds: 35999, gapSeconds: 1}]});
  const quality = byClass(node, 'sc-calendar-quality')[0].textContent;
  assert.ok(quality.includes('覆盖 99.99%'), '一秒缺口应显示覆盖 99.99%');
  assert.ok(!quality.includes('100%'), '有缺口不能显示100%');
});
// 空日的操作不能只灰掉：必须隐藏且移出 Tab 顺序，也不能打开场次。
await check('日历无场次日期隐藏查看链且不占Tab位', () => {
  const selected = [];
  const node = ui.createCalendar(host(), calendar, {onSelect: value => selected.push(value)});
  const cells = byClass(node, 'sc-calendar-day');
  const action = byClass(node, 'sc-calendar-action')[0];
  for (const cell of cells.slice(0, 2)) {
    cell.focus();
    assert.equal(action.style.visibility, 'hidden', '无场次日期应隐藏查看链并保留位置');
    assert.ok(!action.hidden, '不能用hidden属性移除链的位置');
    assert.equal(action.getAttribute('aria-hidden'), 'true');
    assert.equal(action.tabIndex, -1, '隐藏链不应占Tab位');
    cell.fire('keydown', {key: 'Enter'});
    assert.equal(document.activeElement, cell, '无场次时Enter应留在日期上');
    action.fire('click');
  }
  assert.equal(selected.length, 0, '无场次日期不能下钻');
  cells[2].fire('click');
  assert.equal(action.style.visibility, 'visible', '有场次时恢复查看链');
  assert.equal(action.getAttribute('aria-hidden'), 'false');
  assert.equal(action.tabIndex, 0, '有场次的查看链可用Tab到达');
});
// 没场次时的 gapSeconds 不代表监测完整，说明必须与当天状态一致。
for (const [status, gapSeconds, expected] of [
  ['unknown', 0, '监测或时长证据不足 · 不按 0 计算'],
  ['off', 0, '已完整监测，确认没播'],
  ['recorded', 20, '缺口 20 秒 · 覆盖 80.00% · 仅使用已记录数据'],
  ['recorded', 0, '缺口 0 秒 · 覆盖 100% · 无已知缺口'],
  ['recorded', undefined, '当前暂无缺口说明'],
]) await check('日历缺口说明对应状态' + status + '/' + gapSeconds, () => {
  const node = ui.createCalendar(host(), {items: [{...calendar.items[2], status, gapSeconds,
    effectiveSeconds: 100 - (gapSeconds ?? 0)}]});
  assert.equal(byClass(node, 'sc-calendar-quality')[0].textContent, expected, '缺口说明不能与当天状态矛盾');
});
// 缺口格式须与页顶和抽屉一致，不能显示原始的带千分位秒数。
for (const [seconds, expected] of [[59, '59 秒'], [3599, '59 分 59 秒'], [3725, '1 时 2 分 5 秒']]) {
  await check('日历缺口写法/' + seconds, () => {
    const node = ui.createCalendar(host(), {items: [{...calendar.items[2], gapSeconds: seconds}]});
    assert.ok(byClass(node, 'sc-calendar-quality')[0].textContent.includes('缺口 ' + expected),
      '日历缺口应显示' + expected);
  });
}
// 合计逐一对应三种格子，不能漏掉未知状态或把它算成未播。
await check('日历合计与三种格子数一致', () => {
  const node = ui.createCalendar(host(), calendar);
  const totals = byClass(node, 'sc-calendar-totals')[0];
  assert.equal(totals?.children.length, 3, '缺少三种状态的天数合计');
  for (const status of ['recorded', 'off', 'unknown']) {
    const total = totals.children.find(child => child.dataset.status === status);
    assert.equal(total.children[0].textContent, byClass(node, 'sc-calendar-day').filter(cell => cell.className.split(' ').includes('sc-calendar-' + status)).length + '天');
  }
});
// 记录不足与确认未播必须有不同视觉与读屏提示。
await check('日历三态可辨且有直播使用同色', () => {
  const node = ui.createCalendar(host(), calendar);
  const cells = byClass(node, 'sc-calendar-day');
  assert.ok(cells[0].className.includes('sc-calendar-unknown'));
  assert.ok(cells[1].className.includes('sc-calendar-off'));
  assert.ok(cells[2].className.includes('sc-calendar-recorded'));
  for (const [i, word] of ['说不准', '确认没播', '有直播'].entries()) assert.ok(cells[i].getAttribute('aria-label').includes(word), word);
  assert.equal(cells[2].style['--sc-intensity'], cells[3].style['--sc-intensity']);
  assert.ok(!byClass(node, 'sc-calendar-legend')[0].textContent.includes('此前的记录不全'));
});
// 今天不能与历史日期没有区别。
await check('日历今天有标记', () => {
  const node = ui.createCalendar(host(), calendar);
  const today = byClass(node, 'sc-calendar-day')[3];
  assert.equal(today.getAttribute('aria-current'), 'date');
  assert.ok(today.getAttribute('aria-label').includes('今天'));
});
// 先选日期读数，再用按钮打开所选统计日的场次。
await check('日历点选保留边界与场次', () => {
  const selected = [];
  const node = ui.createCalendar(host(), calendar, {onSelect: value => selected.push(value)});
  const cells = byClass(node, 'sc-calendar-day');
  cells[0].fire('click'); cells[1].fire('click'); cells[3].fire('click');
  assert.equal(selected.length, 0, '点一天直接打开了场次');
  assert.ok(byClass(node, 'sc-calendar-readout')[0].textContent.includes('10/16'));
  byClass(node, 'sc-calendar-action')[0].fire('click');
  assert.deepEqual(selected, [{start: 400, end: 500, starts: [410, 430]}]);
});
// 选中较早的有直播日期后，按钮不能仍打开最后一天的场次。
await check('日历按钮打开选中的非最后一天', () => {
  const selected = [];
  const node = ui.createCalendar(host(), calendar, {onSelect: value => selected.push(value)});
  const cell = byClass(node, 'sc-calendar-day').find(cell => cell.dataset.date === '2026-10-15');
  cell.fire('click');
  assert.equal(selected.length, 0, '选日期时不能直接打开场次');
  assert.ok(byClass(node, 'sc-calendar-readout')[0].textContent.includes('10/15'), '读数应显示选中的日期');
  const action = byClass(node, 'sc-calendar-action')[0];
  assert.equal(action.disabled, false, '选中的日期有场次可看');
  assert.equal(action.textContent, '查看 10/15 场次');
  action.fire('click');
  assert.deepEqual(selected, [{start: 300, end: 400, starts: [310]}], '按钮应传递所选日的 start/end/starts');
});
// 一整张日历只占一个日期 Tab 位，方向键选择后 Enter 到查看按钮。
await check('日历键盘单入口且Enter到所选日按钮', () => {
  const node = ui.createCalendar(host(), calendar, {onSelect() {}});
  const cells = byClass(node, 'sc-calendar-day');
  const scroll = byClass(node, 'sc-calendar-scroll')[0];
  assert.notEqual(scroll.tabIndex, 0, '滚动容器不能另占一个 Tab 位');
  assert.equal(cells.filter(cell => cell.tabIndex === 0).length, 1, '整张日历只能一个日期 Tab 位');
  cells[3].fire('keydown', {key: 'ArrowLeft'});
  assert.equal(document.activeElement, cells[2]);
  assert.equal(cells[2].getAttribute('aria-selected'), 'true');
  cells[2].fire('keydown', {key: 'Enter'});
  assert.equal(document.activeElement, byClass(node, 'sc-calendar-action')[0]);
  cells[2].fire('keydown', {key: 'ArrowLeft'});
  assert.ok(byClass(node, 'sc-calendar-action')[0].disabled, '确认没播的日期不能查看场次');
});
// 读数保留接口的时长、场数和缺口；说不准不能被显示为零或确认未播。
await check('日历读数给出状态原因与缺口', () => {
  const input = {...calendar, items: [...calendar.items,
    {...calendar.items[0], date: '2026-10-17', start: 500, end: 600, gapSeconds: 0}]};
  const node = ui.createCalendar(host(), input);
  const cells = byClass(node, 'sc-calendar-day');
  const text = () => byClass(node, 'sc-calendar-readout')[0].textContent;
  cells[0].fire('click');
  assert.ok(text().includes('此前的记录不全') && text().includes('说不准'));
  assert.ok(byClass(node, 'sc-calendar-quality')[0].textContent.includes('不按 0 计算'));
  cells[1].fire('click');
  assert.ok(text().includes('已监看') && text().includes('没开播'));
  cells[3].fire('click');
  assert.ok(text().includes('2 场') && text().includes('统计截止日，尚未结束'));
  assert.ok(byClass(node, 'sc-calendar-quality')[0].textContent.includes('20 秒'));
  cells[4].fire('click');
  assert.ok(text().includes('停机或断线'), '可证明日期之后仍未知时解释监测中断');
});
// 横轴全日期太长会挤压相邻标签，日和周都必须由组件缩写。
for (const [period, label, date, end, expected] of [
  ['day', '2026-09-11', '2026-09-11', '2026-09-12', '09/11'],
  ['week', '2026-09-14 ~ 09-20', '2026-09-14', '2026-09-21', '09/14–09/20'],
]) await check(({day: '日横轴使用短日期', week: '周横轴使用短日期'})[period], () => {
  const chart = ui.createChart(host(), {period, points: [{
    start: Date.parse(date + 'T06:00:00+08:00'), end: Date.parse(end + 'T06:00:00+08:00'), label, value: 12,
  }]});
  assert.equal(byClass(chart.element, 'sc-chart-labels')[0].textContent, expected, '横轴应使用短日期');
  const mark = byClass(chart.element, period === 'day' ? 'sc-chart-hit' : 'sc-bar')[0];
  assert.ok(mark.getAttribute('aria-label').includes(label), '读屏仍保留完整日期');
});
// 周月柱与粉丝折线初次打开就应有最后周期读数，日期由组件简写。
await check('周月与普通曲线默认显示末期短日期读数', () => {
  const start = Date.parse('2026-10-05T06:00:00+08:00');
  for (const period of ['week', 'month', 'day']) {
    const chart = ui.createChart(host(), {period, points: [{start, end: start + 7 * 86400000, label: '2026-10-05 ~ 10-11', value: 43760.3}], format: value => value + '元'});
    const readout = byClass(chart.element, 'sc-chart-readout')[0].textContent;
    assert.ok(readout.includes(period === 'day' ? '10/05' : '10/05–10/11'), '缺少末期短日期');
    assert.ok(readout.includes('43760.3元'), '缺少末期数值');
  }
});
// 鼠标点击导致的 focus 不能冒充键盘聚焦，使游标在移出以后一直显示。
await check('粉丝图点击后移出隐藏游标且键盘聚焦显示', () => {
  const chart = ui.createChart(host(), {points: points.filter(point => point.value != null)});
  const cursor = byClass(chart.element, 'sc-cursor-line')[0];
  const dot = byClass(chart.element, 'sc-chart-hit')[0];
  const clientX = Number(dot.getAttribute('cx'));
  chart.svg.fire('pointermove', {clientX});
  chart.svg.fire('click', {clientX});
  assert.equal(document.activeElement, chart.svg, '点击确实触发图表focus');
  chart.svg.fire('pointerleave');
  assert.equal(cursor.getAttribute('visibility'), 'hidden', '鼠标移出应隐藏游标');
  chart.svg.fire('blur');
  chart.svg.focus();
  assert.equal(cursor.getAttribute('visibility'), 'visible', '键盘Tab进图后保留游标');
});
// 读数和游标必须预览同一天，离开后恢复所选日期并按焦点决定显隐。
await check('粉丝图悬停同步游标读数且离开恢复选择', () => {
  const chart = ui.createChart(host(), {points: points.filter(point => point.value != null)});
  const dots = byClass(chart.element, 'sc-chart-hit');
  const cursor = byClass(chart.element, 'sc-cursor-line')[0];
  const readout = byClass(chart.element, 'sc-chart-readout')[0];
  assert.equal(cursor.getAttribute('visibility'), 'hidden');
  chart.svg.fire('pointermove', {clientX: Number(dots[1].getAttribute('cx')) + 8});
  assert.equal(cursor.getAttribute('x1'), dots[1].getAttribute('cx'), '悬停游标应移到最近的第2点');
  assert.equal(cursor.getAttribute('x2'), dots[1].getAttribute('cx'));
  assert.equal(cursor.getAttribute('visibility'), 'visible');
  assert.match(readout.textContent, /10\/15.*0/);
  chart.svg.fire('pointerleave');
  assert.match(readout.textContent, /10\/16.*-2/);
  assert.equal(cursor.getAttribute('visibility'), 'hidden');
  chart.svg.focus();
  assert.equal(cursor.getAttribute('x1'), dots.at(-1).getAttribute('cx'));
  assert.equal(cursor.getAttribute('visibility'), 'visible');
  chart.svg.fire('pointermove', {clientX: Number(dots[1].getAttribute('cx'))});
  chart.svg.fire('pointerleave');
  assert.equal(cursor.getAttribute('x1'), dots.at(-1).getAttribute('cx'));
  assert.equal(cursor.getAttribute('visibility'), 'visible', '键盘焦点仍在时显示已选点');
  chart.svg.fire('blur');
  assert.equal(cursor.getAttribute('visibility'), 'hidden');
  chart.svg.fire('pointermove', {clientX: Number(dots[0].getAttribute('cx'))});
  chart.svg.focus(); chart.svg.fire('blur');
  assert.equal(cursor.getAttribute('visibility'), 'visible', '指针仍在时保留游标');
  chart.svg.fire('pointerleave');
  chart.svg.fire('click', {clientX: Number(dots[0].getAttribute('cx'))});
  chart.svg.fire('keydown', {key: 'ArrowRight'});
  assert.equal(cursor.getAttribute('x1'), dots[1].getAttribute('cx'), '键盘改选后移动游标');
  chart.svg.fire('pointermove', {clientX: Number(dots.at(-1).getAttribute('cx'))});
  chart.svg.fire('pointerleave');
  assert.match(readout.textContent, /10\/15.*0/, '恢复后来选中的日期');
});
// Esc 关闭后键盘操作必须从原按钮继续。
await check('抽屉Esc关闭恢复焦点', () => {
  const opener = new Element('button'); document.body.append(opener); opener.focus();
  const drawer = ui.openDrawer({title: '趋势明细'});
  assert.equal(drawer.dialog.tagName, 'dialog');
  assert.equal(drawer.dialog.open, true);
  assert.ok(drawer.dialog.getAttribute('aria-labelledby'));
  assert.notEqual(document.activeElement, opener);
  drawer.dialog.fire('cancel');
  assert.equal(drawer.dialog.open, false);
  assert.equal(document.activeElement, opener, '焦点回到打开元素');
  assert.equal(drawer.dialog.isConnected, false);
});
// 多次打开不能叠两层；页面离开应撤掉抽屉并恢复滚动。
await check('抽屉唯一且关闭按钮可用', () => {
  document.body.style.overflow = 'auto';
  const first = ui.openDrawer({title: '第一项'});
  const second = ui.openDrawer({title: '第二项'});
  assert.equal(first.dialog.open, false);
  assert.equal(first.dialog.isConnected, false);
  assert.equal(document.body.style.overflow, 'hidden');
  byClass(second.dialog, 'sc-drawer-close')[0].fire('click');
  assert.equal(document.body.style.overflow, 'auto');
  const third = ui.openDrawer({title: '第三项'});
  ui.releaseComponents();
  assert.equal(third.dialog.isConnected, false);
});
// 构造函数只显示给定数值与对比文字，不得把未知数改为零。
await check('卡片指标与观众环保留输入', () => {
  const parent = host();
  const card = ui.createCard('概览', parent);
  const section = ui.createSection('本期表现', '指定范围', card);
  const kpis = ui.createKpis([{label: '时长', value: '2小时', comparison: '较上期 +10%', direction: 'up'},
    {label: '流水', value: null, comparison: '较上期不可用', direction: 'neutral'}], section.box);
  assert.ok(kpis.textContent.includes('较上期 +10%'));
  assert.ok(byClass(kpis, 'sc-up').length);
  assert.ok(kpis.textContent.includes('—'));
  const ring = ui.createAudienceRing({first: 3, returning: 1, total: 4}, card);
  assert.ok(ring.textContent.includes('首次记录'));
  assert.ok(ring.textContent.includes('回访'));
  const arcs = byClass(ring, 'sc-audience-arc');
  assert.equal(arcs.length, 2);
  assert.ok(Number(arcs[0].getAttribute('stroke-dasharray').split(' ')[0]) > Number(arcs[1].getAttribute('stroke-dasharray').split(' ')[0]));
});

// 观众数四舍五入进到一亿时应换单位，悬停仍读完整人数。
await check('观众环进位后选择单位且保留完整人数', () => {
  for (const [total, expected] of [[99949999, '9995.0 万'], [99950000, '1.0 亿'],
    [99999999, '1.0 亿'], [100000000, '1.0 亿'], [10000, '1.0 万'], [9999, '9999']]) {
    const ring = ui.createAudienceRing({first: total, returning: 0, total}, host());
    const count = byClass(ring, 'sc-audience-label')[0].children[1];
    assert.equal(count.textContent, expected + '人', String(total));
    assert.equal(count.title, String(total) + ' 人');
  }
});

// 低于一万的中间刻度不能突然换成原数，读数不受刻度简写影响。
await check('最大刻度17071时纵轴非零刻度统一为万', () => {
  const chart = ui.createChart(host(), {points: [{start: 100, end: 200, value: 17071}]});
  const labels = byClass(chart.element, 'sc-chart-scale')[0].children.map(n => n.textContent);
  assert.equal(labels.length, 4);
  assert.ok(labels.every(text => text === '0' || /^\d+\.\d万$/.test(text)), '纵轴单位须一致：' + labels.join('/'));
  assert.ok(labels.includes('0.6万'), '低于一万的刻度也须用万');
});
// 万单位仍须保留千分位、去掉无用小数，并把接近零的刻度显示为零。
for (const [name, values, expected] of [
  ['大数保留千分位', [12345678], ['1,234.6万', '823万', '411.5万', '0']],
  ['整万不补小数', [10000], ['1万', '0.7万', '0.3万', '0']],
  ['零附近不显示负零', [10000, -100], ['1万', '0.7万', '0.3万', '0']],
]) await check('纵轴万单位边界：' + name, () => {
  const chart = ui.createChart(host(), {points: values.map((value, i) => ({start: 100 + i * 100, end: 200 + i * 100, value}))});
  const labels = byClass(chart.element, 'sc-chart-scale')[0].children.map(n => n.textContent);
  assert.deepEqual(labels, expected, '刻度写法须一致：' + labels.join('/'));
});
// 格子的辅助标签与可见读数共享小时精度，小于六分钟时秒数须留空格。
await check('日历格读屏标签与读数使用相同有效时长', () => {
  for (const [seconds, expected] of [[30959, '8.6 小时'], [300, '300 秒']]) {
    const node = ui.createCalendar(host(), {items: [{date: '2026-10-15', weekday: 4, start: 100, end: 200,
      status: 'recorded', effectiveSeconds: seconds, durationSeconds: seconds, gapSeconds: 0, starts: [100]}]});
    const cell = byClass(node, 'sc-calendar-day')[0]; cell.fire('click');
    console.log('CALENDAR_SAMPLE: ' + cell.getAttribute('aria-label'));
    assert.ok(cell.getAttribute('aria-label').includes('有效 ' + expected), '读屏标签须与读数同为有效 ' + expected);
    assert.equal(cell.title, cell.getAttribute('aria-label'), '悬停与读屏标签须一致');
    assert.ok(byClass(node, 'sc-calendar-readout')[0].textContent.includes('有效 ' + expected));
  }
});
// 零秒归档没有覆盖率分母，缺口行须省略整段覆盖说明。
await check('零秒归档的日历缺口行省略覆盖', () => {
  const node = ui.createCalendar(host(), {items: [{date: '2026-10-15', weekday: 4, start: 100, end: 200,
    status: 'recorded', durationSeconds: 0, effectiveSeconds: 0, gapSeconds: 0, starts: [100]}]});
  byClass(node, 'sc-calendar-day')[0].fire('click');
  const text = byClass(node, 'sc-calendar-quality')[0].textContent;
  assert.ok(!text.includes('覆盖'), '零秒缺口行不得显示覆盖：' + text);
  assert.ok(text.includes('缺口 0 秒') && text.includes('无已知缺口'), '其余缺口说明须保留');
});

process.stdout.write(`跑了 ${checks} 格，失败 ${failures} 格\n`);
process.exitCode = failures ? 1 : 0;
