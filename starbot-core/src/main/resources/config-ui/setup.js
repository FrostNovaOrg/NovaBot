/**
 * 初始设置页 #/setup：分步引导（独立版式，不显示侧栏）
 *
 * 刚装好的机器上第一段路。本文件只管把 setup-model.js 算好的东西摆上屏幕并接上端点——
 * 「这一步放不放行」「进度条上画什么」「从第几步接着走」一律在那边判。判断留在这里的话，
 * 「第一步不许跳」这种事就只能靠人打开一台没配过的机器手点，而写松了不会有任何报错，
 * 只是那一步变成点一下就过。
 *
 * <b>每一步做完当场落盘</b>，没有草稿态，因此底部那条改动条在这一页上不出现（版式已隐藏）。
 * 各步走到哪也不记在浏览器里：内置那几步各有服务端事实可查，末步记在 /api/setup/state。
 * 记在浏览器里的话，换台电脑打开就从头开始，而这台机器明明配好了。
 *
 * 本文件里没有任何一个直播平台的名字：第 3 步的标题按通名说「直播平台」，
 * 卡上写的名字取自 /api/login 里那个由插件自报的显示名。
 *
 * 「第一位主播，推到哪」不在本文件里：那一步是产品形态，由控制台插件按 setup_step 槽带来
 * （见 docs/architecture.md 的插件步契约）。本文件只负责把它摆进流程、问它成立了没有、
 * 并把它的「下一步」「跳过」转交给它自己判——判在这里的话，核心就得认识一个它管不着的页。
 */

import {ask} from './confirm.js';
import {$, api, el, esc, markDirty, phrase, say, term} from './core.js';
import {resolveTarget, targetOptions} from './links-model.js';
import {registerPasskey} from './passkeys.js';
import {setAuthState} from './settings-auth.js';
import {renderGeneral} from './settings.js';
import {allDone, canAdvance, initialRows, railMarks, startAt, stepFacts, summaryLines, withPluginSteps}
  from './setup-model.js';
import {store} from './store.js';

/**
 * 这一页此刻手里的东西
 *
 * 与 {@link stepFacts} 算出来的「既成事实」分开：事实答的是「这台机器上已经这样了」，
 * 草稿答的是「使用者在这一趟里做了什么」。合成一份的话，「刚刚过了那段后果确认」
 * 与「这台机器本来就是免登录的」会变成同一件事，而前者是一个刚做出的决定。
 *
 * 连接参数也记在这里：换一步再回来时那几格得还在，重画一遍会把没存下的 Token 抹掉。
 */
const draft = {
  locked: false, botOk: false, accountsReady: false, anonymousConfirmed: false,
  targets: [], sent: false,
  bot: {address: '127.0.0.1', httpPort: '3000', wsPort: '3001', httpToken: '', wsToken: ''},
};

/** 每一步被跳过的方式，'' / 'skip' / 'anon' */
let skips = ['', '', '', ''];

/** 各步各自成立了没有，来自服务端事实与插件 done */
let facts = [false, false, false, false];

/** 当前步骤表（内置四步 ± 插件步） */
let steps = withPluginSteps(undefined, store.vocab);

/** slot=setup_step 的页清单 */
let pluginPages = [];

/** 已装上的插件步模块，装不上的键为 null */
let pluginMods = {};

/** 正在做第几步 */
let current = 0;

/** 走完了没有。走完画小结，不画第五步 */
let finished = false;

/** 这一趟取到的几份回包 */
let seen = {status: {}, login: {accounts: []}, mark: {}};

/** 可选的推送目标，来自机器人自己给的名单。没有手填号码的格子 */
let options = [];

/**
 * 等扫码时的轮询。<b>用自己的一份而不是 store.accountTimer</b>：
 * 那一份归平台插件页管，两处各 clearTimeout 一次的结果是谁也说不清此刻还在不在轮询
 */
let poll = null;

/**
 * 离开这一页时把轮询停掉
 *
 * 不停的话，使用者点去连接页看一眼，这一页还在每 3 秒问一次登录状态——
 * 与日志页那个「跟随最新」同一条道理
 */
export function stopSetupPolling() {
  clearTimeout(poll);
  poll = null;
}

/**
 * 机器人草稿五格的对照串。键序写死，只给 {@link botDraftMatchesSaved} 用。
 */
function botSnapshot(bot) {
  return JSON.stringify({
    address: String(bot.address || ''),
    httpPort: String(bot.httpPort || ''),
    wsPort: String(bot.wsPort || ''),
    httpToken: String(bot.httpToken || ''),
    wsToken: String(bot.wsToken || ''),
  });
}

/**
 * 草稿与上次记下的盘上是否同一套参数
 *
 * 还没对过盘时，Token 非空即未保存的编辑。对过之后按五格逐字比。
 */
function botDraftMatchesSaved(draft) {
  if (!draft.botSynced) {
    return !draft.bot.httpToken && !draft.bot.wsToken;
  }
  return botSnapshot(draft.bot) === draft.botSynced;
}

/**
 * 把此刻草稿记成「与盘上一致」。测通存下或从盘上回填之后走这里。
 */
function rememberBotDraft(draft) {
  draft.botSynced = botSnapshot(draft.bot);
  return draft;
}

/**
 * 已配过则回填地址端口并清空 Token，草稿回到与盘上一致；未配过原样返回
 */
function syncBotDraft(draft, bot) {
  if (!bot.configured) return draft;
  if (bot.address) draft.bot.address = String(bot.address);
  if (bot.httpPort) draft.bot.httpPort = String(bot.httpPort);
  if (bot.websocketPort) draft.bot.wsPort = String(bot.websocketPort);
  draft.bot.httpToken = '';
  draft.bot.wsToken = '';
  return rememberBotDraft(draft);
}

/**
 * 重进时：有未保存编辑则不得把已作废的测通翻回真；与盘上一致才采既成事实
 */
function applyBotOkFromFacts(draft, facts) {
  if (botDraftMatchesSaved(draft)) {
    draft.botOk = draft.botOk || facts[1];
  } else {
    draft.botOk = false;
  }
  return draft;
}

function factAt(key) {
  const i = steps.findIndex(step => step.key === key);
  return i >= 0 && !!(facts[i]);
}

function titleOf(key) {
  const hit = steps.find(step => step.key === key);
  return hit ? hit.title : '';
}

async function loadPluginModules(list) {
  const mods = {};
  for (const meta of list) {
    try {
      mods[meta.id] = await import('/config/assets/' + meta.script);
    } catch (e) {
      mods[meta.id] = null;
    }
  }
  return mods;
}

/**
 * 交给插件步的那份上下文
 *
 * {@code pickTargets} 是这一页自己的东西：末步「发一条试试」只在刚选定的那几个目标里挑，
 * 而选目标那一步已经是插件带来的。不给这个口子的话，末步会退回「在全部群与好友里挑」——
 * 它要验的正是刚配好的那条路通不通，换个别的目标发通了说明的是另一回事。
 */
function pluginCtx() {
  return {
    status: seen.status,
    login: seen.login,
    api,
    pickTargets: keys => { draft.targets = Array.isArray(keys) ? keys.slice() : []; },
  };
}

async function collectPluginDone() {
  const ctx = pluginCtx();
  const done = {};
  for (const meta of pluginPages) {
    const mod = pluginMods[meta.id];
    try {
      done[meta.id] = !!(mod && typeof mod.done === 'function' && await mod.done(ctx));
    } catch (e) {
      done[meta.id] = false;
    }
  }
  return done;
}

/**
 * 打开初始设置页：取事实、定落点、画出来
 *
 * 三份回包一起等再画：分三次画的话，进度条已经按新的一份变绿，而正文还是上一份算出来的。
 */
export async function openSetup() {
  const main = $('#setup-main');
  main.textContent = '载入中…';
  loadOptions();

  try {
    const [status, login, mark, bot, pagePack] = await Promise.all([
      api('/status'), api('/login'), api('/setup/state'), api('/setup/bot'),
      api('/pages').catch(() => ({pages: []}))]);
    seen = {status, login, mark};
    // 已经配过的地址与端口回填，免得「重新跑一遍」的人对着 127.0.0.1 重敲一遍自己的地址。
    // 有未保存编辑则留着（两个 Token 不从盘上回填）；与盘上一致时才清空 Token 并回填地址端口。
    if (botDraftMatchesSaved(draft)) {
      syncBotDraft(draft, bot);
    }
    pluginPages = (pagePack.pages || []).filter(meta => meta && meta.slot === 'setup_step');
    steps = withPluginSteps(pluginPages, store.vocab);
    pluginMods = await loadPluginModules(pluginPages);
    skips = steps.map((_, i) => skips[i] || '');
  } catch (e) {
    main.textContent = '';
    main.appendChild(note('err', '载入失败：' + e.message
      + '\n这一页要先问清楚这台机器现在什么样，问不到的话画出来的每一步都是猜的。'));
    return;
  }

  facts = stepFacts(seen.status, seen.login, seen.mark.testSent,
    await collectPluginDone(), pluginPages);
  // 既成事实要盖进草稿：这台机器本来就上了锁、本来就连着机器人时，
  // 那两步不该因为「这一趟里没做过」而拦着人往下走
  draft.locked = facts[0];
  applyBotOkFromFacts(draft, facts);
  draft.accountsReady = facts[2];
  draft.sent = factAt('test');

  const rerun = !!seen.mark.rerunRequested;
  current = startAt(facts, rerun, steps);
  finished = allDone(facts, steps) && !rerun;

  // 标记收下就算用过了。不收的话，此后每一次打开这一页都被拉回第一步，
  // 而使用者只按过那一次「重新跑一遍」
  if (rerun) api('/setup/rerun/consumed', {method: 'POST'}).catch(() => {});

  render();
}

/**
 * 画进度条与正文
 */
function render() {
  const rail = $('#setup-steps');
  const main = $('#setup-main');
  rail.innerHTML = '';
  main.innerHTML = '';

  railMarks(facts, skips, finished ? -1 : current, steps).forEach((mark, i) => {
    const item = el('button', 'su-st');
    item.type = 'button';
    // 还走不到的那几步点不动。点得动的话，第一步没做完就能跳到第五步，
    // 而「第一步不许跳过」正是这一页最要守的一条
    item.disabled = !(facts[i] || skips[i] || i <= current);

    const badge = el('span', 'su-badge ' + mark.state);
    badge.textContent = mark.state === 'done' ? '✓' : (mark.state === 'warn' ? '!' : String(i + 1));
    item.appendChild(badge);

    const text = el('div');
    const title = el('div', 'su-t');
    title.textContent = steps[i].title;
    text.appendChild(title);
    if (mark.note) {
      const small = el('span', 'su-x');
      small.textContent = mark.note;
      text.appendChild(small);
    }
    item.appendChild(text);

    if (!finished && i === current) item.setAttribute('aria-current', 'step');
    item.addEventListener('click', () => goStep(i));
    rail.appendChild(item);
  });

  if (finished) {
    renderDone(main);
    return;
  }
  const dispatch = {
    lock: stepLock, bot: stepBot, account: stepAccount, test: stepTest,
  };
  const step = steps[current];
  if (step && step.plugin) renderPlugin(main, step);
  else if (step && dispatch[step.key]) dispatch[step.key](main);
}

/**
 * 插件步：模块 render 把这一步画进 host；装不上画一句说明，不整页红
 */
function renderPlugin(host, step) {
  const mod = pluginMods[step.key];
  if (!mod || typeof mod.render !== 'function') {
    host.appendChild(note('warn', '这一步的界面没装上'));
  } else {
    try {
      mod.render(host, pluginCtx());
    } catch (e) {
      host.appendChild(note('warn', '这一步的界面没装上'));
    }
  }
  // skippable 可以是一个串，那就是按钮上要写的字（「先不加主播」这类）；
  // 只当真假用的话，每一个许跳过的插件步都只能写「跳过」，而跳过它各自意味着什么并不相同
  const skippable = (mod && mod.skippable) || false;
  step.skippable = skippable;
  foot(host, skippable ? () => skipPlugin(step) : null,
    typeof skippable === 'string' ? skippable : '跳过', async () => {
      if (await pluginNext(step) === false) return false;
      await refreshFacts();
      return true;
    });
}

/**
 * 插件步的「下一步」
 *
 * 🔴 <b>放行由那一步自己判</b>：核心不知道它在收什么、缺什么算没填完。
 * 这里一律放行的话，那一步会变成点一下就过——而它该做的落盘根本没发生，
 * 屏幕上却不会有任何异常。没有 {@code next} 的插件步照旧一律放行。
 * @return {Promise<boolean>} 假即不往下走
 */
async function pluginNext(step) {
  const mod = pluginMods[step.key];
  if (!mod || typeof mod.next !== 'function') return true;
  try {
    return await mod.next(pluginCtx()) !== false;
  } catch (e) {
    say('这一步没能完成：' + e.message, 'err');
    return false;
  }
}

/**
 * 插件步的「跳过」
 *
 * 跳过一步常常是个有后果的决定（「不加主播的话这台机器起来什么都不做」），
 * 那段确认由插件自己弹——它才知道后果是什么。回假即取消，本步留在原地。
 */
async function skipPlugin(step) {
  const mod = pluginMods[step.key];
  try {
    if (mod && typeof mod.skip === 'function' && await mod.skip(pluginCtx()) === false) return;
  } catch (e) {
    say('这一步没能跳过：' + e.message, 'err');
    return;
  }
  finishStep('skip');
}

/**
 * 换一步。往回改是正当走法（每步可回改），往前只能靠「下一步」走
 */
function goStep(index) {
  stopSetupPolling();
  current = Math.max(0, Math.min(steps.length - 1, index));
  finished = false;
  render();
}

/**
 * 这一步做完了，往下走
 * @param how 走的方式，'' 为正常做完，'skip' / 'anon' 为跳过
 */
function finishStep(how) {
  skips = skips.map((value, i) => (i === current ? (how || '') : value));
  if (current < steps.length - 1) {
    goStep(current + 1);
    return;
  }
  stopSetupPolling();
  finished = true;
  render();
}

/**
 * 重新问一遍服务端，再按新的事实算
 *
 * 每一步做完都要走这一趟：上锁、连机器人、加主播都会改变前四步的事实，
 * 不重问的话，进度条上那一格要到刷新页面才变绿
 */
async function refreshFacts() {
  try {
    const [status, login] = await Promise.all([api('/status'), api('/login')]);
    seen.status = status;
    seen.login = login;
    facts = stepFacts(status, login, seen.mark.testSent || draft.sent,
      await collectPluginDone(), pluginPages);
    draft.locked = facts[0];
    draft.accountsReady = facts[2];
  } catch (e) {
    // 取不到就照上一份算。这一趟只是让进度条跟上，失败不该把使用者卡在原地
  }
}

/**
 * 上锁之后把登录态从服务端再取一遍
 *
 * 载入时那一份还是「没口令」。不回灌的话，设置页「登录与安全」仍画「还没设口令」，
 * 顶栏「退出登录」也不出，整页刷新才正。
 */
async function refreshAuthState() {
  try {
    const state = await api('/auth/state');
    if (state.csrfToken) store.csrfToken = state.csrfToken;
    store.totpRequired = !!state.totpRequired;
    if (typeof state.setupDone === 'boolean') store.setupDone = state.setupDone;
    setAuthState(state);
    $('#auth-actions').style.display = state.enabled ? '' : 'none';
    $('#op-banner').style.display = state.operatorSession ? '' : 'none';
    renderGeneral();
  } catch (e) {
    // 口令已经落下。这一趟取不到的话，设置页与顶栏仍是上锁前的画面，刷新即正
  }
}

// ============ 页面零件 ============

function heading(host, title, desc) {
  const head = el('div', 'su-h');
  head.textContent = title;
  host.appendChild(head);

  const line = el('div', 'su-d');
  line.textContent = desc;
  host.appendChild(line);
}

function note(kind, text) {
  const box = el('div', 'su-note ' + (kind || ''));
  box.textContent = text;
  return box;
}

/**
 * 一格输入。值随打随记进草稿，换一步再回来时那几格还在
 * @param host 容器
 * @param label 标签
 * @param id 元素 id
 * @param type input 的 type
 * @param value 初值
 * @param onInput 值变了做什么，不关心时传 null
 * @return 建出来的 input
 */
function field(host, label, id, type, value, onInput) {
  const wrap = el('div', 'su-fld');
  const title = el('label');
  title.textContent = label;
  title.setAttribute('for', id);
  wrap.appendChild(title);

  const input = el('input');
  input.type = type || 'text';
  input.id = id;
  input.value = value == null ? '' : value;
  input.autocomplete = type === 'password' ? 'new-password' : 'off';
  if (onInput) input.addEventListener('input', () => onInput(input.value));
  wrap.appendChild(input);

  host.appendChild(wrap);
  return input;
}

function report(box, res) {
  box.textContent = res.message || (res.success ? '好了' : '没能办成');
  box.className = 'su-r ' + (res.success ? 'ok' : 'err');
}

/**
 * 每一步底下那一条：上一步 / 跳过 / 下一步
 *
 * 拦下来时把理由写在按钮旁边，不只是把按钮变灰：一个点不动的按钮，
 * 与一个坏掉的按钮在屏幕上长得一样。理由那一行<b>常在</b>（不拦时空着并藏起来），
 * 这样接下来只改它的字，不必重画整一步——重画会丢掉正在填的那几格。
 * @param host 容器
 * @param onSkip 跳过时做什么，这一步不许跳时传 null
 * @param skipLabel 跳过按钮上写什么
 * @param onNext 放行之后、进下一步之前要做的事；返回 false 则不走。不需要时传 null
 */
function foot(host, onSkip, skipLabel, onNext) {
  const bar = el('div', 'su-f');
  const back = el('button', 'ghost');
  back.type = 'button';
  back.id = 'setup-back';
  back.textContent = '上一步';
  back.disabled = current === 0;
  back.addEventListener('click', () => goStep(current - 1));
  bar.appendChild(back);
  bar.appendChild(el('span', 'su-sp'));

  if (onSkip && steps[current].skippable) {
    const skip = el('button', 'ghost');
    skip.type = 'button';
    skip.id = 'setup-skip';
    skip.textContent = skipLabel;
    skip.addEventListener('click', onSkip);
    bar.appendChild(skip);
  }

  const next = el('button', 'primary');
  next.type = 'button';
  next.id = 'setup-next';
  next.textContent = current === steps.length - 1 ? '完成' : '下一步';
  next.addEventListener('click', async () => {
    next.disabled = true;
    const ok = onNext ? await onNext() : true;
    next.disabled = false;
    if (ok !== false) finishStep('');
  });
  bar.appendChild(next);
  host.appendChild(bar);

  const why = note('warn su-why', '');
  why.id = 'setup-why';
  host.appendChild(why);
  syncFoot();
}

/**
 * 按此刻的草稿更新「下一步」的开关与那行理由
 *
 * 单独走一趟而不是重画这一步：重画会丢掉正在填的那几格，
 * 而放行条件恰恰是随着那几格一起变的
 */
function syncFoot() {
  const gate = canAdvance(current, draft, steps);
  const next = $('#setup-next');
  const why = $('#setup-why');
  if (next) next.disabled = !gate.ok;
  if (why) {
    why.textContent = gate.reason;
    why.classList.toggle('hide', gate.ok);
  }
}

// ============ 第 1 步：给控制台上把锁 ============

/**
 * 🔴 这一步<b>没有跳过按钮</b>：没上锁的控制台，任何能连上这台机器的人都进得来，
 * 而后面四步配好的东西全在它后面。原型上那个「先跳过」据此作废。
 */
function stepLock(host) {
  heading(host, titleOf('lock'),
    '设了口令，访问这个控制台才要登录。这一步不能跳过。');

  if (draft.locked) {
    host.appendChild(note('ok', '这台机器已经上锁了。要改口令去「设置 → 登录与安全」，那条路要填现在的口令。'));
    passkeyBlock(host);
    foot(host, null, '', null);
    return;
  }

  const row = el('div', 'su-row');
  const first = field(row, '密码', 'setup-pwd', 'password', '', null);
  const again = field(row, '再输一遍', 'setup-pwd2', 'password', '', null);
  host.appendChild(row);
  host.appendChild(note('', phrase('bot.family',
    v => '至少 8 个字符。填的是这个控制台的密码，与 ' + v + ' 那边的界面口令互不相干。',
    '至少 8 个字符。填的是这个控制台的密码，与机器人那边的界面口令互不相干。')));

  const result = el('div', 'su-r');
  const save = el('button', 'primary');
  save.type = 'button';
  save.id = 'setup-lock';
  save.textContent = '上锁';
  save.addEventListener('click', async () => {
    if (first.value !== again.value) {
      report(result, {success: false, message: '两遍口令不一样'});
      return;
    }

    save.disabled = true;
    try {
      const res = await api('/auth/password/set', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({next: first.value}),
      });
      report(result, res);
      if (res.success) {
        // 上锁响应里带着新会话的 CSRF：过滤器此刻已经切到口令形态，
        // 第 2 步的写请求没有它会被挡。Cookie 由这一趟的 Set-Cookie 落下。
        if (res.csrfToken) store.csrfToken = res.csrfToken;
        // 口令一存下去这台机器就上了锁，第一步随之成立。重画是为了把那一格变绿、
        // 顺带把通行密钥那个按钮解开——这一步已经没有正在填的格子要保
        await refreshFacts();
        await refreshAuthState();
        render();
        return;
      }
    } catch (e) {
      report(result, {success: false, message: '没能上锁：' + e.message});
    }
    save.disabled = false;
  });

  host.appendChild(save);
  host.appendChild(result);
  passkeyBlock(host);
  foot(host, null, '', null);
}

/**
 * 「登记一个通行密钥」
 *
 * 摆在上锁这一步里，但它<b>不是这一步的放行条件</b>：通行密钥跟着口令登录走，
 * 没有口令时它签出来的会话打不开任何门。先上锁，再登记。
 */
function passkeyBlock(host) {
  const box = el('div', 'su-pk');
  const button = el('button', 'ghost');
  button.type = 'button';
  button.id = 'setup-passkey';
  button.textContent = '登记一个通行密钥';
  button.disabled = !draft.locked;
  // 把按钮本身交过去，不让那边按 id 取：设置页上另有一个同样的按钮，
  // 两处按 id 取就得共用一个 id，而重复 id 取到的永远是靠前的那一个
  button.addEventListener('click', () => registerPasskey(button));
  box.appendChild(button);

  box.appendChild(note('', draft.locked
    ? '登记过之后，登录时按一下指纹或面容就行，不用口令也不用验证码。可以跳过，之后在设置里补。'
    : '先上锁，再登记：通行密钥跟着口令登录走，没有口令时它签出来的会话打不开任何门。'));
  host.appendChild(box);
}

// ============ 第 2 步：连上 QQ 机器人 ============

function invalidateBot(draft) {
  draft.botOk = false;
  return draft;
}

function stepBot(host) {
  heading(host, titleOf('bot'),
    '机器人指 ' + term('bot.impl.hint', '机器人程序')
    + '，NovaBot 通过它把消息发到 ' + term('bot.platform', '聊天平台')
    + '。这一步不能跳过。');

  const at = draft.bot;
  const result = el('div', 'su-r');
  const retest = () => {
    invalidateBot(draft);
    result.textContent = '参数改了，请重新测试连接';
    result.className = 'su-r';
    syncFoot();
  };
  const row = el('div', 'su-row');
  field(row, '地址', 'setup-addr', 'text', at.address, v => { at.address = v; retest(); });
  field(row, 'HTTP 端口', 'setup-hport', 'text', at.httpPort, v => { at.httpPort = v; retest(); });
  field(row, 'WS 端口', 'setup-wport', 'text', at.wsPort, v => { at.wsPort = v; retest(); });
  field(row, 'HTTP Token', 'setup-htoken', 'password', at.httpToken, v => { at.httpToken = v; retest(); });
  field(row, 'WS Token', 'setup-wtoken', 'password', at.wsToken, v => { at.wsToken = v; retest(); });
  host.appendChild(row);

  const test = el('button', 'ghost');
  test.type = 'button';
  test.id = 'setup-test-bot';
  test.textContent = '测试连接';
  test.addEventListener('click', async () => {
    test.disabled = true;
    result.textContent = '测试中…';
    result.className = 'su-r';
    draft.botOk = false;

    try {
      const res = await api('/setup/test-bot', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          address: at.address.trim(),
          // 端口在这一条上是个数，与连接页那张表单同一种发法
          httpPort: Number(at.httpPort.trim()),
          httpToken: at.httpToken.trim(),
        }),
      });
      // 通过时回显版本与昵称、号；四种失败各一句话——两者都由服务端那一侧给。
      // 界面再写一份的话，两边分叉时屏幕上的话与真正发生的事对不上
      report(result, {success: res.success, message: res.message + (res.advice ? '\n' + res.advice : '')});
      if (res.success) {
        draft.botOk = true;
        await saveBot(result);
        // 存下来的那一刻连接就接上了，「推到哪」要的名单随之有了着落。
        // 重问一遍，进度条与后面几步据此算，不必等到刷新页面
        await refreshFacts();
      }
    } catch (e) {
      report(result, {success: false, message: '测试失败：' + e.message});
    }

    test.disabled = false;
    // 只更新底下那一条，不重画这一步：重画会把刚填的两个 Token 抹掉
    syncFoot();
  });

  host.appendChild(test);
  host.appendChild(result);

  // 这一步不再要求重启：存下来的那一刻适配器就按新参数把连接接上了。
  // 保存的回话由服务端给，说的是「现在通了没有」，不是「已保存」——所以这里不另写一句
  if (!(seen.status.senders || []).length) {
    host.appendChild(note('', phrase('bot.impl',
      v => '填 ' + v + ' 那一侧的地址、端口与两个 Token。测通之后会自动存下来并当场接上，不用重启。',
      '填机器人那一侧的地址、端口与两个 Token。测通之后会自动存下来并当场接上，不用重启。')));
  }

  /**
   * 测通了就存下来。<b>测通之后才存</b>：存一份连不上的参数，
   * 表现是接下来的两步一直取不到名单，而使用者以为这一步已经过了
   */
  async function saveBot(box) {
    try {
      const res = await api('/setup/bot', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          address: at.address.trim(), httpPort: at.httpPort.trim(), websocketPort: at.wsPort.trim(),
          httpToken: at.httpToken.trim(), websocketToken: at.wsToken.trim(),
        }),
      });
      // 存下来之后的回话照实显示：它说的是这条连接此刻通没通，
      // 而这一句正是使用者点下按钮想知道的
      report(box, res);
      if (!res.success) {
        draft.botOk = false;
      } else {
        rememberBotDraft(draft);
      }
    } catch (e) {
      report(box, {success: false, message: '连接是通的，但没能存下来：' + e.message});
      draft.botOk = false;
    }
  }

  foot(host, null, '', null);
}

// ============ 第 3 步：登录直播平台 ============

function stepAccount(host) {
  const accounts = seen.login.accounts || [];
  heading(host, titleOf('account'),
    '登录之后才有动态推送与自动关注；直播推送不登录也照常。');

  if (!accounts.length) {
    host.appendChild(note('warn', '这台机器上没有装任何直播平台插件，这一步没得做。'
      + '装上之后回来再走一遍即可。'));
    foot(host, () => finishStep('skip'), '先跳过', null);
    return;
  }

  accounts.forEach(one => host.appendChild(accountCard(one)));
  foot(host, chooseAnonymous, '不登录，先用免登录模式', null);
  schedulePoll(accounts);
}

/**
 * 「不登录」这条路
 *
 * 🔴 <b>后果确认不许省</b>：免登录模式下采到的数据是残缺的，而界面上一切正常——
 * 使用者要过很久才会发现「怎么弹幕这么少」。进度条上这一格也不打勾，打的是一个警告点：
 * 这一步确实定了，但它定的是一个有后果的决定。
 */
async function chooseAnonymous() {
  if (!await ask({title: '确定先不登录吗？',
    body: '不登录的话：个人主播的直播间实测只能拿到约一成弹幕，'
      + '发送者的号会被抹成 0、昵称只留首字；动态推送与自动关注都用不了。直播推送不受影响。'})) return;

  draft.anonymousConfirmed = true;
  finishStep('anon');
}

/**
 * 一个平台的登录卡。<b>平台叫什么由它自己报</b>，界面这边不写死任何一个名字
 */
function accountCard(one) {
  const card = el('div', 'su-acc');
  const name = el('b');
  name.textContent = one.displayName || '直播平台';
  card.appendChild(name);

  if (one.disabledReason) {
    card.appendChild(note('warn', one.disabledReason));
    return card;
  }

  if (one.loggedIn) {
    card.appendChild(note('ok', '已登录'
      + (one.accountId ? ' · 账号 ' + one.accountId : '')
      + (one.credentialNote ? ' · ' + one.credentialNote : '')));
    return card;
  }

  if (one.qrCode) {
    const img = el('img', 'su-qr');
    img.src = 'data:image/png;base64,' + one.qrCode;
    img.alt = '登录二维码';
    card.appendChild(img);
    card.appendChild(note('', '用手机客户端扫这张码，扫完这一页会自己变成已登录。'));
  } else {
    card.appendChild(note('', '二维码生成中，稍候。'));
  }
  return card;
}

/**
 * 等扫码时每 3 秒问一次。扫完这一页自己变，不用手动刷新
 */
function schedulePoll(accounts) {
  stopSetupPolling();
  if (!accounts.some(one => !one.loggedIn && !one.disabledReason)) return;

  poll = setTimeout(async () => {
    await refreshFacts();
    // 人可能已经翻到别的步了：那时重画会把他从正在填的那一步上拽回来
    if (current === 2 && !finished) render();
  }, 3000);
}

// ============ 推送目标名单 ============

/**
 * 取一次群与好友名单
 *
 * 末步「发一条试试」要在这份名单里挑收件人。插件步（例如「第一位主播，推到哪」）
 * 自己另取一份：它跑在自己的模块里，取不到本文件的局部变量。
 */
async function loadOptions() {
  try {
    const [groups, friends] = await Promise.all([
      api('/onebot/targets?type=group'), api('/onebot/targets?type=friend')]);
    options = targetOptions(groups, friends);
  } catch (e) {
    // 取不到就空着。这一页不退回手填：那等于把「填错一位数不报错」那个失败形态请回来
    options = [];
  }
}

// ============ 末步：发一条试试 ============

/**
 * 试发的那句话
 *
 * 🔴 <b>连着请求一起发出去</b>，而不是让服务端用它自己那句默认的：气泡里显示的与
 * 真正发出去的因此是同一个变量。各写一份的话，两句话分叉时使用者对着气泡核对，
 * 发现群里那条不一样，会以为发错了地方——而这一步问的正是「那头收到的是不是这句」。
 */
const TEST_TEXT = '这是一条来自 NovaBot 的测试消息，收到即表示推送链路正常。';

function stepTest(host) {
  heading(host, titleOf('test'),
    '往一个群或一位好友发一条，那头看得到就说明整条链路是通的。');

  const result = el('div');
  const wrap = el('div', 'su-fld');
  const label = el('label');
  label.textContent = '发给谁';
  label.setAttribute('for', 'setup-send-target');
  wrap.appendChild(label);

  // 刚刚选过推送目标的话就只在那几个里挑：这一步要验的正是那条路通不通，
  // 换个别的目标发通了，说明的是另一回事
  const pickable = options.filter(item => !draft.targets.length || draft.targets.includes(item.key));
  const picker = el('select');
  picker.id = 'setup-send-target';
  picker.innerHTML = pickable.length
    ? pickable.map(item => '<option value="' + esc(item.key) + '">' + esc(item.text) + '</option>').join('')
    : '<option value="">暂时取不到群与好友名单</option>';
  wrap.appendChild(picker);
  host.appendChild(wrap);

  const send = el('button', 'ghost');
  send.type = 'button';
  send.id = 'setup-send';
  send.textContent = '发一条';
  send.disabled = !pickable.length;
  send.addEventListener('click', async () => {
    // 只认名单里有的那几个，这一条判在 links-model 里：连接页那一块用的是同一份判法，
    // 两处各判一遍的话，其中一处放松了不会有任何东西红
    const hit = resolveTarget(options, picker.value);
    if (!hit.ok) {
      result.innerHTML = '';
      result.appendChild(note('err', hit.reason));
      return;
    }

    send.disabled = true;
    result.innerHTML = '';
    result.appendChild(note('', '发送中…'));
    try {
      const res = await api('/test-message', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({platform: hit.platform, type: hit.type, num: hit.num, content: TEST_TEXT}),
      });
      // 发给谁那一行取名单上的原文，不从 resolveTarget 的结果拼：那边只答「认不认得出」，
      // 它给的是号码，而屏幕上要显示的是群名
      paintSent(result, res, (options.find(item => item.key === picker.value) || {}).text || '');
    } catch (e) {
      result.innerHTML = '';
      result.appendChild(note('err', '发送失败：' + e.message));
    }
    send.disabled = false;
  });

  host.appendChild(send);
  host.appendChild(result);
  host.appendChild(defaultsBlock());
  foot(host, null, '', null);
}

/**
 * 发出去之后屏幕上显示什么
 *
 * 🔴 「发出去了」与「那头收到了」是两件事：接口通、Token 对、机器人却被踢出了群，
 * 表现是什么都不发生。因此末步这一位只由<b>看见了的那个人</b>按下来，
 * 不由「发送成功」自动记上。
 */
function paintSent(host, res, targetText) {
  host.innerHTML = '';

  if (!res.success) {
    host.appendChild(note('err', res.message + (res.advice ? '\n' + res.advice : '')));
    host.appendChild(troubleshooting());
    return;
  }

  host.appendChild(note('ok', res.message));

  const bubble = el('div', 'bubble');
  const to = el('div', 'bb-to');
  to.textContent = '发给 ' + targetText;
  bubble.appendChild(to);
  const body = el('div', 'bb-body');
  body.textContent = TEST_TEXT;
  bubble.appendChild(body);
  host.appendChild(bubble);

  const tips = troubleshooting();
  tips.classList.add('hide');

  const bar = el('div', 'su-f');
  const got = el('button', 'primary');
  got.type = 'button';
  got.id = 'setup-got';
  got.textContent = '收到了，完成';
  got.addEventListener('click', async () => {
    got.disabled = true;
    try {
      await api('/setup/test-sent', {method: 'POST'});
      seen.mark.testSent = true;
    } catch (e) {
      // 记不上不该拦着人走完：这一位只影响下次进来时落在第几步
      say('没能记下这一步：' + e.message, 'err');
    }
    draft.sent = true;
    await refreshFacts();
    finishStep('');
  });
  bar.appendChild(got);

  const no = el('button', 'ghost');
  no.type = 'button';
  no.id = 'setup-not-got';
  no.textContent = '没收到';
  no.addEventListener('click', () => tips.classList.toggle('hide'));
  bar.appendChild(no);

  host.appendChild(bar);
  host.appendChild(tips);
}

/**
 * 没收到时的三条排查
 *
 * 都是「接口通了、那头却没人收到」这一档才成立的原因。接口本身没通的话，
 * 服务端那一侧已经给了各自那一句，不必在这里再猜一遍。
 */
function troubleshooting() {
  const box = el('div', 'su-tips');
  box.id = 'setup-tips';
  [['机器人被踢出群了',
      phrase('bot.platform',
        v => '到 ' + v + ' 里看一眼机器人还在不在那个群。不在就拉回去，再回上一步重选一次。',
        '到聊天软件里看一眼机器人还在不在那个群。不在就拉回去，再回上一步重选一次。')],
    [phrase('bot.family', v => 'Token 与 ' + v + ' 那头不一致', 'Token 与机器人那头不一致'),
      phrase('bot.family',
        v => '回第 2 步重测一次；两个 Token 要和 ' + v + ' 的配置里一模一样。',
        '回第 2 步重测一次；两个 Token 要和机器人配置里一模一样。')],
    [phrase('bot.family',
        v => phrase('bot.platform', p => v + ' 那头的 ' + p + ' 掉线了', v + ' 那头掉线了'),
        phrase('bot.platform', p => '机器人那头的 ' + p + ' 掉线了', '机器人那头掉线了')),
      phrase('bot.platform',
        v => '接口通不代表 ' + v + ' 在线。到它自己的界面看登录状态，掉了就重新扫码。',
        '接口通不代表账号在线。到它自己的界面看登录状态，掉了就重新扫码。')]]
    .forEach(pair => {
      const item = el('div', 'su-tip');
      const title = el('b');
      title.textContent = pair[0];
      item.appendChild(title);
      const line = el('p');
      line.textContent = pair[1];
      item.appendChild(line);
      box.appendChild(item);
    });
  return box;
}

/**
 * 「已替你定好的初始值（都可改）」
 *
 * 每一行都从现值算（见 initialRows），不是一张写死的表：写死的话，
 * 一台刚被关掉推送的机器上这一行照样写着「开」，而这一段的全部意义
 * 正是让他看见这台机器<b>现在</b>是什么样。
 */
function defaultsBlock() {
  const box = el('details', 'su-defaults');
  const head = el('summary');
  head.id = 'setup-defaults';
  head.textContent = '已替你定好的初始值（都可改）';
  box.appendChild(head);

  // 命令条数没有任何接口给得出来，因此交 null 进去，由那边说「全开」而不写个数。
  // 随手写一个数摆在一排算出来的值中间，是这几行里最难被发现的一处错
  initialRows(store.values, null, store.vocab, seen && seen.status).forEach(item => {
    const line = el('div', 'su-def');
    const text = el('div');
    const name = el('b');
    name.textContent = item.label;
    text.appendChild(name);
    const value = el('p');
    value.textContent = item.text;
    text.appendChild(value);
    if (item.key) {
      const key = el('div', 'keyname');
      key.textContent = item.key;
      text.appendChild(key);
    }
    line.appendChild(text);

    const go = el('a', 'ghost su-go');
    go.href = item.href;
    go.textContent = '改';
    line.appendChild(go);
    box.appendChild(line);
  });

  return box;
}

// ============ 走完之后 ============

function renderDone(host) {
  heading(host, '初始设置完成', '这几步定的东西都已经生效。之后想改，全在设置页与各功能页里。');

  summaryLines(facts, skips, {
    accounts: seen.login.accounts || [],
    streamers: (seen.status.users || []).length,
    targets: (seen.status.users || []).reduce((n, one) => n + (one.targets || 0), 0),
  }, steps, store.vocab).forEach(line => {
    const item = el('div', 'su-sum');
    item.textContent = '· ' + line;
    host.appendChild(item);
  });

  // 插件步各自可以在这里留一个去处（「去主播页看看」这类）。地址由那一步自己给：
  // 它的页在哪、地址怎么拼，核心一概不知道；自己拼一份的话，两份分叉的那天
  // 点进去会落到别处，而屏幕上看不出任何异常。
  // id 由步骤键拼出来（setup-go-<键>），不收插件报上来的串——那个串会原样成为 DOM 里的 id
  for (const meta of pluginPages) {
    const mod = pluginMods[meta.id];
    let link = null;
    try {
      link = mod && typeof mod.doneLink === 'function' ? mod.doneLink(pluginCtx()) : null;
    } catch (e) {
      link = null;
    }
    if (!link || !link.href) continue;
    const to = el('a', 'ghost su-go');
    to.id = 'setup-go-' + meta.id;
    to.href = link.href;
    to.textContent = link.text || '去看看';
    host.appendChild(to);
  }

  const bar = el('div', 'su-f');
  const go = el('button', 'primary');
  go.type = 'button';
  go.id = 'setup-enter';
  go.textContent = '进控制台';
  go.addEventListener('click', () => { location.hash = '#/home'; });
  bar.appendChild(go);
  host.appendChild(bar);
}
