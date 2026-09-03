/**
 * 总览页：首次配置向导、健康自检、推送开关与推送记录
 */

import {bindBotForm, botFormHtml, renderTestMessage} from './bot.js';
import {$, api, esc, say} from './core.js';
import {pageStatus, switchTab} from './main.js';
import {renderBinds, renderIncomplete, renderSessions, renderSubs} from './sessions.js';
import {store} from './store.js';

export function renderWizard() {
  $('#wizard').innerHTML = `
    <div class="step">
      <h3><span class="no" id="s1-no">1</span>连接机器人</h3>
      <div class="body">` + botFormHtml('s1') + `</div>
    </div>

    <div class="step">
      <h3><span class="no" id="s2-no">2</span><span id="s2-title">登录直播平台</span></h3>
      <div class="body">
        <div class="out" id="s2-out">读取中…</div>
        <div id="s2-qr"></div>
      </div>
    </div>

    <div class="step">
      <h3><span class="no" id="s3-no">3</span>添加第一个主播</h3>
      <div class="body">
        <div class="out" id="s3-out"></div>
        <div class="row"><button id="s3-go" type="button">前往「QQ 推送」</button></div>
      </div>
    </div>

    <div class="step">
      <h3><span class="no" id="s4-no">4</span>发送测试消息</h3>
      <div class="body">
        <div class="out">在「连接」页选择目标并发送一条测试消息，群里收到即表示全链路正常。</div>
        <div class="row"><button id="s4-go" type="button">前往「连接」</button></div>
      </div>
    </div>`;

  bindBotForm('s1');
  $('#s3-go').addEventListener('click', () => switchTab('push'));
  $('#s4-go').addEventListener('click', () => switchTab('bot'));

  refreshWizardState();
}

function stepDone(no, done) {
  const badge = $('#s' + no + '-no');
  badge.classList.toggle('done', !!done);
  badge.textContent = done ? '✓' : String(no);
}

export function setWizardCollapsed(collapsed) {
  $('#wizard').style.display = collapsed ? 'none' : 'block';
  $('#wizard-toggle').textContent = collapsed ? '展开' : '收起';
}

export async function refreshWizardState() {
  try {
    const [login, st] = await Promise.all([api('/login'), api('/status')]);

    // 这一步登录的是哪个平台，由运行时的清单说了算，向导里不写死任何一个平台的名字：
    // 只装了一个平台插件时它就是默认的那个，一个都没装时这一步根本无从做起，
    // 装了多个则每个都要有着落——写死一个名字，别的平台在向导里就永远不存在
    const accounts = login.accounts || [];
    const done = a => !!(a.loggedIn || a.disabledReason);
    // 停在还没着落的那个平台上：已经登录好的那些不必再占着这一屏
    const account = accounts.find(a => !done(a)) || accounts[0];
    const loggedIn = !!(account && account.loggedIn);
    // 登录被配置关掉时（例如匿名模式）这一步不会再有进展，算它「已定」而不是「未完成」，
    // 否则向导会永远停在「还有 1 步未完成」，催一件使用者已经决定不做的事。
    // 一个平台插件都没装时同理：那不是没做完，是没得做
    const disabled = (account && account.disabledReason) || '';
    const settled = accounts.every(done);
    stepDone(2, settled);
    $('#s2-title').textContent = accounts.length === 1 ? '登录' + accounts[0].displayName : '登录直播平台';
    // 装了多个平台时要点明这一屏说的是哪一个，否则二维码是谁的都看不出来
    const who = accounts.length > 1 ? account.displayName + '：' : '';
    $('#s2-out').textContent = account
      ? who + (loggedIn ? '已登录，账号 ' + (account.accountId || '未知')
        : disabled || ('请使用' + account.displayName + '客户端扫描下方二维码'))
      : '未加载任何直播平台插件，无法登录';
    $('#s2-out').className = 'out' + (loggedIn ? ' ok' : '');
    $('#s2-qr').innerHTML = (account && !loggedIn && account.qrCode)
      ? '<img referrerpolicy="no-referrer" style="width:240px;height:240px;background:var(--paper);border-radius:var(--r-ctl);padding:8px" src="data:image/png;base64,' + esc(account.qrCode) + '">'
      : '';

    const count = (st.users || []).length;
    stepDone(3, count > 0);
    $('#s3-out').textContent = count ? '已配置 ' + count + ' 位主播' : '尚未配置任何主播';
    $('#s3-out').className = 'out' + (count ? ' ok' : '');

    const bot = (st.health || []).find(h => h.scope === 'BOT');
    const botOk = !!(bot && bot.level === 'OK');
    stepDone(1, botOk);

    // 第四步没有独立的判定依据：能把消息推出去的前提正是前三步都成立
    const ready = botOk && settled && count > 0;
    stepDone(4, ready);

    const remaining = [botOk, settled, count > 0, ready].filter(x => !x).length;
    $('#wizard-title').textContent = remaining
      ? '首次配置 · 还有 ' + remaining + ' 步未完成'
      : '首次配置已完成';
    if (!store.wizardTouched) setWizardCollapsed(remaining === 0);
  } catch (e) {
    // 向导只是引导，拉取失败不影响其余功能
  }
}

// ============ 推送规则表单 ============
// 处理器的全限定类名属于实现细节，不该要求使用者手抄。此处由 /api/handlers 驱动渲染，

// 「刚才那条推了吗」「为什么没推」此前只能翻 journalctl
function renderHistory(records) {
  const body = $('#history tbody');
  if (!records || !records.length) {
    body.innerHTML = '<tr><td colspan="4" class="empty">尚无推送记录</td></tr>';
    return;
  }

  body.innerHTML = records.map(r => {
    const result = r.success
      ? '<span class="good">成功</span>'
      : '<span class="bad">失败：' + esc(r.reason || '未知原因') + '</span>';
    return '<tr><td>' + esc(r.at) + '</td><td>' + esc(r.target) + '</td>'
      + '<td title="' + esc(r.summary) + '">' + esc(r.summary) + '</td><td>' + result + '</td></tr>';
  }).join('');
}

export async function loadHistory() {
  try {
    renderHistory((await api('/push-history')).records);
  } catch (e) {
    // 推送记录拉取失败不影响其余状态展示
  }
}

// 二维码原本只打印在启动日志里，systemd 部署时得翻 journalctl，
// 且终端字符画在字体或宽度不合适时根本扫不出来


function renderPushSwitch(enabled) {
  store.pushEnabled = enabled !== false;
  $('#toggle-push').textContent = store.pushEnabled ? '暂停全部推送' : '恢复推送';
  $('#push-hint').textContent = store.pushEnabled ? '当前正常推送' : '已暂停，所有推送都会被丢弃';
  $('#push-hint').style.color = store.pushEnabled ? '' : 'var(--err)';
}

export async function togglePush() {
  const next = !store.pushEnabled;
  $('#toggle-push').disabled = true;
  try {
    const res = await api('/push/toggle', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: next })
    });
    renderPushSwitch(next);
    say(res.message, res.success ? 'ok' : 'err');
  } catch (e) {
    say('切换失败：' + e.message, 'err');
  }
  $('#toggle-push').disabled = false;
}

/**
 * 侧栏底部的版本位
 *
 * 值由运行状态下发。没有这个字段时只显示产品名，不在界面里写死一个版本号——
 * 写死的那个与产物脱节的那一天不会有任何提示，而使用者正是照着它去判断该不该换 jar。
 * @param version 当前版本，没有则不显示
 */
function renderVersion(version) {
  $('#side-version').textContent = version ? 'NovaBot ' + version : 'NovaBot';
}

/**
 * 探针行。插件页要渲染属于自己的那几条，因此这里导出去
 * @param list 探针
 * @param emptyText 一条都没有时说的话
 */
export function healthRows(list, emptyText) {
  return list.length
    ? list.map(h =>
        '<div class="row"><span class="dot ' + esc(h.level) + '"></span>' +
        '<span class="who">' + esc(h.name) + '</span>' +
        '<span class="sum">' + esc(h.summary) + '</span>' +
        (h.advice ? '<span class="advice">' + esc(h.advice) + '</span>' : '') +
        '</div>').join('')
    : '<div class="row"><span class="who">健康检查</span><span>' + esc(emptyText) + '</span></div>';
}

export function renderStatus(data) {
  renderPushSwitch(data.pushEnabled);
  renderTestMessage(data.senders);
  renderVersion(data.version);

  // 总览展示全部探针；机器人页只展示与自己相关的，由探针自报 scope 决定归属
  const health = data.health || [];
  $('#health').innerHTML = healthRows(health, '暂无可用探针');
  $('#bot-health').innerHTML = healthRows(health.filter(h => h.scope === 'BOT'),
    '未找到机器人适配器，请确认对应插件已加载');

  const r = data.runtime || {};
  $('#runtime').innerHTML = [
    ['堆内存', r.heapUsedMb + ' / ' + r.heapMaxMb + ' MB'],
    ['线程数', r.threads],
    ['CPU 核心', r.processors],
    // 上限一并显示：只报「监听 10 位」看不出这已经是顶格，再加主播时才发现加不进去
    ['监听主播', (data.users || []).length + (data.streamerLimit ? ' / ' + data.streamerLimit : '')]
  ].map(([l, n]) => '<div class="card"><div class="n">' + n + '</div><div class="l">' + l + '</div></div>').join('');

  // 平台相关的那几块归各平台页自己渲染：这里既不知道装了哪些页，也不知道页里有哪些元素。
  // 反过来由这里按元素 id 去写，那些 id 就成了一条谁都看不见的约定
  pageStatus(data);
}

// ---- 群与成员 ----
// 三份数据同属 state.json，用一个接口一次取回：分三次请求会出现
// 「命令是新的、名单是旧的」这种自相矛盾的画面
let stateData = null;

export async function loadState() {
  try {
    stateData = await api('/state');
    renderState(stateData);
  } catch (e) {
    say('载入运行状态失败：' + e.message, 'err');
  }
}

function renderState(d) {
  renderIncomplete(d.incomplete || []);
  renderSessions(d.sessions || [], d.commands || []);
  renderSubs(d.subscriptions || []);
  renderBinds(d.bindings || []);
}

// 「运行自检」把探针再跑一遍并给出结论。异常项本就在下方逐条列着，
// 这里只回答「现在到底有没有问题」，省得使用者自己数
export async function runSelfTest() {
  $('#selftest-run').disabled = true;
  say('自检中…');
  try {
    const st = await api('/status');
    renderStatus(st);
    const health = st.health || [];
    const bad = health.filter(h => h.level !== 'OK');
    say(bad.length
      ? bad.length + ' 项异常：' + bad.map(h => h.name).join('、')
      : '自检完成，' + health.length + ' 项全部正常', bad.length ? 'err' : 'ok');
  } catch (e) {
    say('自检失败：' + e.message, 'err');
  }
  $('#selftest-run').disabled = false;
}
