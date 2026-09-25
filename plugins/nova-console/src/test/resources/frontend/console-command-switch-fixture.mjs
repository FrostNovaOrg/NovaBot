/**
 * 控制台「本群设置 · 命令」那一行，开关显示的是不是真事
 *
 * 一条命令底下分几种用法之后（本场／累计、开播名单／动态名单、开关式与只取消的旧名），
 * 群里「禁用命令」关掉的是<b>用法</b>，不是命令正名。页面要是还按命令正名去对账，
 * 群里已经关掉的那两种问法在屏幕上仍显示成开着——使用者照着页面以为没关，
 * 群里发了却回「本群已关闭」，两头对不上而屏幕上一切正常。
 *
 * 本尺<b>真跑</b>：假 DOM 与假 fetch 之外，画开关那一段用的是产品码那一份
 * （sessions.js 的 sessionSettings、push-model.js 的 commandGroups／commandSummary）。
 * 只核对回包里有没有某个字段是不行的——字段在、画错了，照样绿。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ConsoleCommandSwitchFixtureTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
    const stack = String(err && err.stack ? err.stack : '');
    const frame = stack.split('\n').find(line => line.includes('console-command-switch-fixture.mjs')) || '';
    const where = frame.replace(/^\s*at\s+/, '').trim();
    failures.push(name + '：' + (err && err.message ? err.message : String(err)) + (where ? ' [' + where + ']' : ''));
  }
}

function same(got, want, what) {
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) {
    throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
  }
}

// —— 假 DOM ——

class MiniNode {
  constructor(tagName) {
    this.tagName = String(tagName || '').toLowerCase();
    this.children = [];
    this.parentNode = null;
    this.className = '';
    this.id = '';
    this._text = '';
    this._html = '';
    this.attributes = {};
    this.style = {};
    this.listeners = {};
    this.disabled = false;
    this.checked = false;
    this.hidden = false;
    this.title = '';
    this.type = '';
    this.value = '';
  }

  get textContent() {
    return this._text;
  }

  set textContent(value) {
    this._text = String(value ?? '');
  }

  get innerHTML() {
    return this._html;
  }

  set innerHTML(html) {
    this._html = String(html ?? '');
    this.children = [];
    // 只把 <input …> 立成真节点：本页会往 label 里塞一个复选框再取回来
    const re = /<input\b([^>]*)>/gi;
    let match;
    while ((match = re.exec(this._html))) {
      const input = new MiniNode('input');
      const attrs = match[1];
      const typeMatch = /type\s*=\s*"([^"]*)"/i.exec(attrs);
      if (typeMatch) input.type = typeMatch[1];
      if (/\bchecked\b/i.test(attrs)) input.checked = true;
      const ariaMatch = /aria-label\s*=\s*"([^"]*)"/i.exec(attrs);
      if (ariaMatch) input.attributes['aria-label'] = ariaMatch[1];
      this.appendChild(input);
    }
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  append(...items) {
    for (const item of items) {
      if (typeof item === 'string') {
        const text = new MiniNode('#text');
        text.textContent = item;
        this.appendChild(text);
      } else {
        this.appendChild(item);
      }
    }
  }

  querySelector(selector) {
    return findAll(this, selector)[0] || null;
  }

  querySelectorAll(selector) {
    return findAll(this, selector);
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
    if (name === 'class') this.className = String(value);
  }

  getAttribute(name) {
    if (name === 'id' && this.id) return this.id;
    if (name === 'class' && this.className) return this.className;
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  removeEventListener(type, fn) {
    const list = this.listeners[type] || [];
    const index = list.indexOf(fn);
    if (index >= 0) list.splice(index, 1);
  }

  dispatchEvent(event) {
    const list = (this.listeners[event.type] || []).slice();
    for (const fn of list) fn.call(this, event);
    return true;
  }

  click() {
    this.dispatchEvent({type: 'click', target: this, stopPropagation() {}});
  }
}

function findAll(root, selector) {
  const sel = String(selector).trim();
  const out = [];
  const walk = node => {
    if (matches(node, sel)) out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(root);
  return out;
}

function matches(node, sel) {
  if (sel.startsWith('#')) return node.id === sel.slice(1);
  if (sel.startsWith('.')) {
    return String(node.className || '').split(/\s+/).includes(sel.slice(1));
  }
  return node.tagName === sel.toLowerCase();
}

const byId = new Map();

function idNode(id) {
  if (!byId.has(id)) {
    const node = new MiniNode('div');
    node.id = id;
    byId.set(id, node);
  }
  return byId.get(id);
}

const htmlClasses = new Set();

globalThis.document = {
  querySelector: selector => {
    const sel = String(selector);
    if (sel.startsWith('#')) return idNode(sel.slice(1));
    return null;
  },
  createElement: tag => new MiniNode(tag),
  createTextNode: text => {
    const node = new MiniNode('#text');
    node.textContent = text;
    return node;
  },
  addEventListener() {},
  removeEventListener() {},
  body: idNode('body'),
  activeElement: null,
  documentElement: {
    classList: {
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};

globalThis.location = {hash: '', href: '', replace() {}, reload() {}};

// —— 假接口 ——
// 回包里带 usages：一条命令底下分哪几种用法、这台机器上每一格开没开，由服务端答
// （照 RuntimeStateController 那一份）。这里要量的是「给了这份之后页面画得对不对」。

const mock = {
  requests: [],
  state: {
    commands: [
      {
        name: '@名单', description: '看谁订阅了开播与动态提醒', category: '提醒',
        requiresAdmin: false, disableable: true, available: true,
        usages: [{key: '开播@名单', available: true}, {key: '动态@名单', available: true}],
      },
      {
        name: '直播间数据', description: '查一位主播的直播数据', category: '数据',
        requiresAdmin: false, disableable: true, available: true,
        usages: [{key: '直播间数据', available: true}, {key: '直播间总数据', available: false}],
      },
      {
        name: '直播报告', description: '下播后看一场的报告', category: '报告',
        requiresAdmin: false, disableable: true, available: true,
      },
    ],
    sessions: [{
      platform: 'q', num: 12345, type: 1,
      disabled: ['开播@名单', '动态@名单'],
      menuHidden: [],
      menuNotes: {},
      revenueVisible: false,
      revenueExplicit: true,
      streamers: ['甲'],
    }],
    subscriptions: [],
    incomplete: [],
    totalDataAvailable: false,
  },
};

const TARGET = {platform: 'q', type: 1, num: 12345};

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function sessionOf(state) {
  return state.sessions.find(item => item.platform === TARGET.platform && item.num === TARGET.num);
}

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  mock.requests.push({path: String(path), method, body: (opt || {}).body});
  const url = String(path);
  if (method === 'GET') {
    if (url.endsWith('/state')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve(clone(mock.state))});
    }
    if (url.includes('/at-all/quota')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve({bots: [], sessions: []})});
    }
    return Promise.resolve({status: 200, json: () => Promise.resolve({})});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({success: true, message: '已保存'})});
};

// —— 载入产品码 ——

let sessions = null;
let model = null;
try {
  model = await import(join(pages, 'push-model.js'));
  sessions = await import(join(pages, 'sessions.js'));
} catch (err) {
  failures.push('载入 sessions.js／push-model.js 就抛了：' + err.message);
}

function buildCtx(state) {
  const session = sessionOf(state);
  return {
    session, target: TARGET,
    commands: state.commands,
    subscriptions: state.subscriptions,
    totalDataAvailable: state.totalDataAvailable,
    summary: {
      revenue: model.revenueSummary(session),
      command: model.commandSummary(state.commands, session, state.totalDataAvailable),
      subscription: model.subscriptionSummary(state.subscriptions, session),
      atAll: model.atAllStatus({bots: [], sessions: []}, session, TARGET, {}),
    },
    groups: model.commandGroups(state.commands, session, state.totalDataAvailable),
  };
}

const host = new MiniNode('div');

function paint(state) {
  host.innerHTML = '';
  if (sessions) sessions.sessionSettings(host, buildCtx(state));
}

/** 按给定的禁用清单重画一遍 */
function paintWith(disabled) {
  const state = clone(mock.state);
  sessionOf(state).disabled = disabled.slice();
  paint(state);
  return state;
}

function allNodes(scope) {
  const out = [];
  const walk = node => {
    out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(scope);
  return out;
}

function inputByLabel(scope, label) {
  return allNodes(scope).find(node =>
    node.tagName === 'input' && node.getAttribute('aria-label') === label) || null;
}

await ask('① 群里关掉的那两种用法，页面上都显示成关着，主开关也没有亮着', async () => {
  if (!sessions || !model) throw new Error('产品码没载入，无从量起');
  paintWith(['开播@名单', '动态@名单']);

  const master = inputByLabel(host, '@名单');
  const live = inputByLabel(host, '开播@名单');
  const dynamic = inputByLabel(host, '动态@名单');

  same({
    '主开关画出来了': !!master,
    '主开关亮着': !!(master && master.checked),
    '开播那一格画出来了': !!live,
    '开播那一格亮着': !!(live && live.checked),
    '动态那一格画出来了': !!dynamic,
    '动态那一格亮着': !!(dynamic && dynamic.checked),
  }, {
    '主开关画出来了': true,
    '主开关亮着': false,
    '开播那一格画出来了': true,
    '开播那一格亮着': false,
    '动态那一格画出来了': true,
    '动态那一格亮着': false,
  }, '群里关掉的两种用法');
});

await ask('② 只关了其中一格，主开关也不该亮着（半开不是全开）', async () => {
  if (!sessions || !model) throw new Error('产品码没载入，无从量起');
  paintWith(['开播@名单']);

  const master = inputByLabel(host, '@名单');
  const live = inputByLabel(host, '开播@名单');
  const dynamic = inputByLabel(host, '动态@名单');

  same({
    '主开关亮着': !!(master && master.checked),
    '开播那一格亮着': !!(live && live.checked),
    '动态那一格亮着': !!(dynamic && dynamic.checked),
  }, {
    '主开关亮着': false,
    '开播那一格亮着': false,
    '动态那一格亮着': true,
  }, '只关一格');
});

await ask('③ 本机没开累计数据，累计那一半置灰，没被连累的那一半照旧', async () => {
  if (!sessions || !model) throw new Error('产品码没载入，无从量起');
  paintWith([]);

  const master = inputByLabel(host, '直播间数据');
  const live = inputByLabel(host, '直播间数据');
  const total = inputByLabel(host, '直播间总数据');

  same({
    '主开关亮着': !!(master && master.checked),
    '本场那一格锁着': !!(live && live.disabled),
    '累计那一格画出来了': !!total,
    '累计那一格锁着': !!(total && total.disabled),
    '累计那一格亮着': !!(total && total.checked),
  }, {
    '主开关亮着': true,
    '本场那一格锁着': false,
    '累计那一格画出来了': true,
    '累计那一格锁着': true,
    '累计那一格亮着': false,
  }, '累计那一半置灰');
});

await ask('④ 只有一种用法的命令，开关还是它自己那一个（形状没被改掉）', async () => {
  if (!sessions || !model) throw new Error('产品码没载入，无从量起');
  paintWith([]);

  const only = inputByLabel(host, '直播报告');

  same({
    '开关画出来了': !!only,
    '开关亮着': !!(only && only.checked),
    '开关锁着': !!(only && only.disabled),
  }, {
    '开关画出来了': true,
    '开关亮着': true,
    '开关锁着': false,
  }, '单用法命令');
});

await ask('⑤ 摘要把群里关掉的那两格算成「被群管理员关了」，不算残留记录', async () => {
  if (!sessions || !model) throw new Error('产品码没载入，无从量起');
  const state = clone(mock.state);
  const summary = model.commandSummary(state.commands, sessionOf(state), state.totalDataAvailable);

  same({
    '被关的那几格': summary.offNames,
    '残留记录': summary.strayNames,
  }, {
    '被关的那几格': ['开播@名单', '动态@名单'],
    '残留记录': [],
  }, '摘要对账');
});

await ask('⑥ 状态文件里留着的旧命令正名，算残留记录（阴性对照）', async () => {
  if (!sessions || !model) throw new Error('产品码没载入，无从量起');
  const state = clone(mock.state);
  sessionOf(state).disabled = ['@名单'];
  const summary = model.commandSummary(state.commands, sessionOf(state), state.totalDataAvailable);

  same({
    '被关的那几格': summary.offNames,
    '残留记录': summary.strayNames,
  }, {
    '被关的那几格': [],
    '残留记录': ['@名单'],
  }, '旧正名算残留');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
