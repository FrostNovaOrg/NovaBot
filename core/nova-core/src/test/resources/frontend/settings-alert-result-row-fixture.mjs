/**
 * 告警卡「发一条测试」的结果在按钮上方，空着不占地方
 *
 * 守的是：结果字一长，不再折到按钮下一行把这张卡的按钮顶上去。
 * 按钮留在卡底最后一行，三张卡的按钮才能齐平。结果行空着时不占行。
 * 查的是卡底里谁先谁后，以及样式是按列排、空结果不显示。不量像素。
 *
 * 切段按花括号配平截到块尾后真执行。由 SettingsAlertResultRowTest 拉起。
 * 量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');
const css = readFileSync(join(ui, 'app.css'), 'utf8');

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
 * 字符串与注释里的花括号不算层。
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

function decl(selector) {
  const at = css.indexOf(selector);
  if (at < 0) return '';
  const open = css.indexOf('{', at);
  const close = css.indexOf('}', open);
  if (open < 0 || close < 0) return '';
  return css.slice(open + 1, close);
}

function el(tag, cls) {
  const node = {
    tagName: tag,
    className: cls || '',
    children: [],
    dataset: {},
    textContent: '',
    style: {},
    parentElement: null,
    appendChild(child) {
      this.children.push(child);
      child.parentElement = this;
      return child;
    },
    append(...nodes) {
      for (const child of nodes) this.appendChild(child);
    },
    addEventListener() {},
    setAttribute() {},
  };
  return node;
}

const shellSrc = bracedFrom(src, 'function shell');
eq(shellSrc.includes('发一条测试') && shellSrc.includes('al-r'), true, '切得到卡片壳');

const shell = new Function('el', shellSrc + '\nreturn shell;')(el);
const built = shell('webhook', 'Webhook', '说明');
const card = built && built.card;
const foot = card && card.children.find(child => child.className === 'al-f');
eq(!!foot, true, '卡底那一行还在');
const buttonAt = foot ? foot.children.findIndex(child => child.tagName === 'button') : -1;
const resultAt = foot ? foot.children.findIndex(child => String(child.className).includes('al-r')) : -1;
eq(!!foot && buttonAt === foot.children.length - 1, true, '按钮是卡底最后一行，结果不再把按钮顶上去');
eq(resultAt >= 0 && resultAt < buttonAt, true, '结果行在按钮上方');

const footRule = decl('.al-f{');
eq(footRule.includes('flex-direction:column'), true, '卡底按列排，长结果在按钮上方折行，不会折到按钮下面把按钮顶上去');
eq(decl('.al-f .al-r{').includes('align-self:stretch'), true, '结果行拉满卡宽，长字在按钮上方折行');
eq(decl('.al-f .al-r:empty{').includes('display:none'), true, '结果行空着不占地方');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
