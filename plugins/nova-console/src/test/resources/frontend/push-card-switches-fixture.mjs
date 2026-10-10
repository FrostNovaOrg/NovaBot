/**
 * 版式区里的数据卡片逐张开关
 *
 * 用户那头的故障：主播想给某个群只放几张卡，比如只要流水、弹幕、大航海，不要点赞、
 * 进入直播间、分享，以前只能把「数据卡片」整块开或整块关。现在十张卡各有一个开关，
 * 挂在「数据卡片」下面：缩进列出，总开关关着时灰掉、存着的值不动，打开就亮回来；
 * 恢复默认把这些键一并删掉；改了任一张，状态行就说「自定义版式」。
 *
 * 顺序那几格抓的故障：点了上移、下移以后存下去的参数没变、界面上的顺序没变、焦点丢了；
 * 只调了顺序，通道上仍显示默认版式；恢复默认以后顺序没回去。
 *
 * 本尺真跑整条通道页：push.js 的 renderStreamers、template.js 的版式编辑器、
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
    this.style = {};
    this.listeners = {};
    this.disabled = false;
    this.checked = false;
    this.hidden = false;
    this.title = '';
    this.type = '';
    this.value = '';
  }
  get textContent() { return this._text; }
  set textContent(v) { this._text = String(v ?? ''); }
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
  click() { this.dispatchEvent({type: 'click', target: this, stopPropagation() {}}); }
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
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};

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
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  model = await import(join(pages, 'push-model.js'));
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
  && String(node.innerHTML).includes('本通道用的是') && String(node.innerHTML).includes('版式'));
const message = () => push.pushEntries()[0].targets[0].messages[0];

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

function flip(input) {
  input.checked = !input.checked;
  input.dispatchEvent({type: 'change', target: input});
}

async function paintPage(params) {
  mock.requests.length = 0;
  push.setPushEntries([{
    platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
    targets: [{
      platform: TARGET.platform, type: 1, num: TARGET.num, enabled: true,
      messages: [{handler: HANDLER, params: Object.assign({}, params)}],
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

await ask('① 十张卡在版式表里、紧跟数据卡片、按图上的顺序缩进列出', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const children = OPTIONS.filter(option => option.parentKey === 'cards').map(option => option.label);
  same(children, CARD_LABELS, '版式表里跟着「数据卡片」的项（改前无此功能）');

  await paintPage({});
  const labels = layoutRows().map(rowLabel);
  const at = labels.indexOf('数据卡片');
  if (at < 0) throw new Error('版式区没有「数据卡片」这一行');
  same(labels.slice(at + 1, at + 1 + CARD_LABELS.length), CARD_LABELS, '数据卡片下面那几行');
  const rows = layoutRows();
  const notIndented = CARD_LABELS.filter((label, i) => !hasClass(rows[at + 1 + i], 'rep-sub'));
  same(notIndented, [], '没缩进的卡片行');
  if (hasClass(rows[at], 'rep-sub')) throw new Error('「数据卡片」本身不该缩进');
  const after = rows[at + 1 + CARD_LABELS.length];
  if (after && hasClass(after, 'rep-sub')) throw new Error('卡片之后的「' + rowLabel(after) + '」不该缩进');
});

await ask('② 总开关关着：十张卡灰掉、存着的值不动；打开后亮回来，原来的值还在', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({cards: false, card_like: false});
  const live = CARD_LABELS.filter(label => {
    const input = layoutInput(label);
    return !input || input.disabled !== true;
  });
  same(live, [], '总开关关着时仍可点的卡片开关（改前无此功能）');
  const like = layoutInput('点赞');
  if (like.checked !== false) throw new Error('灰着的「点赞」没照存着的值显示成关');
  same(message().params.card_like, false, '灰着时「点赞」的存值');

  flip(layoutInput('数据卡片'));
  await settle();
  const still = CARD_LABELS.filter(label => {
    const input = layoutInput(label);
    return !input || input.disabled !== false;
  });
  same(still, [], '打开总开关后仍灰着的卡片开关');
  if (layoutInput('点赞').checked !== false) throw new Error('亮回来之后「点赞」不再是原来的关');
  if (layoutInput('分享').checked !== true) throw new Error('亮回来之后「分享」不再是默认的开');
  same(message().params.card_like, false, '亮回来之后「点赞」的存值');
});

await ask('③ 恢复默认：十张卡的键一并删掉，不认得的键照留', async () => {
  if (!model) throw new Error('产品码没载入，无从量起');
  if (typeof model.restoredLayoutParams !== 'function') {
    throw new Error('push-model.js 没有 restoredLayoutParams（改前恢复默认的删法写在按钮里，无从单独量）');
  }
  const handler = store.handlerList[0];
  const params = {message: '{report}', cards: false, card_like: false, card_share: false, card_revenue: false};
  const restored = model.restoredLayoutParams(params, handler);
  same(restored, {message: '{report}'}, '恢复默认之后的参数');
  same(params.card_like, false, '恢复默认不该改动传进来的那一份');
});

await ask('④ 改任一张卡：状态行当场变成「自定义版式」', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  const before = stateLine();
  if (!before || !String(before.innerHTML).includes('默认版式')) {
    throw new Error('全默认时状态行该是「默认版式」：' + (before ? before.innerHTML : '没找到'));
  }
  const share = layoutInput('分享');
  if (!share) throw new Error('版式区没有「分享」这一项（改前无此功能）');
  flip(share);
  await settle();
  same(message().params.card_share, false, '拨掉「分享」后的存值');
  const after = stateLine();
  if (!after || !String(after.innerHTML).includes('自定义版式')) {
    throw new Error('改了「分享」后状态行该是「自定义版式」：' + (after ? after.innerHTML : '没找到'));
  }
});

// —— 卡片顺序 ——

const CARD_KEYS = ['card_revenue', 'card_danmu', 'card_like', 'card_enter', 'card_follow',
  'card_super_chat', 'card_guard', 'card_box', 'card_free_gift', 'card_share'];

const moveButton = name => allNodes(page())
  .find(node => node.tagName === 'button' && node.getAttribute('aria-label') === name) || null;

/** 版式区里「数据卡片」下面那几行，按界面上的先后读出卡名 */
function cardRowLabels() {
  const rows = layoutRows();
  const at = rows.map(rowLabel).indexOf('数据卡片');
  if (at < 0) throw new Error('版式区没有「数据卡片」这一行');
  const out = [];
  for (let i = at + 1; i < rows.length && hasClass(rows[i], 'rep-sub'); i++) out.push(rowLabel(rows[i]));
  return out;
}

await ask('⑤ 上移下移：换位、存进参数、界面重排，焦点跟着移走的那一项；头尾的按钮不可点', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  const absent = CARD_LABELS.flatMap(label => ['上移：' + label, '下移：' + label]).filter(name => !moveButton(name));
  same(absent, [], '版式区里找不到的上移下移按钮（改前无此功能）');
  same(moveButton('上移：流水').disabled, true, '第一项的「上移」可不可点');
  same(moveButton('下移：分享').disabled, true, '最后一项的「下移」可不可点');
  const live = CARD_LABELS.flatMap(label => ['上移：' + label, '下移：' + label])
    .filter(name => name !== '上移：流水' && name !== '下移：分享' && moveButton(name).disabled);
  same(live, [], '中间那些不该锁着的按钮');

  moveButton('下移：流水').click();
  await settle();
  same(message().params.card_order, ['card_danmu', 'card_revenue', ...CARD_KEYS.slice(2)].join(','),
    '下移「流水」之后存下去的顺序');
  same(cardRowLabels(), ['弹幕', '流水', ...CARD_LABELS.slice(2)], '下移「流水」之后界面上的顺序');
  if (document.activeElement !== moveButton('下移：流水')) {
    throw new Error('下移之后焦点没留在「下移：流水」上：' + (document.activeElement
      ? document.activeElement.getAttribute('aria-label') : '无'));
  }

  // 连按：焦点一直在同一个按钮上，就一直往下移；移到底那一下，焦点落到「上移：流水」
  for (let i = 0; i < CARD_LABELS.length - 2; i++) document.activeElement.click();
  await settle();
  same(cardRowLabels(), [...CARD_LABELS.slice(1), '流水'], '连按「下移：流水」到底之后界面上的顺序');
  same(message().params.card_order, [...CARD_KEYS.slice(1), 'card_revenue'].join(','), '移到底之后存下去的顺序');
  same(moveButton('下移：流水').disabled, true, '移到最后之后「下移：流水」可不可点');
  if (document.activeElement !== moveButton('上移：流水')) {
    throw new Error('移到底之后焦点该落到「上移：流水」：' + (document.activeElement
      ? document.activeElement.getAttribute('aria-label') : '无'));
  }

  moveButton('上移：分享').click();
  await settle();
  same(cardRowLabels().slice(-3), ['分享', '免费礼物', '流水'], '上移「分享」之后末尾三行');
});

await ask('⑥ 关着的卡也能调位置；总开关关着时上移下移一起锁住、存着的顺序不动', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({card_like: false});
  const like = moveButton('上移：点赞');
  if (!like) throw new Error('版式区没有「上移：点赞」（改前无此功能）');
  same(like.disabled, false, '关着的「点赞」的上移可不可点');
  like.click();
  await settle();
  same(cardRowLabels().slice(0, 3), ['流水', '点赞', '弹幕'], '上移关着的「点赞」之后前三行');

  const stored = [...CARD_KEYS].reverse().join(',');
  await paintPage({cards: false, card_order: stored});
  same(cardRowLabels(), [...CARD_LABELS].reverse(), '总开关关着时界面照存着的顺序列出');
  const unlocked = CARD_LABELS.flatMap(label => ['上移：' + label, '下移：' + label])
    .filter(name => !moveButton(name) || moveButton(name).disabled !== true);
  same(unlocked, [], '总开关关着时仍可点的上移下移');
  same(message().params.card_order, stored, '总开关关着时存着的顺序');
});

await ask('⑦ 只调了顺序也算自定义；调回原样就回到默认；恢复默认把顺序一起删掉', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintPage({});
  const down = moveButton('下移：弹幕');
  if (!down) throw new Error('版式区没有「下移：弹幕」（改前无此功能）');
  down.click();
  await settle();
  const custom = stateLine();
  if (!custom || !String(custom.innerHTML).includes('自定义版式')) {
    throw new Error('只调了顺序，状态行该是「自定义版式」：' + (custom ? custom.innerHTML : '没找到'));
  }
  moveButton('上移：弹幕').click();
  await settle();
  const back = stateLine();
  if (!back || !String(back.innerHTML).includes('默认版式')) {
    throw new Error('顺序调回原样，状态行该回到「默认版式」：' + (back ? back.innerHTML : '没找到')
      + '；存着的是 ' + message().params.card_order);
  }

  const handler = store.handlerList[0];
  const restored = model.restoredLayoutParams({message: '{report}', card_order: 'card_share,card_like'}, handler);
  same(restored, {message: '{report}'}, '恢复默认之后的参数（顺序键该一起删掉）');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
