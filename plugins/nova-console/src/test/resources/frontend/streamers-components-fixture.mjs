import assert from 'node:assert/strict';

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
  getBoundingClientRect() { return this.rect; }
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

// 缺测不能变成零点，否则曲线会凭空跌到零；真实的零必须保留。
await check('缺测断线且真实零保留', () => {
  const chart = ui.createChart(host(), {points});
  const dots = byClass(chart.element, 'sc-point');
  assert.equal(dots.length, 3, '有效点数量');
  assert.equal(byClass(chart.element, 'sc-line')[0].getAttribute('d').match(/M/g).length, 2, '缺测处分段');
  const zero = byClass(chart.element, 'sc-zero')[0];
  assert.equal(dots[1].getAttribute('cy'), zero.getAttribute('y1'), '真实零在零线上');
  assert.ok(Number(dots[2].getAttribute('cy')) > Number(dots[1].getAttribute('cy')), '负值位于零线下');
  for (const numbers of [[2, 4], [-2, -4], [0, 0]]) {
    const one = ui.createChart(host(), {points: numbers.map((value, i) => ({start: i, end: i + 1, value}))});
    const baseline = Number(byClass(one.element, 'sc-zero')[0].getAttribute('y1'));
    assert.ok(baseline >= 14 && baseline <= 154, '零线在画布内');
    for (const point of byClass(one.element, 'sc-point')) {
      const coordinate = Number(point.getAttribute('cy'));
      assert.ok(coordinate >= 14 && coordinate <= 154, '数值在画布内');
    }
  }
});
// 日期轴压缩后，点选仍须钻取原始日期，且不得修改调用方数组。
await check('隐藏未播后点选原日期', () => {
  let selected;
  const before = JSON.stringify(points);
  const chart = ui.createChart(host(), {points, hideOff: true, onSelect: interval => { selected = interval; }});
  const dots = byClass(chart.element, 'sc-point');
  dots[1].fire('click');
  assert.deepEqual(selected, {start: 300, end: 400});
  assert.equal(Number(dots[1].getAttribute('cx')) - Number(dots[0].getAttribute('cx')),
    Number(dots[2].getAttribute('cx')) - Number(dots[1].getAttribute('cx')), '压缩后等间距');
  assert.equal(JSON.stringify(points), before);
});
// 卡片缩放须重画坐标，否则文字和图形一起挤压。
await check('尺寸变化重画且离页释放', () => {
  const chart = ui.createChart(host(), {points});
  const observer = observers.at(-1);
  const before = byClass(chart.element, 'sc-point')[2].getAttribute('cx');
  observer.resize(360, 220);
  assert.equal(chart.svg.getAttribute('viewBox'), '0 0 360 220');
  assert.equal(chart.svg.getAttribute('preserveAspectRatio'), 'xMidYMid meet');
  assert.notEqual(byClass(chart.element, 'sc-point')[2].getAttribute('cx'), before, '横坐标重新计算');
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
    assert.ok(byClass(chart.element, 'sc-chart-readout')[0].textContent.includes('10/13'));
  }
});
const calendar = {provableFrom: 250, items: [
  {date: '2026-10-13', weekday: 2, start: 100, end: 200, status: 'unknown', effectiveSeconds: 0, gapSeconds: 40, starts: []},
  {date: '2026-10-14', weekday: 3, start: 200, end: 300, status: 'off', effectiveSeconds: 0, gapSeconds: 0, starts: []},
  {date: '2026-10-15', weekday: 4, start: 300, end: 400, status: 'recorded', effectiveSeconds: 100, gapSeconds: 0, starts: [310]},
  {date: '2026-10-16', weekday: 5, start: 400, end: 500, status: 'recorded', effectiveSeconds: 900, gapSeconds: 20, starts: [410, 430], current: true},
]};
// 记录不足与确认未播必须有不同视觉与读屏提示。
await check('日历三态可辨且时长有深浅', () => {
  const node = ui.createCalendar(host(), calendar);
  const cells = byClass(node, 'sc-calendar-day');
  assert.ok(cells[0].className.includes('sc-calendar-unknown'));
  assert.ok(cells[1].className.includes('sc-calendar-off'));
  assert.ok(cells[2].className.includes('sc-calendar-recorded'));
  for (const [i, word] of ['说不准', '确认没播', '有直播'].entries()) assert.ok(cells[i].getAttribute('aria-label').includes(word), word);
  assert.notEqual(cells[2].style['--sc-intensity'], cells[3].style['--sc-intensity']);
  assert.ok(node.textContent.includes('此前的记录不全，只能显示说不准'));
});
// 今天不能与历史日期没有区别。
await check('日历今天有标记', () => {
  const node = ui.createCalendar(host(), calendar);
  const today = byClass(node, 'sc-calendar-day')[3];
  assert.equal(today.getAttribute('aria-current'), 'date');
  assert.ok(today.textContent.includes('今天'));
});
// 只有有直播的日期可打开明细，并原样带回统计日起止和场次。
await check('日历点选保留边界与场次', () => {
  const selected = [];
  const node = ui.createCalendar(host(), calendar, {onSelect: value => selected.push(value)});
  const cells = byClass(node, 'sc-calendar-day');
  cells[0].fire('click'); cells[1].fire('click'); cells[3].fire('click');
  assert.deepEqual(selected, [{start: 400, end: 500, starts: [410, 430]}]);
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
process.stdout.write(`跑了 ${checks} 格，失败 ${failures} 格\n`);
process.exitCode = failures ? 1 : 0;
