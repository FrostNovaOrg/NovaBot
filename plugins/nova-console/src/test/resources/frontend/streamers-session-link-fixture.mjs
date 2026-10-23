import {enhance, installCharts} from './streamers-dom.mjs';
/**
 * 主播详情的场次列表：每一行都有一个指向这一场的链接
 *
 * 用户那头的故障：场次表只有鼠标点得开——整行挂着点击，行里全是文字格，没有链接、
 * 没有 tabindex。只用键盘的人按 Tab 会把全部场次跳过去，哪一场的报告都打不开。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，主播页的建页、地址解析、取详情、画场次页签走的都是产品码。
 * 画出来的每一行，在它的那几格里找 <a href>，地址要等于主播页拼某一场地址的那一份
 * （streamers-model.js 的 sessionHash）算出来的。整行点照旧跳到同一处，是对照格。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。由 StreamersSessionLinkTest 拉起。
 */

import {readFileSync} from 'node:fs';
import {register} from 'node:module';
import {dirname, join} from 'node:path';
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

const rows = [];

function fakeElement(tag) {
  const node = {
    tagName: String(tag).toLowerCase(),
    className: '', id: '', type: '', textContent: '', innerHTML: '', href: '', title: '',
    hidden: false, disabled: false,
    attrs: {}, dataset: {}, style: {}, scrollLeft: 0,
    children: [], listeners: {}, parentNode: null,
  };
  node.appendChild = child => {
    node.children.push(child);
    if (child.className === 'sd-session-row') rows.push(child);
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
const STARTS = [1760000000000, 1759900000000, 1759800000000, 1759700000000, 1759600000000];

const sessions = STARTS.map((start, i) => ({
  platform: PLATFORM, uid: UID, startTime: start, endTime: start + 3600000,
  durationSeconds: 3600, title: '第 ' + (i + 1) + ' 场', metrics: {danmu_count: 200, gift_value: 100}, interrupted: false,
}));

let liveFailure = '';
let liveHttpReply = null;
let configured = true;
let liveRequests = 0;
let pendingLive = null;
const dataAt = Date.parse('2026-10-14T12:34:00+08:00');
const checkedAt = Date.parse('2026-10-14T12:35:07+08:00');
let liveData = {
  success: true, living: true, since: checkedAt - 3600000, dataAt, checkedAt, revenue: 12.5, messages: 123,
  metrics: {danmu_count: 123, gift_value: 12.5, unknown_metric: 9, online_count: 999, online_rank_count: 3,
    session_extra: 7},
  series: {online_count: [[dataAt - 60000, 50], [dataAt, 42]]},
};
let dashboardFixtures = false;
let insightsFailure = false;
let historyStart = null;
let cutoff = Date.parse('2026-10-16T08:40:00+08:00');
const rangeStart = days => Date.parse('2026-10-16T06:00:00+08:00') - (days - 1) * 86400000;
const archiveItems = ['2026-10-15T10:00', '2026-09-17T07:00', '2026-09-17T05:30', '2026-08-01T10:00'].map(date => {
  const startTime = Date.parse(date + ':00+08:00');
  return {...sessions[0], startTime, endTime: startTime + 3600000};
});
// 范围起点的零秒归档按开播统计日计入，不能被重叠条件漏掉。
archiveItems.push({...sessions[0], startTime: rangeStart(30), endTime: rangeStart(30), durationSeconds: 0});
let trendBuckets = [];
const slotRequests = [];
let pendingSlots = null;
let emptySlots = false;
const slotCells = Array.from({length: 84}, (_, i) => ({weekday: Math.floor(i / 12) + 1,
  startHour: i % 12 * 2, sessions: i === 11 ? 3 : 0, revenuePerHour: i === 11 ? 50 : null,
  messagesPerHour: i === 11 ? 100 : null,
  sessionItems: i === 11 ? [{startTime: STARTS[0], durationSeconds: 3600, revenue: 100, messages: 200}] : [],
}));
const slotData = {cells: slotCells,
  topRevenue: [{weekday: 1, startHour: 22, sessions: 3, value: 50}],
  topMessages: [{weekday: 1, startHour: 22, sessions: 3, value: 100}]};
// 使用目录里的实际名称和单位，让遗漏名称也能在卡片断言中暴露。
const catalogSource = readFileSync(new URL('../../../../../nova-report/src/main/java/org/frostnova/nova/report/analytics/BilibiliLiveMetricCatalog.java', import.meta.url), 'utf8');
const extraLiveMetrics = [...catalogSource.matchAll(/Metric\.count\(BilibiliLiveMetric\.(GUARD_OPEN_COUNT|GUARD_RENEW_COUNT|IMAGE_DEGRADED_COUNT), "([^"]+)", "([^"]+)"\)/g)]
  .map(([, key, name, unit]) => ({key: key.toLowerCase(), name, unit}));
const reply = data => Promise.resolve({status: 200, json: () => Promise.resolve(data)});
globalThis.fetch = url => {
  const path = String(url);
  const params = new URL(path, 'https://example.test').searchParams;
  if (dashboardFixtures && path.includes('/insights?')) {
    if (insightsFailure) return reply({success: false, message: '暂不可用'});
    const from = rangeStart(Number(params.get('days')));
    return reply({success: true, insights: {from, to: cutoff,
      sessions: archiveItems.filter(s => s.startTime >= from && s.startTime < cutoff).length,
      daily: [], coverage: {}}});
  }
  if (dashboardFixtures && path.includes('/trend?')) return reply({success: true, trend: {buckets: trendBuckets}});
  if (dashboardFixtures && path.includes('/fans?')) return reply({success: true, fans: {points: [{at: cutoff, value: 17053}]}});
  if (dashboardFixtures && path.includes('/sessions?')) {
    const items = archiveItems.filter(s => s.startTime < Number(params.get('to') || Infinity)
      && s.endTime > Number(params.get('from') || 0)
      && s.startTime >= Number(params.get('dateFrom') || 0)
      && s.startTime < Number(params.get('dateTo') || Infinity));
    return reply({success: true, sessions: {items, total: items.length, page: 1, pages: 1}});
  }
  if (path.includes('/sessions?')) return reply({success: true, sessions: {items: sessions, messageKey: 'danmu_count', revenueKeys: ['gift_value'], metrics: [{key: 'danmu_count', name: '弹幕', unit: '条'}, {key: 'gift_value', name: '礼物价值', unit: '元', money: true}], total: 3, page: 1, pages: 1}});
  if (path.includes('/calendar?')) return reply({success: true, calendar: {items: []}});
  if (path.includes('/audience?')) return reply({success: true, audience: {counts: {}, items: []}});
  if (path.includes('/start-slots?')) {
    slotRequests.push(path);
    if (pendingSlots) return new Promise(done => { pendingSlots.resolve = data => done({status: 200, json: async () => data}); });
    return reply({success: true, startSlots: emptySlots ? {...slotData, topRevenue: [], topMessages: []} : slotData});
  }
  if (path.includes('/live?')) {
    liveRequests++;
    if (liveHttpReply) return Promise.resolve({status: liveHttpReply.status, json: async () => liveHttpReply.body});
    if (pendingLive) return new Promise(done => { pendingLive.resolve = data => done({status: 200, json: async () => data}); });
    return liveFailure === '网络错误' ? Promise.reject(new Error(liveFailure))
      : reply(liveFailure ? {success: false, message: liveFailure} : liveData);
  }
  if (path === '/config/api/streamers') return reply({streamers: [
    {platform: PLATFORM, uid: UID, uname: '甲', status: 'LIVE', living: true, series: []},
    {platform: PLATFORM, uid: UID + 1, uname: '乙', status: 'OFFLINE', series: []},
  ], days: 7});
  if (path.startsWith('/config/api/streamers/' + PLATFORM + '/' + UID)) {
    return Promise.resolve({status: 200, json: () => Promise.resolve({
      success: true, platform: PLATFORM, uid: UID, uname: '甲', configured,
      living: liveData.living, since: liveData.since,
      overview: dashboardFixtures ? {firstStart: historyStart} : {},
      liveMetrics: [
        ...extraLiveMetrics,
        {key: 'danmu_count', name: '弹幕', unit: '条'},
        {key: 'gift_value', name: '礼物价值', unit: '元', money: true},
        {key: 'gift_paid', name: '观众消耗', unit: '元', money: true},
        {key: 'box_profit', name: '盲盒盈亏', unit: '元', money: true},
        {key: 'session_extra', name: '本场新增计数', unit: '次'},
      ],
      sessions: {items: dashboardFixtures ? archiveItems : sessions, messageKey: 'danmu_count', metrics: [
        {key: 'danmu_count', name: '弹幕', unit: '条'},
        {key: 'gift_value', name: '礼物价值', unit: '元', money: true},
      ], total: dashboardFixtures ? archiveItems.length : sessions.length, page: 1, pageSize: 20},
    })});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({})});
};

async function until(predicate, what) {
  const deadline = Date.now() + 20000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('等待超时：' + what);
    await new Promise(done => setImmediate(done));
  }
}
const textOf = root => root ? [root.textContent, root.innerHTML, ...root.children.map(textOf)].join(' ') : '';
const liveCard = () => find('sd-live');
const refreshButton = () => liveCard() && walk(liveCard()).find(one => one.tagName === 'button');
async function clickRefresh() {
  const button = refreshButton();
  if (!button) throw new Error('没有正在直播卡片的刷新按钮');
  const before = liveRequests;
  for (const fn of button.listeners.click || []) await fn();
  await until(() => liveRequests > before, '手动刷新请求');
}
async function overview() {
  location.hash = model.detailHash(PLATFORM, UID);
  await page.loadStreamers();
}

let page = null;
let model = null;
try {
  model = await import(join(pages, 'streamers-model.js'));
  page = await import(join(pages, 'streamers.js'));
  page.render(fakeElement('section'));
  globalThis.location.hash = model.detailHash(PLATFORM, UID, 'sessions');
  await page.loadStreamers();
  await until(() => rows.length === STARTS.length, '场次列表');
} catch (err) {
  failures.push('载入主播页或画场次页签就抛了：' + err.message);
}

/** 一行里写着的那些 <a href="…">，按出现的先后 */
function linksIn(row) {
  const out = [];
  const re = /<a\b[^>]*\bhref\s*=\s*"([^"]*)"/gi;
  let match;
  while ((match = re.exec(String(row.innerHTML)))) out.push(match[1].replace(/&amp;/g, '&'));
  if (row.tagName === 'a' && row.href) out.push(row.href);
  return out;
}

const want = () => STARTS.map(start => model.sessionHash(PLATFORM, UID, start));

// 故障：只用键盘的人 Tab 过场次表一格都停不下，哪一场都打不开
await ask('① 画出来的每一行都有一个链接，地址指向这一场', () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  if (rows.length !== STARTS.length) throw new Error('该画 ' + STARTS.length + ' 行，画了 ' + rows.length + ' 行');
  same(rows.map(linksIn), want().map(hash => [hash]), '每一行里的链接');
  same(want()[0], '#/streamers/bilibili/3493/1760000000000', '第一场的地址');
});

// 整张场次卡使用原生链接，键盘和新标签页都能进入同一报告。
await ask('② 整张场次卡使用原生链接', () => {
  same(rows.map(row => row.tagName), STARTS.map(() => 'a'), '整行应为链接');
  same(rows.map(row => row.href), want(), '整行链接的目标');
});
await ask('③ 链接没有额外点击处理，支持新标签页打开', () => {
  same(rows.map(row => (row.listeners.click || []).length), STARTS.map(() => 0), '链接不能重复改变当前页地址');
});

// 故障：去掉「打开」以后行里的空白点不进去，或一行留下多个 Tab 停点。
await ask('④ 主播列表每行是唯一的真链接，地址和主播名正确且没有按钮', async () => {
  location.hash = '#/streamers';
  await page.loadStreamers();
  const listed = node('st-list').children.filter(row => row.className.split(' ').includes('rowcard'));
  same(listed.length, 2, '主播行数');
  same(listed.map(row => row.tagName), ['a', 'a'], '整行链接');
  same(listed.map(row => walk(row).filter(one => one.tagName === 'a').map(one => one.href)),
    [[model.detailHash(PLATFORM, UID)], [model.detailHash(PLATFORM, UID + 1)]], '每行唯一链接的地址');
  same(listed.map(row => walk(row).filter(one => one.tagName === 'button').length), [0, 0], '链接内按钮');
  same(listed.map(row => row.attrs['aria-label']), ['甲', '乙'], '读屏的主播名');
});

// 当前场次须有三项摘要，其余数据默认收在快照明细里。
await ask('当前直播显示进行中三项摘要折叠明细与更新说明', async () => {
  await overview();
  const card = liveCard();
  same(walk(card).some(n => n.textContent === '进行中'), true, '进行中标');
  const primary = walk(card).find(n => n.className === 'sd-live-metrics');
  same(primary?.children.map(n => n.children[0].textContent), ['流水', '弹幕', '在线峰值'], '三项摘要标签');
  same(primary?.children.map(n => n.children[1].textContent), ['12.50 元', '123 条', '999 人'], '三项摘要的现有数值');
  const details = walk(card).find(n => n.tagName === 'details');
  same(Boolean(details), true, '快照明细折叠');
  same(Boolean(details?.open), false, '明细默认收起');
  same(details?.children[0].textContent, '查看本场快照明细', '明细入口');
  for (const part of ['已开播', '数据截至 2026/10/14 12:34', '每 15 秒检查更新']) {
    if (!textOf(card).includes(part)) throw new Error('当前直播缺少 ' + part);
  }
  same(textOf(details).includes('unknown_metric'), true, '其余指标留在明细');
});
// 未取得的摘要不能补零，真实零仍须带单位显示。
await ask('直播摘要区分缺失与真实零且流水不累加观众消耗', async () => {
  const saved = liveData;
  try {
    for (const [metrics, revenue, messages] of [[{}, null, null], [{gift_value: 0, gift_paid: 200, box_profit: 8, danmu_count: 0, online_count: 0}, 0, 0]]) {
      liveData = {...saved, metrics, revenue, messages}; await overview();
      const values = walk(liveCard()).find(n => n.className === 'sd-live-metrics')?.children.map(n => n.children[1].textContent);
      same(values, Object.keys(metrics).length ? ['0.00 元', '0 条', '0 人'] : ['暂无数据', '暂无数据', '暂无数据'], '缺失和零的三项摘要');
    }
  } finally { liveData = saved; }
});
// 中途开播或下播时，直播卡和最近场次数都应跟随同一份/live状态。
await ask('直播刷新同步最近三五场与未归档提示', async () => {
  const saved = liveData;
  try {
    await overview();
    const recentCount = () => walk(node('sd-body')).filter(n => n.className === 'sd-recent-row').length;
    same(recentCount(), 3, '在播预览三场');
    same(textOf(find('sd-insights')).includes('正在直播的场次下播后才计入'), true, '在播时的归档提示');
    liveData = {...liveData, living: false, since: null, revenue: null, messages: null, metrics: {}, series: {}};
    for (const timer of [...intervals.values()]) await timer.fn();
    same(liveCard().hidden, true, '中途下播隐藏直播卡');
    same(recentCount(), 5, '中途下播预览五场');
    same(textOf(find('sd-insights')).includes('正在直播的场次下播后才计入'), false, '下播后清除归档提示');
    liveData = {...saved, living: true};
    for (const timer of [...intervals.values()]) await timer.fn();
    same(liveCard().hidden, false, '中途开播显示直播卡');
    same(recentCount(), 3, '中途开播预览三场');
    same(textOf(find('sd-insights')).includes('正在直播的场次下播后才计入'), true, '中途开播恢复归档提示');
  } finally { liveData = saved; await overview(); }
});

// 本场新增指标若误用周期名录，会在正在直播卡显示原始键名。
await ask('正在直播卡从本场名录取中文名和单位，未命名指标仍提示原始键', async () => {
  await overview();
  const labels = walk(liveCard()).filter(one => one.tagName === 'dt');
  same(labels.some(one => one.textContent === '本场新增计数'), true, '本场新增指标的中文名');
  same(labels.some(one => one.textContent === 'session_extra'), false, '已命名指标不露原始键');
  same(textOf(liveCard()).includes('7 次'), true, '本场新增指标的单位');
  const unknown = labels.find(one => one.textContent === 'unknown_metric');
  same(unknown?.title, '本机采集到一项插件尚未命名的指标，按原始键名显示', '未命名指标的提示');
});

// 故障：失败时把已有本场数清空或变成 0，检查时刻混成数据更新时间。
await ask('⑤ 刷新失败保留本场数，只说短话不拼原话，数据更新时间和检查时刻分开', async () => {
  await overview();
  const original = textOf(liveCard());
  for (const expected of ['正在直播', '123', '12.50 元', 'unknown_metric', '42 人',
    '数据截至 2026/10/14 12:34', '检查于 12:35:07', '已开播']) {
    if (!original.includes(expected)) throw new Error('缺少 ' + expected);
  }
  liveFailure = '暂时读不到本场数据';
  await clickRefresh();
  const failed = textOf(liveCard());
  for (const expected of ['123', '12.50 元', '刷新失败，稍后自动重试',
    '当前显示的是 12:35:07 的数据', '数据截至 2026/10/14 12:34', '检查于 12:35:07']) {
    if (!failed.includes(expected)) throw new Error('失败后缺少 ' + expected);
  }
  if (failed.includes(liveFailure)) throw new Error('后台刷新失败不该拼服务端原话：' + liveFailure);
  liveFailure = '网络错误';
  await clickRefresh();
  if (!textOf(liveCard()).includes('刷新失败，稍后自动重试')) throw new Error('网络错误时没有那句短话');
  if (textOf(liveCard()).includes('网络错误')) throw new Error('后台刷新失败不该拼原话：网络错误');
  liveFailure = '';
  await clickRefresh();
  if (textOf(liveCard()).includes('刷新失败')) throw new Error('恢复后失败说明没消失');
});

// 故障：把高能榜人数或本场峰值当成当前在线，又多画一种在线口径。
await ask('⑥ 在线人数取报告图的 online_count 最新点，只画一种在线人数', () => {
  const card = liveCard();
  if (!card) throw new Error('没有正在直播卡片');
  const labels = walk(card).filter(one => one.tagName === 'dt').map(one => one.textContent);
  same(labels.filter(label => label === '在线人数').length, 1, '在线人数标签');
  same(labels.includes('online_count'), false, '在线人数不重复');
  if (!textOf(card).includes('42 人') || textOf(walk(card).find(n => n.tagName === 'details')).includes('999 人')) throw new Error('在线人数没有用分钟序列的最新点');
  same(labels.includes('online_rank_count'), false, '高能榜人数不作为另一种在线人数显示');
});

// 故障：切页或隐藏后继续轮询，回到概况后却不再更新。
await ask('⑦ 可见总览每15秒刷新，隐藏离页停止，明细保持刷新', async () => {
  await overview();
  same([...intervals.values()].map(one => one.ms), [15000], '刷新间隔');
  let before = liveRequests;
  for (const timer of [...intervals.values()]) await timer.fn();
  same(liveRequests, before + 1, '定时刷新');
  document.hidden = true;
  for (const fn of documentListeners.visibilitychange || []) fn();
  same(intervals.size, 0, '隐藏后停止');
  document.hidden = false;
  for (const fn of documentListeners.visibilitychange || []) await fn();
  same(intervals.size, 1, '可见后恢复');
  for (const pane of ['sessions', 'trend']) {
    location.hash = model.detailHash(PLATFORM, UID, pane);
    await page.loadStreamers();
    same(intervals.size, 1, pane + ' 保持刷新');
    await overview();
  }
  page.leave();
  same(intervals.size, 0, '离开后停止');
  for (const fn of documentListeners.visibilitychange || []) await fn();
  same(intervals.size, 0, 'leave 后可见也不停留在主播页');
});

// 故障：离开页面之前发出的慢响应回来后又把卡片画回别的页签。
await ask('⑧ 离开概况后迟到的响应不再更新页面，下播后卡片隐藏', async () => {
  await overview();
  pendingLive = {};
  const button = refreshButton();
  if (!button) throw new Error('没有刷新按钮');
  const requests = (button.listeners.click || []).map(fn => fn());
  await until(() => pendingLive.resolve, '待返回的直播请求');
  page.leave();
  pendingLive.resolve({...liveData, revenue: null, messages: 888, metrics: {danmu_count: 888}});
  await Promise.all(requests);
  if (textOf(liveCard()).includes('888')) throw new Error('离开后慢响应仍修改页面');
  pendingLive = null;
  liveData = {...liveData, living: false, since: null, revenue: null, messages: null, metrics: {}, series: {}};
  await overview();
  same(liveCard().hidden, true, '下播后隐藏卡片');
  page.leave();
});
// 故障：首次取详情时隐藏标签页，详情响应被丢弃，重新可见后再也不刷新。
await ask('⑨ 隐藏时加载概况不启动表，可见后能读取这一场', async () => {
  liveData = {...liveData, living: true, since: checkedAt - 3600000,
    revenue: null, messages: 321, metrics: {danmu_count: 321}, series: {online_count: [[dataAt, 21]]}};
  document.hidden = true;
  const before = liveRequests;
  await overview();
  same(liveRequests, before, '隐藏时不请求本场数据');
  same(intervals.size, 0, '隐藏时不启动表');
  document.hidden = false;
  for (const fn of documentListeners.visibilitychange || []) await fn();
  same(liveRequests, before + 1, '重新可见时读取本场数据');
  if (!textOf(liveCard()).includes('321')) throw new Error('重新可见后没有本场数据');
  same(intervals.size, 1, '重新可见时恢复表');
});

// 故障：另开一场后接口暂时失败，还拿上一次直播的数冒充新一场。
await ask('⑩ 详情已显示新一场时清掉前一场数据，不把旧场指标冒充本场', async () => {
  liveData = {...liveData, since: checkedAt + 3600000};
  liveFailure = '暂时不可用';
  await overview();
  if (textOf(liveCard()).includes('321')) throw new Error('上一场的指标还留在新一场卡片');
  if (!textOf(liveCard()).includes('尚未获取到本场数据')) throw new Error('没说明新一场尚未取到数据');
  liveFailure = '';
  page.leave();
});

// 故障：服务端回默认 500 错误包后，正在直播卡片消失，已有数值和失败原因都看不到。
await ask('⑪ 服务端错误或无效成功回包保留卡片与本场数，只说短话不拼原因', async () => {
  await overview();
  try {
    for (const [status, body, reason] of [
      [500, {timestamp: checkedAt, status: 500, error: 'Internal Server Error', path: '/config/api/streamers/bilibili/3493/live'}, 'Internal Server Error'],
      [503, {status: 503}, 'HTTP 503'],
      [500, {message: '本场数据暂时不可用'}, '本场数据暂时不可用'],
      [200, {success: true, living: 'true'}, 'HTTP 200'],
      [200, {living: true}, 'HTTP 200'],
      [200, null, 'HTTP 200'],
    ]) {
      liveHttpReply = {status, body};
      await clickRefresh();
      same(liveCard().hidden, false, '服务端错误后卡片仍可见');
      const text = textOf(liveCard());
      for (const expected of ['321', '21 人', '刷新失败，稍后自动重试',
        '当前显示的是 12:35:07 的数据', '数据截至 2026/10/14 12:34', '检查于 12:35:07']) {
        if (!text.includes(expected)) throw new Error('服务端错误后缺少 ' + expected);
      }
      if (text.includes('刷新失败：') || text.includes(reason)) throw new Error('服务端错误后拼了原因：' + reason);
    }
  } finally {
    liveHttpReply = null;
    await overview();
  }
});

// 故障：在线序列没有点时显示「— 人」，让缺失值带上人数单位。
await ask('⑫ 在线序列为空时只显示破折号，不带人数单位', async () => {
  const saved = liveData;
  try {
    liveData = {...liveData, series: {online_count: []}};
    await overview();
    const cells = walk(liveCard()).find(one => one.className === 'kv').children;
    const index = cells.findIndex(one => one.tagName === 'dt' && one.textContent === '在线人数');
    if (index < 0) throw new Error('缺少在线人数');
    same(cells[index + 1].textContent, '—', '在线序列为空时的显示');
  } finally {
    liveData = saved;
  }
});

// 故障：正在直播卡片排到最近几场下面，当前这一场的数据被旧场次挤到页面底下。
await ask('⑬ 正在直播卡片在最近几场上面', async () => {
  await overview();
  const cards = walk(node('sd-body'));
  const current = cards.indexOf(liveCard());
  const recent = cards.findIndex(card => card.children.some(one => one.tagName === 'h4'
    && /^最近 \d+ 场归档$/.test(one.textContent)));
  if (current < 0 || recent < 0 || current >= recent) throw new Error('正在直播没有排在最近几场上面');
});

// 故障：只在归档中的主播不采集数据，概况仍反复请求本场数据。
await ask('⑭ 不在推送配置里的主播不请求本场数据，也不启动刷新表', async () => {
  configured = false;
  try {
    const before = liveRequests;
    await overview();
    same(intervals.size, 0, '归档主播不启动刷新表');
    same(liveRequests, before, '归档主播不请求本场数据');
    document.hidden = true;
    for (const fn of documentListeners.visibilitychange || []) await fn();
    document.hidden = false;
    for (const fn of documentListeners.visibilitychange || []) await fn();
    same(intervals.size, 0, '重新可见也不启动刷新表');
    same(liveRequests, before, '重新可见也不请求本场数据');
  } finally {
    configured = true;
  }
});

// 续费与新开不能在本场卡片中以英文键名显示。
await ask('正在直播大航海新开续费显示中文名与人次', async () => {
  const saved = liveData;
  try {
    liveData = {...liveData, revenue: null, messages: null, metrics: {guard_open_count: 1, guard_renew_count: 1, image_degraded_count: 1}};
    await overview();
    const list = walk(liveCard()).find(one => one.className === 'kv');
    same(list.children.map(one => one.textContent),
      ['大航海新开', '1 人次', '大航海续费', '1 人次', '推送图片未送达', '1 条', '在线人数', '21 人'], '本场指标名称与单位');
    same(textOf(liveCard()).includes('guard_'), false, '不显示大航海原始键');
  } finally { liveData = saved; }
});

async function openSlots(query = '') {
  location.hash = model.detailHash(PLATFORM, UID, 'start-slots', query);
  await page.loadStreamers();
}
const drawerBody = () => walk(document.body).find(n => n.className === 'sc-drawer-body');
const slotButtons = () => walk(drawerBody()).filter(n => n.tagName === 'button' && n.attrs['aria-label']);
const press = async button => {
  if (!button) throw new Error('没有找到按钮');
  for (const fn of button.listeners.click || []) await fn();
};

// 开播时段不能漏列、让小样本上色，或把整场总量误写成每小时值。
await ask('开播时段显示84格且点击列出整场数据与场次链接', async () => {
  await openSlots();
  same(textOf(drawerBody()).includes('不表示该两小时内的产出'), true, '热图不得被理解为两小时内的产出');
  same(slotButtons().length, 84, '7×12格');
  same(walk(drawerBody()).some(n => n.className === 'hint slot-unit'), false, '热图不重复显示单位');
  same(walk(drawerBody()).filter(n => n.tagName === 'option').map(n => n.textContent),
    ['每小时流水', '每小时弹幕'], '指标下拉说明单位');
  same(walk(drawerBody()).find(n => n.className === 'slot-grid')?.children[0]?.children[0]?.children[0]?.textContent,
    '', '热图首列表头留空');
  same(slotRequests.at(-1).endsWith('days=90'), true, '默认90天请求');
  const active = slotButtons().find(n => n.attrs['aria-label'].includes('周一 22:00'));
  if (!active) throw new Error('没有周一22点格子');
  same(Boolean(active.style.background), true, '有值格子上色');
  same(slotButtons().filter(n => n.style.background).length, 1, '空格不上色');
  same(active.attrs['aria-pressed'], 'false', '热图初始未选中');
  const header = walk(drawerBody()).find(n => n.tagName === 'th' && n.attrs.scope === 'row');
  header.appendChild(document.createTextNode(header.textContent));
  await press(active);
  same(active.attrs['aria-pressed'], 'true', '所选格向读屏报告选中');
  same(slotButtons().filter(n => n.attrs['aria-pressed'] === 'true').length, 1, '只选中一个时段');
  const parsed = model.parseStreamersHash(location.hash);
  same(parsed.starts, String(STARTS[0]), '热图传递原场次');
  await page.loadStreamers();
  const row = walk(drawerBody()).find(n => n.className === 'sd-session-row' && n.href === model.sessionHash(PLATFORM, UID, STARTS[0]));
  const revenue = walk(row).find(n => n.tagName === 'dt' && n.textContent === '流水');
  same(revenue?.parentNode.children.find(n => n.tagName === 'dd')?.textContent, '100.00 元', '所选场次流水数值');
  same(walk(drawerBody()).some(n => n.href === model.sessionHash(PLATFORM, UID, STARTS[0])), true, '场次卡链接');
});

// 切换指标时不能把已向右滑动的热图送回最左边。
await ask('热图切换指标保留横向滚动位置', async () => {
  await openSlots();
  const scroll = walk(drawerBody()).find(n => n.className === 'slot-scroll');
  scroll.scrollLeft = 872;
  const metric = walk(drawerBody()).find(n => n.tagName === 'select'); metric.value = 'messages'; await metric.fire('change');
  same(walk(drawerBody()).find(n => n.className === 'slot-scroll').scrollLeft, 872, '切换后横向位置');
});

// 选择180天和弹幕时须实际换请求、热图单位与前三，不能只换按钮外观。
await ask('开播时段180天独立取数且弹幕切换更新前三与热图单位', async () => {
  await openSlots('slotDays=180&days=7');
  same(slotRequests.at(-1).endsWith('days=180'), true, '180天请求');
  const metric = walk(drawerBody()).find(n => n.tagName === 'select');
  metric.value = 'messages'; await metric.fire('change');
  const content = textOf(drawerBody());
  for (const part of ['周一 22:00–24:00', '100.00', '条/时', '3 场有效样本']) {
    if (!content.includes(part)) throw new Error('切换弹幕后缺少 ' + part);
  }
});

// 慢请求在切页后不能把开播时段表画到场次页。
await ask('离开开播时段后迟到的请求不覆盖场次页', async () => {
  pendingSlots = {};
  const loading = openSlots();
  await until(() => pendingSlots.resolve, '开播时段请求');
  location.hash = model.detailHash(PLATFORM, UID, 'sessions');
  await page.loadStreamers();
  const previous = textOf(drawerBody());
  const previousCard = drawerBody().firstChild;
  pendingSlots.resolve({success: true, startSlots: slotData});
  await loading;
  same(textOf(drawerBody()), previous, '迟到响应不改场次页');
  if (drawerBody().firstChild !== previousCard) throw new Error('迟到响应重建了场次页');
  pendingSlots = null;
});

// 入口必须与统计口径同数同范围，跨范围起点的重叠场次不能混入；清除后回到全部归档。
dashboardFixtures = true;
page.leave();
await ask('总览归档入口按范围计数并可清除筛选', async () => {
  const counts = [];
  for (const days of [30, 360]) {
    location.hash = model.detailHash(PLATFORM, UID, null, 'days=' + days);
    await page.loadStreamers();
    const expected = days === 30 ? 3 : 5;
    const link = walk(node('sd-body')).find(n => n.tagName === 'a' && /查看 .* 场归档/.test(n.textContent));
    same(link?.textContent, '查看 ' + expected + ' 场归档', days + '天入口');
    same(textOf(find('sd-scope')).includes('归档 ' + expected + ' 场'), true, '入口与统计口径同数');
    const state = model.parseStreamersHash(link.href);
    same([Number(state.dateFrom), Number(state.dateTo), state.source], [rangeStart(days), cutoff, 'dashboard'], '统计日范围与来源');
    same([state.from, state.to], [undefined, undefined], '不使用重叠筛选');
    counts.push(expected);
    location.hash = link.href; await page.loadStreamers();
    same(walk(drawerBody()).filter(n => n.className === 'sd-session-row').length, expected, '抽屉场数');
    same(textOf(drawerBody()).includes('筛选来源：总览归档 · ' + expected + ' 个场次 · 与总览使用同一组场次'), true, '来源说明');
    const clear = walk(drawerBody()).find(n => n.textContent === '清除筛选');
    same(Boolean(clear), true, '有清除筛选');
    location.hash = clear.href; await page.loadStreamers();
    same(walk(drawerBody()).filter(n => n.className === 'sd-session-row').length, 5, '清除后全部归档');
    same(textOf(drawerBody()).includes('筛选来源'), false, '清除后没有来源');
  }
  same(counts[0] !== counts[1], true, '不同范围的归档数不同');
});

// 总览接口失败不能让归档入口永久停在不可点击的读取状态。
await ask('总览读取失败仍可查看全部归档', async () => {
  insightsFailure = true;
  try {
    page.leave(); await overview();
    const link = walk(node('sd-body')).find(n => n.className.includes('sd-archive-link'));
    same(link?.textContent, '查看全部归档', '失败后的入口');
    same(Boolean(link?.href), true, '入口可点击');
    const state = model.parseStreamersHash(link.href);
    same([state.from, state.to, state.dateFrom, state.dateTo, state.source], Array(5).fill(undefined), '全部归档不带筛选');
    location.hash = link.href; await page.loadStreamers();
    same(walk(drawerBody()).filter(n => n.className === 'sd-session-row').length, archiveItems.length, '失败后打开全部归档');
    same(textOf(drawerBody()).includes('清除筛选'), false, '全部归档无清除入口');
  } finally { insightsFailure = false; }
});

// 接口 label 故意保留整周期；显示必须使用已截短的 start/end，且链接与整行选择同一期。
for (const [period, start, end, label, expected] of [
  ['day', '2026-10-15', '2026-10-16', '2026-10-15', '10/15'],
  ['week', '2026-10-12', '2026-10-19', '2026-10-12 ~ 10-18', '10/12–10/18'],
  ['week', '2026-09-17', '2026-09-21', '2026-09-14 ~ 09-20', '09/17–09/20'],
  ['month', '2026-09-17', '2026-10-01', '2026-09', '09/17–09/30'],
  ['month', '2026-10-01', '2026-10-16T08:40', '2026-10', '10/01–10/16'],
]) await ask('趋势周期 ' + period + ' ' + expected + ' 从起止推导并链接', async () => {
  const at = date => Date.parse(date + (date.includes('T') ? ':00' : 'T06:00:00') + '+08:00');
  trendBuckets = [{start: at(start), end: at(end), label, sessions: 2, durationSeconds: 7200, revenue: 42, starts: [STARTS[0]]}];
  location.hash = model.detailHash(PLATFORM, UID, 'trend', 'period=' + period);
  await page.loadStreamers();
  const table = walk(drawerBody()).find(n => n.className === 'sd-trend-table');
  const row = walk(table).find(n => n.tagName === 'tbody')?.children[0];
  same(textOf(row?.children[0]).trim(), expected, period + '周期文字');
  const link = walk(row.children[0]).find(n => n.tagName === 'a');
  same(Boolean(link), true, '周期有原生链接');
  const state = model.parseStreamersHash(link.href);
  same([Number(state.from), Number(state.to), state.source, state.starts], [at(start), at(end), 'trend', String(STARTS[0])], '周期链接范围与场次');
  await press(row);
  same(location.hash, link.href, '整行和文字链接相同');
});

// 缺少任一边界时不能编造历史起点或截止时刻。
await ask('可用历史只显示真实起止', async () => {
  historyStart = null; await overview();
  same(textOf(node('sd-body')).includes('可用历史自 '), false, '无归档不显示历史');
  historyStart = Date.parse('2026-08-01T10:00:00+08:00'); await overview();
  same(textOf(node('sd-body')).includes('可用历史自 2026/08/01 10:00'), true, '合并说明保留真实历史起点');
  same(textOf(node('sd-body')).includes('统计日 06:00–次日06:00 · Asia/Shanghai'), true, '统计日说明');
  const saved = cutoff;
  try {
    cutoff = null; await overview();
    same(textOf(node('sd-body')).includes('可用历史自 '), false, '缺截止时刻不显示历史');
  } finally { cutoff = saved; }
});

// 每次加载新的模块实例，避免从旧模块内存恢复而掩盖地址读取遗漏。
let reloads = 0;
async function reloadPage() {
  page.leave();
  page = await import(join(pages, 'streamers.js') + '?reload=' + ++reloads);
  page.render(fakeElement('section'));
  await page.loadStreamers();
}
await ask('隐藏未播写入地址且重新载入恢复', async () => {
  await overview();
  const checkbox = () => walk(find('sd-trend-content')).find(n => n.type === 'checkbox');
  checkbox().checked = true; await checkbox().fire('change');
  same(model.parseStreamersHash(location.hash).hideOff, '1', '勾选后地址');
  await reloadPage();
  same(checkbox().checked, true, '重新载入仍勾选');
  checkbox().checked = false; await checkbox().fire('change');
  same(model.parseStreamersHash(location.hash).hideOff, undefined, '取消后移除参数');
  await reloadPage();
  same(checkbox().checked, false, '重新载入保持取消');
});

// 导航真实使用页面入口并刷新地址状态，开关不能在开关抽屉和切范围时丢失。
for (const [panel, label] of [['audience', '查看本期名单'], ['sessions', '查看 3 场归档'],
  ['fans', '粉丝净增'], ['start-slots', '查看历史节奏']]) {
  await ask('隐藏未播在' + panel + '抽屉往返和切范围后保留', async () => {
    await overview();
    const checkbox = () => walk(find('sd-trend-content')).find(n => n.type === 'checkbox');
    checkbox().checked = true; await checkbox().fire('change');
    await page.loadStreamers(); // 对应宿主的 hashchange 刷新。
    if (panel === 'fans') {
      const cell = walk(node('sd-body')).find(n => n.attrs['aria-label'] === label);
      await press(cell);
    } else {
      const link = walk(node('sd-body')).find(n => n.tagName === 'a' && n.textContent === label);
      location.hash = link.href;
    }
    await page.loadStreamers();
    same(model.parseStreamersHash(location.hash).panel, panel, '目标抽屉');
    same(model.parseStreamersHash(location.hash).hideOff, '1', '打开抽屉保留地址');
    same(checkbox().checked, true, '打开抽屉保留勾选');
    await press(walk(document.body).find(n => n.className.includes('sc-drawer-close')));
    await page.loadStreamers();
    same(model.parseStreamersHash(location.hash).panel, undefined, '关闭抽屉');
    same(model.parseStreamersHash(location.hash).hideOff, '1', '关闭后保留地址');
    same(checkbox().checked, true, '关闭后保留勾选');
    const range = walk(find('sd-toolbar')).find(n => n.textContent === '360天');
    location.hash = range.href; await page.loadStreamers();
    same(model.parseStreamersHash(location.hash).days, 360, '切换范围');
    same(model.parseStreamersHash(location.hash).hideOff, '1', '切范围保留地址');
    same(checkbox().checked, true, '切范围保留勾选');
  });
}

// 排名为空时须说明样本和效率门槛，不能只留下标题或笼统的场数提示。
await ask('无有效开播时段时显示样本与效率说明', async () => {
  emptySlots = true;
  try {
    page.leave(); await openSlots();
    const card = walk(drawerBody()).find(n => n.className.includes('start-slots'));
    const heading = card.children.findIndex(n => n.textContent === '表现较好的开播时段');
    same(heading >= 0, true, '排名标题');
    same(textOf(card).includes('当前范围暂无达到样本门槛且效率有效的时段。'), true, '空排名说明');
    same(card.children.slice(heading + 1).some(n => n.textContent.includes('当前范围暂无达到样本门槛且效率有效的时段')), true, '说明在标题之后');
  } finally { emptySlots = false; }
});

// 粉丝图实际读数必须带人数单位。
await ask('粉丝图读数带人数单位', async () => {
  location.hash = model.detailHash(PLATFORM, UID, 'fans'); await page.loadStreamers();
  const readout = walk(drawerBody()).find(n => n.className === 'sc-chart-readout');
  same(readout?.textContent.includes('17,053 人'), true, '粉丝读数');
});

page?.leave();

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
