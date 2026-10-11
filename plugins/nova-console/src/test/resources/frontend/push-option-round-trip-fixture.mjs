/**
 * 新建通道的数据卡片、模板与版式里其他选项改了又改回之后，改动条认不认得「回到了原样」
 *
 * 用户那头的故障：推送页上把新建通道的数据卡片拿掉再放回，或把版式里别的选项改了又改回
 * 原值，改动条还显示有一处没保存，换页时还要问要不要放弃。两处根子相同：
 * 一是向导建出来的通道，消息上连 params 这个键都没有（一条 {handler}），编辑器改完又改回
 * 原样时交回来的 params 空了，照写 message.params = {} 的话与快照里没有这一键仍差一截；
 * 二是版式区里数据卡片以外的选项写回时只写值不看形状，快照里本来没存这个键（跟着默认走）
 * 的通道改了又改回原值，参数里多出一个原样值的键。
 *
 * 改动条按整段 JSON 与快照比（见 push.js 的 changeCount）：键的形状与快照对不上就是一处
 * 改动——多一个键、少一个键都算。「回到原样」得连键的形状一起回到原样：快照里没这个键的，
 * 回来时也不能有；快照里显式存着的（哪怕存的正是默认值），回来时还得是显式存着的那一个。
 *
 * 本尺真跑整条通道页：push.js 的 renderStreamers、template.js 的版式与模板编辑器都是
 * 产品码。版式项清单不手抄，由 PushOptionRoundTripTest 把下播报告真正的那张表照
 * /api/handlers 的序列化经环境变量 LAYOUT_OPTIONS 交进来。
 *
 * 各格抓的故障写在各格开头。各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
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
    const frame = stack.split('\n').find(line => line.includes('push-option-round-trip-fixture.mjs')) || '';
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

// —— /api/handlers 的形态：下播报告那一张版式表，与一条带文字模板的通知 ——

const REPORT_HANDLER = 'org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler';
const TEXT_HANDLER = 'org.frostnova.nova.demo.handler.OpenNoticePushHandler';

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

// 段 3 里数据卡片以外的平铺选项：一个开关、一个数字，都不是小块
const ROW_BOOL = {label: '直播间封面', key: 'cover'};
const ROW_INT = {label: '全名单人数', key: 'guard_list_limit', at: 20, back: 30};

const TARGET = {platform: 'qq-onebot', type: 1, num: 12345};

const SESSION = {
  platform: 'qq-onebot', num: 12345, type: 1,
  disabled: [], menuHidden: [], menuNotes: {},
  revenueVisible: true, revenueExplicit: true,
  streamers: ['甲'],
};

// —— 载入产品码 ——

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
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
const hasClass = (node, name) => String((node || {}).className || '').split(/\s+/).includes(name);

/** 版式区里带 aria-label 的最新一枚控件（开关或数字框）：改一下画一遍，取最新的 */
function rowControl(label) {
  const node = allNodes(page())
    .filter(item => item.tagName === 'input' && item.getAttribute('aria-label') === label).at(-1);
  if (!node) throw new Error('版式区没有「' + label + '」这一行');
  return node;
}

/** 拨一下开关：on 为拨成开还是关 */
function flip(label, on) {
  const node = rowControl(label);
  node.checked = on;
  node.dispatchEvent(fakeEvent('change', node));
}

/** 数字框改成几 */
function typeNumber(label, value) {
  const node = rowControl(label);
  node.value = String(value);
  node.dispatchEvent(fakeEvent('change', node));
}

/** @ 那一档换成哪一档 */
function pickAt(mode) {
  const node = allNodes(page()).filter(item => item.tagName === 'select' && item.id === 'tpl-at').at(-1);
  if (!node) throw new Error('模板编辑器里没有「@ 谁」那一档');
  node.value = mode;
  node.dispatchEvent(fakeEvent('change', node));
}

// —— 两区小块 ——

const ON = 'rep-zone-on';
const OFF = 'rep-zone-off';
const ZONE_NAME = {[ON]: '报告里的卡片', [OFF]: '没放进报告的'};

function zone(which) {
  const node = allNodes(page()).find(item => hasClass(item, which));
  if (!node) throw new Error('版式区没有「' + ZONE_NAME[which] + '」那一区');
  return node;
}

const chipsIn = which => allNodes(zone(which)).filter(node => hasClass(node, 'rep-chip'));
const labelsIn = which => chipsIn(which).map(node => LABEL_OF[node.dataset.card] || node.dataset.card);

function chip(which, label) {
  const node = chipsIn(which).find(item => item.dataset.card === KEY_OF[label]);
  if (!node) throw new Error('「' + ZONE_NAME[which] + '」里没有「' + label + '」');
  return node;
}

function killButton(label) {
  const node = allNodes(page())
    .find(item => item.tagName === 'button' && item.getAttribute('aria-label') === '拿掉：' + label);
  if (!node) throw new Error('版式区没有「拿掉：' + label + '」按钮');
  return node;
}

/** 这条通道上某一类的消息（产品码里 messageOf 同一把尺：按处理器名找） */
const messageOf = name => push.pushEntries()[0].targets[0].messages
  .find(item => item.handler === name) || null;
const reportMessage = () => messageOf(REPORT_HANDLER);
const textMessage = () => messageOf(TEXT_HANDLER);

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

/**
 * 把通道页画出来。params 为 null 时报告那条消息上不带 params 这一键：
 * 刚加的通道就是这样，[{handler}]，一个参数都没存过。文字模板那条消息一律如此——
 * 向导建出来的通道，每条消息都是光秃秃的一条 {handler}
 */
async function paintPage(params) {
  mock.requests.length = 0;
  push.setPushEntries([{
    platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: TARGET.platform, type: 1, num: TARGET.num, enabled: true,
      messages: [
        params === null ? {handler: REPORT_HANDLER} : {handler: REPORT_HANDLER, params: Object.assign({}, params)},
        {handler: TEXT_HANDLER},
      ],
    }],
  }]);
  push.setRuntimeState({sessions: [SESSION], commands: [], subscriptions: [], incomplete: []});
  push.showPush('1', String(TARGET.num));
  push.renderStreamers();
  await settle();
}

if (push && store) {
  store.handlerList = [
    {
      className: REPORT_HANDLER,
      aliases: [],
      displayName: '下播报告',
      description: '下播时把整场报告画成一张图',
      platform: 'bilibili',
      placeholders: [],
      attachments: [],
      defaultParams: {},
      factoryParams: {},
      options: OPTIONS,
    },
    {
      className: TEXT_HANDLER,
      aliases: [],
      displayName: '开播通知',
      description: '开播时往群里说一声',
      platform: 'bilibili',
      placeholders: ['{uname}', '{title}'],
      attachments: [],
      defaultParams: {message: '开播了'},
      factoryParams: {message: '开播了'},
      options: [],
    },
  ];
}

// 故障：新建的通道，消息上连 params 这个键都没有。把一张数据卡片拿掉再点回原位，
// 或把 @ 那一档换一档再换回来——值都回到了原样，状态行也回到了「默认」，改动条却还写着
// 「1 处改动」：params 这一键被写了进去，空着也与快照里没有这一键差一截
await ask('① 新建的通道：卡片拿掉再放回、@ 档换一档再换回，改动条 0', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  // 报告那条消息：拿掉最后一张卡，再点回原位（放回末尾＝它原来那一位）
  await paintPage(null);
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '拿掉「分享」之后的改动数');
  chip(OFF, '分享').click();
  await settle();
  same(push.changeCount(), 0, '再点回原位之后的改动数');
  same(labelsIn(ON), CARD_LABELS, '再点回原位之后报告区');
  same(Object.prototype.hasOwnProperty.call(reportMessage(), 'params'), false,
    '再点回原位之后报告那条消息的 params 键');

  // 文字模板那条消息：@ 换成「@全体成员」再换回来
  await paintPage(null);
  push.markPushSaved();
  pickAt('all');
  await settle();
  same(push.changeCount(), 1, '@ 换成「@全体成员」之后的改动数');
  pickAt('subscribers');
  await settle();
  same(push.changeCount(), 0, '@ 换回来之后的改动数');
  same(Object.prototype.hasOwnProperty.call(textMessage(), 'params'), false,
    '@ 换回来之后文字模板那条消息的 params 键');
});

// 故障：版式区里数据卡片以外的选项（开关、数字）改了又改回原值。写回只写值不看形状：
// 快照里本来没存这个键（跟着默认走）的，回来时参数里多出一个原样值的键，改动条记一处。
// 快照里显式存着等于默认值的，回来时还得是显式存着的那一个——两种起点都要对上
await ask('② 版式里非数据卡片的选项改了又改回原值：改动条 0（两种起点都对上）', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  // 起点一：快照里没存这个键，跟着默认走
  await paintPage(null);
  push.markPushSaved();
  flip(ROW_BOOL.label, false);
  await settle();
  same(push.changeCount(), 1, '「' + ROW_BOOL.label + '」关掉之后的改动数');
  flip(ROW_BOOL.label, true);
  await settle();
  same(push.changeCount(), 0, '「' + ROW_BOOL.label + '」改回开之后的改动数');
  same(Object.prototype.hasOwnProperty.call(reportMessage(), 'params'), false,
    '「' + ROW_BOOL.label + '」改回开之后报告那条消息的 params 键');

  typeNumber(ROW_INT.label, ROW_INT.at);
  await settle();
  same(push.changeCount(), 1, '「' + ROW_INT.label + '」改成 ' + ROW_INT.at + ' 之后的改动数');
  typeNumber(ROW_INT.label, ROW_INT.back);
  await settle();
  same(push.changeCount(), 0, '「' + ROW_INT.label + '」改回 ' + ROW_INT.back + ' 之后的改动数');
  same(Object.prototype.hasOwnProperty.call(reportMessage(), 'params'), false,
    '「' + ROW_INT.label + '」改回之后报告那条消息的 params 键');

  // 起点二：快照里显式存着等于默认值的键，回来时还得是显式存着的那一个
  await paintPage({[ROW_BOOL.key]: true});
  push.markPushSaved();
  flip(ROW_BOOL.label, false);
  await settle();
  same(push.changeCount(), 1, '快照显式存着默认值时，「' + ROW_BOOL.label + '」关掉之后的改动数');
  flip(ROW_BOOL.label, true);
  await settle();
  same(push.changeCount(), 0, '快照显式存着默认值时，「' + ROW_BOOL.label + '」改回开之后的改动数');
  same(reportMessage().params[ROW_BOOL.key], true,
    '快照显式存着默认值时，改回开之后这个键还得显式存着');
});

// 故障：改过头——真改了一处也被抹平，改动条回到 0，「还有改动没存」不见了
await ask('③ 守住：真改一处记 1', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage(null);
  push.markPushSaved();
  killButton('分享').click();
  await settle();
  same(push.changeCount(), 1, '真拿掉一张卡之后的改动数');

  await paintPage(null);
  push.markPushSaved();
  flip(ROW_BOOL.label, false);
  await settle();
  same(push.changeCount(), 1, '真关掉「' + ROW_BOOL.label + '」之后的改动数');
  same(reportMessage().params[ROW_BOOL.key], false, '真关掉「' + ROW_BOOL.label + '」之后存着的值');

  await paintPage(null);
  push.markPushSaved();
  typeNumber(ROW_INT.label, ROW_INT.at);
  await settle();
  same(push.changeCount(), 1, '真把「' + ROW_INT.label + '」改成 ' + ROW_INT.at + ' 之后的改动数');

  await paintPage({[ROW_BOOL.key]: true});
  push.markPushSaved();
  flip(ROW_BOOL.label, false);
  await settle();
  same(push.changeCount(), 1, '快照显式存着默认值时，真关掉「' + ROW_BOOL.label + '」之后的改动数');

  await paintPage(null);
  push.markPushSaved();
  pickAt('all');
  await settle();
  same(push.changeCount(), 1, '真把 @ 换成「@全体成员」之后的改动数');
  same(textMessage().params.at_mode, 'all', '真把 @ 换成「@全体成员」之后存着的值');
  same(alerts, [], '弹出的话');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
