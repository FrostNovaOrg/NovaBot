/**
 * 外部程序触发规则待调整时，控制台顶上那条提醒
 *
 * 有待调整的规则时出现，逐条写出配置里的位置，指向使用说明附录 B 并说明改好后重启；
 * 清单为空、取不到或回包失败时不出现。另核接线：页面上有这块、主脚本只在非成员时取数并画它。
 *
 * 由 ExecNoticeFrontendTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

const failures = [];
let checks = 0;

function ok(value, what) {
  checks++;
  if (!value) failures.push(what);
}

class MiniNode {
  constructor(tagName) {
    this.tagName = String(tagName || '').toLowerCase();
    this.children = [];
    this.style = {};
    this.href = '';
    this._text = '';
  }

  get textContent() {
    return this._text + this.children.map(child => child.textContent).join('');
  }

  set textContent(value) {
    this._text = String(value ?? '');
    this.children = [];
  }

  set innerHTML(html) {
    this._text = String(html ?? '');
    this.children = [];
  }

  append(...items) {
    for (const item of items) {
      if (typeof item === 'string') {
        const text = new MiniNode('#text');
        text.textContent = item;
        this.children.push(text);
      } else {
        this.children.push(item);
      }
    }
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }
}

function links(node, found = []) {
  if (node.tagName === 'a') found.push(node);
  for (const child of node.children) links(child, found);
  return found;
}

globalThis.document = {
  createElement: tag => new MiniNode(tag),
  querySelector: sel => (sel === 'meta[name="nova-prefix"]' ? {content: ''} : null),
};

let renderExecNotice = null;
try {
  ({renderExecNotice} = await import(join(ui, 'exec-notice.js')));
} catch (err) {
  failures.push('载入 exec-notice.js 就抛了：' + err.message);
}

const shown = box => box.style.display !== 'none';

if (renderExecNotice) {
  const box = new MiniNode('div');
  renderExecNotice(box, {success: true, rules: [
    {index: 1, key: 'novabot.core.exec.rules[1]', event: 'LiveOnEvent'},
    {index: 3, key: 'novabot.core.exec.rules[3]', event: ''},
  ]});
  const text = box.textContent;
  ok(shown(box), '① 有待调整的规则时提醒出现');
  ok(text.includes('novabot.core.exec.rules[1]') && text.includes('novabot.core.exec.rules[3]'),
    '① 逐条写出规则在配置里的位置：' + text);
  ok(text.includes('LiveOnEvent'), '① 写出规则的事件名：' + text);
  ok(text.includes('command'), '① 说明要改的是 command：' + text);
  ok(text.includes('附录 B'), '① 指向使用说明附录 B：' + text);
  ok(text.includes('重启'), '① 说明改好后重启：' + text);
  ok(links(box).some(link => link.href === '/config/manual/appendix-b-advanced.html'),
    '① 带一条去附录 B 的链接');

  renderExecNotice(box, {success: true, rules: []});
  ok(!shown(box), '② 清单为空时提醒不出现');
  ok(box.textContent === '', '② 清单为空时不留旧字：' + box.textContent);

  for (const [what, data] of [['回包失败', {success: false, message: '成员不能使用这一项'}],
    ['没有回包', null], ['没有清单', {success: true}]]) {
    const other = new MiniNode('div');
    renderExecNotice(other, data);
    ok(!shown(other), '③ ' + what + '时提醒不出现');
  }
}

const html = readFileSync(join(ui, 'index.html'), 'utf8');
const main = readFileSync(join(ui, 'main.js'), 'utf8');
ok(/<div class="notice" id="exec-notice" style="display:none">/.test(html), '④ index.html 上有 #exec-notice');
ok(/import \{renderExecNotice\} from '\.\/exec-notice\.js';/.test(main), '④ main.js 引入 renderExecNotice');
ok(/api\('\/exec\/notice'\)[\s\S]{0,200}renderExecNotice\(\$\('#exec-notice'\)/.test(main),
  '④ main.js 取 /exec/notice 并画进 #exec-notice');
ok(/if \(store\.role !== 'MEMBER'\) \{[^}]*loadExecNotice\(\);/.test(main), '④ 只在非成员时取这份清单');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
