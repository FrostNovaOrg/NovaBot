/**
 * 首页视图模型的九档对照
 *
 * 首页要在八种情形下都对得上，而这八种在真机上凑齐一次的代价极高：QQ 掉线、直播间断流、
 * 接口变慢这几档要么等故障发生，要么去改线上配置。视图模型因此被切成一个纯函数
 * （home-model.js，不碰 DOM），本文件喂它回包，逐档核对链路三段的灯色、顶部横条、
 * 待办与「今天发生了什么」的条数。第九档（有新版）是后来加的：它给待办表添了一条，
 * 恰好也压着「待办只放要人动手的事」这条规矩的边界。
 *
 * 用 node 直接跑：
 *   node tools/home-model-check.mjs
 * 退码 0 即九档全对；任一档对不上打印差异并以 1 退出。
 */

import {homeModel, PROBE_ANCHOR, setupDone, shouldOpenSetup, stationHref, todayAtAllMarkup} from '../starbot-core/src/main/resources/config-ui/home-model.js';

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
    // 八档说的是一台已经配过告警的机器上的运行态。没配时首页会多一条软待办，
    // 那一档下面单独喂，免得八档每一档都沾上同一条
    alerts: {qq: false, webhook: true, mail: false},
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

// ── 九档 ───────────────────────────────────────────────────────────────
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
  {
    name: '有新版',
    // 服务器判「该提示」时 /api/status 才有 update 这一块：版本、说明与站外链接同形
    status: status({update: {
      latestVersion: 'v5.2.0',
      notes: ['修了开播误报', '第二行说明', '第三行说明'],
      url: 'https://example.invalid/release',
    }}),
    login: login(),
    timeline: timeline(),
    // 软待办一条，排在最后；机器一切正常，链路横条都不动
    expect: {chain: ['ok', 'ok', 'ok'], banner: null, todos: ['update'], events: 8, updateTodo: true},
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
  if (want.updateTodo) {
    const t = model.todos.find(x => x.key === 'update');
    if (!t || t.soft !== true || String(t.href).indexOf('http') !== 0) {
      fail.push('新版待办应是一条软的、指向站外说明的待办');
    }
  }

  rows.push([item.name, chain.join('/'), String(banner), '[' + todos + ']', String(events),
    fail.length ? '红' : '绿'].join('\t'));
  if (fail.length) bad.push(item.name + '：' + fail.join('；'));
}

console.log(['档', '三段色', '横条', '待办', '短条', '判定'].join('\t'));
rows.forEach(r => console.log(r));

const hrefBad = [];
if (stationHref('self') !== '#/home?card=probes') {
  hrefBad.push('本机站应去首页探针区，实得「' + stationHref('self') + '」');
}
if (stationHref('platform') !== '#/links?card=platform') {
  hrefBad.push('平台站应去连接页，实得「' + stationHref('platform') + '」');
}
if (stationHref('bot') !== '#/links?card=bot') {
  hrefBad.push('机器人站应去连接页，实得「' + stationHref('bot') + '」');
}
if (stationHref('nope') !== '') hrefBad.push('未知站名应回空串');
if (PROBE_ANCHOR !== 'home-probes') hrefBad.push('探针区锚应为 home-probes');

if (bad.length || hrefBad.length) {
  console.error('\n对不上 ' + (bad.length + hrefBad.length) + ' 处：');
  bad.forEach(b => console.error('  ' + b));
  hrefBad.forEach(b => console.error('  落点：' + b));
  process.exit(1);
}
console.log('本机站\t' + stationHref('self') + '\t锚 #' + PROBE_ANCHOR + '\t绿');
console.log('\n九档全对，本机站落到首页探针区');

// ── 「今日」第三格与 Webhook 待办 ──────────────────────────────────────
// 这两块是后补上的：额度接口与告警三卡交付之后，首页才有真数据可摆。
// 判法只留 home-model 一份。下面逐格喂回包对答案，不碰 DOM。

const tileFails = [];
let tileChecks = 0;

function same(actual, expected, what) {
  tileChecks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) tileFails.push(what + '：得到 ' + a + '，应为 ' + b);
}

function tileOf(quota) {
  const model = homeModel(status(), login(), timeline(), quota);
  return model.today.atAll || {value: null, label: null, details: [], more: 0};
}

function quota(bots, sessions) {
  return {success: true, date: '2026-09-04', bots: bots || [], sessions: sessions || []};
}

function bot(over) {
  return Object.assign({platform: 'onebot', used: 0, limit: 10, limited: true}, over);
}

function group(num, used, over) {
  return Object.assign({platform: 'onebot', num, used, limit: 20, limited: true}, over);
}

// 格三态
same(tileOf(quota([bot({used: 3, limit: 10, limited: true})])).value, '3/10',
  '限额：账号已用画分母');
same(tileOf(quota([bot({used: 3, limit: 0, limited: false})])).value, '3',
  '不限额：只显已用、不画分母');
same(tileOf(quota([])).value, '—', '无机器人显「—」');
same(tileOf(null).value, '—', '接口失败显「—」');
same(tileOf(undefined).value, '—', '没交额度回包显「—」');
same(tileOf({success: false}).value, '—', '回包声明失败显「—」');

// 多账号合计：两路都限额则分母相加；一路不限额则整格不画分母
same(tileOf(quota([
  bot({platform: 'a', used: 3, limit: 10, limited: true}),
  bot({platform: 'b', used: 2, limit: 10, limited: true}),
])).value, '5/20', '两个限额账号合计已用与上限');
same(tileOf(quota([
  bot({platform: 'a', used: 3, limit: 10, limited: true}),
  bot({platform: 'b', used: 2, limit: 0, limited: false}),
])).value, '5', '有一个不限额则整格不画分母');

// 明细：按 used 降序、最多 5 行、其余报还有 N 个群
const six = tileOf(quota([bot({used: 1})], [
  group(11, 1), group(12, 8), group(13, 3), group(14, 8), group(15, 0), group(16, 5),
]));
same(six.details.map(item => item.num), [12, 14, 16, 13, 11],
  '明细按已用降序取前 5，used 相同的保持原序');
same(six.more, 1, '第 6 个群进「还有 N 个群」');
same((six.details[0] || {}).text, '8/20', '限额群的明细也画分母');
same((tileOf(quota([bot()], [group(11, 4, {limited: false, limit: 0})])).details[0] || {}).text, '4',
  '不限额群的明细不画分母');
same(tileOf(quota([bot()], [group(11, 1)])).more, 0, '不超过 5 个群时没有「还有」');

same(tileOf(quota([bot({used: 3})])).label, '@全体成员 已用', '格的标签');

same(tileOf(quota([bot({used: 3, limit: 10, limited: true})])).value.includes('／'), false,
  '限额格不含全角斜线');
same((six.details[0] || {}).text.includes('／'), false, '明细不含全角斜线');

same((tileOf(quota([bot({used: 1})], [group(11, 1, {platform: 'qq-onebot'})])).details[0] || {}).who,
  '群 11', '单平台无前缀');
same((tileOf(quota([bot({used: 1})], [
  group(11, 3, {platform: 'qq-onebot'}),
  group(12, 1, {platform: 'other-bot'}),
])).details[0] || {}).who, 'QQ 群 11', '双平台 QQ 前缀');
same((tileOf(quota([bot({used: 1})], [
  group(11, 3, {platform: 'alpha-bot'}),
  group(12, 1, {platform: 'beta-bot'}),
])).details[0] || {}).who, 'alpha-bot 群 11', '未知平台标识');

const twin = tileOf(quota([bot({used: 4})], [
  group(11, 3, {platform: 'alpha-bot'}),
  group(11, 1, {platform: 'beta-bot'}),
]));
const whoA = (twin.details[0] || {}).who;
const whoB = (twin.details[1] || {}).who;
tileChecks++;
if (whoA === whoB) {
  tileFails.push('同号异平台两条明细文本不应相同：得到 '
    + JSON.stringify(whoA) + ' 与 ' + JSON.stringify(whoB));
}
const twinHtml = todayAtAllMarkup(twin, false);
tileChecks++;
if (!(whoA && whoB && twinHtml.includes(String(whoA)) && twinHtml.includes(String(whoB)))) {
  tileFails.push('明细 HTML 应带上两条不同的平台群名，得到 ' + twinHtml);
}

const emptyHtml = todayAtAllMarkup(tileOf(quota([bot({used: 0})])), false);
tileChecks++;
if (emptyHtml.includes('<button')) {
  tileFails.push('无明细不应渲染 button，得到 ' + emptyHtml);
}
const closedHtml = todayAtAllMarkup(tileOf(quota([bot({used: 1})], [group(11, 1)])), false);
tileChecks++;
if (!closedHtml.includes('aria-expanded="false"')) {
  tileFails.push('有明细收起时应 aria-expanded="false"，得到 ' + closedHtml);
}
const openedHtml = todayAtAllMarkup(tileOf(quota([bot({used: 1})], [group(11, 1)])), true);
tileChecks++;
if (!openedHtml.includes('aria-expanded="true"')) {
  tileFails.push('有明细摊开后应 aria-expanded="true"，得到 ' + openedHtml);
}

// 待办四态。催的是掉线时还有一路能叫到人，QQ 配没配都不算出。
// fresh 那一档只留初始设置，这条不掺进去。
function todoKeys(patch) {
  return homeModel(status(patch), login(), timeline()).todos.map(item => item.key);
}

same(todoKeys({alerts: {qq: false, webhook: false, mail: false}}), ['webhook'],
  '都没配：出 Webhook 软待办');
same(todoKeys({alerts: {qq: true, webhook: false, mail: false}}), ['webhook'],
  '只配了 QQ：仍出待办');
same(todoKeys({alerts: {qq: false, webhook: true, mail: false}}), [],
  '有 Webhook：不出待办');
same(todoKeys({alerts: {qq: false, webhook: false, mail: false}}), ['webhook'],
  '只填收件无主机（后端判未配，mail 为假）：出待办');
same(todoKeys({alerts: {qq: false, webhook: false, mail: true}}), [],
  '邮件已配：不出待办');
same(todoKeys({locked: false, alerts: {qq: false, webhook: false, mail: false}}),
  ['lock', 'webhook'],
  '三路都没配时与上锁待办并存、不去重');
same(todoKeys({alerts: {}}), ['webhook'],
  'alerts 在但三路都缺：作出没配');
same(todoKeys({}), [],
  '默认那份已经配了 Webhook，八档不沾这条待办');

const webhookTodo = homeModel(status({alerts: {qq: false, webhook: false, mail: false}}),
  login(), timeline()).todos[0];
same((webhookTodo || {}).soft, true, 'Webhook 待办是软的');
same((webhookTodo || {}).href, '#/settings?card=alert',
  '点待办落到设置页告警段');
same((webhookTodo || {}).title, 'QQ 告警有死角，建议再配 Webhook', '待办标题');
same((webhookTodo || {}).body,
  '机器人掉线时 QQ 那路叫不到你，Webhook 或邮件配好其中一路这条就消失',
  '待办正文');
same((webhookTodo || {}).action, '去配', '待办按钮');

// 首次安装只出初始设置，不叠 Webhook 待办
const freshStatus = status({
  senders: [], users: [], live: [], locked: false, totalDataAvailable: false,
  alerts: {qq: false, webhook: false, mail: false},
  today: {sent: 0, failed: 0},
  health: [
    probe('直播平台登录', 'PLATFORM', 'DEGRADED', '未登录', '', true),
    probe('机器人连接', 'BOT', 'DOWN', '未配置任何机器人', ''),
    probe('直播间连接', 'PLATFORM', 'OK', '暂无需要连接的直播间'),
    probe('推送活动', 'SYSTEM', 'OK', '启动后尚无推送'),
    probe('风控与静默降级', 'PLATFORM', 'OK', '7 天内 412 0 次'),
    probe('数据存储', 'SYSTEM', 'OK', '仅本场数据'),
  ],
});
same(homeModel(freshStatus, login(), timeline()).todos.map(item => item.key), ['setup'],
  '首次安装只出初始设置待办');

function blankLogin() {
  return login({
    accounts: [{platform: 'bilibili', displayName: '哔哩哔哩', loggedIn: false, accountId: null}],
  });
}

same(shouldOpenSetup(null, blankLogin()), false, '还没取到回包时不拦——配好的机器不能先闪初始设置');
same(setupDone(freshStatus, blankLogin()), 0, '同意协议后五步仍是 0（文件在不在不算）');
same(shouldOpenSetup(freshStatus, blankLogin()), true, '五步全没做：进首页该转到初始设置');
same(shouldOpenSetup(status(), login()), false, '五步都齐了：不转');
same(shouldOpenSetup(Object.assign({}, freshStatus, {locked: true}), blankLogin()), false,
  '只上了锁也算做了一步，不转');
same(shouldOpenSetup(freshStatus, login()), false, '已经登录直播平台：不是 0 步');

if (tileFails.length) {
  console.error('\n今日格／待办对不上 ' + tileFails.length + ' 处（共跑了 ' + tileChecks + ' 格）：');
  tileFails.forEach(line => console.error('  ' + line));
  process.exit(1);
}
console.log('今日格／待办\t跑了 ' + tileChecks + ' 格，全绿');
console.log('跑了 ' + (CASES.length + tileChecks) + ' 格，全绿');
