/**
 * 设置页导航高亮：整页高度变了要重判
 *
 * 抓的用户故障：设置页上方提示条出现或收起（页底那块变高变矮一个样），页面没滚动，
 * 左边导航还亮着先前那一组——「已经滚到最底就亮最后一组」那条判定读的是整页高度，
 * 整页高度变了却不重判，亮着的那一组是按旧高度算出来的。
 *
 * 三格：① #groups 以外的内容变高、不滚动，高亮跟着重判；阳性对照（桩真跑得起来、
 * 亮得出一组）；拆页收尾（观察器断得干净，页面切走不留着空转）。
 *
 * 场景的数：视口高 800、已滚到 500、页面高 1300（正好在最底），三个组 200-600、
 * 600-1000、1000-1300 全在视口 [500,1300] 里。提示条长高 100 后三个组整体下移 100、
 * 页面高变 1400：#groups 自己的盒子高度不变，没有任何一组进出视口，页面一格没滚。
 * 按现有判定，提示条出现前亮最后一组 g3，出现后该亮看得见的最上面那组 g1。
 *
 * 由 SettingsNavHighlightTest 拉起。量的是源码树里 settings.js 那一份，不是构建产物里的副本。
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
// 提示条在 #groups 之外。它长高 100，整页变高、三个组下移，#groups 自己的盒子高度不变
const layout = {
  scroll: 500,
  viewport: 800,
  page: 1300,
  groupsTop: 200,
  groupsHeight: 1100,
  groups: [
    {id: 'g1', top: 200, bottom: 600},
    {id: 'g2', top: 600, bottom: 1000},
    {id: 'g3', top: 1000, bottom: 1300},
  ],
};

function bannerAppears() {
  layout.page += 100;
  layout.groupsTop += 100;
  for (const g of layout.groups) {
    g.top += 100;
    g.bottom += 100;
  }
}

// ---------- 最小 DOM 桩 ----------
// 每个桩带 boxHeight()：被观察的那一层尺寸变了才对应回调，与真的 ResizeObserver 同规矩。
// 「提示条长高」只改整页高度与各组坐标，#groups 的盒子高度不动。

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
  getBoundingClientRect() {
    return {top: layout.groupsTop - layout.scroll, height: layout.groupsHeight};
  },
  boxHeight() {
    return layout.groupsHeight;
  },
};
const documentElement = {
  get scrollHeight() {
    return layout.page;
  },
  boxHeight() {
    return layout.page;
  },
};
const body = {
  get scrollHeight() {
    return layout.page;
  },
  boxHeight() {
    return layout.page;
  },
};
const fakeDocument = {
  documentElement,
  body,
  getElementById(id) {
    return id === 'groups' ? groupsHost : null;
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
// 两个观察器的实例都收着，断没断开由拆页那格看
const roInstances = [];
const ioInstances = [];

function ResizeObserver(callback) {
  this.callback = callback;
  this.targets = new Set();
  this.sizes = new Map();
  this.disconnected = false;
  roInstances.push(this);
}
ResizeObserver.prototype.observe = function (el) {
  this.targets.add(el);
  this.sizes.set(el, el.boxHeight());
};
ResizeObserver.prototype.unobserve = function (el) {
  this.targets.delete(el);
  this.sizes.delete(el);
};
ResizeObserver.prototype.disconnect = function () {
  this.disconnected = true;
  this.targets.clear();
  this.sizes.clear();
};

function IntersectionObserver(callback, options) {
  this.callback = callback;
  this.options = options;
  this.targets = new Set();
  this.disconnected = false;
  ioInstances.push(this);
}
IntersectionObserver.prototype.observe = function (el) {
  this.targets.add(el);
};
IntersectionObserver.prototype.disconnect = function () {
  this.disconnected = true;
  this.targets.clear();
};

/** 尺寸变了的那几层对应回调（页面布局变了之后调，跟真观察器同规矩） */
function notifyResizeObservers() {
  for (const inst of roInstances) {
    if (inst.disconnected) continue;
    let changed = false;
    for (const el of inst.targets) {
      const now = el.boxHeight();
      if (inst.sizes.get(el) !== now) {
        inst.sizes.set(el, now);
        changed = true;
      }
    }
    if (changed) inst.callback();
  }
}

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
const headerHeight = loadFn(src, 'function headerHeight(', [], []);
const pageHeight = loadFn(src, 'function pageHeight(', ['document'], [fakeDocument]);
const watchCurrentGroup = loadFn(src, 'function watchCurrentGroup(',
  ['stopWatchingGroups', 'document', 'window', 'IntersectionObserver', 'ResizeObserver',
    'requestAnimationFrame', 'currentGroupId', 'headerHeight', 'pageHeight'],
  [stopWatchingGroups, fakeDocument, fakeWindow, IntersectionObserver, ResizeObserver,
    requestAnimationFrame, currentGroupId, headerHeight, pageHeight]);

/** 左边导航此刻亮着哪一组 */
function highlight() {
  for (const link of links) {
    if (link.classList.contains('cur')) return link.getAttribute('data-grp-link');
  }
  return '';
}

// ---------- 走一遍 ----------
let initial = 'missing';
let afterBanner = 'missing';
let teardown = 'missing';
try {
  watchCurrentGroup();
  flushRaf();
  initial = highlight();

  bannerAppears();
  notifyResizeObservers();
  flushRaf();
  afterBanner = highlight();

  stopWatchingGroups();
  const roLeft = roInstances.filter(inst => !inst.disconnected).length;
  const ioLeft = ioInstances.filter(inst => !inst.disconnected).length;
  teardown = roInstances.length > 0 && roLeft === 0 && ioLeft === 0
    ? true
    : '尺寸观察器 ' + roInstances.length + ' 个没断 ' + roLeft
      + '，交集观察器 ' + ioInstances.length + ' 个没断 ' + ioLeft;
} catch (e) {
  initial = 'error:' + e.message;
  afterBanner = 'error:' + e.message;
  teardown = 'error:' + e.message;
}

// 阳性对照：桩真跑得起来，亮得出一组（没夹具空跑）
eq(initial, 'g3', '阳性对照：滚到最底时亮最后一组');

// ---------- ① #groups 以外的内容变高、不滚动，高亮跟着重判 ----------
// 病：提示条出现或收起、页面一格没滚，左边导航还亮着先前那一组
eq(afterBanner, 'g1', '① #groups 以外的内容变高、页面没滚动，高亮跟着重判');

// 拆页收尾：观察器断得干净（页面切走不留着空转）
eq(teardown, true, '拆页时观察器断得干净');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
