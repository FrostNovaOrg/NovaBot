/**
 * 向导插件步：平台清单晚到时得有人叫它重画
 *
 * 抓的用户故障：首屏那趟整体载入先画向导、后取 /platforms（宿主 mountPages 完才 load）。
 * 清单一到没有任何人叫向导重画——refreshPages 只认插件页，不认向导里的插件步，
 * 于是「第一位主播」那一步上的「正在载入」一直挂到使用者自己翻一步再翻回来。
 *
 * 两头各量一头：setup.js 新出的 notePlatformsArrived（停在插件步才画、走完或停在
 * 内置步不画、步号越界不炸）按花括号配平切出函数体来真执行；main.js 的 load 切出来
 * 跑一趟，量它取回平台清单之后有没有叫这一声、叫的时候清单是不是已经摆上了。
 * load 用的全是本地的桩，不引 core.js——这一组量的不是它。
 *
 * 六格：①停在插件步、清单到了：重画一次；②已走完：不画；③停在内置步：不画；
 * ④步号越界：不画也不炸；⑤整体载入取回清单时叫了这一声、叫时清单已摆上；
 * ⑥再取一趟再叫一声（重复通知不碍事，照画就是）。①—⑥改前全红：那会儿
 * notePlatformsArrived 还不存在，load 里也没有这一声。
 *
 * ⑦量的是清单那一趟取失败：记下原因、清单保持没到，也照样叫这一声——
 * 向导那一步要靠它把「正在载入」换成「没取到、可以怎么办」。这一格先红后绿。
 *
 * 由 SetupPlatformsLateTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {CLEAR, defaultValue} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

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

/**
 * 切出 async 函数的函数体，包成异步函数交回调用方自己跑
 *
 * load 的体里有 await，套进普通 Function 会当场炸「await is only valid in async
 * functions」——那句红说的是夹具自己，不是产品码。
 */
function loadAsyncFn(text, marker, params, args, formals) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const open = body.indexOf('{');
  const inner = body.slice(open + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return async ' + (formals || '()') + ' => {' + inner + '}')(...args);
}

// —— 向导那边的 notePlatformsArrived：场景各烘一份，量它画没画 ——

const setupSrc = readFileSync(join(ui, 'setup.js'), 'utf8');
const notifierDecl = bracedFrom(setupSrc, 'export function notePlatformsArrived').replace(/^export /, '');

// 与宿主 withPluginSteps 摆出来的同一副骨架：内置步没有 plugin 位，插件步有
const BUILTIN = {key: 'account', title: '账号'};
const PLUGIN_STEP = {key: 'push', title: '推送', plugin: true};

/**
 * 按一个场景烘一份：finished／steps／current 烤进去，render 换成计数器
 * @return {Object} 读数；源文件里还没有这个函数时给一句说得清的话（那一格当红）
 */
function scenario(finished, steps, current) {
  if (!notifierDecl) return '（源文件里还没有这个函数）';
  let renders = 0;
  const fn = new Function('finished', 'steps', 'current', 'render',
    notifierDecl + '\nreturn notePlatformsArrived;')(finished, steps, current, () => { renders++; });
  fn();
  return {'重画了几次': renders};
}

// ---------- ① 停在插件步、清单到了：重画一次 ----------
eq(scenario(false, [BUILTIN, PLUGIN_STEP], 1), {'重画了几次': 1},
  '① 正停在插件步上、平台清单到了：这一步重画一次');

// ---------- ② 已走完：不画 ----------
eq(scenario(true, [BUILTIN, PLUGIN_STEP], 1), {'重画了几次': 0},
  '② 向导已经走完：不再画');

// ---------- ③ 停在内置步：不画 ----------
// 内置那几步不读平台清单，重画它们没有意义；这一格也拦「画太宽」——
// 谁都画的话，停在账号步的人会看见屏幕无故闪一下
eq(scenario(false, [BUILTIN, PLUGIN_STEP], 0), {'重画了几次': 0},
  '③ 停在内置步上：不画');

// ---------- ④ 步号越界：不画也不炸 ----------
eq(scenario(false, [BUILTIN, PLUGIN_STEP], 9), {'重画了几次': 0},
  '④ 步号越界：不画也不炸');

// ---------- ⑤ 整体载入取回清单时叫这一声，叫时清单已摆上 ----------
// 病：清单一到没有任何人叫向导重画——refreshPages 只认插件页，不认向导里的插件步
const store = {tab: 'settings', dirty: {}, restartPending: [], platforms: null};
let notified = 0;
const platformsAtCall = [];
const noteSpy = () => {
  notified++;
  platformsAtCall.push(Array.from(store.platforms || []));
};

const mainSrc = readFileSync(join(ui, 'main.js'), 'utf8');
// 平台清单那一趟的成与败由各格自己定：失败时 load 要记下原因、照样叫向导一声
let platformsFails = '';
async function api(path) {
  if (path === '/schema') return {groups: [], ungrouped: []};
  if (path === '/values') return {values: {}, legacy: {}, locked: {}, fontTables: {}};
  if (path === '/status') return {configPath: '', senders: []};
  if (path === '/handlers') return {handlers: []};
  if (path === '/platforms') {
    if (platformsFails) throw new Error(platformsFails);
    return {platforms: ['bilibili']};
  }
  if (path === '/vocab') return {terms: {}};
  if (path === '/alert/channels') return {channels: []};
  return {};
}
const noop = () => {};

let load = null;
try {
  load = loadAsyncFn(mainSrc, 'function load(',
    ['say', 'api', 'store', 'renderGeneral', 'parseHash', 'focusCard', 'reloadChangeSources',
      'renderStatus', 'renderConfigPath', 'refreshPages', 'refreshHome', 'fillBotForms',
      'applyConnectionVocab', 'markDirty', 'notePlatformsArrived', 'CLEAR', 'defaultValue'],
    [noop, api, store, noop, () => ({name: '', card: ''}), noop, async () => true,
      noop, noop, noop, noop, noop, noop, noop, noteSpy, CLEAR, defaultValue],
    'how');
} catch (err) {
  failures.push('切 load 就抛了：' + err.message);
}

let cell5 = '没跑';
try {
  if (load) await load();
  cell5 = JSON.stringify({
    '平台清单一到通知了几次': notified,
    '通知时清单是': platformsAtCall[0] || '(没通知过)',
    '取完之后清单是': store.platforms,
  });
} catch (e) {
  cell5 = 'error:' + e.message;
}
eq(JSON.parse(cell5), {
  '平台清单一到通知了几次': 1,
  '通知时清单是': ['bilibili'],
  '取完之后清单是': ['bilibili'],
}, '⑤ 整体载入取回平台清单时叫这一声，叫的时候清单已经摆上');

// ---------- ⑥ 再取一趟再叫一声 ----------
// 重复通知不碍事：停在这一步的人两趟都该看到新画的那一遍
let cell6 = '没跑';
try {
  if (load) await load();
  cell6 = JSON.stringify({'两趟一共通知了几次': notified});
} catch (e) {
  cell6 = 'error:' + e.message;
}
eq(JSON.parse(cell6), {'两趟一共通知了几次': 2}, '⑥ 再取一趟再叫一声，向导照画就是');

// ---------- ⑦ 清单那一趟取失败：记下原因、照样叫这一声 ----------
// 病：/platforms 那一趟失败时整批载入全废，向导那一步没人叫、停在「正在载入」——
// 清单没取到这件事没人告诉他，也不知道可以刷新再试或先跳过
let cell7 = '没跑';
try {
  platformsFails = '网络不通';
  notified = 0;
  platformsAtCall.length = 0;
  store.platforms = null;
  store.platformsError = '';
  if (load) await load();
  cell7 = JSON.stringify({
    '叫了这一声吗': notified,
    '清单还是没到吗': store.platforms === null ? '(没到)' : store.platforms,
    '记下的原因是': store.platformsError,
  });
  platformsFails = '';
} catch (e) {
  cell7 = 'error:' + e.message;
}
eq(JSON.parse(cell7), {
  '叫了这一声吗': 1,
  '清单还是没到吗': '(没到)',
  '记下的原因是': '网络不通',
}, '⑦ 清单那一趟取失败：记下原因、清单保持没到，也照样叫这一声');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
