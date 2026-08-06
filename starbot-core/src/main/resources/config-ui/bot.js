/**
 * 机器人页：连接参数表单与测试消息
 */

import {$, api, esc} from './core.js';
import {store} from './store.js';

// 同一文档里不能有两个相同 id，因此按前缀生成 DOM，逻辑仍只写一份。
export function botFormHtml(p) {
  return '<div class="row"><label>地址</label><input id="' + p + '-addr" value="127.0.0.1"></div>'
    + '<div class="row"><label>HTTP 端口</label><input id="' + p + '-hport" value="3000" inputmode="numeric"></div>'
    + '<div class="row"><label>HTTP Token</label><input id="' + p + '-htoken" placeholder="与 OneBot 实现中配置的一致"></div>'
    + '<div class="row"><label>WS 端口</label><input id="' + p + '-wport" value="3001" inputmode="numeric"></div>'
    + '<div class="row"><label>WS Token</label><input id="' + p + '-wtoken" placeholder="与 OneBot 实现中配置的一致"></div>'
    + '<div class="row"><button id="' + p + '-test" type="button">测试连接</button>'
    + '<button id="' + p + '-save" type="button" disabled>保存</button></div>'
    + '<div class="out" id="' + p + '-out"></div>';
}

export function bindBotForm(p) {
  $('#' + p + '-test').addEventListener('click', () => testBotConnection(p));
  $('#' + p + '-save').addEventListener('click', () => saveBotConnection(p));
}

// 回填已配置的地址与端口，免得改一个字段要把整套重敲一遍。
// 两个 token 有意不回填：回填只省几次输入，却让凭据白白多经过一次浏览器；
// 保存端对空白字段是「保持原值」语义，留空不会把已有 token 抹掉。
const BOT_FORMS = ['s1', 'bot'];

export async function fillBotForms() {
  try {
    const current = await api('/setup/bot');
    if (!current.configured) return;
    BOT_FORMS.forEach(p => {
      if (!$('#' + p + '-addr')) return;
      if (current.address) $('#' + p + '-addr').value = current.address;
      if (current.httpPort) $('#' + p + '-hport').value = current.httpPort;
      if (current.websocketPort) $('#' + p + '-wport').value = current.websocketPort;
    });
  } catch (e) {
    // 回填只是便利，失败时保留默认值即可
  }
}

async function testBotConnection(p) {
  const out = $('#' + p + '-out');
  $('#' + p + '-test').disabled = true;
  out.className = 'out';
  out.textContent = '测试中…';

  try {
    const res = await api('/setup/test-bot', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        address: $('#' + p + '-addr').value.trim(),
        httpPort: Number($('#' + p + '-hport').value.trim()),
        httpToken: $('#' + p + '-htoken').value.trim()
      })
    });

    out.className = 'out ' + (res.success ? 'ok' : 'err');
    out.textContent = res.message + (res.advice ? '\n' + res.advice : '');
    // 只有连通了才允许保存：把错误配置写进文件毫无意义
    $('#' + p + '-save').disabled = !res.success;
  } catch (e) {
    out.className = 'out err';
    out.textContent = '测试失败：' + e.message;
  }

  $('#' + p + '-test').disabled = false;
}

async function saveBotConnection(p) {
  const out = $('#' + p + '-out');
  $('#' + p + '-save').disabled = true;

  try {
    const res = await api('/setup/bot', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        address: $('#' + p + '-addr').value.trim(),
        httpPort: $('#' + p + '-hport').value.trim(),
        websocketPort: $('#' + p + '-wport').value.trim(),
        httpToken: $('#' + p + '-htoken').value.trim(),
        websocketToken: $('#' + p + '-wtoken').value.trim()
      })
    });

    out.className = 'out ' + (res.success ? 'ok' : 'err');
    out.textContent = res.message;
  } catch (e) {
    out.className = 'out err';
    out.textContent = '保存失败：' + e.message;
  }

  $('#' + p + '-save').disabled = false;
}

// ============ 首次配置向导 ============
// 每步都当场验证：若填完只能「保存并重启看看」，配错了不会有任何提示，
// 等于把排障成本全推给了使用者。
// 向导嵌在总览页顶部而非独立页签：新用户打开界面第一眼就该看到它，
// 而不是先猜哪个页签是入口。四步都完成后自动收起。

// 让使用者当场发一条真消息，是最直接的验证手段
export function renderTestMessage(senders) {
  const pick = $('#test-platform');
  pick.innerHTML = (senders || []).length
    ? senders.map(s => '<option value="' + esc(s) + '">' + esc(s) + '</option>').join('')
    : '<option value="">（尚无可用的机器人）</option>';
  $('#test-send').disabled = !(senders || []).length;
}

export async function sendTestMessage() {
  const box = $('#test-result');
  const platform = $('#test-platform').value;
  const num = $('#test-num').value.trim();

  if (!platform || !num) {
    box.style.display = 'block';
    box.className = 'issues';
    box.innerHTML = '<b>请先选择机器人并填写群号或 QQ 号</b>';
    return;
  }

  $('#test-send').disabled = true;
  box.style.display = 'block';
  box.className = 'issues';
  box.innerHTML = '<b>发送中…</b>';

  try {
    const res = await api('/test-message', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform, type: Number($('#test-type').value), num: Number(num) })
    });

    box.className = 'issues' + (res.success ? ' ok' : '');
    let html = '<b>' + esc(res.message) + '</b>';
    if (res.advice) html += '<ul><li>' + esc(res.advice) + '</li></ul>';
    if (res.raw) html += '<ul><li>接口原始响应：' + esc(JSON.stringify(res.raw)) + '</li></ul>';
    box.innerHTML = html;
  } catch (e) {
    box.className = 'issues';
    box.innerHTML = '<b>发送失败：' + esc(e.message) + '</b>';
  }

  $('#test-send').disabled = false;
}

// 「临时静音」要求立即生效，因此后端会同时改内存与配置文件
