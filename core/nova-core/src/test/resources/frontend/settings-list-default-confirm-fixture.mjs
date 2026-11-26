/**
 * 设置页列表项的默认值与「保存前先确认」
 *
 * 抓的用户故障：推送接口「允许的来源 IP」默认只放行本机两条，设置页却画成空框。
 * 用户以为是「往里加一条」的框，只填一个外部地址就保存；这一项保存即生效，本机上的程序
 * 从下一个请求起就推不进来，而且没有任何提示。
 *
 * 前五格量行（buildRow 切出来跑，字段表给的默认值取服务端现在回的那个形状）：
 * ①配置文件没写这一项时框里是本机两条；②「默认：」那行也写这两条；
 * ③没动这一项不进改动账（保存整页时不会发它）；④在下面加一行才进改动账；
 * ⑤换了名单再点「恢复默认」填回两条、改动账清掉。
 * 后三格量保存（save 与 confirmAll 切出来跑，接口由夹具攥着）：
 * ⑥服务端回「要先问」、点取消：只发了一趟、不带确认，草稿留着，状态栏说未保存；
 * ⑦点确定：带着确认把同一份再送一次，之后照常重取；⑧不用问的：只发一趟。
 *
 * 由 SettingsListDefaultConfirmTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {
  CLEAR, canonicalValue, dangerOf, defaultText, defaultValue, effectOf, isChanged, isDangerous,
  secretDraft,
} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings.js'), 'utf8');

const KEY = 'novabot.adapter.onebot.security.allow-ips';
const LOOPBACK = ['127.0.0.1/32', '::1/128'];
const QUESTION = '新名单里没有本机地址（127.0.0.1、::1），本机上的程序再推送会被挡住。确定这样保存吗？';

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

/** 切出 async 函数的函数体，包成异步函数（体里有 await，套普通 Function 会炸） */
function loadAsyncFn(text, marker, params, args, formals) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  const inner = body.slice(body.indexOf('{') + 1, body.lastIndexOf('}'));
  return new Function(...params, 'return async ' + (formals || '()') + ' => {' + inner + '}')(...args);
}

/** 最小元素桩：记下属性、子节点与监听器 */
function node(tag, cls) {
  return {
    tag, className: cls || '', dataset: {},
    textContent: '', type: '', value: '', placeholder: '', title: '',
    autocomplete: '', name: '', disabled: false, checked: false,
    attrs: {}, kids: [], listeners: {}, style: {},
    classList: {toggle() {}, add() {}},
    setAttribute(key, value) { this.attrs[key] = String(value); },
    appendChild(child) { this.kids.push(child); return child; },
    append(...children) { this.kids.push(...children); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
  };
}

function collect(root, test, out) {
  const acc = out || [];
  if (root && test(root)) acc.push(root);
  for (const child of (root && root.kids) || []) collect(child, test, acc);
  return acc;
}

/** 字段表里这一项，默认值是服务端现在回的形状：一组字 */
const field = {
  name: KEY, label: '推送接口 · 允许的来源 IP', widget: 'list',
  description: '允许调用推送接口的来源 IP 白名单。', summary: null, help: null,
  defaultValue: LOOPBACK, effect: 'IMMEDIATE', sensitive: false, group: 'push', order: 0,
};

function build(values) {
  const store = {values, dirty: {}, legacy: {}, fontTables: {}};
  const valuesOf = loadFn(src, 'function valuesOf(',
    ['store', 'canonicalValue', 'defaultValue', 'secretDraft', 'CLEAR'],
    [store, canonicalValue, defaultValue, secretDraft, CLEAR]);
  const buildControl = loadFn(src, 'function buildControl(',
    ['el', 'bindPasswordReveal', 'switchControl'], [node, () => {}, () => {}]);
  const buildRow = loadFn(src, 'function buildRow(',
    ['el', 'isDangerous', 'store', 'defaultText', 'valuesOf', 'buildControl', 'effectOf',
      'isChanged', 'defaultValue', 'markDirty', 'ask', 'dangerOf', 'secretDraft', 'CLEAR', 'canonicalValue'],
    [node, isDangerous, store, defaultText, valuesOf, buildControl, effectOf,
      isChanged, defaultValue, () => {}, () => true, dangerOf, secretDraft, CLEAR, canonicalValue]);
  return {row: buildRow(field, false), store};
}

const box = row => collect(row, n => n.tag === 'textarea')[0];

async function type(row, text) {
  const input = box(row);
  input.value = text;
  for (const fn of input.listeners.input || []) await fn();
}

async function clickReset(row) {
  const reset = collect(row, n => n.tag === 'button' && n.textContent === '恢复默认')[0];
  for (const fn of (reset && reset.listeners.click) || []) await fn();
}

// ---------- ① 框里是本机两条 ----------
{
  let seen = 'missing';
  try {
    const input = box(build({}).row);
    seen = input ? input.value : 'no textarea';
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, LOOPBACK.join('\n'), '① 配置文件没写这一项：框里是 127.0.0.1/32、::1/128，不是空框');
}

// ---------- ② 「默认：」那行 ----------
{
  let seen = 'missing';
  try {
    seen = collect(build({}).row, n => typeof n.textContent === 'string' && n.textContent.startsWith('默认：'))
      .map(n => n.textContent);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, ['默认：127.0.0.1/32、::1/128 · '], '② 「默认：」那行写本机两条，不写「空」');
}

// ---------- ③ 没动不进改动账 ----------
{
  let seen = 'missing';
  try {
    const {row, store} = build({});
    await type(row, box(row).value);
    seen = Object.keys(store.dirty);
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, [], '③ 没动这一项：不进改动账，保存整页时不会把它写进配置文件');
}

// ---------- ④ 加一行才进改动账 ----------
{
  let seen = 'missing';
  try {
    const {row, store} = build({});
    await type(row, box(row).value + '\n203.0.113.5');
    seen = store.dirty[KEY];
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, '127.0.0.1/32\n::1/128\n203.0.113.5', '④ 在默认两条下面加一行：送去保存的是三条');
}

// ---------- ⑤ 恢复默认 ----------
{
  let seen = 'missing';
  try {
    const {row, store} = build({[KEY]: '203.0.113.5'});
    await clickReset(row);
    seen = {box: box(row).value, draft: store.dirty[KEY]};
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, {box: LOOPBACK.join('\n'), draft: LOOPBACK.join('\n')},
    '⑤ 名单改过再点「恢复默认」：框里填回两条，保存时写回这两条（不是清空）');
}

// ---------- ⑥—⑧ 保存前先确认 ----------

function saveRig(replies, answer) {
  const posts = [];
  const asked = [];
  const said = [];
  let loads = 0;
  const store = {tab: 'settings', dirty: {[KEY]: '203.0.113.5'}};
  const api = async (path, opt) => {
    posts.push({path, body: JSON.parse(opt.body)});
    return replies[posts.length - 1] || {success: true};
  };
  const ask = async spec => { asked.push(spec); return answer; };
  const confirmAll = loadAsyncFn(src, 'function confirmAll(', ['ask'], [ask], 'questions');
  const save = loadAsyncFn(src, 'function save(',
    ['saveTarget', '$', 'api', 'showIssues', 'say', 'store', 'load', 'markDirty', 'confirmAll'],
    [() => 'values', () => node('button'), api, () => {}, (text) => said.push(text), store,
      async () => { loads++; return ''; }, () => {}, confirmAll]);
  return {save, posts, asked, said, store, loads: () => loads};
}

const MUST_ASK = {success: false, message: '未保存：有改动需要先确认',
  confirm: [{name: KEY, title: field.label, body: QUESTION}]};

{
  let seen = 'missing';
  try {
    const rig = saveRig([MUST_ASK], false);
    await rig.save();
    seen = {
      posts: rig.posts.map(p => p.path),
      asked: rig.asked.map(a => a.body),
      draft: rig.store.dirty[KEY],
      reloaded: rig.loads(),
      last: rig.said[rig.said.length - 1],
    };
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, {posts: ['/values'], asked: [QUESTION], draft: '203.0.113.5', reloaded: 0, last: '未保存，可继续修改'},
    '⑥ 只含外部地址、点取消：只发一趟不带确认，草稿留着回到编辑，状态栏说未保存');
}

{
  let seen = 'missing';
  try {
    const rig = saveRig([MUST_ASK, {success: true, message: '已保存 1 项，已生效'}], true);
    await rig.save();
    seen = {
      posts: rig.posts.map(p => p.path),
      sameBody: JSON.stringify(rig.posts[0].body) === JSON.stringify(rig.posts[1] && rig.posts[1].body),
      reloaded: rig.loads(),
      last: rig.said[rig.said.length - 1],
    };
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, {posts: ['/values', '/values?confirmed=true'], sameBody: true, reloaded: 1, last: '已保存 1 项，已生效'},
    '⑦ 点确定：带着确认把同一份再送一次，之后照常重取');
}

{
  let seen = 'missing';
  try {
    const rig = saveRig([{success: true, message: '已保存 1 项，已生效'}], true);
    await rig.save();
    seen = {posts: rig.posts.map(p => p.path), asked: rig.asked.length};
  } catch (e) {
    seen = 'error:' + e.message;
  }
  eq(seen, {posts: ['/values'], asked: 0}, '⑧ 名单留着本机：不问，只发一趟');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
