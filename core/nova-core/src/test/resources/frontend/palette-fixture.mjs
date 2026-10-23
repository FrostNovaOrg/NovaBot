/**
 * 配色：选了就当场换，刷新、重新登录、换到别的页都还在；没选过的一律粉蓝
 *
 * 抓的用户故障：
 *   - 没选过配色的人打开控制台、登录页，看到的不是粉蓝；
 *   - 选了暖阳，刷新或者重新登录以后又变回粉蓝（或先闪一下粉蓝再切）；
 *   - 另一个标签换了配色，这一个不跟；
 *   - 换配色把浅深也一起改了，或者反过来。
 *
 * 真跑 theme.js 那一份读写；各页首帧前那段 head 脚本从页面里原样切出来跑——刷新、重新登录时
 * 起作用的是它，不是 theme.js。只有假 localStorage、根元素与 window。
 * 由 PaletteSwitchFrontendTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ui = join(here, '../../../main/resources/config-ui');
const napcat = join(here, '../../../../../../plugins/nova-onebot-adapter/src/main/resources/config-ui-pages/napcat-bootstrap.html');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// —— 假 localStorage：可注入读写失败（隐私模式、被策略禁用） ——
const bag = new Map();
let failRead = false;
let failWrite = false;
const storage = {
  getItem(k) {
    if (failRead) throw new Error('storage blocked');
    return bag.has(k) ? bag.get(k) : null;
  },
  setItem(k, v) {
    if (failWrite) throw new Error('storage blocked');
    bag.set(k, String(v));
  },
  removeItem(k) {
    if (failWrite) throw new Error('storage blocked');
    bag.delete(k);
  },
};
globalThis.localStorage = storage;

const attrs = new Map();
globalThis.document = {
  documentElement: {
    setAttribute: (k, v) => attrs.set(k, String(v)),
    removeAttribute: k => attrs.delete(k),
    getAttribute: k => (attrs.has(k) ? attrs.get(k) : null),
  },
};

const listeners = {};
globalThis.window = {
  addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
  removeEventListener() {},
  dispatchEvent() { return true; },
};
function fire(type, event) {
  for (const fn of listeners[type] || []) fn(event);
}

function reset() {
  bag.clear();
  attrs.clear();
  failRead = false;
  failWrite = false;
}

let theme = null;
try {
  theme = await import(new URL('../../../main/resources/config-ui/theme.js', import.meta.url).href);
} catch (err) {
  failures.push('载入 theme.js 就抛了：' + err.message);
}
const KEY = 'novabot-palette';
const has = name => theme && typeof theme[name] === 'function';

// ---------- ① 没选过：粉蓝 ----------
reset();
eq(has('readPalette') ? theme.readPalette() : '(theme.js 没有 readPalette)', 'pink-blue', '① 本地没有配色键：读出粉蓝');
bag.set(KEY, 'purple');
eq(has('readPalette') ? theme.readPalette() : '(无)', 'pink-blue', '① 本地存着认不出的值：按粉蓝');
failRead = true;
eq(has('readPalette') ? theme.readPalette() : '(无)', 'pink-blue', '① 读本地存储出错：按粉蓝');
eq(theme && theme.PALETTE_KEY, KEY, '① 配色键名');

// ---------- ② 选了暖阳：当场换、记进本浏览器、不碰浅深 ----------
reset();
attrs.set('data-theme', 'dark');
const told = [];
if (has('subscribePalette')) theme.subscribePalette(v => told.push(v));
else failures.push('theme.js 没有 subscribePalette：设置页的钮无从跟着亮');
if (has('applyPalette')) theme.applyPalette('warm');
else failures.push('theme.js 没有 applyPalette');
eq(attrs.get('data-palette') ?? '(无)', 'warm', '② 选暖阳：根元素当场换成暖阳');
eq(bag.get(KEY) ?? '(无)', 'warm', '② 选暖阳：记进本浏览器');
eq(attrs.get('data-theme') ?? '(无)', 'dark', '② 换配色不动浅深');
eq(told, ['warm'], '② 订阅者听到换成了暖阳');
if (has('applyPalette')) theme.applyPalette('classic');
eq([attrs.get('data-palette') ?? '(无)', bag.get(KEY) ?? '(无)'], ['classic', 'classic'], '② 选经典');
if (has('applyPalette')) theme.applyPalette('pink-blue');
eq([attrs.get('data-palette') ?? '(无)', bag.get(KEY) ?? '(无)'], ['pink-blue', 'pink-blue'], '② 选回粉蓝');
if (has('applyPalette')) theme.applyPalette('<script>');
eq([attrs.get('data-palette') ?? '(无)', bag.get(KEY) ?? '(无)'], ['pink-blue', 'pink-blue'], '② 认不出的值按粉蓝，不原样写进去');
failWrite = true;
if (has('applyPalette')) theme.applyPalette('warm');
eq(attrs.get('data-palette') ?? '(无)', 'warm', '② 存不下也当场换');
failWrite = false;
reset();
if (typeof theme?.applyTheme === 'function') theme.applyTheme('light');
eq([attrs.get('data-palette') ?? '(无)', bag.get(KEY) ?? '(无)'], ['(无)', '(无)'], '② 换浅深不动配色');

// ---------- ③ 刷新、重新登录、打开 NapCat 那一页：首帧前的 head 脚本照本浏览器的选择设好 ----------
/** 页面 head 里那段同步脚本（不是 module、不带 src，读的是配色键） */
function bootScript(name, html) {
  const head = html.slice(0, html.indexOf('</head>'));
  const found = [];
  for (const m of head.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)) {
    if (/src=|type="module"/.test(m[1])) continue;
    if (m[2].includes(KEY)) found.push(m[2]);
  }
  if (found.length !== 1) {
    failures.push(name + ' 的 head 里应当正好一段同步脚本读配色，实见 ' + found.length + ' 段');
    return null;
  }
  const at = html.indexOf(found[0]);
  const style = html.search(/<link[^>]+stylesheet|<style>/);
  if (style >= 0 && style < at) failures.push(name + ' 读配色的脚本排在样式后面，首帧会先按粉蓝画一下');
  return new Function('localStorage', 'document', found[0]);
}

const pages = [
  ['控制台', readFileSync(join(ui, 'index.html'), 'utf8')],
  ['登录页', readFileSync(join(ui, 'login.html'), 'utf8')],
  ['NapCat 进入页', readFileSync(napcat, 'utf8')],
];
for (const [name, html] of pages) {
  const boot = bootScript(name, html);
  if (!boot) continue;
  const run = () => { attrs.clear(); boot(storage, globalThis.document); return attrs.get('data-palette') ?? '(无)'; };

  reset();
  bag.set(KEY, 'warm');
  bag.set('novabot-theme', 'dark');
  eq([run(), attrs.get('data-theme') ?? '(无)'], ['warm', 'dark'], '③ ' + name + '：选过暖阳与深色，刷新后首帧前就是暖阳深色');

  reset();
  bag.set(KEY, 'classic');
  eq(run(), 'classic', '③ ' + name + '：选过经典，刷新后是经典');

  reset();
  const unset = run();
  eq(unset === '(无)' || unset === 'pink-blue', true, '③ ' + name + '：没选过，落在粉蓝（得到 ' + unset + '）');
  eq(attrs.has('data-theme'), false, '③ ' + name + '：没选过浅深，仍跟系统');

  reset();
  bag.set(KEY, '" onload="x');
  const odd = run();
  eq(odd === '(无)' || odd === 'pink-blue', true, '③ ' + name + '：认不出的值不原样落到页面上（得到 ' + odd + '）');

  reset();
  failRead = true;
  let threw = '';
  try { run(); } catch (err) { threw = err.message; }
  eq(threw, '', '③ ' + name + '：读本地存储出错不抛，页面照常画');
}

// ---------- ④ 另一个标签换了配色 ----------
reset();
told.length = 0;
bag.set(KEY, 'warm');
fire('storage', {key: KEY, newValue: 'warm', storageArea: storage});
eq([attrs.get('data-palette') ?? '(无)', told], ['warm', ['warm']], '④ 另一标签选了暖阳：这一页跟着换，钮也跟着亮');
bag.clear();
fire('storage', {key: null, newValue: null, storageArea: storage});
eq(attrs.get('data-palette') ?? '(无)', 'pink-blue', '④ 另一标签清空了本地存储：回到粉蓝');
fire('storage', {key: 'other', newValue: 'warm', storageArea: storage});
eq(attrs.get('data-palette') ?? '(无)', 'pink-blue', '④ 别的键的事件不动配色');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
