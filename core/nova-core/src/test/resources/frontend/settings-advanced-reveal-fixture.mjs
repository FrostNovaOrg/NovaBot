/**
 * 高级收起时，搜到的、从别处点名跳来的项照样找得到；告警卡默认收起，点名或搜到时展开
 *
 * 抓的用户故障：高级默认收起以后，①搜「代理」命中的是高级里的一项，可它收着，
 * 看起来和没搜到一样；②从组目录点「采集 · 高级」，或从首页「告警有死角 → 去配」跳来，
 * 落点在收起的那一块或收起的卡里，看起来像点了没反应；③告警卡改成默认收起后，
 * 搜「Webhook 地址」只剩那张卡、却是收着的。
 *
 * 各格：①高级什么时候摆出来（纯函数 advancedShown）；②focusGroup 跳进高级里的一组时先把高级摆出来
 * 再量落点；③从别处点名（带 open）时展开那一组里的卡，组目录点过去不展开；
 * ④告警卡的壳是默认收起的折叠卡，标题行带状态药丸；⑤搜索命中卡里的内容就展开，清空搜索回到收起；
 * ⑥点名展开过的卡，清空搜索后仍展开（重画也照旧，跟着开合偏好走）。
 *
 * 由 SettingsAdvancedRevealTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {advancedShown} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const settingsSrc = readFileSync(join(ui, 'settings.js'), 'utf8');
const alertSrc = readFileSync(join(ui, 'settings-alert.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function guard(what, fn) {
  try {
    fn();
  } catch (err) {
    checks++;
    failures.push(what + ' 抛了：' + err.message);
  }
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

function loadFn(text, marker, names, values) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('切不出 ' + marker);
  const name = marker.replace(/^export /, '').replace(/^(async )?function /, '').replace(/\(.*/, '');
  return new Function(...names, body.replace(/^export /, '') + '\nreturn ' + name + ';')(...values);
}

/** 元素桩：类、属性、子节点、监听器，外加 contains 与按类取子节点 */
function node(tag, cls) {
  const classes = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  const self = {
    tag, dataset: {}, textContent: '', type: '', value: '', open: false, kids: [], attrs: {}, listeners: {},
    parentElement: null, style: {},
    classList: {
      add: (...c) => c.forEach(x => classes.add(x)),
      remove: (...c) => c.forEach(x => classes.delete(x)),
      toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); },
      contains: c => classes.has(c),
    },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    appendChild(child) { this.kids.push(child); child.parentElement = this; return child; },
    append(...children) { for (const c of children) this.appendChild(c); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    fire(type) { for (const fn of this.listeners[type] || []) fn({type}); },
    contains(other) {
      for (let n = other; n; n = n.parentElement) if (n === this) return true;
      return false;
    },
    querySelectorAll(sel) {
      const want = sel.split(':')[0].replace(/^\./, '');
      const notHide = sel.includes(':not(.hide)');
      const out = [];
      const walk = n => {
        for (const k of n.kids) {
          if (k.classList && k.classList.contains(want) && (!notHide || !k.classList.contains('hide'))) out.push(k);
          walk(k);
        }
      };
      walk(this);
      return out;
    },
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; },
    getBoundingClientRect: () => ({top: 0}),
  };
  return self;
}

// ---------- ① 高级什么时候摆出来 ----------
eq([
  advancedShown({matches: 12, filtering: false, chosen: false, dismissed: false}),
  advancedShown({matches: 2, filtering: true, chosen: false, dismissed: false}),
  advancedShown({matches: 0, filtering: true, chosen: false, dismissed: false}),
  advancedShown({matches: 12, filtering: false, chosen: true, dismissed: false}),
  advancedShown({matches: 2, filtering: true, chosen: false, dismissed: true}),
  advancedShown({matches: 0, filtering: false, chosen: true, dismissed: false}),
], [false, true, false, true, false, false],
'① 默认收起；搜到了就摆出来；搜不到不摆；点了显示就摆；这一次搜索上亲手收起过就不摆；一项不剩不摆');

// ---------- ②③ focusGroup ----------
guard('②③', () => {
  const groups = node('div');
  const adv = node('section', 'settings-advanced hide');
  const inAdv = node('div', 'setgrp');
  inAdv.dataset.grp = 'collect-advanced';
  adv.appendChild(inAdv);
  const alertGroup = node('div', 'setgrp');
  alertGroup.dataset.grp = 'alert';
  groups.append(alertGroup, adv);

  const order = [];
  const opened = [];
  const fake$ = sel => (sel === '#adv-groups' ? adv : null);
  const fakeDocument = {querySelector: sel => {
    const id = /data-grp="([^"]+)"/.exec(sel);
    return id ? (id[1] === 'alert' ? alertGroup : (id[1] === 'collect-advanced' ? inAdv : null)) : null;
  }};
  const fakeWindow = {scrollY: 0, scrollTo: () => order.push('scroll'), matchMedia: () => ({matches: false})};
  inAdv.getBoundingClientRect = () => {
    order.push(adv.classList.contains('hide') ? 'measure-while-hidden' : 'measure');
    return {top: 0};
  };
  const showAdvanced = () => {
    order.push('show');
    adv.classList.remove('hide');
  };
  const openCards = root => opened.push(root.dataset.grp);
  const focusGroup = loadFn(settingsSrc, 'export function focusGroup',
    ['$', 'document', 'window', 'headerHeight', 'showAdvanced', 'openCards'],
    [fake$, fakeDocument, fakeWindow, () => 0, showAdvanced, openCards]);

  focusGroup('collect-advanced');
  eq(order, ['show', 'measure', 'scroll'], '② 跳进收起的高级里的一组：先摆出来，再量落点、再滚');

  order.length = 0;
  focusGroup('alert');
  eq({order, opened}, {order: ['scroll'], opened: []}, '③ 组目录点「告警」：照旧只滚过去，不动卡的开合');
  focusGroup('alert', true);
  eq(opened, ['alert'], '③ 首页点名跳来（#/settings?card=alert）：展开那一组里的卡');
});

// ---------- ④⑤⑥ 告警卡 ----------
guard('④⑤⑥', () => {
  const store = {dirty: {}, values: {}};
  const box = node('div', 'alcards');
  box.id = 'alert-cards';
  const cardOpen = new Map();
  const shell = loadFn(alertSrc, 'function shell(', ['el', 'sendTest'], [node, () => {}]);
  const openCards = loadFn(alertSrc, 'export function openCards(', ['cardOpen'], [cardOpen]);
  const filterCards = loadFn(alertSrc, 'export function filterCards(', ['$', 'store', 'cardOpen'],
    [sel => (sel === '#alert-cards' ? box : null), store, cardOpen]);

  const hook = shell('webhook', 'Webhook', '推到手机上的通知类应用。');
  const hookKey = node('div', 'keyname');
  hookKey.textContent = 'novabot.core.alert.webhook-url';
  hook.body.appendChild(hookKey);
  hook.card.textContent = 'Webhook 地址 novabot.core.alert.webhook-url';
  const mail = shell('mail', '邮件', '慢一点。');
  const mailKey = node('div', 'keyname');
  mailKey.textContent = 'spring.mail.host';
  mail.body.appendChild(mailKey);
  mail.card.textContent = '邮件 服务器 spring.mail.host';
  box.append(hook.card, mail.card);

  eq({tag: hook.card.tag, fold: hook.card.classList.contains('al-fold'), open: hook.card.open,
    headTag: hook.head.tag, pillInHead: hook.head.kids.includes(hook.pill), pill: hook.pill.textContent},
  {tag: 'details', fold: true, open: false, headTag: 'summary', pillInHead: true, pill: '未配置'},
  '④ 卡是默认收起的折叠卡，标题行里带着状态药丸');

  filterCards('webhook 地址', false);
  eq([hook.card.open, mail.card.open, mail.card.classList.contains('hide')], [true, false, true],
    '⑤ 搜到 Webhook 卡里的内容：那张卡展开，没搜到的藏起');
  filterCards('', false);
  eq([hook.card.open, mail.card.open], [false, false], '⑤ 清空搜索：回到收起');

  openCards(box);
  filterCards('', false);
  eq([hook.card.open, mail.card.open], [true, true], '⑥ 点名展开过：清空搜索后照旧展开');
  // 保存后整页重画：卡是新建的，画完那一趟筛选（不带搜索词）按开合表把它摆回去
  const again = shell('webhook', 'Webhook', '重画出来的那一张');
  again.card.textContent = 'Webhook';
  box.kids = [again.card];
  filterCards('', false);
  eq(again.card.open, true, '⑥ 保存后重画：卡按开合偏好照旧展开，不收回去');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
