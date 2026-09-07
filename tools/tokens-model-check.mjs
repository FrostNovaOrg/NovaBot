/**
 * 只读口令页判定的三档对照
 *
 * 失败体翻人话、吊销后显哪句／要不要重取清单，全是纯函数
 * （config-ui/tokens-model.js，不碰 DOM）。tokens.js 的 import 链拉进整页装配，
 * 夹具进不去；判定抽到这里才能单独喂。本文件喂它空串、只填一半、大小写 reason 对答案。
 *
 * 用 node 直接跑：
 *   node tools/tokens-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {store} from '../starbot-core/src/main/resources/config-ui/store.js';
import {explain, revokeOutcome} from '../starbot-core/src/main/resources/config-ui/tokens-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// —— 档一：失败体翻人话（空串／缺秒数走下限）——
store.totpRequired = false;
eq(explain(401, {reason: 'bad_credentials'}).includes('或动态验证码'), false, '没开二次验证不提验证码');
store.totpRequired = true;
eq(explain(401, {reason: 'bad_credentials'}).includes('或动态验证码'), true, '开了二次验证要提验证码');
store.totpRequired = false;

eq(explain(429, {reason: 'locked_out', retryAfterSeconds: 0}).includes('1 秒'), true, '剩 0 秒也写至少 1 秒');
eq(explain(429, {reason: 'locked_out', retryAfterSeconds: 45}).includes('45 秒'), true, '不足一分钟按秒写');
eq(explain(429, {reason: 'locked_out', retryAfterSeconds: 60}).includes('1 分钟'), true, '满 60 秒改写分钟');
eq(explain(429, {reason: 'locked_out', retryAfterSeconds: '  90  '}).includes('2 分钟'), true, '秒数字符带空白仍按分钟进位');
eq(explain(429, {reason: 'locked_out', retryAfterSeconds: ''}).includes('1 秒'), true, '秒数空串视同 0 再抬到 1 秒');
eq(explain(429, {reason: 'locked_out'}).includes('锁定期内即使输对也会被拒'), true, '锁定文案必须把「输对也会被拒」说出口');

eq(explain(503, {reason: 'busy'}).includes('等几秒再点一次'), true, '忙');
eq(explain(400, {reason: 'auth_disabled'}).includes('没有启用控制台登录口令'), true, '没开口令');
eq(explain(500, {reason: 'write_failed', message: '磁盘满了'}), '磁盘满了', '写盘失败带人话原样透出');
eq(explain(500, {reason: 'write_failed'}), '签发失败（HTTP 500）', '写盘失败没人话就带状态码');
eq(explain(418, {reason: 'no_such', message: '后端那句'}), '后端那句', '没认出的 reason 带了人话就用它');
eq(explain(418, {reason: 'no_such'}), '签发失败（HTTP 418）', '没认出又没人话才落状态码');
eq(explain(401, {reason: 'BAD_CREDENTIALS'}).includes('口令'), false, 'reason 全大写认不出，不走口令不对那句');

// —— 档二：吊销结果（只填一半：有无 message）——
eq(revokeOutcome({success: false, reason: 'write_failed', message: '盘上写失败'}).refresh, false, '写盘失败带话也不重取');
eq(revokeOutcome({success: true}), {text: '已吊销', kind: 'ok', refresh: true}, '成功没带话');
eq(revokeOutcome({success: true, message: '已撤客厅那台'}), {text: '已撤客厅那台', kind: 'ok', refresh: true}, '成功带话');
eq(revokeOutcome({success: false}), {text: '操作失败', kind: 'err', refresh: false}, '失败没带话也不重取');
eq(revokeOutcome({success: false, message: ''}), {text: '操作失败', kind: 'err', refresh: false}, '失败空串视同没带话');
eq(revokeOutcome({success: false, message: '  '}), {text: '  ', kind: 'err', refresh: false}, '失败空白原样当话、仍不重取');

// —— 档三：要不要重取（大小写 reason）——
eq(revokeOutcome({success: false, reason: 'not_found'}).refresh, true, '找不到也要重取，清单多半过期');
eq(revokeOutcome({success: false, reason: 'NOT_FOUND'}).refresh, false, 'reason 全大写不算 not_found');
eq(revokeOutcome({success: false, reason: 'write_failed'}).refresh, false, '写盘失败不重取，盘上还是旧账');
eq(revokeOutcome({success: 1, reason: 'write_failed'}).refresh, true, 'success 为 1 视同成功，即使 reason 是写盘失败也重取');

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
function cred(terms) {
  return explain(401, {reason: 'bad_credentials'}, terms);
}

askPhrase('①有词', () => {
  const got = cred(ADAPTER_TERMS);
  if (!got.includes('不是机器人（NapCat 等）WebUI 的口令')) {
    throw new Error('实得 ' + JSON.stringify(got));
  }
});

askPhrase('②无词', () => {
  const got = cred({});
  if (got.includes('NapCat') || got.includes('（') || !got.includes('不是机器人程序界面的口令')) {
    throw new Error('实得 ' + JSON.stringify(got));
  }
});

askPhrase('③只缺 bot.impl', () => {
  const got = cred(NO_IMPL);
  if (got.includes('NapCat') || got.includes('（') || !got.includes('不是机器人程序界面的口令')) {
    throw new Error('实得 ' + JSON.stringify(got));
  }
});

console.log('terms 三档\t跑了 3 格\t红 ' + phraseReds.length + ' 格\t' + (phraseReds.length ? '红' : '绿'));
if (phraseReds.length) {
  console.error('\nterms 三档对不上 ' + phraseReds.length + ' 处：');
  phraseReds.forEach(line => console.error('  ' + line));
  process.exit(1);
}
