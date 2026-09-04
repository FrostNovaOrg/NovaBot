/**
 * 口令框显示／隐藏
 *
 * 默认藏着，点一下揭开，再点藏回去。登录页一口与设置页改口令三栏共用这一份，
 * 不各写一份——抄四份的话，默认态或文案分叉时，四处在屏幕上长得不一样，
 * 而夹具永远只量得着其中一份。
 *
 * 判定是纯函数，不碰 DOM。接线那一层把算出来的 type / 文案挂到元素上。
 * 登录页不取外部资源，这份文件由服务端拼进那张页面，与设置页 import 的是同一份字节。
 */

/** 四处落点。闭集：少一处等于那一处还在各写一份 */
export const PASSWORD_REVEAL_SITES = ['login', 'pwd-current', 'pwd-next', 'pwd-again'];

/**
 * 这一刻口令框该长什么样
 * @param revealed 是否已经揭开
 * @return {{revealed: boolean, inputType: string, buttonLabel: string}}
 */
export function passwordReveal(revealed) {
  const on = !!revealed;
  return {
    revealed: on,
    inputType: on ? 'text' : 'password',
    buttonLabel: on ? '隐藏' : '显示',
  };
}

/**
 * 点一下。再点切回。
 * @param state 上一份，缺省按隐藏算
 * @return 切换后的状态
 */
export function togglePasswordReveal(state) {
  return passwordReveal(!(state && state.revealed));
}

/**
 * 把算出来的显隐挂到一对输入框与按钮上
 * @param input 口令框
 * @param button 显示／隐藏按钮
 */
export function bindPasswordReveal(input, button) {
  if (!input || !button) return;

  let state = passwordReveal(false);
  const paint = () => {
    input.type = state.inputType;
    button.textContent = state.buttonLabel;
    button.setAttribute('aria-pressed', state.revealed ? 'true' : 'false');
    button.setAttribute('aria-label', state.buttonLabel + '口令');
  };

  paint();
  button.addEventListener('click', () => {
    state = togglePasswordReveal(state);
    paint();
  });
}
