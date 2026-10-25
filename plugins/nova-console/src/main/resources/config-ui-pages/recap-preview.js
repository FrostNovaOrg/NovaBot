import {api, esc} from './core.js';

let box, active = false, showNicknames = false, selected = '', revision = 0, listRevision = 0;

export function render(section) {
  box = section;
  box.innerHTML = `<style>
    .rp-list{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}
    .rp-list a{display:block;padding:10px 14px;border:1px solid var(--line);border-radius:8px;color:inherit;text-decoration:none}
    .rp-list a[aria-current=page]{border-color:var(--accent);background:var(--soft)}
    .rp-list small,.rp-notes{display:block;color:var(--dim);font-size:var(--fs-sm);line-height:1.7}
    .rp-message{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;font:inherit}
    .rp-card{margin-top:16px}.rp-card h4{margin-bottom:8px}
  </style><h2>小结样稿（试验）</h2>
    <p class="hint">回放最近 5 场已封存直播，只看样稿，不会发消息。</p>
    <div class="rp-list" id="rp-list"></div>
    <label><input type="checkbox" id="rp-names"> 显示昵称</label>
    <div id="rp-content" aria-live="polite"></div>`;
  box.querySelector('#rp-names').addEventListener('change', event => {
    showNicknames = event.target.checked;
    return loadPreview();
  });
}

function current() {
  const match = /^#\/recap-preview\/([A-Za-z0-9_-]+)\/([1-9][0-9]*)(?:\?.*)?$/.exec(location.hash);
  return match ? `${match[1]}/${match[2]}` : '';
}

export async function refresh() {
  if (!/^#\/recap-preview(?:\/|\?|$)/.test(location.hash)) { leave(); return; }
  const next = current();
  if (!active || next !== selected) showNicknames = false;
  active = true;
  selected = next;
  box.querySelector('#rp-names').checked = showNicknames;
  const listVersion = ++listRevision;
  revision++;
  const content = box.querySelector('#rp-content');
  content.textContent = selected ? '正在生成样稿……' : '请选择一位主播。';
  box.querySelector('#rp-list').textContent = '正在读取主播列表……';
  const previewTask = selected ? loadPreview() : Promise.resolve();
  // 两份请求独立返回；生成较慢时仍可先选其他主播。
  try {
    const result = await api('/recap-preview');
    if (!active || listVersion !== listRevision) return;
    if (!result.success) throw new Error(result.message);
    box.querySelector('#rp-list').innerHTML = result.streamers.length ? result.streamers.map(streamer => {
      const key = `${streamer.platform}/${streamer.uid}`;
      return `<a href="#/recap-preview/${esc(key)}"${key === selected ? ' aria-current="page"' : ''}>${esc(streamer.uname)}
        <small>${esc(streamer.sessionCount)} 场 · 最近 ${esc(date(streamer.latestStart))}</small></a>`;
    }).join('') : '<p class="hint">还没有已封存的直播场次。</p>';
  } catch (error) {
    if (active && listVersion === listRevision) box.querySelector('#rp-list').textContent = '读取主播列表没成功，请稍后再试。';
  }
  await previewTask;
}

async function loadPreview() {
  const version = ++revision;
  const content = box.querySelector('#rp-content');
  content.textContent = selected ? '正在生成样稿……' : '请选择一位主播。';
  if (!selected) return;
  try {
    const result = await api(`/recap-preview/${selected}?showNicknames=${showNicknames}`);
    if (!active || version !== revision) return;
    if (!result.success) {
      content.textContent = result.message || '生成样稿没成功，请稍后再试。';
      return;
    }
    content.innerHTML = result.sessions.map(card => `<article class="nv-card hcard rp-card">
      <h3>${esc(date(card.startTime))} ${slot(card.startTime)}场 · ${duration(card.durationSeconds)}</h3>
      <p class="hint">真发出去时，金额跟着这条通道的『金额显示』开关走</p>
      ${textBlock('第 1 档', card.level1)}${textBlock('第 2 档', card.level2)}</article>`).join('');
  } catch (error) {
    if (active && version === revision) content.textContent = '读取样稿没成功，请稍后再试。';
  }
}

function textBlock(title, draft) {
  return `<h4>${title}</h4><div class="rp-message">${esc(draft.message || '这一场没有可用的正文。')}</div>`
    + (draft.notes.length ? `<div class="rp-notes">${draft.notes.map(note => `<p>注：${esc(note)}</p>`).join('')}</div>` : '');
}
function date(start) { return new Date(start).toLocaleDateString('zh-CN', {timeZone: 'Asia/Shanghai'}); }
function slot(start) {
  const hour = Number(new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', hour: 'numeric', hourCycle: 'h23'})
    .formatToParts(new Date(start)).find(part => part.type === 'hour').value);
  return ['凌晨', '上午', '下午', '晚上'][Math.floor(hour / 6)];
}
function duration(seconds) {
  if (!(seconds > 0)) return '未记录时长';
  const minutes = Math.floor(seconds / 60), hours = Math.floor(minutes / 60);
  if (minutes === 0) return '不足 1 分钟';
  return (hours ? `${hours} 小时` : '') + (minutes % 60 ? `${hours ? ' ' : ''}${minutes % 60} 分钟` : '');
}

export function leave() {
  active = false;
  showNicknames = false;
  selected = '';
  revision++;
  listRevision++;
  if (box) {
    box.querySelector('#rp-names').checked = false;
    box.querySelector('#rp-content').textContent = '';
  }
}
