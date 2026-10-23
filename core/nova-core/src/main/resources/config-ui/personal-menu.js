/** 当前身份与退出入口。 */
import {api, hasUnsavedChanges} from './core.js';
import {ask} from './confirm.js';

/**
 * 手机顶栏上的「我的」：点一下摊开使用说明、账号与退出，再点、按 Esc 或点别处收起
 *
 * 宽屏上这颗按钮不显示、面板照常摊在侧栏底部，开合只在窄屏上看得出来。
 * 页里缺这两块（比如只建了一部分页面的测试）时什么也不做。
 * @param toggle 「我的」按钮
 * @param panel 收进去的那一块
 */
export function bindMineMenu(toggle, panel) {
  if (!toggle || !panel) return;
  const setOpen = open => {
    panel.classList.toggle('open', open);
    toggle.setAttribute('aria-expanded', String(open));
  };
  toggle.addEventListener('click', () => setOpen(!panel.classList.contains('open')));
  // 点开后焦点还在按钮上，所以按钮与面板都要听 Esc。不挂在 document 上：
  // 退出时弹的确认框也用 Esc，它关掉后要把焦点还给面板里那颗「退出」
  const closeOnEscape = event => {
    if (event.key !== 'Escape' || !panel.classList.contains('open')) return;
    setOpen(false);
    toggle.focus();
  };
  toggle.addEventListener('keydown', closeOnEscape);
  panel.addEventListener('keydown', closeOnEscape);
  document.addEventListener('click', event => {
    if (!panel.classList.contains('open')) return;
    if (toggle.contains(event.target) || panel.contains(event.target)) return;
    setOpen(false);
  });
}

export function renderPersonalMenu(box, state) {
  box.hidden = !state.authenticated;
  if (!state.authenticated) return;
  const summary = box.querySelector('#personal-name');
  summary.textContent = state.accountId === 'local-admin' ? '本地管理员' : (state.displayName || state.email || state.accountId);
  const account = box.querySelector('#account-center');
  account.hidden = !(state.loginMethod === 'oidc' && state.managementUri);
  if (!account.hidden) {
    account.href = state.managementUri;
  } else account.removeAttribute('href');
  const logout = box.querySelector('#logout');
  const result = box.querySelector('#personal-result');
  result.textContent = '';
  logout.onclick = async () => {
    if (hasUnsavedChanges() && !await ask({title: '退出控制台？', body: '有未保存的改动，退出后将丢失。'})) return;
    logout.disabled = true;
    try {
      const response = await api('/auth/logout', {method: 'POST'});
      if (!response.success) throw new Error(response.message || '退出失败');
      window.location.reload();
    } catch (error) {
      result.textContent = error.message;
    } finally {
      logout.disabled = false;
    }
  };
}
