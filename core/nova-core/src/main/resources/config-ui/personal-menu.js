/** 当前身份与退出入口。 */
import {api, hasUnsavedChanges} from './core.js';
import {ask} from './confirm.js';

export function renderPersonalMenu(box, state) {
  box.hidden = !state.authenticated;
  if (!state.authenticated) return;
  const summary = box.querySelector('#personal-name');
  summary.textContent = state.accountId === 'local-admin' ? '本地管理员' : state.displayName;
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
