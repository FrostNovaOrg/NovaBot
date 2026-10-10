/**
 * 推送页：点保存之后、回包之前又改的那一处，不许被当成已存
 *
 * 用户那头的故障：点保存，接口还没回来，这时又关掉一类通知。回包一到，改动条说全存上了；
 * 再点保存什么也不发。这一处从没进过 datasource.json，刷新页面就没了。
 * 根子在「存的是哪一份」：发出去的是点下那一刻那一份，对齐快照用的却是回包那一刻的最新一份——
 * 挂着的那段时间里改的东西被一并算成了已存。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，改动走的是产品码那一份 save 与真开关，
 * 回包放不放行由本尺攥着——真机上凑齐「回包之前又改」那个窗口要试很多回。
 * 只 includes 一段字样是不行的：快照取错照样写着「已保存」。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 量程：不带参数即源码树里那一份；带一份 config-ui-pages 拷贝的绝对路径即量那份拷贝
 * （量一份改过的拷贝时用：拷贝里动了手脚的产品码）。由 PushSavePendingEditTest 拉起。
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
    const frame = stack.split('\n').find(line => line.includes('push-save-pending-edit-fixture.mjs')) || '';
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
    // 只把 <input …> 立成真节点：开关要从 label 里取回来拨
    const re = /<input\b([^>]*)>/gi;
    let match;
    while ((match = re.exec(this._html))) {
      const input = new MiniNode('input');
      const attrs = match[1];
      const typeMatch = /type\s*=\s*"([^"]*)"/i.exec(attrs);
      if (typeMatch) input.type = typeMatch[1];
      if (/\bchecked\b/i.test(attrs)) input.checked = true;
      if (/\bdisabled\b/i.test(attrs)) input.disabled = true;
      const ariaMatch = /aria-label\s*=\s*"([^"]*)"/i.exec(attrs);
      if (ariaMatch) input.attributes['aria-label'] = ariaMatch[1];
      this.appendChild(input);
    }
  }

  get href() {
    return this.attributes.href || '';
  }

  set href(value) {
    this.attributes.href = String(value);
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

function classesOf(node) {
  return String(node.className || '').split(/\s+/).filter(Boolean);
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

// —— 假接口：POST /datasource 可以攥着不回 ——

const mock = {requests: []};

/** 攥着的 POST /datasource 回包，按放行才回 */
let holdDatasource = false;
const datasourceWaiters = [];

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
    if (url.endsWith('/state')) return ok({sessions: [], commands: [], subscriptions: [], incomplete: []});
    if (url.includes('/push-history')) return ok({records: []});
    if (url.includes('/at-all/quota')) return ok({bots: [], sessions: []});
    if (url.includes('/bot/targets')) return ok({items: []});
    return ok({});
  }

  if (url.includes('/datasource')) {
    const reply = {success: true, message: '已保存'};
    if (holdDatasource) {
      return new Promise(resolve => datasourceWaiters.push(() => resolve({
        status: 200, json: () => Promise.resolve(reply),
      })));
    }
    return ok(reply);
  }
  return ok({success: true, message: '已保存'});
};

function releaseDatasource() {
  while (datasourceWaiters.length) datasourceWaiters.shift()();
}

function pushPosts() {
  return mock.requests.filter(item => item.method === 'POST' && item.path.includes('/datasource'));
}

function lastPushContent() {
  const posts = pushPosts();
  if (!posts.length) return null;
  return JSON.parse(JSON.parse(posts[posts.length - 1].body).content);
}

// —— 一位主播、一个通道，两类通知都开着 ——

const LIVE_ON = 'x.LiveOn';
const LIVE_OFF = 'x.LiveOff';

function datasourceBody() {
  return JSON.stringify([{
    platform: 'p', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: 'q', type: 1, num: 12345, enabled: true,
      messages: [{handler: LIVE_ON}, {handler: LIVE_OFF}],
    }],
  }]);
}

// —— 载入产品码 ——

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  store = (await import('./store.js')).store;
} catch (err) {
  failures.push('载入 push.js 就抛了：' + err.message);
}

if (push && store) {
  store.handlerList = [
    {className: LIVE_ON, aliases: [], displayName: '开播通知', description: '开播时往这里发一条',
      platform: '', placeholders: [], attachments: [], defaultParams: {}, factoryParams: {}, options: []},
    {className: LIVE_OFF, aliases: [], displayName: '下播通知', description: '下播时往这里发一条',
      platform: '', placeholders: [], attachments: [], defaultParams: {}, factoryParams: {}, options: []},
  ];
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

/** 右区里那类通知的开关：按标题字找「推什么」那一行 */
function noticeSwitch(name) {
  for (const row of allNodes(idNode('push-right'))) {
    if (row.tagName !== 'div' || !classesOf(row).includes('swrow')) continue;
    const txt = row.children.find(child => classesOf(child).includes('swtxt'));
    if (txt && String(txt.innerHTML).includes('<b>' + name + '</b>')) {
      return row.querySelector('input') || null;
    }
  }
  return null;
}

function flip(input, what) {
  if (!input) throw new Error('没找到「' + what + '」的开关');
  input.checked = !input.checked;
  input.dispatchEvent({type: 'change', target: input});
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

/** 回到「刚打开这一页」：配置干净、两类通知都开着 */
async function fresh() {
  holdDatasource = false;
  datasourceWaiters.length = 0;
  mock.requests.length = 0;
  if (push) await push.reload();
  push.showPush('1', '12345');
  await settle();
  mock.requests.length = 0;
}

await ask('① 点保存、回包之前又关掉一类通知：改动条仍有 1 处，再点保存把这一处发出去', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await fresh();

  // 要存的那一处：关掉「开播通知」
  flip(noticeSwitch('开播通知'), '开播通知');
  await settle();
  if (push.changeCount() !== 1) {
    throw new Error('改完一处该算 1 处，实测 ' + push.changeCount());
  }

  // 点保存，接口攥着不回——回包之前的那段窗口
  holdDatasource = true;
  const saving = push.save();
  await settle();
  if (!pushPosts().length) throw new Error('点下保存就该把配置发出去，实测一条都没发');

  // 这段时间里又关掉「下播通知」：这一处还没存上
  flip(noticeSwitch('下播通知'), '下播通知');
  await settle();

  // 回包放行
  holdDatasource = false;
  releaseDatasource();
  await saving;
  await settle();

  const after = {
    '回包后改动条仍有 1 处': push.changeCount() === 1,
    '发出去的那一下只带第一处': (lastPushContent() || [{}])[0].targets[0].messages.length === 1,
  };

  // 再点保存：第二处要真的发出去
  mock.requests.length = 0;
  await push.save();
  await settle();
  const posts = pushPosts();
  const sent = posts.length ? (lastPushContent() || [{}])[0].targets[0].messages : null;

  same({
    after,
    '再点保存发了几下': posts.length,
    '发出去的内容里两类通知都关了': sent ? sent.length === 0 : false,
  }, {
    after: {'回包后改动条仍有 1 处': true, '发出去的那一下只带第一处': true},
    '再点保存发了几下': 1,
    '发出去的内容里两类通知都关了': true,
  }, '回包前又改');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
