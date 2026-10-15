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
    attrs: {}, dataset: {}, style: {},
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
  node.querySelector = () => null;
  node.classList = {toggle() {}, add() {}, remove() {}, contains() { return false; }};
  if (node.tagName === 'tr') rows.push(node);
  return node;
}

const byId = {};
const node = id => byId[id] || (byId[id] = fakeElement('div'));
const walk = root => [root, ...root.children.flatMap(walk)];
const find = id => Object.values(byId).flatMap(walk).find(one => one.id === id) || byId[id] || null;
const documentListeners = {};
const intervals = new Map();
let nextInterval = 0;
globalThis.setInterval = (fn, ms) => { intervals.set(++nextInterval, {fn, ms}); return nextInterval; };
globalThis.clearInterval = id => intervals.delete(id);

globalThis.document = {
  querySelector: sel => {
    const id = String(sel).replace(/^#/, '');
    return id === 'sd-banner' ? null : (id === 'sd-live' ? find(id) : node(id));
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
  get activeElement() { return null; },
};
globalThis.location = {hash: ''};

const PLATFORM = 'bilibili';
const UID = 3493;
const STARTS = [1760000000000, 1759900000000, 1759800000000];

const sessions = STARTS.map((start, i) => ({
  platform: PLATFORM, uid: UID, startTime: start, endTime: start + 3600000,
  durationSeconds: 3600, title: '第 ' + (i + 1) + ' 场', metrics: {}, interrupted: false,
}));

let liveFailure = '';
let liveHttpReply = null;
let configured = true;
let liveRequests = 0;
let pendingLive = null;
const dataAt = new Date(2026, 9, 14, 12, 34, 0).getTime();
const checkedAt = new Date(2026, 9, 14, 12, 35, 7).getTime();
let liveData = {
  success: true, living: true, since: checkedAt - 3600000, dataAt, checkedAt,
  metrics: {danmu_count: 123, gift_value: 12.5, unknown_metric: 9, online_count: 999, online_rank_count: 3,
    session_extra: 7},
  series: {online_count: [[dataAt - 60000, 50], [dataAt, 42]]},
};
const reply = data => Promise.resolve({status: 200, json: () => Promise.resolve(data)});
globalThis.fetch = url => {
  const path = String(url);
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
      overview: {},
      liveMetrics: [
        {key: 'danmu_count', name: '弹幕', unit: '条'},
        {key: 'gift_value', name: '礼物价值', unit: '元', money: true},
        {key: 'session_extra', name: '本场新增计数', unit: '次'},
      ],
      sessions: {items: sessions, metrics: [
        {key: 'danmu_count', name: '弹幕', unit: '条'},
        {key: 'gift_value', name: '礼物价值', unit: '元', money: true},
      ], total: sessions.length, page: 1, pageSize: 20},
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

// 对照：整行点照旧能用，跳的是同一处
await ask('② 对照：整行点一下照旧跳到这一场', () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  const reached = rows.map(row => {
    globalThis.location.hash = '';
    for (const fn of row.listeners.click || []) fn({target: row});
    return globalThis.location.hash;
  });
  same(reached, want(), '点整行之后的地址');
});

// 故障：在主播页场次表里按住 Ctrl（Mac 上 ⌘）点开播时间，想在新标签页打开那一场，结果这一页也跟着跳走了
await ask('③ 点在行里的链接上时，整行的点击不改地址，交给链接自己去', () => {
  if (!page || !model) throw new Error('主播页没载进来，这一格量不了');
  if (rows.length !== STARTS.length) throw new Error('该画 ' + STARTS.length + ' 行，画了 ' + rows.length + ' 行');
  const reached = rows.map(row => {
    globalThis.location.hash = '';
    const link = {tagName: 'a', closest: sel => (sel === 'a' ? link : null)};
    for (const fn of row.listeners.click || []) fn({target: link});
    return globalThis.location.hash;
  });
  same(reached, STARTS.map(() => ''), '点在链接上之后的地址');
});

// 故障：去掉「打开」以后行里的空白点不进去，或一行留下多个 Tab 停点。
await ask('④ 主播列表每行是唯一的真链接，地址和主播名正确且没有按钮', async () => {
  location.hash = '#/streamers';
  await page.loadStreamers();
  const listed = node('st-list').children;
  same(listed.length, 2, '主播行数');
  same(listed.map(row => row.tagName), ['a', 'a'], '整行链接');
  same(listed.map(row => walk(row).filter(one => one.tagName === 'a').map(one => one.href)),
    [[model.detailHash(PLATFORM, UID)], [model.detailHash(PLATFORM, UID + 1)]], '每行唯一链接的地址');
  same(listed.map(row => walk(row).filter(one => one.tagName === 'button').length), [0, 0], '链接内按钮');
  same(listed.map(row => row.attrs['aria-label']), ['甲', '乙'], '读屏的主播名');
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
await ask('⑤ 刷新失败保留本场数与服务端原话，数据更新时间和检查时刻分开', async () => {
  await overview();
  const original = textOf(liveCard());
  for (const expected of ['正在直播', '123', '12.50 元', 'unknown_metric', '42 人',
    '数据更新至 12:34', '检查于 12:35:07', '已直播']) {
    if (!original.includes(expected)) throw new Error('缺少 ' + expected);
  }
  liveFailure = '暂时读不到本场数据';
  await clickRefresh();
  const failed = textOf(liveCard());
  for (const expected of ['123', '12.50 元', '刷新失败：' + liveFailure,
    '当前显示的是 12:35:07 的数据', '数据更新至 12:34', '检查于 12:35:07']) {
    if (!failed.includes(expected)) throw new Error('失败后缺少 ' + expected);
  }
  liveFailure = '网络错误';
  await clickRefresh();
  if (!textOf(liveCard()).includes('刷新失败：网络错误')) throw new Error('网络错误没有显示');
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
  if (!textOf(card).includes('42 人') || textOf(card).includes('999 人')) throw new Error('在线人数没有用分钟序列的最新点');
  same(labels.includes('online_rank_count'), false, '高能榜人数不作为另一种在线人数显示');
});

// 故障：切页或隐藏后继续轮询，回到概况后却不再更新。
await ask('⑦ 可见概况每 15 秒刷新，隐藏、切页和 leave 停止，返回后恢复', async () => {
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
    same(intervals.size, 0, pane + ' 停表');
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
  pendingLive.resolve({...liveData, metrics: {danmu_count: 888}});
  await Promise.all(requests);
  if (textOf(liveCard()).includes('888')) throw new Error('离开后慢响应仍修改页面');
  pendingLive = null;
  liveData = {...liveData, living: false, since: null, metrics: {}, series: {}};
  await overview();
  same(liveCard().hidden, true, '下播后隐藏卡片');
  page.leave();
});
// 故障：首次取详情时隐藏标签页，详情响应被丢弃，重新可见后再也不刷新。
await ask('⑨ 隐藏时加载概况不启动表，可见后能读取这一场', async () => {
  liveData = {...liveData, living: true, since: checkedAt - 3600000,
    metrics: {danmu_count: 321}, series: {online_count: [[dataAt, 21]]}};
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
await ask('⑪ 服务端错误或无效成功回包保留卡片与本场数，并说明失败原因', async () => {
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
      for (const expected of ['321', '21 人', '刷新失败：' + reason,
        '当前显示的是 12:35:07 的数据', '数据更新至 12:34', '检查于 12:35:07']) {
        if (!text.includes(expected)) throw new Error('服务端错误后缺少 ' + expected);
      }
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
    const cells = walk(liveCard()).find(one => one.tagName === 'dl').children;
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
  const cards = node('sd-body').children;
  const current = cards.indexOf(liveCard());
  const recent = cards.findIndex(card => card.children.some(one => one.tagName === 'h3'
    && one.textContent.startsWith('最近几场')));
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

page?.leave();

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
