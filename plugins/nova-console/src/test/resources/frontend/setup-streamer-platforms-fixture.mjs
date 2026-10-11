/**
 * 向导主播步：平台清单还没到、到了但一个都没装、到了有货，三种情形各说各的话
 *
 * 抓的用户故障：首屏那趟整体载入先画向导、后取平台清单（宿主 mountPages 完才 load），
 * 停在「第一位主播」那一步的人会先看到「没有装任何直播平台插件，加不了主播。」——
 * 其实插件装着，清单只是还在路上。照那句话去补装插件的人，装完还是这句。
 * 「下一步」也一样：清单还没到时它一声不吭地放行，这一步什么都没存就过去了。
 *
 * 这把尺把 setup-streamer.js 的 render／next／note／field／paintFound／stop 按花括号
 * 配平切出函数体来真执行，el 换成记孩子的节点工厂。seen／result 在源文件里是模块级的
 * let，切出来之后落在全局上——render 写上去的，next 里的 stop 读得到。
 *
 * 六格：①清单还没到：画的是「正在载入」不是「没装插件」，回话那块也画出来；
 * ④清单还没到时「下一步」拦下并说在等什么。②③⑤⑥是对照：一个都没装照旧说没装、
 * 有货照旧画平台下拉、空清单照旧放行、没人时照旧拦「先填」。①④先红后绿，
 * ②③⑤⑥改前本来就是绿。
 *
 * ⑦量的是清单取失败那一档：说清「没取到、可以怎么办」，不再说「正在载入」，
 * 「下一步」拦下用的是同一句。这一格先红后绿。
 *
 * 由 SetupStreamerPlatformsTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const pages = join(dirname(fileURLToPath(import.meta.url)),
  '../../../main/resources/config-ui-pages');
const src = readFileSync(join(pages, 'setup-streamer.js'), 'utf8');

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

/** 切出函数体（含声明），形参槽注入后交回那个函数本身 */
function cutFn(marker, params, args) {
  const decl = bracedFrom(src, marker).replace(/^export /, '');
  if (!decl) throw new Error('no ' + marker);
  return new Function(...params, decl + '\nreturn ' + decl.slice(
    decl.indexOf('function') + 'function'.length).trim().split(/[\s(]/)[0] + ';')(...args);
}

// —— el 换成记孩子的节点工厂：画了什么、画没画，事后从孩子里翻 ——

function el(tag, className) {
  return {
    tag, className: className || '', kids: [], textContent: '', innerHTML: '',
    value: '', id: '', type: '', disabled: false, checked: false, autocomplete: '',
    appendChild(kid) { this.kids.push(kid); return kid; },
    setAttribute(name, value) { this['attr:' + name] = value; },
    addEventListener() {},
  };
}

/** 深度翻孩子，找出头一个合乎条件的节点 */
function findKid(node, pred) {
  for (const kid of node.kids) {
    if (pred(kid)) return kid;
    const deep = findKid(kid, pred);
    if (deep) return deep;
  }
  return null;
}

const esc = s => String(s);
// 推送页带过来的那句输入提示，这里给同一句：这一步画说明、拦「先填」用的都是它
const STREAMER_INPUT_HINT = 'uid、直播间号，或粘贴空间／直播间链接';

// 这一步手里的草稿与宿主给的平台清单：各格现改现用
const store = {platforms: null};
const draft = {streamer: null, targets: []};

let render = null;
let next = null;
try {
  const note = cutFn('function note(', ['el'], [el]);
  const field = cutFn('function field(', ['el'], [el]);
  // 本组各格里都没查到人：paintFound 只会把那一块清空，走不到「推到哪」那一段
  const paintFound = cutFn('function paintFound(', ['el', 'draft', 'targetPicker'],
    [el, draft, () => el('div')]);
  const stop = cutFn('function stop(', [], []);
  const saveStreamerCalls = [];
  // 清单没取到时那一句由产品码自己拼，画出来的与「下一步」拦人时说的是同一句。
  // 改前还没有这个小件，槽位先占上、给 null
  let platformsFailedText = null;
  try {
    platformsFailedText = cutFn('function platformsFailedText(', [], []);
  } catch (e) {
    platformsFailedText = null;
  }
  render = cutFn('export function render(',
    ['el', 'esc', 'note', 'field', 'STREAMER_INPUT_HINT', 'store', 'draft', 'paintFound', 'loadOptions',
      'platformsFailedText'],
    [el, esc, note, field, STREAMER_INPUT_HINT, store, draft, paintFound, async () => {},
      platformsFailedText]);
  next = cutFn('export async function next(',
    ['store', 'draft', 'STREAMER_INPUT_HINT', 'stop', 'saveStreamer', 'platformsFailedText'],
    [store, draft, STREAMER_INPUT_HINT, stop, () => { saveStreamerCalls.push(1); return true; },
      platformsFailedText]);
} catch (err) {
  failures.push('切 render／next 就抛了：' + err.message);
}

/** 开一格：平台清单换成 platforms（没取到时带上原因），回话块、草稿都归位 */
function openCell(platforms, platformsError) {
  store.platforms = platforms;
  store.platformsError = platformsError || '';
  draft.streamer = null;
  draft.targets = [];
  globalThis.result = null;
  return el('div');
}

/** 那句平台说明：su-note 那一块（没有即 null） */
function platformNote(host) {
  return findKid(host, kid => String(kid.className).startsWith('su-note'));
}

// ---------- ① 清单还没到：说正在载入，别说没装插件 ----------
// 病：首屏先画向导后取 /platforms，这一步先画就先把「没有装任何直播平台插件」
// 说出去了——插件其实装着。回话那块也得跟着画出来，下一步拦人时才有地方写理由
let cell1 = '没跑';
try {
  const host = openCell(null);
  render(host, {});
  const note = platformNote(host);
  cell1 = JSON.stringify({
    '那句话是什么': note ? note.textContent : '(没有)',
    '当警告说吗': note ? String(note.className).includes('warn') : null,
    '回话那块画出来了吗': Boolean(findKid(host, kid => kid.className === 'su-r')),
  });
} catch (e) {
  cell1 = 'error:' + e.message;
}
eq(JSON.parse(cell1), {
  '那句话是什么': '正在载入直播平台清单…',
  '当警告说吗': false,
  '回话那块画出来了吗': true,
}, '① 清单还没到：说「正在载入」而不是「没装插件」，回话那块也画出来');

// ---------- ② 对照：取到了确实一个都没装 ----------
// 这一句该留着：真没装插件的人靠它知道该去装什么
let cell2 = '没跑';
try {
  const host = openCell([]);
  render(host, {});
  const note = platformNote(host);
  cell2 = JSON.stringify({
    '那句话是什么': note ? note.textContent : '(没有)',
    '当警告说吗': note ? String(note.className).includes('warn') : null,
    '回话那块画出来了吗': Boolean(findKid(host, kid => kid.className === 'su-r')),
  });
} catch (e) {
  cell2 = 'error:' + e.message;
}
eq(JSON.parse(cell2), {
  '那句话是什么': '没有装任何直播平台插件，加不了主播。',
  '当警告说吗': true,
  '回话那块画出来了吗': false,
}, '② 对照：一个都没装时照旧说没装插件，回话那块不画');

// ---------- ③ 对照：清单到了有货 ----------
let cell3 = '没跑';
try {
  const host = openCell(['bilibili', 'demo']);
  render(host, {});
  const picker = findKid(host, kid => kid.id === 'setup-platform');
  cell3 = JSON.stringify({
    '平台下拉在吗': Boolean(picker),
    '选项里有哪些': picker ? ['bilibili', 'demo'].map(name => picker.innerHTML.includes(name)) : null,
    '当警告说吗': Boolean(platformNote(host)),
  });
} catch (e) {
  cell3 = 'error:' + e.message;
}
eq(JSON.parse(cell3), {
  '平台下拉在吗': true,
  '选项里有哪些': [true, true],
  '当警告说吗': false,
}, '③ 对照：清单到了有货，平台下拉照旧画出来、不当警告说');

// ---------- ④ 清单还没到时「下一步」拦下并说在等什么 ----------
// 病：清单在路上时它一声不吭地放行——这一步什么都没存就过去了，第 5 步在
// 一台其实什么都没配的机器上发消息
let cell4 = '没跑';
try {
  const host = openCell(null);
  render(host, {});
  const passed = await next();
  cell4 = JSON.stringify({
    '放行了吗': passed,
    '回话说什么': globalThis.result ? globalThis.result.textContent : '(没有回话块)',
  });
} catch (e) {
  cell4 = 'error:' + e.message;
}
eq(JSON.parse(cell4), {
  '放行了吗': false,
  '回话说什么': '直播平台清单还在载入，稍等一下再试',
}, '④ 清单还没到：「下一步」拦下并说在等什么，不一声不吭地放行');

// ---------- ⑤ 对照：一个都没装时照旧放行 ----------
// 这一步没得做，不该拦着人往下走
let cell5 = '没跑';
try {
  openCell([]);
  const passed = await next();
  cell5 = JSON.stringify({'放行了吗': passed});
} catch (e) {
  cell5 = 'error:' + e.message;
}
eq(JSON.parse(cell5), {'放行了吗': true}, '⑤ 对照：一个都没装时照旧放行');

// ---------- ⑥ 对照：有平台但没查到人，照旧拦「先填」 ----------
let cell6 = '没跑';
try {
  const host = openCell(['bilibili']);
  render(host, {});
  const passed = await next();
  cell6 = JSON.stringify({
    '放行了吗': passed,
    '说的是先填吗': globalThis.result ? globalThis.result.textContent.startsWith('先填') : null,
  });
} catch (e) {
  cell6 = 'error:' + e.message;
}
eq(JSON.parse(cell6), {
  '放行了吗': false,
  '说的是先填吗': true,
}, '⑥ 对照：有平台但没查到人，照旧拦下并说「先填」');

// ---------- ⑦ 清单没取到：说清没取到与出路，「下一步」拦下说的是同一句 ----------
// 病：/platforms 那一趟失败时这一步还挂着「正在载入直播平台清单…」，人一直等下去，
// 不知道可以刷新页面再试、或者点「先不加主播」先跳过。「下一步」也只说「还在载入」
let cell7 = '没跑';
try {
  const host = openCell(null, '网络不通');
  render(host, {});
  const note = platformNote(host);
  const passed = await next();
  cell7 = JSON.stringify({
    '画的那句话是什么': note ? note.textContent : '(没有)',
    '当警告说吗': note ? String(note.className).includes('warn') : null,
    '还在说正在载入吗': note ? note.textContent.includes('正在载入') : null,
    '放行了吗': passed,
    '回话说什么': globalThis.result ? globalThis.result.textContent : '(没有回话块)',
    '两处说的是同一句吗': note && globalThis.result
      ? note.textContent === globalThis.result.textContent : null,
  });
} catch (e) {
  cell7 = 'error:' + e.message;
}
eq(JSON.parse(cell7), {
  '画的那句话是什么': '直播平台清单没取到：网络不通。刷新页面再试，或者点「先不加主播」先跳过。',
  '当警告说吗': true,
  '还在说正在载入吗': false,
  '放行了吗': false,
  '回话说什么': '直播平台清单没取到：网络不通。刷新页面再试，或者点「先不加主播」先跳过。',
  '两处说的是同一句吗': true,
}, '⑦ 清单没取到：说清没取到与出路，「下一步」拦下说的是同一句');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
