/**
 * 首页「建议绑定」卡里绑上验证器之后，设置页那颗二次验证开关要跟着显示「已启用」，不用重载
 *
 * 抓的用户故障：上锁后那张卡当场就出，是最顺的一条路。使用者在卡里绑好验证器，
 * 不重载转去设置页，开关还写着「未绑定」——他以为没绑上；去拨那个开关，
 * 后端回「无需绑定验证器」，重载之前也关不掉。而登录其实已经要输动态验证码了，
 * 屏幕上这两头对不上。
 *
 * 由 SettingsAuthTotpHomeBindTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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
//
// 首页那张卡按 innerHTML 往里画，再按 id 取按钮挂监听；设置页那颗开关是建出来的节点。
// 两样都要找得到：前者按 id 给一只垫片接住监听，后者真挂在树上。

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

  removeChild(child) {
    const at = this.children.indexOf(child);
    if (at >= 0) {
      this.children.splice(at, 1);
      child.parentNode = null;
    }
    return child;
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

  click() {
    for (const fn of (this.listeners.click || []).slice()) fn.call(this, {type: 'click', target: this});
  }
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

const body = new MiniNode('body');
const stubs = new Map();

globalThis.document = {
  createElement: tag => new MiniNode(tag),
  // 先在树上找；找不到的 #id 给一只垫片——首页那张卡里的按钮就靠它接住监听
  querySelector: sel => {
    const id = String(sel || '').replace(/^#/, '');
    const hit = findById(body, id);
    if (hit) return hit;
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
globalThis.location = {reload() {}, assign() {}, hash: ''};

/** 照两个端点回真话：setup 给得出密钥，enroll 办得成 */
globalThis.fetch = url => {
  const u = String(url);
  if (u.endsWith('/auth/totp/setup')) {
    return Promise.resolve({status: 200,
      json: () => Promise.resolve({success: true, secret: 'JBSWY3DPEHPK3PXP', qrCode: ''})});
  }
  if (u.endsWith('/auth/totp/enroll')) {
    return Promise.resolve({status: 200,
      json: () => Promise.resolve({success: true, message: '已绑定，下次登录需要输入动态验证码'})});
  }
  return Promise.resolve({status: 200, json: () => Promise.resolve({success: true})});
};

let auth = null;
try {
  auth = await import(join(ui, 'settings-auth.js'));
} catch (err) {
  failures.push('载入 settings-auth.js 就抛了：' + err.message);
}

/** 把这一轮的异步走完：卡是画在 await 之后的，不等就什么也没看见 */
async function flush() {
  for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0));
}

/** 按一份登录状态画出「登录与安全」那一组，挂在树上并取回开关那几位 */
function paint(state) {
  if (!auth) throw new Error('settings-auth.js 没载入，无从量起');
  auth.setAuthState(state);
  return repaint();
}

/**
 * 再画一次：只重建那几行，不重灌登录状态
 *
 * 进设置页走的正是这条路（renderGeneral 每次进页重建 authCards），开关读的是这一刻的那几位。
 */
function repaint() {
  const wrap = auth.authCards();
  body.appendChild(wrap);
  return readSwitch();
}

function readSwitch() {
  const input = findById(body, 'totp-switch');
  if (!input) return {input: null, label: null};
  const text = input.parentNode && input.parentNode.children[1];
  return {input, label: text ? text.textContent : null};
}

/** 把树上已有的那组行撤掉，好让下一趟画的是新一份 */
function clearCards() {
  for (const child of body.children.slice()) {
    if (child.id === 'auth-cards') body.removeChild(child);
  }
}

const LOCKED = {enabled: true, operatorSession: false, totpEnabled: true, totpRequired: false};

let q1 = 'missing';
try {
  // 一、上锁后那张卡当场就出，先在设置页把开关画出来（写着「未绑定」）
  auth.setAuthState({...LOCKED, totpSetupNeeded: true});
  await flush();
  const setupBox = globalThis.document.querySelector('#totp-setup');
  const first = paint(LOCKED);

  // 二、在卡里填好口令与码，按「确认绑定」
  const enroll = globalThis.document.querySelector('#totp-enroll');
  enroll.click();
  await flush();

  // 三、屏幕上那颗当场跟上；再画一次（进设置页那条路）也要是同一档。
  // 重画时旁注与开关本身要一致：旁注写「已启用」、开关仍画成关着的话，
  // 再拨一下会走进绑定，而不是关掉已经绑上的二次验证。
  const live = readSwitch();
  same({
    '旁注': live.label,
    '开关开着': !!(live.input && live.input.checked),
  }, {
    '旁注': '已启用',
    '开关开着': true,
  }, '首页卡里绑好后，设置页开关重画成开着，与旁注一致');
  clearCards();
  const again = repaint();

  q1 = {
    '卡出得来': !!(setupBox && String(setupBox.innerHTML).includes('id="totp-enroll"')),
    '绑之前写着没绑': first.label,
    '当场那颗': live.label,
    '再画一次': again.label,
  };
} catch (e) {
  q1 = 'error:' + e.message;
}
same(q1, {
  '卡出得来': true,
  '绑之前写着没绑': '未绑定',
  '当场那颗': '已启用',
  '再画一次': '已启用',
}, '① 首页卡里绑上后，开关跟着显示已启用');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
