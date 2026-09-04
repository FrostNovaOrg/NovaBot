/**
 * 主播页那几组纯函数的夹具
 *
 * 量的是 config-ui/streamers-model.js 里的这几件事：地址栏解析与回拼、状态四档的措辞与
 * 闭集覆盖、七天折线的取值与几何、人气峰三态、两种缺口分开列、累计数据那条小横条的条件、
 * 快照裸键换人话、分页与报告图三态。它们全不碰 DOM，因此可以在 node 上直接喂值跑。
 *
 * 其中三组是本页判据的重头：
 *   · 人气峰「不知道」与「知道它是 0」——只分两态的话，几个月前的场次会整批显示成「人气峰 0」，
 *     而那是一句假话，且它与真的 0 在屏幕上长得一模一样；
 *   · 两种缺口分开列——服务端特意分两个字段给出来，界面上一相加就是重复计数；
 *   · 累计数据那条横条的条件——写成取反的话，接口还没回来的那一瞬间会先闪一条假话。
 *
 * 由 StreamersModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本。
 */

import {
  PERIODS, TABS, barGeometry, detailHash, fmtDuration, fmtGap, fmtMetric, fmtTime,
  gapCells, pageBar, parseStreamersHash, peakCell, reportPath, reportView, rowSubtitle,
  seriesValues, sessionHash, sessionTitle, shownMetrics, snapshotRows, sparkline,
  statusChip, summaryTotals, totalDataBanner, uncoveredStatuses,
} from '../../../main/resources/config-ui/streamers-model.js';
import {totalDataOff} from '../../../main/resources/config-ui/home-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// ---------- 一、地址栏 → 子视图 ----------
const LIST = {view: 'list', platform: '', uid: '', pane: 'overview', start: '', page: 1,
  period: 'week'};

eq(parseStreamersHash('#/streamers'), LIST, '光秃秃的地址就是列表');
eq(parseStreamersHash(''), LIST, '地址栏是空的也当列表');
eq(parseStreamersHash('#/streamers/bilibili/3493'),
  {view: 'detail', platform: 'bilibili', uid: '3493', pane: 'overview', start: '', page: 1,
    period: 'week'},
  '平台加 uid 是详情，默认落概况');
eq(parseStreamersHash('#/streamers/bilibili/3493/sessions').pane, 'sessions', '第三段是页签名时按页签');
eq(parseStreamersHash('#/streamers/bilibili/3493/trend').pane, 'trend', '趋势同理');
eq(parseStreamersHash('#/streamers/bilibili/3493/overview').pane, 'overview', '写全概况也认');
// 认不出的第三段退回概况，不留白屏：这一页从三个地方点进来，哪一处拼错都该得到一张能用的页
eq(parseStreamersHash('#/streamers/bilibili/3493/zzz').pane, 'overview', '认不出的页签退回概况');
eq(parseStreamersHash('#/streamers/bilibili/3493/zzz').view, 'detail', '认不出的页签仍是详情');

// 第三段是一串数字＝某一场。页签是词、开播时刻是毫秒时间戳，靠形状分辨
eq(parseStreamersHash('#/streamers/bilibili/3493/1757000000000'),
  {view: 'session', platform: 'bilibili', uid: '3493', pane: 'overview',
    start: '1757000000000', page: 1, period: 'week'},
  '第三段是一串数字时是某一场');

// 🔴 uid 不是数字一律退回列表：不校验的话，undefined 会被当成一位真主播拿去问接口，
//    屏幕上于是出现一张空的详情页——它与「这位主播还没播过」长得一样
eq(parseStreamersHash('#/streamers/bilibili/undefined'), LIST, 'uid 不是数字时退回列表');
eq(parseStreamersHash('#/streamers/bilibili'), LIST, '只有平台没有 uid 时退回列表');
eq(parseStreamersHash('#/streamers//3493'), LIST, '平台是空段时退回列表');

eq(parseStreamersHash('#/streamers/bilibili/3493/sessions?page=3').page, 3, '页码走查询串');
eq(parseStreamersHash('#/streamers/bilibili/3493/sessions?page=0').page, 1, '页码 0 不认，退回第 1 页');
eq(parseStreamersHash('#/streamers/bilibili/3493/sessions?page=-2').page, 1, '负页码不认');
eq(parseStreamersHash('#/streamers/bilibili/3493/sessions?page=zzz').page, 1, '页码不是数字时不认');
eq(parseStreamersHash('#/streamers/bilibili/3493/trend?period=month').period, 'month', '周期走查询串');
eq(parseStreamersHash('#/streamers/bilibili/3493/trend?period=day').period, 'week',
  '认不出的周期退回按周——拿它去问服务端的话，那一头也会退回按周，而地址栏说的是按日');
eq(parseStreamersHash('#/streamers/bilibili/3493?zzz=1').pane, 'overview', '不认得的查询参数一律忽略');
eq(parseStreamersHash('#/streamers/%E5%93%94%E5%93%A9/3493').platform, '哔哩',
  '平台名要解码');
// 坏转义不至于让整页打不开：解不开就用原文，屏幕上是一位查不到的主播，而不是白屏
eq(parseStreamersHash('#/streamers/%E5%93/3493').platform, '%E5%93', '坏转义时用原文');

// ---------- 二、地址回拼 ----------
eq(detailHash('bilibili', 3493), '#/streamers/bilibili/3493', '概况的地址最短');
eq(detailHash('bilibili', 3493, 'overview'), '#/streamers/bilibili/3493',
  '写全概况也拼成最短的那一个——两种写法拼出两个地址的话，收藏夹里会有两条指向同一张页');
eq(detailHash('bilibili', 3493, 'sessions'), '#/streamers/bilibili/3493/sessions', '页签写进路径');
eq(detailHash('bilibili', 3493, 'sessions', 'page=2'), '#/streamers/bilibili/3493/sessions?page=2',
  '页码写进查询串');
eq(sessionHash('bilibili', 3493, 1757000000000), '#/streamers/bilibili/3493/1757000000000',
  '某一场的地址是第三段');
eq(reportPath('bilibili', 3493, 1757000000000),
  '/streamers/bilibili/3493/sessions/1757000000000/report', '报告图的接口路径');
// 拼与解要能对上：只做前一半的话，贴出去的地址打开是另一张页
const ROUND = parseStreamersHash(detailHash('a/b', 3493, 'trend'));
eq([ROUND.platform, ROUND.uid, ROUND.pane], ['a/b', '3493', 'trend'],
  '平台名里带斜杠时，拼出去与读回来仍是同一位');

// ---------- 三、状态四档 ----------
eq(statusChip({status: 'LIVE', statusText: '在播'}), {text: '在播', cls: 'pill live', dot: true},
  '在播带圆点');
eq(statusChip({status: 'OFFLINE', statusText: '未开播'}), {text: '未开播', cls: 'pill', dot: false},
  '未开播不带圆点');
// 定稿要的是带顿号的那一句，服务端给的是不带的。措辞以界面这一份为准
eq(statusChip({status: 'COLLECT_ONLY', statusText: '只采集不推送'}).text, '只采集，不推送',
  '只采集那一档按定稿的措辞');
eq(statusChip({status: 'DISABLED', statusText: '已停用'}).text, '已停用', '停用那一档');
// 🔴 认不出的档退回接口给的那句话：漏一档的表现是那一行的标不见了，
//    而不见了与「这位主播没有状态」在屏幕上长得一样
eq(statusChip({status: 'PAUSED', statusText: '已暂停'}).text, '已暂停', '认不出的档退回接口的措辞');
eq(statusChip({status: 'PAUSED'}).text, 'PAUSED', '接口连措辞都没给时退回状态名');
eq(statusChip({}).text, '', '什么都没有时留空，不编一个');
eq(statusChip({status: 'DISABLED', living: true}).dot, false,
  '停用的不画在播圆点——停用之后不再采集，那个 living 是上次留下的陈值');

eq(uncoveredStatuses([{name: 'LIVE'}, {name: 'OFFLINE'}, {name: 'COLLECT_ONLY'}, {name: 'DISABLED'}]),
  [], '接口现在这四档，措辞表都覆盖得到');
eq(uncoveredStatuses([{name: 'LIVE'}, {name: 'PAUSED'}]), ['PAUSED'],
  '服务端加一档而这边没加时点得出名字来');
eq(uncoveredStatuses(null), [], '接口没给闭集时不报假的缺项');

// ---------- 四、七天折线 ----------
const SERIES = [
  {date: '2026-08-29', sessions: 0, durationSeconds: 0},
  {date: '2026-08-30', sessions: 2, durationSeconds: 7200},
  {date: '2026-08-31', sessions: 0, durationSeconds: 0},
  {date: '2026-09-01', sessions: 1, durationSeconds: 3600},
  {date: '2026-09-02', sessions: 0, durationSeconds: 0},
  {date: '2026-09-03', sessions: 0, durationSeconds: 0},
  {date: '2026-09-04', sessions: 3, durationSeconds: 10800},
];
eq(seriesValues(SERIES, 'sessions'), [0, 2, 0, 1, 0, 0, 3], '折线恰七点，空日是 0 不是跳过');
eq(seriesValues(SERIES, 'durationSeconds'), [0, 7200, 0, 3600, 0, 0, 10800], '时长那一路同形');
eq(seriesValues([{date: '2026-09-04'}], 'sessions'), [0],
  '旧版服务端没给这一栏时当 0，不当 undefined——画出来会是一条断掉的线');
eq(seriesValues(null, 'sessions'), [], '整栏都没有时是空的');

const SPARK = sparkline([0, 2, 0, 1, 0, 0, 3]);
eq([SPARK.width, SPARK.height], [88, 26], '折线的画布');
eq(SPARK.d.startsWith('M0.0 24.0'), true, '首点是 0，贴着底');
eq(SPARK.cx, 88, '末端那个点在最右');
eq(SPARK.cy, 4, '末点是最大值 3，贴着顶');
eq(SPARK.rising, true, '末点高于首点算在涨');
// 🔴 全零时不缩放：0/0 出 NaN，整条线会消失。七天一场没播该是一条平的线，不是一片空白
const FLAT = sparkline([0, 0, 0, 0, 0, 0, 0]);
eq(FLAT.d.indexOf('NaN') < 0, true, '全零不出 NaN');
eq(FLAT.d, 'M0.0 24.0 L14.7 24.0 L29.3 24.0 L44.0 24.0 L58.7 24.0 L73.3 24.0 L88.0 24.0',
  '全零画成贴着底的一条平线');
eq(FLAT.rising, false, '七天都是 0 的线不算在涨');
eq(sparkline([1]), null, '只有一个点时画不出线，给 null 让调用方不画');
eq(sparkline([]), null, '一个点都没有时同理');

// ---------- 五、汇总卡 ----------
const LISTED = [
  {status: 'LIVE', living: true, summary: {sessions: 3, durationSeconds: 10800}},
  {status: 'COLLECT_ONLY', living: false, summary: {sessions: 2, durationSeconds: 7200}},
  // 停用的不采集，把他停用之前的场次算进来会让「最近七天播了几场」比实际发生的多；
  // 但他还在配置里，因此仍计入「共几位」
  {status: 'DISABLED', living: false, summary: {sessions: 9, durationSeconds: 99999}},
];
eq(summaryTotals(LISTED, 7),
  {days: 7, total: 3, living: 1, sessions: 5, durationSeconds: 18000},
  '汇总只算没停用的那几位，位数算全部');
eq(summaryTotals([], 7), {days: 7, total: 0, living: 0, sessions: 0, durationSeconds: 0},
  '一位主播都没有时四个数都是 0');

// ---------- 六、人气峰三态 ----------
eq(peakCell({hasPeaks: true, peaks: {danmu: {at: 1, value: 15}}}, 'danmu'), {known: true, value: 15},
  '有峰值就显示它');
// 🔴 早于「峰值入归档」的场次是「不知道」，不是 0：写 0 是句假话，而它与真的 0 长得一模一样
eq(peakCell({hasPeaks: false, peaks: {}}, 'danmu'), {known: false, value: 0}, '没有峰值这件事时是不知道');
eq(peakCell({hasPeaks: true, peaks: {}}, 'danmu'), {known: true, value: 0},
  '有峰值这件事成立而这一项没有＝那条曲线一个点都没有，是真的 0');
eq(peakCell({hasPeaks: true, peaks: {gift: {at: 1, value: 8}}}, 'danmu'), {known: true, value: 0},
  '别的指标有峰不算这一项有');
eq(peakCell(null, 'danmu'), {known: true, value: 0}, '整条记录都没有时不至于抛');

// ---------- 七、两种缺口分开列 ----------
// 🔴 程序停机期间这个房间当然也是断的，两段必然重叠，加起来就是重复计数
const GAPS = gapCells({maintenanceGapSeconds: 1330, roomOutageSeconds: 44});
eq([GAPS.maintenance, GAPS.outage], [1330, 44], '两种缺口各是各的，不相加');
eq(GAPS.any, true, '有缺口就该标出来');
eq(GAPS.title, '其中 22 分 10 秒因程序停机未采集；其中 44 秒因这个直播间断线未采集。本行各项计数只是下界',
  '两句分开说，说得出各是多少');
eq(gapCells({maintenanceGapSeconds: 0, roomOutageSeconds: 0}), {maintenance: 0, outage: 0,
  any: false, title: ''}, '没有缺口时不标');
eq(gapCells({maintenanceGapSeconds: 0, roomOutageSeconds: 44}).any, true, '只有断线缺口也要标');

// ---------- 八、累计数据那条横条 ----------
// 🔴 三态里只有明确的 false 才算没开。写成取反的话，接口还没回来那一瞬间会先闪一条假话
eq(totalDataBanner({totalDataAvailable: false}), '这台机器没开累计数据：群里只能查本场。', '没开时说这一句');
eq(totalDataBanner({totalDataAvailable: true}), '', '开着时不说');
eq(totalDataBanner({}), '', '旧版服务端没给这一栏时不说——不知道不许读成没开');
eq(totalDataBanner(null), '', '整份状态都没有时不说');
// 与首页那条软待办同一份判定：各判各的那天，首页说没开而这一页说开着
eq(totalDataBanner({totalDataAvailable: false}) !== '', totalDataOff({totalDataAvailable: false}),
  '这条横条与首页软待办读的是同一个判定');
eq(totalDataBanner({}) !== '', totalDataOff({}), '不知道那一档两处也一致');

// ---------- 九、快照裸键换人话 ----------
eq(snapshotRows({fans: 12345, fans_medal: 678, guard: 9}), [
  {key: 'fans', name: '粉丝', unit: '人', value: 12345, known: true},
  {key: 'fans_medal', name: '粉丝团', unit: '人', value: 678, known: true},
  {key: 'guard', name: '大航海', unit: '人', value: 9, known: true},
], '认得的键换成人话');
// 🔴 认不出的键原样显示，不藏起来：藏起来之后，插件新采了一项的那一天屏幕上不会有任何变化
eq(snapshotRows({zzz_new_metric: 7}), [
  {key: 'zzz_new_metric', name: 'zzz_new_metric', unit: '', value: 7, known: false},
], '认不出的键原样显示，并标出它是认不出的那一类');
eq(snapshotRows({}), [], '一项都没采到时是空的');
eq(snapshotRows(null), [], '整段快照都没有时是空的');

// ---------- 十、场次表的列与分页 ----------
const METRICS = [{key: 'danmu', name: '弹幕'}, {key: 'gift', name: '礼物', money: true},
  {key: 'box', name: '盲盒'}];
const ITEMS = [{metrics: {danmu: 120, gift: 0, box: 0}}, {metrics: {danmu: 0, gift: 12.5, box: 0}}];
eq(shownMetrics(METRICS, ITEMS).shown.map(one => one.key), ['danmu', 'gift'],
  '只留至少有一场非零的列');
eq(shownMetrics(METRICS, ITEMS).hidden, 1, '藏了几列要说得出来');
eq(shownMetrics(METRICS, []).shown.length, 0, '一场都没有时一列也不留');

eq(pageBar({total: 45, page: 2, size: 20, pages: 3}),
  {page: 2, pages: 3, prev: 1, next: 3, text: '第 2 / 3 页 · 共 45 场'}, '中间那一页两头都能翻');
eq(pageBar({total: 45, page: 1, size: 20, pages: 3}).prev, 0, '第一页没有上一页');
eq(pageBar({total: 45, page: 3, size: 20, pages: 3}).next, 0, '最后一页没有下一页');
eq(pageBar({total: 5, page: 1, size: 20, pages: 1}).text, '共 5 场', '只有一页时不写页码');
eq(pageBar({total: 0, page: 1, size: 20, pages: 0}).text, '共 0 场', '一场都没有时也说得出来');
// 页码照服务端回的那个数画，不照地址栏：那一头会把越界的页码夹回来
eq(pageBar({total: 5, page: 1, size: 20, pages: 1}).page, 1, '页码以服务端回的为准');

// ---------- 十一、报告图三态 ----------
eq(reportView('ok'), {image: true, text: ''}, '拿到了就显示图');
eq(reportView('loading'),
  {image: false, text: '正在取这一场的报告图。缓存过期的老场次要现画，请稍候。'},
  '正在取要说一句——不说的话，那几秒的空白与「这一场没有报告」长得一样');
eq(reportView('missing', '这一场没有报告图，也没有留下明细数据，重新绘制不出来：可能没开下播报告'),
  {image: false, text: '这一场没有报告图，也没有留下明细数据，重新绘制不出来：可能没开下播报告'},
  '没有图时照服务端那句人话说');
eq(reportView('missing').text.length > 0, true, '服务端没给话时也得有一句，不留白');

// ---------- 十二、几句人话与柱图几何 ----------
eq(fmtDuration(0), '0 分', '零时长');
eq(fmtDuration(3600), '1 时 0 分', '整时');
eq(fmtDuration(7440), '2 时 4 分', '时加分');
eq(fmtDuration(1800), '30 分', '不足一时只写分');
// 缺口不能按分钟四舍五入：一次 44 秒的重启会显示成「1 分」，而 20 秒的会显示成「0 分」
eq(fmtGap(44), '44 秒', '缺口留住秒');
eq(fmtGap(1330), '22 分 10 秒', '缺口的分与秒');
eq(fmtGap(0), '0 秒', '零缺口也说得出来');
eq(fmtMetric(1234, {}), '1,234', '整数带千分位');
eq(fmtMetric(12.345, {money: true}), '12.35', '钱按两位小数');
eq(fmtMetric(null, {}), '—', '没有这一项时是破折号，不是 0');
eq(fmtTime(0), '—', '没有时刻时是破折号');

const BARS = barGeometry([0, 4, 2], 1000, 100);
eq(BARS.length, 3, '三根柱子');
// 零值也留 2px 的痕迹，否则「这一周没播」和「这一周没有这项数据」在图上长得一样
eq([BARS[0].h, BARS[0].zero], [2, true], '零值留 2px 的痕迹并标出它是零');
eq([BARS[1].h, BARS[1].y], [100, 0], '最大值那根顶满');
eq(BARS[2].h, 50, '一半的值画一半高');
eq(barGeometry([0, 0], 1000, 100).map(one => one.h), [2, 2],
  '全零时不缩放——0/0 出 NaN，整张图会消失');
eq(barGeometry([], 1000, 100), [], '一根柱子都没有时是空的');

// ---------- 十三、几处摆到屏幕上的话 ----------
eq(sessionTitle({titles: [{title: '甲'}, {title: '乙'}], titleChangeCount: 1}),
  {title: '乙', changed: '（改过 1 次）'}, '标题取最后改成的那一个');
eq(sessionTitle({titles: [], titleChangeCount: 0}), {title: '', changed: ''}, '没有标题记录时是空的');
eq(rowSubtitle({summary: {days: 7, sessions: 3, durationSeconds: 10800, lastStart: 0}}, 7),
  '最近 7 天 3 场 · 3 时 0 分 · 还没有归档的场次',
  '一场都没归档过时说得出来，不写一个假的时刻');
eq(rowSubtitle({summary: {days: 7, sessions: 0, durationSeconds: 0, lastStart: 1}}, 7)
  .indexOf('最近一场') > 0, true,
  '一个月没播的主播也写得出上次是什么时候——最近一场不受七天窗口限制');

// ---------- 十四、闭集本身 ----------
eq(TABS.map(one => one[0]), ['overview', 'sessions', 'trend'], '三个页签');
eq(PERIODS.map(one => one[0]), ['week', 'month'], '两种周期，与服务端认的值一致');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
