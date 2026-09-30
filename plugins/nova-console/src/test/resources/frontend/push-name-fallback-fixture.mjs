/**
 * 推送页主播名：现场去平台查不回时，退回程序手上已有的昵称
 *
 * 平台断网或被风控时打开推送页，现场查名全落空，一排主播都显示成「uid 数字」，认不出谁是谁；
 * 同一时刻运行状态页与主播页却显示得出名字。查不回（回失败或抛错）时改问 /streamer/name，
 * 那里先给内存名、再给最近一场归档里的昵称；都没有才照旧显示 uid。
 * 添加主播那张确认小卡只认现场查回来的结果，不走这条退路。
 *
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 由 PushNameFallbackTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');
const src = readFileSync(join(ui, 'push.js'), 'utf8');
const {streamerName} = await import(pathToFileURL(join(ui, 'push-model.js')).href);

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

const decorateSrc = bracedFrom(src, 'async function decoratePushData');
if (!decorateSrc) {
  console.log('跑了 0 格，切不出 decoratePushData');
  process.exit(1);
}

/**
 * 用假接口跑一趟 decoratePushData
 * @param routes 按路径给回包的函数，路径不带查询串；抛错即模拟网络失败
 */
async function decorate(pushData, routes) {
  const calls = [];
  const api = async (path, opts) => {
    calls.push(path);
    const route = routes[path.split('?')[0]];
    if (!route) throw new Error('未设的接口 ' + path);
    return route(path, opts);
  };
  const store = {platforms: ['bilibili']};
  const fn = new Function('api', 'store', 'renderStreamers', 'pushData',
    decorateSrc + '\nreturn decoratePushData;')(api, store, () => {}, pushData);
  await fn();
  return calls;
}

const liveMiss = () => ({success: false, message: '未查到 uid 7 对应的主播，请确认 uid 是否正确'});
const liveDown = () => {
  throw new Error('网络断了');
};
const knownName = uname => path => {
  const q = new URLSearchParams(path.split('?')[1] || '');
  return q.get('platform') === 'bilibili' && q.get('uid') === '7'
    ? {success: true, uid: 7, uname}
    : {success: false};
};

// ① 现场查回失败、程序手上有昵称（归档里的）→ 显示该昵称，不显示 uid
{
  const user = {uid: 7, platform: 'bilibili'};
  await decorate([user], {'/streamer/lookup': liveMiss, '/streamer/name': knownName('归档里的昵称')});
  eq(streamerName(user), '归档里的昵称', '① 现场查回失败、手上有昵称 → 显示归档昵称');
}

// ② 现场查抛错（平台断网）、程序手上有昵称 → 显示该昵称
{
  const user = {uid: 7, platform: 'bilibili'};
  await decorate([user], {'/streamer/lookup': liveDown, '/streamer/name': knownName('内存里的昵称')});
  eq(streamerName(user), '内存里的昵称', '② 现场查抛错、手上有昵称 → 显示该昵称');
}

// ③ 两处都没有 → 照旧显示 uid
{
  const user = {uid: 7, platform: 'bilibili'};
  await decorate([user], {'/streamer/lookup': liveMiss, '/streamer/name': () => ({success: false})});
  eq(streamerName(user), 'uid 7', '③ 现场查不回、手上也没有 → 照旧显示 uid');
}

// ④ 退路本身也抛错 → 照旧显示 uid，不连累别的主播
{
  const a = {uid: 7, platform: 'bilibili'};
  const b = {uid: 8, platform: 'bilibili'};
  await decorate([a, b], {
    '/streamer/lookup': (path, opts) => (JSON.parse(opts.body).uid === '8'
      ? {success: true, uid: 8, uname: '现查回来的'}
      : liveMiss()),
    '/streamer/name': liveDown,
  });
  eq([streamerName(a), streamerName(b)], ['uid 7', '现查回来的'], '④ 退路也抛错 → 这位照旧显示 uid，另一位不受连累');
}

// ⑤ 现场查回来了 → 用现查的名，不再问退路
{
  const user = {uid: 7, platform: 'bilibili'};
  const calls = await decorate([user], {
    '/streamer/lookup': () => ({success: true, uid: 7, uname: '现查回来的', roomId: 22}),
    '/streamer/name': knownName('归档里的旧名'),
  });
  eq([streamerName(user), calls.some(p => p.startsWith('/streamer/name'))], ['现查回来的', false],
    '⑤ 现场查回来了 → 用现查的名且不问退路');
}

// ⑥ 添加主播那张确认小卡只认现场查回来的：那一份函数里不出现退路接口
{
  const at = src.indexOf('/streamer/lookup');
  const start = at < 0 ? -1 : src.lastIndexOf('async function', at);
  const addSrc = start < 0 ? '' : bracedFrom(src.slice(start), 'async function');
  eq([addSrc !== '' && addSrc !== decorateSrc, addSrc.includes('/streamer/name')], [true, false],
    '⑥ 添加主播的查询函数不走退路接口');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
