/**
 * 本群设置与上方三段同一条保存纪律：改了先进草稿，按「保存」才写，按「放弃」全部还原
 *
 * 这一行从前是「拨一下就发请求」：改了没点保存，状态文件已经写上了；点「放弃」也撤不回来。
 * 与上方三段（改了进底部改动条）不一致，使用者会在同一页上碰到两套相反的规矩。
 *
 * 本尺<b>真跑</b>：假 DOM 与假 fetch 之外，sessionSettings 的点击、changeCount／save／reload
 * 用的都是产品码那一份。只 includes 一段字样是不行的——请求早发了一拍、放弃撤不回，
 * 字样照样在。假接口把 /state/ 的写口<b>真的记进自己那份状态</b>：
 * 不记的话「放弃了改动还在」在改前码上也会绿，因为重取会把开关拨回原样。
 *
 * 六问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 SessionSettingsDraftTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
    const frame = stack.split('\n').find(line => line.includes('session-settings-draft-fixture.mjs')) || '';
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

// —— 假接口：写口真的记进自己那份状态 ——

const mock = {
  requests: [],
  state: {
    commands: [
      {name: 'cmdA', description: '命令甲', category: '查询', requiresAdmin: false, disableable: true, available: true},
      {name: 'cmdB', description: '命令乙', category: '查询', requiresAdmin: false, disableable: true, available: true},
      {name: 'cmdC', description: '命令丙', category: '管理', requiresAdmin: true, disableable: true, available: true},
    ],
    sessions: [{
      platform: 'q', num: 12345, type: 1,
      disabled: ['cmdB'],
      menuHidden: [],
      menuNotes: {},
      revenueVisible: false,
      revenueExplicit: true,
      streamers: ['甲'],
    }],
    subscriptions: [{
      platform: 'q', num: 12345,
      streamerUid: 1, streamerName: '甲', type: 'live', typeName: '开播',
      users: [1001, 1002],
    }],
    incomplete: [],
    totalDataAvailable: true,
  },
};

const TARGET = {platform: 'q', type: 1, num: 12345};

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function sessionOf(state) {
  return state.sessions.find(item => item.platform === TARGET.platform && item.num === TARGET.num);
}

function applyStateWrite(path, body) {
  const data = JSON.parse(body || '{}');
  const session = sessionOf(mock.state);
  if (path.endsWith('/state/revenue')) {
    session.revenueVisible = !!data.visible;
    session.revenueExplicit = true;
  } else if (path.endsWith('/state/command')) {
    const set = new Set(session.disabled);
    if (data.disabled) set.add(data.command);
    else set.delete(data.command);
    session.disabled = [...set];
  } else if (path.endsWith('/state/commands')) {
    const set = new Set(session.disabled);
    for (const name of data.commands || []) {
      if (data.disabled) set.add(name);
      else set.delete(name);
    }
    session.disabled = [...set];
  } else if (path.endsWith('/state/subscription')) {
    for (const row of mock.state.subscriptions) {
      if (row.platform !== data.platform || row.num !== data.num) continue;
      if (row.streamerUid !== data.streamerUid || row.type !== data.type) continue;
      row.users = data.userUid == null ? [] : row.users.filter(uid => uid !== data.userUid);
    }
    mock.state.subscriptions = mock.state.subscriptions.filter(row => row.users.length);
  }
}

function datasourceBody() {
  return JSON.stringify([{
    platform: 'p', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: 'q', type: 1, num: 12345, enabled: true,
      messages: [{handler: 'x.LiveOn', params: {message: '开播了'}}],
    }],
  }]);
}

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  const body = (opt || {}).body;
  mock.requests.push({path: String(path), method, body});
  const url = String(path);

  if (method === 'GET') {
    if (url.endsWith('/datasource')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve({content: datasourceBody()})});
    }
    if (url.endsWith('/state')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve(clone(mock.state))});
    }
    if (url.includes('/push-history')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve({records: []})});
    }
    if (url.includes('/at-all/quota')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve({bots: [], sessions: []})});
    }
    if (url.includes('/bot/targets')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve({items: []})});
    }
    return Promise.resolve({status: 200, json: () => Promise.resolve({})});
  }

  if (url.includes('/state/')) {
    applyStateWrite(url, body);
    return Promise.resolve({status: 200, json: () => Promise.resolve({success: true, message: '已保存'})});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({success: true, message: '已保存'})});
};

function statePosts() {
  return mock.requests.filter(item => item.method === 'POST' && item.path.includes('/state/'));
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

function buildCtx(state) {
  const session = sessionOf(state);
  const quota = {bots: [], sessions: []};
  return {
    session, target: TARGET,
    commands: state.commands,
    subscriptions: state.subscriptions,
    totalDataAvailable: state.totalDataAvailable,
    summary: {
      revenue: model.revenueSummary(session),
      command: model.commandSummary(state.commands, session, state.totalDataAvailable),
      subscription: model.subscriptionSummary(state.subscriptions, session),
      atAll: model.atAllStatus(quota, session, TARGET, {}),
    },
    groups: model.commandGroups(state.commands, session, state.totalDataAvailable),
  };
}

const host = new MiniNode('div');

function paintFrom(state) {
  host.innerHTML = '';
  if (sessions) sessions.sessionSettings(host, buildCtx(state));
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

function buttonByText(scope, text) {
  return allNodes(scope).find(node =>
    node.tagName === 'button' && node.textContent === text) || null;
}

function buttonsByTitle(scope, title) {
  return allNodes(scope).filter(node =>
    node.tagName === 'button' && node.title === title);
}

function flip(input) {
  input.checked = !input.checked;
  input.dispatchEvent({type: 'change', target: input});
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

async function fresh() {
  mock.requests.length = 0;
  if (push) await push.reload();
  paintFrom(clone(mock.state));
  mock.requests.length = 0;
}

/** 宿主「放弃」对本页做的两件事：让本页重取自己那份，再按服务端此刻那一份重画 */
async function discardAsHostDoes() {
  if (push) await push.reload();
  paintFrom(clone(mock.state));
  mock.requests.length = 0;
}

await ask('① 改金额可见、没点保存：状态文件不该变，改动条上要看得见这一处', async () => {
  if (!push || !sessions) throw new Error('产品码没载入，无从量起');
  await fresh();
  const before = clone(mock.state);
  flip(inputByLabel(host, '直播收益等金额'));
  await settle();
  same({
    '没保存就写了状态': JSON.stringify(mock.state) !== JSON.stringify(before),
    '写状态的请求条数': statePosts().length,
    '改动条算不算它': push.changeCount(),
  }, {
    '没保存就写了状态': false,
    '写状态的请求条数': 0,
    '改动条算不算它': 1,
  }, '金额可见未保存');
});

await ask('② 拨命令开关、没点保存：状态文件不该变，改动条上要看得见这一处', async () => {
  if (!push || !sessions) throw new Error('产品码没载入，无从量起');
  await fresh();
  const before = clone(mock.state);
  flip(inputByLabel(host, 'cmdA'));
  await settle();
  same({
    '没保存就写了状态': JSON.stringify(mock.state) !== JSON.stringify(before),
    '写状态的请求条数': statePosts().length,
    '改动条算不算它': push.changeCount(),
  }, {
    '没保存就写了状态': false,
    '写状态的请求条数': 0,
    '改动条算不算它': 1,
  }, '命令开关未保存');
});

await ask('③ 移除一条订阅、没点保存：状态文件不该变，改动条上要看得见这一处', async () => {
  if (!push || !sessions) throw new Error('产品码没载入，无从量起');
  await fresh();
  const before = clone(mock.state);
  const manage = buttonByText(host, '管理名单');
  if (!manage) throw new Error('没找到「管理名单」按钮');
  manage.click();
  await settle();
  const pills = buttonsByTitle(idNode('push-drawer-body'), '移除');
  if (!pills.length) throw new Error('名单抽屉里没有可移除的订阅');
  pills[0].click();
  await settle();
  same({
    '没保存就写了状态': JSON.stringify(mock.state) !== JSON.stringify(before),
    '写状态的请求条数': statePosts().length,
    '改动条算不算它': push.changeCount(),
  }, {
    '没保存就写了状态': false,
    '写状态的请求条数': 0,
    '改动条算不算它': 1,
  }, '订阅移除未保存');
});

await ask('④ 改完点「放弃」：三处改动全部撤回，状态文件还是原来的', async () => {
  if (!push || !sessions) throw new Error('产品码没载入，无从量起');
  await fresh();
  const before = clone(mock.state);

  flip(inputByLabel(host, '直播收益等金额'));
  await settle();
  flip(inputByLabel(host, 'cmdA'));
  await settle();
  const manage = buttonByText(host, '管理名单');
  if (!manage) throw new Error('没找到「管理名单」按钮');
  manage.click();
  await settle();
  const pills = buttonsByTitle(idNode('push-drawer-body'), '移除');
  if (!pills.length) throw new Error('名单抽屉里没有可移除的订阅');
  pills[0].click();
  await settle();

  await discardAsHostDoes();
  await settle();

  const revenue = inputByLabel(host, '直播收益等金额');
  const cmdA = inputByLabel(host, 'cmdA');
  const manage2 = buttonByText(host, '管理名单');
  manage2.click();
  await settle();
  const left = buttonsByTitle(idNode('push-drawer-body'), '移除').length;

  same({
    '状态文件还是原来的': JSON.stringify(mock.state) === JSON.stringify(before),
    '金额开关回到原样': revenue && revenue.checked === false,
    '命令开关回到原样': cmdA && cmdA.checked === true,
    '订阅人数回到原样': left,
    '放弃后改动数': push.changeCount(),
  }, {
    '状态文件还是原来的': true,
    '金额开关回到原样': true,
    '命令开关回到原样': true,
    '订阅人数回到原样': 2,
    '放弃后改动数': 0,
  }, '放弃');
});

await ask('⑤ 改完点「保存」：这时才写状态文件', async () => {
  if (!push || !sessions) throw new Error('产品码没载入，无从量起');
  await fresh();
  flip(inputByLabel(host, '直播收益等金额'));
  await settle();
  mock.requests.length = 0;
  await push.save();
  await settle();
  same({
    '保存时写状态的请求条数': statePosts().length,
    '保存后改动数': push.changeCount(),
  }, {
    '保存时写状态的请求条数': 1,
    '保存后改动数': 0,
  }, '保存时才写');
});

await ask('⑥ 没改本群设置就点「保存」：不该碰状态文件（阴性对照）', async () => {
  if (!push || !sessions) throw new Error('产品码没载入，无从量起');
  await fresh();
  push.pushEntries()[0].targets[0].enabled = false;
  mock.requests.length = 0;
  await push.save();
  await settle();
  same({
    '保存时写状态的请求条数': statePosts().length,
  }, {
    '保存时写状态的请求条数': 0,
  }, '没改本群就保存');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
