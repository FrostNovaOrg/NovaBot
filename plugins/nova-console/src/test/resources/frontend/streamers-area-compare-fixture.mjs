import {enhance, installCharts} from './streamers-dom.mjs';
/**
 * 开播节奏抽屉里的「直播分区对比」表
 *
 * 用户那头的故障：整区都没有收入记录时，表里把未知当 0 写成「0.00 元」；
 * 点分区名下钻时带错场次，进的是别的分区的场。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，主播页的建页、地址解析、取详情、画开播节奏
 * 抽屉走的都是产品码。一张表两行——一行有流水、一行整区无收入记录——
 * 逐格比对写法；分区链接解析出来的场次与来源名也要对上。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。由 StreamersAreaCompareTest 拉起。
 */

import {join, dirname} from 'node:path';
import {register} from 'node:module';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const pages = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');

const failures = [];
let checks = 0;

async function ask(name, fn) {
  checks++;
  try {
    await fn();
  } catch (err) {
    failures.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) {
    throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
  }
}

// —— 假 DOM：innerHTML 只是摆着的字符串，链接从字符串里读 ——

function fakeElement(tag) {
  const node = {
    tagName: String(tag).toLowerCase(),
    className: '', id: '', type: '', textContent: '', href: '', title: '',
    hidden: false, disabled: false,
    attrs: {}, dataset: {}, style: {}, scrollLeft: 0,
    children: [], listeners: {}, parentNode: null,
  };
  node.appendChild = child => {
    node.children.push(child);
    child.parentNode = node;
    return child;
  };
  node.insertBefore = child => node.appendChild(child);
  let html = '';
  Object.defineProperty(node, 'innerHTML', {
    get: () => html,
    set: value => { html = String(value); node.children = []; },
  });
  Object.defineProperty(node, 'firstChild', {get: () => node.children[0] || null});
  node.remove = () => {
    if (node.parentNode) node.parentNode.children = node.parentNode.children.filter(child => child !== node);
  };
  node.addEventListener = (kind, fn) => {
    (node.listeners[kind] = node.listeners[kind] || []).push(fn);
  };
  node.setAttribute = (key, value) => { node.attrs[key] = String(value); };
  node.getAttribute = key => node.attrs[key];
  node.querySelector = selector => walk(node).slice(1).find(one =>
    selector.startsWith('.') && one.className.split(' ').includes(selector.slice(1))) || null;
  node.classList = {toggle() {}, add() {}, remove() {}, contains() { return false; }};
  return enhance(node);
}

const byId = {};
const node = id => byId[id] || (byId[id] = fakeElement('div'));
const walk = root => [root, ...root.children.flatMap(walk)];
const find = id => [...Object.values(byId), document.body].flatMap(walk).find(one => one.id === id) || byId[id] || null;
const documentListeners = {};
const intervals = new Map();
let nextInterval = 0;
globalThis.setInterval = (fn, ms) => { intervals.set(++nextInterval, {fn, ms}); return nextInterval; };
globalThis.clearInterval = id => intervals.delete(id);

globalThis.document = {
  querySelector: sel => {
    const id = String(sel).replace(/^#/, '');
    return id === 'sd-banner' ? null : (['sd-live', 'sd-insights-content', 'sd-trend-content', 'sd-archive-note', 'sd-recent', 'sd-audience', 'sd-toolbar', 'sd-scope', 'sd-trend', 'sd-insights', 'sd-calendar', 'sd-audience-summary'].includes(id) ? find(id) : node(id));
  },
  getElementById: id => byId[id] || null,
  createElement: fakeElement,
  createElementNS: (ns, tag) => fakeElement(tag),
  createTextNode: text => Object.assign(fakeElement('#text'), {textContent: String(text)}),
  head: fakeElement('head'),
  body: fakeElement('body'),
  documentElement: {classList: {toggle() {}, contains() { return false; }}},
  hidden: false,
  addEventListener(kind, fn) { (documentListeners[kind] ||= []).push(fn); },
  activeElement: null,
};
installCharts(document);
globalThis.location = {hash: ''};

const PLATFORM = 'bilibili';
const UID = 3493;
// 两个分区：前两场归「娱乐 · 视频聊天」且有流水，后一场归「未记录分区」且整区无收入记录
const AREA_KNOWN = '娱乐 · 视频聊天';
const STARTS_KNOWN = [1760000000000, 1759900000000];
const STARTS_UNKNOWN = [1759800000000];
const AREAS = [
  {area: AREA_KNOWN, sessions: 2, effectiveSeconds: 7200, revenue: 120, revenuePerHour: 60,
    starts: STARTS_KNOWN},
  {area: '未记录分区', sessions: 1, effectiveSeconds: 1800, revenue: null, revenuePerHour: null,
    starts: STARTS_UNKNOWN},
];
const SLOTS_FROM = 1759000000000;
const SLOTS_TO = 1760100000000;

const sessions = [...STARTS_KNOWN, ...STARTS_UNKNOWN].map((start, i) => ({
  platform: PLATFORM, uid: UID, startTime: start, endTime: start + 3600000,
  durationSeconds: 3600, title: '第 ' + (i + 1) + ' 场', metrics: {danmu_count: 200, gift_value: 100},
  interrupted: false,
}));

const slotCells = Array.from({length: 84}, () => ({weekday: 1, startHour: 0, sessions: 0,
  revenuePerHour: null, messagesPerHour: null, sessionItems: []}));
const slotData = {from: SLOTS_FROM, to: SLOTS_TO, cells: slotCells,
  topRevenue: [], topMessages: []};

let slotRequest = null;
let audienceKnown = true;
const audienceItems = [
  {name: 'current', firstSeen: SLOTS_FROM + 1, attendance: 1},
  {name: 'boundary', firstSeen: SLOTS_FROM, attendance: 1},
  {name: 'returning', firstSeen: SLOTS_FROM - 1, attendance: 1},
];

const reply = data => Promise.resolve({status: 200, json: () => Promise.resolve(data)});
globalThis.fetch = url => {
  const path = String(url);
  if (path.includes('/sessions?')) {
    const starts = new URLSearchParams(path.split('?')[1] || '').get('starts');
    const wanted = starts ? starts.split(',').map(Number) : null;
    const items = wanted ? sessions.filter(one => wanted.includes(one.startTime)) : sessions;
    return reply({success: true, sessions: {items, messageKey: 'danmu_count', revenueKeys: ['gift_value'],
      metrics: [{key: 'danmu_count', name: '弹幕', unit: '条'}, {key: 'gift_value', name: '礼物价值', unit: '元', money: true}],
      total: items.length, page: 1, pages: 1}});
  }
  if (path.includes('/start-slots?')) {
    if (slotRequest) return slotRequest();
    return reply({success: true, startSlots: slotData, areas: AREAS});
  }
  if (path.includes('/calendar?')) return reply({success: true, calendar: {items: []}});
  if (path.includes('/audience?')) {
    const group = new URLSearchParams(path.split('?')[1]).get('group') || 'all';
    return reply({success: true, audience: {from: SLOTS_FROM, to: SLOTS_TO, group, sort: 'attendance',
      counts: {all: 3, first: audienceKnown ? 2 : null, returning: audienceKnown ? 1 : null},
      items: audienceItems, total: 3, page: 1, pages: 1}});
  }
  if (path.includes('/live?')) return reply({success: true, living: false, metrics: {}, series: {}});
  if (path === '/config/api/streamers') return reply({streamers: [
    {platform: PLATFORM, uid: UID, uname: '甲', status: 'OFFLINE', living: false, series: []},
  ], days: 7});
  if (path.startsWith('/config/api/streamers/' + PLATFORM + '/' + UID)) {
    return reply({success: true, platform: PLATFORM, uid: UID, uname: '甲', configured: true,
      living: false, overview: {},
      liveMetrics: [{key: 'danmu_count', name: '弹幕', unit: '条'}, {key: 'gift_value', name: '礼物价值', unit: '元', money: true}],
      sessions: {items: sessions, messageKey: 'danmu_count', metrics: [
        {key: 'danmu_count', name: '弹幕', unit: '条'},
        {key: 'gift_value', name: '礼物价值', unit: '元', money: true},
      ], total: sessions.length, page: 1, pageSize: 20}});
  }
  return reply({});
};

const drawerBody = () => walk(document.body).find(n => n.className === 'sc-drawer-body');
const areaTable = () => walk(drawerBody() || {}).find(n => n.className === 'sd-area-table');
const areaRows = () => {
  const table = areaTable();
  if (!table) return null;
  const body = table.children.find(n => n.tagName === 'tbody');
  return body ? body.children : null;
};
// 一格里的字：自己有 textContent 就用它，没有就取子孙的
const cellText = cell => {
  const own = String(cell.textContent || '').trim();
  if (own) return own;
  return cell.children.map(cellText).filter(Boolean).join('');
};
const rowTexts = row => row.children.map(cellText);

async function openSlots() {
  location.hash = model.detailHash(PLATFORM, UID, 'start-slots');
  await page.loadStreamers();
}

let page = null;
let model = null;
try {
  model = await import(join(pages, 'streamers-model.js'));
  page = await import(join(pages, 'streamers.js'));
  page.render(fakeElement('section'));
  globalThis.location.hash = model.detailHash(PLATFORM, UID, 'start-slots');
  await page.loadStreamers();
} catch (err) {
  failures.push('载入主播页或画开播节奏抽屉就抛了：' + err.message);
}

// 故障：整区都没有收入记录时把未知当 0 写成「0.00 元」，或把有记录的行写糊
await ask('① 流水未知写「—」，有记录的按两位小数与单位写', async () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  await openSlots();
  const rows = areaRows();
  if (!rows) throw new Error('没有分区对比表');
  same(rows.length, AREAS.length, '行数');
  same(rows.map(rowTexts), [
    [AREA_KNOWN, '2', '2 时 0 分', '120.00 元', '60.00 元/时'],
    ['未记录分区', '1', '30 分', '—', '—'],
  ], '两行各格');
});

// 故障：点进去的不是这个分区的场，或来源不写分区名
await ask('② 分区链接带本区场次，来源写分区名', async () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  await openSlots();
  const rows = areaRows();
  if (!rows) throw new Error('没有分区对比表');
  const link = walk(rows[0]).find(n => n.tagName === 'a');
  if (!link) throw new Error('分区名不是链接');
  const parsed = model.parseStreamersHash(link.href);
  same(parsed.starts, STARTS_KNOWN.join(','), '链接带的场次');
  same(parsed.source, 'area', '链接的来源标');
  same(parsed.sourceArea, AREA_KNOWN, '链接带的分区名');
  location.hash = link.href;
  await page.loadStreamers();
  const source = walk(drawerBody()).find(n => String(n.textContent || '').startsWith('筛选来源'));
  same(source?.textContent, '筛选来源：分区对比 · ' + AREA_KNOWN + ' · 2 个场次', '来源行');
});

// 抓住其他来源误用地址里的分区名，覆盖日期说明的情况。
await ask('③ 非分区来源不使用sourceArea', async () => {
  location.hash = model.detailHash(PLATFORM, UID, 'overview',
    'panel=sessions&source=calendar&sourceDay=2026-10-15&sourceArea=other&starts=' + STARTS_KNOWN.join(','));
  await page.loadStreamers();
  const source = walk(drawerBody()).find(n => String(n.textContent || '').startsWith('筛选来源'));
  same(source?.textContent.includes('other'), false, '日历来源不得混入分区名');
  same(source?.textContent.includes('2026/10/15'), true, '日历来源保留统计日');
});

// 抓住清除筛选后仍把旧分区名写进地址的情况。
await ask('④ 清除分区筛选后地址没有sourceArea并保留hideOff', async () => {
  location.hash = model.detailHash(PLATFORM, UID, 'overview',
    'panel=sessions&source=area&sourceArea=other&hideOff=1&starts=' + STARTS_KNOWN.join(','));
  await page.loadStreamers();
  const clear = walk(drawerBody()).find(n => n.textContent === '清除筛选');
  const params = new URLSearchParams(clear.href.split('?')[1]);
  for (const key of ['sourceArea', 'source', 'starts', 'from', 'to']) {
    same(params.has(key), false, '清除后地址不保留' + key);
  }
  same(params.get('hideOff'), '1', '清除筛选保留隐藏设置');
  location.hash = clear.href;
  await page.loadStreamers();
  same(walk(drawerBody()).some(n => String(n.textContent || '').startsWith('筛选来源')), false, '清除后没有来源行');
});

// 首次时刻与本期起点比较，构成未知时必须与分组块一起写未知。
for (const known of [true, false]) await ask('本期构成与分组一致/' + (known ? 'known' : 'unknown'), async () => {
  audienceKnown = known;
  location.hash = model.detailHash(PLATFORM, UID, 'audience');
  await page.loadStreamers();
  const table = walk(drawerBody()).find(n => n.tagName === 'table');
  const headers = table.children.find(n => n.tagName === 'thead').children[0].children.map(cellText);
  same(headers[1], '本期构成', '昵称后一列');
  const rows = table.children.find(n => n.tagName === 'tbody').children.filter(n => !n.hidden);
  same(rows.map(row => cellText(row.children[1])), known ? ['本期首次记录', '本期首次记录', '历史回访'] : ['—', '—', '—'], '逐人构成');
  const groups = walk(drawerBody()).filter(n => n.className === 'sd-audience-group');
  same(groups.slice(1, 3).map(n => cellText(n.children[1])), known ? ['2', '1'] : ['—', '—'], '分组人数');
});

// 请求返回前检查卡片，防止先把读取中当成空结果。
await ask('分区切范围读取中显示读取提示', async () => {
  await openSlots();
  let resolveResponse, requestStarted, requestTimer;
  const started = new Promise((resolve, reject) => {
    requestStarted = resolve;
    requestTimer = setTimeout(() => reject(new Error('未发出分区读取请求')), 10000);
  });
  slotRequest = () => { requestStarted(); return new Promise(resolve => { resolveResponse = resolve; }); };
  location.hash = model.detailHash(PLATFORM, UID, 'start-slots', 'slotDays=180');
  const loading = page.loadStreamers();
  try {
    await started;
    const card = walk(drawerBody()).find(n => n.className.split(' ').includes('sd-area-compare'));
    const message = cellText(card);
    same(message.includes('正在读取归档数据'), true, '分区卡读取提示');
    same(message.includes('所选范围无'), false, '读取中不应显示无数据');
  } finally {
    clearTimeout(requestTimer);
    resolveResponse?.(await reply({success: true, startSlots: slotData, areas: AREAS}));
    slotRequest = null;
    await loading;
  }
});

// 折叠条与页顶使用相同的时分秒，防止原始秒数和千分位混入。
for (const [seconds, expected] of [[59, '59 秒'], [3599, '59 分 59 秒'], [3725, '1 时 2 分 5 秒']]) {
  await ask('已记录数据缺口写法/' + seconds, async () => {
    slotData.capture = {gapSeconds: seconds, gapSessions: 1};
    slotData.sessions = 3;
    await openSlots();
    const recording = walk(drawerBody()).find(n => n.className === 'sd-recording');
    const line = walk(recording).find(n => n.tagName === 'li' && n.textContent.startsWith('缺口合计'));
    same(line?.textContent.split(' · ')[0], '缺口合计 ' + expected, '折叠条缺口');
  });
}

page?.leave();

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
