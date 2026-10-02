/**
 * 版式区与「本群设置 · 金额可见」联动：不出图的项灰掉并写原因，草稿里拨了当场跟着变
 *
 * 用户那头的故障：群里设了不显示金额，再去版式里把「流水排行」调成前 10 名，
 * 图上永远没有这一榜——以为是坏了；反过来开着金额勾「本场开通大航海名单」，
 * 怎么勾也不出。版式区若不照当前的金额设定灰掉这些项，配了不出图与坏了就长得一样。
 *
 * 本尺<b>真跑</b>：假 DOM 与假 fetch 之外，整条通道页（含版式段与本群设置段）用的都是
 * 产品码那一份——push.js 的 renderStreamers、template.js 的版式编辑器、sessions.js 的
 * 金额开关、session-draft.js 的草稿，一路都是真码。只查回包字段是不行的：字段在、
 * 灰不掉，照样绿。处理器清单喂成 /api/handlers 的形态（版式项带随金额栏与原因句）。
 *
 * 预览那一头一起量：草稿里拨了金额开关（还没保存），预览请求要带上草稿的值、
 * 图下说明跟着说——预览与灰掉读的是同一个草稿，两个读法分叉的话，图上是显示的、
 * 开关那头灰着，两头各说各话。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 PushRevenueLayoutLinkTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
    const frame = stack.split('\n').find(line => line.includes('push-revenue-layout-link-fixture.mjs')) || '';
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

// —— /api/handlers 的形态：版式项带随金额栏与原因句（无这项的第三方项不带栏） ——

const HANDLER = 'org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler';

const OPTIONS = [
  {key: 'cards', label: '数据卡片', description: '弹幕、流水、点赞等概览卡片', type: 'BOOLEAN', defaultValue: true,
    revenueVisibility: 'RESTYLED_WHEN_HIDDEN', revenueNote: '隐藏金额的会话里流水卡改写付费人数，不带金额'},
  {key: 'interaction_curve', label: '互动曲线', description: '弹幕、流水等随时间的变化曲线', type: 'BOOLEAN', defaultValue: true,
    revenueVisibility: 'RESTYLED_WHEN_HIDDEN', revenueNote: '隐藏金额的会话里曲线照画、不标金额峰值'},
  {key: 'guard_list', label: '本场开通大航海名单', description: '本场开通大航海的观众', type: 'BOOLEAN', defaultValue: true,
    revenueVisibility: 'ONLY_WHEN_HIDDEN', revenueNote: '显示金额的会话里不画这张名单：大航海全名单上的「本场」小标承担了同一件事'},
  {key: 'danmu_cloud', label: '弹幕词云', description: '本场弹幕的词云图', type: 'BOOLEAN', defaultValue: true},
  {key: 'gift_list', label: '礼物列表', description: '本场收到的礼物与大航海，按种类列出个数', type: 'BOOLEAN', defaultValue: true,
    revenueVisibility: 'ONLY_WHEN_SHOWN', revenueNote: '隐藏金额的会话里不画这一段：收到的礼物清单也是消费明细，与流水排行同一个判断'},
  {key: 'gift_ranking', label: '流水排行', description: '每人本场礼物、醒目留言与上舰的金额合计，展示前几名，0 为不展示',
    type: 'INTEGER', defaultValue: 5, min: 0, max: 20,
    revenueVisibility: 'ONLY_WHEN_SHOWN', revenueNote: '隐藏金额的会话里整榜不出：每一行都是某人的消费合计，去掉数字也仍然在排消费'},
  {key: 'super_chat_ranking', label: '醒目留言名单', description: '发过醒目留言的观众，每人一行带原文，展示前几人，0 为不展示',
    type: 'INTEGER', defaultValue: 10, min: 0, max: 20,
    revenueVisibility: 'ONLY_WHEN_SHOWN', revenueNote: '隐藏金额的会话里整榜不出：每一行都是某人的消费合计，去掉数字也仍然在排消费'},
  {key: 'box_ranking', label: '盲盒榜', description: '每人开了几个、盈亏多少，展示前几名，0 为不展示',
    type: 'INTEGER', defaultValue: 0, min: 0, max: 20,
    revenueVisibility: 'RESTYLED_WHEN_HIDDEN', revenueNote: '隐藏金额的会话里只写个数，不写盈亏'},
];

const TARGET = {platform: 'qq-onebot', type: 1, num: 12345};

const SESSION = {
  platform: 'qq-onebot', num: 12345, type: 1,
  disabled: [], menuHidden: [], menuNotes: {},
  revenueVisible: false, revenueExplicit: true,
  streamers: ['甲'],
};

// —— 载入产品码 ——

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  // 核心那份 store 走插件页同一相对名（alias 钩子指回去），不另写一条模块路径：
  // 写死路径的话，目录一改这份夹具安静失灵，而它与产品码拿的就不再是同一个 store
  store = (await import('./store.js')).store;
} catch (err) {
  failures.push('载入 push.js 就抛了：' + err.message);
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
// 开关的 input 住在 label 里，数字框直接住在行里：向上找到 rep-row 那一层再找原因行
const rowOf = label => {
  let node = layoutInput(label);
  while (node && !String(node.className || '').includes('rep-row')) node = node.parentNode;
  return node;
};
const reasonOf = label => {
  const row = rowOf(label);
  const why = row ? allNodes(row).filter(node => node.tagName === 'p'
    && String(node.className).includes('rep-why')) : [];
  return why.length ? why[0].textContent : null;
};
const captionText = () => {
  const node = allNodes(page()).find(item => item.tagName === 'p'
    && item.textContent.startsWith('按本群金额可见画'));
  return node ? node.textContent : null;
};
const revenueSwitch = () => allNodes(page())
  .find(node => node.tagName === 'input' && node.getAttribute('aria-label') === '直播收益等金额') || null;
const lastPreviewBody = () => {
  const posts = mock.requests.filter(item => item.path.endsWith('/config/api/report/preview'));
  return posts.length ? JSON.parse(posts[posts.length - 1].body) : null;
};

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

function flip(input) {
  input.checked = !input.checked;
  input.dispatchEvent({type: 'change', target: input});
}

async function paintPage() {
  mock.requests.length = 0;
  if (push) {
    push.setPushEntries([{
      platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
      targets: [{
        platform: TARGET.platform, type: 1, num: TARGET.num, enabled: true,
        messages: [{handler: HANDLER, params: {gift_ranking: 10}}],
      }],
    }]);
    push.setRuntimeState({
      sessions: [SESSION], commands: [], subscriptions: [], incomplete: [],
    });
    push.showPush('1', String(TARGET.num));
    push.renderStreamers();
  }
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
  await paintPage();
}

await ask('① 会话隐藏金额时：礼物列表、流水排行、醒目留言名单灰掉并带原因，大航海名单可点，变样的三项只加说明', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const locked = ['礼物列表', '流水排行', '醒目留言名单'];
  for (const label of locked) {
    const input = layoutInput(label);
    if (!input) throw new Error('版式区没有「' + label + '」这一项');
    if (input.disabled !== true) throw new Error('「' + label + '」在隐藏金额的会话里仍可点');
    const why = reasonOf(label);
    if (!why) throw new Error('「' + label + '」灰掉却没写原因');
    if (!why.includes('本群设置') || !why.includes('金额可见')) {
      throw new Error('「' + label + '」的原因没指向「本群设置」的金额开关：' + why);
    }
  }
  const note = reasonOf('礼物列表');
  const giftRow = rowOf('礼物列表');
  const rowText = allNodes(giftRow).map(node => node.textContent).join('');
  if (!rowText.includes('与流水排行同一个判断')) {
    throw new Error('灰掉的原因没有照服务端给的那句说：' + note);
  }
  const open = layoutInput('本场开通大航海名单');
  if (!open || open.disabled === true) throw new Error('隐藏金额的会话里「本场开通大航海名单」该可点却灰着');
  for (const label of ['数据卡片', '互动曲线', '盲盒榜']) {
    const input = layoutInput(label);
    if (!input || input.disabled === true) throw new Error('「' + label + '」只是变样，不该灰');
  }
  const curveRow = allNodes(rowOf('互动曲线')).map(node => node.textContent).join('');
  if (!curveRow.includes('不标金额峰值')) throw new Error('「互动曲线」没带变样的那句说明');
  const cloud = layoutInput('弹幕词云');
  if (!cloud || cloud.disabled === true) throw new Error('「弹幕词云」与金额无关，不该灰');
  if (reasonOf('弹幕词云')) throw new Error('「弹幕词云」不该带原因行');
});

await ask('② 草稿拨到显示金额（还没保存）：三项当场可点、大航海名单转灰，预览与图下说明都按草稿走', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const toggle = revenueSwitch();
  if (!toggle) throw new Error('本群设置里没找到金额开关');
  if (toggle.checked !== false) throw new Error('金额开关初值该是关（会话设为隐藏金额）');
  flip(toggle);
  await settle();

  for (const label of ['礼物列表', '流水排行', '醒目留言名单']) {
    const input = layoutInput(label);
    if (!input || input.disabled !== false) throw new Error('草稿拨到显示后「' + label + '」仍灰着');
  }
  const guard = layoutInput('本场开通大航海名单');
  if (!guard || guard.disabled !== true) throw new Error('草稿拨到显示后「本场开通大航海名单」该转灰');
  const guardWhy = reasonOf('本场开通大航海名单');
  if (!guardWhy || !guardWhy.includes('本群设置') || !guardWhy.includes('金额可见')) {
    throw new Error('转灰的大航海名单没写指向金额开关的原因：' + guardWhy);
  }
  const body = lastPreviewBody();
  if (!body || body.revenueVisible !== true) {
    throw new Error('预览请求没带上草稿的金额设定：' + JSON.stringify(body));
  }
  same(captionText(), '按本群金额可见画：显示', '图下说明');
});

await ask('③ 草稿拨回隐藏（不保存）：灰的亮回来，预览与说明也跟着回', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const toggle = revenueSwitch();
  flip(toggle);
  await settle();

  for (const label of ['礼物列表', '流水排行', '醒目留言名单']) {
    const input = layoutInput(label);
    if (!input || input.disabled !== true) throw new Error('拨回隐藏后「' + label + '」没灰回去');
  }
  const guard = layoutInput('本场开通大航海名单');
  if (!guard || guard.disabled === true) throw new Error('拨回隐藏后「本场开通大航海名单」该亮回来');
  const body = lastPreviewBody();
  if (!body || body.revenueVisible !== false) {
    throw new Error('拨回后预览请求没带隐藏的草稿值：' + JSON.stringify(body));
  }
  same(captionText(), '按本群金额可见画：隐藏', '图下说明');
});

await ask('④ 灰掉的那些格：存着的值不动，「自定义」的判法也不变', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const message = push.pushEntries()[0].targets[0].messages[0];
  same(message.params.gift_ranking, 10, '灰着又亮过的流水排行存值');
  const stateLine = allNodes(page()).find(node => node.tagName === 'span'
    && String(node.innerHTML).includes('本通道用的是') && String(node.innerHTML).includes('版式'));
  if (!stateLine || !String(stateLine.innerHTML).includes('自定义版式')) {
    throw new Error('版式状态行该仍是「自定义版式」：' + (stateLine ? stateLine.innerHTML : '没找到'));
  }
});

await ask('⑤ 金额开关拨过两回，「本群设置」那一段不该越画越长', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  const rows = findAll(page(), '.crow').length;
  same(rows, 3, '本群设置的行数（金额可见、命令、提醒订阅三行折叠项）');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
