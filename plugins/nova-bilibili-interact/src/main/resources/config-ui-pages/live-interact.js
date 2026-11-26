/** 管理员使用的直播间互动页。 */

import {ask} from './confirm.js';
import {api, esc} from './core.js';
import {store} from './store.js';

/**
 * 「发弹幕用哪个账号」那一项的配置键，服务端的状态回包里也带着同一个值
 */
const SETTING_KEY = 'novabot.bilibili.interact.danmaku.account';

/**
 * 两个选项的说明。选监听账号的那句风险提醒必须一直摆在选项旁边，不只在确认框里出现
 */
const CHOICE_NOTES = {
  speaker: '在「连接」页的「哔哩哔哩发言账号」卡上另登录一个号。它被平台限制时，监听、开播通知和直播报告照常。',
  listener: '不用另登录。这个号如果因为发弹幕被平台限制，所有主播的监听、开播通知和直播报告会一起受影响。',
};

let root = null;
let state = null;
let loadError = '';
let request = 0;
let saving = false;
let pendingChoice = null;
let sending = false;
let selected = '';
const limits = {};
let limitAsked = '';
let settingsUid = '';
let historyUid = '';
let draft = null;
let settingsRequest = 0;
let historyRequest = 0;
let page = 1;
let settingsEdited = false;
let savingSettings = false;
const unknownSendResult = '没有收到 NovaBot 的发送结果，不确定有没有发出。请去发送记录或直播间确认。';
const outcomes = {SENT: '已发出', NOT_SENT: '没发出', UNCERTAIN: '不确定', DROPPED: '已跳过'};

const part = id => root && root.querySelector('#' + id);

/**
 * 按字算长度：一个汉字、一个表情都算一个字，与服务端同一个算法
 */
const lengthOf = text => [...String(text || '').trim()].length;

export function render(section) {
  root = section;
  section.innerHTML = `
    <style>
      #page-live-interact {overflow-wrap:anywhere}
      .li-wrap {display:grid;gap:16px;max-width:46rem}
      .li-wrap h2 {margin:0}
      .li-wrap,.li-card,.li-form {min-width:0}
      .li-summary {margin:0;padding:10px 14px;border-inline-start:3px solid var(--accent);background:var(--ground)}
      .li-fields,.li-plan {display:grid;gap:12px;min-width:0;margin:0}
      .li-fields {padding:0;border:0}
      .li-fields:disabled {opacity:.55}
      .li-plan {padding:12px;border:1px solid var(--line);border-radius:var(--r-ctl)}
      .li-plan legend {padding:0 5px;font-weight:600}
      .li-row {display:flex;align-items:end;flex-wrap:wrap;gap:10px 16px}
      .li-row > label {min-width:0}
      .li-form label.li-check {display:flex;align-items:center;gap:8px}
      .li-form .li-check input {width:auto}
      .li-form textarea {width:100%;box-sizing:border-box;min-height:4.5em;resize:vertical;font:inherit;
        padding:8px;color:var(--text);background:var(--ground);border:1px solid var(--line);border-radius:var(--r-ctl)}
      .li-record {display:grid;gap:4px;padding:10px 0;border-bottom:1px solid var(--line)}
      .li-record small {color:var(--dim)}
      .li-record p,.li-form p {margin:0}
      .li-pages {display:flex;align-items:center;gap:12px;flex-wrap:wrap}
      .li-card {padding:18px}
      .li-card h3 {margin:0 0 10px}
      .li-choices {display:grid;gap:10px;margin:0 0 12px}
      .li-choice {display:grid;grid-template-columns:auto 1fr;gap:4px 10px;align-items:start;padding:10px 12px;
        border:1px solid var(--line);border-radius:var(--r-ctl);cursor:pointer}
      .li-choice input {margin-top:3px}
      .li-choice small {grid-column:2;color:var(--dim);font-size:var(--fs-sm);line-height:1.6}
      .li-choice .li-risk {color:var(--warn)}
      .li-status {display:flex;gap:8px;align-items:flex-start;font-size:var(--fs-sm);line-height:1.6}
      .li-dot {flex:none;width:9px;height:9px;border-radius:50%;margin-top:6px;background:var(--dim)}
      .li-dot.ok {background:var(--ok)}
      .li-dot.err {background:var(--err)}
      .li-dot.warn {background:var(--warn)}
      .li-form {display:grid;gap:12px}
      .li-form label {display:grid;gap:4px}
      .li-form select,.li-form input {width:100%;min-width:0;box-sizing:border-box;padding:8px;font:inherit;color:var(--text);
        background:var(--ground);border:1px solid var(--line);border-radius:var(--r-ctl)}
      .li-count {font-size:var(--fs-sm);color:var(--dim)}
      .li-count.over {color:var(--err)}
      .li-actions {display:flex;flex-wrap:wrap;gap:10px;align-items:center}
      .li-result {font-size:var(--fs-sm);line-height:1.6}
      .li-result.ok {color:var(--ok)}
      .li-result.warn {color:var(--warn)}
      .li-result.err {color:var(--err)}
      @media(max-width:680px) {
        .li-card {padding:12px}
        .li-actions button {width:100%}
      }
    </style>
    <div class="li-wrap">
      <h2>直播间互动</h2>
      <p class="li-summary" id="li-summary" role="status">正在读取发送账号与主播设置…</p>
      <div class="note n-warn" id="li-reminder">试发的内容直播间里所有人都看得见。发弹幕走的是非官方途径，账号可能被平台限制，请只在自己有权管理的直播间使用。</div>
      <p class="hint" id="li-load-error" hidden></p>
      <section class="nv-card li-card" id="li-account">
        <h3>发弹幕用哪个账号</h3>
        <div class="li-choices" id="li-choices" role="radiogroup" aria-label="发弹幕用哪个账号"></div>
        <div class="li-status" id="li-status" role="status"></div>
        <p class="li-result" id="li-save-result" role="status"></p>
      </section>
      <section class="nv-card li-card" id="li-test">
        <h3>试发一条</h3>
        <div class="li-form">
          <label>主播<select id="li-room" aria-label="往哪位主播的直播间发"></select></label>
          <label>弹幕内容<input id="li-text" type="text" autocomplete="off" placeholder="写一句话，只能一行"></label>
          <div class="li-count" id="li-count" aria-live="polite"></div>
          <div class="li-actions"><button type="button" class="primary" id="li-send">发送</button></div>
          <p class="li-result" id="li-result" role="status"></p>
        </div>
      </section>
      <section class="nv-card li-card">
        <h3>按主播设置</h3>
        <div class="li-form">
          <label>主播<select id="li-settings-room"></select></label>
          <fieldset class="li-fields" id="li-editor" disabled>
            <label class="li-check"><input id="li-enabled" type="checkbox">自动发弹幕</label>
            <p class="hint" id="li-off-note">总开关已关闭，这位主播的所有自动弹幕都已停用。</p>
            <fieldset class="li-fields" id="li-features">
              <label>每分钟最多自动发送（条）<input type="number" min="1" max="20" id="li-per-minute"></label>
              <label class="li-check"><input id="li-timed" type="checkbox">定时弹幕</label>
              <div class="li-fields" id="li-plans"></div>
              <div><button type="button" id="li-add">添加计划</button></div>
            </fieldset>
            <div class="li-actions"><button type="button" class="primary" id="li-save">保存设置</button></div>
          </fieldset>
          <p class="li-result" id="li-settings-result" role="status"></p>
        </div>
      </section>
      <section class="nv-card li-card">
        <h3>发送记录</h3>
        <div class="li-form">
          <div class="li-row">
            <label>主播<select id="li-history-room"></select></label>
            <label>结果<select id="li-outcome"><option value="">全部结果</option>
              ${Object.entries(outcomes).map(([value, label]) => '<option value="' + value + '">' + label + '</option>').join('')}
            </select></label>
            <button type="button" id="li-reload">刷新记录</button>
          </div>
          <div id="li-history" aria-live="polite"></div>
          <div class="li-pages"><button type="button" id="li-prev">上一页</button><span id="li-page"></span>
            <button type="button" id="li-next">下一页</button></div>
        </div>
      </section>
    </div>`;
  bindAutomatic();
  part('li-room').addEventListener('change', () => {
    selected = part('li-room').value;
    drawCount();
  });
  part('li-text').addEventListener('input', drawCount);
  part('li-text').addEventListener('keydown', event => {
    if (event.key === 'Enter') {
      event.preventDefault?.();
      send();
    }
  });
  part('li-send').addEventListener('click', send);
}

/**
 * 进页、整体载入时重取一次状态；草稿（选的主播、写的字、上一次的结果）留着
 */
export function refresh() {
  return load();
}

async function load() {
  const mine = ++request;
  try {
    const res = await api('/interact/danmaku/state');
    if (mine !== request) return;
    if (!res || !res.success) {
      loadError = (res && res.message) || '没取到直播间互动的状态';
    } else {
      loadError = '';
      state = res;
    }
  } catch (e) {
    if (mine !== request) return;
    loadError = '没取到直播间互动的状态：' + e.message;
  }
  draw();
}

function draw() {
  if (!root) return;
  const error = part('li-load-error');
  error.hidden = !loadError;
  error.textContent = loadError;
  if (!state) return;
  drawChoices();
  drawStatus();
  drawRooms();
  drawCount();
  const rooms = state.rooms || [];
  const account = state.account || {};
  const active = rooms.filter(r => r.autoEnabled).map(r => r.name);
  part('li-summary').textContent = (account.name || '发送账号') + (account.uid ? ' ' + account.uid : '')
    + ' · ' + (account.pausedSeconds ? '暂停中' : account.usable ? '可用' : '不可用')
    + '　|　自动发弹幕：' + (active.join('、') || '全部关闭');
  const uid = fillRooms('li-settings-room', settingsUid);
  if (uid !== settingsUid || (!settingsEdited && !savingSettings)) readSettings(uid);
  const removed = (state.historyRooms || []).filter(past => !rooms.some(room => room.uid === past.uid))
    .map(past => ({...past, name: past.name + ' · 已移除'}));
  const history = fillRooms('li-history-room', historyUid, [...rooms, ...removed]);
  if (history !== historyUid) page = 1;
  historyUid = history;
  readHistory();
}

function drawChoices() {
  const box = part('li-choices');
  const choices = state.choices || [];
  box.innerHTML = choices.map(choice => '<label class="li-choice">'
    + '<input type="radio" name="li-account" data-li-choice="' + esc(choice.value) + '" value="' + esc(choice.value) + '">'
    + '<span>' + esc(choice.label) + (choice.value === 'speaker' ? '（推荐）' : '') + '</span>'
    + '<small' + (choice.value === 'listener' ? ' class="li-risk"' : '') + '>' + esc(CHOICE_NOTES[choice.value] || '') + '</small>'
    + '</label>').join('');
  const current = saving && pendingChoice ? pendingChoice : state.choice;
  box.querySelectorAll('[data-li-choice]').forEach(input => {
    input.checked = input.getAttribute('data-li-choice') === current;
    input.disabled = saving;
    input.addEventListener('change', () => {
      if (input.checked) choose(input.getAttribute('data-li-choice'));
    });
  });
}

function drawStatus() {
  const account = state.account || {};
  const who = account.name + (account.uid ? '（uid ' + account.uid + '）' : '');
  let tone;
  let text;
  if (!account.usable) {
    tone = 'err';
    text = '现在不能用：' + (account.reason || '原因不明');
  } else if (account.pausedSeconds) {
    tone = 'warn';
    text = '现在用的是' + who + '，但它刚被平台限制，暂停发送中，还要等约 ' + wait(account.pausedSeconds) + '，到点自动恢复。';
  } else {
    tone = 'ok';
    text = '现在用的是' + who + '，能用。同一个账号两条之间至少隔 ' + state.minInterval + ' 秒；平台说太频繁或风控时暂停 '
      + state.pauseMinutes + ' 分钟。';
  }
  part('li-status').innerHTML = '<span class="li-dot ' + tone + '"></span><span>' + esc(text) + '</span>';
}

function drawRooms() {
  const select = part('li-room');
  const rooms = state.rooms || [];
  if (!rooms.length) {
    select.innerHTML = '<option value="">还没有添加主播</option>';
    select.value = '';
    selected = '';
    select.disabled = true;
    return;
  }
  if (!rooms.some(room => room.uid === selected)) selected = rooms[0].uid;
  select.innerHTML = rooms.map(room => '<option value="' + esc(room.uid) + '">'
    + esc(room.name + '（房间 ' + room.roomId + '）') + '</option>').join('');
  select.value = selected;
  select.disabled = false;
}

function drawCount() {
  if (!root || !state) return;
  const count = part('li-count');
  const length = lengthOf(part('li-text').value);
  const send = part('li-send');
  send.disabled = sending || !selected;
  if (!selected) {
    count.textContent = '先到「主播」页添加一位哔哩哔哩主播，才能试发。';
    count.classList.remove('over');
    return;
  }
  const known = limits[selected];
  if (!known) {
    count.textContent = '已输入 ' + length + ' 字 · 正在查这个房间的字数上限…';
    count.classList.remove('over');
    askLimit(selected);
    return;
  }
  count.textContent = '已输入 ' + length + ' 字 · 这个房间一条最多 ' + known.limit + ' 字'
    + (known.fromPlatform ? '' : '（没能从平台取到，按 ' + known.limit + ' 字算）');
  count.classList.toggle('over', length > known.limit);
}

async function askLimit(uid) {
  if (limitAsked === uid) return;
  limitAsked = uid;
  try {
    const res = await api('/interact/danmaku/limit?uid=' + encodeURIComponent(uid));
    if (res && res.success) limits[uid] = {limit: res.limit, fromPlatform: !!res.fromPlatform};
    else limits[uid] = {limit: state.defaultLimit || 20, fromPlatform: false};
  } catch (e) {
    limits[uid] = {limit: state.defaultLimit || 20, fromPlatform: false};
  } finally {
    if (limitAsked === uid) limitAsked = '';
  }
  if (uid === selected) drawCount();
}

function showResult(id, text, tone) {
  const node = part(id);
  if (!node) return;
  node.textContent = text || '';
  node.className = 'li-result' + (tone ? ' ' + tone : '');
}

/**
 * 试发一条。按钮点一次只发一次：结果回来之前按钮一直是灰的，再点、再按回车都不会多发
 */
async function send() {
  if (sending || !state || !selected) return;
  const uid = selected;
  const text = part('li-text').value;
  sending = true;
  part('li-send').disabled = true;
  showResult('li-result', '正在发送…');
  try {
    const res = await api('/interact/danmaku/test', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({uid, text}),
    });
    if (res?.success === false && res.message) {
      showResult('li-result', res.message, 'err');
    } else if (!res?.success) {
      showResult('li-result', unknownSendResult, 'warn');
    } else if (res.sent) {
      showResult('li-result', res.message || '已发出', 'ok');
    } else {
      showResult('li-result', (res.uncertain ? '不确定是否发出：' : '没发出：') + (res.message || res.failureText || ''),
        res.uncertain ? 'warn' : 'err');
      if (res.failure === 'TOO_LONG') delete limits[uid];
    }
  } catch (e) {
    showResult('li-result', unknownSendResult, 'warn');
  } finally {
    sending = false;
  }
  await load();
}

/**
 * 改「发弹幕用哪个账号」。存进设置，保存后当场生效；选监听账号要先确认那句风险
 */
async function choose(value) {
  if (saving || !state || value === state.choice) return;
  saving = true;
  pendingChoice = value;
  showResult('li-save-result', '正在保存…');
  drawChoices();
  try {
    const key = state.settingKey || SETTING_KEY;
    let res = await saveSetting(key, value, false);
    if (res && !res.success && Array.isArray(res.confirm) && res.confirm.length) {
      const ok = await ask({title: '用监听直播间的账号发弹幕？', body: res.confirm.map(item => item.body).join('\n'),
        danger: true, confirmText: '仍然改用监听账号'});
      if (!ok) {
        showResult('li-save-result', '没有改，仍用' + labelOf(state.choice) + '。');
        return;
      }
      res = await saveSetting(key, value, true);
    }
    if (res && res.success) {
      if (store.values && typeof store.values === 'object') store.values[key] = value;
      state.choice = value;
      showResult('li-save-result', '已保存，下一条起用' + labelOf(value) + '，不用重启。', 'ok');
    } else {
      showResult('li-save-result', (res && res.message) || '没能保存', 'err');
    }
  } catch (e) {
    showResult('li-save-result', '没能保存：' + e.message, 'err');
  } finally {
    saving = false;
    pendingChoice = null;
    Object.keys(limits).forEach(uid => delete limits[uid]);
    await load();
  }
}

function saveSetting(key, value, confirmed) {
  return api('/values' + (confirmed ? '?confirmed=true' : ''), {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({[key]: value}),
  });
}

function labelOf(value) {
  const choice = (state && state.choices || []).find(item => item.value === value);
  return choice ? '「' + choice.label + '」' : value;
}

function wait(seconds) {
  const total = Math.max(1, Math.round(seconds));
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  if (!minutes) return rest + ' 秒';
  return rest ? minutes + ' 分 ' + rest + ' 秒' : minutes + ' 分钟';
}

function fillRooms(id, current, rooms = state.rooms || []) {
  const select = part(id);
  select.innerHTML = rooms.map(room => '<option value="' + esc(room.uid) + '">' + esc(room.name)
    + '（' + esc(room.uid) + '）</option>').join('') || '<option value="">还没有添加主播</option>';
  select.value = rooms.some(room => room.uid === current) ? current : rooms[0]?.uid || '';
  select.disabled = !rooms.length || (id === 'li-settings-room' && savingSettings);
  return select.value;
}

function bindAutomatic() {
  part('li-settings-room').addEventListener('change', () => readSettings(part('li-settings-room').value));
  part('li-editor').addEventListener('input', markDirty);
  part('li-editor').addEventListener('change', markDirty);
  part('li-enabled').addEventListener('change', enableFields);
  part('li-add').addEventListener('click', () => {
    if (!draft || draft.plans.length >= 10) return;
    collectDraft();
    const id = globalThis.crypto?.randomUUID?.() || 'p-' + Date.now().toString(36) + Math.random().toString(36).slice(2);
    draft.plans.push({id, enabled: true, minutes: 10, texts: [], random: false, skipQuiet: true});
    drawPlans();
    markDirty();
  });
  part('li-save').addEventListener('click', saveSettings);
  part('li-history-room').addEventListener('change', () => {
    historyUid = part('li-history-room').value;
    page = 1;
    readHistory();
  });
  part('li-outcome').addEventListener('change', () => { page = 1; readHistory(); });
  part('li-reload').addEventListener('click', () => { page = 1; readHistory(); });
  part('li-prev').addEventListener('click', () => { if (page > 1) { page--; readHistory(); } });
  part('li-next').addEventListener('click', () => { page++; readHistory(); });
}

function markDirty() {
  settingsEdited = true;
  showResult('li-settings-result', '有尚未保存的修改。');
}

function enableFields() {
  const enabled = part('li-enabled').checked;
  part('li-features').disabled = !enabled;
  part('li-off-note').hidden = !!enabled;
}

async function readSettings(uid) {
  const mine = ++settingsRequest;
  settingsUid = uid;
  settingsEdited = false;
  draft = null;
  part('li-editor').disabled = true;
  part('li-plans').innerHTML = '';
  showResult('li-settings-result', uid ? '正在读取设置…' : '请先添加一位主播。');
  if (!uid) return;
  try {
    const res = await api('/interact/danmaku/settings?uid=' + encodeURIComponent(uid));
    if (mine !== settingsRequest) return;
    if (!res?.success) throw new Error(res?.message || '读取失败');
    draft = res.settings;
    part('li-enabled').checked = draft.enabled;
    part('li-per-minute').value = draft.perMinute;
    part('li-timed').checked = !!draft.features.timed;
    drawPlans();
    enableFields();
    part('li-editor').disabled = false;
    showResult('li-settings-result', '');
  } catch (e) {
    if (mine === settingsRequest) showResult('li-settings-result', '没能读取主播设置：' + e.message, 'err');
  }
}

function drawPlans() {
  const box = part('li-plans');
  box.innerHTML = draft.plans.map((_, i) => `<fieldset class="li-plan"><legend>计划 ${i + 1}</legend>
    <label class="li-check"><input class="li-plan-on" type="checkbox">启用这条计划</label>
    <div class="li-row"><label>间隔（分钟）<input class="li-minutes" type="number" min="2"></label>
    <label>发送顺序<select class="li-order"><option value="cycle">轮流</option><option value="random">随机</option></select></label></div>
    <label>弹幕文字（每行一条，可用 {主播}）<textarea class="li-lines" rows="2"></textarea></label>
    <label class="li-check"><input class="li-quiet" type="checkbox">上一条定时弹幕后没有观众发言就跳过</label>
    <div><button class="li-remove" type="button">删除计划</button></div></fieldset>`).join('');
  box.querySelectorAll('.li-plan').forEach((row, i) => {
    const plan = draft.plans[i];
    row.querySelector('.li-plan-on').checked = plan.enabled;
    row.querySelector('.li-minutes').value = plan.minutes;
    row.querySelector('.li-order').value = plan.random ? 'random' : 'cycle';
    row.querySelector('.li-lines').value = plan.texts.join('\n');
    row.querySelector('.li-quiet').checked = plan.skipQuiet;
    row.querySelector('.li-remove').addEventListener('click', () => {
      collectDraft();
      draft.plans.splice(i, 1);
      drawPlans();
      markDirty();
    });
  });
  part('li-add').disabled = draft.plans.length >= 10;
}

function collectDraft() {
  draft.enabled = !!part('li-enabled').checked;
  draft.perMinute = Number(part('li-per-minute').value);
  draft.features = {...draft.features, timed: !!part('li-timed').checked};
  part('li-plans').querySelectorAll('.li-plan').forEach((row, i) => {
    Object.assign(draft.plans[i], {
      enabled: !!row.querySelector('.li-plan-on').checked,
      minutes: Number(row.querySelector('.li-minutes').value),
      random: row.querySelector('.li-order').value === 'random',
      texts: row.querySelector('.li-lines').value.split(/\r?\n/).map(s => s.trim()).filter(Boolean),
      skipQuiet: !!row.querySelector('.li-quiet').checked,
    });
  });
}

async function saveSettings() {
  if (!draft || savingSettings) return;
  collectDraft();
  savingSettings = true;
  part('li-editor').disabled = true;
  part('li-settings-room').disabled = true;
  showResult('li-settings-result', '正在保存…');
  try {
    const res = await api('/interact/danmaku/settings', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({uid: settingsUid, settings: draft})});
    if (!res?.success) throw new Error(res?.message || '保存失败');
    settingsEdited = false;
    await load();
    showResult('li-settings-result', '已保存，设置已生效。', 'ok');
  } catch (e) {
    showResult('li-settings-result', '没能确认保存结果：' + e.message, 'err');
  } finally {
    savingSettings = false;
    part('li-editor').disabled = !draft;
    part('li-settings-room').disabled = !state.rooms?.length;
  }
}

async function readHistory() {
  const mine = ++historyRequest;
  part('li-prev').disabled = true;
  part('li-next').disabled = true;
  part('li-page').textContent = '';
  const box = part('li-history');
  box.textContent = historyUid ? '正在读取记录…' : '请先添加一位主播。';
  if (!historyUid) return;
  try {
    const outcome = part('li-outcome').value;
    const res = await api('/interact/danmaku/history?uid=' + encodeURIComponent(historyUid) + '&page=' + page
      + (outcome ? '&outcome=' + encodeURIComponent(outcome) : ''));
    if (mine !== historyRequest) return;
    if (!res?.success) throw new Error(res?.message || '读取失败');
    const pages = Math.max(1, Math.ceil(res.total / res.pageSize));
    if (page > pages) { page = pages; return readHistory(); }
    box.innerHTML = res.rows.map(r => '<article class="li-record"><small>'
      + esc(new Date(r.time).toLocaleString()) + ' · ' + esc(r.feature === 'test' ? '试发' : r.feature === 'timed' ? '定时弹幕' : r.feature)
      + '</small><p>' + esc(r.text) + '</p><small>' + esc(outcomes[r.outcome]) + (r.reason && r.reason !== outcomes[r.outcome]
        ? ' · ' + esc(r.reason) : '') + '</small></article>').join('') || '没有符合条件的记录。';
    part('li-page').textContent = '第 ' + page + ' / ' + pages + ' 页 · 共 ' + res.total + ' 条';
    part('li-prev').disabled = page <= 1;
    part('li-next').disabled = page >= pages;
  } catch (e) {
    if (mine === historyRequest) box.textContent = '没能读取发送记录：' + e.message;
  }
}
