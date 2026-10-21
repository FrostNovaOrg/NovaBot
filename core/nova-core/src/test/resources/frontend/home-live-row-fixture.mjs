import assert from 'node:assert/strict';
import {register} from 'node:module';

class Node {
  constructor() {
    this.innerHTML = '';
    this.textContent = '';
    this.className = '';
    this.classList = {
      toggle: (name, force) => {
        const names = new Set(this.className.split(/\s+/).filter(Boolean));
        const on = force === undefined ? !names.has(name) : !!force;
        if (on) names.add(name); else names.delete(name);
        this.className = [...names].join(' ');
        return on;
      },
    };
    this.style = {display: ''};
    this.disabled = false;
    this.dataset = {};
    this.children = [];
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  addEventListener() {
  }

  querySelector() {
    return null;
  }

  querySelectorAll() {
    return [];
  }
}

const nodes = new Map();

function node(key) {
  let found = nodes.get(key);
  if (!found) {
    found = new Node();
    nodes.set(key, found);
  }
  return found;
}

globalThis.document = {
  documentElement: {classList: {toggle() {}}},
  querySelector: selector => node(selector),
  querySelectorAll: () => [],
  createElement: () => new Node(),
};
globalThis.location = {hash: '#/home', reload() {}};

register(new URL('./home-problems-fetch-failure-loader.mjs', import.meta.url));
const fixedNow = Date.parse('2026-10-21T12:00:00+08:00');
Date.now = () => fixedNow;
const users = ['主播甲', '主播乙', '主播丙', '主播丁'].map((uname, i) => ({
  uid: i + 1, uname, platform: 'bilibili', enabled: true, targets: i + 1,
}));
const live = users.map((user, i) => ({
  ...user, roomId: 1001 + i, since: fixedNow - (60 + i) * 60000,
}));
let current = {
  success: true, users, live, health: [], locked: true, pushEnabled: true,
  senders: [{id: 'qq'}], totalDataAvailable: true, alerts: {webhook: true, mail: true},
};
globalThis.fetch = async url => {
  const path = String(url);
  const body = path.includes('/pages') ? {pages: []}
    : path.includes('/status') ? current
    : path.includes('/login') ? {success: true, accounts: []}
    : {success: true, events: []};
  return {status: 200, json: async () => body};
};
const {refreshHome} = await import('../../../main/resources/config-ui/overview.js');
let checks = 0, failures = 0;
async function check(name, fn) {
  checks++;
  try { await fn(); console.log('绿 ' + name); }
  catch (error) { failures++; console.log('红 ' + name + '：' + error.message); }
}
async function render(extra = {}) {
  current = {...current, ...extra};
  assert.ok(await refreshHome(), '首页应成功渲染');
  return node('#now-box').innerHTML;
}
// 多位在播时，逐位展开的直播间与推送目标会把今日数据和事件挤出第一屏。
await check('四位在播各占一个行元素并按名字、小牌、直播间、推送目标排列', async () => {
  const html = await render();
  assert.equal((html.match(/class="livewho"/g) || []).length, 4, '应有四个主播行');
  assert.doesNotMatch(html, /<dl\b/, '每位主播不应再展开一份 dl');
  const rows = html.split('<div class="livewho">').slice(1);
  rows.forEach((row, i) => {
    assert.match(row, new RegExp('<span class="lw-nm">' + users[i].uname + '</span>'));
    const parts = [users[i].uname, 'class="pill live"', '直播中 1 小时 ' + i + ' 分钟',
      '直播间 ' + live[i].roomId, '推送目标 ' + users[i].targets + ' 个'];
    let previous = -1;
    for (const part of parts) {
      const index = row.indexOf(part);
      assert.ok(index > previous, '行内信息顺序：' + part);
      previous = index;
    }
  });
  console.log('DOM: ' + html);
});
// 未知直播间不能出现空标签，采集缺口和暂停仍需清楚显示。
await check('缺少直播间时省略该项并保留采集缺口及暂停提示', async () => {
  const html = await render({live: [{...live[0], roomId: null}], pushEnabled: false,
    health: [{name: '连接', scope: 'PLATFORM', level: 'DEGRADED', summary: '连接中断'}]});
  assert.doesNotMatch(html, /直播间<|直播间 /);
  assert.match(html, /class="pill warn">直播中 · 采集有缺口/);
  assert.match(html, /class="note n-warn">有直播间的连接中断/);
  assert.match(html, /class="note n-err">推送已暂停/);
  assert.match(html, /推送目标.*1 个/);
});
// 无人在播时仍需告诉使用者当前状态和监听人数。
await check('没有直播时保留空态说明', async () => {
  const html = await render({live: [], health: [], pushEnabled: true});
  assert.equal(html, '<div class="empty">当前没有主播在直播 · 共监听 4 位主播</div>');
  assert.ok(node('#home-now').className.split(' ').includes('is-empty'));
});
console.log('跑了 ' + checks + ' 格');
if (failures) process.exit(1);
