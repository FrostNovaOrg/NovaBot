/**
 * 日志页那几组纯函数的夹具
 *
 * 量的是 config-ui/log-model.js 里的四件事：地址栏与筛选状态的往返、查询串的换名、
 * 日期导航、工程日志的分行与筛。它们全不碰 DOM，因此可以在 node 上直接喂值跑。
 *
 * 往返那一组是本页判据的重头：筛完的画面要能收藏、能贴给别人，而「写得进地址栏」
 * 与「从地址栏还原得回来」是两件事——只做前一半的话，贴过去的地址打开是一张没筛过的页，
 * 而它看起来与筛过的完全一样，没有任何东西会提示对方看到的不是同一份。
 *
 * 由 LogModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本。
 */

import {
  DEFAULT_LIMIT, emptyState, parseLogHash, logHash, timelineQuery, hasFilter, emptyText,
  olderDay, newerDay, engLevelOf, groupEngLines, engVisible, engSegments,
  engQuery, engAtBottom, engFollowing, engCopyText, engEmptyText,
} from '../../../main/resources/config-ui/log-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

const TODAY = '2026-09-04';

/** 造一份筛选状态 */
function state(over) {
  return Object.assign(emptyState(TODAY), over);
}

// ---------- 一、地址栏 → 状态 ----------
eq(parseLogHash('#/log', TODAY), state({}), '光秃秃的地址就是今天、什么都不筛');
eq(parseLogHash('', TODAY), state({}), '地址栏是空的也当今天');
eq(parseLogHash('#/log/2026-09-01', TODAY).date, '2026-09-01', '路径第二段是日期时按那一天');
eq(parseLogHash('#/log/2026-09-01', TODAY).view, 'timeline', '带日期的仍是时间线');
eq(parseLogHash('#/log/eng', TODAY).view, 'eng', '第二段是 eng 时进工程日志');
eq(parseLogHash('#/log/eng', TODAY).date, TODAY, '工程日志没带日期时按今天');
eq(parseLogHash('#/log/eng?d=2026-09-01', TODAY).date, '2026-09-01',
  '工程日志的日期走查询串，因为路径那一段已经被 eng 占了');
// 认不出的第二段不当日期也不当子页：白屏与「看起来没筛」都比退回今天更难查
eq(parseLogHash('#/log/2026年9月1日', TODAY).date, TODAY, '认不出的日期退回今天');
eq(parseLogHash('#/log?only=problem', TODAY).only, true, '只看问题认 only=problem');
eq(parseLogHash('#/log?only=1', TODAY).only, false, '只认 problem 这一个值，别的值不算开');
eq(parseLogHash('#/log?type=PUSH_FAILED', TODAY).type, 'PUSH_FAILED', '类型原样取');
eq(parseLogHash('#/log?cat=PUSH', TODAY).cat, 'PUSH', '大类原样取');
eq(parseLogHash('#/log/eng?at=20%3A07', TODAY).at, '20:07', '要看哪一分钟走查询串');
eq(parseLogHash('#/log/eng?at=20:07', TODAY).at, '20:07', '冒号没编码时也认');
// 认不出的时刻当没给：定位到一个瞎猜的位置，比压根不定位更难查——屏幕上那条「已定位到」
// 会照常显示，而高亮的是另一刻的行
eq(parseLogHash('#/log/eng?at=%E6%99%9A%E4%B8%8A%E5%85%AB%E7%82%B9', TODAY).at, '',
  '认不出的时刻当没给');
eq(parseLogHash('#/log/eng?at=25:99', TODAY).at, '', '形状对但不存在的时刻也不认');
eq(parseLogHash('#/log?streamer=%E7%94%B2%E4%B8%BB%E6%92%AD', TODAY).streamer, '甲主播', '主播要解码');
eq(parseLogHash('#/log?chan=%E7%BE%A4%20111', TODAY).channel, '群 111', '通道要解码');
eq(parseLogHash('#/log?q=%E5%BC%80%E6%92%AD', TODAY).q, '开播', '关键词要解码');
eq(parseLogHash('#/log?q=a%26b', TODAY).q, 'a&b', '关键词里的 & 不该把查询串切成两段');
eq(parseLogHash('#/log?zzz=1', TODAY), state({}), '不认得的查询参数一律忽略');

// ---------- 二、状态 → 地址栏 ----------
eq(logHash(state({}), TODAY), '#/log', '今天且不筛时地址最短');
eq(logHash(state({date: '2026-09-01'}), TODAY), '#/log/2026-09-01', '别的日子写进路径');
eq(logHash(state({only: true}), TODAY), '#/log?only=problem', '只看问题写成 only=problem');
eq(logHash(state({streamer: '甲主播'}), TODAY), '#/log?streamer=%E7%94%B2%E4%B8%BB%E6%92%AD',
  '主播要编码');
eq(logHash(state({cat: 'PUSH'}), TODAY), '#/log?cat=PUSH', '大类写成 cat=');
eq(logHash(state({view: 'eng'}), TODAY), '#/log/eng', '工程日志是子路径');
eq(logHash(state({view: 'eng', date: '2026-09-01'}), TODAY), '#/log/eng?d=2026-09-01',
  '工程日志把日期挪进查询串');
eq(logHash(state({view: 'eng', at: '20:07'}), TODAY), '#/log/eng?at=20%3A07',
  '要看哪一分钟也写进地址栏：日志页那一跳贴给别人，对方落在同一刻');
eq(logHash(state({date: '2026-09-01', only: true, type: 'PUSH_SENT', q: '开播'}), TODAY),
  '#/log/2026-09-01?only=problem&type=PUSH_SENT&q=%E5%BC%80%E6%92%AD',
  '几项一起时按固定顺序拼，同一份筛选只有一个地址');

// ---------- 三、往返 ----------
// 一头写一头读，两头必须是同一件事。只测其中一头的话，贴出去的地址打开是一张
// 没筛过的页，而它看起来与筛过的一模一样
for (const one of [
  state({}),
  state({date: '2026-09-01'}),
  state({only: true}),
  state({type: 'PUSH_FAILED'}),
  state({cat: 'LINK'}),
  state({streamer: '甲主播'}),
  state({channel: '群 111'}),
  state({q: '开播 & 下播'}),
  state({date: '2026-08-30', only: true, cat: 'PUSH', type: 'AT_ALL_SKIPPED', streamer: '甲主播',
    channel: '私聊 5201314', q: '额度'}),
  state({view: 'eng'}),
  state({view: 'eng', at: '00:03'}),
  state({view: 'eng', date: '2026-09-01', at: '20:07'}),
  state({view: 'eng', date: '2026-09-01', only: true, q: '超时'}),
]) {
  eq(parseLogHash(logHash(one, TODAY), TODAY), one, '往返：' + logHash(one, TODAY));
}

// ---------- 四、接口查询串 ----------
// 地址栏那一套名字（only / chan）是原型定的，接口那一套（problems / channel）是 /api/timeline 定的，
// 两套只在这一个函数里换一次
eq(timelineQuery(state({}), 0, ''), '?date=2026-09-04&limit=' + DEFAULT_LIMIT,
  '不筛时只带日期与条数');
eq(timelineQuery(state({only: true}), 0, ''), '?date=2026-09-04&limit=' + DEFAULT_LIMIT
  + '&problems=true', '只看问题在接口那头叫 problems');
eq(timelineQuery(state({channel: '群 111'}), 0, ''), '?date=2026-09-04&limit=' + DEFAULT_LIMIT
  + '&channel=%E7%BE%A4%20111', '通道在接口那头叫 channel');
eq(timelineQuery(state({cat: 'PUSH'}), 0, ''), '?date=2026-09-04&limit=' + DEFAULT_LIMIT
  + '&category=PUSH', '大类在接口那头叫 category');
eq(timelineQuery(state({type: 'PUSH_SENT', streamer: '甲主播', q: '开播'}), 50, ''),
  '?date=2026-09-04&limit=50&type=PUSH_SENT&streamer=%E7%94%B2%E4%B8%BB%E6%92%AD&q=%E5%BC%80%E6%92%AD',
  '三筛与条数一起带');
eq(timelineQuery(state({}), 0, '2026-09-04:57'),
  '?date=2026-09-04&limit=' + DEFAULT_LIMIT + '&cursor=2026-09-04%3A57',
  '翻页游标带在查询串上, 不进地址栏');

// ---------- 五、筛没筛与空态那句话 ----------
eq(hasFilter(state({})), false, '什么都没筛');
eq(hasFilter(state({date: '2026-09-01'})), false, '换一天不算筛——那是翻页不是筛选');
eq(hasFilter(state({only: true})), true, '只看问题算筛');
eq(hasFilter(state({type: 'PUSH_SENT'})), true, '类型算筛');
eq(hasFilter(state({cat: 'PUSH'})), true, '大类算筛');
eq(hasFilter(state({streamer: '甲主播'})), true, '主播算筛');
eq(hasFilter(state({channel: '群 111'})), true, '通道算筛');
eq(hasFilter(state({q: '开播'})), true, '搜索算筛');
// 空日与无命中是两回事：前者要说的是「这一天什么都没发生」，后者要说的是「筛得太狠了」，
// 而使用者对这两句话的反应完全不同
eq(emptyText(state({}), 0), '这一天没有记录。', '这一天一条都没有');
eq(emptyText(state({only: true}), 0), '这一天没有记录。', '这一天一条都没有时，先说没记录');
eq(emptyText(state({only: true}), 12), '没有符合条件的，清掉筛选试试。', '有记录而筛没了');
eq(emptyText(state({}), 12), '这一天没有记录。', '没筛却没命中只可能是这一天空着');

// ---------- 六、日期导航 ----------
const DAYS = ['2026-09-04', '2026-09-02', '2026-08-30'];
eq(olderDay(DAYS, '2026-09-04'), '2026-09-02', '前一天取有记录的那一天，不是日历上的前一天');
eq(olderDay(DAYS, '2026-09-02'), '2026-08-30', '再往前');
eq(olderDay(DAYS, '2026-08-30'), '', '到头了就没有前一天');
eq(olderDay(DAYS, '2026-09-03'), '2026-09-02', '站在没有记录的那一天上也找得到前一天');
eq(newerDay(DAYS, '2026-08-30'), '2026-09-02', '后一天同理');
eq(newerDay(DAYS, '2026-09-04'), '', '今天之后没有了');
eq(olderDay([], '2026-09-04'), '', '一天记录都没有时两头都空');
eq(newerDay([], '2026-09-04'), '', '一天记录都没有时两头都空');

// ---------- 七、工程日志：级别与分行 ----------
const HEAD_ERR = '2026-09-04 20:07:03.221 ERROR 1234 --- [main] c.s.b.c.sender.NovaMessageSender'
  + '      : 消息发送失败';
const HEAD_INFO = '2026-09-04 20:05:01.100  INFO 1234 --- [main] c.s.b.core.StarBot'
  + '                       : 已启动';
eq(engLevelOf(HEAD_ERR), 'error', '认得出行首的级别');
eq(engLevelOf(HEAD_INFO), 'info', '级别前面的空格不算数（%5p 右对齐）');
eq(engLevelOf('\tat java.base/java.lang.Thread.run(Thread.java:840)'), '',
  '堆栈那几行自己没有级别');
eq(engLevelOf('2026-09-04 20:05:01.100 TRACE 1 --- [main] x : y'), 'debug',
  'TRACE 并进调试那一档——药丸只有四个，多出来的一档没有开关管得着它');

const GROUPED = groupEngLines([HEAD_ERR, '\tat a.b.C.d(C.java:1)', '\tat e.f.G.h(G.java:2)', HEAD_INFO]);
eq(GROUPED.length, 2, '堆栈跟着它那一行走，不算独立的两行');
eq(GROUPED[0].level, 'error', '整段的级别取头一行的');
eq(GROUPED[0].text.split('\n').length, 3, '头一行加两行堆栈');
eq(GROUPED[1].level, 'info', '下一段另起');
eq(groupEngLines(['\tat a.b.C.d(C.java:1)']),
  [{level: '', text: '\tat a.b.C.d(C.java:1)', hl: false}],
  '开头就是续行时（尾部读进来的第一行常常正是这种）不丢它');
eq(groupEngLines([]), [], '没有行就没有段');

// 高亮的是「那一行所属的整段」，不是那一行本身：服务端给的是原文行号，而屏幕上一段
// 可能是一行加它的堆栈——只高亮其中一行的话，屏幕上会亮起半个异常，找的人不知道它属于谁
const MARKED = groupEngLines([HEAD_ERR, '\tat a.b.C.d(C.java:1)', HEAD_INFO], 1);
eq(MARKED.length, 2, '标高亮不改变分段');
eq(MARKED[0].hl, true, '堆栈那一行归它头上那一段');
eq(MARKED[1].hl, false, '别的段不高亮');
eq(groupEngLines([HEAD_ERR, HEAD_INFO], 1)[1].hl, true, '指到哪一行就亮哪一段');
eq(groupEngLines([HEAD_ERR, HEAD_INFO]).some(entry => entry.hl), false, '没给行号时一段都不亮');
eq(groupEngLines([HEAD_ERR, HEAD_INFO], 9).some(entry => entry.hl), false, '行号超出范围时一段都不亮');

// ---------- 八、工程日志：筛 ----------
const ON = {error: true, warn: true, info: true, debug: true};
const OFF_INFO = {error: true, warn: true, info: false, debug: true};
eq(engVisible({level: 'info', text: 'x'}, ON, ''), true, '都开着时都显示');
eq(engVisible({level: 'info', text: 'x'}, OFF_INFO, ''), false, '关掉的那一档不显示');
eq(engVisible({level: 'error', text: 'x'}, OFF_INFO, ''), true, '别的档不受影响');
// 认不出级别的段往「看得见」的方向失败：藏起来的那几行不会有人发现自己没看见
eq(engVisible({level: '', text: 'x'}, OFF_INFO, ''), true, '认不出级别的段照样显示');
eq(engVisible({level: 'info', text: '发送失败 retcode=-1'}, ON, '发送失败'), true, '搜得到');
eq(engVisible({level: 'info', text: '发送失败 retcode=-1'}, ON, 'RETCODE'), true, '搜索不分大小写');
eq(engVisible({level: 'info', text: '发送失败'}, ON, '  发送  '), true, '搜索词两头的空白不算数');
// 是整串子串匹配，不是拆词后各自匹配——换成拆词的话，搜「失败 群」会命中一堆不相干的行
eq(engVisible({level: 'info', text: '发送失败'}, ON, '失败 发送'), false, '词序反了就搜不到');
eq(engVisible({level: 'info', text: '发送失败'}, ON, '开播'), false, '搜不相干的词搜不到');
// 堆栈并进段里之后，搜类名也搜得到它所属的那一行
eq(engVisible(GROUPED[0], ON, 'C.java'), true, '搜堆栈里的字样也留得住整段');
eq(engVisible({level: 'info', text: 'x'}, ON, '   '), true, '搜索词只有空白时当没搜');

// ---------- 九、工程日志：查询串 ----------
// 与 timelineQuery 同理，地址栏那一套名字只在这一个函数里换成接口那一套。
// 日期一律写明（同 timelineQuery 的 date=），不省今天那一次：省掉的话，哪一天由服务端自己算，
// 而浏览器与服务端不在同一个时区时，跨零点那几个小时里两边说的「今天」不是同一天
eq(engQuery(state({view: 'eng'}), 300, -1), '?limit=300&d=2026-09-04',
  '不定位、不跟随时只带日期与行数');
eq(engQuery(state({view: 'eng', date: '2026-09-01'}), 300, -1), '?limit=300&d=2026-09-01',
  '翻别的日子读那一天那一份');
eq(engQuery(state({view: 'eng', at: '20:07'}), 300, -1), '?limit=300&d=2026-09-04&at=20%3A07',
  '定位到某一分钟');
eq(engQuery(state({view: 'eng'}), 300, 4096), '?limit=300&d=2026-09-04&since=4096',
  '跟随最新只要那个位置之后新写进去的，不是每 3 秒把 300 行重取一遍');
// 定住与跟随不同时发：跟了就会把定住的那一刻推出视野，而使用者刚点了「看这一刻」
eq(engQuery(state({view: 'eng', at: '20:07'}), 300, 4096), '?limit=300&d=2026-09-04&at=20%3A07',
  '定住的时候不跟随');

// ---------- 十、工程日志：跟随最新 ----------
// 阴性那几格是这一组的重头：跟随做错的方向不是「没跟上」，而是「正翻着旧行时被推走」——
// 那时人以为自己点错了，而屏幕上没有任何东西说明刚才发生了什么
eq(engAtBottom(400, 100, 500), true, '滚到底');
eq(engAtBottom(396, 100, 500), true, '差几像素也算到底——滚轮与触控板停不到整数上');
eq(engAtBottom(0, 100, 100), true, '内容装得下时本来就在底部');
eq(engAtBottom(300, 100, 500), false, '离底还远');
eq(engFollowing(true, true, state({view: 'eng'}), TODAY), true, '开着且停在底部时跟');
eq(engFollowing(true, false, state({view: 'eng'}), TODAY), false, '不在底部不跟');
eq(engFollowing(false, true, state({view: 'eng'}), TODAY), false, '开关关着不跟');
eq(engFollowing(true, true, state({view: 'eng', at: '20:07'}), TODAY), false, '定位到某一分钟时不跟');
eq(engFollowing(true, true, state({view: 'eng', date: '2026-09-01'}), TODAY), false,
  '旧日子那一份不会再长，跟它等于每 3 秒问一次同一个答案');

// ---------- 十一、工程日志：复制这一段与空态 ----------
// 复制的就是屏幕上那几段，不另走一条取数路径。打码发生在服务端读盘那一层
// （EngineeringLogService.mask，那一头另有判据），因此屏幕上的、复制走的、传到浏览器的
// 是同一份已经打过码的文本——另开一条「复制时去取原文」的路，才是这一格防的事
const SHOWN = [{level: 'error', text: '甲\n\tat a.b.C.d(C.java:1)', hl: false},
  {level: 'info', text: '乙', hl: false}];
eq(engCopyText(SHOWN), '甲\n\tat a.b.C.d(C.java:1)\n乙', '逐段一行，堆栈跟着它那一段走');
eq(engCopyText([]), '', '一段都没有时复制出来是空的');
eq(engCopyText([{level: 'info', text: '登录失败 password: ******', hl: false}]),
  '登录失败 password: ******', '口令那一行复制出去仍是打过码的那一份');
eq(engEmptyText(state({view: 'eng'}), TODAY, 0), '这一份日志此刻还没有内容。',
  '今天这一份还一行都没写过');
eq(engEmptyText(state({view: 'eng', date: '2026-09-01'}), TODAY, 0), '这一天没有记录。',
  '那一天的文件不在——与「今天还没写」不是一回事，后者过一会儿自己就有了');
eq(engEmptyText(state({view: 'eng'}), TODAY, 12), '没有符合条件的行。', '有行而筛没了');
eq(engEmptyText(state({view: 'eng', date: '2026-09-01'}), TODAY, 12), '没有符合条件的行。',
  '旧日子里筛没了也是筛没了');

// ---------- 十二、工程日志：画与复制共用的那一份「屏幕上是哪几段」 ----------
// 画一遍、复制时再自己筛一遍的话，两份规则会各自漂：改了其中一份（譬如复制时顺手不筛级别），
// 屏幕与剪贴板就此不是同一份日志，而两边都不报错——贴给别人排障的，
// 是一份他自己从没看见过的行。因此这一份判定只留一处，两个调用点都问它
const SEG_LINES = [HEAD_ERR, '\tat a.b.C.d(C.java:1)', HEAD_INFO];
const SEG_ALL = engSegments(SEG_LINES, -1, ON, '');
eq(SEG_ALL.all.length, 2, '分段与 groupEngLines 是同一份：堆栈不另算一段');
eq(SEG_ALL.shown.length, 2, '什么都不筛时屏幕上就是全部');
// all 是页脚那句「共读到 N 段」的分母，它不跟着筛缩水：跟着缩水的话，
// 那句话会永远说「显示 N 段，共读到 N 段」，而筛掉了多少就此看不见
const SEG_OFF = engSegments(SEG_LINES, -1, OFF_INFO, '');
eq(SEG_OFF.all.length, 2, '关掉一档不改变读到了几段');
eq(SEG_OFF.shown.length, 1, '关掉的那一档不上屏');
eq(SEG_OFF.shown[0].level, 'error', '留下的是没被关掉的那一档');
const SEG_Q = engSegments(SEG_LINES, -1, ON, 'C.java');
eq(SEG_Q.all.length, 2, '搜索同样不改变分母');
eq(SEG_Q.shown.length, 1, '搜索也只动 shown');
eq(SEG_Q.shown[0].text.split('\n').length, 2, '搜堆栈里的字样，留下的是整段');
// shown 里的段必须就是 all 里那几段本身，而不是另算一份：另算一份的话，
// 复制走的与画在屏幕上的可以慢慢变成两件事，而屏幕上看不出来
eq(SEG_OFF.shown.every(entry => SEG_OFF.all.includes(entry)), true,
  '屏幕上那几段取自同一份分段结果，不另走一条路');
eq(engSegments([], -1, ON, ''), {all: [], shown: []}, '没有行时两头都是空的');
eq(engSegments(SEG_LINES, 1, ON, '').shown[0].hl, true, '高亮照样落在它所属的那一段上');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
