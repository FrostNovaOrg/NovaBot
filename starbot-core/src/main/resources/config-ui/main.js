/**
 * 入口：整体载入、页签切换与事件绑定
 * 必须最后加载——它在解析时就会调用其余模块里的函数。
 */

async function load() {
  say('载入中…');
  try {
    const [s, v] = await Promise.all([api('/schema'), api('/values')]);
    schema = s.groups || [];
    values = v.values || {};
    dirty = {};
    renderGeneral();

    const [d, y, st, b, h] = await Promise.all([
      api('/datasource'), api('/raw'), api('/status'), api('/backups'), api('/handlers')]);
    $('#datasource').value = d.content || '[]';
    $('#rawyml').value = y.content || '';
    renderStatus(st);

    handlerList = h.handlers || [];
    senderList = st.senders || [];
    try {
      pushData = JSON.parse(d.content || '[]');
    } catch (e) {
      // 文件内容不合法时退回高级模式，让使用者直接修，而不是把错误内容悄悄吞掉
      pushData = [];
      if (!advancedMode) toggleAdvanced();
      say('推送配置不是合法 JSON，已切换到高级模式供你修正', 'err');
    }
    renderStreamers();
    decoratePushData();
    renderBackups(b.backups);
    loadAccounts();
    loadHistory();
    loadState();
    renderWizard();
    // 向导渲染完成后两份表单才都在 DOM 里，此时统一回填
    fillBotForms();

    const count = schema.reduce((n, g) => n + g.fields.length, 0);
    $('#head-sub').textContent = count + ' 个配置项 · ' + schema.length + ' 个分组';
    say('');
  } catch (e) {
    say('载入失败：' + e.message, 'err');
  }
  markDirty();
}

function switchTab(name) {
  document.querySelectorAll('nav button').forEach(x => x.classList.toggle('on', x.dataset.tab === name));
  document.querySelectorAll('section').forEach(x => x.classList.toggle('on', x.id === name));
  tab = name;

  // 窄屏上页签栏是横向滚动的，靠右的页签会落在视野外。选中却看不见等于没有选中标记，
  // 因此把当前页签滚进来。block:'nearest' 防止它顺带把整页往下拉
  $('nav button.on')?.scrollIntoView({inline: 'center', block: 'nearest'});

  clearTimeout(accountTimer);
  if (tab === 'overview') { api('/status').then(renderStatus); loadHistory(); loadAccounts(); refreshWizardState(); }
  else if (tab === 'bot') api('/status').then(renderStatus);
  else if (tab === 'bilibili') { api('/status').then(renderStatus); loadAccounts(); }
  // 每次进入都重取：群里随时可能有人订阅或关掉命令，缓存的画面会误导人
  else if (tab === 'sessions') loadState();
  else if (tab === 'analytics') loadAnalytics();

  markDirty();
}

document.querySelectorAll('nav button').forEach(b => {
  b.addEventListener('click', () => switchTab(b.dataset.tab));
});

$('#save').addEventListener('click', save);
$('#raw-save').addEventListener('click', saveRaw);
$('#test-send').addEventListener('click', sendTestMessage);
$('#selftest-run').addEventListener('click', runSelfTest);
// 切换显示范围时保留已改动的字段：重绘只影响可见性，不该丢掉未保存的编辑
$('#show-advanced').addEventListener('change', () => { renderGeneral(); markDirty(); });
$('#toggle-push').addEventListener('click', togglePush);
$('#add-streamer').addEventListener('click', addStreamer);
$('#toggle-advanced').addEventListener('click', toggleAdvanced);
$('#add-uid').addEventListener('keydown', e => { if (e.key === 'Enter') addStreamer(); });
$('#wizard-toggle').addEventListener('click', () => {
  wizardTouched = true;
  setWizardCollapsed($('#wizard').style.display !== 'none');
});
$('#ana-view').addEventListener('change', loadAnalytics);
$('#ana-period').addEventListener('change', loadAnalytics);
$('#ana-uid').addEventListener('change', loadAnalytics);
$('#reload').addEventListener('click', load);
// 先注入 DOM 再绑定，否则 bindBotForm 取到的是 null
$('#bot-form').innerHTML = botFormHtml('bot');
bindBotForm('bot');
load();
