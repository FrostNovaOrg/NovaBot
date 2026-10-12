/**
 * 服务端判成机密的项，界面照旧给密码框；路径换等宽字也不改这一条
 *
 * 抓的用户故障：前端自作主张把服务端标了机密的字段改成不机密（「这是个路径，不是口令」），
 * 于是口令、令牌在控制台上明文摆着——面板可能正开在直播画面上。机密与否只由服务端判，
 * 路径用等宽字认的是服务端给的 format（PATH），两件事互不相干。
 *
 * 各格：①机密项出密码框；②机密项即使标了 PATH 仍是密码框，不因为是路径就露出来；
 * ③不机密、标了 PATH 的项是普通文本框，用等宽字（setting-path）；④不机密也没标 PATH 的不加等宽。
 *
 * 由 SettingsSecretBoxTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {timeOfDay} from '../../../main/resources/config-ui/settings-model.js';

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

function node(tag, cls) {
  const classes = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  return {
    tag, dataset: {}, textContent: '', type: '', value: '', autocomplete: '', kids: [], attrs: {},
    classList: {
      add: (...c) => c.forEach(x => classes.add(x)),
      remove: (...c) => c.forEach(x => classes.delete(x)),
      toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); },
      contains: c => classes.has(c),
    },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    appendChild(child) { this.kids.push(child); return child; },
    append(...children) { this.kids.push(...children); },
    addEventListener() {},
  };
}

let buildControl = null;
try {
  const body = bracedFrom(src, 'function buildControl(');
  buildControl = new Function('el', 'bindPasswordReveal', 'switchControl', 'timeOfDay',
    body + '\nreturn buildControl;')(node, () => {}, () => ({}), timeOfDay);
} catch (err) {
  failures.push('烘 buildControl 就抛了：' + err.message);
}

function control(over) {
  const field = Object.assign({name: 'demo.key', label: '演示', widget: 'string', sensitive: false}, over);
  const cell = node('div');
  const input = buildControl(field, 'value-on-file', cell);
  return {type: input.type, mono: input.classList.contains('setting-path'), value: input.value};
}

if (buildControl) {
  eq(control({sensitive: true}), {type: 'password', mono: false, value: 'value-on-file'},
    '① 服务端判成机密：密码框');
  eq(control({sensitive: true, format: 'PATH'}).type, 'password',
    '② 机密项即使标了 PATH：照旧密码框，不因为是路径就明文');
  eq(control({format: 'PATH'}), {type: 'text', mono: true, value: 'value-on-file'},
    '③ 不机密、标了 PATH：普通文本框，等宽字');
  eq(control({}).mono, false, '④ 没标 PATH：不加等宽');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
