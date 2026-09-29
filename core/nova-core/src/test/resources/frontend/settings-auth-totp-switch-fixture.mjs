/**
 * 二次验证那一档说的是「登录要不要输码」，不是配置里那一位
 *
 * 抓的用户故障：控制台上了锁、配置里 auth.totp 也开着，但还没绑验证器时，
 * 设置页的开关说「已启用」——使用者以为登录已经要输动态验证码，其实只要口令。
 * 这时把开关拨到关也拨不下去（后端回「二次验证本来就没开着」），两头对不上。
 *
 * 真开着的判法是「开着且已绑」。开关按这一位画：没绑时画成关着、写明「未绑定」，
 * 拨开直接进绑定流程——没绑这一档根本没有「拨关」可按。
 *
 * 由 SettingsAuthTotpSwitchTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

const failures = [];
let checks = 0;

function same(got, want, what) {
  checks++;
  const a = JSON.stringify(got);
  const b = JSON.stringify(want);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
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

  insertBefore(node, before) {
    const at = this.children.indexOf(before);
    node.parentNode = this;
    if (at < 0) this.children.push(node);
    else this.children.splice(at, 0, node);
    return node;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
    if (name === 'class') this.className = String(value);
  }

  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  removeEventListener() {}
}

function walk(root, visit) {
  visit(root);
  for (const child of root.children || []) walk(child, visit);
}

function findById(root, id) {
  let hit = null;
  walk(root, node => {
    if (!hit && node.id === id) hit = node;
  });
  return hit;
}

const stubs = new Map();

globalThis.document = {
  createElement: tag => new MiniNode(tag),
  // 拨开关走的那几段会按 id 取按钮往里挂监听（#totp-off-*、#totp-enroll-*）。
  // 这里按 id 现造一只垫片接住它们：本尺量的是「走哪条路」，按钮长什么样不在射程里。
  querySelector: sel => {
    const id = String(sel || '').replace(/^#/, '');
    if (!stubs.has(id)) {
      const node = new MiniNode('div');
      node.id = id;
      stubs.set(id, node);
    }
    return stubs.get(id);
  },
  addEventListener: () => {},
  documentElement: {classList: {toggle() {}, contains: () => false}},
};
globalThis.window = {};
globalThis.fetch = () => Promise.resolve({status: 200, json: () => Promise.resolve({success: true})});
globalThis.location = {reload() {}, assign() {}, hash: ''};

let auth = null;
try {
  auth = await import(join(ui, 'settings-auth.js'));
} catch (err) {
  failures.push('载入 settings-auth.js 就抛了：' + err.message);
}

/**
 * 按一份登录状态把「登录与安全」那一组画出来，取回开关与它旁注的那几个字
 */
function paint(state) {
  if (!auth) throw new Error('settings-auth.js 没载入，无从量起');
  auth.setAuthState(state);
  const wrap = auth.authCards();
  const input = findById(wrap, 'totp-switch');
  if (!input) return {input: null, label: null, row: null};
  const label = input.parentNode;
  const text = label && label.children[1] ? label.children[1].textContent : null;
  let row = null;
  walk(wrap, node => {
    if (!row && node.id === 'auth-totp') row = node;
  });
  return {input, label: text, row};
}

function rowText(row) {
  if (!row) return '';
  let out = '';
  walk(row, node => {
    out += (node.textContent || '') + '\n';
  });
  return out;
}

const LOCKED = {enabled: true, operatorSession: false};

// ① 抓的用户故障：上锁、配置要开、还没绑验证器，开关说「已启用」
let q1 = 'missing';
try {
  const {input, label, row} = paint({...LOCKED, totpEnabled: true, totpRequired: false});
  q1 = {
    '开关画出来了': !!input,
    '开关亮着': !!(input && input.checked),
    '旁注': label,
    '写着没绑': rowText(row).includes('未绑定'),
  };
} catch (e) {
  q1 = 'error:' + e.message;
}
same(q1, {
  '开关画出来了': true,
  '开关亮着': false,
  '旁注': '未绑定',
  '写着没绑': true,
}, '① 没绑验证器时开关说已启用');

// ② 阳性对照：绑上了就真叫「已启用」，开关亮着
let q2 = 'missing';
try {
  const {input, label} = paint({...LOCKED, totpEnabled: true, totpRequired: true});
  q2 = {'开关亮着': !!(input && input.checked), '旁注': label};
} catch (e) {
  q2 = 'error:' + e.message;
}
same(q2, {'开关亮着': true, '旁注': '已启用'}, '② 绑上了仍叫已启用');

// ③ 配置里那一位是关的：画成关着、说「已关闭」，不冒充没绑
let q3 = 'missing';
try {
  const {input, label, row} = paint({...LOCKED, totpEnabled: false, totpRequired: false});
  q3 = {
    '开关亮着': !!(input && input.checked),
    '旁注': label,
    '写着没绑': rowText(row).includes('未绑定'),
  };
} catch (e) {
  q3 = 'error:' + e.message;
}
same(q3, {'开关亮着': false, '旁注': '已关闭', '写着没绑': false}, '③ 配置关着说已关闭');

/** 把 change 按这一档真触发一次：真 DOM 里那是拨完才发的，这里照同一个监听走 */
function fireChange(input) {
  for (const fn of (input.listeners && input.listeners.change || [])) {
    fn.call(input, {type: 'change', target: input});
  }
}

/**
 * 拨一下之后，开关下面那一段现在是哪一副
 *
 * 空着＝不给拨（什么流程都没开）；非空则按那一段自己的按钮落点认是关断还是绑定。
 * @param flowNode 开关下面那一段
 * @return 「不给拨」「关断」「绑定」之一
 */
function roadOf(flowNode) {
  const html = String((flowNode && flowNode.innerHTML) || '').trim();
  if (!html) return '不给拨';
  return html.includes('totp-off') ? '关断' : '绑定';
}

// ④ 没绑时拨关这一档真走一趟，看它走的是哪条路
//
// 这一档下开关本来就画成关着，「拨关」没有落点；可它一旦真拨下去，就不能把人带进
// 关断那段（那段要密码加验证器上的码），更不能走到底是「二次验证本来就没开着」。
// 这一格量的是走哪条路，不复述开关画在哪一档——那归①。
let q4 = 'missing';
try {
  const {row} = paint({...LOCKED, totpEnabled: true, totpRequired: false});
  const flowNode = findById(row, 'totp-flow');
  const input = findById(row, 'totp-switch');
  input.checked = false;
  fireChange(input);
  q4 = {'走的是哪条路': roadOf(flowNode)};
} catch (e) {
  q4 = 'error:' + e.message;
}
same(q4, {'走的是哪条路': '不给拨'}, '④ 没绑时拨关走的是哪条路');

// ⑤ 阴性对照：这张卡只画在上过锁的机器上，没锁时不该冒出一个开关来
let q5 = 'missing';
try {
  const {input} = paint({enabled: false, operatorSession: false, totpEnabled: true, totpRequired: false});
  q5 = {'开关画出来了': !!input};
} catch (e) {
  q5 = 'error:' + e.message;
}
same(q5, {'开关画出来了': false}, '⑤ 没上锁不出二次验证开关');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
