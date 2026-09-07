/**
 * 首页「今日」卡的判定
 *
 * 三个数格该写什么、第三格摊不摊得开、明细怎么排怎么截、群名前面加不加平台——
 * 全是纯函数，一个 DOM 也不碰、一个请求也不发。摆在这里而不是渲染代码里，
 * 是因为这几件事在真机上凑齐一次的代价极高：要点出「不限额那一档不画分母」，
 * 得先去线上把配额上限改成 0；而写错的方向是<b>画出一个 3/0 的分数</b>，
 * 使用者会以为今天已经用完了。切成纯函数之后，喂几份回包跑一遍就能逐条对答案
 * （tools/today-model-check.sh）。
 *
 * 本文件里没有任何一个推送平台的标识：群名前缀取自额度接口下发的显示名，
 * 那是适配器登记时自报的人话名。写死一份映射，装第二套推送平台那天前缀就会对不上，
 * 而对不上的方向是把接口标识直接画到屏幕上。
 */

import {esc} from './core.js';

/**
 * 「今日」第三格：账号维度的 @全体成员 已用
 * <p>
 * 数字取自 /api/at-all/quota。无机器人或接口失败时写「—」，不编一个 0/10——
 * 0 看起来像今天一次都没用，而「—」说的是这台机器此刻无从谈起。
 * {@code limited} 为假时不画分母：上限是 0 或负数在配额服务里都是「不限」，
 * 画成 3/0 会让人以为今天已经用完了。
 * @param quota /api/at-all/quota 回包；没有或失败时传 null
 * @return {{value: string, label: string, details: object[], more: number}}
 */
export function atAllTile(quota) {
  const empty = {value: '—', label: '@全体成员 已用', details: [], more: 0};
  if (!quota || quota.success === false) return empty;

  const bots = Array.isArray(quota.bots) ? quota.bots : [];
  if (!bots.length) return empty;

  const used = bots.reduce((n, item) => n + Number(item.used || 0), 0);
  const capped = bots.every(item => item.limited);
  const limit = bots.reduce((n, item) => n + (item.limited ? Number(item.limit || 0) : 0), 0);
  const value = capped ? used + '/' + limit : String(used);

  const sessions = Array.isArray(quota.sessions) ? quota.sessions.slice() : [];
  sessions.sort((a, b) => Number(b.used || 0) - Number(a.used || 0));
  const shown = sessions.slice(0, 5);
  const kinds = new Set(sessions.map(item => item.platform || '').filter(Boolean));
  const named = kinds.size >= 2;
  return {
    value,
    label: '@全体成员 已用',
    details: shown.map(item => {
      const rowUsed = Number(item.used || 0);
      const platform = item.platform || '';
      const label = item.platformName || platform;
      const prefix = named && platform ? label + ' 群 ' : '群 ';
      return {
        platform,
        num: item.num,
        used: rowUsed,
        limit: Number(item.limit || 0),
        limited: !!item.limited,
        text: item.limited ? rowUsed + '/' + Number(item.limit || 0) : String(rowUsed),
        who: prefix + item.num,
      };
    }),
    more: Math.max(0, sessions.length - shown.length),
  };
}

/**
 * 今日第三格的 HTML。expanded 只在有明细时有意义。
 * <p>
 * 有明细才是按钮（能开合、带 aria-expanded）；没明细与旁边两格一样是普通格，
 * 点了不会摊开任何东西。
 * @param tile {@link atAllTile} 的返回值
 * @param expanded 此刻是否摊开
 * @return {string}
 */
export function todayAtAllMarkup(tile, expanded) {
  const cell = tile || {value: '—', label: '@全体成员 已用', details: [], more: 0};
  const details = cell.details || [];
  const rows = details.map(item =>
    '<div class="stat-row"><span>' + esc(item.who || ('群 ' + item.num)) + '</span><span>'
    + esc(item.text) + '</span></div>').join('')
    + (cell.more ? '<div class="stat-more">还有 ' + esc(cell.more) + ' 个群</div>' : '');
  const inner = '<div class="stat-v">' + esc(cell.value) + '</div>'
    + '<div class="stat-l">' + esc(cell.label) + '</div>'
    + (rows ? '<div class="stat-drop">' + rows + '</div>' : '');
  if (!details.length) {
    return '<div class="stat">' + inner + '</div>';
  }
  return '<button class="stat stat-exp' + (expanded ? ' open' : '')
    + '" type="button" id="today-atall" aria-expanded="' + (expanded ? 'true' : 'false') + '">'
    + inner + '</button>';
}

/**
 * 这张卡此刻该显示什么
 *
 * 前两格取自 /api/status 的 today 块，缺席时写 0——那两个数问的是「今天发出去多少」，
 * 一台今天什么都没推的机器上它们确实是 0，与额度那一格的「—」不是一回事：
 * 后者说的是「这台机器此刻无从谈起」。
 * @param status /api/status 回包
 * @param quota /api/at-all/quota 回包；没有或失败时传 null
 * @return {{sent: number, failed: number, atAll: object, pushOn: boolean}}
 */
export function todayModel(status, quota) {
  const state = status || {};
  return {
    sent: (state.today && state.today.sent) || 0,
    failed: (state.today && state.today.failed) || 0,
    atAll: atAllTile(quota),
    // 三态里只有明确的 false 才算暂停：缺这一栏（回包还没到）不许读成「已暂停」，
    // 那会让开关先画成「恢复推送」再自己变回去
    pushOn: state.pushEnabled !== false,
  };
}
