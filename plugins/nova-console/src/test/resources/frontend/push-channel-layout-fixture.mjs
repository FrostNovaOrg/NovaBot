import {datasourceRead} from './datasource-read.mjs';
/**
 * 通道页按通知类型重排之后，几件点了才看得出来的事
 *
 * 用户那头的毛病，逐格写在各格上方。量的是行为（点了以后出现什么、存下去的是什么），
 * 不量排布与样式：排布由测试机上的人看过再定。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，通道页走的是产品码那一份（push.js 的 showPush 与 save）。
 * 第一个命令行参数可以指另一份页面目录（量改动前的那一份时用），缺省量源码树里这一份。
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。由 PushChannelLayoutFixtureTest 拉起。
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
    const frame = stack.split('\n').find(line => line.includes('push-channel-layout-fixture.mjs')) || '';
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
    this.open = false;
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
  // 挂在页上（一路往上走得到某个按号取的根节点）才算连着：折叠项的开合记忆认这一条
  get isConnected() {
    for (let at = this; at; at = at.parentNode) if (at.root) return true;
    return false;
  }
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
    // 确认弹层：标题、正文、各区与两颗按钮都立成节点（平铺在弹层下），「确认」要点得到
    if (this._html.includes('ask-card')) {
      const tags = /<([a-z0-9]+)\b([^>]*)>/gi;
      let tag;
      while ((tag = tags.exec(this._html))) {
        const node = new MiniNode(tag[1]);
        const classMatch = /class\s*=\s*"([^"]*)"/i.exec(tag[2]);
        if (classMatch) node.className = classMatch[1];
        const actMatch = /data-act\s*=\s*"([^"]*)"/i.exec(tag[2]);
        if (actMatch) {
          node.dataset.act = actMatch[1];
          node.attributes['data-act'] = actMatch[1];
        }
        this.appendChild(node);
      }
      return;
    }
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
  removeAttribute(name) { delete this.attributes[name]; }
  click() { this.dispatchEvent(fakeEvent('click', this)); }
  focus() { globalThis.document.activeElement = this; }
  blur() { this.dispatchEvent(fakeEvent('blur', this)); }
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
  const dataValue = /^\[data-([a-z]+)="([^"]*)"]$/.exec(sel);
  if (dataValue) return !!node.dataset && node.dataset[dataValue[1]] === dataValue[2];
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
    node.root = true;
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

const notice = (className, displayName, extra = {}) => ({
  className, aliases: [], displayName, description: displayName + '时发送', platform: '',
  placeholders: ['{uname}'], placeholderLabels: {}, attachments: [],
  defaultParams: {message: '{uname} ' + displayName}, factoryParams: {message: '{uname} ' + displayName},
  options: [], reportLayout: false, defaultEnabled: true, ...extra,
});
// 开播与动态带两种 @ 占位符，与内置那两类一样：编辑器据此画「@ 谁」那一行
const AT_PLACEHOLDERS = ['{uname}', '{at}', '{at=all}'];
const LIVE_ON = notice('x.LiveOn', '开播通知', {placeholders: AT_PLACEHOLDERS});
const REPORT = notice('org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler', '下播报告', {placeholders: [], reportLayout: true, options: [
  {key: 'cover', label: '直播间封面', description: '', type: 'BOOLEAN', defaultValue: true}]});
const DIGEST = notice('x.Digest', '下播打赏播报', {options: [
  {key: 'reward_threshold_yuan', label: '播报门槛（元）', description: '', type: 'INTEGER',
    defaultValue: 50, min: 0, max: 1000000}]});
const DYNAMIC = notice('x.Dynamic', '动态通知', {placeholders: AT_PLACEHOLDERS});
const WEEKLY = notice('org.frostnova.nova.core.handler.WeeklyReportPushHandler', '运营周报',
  {placeholders: [], defaultParams: {}, factoryParams: {}, defaultEnabled: false});
const HANDLERS = [LIVE_ON, REPORT, DIGEST, DYNAMIC, WEEKLY];

const GROUP = {platform: 'qq', type: 1, num: 12345, enabled: true};
const FRIEND = {platform: 'qq', type: 0, num: 67890, enabled: true};
// 运行数据里没有这一位：机器人没连上、或刚加的好友，命令那一块拿不到菜单状态
const STRANGER = {platform: 'qq', type: 0, num: 67891, enabled: true};
const COMMANDS = [{name: '直播报告', category: '数据查询', description: '查本场', disableable: true, channelScoped: true}];
const SESSIONS = [
  {platform: 'qq', num: 12345, type: '群', typeCode: 1, disabled: [], menuHidden: [], menuNotes: {}},
  {platform: 'qq', num: 67890, type: '好友', typeCode: 0, disabled: [], menuHidden: [], menuNotes: {}},
];

const requests = [];
let saved = [];
globalThis.fetch = async (path, opt = {}) => {
  const url = String(path);
  requests.push({url, opt});
  if (url.endsWith('/api/report/preview')) return {ok: true, blob: async () => 'image'};
  if (url.includes('/weekly-report/')) return {status: 200, json: async () => ({success: true, status: '上次 10/19 10:00 已发出'})};
  if (url.includes('/datasource')) {
    if (opt.method === 'POST') {
      saved = [JSON.parse(opt.body).user];
      return {status: 200, json: async () => ({success: true, version: 'v1'})};
    }
    return {status: 200, json: async () => datasourceRead(url, JSON.stringify(saved))};
  }
  if (url.endsWith('/state')) {
    return {status: 200, json: async () => ({commands: COMMANDS, sessions: SESSIONS, subscriptions: [],
      incomplete: [], totalDataAvailable: true, quietGlobal: {start: '23:00', end: '08:00'}})};
  }
  return {status: 200, json: async () => ({success: true, records: [], handlers: HANDLERS})};
};

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  store = (await import('./store.js')).store;
} catch (err) {
  failures.push('载入 push.js 就抛了：' + err.message);
}

const user = {platform: 'bilibili', uid: 1, _uname: '甲'};
const all = root => (root ? [root, ...root.children.flatMap(all)] : []);
const page = () => idNode('push-right');
const hasClass = (node, name) => String(node.className).split(/\s+/).includes(name);
/** 这个节点与它底下读得到的字：直接写的字、innerHTML 里的字、子节点的字 */
const textOf = node => all(node).map(item => item._text + String(item._html || '').replace(/<[^>]*>/g, '')).join(' ');
const message = (handler, params) => (params ? {handler: handler.className, params} : {handler: handler.className});
const messages = () => push.pushEntries()[0].targets[0].messages;
const inputOf = label => all(page()).find(item => item.tagName === 'input' && item.getAttribute('aria-label') === label) || null;
const rowOf = handler => all(page()).find(item => hasClass(item, 'nrow') && item.getAttribute('data-notice') === handler.className) || null;
const editOf = handler => all(page()).find(item => item.tagName === 'button'
  && item.getAttribute('data-notice-edit') === handler.className) || null;
const openRows = () => all(page()).filter(item => hasClass(item, 'nrow') && all(item).some(child => hasClass(child, 'nrow-body')))
  .map(item => item.getAttribute('data-notice'));
const offFold = () => all(page()).find(item => item.tagName === 'details' && hasClass(item, 'p-off-fold')) || null;

function change(node, value) {
  if (node.type === 'checkbox' || node.type === 'radio') node.checked = value; else node.value = String(value);
  node.dispatchEvent(fakeEvent('change', node));
}

function toggle(details, open) {
  details.open = open;
  details.dispatchEvent(fakeEvent('toggle', details));
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve));
}

async function paint(target, list) {
  requests.length = 0;
  store.handlerList = HANDLERS;
  saved = [{...user, targets: [{...target, messages: list}]}];
  await push.reload();
  await push.loadPushPage();
  await push.showPush('1', target.platform + '|' + target.type + '|' + target.num);
  await settle();
  push.renderStreamers();
}

/** 点某一行的「编辑」；按钮不在就红，不往下量 */
function edit(handler) {
  const button = editOf(handler);
  if (!button) throw new Error('「' + handler.displayName + '」那一行没有「编辑」');
  button.click();
}

// 故障：两行同时摊开，页面又回到「找了半天才找到我在改哪一种」；或展开一行时页上挂着几份编辑器，
// 预览与改动各认各的一份。
await ask('① 行内编辑同时只展开一行：点了另一行，前一行收起', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(GROUP, [message(LIVE_ON), message(REPORT), message(DIGEST)]);
  const before = openRows();
  edit(REPORT);
  const first = openRows();
  edit(LIVE_ON);
  const second = openRows();
  const editors = all(page()).filter(item => hasClass(item, 'tpl-wrap')).length;
  edit(LIVE_ON);
  same({before, first, second, editors, closed: openRows()},
    {before: [], first: [REPORT.className], second: [LIVE_ON.className], editors: 1, closed: []}, '展开的行');
});

// 故障：展开一行改了门槛，转去编辑别的一行再保存，改的那一处丢了；或这一行的现状还写着旧值。
await ask('② 展开后改了能存住：改门槛、换一行编辑、保存，读回仍是新值', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(GROUP, [message(LIVE_ON), message(DIGEST)]);
  edit(DIGEST);
  const settings = all(rowOf(DIGEST)).find(item => item.tagName === 'button' && item.textContent === '设置');
  if (!settings) throw new Error('下播打赏播报的编辑区里没有「设置」标签');
  settings.click();
  const box = inputOf('播报门槛（元）');
  if (!box) throw new Error('展开后找不到门槛框');
  change(box, 200);
  const meta = textOf(rowOf(DIGEST));
  edit(LIVE_ON);
  await push.save();
  const stored = saved[0].targets[0].messages.find(item => item.handler === DIGEST.className);
  await paint(GROUP, saved[0].targets[0].messages);
  edit(DIGEST);
  all(rowOf(DIGEST)).find(item => item.tagName === 'button' && item.textContent === '设置').click();
  same({
    '那一行的现状写着新门槛': meta.includes('播报门槛（元） 200'),
    '存下去的门槛': stored && stored.params && stored.params.reward_threshold_yuan,
    '重新打开后的门槛框': inputOf('播报门槛（元）') && inputOf('播报门槛（元）').value,
  }, {
    '那一行的现状写着新门槛': true,
    '存下去的门槛': 200,
    '重新打开后的门槛框': '200',
  }, '门槛');
});

// 故障：没选「自定义」时也摆着两个填不了的时间框，读起来像另外两档也要填时间；选了自定义却不出框。
await ask('③ 静音时段选「自定义」才出现时间框，换回别的档就收起', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(GROUP, [message(LIVE_ON)]);
  const follow = [!!inputOf('静音开始'), !!inputOf('静音结束')];
  change(inputOf('本通道自定义时段'), true);
  const custom = [inputOf('静音开始') && inputOf('静音开始').value, inputOf('静音结束') && inputOf('静音结束').value];
  change(inputOf('本通道不静音'), true);
  const off = [!!inputOf('静音开始'), !!inputOf('静音结束')];
  same({follow, custom, off}, {follow: [false, false], custom: ['23:00', '08:00'], off: [false, false]}, '时间框');
});

// 故障：好友通道底下写着「@全体成员（本群各主播共用）」「群成员发送…即可订阅」，好友里根本没有这回事。
await ask('④ 好友通道不出现群的说法，也不画 @全体成员与提醒订阅', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(FRIEND, [message(LIVE_ON)]);
  const text = textOf(page());
  same({
    '出现「群」字': [...text.matchAll(/.{0,8}群.{0,8}/g)].map(match => match[0]),
    '出现 @全体成员': text.includes('@全体成员'),
    '出现提醒订阅': text.includes('提醒订阅'),
    '写着该好友': text.includes('该好友'),
  }, {
    '出现「群」字': [],
    '出现 @全体成员': false,
    '出现提醒订阅': false,
    '写着该好友': true,
  }, '好友通道的说法');
});

// 故障：没开的几种收进了「没开的 n 种」，展开后却拨不动，或拨了之后它还留在收起的那一堆里。
await ask('⑤ 收起的「没开的 n 种」展开后能开，开了就回到上面那一列', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(GROUP, [message(LIVE_ON), message(REPORT), message(DIGEST), message(WEEKLY)]);
  const fold = offFold();
  if (!fold) throw new Error('没有「没开的 n 种」那一块');
  const summary = all(fold).find(item => item.tagName === 'summary');
  const closed = fold.open;
  toggle(fold, true);
  const sw = all(fold).find(item => item.tagName === 'input' && item.getAttribute('aria-label') === '动态通知');
  if (!sw) throw new Error('展开后没有「动态通知」的开关');
  change(sw, true);
  const row = rowOf(DYNAMIC);
  same({
    '收起时的标题': summary && summary.textContent,
    '默认收着': closed,
    '开了以后配置里有这一种': messages().some(item => item.handler === DYNAMIC.className),
    '开了以后它在收起那一堆外面': !!row && !all(offFold()).includes(row),
    '全开了就没有那一块': offFold() === null,
  }, {
    '收起时的标题': '没开的 1 种',
    '默认收着': false,
    '开了以后配置里有这一种': true,
    '开了以后它在收起那一堆外面': true,
    '全开了就没有那一块': true,
  }, '没开的那几种');
});

// 故障：运营周报关着，「上次发出 · 预览 · 发送上一周的报告」照样摆着，看着像周报在发。
await ask('⑥ 运营周报关着时不显示周报工具，开着时在它那一行里', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(GROUP, [message(LIVE_ON)]);
  const offTools = all(page()).filter(item => hasClass(item, 'weekly-report-tools')).length;
  await paint(GROUP, [message(LIVE_ON), message(WEEKLY)]);
  const tools = all(page()).find(item => hasClass(item, 'weekly-report-tools'));
  same({offTools, onInRow: !!tools && all(rowOf(WEEKLY)).includes(tools)}, {offTools: 0, onInRow: true}, '周报工具');
});

// 故障：页头那行摘要点了没反应，命令那块收着时使用者以为没有命令可调。
await ask('⑦ 页头摘要点「命令 n 条可用」：命令那一块展开', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(GROUP, [message(LIVE_ON)]);
  const jump = all(page()).find(item => item.tagName === 'button' && /^命令 \d+ 条可用$/.test(item.textContent));
  if (!jump) throw new Error('页头没有「命令 n 条可用」');
  const fold = all(page()).find(item => item.tagName === 'details' && all(item).some(child => child.tagName === 'summary'
    && String(child._html).includes('群内命令')));
  if (!fold) throw new Error('没有「群内命令」那一块');
  const before = fold.open;
  jump.click();
  same({text: jump.textContent, before, after: fold.open}, {text: '命令 1 条可用', before: false, after: true}, '跳到命令');
});

// 故障：好友通道展开开播通知，还能看到 @全体成员那几档（只是变灰），和「仅 @ 在本群发送过……」
// 「私聊不支持 @全体成员。」这类说法；好友里根本没有这回事。看完存一遍，存下的值也不能变。
await ask('⑧ 好友通道展开开播通知、动态通知：不出现 @全体成员与群的说法，存下的值不变', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const list = [message(LIVE_ON, {message: '{uname} 开播通知', at_mode: 'all'}), message(DYNAMIC)];
  await paint(FRIEND, list);
  const seen = {};
  for (const handler of [LIVE_ON, DYNAMIC]) {
    edit(handler);
    const row = rowOf(handler);
    if (!all(row).some(item => hasClass(item, 'tpl-wrap'))) throw new Error(handler.displayName + ' 展开后没有编辑器');
    const text = textOf(row);
    seen[handler.displayName] = {
      '出现「群」字': [...text.matchAll(/.{0,8}群.{0,8}/g)].map(match => match[0]),
      '出现 @全体成员': [...text.matchAll(/.{0,8}@全体成员.{0,8}/g)].map(match => match[0]),
      '「@ 谁」的选项': all(row).filter(item => item.tagName === 'option').map(item => item.textContent),
    };
  }
  const want = {'出现「群」字': [], '出现 @全体成员': [], '「@ 谁」的选项': []};
  same({seen, stored: messages()}, {seen: {'开播通知': want, '动态通知': want}, stored: list}, '好友通道的编辑区');
});

// 故障：好友这一位不在运行数据里时，命令那一块写着「不代表群里实际列出的命令」，好友里没有群。
await ask('⑨ 好友通道缺运行数据时，命令提示不说群', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await paint(STRANGER, [message(LIVE_ON)]);
  const text = textOf(page());
  same({
    '出现「群」字': [...text.matchAll(/.{0,8}群.{0,8}/g)].map(match => match[0]),
    '提示了拿不到菜单状态': text.includes('无法获取菜单状态'),
  }, {'出现「群」字': [], '提示了拿不到菜单状态': true}, '缺运行数据时的命令提示');
});

await ask('报告操作与图片编辑', async () => {
  await paint(GROUP, [message(REPORT), message(WEEKLY)]);
  same(all(rowOf(WEEKLY)).some(node => node.textContent === '发送上一周的报告'), true, '周报发送按钮');
  same(all(rowOf(REPORT)).some(node => node.textContent === '发送上一份下播报告'), true, '下播发送按钮');
  edit(REPORT);
  same(all(rowOf(REPORT)).some(node => node.textContent === '文字' || hasClass(node, 'tpl-wrap')), false, '下播报告没有文字编辑');
});

await ask('旧报告文字保留，默认模板不提供下播报告文字编辑', async () => {
  const legacy = '保留的旧文字{uname}{report}{at=all}';
  await paint(GROUP, [message(REPORT, {message: legacy}), message(LIVE_ON)]);
  if (!all(rowOf(REPORT)).some(node => hasClass(node, 'nrow-body'))) edit(REPORT);
  change(inputOf('直播间封面'), false);
  await push.save();
  same(saved[0].targets[0].messages.find(item => item.handler === REPORT.className).params.message,
    legacy, '保存版式后保留旧文字');
  await push.showPush('default');
  same(all(page()).some(node => node.tagName === 'button' && node.textContent === '下播报告'), false, '默认模板的通知清单');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
