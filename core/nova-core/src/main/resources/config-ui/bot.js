import {ask} from './confirm.js';
import {$, api} from './core.js';
import {botFieldsHtml} from './bot-fields.js';
import {showLinkResult, trackLinkEdits} from './link-feedback.js';

export function botFormHtml(p) {
  return botFieldsHtml(p)
    + '<div class="lc-actions"><div class="lc-actions">'
    + '<button id="' + p + '-save" class="primary" type="button" disabled>保存并连接</button>'
    + '<span class="lc-unsaved" id="' + p + '-unsaved" hidden>未保存</span>'
    + '<span class="lc-result" id="' + p + '-save-result" role="status" hidden></span></div>'
    + '<div class="lc-actions"><button id="' + p + '-test" class="ghost" type="button">测试连接</button>'
    + '<span class="lc-result" id="' + p + '-test-result" role="status" hidden></span></div></div>';
}

const fieldKeys = ['addr', 'hport', 'htoken', 'wport', 'wtoken'];
const fields = p => fieldKeys.map(key => $('#' + p + '-' + key));

let formVersion = 0;
let testedVersion = -1;
let saving = false;
let testing = false;
let channel = null;
let channelChanging = false;
let compact = null;
let expanded = false;
let refreshConnection = null;
let updateDirty = () => {};

function updateButtons(p) {
  const stopped = channel?.enabled === false;
  $('#' + p + '-test').disabled = stopped || testing || saving || channelChanging;
  $('#' + p + '-save').disabled = (!stopped && testedVersion !== formVersion) || testing || saving || channelChanging;
}

export function renderBotChannel(p, value, refresh, unused = false) {
  const box = $('#bot-channel');
  const toggle = $('#bot-channel-enabled');
  const disclosure = $('#bot-toggle-settings');
  const settings = $('#bot-settings');
  refreshConnection = refresh;
  box.hidden = !value;

  if (channel?.enabled !== value?.enabled) {
    testedVersion = -1;
    formVersion++;
    showLinkResult($('#' + p + '-test-result'));
  }
  channel = value;
  updateButtons(p);

  const shouldCollapse = value?.enabled === false || unused;
  if (compact !== shouldCollapse) expanded = !shouldCollapse;
  compact = shouldCollapse;
  const showSettings = () => {
    $('#bot-intro').hidden = !expanded;
    $('#bot-channel-alert').hidden = !expanded || !value?.alertWarning;
    settings.hidden = !expanded;
    disclosure.setAttribute('aria-expanded', String(expanded));
    disclosure.textContent = expanded ? '收起连接设置' : '展开连接设置';
  };
  disclosure.hidden = !compact;
  disclosure.onclick = () => {
    expanded = !expanded;
    showSettings();
  };
  showSettings();

  if (!value) return;
  if (!channelChanging) toggle.checked = value.enabled;
  $('#bot-channel-label').textContent = '启用 ' + value.name + ' 通道';
  $('#bot-channel-alert').textContent = value.alertWarning || '';
  toggle.onchange = async () => {
    if (channelChanging) return;
    const enabled = toggle.checked;
    const result = $('#bot-channel-result');
    channelChanging = true;
    updateButtons(p);
    try {
      if (!enabled) {
        toggle.checked = value.enabled;
        const body = '停用后会断开 ' + value.name + '。通过此通道发送的群和好友推送、测试消息将停止，'
          + '群聊和私聊中的命令、助手与认领也将停止响应。已保存的连接和推送配置会保留。'
          + (value.alertWarning ? ' ' + value.alertWarning : '');
        if (!await ask({title: '停用 ' + value.name + ' 通道？', body})) return;
      }
      toggle.checked = enabled;
      toggle.disabled = true;
      showLinkResult(result, '正在保存…');
      const res = await api('/values', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({[value.key]: String(enabled)})});
      if (!res.success || res.restartRequired?.length) throw new Error(res.message || '无法保存通道状态');
      renderBotChannel(p, {...value, enabled}, refresh, unused);
      showLinkResult(result, '已保存', 'ok');
      await refresh();
    } catch (e) {
      toggle.checked = channel?.enabled ?? value.enabled;
      showLinkResult(result, '切换失败：' + e.message, 'err');
    } finally {
      channelChanging = false;
      toggle.disabled = false;
      updateButtons(p);
    }
  };
}

function invalidateBotTest(p) {
  formVersion++;
  updateButtons(p);
  const result = $('#' + p + '-test-result');
  if (channel?.enabled !== false && !result.classList.contains('err') && !result.classList.contains('warn')) {
    showLinkResult(result, '连接参数已修改，请重新测试连接', 'warn');
  }
}

export function bindBotForm(p) {
  $('#' + p + '-test').addEventListener('click', () => testBotConnection(p));
  $('#' + p + '-save').addEventListener('click', () => saveBotConnection(p));
  const inputs = fields(p);
  updateDirty = trackLinkEdits(inputs, $('#' + p + '-unsaved'));
  inputs.forEach(input => input.addEventListener('input', () => invalidateBotTest(p)));
}

// 回填地址和端口。留空的 Token 由保存端保留原值。
export async function fillBotForms() {
  try {
    const current = await api('/setup/bot');
    if (!current.configured) return;
    ['s1', 'bot'].forEach(p => {
      if (!$('#' + p + '-addr') || (p === 'bot' && saving)) return;
      const inputs = fields(p);
      const priorValues = inputs.map(input => input.value);
      const savedValues = [current.address || undefined, current.httpPort || undefined, '',
        current.websocketPort || undefined, ''];
      inputs.forEach((input, i) => {
        const draft = input.value;
        const edited = draft !== input.defaultValue;
        if (savedValues[i] !== undefined) input.defaultValue = String(savedValues[i]);
        if (edited) input.value = draft;
        else input.value = input.defaultValue;
      });
      if (p === 'bot' && (testing || testedVersion >= 0)
          && inputs.some((input, i) => input.value !== priorValues[i])) invalidateBotTest(p);
    });
    updateDirty();
  } catch (e) {
    // 回填失败时使用表单里的值。
  }
}

async function testBotConnection(p) {
  const result = $('#' + p + '-test-result');
  testing = true;
  testedVersion = -1;
  updateButtons(p);
  showLinkResult(result, '正在测试…');
  const version = formVersion;
  const [address, httpPort, httpToken] = fields(p).map(input => input.value.trim());
  try {
    const res = await api('/setup/test-bot', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({address, httpPort: Number(httpPort), httpToken})
    });
    if (version !== formVersion) return;
    showLinkResult(result, res.message + (res.advice ? '\n' + res.advice : ''), res.success ? 'ok' : 'err');
    if (res.success) testedVersion = version;
  } catch (e) {
    if (version === formVersion) showLinkResult(result, '测试失败：' + e.message, 'err');
  } finally {
    testing = false;
    updateButtons(p);
  }
}

async function saveBotConnection(p) {
  const result = $('#' + p + '-save-result');
  const inputs = fields(p);
  const toggle = $('#bot-channel-enabled');
  const [address, httpPort, httpToken, websocketPort, websocketToken] = inputs.map(input => input.value.trim());
  saving = true;
  toggle.disabled = true;
  inputs.forEach(input => { input.disabled = true; });
  updateButtons(p);
  showLinkResult(result, '正在保存…');
  try {
    const res = await api('/setup/bot', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({address, httpPort, websocketPort, httpToken, websocketToken})
    });
    // 连接已建立但写盘失败时，接口仍会返回 success，提示里会写明未能保存。
    if (!res.success || res.message?.includes('未能写入配置文件')) {
      showLinkResult(result, res.message || '保存失败', 'err');
      return;
    }

    const stopped = channel?.enabled === false;
    const savedValues = [address, httpPort, '', websocketPort, ''];
    inputs.forEach((input, i) => { input.value = input.defaultValue = savedValues[i]; });
    updateDirty();
    testedVersion = -1;
    showLinkResult(result, res.message || '已保存', !stopped && res.live === false ? 'warn' : 'ok');
    await refreshConnection?.();
  } catch (e) {
    showLinkResult(result, '保存失败：' + e.message, 'err');
  } finally {
    saving = false;
    toggle.disabled = false;
    inputs.forEach(input => { input.disabled = false; });
    updateButtons(p);
  }
}
