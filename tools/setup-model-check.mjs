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
  SETUP_STEPS, allDone, canAdvance, railMarks, startAt,
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
    streamer: null, targets: [], noStreamerConfirmed: false, sent: false,
  }, over);
}

const pass = (i, over) => canAdvance(i, draft(over)).ok;

// —— 档一：放行 ——
eq(SETUP_STEPS.length, 5, '一共五步');
eq(SETUP_STEPS.map(s => s.skippable), [false, false, true, true, false], '哪几步许跳过');
eq(pass(0, {}), false, '第一步 没上锁不许过');
eq(pass(0, {locked: true}), true, '第一步 上了锁才放行');
eq(pass(3, {streamer: {uid: 3493}}), false, '第四步 找到了人但一个目标都没选，不许过');
eq(pass(3, {streamer: {uid: 3493}, targets: ['g|1|1']}), true, '第四步 选了目标才放行');
eq(pass(5, {locked: true, sent: true}), false, '没有第六步，一律不放行');

// —— 档二：进度条 ——
// 免登录那一步打 warn 点不是打勾：打勾会让人以为登录成功了
eq(railMarks([true, true, false, false, false], ['', '', 'anon', '', ''], 3).map(m => m.state),
  ['done', 'done', 'warn', 'current', 'idle'], '免登录那一步打 warn 点');
eq(railMarks([true, true, true, true, false], ['', '', '', 'skip', ''], 4).map(m => m.state),
  ['done', 'done', 'done', 'done', 'current'], '事实成立时跳过的记号盖不住');

// —— 档三：从哪一步接着走 ——
eq(startAt([true, true, false, false, false], false), 2, '停在第一件没做的事上');
eq(startAt([true, true, true, true, true], true), 0, '重新跑一遍一律回到第一步');
eq(allDone([true, true, true, true, true]), true, '五步都齐了');
eq(allDone([true, true, true, true, false]), false, '差一步就不算齐');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
