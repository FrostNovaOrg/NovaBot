/**
 * 数据卡片拖走、拿掉再放回之后，改动条认不认得「回到了原样」
 *
 * 用户那头的故障：走默认版式的通道（message.params 里没有数据卡片的顺序键与开关键），
 * 把一张卡拿掉再点回原位、或把小块拖走再拖回原处——分两下做完，报告与开关都回到了原样，
 * 状态行也回到了「本通道用的是：默认版式 ✓」，改动条却还写着「1 处改动」，换页时还要问
 * 要不要放弃。一下做完放回原位从来不记，记一笔的只有分两下做完的这条路。
 *
 * 各格抓的故障写在各格开头。改动条按整段 JSON 与快照比（见 push.js 的 changeCount）：
 * 键的形状与快照对不上就是一处改动——多一个键、少一个键都算。所以「回到原样」得连键的
 * 形状一起回到原样，两种快照都要对上：快照里这个键本来没有的，回来时也不能有；快照里显式
 * 存着等于默认值的，回来时还得是显式存着的那一个。
 *
 * 本尺真跑整条通道页：push.js 的 renderStreamers、template.js 的版式编辑器、
 * push-model.js 的判法都是产品码。版式项清单不手抄，由 PushCardRoundTripTest 把
 * 下播报告真正的那张表照 /api/handlers 的序列化经环境变量 LAYOUT_OPTIONS 交进来。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
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
    const frame = stack.split('\n').find(line => line.includes('push-card-round-trip-fixture.mjs')) || '';
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
    this.dataset = {};
    this.style = {};
    this.listeners = {};
    this.disabled = false;
    this.checked = false;
    this.hidden = false;
    this.draggable = false;
    this.title = '';
    this.type = '';
    this.value = '';
    const self = this;
    this.classList = {
      add: name => { if (!self.classList.contains(name)) self.className = (self.className + ' ' + name).trim(); },
      remove: name => {
        self.className = String(self.className || '').split(/\s+/).filter(item => item && item !== name).join(' ');
      },
      contains: name => String(self.className || '').split(/\s+/).includes(name),
      toggle: (name, on) => {
        const want = on === undefined ? !self.classList.contains(name) : !!on;
        if (want) self.classList.add(name); else self.classList.remove(name);
        return want;
      },
    };
  }
  get nodeType() { return this.tagName === '#text' ? 3 : 1; }
  get nodeValue() { return this._text; }
  get childNodes() { return this.children; }
  get textContent() { return this._text; }
  set textContent(v) {
    this._text = String(v ?? '');
    // 与浏览器一样换成一个文字子节点：消息正文读回块表时走的是子节点
    if (this.tagName === '#text') return;
    this.children = [];
    if (this._text) {
      const text = new MiniNode('#text');
      text._text = this._text;
      this.appendChild(text);
    }
  }
  get innerHTML() { return this._html; }
  set innerHTML(html) {
    this._html = String(html ?? '');
    this.children = [];
    // 只把 <input …> 立成真节点：开关与数字框要从 label 里取回来拨
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
  remove() {
    if (!this.parentNode) return;
    const list = this.parentNode.children;
    const index = list.indexOf(this);
    if (index >= 0) list.splice(index, 1);
    this.parentNode = null;
  }
  contains(node) {
    for (let at = node; at; at = at.parentNode) if (at === this) return true;
    return false;
  }
  closest(selector) {
    for (let at = this; at; at = at.parentNode) if (matches(at, String(selector).trim())) return at;
    return null;
  }
  getBoundingClientRect() { return {left: 0, top: 0, width: 100, height: 20}; }
  querySelector(selector) { return findAll(this, selector)[0] || null; }
  querySelectorAll(selector) { return findAll(this, selector); }
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
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
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
  click() { this.dispatchEvent(fakeEvent('click', this)); }
  focus() { globalThis.document.activeElement = this; }
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
  const data = /^\[data-([a-z]+)]$/.exec(sel);
  if (data) return !!(node.dataset && node.dataset[data[1]]);
  return node.tagName === sel.toLowerCase();
}

/** 一个事件：记下有没有被 preventDefault，拖放的带一份 dataTransfer，setData 的每一次调用记在 calls 上 */
function fakeEvent(type, target, extra) {
  const calls = [];
  return Object.assign({
    type, target, defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; },
    stopPropagation() {},
    dataTransfer: {
      effectAllowed: '', dropEffect: '', calls,
      setData(kind, value) { calls.push([String(kind), String(value)]); },
      getData: () => '',
    },
  }, extra || {});
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
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};

// 模板编辑器拒收一块时弹 alert；这里记下来，弹了就是收了不该收的东西
const alerts = [];
globalThis.window = {alert: text => alerts.push(String(text)), getSelection: () => null};

globalThis.location = {hash: '', href: '', replace() {}, reload() {}};

// 预览图在浏览器里经 createObjectURL 变成 img 的 src，node 上没有这一对
let blobSeq = 0;
globalThis.URL.createObjectURL = () => 'blob:preview-' + (++blobSeq);
globalThis.URL.revokeObjectURL = () => {};

// —— 假接口 ——

const mock = {requests: []};

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  const body = (opt || {}).body;
  mock.requests.push({path: String(path), method, body});
  const url = String(path);

  if (method === 'GET') {
    if (url.endsWith('/push-history')) {
      return Promise.resolve({status: 200, json: () => Promise.resolve({records: []})});
    }
    return Promise.resolve({status: 200, json: () => Promise.resolve({})});
  }

  if (url.endsWith('/config/api/report/preview')) {
    return Promise.resolve({ok: true, blob: () => Promise.resolve('preview-bytes')});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({success: true, message: '已保存'})});
};

// —— /api/handlers 的形态：下播报告真正的那张版式表 ——

const HANDLER = 'org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler';

let OPTIONS = [];
try {
  OPTIONS = JSON.parse(process.env.LAYOUT_OPTIONS || '[]');
} catch (err) {
  failures.push('LAYOUT_OPTIONS 读不成 JSON：' + err.message);
}

const CARD_LABELS = ['流水', '弹幕', '点赞', '进入直播间', '新增关注', '醒目留言', '大航海', '盲盒', '免费礼物', '分享'];
const CARD_KEYS = ['card_revenue', 'card_danmu', 'card_like', 'card_enter', 'card_follow',
  'card_super_chat', 'card_guard', 'card_box', 'card_free_gift', 'card_share'];
const KEY_OF = Object.fromEntries(CARD_LABELS.map((label, i) => [label, CARD_KEYS[i]]));
const LABEL_OF = Object.fromEntries(CARD_KEYS.map((key, i) => [key, CARD_LABELS[i]]));
const DEFAULT_ORDER = CARD_KEYS.join(',');

const TARGET = {platform: 'qq-onebot', type: 1, num: 12345};

const SESSION = {
  platform: 'qq-onebot', num: 12345, type: 1,
  disabled: [], menuHidden: [], menuNotes: {},
  revenueVisible: true, revenueExplicit: true,
  streamers: ['甲'],
};

// —— 载入产品码 ——

let push = null;
let model = null;
let editors = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  model = await import(join(pages, 'push-model.js'));
  editors = await import(join(pages, 'template.js'));
  store = (await import('./store.js')).store;
} catch (err) {
  failures.push('载入产品码就抛了：' + err.message);
}

const page = () => idNode('push-right');
const allNodes = scope => {
  const out = [];
  const walk = node => {
    out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(scope);
  return out;
};
const layoutInput = label => allNodes(page())
  .find(node => node.tagName === 'input' && node.getAttribute('aria-label') === label) || null;
const layoutRows = () => allNodes(page()).filter(node => String(node.className || '').split(/\s+/).includes('rep-row'));
const rowLabel = row => {
  const input = allNodes(row).find(node => node.tagName === 'input');
  return input ? input.getAttribute('aria-label') : null;
};
const hasClass = (node, name) => String((node || {}).className || '').split(/\s+/).includes(name);
const stateLine = () => allNodes(page()).find(node => node.tagName === 'span'
  && String(node.innerHTML).includes('当前版式'));
const message = () => push.pushEntries()[0].targets[0].messages[0];
const textOf = scope => allNodes(scope).map(node => node.textContent || '').join(' ');

// —— 两区小块 ——

const ON = 'rep-zone-on';
const OFF = 'rep-zone-off';
const ZONE_NAME = {[ON]: '报告里的卡片', [OFF]: '没放进报告的'};

/** 某一区；scope 缺省为通道页 */
function zone(which, scope) {
  const node = allNodes(scope || page()).find(item => hasClass(item, which));
  if (!node) throw new Error('版式区没有「' + ZONE_NAME[which] + '」那一区');
  return node;
}

const chipsIn = (which, scope) => allNodes(scope || page()).filter(node => hasClass(node, 'rep-chip')
  && hasClass(node.parentNode, which));
const labelsIn = (which, scope) => chipsIn(which, scope).map(node => LABEL_OF[node.dataset.card] || node.dataset.card);

function chip(which, label, scope) {
  const node = chipsIn(which, scope).find(item => item.dataset.card === KEY_OF[label]);
  if (!node) throw new Error('「' + ZONE_NAME[which] + '」里没有「' + label + '」');
  return node;
}

function killButton(label, scope) {
  const node = allNodes(scope || page())
    .find(item => item.tagName === 'button' && item.getAttribute('aria-label') === '拿掉：' + label);
  if (!node) throw new Error('版式区没有「拿掉：' + label + '」按钮');
  return node;
}

/** 把一枚小块拖到另一枚的左半边（before）或右半边（after），或拖到一区的空白处 */
function dragTo(from, to, side) {
  from.dispatchEvent(fakeEvent('dragstart', from));
  const area = to.closest('.rep-zone') || to.closest('.tpl-c-body') || to;
  const clientX = side === 'after' ? 90 : 10;
  const over = fakeEvent('dragover', to, {clientX});
  area.dispatchEvent(over);
  const drop = fakeEvent('drop', to, {clientX});
  area.dispatchEvent(drop);
  from.dispatchEvent(fakeEvent('dragend', from));
  return {over, drop};
}

function press(node, key) {
  const event = fakeEvent('keydown', node, {key});
  node.dispatchEvent(event);
  return event;
}

const focused = () => {
  const node = document.activeElement;
  if (!node) return '无';
  return (node.dataset && node.dataset.card ? (hasClass(node.parentNode, ON) ? '报告区·' : '下面·')
    + LABEL_OF[node.dataset.card] : '') || node.getAttribute('aria-label') || node.tagName;
};

function expectState(word, what) {
  const line = stateLine();
  if (!line || !String(line.innerHTML).includes(word)) {
    throw new Error(what + '，状态行该是「' + word + '」：' + (line ? line.innerHTML : '没找到'));
  }
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

/**
 * 把通道页画出来。params 为 null 时消息上不带 params 这一键：
 * 刚加的通道就是这样，[{handler}]，一个参数都没存过
 */
async function paintPage(params) {
  mock.requests.length = 0;
  push.setPushEntries([{
    platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: TARGET.platform, type: 1, num: TARGET.num, enabled: true,
      messages: [params === null ? {handler: HANDLER} : {handler: HANDLER, params: Object.assign({}, params)}],
    }],
  }]);
  push.setRuntimeState({sessions: [SESSION], commands: [], subscriptions: [], incomplete: []});
  push.showPush('1', String(TARGET.num));
  push.renderStreamers();
  await settle();
}

if (push && store) {
  store.handlerList = [{
    className: HANDLER,
    aliases: [],
    displayName: '下播报告',
    description: '下播时把整场报告画成一张图',
    platform: 'bilibili',
    placeholders: [],
    attachments: [],
    defaultParams: {},
    factoryParams: {},
    options: OPTIONS,
  }];
}

// 故障：走默认版式的通道（params 里没有顺序键与开关键）拿掉一张卡、再点回原位，或把小块
// 拖走、再拖回原处——分两下做完，报告与开关都回到了原样，状态行也回到了「默认版式 ✓」，
// 改动条却还写着「1 处改动」，换页时还要问要不要放弃
await ask('① 走默认版式的通道分两下回到原样：改动条 0', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  // 拿掉一张卡再点回原位：拿的是最后一张，放回末尾＝它原来那一位
  await paintPage({});
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '拿掉「分享」之后的改动数');
  same(labelsIn(OFF), ['分享'], '拿掉「分享」之后下面那区');
  chip(OFF, '分享').click();
  await settle();
  same(push.changeCount(), 0, '再点回原位之后的改动数');
  same(labelsIn(ON), CARD_LABELS, '再点回原位之后报告区');
  same(Object.keys(message().params), [], '再点回原位之后的参数');
  expectState('默认', '分两下回到原样之后');

  // 拖走、再拖回原处：两下做完，顺序键也得回到没存过的样子
  await paintPage({});
  push.markPushSaved();
  dragTo(chip(ON, '流水'), chip(ON, '分享'), 'after');
  await settle();
  same(push.changeCount(), 1, '把「流水」拖到末尾之后的改动数');
  same(labelsIn(ON), [...CARD_LABELS.slice(1), '流水'], '把「流水」拖到末尾之后报告区');
  dragTo(chip(ON, '流水'), chip(ON, '弹幕'), 'before');
  await settle();
  same(push.changeCount(), 0, '再拖回原处之后的改动数');
  same(labelsIn(ON), CARD_LABELS, '再拖回原处之后报告区');
  same(Object.keys(message().params), [], '再拖回原处之后的参数');
  expectState('默认', '拖回原处之后');
});

// 故障：快照里显式存着等于默认值的键时，同样分两下回到原样，改动条还写着一处改动——
// 键的形状与快照对不上。三种形状都得对上：被动的那键快照里本来没有的，回来时也不能有；
// 快照里显式存着等于默认值的，回来时还得是显式存着的那一个
await ask('② 快照里显式存着等于默认值的键，分两下回到原样：改动条 0', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  // 顺序键显式存着默认整串，开关键没存过：拿掉「分享」再点回原位
  await paintPage({card_order: DEFAULT_ORDER});
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '拿掉「分享」之后的改动数');
  chip(OFF, '分享').click();
  await settle();
  same(push.changeCount(), 0, '顺序键显式存着默认整串时，拿掉「分享」再点回原位之后的改动数');
  same(message().params.card_order, DEFAULT_ORDER, '顺序键显式存着默认整串时，放回之后顺序键的样子');
  same(Object.prototype.hasOwnProperty.call(message().params, 'card_share'), false,
    '顺序键显式存着默认整串时，放回之后开关键的样子');

  // 开关键显式存着等于默认的值，顺序键没存过：把「流水」拖走、再拖回原处
  await paintPage({card_revenue: true});
  push.markPushSaved();
  dragTo(chip(ON, '流水'), chip(ON, '分享'), 'after');
  await settle();
  same(push.changeCount(), 1, '把「流水」拖到末尾之后的改动数');
  dragTo(chip(ON, '流水'), chip(ON, '弹幕'), 'before');
  await settle();
  same(push.changeCount(), 0, '开关键显式存着等于默认的值时，把「流水」拖走再拖回原处之后的改动数');
  same(message().params.card_revenue, true, '开关键显式存着等于默认的值时，拖回之后开关键的样子');
  same(Object.prototype.hasOwnProperty.call(message().params, 'card_order'), false,
    '开关键显式存着等于默认的值时，拖回之后顺序键的样子');

  // 被动的那键本身就显式存着等于默认的值：拿掉「分享」再点回原位。
  // 值写回原样还不够——键还得是显式存着的那一个，一律删键在这里会少一个键，照样多记一处
  await paintPage({card_share: true});
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '开关键本身就显式存着等于默认的值时，拿掉「分享」之后的改动数');
  chip(OFF, '分享').click();
  await settle();
  same(push.changeCount(), 0, '开关键本身就显式存着等于默认的值时，拿掉「分享」再点回原位之后的改动数');
  same(message().params.card_share, true, '开关键本身就显式存着等于默认的值时，放回之后开关键的样子');
});

// 故障：改过头——真改了顺序或拿掉一张卡也被抹平，改动条回到 0，「还有改动没存」不见了
await ask('③ 守住：真改了顺序或拿掉一张卡，改动条 1', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  push.markPushSaved();
  dragTo(chip(ON, '流水'), chip(ON, '分享'), 'after');
  await settle();
  same(push.changeCount(), 1, '把「流水」拖到末尾之后的改动数');
  expectState('自定义', '真改了顺序后');

  await paintPage({card_order: DEFAULT_ORDER});
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '拿掉「分享」之后的改动数');
  expectState('自定义', '真拿掉一张卡后');

  await paintPage({card_share: true});
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '开关键显式存着等于默认的值时，拿掉「分享」之后的改动数');
});

// 故障：一下拖完放回原位也记一笔，或者参数里被写进一个原样值
await ask('④ 守住：一下拖完放回原位，照旧不记', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  push.markPushSaved();
  dragTo(chip(ON, '分享'), chip(ON, '免费礼物'), 'after');
  await settle();
  same(push.changeCount(), 0, '一下拖完放回原位之后的改动数');
  same(Object.keys(message().params), [], '一下拖完放回原位之后的参数');
  expectState('默认', '一下拖完放回原位之后');

  await paintPage({card_order: DEFAULT_ORDER});
  push.markPushSaved();
  dragTo(chip(ON, '分享'), chip(ON, '分享'), 'after');
  await settle();
  same(push.changeCount(), 0, '顺序键显式存着默认整串时，一下拖完放回原位之后的改动数');
  same(message().params.card_order, DEFAULT_ORDER, '顺序键显式存着默认整串时，一下拖完放回原位之后顺序键的样子');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
