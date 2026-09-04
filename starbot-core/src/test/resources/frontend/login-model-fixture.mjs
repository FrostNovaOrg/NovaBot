/**
 * 登录页显隐矩阵与锁定文案的夹具
 *
 * 量的是 config-ui/login-model.js：登录页上「摆什么、折什么、禁什么、写哪句话」这四件事。
 * 它们由三个互不相干的条件决定——这台机器登记过通行密钥没有、二次验证开着没有、
 * 这个来源此刻是不是被锁着——因此一共八格。八格里只有一格是使用者最常见的那一格，
 * 靠人打开页面手点，点得到的永远是那一格。
 *
 * 由 LoginModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本——也正是登录页
 * 在服务端拼进自己那张页面里的同一份字节。
 */

import {loginView, lockText} from '../../../main/resources/config-ui/login-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 把一格的四个判定收成一行，便于逐格对照 */
function view(over) {
  const v = loginView(Object.assign({hasPasskey: false, totpRequired: false, lockedSeconds: 0}, over));
  return {passkey: v.passkey, folded: v.passwordFolded, code: v.code, disabled: v.disabled};
}

// ---------- 一、八格矩阵（通行密钥有/无 × 二次验证开/关 × 锁定中） ----------
//
// 三条规则各管一维，互不牵连：
//   通行密钥登记过 ⇒ 摆主按钮，口令那条路折起来成为第二条路
//   二次验证开着   ⇒ 出 6 位框
//   这个来源锁着   ⇒ 整张表单禁用（通行密钥那条路也一样——锁定按来源计，不按凭据种类计）
const CELLS = [
  [{}, {passkey: false, folded: false, code: false, disabled: false}, '无钥·无码·未锁'],
  [{totpRequired: true}, {passkey: false, folded: false, code: true, disabled: false}, '无钥·有码·未锁'],
  [{lockedSeconds: 90}, {passkey: false, folded: false, code: false, disabled: true}, '无钥·无码·锁定'],
  [{totpRequired: true, lockedSeconds: 90},
    {passkey: false, folded: false, code: true, disabled: true}, '无钥·有码·锁定'],
  [{hasPasskey: true}, {passkey: true, folded: true, code: false, disabled: false}, '有钥·无码·未锁'],
  [{hasPasskey: true, totpRequired: true},
    {passkey: true, folded: true, code: true, disabled: false}, '有钥·有码·未锁'],
  [{hasPasskey: true, lockedSeconds: 90},
    {passkey: true, folded: true, code: false, disabled: true}, '有钥·无码·锁定'],
  [{hasPasskey: true, totpRequired: true, lockedSeconds: 90},
    {passkey: true, folded: true, code: true, disabled: true}, '有钥·有码·锁定'],
];
for (const [state, expected, name] of CELLS) eq(view(state), expected, '矩阵 ' + name);

// 通行密钥登录不经二次验证（清单 §二），因此那个按钮的显示与 totpRequired 无关：
// 上面第 5 格与第 6 格的 passkey 都是 true，这一条把那件事单独说一遍
eq(view({hasPasskey: true, totpRequired: true}).passkey,
  view({hasPasskey: true, totpRequired: false}).passkey, '通行密钥按钮不受二次验证影响');

// ---------- 二、锁定判定 ----------
eq(loginView({lockedSeconds: 0}).locked, false, '剩 0 秒不算锁定');
eq(loginView({lockedSeconds: 1}).locked, true, '剩 1 秒就算锁定');
// 服务端不该给负数，但界面不能因为一个负数就把表单永久禁用
eq(loginView({lockedSeconds: -5}).locked, false, '负数不当锁定');
eq(loginView({}).locked, false, '没给这一位时不当锁定');
eq(loginView({lockedSeconds: null}).locked, false, 'null 不当锁定');

// ---------- 三、锁定文案：必须含实值秒数 ----------
eq(lockText(0), '', '没锁定时不出文案');
eq(lockText(-3), '', '负数同样不出文案');
eq(lockText(45).includes('45 秒'), true, '不足一分钟按秒写');
eq(lockText(45).includes('分'), false, '不足一分钟不写「分」');
eq(lockText(120).includes('2 分'), true, '整分钟按分写');
eq(lockText(120).includes('秒'), false, '整分钟不拖一个「0 秒」');
eq(lockText(125).includes('2 分 5 秒'), true, '零头分秒都写');
eq(lockText(1).includes('1 秒'), true, '只剩一秒也写出来');
// 那句话本身：锁定期内输对也拒，这一条必须说出来——不说的话，
// 使用者会以为是口令记错了，转头去把一个根本没问题的口令改掉
eq(lockText(60).includes('输对'), true, '文案说明锁定期内输对也会被拒');
eq(lockText(60).includes('别急着去改'), true, '文案劝住「去改密码」这个动作');
// 拿变量拼出来的秒数是这条判据的要害：写死一个「15 分钟」的话，
// 屏幕上那个数字与真实剩余时间无关，而两者长得一模一样
eq(lockText(7) === lockText(8), false, '不同剩余时长给出不同文案');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
