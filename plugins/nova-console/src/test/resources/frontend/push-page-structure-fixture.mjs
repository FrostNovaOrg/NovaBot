import {datasourceRead} from './datasource-read.mjs';
/**
 * 推送页这一批改完之后，三处不能跟着没了
 *
 * 用户那头的毛病：窄屏上「本群最近推送」那张表把整页撑宽；页顶、通道一览、通道页头
 * 那三段长说明与第 4 段里那句重复，删的时候容易把讲清「共用」的那句一起删掉；左树上
 * 群／好友那一行从前挂着「N 类关着」「N 条命令被关」两枚小标，这一批删掉了，
 * 删的时候别把主播那一行的「已停用」「只采集，不推送」一起抹了，也别把群行那两枚又挂回去。
 *
 * 这里量的是三件：表外面包着只在卡片里横向滚的那一层（真宽度留到测试机上量）；
 * 第 4 段那句共用说明仍在、有几位主播时列出名字；群／好友那一行不挂那两枚小标，
 * 主播那一行的两颗照在。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，整条通道页与左树走的都是产品码那一份
 * （push.js 的 showPush／renderStreamers）。只查源码里有没有某段字是不行的——
 * 字在、画不出来，照样绿。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 PushPageStructureFixtureTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
    const frame = stack.split('\n').find(line => line.includes('push-page-structure-fixture.mjs')) || '';
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
    this.isConnected = false;
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
    this.open = false;
    this.dataset = {};
  }
  // 和真 DOM 一样，自己那点字连上后辈的字一起算：小标那两颗药丸的字挂在子节点上
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(v) {
    this._text = String(v ?? '');
    for (const child of this.children) detach(child);
    this.children = [];
  }
  get innerHTML() { return this._html; }
  set innerHTML(html) {
    this._html = String(html ?? '');
    for (const child of this.children) detach(child);
    this.children = [];
    // 只把 <input …> 立成真节点：本页会往 label 里塞一个复选框再取回来
    const re = /<input\b([^>]*)>/gi;
    let match;
    while ((match = re.exec(this._html))) {
      const input = new MiniNode('input');
      const attrs = match[1];
      const typeMatch = /type\s*=\s*"([^"]*)"/i.exec(attrs);
      if (typeMatch) input.type = typeMatch[1];
      if (/\bchecked\b/i.test(attrs)) input.checked = true;
      const ariaMatch = /aria-label\s*=\s*"([^"]*)"/i.exec(attrs);
      if (ariaMatch) input.attributes['aria-label'] = ariaMatch[1];
      this.appendChild(input);
    }
  }
  appendChild(child) {
    child.parentNode = this;
    child.isConnected = true;
    this.children.push(child);
    return child;
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
  removeAttribute(name) {
    delete this.attributes[name];
    if (name === 'id') this.id = '';
    if (name === 'class') this.className = '';
  }
  contains(other) {
    for (let node = other; node; node = node.parentNode) {
      if (node === this) return true;
    }
    return false;
  }
  getBoundingClientRect() {
    return {top: 100, bottom: 122, left: 40, right: 62, width: 22, height: 22};
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
  focus() { globalThis.document.activeElement = this; }
  click() { this.dispatchEvent({type: 'click', target: this, stopPropagation() {}}); }
}

function detach(node) {
  node.parentNode = null;
  node.isConnected = false;
  for (const child of node.children || []) detach(child);
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
  return String((node && node.className) || '').split(/\s+/).filter(Boolean);
}

const byId = new Map();

function idNode(id) {
  if (!byId.has(id)) {
    const node = new MiniNode('div');
    node.id = id;
    node.isConnected = true;
    byId.set(id, node);
  }
  return byId.get(id);
}

const htmlClasses = new Set();
const docListeners = {};

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
  addEventListener(type, fn) { (docListeners[type] = docListeners[type] || []).push(fn); },
  removeEventListener() {},
  body: idNode('body'),
  activeElement: null,
  documentElement: {
    clientWidth: 390,
    clientHeight: 780,
    classList: {
      toggle: (name, on) => { if (on) htmlClasses.add(name); else htmlClasses.delete(name); },
      contains: name => htmlClasses.has(name),
    },
  },
};

globalThis.location = {hash: '', href: '', replace(v) { this.hash = String(v); }, reload() {}};

// —— 假接口 ——

const PLATFORM = 'qq-onebot';
const GROUP_NUM = 123456;

const mock = {requests: []};

function ok(payload) {
  return Promise.resolve({status: 200, json: () => Promise.resolve(payload)});
}

const SESSION = {
  platform: PLATFORM, num: GROUP_NUM, type: '群', typeCode: 1,
  disabled: ['直播报告'], menuHidden: [], menuNotes: {},
  revenueVisible: false, revenueExplicit: true,
  quietMode: 'follow', quietStart: null, quietEnd: null, quietActive: false,
  streamers: ['甲', '乙'],
};

/** 这个群收到的推送记录：表要靠它们才画得出来 */
const RECORDS = [{
  platform: PLATFORM, target: '群 ' + GROUP_NUM,
  at: '21:00', summary: '开播通知', success: true,
}, {
  platform: PLATFORM, target: '群 ' + GROUP_NUM,
  at: '21:30', summary: '下播通知', success: false, reason: '机器人不在线',
}];

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  mock.requests.push({path: String(path), method, body: (opt || {}).body});
  const url = String(path);

  if (method === 'GET') {
    if (url.includes('/datasource')) return ok(datasourceRead(url, JSON.stringify([{
      platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
      targets: [{
        platform: PLATFORM, type: 1, num: GROUP_NUM, enabled: true,
        messages: [{handler: 'x.LiveOn'}],
      }],
    }, {
      platform: 'bilibili', uid: 2, _uname: '乙', enabled: true,
      targets: [{
        platform: PLATFORM, type: 1, num: GROUP_NUM, enabled: true,
        messages: [{handler: 'x.LiveOn'}],
      }],
    }, {
      // 两位不推到这个群的主播，只用来把左树主播那一行的两颗小标画出来
      platform: 'bilibili', uid: 3, _uname: '丙', enabled: false,
      targets: [{platform: PLATFORM, type: 1, num: 654321, enabled: true, messages: []}],
    }, {
      platform: 'bilibili', uid: 4, _uname: '丁', enabled: true, targets: [],
    }])));
    if (url.endsWith('/state')) return ok({
      commands: [], sessions: [SESSION], subscriptions: [], incomplete: [],
      totalDataAvailable: false,
    });
    if (url.includes('/push-history')) return ok({records: RECORDS});
    if (url.includes('/at-all/quota')) return ok({bots: [], sessions: []});
    if (url.includes('/bot/targets?type=group')) {
      return ok({items: [{sender: PLATFORM, num: GROUP_NUM, name: '技术群', memberCount: 30, admin: true}, {sender: PLATFORM, num: 987654, name: '新群'}]});
    }
    if (url.includes('/bot/targets?type=friend')) return ok({items: [{sender: PLATFORM, num: GROUP_NUM, name: '好友'}]});
    return ok({});
  }
  return ok({success: true, message: '已保存'});
};

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
  store.handlerList = [{
    className: 'x.LiveOn', aliases: [], displayName: '开播通知', description: '开播时发',
    platform: '', placeholders: [], attachments: [], defaultParams: {}, factoryParams: {}, options: [],
  }, {
    className: 'x.LiveOff', aliases: [], displayName: '下播通知', description: '下播时发',
    platform: '', placeholders: [], attachments: [], defaultParams: {}, factoryParams: {}, options: [],
  }];
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

const rightPane = () => allNodes(idNode('push-right'));

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

if (push) {
  await push.reload();
  await push.loadPushPage();
  await settle();
  // 进甲 → 技术群那条通道：页头、四段与左树一起画出来
  push.showPush('1', PLATFORM + '|1|' + GROUP_NUM);
  await settle();
}

await ask('① 「本群最近推送」那张表外面包着只在卡片里横着滚的那一层，不把整页顶宽', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const table = rightPane().find(node => classesOf(node).includes('ptable') && node.tagName === 'table');
  if (!table) throw new Error('通道页上没有画出最近推送那张表（推送记录没进来？）');
  const wrap = table.parentNode;
  same({
    '表外面包着滚动层': !!wrap && classesOf(wrap).includes('push-history-scroll'),
    '滚动层贴着卡片放': !!wrap && !!wrap.parentNode && classesOf(wrap.parentNode).includes('nv-card'),
  }, {
    '表外面包着滚动层': true,
    '滚动层贴着卡片放': true,
  }, '最近推送表的滚动层');
});

await ask('② 第 4 段说明只作用于当前主播通道，不再宣称修改全部主播', async () => {
  if (!push) throw new Error('push.js 未载入');
  const text = rightPane().map(node => node.textContent + node.innerHTML).join(' ');
  if (!text.includes('通道设置')) throw new Error('缺少通道设置标题');
  if (!text.includes('只作用于这位主播在本群的推送和命令')) throw new Error('缺少通道范围说明');
  if (text.includes('在此修改将同时作用于全部主播')) throw new Error('仍显示旧的共用说明');
});

await ask('③ 群／好友那一行不再挂「N 类关着」「N 条命令被关」，主播那一行的两颗照在', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  const tree = idNode('push-tree');
  const all = allNodes(tree);
  const markTexts = all
    .filter(node => classesOf(node).includes('tn-marks'))
    .map(node => node.textContent);
  const chanRows = all.filter(node => classesOf(node).includes('tchan'));
  const chanWithMarks = chanRows
    .filter(row => allNodes(row).some(node => classesOf(node).includes('tn-marks')));
  const pills = markTexts.join('｜');
  same({
    '群／好友那一行还挂着「N 类关着」': markTexts.some(text => text.includes('类关着')),
    '群／好友那一行还挂着「N 条命令被关」': markTexts.some(text => text.includes('条命令被关')),
    '群／好友那一行剩下空的小标层': chanWithMarks.length,
    '「已停用」那颗小标': pills.includes('已停用'),
    '「仅采集」那颗小标': pills.includes('仅采集'),
  }, {
    '群／好友那一行还挂着「N 类关着」': false,
    '群／好友那一行还挂着「N 条命令被关」': false,
    '群／好友那一行剩下空的小标层': 0,
    '「已停用」那颗小标': true,
    '「仅采集」那颗小标': true,
  }, '左树小标');
});

// 故障：添加通道时看不出群已经在推谁，也不知道通道设置只属于这条通道。
await ask('添加通道写明通道设置各条一份并列出其他主播', async () => {
  await push.showPush('3', '');
  push.renderStreamers();
  const add = rightPane().find(node => node.textContent === '＋ 添加通道');
  if (!add) throw new Error('没有添加通道按钮');
  add.click();
  const rows = allNodes(idNode('push-drawer-body')).filter(node => classesOf(node).includes('dwrow'));
  same(rows[0].innerHTML.includes(' · 正在推送 甲、乙'), true, '已有推送的群列出主播');
  same(rows[1].innerHTML.includes('正在推送'), false, '没人推的群不写主播');
  same(rows[2].innerHTML.includes('正在推送'), false, '好友不写主播');
  same(idNode('push-drawer-lead').textContent.includes('通道设置（金额可见、静音时段、命令开关）每条通道各一份，不影响同群的其他主播。'),
    true, '添加前的通道设置说明');
  push.closeDrawer();
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
