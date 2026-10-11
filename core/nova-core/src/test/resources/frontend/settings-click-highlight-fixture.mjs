/**
 * 窄屏点目录里的某一组，高亮要对准那一组
 *
 * 抓的用户故障：窄屏（手机、平板或放大页面）上点设置目录里的某一组，高亮停在上一组。
 * 点目录滚到位时，组标题正好落在吸顶的顶栏与组目录下面——落点让出了它们的实测高度；
 * 而「哪一组算当前」让出的是 0，上一组的尾巴还压在吸顶栏底下，却被当成看得见、
 * 又最靠上，于是亮了上一组。手动滚到同一个位置也一样：高亮停在上一组。
 * 宽屏上没有吸顶元素，两处让的都是 0，因此只有窄屏上差一组。
 *
 * node 夹具没有排版，窄屏断点、顶栏与目录的实测高度、各组的位置全按桩上的数走。
 * 点完之后的落点按 focusGroup 的口径摆（组标题刚露在吸顶栏下面，那正是点目录的结果），
 * 量的是真 headerHeight 与真 watchCurrentGroup——含把 headerHeight 交给 currentGroupId
 * 的那条接线。落点自己对不对由 settings-focus-landing-fixture 量，不在本尺。
 *
 * 三格：①窄屏点每一组都亮那一组（主格，先红后绿）；②对照——滚到底亮最后一组照旧；
 * ③对照——宽屏点目录也对准那一组，且宽屏上不让位（上一组的尾巴还露着时照旧亮上一组）。
 * 若 headerHeight 又写成固定返回 0，① 会红（亮的又是上一组）。
 *
 * 由 SettingsClickHighlightTest 拉起。量的是源码树里 settings.js 那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {currentGroupId} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings.js'), 'utf8');

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

function loadFn(text, marker, params, args) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  return new Function(...params,
    body + '\nreturn ' + marker.slice(marker.indexOf(' ') + 1).replace(/\(.*/, '') + ';')(...args);
}

// ---------- 页面的数 ----------
// 窄屏：顶栏与组目录都吸在视口顶上，实测高度各 64 与 48。三个组紧挨着，
// 最后一组的底就是页底。视口 800 高、页 2400 高，还滚得动
const layout = {
  narrow: true,
  scroll: 0,
  viewport: 800,
  page: 2400,
  sideHeight: 64,
  navHeight: 48,
  groups: [
    {id: 'g1', top: 200, bottom: 900},
    {id: 'g2', top: 900, bottom: 1600},
    {id: 'g3', top: 1600, bottom: 2400},
  ],
};

/**
 * 吸顶那两块此刻有多高
 *
 * 夹具自己按桩上的数算：这是「点目录之后组标题落在哪」那一侧的口径
 * （见 focusGroup 的落点规矩），不是拿来替产品码答题的
 */
function stickyHeight() {
  return layout.narrow ? layout.sideHeight + layout.navHeight : 0;
}

// ---------- 最小 DOM 桩 ----------

function sectionStub(g) {
  return {
    tag: 'div',
    dataset: {grp: g.id},
    isConnected: true,
    classList: {contains: () => false},
    getBoundingClientRect() {
      return {top: g.top - layout.scroll, height: g.bottom - g.top};
    },
    boxHeight() {
      return g.bottom - g.top;
    },
  };
}

function linkStub(id) {
  const set = new Set();
  return {
    tag: 'a',
    attrs: {'data-grp-link': id},
    classList: {
      toggle(name, force) {
        if (force) set.add(name); else set.delete(name);
      },
      contains(name) {
        return set.has(name);
      },
    },
    getAttribute(key) {
      return key in this.attrs ? this.attrs[key] : null;
    },
  };
}

const sections = layout.groups.map(sectionStub);
const links = layout.groups.map(g => linkStub(g.id));
const groupsHost = {
  tag: 'div',
  id: 'groups',
  getBoundingClientRect: () => ({top: 0, height: 0}),
  boxHeight: () => 0,
};
// 窄屏吸顶的那两块：高度按桩上的数报
const sideHost = {
  tag: 'div',
  id: 'side',
  get offsetHeight() {
    return layout.sideHeight;
  },
};
const navHost = {
  tag: 'div',
  id: 'grp-nav',
  get offsetHeight() {
    return layout.navHeight;
  },
};
const fake$ = sel => (sel === '#side' ? sideHost : sel === '#grp-nav' ? navHost : null);
const documentElement = {
  get scrollHeight() {
    return layout.page;
  },
  style: {
    setProperty() {},
  },
};
const body = {
  get scrollHeight() {
    return layout.page;
  },
};
const fakeDocument = {
  documentElement,
  body,
  getElementById(id) {
    if (id === 'groups') return groupsHost;
    if (id === 'side') return sideHost;
    return null;
  },
  querySelectorAll(selector) {
    if (selector === '.setgrp[data-grp]') return sections;
    if (selector === '#grp-nav [data-grp-link]') return links;
    return [];
  },
};

const winListeners = new Map();
const fakeWindow = {
  get scrollY() {
    return layout.scroll;
  },
  get innerHeight() {
    return layout.viewport;
  },
  matchMedia: () => ({matches: layout.narrow}),
  addEventListener(type, fn) {
    const list = winListeners.get(type) || [];
    list.push(fn);
    winListeners.set(type, list);
  },
  removeEventListener(type, fn) {
    const list = winListeners.get(type) || [];
    const at = list.indexOf(fn);
    if (at >= 0) list.splice(at, 1);
  },
};

// ---------- 观察器与帧 ----------

function ResizeObserver(callback) {
  this.callback = callback;
  this.targets = new Set();
  this.disconnected = false;
}
ResizeObserver.prototype.observe = function (el) {
  this.targets.add(el);
};
ResizeObserver.prototype.unobserve = function (el) {
  this.targets.delete(el);
};
ResizeObserver.prototype.disconnect = function () {
  this.disconnected = true;
  this.targets.clear();
};

function IntersectionObserver(callback) {
  this.callback = callback;
  this.targets = new Set();
  this.disconnected = false;
}
IntersectionObserver.prototype.observe = function (el) {
  this.targets.add(el);
};
IntersectionObserver.prototype.disconnect = function () {
  this.disconnected = true;
  this.targets.clear();
};

let rafSeq = 0;
const rafPending = new Map();

function requestAnimationFrame(fn) {
  const id = ++rafSeq;
  rafPending.set(id, fn);
  return id;
}

function cancelAnimationFrame(id) {
  rafPending.delete(id);
}

function flushRaf() {
  for (let round = 0; round < 10 && rafPending.size > 0; round++) {
    const batch = [...rafPending.values()];
    rafPending.clear();
    for (const fn of batch) fn();
  }
}

// ---------- 抽出真函数跑 ----------
// 观察器那几个名字是模块里的共有状态，抽出的函数读写的是全局那几格
globalThis.groupWatcher = null;
globalThis.groupSizer = null;
globalThis.groupMarkFrame = 0;
globalThis.groupScroll = null;
globalThis.groupResize = null;

const stopWatchingGroups = loadFn(src, 'function stopWatchingGroups(',
  ['window', 'cancelAnimationFrame'], [fakeWindow, cancelAnimationFrame]);
const headerHeight = loadFn(src, 'function headerHeight(',
  ['window', '$'], [fakeWindow, fake$]);
const pageHeight = loadFn(src, 'function pageHeight(', ['document'], [fakeDocument]);
const syncSideHeight = loadFn(src, 'function syncSideHeight(',
  ['$', 'document'], [fake$, fakeDocument]);
const watchCurrentGroup = loadFn(src, 'function watchCurrentGroup(',
  ['stopWatchingGroups', 'document', 'window', 'IntersectionObserver', 'ResizeObserver',
    'requestAnimationFrame', 'currentGroupId', 'headerHeight', 'pageHeight', 'syncSideHeight'],
  [stopWatchingGroups, fakeDocument, fakeWindow, IntersectionObserver, ResizeObserver,
    requestAnimationFrame, currentGroupId, headerHeight, pageHeight, syncSideHeight]);

/** 目录此刻亮着哪一组 */
function highlight() {
  for (const link of links) {
    if (link.classList.contains('cur')) return link.getAttribute('data-grp-link');
  }
  return '';
}

/**
 * 点目录里的一组：把页滚到组标题刚露在吸顶栏下面，然后按此刻的位置亮一次
 *
 * 落点取的是 focusGroup 的口径（组标题落在吸顶那两块的下沿），因此这里只搬 scroll，
 * 不再跑一遍 focusGroup：落点自己对不对由 settings-focus-landing-fixture 量
 */
function clickGroup(id) {
  const g = layout.groups.find(one => one.id === id);
  layout.scroll = Math.max(0, g.top - stickyHeight());
  watchCurrentGroup();
  flushRaf();
  return highlight();
}

// ---------- ① 窄屏点目录里每一组，高亮都对准那一组 ----------
// 病：窄屏上点设置目录里的某一组，高亮停在上一组。点完的落点让出了吸顶的顶栏与目录
// （组标题落在它们下面），而「哪一组算当前」让出的是 0——上一组的尾巴还压在底下，
// 却被当成看得见、又最靠上。手动滚到同一个位置也一样
const clicks = {};
for (const g of layout.groups) clicks['点 ' + g.id + ' 后亮的'] = clickGroup(g.id);
eq(clicks, {
  '点 g1 后亮的': 'g1',
  '点 g2 后亮的': 'g2',
  '点 g3 后亮的': 'g3',
}, '① 窄屏点目录里的每一组，高亮都对准那一组');

// ---------- ② 对照：滚到底亮最后一组照旧 ----------
// 这条判定（还能往下滚且已滚到最底时亮最后一组）不该被这次改动碰坏
layout.scroll = layout.page - layout.viewport;
watchCurrentGroup();
flushRaf();
eq(highlight(), 'g3', '② 对照：滚到最底，照旧亮最后一组');

// ---------- ③ 对照：宽屏没有吸顶元素 ----------
// 点目录也对准那一组；上一组的尾巴还露在视口顶上时照旧亮上一组（宽屏让位量是 0）
layout.narrow = false;
const wideClick = clickGroup('g2');
layout.scroll = 800;
watchCurrentGroup();
flushRaf();
eq({
  '宽屏点 g2 后亮的': wideClick,
  '宽屏上一组尾巴还露着时亮的': highlight(),
}, {
  '宽屏点 g2 后亮的': 'g2',
  '宽屏上一组尾巴还露着时亮的': 'g1',
}, '③ 对照：宽屏上没有吸顶元素，点目录对准那一组，判「看得见」也不让位');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
