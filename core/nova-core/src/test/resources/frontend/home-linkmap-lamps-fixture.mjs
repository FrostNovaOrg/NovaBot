/**
 * 首页连接图：三个站都带状态灯，读屏念得出各自的状态
 *
 * 只有本机那站有灯的话，直播平台或机器人那一侧掉了，图上那一站看不出来，
 * 只剩两站之间的线在变色，读屏更是一句也念不到。
 * 这里喂进三份运行状态，各站的灯色互相错开：每站的灯照它自己那一段的级别画，
 * 灯本身对读屏藏起来，站按钮的 aria-label 写着这一站此刻的状态。
 * overview.js 交不出 refreshHome 时记一格红，不空转。
 *
 * 由 HomeLinkMapLampsTest 拉起。退码 0 ＝ 全绿。
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

function probe(scope, level, summary) {
  return {name: scope + ' 探针', scope, level, summary, advice: ''};
}

function status(health, extra) {
  return Object.assign({
    success: true,
    senders: [{id: 'qq'}],
    users: [{uid: 1, uname: '甲', platform: 'bilibili', enabled: true, targets: 1}],
    health,
    locked: true,
    totalDataAvailable: true,
    alerts: {webhook: true, mail: true},
    pushEnabled: true,
    live: [],
    quiet: {active: false},
    version: '9',
    runtime: {heapUsedMb: 1, heapMaxMb: 2, threads: 1},
  }, extra || {});
}

// 每站在三份里至少换过两种灯色：哪一站的灯若照别的站画、或写死一种颜色，总有一份对不上
const CASES = [
  {
    name: '平台黄、本机绿、机器人红',
    body: status([
      probe('PLATFORM', 'DEGRADED', '直播间连接不稳'),
      probe('SYSTEM', 'OK', '本机一切正常'),
      probe('BOT', 'DOWN', '机器人连不上'),
    ]),
    want: {platform: 'warn', self: 'ok', bot: 'err'},
  },
  {
    name: '平台红、本机黄、机器人绿',
    body: status([
      probe('PLATFORM', 'DOWN', '账号登录失效'),
      probe('SYSTEM', 'DEGRADED', '磁盘快满了'),
      probe('BOT', 'OK', '机器人在线'),
    ]),
    want: {platform: 'err', self: 'warn', bot: 'ok'},
  },
  {
    name: '推送暂停：机器人那站熄灯，另两站绿',
    body: status([
      probe('PLATFORM', 'OK', '直播间连着'),
      probe('SYSTEM', 'OK', '本机一切正常'),
      probe('BOT', 'OK', '机器人在线'),
    ], {pushEnabled: false}),
    want: {platform: 'ok', self: 'ok', bot: 'off'},
    botState: '已暂停',
  },
  {
    name: '静音生效：机器人灰灯并说明静音中',
    body: status([], {quiet: {active: true, start: '22:00', end: '08:00'}}),
    want: {platform: 'off', self: 'off', bot: 'off'},
    botState: '静音中',
  },
  {
    name: '尚未配置：机器人仍写未启用',
    body: status([], {senders: [], users: [], pushEnabled: false}),
    want: {platform: 'off', self: 'off', bot: 'off'},
    botState: '未启用',
  },
];

/** 灯色对应读屏念出来的那个词。认不出的级别念「状态未知」 */
const STATE_WORD = {ok: '正常', warn: '需要关注', err: '连接异常', off: '未启用'};

let current = CASES[0].body;

function ok(body) {
  return Promise.resolve({status: 200, json: () => Promise.resolve(body)});
}

globalThis.fetch = url => {
  const path = String(url);
  if (path.includes('/pages')) return ok({pages: []});
  if (path.includes('/status')) return ok(current);
  if (path.includes('/login')) return ok({success: true, accounts: []});
  if (path.includes('/timeline')) return ok({success: true, matched: 0, events: []});
  return Promise.reject(new Error('没准备这一趟 ' + path));
};

/** 切出某一站那颗按钮的开标签与整段 */
function stationOf(html, key) {
  const at = html.indexOf('data-goto="' + key + '"');
  if (at < 0) return null;
  const start = html.lastIndexOf('<button', at);
  const end = html.indexOf('</button>', at);
  if (start < 0 || end < 0) return null;
  const whole = html.slice(start, end);
  return {open: whole.slice(0, whole.indexOf('>') + 1), whole};
}

function attr(tag, name) {
  const m = tag.match(new RegExp('\\s' + name + '="([^"]*)"'));
  return m ? m[1] : null;
}

/** 每个状态入口内的灯：灯色与标签里余下的属性。 */
function lampsIn(whole) {
  return [...whole.matchAll(/<span class="lamp ([^"]*)"([^>]*)>/g)].map(m => ({level: m[1], rest: m[2]}));
}

register(new URL('./home-problems-fetch-failure-loader.mjs', import.meta.url));

let refreshHome = null;
let loadErr = null;
try {
  refreshHome = (await import('../../../main/resources/config-ui/overview.js')).refreshHome;
} catch (err) {
  loadErr = err;
}

if (typeof refreshHome === 'function') {
  for (const item of CASES) {
    current = item.body;
    node('#linkmap').innerHTML = '';
    const back = await refreshHome();
    eq(back != null, true, item.name + '：首页取到了运行状态');
    const html = node('#linkmap').innerHTML;

    for (const key of ['platform', 'self', 'bot']) {
      const where = item.name + ' · ' + key + ' 站';
      const level = item.want[key];
      const word = key === 'bot' && item.botState || STATE_WORD[level];
      const st = stationOf(html, key);
      eq(st != null, true, where + '画出来了');
      if (!st) continue;

      const lamps = lampsIn(st.whole);
      eq(lamps.length, 1, where + '的状态入口内有且只有一盏灯');
      if (lamps.length) {
        eq(lamps[0].level, level, where + '的灯色照这一段的级别');
        eq(lamps[0].rest.includes('aria-hidden="true"'), true, where + '的灯对读屏藏起来');
      }

      if (key === 'bot' && item.botState) {
        eq(st.whole.includes('>' + word + '</span>'), true, where + '格上写着' + word);
        if (word !== '未启用') eq((attr(st.open, 'title') || '').includes(word), true, where + '悬停说明含' + word);
      }
      const label = attr(st.open, 'aria-label');
      eq(label != null, true, where + '的按钮带 aria-label');
      if (label != null) {
        eq(label.includes(word), true,
          where + '的 aria-label 写着「' + word + '」，实为「' + label + '」');
        const others = [...Object.values(STATE_WORD), '已暂停', '静音中'].filter(w => w !== word);
        eq(others.filter(w => label.includes(w)), [], where + '的 aria-label 不写别的状态');
      }
    }
  }
} else {
  eq(typeof refreshHome, 'function', '首页脚本要给出 refreshHome（'
    + (loadErr ? '导入就抛了：' + (loadErr.stack || loadErr)
      : '导入没抛，但导出里没有 refreshHome') + '）');
}

console.log('跑了 ' + checks + ' 格');
if (failures.length) {
  for (const line of failures) console.log(line);
  process.exit(1);
}
