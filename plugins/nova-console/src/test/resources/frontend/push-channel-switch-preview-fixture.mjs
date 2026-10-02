/**
 * 切通道之后：旧编辑器的金额草稿监听要摘掉，旧预览晚回来不许撤销当前预览图
 *
 * 用户那头的故障：在推送页从报告通道切到别的通道后再拨「金额可见」，旧通道的编辑器
 * 还挂在草稿监听的槽位上（切走时没人注销它），后台会白发一次旧通道的预览请求——图
 * 与说明画的是它自己那份已离开页面的节点，用户看不见，但请求真的发了；碰巧旧请求
 * 回来得晚，共用的那份预览图地址被它撤销，当前通道的新图还没载完时，预览那一块就白。
 *
 * 本尺<b>真跑</b>：假 DOM 与假 fetch 之外，整条通道页用的都是产品码那一份——
 * push.js 的换屏与版式段、template.js 的版式编辑器、sessions.js 的金额开关、
 * session-draft.js 的草稿，一路都是真码。只看源码里有没有那句注销是不行的：
 * 注销写了、挂错时机，照样绿。预览回应的先后由本尺手动放行，专门量「晚到」
 * 那一档——真机上凑齐「旧请求恰好比新请求晚回来」那个窗口，一次要试很多回。
 *
 * 量程：不带参数即源码树里那一份产品码；带一份 config-ui-pages 拷贝的绝对路径
 * （量一份改过的拷贝时用：拷贝里动了手脚的产品码）即量那份拷贝；经 mvn 跑时由对应的 JUnit 壳把
 * -Dnova.push.pages 透传成这个参数，平常不设该属性。
 *
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 PushChannelSwitchPreviewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
    const frame = stack.split('\n').find(line => line.includes('push-channel-switch-preview-fixture.mjs')) || '';
    const where = frame.replace(/^\s*at\s+/, '').trim();
    failures.push(name + '：' + (err && err.message ? err.message : String(err)) + (where ? ' [' + where + ']' : ''));
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

// 预览图在浏览器里经 createObjectURL 变成 img 的 src，node 上没有这一对。
// 撤销要记账：本尺量的正是「晚到的回应不许撤销当前正在用的那个地址」
let blobSeq = 0;
const revokedUrls = [];
globalThis.URL.createObjectURL = () => 'blob:preview-' + (++blobSeq);
globalThis.URL.revokeObjectURL = url => revokedUrls.push(String(url));

// —— 假接口 ——

const mock = {requests: []};

// 预览回应两档：自动档当场回（量「切走后还发不发」）；手动档攥在手里按测试要的
// 先后放行（量「晚到的回应撤销了谁」）——真机上等两个请求自然乱序，窗口凑不齐
let previewManual = false;
const previewPending = [];

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
    if (previewManual) {
      return new Promise(resolve => previewPending.push(resolve))
        .then(() => ({ok: true, blob: () => Promise.resolve('preview-bytes')}));
    }
    return Promise.resolve({ok: true, blob: () => Promise.resolve('preview-bytes')});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({success: true, message: '已保存'})});
};

// —— /api/handlers 的形态：带版式项的那类通知（只留两项，够立起版式区即可） ——

const HANDLER = 'org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler';

const OPTIONS = [
  {key: 'cards', label: '数据卡片', description: '弹幕、流水、点赞等概览卡片', type: 'BOOLEAN', defaultValue: true,
    revenueVisibility: 'RESTYLED_WHEN_HIDDEN', revenueNote: '隐藏金额的会话里流水卡改写付费人数，不带金额'},
  {key: 'gift_list', label: '礼物列表', description: '本场收到的礼物与大航海，按种类列出个数', type: 'BOOLEAN', defaultValue: true,
    revenueVisibility: 'ONLY_WHEN_SHOWN', revenueNote: '隐藏金额的会话里不画这一段：收到的礼物清单也是消费明细，与流水排行同一个判断'},
];

// 三个通道：两个开着带版式的报告（「晚到的旧回应」用它们的先后），一个什么都不推（「切到不推的通道」切过去）
const REPORT_A = 11111;
const REPORT_B = 22222;
const PLAIN_C = 33333;

function targetOf(num, withReport) {
  return {
    platform: 'qq-onebot', type: 1, num, enabled: true,
    messages: withReport ? [{handler: HANDLER, params: {}}] : [],
  };
}

function sessionOf(num) {
  return {
    platform: 'qq-onebot', num, type: 1,
    disabled: [], menuHidden: [], menuNotes: {},
    revenueVisible: false, revenueExplicit: true,
    streamers: ['甲'],
  };
}

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
const revenueSwitch = () => allNodes(page())
  .find(node => node.tagName === 'input' && node.getAttribute('aria-label') === '直播收益等金额') || null;
const previewShot = () => allNodes(page())
  .find(node => node.tagName === 'img' && String(node.className).includes('rep-shot')) || null;
const previewCount = () => mock.requests
  .filter(item => item.path.endsWith('/config/api/report/preview')).length;
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

async function paintChannel(num) {
  push.showPush('1', String(num));
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
  push.setPushEntries([{
    platform: 'bilibili', uid: 1, _uname: '甲', enabled: true,
    targets: [targetOf(REPORT_A, true), targetOf(REPORT_B, true), targetOf(PLAIN_C, false)],
  }]);
  push.setRuntimeState({
    sessions: [sessionOf(REPORT_A), sessionOf(REPORT_B), sessionOf(PLAIN_C)],
    commands: [], subscriptions: [], incomplete: [],
  });
  await paintChannel(REPORT_A);
}

await ask('① 切到不推的通道·从报告通道切到没有版式区的通道后拨金额：旧通道的预览请求一次都不该发', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  // 前置：报告通道那一屏真有版式区、真画过一张预览——不然下面「没多发」可能量了个空
  if (!layoutInput('礼物列表')) throw new Error('报告通道该有版式区（「礼物列表」那一项没找到）');
  if (previewCount() !== 1) throw new Error('报告通道初画该恰好发一次预览请求，实测 ' + previewCount());
  await paintChannel(PLAIN_C);
  if (!revenueSwitch()) throw new Error('没有版式区的通道里也该有「本群设置 · 金额可见」那一行');
  const before = previewCount();
  flip(revenueSwitch());
  await settle();
  const after = previewCount();
  if (after !== before) {
    throw new Error('切走后拨金额，旧通道的预览请求又发了 ' + (after - before)
      + ' 次——旧编辑器的草稿监听没摘掉，白打了一次旧通道的预览');
  }
  // 拨了要真拨上：开关按草稿重画成开。不然上面那句「一次没发」可能是拨了个空
  if (revenueSwitch().checked !== true) throw new Error('金额开关没拨上，这一格白量');
});

await ask('② 对照·留在报告通道里拨金额：当前编辑器照发一次预览、请求带上草稿值', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  await paintChannel(REPORT_A);
  const before = previewCount();
  flip(revenueSwitch());
  await settle();
  const fired = previewCount() - before;
  if (fired !== 1) {
    throw new Error('当前通道拨金额该重画一次预览，实测发了 ' + fired
      + ' 次——摘监听摘过了头，当前编辑器也不听了');
  }
  const body = lastPreviewBody();
  if (!body || body.revenueVisible !== true) {
    throw new Error('预览请求没带上草稿的金额设定：' + JSON.stringify(body));
  }
});

await ask('③ 晚到的旧回应·旧通道的预览回应晚于新通道的回来：不许撤销新通道正在显示的那张图', async () => {
  if (!push) throw new Error('产品码没载入，无从量起');
  previewManual = true;
  // 旧通道先起一张先不放行的预览，再切到另一个报告通道起一张
  await paintChannel(REPORT_A);
  await paintChannel(REPORT_B);
  if (previewPending.length !== 2) {
    throw new Error('两个报告通道各该挂起一张待放行的预览回应，实测 ' + previewPending.length + ' 张');
  }
  // 新通道的先回来：当前预览图挂上它给的地址
  previewPending[1]();
  await settle();
  const shot = previewShot();
  if (!shot || !String(shot.src).startsWith('blob:')) {
    throw new Error('新通道的预览图没挂上地址，这一格白量');
  }
  const current = String(shot.src);
  // 旧通道的那张最后才回来：不许撤销当前正在用的这个地址——新图还没载完时
  // 地址被撤销，预览那一块就白了
  previewPending[0]();
  await settle();
  if (revokedUrls.includes(current)) {
    throw new Error('旧通道晚到的回应撤销了新通道正在用的图地址 ' + current
      + '——共用一份地址时这一下会白掉当前预览');
  }
  const shotAfter = previewShot();
  if (!shotAfter || String(shotAfter.src) !== current) {
    throw new Error('当前预览图的地址被晚到的旧回应换掉了');
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
