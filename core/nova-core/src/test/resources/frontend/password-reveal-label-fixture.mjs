/**
 * 读屏标签按字段说名词的夹具
 *
 * 量的是 bindPasswordReveal 的名词参数与四个调用点各传各的名词：
 * 签发口令页念「口令」、登录页与改密码三栏念「密码」、设置页机密行念该行自己的标签名。
 * 读屏用户听到的名词要与页面上管它叫什么对得上——对上之后，
 * 视力正常的开发者在屏幕上仍然看不出任何变化，因此这一组只能靠夹具钉住。
 *
 * 由 PasswordRevealLabelTest 拉起。引用的是源码树里那一份，
 * 也是登录页服务端拼接与设置页 import 的同一份字节。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {bindPasswordReveal} from '../../../main/resources/config-ui/password-reveal.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => readFileSync(join(ui, name), 'utf8');

/** 桩 DOM：把 setAttribute 记下来，接线层只碰这两样 */
function stub() {
  const attrs = {};
  const listeners = {};
  const input = {type: 'password', disabled: false};
  const button = {
    textContent: '',
    dataset: {},
    setAttribute(name, value) {
      attrs[name] = value;
    },
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    click() {
      for (const fn of listeners.click || []) fn();
    },
  };
  return {input, button, attrs};
}

/** 绑上之后、没点之前念什么 */
function labelOf(noun) {
  const {input, button, attrs} = stub();
  if (noun === undefined) {
    bindPasswordReveal(input, button);
  } else {
    bindPasswordReveal(input, button, noun);
  }
  return attrs['aria-label'];
}

/** 点一下揭开之后念什么 */
function labelAfterClick(noun) {
  const {input, button, attrs} = stub();
  if (noun === undefined) {
    bindPasswordReveal(input, button);
  } else {
    bindPasswordReveal(input, button, noun);
  }
  button.click();
  return attrs['aria-label'];
}

// ---------- 一、登录页与改密码三栏：不传名词，念「密码」 ----------
eq(labelOf(undefined), '显示密码', '读屏用户在登录页听到「显示密码」');
eq(labelAfterClick(undefined), '隐藏密码', '登录页揭开后念「隐藏密码」');

// ---------- 二、签发口令页：传「口令」 ----------
eq(labelOf('口令'), '显示口令', '读屏用户在签发口令页听到「显示口令」');
eq(labelAfterClick('口令'), '隐藏口令', '签发口令页揭开后念「隐藏口令」');

// ---------- 三、设置页机密行：传该行自己的标签名 ----------
eq(labelOf('二次验证密钥'), '显示二次验证密钥', '读屏用户在设置页机密行听到的是该行的名字（二次验证密钥）');
eq(labelOf('控制台访问令牌'), '显示控制台访问令牌', '读屏用户在设置页机密行听到的是该行的名字（控制台访问令牌）');
eq(labelAfterClick('二次验证密钥'), '隐藏二次验证密钥', '设置页机密行揭开后念「隐藏」加该行名字');

// 空名词退回「密码」：标签缺了不许念出 undefined
eq(labelOf(''), '显示密码', '名词为空串时退回「密码」');
eq(labelOf(null), '显示密码', '名词为 null 时退回「密码」');

// ---------- 四、四个调用点各传各的名词 ----------
const tokens = read('tokens.js');
const settings = read('settings.js');
const login = read('login.html');
const auth = read('settings-auth.js');

eq(/bindPasswordReveal\s*\(\s*field\s*,\s*\$\(\s*'#tk-reveal'\s*\)\s*,\s*'口令'\s*\)/.test(tokens),
  true, 'tokens.js:签发口令页调用点传「口令」');
eq(/bindPasswordReveal\s*\(\s*input\s*,\s*eye\s*,\s*field\.label\s*\)/.test(settings),
  true, 'settings.js:设置页机密行调用点传该行标签名');
eq(/bindPasswordReveal\s*\(\s*\$\(\s*'#password'\s*\)\s*,\s*\$\(\s*'#password-reveal'\s*\)\s*\)/.test(login),
  true, 'login.html:登录页调用点用默认名词');
eq(/bindPasswordReveal\s*\(\s*input\s*,\s*eye\s*\)/.test(auth),
  true, 'settings-auth.js:改密码三栏调用点用默认名词');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
