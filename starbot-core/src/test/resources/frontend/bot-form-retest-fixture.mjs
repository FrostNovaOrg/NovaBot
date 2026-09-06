/**
 * 连接页机器人表单：改了地址、端口或 Token 后，先前测通必须作废
 *
 * 测通后「保存」已解锁，再改格子里的值、不点「测试连接」就保存，
 * 写进文件的必须不能还是没测过的参数。测试请求抛错时也不得留着上次的可保存。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 BotFormRetestTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'bot.js'), 'utf8');

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

function fakeDom() {
  const byId = {};
  function el() {
    return {
      disabled: false,
      value: '',
      textContent: '',
      className: '',
      listeners: {},
      addEventListener(type, fn) {
        if (!this.listeners[type]) this.listeners[type] = [];
        this.listeners[type].push(fn);
      }
    };
  }
  function $(sel) {
    const id = String(sel).replace(/^#/, '');
    if (!byId[id]) byId[id] = el();
    return byId[id];
  }
  return {$, byId};
}

const FIELDS = ['addr', 'hport', 'htoken', 'wport', 'wtoken'];

// ① 切出 invalidateBotForm 真执行：save.disabled 由 false→true 且 out 含「重新测试连接」
let q1 = 'missing';
try {
  const body = bracedFrom(src, 'function invalidateBotForm');
  if (!body) throw new Error('no function');
  const {$, byId} = fakeDom();
  const fn = new Function('$', body + '\nreturn invalidateBotForm;')($);
  const save = $('#bot-save');
  save.disabled = false;
  fn('bot');
  q1 = save.disabled === true
    && String(byId['bot-out'].textContent).includes('重新测试连接');
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, '① invalidateBotForm：save.disabled 由 false→true 且 out 含「重新测试连接」');

// ② 切出 bindBotForm 真执行：五格各挂一个 input，触发任一个后 save.disabled===true
let q2 = 'missing';
try {
  const inv = bracedFrom(src, 'function invalidateBotForm');
  const bind = bracedFrom(src, 'function bindBotForm');
  if (!bind) throw new Error('no bindBotForm');
  const {$, byId} = fakeDom();
  const fn = new Function('$', (inv || '') + '\n' + bind + '\nreturn bindBotForm;')($);
  fn('bot');
  const eachOne = FIELDS.every(k => (byId['bot-' + k]?.listeners?.input || []).length === 1);
  if (!eachOne) {
    q2 = false;
  } else {
    byId['bot-save'].disabled = false;
    byId['bot-addr'].listeners.input[0]();
    q2 = byId['bot-save'].disabled === true;
  }
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, '② 五格各挂一个 input，触发任一个后 save.disabled===true');

// ③ 文本断言：testBotConnection 块的 catch 段内含 -save').disabled = true
let q3 = 'missing';
try {
  const testFn = bracedFrom(src, 'function testBotConnection');
  const caught = bracedFrom(testFn, 'catch');
  q3 = caught.includes("-save').disabled = true");
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, "③ testBotConnection 的 catch 段内含 -save').disabled = true");

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
