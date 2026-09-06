/**
 * 重进初始设置时，机器人草稿须回到与盘上一致
 *
 * 切出再回来时 Token 格不得留着没存过的输入，否则既成事实会把先前测通翻回有效，
 * 「下一步」放行、屏幕上的 Token 却从未存过。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 SetupBotSyncTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const setupSrc = readFileSync(join(ui, 'setup.js'), 'utf8');

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

function loadSync() {
  const body = bracedFrom(setupSrc, 'function syncBotDraft');
  if (!body) throw new Error('no function');
  return new Function(body + '\nreturn syncBotDraft;')();
}

function seed() {
  return {
    bot: {
      address: '1.1.1.1', httpPort: '1', wsPort: '2',
      httpToken: 'old', wsToken: 'old',
    },
  };
}

// ① 切出 syncBotDraft 真执行：已配过则回填地址端口并清空 Token
let after = 'missing';
try {
  const fn = loadSync();
  const draft = seed();
  fn(draft, {configured: true, address: '10.0.0.1', httpPort: 3000, websocketPort: 3001});
  after = draft.bot;
} catch (e) {
  after = 'error:' + e.message;
}
eq(after, {
  address: '10.0.0.1', httpPort: '3000', wsPort: '3001', httpToken: '', wsToken: '',
}, '① configured 回填地址端口并清空 Token');

// ② 阳性对照：未配过则五格逐字不变
let untouched = 'missing';
try {
  const fn = loadSync();
  const draft = seed();
  fn(draft, {configured: false});
  untouched = draft.bot;
} catch (e) {
  untouched = 'error:' + e.message;
}
eq(untouched, seed().bot, '② configured:false 五格逐字不变');

// ③ 文本断言：openSetup 块含 syncBotDraft(draft, bot) 且不再含 draft.bot.address =
const open = bracedFrom(setupSrc, 'function openSetup');
eq(
  open.includes('syncBotDraft(draft, bot)') && !open.includes('draft.bot.address ='),
  true,
  '③ openSetup 含 syncBotDraft(draft, bot) 且不再含 draft.bot.address =');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
