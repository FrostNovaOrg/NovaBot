/**
 * 登录页锁定接线与机密按钮样式、加主播空输入句的夹具
 *
 * 量四件事，都是源码里写了才成立、漏写页面上照样能用：
 *   锁定时「显示」是否跟着禁（paint 漏了这一颗，锁定期仍能揭开口令）；
 *   登录页内联 .secret button 与 app.css 那一份数字是否相同；
 *   加主播空输入句与 placeholder 是否同一条常量；
 *   眼睛接线落点数是否等于闭集长度，改口令三栏是否真接到共用构件。
 *
 * 由 LoginRevealWiringTest 拉起。引用的是源码树里那几份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {PASSWORD_REVEAL_SITES} from '../../../main/resources/config-ui/password-reveal.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => readFileSync(join(ui, name), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

const login = read('login.html');
const paintStart = login.indexOf('function paint()');
const paintEnd = login.indexOf('function tick()');
eq(paintStart >= 0 && paintEnd > paintStart, true, 'login.html 里找得到 paint');
const paint = paintStart >= 0 && paintEnd > paintStart ? login.slice(paintStart, paintEnd) : '';
eq(paint.includes("$('#password-reveal').disabled = view.disabled"), true,
  '锁定时眼睛随 locked 禁');

function secretButtonProps(css) {
  const match = css.match(/(?:^|[\n}])\s*\.secret button\s*\{([^}]+)\}/);
  if (!match) return null;
  const out = {};
  for (const part of match[1].split(';')) {
    const cut = part.indexOf(':');
    if (cut < 0) continue;
    const name = part.slice(0, cut).trim();
    const value = part.slice(cut + 1).trim();
    if (name) out[name] = value;
  }
  return out;
}

const loginSecret = secretButtonProps(login);
const appSecret = secretButtonProps(read('app.css'));
eq(!!loginSecret && !!appSecret, true, '两份 .secret button 都找得到');
eq(loginSecret, appSecret, '.secret button 数值与 app.css 相同');

const push = read('push.js');
eq(/export const STREAMER_INPUT_HINT\s*=/.test(push), true, 'push.js 导出 STREAMER_INPUT_HINT');
eq(push.includes("placeholder = '输入 ' + STREAMER_INPUT_HINT"), true,
  'placeholder 用同一条常量');
eq(push.includes("'请先输入 ' + STREAMER_INPUT_HINT"), true, '空输入句用同一条常量');
eq(push.includes('请先输入 uid、直播间号或链接'), false, '旧空输入句已撤');

const settings = read('settings.js');
const tokens = read('tokens.js');
eq(settings.includes("from './password-reveal.js'"), true, 'settings.js 接到共用构件');
eq(tokens.includes("from './password-reveal.js'"), true, 'tokens.js 接到共用构件');
eq(settings.includes('bindPasswordReveal'), true, 'settings.js 调用 bindPasswordReveal');
eq(tokens.includes('bindPasswordReveal'), true, 'tokens.js 调用 bindPasswordReveal');
eq((settings.match(/type === 'password'/g) || []).length, 0,
  'settings.js 不再自写 type === password 切换');
eq((tokens.match(/type === 'password'/g) || []).length, 0,
  'tokens.js 不再自写 type === password 切换');

function dropCount(src) {
  // attachEye 体内那一次 bindPasswordReveal 是共用构件的接线，不是第六处落点
  const withoutHelper = src.replace(
    /function\s+attachEye\s*\([^)]*\)\s*\{[\s\S]*?\n\}/,
    '',
  );
  const binds = withoutHelper.match(/\bbindPasswordReveal\s*\(/g) || [];
  const eyes = withoutHelper.match(/\battachEye\s*\(/g) || [];
  return binds.length + eyes.length;
}

const auth = read('settings-auth.js');
const drops = dropCount(read('login.html'))
  + dropCount(settings)
  + dropCount(tokens)
  + dropCount(auth);
eq(drops, PASSWORD_REVEAL_SITES.length,
  '四份界面的 bindPasswordReveal／attachEye 落点数＝闭集长度');
eq(/function\s+attachEye[\s\S]*?\bbindPasswordReveal\s*\(/.test(auth), true,
  'attachEye 接到共用构件');
eq(auth.includes('attachEye(current)') && auth.includes("currentEye.id = 'pwd-current-reveal'"),
  true, 'pwd-current 经 attachEye 接线');
eq(auth.includes('attachEye(next)') && auth.includes("nextEye.id = 'pwd-next-reveal'"),
  true, 'pwd-next 经 attachEye 接线');
eq(auth.includes('attachEye(again)') && auth.includes("againEye.id = 'pwd-again-reveal'"),
  true, 'pwd-again 经 attachEye 接线');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
