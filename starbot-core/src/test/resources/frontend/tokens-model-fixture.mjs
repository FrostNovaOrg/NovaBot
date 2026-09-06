/**
 * 只读口令页两件判法的行为夹具
 *
 * 量的是 config-ui/tokens-model.js：explain() 把失败体翻成人话（写盘失败那句不再被吞成
 * HTTP 500）、revokeOutcome() 决定吊销之后显哪句、按什么颜色、要不要重取清单。
 * 之所以喂值跑而不是查源码文本：同名注释就能骗过文本检查，而这里要答的是
 * 「给了这样的失败体，使用者会看到什么」。判定原先混在 tokens.js 里，那条 import 链
 * 拉进整页装配（模块顶层就是 DOM 绑定），夹具进不去——抽成 model 后链上只剩 store。
 *
 * 由 TokensModelTest 拉起，退码 0 ＝ 全绿。
 */

const mod = await import('../../../main/resources/config-ui/tokens-model.js');
const {store} = await import('../../../main/resources/config-ui/store.js');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function includes(actual, part, what) {
  checks++;
  if (!String(actual).includes(part)) {
    failures.push(what + '：得到 ' + JSON.stringify(actual) + '，应含「' + part + '」');
  }
}

function excludes(actual, part, what) {
  checks++;
  if (String(actual).includes(part)) {
    failures.push(what + '：得到 ' + JSON.stringify(actual) + '，不应含「' + part + '」');
  }
}

// 判法还没导出／还没建出来的那一半，红成「没法调用」而不是整个夹具炸掉——
// 整个炸掉的话，已导出那一半的行为读数就一起丢了
function outcome(fn, what) {
  checks++;
  try {
    fn();
  } catch (e) {
    failures.push(what + '：还没法调用——' + (e && e.message ? e.message : e));
  }
}

// ---------- 一、explain：失败体 → 人话 ----------
outcome(() => {
  // 写盘失败：后端那句人话要原样到使用者眼前，这是本笔要修的那一刀
  eq(mod.explain(500, {reason: 'write_failed', message: 'X'}), 'X', '写盘失败带 message：原样透出');
  includes(mod.explain(500, {reason: 'write_failed'}), 'HTTP 500', '写盘失败没带 message：回落 HTTP 句');

  // 没认出的 reason：带了人话就用它，不再把一句说明吞成状态码
  eq(mod.explain(500, {message: 'X'}), 'X', '未知 reason 带 message：原样透出');
  includes(mod.explain(500, {}), 'HTTP 500', '啥都没有：维持 HTTP 句');

  // 既有四支的文案不能被这次改动碰歪（回归护栏）
  includes(mod.explain(401, {reason: 'bad_credentials'}), '控制台口令', 'bad_credentials 文案');
  includes(mod.explain(429, {reason: 'locked_out', retryAfterSeconds: 30}), '30 秒', '锁定文案带等待时长');
  includes(mod.explain(503, {reason: 'busy'}), '同时在校验', 'busy 文案');
  includes(mod.explain(400, {reason: 'auth_disabled'}), '登录口令', 'auth_disabled 文案');
}, 'explain 翻译');

outcome(() => {
  // 同一个 bad_credentials，按「此刻真的要输验证码吗」说两句不同的话——这是行为，不是文案
  store.totpRequired = false;
  excludes(mod.explain(401, {reason: 'bad_credentials'}), '或动态验证码', '没开验证码时提到验证器＝说明书与实际要求各说各话');
  try {
    store.totpRequired = true;
    includes(mod.explain(401, {reason: 'bad_credentials'}), '或动态验证码',
      '开了验证码时得提它，否则人会以为验证码可以不填');
  } finally {
    store.totpRequired = false;
  }
}, 'explain 随二次验证自适应');

// ---------- 二、revokeOutcome：吊销结果 → 显示与后续动作 ----------
outcome(() => {
  eq(mod.revokeOutcome({success: true, message: 'M'}), {text: 'M', kind: 'ok', refresh: true},
    '吊销成功：显后端消息、绿色、重取清单');
  eq(mod.revokeOutcome({success: true}), {text: '已吊销', kind: 'ok', refresh: true},
    '成功但后端没带 message：回落「已吊销」');

  // 本笔要的那一刀：没找到＝清单已过期（多半别处撤过了），也得重取，
  // 否则撤过的那把还挂在屏幕上说「有效」
  eq(mod.revokeOutcome({success: false, reason: 'not_found', message: '没有找到'}),
    {text: '没有找到', kind: 'err', refresh: true}, 'not_found 也要重取清单');

  // 写盘失败不能重取：盘上还是旧账，刷新只会把同一份旧账再画一遍，看起来像撤成功了
  eq(mod.revokeOutcome({success: false, reason: 'write_failed', message: 'W'}),
    {text: 'W', kind: 'err', refresh: false}, 'write_failed 不重取，只提示');

  // 旧形态（没有 reason）：行为与从前一致，不因这次改分派而变
  eq(mod.revokeOutcome({success: false}), {text: '操作失败', kind: 'err', refresh: false},
    '未知失败不重取');
}, 'revokeOutcome 分派');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
