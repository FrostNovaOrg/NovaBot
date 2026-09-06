/**
 * 机器人页：连接参数表单与测试消息
 */

import {$, api} from './core.js';

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

function invalidateBotForm(p) {
  $('#' + p + '-save').disabled = true;
  $('#' + p + '-out').textContent = '参数改了，请重新测试连接';
  $('#' + p + '-out').className = 'out';
}

export function bindBotForm(p) {
  $('#' + p + '-test').addEventListener('click', () => testBotConnection(p));
  $('#' + p + '-save').addEventListener('click', () => saveBotConnection(p));
  ['addr', 'hport', 'htoken', 'wport', 'wtoken'].forEach(k => {
    $('#' + p + '-' + k).addEventListener('input', () => invalidateBotForm(p));
  });
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
    $('#' + p + '-save').disabled = true;
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

/*
 * 「发一条试试」原先也在本文件里，5.1 随连接页改版搬去了 links.js。
 *
 * 搬走不只是挪位置：那一版的目标是手填的群号或 QQ 号，而这一步存在的全部意义
 * 正是把「群号填错」与其余三类错分开——手填的话，第一类错又混了回去，
 * 且是在使用者最相信这一步的时候。现在目标只能从机器人自己给的名单里挑。
 */
