/**
 * 设置页收起的组：搜索与筛选仍能找到里面那一项
 *
 * 抓的用户故障：组在手机上默认收起以后，搜「重试」明明命中了收着的那一项，
 * 可屏幕上只有一排组名——搜到了却看不见，等于搜不到；「只看改过」同理。
 * 筛着东西的时候，有命中的组要先放出来，命中的那行看得见；筛完再按原来收放复原，
 * 不因为搜过一次就把手机上的收起全放开了。
 *
 * 三格：①有命中的组放出来、命中的那行看得见、没命中的行照旧藏（这一格先红后绿）；
 * ②「只看改过」同一条规矩；③筛完回到原来的收放。
 *
 * 由 SettingsGroupFoldTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {
  CLEAR, canonicalValue, defaultText, defaultValue, secretDraft, isVisible,
} from '../../../main/resources/config-ui/settings-model.js';

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

/** 最小元素桩：类名、data-* 与后代查询够筛选用 */
function node(tag, cls) {
  const classes = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  return {
    tag, className: cls || '', dataset: {}, textContent: '', value: '', checked: false,
    kids: [], attrs: {},
    classList: {
      add: (...c) => c.forEach(x => classes.add(x)),
      remove: (...c) => c.forEach(x => classes.delete(x)),
      toggle(c, on) {
        if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c);
      },
      contains: c => classes.has(c),
    },
    setAttribute(key, value) { this.attrs[key] = String(value); },
    getAttribute(key) { return this.attrs[key]; },
    appendChild(child) { this.kids.push(child); return child; },
    append(...children) { this.kids.push(...children); },
    querySelectorAll(selector) {
      const out = [];
      for (const n of walk(this)) {
        if (selector.includes('.setitem') && n.classList.contains('setitem') && !n.classList.contains('hide')) out.push(n);
        if (selector.includes('.alcard') && n.classList.contains('alcard') && !n.classList.contains('hide')) out.push(n);
      }
      return out;
    },
    querySelector() { return null; },
  };
}

function walk(root) {
  const out = [];
  for (const child of (root && root.kids) || []) {
    out.push(child, ...walk(child));
  }
  return out;
}

/** 一页：一组收着的设置项（data-fold='1'）加一只搜索框 */
function page(fields, query, onlyChanged, values) {
  const store = {
    values: Object.assign({}, values), dirty: {}, legacy: {},
    schema: [{group: 'service', fields}], ungrouped: [],
  };
  const rows = fields.map(field => {
    const row = node('div', 'field setitem');
    row.dataset.name = field.name;
    row.dataset.changed = store.values[field.name] !== field.defaultValue ? '1' : '0';
    return row;
  });
  const group = node('div', 'group setgrp');
  group.dataset.grp = 'service';
  group.dataset.fold = '1';
  for (const row of rows) group.appendChild(row);
  const search = {value: query || ''};
  const only = {checked: !!onlyChanged};
  const count = node('span');
  const controls = {
    '#set-search': search, '#only-changed': only, '#set-count': count,
    '#adv-groups': null, '#settings-danger': null, '#adv-nav': null, '#show-advanced': null,
  };
  const document = {
    querySelectorAll(selector) {
      if (selector === '.setitem') return rows;
      if (selector.startsWith('.setgrp')) return [group];
      return [];
    },
    querySelector() { return null; },
  };
  const valuesOf = field => ({saved: store.values[field.name] ?? '', text: store.values[field.name] ?? '',
    current: store.values[field.name] ?? '', cleared: false});
  const body = bracedFrom(src, 'function filterSettings(');
  if (!body) throw new Error('切不出 filterSettings');
  const filterSettings = new Function('$', 'document', 'store', 'closeHelp', 'filterCards',
    'filterAuthCards', 'filterMembers', 'advancedShown', 'filterKey', 'watchCurrentGroup', 'arranged',
    'isVisible', 'valuesOf',
    'let advancedDismissed = null, advancedChosen = false;\n' + body + '\nreturn filterSettings;')(
    key => controls[key] ?? null, document, store, () => {}, () => 0, () => 0, () => 0,
    () => false, () => '', () => {}, {common: [{fields}], advanced: []}, isVisible, valuesOf);
  return {group, rows, filterSettings};
}

const fields = [
  {
    name: 'novabot.bilibili.network.api-retry-interval', label: '重试间隔', widget: 'integer',
    description: '接口失败后隔多久再试，单位：毫秒。', defaultValue: '3000', effect: 'RESTART',
    sensitive: false, group: 'service', order: 0,
  },
  {
    name: 'novabot.core.live.detail-retention-days', label: '明细保留', widget: 'integer',
    description: '直播数据明细保留多少天。', defaultValue: '0', effect: 'RESTART',
    sensitive: false, group: 'service', order: 1,
  },
];

// ---------- ① 收着的组里有命中：放出来，那一行看得见 ----------
{
  const p = page(fields, '重试间隔');
  p.filterSettings();
  eq({fold: p.group.dataset.fold, groupHidden: p.group.classList.contains('hide'),
    rows: p.rows.map(r => r.classList.contains('hide') ? '藏' : '见')},
    {fold: '0', groupHidden: false, rows: ['见', '藏']},
    '① 搜「重试间隔」：收着的那一组放出来，命中的那行看得见，没命中的照旧藏');
}

// ---------- ② 「只看改过」同一条规矩 ----------
{
  const values = {
    'novabot.bilibili.network.api-retry-interval': '3000',
    'novabot.core.live.detail-retention-days': '7',
  };
  const p = page(fields, '', true, values);
  p.filterSettings();
  eq({fold: p.group.dataset.fold, rows: p.rows.map(r => r.classList.contains('hide') ? '藏' : '见')},
    {fold: '0', rows: ['藏', '见']},
    '② 只看改过：收着的那一组放出来，改过的那行看得见');
}

// ---------- ③ 筛完回到原来的收放 ----------
{
  const p = page(fields, '重试间隔');
  p.filterSettings();
  const opened = p.group.dataset.fold;
  const clear = page(fields, '');
  clear.group.dataset.fold = '1';
  clear.filterSettings();
  eq({searching: opened, afterClear: clear.group.dataset.fold,
    rows: clear.rows.map(r => r.classList.contains('hide') ? '藏' : '见')},
    {searching: '0', afterClear: '1', rows: ['见', '见']},
    '③ 筛完回到原来的收放：手机上仍是收着的，各行恢复');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
