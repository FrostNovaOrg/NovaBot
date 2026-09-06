/**
 * 口令框显示／隐藏的夹具
 *
 * 量的是 config-ui/password-reveal.js：默认藏着、点一下切换、再点切回，
 * 登录页一口、改口令三栏、设置页机密行、签发口令页共用同一份构件。
 * 六处各自一份状态，互不牵连——登录页点了「显示」不该把别处一起揭开。
 *
 * 由 PasswordRevealModelTest 拉起。引用的是源码树里那一份，也是登录页拼进去的同一份字节。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {passwordReveal, togglePasswordReveal, PASSWORD_REVEAL_SITES,
  bindPasswordReveal, boundPasswordRevealIds}
  from '../../../main/resources/config-ui/password-reveal.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function view(revealed) {
  const s = passwordReveal(revealed);
  return {revealed: s.revealed, type: s.inputType, label: s.buttonLabel};
}

// ---------- 一、默认隐藏 ----------
eq(view(false), {revealed: false, type: 'password', label: '显示'}, '默认隐藏');
eq(view(undefined), {revealed: false, type: 'password', label: '显示'}, '没给这一位时默认隐藏');
eq(view(null), {revealed: false, type: 'password', label: '显示'}, 'null 默认隐藏');
eq(view(0), {revealed: false, type: 'password', label: '显示'}, '0 默认隐藏');

// ---------- 二、切换、再切回 ----------
eq(view(true), {revealed: true, type: 'text', label: '隐藏'}, '揭开后是明文');
eq(view(togglePasswordReveal(passwordReveal(false)).revealed),
  {revealed: true, type: 'text', label: '隐藏'}, '点一下从隐藏切到显示');
eq(view(togglePasswordReveal(passwordReveal(true)).revealed),
  {revealed: false, type: 'password', label: '显示'}, '再点从显示切回隐藏');
eq(togglePasswordReveal(togglePasswordReveal(passwordReveal(false))),
  passwordReveal(false), '切两下回到原样');

// ---------- 三、六处共用同一份构件，状态各自独立 ----------
// 逐处 toggle 一次，只有它自己翻面。构造后从不切换的那一版，五格恒真。
for (const site of PASSWORD_REVEAL_SITES) {
  const local = {};
  for (const other of PASSWORD_REVEAL_SITES) local[other] = passwordReveal(false);
  local[site] = togglePasswordReveal(local[site]);
  eq(view(local[site].revealed), {revealed: true, type: 'text', label: '隐藏'}, site + ' 揭开');
  for (const other of PASSWORD_REVEAL_SITES) {
    if (other === site) continue;
    eq(view(local[other].revealed), {revealed: false, type: 'password', label: '显示'},
      site + ' 揭开不带动 ' + other);
  }
}

// ---------- 四、运行期登记：最小桩 DOM 触发一次渲染 ----------
const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => readFileSync(join(ui, name), 'utf8');

function wiredSites() {
  const sites = [];
  if (/\bbindPasswordReveal\s*\(/.test(read('login.html'))) sites.push('login');
  const auth = read('settings-auth.js');
  const attachUsesBind = /function\s+attachEye[\s\S]*?\bbindPasswordReveal\s*\(/.test(auth);
  if (attachUsesBind && auth.includes('attachEye(current)')
      && auth.includes("currentEye.id = 'pwd-current-reveal'")) sites.push('pwd-current');
  if (attachUsesBind && auth.includes('attachEye(next)')
      && auth.includes("nextEye.id = 'pwd-next-reveal'")) sites.push('pwd-next');
  if (attachUsesBind && auth.includes('attachEye(again)')
      && auth.includes("againEye.id = 'pwd-again-reveal'")) sites.push('pwd-again');
  if (/\bbindPasswordReveal\s*\(/.test(read('settings.js'))) sites.push('settings');
  if (/\bbindPasswordReveal\s*\(/.test(read('tokens.js'))) sites.push('tokens');
  return sites;
}

function stubPair(id) {
  const listeners = {};
  const input = {type: 'password', disabled: false};
  const button = {
    id,
    type: 'button',
    disabled: false,
    textContent: '',
    setAttribute() {},
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    click() {
      for (const fn of listeners.click || []) fn();
    },
  };
  return {input, button};
}

const pairs = {};
for (const site of wiredSites()) {
  pairs[site] = stubPair(site);
  bindPasswordReveal(pairs[site].input, pairs[site].button);
}
eq(boundPasswordRevealIds().slice().sort(), PASSWORD_REVEAL_SITES.slice().sort(),
  '登记表＝闭集');

for (const site of PASSWORD_REVEAL_SITES) {
  const pair = pairs[site];
  eq(!!pair, true, site + ' 已接线');
  if (!pair) continue;
  pair.button.click();
  eq(pair.input.type, 'text', site + ' 点一下变明文');
  for (const other of PASSWORD_REVEAL_SITES) {
    if (other === site || !pairs[other]) continue;
    eq(pairs[other].input.type, 'password', site + ' 不带动 ' + other);
  }
  pair.button.click();
  eq(pair.input.type, 'password', site + ' 再点切回');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
