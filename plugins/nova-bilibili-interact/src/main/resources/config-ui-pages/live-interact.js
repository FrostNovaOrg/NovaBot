/**
 * 「直播间互动」页：发弹幕用哪个账号、试发一条
 *
 * 本文件随直播间互动插件走，由控制台在运行时按注册清单装载，成为与首页、推送、主播并列的一整页。
 * 只有管理员看得到这一页，它调的三个接口也只给管理员。
 *
 * 上半是「发弹幕用哪个账号」：它是一项设置，走设置保存那一条通道（/values），保存后当场生效、不用重启。
 * 改成监听账号之前服务端会要求先确认一句风险，这里照它的原话问。
 * 下半是「试发一条」：选一位已添加的主播、写一句话、点发送。按钮点一次只发一次，结果回来之前不能再点。
 *
 * 不点发送就不发弹幕。选中一位主播时会问一次这个房间的字数上限（服务端会缓存），此外不轮询。
 */

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
    </div>`;
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
    if (!res || !res.success) {
      showResult('li-result', (res && res.message) || '没能试发', 'err');
    } else if (res.sent) {
      showResult('li-result', res.message || '已发出', 'ok');
    } else {
      showResult('li-result', (res.uncertain ? '不确定是否发出：' : '没发出：') + (res.message || res.failureText || ''),
        res.uncertain ? 'warn' : 'err');
      if (res.failure === 'TOO_LONG') delete limits[uid];
    }
  } catch (e) {
    showResult('li-result', '没能把请求交给 NovaBot：' + e.message + '。这条没发', 'err');
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
