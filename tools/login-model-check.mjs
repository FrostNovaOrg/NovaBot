/**
 * 登录页视图模型的三档对照
 *
 * 摆什么、折什么、禁什么由三个互不相干的条件决定，一共八格，
 * 而使用者日常见到的永远只有一格——「锁定中」那一列要先连错五次口令才看得见。
 * 视图模型因此被切成纯函数（config-ui/login-model.js，不碰 DOM），本文件喂它几份登录态对答案。
 *
 * 用 node 直接跑：
 *   node tools/login-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {loginView, lockText, landing} from '../starbot-core/src/main/resources/config-ui/login-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function view(over) {
  const v = loginView(Object.assign({hasPasskey: false, totpRequired: false, lockedSeconds: 0}, over));
  return {passkey: v.passkey, folded: v.passwordFolded, code: v.code, disabled: v.disabled};
}

// —— 档一：显隐矩阵里三格 ——
eq(view({}), {passkey: false, folded: false, code: false, disabled: false}, '无钥·无码·未锁');
eq(view({hasPasskey: true, totpRequired: true}),
  {passkey: true, folded: true, code: true, disabled: false}, '有钥·有码·未锁');
eq(view({hasPasskey: true, lockedSeconds: 90}),
  {passkey: true, folded: true, code: false, disabled: true}, '有钥·无码·锁定——通行密钥那条路也禁');

// —— 档二：锁定判定 ——
eq(loginView({lockedSeconds: 0}).locked, false, '剩 0 秒不算锁定');
eq(loginView({lockedSeconds: 1}).locked, true, '剩 1 秒就算锁定');
eq(loginView({lockedSeconds: -5}).locked, false, '负数不当锁定');

// —— 档三：锁定文案含实值 ——
eq(lockText(0), '', '没锁定时不出文案');
eq(lockText(45).includes('45 秒'), true, '不足一分钟按秒写');
eq(lockText(120).includes('秒'), false, '整分钟不拖一个 0 秒');
eq(lockText(125).includes('2 分 5 秒'), true, '零头分秒都写');
eq(lockText(60).includes('输对'), true, '文案说明锁定期内输对也会被拒');
eq(lockText(7) === lockText(8), false, '不同剩余时长给出不同文案');

// —— 档四：落点 ——
eq(landing('http://h/config#/settings', '#/settings').reload, true, '同址带片段：replace 只做片段导航，须改重载');
eq(landing('http://h/config', ''), {reload: false, url: '/config'}, '无片段时不重载，落点是 /config');
eq(landing('http://h/config?token=x', '').reload, false, '带 query 的现址与落点不同，replace 照常整页导航');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
