/**
 * 日志页视图模型的三档对照
 *
 * 地址栏往返、空态那句话、工程日志分行——这几件事在真机上凑齐一次的代价极高：
 * 要造「有记录却筛没了」得先攒出那一天的事件再设一组筛不到的条件，
 * 要造「堆栈跟在错误后面」得真抛一次。视图模型因此被切成纯函数
 * （config-ui/log-model.js，不碰 DOM），本文件喂它几份值对答案。
 *
 * 用 node 直接跑：
 *   node tools/log-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {
  emptyState, parseLogHash, logHash, hasFilter, emptyText,
  olderDay, newerDay, engLevelOf, groupEngLines,
} from '../starbot-core/src/main/resources/config-ui/log-model.js';

const TODAY = '2026-09-04';
const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function state(over) {
  return Object.assign(emptyState(TODAY), over);
}

// —— 档一：地址栏往返 ——
// 写得进与还原得回来是两件事。只做前一半的话，贴出去的地址打开是一张没筛过的页
const filtered = state({
  date: '2026-08-30', only: true, cat: 'PUSH', type: 'AT_ALL_SKIPPED',
  streamer: '主播甲', channel: '群 111', q: '开播 & 下播',
});
eq(parseLogHash(logHash(filtered, TODAY), TODAY), filtered, '筛过的画面写进地址栏还能还原回来');
eq(parseLogHash('#/log/eng?at=25:99', TODAY).at, '', '时刻形状不对时当没点名，不拿去问服务端');
eq(hasFilter(state({date: '2026-09-01'})), false, '换一天不算筛——那是翻页');

// —— 档二：空态那句话 ——
// 空日与无命中是两回事，合成一句的话筛过头的人会以为机器人那天没干活
eq(emptyText(state({}), 0), '这一天没有记录。', '这一天一条都没有');
eq(emptyText(state({only: true}), 12), '没有符合条件的，清掉筛选试试。', '有记录而筛没了');
eq(olderDay(['2026-09-04', '2026-09-02', '2026-08-30'], '2026-09-04'), '2026-09-02',
  '前一天取有记录的那一天，不是日历上的前一天');
eq(newerDay(['2026-09-04', '2026-09-02', '2026-08-30'], '2026-09-04'), '', '今天之后没有了');

// —— 档三：工程日志分行 ——
const HEAD_ERR = '2026-09-04 20:07:03.221 ERROR 1234 --- [main] x.Y : 失败';
const HEAD_INFO = '2026-09-04 20:05:01.100  INFO 1234 --- [main] x.Y : 已启动';
eq(engLevelOf('2026-09-04 20:05:01.100 TRACE 1 --- [main] x : y'), 'debug',
  'TRACE 并进调试那一档');
const grouped = groupEngLines([HEAD_ERR, '\tat a.b.C.d(C.java:1)', HEAD_INFO], 1);
eq(grouped.length, 2, '堆栈跟着它那一行走，不算独立的两行');
eq(grouped[0].hl, true, '高亮的是那一行所属的整段');
eq(grouped[1].hl, false, '别的段不高亮');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
