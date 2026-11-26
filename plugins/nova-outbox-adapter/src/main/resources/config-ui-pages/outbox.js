import {api, CONSOLE, el, loadingHtml, say} from './core.js';
import {ask} from './confirm.js';
import {store} from './store.js';

const states = {PENDING: '待发', SENDING: '发送中', SENT: '已发', FAILED: '没发成', SKIPPED: '跳过',
  UNKNOWN: '结果不明', EXPIRED: '过期未发', LIMITED: '超限未入箱', VOID: '已作废'};
const kinds = {LIVE_ON: '开播通知', LIVE_OFF: '下播通知', DYNAMIC: '动态', LIVE_REPORT: '直播报告',
  REWARD_DIGEST: '下播打赏播报'};
const retryable = ['FAILED', 'UNKNOWN', 'EXPIRED'];
const counted = ['PENDING', 'SENDING', ...retryable];
const guide = '先请管理员登记一个外发会话，再给主播添加推送目标，去向选「外发」。通知会存进这里，由另一台设备上的程序取走并发出去。';
const isAdmin = () => store.role === 'ADMIN';
const current = () => /^#\/?outbox(?:[/?]|$)/.test(location.hash);
let root, sectionName, timer, reading, previousData;
let cursors = [0];

function label(tag, value, cls) {
  const node = el(tag, cls);
  node.textContent = value;
  return node;
}

function button(title, click, cls = 'ghost sm') {
  const node = label('button', title, cls);
  node.type = 'button';
  node.addEventListener('click', click);
  return node;
}

function part(selector) { return root.querySelector(selector); }

export function render(section) {
  root = section;
  root.innerHTML = `
    <style>
      #page-outbox {overflow-wrap:anywhere}
      .ob-head,.ob-toolbar,.ob-actions,.ob-pages,.ob-tabs {display:flex;align-items:center;gap:10px;flex-wrap:wrap}
      .ob-head,.ob-toolbar {justify-content:space-between;margin-bottom:16px}
      .ob-head h2,.ob-toolbar h3,.ob-conv h4 {margin:0}
      .ob-tabs {padding:3px;border:1px solid var(--line);border-radius:var(--r-pill);gap:2px;background:var(--surface)}
      .ob-tabs a {padding:5px 15px;border-radius:inherit;text-decoration:none;color:var(--dim)}
      .ob-tabs a[aria-current] {background:var(--soft);color:var(--accent-text);font-weight:600}
      .ob-counts {display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;margin-bottom:16px}
      .ob-counts .card {padding:12px 16px}
      .ob-counts small,.ob-caption,.ob-meta {font-size:var(--fs-sm);color:var(--dim)}
      .ob-panel {padding:18px}
      .ob-toolbar select {margin-left:8px}
      .ob-item,.ob-conv {padding:12px 0;border-top:1px solid var(--line)}
      .ob-item summary {cursor:pointer;display:grid;grid-template-columns:1fr 1.25fr 1fr auto;align-items:start;gap:6px 14px}
      .ob-item summary::-webkit-details-marker {display:none}
      .ob-item summary:before {content:'▸';grid-column:1;grid-row:1;color:var(--dim)}
      .ob-item details[open] summary:before {content:'▾'}
      .ob-time {grid-row:1;grid-column:1;padding-left:18px;font-variant-numeric:tabular-nums}
      .ob-caption,.ob-meta {display:block;margin-top:3px}
      .ob-result {grid-column:2/-1;color:var(--dim);font-size:var(--fs-sm);white-space:pre-line}
      .ob-state {justify-self:start;border-radius:var(--r-pill);padding:2px 9px;background:var(--ground);font-size:var(--fs-sm)}
      .ob-state[data-tone=warn] {color:var(--warn);background:var(--warnsoft)}
      .ob-state[data-tone=ok] {color:var(--ok);background:var(--oksoft)}
      .ob-state[data-tone=err] {color:var(--err);background:var(--errsoft)}
      .ob-content {margin:12px 0;padding:12px 15px;border-left:2px solid var(--line);background:var(--ground)}
      .ob-text {white-space:pre-wrap}
      .ob-content a,.ob-content button {display:block;width:fit-content;margin:6px 0}
      .ob-content button {max-width:min(208px,100%);background:var(--surface);padding:3px;border:1px solid var(--line)}
      .ob-content img {display:block;max-width:100%;max-height:118px}
      .ob-actions {margin:8px 0 0;justify-content:flex-end}
      .ob-pages {justify-content:flex-end;margin-top:12px}
      .ob-form {margin:16px 0;display:grid;gap:10px;max-width:32rem}
      .ob-form label {display:grid;gap:4px}
      .ob-form input {min-width:0;background:var(--ground);color:var(--text);border:1px solid var(--line);border-radius:var(--r-ctl);padding:8px;font:inherit}
      .ob-error {color:var(--err)}
      .ob-image {max-width:95vw;max-height:92vh;background:var(--surface);color:var(--text);border:1px solid var(--line);border-radius:var(--r-card)}
      .ob-image::backdrop {background:var(--overlay)}
      .ob-image img {display:block;max-width:none}
      .ob-image div {overflow:auto;max-height:75vh;max-width:88vw;margin-top:10px}
      @media(max-width:680px) {
        .ob-counts {grid-template-columns:repeat(6,1fr);gap:6px}
        .ob-counts .card {grid-column:span 2;padding:8px}
        .ob-counts .card:nth-child(-n+2) {grid-column:span 3}
        .ob-item summary {grid-template-columns:repeat(2,minmax(0,1fr));gap:5px 10px}
        .ob-time {grid-column:1;grid-row:1}
        .ob-state {grid-column:2;grid-row:1;justify-self:end}
        .ob-result {grid-column:1/-1}
        .ob-panel {padding:12px}
      }
    </style>
    <header class="ob-head"><h2>外发箱</h2><nav aria-label="外发箱分区" class="ob-tabs">
      <a href="#/outbox">记录</a><a href="#/outbox/conversations">会话</a></nav></header>
    <div class="ob-error" role="alert"></div>
    <section class="ob-records">
      <div class="ob-counts"></div>
      <div class="nv-card ob-panel"><header class="ob-toolbar"><h3>外发记录</h3>
        <label>状态<select><option value="">全部状态</option></select></label></header>
        <div class="ob-rows"></div><div class="ob-pages"></div></div>
    </section>
    <section class="nv-card ob-panel ob-directory" hidden>
      <header class="ob-toolbar"><h3>外发会话</h3></header>
      <div class="ob-editor"></div><div class="ob-conversations"></div>
    </section>
    <dialog class="ob-image" aria-label="图片原图"><button class="ghost sm" type="button">关闭原图</button><div></div></dialog>`;
  const filter = part('select');
  for (const [key, value] of Object.entries(states)) filter.add(new Option(value, key));
  filter.onchange = () => { cursors = [0]; changePage(); };
  if (isAdmin()) part('.ob-directory header').append(button('新增会话', () => editConversation(), 'primary sm'));
  const dialog = part('dialog');
  dialog.querySelector('button').onclick = () => dialog.close();
  dialog.onclose = () => dialog.querySelector('div').replaceChildren();
  document.addEventListener('visibilitychange', visibility);
}

function stopReading() {
  clearTimeout(timer);
  reading?.abort();
  reading = null;
}

export function leave() {
  stopReading();
  sectionName = null;
  part('.ob-editor').replaceChildren();
  if (part('dialog').open) part('dialog').close();
}

function visibility() {
  if (document.hidden) stopReading();
  else if (current()) refresh();
}

export function refresh() {
  if (!root || !current() || document.hidden) return;
  const next = /^#\/?outbox\/conversations(?:[/?]|$)/.test(location.hash) ? 'conversations' : 'records';
  if (sectionName !== next) {
    sectionName = next;
    if (part('dialog').open) part('dialog').close();
    part('.ob-records').hidden = sectionName !== 'records';
    part('.ob-directory').hidden = sectionName !== 'conversations';
    root.querySelectorAll('.ob-tabs a').forEach(a => a.removeAttribute('aria-current'));
    part('.ob-tabs a' + (sectionName === 'records' ? ':first-child' : ':last-child')).setAttribute('aria-current', 'page');
    part('.ob-editor').replaceChildren();
    return changePage();
  }
  if (!reading) return load();
}

function changePage() {
  stopReading();
  previousData = null;
  part(sectionName === 'records' ? '.ob-rows' : '.ob-conversations').innerHTML = loadingHtml();
  part('.ob-pages').replaceChildren();
  return load();
}

async function outbox(path, options) {
  const result = await api('/outbox' + path, options);
  if (!result.success) throw new Error(result.message || '请求失败，请重试');
  return result;
}

async function load() {
  if (document.hidden || !current() || !sectionName) return;
  stopReading();
  const request = new AbortController();
  reading = request;
  const options = {signal: request.signal};
  try {
    if (sectionName === 'records') {
      const query = new URLSearchParams({beforeId: cursors.at(-1)});
      if (part('select').value) query.set('status', part('select').value);
      const [summary, page] = await Promise.all([outbox('/summary', options), outbox('/records?' + query, options)]);
      if (request.signal.aborted) return;
      showCounts(summary.counts);
      const snapshot = JSON.stringify(page);
      if (previousData !== snapshot) {
        showRecords(page);
        previousData = snapshot;
      }
    } else {
      const result = await outbox('/conversations', options);
      if (request.signal.aborted) return;
      const snapshot = JSON.stringify(result.conversations);
      if (previousData !== snapshot) {
        showConversations(result.conversations);
        previousData = snapshot;
      }
    }
    part('.ob-error').textContent = '';
  } catch (error) {
    if (!request.signal.aborted) {
      part('.ob-error').textContent = '读取外发箱失败：' + error.message;
      root.querySelector('.content-loading')?.remove();
    }
  } finally {
    if (reading === request) {
      reading = null;
      timer = setTimeout(load, 30000);
    }
  }
}

function showCounts(counts) {
  part('.ob-counts').replaceChildren(...counted.map(key => {
    const card = el('div', 'card');
    card.append(label('div', counts[key], 'n'), label('div', states[key], 'l'));
    card.append(label('small', retryable.includes(key) ? '最近 24 小时' : '当前'));
    return card;
  }));
}

function time(value) {
  return new Date(value).toLocaleString('zh-CN', {month: '2-digit', day: '2-digit', hour: '2-digit',
    minute: '2-digit', hour12: false});
}

function showRecords(page) {
  const rows = part('.ob-rows');
  const open = new Set([...rows.querySelectorAll('details[open]')].map(row => row.dataset.id));
  rows.replaceChildren();
  if (!page.records.length) rows.append(label('p', part('select').value || cursors.length > 1
    ? '当前页没有符合条件的记录。' : '外发箱暂时没有记录。' + guide, 'hint'));
  for (const record of page.records) rows.append(recordRow(record, open.has(String(record.id))));
  const pages = part('.ob-pages');
  pages.replaceChildren();
  if (cursors.length === 1 && !page.hasMore) return;
  const prev = button('上一页', () => { cursors.pop(); changePage(); });
  const next = button('下一页', () => { cursors.push(page.records.at(-1).id); changePage(); });
  prev.disabled = cursors.length === 1;
  next.disabled = !page.hasMore;
  pages.append(prev, label('span', '第 ' + cursors.length + ' 页'), next);
}

function recordRow(record, open) {
  const wrap = el('article', 'ob-item');
  const row = el('details');
  row.dataset.id = record.id;
  const head = el('summary');
  const when = label('span', time(record.createdAt), 'ob-time');
  when.append(label('small', '入箱', 'ob-caption'));
  const who = label('strong', record.streamer.name || record.streamer.uid || '未知主播');
  who.append(label('small', kinds[record.kind] || '其他', 'ob-caption'));
  const destination = label('span', record.conversationName);
  destination.append(label('small', '外发会话', 'ob-caption'));
  const state = label('span', states[record.status], 'ob-state');
  state.dataset.tone = {FAILED: 'err', UNKNOWN: 'warn', EXPIRED: 'warn', SENT: 'ok', LIMITED: 'warn'}[record.status] || '';
  const result = [];
  if (record.claimant) result.push(record.claimant + ' 于 ' + time(record.claimedAt) + ' 取走');
  if (record.note) result.push(record.note);
  if (!result.length) result.push('到期 ' + time(record.expiresAt));
  head.append(when, who, destination, state, label('span', result.join(' · '), 'ob-result'));
  row.append(head);
  row.open = open;
  row.ontoggle = () => {
    if (row.open && !row.querySelector('.ob-content')) row.append(recordContent(record));
  };
  wrap.append(row);
  if (isAdmin() && (retryable.includes(record.status) || record.status === 'PENDING')) {
    const actions = el('div', 'ob-actions');
    const retry = retryable.includes(record.status);
    const title = retry ? '重新放回待发' : '作废';
    actions.append(button(title, async event => {
      const target = event.currentTarget;
      if (!await ask({title: title + ' #' + record.id, confirmText: title, danger: true,
        body: retry ? '这条通知将重新开始计算有效期，可能被再发一遍。' : '这条待发通知将被作废，取件程序之后无法取走。'})) return;
      await mutate(target, '/records/' + record.id + (retry ? '/requeue' : '/void'), {method: 'POST'});
    }));
    wrap.append(actions);
  }
  return wrap;
}

function recordContent(record) {
  const content = el('div', 'ob-content');
  if (record.atAll) content.append(label('small', '提醒全体成员', 'ob-caption'));
  record.segments.forEach((segment, index) => {
    if (segment.type === 'IMAGE_BYTES') {
      const src = CONSOLE + '/api/outbox/records/' + record.id + '/images/' + index;
      const thumbnail = el('img');
      thumbnail.alt = '查看第 ' + (index + 1) + ' 段图片原图';
      thumbnail.src = src;
      const view = button('', () => {
        const original = el('img');
        original.src = src;
        original.alt = '第 ' + (index + 1) + ' 段图片原图';
        part('dialog div').replaceChildren(original);
        part('dialog').showModal();
      });
      view.append(thumbnail);
      content.append(view);
    } else if (segment.type === 'IMAGE_URL' && /^https?:\/\//i.test(segment.value)) {
      const link = label('a', segment.value);
      link.href = segment.value;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      content.append(link);
    } else content.append(label('span', segment.value, 'ob-text'));
  });
  return content;
}

async function mutate(target, path, options) {
  target.disabled = true;
  try {
    await outbox(path, options);
    say('已保存', 'ok');
    await load();
    return true;
  } catch (error) {
    say(error.message, 'err');
    return false;
  } finally { target.disabled = false; }
}

function showConversations(items) {
  const host = part('.ob-conversations');
  host.replaceChildren();
  if (!items.length) host.append(label('p', '还没有登记外发会话。' + guide, 'hint'));
  for (const item of items) {
    const row = el('article', 'ob-conv');
    const title = el('header', 'ob-toolbar');
    title.append(label('h4', item.name), label('span', item.enabled ? '使用中' : '已停用', 'ob-state'));
    row.append(title, label('div', item.remark || '无备注'));
    row.append(label('div', '最小间隔 ' + (item.minIntervalSeconds ? item.minIntervalSeconds + ' 秒' : '不限')
      + ' · 每日上限 ' + (item.dailyLimit ? item.dailyLimit + ' 条' : '不限'), 'ob-meta'));
    if (isAdmin()) {
      const actions = el('div', 'ob-actions');
      actions.append(button('修改', () => editConversation(item)));
      if (item.enabled) actions.append(button('停用', async event => {
        const target = event.currentTarget;
        if (await ask({title: '停用「' + item.name + '」？', body: '新通知将无法进入这个会话，已有记录仍可查看和处理。',
          confirmText: '停用', danger: true})) await mutate(target, '/conversations/' + item.id + '/disable', {method: 'POST'});
      }));
      row.append(actions);
    }
    host.append(row);
  }
}

function editConversation(item) {
  const form = el('form', 'ob-form');
  form.append(label('h4', item ? '修改会话' : '新增会话'));
  for (const [key, title, type] of [['name', '名称', 'text'], ['remark', '备注', 'text'],
    ['minIntervalSeconds', '最小间隔（秒，0 为不限）', 'number'], ['dailyLimit', '每日上限（条，0 为不限）', 'number']]) {
    const field = label('label', title);
    const input = el('input');
    input.name = key;
    input.type = type;
    input.required = key === 'name';
    if (type === 'number') { input.min = '0'; input.max = '2147483647'; input.step = '1'; }
    input.value = item?.[key] ?? (type === 'number' ? 0 : '');
    field.append(input);
    form.append(field);
  }
  const actions = el('div', 'ob-actions');
  const save = label('button', '保存', 'primary sm');
  save.type = 'submit';
  actions.append(button('取消', () => form.remove()), save);
  form.append(actions);
  form.onsubmit = async event => {
    event.preventDefault();
    const edit = Object.fromEntries(new FormData(form));
    edit.minIntervalSeconds = Number(edit.minIntervalSeconds);
    edit.dailyLimit = Number(edit.dailyLimit);
    if (await mutate(save, '/conversations' + (item ? '/' + item.id : ''), {
      method: item ? 'PUT' : 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(edit),
    })) form.remove();
  };
  part('.ob-editor').replaceChildren(form);
  form.elements.name.focus();
}
