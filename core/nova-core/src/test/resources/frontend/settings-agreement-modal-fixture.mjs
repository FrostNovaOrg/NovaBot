/**
 * 设置页「使用协议」：全文不平铺，点「查看全文」在弹层里看
 *
 * 从前整篇协议直接铺在设置页上，要翻过它才看得到下面的设置。
 * 改成按钮点开之后还有两件容易弄丢的事：弹层里要真能读到全文（取不到得说明），
 * 以及 Esc／关闭钮／遮罩都关得掉、关完焦点回到按钮。
 *
 * 本尺<b>真跑</b>：agreementCard 切段注入依赖后执行，弹层走 confirm.js 那份 showDoc。
 * 只 includes 一段字样是不行的——全文照样铺着、「查看全文」只是个摆设，字样也都在。
 *
 * 各段各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 SettingsAgreementModalTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ui = join(here, '..', '..', '..', 'main', 'resources', 'config-ui');

const failures = [];
let checks = 0;

async function section(what, run) {
  try {
    await run();
  } catch (e) {
    checks++;
    failures.push(what + ' 这一段抛了：' + (e && e.message ? e.message : String(e)));
  }
}

function eq(actual, expected, what) {
  checks++;
  if (actual === expected) return;
  // 节点这类对象按同一性算：JSON.stringify 会把两只不同的节点都压成 {}，比不出来
  if (actual !== null && typeof actual === 'object') {
    failures.push(what + '：不是同一个对象');
    return;
  }
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// —— 假 DOM：够 confirm.js 画弹层、够 agreementCard 建卡 ——

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
    this.hidden = false;
    this.disabled = false;
    this.type = '';
    this.value = '';
    this.title = '';
    const self = this;
    this.classList = {
      toggle(name, force) {
        const set = new Set(String(self.className || '').split(/\s+/).filter(Boolean));
        const on = force === undefined ? !set.has(name) : !!force;
        if (on) set.add(name);
        else set.delete(name);
        self.className = [...set].join(' ');
        return on;
      },
      add(name) {
        this.toggle(name, true);
      },
      remove(name) {
        this.toggle(name, false);
      },
      contains(name) {
        return String(self.className || '').split(/\s+/).includes(name);
      },
    };
  }

  get textContent() {
    if (this.children.length && this.tagName !== '#text') {
      return this.children.map(c => c.textContent).join('');
    }
    return this._text;
  }

  set textContent(value) {
    this._text = String(value ?? '');
    this.children = [];
    this._html = '';
  }

  get innerHTML() {
    return this._html;
  }

  set innerHTML(html) {
    this._html = String(html ?? '');
    this.children = [];
    parseInto(this, this._html);
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
    if (name === 'class') this.className = String(value);
    if (name === 'type') this.type = String(value);
    if (name === 'hidden') this.hidden = true;
    if (name === 'disabled') this.disabled = true;
    if (name === 'title') this.title = String(value);
    if (name === 'value') this.value = String(value);
  }

  getAttribute(name) {
    if (name === 'id' && this.id) return this.id;
    if (name === 'class' && this.className) return this.className;
    if (name === 'hidden') return this.hidden ? '' : null;
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  dispatchEvent(event) {
    // 照真 DOM 冒泡：confirm.js 的按钮监听挂在弹层根上，不冒泡就点不动
    if (!event.target) event.target = this;
    let node = this;
    while (node) {
      const list = (node.listeners[event.type] || []).slice();
      for (const fn of list) fn.call(node, event);
      node = node.parentNode;
    }
    return true;
  }

  click() {
    this.dispatchEvent({type: 'click', target: this, preventDefault() {}, stopPropagation() {}});
  }

  focus() {
    currentFocus = this;
  }

  querySelector(selector) {
    return findAll(this, selector)[0] || null;
  }

  querySelectorAll(selector) {
    return findAll(this, selector);
  }

  closest(selector) {
    let node = this;
    while (node) {
      if (matches(node, selector)) return node;
      node = node.parentNode;
    }
    return null;
  }
}

function applyAttrs(node, raw) {
  const re = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*"([^"]*)"|([a-zA-Z_:][-a-zA-Z0-9_:.]*)/g;
  let m;
  while ((m = re.exec(raw))) {
    if (m[1] != null) node.setAttribute(m[1], m[2]);
    else if (m[3] != null) node.setAttribute(m[3], '');
  }
}

/** 够 confirm.js 那份 innerHTML 用的浅解析：标签嵌套＋属性＋文本 */
function parseInto(parent, html) {
  const stack = [parent];
  const re = /<(\/?)([a-zA-Z0-9]+)([^>]*)>|([^<]+)/g;
  let m;
  while ((m = re.exec(html))) {
    if (m[4] != null) {
      const text = m[4];
      if (text.trim()) {
        const node = new MiniNode('#text');
        node.textContent = text;
        stack[stack.length - 1].appendChild(node);
      }
      continue;
    }
    const closing = m[1] === '/';
    const tag = m[2].toLowerCase();
    const rawAttrs = m[3] || '';
    if (closing) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const node = new MiniNode(tag);
    applyAttrs(node, rawAttrs);
    stack[stack.length - 1].appendChild(node);
    const selfClose = /\/\s*$/.test(rawAttrs) || tag === 'input' || tag === 'br' || tag === 'img';
    if (!selfClose) stack.push(node);
  }
}

function matches(node, sel) {
  const s = String(sel).trim();
  const attr = /^\[([a-zA-Z_-]+)(?:="([^"]*)")?\]$/.exec(s);
  if (attr) {
    const value = node.getAttribute(attr[1]);
    if (value == null) return false;
    return attr[2] === undefined || value === attr[2];
  }
  if (s.startsWith('#')) return node.id === s.slice(1);
  if (s.startsWith('.')) return String(node.className || '').split(/\s+/).includes(s.slice(1));
  return node.tagName === s.toLowerCase();
}

function findAll(root, selector) {
  const out = [];
  const walk = node => {
    if (matches(node, selector)) out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(root);
  return out;
}

let currentFocus = null;
const docListeners = {};

globalThis.document = {
  createElement: tag => new MiniNode(tag),
  createTextNode: text => {
    const node = new MiniNode('#text');
    node.textContent = text;
    return node;
  },
  addEventListener(type, fn) {
    (docListeners[type] = docListeners[type] || []).push(fn);
  },
  removeEventListener() {},
  get activeElement() {
    return currentFocus;
  },
  body: null,
  documentElement: {classList: {toggle() {}, contains() { return false; }}},
  querySelector: selector => (globalThis.document.body && globalThis.document.body.querySelector(selector)) || null,
};

globalThis.document.body = new MiniNode('body');

function pressKey(key) {
  const ev = {key, preventDefault() {}};
  for (const fn of docListeners.keydown || []) fn(ev);
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

// —— 真弹层 ——

let showDoc = null;
try {
  const confirm = await import(join(ui, 'confirm.js'));
  showDoc = confirm.showDoc;
} catch (err) {
  failures.push('载入 confirm.js 就抛了：' + err.message);
}

// —— agreementCard 切段真跑 ——

function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

const PROBE = 'NovaBot 是 Nova 系列';
let agreementText = PROBE + '的一份协议正文，夹具探针用。';
let agreementFail = false;

function buildCard() {
  const auth = readFileSync(join(ui, 'settings-auth.js'), 'utf8');
  const cardSrc = bracedFrom(auth, 'function agreementCard');
  if (!cardSrc) throw new Error('找得到 agreementCard');
  const calls = [];
  const api = path => {
    calls.push(path);
    if (agreementFail) return Promise.reject(new Error('服务端没给'));
    return Promise.resolve({success: true, text: agreementText});
  };
  const el = (t, c) => {
    const e = new MiniNode(t);
    if (c) e.className = c;
    return e;
  };
  const authRow = (id, title, desc) => {
    const row = el('div', 'auth-row');
    row.id = id;
    const meta = el('div', 'meta');
    const cell = el('div', 'cell');
    row.appendChild(meta);
    row.appendChild(cell);
    return {row, meta, cell};
  };
  const keyLine = () => {};
  const acceptedLine = () => '已同意第 1 版';
  const report = (box, res) => {
    box.said = res;
  };
  const revokeAgreement = () => {};
  const ask = () => Promise.resolve(false);
  // agreementCard 体里拿那三个 key 当参数递给 keyLine，不注入就整段抛
  const fn = new Function(
    'authRow', 'keyLine', 'el', 'acceptedLine', 'api', 'report', 'revokeAgreement', 'ask', 'showDoc',
    'AGREEMENT_VERSION_KEY', 'AGREEMENT_TIME_KEY', 'AGREEMENT_BY_KEY',
    cardSrc + '\nreturn agreementCard;')(
    authRow, keyLine, el, acceptedLine, api, report, revokeAgreement, ask, showDoc,
    'novabot.core.config-ui.agreement.accepted-version',
    'novabot.core.config-ui.agreement.accepted-at',
    'novabot.core.config-ui.agreement.accepted-by');
  return {row: fn(), calls};
}

function textOf(node) {
  return node ? node.textContent : '';
}

// ---- (a) 一打开不铺全文 ----

await section('设置页不平铺协议全文', async () => {
  agreementFail = false;
  const {row} = buildCard();
  await settle();
  eq(textOf(row).includes(PROBE), false, '卡片一建好就铺着整篇协议');
  eq(!!row.querySelector('#agreement-full'), false, '不再有平铺的全文区');
  const view = row.querySelector('#agreement-view');
  eq(!!view, true, '有一颗查看全文按钮');
  eq(textOf(view), '查看全文', '按钮上写的是查看全文');
  eq(!!row.querySelector('#agreement-revoke'), true, '撤回同意仍原样留着');
  eq(!!row.querySelector('#agreement-record'), true, '已同意第 N 版那一行仍原样留着');
});

// ---- (b) 点开能看到全文，且关得掉 ----

await section('弹层可读可关', async () => {
  if (typeof showDoc !== 'function') throw new Error('showDoc 没载入');
  agreementFail = false;
  const {row} = buildCard();
  await settle();
  const view = row.querySelector('#agreement-view');
  if (!view) throw new Error('没有查看全文按钮');

  view.click();
  await settle();
  const box = globalThis.document.body.querySelector('.ask');
  eq(box && !box.hidden, true, '点查看全文后弹层开着');
  const doc = box && box.querySelector('.ask-doc');
  eq(textOf(doc).includes(PROBE), true, '弹层里读得到协议全文');

  pressKey('Escape');
  await settle();
  eq(box.hidden, true, 'Esc 关得掉弹层');
  eq(currentFocus, view, '关掉后焦点回到按钮');

  view.click();
  await settle();
  eq(box && !box.hidden, true, '再点开仍然开着');
  const closeBtn = box.querySelector('[data-act="yes"]');
  eq(textOf(closeBtn), '关闭', '只留一颗关闭');
  eq(box.querySelector('[data-act="no"]').hidden, true, '没有多余的取消钮');
  closeBtn.click();
  await settle();
  eq(box.hidden, true, '关闭钮关得掉弹层');
  eq(currentFocus, view, '关闭钮关掉后焦点也回按钮');

  view.click();
  await settle();
  box.click();
  await settle();
  eq(box.hidden, true, '点遮罩关得掉弹层');
});

// ---- 取不到正文就在弹层里说明 ----

await section('取不到正文要说明', async () => {
  if (typeof showDoc !== 'function') throw new Error('showDoc 没载入');
  agreementFail = true;
  const {row} = buildCard();
  await settle();
  const view = row.querySelector('#agreement-view');
  if (!view) throw new Error('没有查看全文按钮');
  view.click();
  await settle();
  const box = globalThis.document.body.querySelector('.ask');
  const doc = box && box.querySelector('.ask-doc');
  const text = textOf(doc);
  eq(text.includes(PROBE), false, '取不到时不假装有正文');
  eq(text.length > 0, true, '取不到时弹层里有一句说明');
  pressKey('Escape');
  await settle();
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
