/**
 * 设置页组目录的落点：窄屏上顶栏与目录都吸在视口顶上，标题得让出它们的高度
 *
 * 抓的用户故障：平板、手机或放大页面（959px 以下）时，#side 变成吸顶的顶部导航，
 * .grpnav 也吸顶。点左边目录的条目跳过去，滚到位的组标题正好被这两块盖住——
 * 看起来像「点了没反应」。宽屏上没有吸顶元素，落点本就不偏。
 *
 * 这把尺把 settings.js 的 focusGroup 按花括号配平切出函数体来真执行，$／document／
 * window 换成桩：顶栏与目录各报一个可拨的实测高度，scrollTo 记账。第五格不跑函数，
 * 量的是两处各写的一个数：focusGroup 里的断点必须与 app.css 用着的断点同一处——
 * 各写各的话，改了一处没改另一处，宽屏上会白让一段，窄屏上照旧盖住。
 *
 * 五格：①窄屏：落点扣掉顶栏与目录的实测高度；③同一屏换个设备高度（顶栏会折行，
 * 高度不是定数）：扣的是新量出来的那份，不是写死的数；④扣完成负数时按 0 兜住；
 * ⑤断点与样式表同一处。②是宽屏对照：宽屏上落点本就不偏，改前就是绿的。
 * ①③④⑤先红后绿。
 *
 * 由 SettingsFocusLandingTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

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

const settingsSrc = readFileSync(join(ui, 'settings.js'), 'utf8');
const focusDecl = bracedFrom(settingsSrc, 'export function focusGroup').replace(/^export /, '');
if (!focusDecl) failures.push('focusGroup 切不出来');

// —— 桩：顶栏与目录各报一个可拨的高度，matchMedia 一拨就换档 ——

const heights = {'#side': 0, '#grp-nav': 0};
const fake$ = sel => (heights[sel] !== undefined ? {offsetHeight: heights[sel]} : null);

const target = {
  classList: {remove() {}},
  getBoundingClientRect: () => ({top: 0}),
};
const fakeDocument = {querySelector: () => target};

const scrolled = [];
let narrowNow = false;
const fakeWindow = {
  scrollY: 0,
  scrollTo: (x, y) => scrolled.push([x, y]),
  matchMedia: () => ({matches: narrowNow}),
};

let focusGroup = null;
try {
  focusGroup = new Function('$', 'document', 'window',
    focusDecl + '\nreturn focusGroup;')(fake$, fakeDocument, fakeWindow);
} catch (err) {
  failures.push('烘 focusGroup 就抛了：' + err.message);
}

/** 开一格：换屏（narrow 与否）、拨高度、定目标离视口顶多远，然后点一次目录条目 */
function jump(narrow, scrollY, rectTop, sideH, navH) {
  scrolled.length = 0;
  narrowNow = narrow;
  fakeWindow.scrollY = scrollY;
  target.getBoundingClientRect = () => ({top: rectTop});
  heights['#side'] = sideH;
  heights['#grp-nav'] = navH;
  focusGroup('demo');
  return scrolled;
}

// ---------- ① 窄屏：落点扣掉顶栏与目录的实测高度 ----------
// 病：落点没让它们，滚到位的组标题正好被盖住——看起来像「点了没反应」
eq(jump(true, 100, 300, 60, 40), [[0, 300]],
  '① 窄屏：滚到 scrollY+300-（顶栏 60+目录 40）＝300，标题落在两块吸顶元素底下露出头来');

// ---------- ② 对照：宽屏落点本就不偏 ----------
eq(jump(false, 100, 300, 60, 40), [[0, 400]],
  '② 对照：宽屏没有吸顶元素，落点照旧是 scrollY+300');

// ---------- ③ 同一屏换个设备：扣的是新量出来的那份 ----------
// 顶栏里的内容会折行（退出块单独一行），高度不是定数。写死一个数的话，
// 换台设备、改一下字号，标题又被盖回去
eq(jump(true, 100, 300, 90, 25), [[0, 285]],
  '③ 窄屏换一档高度（顶栏 90+目录 25）：扣的是新量出来的那份，不是写死的数');

// ---------- ④ 扣完成负数：按 0 兜住 ----------
eq(jump(true, 0, 10, 60, 40), [[0, 0]],
  '④ 窄屏上目标本来就贴着顶：扣完成负数，按 0 兜住，不往回倒');

// ---------- ⑤ focusGroup 的断点与 app.css 同一处 ----------
// 两边各写一个数的话，改了一处没改另一处：宽屏上白让一段，窄屏上照旧盖住
const focusSource = focusDecl || '';
const focusBreak = focusSource.match(/max-width:\s*(\d+)px/);
const cssBreaks = [...readFileSync(join(ui, 'app.css'), 'utf8')
  .matchAll(/@media\s*\(max-width:\s*(\d+)px\)/g)].map(m => m[1]);
eq({
  'focusGroup 写了断点吗': focusBreak ? focusBreak[1] : '(没写)',
  '样式表里有这个断点吗': focusBreak ? cssBreaks.includes(focusBreak[1]) : false,
}, {
  'focusGroup 写了断点吗': '959',
  '样式表里有这个断点吗': true,
}, '⑤ focusGroup 量屏用的断点与 app.css 的窄屏断点同一处');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
