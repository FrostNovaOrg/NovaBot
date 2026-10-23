import {botFieldsHtml} from '../../../main/resources/config-ui/bot-fields.js';
import {MiniNode} from './layout-dom.mjs';
/**
 * 初始设置第 2 步：自动保存还没回来时改了参数，改过的那份不得记成「与盘上一致」
 *
 * 用户故障：测通之后、自动保存回来之前改了 HTTP Token；回包一到，改过的那份被当成已存。
 * 重进向导时草稿被盘上的参数盖掉、两个 Token 清空，这一步却显示已接上。
 * 切出 stepBot 与它用到的草稿、对盘那几个函数真执行：点「测试连接」、让测试回包、
 * 保存挂着时改格子、再让保存回包，然后照 openSetup 的顺序重进一次。
 * 对照：保存回来之前没改，回包后照旧算与盘上一致，重进时采既成事实。
 * 保存没存下（回包说失败、请求抛错）时，发出去的那份也不算与盘上一致。
 *
 * 由 SetupBotSaveRaceTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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

/** 草稿取源码里那一份初值；页面零件与接口换成桩，保存请求挂着等放行 */
function loadStep() {
  const pieces = [
    bracedFrom(setupSrc, 'const draft'),
    bracedFrom(setupSrc, 'function botSnapshot'),
    bracedFrom(setupSrc, 'function botDraftMatchesSaved'),
    bracedFrom(setupSrc, 'function rememberBotDraft'),
    bracedFrom(setupSrc, 'function syncBotDraft'),
    bracedFrom(setupSrc, 'function applyBotOkFromFacts'),
    bracedFrom(setupSrc, 'function invalidateBot'),
    bracedFrom(setupSrc, 'function stepBot'),
  ];
  if (!pieces.every(Boolean)) throw new Error('no function');

  const made = [];
  const inputs = {};
  const calls = [];
  let release = null;
  let fail = null;
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
      if (path === '/setup/bot') {
        return new Promise((resolve, reject) => {
          release = resolve;
          fail = reject;
        });
      }
      return {success: true, message: '连接正常', advice: ''};
    },
  };
  const names = Object.keys(deps);
  const run = new Function(...names,
    pieces.join(';\n') + '\nreturn {draft, stepBot, botDraftMatchesSaved, syncBotDraft, applyBotOkFromFacts};')(
    ...names.map(n => deps[n]));
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
  return {run, draft: run.draft, inputs, calls, test, answerSave: res => release(res), failSave: err => fail(err)};
}

function click(node) {
  return Promise.all((node.listeners.click || []).map(fn => fn()));
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/** 盘上那一份：存下的是测试时那一套 */
const SAVED = {configured: true, address: '127.0.0.1', httpPort: 3000, websocketPort: 3001};

/** 照 openSetup 的顺序重进一次：与盘上一致才回填并清空 Token，再按事实定「已接上」 */
function reenter(run) {
  if (run.botDraftMatchesSaved(run.draft)) run.syncBotDraft(run.draft, SAVED);
  run.applyBotOkFromFacts(run.draft, [true, true, false, false]);
}

/** 填好两个 Token，点测试，等到保存请求发出去；包一层，免得 await 等到整个点击做完 */
async function startSave(page) {
  page.inputs['setup-htoken']('tok-a');
  page.inputs['setup-wtoken']('ws-a');
  const pending = click(page.test);
  for (let i = 0; i < 5 && !page.calls.some(c => c.path === '/setup/bot'); i++) await flush();
  return {pending};
}

// ① 保存挂着时改了 HTTP Token：回包后这份不算与盘上一致；重进时草稿不被盘上盖掉、不翻回已接上
let raced = 'missing';
try {
  const page = loadStep();
  const {pending} = await startSave(page);
  page.inputs['setup-htoken']('tok-b');
  page.answerSave({success: true, message: '已接上'});
  await pending;
  const afterSave = {
    sentToken: page.calls.filter(c => c.path === '/setup/bot').map(c => c.body.httpToken),
    matches: page.run.botDraftMatchesSaved(page.draft),
    botOk: page.draft.botOk,
  };
  reenter(page.run);
  raced = {
    afterSave,
    reentered: {httpToken: page.draft.bot.httpToken, wsToken: page.draft.bot.wsToken, botOk: page.draft.botOk},
  };
} catch (e) {
  raced = 'error:' + e.message;
}
eq(raced, {
  afterSave: {sentToken: ['tok-a'], matches: false, botOk: false},
  reentered: {httpToken: 'tok-b', wsToken: 'ws-a', botOk: false},
}, '① 保存回包前改 HTTP Token：不算与盘上一致；重进时 Token 仍是改过的那份，「已接上」不翻回真');

// ② 对照：保存回来之前没改，回包后照旧算与盘上一致；重进时回填并采既成事实
let calm = 'missing';
try {
  const page = loadStep();
  const {pending} = await startSave(page);
  page.answerSave({success: true, message: '已接上'});
  await pending;
  const afterSave = {matches: page.run.botDraftMatchesSaved(page.draft), botOk: page.draft.botOk};
  reenter(page.run);
  calm = {
    afterSave,
    reentered: {httpToken: page.draft.bot.httpToken, wsToken: page.draft.bot.wsToken, botOk: page.draft.botOk},
  };
} catch (e) {
  calm = 'error:' + e.message;
}
eq(calm, {
  afterSave: {matches: true, botOk: true},
  reentered: {httpToken: '', wsToken: '', botOk: true},
}, '② 对照：保存回包前没改，回包后与盘上一致，重进时照旧算已接上');

/** 照 ① 的走法存一次，保存那一趟由 settle 了结；量回包后与重进后的草稿 */
async function failedSave(settle) {
  try {
    const page = loadStep();
    const {pending} = await startSave(page);
    if (!page.calls.some(c => c.path === '/setup/bot')) return 'save-not-sent';
    settle(page);
    await pending;
    const afterSave = {matches: page.run.botDraftMatchesSaved(page.draft), botOk: page.draft.botOk};
    reenter(page.run);
    return {
      afterSave,
      reentered: {httpToken: page.draft.bot.httpToken, wsToken: page.draft.bot.wsToken, botOk: page.draft.botOk},
    };
  } catch (e) {
    return 'error:' + e.message;
  }
}

const UNSAVED = {
  afterSave: {matches: false, botOk: false},
  reentered: {httpToken: 'tok-a', wsToken: 'ws-a', botOk: false},
};

// ③ 用户故障：测通之后自动保存没存下（服务端回失败），向导却把这套参数当成已存；
// 重进时刚填的参数被盘上旧的盖掉、两个 Token 清空，盘上旧的那套若是通的，这一步还显示已接上
eq(await failedSave(page => page.answerSave({success: false, message: '写不进配置文件'})), UNSAVED,
  '③ 保存回包说失败：不算与盘上一致，botOk 为假；重进时 Token 仍是刚填的，「已接上」不翻回真');

// ④ 用户故障：测通之后自动保存没存下（请求出错），向导却把这套参数当成已存；
// 重进时刚填的参数被盘上旧的盖掉、两个 Token 清空，盘上旧的那套若是通的，这一步还显示已接上
eq(await failedSave(page => page.failSave(new Error('网络断了'))), UNSAVED,
  '④ 保存请求抛错：不算与盘上一致，botOk 为假；重进时 Token 仍是刚填的，「已接上」不翻回真');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
