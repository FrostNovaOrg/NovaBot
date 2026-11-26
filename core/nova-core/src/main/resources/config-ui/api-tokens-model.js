/**
 * 访问令牌页的判定：有效期选项、状态说明、排序、签发请求体
 *
 * 全是纯函数，一个 DOM 也不碰，可以单独喂夹具跑。视图接线在 api-tokens.js。
 */

/** 有效期选项；days 为 null 是不过期。默认选中的是 90 天 */
export const VALIDITY = [
  {days: null, label: '不过期'},
  {days: 30, label: '30 天'},
  {days: 90, label: '90 天'},
  {days: 365, label: '365 天'},
];

export const DEFAULT_VALIDITY = 90;

/** 列表里「状态」一格的字 */
export function stateText(token) {
  if (token.state === 'revoked') return '已吊销';
  if (token.state === 'expired') return '已过期';
  return '有效';
}

/** 有效的在上，同一档里新签的在上：要吊销的多半是刚签错的那一把 */
export function sortTokens(tokens) {
  const rank = token => (token.state === 'active' ? 0 : 1);
  return (tokens || []).slice().sort((a, b) => rank(a) - rank(b) || (b.issuedAt || 0) - (a.issuedAt || 0));
}

/** 所属账号的显示名；本地管理员与已删除的账号由服务端给好名字。停用的账号注一句，那把令牌此刻用不了 */
export function ownerText(token) {
  const owner = token.owner || {};
  const status = owner.status === 'DISABLED' ? '（已停用）' : '';
  return (owner.displayName || owner.id || '—') + status;
}

/** 范围标识换成中文名；登记表里找不到的（插件卸了）原样显示标识 */
export function scopeText(ids, scopes) {
  if (!ids || !ids.length) return '仅自查';
  const names = new Map((scopes || []).map(scope => [scope.id, scope.name]));
  return ids.map(id => names.get(id) || id).join('、');
}

/**
 * 签发请求体
 * @param name 名字（已去首尾空白）
 * @param scopes 勾选的范围标识
 * @param days 有效期天数，空串或 null 为不过期
 */
export function issueBody(name, scopes, days) {
  const value = days === '' || days === null || days === undefined ? null : Number(days);
  return {name, scopes: scopes.slice(), expiresInDays: value};
}

/** 时间戳（毫秒）印成年月日时分；没有为「—」 */
export function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

/** 时间戳（毫秒）只印年月日；没有为「—」。签发、到期那两格到日就够，表格才放得下吊销按钮 */
export function fmtDate(ms) {
  return ms ? fmtTime(ms).slice(0, 10) : '—';
}

/** 到期一格：不过期写「不过期」 */
export function expiryText(token) {
  return token.expiresAt ? fmtDate(token.expiresAt) : '不过期';
}
