/**
 * 初始设置页第 1 步那只登记钮：上了锁就进页判能不能登记
 *
 * 浏览器没给通行密钥接口（http 加 IP 这类非安全上下文）时，点下去什么也不发生；
 * 浏览器给了接口、地址却是 IP 时，要点了才从登记参数那一条的失败回包里得知原因。
 * 这里切出 setup.js 的 passkeyBlock 与 passkeys.js 的 registerBlockedReason 真执行：
 * 上了锁、登记不了就进页置灰并把说明换成原因句；登记得了照旧可点、说明不换；
 * 没上锁时照旧置灰，也不去问服务端。浏览器没给接口时沿用设置卡原句、不发请求；
 * 原因句为空用兜底句；取不到回包照旧可点；原因句照字面显示，不当 HTML。
 *
 * 由 SetupPasskeyBlockTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const setupSrc = readFileSync(join(ui, 'setup.js'), 'utf8');
const passkeysSrc = readFileSync(join(ui, 'passkeys.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个 function」。
 */
function bracedFrom(text, marker) {
  const start = text.indexOf(marker);
  if (start < 0) return '';
  const open = text.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    const prev = i > 0 ? text[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && text[i + 1] === '/') {
      const nl = text.indexOf('\n', i);
      i = nl < 0 ? text.length : nl;
      continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return '';
}

/**
 * 只够这一块用的极小节点：按钮与说明各一，挂在一只盒子里
 */
function element(tag) {
  return {
    tagName: tag,
    className: '',
    textContent: '',
    disabled: false,
    children: [],
    listeners: {},
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
    // 赋 innerHTML 时把标签吃掉、只留字面：原因句若走了 innerHTML，句里的标签就不见了
    set innerHTML(html) {
      this.textContent = String(html).replace(/<[^>]*>/g, '');
    },
  };
}

const LOCKED_NOTE = '登记过之后';
const UNLOCKED_NOTE = '先上锁';
const REASON = '通行密钥只能绑定域名，而现在是用 IP 地址（192.168.1.10）访问的。请改用一个域名访问控制台后再试。';
const FALLBACK = '这个地址用不了通行密钥。';
const MARKED_REASON = '通行密钥只能绑定域名，而现在是用 <b>IP 地址</b> 访问的。';

/**
 * 切出 passkeyBlock 与 registerBlockedReason 真执行，画进一只游离的 host
 *
 * el／note 按 setup.js 里的形状给最小桩。passkeys.js 里还没有 registerBlockedReason 时不补定义：
 * 旧写法照常跑，量出来的是「进页没判」这个行为，而不是切段失败。
 * 判定是进页后发出去的一问，等它回来（一轮 setImmediate）再读按钮与说明。
 * @param locked 这台机器上没上锁
 * @param supported 浏览器给没给通行密钥接口
 * @param reply 列表那一条的回包；给一个 Error 就当这一问取不到回包
 */
async function render(locked, supported, reply) {
  const block = bracedFrom(setupSrc, 'function passkeyBlock');
  if (!block) throw new Error('切段失败：passkeyBlock');
  const reason = bracedFrom(passkeysSrc, 'async function registerBlockedReason');
  const asked = [];
  const api = async path => {
    asked.push(path);
    if (reply instanceof Error) throw reply;
    return reply;
  };
  const el = (tag, cls) => {
    const node = element(tag);
    if (cls) node.className = cls;
    return node;
  };
  const note = (kind, text) => {
    const node = el('div', 'su-note ' + (kind || ''));
    node.textContent = text;
    return node;
  };
  const host = element('div');
  new Function('api', 'el', 'note', 'draft', 'registerPasskey', 'supported', 'host',
    reason + '\n' + block + '\npasskeyBlock(host);')(
    api, el, note, {locked}, async () => {}, () => supported, host);
  await new Promise(resolve => setImmediate(resolve));

  const box = host.children[0];
  const button = box.children.find(node => node.tagName === 'button');
  const hint = box.children.find(node => String(node.className).includes('su-note'));
  return {disabled: button.disabled, note: hint.textContent, asked: asked.length};
}

/**
 * 设置卡在浏览器没给接口时印的那句：切出 loadPasskeys 真执行，取列表盒子里的字面
 *
 * 登记钮沿用这一句，⑤ 量的是两处一字不差，夹具里不另抄一份原句。
 */
async function cardUnsupportedText() {
  const load = bracedFrom(passkeysSrc, 'async function loadPasskeys');
  if (!load) throw new Error('切段失败：loadPasskeys');
  const box = element('div');
  await new Function('supported', load + '\nreturn loadPasskeys;')(() => false)(box, element('button'));
  return box.textContent;
}

// ① 上了锁、浏览器没给接口（非安全上下文）：进页就置灰，说明换成「用不了」那句
let q1 = 'missing';
try {
  const got = await render(true, false, {usable: true, passkeys: []});
  q1 = {disabled: got.disabled, told: got.note.includes('用不了通行密钥')};
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, {disabled: true, told: true}, '① 上了锁、浏览器没给接口：进页置灰，说明换成用不了的原因');

// ② 上了锁、浏览器给了接口、服务端说这个地址用不了：进页就置灰，说明换成服务端那句原因
let q2 = 'missing';
try {
  const got = await render(true, true, {usable: false, unusableReason: REASON, passkeys: []});
  q2 = {disabled: got.disabled, note: got.note};
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, {disabled: true, note: REASON}, '② 上了锁、服务端说这个地址用不了：进页置灰，说明换成服务端的原因句');

// ③ 阳性对照：上了锁、服务端说用得了，或回包里没有 usable 这个键：照旧可点，说明不换
let q3 = 'missing';
try {
  const open = await render(true, true, {usable: true, passkeys: []});
  const noKey = await render(true, true, {passkeys: []});
  q3 = {
    usable: {disabled: open.disabled, noteKept: open.note.includes(LOCKED_NOTE)},
    noUsableKey: {disabled: noKey.disabled, noteKept: noKey.note.includes(LOCKED_NOTE)},
  };
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, {usable: {disabled: false, noteKept: true}, noUsableKey: {disabled: false, noteKept: true}},
  '③ 上了锁、服务端说用得了（或没带 usable）：照旧可点，说明不换');

// ④ 没上锁：照旧置灰、说「先上锁」，不去问服务端——服务端就算说这个地址用不了，也不把那句盖上去
let q4 = 'missing';
try {
  const got = await render(false, true, {usable: false, unusableReason: REASON, passkeys: []});
  q4 = {disabled: got.disabled, noteKept: got.note.includes(UNLOCKED_NOTE), asked: got.asked};
} catch (e) {
  q4 = 'error:' + e.message;
}
eq(q4, {disabled: true, noteKept: true, asked: 0}, '④ 没上锁：照旧置灰、说先上锁，不去问服务端');

// ⑤ 上了锁、浏览器没给接口：说明与设置卡那句一字不差，也不去问服务端——服务端就算说这个地址用不了，也不问
let q5 = 'missing';
let card = '';
try {
  card = await cardUnsupportedText();
  const got = await render(true, false, {usable: false, unusableReason: REASON, passkeys: []});
  q5 = {note: got.note, asked: got.asked};
} catch (e) {
  q5 = 'error:' + e.message;
}
eq(q5, {note: card, asked: 0}, '⑤ 上了锁、浏览器没给接口：说明沿用设置卡原句，不发请求');

// ⑥ 上了锁、服务端说用不了却没给原因句：进页置灰，说明换成兜底句
let q6 = 'missing';
try {
  const got = await render(true, true, {usable: false, unusableReason: '', passkeys: []});
  q6 = {disabled: got.disabled, note: got.note};
} catch (e) {
  q6 = 'error:' + e.message;
}
eq(q6, {disabled: true, note: FALLBACK}, '⑥ 上了锁、服务端说用不了而原因句为空：进页置灰，说明换成兜底句');

// ⑦ 上了锁、列表那一问取不到回包：照旧可点，说明不换
let q7 = 'missing';
try {
  const got = await render(true, true, new Error('模拟取列表失败'));
  q7 = {disabled: got.disabled, noteKept: got.note.includes(LOCKED_NOTE), asked: got.asked};
} catch (e) {
  q7 = 'error:' + e.message;
}
eq(q7, {disabled: false, noteKept: true, asked: 1}, '⑦ 上了锁、取不到回包：照旧可点，说明不换');

// ⑧ 原因句里带标签：说明照字面显示，标签原样可见——走 textContent，不当 HTML 解析
let q8 = 'missing';
try {
  const got = await render(true, true, {usable: false, unusableReason: MARKED_REASON, passkeys: []});
  q8 = {disabled: got.disabled, note: got.note};
} catch (e) {
  q8 = 'error:' + e.message;
}
eq(q8, {disabled: true, note: MARKED_REASON}, '⑧ 原因句带标签：说明照字面显示，标签不被当成 HTML');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
