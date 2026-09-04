/**
 * 初始设置五步状态机的夹具
 *
 * 量的是 config-ui/setup-model.js：五步各自走到哪、哪一步放不放行、进度条上画什么、
 * 从第几步接着走、以及「已替你定好的初始值」那几行写什么。
 *
 * 🔴 这几件事在真机上一格一格点出来的代价极高：要点出「第一步不许跳」得先有一台没上锁的机器，
 * 要点出「第四步 0 主播不许过」得先把机器人连上再故意不选目标，而这两格恰恰是
 * 使用者最容易被卡住、也最容易被写松的地方——放行条件写反了不会有任何报错，
 * 只是那一步变成了点一下就过。
 *
 * 由 SetupModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里那一份，不是构建产物里的副本。
 */

import {
  SETUP_STEPS, allDone, canAdvance, initialRows, railMarks, setupProgress, startAt, summaryLines,
} from '../../../main/resources/config-ui/setup-model.js';
import {mailAlertConfigured} from '../../../main/resources/config-ui/alert-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 一份「什么都还没做」的草稿，各格只覆盖自己要试的那一位 */
function draft(over) {
  return Object.assign({
    locked: false, botOk: false, accountsReady: false, anonymousConfirmed: false,
    streamer: null, targets: [], noStreamerConfirmed: false, sent: false,
  }, over);
}

/** 只取放行与否，理由那一栏另行单独查 */
const pass = (i, over) => canAdvance(i, draft(over)).ok;

// ---------- 一、五步是个闭集 ----------
eq(SETUP_STEPS.length, 5, '一共五步');
eq(SETUP_STEPS.map(s => s.key), ['lock', 'bot', 'account', 'streamer', 'test'], '五步的标识');
eq(SETUP_STEPS.map(s => s.skippable), [false, false, true, true, false], '哪几步许跳过');
// 步名里不许出现任何一个直播平台的名字（格1 契约）。这一条在 tools/novacore-boundary-check.sh
// 里也有一格，那一格扫的是整个界面目录；这里守的是同一件事在闭集上的形态——
// 平台名一旦写进这张表，进度条、小结、首页待办三处会一起显示出来
eq(SETUP_STEPS.map(s => s.title).join('').includes('平台'), true, '第三步按通名说「直播平台」');

// ---------- 二、放行条件 ----------
//
// 🔴 三条点名的：第一步不可跳、第四步 0 主播不许过、第三步免登录必过确认。
// 每条都配阴性对照（那一位翻过来就该放行），否则「恒不放行」与「判得对」长得一样。

// 第 1 步 上锁：这一步不许跳过——没有 skip 那条路，只有上了锁才过得去
eq(pass(0, {}), false, '第一步 没上锁不许过');
eq(pass(0, {locked: true}), true, '第一步 上了锁才放行');
// 阴性对照：别的位都不作数。写成「填了口令框就放行」的话下面这一格会绿
eq(pass(0, {botOk: true, sent: true, accountsReady: true}), false, '第一步 别的步做完了也不顶');
eq(SETUP_STEPS[0].skippable, false, '第一步不给跳过按钮');

// 第 2 步 连机器人：测试通过才算
eq(pass(1, {}), false, '第二步 没测过不许过');
eq(pass(1, {botOk: true}), true, '第二步 测试通过才放行');
eq(SETUP_STEPS[1].skippable, false, '第二步不给跳过按钮');

// 第 3 步 登录直播平台：登录了直接过；不登录要先过那段后果确认
eq(pass(2, {}), false, '第三步 既没登录也没确认，不许过');
eq(pass(2, {accountsReady: true}), true, '第三步 登录了就放行');
eq(pass(2, {anonymousConfirmed: true}), true, '第三步 过了后果确认也放行');
// 🔴 确认这件事不许被别的动作代劳：点了「不登录」而没确认，仍然不许过
eq(pass(2, {streamer: {uid: 1}, targets: ['g|1|1'], sent: true}), false,
  '第三步 后面几步做了也不代替那段确认');

// 第 4 步 第一位主播：找到人且至少选一个目标；「先不加」要单独确认
eq(pass(3, {}), false, '第四步 没找到主播不许过');
eq(pass(3, {streamer: {uid: 3493}}), false, '第四步 🔴 找到了人但一个目标都没选，不许过');
eq(pass(3, {streamer: {uid: 3493}, targets: ['g|1|1']}), true, '第四步 选了目标才放行');
eq(pass(3, {targets: ['g|1|1']}), false, '第四步 只有目标没有主播也不许过');
eq(pass(3, {noStreamerConfirmed: true}), true, '第四步 确认过「先不加主播」才放行');

// 第 5 步 发一条试试
eq(pass(4, {}), false, '第五步 没发过不许过');
eq(pass(4, {sent: true}), true, '第五步 发过了才放行');

// 认不出来的步一律不放行：多一步少一步的时候，宁可卡住也不许默默放过去
eq(pass(5, {locked: true, botOk: true, sent: true}), false, '没有第六步，一律不放行');

// 拦下来时必须说出拦的是什么。空理由等于屏幕上什么都不显示，
// 而使用者看到的是「下一步这个按钮点不动」
for (let i = 0; i < 5; i++) {
  const stopped = canAdvance(i, draft({}));
  checks++;
  if (stopped.ok || !stopped.reason || stopped.reason.length < 8) {
    failures.push('第 ' + (i + 1) + ' 步拦下来时没给出人话理由：' + JSON.stringify(stopped));
  }
}
// 五条理由各不相同：抄成同一句的话，第二步与第四步的下一步动作完全不同却写着同一行字
eq(new Set([0, 1, 2, 3, 4].map(i => canAdvance(i, draft({})).reason)).size, 5, '五条理由互不相同');

// ---------- 三、进度条上画什么 ----------
const marks = (facts, skips, current) => railMarks(facts, skips, current).map(m => m.state);

eq(marks([false, false, false, false, false], ['', '', '', '', ''], 0),
  ['current', 'idle', 'idle', 'idle', 'idle'], '刚进来时只有第一步是「正在做」');
eq(marks([true, true, false, false, false], ['', '', '', '', ''], 2),
  ['done', 'done', 'current', 'idle', 'idle'], '走到第三步');
eq(marks([true, true, false, false, false], ['', '', 'anon', '', ''], 3),
  ['done', 'done', 'warn', 'current', 'idle'], '🔴 免登录那一步打 warn 点，不是打勾');
eq(marks([true, true, true, false, false], ['', '', '', 'skip', ''], 4),
  ['done', 'done', 'done', 'skip', 'current'], '「先不加主播」显示成跳过');
// 事实成立就是成立：跳过的记号盖不住它。使用者回头补上了主播，那一格该变成完成
eq(marks([true, true, true, true, false], ['', '', '', 'skip', ''], 4)[3], 'done',
  '事实成立之后，跳过的记号让位给完成');
eq(railMarks([false, false, false, false, false], ['', '', 'anon', '', ''], 0)[2].note, '免登录模式',
  'warn 那一格写明它是什么');

// ---------- 四、从第几步接着走 ----------
eq(startAt([false, false, false, false, false], false), 0, '什么都没做时从第一步起');
eq(startAt([true, true, false, false, false], false), 2, '落在第一个还没做完的那一步');
eq(startAt([true, true, true, true, true], false), 4, '全做完了停在最后一步（页面另画小结）');
// 🔴 「重新跑一遍」按下之后必须回到第一步。不回的话，那个按钮点完停在原地，
// 与「点了没反应」在屏幕上长得一样
eq(startAt([true, true, true, true, true], true), 0, '要求重来时回到第一步');
eq(allDone([true, true, true, true, true]), true, '五步齐了');
eq(allDone([true, true, true, true, false]), false, '差一步就不算齐');
eq(allDone([true, true, true, false, true]), false, '中间缺一步同样不算齐');

// ---------- 五、完成度：阳性与阴性 ----------
//
// 完成度读的是服务端那几份事实，不是页面自己攒的计数器——攒的那种在刷新之后归零，
// 而屏幕上会写着「完成了 0 步」，尽管这台机器早就配好了。
const bare = {locked: false, health: [], users: [], senders: []};
const full = {
  locked: true,
  health: [{scope: 'BOT', level: 'OK', name: '机器人连接', summary: '正常'}],
  users: [{uid: 3493}],
  senders: ['qq'],
};
const noAccounts = {accounts: []};
const loggedIn = {accounts: [{platform: 'x', displayName: '某平台', loggedIn: true}]};
const notLoggedIn = {accounts: [{platform: 'x', displayName: '某平台', loggedIn: false}]};

eq(setupProgress(bare, notLoggedIn, false), 0, '阴性 —— 什么都没配时是 0 步');
eq(setupProgress(full, loggedIn, true), 5, '阳性 —— 全配齐时是 5 步');
eq(setupProgress(full, loggedIn, false), 4, '第五步没发过时是 4 步');
// 不交第五步那一位时按「前四步都成立」算，首页那条待办走的就是这条路
eq(setupProgress(full, loggedIn, null), 5, '不知道发没发过时，前四步齐了就算齐');
eq(setupProgress(bare, notLoggedIn, null), 0, '前四步没齐时，第五步跟着不算');
// 🔴 空集上「每个平台都登录了」恒真。这不是漏判，是明写的读法：一个直播平台插件都没装时，
// 第三步是「没得做」而不是「没做完」——把它算成没做完的话，那台机器永远走不完这五步。
// 写成一格是因为它与上面那条阴性只差一个空数组，而两者的读数不一样
eq(setupProgress(bare, noAccounts, false), 1, '一个平台插件都没装时，第三步算已定');
// 免登录也算这一步已定：它是一个做过的决定，不是一件没做的事
eq(setupProgress(full, {accounts: [{platform: 'x', displayName: '某平台', loggedIn: false,
  disabledReason: '免登录模式'}]}, true), 5, '免登录模式算第三步已定');
eq(setupProgress(full, {accounts: [{platform: 'x', displayName: '某平台', loggedIn: false}]}, true), 4,
  '没登录又没关掉，第三步不算');

// ---------- 六、已替你定好的初始值 ----------
const rows = initialRows({
  'starbot.core.push.enabled': 'true',
  'starbot.core.push.quiet-start': '',
  'starbot.core.push.quiet-end': '',
  'starbot.core.alert.webhook-url': '',
  'starbot.core.alert.qq-num': '',
  'starbot.core.timeline.retention-days': '14',
}, 14);

eq(rows.length >= 6, true, '初始值至少摊开六行');
eq(rows.every(r => r.label && r.text && r.href), true, '每行都要有名字、现值与去处');
eq(rows[0].text, '开', '推送总开关按现值写，不写死');
eq(rows.find(r => r.label === '静音时段').text.includes('未设'), true, '没设静音时段时写「未设」');
eq(rows.find(r => r.label === '日志保留').text, '14 天', '日志保留天数取自现值');
eq(rows.find(r => r.label === '告警').text.includes('未配'), true, '没配告警时说未配');
eq(rows.find(r => r.label === '命令').text.includes('14'), true, '命令条数取自运行期清单');

// 🔴 现值真的会跟着变：写死一张表的话，一台已经关掉推送的机器上，
// 这一行照样写着「开」，而使用者刚刚才关掉它
const changed = initialRows({
  'starbot.core.push.enabled': 'false',
  'starbot.core.push.quiet-start': '23:00',
  'starbot.core.push.quiet-end': '08:00',
  'starbot.core.alert.webhook-url': 'https://example.invalid/hook',
  'starbot.core.timeline.retention-days': '0',
}, null);
eq(changed[0].text, '关', '推送关掉之后这一行跟着变');
eq(changed.find(r => r.label === '静音时段').text, '23:00 – 08:00', '设了静音时段就写出来');
eq(changed.find(r => r.label === '告警').text.includes('已配'), true, '配了 Webhook 就说已配');
eq(changed.find(r => r.label === '日志保留').text.includes('不自动清理'), true, '0 天是不清理，不是留 0 天');
eq(changed.find(r => r.label === '命令').text.includes('条'), false, '数不到条数时不编一个出来');

eq(mailAlertConfigured('ops@example.invalid', 'smtp.example.invalid'), true, '邮件两栏都有才算已配');
eq(mailAlertConfigured('ops@example.invalid', ''), false, '只填收件不算已配');
eq(mailAlertConfigured('', 'smtp.example.invalid'), false, '只填主机不算已配');

const mailed = initialRows({
  'starbot.core.push.enabled': 'true',
  'starbot.core.alert.webhook-url': '',
  'starbot.core.alert.qq-num': '',
  'starbot.core.mail.default-to': 'ops@example.invalid',
  'spring.mail.host': 'smtp.example.invalid',
  'starbot.core.timeline.retention-days': '14',
}, null);
eq(mailed.find(r => r.label === '告警').text.includes('已配'), true, '配了邮件就说已配');

const mailOnlyTo = initialRows({
  'starbot.core.push.enabled': 'true',
  'starbot.core.alert.webhook-url': '',
  'starbot.core.alert.qq-num': '',
  'starbot.core.mail.default-to': 'ops@example.invalid',
  'spring.mail.host': '',
  'starbot.core.timeline.retention-days': '14',
}, null);
eq(mailOnlyTo.find(r => r.label === '告警').text.includes('未配'), true, '只填收件时初始值仍说未配');

// ---------- 七、完成后的三行小结 ----------
const lines = summaryLines([true, true, true, true, true], ['', '', '', '', ''], {
  accounts: [{displayName: '某平台', loggedIn: true}], streamers: 1, targets: 2,
});
eq(lines.length, 3, '小结正好三行');
eq(lines[2].includes('2'), true, '第三行写出推到几个目标');
const skipped = summaryLines([true, true, true, false, true], ['', '', 'anon', 'skip', ''], {
  accounts: [{displayName: '某平台', loggedIn: false, disabledReason: '免登录模式'}],
  streamers: 0, targets: 0,
});
eq(skipped[2].includes('什么都不做'), true, '🔴 没加主播时小结如实说这台机器起来什么都不做');
eq(skipped[1].includes('免登录'), true, '免登录时小结把后果带上');
eq(summaryLines([true, true, true, true, true], ['', '', '', '', ''],
  {accounts: [], streamers: 1, targets: 1})[1].includes('插件'), true,
  '一个直播平台插件都没装时，第二行说的是这件事');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
