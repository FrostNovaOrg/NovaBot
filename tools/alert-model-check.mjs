/**
 * 告警页「邮件配好了没有」的三档对照
 *
 * 设置页药丸和初始设置完成页那一行，邮件这一路都只认收件与 SMTP 主机这两栏。
 * 判定只留这一份（config-ui/alert-model.js，不碰 DOM）：两处各写各的话，
 * 完成页写着「已配」、药丸却是「未配置」。本文件喂它空串、只填一半、首尾空白对答案。
 *
 * 用 node 直接跑：
 *   node tools/alert-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {mailAlertConfigured} from '../core/starbot-core/src/main/resources/config-ui/alert-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// —— 档一：空串／缺省 ——
eq(mailAlertConfigured('', ''), false, '两栏都空');
eq(mailAlertConfigured(null, null), false, '两栏都缺');
eq(mailAlertConfigured(undefined, 'smtp.example.com'), false, '只给了主机、收件缺');

// —— 档二：只填一半 ——
eq(mailAlertConfigured('a@b.com', ''), false, '有收件无主机');
eq(mailAlertConfigured('', 'smtp.example.com'), false, '有主机无收件');
eq(mailAlertConfigured('a@b.com', '   '), false, '主机只空白视同没填');
eq(mailAlertConfigured('  ', 'smtp.example.com'), false, '收件只空白视同没填');

// —— 档三：首尾空白与大小写 ——
eq(mailAlertConfigured('  a@b.com  ', '  smtp.example.com  '), true, '两栏带空白 trim 后都有就算配好');
eq(mailAlertConfigured('A@B.COM', 'SMTP.Example.COM'), true, '大小写原样保留、非空即配好');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
