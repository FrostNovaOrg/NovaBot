/** 设置页成员管理。账号权限与敏感操作的校验由接口执行。 */
import {api, el} from './core.js';
import {ask} from './confirm.js';

let memberAuth = {};
const LOCAL_ID = 'local-admin';
const FRESH_LOCAL = '这一步要先用本地方式重新登录';
const NEW_MEMBER_HELP = '在账户中心建好账号后，让对方用统一登录进来一次，这里就会出现一行待授权。';

export function setMemberAuthState(state) {
  memberAuth = {...state};
}

export function memberGroup() {
  if (memberAuth.role !== 'ADMIN') return null;
  const box = el('section', 'group setgrp members-group');
  box.dataset.grp = 'members';
  box.dataset.ui = 'members';
  renderMembers(box, memberAuth);
  return box;
}

function text(parent, tag, value, cls = '') {
  const node = el(tag, cls);
  node.textContent = value;
  parent.appendChild(node);
  return node;
}

function button(parent, label, action, callback) {
  const node = text(parent, 'button', label, 'nv-btn2');
  node.type = 'button';
  node.dataset.action = action;
  node.addEventListener('click', callback);
  return node;
}

function failureMessage(error) {
  const message = error.message;
  return message.includes(FRESH_LOCAL) ? message + '。退出后用口令重新登录一次。' : message;
}

async function accountApi(path, method = 'POST', body) {
  const options = {method};
  if (body !== undefined) {
    options.headers = {'Content-Type': 'application/json'};
    options.body = JSON.stringify(body);
  }
  const result = await api('/accounts' + path, options);
  if (!result.success) throw new Error(result.message || '账号操作失败');
  return result;
}

const streamerKey = user => user.platform + ':' + user.uid;
const identity = account => account.id === LOCAL_ID ? '本地管理员' : account.role === 'ADMIN' ? '管理员' : '成员';
const statusText = status => ({PENDING: '待授权', ENABLED: '已启用', DISABLED: '已停用'}[status] || status);

/** 昵称不可用时保留平台和 uid；包括停用的主播，不根据 enabled 过滤。 */
async function streamerOptions() {
  const result = await api('/datasource/users');
  if (!result.success || !Array.isArray(result.users)) throw new Error(result.message || '无法读取主播列表');
  return Promise.all(result.users.map(async user => {
    let name = '';
    try {
      const known = await api('/streamer/name?platform=' + encodeURIComponent(user.platform)
        + '&uid=' + encodeURIComponent(String(user.uid)));
      if (known.success) name = known.uname || '';
    } catch (error) {
      // 名字不可用不影响授权，仍以平台和 uid 展示。
    }
    return {key: streamerKey(user), name};
  }));
}

function streamerEditor(parent, account, options) {
  const fieldset = el('fieldset', 'member-streamers');
  text(fieldset, 'legend', '指定主播');
  const all = [...options];
  // 已从推送配置移走的主播仍可取消授权，保存其他选择时也不会悄悄丢掉它。
  for (const key of account.streamers || []) {
    if (!all.some(option => option.key === key)) all.push({key, name: ''});
  }
  for (const option of all) {
    const label = el('label');
    const input = el('input');
    input.type = 'checkbox';
    input.value = option.key;
    input.checked = (account.streamers || []).includes(option.key);
    label.appendChild(input);
    text(label, 'span', option.name ? option.name + ' · ' + option.key : option.key);
    fieldset.appendChild(label);
  }
  if (!all.length) text(fieldset, 'p', '推送配置里还没有主播。', 'hint');
  parent.appendChild(fieldset);
  return fieldset;
}

function accountRow(tbody, account, options, optionsReady, state, refresh) {
  const row = el('tr');
  row.dataset.accountId = account.id;
  const who = el('td');
  text(who, 'strong', account.displayName || account.email || account.id);
  text(who, 'div', account.email || '—', 'hint');
  row.appendChild(who);
  text(row, 'td', identity(account));
  const status = text(row, 'td', statusText(account.status));
  if (account.status === 'PENDING') text(status, 'p', '对方已经用统一登录进来过一次，等你授权。', 'hint');
  const assigned = (account.streamers || []).map(key => options.find(option => option.key === key)?.name || key);
  text(row, 'td', account.role === 'MEMBER' ? assigned.join('、') || '未指定主播' : '—');
  const lastLogin = el('td');
  if (account.lastLoginAt) {
    const time = text(lastLogin, 'time', new Date(account.lastLoginAt).toLocaleString());
    time.dateTime = account.lastLoginAt;
  } else text(lastLogin, 'span', '尚未登录');
  row.appendChild(lastLogin);
  const actions = el('td', 'member-actions');
  const result = el('p', 'member-result');
  result.setAttribute('role', 'status');
  result.setAttribute('aria-live', 'polite');
  let busy = false;
  const run = async operation => {
    if (busy) return;
    busy = true;
    result.textContent = '';
    const controls = Array.from(row.querySelectorAll('button'));
    const prior = controls.map(node => node.disabled);
    controls.forEach(node => { node.disabled = true; });
    try {
      if (await operation() !== false) await refresh();
    } catch (error) {
      result.textContent = failureMessage(error);
    } finally {
      busy = false;
      controls.forEach((node, index) => { node.disabled = prior[index]; });
    }
  };
  const path = '/' + encodeURIComponent(account.id);
  if (account.id === LOCAL_ID) {
    if (state.accountId === LOCAL_ID) {
      if (account.issuer && account.subject) {
        button(actions, '解除绑定', 'unbind', () => run(() => accountApi('/local/binding', 'DELETE')));
      } else {
        button(actions, '绑定我的统一登录身份', 'bind', () => run(async () => {
          const response = await accountApi('/local/bind');
          if (!response.authorizationUrl) throw new Error('未收到统一登录地址');
          window.location.href = response.authorizationUrl;
          return false;
        }));
      }
    }
  } else {
    if (account.role === 'MEMBER') {
      const editor = streamerEditor(actions, account, options);
      editor.disabled = !optionsReady;
      const selected = () => Array.from(editor.querySelectorAll('input:checked'), input => input.value);
      if (account.status === 'ENABLED') {
        editor.hidden = true;
        const edit = button(actions, '改指定主播', 'edit-streamers', () => {
          editor.hidden = false;
          edit.hidden = true;
          save.hidden = false;
        });
        edit.disabled = !optionsReady;
        const save = button(actions, '保存指定主播', 'save-streamers', () => run(() => accountApi(path + '/streamers', 'POST', {streamers: selected()})));
        save.hidden = true;
      } else {
        const enable = button(actions, account.status === 'PENDING' ? '授权并启用' : '启用', 'enable', () => run(async () => {
          const streamers = selected();
          if (!streamers.length && !await ask({title: '启用这位成员？', body: '没指定主播，对方进来什么都看不到。'})) return false;
          await accountApi(path + '/streamers', 'POST', {streamers});
          await accountApi(path + '/enable');
        }));
        enable.disabled = !optionsReady;
      }
    }
    if (account.role === 'ADMIN' && ['PENDING', 'DISABLED'].includes(account.status)) {
      button(actions, account.status === 'PENDING' ? '授权并启用' : '启用', 'enable', () => run(() => accountApi(path + '/enable')));
    }
    if (account.status === 'ENABLED') {
      button(actions, '停用', 'disable', () => run(async () => {
        if (!await ask({title: '停用这个账号？', body: '对方会立刻被踢下线，停用后不能登录控制台。'})) return false;
        await accountApi(path + '/disable');
      }));
    }
    if (account.role === 'MEMBER' && ['PENDING', 'DISABLED'].includes(account.status)) {
      button(actions, '删除', 'delete', () => run(async () => {
        if (!await ask({title: '删除这位成员？', body: '账号及指定主播将从控制台删除。'})) return false;
        await accountApi(path, 'DELETE');
      }));
    }
    button(actions, account.role === 'MEMBER' ? '改为管理员' : '改为成员', 'role', () => run(() =>
      accountApi(path + '/role', 'POST', {role: account.role === 'MEMBER' ? 'ADMIN' : 'MEMBER'})));
  }
  actions.appendChild(result);
  row.appendChild(actions);
  tbody.appendChild(row);
}

export async function renderMembers(box, state) {
  box.replaceChildren();
  if (state.role !== 'ADMIN') return;
  text(box, 'h2', '成员');
  if (!state.oidcEnabled) {
    text(box, 'p', '在设置页的统一登录配置里填好账户中心地址、客户端编号和密钥，即可开启统一登录并管理成员。', 'hint');
    return;
  }
  const loading = text(box, 'p', '正在读取成员…', 'hint');
  try {
    const result = await accountApi('', 'GET');
    let options = [];
    let optionsReady = true;
    try { options = await streamerOptions(); } catch (error) {
      optionsReady = false;
      text(box, 'p', error.message, 'member-result');
    }
    loading.hidden = true;
    const intro = el('div', 'members-intro');
    text(intro, 'p', NEW_MEMBER_HELP, 'hint');
    if (result.managementUri) {
      button(intro, '新建成员', 'new-member', () => window.open(result.managementUri, '_blank', 'noopener,noreferrer'));
    } else {
      text(intro, 'p', '在设置页的统一登录配置里填上管理地址，「新建成员」按钮就能直接跳过去。', 'hint');
    }
    box.appendChild(intro);
    const scroll = el('div', 'members-table-wrap');
    const table = el('table', 'members-table');
    const head = el('thead');
    const titles = el('tr');
    for (const title of ['显示名 / 邮箱', '身份', '状态', '指定主播', '最近登录', '操作']) {
      text(titles, 'th', title).setAttribute('scope', 'col');
    }
    head.appendChild(titles);
    table.appendChild(head);
    const body = el('tbody');
    const accounts = [...(result.accounts || [])].sort((a, b) => Number(b.status === 'PENDING') - Number(a.status === 'PENDING'));
    for (const account of accounts) {
      accountRow(body, account, options, optionsReady, state, () => renderMembers(box, state));
    }
    table.appendChild(body);
    scroll.appendChild(table);
    box.appendChild(scroll);
  } catch (error) {
    loading.textContent = failureMessage(error);
  }
}
