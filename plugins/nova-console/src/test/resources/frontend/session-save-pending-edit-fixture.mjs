/**
 * 本群设置：点保存之后、回包之前又改的那一档，不许被当成已存
 *
 * 用户那头的故障：静音时段从「跟全局」选成「本会话不静音」，点保存，接口还没回来，
 * 又选成「本会话自己的时段」。回包一到改动条说存好了，再点保存也不发后面那一档。
 * 它从没进过状态文件，刷新页面就退回「跟全局」。
 * 根子在 done()：那一笔回包时清的是草稿槽位「此刻」这一份，而写请求发出去的是
 * 点下保存那一刻那一份——挂着的那段时间里换成的新草稿被一并抹掉了。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，静音那几档的点选与 save 用的都是产品码那一份，
 * 回包放不放行由本尺攥着。只 includes 一段字样是不行的：清错草稿照样写着「已保存」。
 * 假接口把 /state/ 的写口真的记进自己那份状态：不记的话重取会把档位拨回原样，量不出差别。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 量程：不带参数即源码树里那一份；带一份 config-ui-pages 拷贝的绝对路径即量那份拷贝
 * （量一份改过的拷贝时用：拷贝里动了手脚的产品码）。由 SessionSavePendingEditTest 拉起。
 */

import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const pages = process.argv[2]
  ? process.argv[2]
  : join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');

const failures = [];
let checks = 0;

async function ask(name, fn) {
  checks++;
  try {
    await fn();
  } catch (err) {
    const stack = String(err && err.stack ? err.stack : '');
    const frame = stack.split('\n').find(line => line.includes('session-save-pending-edit-fixture.mjs')) || '';
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
    if (!child) return child;
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
  if (sel.startsWith('.')) return String(node.className || '').split(/\s+/).includes(sel.slice(1));
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
    return sel.startsWith('#') ? idNode(sel.slice(1)) : null;
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
      toggle: (name, on) => {
        if (on) htmlClasses.add(name);
        else htmlClasses.delete(name);
      },
      contains: name => htmlClasses.has(name),
    },
  },
};

globalThis.location = {hash: '', href: '', replace() {}, reload() {}};

// —— 假接口：写口真的记进自己那份状态，POST /state/quiet 可以攥着不回 ——

const mock = {requests: []};

/** 攥着的 POST /state/quiet 回包，按放行才回 */
let holdQuiet = false;
const quietWaiters = [];

const TARGET = {platform: 'q', type: 1, num: 12345};

function sessionOf(state) {
  return state.sessions.find(item => item.platform === TARGET.platform && item.num === TARGET.num);
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function applyStateWrite(path, body) {
  const data = JSON.parse(body || '{}');
  const session = sessionOf(mock.state);
  if (path.endsWith('/state/quiet')) {
    session.quietMode = data.mode || 'follow';
    session.quietStart = data.mode === 'custom' ? data.start : null;
    session.quietEnd = data.mode === 'custom' ? data.end : null;
    session.quietActive = false;
  }
}

function datasourceBody() {
  return JSON.stringify([{
    platform: 'p', uid: 1, _uname: '甲', enabled: true,
    targets: [{platform: 'q', type: 1, num: 12345, enabled: true, messages: []}],
  }]);
}

function ok(payload) {
  return Promise.resolve({status: 200, json: () => Promise.resolve(payload)});
}

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  const body = (opt || {}).body;
  mock.requests.push({path: String(path), method, body});
  const url = String(path);

  if (method === 'GET') {
    if (url.endsWith('/datasource')) return ok({content: datasourceBody()});
    if (url.endsWith('/state')) return ok(clone(mock.state));
    if (url.includes('/push-history')) return ok({records: []});
    if (url.includes('/at-all/quota')) return ok({bots: [], sessions: []});
    if (url.includes('/bot/targets')) return ok({items: []});
    return ok({});
  }

  if (url.endsWith('/state/quiet')) {
    const reply = {success: true, message: '已保存'};
    const land = () => {
      applyStateWrite(url, body);
      return Promise.resolve({status: 200, json: () => Promise.resolve(reply)});
    };
    if (holdQuiet) return new Promise(resolve => quietWaiters.push(() => resolve(land())));
    return land();
  }
  return ok({success: true, message: '已保存'});
};

function releaseQuiet() {
  while (quietWaiters.length) quietWaiters.shift()();
}

function quietPosts() {
  return mock.requests.filter(item => item.method === 'POST' && item.path.endsWith('/state/quiet'));
}

// —— 载入产品码 ——

let sessions = null;
let push = null;
let model = null;
try {
  push = await import(join(pages, 'push.js'));
  model = await import(join(pages, 'push-model.js'));
  sessions = await import(join(pages, 'sessions.js'));
} catch (err) {
  failures.push('载入 sessions.js／push.js 就抛了：' + err.message);
}

mock.state = {
  commands: [],
  sessions: [{
    platform: 'q', num: 12345, type: 1,
    disabled: [], menuHidden: [], menuNotes: {},
    revenueVisible: false, revenueExplicit: true,
    quietMode: 'follow', quietStart: null, quietEnd: null, quietActive: false,
    streamers: ['甲'],
  }],
  quietGlobal: {start: '23:00', end: '08:00'},
  subscriptions: [],
  incomplete: [],
  totalDataAvailable: true,
};

function buildCtx(state) {
  const session = sessionOf(state);
  return {
    session, target: TARGET,
    commands: state.commands,
    subscriptions: state.subscriptions,
    totalDataAvailable: state.totalDataAvailable,
    quietGlobal: state.quietGlobal,
    summary: {
      revenue: model.revenueSummary(session),
      quiet: model.quietSummary(session, state.quietGlobal),
      command: model.commandSummary(state.commands, session, state.totalDataAvailable),
      subscription: model.subscriptionSummary(state.subscriptions, session),
      atAll: model.atAllStatus({bots: [], sessions: []}, session, TARGET, {}),
    },
    groups: model.commandGroups(state.commands, session, state.totalDataAvailable),
  };
}

const host = new MiniNode('div');

function paintFrom(state) {
  host.innerHTML = '';
  if (sessions) sessions.sessionSettings(host, buildCtx(state));
}

const allNodes = scope => {
  const out = [];
  const walk = node => {
    out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(scope);
  return out;
};

function inputByLabel(scope, label) {
  return allNodes(scope).find(node =>
    node.tagName === 'input' && node.getAttribute('aria-label') === label) || null;
}

function choose(input, what) {
  if (!input) throw new Error('没找到「' + what + '」那一档');
  input.checked = true;
  input.dispatchEvent({type: 'change', target: input});
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

/** 回到「刚打开这一段」：服务端是「跟全局」，草稿清空 */
async function fresh() {
  holdQuiet = false;
  quietWaiters.length = 0;
  mock.state.sessions[0].quietMode = 'follow';
  mock.state.sessions[0].quietStart = null;
  mock.state.sessions[0].quietEnd = null;
  mock.requests.length = 0;
  if (push) {
    await push.reload();
    // reload 清的是草稿；这一段的草稿住在 session-draft 那份表里，push.reload 已经清过了
    paintFrom(clone(mock.state));
  }
  mock.requests.length = 0;
}

await ask('① 静音从「跟全局」改成「不静音」、点保存，回包前又改成「自己的时段」：这一档还在，下次保存发出去', async () => {
  if (!push || !sessions || !model) throw new Error('产品码没载入，无从量起');
  await fresh();

  // A→B：选「本会话不静音」，这是要存的那一档
  choose(inputByLabel(host, '本会话不静音'), '本会话不静音');
  await settle();
  if (push.changeCount() !== 1) {
    throw new Error('改完一档该算 1 处，实测 ' + push.changeCount());
  }

  // 点保存，接口攥着不回——回包之前的那段窗口
  holdQuiet = true;
  const saving = push.save();
  await settle();
  if (!quietPosts().length) throw new Error('点下保存就该把静音写出去，实测一条都没发');

  // 这段时间里又选「本会话自己的时段」并填上起止：这一档还没存上
  choose(inputByLabel(host, '本会话自己的时段'), '本会话自己的时段');
  await settle();
  // 起止各拨一下即整段重画一次，两框得各找各的——拿旧框拨只会写进旧那一份草稿
  const start = inputByLabel(host, '静音开始');
  if (!start) throw new Error('选了自己的时段就该有起止两框');
  start.value = '22:00';
  start.dispatchEvent({type: 'change', target: start});
  await settle();
  const end = inputByLabel(host, '静音结束');
  if (!end) throw new Error('选了自己的时段就该有起止两框');
  end.value = '07:00';
  end.dispatchEvent({type: 'change', target: end});
  await settle();

  // 回包放行
  holdQuiet = false;
  releaseQuiet();
  await saving;
  await settle();

  const after = {
    '回包后改动条仍有 1 处': push.changeCount() === 1,
    '发出去的那一下是不静音': quietPosts().length === 1
      && JSON.parse(quietPosts()[0].body).mode === 'off',
  };

  // 再点保存：改成的那一档要真的发出去
  mock.requests.length = 0;
  await push.save();
  await settle();
  const posts = quietPosts();
  const body = posts.length ? (({mode, start, end}) => ({mode, start, end}))(JSON.parse(posts[0].body)) : null;

  same({
    after,
    '再点保存发了几下': posts.length,
    '发出去的档位': body,
  }, {
    after: {'回包后改动条仍有 1 处': true, '发出去的那一下是不静音': true},
    '再点保存发了几下': 1,
    '发出去的档位': {mode: 'custom', start: '22:00', end: '07:00'},
  }, '回包前又改');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
