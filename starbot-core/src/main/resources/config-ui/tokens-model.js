/**
 * 只读口令页的判定：失败体翻人话、吊销后的动作
 *
 * 全是纯函数，一个 DOM 也不碰——因此它们可以被单独喂夹具跑出读数来。
 * tokens.js 的 import 链一路拉进整页装配（main.js 起的全家桶，模块顶层就是绑定），
 * 夹具进不去；判定抽到这里，链上只剩 store，行为照样量得到。
 *
 * 抽法与 confirm-model.js / links-model.js 同构，视图接线留在 tokens.js。
 */

import {store} from './store.js';

/** 同义 core.js 的 term：有键用词，无键用中性兜底。模型不读全局。 */
function word(terms, key, fallback) {
  return (terms && terms[key]) || fallback;
}

/** 同义 core.js 的 phrase：词在则套进 withTerm，词缺则整句退成中性 without。 */
function say(terms, key, withTerm, without) {
  const v = terms && terms[key];
  return v ? withTerm(v) : without;
}

/**
 * 把契约里的 reason 翻成人话
 *
 * 🔴 locked_out 与 bad_credentials 必须分开说：锁定期内输对的口令也会被拒，
 * 此时说「口令不对」会让人去重置一个根本没问题的密码。
 * @param status HTTP 状态
 * @param data 失败体
 * @param terms /api/vocab 七键；缺则中性兜底
 */
export function explain(status, data, terms) {
  switch (data.reason) {
    case 'bad_credentials':
      return '密码' + (store.totpRequired ? '或动态验证码' : '') + '不对。'
        + '要填的是登录这个控制台用的那一个——'
        + say(terms, 'bot.impl',
          v => '不是机器人（' + v + ' 等）WebUI 的口令，两者互不相干',
          '不是机器人程序界面的口令，两者互不相干');
    case 'locked_out':
      return '连续失败太多次，你这个来源已被暂时锁定，' + waitText(data.retryAfterSeconds) + '后再试。'
        + '⚠️ 锁定期内即使输对也会被拒，这不代表口令错了，别急着去改密码';
    case 'busy':
      return '同时在校验的请求太多，等几秒再点一次';
    case 'auth_disabled':
      return '这台机器没有启用密码，因此没有可校验的凭据。请先在「设置」里配置密码';
    case 'write_failed':
      // 后端已把原因写成一句人话带过来，原样透出——转述一道就是多一处会走样的地方
      return data.message || '签发失败（HTTP ' + status + '）';
    default:
      // 没认出的 reason：带了人话就用它。原先一律吞成状态码，后端特意写的那句说明到不了人眼前
      return data.message || '签发失败（HTTP ' + status + '）';
  }
}

function waitText(seconds) {
  const value = Number(seconds) || 0;
  return value >= 60 ? Math.ceil(value / 60) + ' 分钟' : Math.max(1, value) + ' 秒';
}

/**
 * 吊销结果 → 显哪句、什么颜色、要不要重取清单
 *
 * 🔴 not_found 也要重取：它多半是清单过期（那把口令在别处已经撤过），
 * 不刷新的话，撤过的那把还挂在屏幕上说「有效」。
 * write_failed 则恰恰不能重取——盘上还是旧账，刷新只会把同一份旧账再画一遍，
 * 看起来像撤成功了。
 * @param result 吊销端点的响应体
 * @return {{text: string, kind: string, refresh: boolean}}
 */
export function revokeOutcome(result) {
  const ok = !!result.success;
  return {
    text: result.message || (ok ? '已吊销' : '操作失败'),
    kind: ok ? 'ok' : 'err',
    refresh: ok || result.reason === 'not_found'
  };
}
