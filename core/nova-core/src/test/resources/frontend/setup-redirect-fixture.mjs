/**
 * 进首页仍转初始设置
 *
 * 五步一件都还没做、正停在首页、没点过「稍后再说」时，才把地址改成 #/setup。
 * main.js 顶层碰 DOM，node 里 import 不动，只能切 considerSetupRedirect 真执行。
 *
 * 由 SetupRedirectTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const src = readFileSync(join(dirname(fileURLToPath(import.meta.url)),
  '../../../main/resources/config-ui/main.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 从 marker 起按花括号配平截到块尾（含声明本身）。字符串与注释里的花括号不算层 */
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

const body = bracedFrom(src, 'export function considerSetupRedirect');

function run(open, name, later) {
  const location = {hash: '#/home'};
  const parseHash = () => ({name});
  const shouldOpenSetup = () => open;
  const make = new Function('location', 'parseHash', 'shouldOpenSetup', 'later',
    'let homeStatus = null;\nlet homeLogin = null;\n'
    + body.replace('export function', 'function')
    + '\nreturn considerSetupRedirect;');
  const fn = make(location, parseHash, shouldOpenSetup, later);
  const ret = fn({}, {});
  return {hash: location.hash, ret};
}

eq(run(true, 'home', false), {hash: '#/setup', ret: true},
  '① 该开向导且停在首页且没点稍后再说：转到 #/setup');
eq(run(true, 'settings', false), {hash: '#/home', ret: false},
  '② 不在首页：hash 不动');
eq(run(true, 'home', true), {hash: '#/home', ret: false},
  '③ 点过稍后再说：hash 不动');
eq(run(false, 'home', false), {hash: '#/home', ret: false},
  '④ 不该开向导：hash 不动');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
