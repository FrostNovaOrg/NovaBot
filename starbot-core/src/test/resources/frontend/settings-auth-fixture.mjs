/**
 * 二次验证开关：开完或关完，签发只读口令的表单要立刻按新状态画验证码栏
 *
 * settle 若只改卡片自己的 authState.totpEnabled，不写 store.totpRequired，
 * 签发表单仍按进页时那一份画：刚绑上会少一格，刚关掉会多要一格。
 * 签发表单读的是 store.totpRequired，不是 authState 那一位。
 *
 * 切段按花括号配平截到块尾。由 SettingsAuthViewTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
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

const settle = bracedFrom(read('settings-auth.js'), 'const settle = state =>');
eq(settle.length > 0, true, '找得到 settle');
eq(settle.includes('store.totpRequired'), true, 'settle 回灌 store.totpRequired');

const form = bracedFrom(read('tokens.js'), 'function issueFormHtml');
eq(form.length > 0, true, '找得到签发表单');
eq(form.includes('store.totpRequired'), true, '签发表单读 store.totpRequired');
eq(form.includes('authState.totpEnabled'), false, '签发表单不读 authState.totpEnabled');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
