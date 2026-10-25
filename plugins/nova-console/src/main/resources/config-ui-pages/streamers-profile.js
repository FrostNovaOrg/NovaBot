import {api, el} from './core.js';

const PATTERNS = {
  newcomer_speak_return: '新人开口', regulars_absent: '老观众',
  slot_compare: '开播时间', pk_share: 'PK',
};
function time(value) {
  return value == null ? '默认，尚未设置' : new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', hour12: false});
}

// 每次挂载只服务当前主播，离开或换人后到达的回包不再写入界面。
export function mountProfile(host, identity) {
  let active = true;
  const path = '/streamers/' + encodeURIComponent(identity.platform) + '/' + encodeURIComponent(identity.uid) + '/profile';
  const section = el('section', 'sc-card');
  const title = el('h3'); title.textContent = '主播档案'; section.appendChild(title);
  const body = el('div'); section.appendChild(body); host.appendChild(section);
  body.textContent = '正在读取档案…';
  const line = (label, value, at) => {
    const row = el('p'); row.style.whiteSpace = 'pre-wrap';
    row.textContent = label + '：' + value + '（' + time(at) + '）'; body.appendChild(row);
  };
  function render(profile) {
    body.textContent = '';
    if (profile.status === 'TEMPORARILY_UNREADABLE') {
      body.textContent = '档案暂时读不出，本次停发，请稍后重试'; return;
    }
    if (profile.status === 'UNREADABLE') {
      const warning = el('p'); warning.textContent = '档案读不出，已停发。核对后可重置为第 1 档、不暂停，并清空在意和别提的条目。'; body.appendChild(warning);
      const button = el('button', 'nv-btn2'); button.type = 'button'; button.textContent = '档案已核对，恢复'; body.appendChild(button);
      const feedback = el('p'); feedback.setAttribute('role', 'status'); body.appendChild(feedback);
      button.onclick = async () => {
        if (!active || button.disabled) return;
        button.disabled = true; feedback.textContent = '正在恢复…';
        try {
          const result = await api(path + '/recover', {method: 'POST'});
          if (!active) return;
          if (!result?.success || !result.profile) throw new Error('恢复失败');
          render(result.profile);
        } catch {
          if (active) { feedback.textContent = '恢复失败，仍然停发，请稍后重试'; button.disabled = false; }
        }
      };
    }
    line('档位', '第 ' + profile.tier + ' 档', profile.tierSetAt);
    line('是否暂停', profile.paused ? '是' : '否', profile.pauseSetAt);
    const concerns = el('h4'); concerns.textContent = '在意什么'; body.appendChild(concerns);
    if (!profile.concerns?.length) { const empty = el('p'); empty.textContent = '暂无'; body.appendChild(empty); }
    else profile.concerns.forEach(item => line('在意', item.text, item.setAt));
    const exclusions = el('h4'); exclusions.textContent = '别提什么'; body.appendChild(exclusions);
    if (!profile.exclusions?.length) { const empty = el('p'); empty.textContent = '暂无'; body.appendChild(empty); }
    else profile.exclusions.forEach(item => line('别提', PATTERNS[item.type] || '未识别的条目', item.setAt));
  }
  const ready = (async () => {
    try {
      const result = await api(path);
      if (!active) return;
      if (!result?.success || !result.profile) { body.textContent = '档案暂时看不到，请稍后重试'; return; }
      render(result.profile);
    } catch {
      if (active) body.textContent = '档案暂时看不到，请稍后重试';
    }
  })();
  return {ready, destroy() { active = false; section.remove(); }};
}
