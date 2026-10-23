/**
 * 先搜过别的，再从首页点名跳来：目标那一组的行和卡都看得见，搜索框已清空
 *
 * 抓的用户故障：在设置页搜过 quiet，去首页点「告警有死角 → 去配」（#/settings?card=alert），
 * 或点名跳进高级里的一组。旧搜索还挂着，告警那一组的行、三张卡和整块高级照旧被筛掉，
 * 落点只揭掉了组标题的 hide——屏幕上是一个空组，看起来像点了没反应。
 *
 * 这把尺不另抄一份筛选：main.js 的 applyRoute／focusCard／load 与 settings.js 的
 * focusGroup／clearFilters／filterSettings／valuesOf／showAdvanced／filterKey、
 * settings-alert.js 的 shell／openCards／filterCards 都按花括号配平切出来真跑，
 * 判可见用真 settings-model.js。DOM 是桩：只认这几处用到的选择器。
 * 与本问无关的那一大堆（画导航、取数、插件页）由一个「什么都接得住」的作用域兜着。
 *
 * 各格：①先搜 quiet，从首页点名跳到告警：搜索框清空，告警组的行与两张卡都看得见、卡展开；
 * ②先搜 quiet（并开着「只看改过」），从首页点名跳进高级里的一组：高级摆出来，那一组的行看得见；
 * ③对照——保存、放弃之后的重画（load）也按地址栏落点，那一趟不清搜索词；
 * ④对照——设置页里自己点组目录，照旧不清搜索词。
 *
 * 由 SettingsNamedJumpTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {advancedShown, canonicalValue, defaultValue, isVisible, secretDraft}
  from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = name => readFileSync(join(ui, name), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function guard(what, fn) {
  return Promise.resolve().then(fn).catch(err => {
    checks++;
    failures.push(what + ' 抛了：' + (err && err.message ? err.message : String(err)));
  });
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

/** 产品里可有可无的帮手：没有就不放，叫到它的地方落到作用域的桩上，红在行为上而不是红在切不出 */
function cutIf(text, marker) {
  return bracedFrom(text, marker).replace(/^export /, '');
}

/**
 * 「什么都接得住」的作用域：给了的名字用给的，全局有的用全局的，其余一律是个
 * 调得动、取得到属性、取到的还是它自己的桩。赋值落回给的那张表（applyRoute 会改 route）。
 */
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

/** 把一串声明放进作用域里，交回 names 里点到的那几个 */
function within(given, decls, names) {
  const body = 'with (scope) {\n' + decls.join('\n') + '\nreturn {' + names.join(', ') + '};\n}';
  return new Function('scope', body)(scope(given));
}

// ---------- DOM 桩 ----------

function node(tag, cls) {
  const classes = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  const self = {
    tag, id: '', dataset: {}, textContent: '', type: '', value: '', checked: false, open: false,
    kids: [], attrs: {}, listeners: {}, parentElement: null, style: {},
    classList: {
      add: (...c) => c.forEach(x => classes.add(x)),
      remove: (...c) => c.forEach(x => classes.delete(x)),
      toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); },
      contains: c => classes.has(c),
    },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; },
    appendChild(child) { this.kids.push(child); child.parentElement = this; return child; },
    append(...children) { for (const c of children) this.appendChild(c); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    contains(other) {
      for (let n = other; n; n = n.parentElement) if (n === this) return true;
      return false;
    },
    querySelectorAll(sel) { return findAll(this, sel); },
    querySelector(sel) { return findAll(this, sel)[0] || null; },
    getBoundingClientRect: () => ({top: 0}),
  };
  return self;
}

/** 只认这几种：.类、.类:not(.hide)、.类:not([data-ui])、.类[data-grp="x"]、[data-grp="x"]、[data-grp-link]、逗号并列 */
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
  if (notAttr) {
    const key = notAttr.replace(/^data-/, '');
    if (key in n.dataset) return false;
  }
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

// ---------- 一页设置：推送（常用）、告警（常用，带两张卡）、采集 · 高级 ----------

const store = {dirty: {}, values: {}, schema: [], ungrouped: []};
const byId = {};

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

let page = null;

function build(alertSrc) {
  for (const k of Object.keys(byId)) delete byId[k];
  const push = [field('novabot.core.push.quiet-start', '静音开始', '这段时间里不推送。'),
    field('novabot.core.push.enabled', '推送总开关', '关了全停。')];
  const alert = [field('novabot.core.alert.enabled', '告警总开关', '出故障时通知你。')];
  const collect = [field('novabot.bilibili.live.snapshot-interval', '留档间隔', '多久存一次。')];
  store.schema = [
    {group: 'push', title: '推送', fields: push},
    {group: 'alert', title: '告警', fields: alert},
    {group: 'collect', title: '采集', fields: collect},
  ];

  const search = node('input');
  search.id = 'set-search';
  const onlyChanged = node('input');
  onlyChanged.id = 'only-changed';
  const count = node('span');
  const toggle = node('button');
  const groups = node('div');
  const pushGroup = group('push', push);
  const alertGroup = group('alert', alert);
  const cards = node('div', 'alcards');
  alertGroup.appendChild(cards);
  const adv = node('section', 'settings-advanced hide');
  const collectGroup = group('collect-advanced', collect);
  adv.appendChild(collectGroup);
  groups.append(pushGroup, alertGroup, adv);

  const nav = node('nav');
  for (const id of ['push', 'alert', 'collect-advanced']) {
    const link = node('a');
    link.dataset.grpLink = id;
    link.attrs['data-grp-link'] = id;
    nav.appendChild(link);
  }
  const sep = node('div', 'sep');
  nav.appendChild(sep);

  Object.assign(byId, {'set-search': search, 'only-changed': onlyChanged, 'set-count': count,
    'show-advanced': toggle, 'adv-groups': adv, 'alert-cards': cards, 'grp-nav': nav});

  const cardOpen = new Map();
  const alertFns = within({el: node, sendTest: () => {}, cardOpen, store, $: sel => $(sel)},
    [cut(alertSrc, 'function shell('), cut(alertSrc, 'export function openCards('),
      cut(alertSrc, 'export function filterCards(')],
    ['shell', 'openCards', 'filterCards']);
  const hook = alertFns.shell('webhook', 'Webhook', '推到手机上的通知类应用。');
  const hookKey = node('div', 'keyname');
  hookKey.textContent = 'novabot.core.alert.webhook-url';
  hook.body.appendChild(hookKey);
  hook.card.textContent = 'Webhook 地址 novabot.core.alert.webhook-url';
  const mail = alertFns.shell('mail', '邮件', '慢一点。');
  const mailKey = node('div', 'keyname');
  mailKey.textContent = 'spring.mail.host';
  mail.body.appendChild(mailKey);
  mail.card.textContent = '邮件 服务器 spring.mail.host';
  cards.append(hook.card, mail.card);

  page = {search, onlyChanged, pushGroup, alertGroup, collectGroup, adv, cards: [hook.card, mail.card],
    root: groups, nav, alertFns};
  return page;
}

function $(sel) {
  const id = /^#([\w-]+)$/.exec(sel);
  return id ? (byId[id[1]] || null) : null;
}

const fakeDocument = {
  querySelectorAll(sel) {
    if (sel === '#grp-nav [data-grp-link]') return findAll(page.nav, '[data-grp-link]');
    if (sel.startsWith('#')) return [];
    return findAll(page.root, sel);
  },
  querySelector(sel) {
    if (sel === '#grp-nav .sep') return findAll(page.nav, '.sep')[0] || null;
    if (sel.startsWith('#')) return null;
    return findAll(page.root, sel)[0] || null;
  },
  documentElement: {classList: {toggle() {}, contains: () => false}},
};
const fakeWindow = {scrollY: 0, scrollTo() {}, matchMedia: () => ({matches: false})};

const settingsSrc = src('settings.js');
const mainSrc = src('main.js');
const alertSrc = src('settings-alert.js');

/** 一套真函数接到这一页上；hash 是此刻地址栏解析出来的样子 */
function wire(hash) {
  build(alertSrc);
  const given = {
    $, el: node, document: fakeDocument, window: fakeWindow, store,
    isVisible, advancedShown, canonicalValue, defaultValue, secretDraft,
    filterCards: page.alertFns.filterCards, openCards: page.alertFns.openCards,
    filterAuthCards: () => 0, closeHelp() {}, watchCurrentGroup() {}, headerHeight: () => 0,
    focusStation() {}, location: {hash: ''}, parseHash: () => hash.now, pages: [],
    route: 'home', later: false, homeStatus: null, homeLogin: null, shouldOpenSetup: () => false,
    arranged: {common: [], advanced: [{group: 'collect-advanced', fields: [{}]}]},
    advancedChosen: false, advancedDismissed: null,
    api: async () => ({groups: store.schema, ungrouped: [], values: {}}),
  };
  const fns = within(given, [
    cut(settingsSrc, 'export function focusGroup'),
    cutIf(settingsSrc, 'function clearFilters('),
    cut(settingsSrc, 'function valuesOf('),
    cut(settingsSrc, 'export function filterSettings('),
    cut(settingsSrc, 'function filterKey('),
    cut(settingsSrc, 'function showAdvanced('),
    cut(settingsSrc, 'function toggleAdvanced('),
    cut(settingsSrc, 'function buildNavLink('),
    cut(mainSrc, 'function focusCard('),
    cut(mainSrc, 'function applyRoute('),
    cut(mainSrc, 'export async function load('),
  ], ['focusGroup', 'filterSettings', 'applyRoute', 'load', 'buildNavLink', 'toggleAdvanced']);
  // 设置页重画那一步：真 renderGeneral 拆了重建，末尾按框里此刻的搜索词筛一遍。这里只留筛那一下
  given.renderGeneral = () => fns.filterSettings();
  return fns;
}

/** 眼前看得见吗：自己和祖先都没挂 hide */
function seen(n) {
  for (let x = n; x; x = x.parentElement) if (x.classList.contains('hide') || x.classList.contains('solo-hide')) return false;
  return true;
}

function typeSearch(fns, text, onlyChanged) {
  page.search.value = text;
  page.onlyChanged.checked = !!onlyChanged;
  fns.filterSettings();
}

// ---------- ① 先搜 quiet，从首页点名跳到告警 ----------
await guard('①', () => {
  const hash = {now: {name: 'settings', card: ''}};
  const fns = wire(hash);
  fns.filterSettings();
  typeSearch(fns, 'quiet');
  eq(seen(page.alertGroup.kids[0]), false, '① 前提：搜着 quiet 时告警那一行确实被筛掉');
  hash.now = {name: 'home', card: ''};
  fns.applyRoute();
  hash.now = {name: 'settings', card: 'alert'};
  fns.applyRoute();
  eq({search: page.search.value, row: seen(page.alertGroup.kids[0]),
    cards: page.cards.map(c => ({seen: seen(c), open: c.open}))},
  {search: '', row: true, cards: [{seen: true, open: true}, {seen: true, open: true}]},
  '① 先搜过 quiet，从首页点名跳到告警：搜索框清空，告警组的行与两张卡都看得见、卡展开');
});

// ---------- ② 先搜 quiet 并开着只看改过，从首页点名跳进高级里的一组 ----------
await guard('②', () => {
  const hash = {now: {name: 'settings', card: ''}};
  const fns = wire(hash);
  fns.filterSettings();
  typeSearch(fns, 'quiet', true);
  hash.now = {name: 'home', card: ''};
  fns.applyRoute();
  hash.now = {name: 'settings', card: 'collect-advanced'};
  fns.applyRoute();
  eq({search: page.search.value, onlyChanged: page.onlyChanged.checked,
    adv: seen(page.adv), row: seen(page.collectGroup.kids[0])},
  {search: '', onlyChanged: false, adv: true, row: true},
  '② 先搜过 quiet（开着只看改过），从首页点名跳进「采集 · 高级」：筛选清掉，高级摆出来，那一行看得见');
});

// ---------- ③ 保存、放弃后的重画也按地址栏落点：那一趟不清搜索词 ----------
await guard('③', async () => {
  const hash = {now: {name: 'settings', card: 'alert'}};
  const fns = wire(hash);
  fns.filterSettings();
  typeSearch(fns, 'quiet');
  await fns.load({keepDrafts: true});
  eq(page.search.value, 'quiet', '③ 对照：地址栏带着 card=alert 时保存（整体载入后重画），搜索词不被清掉');
});

// ---------- ④ 设置页里自己点组目录：照旧不清 ----------
await guard('④', () => {
  const fns = wire({now: {name: 'settings', card: ''}});
  fns.filterSettings();
  typeSearch(fns, 'quiet');
  fns.focusGroup('push');
  eq(page.search.value, 'quiet', '④ 对照：设置页里点组目录，搜索词照旧留着');
});

// 点目录只看推送后，仍能打开高级和从首页跳到告警，不能留下空白。
await guard('目录选择与后续导航', () => {
  const fns = wire({now: {name: 'settings', card: ''}});
  const link = fns.buildNavLink({group: 'push', title: '推送'});
  link.listeners.click[0]();
  eq({push: seen(page.pushGroup), alert: seen(page.alertGroup)},
    {push: true, alert: false}, '目录选择只显示选中的组');
  typeSearch(fns, '告警');
  eq(seen(page.alertGroup.kids[0]), true, '搜索可显示目录选择隐藏的其他组');
  typeSearch(fns, '');
  fns.toggleAdvanced();
  eq({advanced: seen(page.adv), row: seen(page.collectGroup.kids[0])},
    {advanced: true, row: true}, '选择一组后仍能打开高级并查看其中的项');
});

await guard('从首页跳到另一组', () => {
  const fns = wire({now: {name: 'settings', card: ''}});
  fns.buildNavLink({group: 'push', title: '推送'}).listeners.click[0]();
  fns.focusGroup('alert', true, true);
  eq(seen(page.alertGroup.kids[0]), true, '先选择推送组，再从首页点名跳到告警，目标仍可见');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
