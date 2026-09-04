/**
 * 首页视图模型的八档对照
 *
 * 首页要在八种情形下都对得上，而这八种在真机上凑齐一次的代价极高：QQ 掉线、直播间断流、
 * 接口变慢这几档要么等故障发生，要么去改线上配置。视图模型因此被切成一个纯函数
 * （home-model.js，不碰 DOM），本文件喂它八份回包，逐档核对链路三段的灯色、顶部横条、
 * 待办与「今天发生了什么」的条数。
 *
 * 用 node 直接跑：
 *   node tools/home-model-check.mjs
 * 退码 0 即八档全对；任一档对不上打印差异并以 1 退出。
 */

import {homeModel} from '../starbot-core/src/main/resources/config-ui/home-model.js';

/** 探针的原样形态，与 /api/status 里 health 那一项逐字段同形 */
function probe(name, scope, level, summary, advice, loginState) {
  return {name, scope, level, summary, advice: advice || '', loginState: !!loginState};
}

const OK_PROBES = () => [
  probe('哔哩哔哩登录', 'PLATFORM', 'OK', '正常（uid 12345678）', '', true),
  probe('机器人连接', 'BOT', 'OK', '默认：HTTP 正常 / 账号 在线 / WS 正常'),
  probe('直播间连接', 'PLATFORM', 'OK', '3/3 已连接'),
  probe('推送活动', 'SYSTEM', 'OK', '成功 128 次，失败 2 次'),
  probe('风控与静默降级', 'PLATFORM', 'OK', '7 天内 412 0 次'),
  probe('数据存储', 'SYSTEM', 'OK', '本场数据 + 累计数据'),
];

/** 一份「一切正常」的 /api/status，各档在它上面改动几处 */
function status(patch) {
  return Object.assign({
    success: true,
    health: OK_PROBES(),
    users: [{uid: 3493, uname: '柚子', roomId: 1234, platform: 'bilibili', enabled: true, targets: 3}],
    runtime: {heapUsedMb: 123, heapMaxMb: 512, threads: 42, processors: 8},
    version: '5.1.0',
    configPath: '/opt/novabot/application.yml',
    streamerLimit: 10,
    pushEnabled: true,
    restartPending: [],
    senders: ['默认'],
    locked: true,
    totalDataAvailable: true,
    quiet: {active: false, start: '', end: ''},
    live: [{uid: 3493, uname: '柚子', roomId: 1234, platform: 'bilibili', since: 1757000000000}],
    today: {sent: 14, failed: 1},
  }, patch);
}

function login(patch) {
  return Object.assign({
    success: true,
    accounts: [{platform: 'bilibili', displayName: '哔哩哔哩', loggedIn: true, accountId: '12345678'}],
  }, patch);
}

function event(level, type, text) {
  return {at: 1757000000000, type, typeText: type, level, streamer: null, channel: '群 12345', text, detail: {}};
}

/** 今天 12 条：2 条失败、1 条告警、9 条普通 */
function timeline(events) {
  return {success: true, events: events || [
    event('error', 'PUSH_FAILED', '推送失败：图片下载超时'),
    event('error', 'PUSH_FAILED', '推送失败：连接被拒'),
    event('warn', 'PROBE_CHANGED', '推送活动 转为 队列积压'),
    ...Array.from({length: 9}, (_, i) => event('info', 'PUSH_SENT', '已推送：开播通知 ' + i)),
  ]};
}

// ── 八档 ───────────────────────────────────────────────────────────────
// 每档写明：改了哪几处回包字段（驱动字段），以及首页该长成什么样（应）
const CASES = [
  {
    name: '正常',
    status: status(),
    login: login(),
    timeline: timeline(),
    expect: {chain: ['ok', 'ok', 'ok'], banner: null, todos: [], events: 8},
  },
  {
    name: '首次安装',
    // 没有配置文件或配置里没有机器人：一个推送平台都没注册出来，也没有主播
    status: status({
      senders: [], users: [], live: [], locked: false, totalDataAvailable: false,
      today: {sent: 0, failed: 0},
      health: [
        probe('哔哩哔哩登录', 'PLATFORM', 'DEGRADED', '未登录', '请到「连接」页扫码登录', true),
        probe('机器人连接', 'BOT', 'DOWN', '未配置任何机器人', '请在「连接」页填写地址与端口'),
        probe('直播间连接', 'PLATFORM', 'OK', '暂无需要连接的直播间'),
        probe('推送活动', 'SYSTEM', 'OK', '启动后尚无推送'),
        probe('风控与静默降级', 'PLATFORM', 'OK', '7 天内 412 0 次'),
        probe('数据存储', 'SYSTEM', 'OK', '仅本场数据'),
      ],
    }),
    login: login({accounts: [{platform: 'bilibili', displayName: '哔哩哔哩', loggedIn: false, accountId: null}]}),
    timeline: timeline([]),
    expect: {chain: ['off', 'off', 'off'], banner: 'setup', todos: ['setup'], events: 0},
  },
  {
    name: 'QQ 掉线',
    status: status({
      health: OK_PROBES().map(p => {
        if (p.name === '机器人连接') {
          return probe(p.name, p.scope, 'DOWN', '默认 的 QQ 账号已掉线，接口仍可调用但消息不会送达',
            '请到 OneBot 实现的界面重新扫码登录');
        }
        if (p.name === '推送活动') {
          return probe(p.name, p.scope, 'DEGRADED', '队列积压 3 条', '请检查机器人连接');
        }
        return p;
      }),
      today: {sent: 11, failed: 3},
    }),
    login: login(),
    timeline: timeline(),
    expect: {chain: ['ok', 'warn', 'err'], banner: 'botdown', todos: ['bot'], events: 8},
  },
  {
    name: '直播平台掉登录',
    status: status({
      health: OK_PROBES().map(p => p.name === '哔哩哔哩登录'
        ? probe(p.name, p.scope, 'DEGRADED', '未登录 · 动态推送与自动关注不可用，直播推送不受影响',
          '请扫描「连接」页上的二维码完成登录', true)
        : p),
    }),
    login: login({accounts: [{platform: 'bilibili', displayName: '哔哩哔哩', loggedIn: false, accountId: null}]}),
    timeline: timeline(),
    expect: {chain: ['warn', 'ok', 'ok'], banner: 'platform', todos: [], events: 8},
  },
  {
    name: '直播间断流',
    status: status({
      health: OK_PROBES().map(p => p.name === '直播间连接'
        ? probe(p.name, p.scope, 'DEGRADED', '3/3 已连接，其中 1 个业务消息断流', '原因未定，先看该直播间是否确有人在互动')
        : p),
    }),
    login: login(),
    timeline: timeline(),
    expect: {chain: ['warn', 'ok', 'ok'], banner: 'platform', todos: [], events: 8, gap: true},
  },
  {
    name: '推送变慢',
    status: status({
      health: OK_PROBES().map(p => p.name === '机器人连接'
        ? probe(p.name, p.scope, 'DEGRADED', '默认：调用慢（中位 3.2 秒）', '带图的推送可能因超时被丢弃')
        : p),
      today: {sent: 14, failed: 2},
    }),
    login: login(),
    timeline: timeline(),
    expect: {chain: ['ok', 'ok', 'warn'], banner: 'botslow', todos: [], events: 8},
  },
  {
    name: '静音时段中',
    status: status({quiet: {active: true, start: '23:00', end: '08:00'}}),
    login: login(),
    timeline: timeline(),
    // 静音把 QQ 那一段判成熄灯：链路本身通着，是这一段此刻按配置不走消息
    expect: {chain: ['ok', 'ok', 'off'], banner: 'quiet', todos: [], events: 8},
  },
  {
    name: '已暂停推送',
    status: status({pushEnabled: false}),
    login: login(),
    timeline: timeline(),
    expect: {chain: ['ok', 'ok', 'off'], banner: 'paused', todos: [], events: 8},
  },
];

// ── 跑 ─────────────────────────────────────────────────────────────────
const bad = [];
const rows = [];

for (const item of CASES) {
  const model = homeModel(item.status, item.login, item.timeline);
  const chain = [model.chain.platform.level, model.chain.self.level, model.chain.bot.level];
  const banner = model.banner ? model.banner.kind : null;
  const todos = model.todos.map(t => t.key);
  const events = model.events.length;

  const want = item.expect;
  const fail = [];
  if (chain.join(',') !== want.chain.join(',')) fail.push(`三段色 期望 ${want.chain} 实得 ${chain}`);
  if (banner !== want.banner) fail.push(`横条 期望 ${want.banner} 实得 ${banner}`);
  // 待办按「必须出现哪几条」比，不比顺序之外的多余项：多出来的一律算差异
  if (todos.join(',') !== want.todos.join(',')) fail.push(`待办 期望 [${want.todos}] 实得 [${todos}]`);
  if (events !== want.events) fail.push(`短条条数 期望 ${want.events} 实得 ${events}`);
  if (want.gap && !model.now.note) fail.push('断流档「现在」卡缺采集缺口注');

  rows.push([item.name, chain.join('/'), String(banner), '[' + todos + ']', String(events),
    fail.length ? '红' : '绿'].join('\t'));
  if (fail.length) bad.push(item.name + '：' + fail.join('；'));
}

console.log(['档', '三段色', '横条', '待办', '短条', '判定'].join('\t'));
rows.forEach(r => console.log(r));

if (bad.length) {
  console.error('\n对不上 ' + bad.length + ' 档：');
  bad.forEach(b => console.error('  ' + b));
  process.exit(1);
}
console.log('\n八档全对');
