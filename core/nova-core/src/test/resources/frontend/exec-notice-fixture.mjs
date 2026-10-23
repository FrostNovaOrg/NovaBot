/**
 * 外部程序触发规则待调整时，控制台顶上那条提醒
 *
 * 有待调整的规则时出现，逐条写出配置里的位置，指向使用说明附录 B 并说明改好后重启；
 * 清单为空、取不到或回包失败时不出现。另核接线：页面上有这块、主脚本按当前身份取数并画它。
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


ok(html.indexOf('id="oidc-callback-notice"') < html.indexOf('id="exec-notice"'), '④ 回调地址提醒在规则提醒上方');

const source = main.slice(main.indexOf('function loadExecNotice('),
  main.indexOf("$('#startup-retry').addEventListener"));
const box = new MiniNode('div');
const elements = new Map([['#exec-notice', box]]);
const $ = key => {
  if (!elements.has(key)) elements.set(key, new MiniNode('div'));
  return elements.get(key);
};
let state = {role: 'ADMIN'};
let pending;
let requests = 0;
const data = {success: true, rules: [{key: 'novabot.core.exec.rules[0]'}]};
let noticeData = data;
const noop = () => {};
const dependencies = {
  $, store: {}, renderExecNotice,
  api: async path => {
    if (path === '/auth/state') return state;
    if (path === '/exec/notice') {
      requests++;
      return pending ? await pending : noticeData;
    }
    return {};
  },
  applyRoleView: noop, setAuthState: noop, setMemberAuthState: noop,
  renderPersonalMenu: noop, bindBotForm: noop, botFormHtml: noop, loadBotConsole: noop,
  mountPages: async () => {}, applyRoute: noop, load: noop,
  setTimeout: noop, clearTimeout: noop,
};
const lifecycle = new Function(...Object.keys(dependencies),
  'let homeStatus, homeLogin, execNoticeVersion = 0;\n' + source + '\nreturn {start: startConsole, refresh: loadExecNotice};')(...Object.values(dependencies));
const start = lifecycle.start;
await start();
ok(shown(box), '⑤ 管理员有待调整规则时显示');
state = {role: 'ADMIN', oidcCallbackSetupNeeded: true};
await start();
ok(shown(box) && shown($('#oidc-callback-notice')), '④ 两条提醒可以同时显示');
noticeData = {success: true, rules: []};
await lifecycle.refresh();
ok(!shown(box) && shown($('#oidc-callback-notice')), '④ 规则清单为空不改变回调地址提醒');
noticeData = data;
await lifecycle.refresh();
ok(shown(box) && shown($('#oidc-callback-notice')), '④ 规则提醒恢复不改变回调地址提醒');
const administratorRequests = requests;
state = {role: 'MEMBER'};
await start();
ok(!shown(box) && box.textContent === '', '⑤ 身份改为成员后清空提醒');
ok(requests === administratorRequests, '⑤ 成员不取清单');
state = {};
await start();
ok(!shown(box) && requests === administratorRequests, '⑤ 未登录不取清单且不留文字');
state = {role: 'ADMIN'};
await start();
ok(shown(box), '⑤ 成员改为管理员后重新显示');
let release;
pending = new Promise(resolve => { release = resolve; });
await start();
state = {role: 'MEMBER'};
await start();
release(data);
await pending;
await Promise.resolve();
ok(!shown(box) && box.textContent === '', '⑤ 身份变更前的回包不再显示');
pending = null;
state = {role: 'ADMIN'};
await lifecycle.refresh();
ok(shown(box), '⑤ 身份更新为管理员后刷新显示');
state = {role: 'MEMBER'};
await lifecycle.refresh();
ok(!shown(box) && box.textContent === '', '⑤ 刷新时按当前身份清空提醒');
state = {role: 'ADMIN'};
pending = new Promise(resolve => { release = resolve; });
const count = requests;
const earlier = lifecycle.refresh();
for (let i = 0; i < 50 && requests === count; i++) await Promise.resolve();
ok(requests > count, '⑤ 已开始较早的清单请求');
pending = null;
noticeData = {success: true, rules: []};
await lifecycle.refresh();
release(data);
await earlier;
ok(!shown(box) && box.textContent === '', '⑤ 较早的回包不覆盖较新的清单');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
