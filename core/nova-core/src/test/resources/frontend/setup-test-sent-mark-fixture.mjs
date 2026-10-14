/**
 * 设置引导：试发那一步写「有试发记录」，只在服务端说过有记录的时候
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
    this.classList = {add() {}, remove() {}, toggle() {}, contains: () => false};
  }

  set innerHTML(html) {
    this.children = [];
    this.textContent = '';
  }

  get innerHTML() {
    return '';
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

  addEventListener() {
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

globalThis.document = {
  documentElement: {classList: {toggle() {}, add() {}, remove() {}, contains: () => false}},
  body: new Node('body'),
  head: new Node('head'),
  querySelector: selector => node(selector),
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

let mark = {};

function ok(body) {
  return Promise.resolve({status: 200, ok: true, json: () => Promise.resolve(body)});
}

globalThis.fetch = url => {
  const path = String(url);
  if (path.endsWith('/api/status')) return ok(STATUS);
  if (path.endsWith('/api/login')) return ok(LOGIN);
  if (path.endsWith('/api/setup/state')) return ok(mark);
  if (path.endsWith('/api/setup/bot')) return ok({address: '127.0.0.1', httpPort: 3000, wsPort: 3001});
  if (path.endsWith('/api/pages')) return ok({pages: []});
  if (path.includes('/api/bot/targets')) return ok({targets: []});
  return ok({success: true});
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
  const rail = node('#setup-steps');
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

// 前几步都配好、又要求重走：页上画的是进度条，不是走完后的小结
const RERUN = {rerunRequested: true};

let cell1;
try {
  cell1 = await railFor(Object.assign({}, RERUN));
} catch (err) {
  cell1 = 'error:' + err.message;
}
eq(cell1 && cell1['小字'] === '已有测试发送记录', false,
  '① 回包缺 testSent：试发那一格不写「已有测试发送记录」（读到 ' + JSON.stringify(cell1) + '）');
eq(cell1, {'记号': '✓', '小字': '已配置'}, '① 回包缺 testSent：勾照前几步推断，小字写「已配置」');

let cell2;
try {
  cell2 = await railFor(Object.assign({testSent: false}, RERUN));
} catch (err) {
  cell2 = 'error:' + err.message;
}
// 重走时停在第 1 步，试发那一格还没轮到：画步号 4、不带小字
eq(cell2, {'记号': '4', '小字': ''}, '② testSent 为 false：不打勾');

let cell3;
try {
  cell3 = await railFor(Object.assign({testSent: true, testSentAt: '2026-10-01T08:00:00Z'}, RERUN));
} catch (err) {
  cell3 = 'error:' + err.message;
}
eq(cell3, {'记号': '✓', '小字': '已有测试发送记录'}, '③ testSent 为 true：打勾，写「已有测试发送记录」');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
