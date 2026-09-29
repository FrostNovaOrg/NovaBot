/**
 * 使用协议那一行的时间：到分钟，不带 T，和同一屏通行密钥表同一种写法
 *
 * 配置里那一行是机器写法，带 T、带秒，还可能带时区尾。句子里应换成到分钟的写法。
 * 读不懂的原值照原样留在句子里，不报错。
 *
 * 夹具值不带时区尾，钟点按本机当地时间解析，断言不随跑测试那台机器的时区变。
 * 只认写法：不带 T、不到秒、钟点到分钟还在。不把整句钉死成某一种地区格式。
 *
 * 切段按花括号配平截到块尾后真执行。由 SettingsAgreementTimeTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-auth.js'), 'utf8');

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
 * 字符串与注释里的花括号不算层。
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

function constLine(name) {
  const marker = 'const ' + name + ' = ';
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const end = src.indexOf('\n', start);
  return src.slice(start, end < 0 ? src.length : end);
}

const channelSrc = bracedFrom(src, 'function channelText');
const lineSrc = bracedFrom(src, 'function acceptedLine');
const timeSrc = bracedFrom(src, 'function agreementTime');
eq(channelSrc.includes('password') && lineSrc.includes('时间') && constLine('AGREEMENT_TIME_KEY').includes('accepted-at'),
  true, '切得到通道人话、同意那一行和三个键');

function load(store) {
  return new Function('store',
    constLine('AGREEMENT_VERSION_KEY') + '\n'
    + constLine('AGREEMENT_TIME_KEY') + '\n'
    + constLine('AGREEMENT_BY_KEY') + '\n'
    + channelSrc + '\n'
    + timeSrc + '\n'
    + lineSrc + '\nreturn acceptedLine;')(store);
}

const store = {values: {}};
const acceptedLine = load(store);

store.values['novabot.core.config-ui.agreement.accepted-version'] = '1';
store.values['novabot.core.config-ui.agreement.accepted-at'] = '2026-09-30T03:18:34';
store.values['novabot.core.config-ui.agreement.accepted-by'] = 'password';
const shown = acceptedLine();
console.log('协议时间行：' + shown);
eq(shown.includes('2026-09-30T03:18:34'), false, '不再把机器格式原样拼进句子');
eq(shown.includes('T'), false, '时间里不带 T');
eq(/\d:\d\d:\d\d/.test(shown), false, '时间不到秒');
eq(/0?3:18/.test(shown), true, '钟点到分钟还在');
eq(shown.includes('2026') && shown.includes('30'), true, '年月日还在');
eq(shown.includes('已同意第 1 版'), true, '版次还在');
eq(shown.includes('输密码登录之后点的。'), true, '通道仍是人话');

store.values['novabot.core.config-ui.agreement.accepted-at'] = '不是时间';
let raw = 'missing';
try {
  raw = acceptedLine();
} catch (e) {
  raw = 'error:' + e.message;
}
eq(typeof raw === 'string' && raw.includes('不是时间'), true, '读不懂的原值照原样显示，不报错');
eq(raw.includes('已同意第 1 版'), true, '读不懂时版次仍在');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
