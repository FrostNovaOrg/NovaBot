/**
 * QQ 推送页视图模型的逐档对照
 *
 * 这一页要在好几种情形下都说得对，而这些情形在真机上凑齐一次的代价极高：命令被群管理员关掉
 * 要先去群里发一条「禁用命令」；累计数据没开要去改线上存储配置；@全体成员 那一档要把
 * 三位主播的模板都改一遍；OneBot 掉线才看得到「群名取不到」。视图模型因此被切成纯函数
 * （push-model.js，不碰 DOM、不发请求），本文件喂它几份回包，逐档核对树、通道一览、
 * 模板默认/自定义、命令摘要 14 → 12、被点名的那几条与「一键恢复」的集合。
 *
 * 用 node 直接跑：
 *   node tools/push-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {
  atAllStatus, buildDirectory, channelIndex, channelName, commandGroups, commandSummary,
  layoutState, menuKnown, noticeSwitches, previewRequestBody, previewRevenueCaption,
  pushTree, recentPushes, revenueSummary,
  sessionOf, strandedSessions, subscriptionSummary, templateState,
} from '../plugins/nova-console/src/main/resources/config-ui-pages/push-model.js';
import {targetOptions} from '../core/nova-core/src/main/resources/config-ui/links-model.js';

const PLATFORM = 'bilibili';
const SENDER = 'qq';

/** 处理器的原样形态，与 /api/handlers 里一项逐字段同形 */
function handler(className, displayName, placeholders, options) {
  return {
    className, displayName, description: displayName + '时推送', platform: PLATFORM,
    placeholders: placeholders || [], options: options || [],
    defaultParams: {message: '默认的' + displayName},
  };
}

const LAYOUT_OPTIONS = [
  {key: 'showRevenue', label: '显示营收', type: 'BOOLEAN', defaultValue: true},
  {key: 'danmuTop', label: '弹幕榜条数', type: 'INTEGER', defaultValue: 5},
];

const HANDLERS = [
  handler('LiveOn', '开播通知', ['{uname}', '{url}']),
  handler('LiveOff', '下播通知', ['{uname}', '{time}']),
  handler('LiveReport', '下播报告', ['{uname}', '{report}'], LAYOUT_OPTIONS),
  handler('Dynamic', '动态通知', ['{uname}', '{picture}']),
];

/** 命令的原样形态，与 /api/state 里 commands 一项逐字段同形 */
function command(name, category, extra) {
  return Object.assign({
    name, category, description: name + '做什么', usage: '', aliases: [],
    disableable: true, requiresAdmin: false, groupOnly: true, available: true,
  }, extra || {});
}

/** 14 条。与真机上那一份同形：三组，其中两条带「总」字的能力由累计存储定 */
const COMMANDS = [
  command('菜单', '命令管理', {disableable: false}),
  command('启用命令', '命令管理', {disableable: false, requiresAdmin: true}),
  command('禁用命令', '命令管理', {requiresAdmin: true}),
  command('开播@我', '提醒订阅'),
  command('取消开播@我', '提醒订阅'),
  command('开播@名单', '提醒订阅'),
  command('动态@我', '提醒订阅'),
  command('取消动态@我', '提醒订阅'),
  command('动态@名单', '提醒订阅'),
  command('直播报告', '数据查询'),
  command('直播间数据', '数据查询'),
  command('数据排行榜', '数据查询'),
  command('直播间总数据', '数据查询'),
  command('总数据排行榜', '数据查询'),
];

/** 累计存储没配：那两条「总」字命令整台机器都用不上，菜单里也就不列 */
const TOTAL_OFF = COMMANDS.map(item =>
  ['直播间总数据', '总数据排行榜'].includes(item.name)
    ? Object.assign({}, item, {available: false}) : item);

const TOTAL_OFF_HIDDEN = ['直播间总数据', '总数据排行榜'];

/** 本群这类通知全配成 @全体成员 时，后端把这三条从菜单里撤下来 */
const AT_ALL_HIDDEN = ['开播@我', '取消开播@我', '开播@名单'];

function session(patch) {
  return Object.assign({
    platform: SENDER, num: 12345, type: '群', configured: true,
    streamers: ['柚子'], disabled: [], revenueVisible: false, revenueExplicit: false,
    menuHidden: [], menuNotes: {},
  }, patch);
}

function messages(patch) {
  return HANDLERS.map(item => Object.assign({handler: item.className}, (patch || {})[item.className] || {}));
}

function user(patch) {
  return Object.assign({
    uid: 3493, platform: PLATFORM, enabled: true, _uname: '柚子', _roomId: 22345678,
    targets: [{platform: SENDER, type: 1, num: 12345, enabled: true, messages: messages()}],
  }, patch);
}

const DIRECTORY = buildDirectory(targetOptions(
  {items: [{sender: SENDER, num: 12345, name: '公会运营群', memberCount: 42, admin: true, configured: true}]},
  {items: [{sender: SENDER, num: 87654321, nickname: '小号', remark: '', configured: false}]}));

/** 机器人不是本群管理员的那一份名单，别的都一样 */
const DIRECTORY_NOT_ADMIN = buildDirectory(targetOptions(
  {items: [{sender: SENDER, num: 12345, name: '公会运营群', memberCount: 42, admin: false, configured: true}]},
  {items: []}));

const QUOTA = {
  date: '2026-09-04',
  bots: [{platform: SENDER, used: 1, limit: 10, limited: true}],
  sessions: [{platform: SENDER, num: 12345, used: 3, limit: 20, limited: true}],
};

function record(target, summary, ok) {
  return {at: '09-04 18:00', platform: SENDER, target, summary, success: ok !== false, reason: ok === false ? '超时' : null};
}

// ── 各档 ─────────────────────────────────────────────────────────────────
// 每档写明：改了哪几处回包（驱动字段），以及这一页该长成什么样（应）
const CASES = [
  {
    name: '正常',
    run: () => {
      const u = user();
      const s = session();
      const tree = pushTree([u], [s], HANDLERS, DIRECTORY);
      const sum = commandSummary(COMMANDS, s, true);
      return {
        '树': tree.length + ' 主播 / ' + tree[0].channels.length + ' 通道',
        '通道名': tree[0].channels[0].name,
        '模板': templateState(u.targets[0], HANDLERS).custom ? '自定义' : '默认',
        '命令': sum.listed + ' 条 · 关 ' + sum.offNames.length,
      };
    },
    expect: {'树': '1 主播 / 1 通道', '通道名': '公会运营群', '模板': '默认', '命令': '14 条 · 关 0'},
  },
  {
    name: '累计数据没开',
    run: () => {
      const s = session({menuHidden: TOTAL_OFF_HIDDEN});
      const sum = commandSummary(TOTAL_OFF, s, false);
      const groups = commandGroups(TOTAL_OFF, s, false);
      const query = groups.find(item => item.category === '数据查询');
      return {
        '总条数': sum.total,
        '列出来的': sum.listed,
        '摘要': sum.text,
        '灰行': query.commands.filter(item => !item.listed).map(item => item.hiddenReason).join('｜'),
      };
    },
    expect: {
      '总条数': 14, '列出来的': 12,
      '摘要': '12 条全部可用 · 2 条因累计数据没开隐藏',
      '灰行': '累计数据没开，菜单里不显示｜累计数据没开，菜单里不显示',
    },
  },
  {
    name: '命令被群管理员关掉',
    run: () => {
      const s = session({disabled: ['直播报告', '数据排行榜']});
      const sum = commandSummary(COMMANDS, s, true);
      const groups = commandGroups(COMMANDS, s, true);
      return {
        '摘要': sum.text,
        '点名': sum.offNames.join('、'),
        '一键恢复': sum.restorable.join('、'),
        '数据查询组': groups.find(item => item.category === '数据查询').on ? '开' : '半开',
        '命令管理组': groups.find(item => item.category === '命令管理').on ? '开' : '半开',
      };
    },
    expect: {
      '摘要': '12 条可用 · 2 条被群管理员关了（直播报告、数据排行榜）',
      '点名': '直播报告、数据排行榜',
      '一键恢复': '直播报告、数据排行榜',
      '数据查询组': '半开',
      '命令管理组': '开',
    },
  },
  {
    name: '状态文件里的残留',
    run: () => {
      // 「我的数据」那一族命令已停用，记录仍留在状态文件里
      const s = session({disabled: ['直播报告', '我的数据']});
      const sum = commandSummary(COMMANDS, s, true);
      return {
        '点名': sum.offNames.join('、'),
        '残留': sum.strayNames.join('、'),
        '一键恢复': sum.restorable.join('、'),
      };
    },
    // 一键恢复只管真有这条命令的那几条：混进残留的话，按下去顺手清掉了什么就说不清了
    expect: {'点名': '直播报告', '残留': '我的数据', '一键恢复': '直播报告'},
  },
  {
    name: '本群通知配成 @全体成员',
    run: () => {
      const s = session({menuHidden: AT_ALL_HIDDEN, menuNotes: {'动态@我': '本群动态通知会先 @全体成员'}});
      const sum = commandSummary(COMMANDS, s, true);
      const groups = commandGroups(COMMANDS, s, true);
      const subs = groups.find(item => item.category === '提醒订阅');
      return {
        '摘要': sum.text,
        '本会话不列': sum.hiddenBySession.join('、'),
        '机器用不上': sum.hiddenByMachine.length,
        '理由': subs.commands.find(item => item.name === '开播@我').hiddenReason,
        '说明': subs.commands.find(item => item.name === '动态@我').note,
      };
    },
    expect: {
      '摘要': '11 条全部可用 · 3 条在这个会话里不列',
      '本会话不列': '开播@我、取消开播@我、开播@名单',
      '机器用不上': 0,
      '理由': '在这个会话里不起作用，菜单里不显示',
      '说明': '本群动态通知会先 @全体成员',
    },
  },
  {
    name: '菜单口径取不到',
    run: () => {
      // 推送配置里已经没有这个会话，构不出上下文，后端答不了「菜单里列哪几条」。
      // 那一栏是缺的，而缺的那一栏与一个空表在 `|| []` 底下长得一样
      const s = session({configured: false, type: null, menuHidden: null});
      const sum = commandSummary(COMMANDS, s, true);
      return {'答得上': menuKnown(s) ? '是' : '否', '摘要': sum.text};
    },
    expect: {'答得上': '否', '摘要': '14 条全部可用 · 这个会话不在推送配置里，菜单口径取不到'},
  },
  {
    name: '模板改过一处',
    run: () => {
      const u = user({targets: [{
        platform: SENDER, type: 1, num: 12345, enabled: true,
        messages: messages({LiveOn: {params: {message: '开播啦 {uname}'}}}),
      }]});
      const state = templateState(u.targets[0], HANDLERS);
      const rows = channelIndex([u], [session()], HANDLERS, DIRECTORY);
      return {
        '标': state.custom ? '自定义' : '默认',
        '哪几类': state.changed.join('、'),
        '一览标': rows[0].custom ? '自定义' : '默认',
      };
    },
    expect: {'标': '自定义', '哪几类': 'LiveOn', '一览标': '自定义'},
  },
  {
    name: '@ 谁改过（默认参数里本来没有这个键）',
    run: () => {
      // at_mode 刻意不写进默认参数：「有值」就等于「在界面上选过」。
      // 因此它一出现就该算成与默认不同——否则「改了却仍写着默认」
      const u = user({targets: [{
        platform: SENDER, type: 1, num: 12345, enabled: true,
        messages: messages({LiveOn: {params: {at_mode: 'all'}}}),
      }]});
      return {'标': templateState(u.targets[0], HANDLERS).custom ? '自定义' : '默认'};
    },
    expect: {'标': '自定义'},
  },
  {
    name: '报告版式改过',
    run: () => {
      const u = user({targets: [{
        platform: SENDER, type: 1, num: 12345, enabled: true,
        messages: messages({LiveReport: {params: {showRevenue: false}}}),
      }]});
      const state = layoutState(u.targets[0], HANDLERS);
      return {'出这一段': state.present ? '出' : '不出', '标': state.custom ? '自定义' : '默认', '哪一类': state.className};
    },
    expect: {'出这一段': '出', '标': '自定义', '哪一类': 'LiveReport'},
  },
  {
    name: '关掉下播报告',
    run: () => {
      const u = user({targets: [{
        platform: SENDER, type: 1, num: 12345, enabled: true,
        messages: messages().filter(item => item.handler !== 'LiveReport'),
      }]});
      const tree = pushTree([u], [session()], HANDLERS, DIRECTORY);
      const switches = noticeSwitches(u, u.targets[0], HANDLERS);
      return {
        '出这一段': layoutState(u.targets[0], HANDLERS).present ? '出' : '不出',
        '关着几类': tree[0].channels[0].noticesOff,
        '开关行数': switches.length,
        '带版式的': switches.filter(item => item.hasLayout).map(item => item.displayName).join('、'),
      };
    },
    expect: {'出这一段': '不出', '关着几类': 1, '开关行数': 4, '带版式的': '下播报告'},
  },
  {
    name: '群名取不到',
    run: () => {
      // OneBot 掉线时名单就是空的。编一个名字出来更糟：屏幕上会有一个查无此群的名字
      const u = user();
      const s = session();
      return {
        '通道名': channelName(s, u.targets[0], {}),
        '私聊名': channelName({platform: SENDER, num: 87654321, type: '好友'},
          {platform: SENDER, type: 0, num: 87654321}, {}),
      };
    },
    expect: {'通道名': '群 12345', '私聊名': '好友 87654321'},
  },
  {
    name: '最近推送按通道拆',
    run: () => {
      const s = session();
      const records = [
        record('群 12345', '开播通知'),
        record('群 123', '开播通知'),
        record('群 12345', '下播报告', false),
        record('好友 12345', '开播通知'),
        record('群 999', '开播通知'),
      ];
      const rows = recentPushes(records, s, 5);
      return {
        '条数': rows.length,
        '内容': rows.map(item => item.summary).join('、'),
        // 「群 123」不许被当成「群 12345」的一部分：整串相等，不是找子串
        '误收': rows.some(item => item.target !== '群 12345') ? '有' : '无',
      };
    },
    expect: {'条数': 2, '内容': '开播通知、下播报告', '误收': '无'},
  },
  {
    name: '@全体成员 状态行',
    run: () => {
      const u = user();
      const s = session();
      const admin = atAllStatus(QUOTA, s, u.targets[0], DIRECTORY);
      const plain = atAllStatus(QUOTA, s, u.targets[0], DIRECTORY_NOT_ADMIN);
      const friend = atAllStatus(QUOTA, {platform: SENDER, num: 87654321, type: '好友'},
        {platform: SENDER, type: 0, num: 87654321}, DIRECTORY);
      return {
        '管理员': admin.text,
        '非管理员告警': plain.warning ? '有' : '无',
        '私聊': friend.applicable ? '照样报额度' : friend.text,
      };
    },
    expect: {
      '管理员': '今日本群已用 3/20 · 账号 1/10 · 机器人角色：管理员',
      '非管理员告警': '有',
      '私聊': '私聊没有 @全体成员。',
    },
  },
  {
    name: '额度不限时不画分母',
    run: () => {
      // limited 为假＝没有上限。画一个 0 的分母，看的人会以为一次都不许用
      const quota = {
        date: '2026-09-04',
        bots: [{platform: SENDER, used: 4, limit: 0, limited: false}],
        sessions: [{platform: SENDER, num: 12345, used: 7, limit: 0, limited: false}],
      };
      return {'行': atAllStatus(quota, session(), user().targets[0], DIRECTORY).text};
    },
    expect: {'行': '今日本群已用 7（不限） · 账号 4（不限） · 机器人角色：管理员'},
  },
  {
    name: '只采集不推送',
    run: () => {
      const u = user({targets: []});
      const tree = pushTree([u], [], HANDLERS, DIRECTORY);
      return {
        '只采集': tree[0].collectOnly ? '是' : '否',
        '通道数': tree[0].channels.length,
        '一览行数': channelIndex([u], [], HANDLERS, DIRECTORY).length,
      };
    },
    expect: {'只采集': '是', '通道数': 0, '一览行数': 0},
  },
  {
    name: '配置里没有、状态里还留着',
    run: () => {
      const s2 = session({num: 777, configured: false, type: null, menuHidden: null,
        disabled: ['直播报告'], streamers: []});
      const rows = strandedSessions([user()], [session(), s2]);
      return {
        '条数': rows.length,
        '是哪个': rows.length ? rows[0].num : '—',
        '关着': rows.length ? rows[0].disabled.join('、') : '—',
      };
    },
    expect: {'条数': 1, '是哪个': 777, '关着': '直播报告'},
  },
  {
    name: '金额可见与提醒订阅摘要',
    run: () => {
      const s = session();
      const subs = [
        {platform: SENDER, num: 12345, streamerUid: 3493, streamerName: '柚子',
          type: 'live', typeName: '开播', users: [1, 2, 3]},
        {platform: SENDER, num: 12345, streamerUid: 3493, streamerName: '柚子',
          type: 'dynamic', typeName: '动态', users: [1]},
        {platform: SENDER, num: 999, streamerUid: 3493, streamerName: '柚子',
          type: 'live', typeName: '开播', users: [9, 8]},
      ];
      return {
        '金额默认': revenueSummary(s).text,
        '金额配过': revenueSummary(session({revenueVisible: true, revenueExplicit: true})).text,
        '订阅': subscriptionSummary(subs, s).text,
        '别的群不算进来': subscriptionSummary(subs, s).rows.length,
      };
    },
    expect: {
      '金额默认': '隐藏金额（默认：群聊隐藏、私聊显示）',
      '金额配过': '显示金额',
      '订阅': '开播 3 人 · 动态 1 人',
      '别的群不算进来': 2,
    },
  },
  {
    name: '按平台与号认会话',
    run: () => {
      const a = session();
      const b = session({platform: '别的机器人', num: 12345});
      return {
        '取到的': sessionOf([a, b], '别的机器人', 12345) === b ? '第二个' : '第一个',
        '没有的': sessionOf([a, b], SENDER, 404) === null ? 'null' : '瞎给一个',
      };
    },
    expect: {'取到的': '第二个', '没有的': 'null'},
  },
  {
    name: '预览请求体带通道',
    run: () => {
      const group = previewRequestBody({cover: false}, {platform: 'qq-onebot', type: 1, num: 12345});
      const none = previewRequestBody({cover: true}, null);
      const friend = previewRequestBody({}, {platform: 'qq-onebot', type: 0, num: 99});
      return {
        '带平台': group.platform,
        '带类型': group.type,
        '带号': group.num,
        '版式还在': group.cover,
        '没通道不带平台': none.platform === undefined ? '无' : none.platform,
        '没通道仍带版式': none.cover,
        '好友类型零也带': friend.type,
        '群隐藏': previewRevenueCaption({revenueVisible: false}, {type: 1}),
        '群显示': previewRevenueCaption({revenueVisible: true}, {type: 1}),
        '私聊无会话': previewRevenueCaption(null, {type: 0}),
      };
    },
    expect: {
      '带平台': 'qq-onebot',
      '带类型': 1,
      '带号': 12345,
      '版式还在': false,
      '没通道不带平台': '无',
      '没通道仍带版式': true,
      '好友类型零也带': 0,
      '群隐藏': '按本群金额可见画：隐藏',
      '群显示': '按本群金额可见画：显示',
      '私聊无会话': '按本群金额可见画：显示',
    },
  },
];

// ── 跑 ───────────────────────────────────────────────────────────────────
const bad = [];
const rows = [];

for (const item of CASES) {
  // 抛异常的那一档记成红，别的档接着跑：让它把整轮打断的话，屏幕上只剩一段堆栈，
  // 剩下十几档是「查过了」还是「没轮到」分不出来——而故意破坏这一页时最先出现的正是异常
  let got;
  try {
    got = item.run();
  } catch (error) {
    bad.push(item.name + '：抛了 ' + error);
    rows.push([item.name, Object.keys(item.expect).length + ' 项', '红（抛异常）'].join('\t'));
    continue;
  }

  const diff = [];
  for (const key of Object.keys(item.expect)) {
    if (String(got[key]) !== String(item.expect[key])) {
      diff.push(key + ' 应「' + item.expect[key] + '」实得「' + got[key] + '」');
    }
  }
  // 多算出来的栏也要报：夹具漏写一条「应」与「查过了」在这张表上长得一样
  for (const key of Object.keys(got)) {
    if (!(key in item.expect)) diff.push(key + ' 没有写「应」');
  }
  if (diff.length) bad.push(item.name + '：' + diff.join('；'));
  rows.push([item.name, Object.keys(item.expect).length + ' 项', diff.length ? '红' : '绿'].join('\t'));
}

console.log(['档', '量了几项', '判定'].join('\t'));
rows.forEach(row => console.log(row));

if (bad.length) {
  console.error('\n对不上 ' + bad.length + ' 处：');
  bad.forEach(item => console.error('  ' + item));
  process.exit(1);
}

console.log('\n' + CASES.length + ' 档全对');
