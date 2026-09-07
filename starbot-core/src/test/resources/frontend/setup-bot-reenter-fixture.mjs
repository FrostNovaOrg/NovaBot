/**
 * 重进初始设置时，未保存的机器人改动不得把已作废的测通翻回真
 *
 * 改了参数再离开、回来：botOk 须仍是 false，Token 格仍是编辑值。
 * 没改过再回来：botOk 采既成事实为 true。
 * 把无条件 `|| facts[1]` 写回，前一问必须红。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 *
 * 由 SetupBotReenterTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const setupSrc = readFileSync(join(ui, 'setup.js'), 'utf8');
const timerSrc = readFileSync(join(ui, '../../../../../dist/templates/novabot-backup.timer'), 'utf8');

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

function loadFns(src) {
  const pieces = [
    bracedFrom(src, 'function botSnapshot'),
    bracedFrom(src, 'function botDraftMatchesSaved'),
    bracedFrom(src, 'function rememberBotDraft'),
    bracedFrom(src, 'function syncBotDraft'),
    bracedFrom(src, 'function applyBotOkFromFacts'),
  ];
  if (pieces.every(Boolean)) {
    return new Function(
      pieces.join('\n')
      + '\nreturn {'
      + 'botSnapshot, botDraftMatchesSaved, rememberBotDraft, syncBotDraft, applyBotOkFromFacts,'
      + 'reenter: function(draft, bot, facts) {'
      + '  if (botDraftMatchesSaved(draft)) syncBotDraft(draft, bot);'
      + '  applyBotOkFromFacts(draft, facts);'
      + '  return draft;'
      + '}'
      + '};'
    )();
  }
  const sync = bracedFrom(src, 'function syncBotDraft');
  if (!sync) throw new Error('no syncBotDraft');
  return new Function(
    sync
    + '\nreturn {reenter: function(draft, bot, facts) {'
    + '  syncBotDraft(draft, bot);'
    + '  draft.botOk = draft.botOk || facts[1];'
    + '  return draft;'
    + '}};'
  )();
}

function loadInvalidate(src) {
  const body = bracedFrom(src, 'function invalidateBot');
  if (!body) throw new Error('no invalidateBot');
  return new Function(body + '\nreturn invalidateBot;')();
}

function savedBot() {
  return {configured: true, address: '10.0.0.1', httpPort: 3000, websocketPort: 3001};
}

function seed(fns) {
  const draft = {
    botOk: true,
    bot: {
      address: '10.0.0.1', httpPort: '3000', wsPort: '3001',
      httpToken: '', wsToken: '',
    },
  };
  if (fns.rememberBotDraft) fns.rememberBotDraft(draft);
  return draft;
}

function mutateApply(src) {
  const body = bracedFrom(src, 'function applyBotOkFromFacts');
  if (!body) return src;
  const start = src.indexOf('function applyBotOkFromFacts');
  const stub = 'function applyBotOkFromFacts(draft, facts) {\n'
    + '  draft.botOk = draft.botOk || facts[1];\n'
    + '  return draft;\n'
    + '}';
  return src.slice(0, start) + stub + src.slice(start + body.length);
}

const factsOn = [true, true, true, true, true];

// ① 改参→离开→重进：botOk 仍 false，Token 格仍是编辑值
let q1 = 'missing';
try {
  const fns = loadFns(setupSrc);
  const draft = seed(fns);
  draft.bot.httpToken = 'edited-token';
  loadInvalidate(setupSrc)(draft);
  fns.reenter(draft, savedBot(), factsOn);
  q1 = {ok: draft.botOk, tok: draft.bot.httpToken};
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, {ok: false, tok: 'edited-token'},
  '① 改参后重进 botOk 仍 false 且 Token 仍是编辑值');

// ② 阴性对照：未改参重进 → botOk 采 facts 为 true
let q2 = 'missing';
try {
  const fns = loadFns(setupSrc);
  const draft = seed(fns);
  draft.botOk = false;
  fns.reenter(draft, savedBot(), factsOn);
  q2 = draft.botOk;
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, '② 未改参重进 botOk 采 facts 为 true');

// ③ 把无条件 || facts[1] 写回 → ① 那问必须红（夹具自跑突变）
let q3 = 'missing';
try {
  const mutated = loadFns(mutateApply(setupSrc));
  const draft = seed(mutated);
  draft.bot.httpToken = 'edited-token';
  loadInvalidate(setupSrc)(draft);
  mutated.reenter(draft, savedBot(), factsOn);
  q3 = draft.botOk;
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, '③ 无条件 || facts[1] 写回后 botOk 翻成 true');

// ④ 接线：openSetup 经 applyBotOkFromFacts，自身不再 || facts[1]
const open = bracedFrom(setupSrc, 'function openSetup');
eq(
  open.includes('applyBotOkFromFacts(draft, facts)')
    && !open.includes('draft.botOk = draft.botOk || facts[1]'),
  true,
  '④ openSetup 调用 applyBotOkFromFacts(draft, facts) 且自身不再 || facts[1]');

// ⑤ 备份定时器注释安装目录
const oldHits = (timerSrc.match(/\/opt\/novabot/g) || []).length;
const newHits = (timerSrc.match(/\/opt\/starbot/g) || []).length;
eq(oldHits, 0, '⑤ timer 不含 /opt/novabot');
eq(newHits >= 1, true, '⑤ timer 含 /opt/starbot');

// ⑥ 真执行 syncBotDraft 后 botSynced 与 botSnapshot 同一份
let q6 = 'missing';
try {
  const fns = loadFns(setupSrc);
  const draft = seed(fns);
  if (!fns.syncBotDraft || !fns.botSnapshot) {
    throw new Error('missing fn');
  }
  fns.syncBotDraft(draft, savedBot());
  q6 = draft.botSynced === fns.botSnapshot(draft.bot);
} catch (e) {
  q6 = 'error:' + e.message;
}
eq(q6, true, '⑥ syncBotDraft 后 botSynced === botSnapshot(draft.bot)');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
