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
  const section = el('section', 'sc-card');
  const title = el('h3'); title.textContent = '主播档案'; section.appendChild(title);
  const body = el('div'); section.appendChild(body); host.appendChild(section);
  body.textContent = '正在读取档案…';
  const line = (label, value, at) => {
    const row = el('p'); row.style.whiteSpace = 'pre-wrap';
    row.textContent = label + '：' + value + '（' + time(at) + '）'; body.appendChild(row);
  };
  const ready = (async () => {
    try {
      const result = await api('/streamers/' + encodeURIComponent(identity.platform) + '/' + encodeURIComponent(identity.uid) + '/profile');
      if (!active) return;
      body.textContent = '';
      if (!result?.success || !result.profile) { body.textContent = '档案暂时看不到，请稍后重试'; return; }
      const profile = result.profile;
      if (profile.status === 'UNREADABLE') {
        const warning = el('p'); warning.textContent = '档案读不出，自动发送已暂停。以下是目前能读到的设置。'; body.appendChild(warning);
      }
      line('档位', '第 ' + profile.tier + ' 档', profile.tierSetAt);
      line('是否暂停', profile.paused ? '是' : '否', profile.pauseSetAt);
      const concerns = el('h4'); concerns.textContent = '在意什么'; body.appendChild(concerns);
      if (!profile.concerns?.length) { const empty = el('p'); empty.textContent = '暂无'; body.appendChild(empty); }
      else profile.concerns.forEach(item => line('在意', item.text, item.setAt));
      const exclusions = el('h4'); exclusions.textContent = '别提什么'; body.appendChild(exclusions);
      if (!profile.exclusions?.length) { const empty = el('p'); empty.textContent = '暂无'; body.appendChild(empty); }
      else profile.exclusions.forEach(item => line('别提', PATTERNS[item.type] || '未识别的条目', item.setAt));
    } catch {
      if (active) body.textContent = '档案暂时看不到，请稍后重试';
    }
  })();
  return {ready, destroy() { active = false; section.remove(); }};
}
