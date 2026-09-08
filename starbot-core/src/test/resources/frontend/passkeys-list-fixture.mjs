/**
 * 通行密钥列表：画进传入的那只容器
 *
 * 设置页那张卡是游离节点，loadPasskeys 的容器与按钮由调用方传进来。
 * 源码级正则只认「声明形」的按 id 取（const|let|var box = $('#passkey-list')），
 * 赋值形或换 document.querySelector 的全局取一概看不见——那正是这张卡画空白的路。
 * 这里用游离容器加假 api 真执行切段：列表与空列表提示必须画进传入的 box；
 * 行上删除按钮存在；删除之后重画进接线当次的那只容器，不随后面别的
 * loadPasskeys 调用漂到别处。全局「最近一次」那类可变状态量不出这个性质。
 *
 * 由 PasskeysListTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'passkeys.js'), 'utf8');

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
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个 function」。
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

/**
 * 只够这份列表用的极小 DOM：innerHTML 按标签配平建子节点，
 * querySelector/querySelectorAll 认「标签名」与「标签名[属性名]」两种形状
 */
function matches(node, selector) {
  const m = String(selector).match(/^([-\w]+)(?:\[([-\w]+)\])?$/);
  if (!m) return false;
  if (m[1] !== '*' && node.tagName !== m[1]) return false;
  if (m[2] && node.attrs[m[2]] === undefined) return false;
  return true;
}

/**
 * innerHTML 赋值即按标签配平重建子节点（这份列表只会生成配平的标签串）
 */
function parseInto(node, html) {
  node.children = [];
  node.textContent = '';
  const stack = [node];
  for (const part of String(html).split(/(<[^>]+>)/)) {
    if (!part) continue;
    if (part[0] !== '<') {
      stack[stack.length - 1].textContent += part;
      continue;
    }
    if (part[1] === '/') {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const selfClosing = part.endsWith('/>');
    const body = part.slice(1, selfClosing ? -2 : -1).trim();
    const child = element(body.match(/^([-\w]+)/)?.[1]?.toLowerCase());
    for (const attr of body.matchAll(/([-\w]+)="([^"]*)"/g)) {
      child.attrs[attr[1]] = attr[2];
      if (attr[1].startsWith('data-')) {
        const key = attr[1].slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
        child.dataset[key] = attr[2];
      }
    }
    stack[stack.length - 1].children.push(child);
    if (!selfClosing) stack.push(child);
  }
}

function element(tag) {
  const node = {
    tagName: tag || 'div',
    attrs: {},
    dataset: {},
    children: [],
    textContent: '',
    disabled: false,
    style: {},
    listeners: {},
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
    querySelector(selector) {
      return this.querySelectorAll(selector)[0] || null;
    },
    querySelectorAll(selector) {
      const out = [];
      const walk = n => {
        for (const child of n.children) {
          if (matches(child, selector)) out.push(child);
          walk(child);
        }
      };
      walk(this);
      return out;
    },
  };
  let html = '';
  Object.defineProperty(node, 'innerHTML', {
    get: () => html,
    set: value => {
      html = String(value);
      parseInto(node, html);
    },
  });
  return node;
}

/**
 * 页上按 id 可见的那几个节点（#passkey-list／#passkey-add）与 document.querySelector，
 * 全局取路若被走到，画出来的就是这里的节点，而不是传入的游离容器
 */
function fakeDom() {
  const byId = {};
  const $ = sel => {
    const id = String(sel).replace(/^#/, '');
    if (!byId[id]) byId[id] = element();
    return byId[id];
  };
  const documentLike = {
    querySelector: sel => (String(sel) === '#passkey-list' ? $('#passkey-list') : null),
  };
  return {$, byId, document: documentLike, newElement: () => element()};
}

/**
 * 假 api：GET 回两把钥匙；DELETE 记账后 GET 只回剩下那把
 */
function fakeApi() {
  const keys = [
    {id: 'k1', name: '我的手机', createdAt: '2026-09-07T10:00:00', lastUsedAt: null},
    {id: 'k2', name: '我的电脑', createdAt: '2026-09-07T11:00:00', lastUsedAt: '2026-09-08T09:00:00'},
  ];
  const deleted = [];
  const api = async (path, opts) => {
    if (opts && opts.method === 'DELETE') {
      deleted.push(String(path));
      return {success: true, message: '已删除'};
    }
    return {passkeys: keys.filter(k => !deleted.some(d => d.endsWith('/' + k.id)))};
  };
  return api;
}

const esc = value => String(value);
const ask = async () => true;
const say = () => {
};

/**
 * 切出 time／remove／loadPasskeys 真执行。
 * 模块顶上若还有「最近一次」那对可变量，这里补同名声明让旧写法照常跑；
 * 新写法不认它们，声明空着不影响。
 */
function makeSubject(dom, api) {
  const time = bracedFrom(src, 'function time');
  const remove = bracedFrom(src, 'async function remove');
  const load = bracedFrom(src, 'async function loadPasskeys');
  if (!time || !remove || !load) throw new Error('切段失败：time=' + !!time
    + ' remove=' + !!remove + ' load=' + !!load);
  const assembled = 'let lastBox;\nlet lastAdd;\nconst supported = () => true;\n'
    + time + '\n' + remove + '\n' + load + '\nreturn {loadPasskeys, remove};';
  return new Function('$', 'api', 'esc', 'ask', 'say', 'document', assembled)(
    dom.$, api, esc, ask, say, dom.document);
}

// ① 列表画进传入的 box：两行、两颗删除按钮；页上 #passkey-list 一个表格也不落
let q1 = 'missing';
try {
  const dom = fakeDom();
  const load = makeSubject(dom, fakeApi()).loadPasskeys;
  const box = dom.newElement();
  const add = dom.newElement();
  await load(box, add);
  q1 = box.querySelectorAll('table').length === 1
    && box.querySelectorAll('button[data-id]').length === 2
    && dom.$('#passkey-list').querySelectorAll('table').length === 0;
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, '① 列表画进传入的 box，页上 #passkey-list 不被碰');

// ② 空列表的提示画在传入的 box 里，且不画表格
let q2 = 'missing';
try {
  const dom = fakeDom();
  const emptyApi = async () => ({passkeys: []});
  const load = makeSubject(dom, emptyApi).loadPasskeys;
  const box = dom.newElement();
  await load(box, dom.newElement());
  q2 = box.innerHTML.includes('还没有登记过通行密钥')
    && box.querySelectorAll('table').length === 0
    && !dom.$('#passkey-list').innerHTML;
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, '② 空列表提示画在传入的 box 里');

// ③ 每行删除按钮存在、带自己的 id、各接一个 click
let q3 = 'missing';
try {
  const dom = fakeDom();
  const load = makeSubject(dom, fakeApi()).loadPasskeys;
  const box = dom.newElement();
  await load(box, dom.newElement());
  const buttons = box.querySelectorAll('button[data-id]');
  q3 = buttons.map(b => b.dataset.id).join(',') === 'k1,k2'
    && buttons.every(b => (b.listeners.click || []).length === 1);
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, '③ 每行删除按钮存在并接了 click');

// ④ 删除后重画进接线当次的容器：旧卡上的删除回调触发后，重画必须落在
//    接线那一趟传入的 box（剩一行），不因之后又画过一张新卡而漂过去
let q4 = 'missing';
try {
  const dom = fakeDom();
  const load = makeSubject(dom, fakeApi()).loadPasskeys;
  const box1 = dom.newElement();
  await load(box1, dom.newElement());
  const box2 = dom.newElement();
  await load(box2, dom.newElement());
  const oldButton = box1.querySelectorAll('button[data-id]')[0];
  await oldButton.listeners.click[0]();
  const after = box1.querySelectorAll('button[data-id]');
  q4 = after.length === 1 && after[0].dataset.id === 'k2';
} catch (e) {
  q4 = 'error:' + e.message;
}
eq(q4, true, '④ 删除后重画进接线当次的容器，不漂到后画的卡');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
