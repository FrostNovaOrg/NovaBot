/**
 * 第 4 段「本群设置」：栏目开合记住、命令分类原生折叠、问号说明开得了也关得了
 *
 * 用户那头的毛病：展开的栏目切到别的通道再回来又收起了，连改几处每改一处都得再展开一次；
 * 命令开关要先点「细调」才看得见，还以为整组没有开关；几行长说明摊在段里占掉大半屏。
 *
 * 这里量的是三件：切到别的通道再回来开合状态照旧；命令分类是原生 details 折叠、
 * 没有「细调／收起」按钮、每类标着「当前群共用」（好友会话写「当前会话共用」）；
 * 各行的问号说明键盘开得了关得了、读屏念得出、手机上点得开。
 *
 * 本尺真跑：假 DOM 之外，画第 4 段用的是产品码那一份（sessions.js 的 sessionSettings）。
 * 各问各自 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 SessionSettingsFoldFixtureTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
    const frame = stack.split('\n').find(line => line.includes('session-settings-fold-fixture.mjs')) || '';
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
  get textContent() { return this._text; }
  set textContent(v) { this._text = String(v ?? ''); }
  get innerHTML() { return this._html; }
  set innerHTML(html) {
    this._html = String(html ?? '');
    for (const child of this.children) detach(child);
    this.children = [];
    queueMicrotask(() => { for (const observer of observers) observer(); });
    // 只把 <input …> 立成真节点：本页会往 label 里塞一个复选框再取回来。
    // 旁的字串（组名、旁注这些）仍留在 innerHTML 里，按字串认
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
  click() { this.dispatchEvent({type: 'click', target: this, stopPropagation() {}}); }
  focus() {
    globalThis.document.activeElement = this;
    this.dispatchEvent({type: 'focus', target: this});
  }
  blur() {
    if (globalThis.document.activeElement === this) globalThis.document.activeElement = null;
    this.dispatchEvent({type: 'blur', target: this});
  }
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
const observers = [];
globalThis.MutationObserver = class {
  constructor(callback) { this.callback = callback; }
  observe() { observers.push(this.callback); }
};
const windowListeners = {};
globalThis.window = {
  innerWidth: 390, innerHeight: 780,
  addEventListener(type, fn) { (windowListeners[type] ||= []).push(fn); },
  dispatch(type) { for (const fn of windowListeners[type] || []) fn(); },
};

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
  dispatchDoc(type, event) {
    for (const fn of (docListeners[type] || []).slice()) fn(event);
  },
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

globalThis.location = {hash: '', href: '', replace() {}, reload() {}};

// —— 假接口：只供 sessions.js 的草稿记账用，画那一段用不上回包 ——

globalThis.fetch = () => Promise.resolve({status: 200, json: () => Promise.resolve({success: true, message: '已保存'})});

// —— 载入产品码 ——

let sessions = null;
let model = null;
try {
  model = await import(join(pages, 'push-model.js'));
  sessions = await import(join(pages, 'sessions.js'));
} catch (err) {
  failures.push('载入 sessions.js／push-model.js 就抛了：' + err.message);
}

const COMMANDS = [
  {name: '直播报告', description: '下播后看一场的报告', category: '报告',
    requiresAdmin: false, disableable: true, available: true},
  {name: '@名单', description: '看谁订阅了开播与动态提醒', category: '提醒',
    requiresAdmin: false, disableable: true, available: true},
  {name: '菜单', description: '列出可用命令', category: '命令管理',
    requiresAdmin: false, disableable: false, available: true},
];

function ctxOf(target, session) {
  return {
    session, target,
    commands: COMMANDS,
    subscriptions: [],
    totalDataAvailable: false,
    summary: {
      revenue: model.revenueSummary(session),
      quiet: model.quietSummary(session, {}),
      command: model.commandSummary(COMMANDS, session, false),
      subscription: model.subscriptionSummary([], session),
      atAll: model.atAllStatus({bots: [], sessions: []}, session, target, {}),
    },
    groups: model.commandGroups(COMMANDS, session, false),
  };
}

const GROUP_A = {platform: 'q', type: 1, num: 12345};
const GROUP_B = {platform: 'q', type: 1, num: 67890};
const FRIEND = {platform: 'q', type: 0, num: 11111};

function sessionOf(target) {
  return {
    platform: target.platform, num: target.num,
    type: Number(target.type) === 1 ? '群' : '好友', typeCode: Number(target.type),
    disabled: [], menuHidden: [], menuNotes: {},
    revenueVisible: false, revenueExplicit: true,
    quietMode: 'follow', quietStart: null, quietEnd: null, quietActive: false,
    streamers: ['甲'],
  };
}

function paint(target, host = idNode('session-host-' + target.num)) {
  host.innerHTML = '';
  if (sessions) sessions.sessionSettings(host, ctxOf(target, sessionOf(target)));
  return host;
}

function allNodes(scope) {
  const out = [];
  const walk = node => {
    out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(scope);
  return out;
}

/**
 * 某一行折叠项：标题写着 title 的那个 details
 * 组名写在 summary 的 innerHTML 里，假 DOM 不把那段拆成文本节点，按字串认
 */
function rowOf(host, title) {
  return allNodes(host).find(node => classesOf(node).includes('crow')
    && allNodes(node).some(child => child.tagName === 'summary'
      && String(child.innerHTML || '').includes('<span class="cr-t">' + title + '</span>'))) || null;
}

/** 某一行的问号按钮 */
function helpOf(host, title) {
  const row = rowOf(host, title);
  return row ? allNodes(row).find(node => classesOf(node).includes('row-help')) : null;
}

/** 此刻挂着的那块说明浮层 */
function tipPanels() {
  return allNodes(idNode('body')).filter(node => node.getAttribute
    && node.getAttribute('role') === 'tooltip');
}

/** 打开或关上一个折叠项，并把浏览器那一下 toggle 补上 */
function setOpen(details, open) {
  details.open = open;
  details.dispatchEvent({type: 'toggle', target: details});
}

const ROWS = ['金额可见', '静音时段', '命令', '提醒订阅'];

const hostA = paint(GROUP_A);
const hostB = paint(GROUP_B);
const hostFriend = paint(FRIEND);

await ask('① 展开的栏目切到别的通道再回来仍是展开的', async () => {
  if (!sessions) throw new Error('sessions.js 没载入，无从量起');
  const opened = [];
  for (const title of ROWS) {
    const row = rowOf(hostA, title);
    if (!row) throw new Error('「' + title + '」那一行没画出来');
    setOpen(row, true);
    opened.push(row);
  }

  // 切到另一个群（这一段整段换掉），再切回来
  const away = paint(GROUP_B);
  const back = paint(GROUP_A);

  const kept = {};
  const lost = {};
  for (const title of ROWS) {
    kept[title + '切回来后仍摊开着'] = !!(rowOf(back, title) || {}).open;
    lost[title + '在别的通道自己收着'] = !((rowOf(away, title) || {}).open);
  }

  same({
    '切走前那几行都摊开着': opened.every(node => node.open),
    ...kept, ...lost,
  }, {
    '切走前那几行都摊开着': true,
    ...Object.fromEntries(ROWS.map(title => [title + '切回来后仍摊开着', true])),
    ...Object.fromEntries(ROWS.map(title => [title + '在别的通道自己收着', true])),
  }, '开合状态');
});

await ask('② 命令分类是原生 details 折叠，没有「细调／收起」按钮', async () => {
  if (!sessions) throw new Error('sessions.js 没载入，无从量起');
  const host = paint(GROUP_A);
  const groups = allNodes(host).filter(node => classesOf(node).includes('cgroup'));
  const fine = allNodes(host).filter(node => node.tagName === 'button'
    && (node.textContent === '细调' || node.textContent === '收起'));

  same({
    '命令分类画出来了': groups.length > 0,
    '每一类都是 details': groups.every(node => node.tagName === 'details'),
    '细调／收起按钮数': fine.length,
  }, {
    '命令分类画出来了': true,
    '每一类都是 details': true,
    '细调／收起按钮数': 0,
  }, '命令分类折叠');
});

await ask('③ 每类命令标着共用范围：群里写「当前群共用」，好友会话写「当前会话共用」', async () => {
  if (!sessions) throw new Error('sessions.js 没载入，无从量起');
  const groupHost = paint(GROUP_A);
  const friendHost = paint(FRIEND);
  const groupMarks = allNodes(groupHost)
    .filter(node => classesOf(node).includes('cmdscope'))
    .map(node => node.textContent);
  const friendMarks = allNodes(friendHost)
    .filter(node => classesOf(node).includes('cmdscope'))
    .map(node => node.textContent);

  same({
    '群会话那几类都标了': groupMarks.length > 0 && groupMarks.every(text => text === '当前群共用'),
    '好友会话那几类都标了': friendMarks.length > 0 && friendMarks.every(text => text === '当前会话共用'),
  }, {
    '群会话那几类都标了': true,
    '好友会话那几类都标了': true,
  }, '共用范围标记');
});

await ask('④ 各行问号说明：键盘开得了关得了，读屏念得出', async () => {
  if (!sessions) throw new Error('sessions.js 没载入，无从量起');
  const host = paint(GROUP_A);
  const button = helpOf(host, '金额可见');
  if (!button) throw new Error('「金额可见」那一行没有问号说明');

  const before = {
    '四行都有问号': ROWS.every(title => !!helpOf(host, title)),
    '按钮带读屏名字': button.getAttribute('aria-label') === '金额可见：查看说明',
    '按钮指着浮层': !!button.getAttribute('aria-controls'),
    '收着时写着没展开': button.getAttribute('aria-expanded') === 'false',
    '收着时读屏读不到说明': !button.getAttribute('aria-describedby'),
  };

  // 键盘走到它就是打开
  button.focus();
  const opened = {
    '展开时写着已展开': button.getAttribute('aria-expanded') === 'true',
    '展开时读屏读得到说明': !!button.getAttribute('aria-describedby'),
  };
  const panelId = String(button.getAttribute('aria-describedby') || '').replace(/^#/, '');
  const panel = tipPanels().find(node => node.getAttribute('id') === panelId) || tipPanels()[0] || null;
  opened['浮层是读屏读得出的 tooltip'] = !!(panel && panel.getAttribute('role') === 'tooltip');
  opened['浮层里写着说明'] = !!(panel && panel.textContent.includes('金额'));
  opened['浮层看得见'] = !!(panel && !panel.hidden);
  opened['读屏名字指着这块浮层'] = !!(panel && button.getAttribute('aria-describedby') === panel.getAttribute('id'));

  button.blur();
  same(button.getAttribute('aria-expanded'), 'false', '未钉住时失焦收起');
  button.focus();

  // Escape 关掉
  globalThis.document.dispatchDoc('keydown', {
    key: 'Escape', target: button, preventDefault() {},
  });
  const closed = {
    'Escape 后写着收起': button.getAttribute('aria-expanded') === 'false',
    'Escape 后浮层藏起来了': !!(panel && panel.hidden),
  };

  same({...before, ...opened, ...closed}, {
    '四行都有问号': true,
    '按钮带读屏名字': true,
    '按钮指着浮层': true,
    '收着时写着没展开': true,
    '收着时读屏读不到说明': true,
    '展开时写着已展开': true,
    '展开时读屏读得到说明': true,
    '浮层是读屏读得出的 tooltip': true,
    '浮层里写着说明': true,
    '浮层看得见': true,
    '读屏名字指着这块浮层': true,
    'Escape 后写着收起': true,
    'Escape 后浮层藏起来了': true,
  }, '问号说明·键盘与读屏');
});

await ask('⑤ 各行问号说明：手机上点一下开、再点一下关', async () => {
  if (!sessions) throw new Error('sessions.js 没载入，无从量起');
  const host = paint(GROUP_A);
  const button = helpOf(host, '静音时段');
  if (!button) throw new Error('「静音时段」那一行没有问号说明');

  button.click();
  const afterFirst = {
    '点一下开着': button.getAttribute('aria-expanded') === 'true',
  };
  const panel = tipPanels().find(node => !node.hidden) || null;
  afterFirst['浮层露出来了'] = !!panel;
  afterFirst['浮层里是这一行的说明'] = !!(panel && panel.textContent.includes('静音'));

  button.click();
  const afterSecond = {
    '再点一下收起': button.getAttribute('aria-expanded') === 'false',
    '再点一下浮层藏起来了': !!(panel && panel.hidden),
  };

  same({...afterFirst, ...afterSecond}, {
    '点一下开着': true,
    '浮层露出来了': true,
    '浮层里是这一行的说明': true,
    '再点一下收起': true,
    '再点一下浮层藏起来了': true,
  }, '问号说明·点按');
});

// 切换通道或重新绘制后，旧按钮与说明应一起退出。
await ask('⑥ 钉住的问号说明，换通道或重画后收起', async () => {
  for (const target of [GROUP_B, GROUP_A]) {
    const host = paint(GROUP_A);
    const button = helpOf(host, '金额可见');
    button.click();
    const panel = tipPanels()[0];
    paint(target, host);
    await Promise.resolve();
    same([panel.hidden, button.getAttribute('aria-expanded'), button.getAttribute('aria-describedby')],
      [true, 'false', null], '通道重画后的说明');
  }
});

// 触屏没有 Esc；点击说明内部应能继续阅读，点击外部应能关闭。
await ask('⑦ 钉住后点说明内保留，点别处收起', async () => {
  const button = helpOf(paint(GROUP_A), '静音时段');
  button.click();
  const panel = tipPanels()[0];
  const child = new MiniNode('span');
  panel.appendChild(child);
  for (const target of [button, child]) {
    document.dispatchDoc('pointerdown', {target});
    same(panel.hidden, false, '说明内与问号上的点按');
  }
  document.dispatchDoc('pointerdown', {target: document.body});
  same([panel.hidden, button.getAttribute('aria-expanded'), button.getAttribute('aria-describedby')],
    [true, 'false', null], '点别处后的说明');
});

// 页面路由变化后，上一页的说明不应继续悬在屏幕上。
await ask('⑧ 换页后问号说明收起', async () => {
  const button = helpOf(paint(GROUP_A), '金额可见');
  button.click();
  const panel = tipPanels()[0];
  window.dispatch('hashchange');
  same([panel.hidden, button.getAttribute('aria-expanded')], [true, 'false'], '换页后的说明');
});

// 滚动与窗口缩放后说明仍贴着按钮，按钮出屏或被移除时说明应关闭。
await ask('⑨ 滚动与缩放时说明跟随，出屏或移除时收起', async () => {
  const host = paint(GROUP_A);
  const button = helpOf(host, '金额可见');
  let rect = {top: 100, bottom: 122, left: 40, right: 62};
  button.getBoundingClientRect = () => rect;
  button.click();
  const panel = tipPanels()[0];
  panel.offsetWidth = 280;
  panel.offsetHeight = 100;
  const initialTop = panel.style.top;
  rect = {top: 200, bottom: 222, left: 50, right: 72};
  window.dispatch('scroll');
  same([panel.hidden, panel.style.top !== initialTop], [false, true], '滚动后跟随');
  rect = {top: 720, bottom: 742, left: 370, right: 392};
  window.dispatch('resize');
  same([parseFloat(panel.style.top) < rect.top, parseFloat(panel.style.left) + panel.offsetWidth <= window.innerWidth],
    [true, true], '缩放后不出视口');
  rect = {top: -30, bottom: -8, left: 40, right: 62};
  window.dispatch('scroll');
  same([panel.hidden, button.getAttribute('aria-expanded')], [true, 'false'], '按钮出屏后');
  rect = {top: 100, bottom: 122, left: 40, right: 62};
  button.click();
  host.innerHTML = '';
  await Promise.resolve();
  same([panel.hidden, button.getAttribute('aria-expanded')], [true, 'false'], '按钮移除后');
});

// 问号应解释隐藏整榜的理由，开关下面的后果仍保留且不在说明里重复。
await ask('⑩ 金额说明只讲理由，开关下保留后果', async () => {
  const host = paint(GROUP_A);
  helpOf(host, '金额可见').click();
  same(tipPanels()[0].textContent,
    '金额榜整榜不出而不是抹掉数字：那几张榜的每一行本质都是「某人花了多少钱」，只去掉右侧的数字，仍然是在公开排消费。', '金额问号文字');
  same(allNodes(rowOf(host, '金额可见')).some(node => node.innerHTML.includes(
    '关掉后，下播报告不显示收益、卡片改用人数与条数、礼物与醒目留言榜整榜不出；数据查询命令同样不再回金额。')),
    true, '开关下的后果说明');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
