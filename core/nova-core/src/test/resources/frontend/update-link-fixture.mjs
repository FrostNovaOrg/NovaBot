/**
 * 新版提示里「看完整说明」那条链接只指向 http／https 地址。
 *
 * 地址来自新版来源的回包。来源被换成别人的镜像、或发布页被篡改时，
 * 它可能写成 javascript: 一类；照样画成链接的话，管理员一点，
 * 那段脚本就在已登录的控制台里跑。关于卡（新版气泡）与首页待办两处都画这条链接。
 * 正常的 https 地址两处照常画成链接，新开一页，不带本页地址。
 *
 * 真把首页取一趟、画出来，再从画出来的 HTML 里取 href 来看。
 * 参数：about-script ＝ 关于卡遇上脚本地址；todo-script ＝ 首页待办遇上脚本地址；
 * https-about、https-todo ＝ 两处遇上 https 地址的对照。
 *
 * 由 UpdateLinkFrontendTest 拉起。退码 0 ＝ 全绿。
 */

import {register} from 'node:module';

const mode = process.argv[2] || '';
const failures = [];
let checks = 0;

function check(ok, what) {
  checks++;
  if (!ok) failures.push(what);
}

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

function statusWith(url) {
  return {
    success: true,
    senders: [{id: 'qq'}],
    users: [{uid: 1, uname: '甲', platform: 'bilibili', enabled: true, targets: 1}],
    health: [],
    locked: true,
    totalDataAvailable: true,
    alerts: {webhook: true, mail: true},
    pushEnabled: true,
    live: [],
    quiet: {active: false},
    version: '5.0.0',
    runtime: {heapUsedMb: 1, heapMaxMb: 2, threads: 1},
    update: {latestVersion: 'v5.1.0', notes: ['修了开播误报'], url},
  };
}

let statusBody = null;

function ok(body) {
  return Promise.resolve({status: 200, json: () => Promise.resolve(body)});
}

globalThis.fetch = url => {
  const path = String(url);
  if (path.includes('/pages')) return ok({pages: []});
  if (path.includes('/status')) return ok(statusBody);
  if (path.includes('/login')) return ok({success: true, accounts: []});
  if (path.includes('/timeline')) return ok({success: true, matched: 0, events: []});
  return Promise.reject(new Error('没准备这一趟 ' + path));
};

/** 画出来的 HTML 里每个开始标签 a，连同按浏览器那样还原了转义的 href */
function anchors(html) {
  const decode = text => text
    .replace(/&quot;/g, '"').replace(/&#39;/g, '\'')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  return [...String(html).matchAll(/<a\b[^>]*>/gi)].map(match => {
    const tag = match[0];
    const href = /\bhref\s*=\s*"([^"]*)"/i.exec(tag);
    return {tag, href: href ? decode(href[1]) : null};
  });
}

/** 首页待办里「有新版」那一行 */
function updateTodo(html) {
  const rows = String(html).split('<div class="todo">');
  return rows.find(row => row.includes('发现新版本 v5.1.0')) || '';
}

register(new URL('./home-problems-fetch-failure-loader.mjs', import.meta.url));

let refreshHome = null;
try {
  refreshHome = (await import('../../../main/resources/config-ui/overview.js')).refreshHome;
} catch (err) {
  failures.push('载入首页脚本就抛了：' + (err && err.stack ? err.stack : err));
}

async function draw(url) {
  statusBody = statusWith(url);
  node('#update-pop').innerHTML = '';
  node('#home-todo').innerHTML = '';
  const back = await refreshHome();
  check(back != null, '首页没取到运行状态，什么也没画：' + node('#status-text').textContent);
  return {pop: node('#update-pop').innerHTML, todo: node('#home-todo').innerHTML};
}

const SCRIPTS = ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', ' javascript:alert(1)'];

function noScriptHref(html, where, url) {
  for (const a of anchors(html)) {
    const href = a.href == null ? '' : a.href;
    check(href.trim().toLowerCase() !== url.trim().toLowerCase()
      && !/^\s*javascript:/i.test(href),
    where + '里有链接指向脚本地址 ' + JSON.stringify(url) + '：' + a.tag);
  }
}

function newTab(html, where, url) {
  const hit = anchors(html).filter(a => a.href === url);
  check(hit.length === 1, where + '里应有一条链接指向 ' + url + '，得到 ' + hit.length + ' 条。正文：' + html);
  for (const a of hit) {
    check(/\btarget\s*=\s*"_blank"/i.test(a.tag), where + '的链接不新开一页：' + a.tag);
    const rel = (/\brel\s*=\s*"([^"]*)"/i.exec(a.tag) || [, ''])[1].toLowerCase().split(/\s+/);
    check(rel.includes('noopener') && rel.includes('noreferrer'),
      where + '的链接 rel 要同时有 noopener 与 noreferrer：' + a.tag);
  }
}

if (refreshHome) {
  if (mode === 'about-script') {
    for (const url of SCRIPTS) {
      const {pop} = await draw(url);
      check(pop.includes('新版本 v5.1.0'), '关于卡没画出来，这一格什么也没量。正文：' + pop);
      check(pop.includes('此版本不再提醒'), '关于卡的「先不提醒」不见了');
      noScriptHref(pop, '关于卡', url);
    }
  } else if (mode === 'todo-script') {
    for (const url of SCRIPTS) {
      const {todo} = await draw(url);
      const row = updateTodo(todo);
      check(row !== '', '首页待办里没有「有新版」那一行，这一格什么也没量。正文：' + todo);
      noScriptHref(todo, '首页待办', url);
    }
  } else if (mode === 'https-about') {
    const url = 'https://example.invalid/releases/v5.1.0';
    const {pop} = await draw(url);
    newTab(pop, '关于卡', url);
  } else if (mode === 'https-todo') {
    const url = 'https://example.invalid/releases/v5.1.0';
    const {todo} = await draw(url);
    newTab(updateTodo(todo), '首页待办', url);
  } else {
    failures.push('不认得的参数：' + JSON.stringify(mode));
  }
}

console.log('跑了 ' + checks + ' 格');
if (failures.length) {
  for (const line of failures) console.log(line);
  process.exit(1);
}
