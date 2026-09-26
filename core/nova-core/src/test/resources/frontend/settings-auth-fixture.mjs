/**
 * 二次验证开关：开完或关完，签发只读口令的表单要立刻按新状态画验证码栏
 *
 * settle 若只改卡片自己的 authState.totpEnabled，不写 store.totpRequired，
 * 签发表单仍按进页时那一份画：刚绑上会少一格，刚关掉会多要一格。
 * 签发表单读的是 store.totpRequired，不是 authState 那一位。
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 由 SettingsAuthViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

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

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个空行」。
 */
function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

const settleSrc = bracedFrom(read('settings-auth.js'), 'const settle = state =>');
eq(settleSrc.length > 0, true, '找得到 settle');
eq(settleSrc.includes('store.totpRequired'), true, 'settle 文本含 store.totpRequired');

function runSettle(state) {
  const store = {};
  const authState = {};
  const input = {checked: false};
  const text = {textContent: ''};
  const flow = {innerHTML: ''};
  const settle = new Function('store', 'authState', 'input', 'text', 'flow',
    settleSrc + '\nreturn settle;')(store, authState, input, text, flow);
  settle(state);
  return store.totpRequired;
}

let qTrue = 'missing';
try {
  qTrue = runSettle(true);
} catch (e) {
  qTrue = 'error:' + e.message;
}
eq(qTrue, true, 'settle(true) 后 store.totpRequired === true');

let qFalse = 'missing';
try {
  qFalse = runSettle(false);
} catch (e) {
  qFalse = 'error:' + e.message;
}
eq(qFalse, false, 'settle(false) 后 store.totpRequired === false');

const form = bracedFrom(read('tokens.js'), 'function issueFormHtml');
eq(form.length > 0, true, '找得到签发表单');

function runIssueForm(totpRequired) {
  const store = {totpRequired};
  const issueFormHtml = new Function('store', form + '\nreturn issueFormHtml;')(store);
  return issueFormHtml();
}

let qFormOn = 'missing';
try {
  const html = runIssueForm(true);
  qFormOn = html.includes('id="tk-code"') && html.includes('动态验证码');
} catch (e) {
  qFormOn = 'error:' + e.message;
}
eq(qFormOn, true, 'store.totpRequired===true 签发表单含验证码栏');

let qFormOff = 'missing';
try {
  const html = runIssueForm(false);
  qFormOff = !html.includes('id="tk-code"');
} catch (e) {
  qFormOff = 'error:' + e.message;
}
eq(qFormOff, true, 'store.totpRequired===false 签发表单不含验证码栏');

/**
 * 从拼出来的表单里取出含 marker 的那一个 input 标签
 * @param html 签发表单
 * @param marker 标签里能认得出它的那一段
 * @return 整个标签；没有时为空串
 */
function inputTag(html, marker) {
  const re = /<input\b[^>]*>/g;
  let found;
  while ((found = re.exec(html))) {
    if (found[0].includes(marker)) return found[0];
  }
  return '';
}

/** 取标签上的属性；没写这个属性时为空串 */
function attr(tag, name) {
  const found = tag.match(new RegExp('\\b' + name + '="([^"]*)"'));
  return found ? found[1] : '';
}

let issueHtml = '';
try {
  issueHtml = runIssueForm(false);
} catch (e) {
  issueHtml = '';
}
const passTag = inputTag(issueHtml, 'id="tk-pass"');
const whoTag = inputTag(issueHtml, 'id="tk-label"');
const userTag = inputTag(issueHtml, 'autocomplete="username"');
eq(attr(passTag, 'autocomplete'), 'current-password',
  '签发页密码框 autocomplete 是 current-password');
eq(userTag.length > 0 && attr(userTag, 'name') === 'username'
  && /\bhidden\b/.test(attr(userTag, 'class'))
  && !userTag.includes('id="tk-label"')
  && issueHtml.indexOf(userTag) < issueHtml.indexOf(passTag),
  true, '密码框前面有一个隐藏的用户名框');
eq(attr(whoTag, 'name') !== 'username' && attr(whoTag, 'autocomplete') !== 'username',
  true, '「签给谁」不会被当成登录名');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
