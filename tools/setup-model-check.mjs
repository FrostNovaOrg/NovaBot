/**
 * 初始设置页视图模型的三档对照
 *
 * 哪一步不许跳过、少了什么不许往下走、「重新跑一遍」之后从第几步接着走——
 * 这几件事在真机上凑齐一次的代价极高：要点出「第一步不许跳」得先有一台没上锁的机器。
 * 放行条件写反了不会有任何报错，只是那一步变成点一下就过。视图模型因此被切成纯函数
 * （config-ui/setup-model.js，不碰 DOM），本文件喂它几份草稿对答案。
 *
 * 用 node 直接跑：
 *   node tools/setup-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {
  SETUP_STEPS, allDone, canAdvance, initialRows, railMarks, startAt, summaryLines,
  withPluginSteps,
} from '../starbot-core/src/main/resources/config-ui/setup-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function draft(over) {
  return Object.assign({
    locked: false, botOk: false, accountsReady: false, anonymousConfirmed: false,
    targets: [], sent: false,
  }, over);
}

const pass = (i, over) => canAdvance(i, draft(over)).ok;

// —— 档一：放行 ——
eq(SETUP_STEPS.length, 4, '内置一共四步');
eq(SETUP_STEPS.map(s => s.skippable), [false, false, true, false], '哪几步许跳过');
eq(SETUP_STEPS.some(s => s.key === 'streamer'), false,
  '主播那一步不在内置表上：它已随控制台插件走');
eq(pass(0, {}), false, '第一步 没上锁不许过');
eq(pass(0, {locked: true}), true, '第一步 上了锁才放行');
eq(pass(2, {}), false, '第三步 没登录也没确认免登录，不许过');
eq(pass(2, {anonymousConfirmed: true}), true, '第三步 过了免登录那段确认才放行');
eq(pass(3, {}), false, '末步 没发过试试不许过');
eq(pass(3, {sent: true}), true, '末步 发过了才放行');
eq(pass(4, {locked: true, sent: true}), false, '没有第五步，一律不放行');

const PLUGIN_PAGE = {id: 'danmu', displayName: '弹幕', order: 50, slot: 'setup_step'};
const pluginSteps = withPluginSteps([PLUGIN_PAGE]);
eq(pluginSteps.map(s => s.key), ['lock', 'bot', 'account', 'danmu', 'test'],
  '插件步插在登录直播平台之后、试发之前');
eq(canAdvance(4, draft({sent: true}), pluginSteps).ok, true,
  '有插件步时试发下标挪到 4，仍按试发放行');
eq(canAdvance(3, draft({}), pluginSteps).ok, true,
  '插件步一律放行：它该不该走由那一步自己判，核心不知道它在收什么');
// 🔴 插件步先认出来再进 switch：它的 key 由插件申报，可以恰好与某个曾经的内置键同名
// （主播那一步搬进插件之后仍叫 streamer）。先进 switch 的话，那一步会被拿一份早已不存在的
// 核心草稿去判，屏幕上的表现是「下一步永远点不动」
const renamed = withPluginSteps([{id: 'streamer', displayName: '主播', order: 40, slot: 'setup_step'}]);
eq(renamed.map(s => s.key), ['lock', 'bot', 'account', 'streamer', 'test'],
  '叫 streamer 的插件步登记得进来');
eq(canAdvance(3, draft({}), renamed).ok, true,
  '叫 streamer 的插件步不被早已作废的那份核心判定拦住');

// —— 档二：进度条 ——
// 免登录那一步打 warn 点不是打勾：打勾会让人以为登录成功了
eq(railMarks([true, true, false, false], ['', '', 'anon', ''], 3).map(m => m.state),
  ['done', 'done', 'warn', 'current'], '免登录那一步打 warn 点');
eq(railMarks([true, true, true, false], ['', '', 'skip', ''], 3).map(m => m.state),
  ['done', 'done', 'done', 'current'], '事实成立时跳过的记号盖不住');
eq(railMarks([true, true, true, false, false], ['', '', '', '', ''], 3, pluginSteps)
  .map(m => m.state),
  ['done', 'done', 'done', 'current', 'idle'], '插件步未完成时进度条停在它上面');

// —— 档三：从哪一步接着走 ——
eq(startAt([true, true, false, false], false), 2, '停在第一件没做的事上');
eq(startAt([true, true, true, true], true), 0, '重新跑一遍一律回到第一步');
eq(allDone([true, true, true, true]), true, '四步都齐了');
eq(allDone([true, true, true, false]), false, '差一步就不算齐');
eq(allDone([true, true, true, false, true], pluginSteps), false,
  'pluginDone 假时 allDone 为假');
eq(allDone([true, true, true, true, true], pluginSteps), true,
  'pluginDone 真时 allDone 与无插件时同答案');
eq(allDone([true, true, true, true]), true,
  '无插件时 allDone 一字不变');
eq(startAt([true, true, true, false, true], false, pluginSteps), 3,
  '插件步未完成时从它接着走');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
if (failures.length) process.exit(1);

// ── terms 三档：有词＝适配器七键／无词＝{}／只缺 bot.impl ────────────────
// 三问各自 try/catch，末尾汇总，不得短路。
const ADAPTER_TERMS = {
  'bot.platform': 'QQ',
  'bot.impl': 'NapCat',
  'bot.family': 'OneBot 实现',
  'bot.impl.hint': 'NapCat、Lagrange 等 OneBot 实现',
  'bot.target.group': '群号',
  'bot.target.user': 'QQ 号',
  'bot.targets': '群与好友',
};
const NO_IMPL = Object.assign({}, ADAPTER_TERMS);
delete NO_IMPL['bot.impl'];

const phraseReds = [];
function askPhrase(name, fn) {
  try {
    fn();
  } catch (err) {
    phraseReds.push(name + '：' + (err && err.message ? err.message : String(err)));
  }
}
function mustEq(actual, expected, what) {
  if (actual !== expected) {
    throw new Error(what + ' 得到 ' + JSON.stringify(actual) + ' 应为 ' + JSON.stringify(expected));
  }
}
function alertText(terms) {
  return (initialRows({}, null, terms).find(row => row.label === '告警') || {}).text || '';
}

askPhrase('①有词', () => {
  mustEq((withPluginSteps(undefined, ADAPTER_TERMS).find(s => s.key === 'bot') || {}).title,
    '连上 QQ 机器人', '步名');
  const alert = alertText(ADAPTER_TERMS);
  if (!alert.includes('QQ 掉线')) throw new Error('告警行 ' + JSON.stringify(alert));
  mustEq(summaryLines([true, true, true, true], ['', '', '', ''], {}, undefined, ADAPTER_TERMS)[0],
    'QQ 机器人 已连上', '小结已连');
});

askPhrase('②无词', () => {
  mustEq((withPluginSteps(undefined, {}).find(s => s.key === 'bot') || {}).title,
    '连上机器人', '步名');
  const alert = alertText({});
  if (alert.includes('QQ') || !alert.includes('机器人掉线')) {
    throw new Error('告警行 ' + JSON.stringify(alert));
  }
  mustEq(summaryLines([true, true, true, true], ['', '', '', ''], {}, undefined, {})[0],
    '机器人 已连上', '小结已连');
});

askPhrase('③只缺 bot.impl', () => {
  mustEq((withPluginSteps(undefined, NO_IMPL).find(s => s.key === 'bot') || {}).title,
    '连上 QQ 机器人', '步名仍用 platform');
  const alert = alertText(NO_IMPL);
  if (!alert.includes('QQ 掉线')) throw new Error('告警行 ' + JSON.stringify(alert));
  mustEq(summaryLines([false, false, false, false], ['', '', '', ''], {}, undefined, NO_IMPL)[0],
    'QQ 机器人 还没连上，推送发不出去', '小结未连');
});

console.log('terms 三档\t跑了 3 格\t红 ' + phraseReds.length + ' 格\t' + (phraseReds.length ? '红' : '绿'));
if (phraseReds.length) {
  console.error('\nterms 三档对不上 ' + phraseReds.length + ' 处：');
  phraseReds.forEach(line => console.error('  ' + line));
  process.exit(1);
}
