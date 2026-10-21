import {datasourceRead} from './datasource-read.mjs';
/** 每类通知的设置各自可见、独立保存与恢复；配置项由真实处理器经接口传入。 */
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
    const frame = stack.split('\n').find(line => line.includes('push-handler-settings-fixture.mjs')) || '';
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


const handlers = JSON.parse(process.env.HANDLERS);
const report = handlers.find(item => item.displayName === '下播报告');
const digest = handlers.find(item => item.displayName === '下播打赏播报');
const plugin = handlers.find(item => item.displayName === '额外通知');
const requests = [];
let saved = [];
globalThis.fetch = async (path, opt = {}) => {
  const url = String(path);
  requests.push({url, opt});
  if (url.endsWith('/api/report/preview')) return {ok: true, blob: async () => 'image'};
  if (url.includes('/datasource')) {
    if (opt.method === 'POST') {
      saved = [JSON.parse(opt.body).user];
      return {status: 200, json: async () => ({success: true, version: 'v1'})};
    }
    return {status: 200, json: async () => datasourceRead(url, JSON.stringify(saved))};
  }
  return {status: 200, json: async () => ({success: true, records: [], handlers})};
};
const push = await import(join(pages, 'push.js'));
const model = await import(join(pages, 'push-model.js'));
const {store} = await import('./store.js');
const user = {platform: 'bilibili', uid: 1, _uname: '甲'};
const target = {platform: 'qq', type: 1, num: 12345, enabled: true};
const all = root => {
  if (!root) return [];
  return [root, ...root.children.flatMap(all)];
};
const page = () => idNode('push-right');
const section = name => all(page()).find(item => item.tagName === 'h3' && item.innerHTML.endsWith(name))?.parentNode;
const previewCount = box => all(box).filter(item => hasClass(item, 'rep-view')).length;
const hasClass = (node, name) => String(node.className).split(/\s+/).includes(name);
const previewRequests = () => requests.filter(item => item.url.endsWith('/api/report/preview'));
function control(box, label) {
  const node = all(box).find(item => item.tagName === 'input' && item.getAttribute('aria-label') === label);
  if (!node) throw new Error('缺少数字框：' + label);
  return node;
}
function change(node, value) {
  if (node.type === 'checkbox') node.checked = value; else node.value = String(value);
  node.dispatchEvent(fakeEvent('change', node));
}
async function paint(messages, list = handlers) {
  requests.length = 0;
  store.handlerList = list;
  saved = [{...user, targets: [{...target, messages}]}];
  await push.reload();
  await push.showPush('1', '12345');
  push.renderStreamers();
}
const message = (handler, params = {}) => ({handler: handler.className, params});
const messages = () => push.pushEntries()[0].targets[0].messages;
const paramsOf = handler => messages().find(item => item.handler === handler.className).params;
const threshold = () => control(section('下播打赏播报设置'), '播报门槛（元）');
async function until(read, what) {
  const deadline = Date.now() + 10000;
  while (!read()) {
    if (Date.now() >= deadline) throw new Error('等待超时：' + what);
    await new Promise(resolve => setImmediate(resolve));
  }
}

// 抓两类同时订阅时只有第一类配置可见；平台、停用状态与别名也应按推送类型的口径筛选。
await ask('模型：所有启用配置按推送类型顺序列出', async () => {
  const list = [digest, report, {...plugin, platform: 'other'}];
  const states = model.optionStates(user, {...target, messages: [message(report), message(digest), message(plugin)]}, list);
  same(states.map(item => [item.className, item.preview]), [[digest.className, false], [report.className, true]], '配置集合');
  const alias = {...digest, aliases: ['old.Digest']};
  same(model.optionStates(user, {...target, messages: [{handler: 'old.Digest', params: {reward_threshold_yuan: 200}},
    {...message(report), enabled: false}]}, [report, alias]).map(item => [item.className, item.custom]),
    [[digest.className, true]], '旧名和停用');
});
// 抓恢复默认删错消息、删掉模板或其他插件参数。
await ask('模型：恢复只删除本类配置键', async () => {
  const input = {reward_threshold_yuan: 200, message: '保留', extra: 7};
  same(model.restoredLayoutParams(input, digest), {message: '保留', extra: 7}, '恢复结果');
  same(input.reward_threshold_yuan, 200, '原参数未修改');
  const states = model.optionStates(user, {...target, messages: [message(report, {cover: false}), message(digest, model.restoredLayoutParams(input, digest))]}, handlers);
  same(states.map(item => item.custom), [true, false], '各类独立状态');
});
// 抓两类都订时门槛项找不到，或默认不是处理器声明的 50。
await ask('两类同时订阅：报告预览和门槛 50 同时可见', async () => {
  await paint([message(report), message(digest)]);
  same(previewCount(section('下播报告版式')), 1, '报告预览区');
  same(threshold().value, '50', '门槛默认值');
  same(previewCount(section('下播打赏播报设置')), 0, '设置块没有预览区');
});
// 抓门槛设置误挂下播报告预览；金额说明与网络请求也必须不存在。
await ask('只订打赏：无报告预览、金额说明或预览请求', async () => {
  await paint([message(digest)]);
  same(previewCount(page()), 0, '预览区数');
  same(previewRequests().length, 0, '预览请求数');
  same(all(page()).some(item => item.textContent.includes('按本群金额可见设置预览')), false, '金额说明');
  same(threshold().value, '50', '门槛默认值');
});
// 抓改门槛无法保存或误写报告那条消息。真正调用保存，再将收到的数据重新交给页面读回。
await ask('门槛改为 200 保存读回，报告参数原样保留', async () => {
  await paint([message(report, {cover: false}), message(digest)]);
  change(threshold(), 200);
  await push.save();
  same(saved[0].targets[0].messages.map(item => item.params), [{cover: false}, {reward_threshold_yuan: 200}], '保存内容');
  await paint(saved[0].targets[0].messages);
  same(threshold().value, '200', '重新打开门槛');
});
// 抓清空已存门槛后变成 0，导致每笔打赏都播。
await ask('清空已存门槛仍保留 200', async () => {
  await paint([message(digest, {reward_threshold_yuan: 200})]);
  change(threshold(), '');
  same(paramsOf(digest), {reward_threshold_yuan: 200}, '门槛参数');
  same(threshold().value, '200', '门槛框');
  await push.save();
  same(saved[0].targets[0].messages[0].params, {reward_threshold_yuan: 200}, '保存门槛');
});
// 抓清空默认门槛后写入 0，覆盖原来的默认行为。
await ask('清空默认门槛仍无键且显示 50', async () => {
  await paint([message(digest)]);
  change(threshold(), '');
  same(paramsOf(digest), {}, '默认门槛参数');
  same(threshold().value, '50', '默认门槛框');
});
// 抓非法数字污染存值，或框里没有恢复到原值。
for (const invalid of ['abc', 'Infinity', '   ']) {
  await ask('非数字门槛恢复原值：' + JSON.stringify(invalid), async () => {
    await paint([message(digest, {reward_threshold_yuan: 200})]);
    change(threshold(), invalid);
    same(paramsOf(digest), {reward_threshold_yuan: 200}, '门槛参数');
    same(threshold().value, '200', '门槛框');
  });
}
// 抓越界数字照存，页面与实际播报门槛不一致。
for (const [input, expected] of [[-5, 0], [2000000, 1000000]]) {
  await ask('越界门槛夹住：' + input, async () => {
    await paint([message(digest)]);
    change(threshold(), input);
    same(paramsOf(digest).reward_threshold_yuan, expected, '门槛参数');
    same(threshold().value, String(expected), '门槛框');
  });
}
// 抓整数项存下小数，页面与运行时读到的整数不一致。
for (const [input, expected] of [[12.4, 12], [12.5, 13]]) {
  await ask('小数门槛四舍五入：' + input, async () => {
    await paint([message(digest)]);
    change(threshold(), input);
    same(paramsOf(digest).reward_threshold_yuan, expected, '门槛参数');
    same(threshold().value, String(expected), '门槛框');
  });
}
// 抓共用数字框只修设置块，报告版式清空仍变成 0。
await ask('清空报告弹幕排行仍保留 8', async () => {
  await paint([message(report, {danmu_ranking: 8})]);
  change(control(section('下播报告版式'), '弹幕排行'), '');
  same(paramsOf(report), {danmu_ranking: 8}, '版式参数');
  same(control(section('下播报告版式'), '弹幕排行').value, '8', '版式数字框');
});
// 抓一次保存只写入其中一类。
await ask('一次保存两类改动，重新打开都在', async () => {
  await paint([message(report), message(digest)]);
  change(control(section('下播报告版式'), '直播间封面'), false);
  change(threshold(), 200);
  await push.save();
  const stored = saved[0].targets[0].messages;
  same(stored.map(item => item.params), [{cover: false}, {reward_threshold_yuan: 200}], '两类保存内容');
  await paint(stored);
  same(control(section('下播报告版式'), '直播间封面').checked, false, '报告读回');
  same(threshold().value, '200', '门槛读回');
});
// 抓一块的自定义状态跟着另一块走，或恢复默认连另一块一并清掉。
await ask('设置独立显示状态、确认与恢复，报告版式保留', async () => {
  await paint([message(report, {cover: false}), message(digest, {reward_threshold_yuan: 200, message: '保留'})]);
  const box = section('下播打赏播报设置');
  const restore = all(box).find(item => item.tagName === 'button' && item.textContent === '恢复默认' && !item.hidden);
  if (!restore) throw new Error('设置块缺少恢复默认按钮');
  same(all(box).some(item => item.innerHTML.includes('当前设置：<b>自定义</b>')), true, '设置自定义状态');
  restore.click();
  const dialog = all(document.body).find(item => hasClass(item, 'ask'));
  const body = dialog.querySelector('p').textContent;
  same(body.includes('此通道的「下播打赏播报」设置将恢复为默认'), true, '确认说明');
  same(body.includes('报告版式'), false, '确认不提报告版式');
  dialog.dispatchEvent(fakeEvent('click', dialog.querySelector('[data-act="yes"]')));
  await until(() => paramsOf(digest).reward_threshold_yuan === undefined, '恢复默认');
  same(paramsOf(digest), {message: '保留'}, '只删门槛键');
  same(paramsOf(report), {cover: false}, '报告参数');
  same(threshold().value, '50', '恢复默认门槛');
  same(all(section('下播打赏播报设置')).some(item => item.innerHTML.includes('当前设置：<b>默认</b>')), true, '恢复后默认');
  same(all(section('下播报告版式')).some(item => item.innerHTML.includes('当前版式：<b>自定义</b>')), true, '报告仍自定义');
});
// 抓第三方插件未声明预览能力时被默认当成报告版式。
await ask('未声明的插件按设置画且不请求报告预览', async () => {
  const legacy = {...plugin};
  delete legacy.reportLayout;
  await paint([message(plugin)], [legacy]);
  same(previewCount(page()), 0, '插件预览区');
  same(previewRequests().length, 0, '插件预览请求');
  same(control(section('额外通知设置'), '重试次数').value, '3', '插件配置项');
});
// 抓多配置块顺序与推送类型列表不一致。
await ask('配置块跟随推送类型顺序，通道设置最后', async () => {
  await paint([message(report), message(digest)], [digest, report]);
  const titles = all(page()).filter(item => item.tagName === 'h3').map(item => item.innerHTML.replace(/<[^>]+>/g, ''));
  same(titles, ['1推送类型', '2消息模板', '3下播打赏播报设置', '3下播报告版式', '4通道设置'], '段落顺序');
});
for (const [name, chosen] of [['只开下播报告', [report]], ['只开下播打赏播报', [digest]], ['两样都开', [report, digest]]]) {
  await paint(chosen.map(item => message(item)));
  console.log(name + '：' + all(page()).filter(item => item.tagName === 'h3')
    .map(item => item.innerHTML.replace(/<[^>]+>/g, '')).join(' → '));
}
console.log('跑了 ' + checks + ' 格，' + failures.length + ' 格红');
for (const failure of failures) console.log('红 ' + failure);
process.exitCode = failures.length ? 1 : 0;
