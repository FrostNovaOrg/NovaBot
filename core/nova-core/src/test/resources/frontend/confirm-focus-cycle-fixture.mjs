/**
 * 确认弹层：开着的时候 Tab 只在弹层里打转，跑不到后面的页面上
 *
 * 弹层标了 aria-modal，读屏那一侧守得住；键盘上的 Tab 浏览器却照旧放行——
 * 不拦的话，弹层开着时按 Tab，焦点会一层层走到弹层后面的导航上，
 * 只用键盘的人从此回不到弹层里，得整页 Tab 一圈才绕得回来。
 * 焦点还回触发按钮的那套（Esc／关闭钮／遮罩）另由 settings-agreement-modal-fixture 量。
 *
 * 本尺在假 DOM 上<b>真跑</b> confirm.js，pressKey 按 settings-agreement-modal-fixture
 * 的做法驱动 keydown，并能带上 Shift。带输入栏那一形（输入栏、取消、确认）与
 * 长文那一形（只有关闭；藏起来的取消不算进圈）各转一圈，首尾相接。
 * 圈法本身另量三格：藏起来的那一层里的控件、禁用的控件不进圈；焦点在圈外时
 * Tab 落第一个、Shift+Tab 落最后一个。
 *
 * 各段各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 ConfirmFocusCycleTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

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

// —— 假 DOM：够 confirm.js 画弹层 ——

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

/** 驱动一次 keydown。带不带 Shift 都要能按，preventDefault 记下来给「拦下了」那一格 */
function pressKey(key, shift) {
  const ev = {key, shiftKey: !!shift, prevented: false, preventDefault() { this.prevented = true; }};
  for (const fn of docListeners.keydown || []) fn(ev);
  return ev;
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

// —— 真弹层 ——

let ask = null;
let showDoc = null;
let keepTabInside = null;
try {
  const confirm = await import(join(ui, 'confirm.js'));
  ask = confirm.ask;
  showDoc = confirm.showDoc;
  keepTabInside = confirm.keepTabInside;
} catch (err) {
  failures.push('载入 confirm.js 就抛了：' + err.message);
}

function openBox() {
  const box = globalThis.document.body.querySelector('.ask');
  if (!box || box.hidden) throw new Error('弹层没开着');
  return box;
}

// ---- (a) 带输入栏那一形：输入栏、取消、确认，首尾循环 ----

await section('带输入栏那一形 Tab 首尾循环', async () => {
  if (typeof ask !== 'function') throw new Error('ask 没载入');
  const pending = ask({title: '改名', body: '说一个新名字', fields: [{id: 'ask-name', label: '新名字'}]});
  await settle();
  const box = openBox();
  const input = box.querySelector('#ask-name');
  const no = box.querySelector('[data-act="no"]');
  const yes = box.querySelector('[data-act="yes"]');
  if (!input || !no || !yes) throw new Error('弹层里找不到输入栏或取消、确认');
  eq(currentFocus, input, '一打开焦点在输入栏上');

  let ev = pressKey('Tab');
  eq(ev.prevented, true, 'Tab 被拦下，不交给浏览器走出弹层');
  eq(currentFocus, no, '输入栏上按 Tab 到取消');
  pressKey('Tab');
  eq(currentFocus, yes, '取消上按 Tab 到确认');
  ev = pressKey('Tab');
  eq(currentFocus, input, '最后一个控件上按 Tab 回到第一个');
  eq(ev.prevented, true, '末位上的 Tab 也被拦下');

  pressKey('Tab', true);
  eq(currentFocus, yes, '第一个控件上按 Shift+Tab 回到最后一个');
  pressKey('Tab', true);
  eq(currentFocus, no, '确认上按 Shift+Tab 到取消');
  pressKey('Tab', true);
  eq(currentFocus, input, '取消上按 Shift+Tab 到输入栏');

  let inside = true;
  for (let i = 0; i < 12; i++) {
    pressKey('Tab', i % 2 === 1);
    if (![input, no, yes].includes(currentFocus)) {
      inside = false;
      break;
    }
  }
  eq(inside, true, '来回按 Tab／Shift+Tab，焦点始终不出弹层');

  pressKey('Escape');
  await settle();
  eq(box.hidden, true, 'Esc 仍照旧收掉弹层');
  await pending;
});

// ---- (b) 长文那一形：圈里只有关闭一颗 ----

await section('长文那一形 Tab 出不去', async () => {
  if (typeof showDoc !== 'function') throw new Error('showDoc 没载入');
  const pending = showDoc({title: '协议', text: '一段正文。'});
  await settle();
  const box = openBox();
  const yes = box.querySelector('[data-act="yes"]');
  const no = box.querySelector('[data-act="no"]');
  if (!yes) throw new Error('弹层里找不到关闭钮');
  eq(yes.textContent, '关闭', '长文那一形只有关闭');
  eq(no && no.hidden, true, '取消钮藏起来了');
  eq(currentFocus, yes, '焦点在关闭上');
  let ev = pressKey('Tab');
  eq(ev.prevented, true, 'Tab 被拦下');
  eq(currentFocus, yes, '关闭上按 Tab 还在关闭上');
  pressKey('Tab', true);
  eq(currentFocus, yes, '关闭上按 Shift+Tab 还在关闭上');

  pressKey('Escape');
  await settle();
  eq(box.hidden, true, 'Esc 仍照旧收掉弹层');
  ev = pressKey('Tab');
  eq(ev.prevented, false, '弹层关掉后不再拦 Tab');
  await pending;
});

// —— 下面三段直接量圈法 keepTabInside：哪些控件进圈、圈外按 Tab 落在哪 ——

/** 搭一个圈：children 里每项是 [标签, 设置] 或 [标签, 设置, 子项] */
function build(spec) {
  const node = new MiniNode(spec[0]);
  Object.assign(node, spec[1] || {});
  for (const child of spec[2] || []) node.appendChild(build(child));
  return node;
}

function tabIn(box, shift) {
  const ev = {key: 'Tab', shiftKey: !!shift, prevented: false, preventDefault() { this.prevented = true; }};
  keepTabInside(box, ev);
  return ev;
}

// ---- (c) 上一层藏起来了，下面的控件还在：不进圈 ----
// 抓的故障：弹层里藏起一整块（比如收起的一段输入栏），只查控件自身的 hidden 的话，
// 块里的按钮仍进圈，按 Tab 焦点落到看不见的控件上，人不知道焦点去哪了。

await section('藏起来的那一层里的控件不进圈', async () => {
  if (typeof keepTabInside !== 'function') throw new Error('keepTabInside 没载入');
  const box = build(['div', {}, [
    ['div', {hidden: true}, [['button', {id: 'gone'}]]],
    ['button', {id: 'a'}],
    ['button', {id: 'b'}],
  ]]);
  const [, a, b] = box.children;
  a.focus();
  tabIn(box);
  eq(currentFocus, b, '第一个看得见的控件上按 Tab 到第二个');
  tabIn(box);
  eq(currentFocus, a, '末位上按 Tab 回到第一个看得见的，跳过藏起来那一层里的按钮');
  tabIn(box, true);
  eq(currentFocus, b, '首位上按 Shift+Tab 到末位，不落到藏起来的按钮上');
});

// ---- (d) 禁用的控件不进圈 ----
// 抓的故障：禁用的按钮进了圈，Tab 停到一颗按不动的按钮上，读屏念「不可用」，像是弹层坏了。

await section('禁用的控件不进圈', async () => {
  if (typeof keepTabInside !== 'function') throw new Error('keepTabInside 没载入');
  const box = build(['div', {}, [
    ['button', {id: 'a'}],
    ['button', {id: 'off', disabled: true}],
    ['button', {id: 'b'}],
  ]]);
  const [a, , b] = box.children;
  a.focus();
  tabIn(box);
  eq(currentFocus, b, '第一个上按 Tab 跳过禁用的那颗，到第二颗能按的');
  tabIn(box, true);
  eq(currentFocus, a, '回按 Shift+Tab 也跳过禁用的那颗');
});

// ---- (e) 焦点在圈外：Tab 落第一个，Shift+Tab 落最后一个 ----
// 抓的故障：焦点不在弹层的控件上时（比如刚点了弹层的空白处），按 Shift+Tab 却落到第一个，
// 和浏览器原生「往回走到最后一个」相反，只用键盘的人要多绕一圈。

await section('焦点在圈外时 Tab 落首位、Shift+Tab 落末位', async () => {
  if (typeof keepTabInside !== 'function') throw new Error('keepTabInside 没载入');
  const box = build(['div', {}, [
    ['input', {id: 'a'}],
    ['button', {id: 'b'}],
    ['button', {id: 'c'}],
  ]]);
  const [a, , c] = box.children;
  const outside = new MiniNode('button');

  outside.focus();
  let ev = tabIn(box);
  eq(ev.prevented, true, '圈外按 Tab 也被拦下');
  eq(currentFocus, a, '圈外按 Tab 落到第一个');

  outside.focus();
  ev = tabIn(box, true);
  eq(ev.prevented, true, '圈外按 Shift+Tab 也被拦下');
  eq(currentFocus, c, '圈外按 Shift+Tab 落到最后一个，跟浏览器原生一样');

  currentFocus = null;
  tabIn(box, true);
  eq(currentFocus, c, '焦点不在任何控件上时，Shift+Tab 也落到最后一个');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
