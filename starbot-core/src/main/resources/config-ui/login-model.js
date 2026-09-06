/**
 * 登录页的判定：摆什么、折什么、禁什么、锁定那句话怎么写
 *
 * 这几件事全是纯函数，一个 DOM 也不碰——因此它们可以被单独喂夹具跑出读数来。
 * 混在渲染代码里的话，八格里只有使用者日常见到的那一格测得着，
 * 而「锁定中」那一列要先连错五次口令才看得见。
 *
 * <h2>这份文件是被<b>拼进</b>登录页的，不是被它 import 的</h2>
 *
 * 登录页是一张不取任何外部资源的独立页面：未登录时由安全过滤器直接吐出来，
 * 此刻除了登录那几条接口之外，控制台的一切（包括 /config/assets 下的脚本）都还关着门。
 * 因此服务端在发出那张页面时把本文件的内容原样拼进它的 module 脚本里，
 * 见 ConfigUiLoginPage。<b>两处是同一份字节</b>：抄一份到 login.html 里的话，
 * 夹具量的就不再是页面上真正跑着的那一份了。
 */

/**
 * 剩余锁定时长写成人话
 *
 * 🔴 秒数必须是<b>传进来的实值</b>，不许写死一个「15 分钟」——锁定时长会随反复触发翻倍，
 * 而写死的那个数字与真实剩余时间无关，两者在屏幕上长得一模一样。
 *
 * 那句「输对也会被拒」不能省：锁定期内输对的口令<b>也</b>被拒，此时若只说「登录失败」，
 * 使用者会以为是自己记错了，转头去把一个根本没问题的口令改掉。
 * @param seconds 还要等多少秒，0 或负数表示没有锁定
 * @return {string} 文案，没有锁定时为空串
 */
export function lockText(seconds) {
  const total = Math.floor(Number(seconds) || 0);
  if (total <= 0) return '';

  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  // 整分钟不拖一个「0 秒」，不足一分钟不写「0 分」——两者都会让人多读一遍才算得出还要等多久
  const span = minutes === 0 ? rest + ' 秒'
    : (rest === 0 ? minutes + ' 分' : minutes + ' 分 ' + rest + ' 秒');

  return '连续失败太多次，你这个来源被暂时锁定，还要等 ' + span
    + '。锁定期内即使输对也会被拒，这不代表口令错了，别急着去改密码。';
}

/**
 * 登录页此刻该长什么样
 *
 * 三条规则各管一维，互不牵连：
 * <ul>
 *   <li><b>登记过通行密钥</b>——摆出主按钮，口令那条路折起来成为第二条路。
 *       没登记过就一个字也不提：一个点了必然失败的按钮，比没有这个按钮更让人不知所措</li>
 *   <li><b>二次验证开着</b>——出 6 位框。它只管口令登录这条路，
 *       通行密钥那条路不经二次验证，因此上面那个按钮的显示与这一位无关</li>
 *   <li><b>这个来源锁着</b>——整张表单禁用，通行密钥那条路<b>也一样</b>：
 *       锁定按来源计而不按凭据种类计，各算各的话，正在被爆破口令的地址可以转头去猜通行密钥</li>
 * </ul>
 * @param state 登录状态，取自 /api/auth/state 再补上「这台机器登记过通行密钥没有」
 * @return {{passkey: boolean, passwordFolded: boolean, code: boolean, locked: boolean,
 *   disabled: boolean, lockText: string}} 显隐与文案
 */
export function loginView(state) {
  const s = state || {};
  const seconds = Math.floor(Number(s.lockedSeconds) || 0);
  const locked = seconds > 0;
  const passkey = !!s.hasPasskey;

  return {
    passkey,
    passwordFolded: passkey,
    code: !!s.totpRequired,
    locked,
    disabled: locked,
    lockText: lockText(seconds),
  };
}

/**
 * 锁定时哪几颗控件该禁
 *
 * 返回表的键就是页面上那五颗控件的 id，闭集：少一项等于那一颗锁定期仍能点。
 * 眼睛漏写的那一版，锁定期仍能把口令揭开。paint 只按表挂 disabled，
 * 不在这里判谁该禁——判在 {@link loginView}。
 * @param view {@link loginView} 的返回值
 * @return {{passkey: boolean, password: boolean, 'password-reveal': boolean,
 *   code: boolean, submit: boolean}}
 */
export function loginControlState(view) {
  const on = !!(view && view.disabled);
  return {
    passkey: on,
    password: on,
    'password-reveal': on,
    code: on,
    submit: on,
  };
}
