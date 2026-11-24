/**
 * 设置引导：试发记录、官方群试发、连接状态重试
 *
 * 抓的用户故障：/api/setup/state 回包里缺 testSent（不知道试没试发过）时，
 * 进度条照前几步都配好了推断末步打勾，勾底下又写着「有试发记录」——
 * 屏幕断言了一件谁也没记下过的事。
 *
 * 真跑 setup.js 的 openSetup：假 DOM 与假 fetch 之外，取事实、算记号、画进度条都是产品码那一份。
 * 前几步的事实一律喂成已配好，只换 testSent 那一位，读试发那一格的勾与小字：
 * ① 缺这个字段：不写「有试发记录」（勾照前几步推断，小字写「已配置」）；
 * ② false：不打勾；③ true：打勾，写「有试发记录」。
 * setup.js 载不进来、或进度条上找不到试发那一格时记红，不空转。
 *
 * 由 SetupTestSentMarkTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {register} from 'node:module';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

class Node {
  constructor(tag) {
    this.tagName = String(tag || '').toLowerCase();
    this.children = [];
    this.className = '';
    this.id = '';
    this.type = '';
    this.value = '';
    this.textContent = '';
    this.disabled = false;
    this.hidden = false;
    this.style = {};
    this.dataset = {};
    this.attributes = {};
    this.listeners = {};
    this.classList = {add() {}, remove() {}, toggle() {}, contains: () => false};
  }

  set innerHTML(html) {
    this.children = [];
    this.textContent = '';
    if (this.tagName === 'select') this.value = html.match(/<option value="([^"]*)"/)?.[1] || '';
  }

  get innerHTML() {
    return '';
  }

  get isConnected() {
    return find(main, item => item === this) === this;
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  append(...items) {
    for (const item of items) if (item && typeof item === 'object') this.children.push(item);
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }

  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  removeAttribute(name) {
    delete this.attributes[name];
  }

  addEventListener(event, listener) {
    this.listeners[event] = listener;
  }

  click() {
    if (!this.disabled) return this.listeners.click?.();
  }

  removeEventListener() {
  }

  focus() {
  }

  querySelector() {
    return null;
  }

  querySelectorAll() {
    return [];
  }
}

const nodes = new Map();

function node(selector) {
  let found = nodes.get(selector);
  if (!found) {
    found = new Node('div');
    nodes.set(selector, found);
  }
  return found;
}

function find(root, predicate) {
  if (predicate(root)) return root;
  for (const child of root.children) {
    const match = find(child, predicate);
    if (match) return match;
  }
}

const main = node('#setup-main');
const rail = node('#setup-steps');
const button = id => find(main, item => item.id === id);

globalThis.document = {
  documentElement: {classList: {toggle() {}, add() {}, remove() {}, contains: () => false}},
  body: new Node('body'),
  head: new Node('head'),
  querySelector: selector => button(selector.slice(1)) || node(selector),
  querySelectorAll: () => [],
  getElementById: id => node('#' + id),
  createElement: tag => new Node(tag),
  createTextNode: text => Object.assign(new Node('#text'), {textContent: String(text)}),
  addEventListener() {},
  removeEventListener() {},
};
globalThis.location = {hash: '#/setup', href: '', replace() {}, reload() {}};
globalThis.window = globalThis;

/** 前几步一律配好：上了锁、机器人在线、直播平台的账号登着、有主播 */
const STATUS = {
  success: true,
  locked: true,
  senders: [{id: 'qq'}],
  users: [{uid: 1, uname: '甲', platform: 'bilibili', enabled: true, targets: 1}],
  health: [{scope: 'BOT', level: 'OK', name: '机器人连接', summary: '正常'}],
};
const LOGIN = {success: true, accounts: [{platform: 'bilibili', name: '平台', loggedIn: true}]};
const OFFICIAL = {
  ...STATUS,
  senders: ['qq-official'],
  health: [{scope: 'BOT', card: 'qqbot', level: 'OK', name: 'QQ 官方机器人', summary: '已连接'}],
};
const CONNECTING = {...OFFICIAL, health: [{...OFFICIAL.health[0], level: 'DEGRADED', summary: '连接中'}]};
const GROUP = {sender: 'qq-official', num: 1571234567890123, name: '测试群'};
const QQBOT_PAGE = {
  id: 'qqbot', slot: 'links', displayName: 'QQ 官方机器人',
  script: 'data:text/javascript,export function render() {}',
};

let mark = {};
let status = STATUS;
let pages = [];
let groups = [];
let pollReplies = [];
let sentRequests = [];

function reply(body, code = 200) {
  return Promise.resolve(Response.json(body, {status: code}));
}

globalThis.fetch = (url, options) => {
  const path = String(url);
  if (path.endsWith('/api/status')) {
    const next = options.cache === 'no-store' && pollReplies.shift();
    return next ? reply(next.body, next.code) : reply(status);
  }
  if (path.endsWith('/api/login')) return reply(LOGIN);
  if (path.endsWith('/api/setup/state')) return reply(mark);
  if (path.endsWith('/api/setup/bot')) return reply({address: '127.0.0.1', httpPort: 3000, wsPort: 3001});
  if (path.endsWith('/api/pages')) return reply({pages});
  if (path.endsWith('/api/bot/targets?type=group')) return reply({success: true, items: groups});
  if (path.endsWith('/api/bot/targets?type=friend')) return reply({success: true, items: []});
  if (path.endsWith('/api/test-message')) {
    const body = JSON.parse(options.body);
    sentRequests.push(body);
    const success = options.method === 'POST' && body.platform === GROUP.sender
      && body.type === 1 && body.num === GROUP.num;
    return reply({success, message: success ? '已发送' : '发送失败'});
  }
  if (path.endsWith('/api/setup/test-sent') && options.method === 'POST') {
    mark = {...mark, testSent: true};
  }
  return reply({success: true});
};

// settings.js 引入口 main.js，真入口在解析时就去绑整页按钮；换成替身
register(new URL('./home-problems-fetch-failure-loader.mjs', import.meta.url));

let setup = null;
try {
  setup = await import('../../../main/resources/config-ui/setup.js');
} catch (err) {
  failures.push('载入 setup.js 就抛了：' + err.message);
}

/** 进度条上试发那一格：勾或步号、勾底下那行小字 */
function testCell() {
  for (const item of rail.children) {
    const [badge, text] = item.children;
    const [title, small] = (text && text.children) || [];
    if (title && title.textContent === '发送测试消息') {
      return {'记号': badge ? badge.textContent : '', '小字': small ? small.textContent : ''};
    }
  }
  return {'记号': '(进度条上没有试发那一格)', '小字': ''};
}

async function railFor(state) {
  if (!setup) return {'记号': '(setup.js 没载入)', '小字': ''};
  mark = state;
  await setup.openSetup();
  return testCell();
}

async function checkTestSentMarks() {
  // 前几步都配好、又要求重走：页上画的是进度条，不是走完后的小结
  const rerun = {rerunRequested: true};
  const cell1 = await railFor(rerun);
  eq(cell1['小字'] === '已有测试发送记录', false,
    '① 回包缺 testSent：试发那一格不写「已有测试发送记录」');
  eq(cell1, {'记号': '✓', '小字': '已配置'}, '① 回包缺 testSent：勾照前几步推断，小字写「已配置」');
  // 重走时停在第 1 步，试发那一格还没轮到：画步号 4、不带小字
  const cell2 = await railFor({...rerun, testSent: false});
  eq(cell2, {'记号': '4', '小字': ''}, '② testSent 为 false：不打勾');
  const cell3 = await railFor({...rerun, testSent: true, testSentAt: '2026-10-01T08:00:00Z'});
  eq(cell3, {'记号': '✓', '小字': '已有测试发送记录'}, '③ testSent 为 true：打勾，写「已有测试发送记录」');
}

const flushResponses = () => new Promise(resolve => setImmediate(resolve));

// 只配官方机器人，进群事件在引导已打开后到达。末步仍应能试发，确认收到后才记完成。
async function sendToOfficialGroup() {
  status = CONNECTING;
  pages = [QQBOT_PAGE];
  mark = {testSent: false};
  pollReplies = [{body: OFFICIAL}];
  await setup.openSetup();
  await flushResponses();
  status = OFFICIAL;
  await button('setup-next').click();
  groups = [GROUP];
  await button('setup-next').click();
  await flushResponses();

  eq(button('setup-send-target')?.value, GROUP.sender + '|1|' + GROUP.num, '末步列出新登记的官方群');
  await button('setup-send')?.click();
  eq(sentRequests.map(({platform, type, num}) => ({platform, type, num})),
    [{platform: GROUP.sender, type: 1, num: GROUP.num}], '试发把名单上的官方群和发信平台一起交给接口');
  eq(mark.testSent, false, '还未确认收到时未记完成');
  await button('setup-got')?.click();
  eq(mark.testSent, true, '确认收到后记下 testSent');
  eq(!!button('setup-enter'), true, '确认收到后可从引导进入控制台');
}

// 状态接口临时回 JSON 错误体。引导应提示重试，下一趟取到在线状态后即可继续。
async function retryOfficialStatus() {
  status = CONNECTING;
  pages = [QQBOT_PAGE];
  mark = {testSent: false};
  pollReplies = [{code: 500, body: {status: 500, error: 'Internal Server Error'}}, {body: OFFICIAL}];
  const {setTimeout, clearTimeout} = globalThis;
  const timers = new Map();
  let nextId = 0;
  globalThis.setTimeout = callback => { timers.set(++nextId, callback); return nextId; };
  globalThis.clearTimeout = id => timers.delete(id);
  try {
    await setup.openSetup();
    await flushResponses();
    const result = find(main, item => item.getAttribute('role') === 'status');
    eq(result?.textContent.includes('正在重试'), true, 'HTTP 500 时显示重试提示');
    eq(button('setup-next')?.disabled, true, '状态未恢复时仍不能继续');
    const retry = timers.values().next().value;
    eq(!!retry, true, 'HTTP 500 后安排下一次状态轮询');
    status = OFFICIAL;
    await retry?.();
    eq(result?.textContent.includes('已连接'), true, '状态恢复后显示已连接');
    eq(button('setup-next')?.disabled, false, '状态恢复后无需重开即可继续');
  } finally {
    setup.stopSetupPolling();
    Object.assign(globalThis, {setTimeout, clearTimeout});
  }
}

const cases = {marks: checkTestSentMarks, 'official-send': sendToOfficialGroup, 'status-retry': retryOfficialStatus};
const {store} = await import('../../../main/resources/config-ui/store.js');
store.tab = 'setup';
const name = process.argv[2];
if (name) await cases[name]();
else for (const check of Object.values(cases)) await check();

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
