/**
 * 手机顶栏的「我的」：摊开以后怎么收起来
 *
 * 点「我的」摊开面板时，焦点还留在这颗按钮上。只在面板上听 Esc 的话，
 * 这时直接按 Esc 什么也不发生——用户得先 Tab 进面板才按得掉，读起来就是「关不掉」。
 * 本尺在假 DOM 上真跑 personal-menu.js 的 bindMineMenu，逐格量四条收起的路：
 * 焦点在按钮上按 Esc、焦点在面板里按 Esc、再点按钮、点别处；另量两格不该收的：
 * 点面板里面、收着的时候按 Esc（不抢焦点）。
 *
 * 各格自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 MineMenuCloseTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');

const failures = [];
let checks = 0;

function ask(what, fn) {
  checks++;
  try {
    fn();
  } catch (err) {
    failures.push(what + '：' + (err && err.message ? err.message : String(err)));
  }
}

function want(actual, expected, what) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
}

// —— 假 DOM：事件从目标往上冒到 document，够 bindMineMenu 用 ——

class MiniNode {
  constructor(id) {
    this.id = id;
    this.parentNode = null;
    this.children = [];
    this.listeners = {};
    this.attrs = {};
    const classes = new Set();
    this.classList = {
      contains: name => classes.has(name),
      toggle: (name, force) => {
        const on = force === undefined ? !classes.has(name) : Boolean(force);
        if (on) classes.add(name);
        else classes.delete(name);
        return on;
      },
    };
  }

  append(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  addEventListener(type, fn) {
    (this.listeners[type] ||= []).push(fn);
  }

  setAttribute(name, value) {
    this.attrs[name] = String(value);
  }

  getAttribute(name) {
    return name in this.attrs ? this.attrs[name] : null;
  }

  contains(node) {
    for (let n = node; n; n = n.parentNode) if (n === this) return true;
    return false;
  }

  focus() {
    globalThis.document.activeElement = this;
  }
}

function dispatch(target, type, extra = {}) {
  const event = {type, target, defaultPrevented: false, ...extra};
  event.preventDefault = () => { event.defaultPrevented = true; };
  for (let n = target; n; n = n.parentNode) {
    for (const fn of n.listeners[type] || []) fn(event);
  }
  return event;
}

/** 一页够用的骨架：body 下挂「我的」按钮、面板（里面一颗退出）、页面正文 */
function page() {
  const doc = new MiniNode('#document');
  doc.querySelector = () => null;
  globalThis.document = doc;
  const body = doc.append(new MiniNode('body'));
  doc.activeElement = body;
  const toggle = body.append(new MiniNode('mine-toggle'));
  const panel = body.append(new MiniNode('mine-panel'));
  const logout = panel.append(new MiniNode('logout'));
  const main = body.append(new MiniNode('main'));
  return {doc, toggle, panel, logout, main};
}

/** 用户点一下：焦点落到被点的那颗上，再发 click */
function click(node) {
  node.focus();
  dispatch(node, 'click');
}

/** 在当前焦点上按一个键 */
function press(key) {
  dispatch(globalThis.document.activeElement, 'keydown', {key});
}

const state = ({doc, toggle, panel}) => ({
  open: panel.classList.contains('open'),
  expanded: toggle.getAttribute('aria-expanded'),
  focus: doc.activeElement ? doc.activeElement.id : null,
});

// 全局 document 要先在场：core.js 载入时会探一下 document.querySelector
page();
const {bindMineMenu} = await import(pathToFileURL(join(ui, 'personal-menu.js')).href);

function opened() {
  const p = page();
  bindMineMenu(p.toggle, p.panel);
  click(p.toggle);
  want(state(p), {open: true, expanded: 'true', focus: 'mine-toggle'}, '点「我的」之后');
  return p;
}

ask('① 点开后焦点还在按钮上，按 Esc 收起、焦点留在按钮上', () => {
  const p = opened();
  press('Escape');
  want(state(p), {open: false, expanded: 'false', focus: 'mine-toggle'}, '按钮上按 Esc 之后');
});

ask('② 焦点在面板里按 Esc 收起、焦点回到按钮', () => {
  const p = opened();
  p.logout.focus();
  press('Escape');
  want(state(p), {open: false, expanded: 'false', focus: 'mine-toggle'}, '面板里按 Esc 之后');
});

ask('③ 再点一次按钮收起', () => {
  const p = opened();
  click(p.toggle);
  want(state(p).open, false, '再点按钮之后面板开着');
});

ask('④ 点别处收起', () => {
  const p = opened();
  click(p.main);
  want(state(p).open, false, '点正文之后面板开着');
});

ask('⑤ 点面板里面不收', () => {
  const p = opened();
  click(p.logout);
  want(state(p).open, true, '点面板里那颗之后面板开着');
});

ask('⑥ 收着的时候在按钮上按 Esc：不变、不报错', () => {
  const p = page();
  bindMineMenu(p.toggle, p.panel);
  p.toggle.focus();
  press('Escape');
  want(state(p).open, false, '收着按 Esc 之后面板开着');
  want(p.toggle.getAttribute('aria-expanded'), null, '收着按 Esc 之后 aria-expanded 被改写');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
