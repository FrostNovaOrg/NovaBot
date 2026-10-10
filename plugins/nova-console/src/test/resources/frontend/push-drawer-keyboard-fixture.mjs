/**
 * 推送页「＋ 添加主播」的面板：键盘能用
 *
 * 用户那头的故障：只用键盘的人点开「＋ 添加主播」，焦点没进面板，要先按好几下 Tab 才摸到
 * 输入框；按 Esc 关不掉；在面板里按 Tab 走着走着就落到面板后面那一页上去了；
 * 关掉以后焦点不知去了哪，得从页头重新 Tab 一遍。各格开头写着它抓的是哪一样。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，render 挂的监听、openDrawer／closeDrawer、
 * 添加主播那一份面板、圈 Tab 的 keepTabInside 都是产品码那一份。
 * 假 DOM 的 focus 不管节点藏没藏都会成功，所以「先显示、再聚焦」只能量先后：
 * 面板的 hidden 与各节点的 focus 都记进同一本流水，按先后比。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。由 PushDrawerKeyboardTest 拉起。
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

// —— 流水：面板显隐与每一次聚焦，按发生的先后记 ——

const log = [];

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
    this.dataset = {};
    this.style = {};
    this.listeners = {};
    this.disabled = false;
    this.hidden = false;
    this.type = '';
    this.value = '';
    this.placeholder = '';
    this.root = false;
  }

  get textContent() { return this._text; }
  set textContent(value) { this._text = String(value ?? ''); }
  get innerHTML() { return this._html; }
  set innerHTML(html) {
    this._html = String(html ?? '');
    for (const child of this.children) child.parentNode = null;
    this.children = [];
  }

  /** 挂在页上：一路往上找得到一个根 */
  get isConnected() {
    for (let at = this; at; at = at.parentNode) if (at.root) return true;
    return false;
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  remove() {
    if (!this.parentNode) return;
    const list = this.parentNode.children;
    list.splice(list.indexOf(this), 1);
    this.parentNode = null;
  }

  querySelector(selector) { return findAll(this, selector)[0] || null; }
  querySelectorAll(selector) { return findAll(this, selector); }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener() {}
  dispatchEvent(event) {
    for (const fn of (this.listeners[event.type] || []).slice()) fn.call(this, event);
    return true;
  }
  click() { this.dispatchEvent(fakeEvent('click', this)); }
  focus() {
    log.push('聚焦 ' + nameOf(this));
    globalThis.document.activeElement = this;
  }
}

function findAll(root, selector) {
  const out = [];
  const walk = node => {
    for (const child of node.children || []) {
      if (matches(child, selector)) out.push(child);
      walk(child);
    }
  };
  walk(root);
  return out;
}

function matches(node, selector) {
  return String(selector).split(',').some(part => {
    const sel = part.trim();
    if (sel.startsWith('#')) return node.id === sel.slice(1);
    if (sel.startsWith('.')) return String(node.className || '').split(/\s+/).includes(sel.slice(1));
    const attr = /^([a-z]+)\[([a-z-]+)]$/.exec(sel);
    if (attr) return node.tagName === attr[1] && node.getAttribute(attr[2]) != null;
    return node.tagName === sel.toLowerCase();
  });
}

function fakeEvent(type, target, extra) {
  return Object.assign({
    type, target, key: '', shiftKey: false, defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; },
    stopPropagation() {},
  }, extra || {});
}

function nameOf(node) {
  if (!node) return '无';
  if (node.id) return '#' + node.id;
  return node.tagName + (node.textContent ? '「' + node.textContent + '」' : '');
}

const byId = new Map();

function idNode(id, tag) {
  if (!byId.has(id)) {
    const node = new MiniNode(tag || 'div');
    node.id = id;
    node.root = true;
    byId.set(id, node);
  }
  return byId.get(id);
}

// 面板照 ensureDrawer 那份的结构先摆好：外壳、标题、关闭钮、说明、正文
const drawer = idNode('push-drawer');
const panel = drawer.appendChild(new MiniNode('div'));
panel.className = 'dw';
const head = panel.appendChild(new MiniNode('div'));
head.appendChild(idNode('push-drawer-title', 'h3'));
const closeButton = head.appendChild(idNode('push-drawer-close', 'button'));
closeButton.textContent = '关闭';
panel.appendChild(idNode('push-drawer-lead', 'p'));
panel.appendChild(idNode('push-drawer-body'));
for (const node of [idNode('push-drawer-title'), closeButton, idNode('push-drawer-lead'), idNode('push-drawer-body')]) {
  node.root = false;
}
let drawerHidden = true;
Object.defineProperty(drawer, 'hidden', {
  get: () => drawerHidden,
  set: value => {
    if (!!value !== drawerHidden) log.push(value ? '面板藏起' : '面板显示');
    drawerHidden = !!value;
  },
});

const addButton = idNode('push-add-streamer', 'button');

globalThis.document = {
  querySelector: selector => {
    const sel = String(selector);
    return sel.startsWith('#') ? idNode(sel.slice(1)) : null;
  },
  getElementById: id => (id === 'push-drawer' || id === 'push-page-style' ? idNode(id) : null),
  createElement: tag => new MiniNode(tag),
  createTextNode: text => {
    const node = new MiniNode('#text');
    node.textContent = text;
    return node;
  },
  addEventListener() {},
  removeEventListener() {},
  head: idNode('head'),
  body: idNode('body'),
  activeElement: null,
  documentElement: {classList: {toggle() {}, contains: () => false}},
};

globalThis.location = {hash: '', href: '', replace() {}, reload() {}};

globalThis.fetch = () => Promise.resolve({status: 200, json: () => Promise.resolve({})});

// —— 载入产品码 ——

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  store = (await import('./store.js')).store;
  push.render(idNode('page-push', 'section'));
  store.platforms = ['bilibili'];
} catch (err) {
  failures.push('载入 push.js 或建页就抛了：' + err.message);
}

const focused = () => nameOf(globalThis.document.activeElement);

/** 照用户那样：焦点先在「＋ 添加主播」上，再点它 */
function openAddStreamer() {
  if (!push) throw new Error('push.js 没载入，无从量起');
  if (!drawer.hidden) push.closeDrawer();
  addButton.focus();
  log.length = 0;
  addButton.click();
  if (drawer.hidden) throw new Error('点了「＋ 添加主播」面板没打开');
}

/** 键按在面板里此刻有焦点的那一个上，往上冒到面板外壳 */
function press(key, shiftKey) {
  const event = fakeEvent('keydown', globalThis.document.activeElement, {key, shiftKey: !!shiftKey});
  drawer.dispatchEvent(event);
  return event;
}

// 故障：面板里的输入框在面板还藏着时就被聚焦，聚焦落空，焦点留在「＋ 添加主播」上，打不了字
await ask('① 打开时先显示、再聚焦面板里第一个控件（输入框）', async () => {
  openAddStreamer();
  const shown = log.indexOf('面板显示');
  const typed = log.indexOf('聚焦 #add-uid');
  same({
    '流水': log,
    '先显示后聚焦': shown >= 0 && typed > shown,
    '焦点停在': focused(),
  }, {
    '流水': ['面板显示', '聚焦 #add-uid'],
    '先显示后聚焦': true,
    '焦点停在': '#add-uid',
  }, '打开添加主播的面板');
});

// 故障：按 Esc 关不掉面板，只能摸去点「关闭」
await ask('② 按 Esc 关掉面板', async () => {
  openAddStreamer();
  const event = press('Escape');
  same({'面板藏起': drawer.hidden, '拦下了这一键': event.defaultPrevented},
    {'面板藏起': true, '拦下了这一键': true}, '按 Esc 之后');
});

// 故障：在最后一个控件上按 Tab，焦点跑到面板后面那一页上去了
await ask('③ 在最后一个控件上按 Tab 回到第一个；第一个上按 Shift+Tab 回到最后一个', async () => {
  openAddStreamer();
  idNode('add-streamer').focus();
  const forward = press('Tab');
  const wrapped = focused();
  const back = press('Tab', true);
  same({
    '最后一个上按 Tab 之后': wrapped,
    '拦下了浏览器自己的 Tab': forward.defaultPrevented,
    '第一个上按 Shift+Tab 之后': focused(),
    '拦下了浏览器自己的 Shift+Tab': back.defaultPrevented,
  }, {
    '最后一个上按 Tab 之后': '#push-drawer-close',
    '拦下了浏览器自己的 Tab': true,
    '第一个上按 Shift+Tab 之后': '#add-streamer',
    '拦下了浏览器自己的 Shift+Tab': true,
  }, '面板里按 Tab');
});

// 故障：关掉面板以后焦点丢了，键盘得从页头重新走一遍
await ask('④ 不管点 ×、点遮罩还是按 Esc 关掉，焦点都回到「＋ 添加主播」', async () => {
  const after = {};
  openAddStreamer();
  closeButton.click();
  after['点关闭'] = focused();

  openAddStreamer();
  drawer.dispatchEvent(fakeEvent('click', drawer));
  after['点遮罩'] = focused();

  openAddStreamer();
  press('Escape');
  after['按 Esc'] = focused();

  same(after, {'点关闭': '#push-add-streamer', '点遮罩': '#push-add-streamer', '按 Esc': '#push-add-streamer'},
    '关掉之后焦点');
});

// 故障：打开面板的那颗按钮已经随重画没了，关面板时还去聚焦它（或因此抛错）
await ask('⑤ 打开它的那个按钮已经不在页上了，关掉时就不再去聚焦它', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const host = idNode('push-right');
  const gone = host.appendChild(new MiniNode('button'));
  gone.textContent = '＋ 添加通道';
  gone.focus();
  push.openDrawer('给某位主播添加通道', '', body => {
    const search = body.appendChild(new MiniNode('input'));
    search.type = 'search';
  });
  gone.remove();
  log.length = 0;
  push.closeDrawer();
  same({'面板藏起': drawer.hidden, '去聚焦过已不在页上的按钮': log.includes('聚焦 button「＋ 添加通道」')},
    {'面板藏起': true, '去聚焦过已不在页上的按钮': false}, '按钮没了之后关面板');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
