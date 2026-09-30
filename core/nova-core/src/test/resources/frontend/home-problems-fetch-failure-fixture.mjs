/**
 * 首页另取失败与告警那一趟出错时，首页仍要画出来。
 *
 * 那一趟是后来加上的。它网络中断、或回包不是数据时，若和原来三趟绑在同一处等，
 * 整页会报载入失败，一条都不画。改前没有这一趟，首页照常，短条从时间线这一页里数。
 * 取不到这份数据时就退回那种数法：这一页里的失败仍显示，普通记录仍补上。
 * 原来三趟里任何一趟出错，整页仍报载入失败，这一点不改。
 * 那一趟正常回来时，短条仍用它带回来的失败，不退回这一页。
 *
 * 由 HomeProblemsFetchFailureTest 拉起。退码 0 ＝ 全绿。
 */

import {register} from 'node:module';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function has(hay, needle, what) {
  checks++;
  if (!String(hay ?? '').includes(needle)) {
    failures.push(what + '：正文里没有「' + needle + '」。正文：' + JSON.stringify(hay));
  }
}

function lacks(hay, needle, what) {
  checks++;
  if (String(hay ?? '').includes(needle)) {
    failures.push(what + '：正文里不该有「' + needle + '」。正文：' + JSON.stringify(hay));
  }
}

class Node {
  constructor() {
    this.innerHTML = '';
    this.textContent = '';
    this.className = '';
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

const statusBody = {
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
  version: '9',
  runtime: {heapUsedMb: 1, heapMaxMb: 2, threads: 1},
};
const loginBody = {success: true, accounts: []};

function row(level, text) {
  return {
    at: 1757000000000, type: 'ALERT_FAILED', typeText: '告警发不出', level,
    streamer: null, channel: 'QQ', text, detail: {},
  };
}

const pageWithFailure = {
  success: true,
  matched: 2,
  events: [row('error', '页上这条失败'), row('info', '页上这条普通记录')],
};
const pageOnlyInfo = {
  success: true,
  matched: 1,
  events: [row('info', '只有普通记录')],
};
const problemsOk = {
  success: true,
  matched: 1,
  events: [row('error', '另取到的失败')],
};

let mode = 'problems-down';
const hits = {problems: 0};

function ok(body) {
  return Promise.resolve({status: 200, json: () => Promise.resolve(body)});
}

function broken(message) {
  return Promise.resolve({
    status: 500,
    json: () => Promise.reject(new SyntaxError(message)),
  });
}

globalThis.fetch = url => {
  const path = String(url);
  if (path.includes('/pages')) return ok({pages: []});
  if (path.includes('/status')) return ok(statusBody);
  if (path.includes('/login')) {
    if (mode === 'login-down') return broken('登录这一趟断了');
    return ok(loginBody);
  }
  if (path.includes('problems=true')) {
    hits.problems++;
    if (mode === 'problems-down') return broken('Unexpected token < in JSON');
    return ok(problemsOk);
  }
  if (path.includes('/timeline')) {
    return ok(mode === 'problems-ok' ? pageOnlyInfo : pageWithFailure);
  }
  return Promise.reject(new Error('没准备这一趟 ' + path));
};

function clearScreen() {
  node('#home-timeline').innerHTML = '';
  node('#status-text').textContent = '';
  node('#status-text').className = '';
}

register(new URL('./home-problems-fetch-failure-loader.mjs', import.meta.url));

let refreshHome = null;
try {
  refreshHome = (await import('../../../main/resources/config-ui/overview.js')).refreshHome;
} catch (err) {
  failures.push('载入首页脚本就抛了：' + (err && err.stack ? err.stack : err));
}

if (refreshHome) {
  mode = 'problems-down';
  hits.problems = 0;
  clearScreen();
  let back = null;
  try {
    back = await refreshHome();
  } catch (err) {
    failures.push('失败与告警那一趟出错时，取数本身又抛了：' + err.message);
    checks++;
  }
  eq(hits.problems > 0, true, '失败与告警那一趟仍然发出');
  eq(back != null, true, '失败与告警那一趟出错时首页仍取到运行状态');
  const strip = node('#home-timeline').innerHTML;
  const note = node('#status-text').textContent;
  lacks(note, '载入首页失败', '失败与告警那一趟出错时不报整页载入失败');
  has(strip, '页上这条失败', '取不到那份数据时，短条仍显示这一页里的失败');
  has(strip, '页上这条普通记录', '取不到那份数据时，没占满的位置仍补这一页的普通记录');
  lacks(strip, '还有', '这一页里的失败没超过八条，不另起还有几条那一行');

  mode = 'problems-ok';
  clearScreen();
  back = await refreshHome();
  eq(back != null, true, '失败与告警那一趟正常回来时首页仍取到运行状态');
  has(node('#home-timeline').innerHTML, '另取到的失败', '那一趟正常回来时，短条用它带回来的失败');
  lacks(node('#status-text').textContent, '载入首页失败', '那一趟正常回来时不报整页载入失败');

  mode = 'login-down';
  node('#home-timeline').innerHTML = '尚未重画';
  node('#status-text').textContent = '';
  back = await refreshHome();
  eq(back, null, '原来三趟里登录那一趟出错时，首页不把这一趟当成取到了');
  has(node('#status-text').textContent, '载入首页失败', '原来三趟里登录那一趟出错时，仍报整页载入失败');
  eq(node('#home-timeline').innerHTML, '尚未重画', '原来三趟出错时首页不重画');
}

console.log('跑了 ' + checks + ' 格');
if (failures.length) {
  for (const line of failures) console.log(line);
  process.exit(1);
}
