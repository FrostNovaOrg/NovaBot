import {botFieldsHtml} from '../../../main/resources/config-ui/bot-fields.js';
import {MiniNode} from './layout-dom.mjs';
/**
 * 初始设置第 2 步：测试连接还没回来时改了参数，旧结果不得存下改过的参数
 *
 * 用户故障：点了测试连接，回包前把地址改错；旧的成功结果一回来，
 * 这一步就把错地址存进配置并当场切过去，机器人断线。
 * 切出 stepBot 与它用到的草稿真执行：点「测试连接」、改格子、让接口回包都真走一遍，
 * 量有没有发保存请求、这一步算不算测通。对照：回包前没动，测通照旧自动存下。
 *
 * 由 SetupBotStaleResultTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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

function fakeEl(cls) {
  const node = new MiniNode('div');
  node.className = cls || '';
  return node;
}

/** 草稿取源码里那一份初值；页面零件与接口换成桩 */
function loadStep() {
  const pieces = [
    bracedFrom(setupSrc, 'const draft'),
    bracedFrom(setupSrc, 'function botSnapshot'),
    bracedFrom(setupSrc, 'function invalidateBot'),
    bracedFrom(setupSrc, 'function stepBot'),
  ];
  if (!pieces.every(Boolean)) throw new Error('no function');

  const made = [];
  const inputs = {};
  const calls = [];
  let release = null;
  const deps = {
    botFieldsHtml,
    seen: {status: {senders: [{}]}},
    el: (tag, cls) => {
      const node = fakeEl(cls);
      made.push(node);
      return node;
    },
    heading: () => {},
    titleOf: () => '',
    term: (key, fallback) => fallback,
    phrase: (key, fn, fallback) => fallback,
    note: () => fakeEl(),
    foot: () => {},
    syncFoot: () => {},
    refreshFacts: async () => {},
    report: (box, res) => {
      box.textContent = res.message;
      box.className = 'su-r ' + (res.success ? 'ok' : 'err');
    },
    api: async (path, opts) => {
      calls.push({path, body: opts && opts.body ? JSON.parse(opts.body) : null});
      if (path === '/setup/test-bot') {
        return new Promise(resolve => { release = resolve; });
      }
      return {success: true, message: '已接上'};
    },
  };
  const names = Object.keys(deps);
  const run = new Function(...names,
    pieces.join(';\n') + '\nreturn {draft, stepBot};')(...names.map(n => deps[n]));
  run.stepBot(fakeEl());
  for (const node of made) {
    for (const input of node.querySelectorAll('input')) {
      inputs[input.id] = value => {
        input.value = value;
        input.dispatchEvent({type: 'input', target: input});
      };
    }
  }

  const test = made.find(node => node.id === 'setup-test-bot');
  const result = made.find(node => node.className === 'su-r');
  return {draft: run.draft, inputs, calls, test, result, answer: res => release(res)};
}

function click(node) {
  return Promise.all((node.listeners.click || []).map(fn => fn()));
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

// ① 回包前改了地址，旧的成功回包不得发保存，这一步仍算没测通
let stale = 'missing';
try {
  const page = loadStep();
  const pending = click(page.test);
  await flush();
  page.inputs['setup-addr']('10.0.0.99');
  page.answer({success: true, message: '连接正常', advice: ''});
  await pending;
  stale = {
    saves: page.calls.filter(c => c.path === '/setup/bot').length,
    botOk: page.draft.botOk,
    result: page.result.textContent,
  };
} catch (e) {
  stale = 'error:' + e.message;
}
eq(stale, {saves: 0, botOk: false, result: '连接参数已修改，请重新测试连接'},
  '① 测试挂着时改地址、再回成功：不发 POST /setup/bot，botOk 仍为假');

// ② 对照：回包前没动，测通照旧自动存下，存的是测过的那一套
let fresh = 'missing';
try {
  const page = loadStep();
  const pending = click(page.test);
  await flush();
  page.answer({success: true, message: '连接正常', advice: ''});
  await pending;
  fresh = {
    saved: page.calls.filter(c => c.path === '/setup/bot').map(c => c.body.address),
    botOk: page.draft.botOk,
  };
} catch (e) {
  fresh = 'error:' + e.message;
}
eq(fresh, {saved: ['127.0.0.1'], botOk: true},
  '② 对照：回包前没改，测通自动存下测过的地址，botOk 为真');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
