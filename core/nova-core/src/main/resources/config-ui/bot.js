/**
 * 机器人页：连接参数表单与测试消息
 */

import {$, api, phrase} from './core.js';

// 同一文档里不能有两个相同 id，因此按前缀生成 DOM，逻辑仍只写一份。
// label 的 for 接上各框的 id：点标签定位得到框，读屏也念得出框的名字——
// 两个 Token 框的 placeholder 一字不差，没有 for 时听读屏分不出停在哪一个上。
export function botFormHtml(p) {
  return '<div class="row"><label for="' + p + '-addr">地址</label><input id="' + p + '-addr" value="127.0.0.1"></div>'
    + '<div class="row"><label for="' + p + '-hport">HTTP 端口</label><input id="' + p + '-hport" value="3000" inputmode="numeric"></div>'
    + '<div class="row"><label for="' + p + '-htoken">HTTP Token</label><input id="' + p + '-htoken" placeholder="'
    + phrase('bot.family', v => '与 ' + v + ' 中的配置保持一致', '与机器人程序中的配置保持一致') + '"></div>'
    + '<div class="row"><label for="' + p + '-wport">WS 端口</label><input id="' + p + '-wport" value="3001" inputmode="numeric"></div>'
    + '<div class="row"><label for="' + p + '-wtoken">WS Token</label><input id="' + p + '-wtoken" placeholder="'
    + phrase('bot.family', v => '与 ' + v + ' 中的配置保持一致', '与机器人程序中的配置保持一致') + '"></div>'
    + '<div class="row"><button id="' + p + '-test" class="ghost" type="button">测试连接</button>'
    + '<button id="' + p + '-save" class="primary" type="button" disabled>保存</button></div>'
    + '<div class="out" id="' + p + '-out"></div>';
}

// 每张表单的参数改过几次。测试回包时与发出时的次数不同，说明那次测的不是此刻的参数
const botFormEdits = {};

function invalidateBotForm(p) {
  botFormEdits[p] = (botFormEdits[p] || 0) + 1;
  $('#' + p + '-save').disabled = true;
  $('#' + p + '-out').textContent = '连接参数已修改，请重新测试连接';
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
  out.textContent = '正在测试…';
  const edits = botFormEdits[p] || 0;
  // 等回包期间参数改过：这次结果作废，保存保持禁用，提示留着「请重新测试连接」
  const stale = () => (botFormEdits[p] || 0) !== edits;

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

    if (!stale()) {
      out.className = 'out ' + (res.success ? 'ok' : 'err');
      out.textContent = res.message + (res.advice ? '\n' + res.advice : '');
      // 只有连通了才允许保存：把错误配置写进文件毫无意义
      $('#' + p + '-save').disabled = !res.success;
    }
  } catch (e) {
    if (!stale()) {
      out.className = 'out err';
      out.textContent = '测试失败：' + e.message;
    }
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
