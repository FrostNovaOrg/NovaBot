/**
 * 口令框显示／隐藏的夹具
 *
 * 量的是 config-ui/password-reveal.js：默认藏着、点一下切换、再点切回，
 * 登录页一口、改口令三栏、设置页机密行、签发口令页共用同一份构件。
 * 六处各自一份状态，互不牵连——登录页点了「显示」不该把别处一起揭开。
 *
 * 由 PasswordRevealModelTest 拉起。引用的是源码树里那一份，也是登录页拼进去的同一份字节。
 */

import {passwordReveal, togglePasswordReveal, PASSWORD_REVEAL_SITES}
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
// 落点闭集改由 login-reveal-wiring-fixture 按源码计数，不在这里对一份自写的名单。
const states = {};
for (const site of PASSWORD_REVEAL_SITES) states[site] = passwordReveal(false);
states.login = togglePasswordReveal(states.login);
eq(view(states.login.revealed), {revealed: true, type: 'text', label: '隐藏'}, '登录页揭开');
eq(view(states['pwd-current'].revealed), {revealed: false, type: 'password', label: '显示'},
  '登录页揭开不带动现在的口令');
eq(view(states['pwd-next'].revealed), {revealed: false, type: 'password', label: '显示'},
  '登录页揭开不带动新口令');
eq(view(states['pwd-again'].revealed), {revealed: false, type: 'password', label: '显示'},
  '登录页揭开不带动再输一遍');
eq(states.settings ? view(states.settings.revealed) : null,
  {revealed: false, type: 'password', label: '显示'},
  '登录页揭开不带动设置页机密行');
eq(states.tokens ? view(states.tokens.revealed) : null,
  {revealed: false, type: 'password', label: '显示'},
  '登录页揭开不带动签发口令页');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
