/**
 * 版式区里的数据卡片：报告区与「没放进报告的」两区小块
 *
 * 用户那头的故障：主播想给某个群只放几张卡，比如只要流水、弹幕、大航海，不要点赞、
 * 进入直播间、分享，以前只能把「数据卡片」整块开或整块关。现在「数据卡片」下面是两区小块：
 * 上面「报告里的卡片」按报告上的先后排，拖着换位、× 拿掉；下面「没放进报告的」点一下放回末尾，
 * 也能拖回想要的位置。在报告区＝那张卡的开关开着，报告区里的先后＝存着的顺序。
 *
 * 各格抓的故障写在各格开头。⑩ 量的是四处拖起来的地方都给 dataTransfer 塞了一样东西：
 * Firefox 不见 setData 就不起拖，点和键盘不受影响，所以这一样只有在 Firefox 里才看得出缺。
 *
 * 本尺真跑整条通道页：push.js 的 renderStreamers、template.js 的两个编辑器、
 * push-model.js 的判法都是产品码。版式项清单不手抄，由 PushCardSwitchesTest 把
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
    const frame = stack.split('\n').find(line => line.includes('push-card-switches-fixture.mjs')) || '';
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
const ZONE_NAME = {[ON]: '已启用卡片', [OFF]: '未启用卡片'};

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
    .find(item => item.tagName === 'button' && item.getAttribute('aria-label') === '移除：' + label);
  if (!node) throw new Error('版式区没有「移除：' + label + '」按钮');
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

/**
 * 推送内容里那一种通知展开编辑：没展开就点它那一行的「编辑」
 *
 * 版式区与模板编辑器只画在展开的那一行里，一条通道同时只展开一行；展开哪一行记在页上，
 * 重画之后照旧展开着。假 DOM 清不掉旧子节点时新画的接在后面，一律取最后一份
 */
function openNotice(className) {
  const nodes = allNodes(page());
  const row = nodes.filter(node => node.getAttribute && node.getAttribute('data-notice') === className).at(-1);
  if (row && allNodes(row).some(node => String(node.className || '').split(/\s+/).includes('nrow-body'))) return;
  const button = nodes.filter(node => node.tagName === 'button' && node.getAttribute
    && node.getAttribute('data-notice-edit') === className).at(-1);
  if (!button) throw new Error('推送内容里「' + className + '」那一行没有「编辑」');
  button.dispatchEvent({type: 'click', target: button, preventDefault() {}, stopPropagation() {}});
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
  openNotice(HANDLER);
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
    reportLayout: true, options: OPTIONS,
  }];
}

await ask('① 十张卡在版式表里跟着数据卡片；界面上紧跟「数据卡片」那一行，是一枚枚小块而不是一行行开关', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const children = OPTIONS.filter(option => option.parentKey === 'cards').map(option => option.label);
  same(children, CARD_LABELS, '版式表里跟着「数据卡片」的项');

  await paintPage({});
  const controls = allNodes(page()).find(node => node.id === 'rep-options');
  const rows = controls.children;
  const at = rows.findIndex(row => rowLabel(row) === '数据卡片');
  if (at < 0) throw new Error('版式区没有「数据卡片」这一行');
  const board = rows[at + 1];
  if (!hasClass(board, 'rep-board')) throw new Error('「数据卡片」下面一行不是两区小块');
  if (!hasClass(board, 'rep-sub')) throw new Error('两区小块没缩进在「数据卡片」下面');
  same(labelsIn(ON), CARD_LABELS, '报告区里的小块');
  same(labelsIn(OFF), [], '下面那区的小块');
  same(CARD_LABELS.filter(label => layoutInput(label)), [], '还画成一行行开关的卡');
  same(rowLabel(rows[at + 2]), '本场变化', '小块之后那一行');
});

await ask('② 恢复默认：十张卡的键与顺序键一并删掉，不认得的键照留', async () => {
  if (!model) throw new Error('产品码没载入，无从量起');
  const handler = store.handlerList[0];
  const params = {message: '{report}', cards: false, card_like: false, card_share: false, card_revenue: false,
    card_order: 'card_share,card_like'};
  const restored = model.restoredLayoutParams(params, handler);
  same(restored, {message: '{report}'}, '恢复默认之后的参数');
  same(params.card_like, false, '恢复默认不该改动传进来的那一份');
});

await ask('③ 拿掉任一张卡：状态行当场变成「自定义版式」', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  expectState('默认', '全默认时');
  killButton('分享').click();
  await settle();
  same(message().params.card_share, false, '拿掉「分享」后的存值');
  expectState('自定义', '拿掉了「分享」后');
});

// 故障：拖一枚到另一枚前面，存下去的顺序或界面上的顺序没变；拖回原位却记成了改动
await ask('④ 拖着换位：顺序存进参数、界面跟着排；拖回原位不算改动', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  const {over} = dragTo(chip(ON, '分享'), chip(ON, '流水'), 'before');
  if (!over.defaultPrevented) throw new Error('报告区不收报告区里拖起来的小块');
  same(message().params.card_order, ['card_share', ...CARD_KEYS.slice(0, 9)].join(','), '把「分享」拖到「流水」前面之后存下去的顺序');
  same(labelsIn(ON), ['分享', ...CARD_LABELS.slice(0, 9)], '把「分享」拖到「流水」前面之后界面上的顺序');
  expectState('自定义', '调了顺序后');

  dragTo(chip(ON, '分享'), chip(ON, '免费礼物'), 'after');
  same(Object.keys(message().params), [], '拖回原位之后存下去的参数（顺序键回到没存过的样子）');
  same(labelsIn(ON), CARD_LABELS, '拖回原位之后界面上的顺序');
  expectState('默认', '拖回原位后');

  await paintPage({});
  dragTo(chip(ON, '弹幕'), chip(ON, '流水'), 'after');
  dragTo(chip(ON, '弹幕'), chip(ON, '点赞'), 'before');
  dragTo(chip(ON, '弹幕'), chip(ON, '弹幕'), 'after');
  same(Object.keys(message().params), [], '原地拖了三下之后的参数（不该写进任何键）');

  // 刚加的通道一个参数都没存过：原地拖几下，改动条上不该冒出一处没存的改动
  await paintPage(null);
  push.markPushSaved();
  dragTo(chip(ON, '弹幕'), chip(ON, '流水'), 'after');
  dragTo(chip(ON, '弹幕'), chip(ON, '点赞'), 'before');
  dragTo(chip(ON, '弹幕'), chip(ON, '弹幕'), 'after');
  same(push.changeCount(), 0, '没存过参数的通道上原地拖了三下之后的改动数');
});

// 故障：点 × 以后那张卡的开关没存成关、或小块没到下面那区；点下面那区的小块没回到报告区末尾、或放回后开关没开着
await ask('⑤ 拿掉放回：× 存成关并落到下面那区；点一下放回末尾，开关开着；拖回来落在放下的位置；空着时各写一句', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  same(allNodes(page()).some(node => hasClass(node, OFF)), false, '备选为空时不占位');
  killButton('点赞').click();
  await settle();
  same(message().params.card_like, false, '点「拿掉：点赞」之后的存值');
  same(labelsIn(ON), CARD_LABELS.filter(label => label !== '点赞'), '拿掉「点赞」之后报告区');
  same(labelsIn(OFF), ['点赞'], '拿掉「点赞」之后下面那区');

  chip(OFF, '点赞').click();
  await settle();
  same(Object.prototype.hasOwnProperty.call(message().params, 'card_like'), false,
    '点下面那区的「点赞」之后的开关键（回到没存过的样子：跟着默认，开着）');
  same(labelsIn(ON), [...CARD_LABELS.filter(label => label !== '点赞'), '点赞'], '放回「点赞」之后报告区');
  same(labelsIn(OFF), [], '放回「点赞」之后下面那区');
  same(message().params.card_order.split(',').pop(), 'card_like', '放回之后存着的顺序的最后一项');

  await paintPage({card_like: false, card_share: false});
  const {over} = dragTo(chip(OFF, '分享'), chip(ON, '弹幕'), 'before');
  if (!over.defaultPrevented) throw new Error('报告区不收下面那区拖来的小块');
  same(Object.prototype.hasOwnProperty.call(message().params, 'card_share'), false,
    '把「分享」拖进报告区之后的开关键（开着＝跟着默认，不再钉住）');
  same(labelsIn(ON), ['流水', '分享', '弹幕', ...CARD_LABELS.slice(3, 9)], '把「分享」拖到「弹幕」前面之后报告区');
  same(labelsIn(OFF), ['点赞'], '把「分享」拖进报告区之后下面那区');

  dragTo(chip(ON, '流水'), zone(OFF), 'after');
  same(message().params.card_revenue, false, '把「流水」拖进下面那区之后的存值');

  await paintPage(Object.fromEntries(CARD_KEYS.map(key => [key, false])));
  same(labelsIn(ON), [], '十张全拿掉之后报告区');
  same(textOf(zone(ON)).includes('尚未启用任何卡片，报告中不显示这一块'), true, '报告区空着时那一句');
});

// 故障：拖不了的人用键盘做不完：方向键挪不了位、Delete 拿不掉、回车放不回，或动完焦点丢了
await ask('⑥ 键盘：Tab 走得到，左右方向键挪位，Delete／Backspace 拿掉，回车／空格放回；焦点落在刚动过的那一枚', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  const first = chip(ON, '流水');
  same(first.tabIndex, 0, '报告区小块的 tabIndex');
  const name = first.getAttribute('aria-label') || '';
  for (const word of ['流水', '第 1 张', '方向键', 'Delete']) {
    if (!name.includes(word)) throw new Error('「流水」的读屏名里没有「' + word + '」：' + name);
  }

  press(first, 'ArrowRight');
  await settle();
  same(labelsIn(ON).slice(0, 2), ['弹幕', '流水'], '对「流水」按右方向键之后前两枚');
  same(message().params.card_order.split(',').slice(0, 2), ['card_danmu', 'card_revenue'], '右移之后存下去的顺序');
  same(focused(), '报告区·流水', '右移之后焦点');
  press(document.activeElement, 'ArrowLeft');
  await settle();
  same(labelsIn(ON).slice(0, 2), ['流水', '弹幕'], '再按左方向键之后前两枚');
  same(focused(), '报告区·流水', '左移之后焦点');

  press(document.activeElement, 'Delete');
  await settle();
  same(message().params.card_revenue, false, '按 Delete 之后「流水」的存值');
  same(focused(), '下面·流水', '按 Delete 之后焦点');
  const back = chip(OFF, '流水');
  same(back.tabIndex, 0, '下面那区小块的 tabIndex');
  if (!(back.getAttribute('aria-label') || '').includes('流水')) throw new Error('下面那区「流水」的读屏名里没有卡名');

  press(document.activeElement, 'Enter');
  await settle();
  same(Object.prototype.hasOwnProperty.call(message().params, 'card_revenue'), false,
    '按回车之后「流水」的开关键（回到没存过的样子：跟着默认，开着）');
  same(labelsIn(ON).pop(), '流水', '按回车之后报告区最后一枚');
  same(focused(), '报告区·流水', '按回车之后焦点');

  press(chip(ON, '弹幕'), 'Backspace');
  await settle();
  same(message().params.card_danmu, false, '按 Backspace 之后「弹幕」的存值');
  same(focused(), '下面·弹幕', '按 Backspace 之后焦点');
  press(document.activeElement, ' ');
  await settle();
  same(Object.prototype.hasOwnProperty.call(message().params, 'card_danmu'), false,
    '按空格之后「弹幕」的开关键（回到没存过的样子：跟着默认，开着）');
  same(focused(), '报告区·弹幕', '按空格之后焦点');
});

// 故障：总开关关着、或这一页不可编辑时，小块照样能拖、× 和放回照样起作用，存着的值被改了
await ask('⑦ 锁着：总开关关着或页不可编辑时，拖、×、放回、按键都不起作用，存着的值不动，写明为什么', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const stored = [...CARD_KEYS].reverse().join(',');
  await paintPage({cards: false, card_like: false, card_order: stored});
  const before = JSON.stringify(message().params);
  same(labelsIn(ON), [...CARD_LABELS].reverse().filter(label => label !== '点赞'), '总开关关着时报告区照存着的顺序列出');
  same(chipsIn(ON).filter(node => node.draggable).length, 0, '总开关关着时拖得动的小块');
  same(CARD_LABELS.filter(label => label !== '点赞' && !killButton(label).disabled), [], '总开关关着时可点的 ×');
  same(chip(OFF, '点赞').getAttribute('aria-disabled'), 'true', '总开关关着时下面那区小块的 aria-disabled');
  if (!textOf(page()).includes('「数据卡片」未启用')) throw new Error('锁着时没写为什么');

  killButton('分享').click();
  chip(OFF, '点赞').click();
  press(chip(ON, '分享'), 'Delete');
  press(chip(ON, '分享'), 'ArrowLeft');
  press(chip(OFF, '点赞'), 'Enter');
  dragTo(chip(ON, '流水'), chip(ON, '分享'), 'before');
  dragTo(chip(OFF, '点赞'), chip(ON, '分享'), 'before');
  await settle();
  same(JSON.stringify(message().params), before, '总开关关着时动过一圈之后的存值');

  const params = {card_like: false};
  let calls = 0;
  const host = new MiniNode('div');
  editors.buildLayoutEditor(host, {
    editable: false, items: OPTIONS, lockedNote: '这一页只能看',
    paramsOf: () => params, onChange: () => { calls++; }, render: () => Promise.resolve(null),
  });
  same(chipsIn(ON, host).filter(node => node.draggable).length, 0, '页不可编辑时拖得动的小块');
  killButton('分享', host).click();
  chip(OFF, '点赞', host).click();
  press(chip(ON, '分享', host), 'Delete');
  press(chip(OFF, '点赞', host), 'Enter');
  dragTo(chip(ON, '流水', host), chip(ON, '分享', host), 'before');
  same(calls, 0, '页不可编辑时动过一圈之后写回的次数');
});

// 故障：两个编辑器共用一个「正在拖什么」，卡片小块拖进消息正文被当成占位符插进去，占位符拖进卡片区被收下
await ask('⑧ 两个编辑器不串：卡片小块拖进消息正文、占位符拖进卡片区都什么都不做；模板编辑器原有的拖放照旧', async () => {
  if (!editors) throw new Error('产品码没载入，无从量起');
  alerts.length = 0;
  const tplHost = new MiniNode('div');
  const tplHandler = {className: 'demo', displayName: '开播通知', placeholders: ['{uname}', '{title}'],
    attachments: [], defaultParams: {message: '开播了'}};
  let tplParams = {};
  const tplWrites = [];
  editors.buildTemplateEditor(tplHost, {
    handlers: [tplHandler], editable: true, paramsOf: () => tplParams,
    onChange: (handler, params) => { tplWrites.push(params); tplParams = params; },
  });
  const repHost = new MiniNode('div');
  const repParams = {card_danmu: false};
  let repWrites = 0;
  editors.buildLayoutEditor(repHost, {
    editable: true, items: OPTIONS, paramsOf: () => repParams,
    onChange: () => { repWrites++; }, render: () => Promise.resolve(null),
  });
  const body = () => allNodes(tplHost).find(node => hasClass(node, 'tpl-c-body'));
  const paletteButton = key => allNodes(tplHost).find(node => hasClass(node, 'tpl-blk') && node.textContent === key);

  const card = chip(ON, '分享', repHost);
  const into = dragTo(card, body(), 'after');
  same(tplWrites.length, 0, '卡片小块拖进消息正文之后模板写回的次数');
  same(alerts, [], '卡片小块拖进消息正文之后弹出的话');
  if (!into.drop.defaultPrevented) throw new Error('卡片小块落进消息正文时没拦住浏览器自己往正文里插东西');

  const onto = dragTo(paletteButton('title'), chip(ON, '流水', repHost), 'before');
  if (onto.over.defaultPrevented) throw new Error('报告区对拖来的占位符显示可以放下');
  const below = dragTo(paletteButton('title'), zone(OFF, repHost), 'after');
  if (below.over.defaultPrevented) throw new Error('下面那区对拖来的占位符显示可以放下');
  same(repWrites, 0, '占位符拖进卡片区之后版式写回的次数');
  same(labelsIn(ON, repHost), CARD_LABELS.filter(label => label !== '弹幕'), '占位符拖进卡片区之后报告区');

  const {over} = dragTo(paletteButton('uname'), body(), 'after');
  if (!over.defaultPrevented) throw new Error('消息正文不再收调色板上拖来的占位符');
  same(tplWrites.length, 1, '占位符拖进消息正文之后模板写回的次数');
  same(tplWrites[0].message, '开播了{uname}', '占位符拖进消息正文之后的模板');
});

// 故障：存着的顺序是旧版本留下的（少了后来加的卡）或手改过（带着一个不认得的键），
// 小块画乱了、少画了卡、把认不得的键画成一枚空名的小块，或者整块画不出来
await ask('⑨ 存着的顺序缺了几张、带着认不得的键：认得的照存的先后，缺的按默认顺序补在后面，认不得的不画', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  alerts.length = 0;
  const stored = 'card_share,card_bogus,card_danmu';
  await paintPage({card_order: stored});
  same(labelsIn(ON), ['分享', '弹幕', '流水', '点赞', '进入直播间', '新增关注', '醒目留言', '大航海', '盲盒', '免费礼物'],
    '报告区');
  same(labelsIn(OFF), [], '下面那区');
  same([...chipsIn(ON), ...chipsIn(OFF)].filter(node => !CARD_KEYS.includes(node.dataset.card)).length, 0,
    '画成小块的认不得的键');
  same(alerts, [], '弹出的话');
  same(message().params.card_order, stored, '光是画出来，存着的值不动');
});

// 故障：Firefox 里拖不动模板里的块与数据卡片的小块（它不见 setData 就不起拖）；
// 或者塞的是 text/plain，拖过别的输入框时被当成文字收进去，拖整张卡时那串字被插进正文
await ask('⑩ 四处拖起来的地方都调了 setData，用自定义类型，没有一处用 text/plain', async () => {
  if (!editors) throw new Error('产品码没载入，无从量起');
  const tplHost = new MiniNode('div');
  editors.buildTemplateEditor(tplHost, {
    handlers: [{className: 'demo', displayName: '开播通知', placeholders: ['{uname}', '{title}'],
      attachments: [], defaultParams: {message: '开播了{uname}'}}],
    editable: true, paramsOf: () => ({}), onChange: () => {},
  });
  const repHost = new MiniNode('div');
  editors.buildLayoutEditor(repHost, {
    editable: true, items: OPTIONS, paramsOf: () => ({}), onChange: () => {}, render: () => Promise.resolve(null),
  });
  const find = (host, cls, what) => {
    const node = allNodes(host).find(item => hasClass(item, cls));
    if (!node) throw new Error('编辑器里没有' + what);
    return node;
  };
  const sources = {
    '调色板上的块': find(tplHost, 'tpl-blk', '调色板上的块'),
    '整张卡的把手': find(tplHost, 'tpl-c-h', '整张卡的把手'),
    '正文里的块': find(tplHost, 'tpl-pill', '正文里的块'),
    '数据卡片的小块': chip(ON, '流水', repHost),
  };
  const got = {};
  for (const [what, node] of Object.entries(sources)) {
    const start = fakeEvent('dragstart', node);
    node.dispatchEvent(start);
    node.dispatchEvent(fakeEvent('dragend', node));
    got[what] = start.dataTransfer.calls.map(call => call[0]);
  }
  const want = Object.fromEntries(Object.keys(sources).map(what => [what, ['application/x-nova-drag']]));
  same(got, want, '各处 dragstart 里 setData 用的类型');
  same(Object.values(got).flat().filter(kind => kind.startsWith('text/')).length, 0, '用了 text/ 类型的次数');
});

// 全部卡片启用时，空的备选框不应占位；拿掉后仍给触屏用户保留操作说明。
await ask('备选区按需出现且说明可见', async () => {
  await paintPage({});
  same(allNodes(page()).filter(node => hasClass(node, OFF)).length, 0, '全部放入时的备选区数');
  killButton('点赞').click();
  await settle();
  same(labelsIn(OFF), ['点赞'], '拿掉后出现的备选卡片');
  same(textOf(page()).includes('点击或拖回上方即可启用'), true, '备选区的可见操作说明');
  chip(OFF, '点赞').click();
  await settle();
  same(allNodes(page()).filter(node => hasClass(node, OFF)).length, 0, '最后一项放回后备选区消失');
});

// 故障：中文块名、光标插入、跨消息拖动或卡内键盘挪位失效，打开时误写回。
// 故障：变量仍显示占位符原文、@ 块不能按光标定位或拖动，或仅全体类权限提示不符。
await ask('中文药丸与@入口按能力和权限显示', async () => {
  const find = (scope, cls) => {
    const node = allNodes(scope).find(item => hasClass(item, cls));
    if (!node) throw new Error('没有 ' + cls);
    return node;
  };
  const handler = {className: 'demo', placeholders: ['{uname}', '{at}', '{at=all}', '{next}'],
    placeholderLabels: {'{uname}': '主播昵称'}, defaultParams: {message: '第一条{next}前后'}};
  const host = new MiniNode('div');
  let params = {};
  let writes = 0;
  editors.buildTemplateEditor(host, {handlers: [handler], editable: true,
    context: {isGroup: true, admin: null}, paramsOf: () => params,
    onChange: (_, value) => { params = value; writes++; }});
  same(writes, 0, '打开编辑器不能写回');
  same(find(host, 'tpl-blk', '中文变量').textContent, '主播昵称', '调色板中文名');
  same(allNodes(host).some(node => node.id === 'tpl-note'), false, '常驻教学段已移除');
  const bodies = () => allNodes(host).filter(node => hasClass(node, 'tpl-c-body'));
  const insert = () => host.querySelector('.tpl-pal').querySelectorAll('.tpl-blk').at(-1);
  const targetText = bodies()[1].children[0].children[0];
  window.getSelection = () => ({rangeCount: 1, getRangeAt: () => ({startContainer: targetText, startOffset: 1})});
  bodies()[1].dispatchEvent(fakeEvent('mouseup', bodies()[1]));
  window.getSelection = () => null;
  insert().click();
  same(params.message, '第一条{next}前{at}后', '@ 块在第二条光标处定位');
  const pill = () => allNodes(host).find(node => node.dataset.k === '{at}');
  press(pill(), 'ArrowLeft');
  same(params.message, '第一条{next}{at}前后', '方向键挪提醒');
  const select = host.querySelector('#tpl-at');
  select.value = 'all_or_subscribers';
  select.dispatchEvent(fakeEvent('change', select));
  same(params.message, '第一条{next}{at=all}前后', '切模式同步已有提醒');
  dragTo(insert(), bodies()[0], 'after');
  same(params.message, '第一条{at=all}{next}前后', '@ 块拖到第一条');
  const dragText = bodies()[1].children[0].children[0];
  document.caretRangeFromPoint = () => ({startContainer: dragText, startOffset: 1});
  dragTo(insert(), bodies()[1], 'after');
  delete document.caretRangeFromPoint;
  same(params.message, '第一条{next}前{at=all}后', '再次拖进第二条句中只保留一份');

  const unsupported = new MiniNode('div');
  editors.buildTemplateEditor(unsupported, {handlers: [{...handler, placeholders: ['{uname}', '{at=all}']}],
    editable: true, context: {isGroup: true, admin: false},
    paramsOf: () => ({message: '前{at=all}后', at_mode: 'all'})});
  same(textOf(unsupported).includes('机器人不是本群管理员，无法选择 @全体成员。'), true, '已确定非管理员时提示');
  const picker = unsupported.querySelector('#tpl-at');
  same(picker.children.map(node => node.disabled), [false, true], '全体档禁用');
  same(allNodes(unsupported).filter(node => hasClass(node, 'tpl-preview-note')).length, 1, '只画实际降级说明');
});

function reminderEditor(placeholders, context, initial = {}, editable = true) {
  const host = new MiniNode('div');
  let params = {...initial};
  const handler = {className: 'demo', placeholders,
    placeholderLabels: {'{at}': '@订阅者', '{at=all}': '@全体成员'},
    defaultParams: {message: '前后'}};
  editors.buildTemplateEditor(host, {handlers: [handler], editable, context,
    paramsOf: () => params, onChange: (_, value) => { params = value; }});
  return {host, params: () => params, choose(mode) {
    const picker = host.querySelector('#tpl-at');
    picker.value = mode;
    picker.dispatchEvent(fakeEvent('change', picker));
    same(host.querySelector('#tpl-at').value, mode, '下拉当前提醒方式');
    same(params.at_mode || 'subscribers', mode, '保存的提醒方式');
  }};
}

// 仅全体类默认不提醒订阅成员，但选全体后仍应能切回默认档。
await ask('仅全体类提醒方式选全体后能切回订阅档', async () => {
  const editor = reminderEditor(['{at=all}'], {isGroup: true, admin: null});
  editor.choose('all');
  editor.choose('subscribers');
});

// 故障：非管理员仍能选择发不出去的全体提醒，或打开编辑器就改掉原有设置。
await ask('全体提醒按群权限变灰，已选值和存值保留', async () => {
  for (const [context, disabled, reason] of [
    [{isGroup: true, admin: false}, [false, true, true], '机器人不是本群管理员，两档 @全体成员 均不可选。'],
    [{isGroup: true, admin: null}, [false, false, false], ''],
  ]) {
    const initial = {at_mode: 'all', message: '前{at=all}后'};
    const editor = reminderEditor(['{at}', '{at=all}'], context, initial);
    const picker = editor.host.querySelector('#tpl-at');
    same(picker.children.map(node => node.disabled), disabled, '选项禁用状态');
    same(picker.value, 'all', '原先选中的全体档');
    same(editor.params(), initial, '打开时存值不变');
    if (reason) same(textOf(editor.host).includes(reason), true, '可见禁用原因');
    editor.choose('subscribers');
    same(editor.host.querySelector('#tpl-at').children.map(node => node.disabled), disabled, '换走后的禁用状态');
  }
});

// 故障：私聊里摆着三档灰掉的 @ 选项和「私聊不支持 @全体成员」，或不画之后把原先那一档改掉了。
await ask('私聊不画「@ 谁」那一行，存值保留', async () => {
  const initial = {at_mode: 'all', message: '前{at=all}后'};
  const editor = reminderEditor(['{at}', '{at=all}'], {isGroup: false, admin: true}, initial);
  same({
    picker: !!editor.host.querySelector('#tpl-at'),
    row: !!editor.host.querySelector('.tpl-at'),
    params: editor.params(),
  }, {picker: false, row: false, params: initial}, '私聊的 @ 那一行');
});

// 界面切回订阅档后，正文不能留下会使发送端仍提醒全体的旧块。
await ask('仅全体类切回订阅档移除已定位的全体块', async () => {
  const editor = reminderEditor(['{at=all}'], {isGroup: true, admin: null});
  editor.choose('all');
  editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk').click();
  same(editor.params().message, '前后{at=all}', '已经定位全体块');
  editor.choose('subscribers');
  same(editor.params().message || '前后', '前后', '切回后不留全体块');
  same(allNodes(editor.host).filter(node => node.dataset.k === '{at=all}').length, 0, '正文全体块数');
});

// 故障：仅全体类下拉仍列没有意义的订阅提醒和降级档，或不提醒时仍显示块与说明。
await ask('仅全体类只有不提醒与全体两项', async () => {
  const editor = reminderEditor(['{at=all}'], {isGroup: true, admin: true});
  same(editor.host.querySelector('#tpl-at').children.map(node => [node.value, node.textContent]),
    [['subscribers', '不 @ 任何人'], ['all', '@全体成员']], '仅全体类可选项');
  same(!!editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk'), false, '不提醒时无 @ 块');
  same(!!editor.host.querySelector('.tpl-at-note'), false, '不提醒时无说明');
  editor.choose('all');
  same(editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk').textContent, '@全体成员', '全体档块');
  same(editor.host.querySelector('.tpl-at-note').textContent,
    '机器人不是管理员或额度用完时，@全体成员 会被省略，通知照常发送。', '全体档说明');
  editor.choose('subscribers');
  same(!!editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk'), false, '切回后无 @ 块');
  same(!!editor.host.querySelector('.tpl-at-note'), false, '切回后无说明');
});

// 故障：选择提醒方式的下拉仍混在可拖块里，看起来也能拖动。
await ask('@ 谁独立在可用块上方，块留在可用块里', async () => {
  const editor = reminderEditor(['{at}', '{at=all}'], {isGroup: true, admin: true});
  const palette = editor.host.querySelector('.tpl-pal');
  const row = editor.host.querySelector('.tpl-at');
  same(palette.contains(editor.host.querySelector('#tpl-at')), false, '下拉不在可用块里');
  same(row.parentNode.parentNode === palette.parentNode, true, '下拉与可用块同属左栏');
  same(palette.parentNode.children.indexOf(row.parentNode) < palette.parentNode.children.indexOf(palette), true, '下拉在可用块上方');
  same(textOf(row).includes('@ 谁：'), true, '下拉标签');
  same(!!palette.querySelector('.tpl-blk'), true, '@ 块在可用块里');
  same(!!editor.host.querySelector('.tpl-at-capsule'), false, '下拉不套块状外壳');
});

// 故障：仅全体类只有一档全体提醒，禁用提示仍说有两档。
await ask('仅全体类非管理员提示只说全体一档', async () => {
  const editor = reminderEditor(['{at=all}'], {isGroup: true, admin: false});
  const warning = editor.host.querySelector('.tpl-at-warning');
  same(warning.textContent, '机器人不是本群管理员，无法选择 @全体成员。', '仅全体类禁用原因');
  same(warning.parentNode === editor.host.querySelector('.tpl-at'), true, '提示跟随下拉行');
  same(editor.host.querySelector('#tpl-at').children.map(node => node.disabled), [false, true], '全体档禁用');
  const privateEditor = reminderEditor(['{at=all}'], {isGroup: false, admin: true});
  same(!!privateEditor.host.querySelector('.tpl-at-warning'), false, '私聊不出禁用提示');
});

// 故障：可用的 @ 块消失、不可操作，或名称和操作提示与其他块不一致。
await ask('有订阅名单时定位控件可用且写明光标', async () => {
  const editor = reminderEditor(['{at}', '{at=all}'], {isGroup: true, admin: true});
  const block = editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk');
  same(!!block && !block.disabled && block.draggable, true, '@ 块存在且可点击拖动');
  same(block.tagName, 'button', '@ 块使用可 Tab 和回车操作的原生按钮');
  same(block.textContent, '@订阅者', '@ 块名称');
  same(block.title, '点击插入到光标处，或拖入卡片', '@ 块操作提示');
  editor.choose('all');
  same(editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk').textContent,
    '@全体成员', '全体档 @ 块名称');
  const locked = reminderEditor(['{at}', '{at=all}'], {isGroup: true, admin: true}, {}, false);
  const lockedBlock = locked.host.querySelector('.tpl-pal').querySelector('.tpl-blk');
  same(lockedBlock.disabled && !lockedBlock.draggable, true, '锁住时 @ 块禁用且不可拖');
  lockedBlock.click();
  same(locked.params(), {}, '锁住时不能插入');
});

// 故障：@ 块点了不进消息，或点第二次多出一个 @。
await ask('@ 块点击插入光标处，再点只挪位置', async () => {
  const editor = reminderEditor(['{at}', '{at=all}'], {isGroup: true, admin: true});
  const clickAt = offset => {
    const body = editor.host.querySelector('.tpl-c-body');
    const text = body.children[0].children[0];
    window.getSelection = () => ({rangeCount: 1,
      getRangeAt: () => ({startContainer: text, startOffset: offset})});
    body.dispatchEvent(fakeEvent('mouseup', body));
    window.getSelection = () => null;
    editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk').click();
  };
  clickAt(1);
  same(editor.params().message, '前{at}后', '首次点击在光标处插入');
  clickAt(0);
  same(editor.params().message, '{at}前后', '再次点击挪到新光标');
  same(allNodes(editor.host).filter(node => node.dataset.k === '{at}').length, 1,
    '全模板只有一个 @ 药丸');
});

// 故障：打开旧仅全体类降级档时，显示错误或悄悄把保存的值改成全体档。
await ask('仅全体类旧降级档显示全体且保留存值', async () => {
  const initial = {at_mode: 'all_or_subscribers', message: '前{at=all}后'};
  const editor = reminderEditor(['{at=all}'], {isGroup: true, admin: true}, initial);
  same(editor.host.querySelector('#tpl-at').value, 'all', '旧降级档显示全体');
  same(editor.params(), initial, '打开时存值不变');
  same(editor.host.querySelector('.tpl-at-note').textContent,
    '机器人不是管理员或额度用完时，@全体成员 会被省略，通知照常发送。', '旧降级档显示全体说明');
  editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk').click();
  same(editor.params().at_mode, 'all_or_subscribers', '编辑正文也保留旧档');
  editor.choose('all');
});

// 触屏用户看不到悬停提示，三档原有说明都应在下拉旁直接可读。
await ask('三档提醒说明常显在下拉旁', async () => {
  const editor = reminderEditor(['{at}', '{at=all}'], {isGroup: true, admin: null});
  const notes = {
    subscribers: '仅 @ 在本群发送过「开播@我」等订阅命令的成员。',
    all: '机器人不是管理员或额度用完时，@全体成员 会被省略，通知照常发送。',
    all_or_subscribers: '@全体成员 被省略的那一次，改为 @ 订阅名单中的成员。',
  };
  for (const [mode, note] of Object.entries(notes)) {
    editor.choose(mode);
    const row = allNodes(editor.host).find(node => hasClass(node, 'tpl-at'));
    same(textOf(row).includes(note), true, '下拉旁常显 ' + mode + ' 说明');
  }
});

// 故障：下播类仍显示提醒控件或权限提示。
await ask('下播类没有提醒行和块', async () => {
  for (const context of [{isGroup: true, admin: true}, {isGroup: true, admin: false}, {isGroup: false, admin: true}]) {
    const editor = reminderEditor(['{uname}', '{next}'], context, {at_mode: 'all'});
    same(!!editor.host.querySelector('#tpl-at'), false, '下播无提醒下拉');
    same(!!editor.host.querySelector('.tpl-at'), false, '下播无提醒行');
    same(!!editor.host.querySelector('.tpl-at-warning'), false, '下播无权限提示');
    same(!!editor.host.querySelector('.tpl-at-note'), false, '下播无提醒说明');
    same(editor.host.querySelector('.tpl-pal').querySelectorAll('.tpl-blk').map(node => node.textContent), ['uname'], '只有普通块');
  }
});

// 故障：旧下播模板仍展示 @、预览仍提醒，或打开时改了存值。
await ask('下播旧提醒不展示且打开保留存值', async () => {
  for (const mode of ['all', 'all_or_subscribers']) {
    const initial = {at_mode: mode, message: '{at=all}{next}前{at}后{at=all}'};
    const editor = reminderEditor(['{uname}', '{next}'], {isGroup: true, admin: true}, initial);
    same(editor.params(), initial, '打开时存值不变');
    same(textOf(editor.host.querySelector('.tpl-cards')).includes('{at'), false, '正文不显示旧提醒');
    same(allNodes(editor.host).some(node => ['{at}', '{at=all}'].includes(node.dataset.k)), false, '旧提醒不画成块');
    same(textOf(editor.host.querySelector('.tpl-pv')).includes('@'), false, '预览无提醒');
    same(!!editor.host.querySelector('.tpl-preview-note'), false, '预览无提醒说明');
  }
});

// 故障：用户保存下播模板后，旧提醒仍留在文字里或旧档被改掉。
await ask('下播编辑保存去掉旧提醒且保留档值', async () => {
  const editor = reminderEditor(['{uname}', '{next}'], {isGroup: true, admin: true},
    {at_mode: 'all_or_subscribers', message: '{at=all}{next}前{at}后{at=all}'});
  editor.host.querySelector('.tpl-pal').querySelector('.tpl-blk').click();
  same(editor.params().message.includes('{at'), false, '保存移除旧提醒');
  same(editor.params().at_mode, 'all_or_subscribers', '正文编辑保留旧档');
  const body = editor.host.querySelector('.tpl-c-body');
  body.innerHTML = '';
  body.appendChild(document.createTextNode('新{at}正文{at=all}'));
  body.dispatchEvent(fakeEvent('input', body));
  same(editor.params().message.includes('{at'), false, '新输入的提醒也在保存时移除');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
