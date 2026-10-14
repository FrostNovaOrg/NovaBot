/**
 * 「高级选项」开关在组目录最底下：点得动，页上只有它一个；搜到高级里的项时目录那一段一起出来
 *
 * 抓的用户故障：
 * toggle：点了目录底下的「高级选项」没反应，或者页上有两个 id 为 show-advanced 的元素——
 *   按 id 取到的是排在前面那个没人点的，状态同步到它身上，人点的那颗一直写着「已隐藏」；
 * search：高级收着时搜一个只在高级里的设置项，正文里出来了，左边目录里「高级」那一段却还收着，
 *   点不到那一组，看起来像搜到的东西没处可去。
 *
 * 这把尺不另抄一份开合：settings.js 的 buildNav／buildNavLink／filterSettings／valuesOf／
 * filterKey／toggleAdvanced 按花括号配平切出来真跑，判可见用真 settings-model.js。
 * 页上静态写着几个 id="show-advanced"，就照 index.html 原样数出来，摆在目录前面的搜索条里
 * （与 index.html 里的先后一致），按 id 取元素照浏览器的规矩取文档里的第一个。
 * DOM 是桩：只认这几处用到的选择器。
 *
 * 用法：node settings-advanced-toggle-fixture.mjs toggle|search。由 SettingsAdvancedToggleTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {advancedShown, canonicalValue, defaultValue, isVisible, secretDraft}
  from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = name => readFileSync(join(ui, name), 'utf8');

const mode = process.argv[2] || '';
const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

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

function cut(text, marker) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('切不出 ' + marker);
  return body.replace(/^export /, '');
}

/** 给了的名字用给的，全局有的用全局的，其余一律是个调得动、取得到属性的桩；赋值落回给的那张表 */
function anything() {
  const self = new Proxy(function () {}, {
    get: (t, k) => (k === 'then' || typeof k === 'symbol' ? undefined : self),
    apply: () => self,
  });
  return self;
}

function scope(given) {
  const any = anything();
  return new Proxy(given, {
    has: (t, k) => typeof k === 'string' && (k in t || !(k in globalThis)),
    get: (t, k) => {
      if (k === Symbol.unscopables) return undefined;
      if (k in t) return t[k];
      return k in globalThis ? globalThis[k] : any;
    },
    set: (t, k, v) => {
      t[k] = v;
      return true;
    },
  });
}

function within(given, decls, names) {
  const body = 'with (scope) {\n' + decls.join('\n') + '\nreturn {' + names.join(', ') + '};\n}';
  return new Function('scope', body)(scope(given));
}

// ---------- DOM 桩 ----------

function node(tag, cls) {
  const classes = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  let html = '';
  const self = {
    tag, id: '', type: '', title: '', dataset: {}, textContent: '', value: '', checked: false,
    kids: [], attrs: {}, listeners: {}, parentElement: null,
    classList: {
      add: (...c) => c.forEach(x => classes.add(x)),
      remove: (...c) => c.forEach(x => classes.delete(x)),
      toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); },
      contains: c => classes.has(c),
    },
    get innerHTML() { return html; },
    set innerHTML(v) {
      html = String(v);
      if (!html) this.kids = [];
    },
    setAttribute(k, v) {
      this.attrs[k] = String(v);
      if (k.startsWith('data-')) {
        this.dataset[k.slice(5).replace(/-(\w)/g, (_, c) => c.toUpperCase())] = String(v);
      }
    },
    getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; },
    appendChild(child) { this.kids.push(child); child.parentElement = this; return child; },
    append(...children) { for (const c of children) this.appendChild(c); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    click() { for (const fn of this.listeners.click || []) fn({type: 'click'}); },
    contains(other) {
      for (let n = other; n; n = n.parentElement) if (n === this) return true;
      return false;
    },
    querySelectorAll(sel) { return findAll(this, sel); },
    querySelector(sel) { return findAll(this, sel)[0] || null; },
  };
  return self;
}

/** 只认这几种：.类、.类:not(.hide)、.类:not([data-ui])、.类[data-grp="x"]、[data-grp-link]、逗号并列 */
function matches(n, simple) {
  const m = /^(?:\.([\w-]+))?(?:\[([\w-]+)(?:="([^"]*)")?\])?(?::not\((?:\.([\w-]+)|\[([\w-]+)\])\))?$/.exec(simple.trim());
  if (!m) throw new Error('桩不认这个选择器：' + simple);
  const [, cls, attr, attrValue, notCls, notAttr] = m;
  if (cls && !n.classList.contains(cls)) return false;
  if (attr) {
    const key = attr.replace(/^data-/, '').replace(/-(\w)/g, (_, c) => c.toUpperCase());
    if (!(key in n.dataset)) return false;
    if (attrValue !== undefined && n.dataset[key] !== attrValue) return false;
  }
  if (notCls && n.classList.contains(notCls)) return false;
  if (notAttr && notAttr.replace(/^data-/, '') in n.dataset) return false;
  return true;
}

function findAll(root, sel) {
  const parts = sel.split(',').map(s => s.trim());
  const out = [];
  const walk = n => {
    for (const k of n.kids) {
      if (parts.some(p => matches(k, p))) out.push(k);
      walk(k);
    }
  };
  walk(root);
  return out;
}

/** 文档序里所有 id 为这一个的元素 */
function allWithId(root, id) {
  const out = [];
  const walk = n => {
    for (const k of n.kids) {
      if (k.id === id) out.push(k);
      walk(k);
    }
  };
  walk(root);
  return out;
}

/** 眼前看得见吗：自己和祖先都没挂 hide */
function seen(n) {
  for (let x = n; x; x = x.parentElement) if (x.classList.contains('hide')) return false;
  return true;
}

// ---------- 一页设置：推送（常用）与「采集 · 高级」 ----------

const indexHtml = src('index.html');
const settingsSrc = src('settings.js');
/** index.html 里静态写着几个 id="show-advanced"：组目录是脚本建的，静态的那几个都摆在它前面的搜索条里 */
const staticToggles = (indexHtml.match(/id="show-advanced"/g) || []).length;

function field(name, label, description) {
  return {name, label, description, widget: 'string', defaultValue: null, sensitive: false};
}

function row(f) {
  const r = node('div', 'field setitem');
  r.dataset.name = f.name;
  return r;
}

function group(id, fields) {
  const g = node('div', 'setgrp');
  g.dataset.grp = id;
  for (const f of fields) g.appendChild(row(f));
  return g;
}

function wire() {
  const quiet = field('novabot.core.push.quiet-start', '静音开始', '这段时间里不推送。');
  const snapshot = field('novabot.bilibili.live.snapshot-interval', '留档间隔', '多久存一次直播间快照。');
  const store = {dirty: {}, values: {}, ungrouped: [],
    schema: [{group: 'push', title: '推送', fields: [quiet]}, {group: 'collect', title: '采集', fields: [snapshot]}]};
  const arranged = {
    common: [{group: 'push', title: '推送', fields: [quiet]}],
    advanced: [{group: 'collect-advanced', title: '采集 · 高级', fields: [snapshot], advanced: true, base: 'collect'}],
  };

  const root = node('div');
  const setbar = node('div', 'setbar');
  const search = node('input');
  search.id = 'set-search';
  const onlyChanged = node('input');
  onlyChanged.id = 'only-changed';
  setbar.append(search, onlyChanged);
  for (let i = 0; i < staticToggles; i++) {
    const old = node('button', 'ghost settings-advanced-toggle');
    old.id = 'show-advanced';
    old.setAttribute('aria-expanded', 'false');
    setbar.appendChild(old);
  }
  const count = node('span', 'dim');
  count.id = 'set-count';
  setbar.appendChild(count);

  const nav = node('div', 'grpnav');
  nav.id = 'grp-nav';
  const groups = node('div');
  groups.id = 'groups';
  const pushGroup = group('push', [quiet]);
  const adv = node('section', 'settings-advanced hide');
  adv.id = 'adv-groups';
  const collectGroup = group('collect-advanced', [snapshot]);
  adv.appendChild(collectGroup);
  groups.append(pushGroup, adv);
  root.append(setbar, nav, groups);

  // 按 id 取：文档里第一个，与浏览器的 getElementById 同一条规矩
  const $ = sel => {
    const id = /^#([\w-]+)$/.exec(sel);
    return id ? (allWithId(root, id[1])[0] || null) : null;
  };
  const fakeDocument = {
    querySelectorAll(sel) {
      if (sel === '#grp-nav [data-grp-link]') return findAll(nav, '[data-grp-link]');
      if (sel.startsWith('#')) return [];
      return findAll(root, sel);
    },
    querySelector(sel) {
      if (sel === '#grp-nav .sep') return findAll(nav, '.sep')[0] || null;
      if (sel.startsWith('#')) return null;
      return findAll(root, sel)[0] || null;
    },
  };

  const given = {
    $, el: node, document: fakeDocument, store, arranged,
    isVisible, advancedShown, canonicalValue, defaultValue, secretDraft,
    filterCards: () => 0, filterAuthCards: () => 0, closeHelp() {}, watchCurrentGroup() {},
    focusGroup() {}, ADVANCED_EYE: '<svg></svg>',
    advancedChosen: false, advancedDismissed: null,
  };
  const fns = within(given, [
    cut(settingsSrc, 'function buildNavLink('),
    cut(settingsSrc, 'function buildNav('),
    cut(settingsSrc, 'function valuesOf('),
    cut(settingsSrc, 'export function filterSettings('),
    cut(settingsSrc, 'function filterKey('),
    cut(settingsSrc, 'function toggleAdvanced('),
  ], ['buildNav', 'filterSettings']);

  // renderGeneral 的末两步：先建目录，再按此刻的搜索词筛一遍
  fns.buildNav(arranged.common, arranged.advanced);
  fns.filterSettings();

  const link = id => findAll(nav, '[data-grp-link]').find(n => n.dataset.grpLink === id) || null;
  return {root, nav, adv, search, collectGroup, fns, link, sep: () => findAll(nav, '.sep')[0] || null};
}

/** 此刻屏上那几样：高级区、目录里高级那一段（分隔与那一组）、人点的那颗钮报的开合 */
function look(page, button) {
  const sep = page.sep();
  const link = page.link('collect-advanced');
  return {
    region: seen(page.adv),
    navSection: !!sep && seen(sep) && !!link && seen(link),
    expanded: button ? button.getAttribute('aria-expanded') : null,
  };
}

if (mode === 'toggle') {
  const page = wire();
  const all = allWithId(page.root, 'show-advanced');
  const last = page.nav.kids[page.nav.kids.length - 1] || null;
  eq({count: all.length, lastInNav: !!last && last.id === 'show-advanced'},
    {count: 1, lastInNav: true},
    'toggle① 页上 id 为 show-advanced 的元素恰好一个，就是组目录的最后一项');

  const button = last && last.id === 'show-advanced' ? last : null;
  eq(button && {tag: button.tag, type: button.type, word: button.innerHTML.includes('高级选项'),
    controls: String(button.getAttribute('aria-controls')).split(/\s+/).includes('adv-groups'),
    unreachable: button.getAttribute('tabindex') === '-1'},
  {tag: 'button', type: 'button', word: true, controls: true, unreachable: false},
  'toggle② 是真按钮（Tab 停得到，回车、空格由浏览器转成点击），写着「高级选项」，aria-controls 指向高级区');

  eq(look(page, button), {region: false, navSection: false, expanded: 'false'},
    'toggle③ 默认收着：高级区与目录里「高级」一段都不显示，钮报 aria-expanded=false');

  if (button) button.click();
  eq(look(page, button), {region: true, navSection: true, expanded: 'true'},
    'toggle④ 点目录底下的「高级选项」：高级区与目录里「高级」一段一起出现，这颗钮的 aria-expanded 变 true');
  eq(button && String(button.getAttribute('aria-label')).includes('已显示'), true,
    'toggle④ 读屏念的名字跟着说已显示');

  if (button) button.click();
  eq(look(page, button), {region: false, navSection: false, expanded: 'false'},
    'toggle⑤ 再点一下：两处一起收起，aria-expanded 回到 false');
} else if (mode === 'search') {
  const page = wire();
  const button = allWithId(page.root, 'show-advanced').pop() || null;
  eq(look(page, button), {region: false, navSection: false, expanded: 'false'},
    'search① 前提：高级收着，目录里「高级」一段也收着');

  page.search.value = '留档';
  page.fns.filterSettings();
  eq({...look(page, button), row: seen(page.collectGroup.kids[0])},
    {region: true, navSection: true, expanded: 'true', row: true},
    'search② 高级收着时搜「留档」（只在高级里）：高级区与目录里「高级」一段都摆出来，那一项看得见');

  page.search.value = '';
  page.fns.filterSettings();
  eq(look(page, button), {region: false, navSection: false, expanded: 'false'},
    'search③ 清空搜索：没点过开关，两处一起收回去');
} else {
  checks++;
  failures.push('不认识的用法：' + mode + '（要 toggle 或 search）');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
