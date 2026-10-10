/**
 * 同一个号既是群又是好友时：点哪一个，右栏开的就是哪一个
 *
 * 用户那头的故障：一位主播配了群 123456，又配了好友 123456。点左树上好友那一行，
 * 右栏开的是群那一个；窄屏下拉里选好友，开的也是群。左树高亮、一览表的「打开」、
 * 主播页通道行的「打开」同样只按号认，指向谁全看谁排在前面。
 * 根子在通道地址只带着号：加通道时的去重按「平台|类型|号」三样认，
 * 而跳转地址、右区查找、左树高亮都只拿号当身份——同一号的群与好友在这些地方是同一行。
 *
 * 本尺真跑：假 DOM 与假 fetch 之外，左树点击、地址栏路由、右区重画走的都是产品码那一份。
 * 只 includes 一段字样是不行的：地址里多写一截类型照样能被按号那段查重。
 * 另量旧地址 #/push/<uid>/<号> 照样能打开（按号取第一个），书签与手册里的例子不受影响。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 量程：不带参数即源码树里那一份；带一份 config-ui-pages 拷贝的绝对路径即量那份拷贝
 * （量一份改过的拷贝时用：拷贝里动了手脚的产品码）。由 PushChannelLinkTest 拉起。
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
    const frame = stack.split('\n').find(line => line.includes('push-channel-link-fixture.mjs')) || '';
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

  get textContent() {
    return this._text;
  }

  set textContent(value) {
    this._text = String(value ?? '');
  }

  get innerHTML() {
    return this._html;
  }

  set innerHTML(html) {
    this._html = String(html ?? '');
    this.children = [];
    // 把 <input …> 与 <a href=…> 立成真节点：开关要拨、链接的地址要看
    const re = /<(input|a)\b([^>]*)>/gi;
    let match;
    while ((match = re.exec(this._html))) {
      const tag = match[1].toLowerCase();
      const attrs = match[2];
      const node = new MiniNode(tag);
      const typeMatch = /type\s*=\s*"([^"]*)"/i.exec(attrs);
      if (typeMatch) node.type = typeMatch[1];
      if (/\bchecked\b/i.test(attrs)) node.checked = true;
      if (/\bdisabled\b/i.test(attrs)) node.disabled = true;
      const hrefMatch = /href\s*=\s*"([^"]*)"/i.exec(attrs);
      if (hrefMatch) node.attributes.href = hrefMatch[1];
      const ariaMatch = /aria-label\s*=\s*"([^"]*)"/i.exec(attrs);
      if (ariaMatch) node.attributes['aria-label'] = ariaMatch[1];
      this.appendChild(node);
    }
  }

  get href() {
    return this.attributes.href || '';
  }

  set href(value) {
    this.attributes.href = String(value);
  }

  appendChild(child) {
    if (!child) return child;
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

  querySelector(selector) {
    return findAll(this, selector)[0] || null;
  }

  querySelectorAll(selector) {
    return findAll(this, selector);
  }

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

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

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

  click() {
    this.dispatchEvent({type: 'click', target: this, stopPropagation() {}});
  }
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
  return String(node.className || '').split(/\s+/).filter(Boolean);
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
      toggle: (name, on) => {
        if (on) htmlClasses.add(name);
        else htmlClasses.delete(name);
      },
      contains: name => htmlClasses.has(name),
    },
  },
};

globalThis.location = {hash: '', href: '', replace(v) { this.hash = String(v); }, reload() {}};

// —— 假接口 ——

const mock = {requests: []};

/** 机器人已经加入的群、已经加的好友：同号的群与好友各一条 */
const GROUP_NUM = 123456;
const FRIEND_NUM = 123456;
const PLATFORM = 'qq-onebot';

function ok(payload) {
  return Promise.resolve({status: 200, json: () => Promise.resolve(payload)});
}

globalThis.fetch = (path, opt) => {
  const method = ((opt || {}).method || 'GET').toUpperCase();
  mock.requests.push({path: String(path), method, body: (opt || {}).body});
  const url = String(path);

  if (method === 'GET') {
    if (url.endsWith('/datasource')) return ok({content: datasourceBody()});
    if (url.endsWith('/state')) return ok({
      commands: [], sessions: sessionsList(), subscriptions: [], incomplete: [], totalDataAvailable: true,
    });
    if (url.includes('/push-history')) return ok({records: []});
    if (url.includes('/at-all/quota')) return ok({bots: [], sessions: []});
    if (url.includes('/bot/targets?type=group')) {
      return ok({items: [{sender: PLATFORM, num: GROUP_NUM, name: '技术群', memberCount: 30, admin: true}]});
    }
    if (url.includes('/bot/targets?type=friend')) {
      return ok({items: [{sender: PLATFORM, num: FRIEND_NUM, nickname: '小明', remark: ''}]});
    }
    return ok({});
  }
  return ok({success: true, message: '已保存'});
};

/**
 * 会话行按「平台:号」合一行，同号的群与好友共用——这正是它分不出谁是谁的原因。
 * 这里不给 type 一栏，类型那一枚药丸才按通道自己的类型码退成「群聊」／「私聊」。
 */
function sessionsList() {
  return [{
    platform: PLATFORM, num: GROUP_NUM,
    disabled: [], menuHidden: [], menuNotes: {},
    revenueVisible: false, revenueExplicit: true,
    quietMode: 'follow', quietStart: null, quietEnd: null, quietActive: false,
    streamers: ['甲'],
  }];
}

function datasourceBody() {
  return JSON.stringify([{
    platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
    targets: [
      {platform: PLATFORM, type: 1, num: GROUP_NUM, enabled: true,
        messages: [{handler: 'x.LiveOn'}]},
      {platform: PLATFORM, type: 0, num: FRIEND_NUM, enabled: true,
        messages: [{handler: 'x.LiveOff'}]},
    ],
  }]);
}

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
  store.handlerList = [
    {className: 'x.LiveOn', aliases: [], displayName: '开播通知', description: '开播时发',
      platform: '', placeholders: [], attachments: [], defaultParams: {}, factoryParams: {}, options: []},
    {className: 'x.LiveOff', aliases: [], displayName: '下播通知', description: '下播时发',
      platform: '', placeholders: [], attachments: [], defaultParams: {}, factoryParams: {}, options: []},
  ];
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

function rightPane() {
  return allNodes(idNode('push-right'));
}

/** 右区标题那一行：名字与它旁边那一枚类型药丸 */
function headLine() {
  return rightPane().find(node => classesOf(node).includes('chead')) || null;
}

function headName() {
  const line = headLine();
  const name = line && line.children.find(child => classesOf(child).includes('ch-nm'));
  return name ? name.textContent : '';
}

function headTypePill() {
  const line = headLine();
  if (!line) return '';
  const pill = line.children.find(child => classesOf(child).includes('nv-pill'));
  return pill ? pill.textContent : '';
}

function headIdLine() {
  const line = headLine();
  const id = line && line.children.find(child => classesOf(child).includes('ch-id'));
  return id ? id.textContent : '';
}

/** 左树上带「on」的那一行通道（或主播） */
function highlightedTreeNode() {
  return allNodes(idNode('push-tree')).find(node => classesOf(node).includes('on')) || null;
}

function treeRowName(node) {
  const name = node && node.querySelector('.tn-nm');
  return name ? name.textContent : '';
}

function navValue() {
  return String(idNode('push-nav').value || '');
}

function linksUnder(host) {
  return allNodes(host).filter(node => node.tagName === 'a').map(node => String(node.href || ''));
}

async function settle() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

/** 打开一个地址：先看地址栏，再走一遍本页自己的路由（与宿主刷新、后退同一条路） */
async function openHash(hash) {
  globalThis.location.hash = hash;
  push.refresh();
  await settle();
}

/** 点左树上名字叫 name 的那一行通道，再走一遍路由 */
async function clickTreeChannel(name) {
  push.showPush('1', '');
  await settle();
  const row = allNodes(idNode('push-tree')).find(node =>
    classesOf(node).includes('tchan')
    && treeRowName(node) === name);
  if (!row) throw new Error('左树上没找到「' + name + '」那一行');
  row.click();
  await settle();
  // 点击只改地址栏；宿主监听 hash 变化后叫 refresh——这里照那条路走一遍
  push.refresh();
  await settle();
}

if (push) {
  // 与宿主进这一页同一件事：配置由 reload 取，名单与运行状态由 loadPushPage 取
  await push.reload();
  await push.loadPushPage();
  await settle();
}

await ask('① 同号的群与好友：点左树上好友那一行，右栏开的是好友那个，高亮与下拉都认它', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await clickTreeChannel('小明');
  const on = highlightedTreeNode();
  const onName = treeRowName(on);
  same({
    '地址里带上了类型与平台': String(globalThis.location.hash).includes('|0|' + FRIEND_NUM),
    '右栏标题是好友那一个': headName(),
    '标题旁的类型是私聊': headTypePill(),
    '号那一行写着用户号': headIdLine().includes('用户号'),
    '左树高亮在好友那一行': onName,
    '窄屏下拉也停在好友那个选项': navValue().includes('|0|' + FRIEND_NUM),
    '高亮行是通道行': on ? classesOf(on).includes('tchan') : false,
  }, {
    '地址里带上了类型与平台': true,
    '右栏标题是好友那一个': '小明',
    '标题旁的类型是私聊': '私聊',
    '号那一行写着用户号': true,
    '左树高亮在好友那一行': '小明',
    '窄屏下拉也停在好友那个选项': true,
    '高亮行是通道行': true,
  }, '点好友那一行');
});

await ask('② 一览表与通道行的「打开」都指向好友那一个，地址里带着类型与平台', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  // 通道一览
  await openHash('#/push');
  const indexLinks = linksUnder(idNode('push-right')).filter(href => href.startsWith('#/push/'));
  const friendInIndex = indexLinks.filter(href => href.includes('|0|' + FRIEND_NUM));
  const bareIndex = indexLinks.filter(href => /#\/push\/1\/123456$/.test(href));

  // 主播级的通道行
  await openHash('#/push/1');
  const rowLinks = linksUnder(idNode('push-right')).filter(href => href.startsWith('#/push/1/'));
  const friendInRows = rowLinks.filter(href => href.includes('|0|' + FRIEND_NUM));
  const bareRows = rowLinks.filter(href => /#\/push\/1\/123456$/.test(href));

  same({
    '一览表里指向好友的条数': friendInIndex.length,
    '一览表里只写号的条数': bareIndex.length,
    '通道行里指向好友的条数': friendInRows.length,
    '通道行里只写号的条数': bareRows.length,
    '一览表里也有指向群的': indexLinks.filter(href => href.includes('|1|' + GROUP_NUM)).length,
    '通道行里也有指向群的': rowLinks.filter(href => href.includes('|1|' + GROUP_NUM)).length,
  }, {
    // 一览表一行一个「打开」；通道行上标题与「打开」各一个，两处都得认对人
    '一览表里指向好友的条数': 1,
    '一览表里只写号的条数': 0,
    '通道行里指向好友的条数': 2,
    '通道行里只写号的条数': 0,
    '一览表里也有指向群的': 1,
    '通道行里也有指向群的': 2,
  }, '打开链接');
});

await ask('③ 点左树上群那一行：右栏开的是群那个，两行互不串', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await clickTreeChannel('技术群');
  const on = highlightedTreeNode();
  const onName = treeRowName(on);
  same({
    '地址里带上了类型与平台': String(globalThis.location.hash).includes('|1|' + GROUP_NUM),
    '右栏标题是群那一个': headName(),
    '标题旁的类型是群聊': headTypePill(),
    '号那一行写着群号': headIdLine().includes('群号'),
    '左树高亮在群那一行': onName,
  }, {
    '地址里带上了类型与平台': true,
    '右栏标题是群那一个': '技术群',
    '标题旁的类型是群聊': '群聊',
    '号那一行写着群号': true,
    '左树高亮在群那一行': '技术群',
  }, '点群那一行');
});

await ask('④ 旧地址只带号的照样打开（按号取第一个），书签与手册里的例子不受影响', async () => {
  if (!push) throw new Error('push.js 没载入，无从量起');
  await openHash('#/push/1/' + FRIEND_NUM);
  const on = highlightedTreeNode();
  const onName = treeRowName(on);
  same({
    '右栏有开': headName(),
    '左树有高亮': onName,
    '下拉有停': navValue() !== '',
    '号那一行写着群号': headIdLine().includes('群号'),
  }, {
    '右栏有开': '技术群',
    '左树有高亮': '技术群',
    '下拉有停': true,
    '号那一行写着群号': true,
  }, '旧地址');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
