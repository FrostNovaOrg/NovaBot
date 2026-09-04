/**
 * 危险确认弹层的判定：打开／取消／确认，以及回调只调一次
 *
 * 全是纯函数，一个 DOM 也不碰——因此它们可以被单独喂夹具跑出读数来。
 * 混在渲染代码里的话，「连点两下确认」这种事就只能靠人在页面上手点，
 * 而手点测不出「弹着的时候再点取消会不会把同一件事办两遍」。
 *
 * 原生 confirm() 是同步的，这两件事由浏览器保证。换成自绘弹层之后必须自己守。
 * Esc＝取消、关掉以后把焦点还回触发钮，同理：浏览器弹窗自带，自绘之后要自己记。
 */

/**
 * 收起、还没问过
 * @return {{status: string, title: string, body: string, accepted: boolean|null, calls: number, trigger: *}}
 */
export function idle() {
  return {status: 'idle', title: '', body: '', accepted: null, calls: 0, trigger: null};
}

/**
 * 打开一层。标题与后果原样带上，缺的不编字。
 * @param _state 上一份（打开不读它，每次都是新的一层）
 * @param spec {title, body, trigger}
 * @return 打开态
 */
export function open(_state, spec) {
  const s = spec || {};
  return {
    status: 'open',
    title: s.title == null ? '' : String(s.title),
    body: s.body == null ? '' : String(s.body),
    accepted: null,
    calls: 0,
    trigger: s.trigger == null ? null : s.trigger,
  };
}

/**
 * 取消或确认。已经不在打开态时原样返回，回调也不调。
 * @param state 当前
 * @param accepted 确认则为 true，取消为 false
 * @param onDone 第一次结算时调一次，参数是 accepted
 * @return 结算后的状态，或原对象
 */
export function settle(state, accepted, onDone) {
  if (!state || state.status !== 'open') {
    return state;
  }
  const ok = !!accepted;
  if (typeof onDone === 'function') {
    onDone(ok);
  }
  return {
    status: 'done',
    title: state.title,
    body: state.body,
    accepted: ok,
    calls: 1,
    trigger: state.trigger,
  };
}

/**
 * 打开时按 Esc 等于取消。别的键原样返回，已经收掉或还没打开时也不调回调。
 * @param state 当前
 * @param key 键盘事件的 key
 * @param onDone 第一次结算时调一次，参数是 accepted
 * @return 结算后的状态，或原对象
 */
export function keydown(state, key, onDone) {
  if (!state || state.status !== 'open') {
    return state;
  }
  if (key !== 'Escape') {
    return state;
  }
  return settle(state, false, onDone);
}
