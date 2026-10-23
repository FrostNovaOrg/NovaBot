import {botFieldsHtml} from '../../../main/resources/config-ui/bot-fields.js';
import {phrase} from '../../../main/resources/config-ui/core.js';
/**
 * 连接页机器人表单：五个输入框各有自己的名字
 *
 * 表单里五个 label 没有接上输入框（无 for、也没包住）：点标签定位不到框，
 * 读屏念不出框的名字；两个 Token 框的 placeholder 又一字不差，
 * 听读屏的人分不出此刻停在哪个框上。这一尺把 botFormHtml 画出来的
 * 五个输入框逐一对照：每个框都有一个 label[for] 对上它的 id（id 一个不许改，
 * 取值的那几处按 id 找），且五个标签文字互不相同。
 *
 * botFormHtml 切段注入 phrase 后真执行，产出的 HTML 浅解析成节点再数。
 * 由 BotFormLabelsTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ui = join(here, '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'bot.js'), 'utf8');

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

// —— 浅解析：够数 botFormHtml 那份 HTML 里的 input 与 label ——

class MiniNode {
  constructor(tagName) {
    this.tagName = String(tagName || '').toLowerCase();
    this.children = [];
    this.attributes = {};
    this._text = '';
  }
  get textContent() {
    if (this.children.length && this.tagName !== '#text') {
      return this.children.map(c => c.textContent).join('');
    }
    return this._text;
  }
  set textContent(value) {
    this._text = String(value ?? '');
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }
  appendChild(child) {
    this.children.push(child);
    return child;
  }
}

function applyAttrs(node, raw) {
  const re = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*"([^"]*)"|([a-zA-Z_:][-a-zA-Z0-9_:.]*)/g;
  let m;
  while ((m = re.exec(raw))) {
    if (m[1] != null) node.setAttribute(m[1], m[2]);
    else if (m[3] != null) node.setAttribute(m[3], '');
  }
}

function parseInto(parent, html) {
  const stack = [parent];
  const re = /<(\/?)([a-zA-Z0-9]+)([^>]*)>|([^<]+)/g;
  let m;
  while ((m = re.exec(html))) {
    if (m[4] != null) {
      const text = m[4];
      if (text.trim()) {
        const node = new MiniNode('#text');
        node.textContent = text;
        stack[stack.length - 1].appendChild(node);
      }
      continue;
    }
    const closing = m[1] === '/';
    const tag = m[2].toLowerCase();
    const rawAttrs = m[3] || '';
    if (closing) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const node = new MiniNode(tag);
    applyAttrs(node, rawAttrs);
    stack[stack.length - 1].appendChild(node);
    const selfClose = /\/\s*$/.test(rawAttrs) || tag === 'input' || tag === 'br' || tag === 'img';
    if (!selfClose) stack.push(node);
  }
}

function collect(root, tag) {
  const out = [];
  const walk = node => {
    if (node.tagName === tag) out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(root);
  return out;
}

// —— 真执行 botFormHtml，两个前缀各画一份 ——

const body = bracedFrom(src, 'export function botFormHtml');
if (!body) {
  console.log('跑了 0 格，红 1 格');
  console.log('  红：bot.js 里切不出 botFormHtml');
  process.exit(1);
}
const botFormHtml = new Function('botFieldsHtml', 'phrase', body.replace(/^export\s+/, '') + '\nreturn botFormHtml;')(
  botFieldsHtml, phrase);

for (const p of ['s1', 'bot']) {
  const host = new MiniNode('div');
  parseInto(host, botFormHtml(p));

  const inputs = collect(host, 'input');
  eq(inputs.map(node => node.getAttribute('id')),
    [p + '-addr', p + '-hport', p + '-htoken', p + '-wport', p + '-wtoken'],
    p + '：五个输入框的 id 一个不改（取值处按 id 找）');

  const labels = collect(host, 'label');
  eq(labels.map(node => node.getAttribute('for')),
    [p + '-addr', p + '-hport', p + '-htoken', p + '-wport', p + '-wtoken'],
    p + '：五个 label 的 for 各对上一个输入框的 id');

  const texts = labels.map(node => node.textContent.trim());
  eq(new Set(texts).size, 5, p + '：五个标签文字互不相同（读屏靠它分辨框）');
  eq(texts.includes(''), false, p + '：没有空标签');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
