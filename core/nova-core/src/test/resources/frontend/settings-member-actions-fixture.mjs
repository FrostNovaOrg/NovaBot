/**
 * 手机宽度下成员表的操作按钮看得见、点得到
 *
 * 抓的用户故障：成员表在手机宽度下要横着滑，一屏装不下六列，操作那几颗按钮被挤到
 * 屏幕外——名单看得到，按钮却够不着。每位成员该是一张小卡片，操作按钮在卡片底部，
 * 不用横着滑去找。
 *
 * 三格：①阳性对照，宽屏下按钮都在、点得动；②手机宽度下每位成员是一张卡片、
 * 不再是横着滚的宽表，操作按钮在卡片底部那一块里（这一格先红后绿）；
 * ③卡片里每颗按钮都接着事，点下去真的发得出请求。
 *
 * 由 SettingsMemberActionsTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync, existsSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => (existsSync(join(ui, name)) ? readFileSync(join(ui, name), 'utf8') : '');

const failures = [];
let checks = 0;
async function check(problem, test) {
  checks++;
  try {
    await test();
  } catch (error) {
    failures.push(problem + '：' + error.message);
  }
}

// 使用小型 DOM 执行页面模块，直接点按钮并检查请求。
class Node {
  constructor(tag = 'div', cls = '') {
    this.tagName = tag; this.className = cls; this.children = []; this.dataset = {};
    this.listeners = {}; this.attributes = {}; this.hidden = false; this.disabled = false;
    this.checked = false; this._text = '';
    const classes = new Set(cls.split(/\s+/).filter(Boolean));
    this.classList = {
      contains: name => classes.has(name),
      toggle: (name, on) => {
        if (on) classes.add(name); else classes.delete(name);
      },
    };
  }

  set textContent(value) { this._text = String(value); this.children = []; }

  get textContent() { return this._text + this.children.map(n => n.textContent).join(''); }

  replaceChildren(...nodes) { this._text = ''; this.children = nodes; }

  appendChild(node) { this.children.push(node); return node; }

  setAttribute(key, value) { this.attributes[key] = String(value); }

  removeAttribute(key) { delete this.attributes[key]; }

  querySelector(selector) { return walk(this).find(n => selector === '#' + n.id); }

  addEventListener(name, fn) { this.listeners[name] = fn; }

  querySelectorAll(selector) {
    return walk(this).filter(n => (selector === 'input:checked' ? n.tagName === 'input' && n.checked
      : selector === 'button' ? n.tagName === 'button'
        : selector === 'input' ? n.tagName === 'input' : false));
  }

  async click() { if (!this.disabled) await (this.onclick || this.listeners.click)?.(); }
}

function walk(root) { return root.children.flatMap(node => [node, ...walk(node)]); }

function find(root, action) {
  const node = walk(root).find(n => n.dataset.action === action || n.textContent === action);
  assert.ok(node, '缺少操作 ' + action);
  return node;
}

const base = {role: 'ADMIN', accountId: 'local-admin', authenticated: true, enabled: true, oidcEnabled: true};
const account = (id, role, status, extra = {}) => ({
  id, role, status, displayName: id, email: id + '@example.com',
  streamers: [], lastLoginAt: '2026-10-20T01:02:03Z', ...extra,
});
const entries = [
  account('local-admin', 'ADMIN', 'ENABLED'),
  account('enabled', 'MEMBER', 'ENABLED', {streamers: ['bilibili:11']}),
  account('admin', 'ADMIN', 'ENABLED'),
];

/** 起一个成员页；phone 为真时报窄屏 */
async function members(phone) {
  const requests = [];
  const box = new Node();
  const api = async (path, options = {}) => {
    requests.push({path, ...options});
    if (path === '/accounts') {
      return {success: true, accounts: entries, managementUri: 'https://accounts.example.com/manage'};
    }
    if (path === '/datasource/users') {
      return {success: true, users: [{platform: 'bilibili', uid: '11', enabled: true}]};
    }
    if (path.includes('/streamer/name?')) return {success: true, uname: '主播甲'};
    return {success: true};
  };
  const window = {
    location: {href: '', reload() {}},
    open() {},
    matchMedia: query => ({matches: !!phone && String(query).includes('959')}),
  };
  const src = read('settings-members.js').replace(/^import .*?;\s*$/gm, '').replace(/export /g, '');
  const mod = new Function('el', 'api', 'ask', 'window', 'hasUnsavedChanges', 'store', '$',
    src + '\nreturn {renderMembers, memberGroup, setMemberAuthState};')(
    (tag, cls) => new Node(tag, cls), api, async () => true, window, () => false, {role: 'ADMIN'},
    () => box);
  await mod.renderMembers(box, base);
  return {box, requests, row: id => walk(box).find(n => n.dataset.accountId === id)};
}

// ---------- 阳性对照：宽屏下按钮都在、点得动 ----------
await check('阳性对照：成员行上有操作按钮，点得动', async () => {
  const s = await members(false);
  const row = s.row('enabled');
  assert.ok(row, '缺少成员行');
  const buttons = walk(row).filter(n => n.tagName === 'button' && n.dataset.action);
  assert.ok(buttons.length >= 1, '成员行上一颗操作按钮都没有');
  await find(row, 'disable').click();
  assert.ok(s.requests.some(request => request.path === '/accounts/enabled/disable'), '停用没发出去');
});

// ---------- ① 手机宽度：每位成员一张卡片，按钮在卡片底部 ----------
await check('手机宽度下成员是一张卡片，操作按钮在卡片底部那一块', async () => {
  const s = await members(true);
  const tables = walk(s.box).filter(n => n.tagName === 'table');
  assert.equal(tables.length, 0, '手机宽度下不该还有要横着滚的宽表，实际还有 ' + tables.length + ' 张');
  for (const id of ['local-admin', 'enabled', 'admin']) {
    const row = s.row(id);
    assert.ok(row, '缺少成员卡 ' + id);
    assert.ok(String(row.className).includes('member-card'),
      id + ' 不是一张卡片，实际类名「' + row.className + '」');
    const actions = row.children[row.children.length - 1];
    assert.ok(actions && String(actions.className).includes('member-actions'),
      id + ' 的卡片底部不是操作那一块');
    const buttons = walk(actions).filter(n => n.tagName === 'button' && n.dataset.action);
    assert.ok(buttons.length >= 1, id + ' 的卡片底部一颗按钮都没有');
  }
});

// ---------- ② 卡片里每颗按钮都接着事 ----------
await check('手机宽度下每颗操作按钮点得到、发得出请求', async () => {
  const s = await members(true);
  const row = s.row('enabled');
  await find(row, 'disable').click();
  assert.ok(s.requests.some(request => request.path === '/accounts/enabled/disable'),
    '卡片底部的停用按钮点不动');
  await find(row, 'role').click();
  assert.ok(s.requests.some(request => request.path === '/accounts/enabled/role'),
    '卡片底部的改身份按钮点不动');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
