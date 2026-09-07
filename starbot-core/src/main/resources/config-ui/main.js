/**
 * 入口：整体载入、六页路由与事件绑定
 * 必须最后加载——它在解析时就会调用其余模块里的函数。
 */

import {bindBotForm, botFormHtml, fillBotForms} from './bot.js';
import {$, api, el, esc, markDirty, phrase, say, term} from './core.js';
import {PROBE_ANCHOR, shouldOpenSetup} from './home-model.js';
import {focusStation, loadTargets, mountLinkCard, refreshLinks, sendTestMessage} from './links.js';
import {loadLog, stopFollow, syncLogView} from './log.js';
import {refreshHome, renderStatus, runSelfTest, togglePush} from './overview.js';
import {decoratePushData, loadPushPage, renderStreamers, serializePush, showPush} from './push.js';
import {setAuthState} from './settings-auth.js';
import {copyConfigPath, discard, filterSettings, focusGroup, renderConfigPath, renderGeneral, save, toggleKeyNames}
  from './settings.js';
import {openSetup, stopSetupPolling} from './setup.js';
import {store} from './store.js';
import {loadStreamers, releaseReport, syncStreamersView} from './streamers.js';
import {ask} from './confirm.js';
import {clearIssuedToken, loadTokens} from './tokens.js';

/**
 * 插件带来的页，装好之后每项是 {meta, module}
 *
 * 平台页此前是编译期定死的一句 import：核心必须先认识那个平台，页面才存在。
 * 现在它是运行期的一张清单——核心只知道「有几页、叫什么、脚本在哪」，页里做什么与它无关。
 */
const pages = [];

/**
 * 落在连接页上的那一档，与服务端下发的 slot 取值一致
 *
 * 写成常量而不是三处各写一个字符串：对不上的表现是插件卡挂到了设置页的折页里，
 * 而两处的代码看起来都对
 */
const SLOT_LINKS = 'links';

/**
 * 落在设置页「高级」折页里的那一档
 */
const SLOT_SETTINGS = 'settings';

/**
 * 与首页／推送／主播等并列的一整页，地址 #/<页标识>
 */
const SLOT_TOP = 'top';

/**
 * 首页一张卡，不占导航、无独立地址
 */
const SLOT_HOME_CARD = 'home_card';

/**
 * 初始设置向导里的一步。向导页自己从 /api/pages 取并渲染，这里不挂任何地方
 */
const SLOT_SETUP_STEP = 'setup_step';

/**
 * 已挂上的顶级插件页标识。parseHash 靠它认 #/<id>，不把这些名字写进 PAGE_TAB
 */
const topPageIds = new Set();

/**
 * 按注册清单建出入口与页面容器，并装载各自的脚本
 *
 * 逐个装而不是一次性 Promise.all：入口的先后要与清单一致，
 * 而某一页装不上时也只该影响它自己——其余的页照常可用，那一页上写清为什么空着。
 *
 * 落位由插件自己申报：连接页上的一张卡、设置页「高级」下的一张子页
 * （地址 #/settings/<页标识>）、与内置页并列的一整页（地址 #/<页标识>），
 * 或首页一张卡（不占导航、无独立地址）。
 * 核心不认识任何一个具体平台，也就无从判断某一页该摆在哪儿。
 * 与插件之间的约定一个字未改：仍是清单里的 id/displayName/script，
 * 加上脚本导出的 render/refresh/status ——它们不知道自己被挂在哪里。
 */
async function mountPages() {
  let list = [];
  try {
    list = (await api('/pages')).pages || [];
  } catch (e) {
    // 取不到清单只是没有平台页，控制台其余部分照常
    return;
  }

  // 一个折进设置页的插件页都没有时整块不显示：一个点开是空的折页，比没有这个折页更费解。
  // 只数 SETTINGS：顶级页、连接卡与首页卡都不进这个折页，有它们不等于折页里有子页
  if (list.some(meta => meta.slot === SLOT_SETTINGS)) $('#plugin-adv').style.display = '';

  const slot = $('#page-tabs');
  for (const meta of list) {
    const container = meta.slot === SLOT_LINKS ? mountLinkCard(meta)
      : meta.slot === SLOT_TOP ? mountTopPage(meta)
      : meta.slot === SLOT_HOME_CARD ? mountHomeCard(meta)
      : meta.slot === SLOT_SETUP_STEP ? null
      : mountSettingsPage(meta, slot);
    if (!container) continue;

    try {
      const module = await import('/config/assets/' + meta.script);
      pages.push({meta, module});
      module.render?.(container);
    } catch (e) {
      // 装不上的页也要留在清单里。清单是路由认页的唯一依据，不留就等于 #/settings/<页标识>
      // 认不出它，于是下面这句「为什么空着」永远显示不出来——而这句话正是给装不上时看的
      if (!pages.some(item => item.meta.id === meta.id)) pages.push({meta, module: {}});
      container.innerHTML = '<p class="hint">' + esc(meta.displayName) + ' 的页面没能载入：' + esc(e.message)
        + '。该插件的其余功能不受影响。</p>';
    }
  }
}

/**
 * 建出设置页「高级」下的一张子页与它的入口
 * @param meta 页面清单里的一项
 * @param slot 标签条容器
 * @return {HTMLElement} 插件往里渲染的容器
 */
function mountSettingsPage(meta, slot) {
  const button = el('button');
  button.type = 'button';
  button.dataset.tab = meta.id;
  button.textContent = meta.displayName;
  button.addEventListener('click', () => switchTab(meta.id));
  slot.appendChild(button);

  const section = el('section', 'pgpage');
  section.id = meta.id;
  $('#page-sections').appendChild(section);
  return section;
}

/**
 * 建出与内置页并列的一整页：侧栏入口在「设置」之前，容器追加到主区
 * @param meta 页面清单里的一项
 * @return {HTMLElement} 插件往里渲染的容器
 */
function mountTopPage(meta) {
  topPageIds.add(meta.id);

  const nav = $('#nav');
  const settings = nav && nav.querySelector('[data-page="settings"]');
  const link = el('a');
  link.href = '#/' + meta.id;
  link.dataset.page = meta.id;
  link.textContent = meta.displayName;
  if (settings) nav.insertBefore(link, settings);
  else if (nav) nav.appendChild(link);
  // 内置入口在载入时绑过「再点当前项也走一遍」；动态加上的这一条当时还不在
  link.addEventListener('click', () => { if (location.hash === link.getAttribute('href')) applyRoute(); });

  const section = el('section', 'page');
  section.id = 'page-' + meta.id;
  $('#main').appendChild(section);
  return section;
}

/**
 * 已挂上的首页卡，按清单顺序排在探针卡后面
 */
const homeCards = [];

/**
 * 建出首页一张卡：形制与「探针」相同，插在它后面、同级
 * @param meta 页面清单里的一项
 * @return {HTMLElement} 插件往里渲染的容器
 */
function mountHomeCard(meta) {
  const card = el('div', 'nv-card hcard');
  card.id = meta.id;
  const heading = el('h3');
  heading.textContent = meta.displayName;
  card.appendChild(heading);
  const body = el('div');
  card.appendChild(body);

  const probes = $('#home-probes');
  const after = homeCards.length ? homeCards[homeCards.length - 1] : probes;
  if (after && after.parentNode) {
    after.parentNode.insertBefore(card, after.nextSibling);
  }
  homeCards.push(card);
  return body;
}

/**
 * 调某一页的钩子，它抛出来只算它自己那一页出事
 *
 * 钩子里跑的是插件的代码。不拦在这里的话，一页抛出去会当场中断整轮遍历——
 * 排在它后面的页就都不再刷新，而调用方那一趟整体载入也跟着失败，
 * 屏幕上只剩一句与出事那一页毫无关系的「载入失败」。
 */
function callPage(page, hook, ...args) {
  try {
    page.module[hook]?.(...args);
  } catch (e) {
    say(page.meta.displayName + ' 页出错：' + e.message, 'err');
  }
}

/**
 * 让各插件页重取自己的数据
 */
export function refreshPages() {
  pages.forEach(page => callPage(page, 'refresh'));
}

/**
 * 把运行状态转给各插件页，由它们自己挑要用的部分
 */
export function pageStatus(data) {
  pages.forEach(page => callPage(page, 'status', data));
}

/**
 * 把连接面上写死的中性词换成插件申报的带名版
 *
 * HTML 里只留中性兜底，启动后再填：核心自身零词表时屏幕上仍是「机器人」，
 * 装了适配器才出现实现名。取不到词表不弹条，保留 HTML 里那一版。
 */
function applyConnectionVocab() {
  const title = $('#card-napcat .lc-hd b');
  if (title) {
    title.textContent = phrase('bot.impl', v => '机器人（' + v + ' 等）', '机器人');
  }
  const hint = $('#card-napcat .lc-body > p.hint');
  if (hint) {
    hint.innerHTML = '机器人指 ' + esc(term('bot.impl.hint', '机器人程序'))
      + '，NovaBot 通过它把消息发到 ' + esc(term('bot.platform', '聊天平台'))
      + '。两个 Token 留空表示<b>保持原值</b>，不会被抹掉。';
  }
  const entryHint = $('#napcat-entry p.hint');
  if (entryHint) {
    entryHint.innerHTML = '扫码登录 ' + esc(term('bot.platform', '聊天平台'))
      + '、查看它自己的运行日志，'
      + phrase('bot.impl', v => '这些事在 ' + esc(v) + ' 的界面里做', '这些事在机器人的界面里做')
      + '。你已经登录了这个控制台，<b>不必再登录它一次</b>——点开时会替你办好。';
  }
  const open = $('#napcat-open');
  if (open) {
    open.textContent = phrase('bot.impl', v => '打开 ' + v + ' 界面 ↗', '打开机器人界面 ↗');
  }
}

export async function load() {
  say('载入中…');
  try {
    const [s, v] = await Promise.all([api('/schema'), api('/values')]);
    store.schema = s.groups || [];
    // 归不了组的那几项单独收着，由设置页摆在最上面一块里，见 store.ungrouped
    store.ungrouped = s.ungrouped || [];
    // 生效时机摊平成一张表：底部改动条要按键查，而字段表是按分组套着的
    store.effects = {};
    for (const g of store.schema) {
      for (const f of g.fields) store.effects[f.name] = f.effect;
    }
    store.values = v.values || {};
    // 哪几项是按旧位置生效的，要跟着值一起进来：值与它的出处分开取，两次之间配置一变就对不上了
    store.legacy = v.legacy || {};
    store.dirty = {};
    renderGeneral();
    const here = parseHash();
    focusCard(here.name, here.card);

    const [d, st, h, p] = await Promise.all([
      api('/datasource'), api('/status'), api('/handlers'), api('/platforms')]);
    // 上一次保存留下的「还欠一次重启」也在这一趟里进来，见 renderStatus
    renderStatus(st);
    renderConfigPath(st.configPath);

    store.handlerList = h.handlers || [];
    store.senderList = st.senders || [];
    // 能添加哪些平台的主播由已注册的数据源服务决定，界面不替任何一个平台作主
    store.platforms = p.platforms || [];
    // 推送配置解析不了是这一趟里唯一「载入成功了但有话要说」的情况。
    // 记一位是因为末尾那句 say('') 会把状态栏清空——不记的话，这条提示刚显示就被自己抹掉
    let pushBroken = false;
    try {
      store.pushData = JSON.parse(d.content || '[]');
      // 快照要取序列化之后的形态，与改动计数比的是同一把尺；
      // 直接存 d.content 的话，文件里的缩进与键序都会算成「改动」
      store.pushSaved = serializePush();
    } catch (e) {
      // 内容不合法时不在界面上开一个编辑器让人现场改文件——那条路已经撤了。
      // 这里只把话说清楚：改哪个文件、在哪儿改，路径就显示在设置页底部
      store.pushData = [];
      store.pushSaved = '[]';
      pushBroken = true;
      say('推送配置 datasource.json 不是合法 JSON，界面上暂时看不到已配好的主播。'
        + '请到服务器上修正该文件后重新载入', 'err');
    }
    renderStreamers();
    decoratePushData();
    try {
      store.vocab = (await api('/vocab')).terms || {};
    } catch (e) {
      store.vocab = {};
    }
    applyConnectionVocab();
    refreshPages();
    loadPushPage();
    // 首页要四份数据一起算，与这一趟里的 /status 各取各的：它那一趟晚一点回来，
    // 画出来的是更新的一份，不会与这里的运行状态互相矛盾
    refreshHome();
    fillBotForms();

    const count = store.schema.reduce((n, g) => n + g.fields.length, 0);
    $('#head-sub').textContent = count + ' 个配置项 · ' + store.schema.length + ' 个分组';
    if (!pushBroken) say('');
  } catch (e) {
    say('载入失败：' + e.message, 'err');
  }
  markDirty();
}

/**
 * 六页导航的路由名 → 该页当家的那个旧页签
 *
 * store.tab 仍取旧页签名，没有跟着改成路由名：它是插件页读得到的东西
 * （带扫码登录的那类插件页据 `store.tab === 'overview'` 决定要不要接着轮询二维码），
 * 换一套取值就是悄悄改了对插件的约定，而表现是那张二维码扫完不自己变。
 *
 * 一页装下两个旧页签时，取有草稿态的那个：底部保存按钮照 store.tab 决定存去哪
 * （见 saveTarget）。「群与成员」与「只读口令」都是改完立刻落盘的，不占这一位。
 */
const PAGE_TAB = {
  home: 'overview', push: 'push', streamers: 'analytics',
  log: 'log', links: 'bot', settings: 'settings', setup: 'setup',
};

/**
 * 旧页签名 → 它现在所在的地址
 *
 * 旧页签在新导航里不再一一对应一页：群与成员并进了「QQ 推送」，只读口令并进了「连接」。
 * 调用方只知道自己要去的那个页签叫什么，这张表因此由这里维护，调用点一处未改。
 */
const TAB_HASH = {
  overview: '#/home', push: '#/push', sessions: '#/push', analytics: '#/streamers',
  log: '#/log', bot: '#/links', tokens: '#/links', settings: '#/settings', setup: '#/setup',
};

/** 上一次落在哪个路由。离开某页时要收的尾巴据此判断 */
let route = '';

/**
 * 使用者按过「稍后再说」没有
 *
 * 按过之后不再把他从首页转去初始设置页。<b>只记在这一趟里</b>，刷新之后重新拦一次——
 * 而这只影响五步一件都还没做的机器。刷新一次又被拦住，
 * 说的正是「这台机器上一件事也还没配」。
 */
let later = false;

/**
 * 最近一次 /api/status 与 /api/login，供进首页时问五步做了没
 *
 * 还没取到时为 null：按「没配过」拦的话，已经配好的机器会先闪一下初始设置页。
 */
let homeStatus = null;
let homeLogin = null;

/**
 * 解析地址栏。认不出来的路由一律当首页，不留白屏
 *
 * 页签时代的旧地址（#/tokens、#/bot 这类）不当作认不出来：收藏夹与旧文档里留着它们，
 * 掉回首页的表现是「点进去到了别的地方」，而那看起来像是收藏错了。此处转到它现在所在的页。
 *
 * 问号后面那一段是「进去之后看哪一块」（#/links?card=platform），不参与认页——
 * 拿它一起去查路由表的话，带参数的地址一律认不出来，于是全掉回首页。
 * @return {{name: string, sub: string, tail: string, card: string, redirect: string}}
 *         路由名、子路由（插件页标识 / 主播）、第三段（通道号）、要看的那一块、旧地址该转去哪
 */
function parseHash() {
  const raw = (location.hash || '').replace(/^#\/?/, '');
  const cut = raw.indexOf('?');
  const path = cut < 0 ? raw : raw.slice(0, cut);
  const query = cut < 0 ? '' : raw.slice(cut + 1);

  const parts = path.split('/').filter(Boolean);
  const name = parts[0] || 'home';
  const card = /^card=([A-Za-z0-9_-]+)$/.exec(query);
  const known = PAGE_TAB[name] || topPageIds.has(name);

  return {
    name: known ? name : 'home',
    sub: parts[1] || '',
    // 推送页有三段：#/push/<主播>/<通道号>。第三段只这一页用得上，
    // 但解析放在这里而不是那一页自己再切一遍地址栏——两处各切一遍的话，
    // 「认页」与「认页里的哪一个」会按两套规则来
    tail: parts[2] || '',
    card: card ? card[1] : '',
    // 名字不叫 legacy：那个词是 store 上一份共享状态的名字（按旧位置生效的配置项），
    // 在界面文件里裸着出现即为 ReferenceError，因此有一条判据在盯着它——它当场逮住了这一处
    redirect: !known && TAB_HASH[name] ? TAB_HASH[name] : '',
  };
}

/**
 * 按地址栏滚到「要看的那一块」
 *
 * 连接页的卡、首页健康自检、设置页的某一组，问的都是地址栏里的 card。
 * 首屏整体载入会再画一次设置页分组，因此 load 在画完之后也走这里——
 * 只在切页时滚的话，从收藏夹打开带 card 的设置页地址会停在页顶。
 */
function focusCard(name, card) {
  focusStation(name === 'links' ? card : '');
  if (name === 'home' && card === 'probes') {
    const box = $('#' + PROBE_ANCHOR);
    if (box) box.scrollIntoView({block: 'start', behavior: 'smooth'});
  }
  if (name === 'settings' && card) focusGroup(card);
}

/**
 * 记下这一趟首页用的两份回包，并在「五步全没做、正要画首页、没点稍后再说」时转到初始设置
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @return {boolean} 已经改了地址、调用方不该再画首页
 */
export function considerSetupRedirect(status, login) {
  homeStatus = status || null;
  homeLogin = login || null;
  const {name} = parseHash();
  if (shouldOpenSetup(homeStatus, homeLogin) && name === 'home' && !later) {
    location.hash = '#/setup';
    return true;
  }
  return false;
}

/**
 * 按地址栏切页
 *
 * 只认地址栏、不认「谁点了哪个链接」：刷新、收藏、后退三种进法走的都是这一条路，
 * 各写一套的话，从地址栏直接进 #/settings 与点侧栏进去会渲染出两种结果。
 * @param withData 是否顺带重取本页的数据。首屏那一次只摆版式，数据由随后的整体载入取
 */
function applyRoute(withData = true) {
  const {name, sub, tail, card, redirect} = parseHash();

  // 旧地址转到它现在所在的页。改地址会再触发一次 hashchange，这一趟到此为止
  if (redirect) {
    location.hash = redirect;
    return;
  }

  // 从没配过的机器上，首页与根地址一律转初始设置页。
  //
  // 只拦这两处，不拦别的：使用者从初始设置页点去连接页看一眼再回来，是正当走法，
  // 拦下来的表现是「除了第一步哪儿也去不了」。而 home 是默认落点——认不出来的地址
  // 也归到它，所以拦住它就等于拦住了「随手打开控制台」这条路。
  //
  // 看的是五步完成了 0 步，不是配置文件在不在。同意协议会写出 application.yml，
  // 按文件在不在判的话，刚装好的机器会停在空首页。
  if (shouldOpenSetup(homeStatus, homeLogin) && name === 'home' && !later) {
    location.hash = '#/setup';
    return;
  }

  // 设置页折页里的插件页只在落到设置页的那一档里找：落在连接页上的那些是卡不是页，
  // 一起找的话，#/settings/<平台标识> 会打开一张空的折页，而那张卡明明在连接页上。
  // 顶级页走 #/<id>，高亮与开关靠下面 .page／data-page 那两句，不另写一套
  const plugin = name === 'settings' && sub
    ? pages.find(item => item.meta.id === sub && item.meta.slot === SLOT_SETTINGS) : null;
  const topPage = pages.find(item => item.meta.slot === SLOT_TOP && item.meta.id === name);

  // 离开「连接」页就把刚签发的口令从 DOM 里抹掉。界面上写着「离开本页后无法再次查看」，
  // 这一行就是那句话的实现——留着它，那句话只是句话
  if (route === 'links' && name !== 'links') clearIssuedToken();
  // 离开日志页就停掉工程日志那个「跟随最新」：留着的话，使用者在别的页上待一夜，
  // 它还在每 3 秒读一次日志文件
  if (route === 'log' && name !== 'log') stopFollow();
  // 离开初始设置页就停掉那一页等扫码的轮询：不停的话，使用者点去连接页看一眼，
  // 那一页还在每 3 秒问一次登录状态，而它已经不在屏幕上了
  if (route === 'setup' && name !== 'setup') stopSetupPolling();
  // 离开主播页就把报告图那个临时地址撤掉：一张几百 KB 的图会一直被它拴在内存里，
  // 而屏幕上早就换了别的页
  if (route === 'streamers' && name !== 'streamers') releaseReport();
  // 是不是刚从别的页进来。推送页在页内换选中项也走改地址栏这条路，
  // 每换一次都重取一遍名单与额度的话，点树上一行要等四个请求回来才动
  const entering = route !== name;
  route = name;

  document.querySelectorAll('#nav a').forEach(a => {
    if (a.dataset.page === name) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  document.querySelectorAll('.page').forEach(x => x.classList.toggle('on', x.id === 'page-' + name));
  document.querySelectorAll('.pgpage').forEach(x => x.classList.toggle('on', !!plugin && x.id === plugin.meta.id));
  document.querySelectorAll('#page-tabs button').forEach(
    x => x.classList.toggle('on', !!plugin && x.dataset.tab === plugin.meta.id));
  // 初始设置页是独立版式：这台机器可能还没上锁，页头上的「退出登录」无从谈起
  document.documentElement.classList.toggle('chromeless', name === 'setup');
  // 日志页有两半（时间线与工程日志），哪一半该显示由地址栏定，与取不取数据无关——
  // 合进下面那一趟的话，直接打开 #/log/eng 会先闪一下时间线那一半
  if (name === 'log') syncLogView();
  // 主播页三块（列表、详情、某一场）同理：哪一块该显示由地址栏定，与取不取数据无关。
  // 合进下面那一趟的话，直接打开某一场的地址会先闪一下列表
  if (name === 'streamers') syncStreamersView();
  // 推送页选中的是哪一位主播、哪一个通道同理：它只由地址栏定，与取不取数据无关。
  // 合进下面那一趟的话，点树上一行会先闪一下上一次选中的那一页
  if (name === 'push') showPush(sub, tail);
  // 插件页是折起来的，点它的入口进来时要替使用者展开，否则地址对了而屏幕上什么都没变
  if (plugin) $('#plugin-adv').open = true;

  store.tab = plugin ? plugin.meta.id : (topPage ? topPage.meta.id : PAGE_TAB[name]);

  // 窄屏上导航是横向滚动的，靠右的项会落在视野外。选中却看不见等于没有选中标记
  $('#nav a[aria-current="page"]')?.scrollIntoView({inline: 'center', block: 'nearest'});

  // 要看哪一块得赶在取数之前记下：真正滚过去是在卡画完之后，
  // 此刻卡上还是上一刻的高度，滚了也白滚
  focusCard(name, card);

  markDirty();
  if (!withData) return;

  clearTimeout(store.accountTimer);
  // 首页四份数据一起取，见 refreshHome
  if (name === 'home') { refreshHome(); refreshPages(); }
  // 每次进入都重取：群里随时可能有人订阅或关掉命令，缓存的画面会误导人。
  // 页内换选中项不重取——那一下没有任何东西会变，重取只是让点一行慢四个请求
  else if (name === 'push') { if (entering) loadPushPage(); }
  // 在本页里点开一位主播、翻一页场次走的都是改地址栏这一条路，因此每次进来都重取：
  // 这一页现在是哪一块、哪一位、第几页，全部只存在地址栏里，本页自己不记。
  // 侧栏那三行运行状态由这里刷，不由主播页刷——那一页去调 renderStatus 的话，
  // 它与 overview.js 会互相 import 成环，而环里谁先求值取决于加载顺序
  else if (name === 'streamers') { loadStreamers(); api('/status').then(renderStatus); }
  // 每次进入都重建只读口令那一块：顺带抹掉上一次留在屏幕上的口令明文。
  // 三张卡与名单跟着一起重取——群随时会被踢，缓存的名单会让人对着一个已经不在的群发测试消息
  else if (name === 'links') { loadTokens(); refreshLinks(); refreshPages(); loadTargets(false); }
  // 翻天、改筛选、进出工程日志走的都是改地址栏这一条路，因此每次进来都重取：
  // 日志页的「现在是哪一天、筛了什么」全部只存在地址栏里，本页自己不记
  else if (name === 'log') loadLog();
  // 每次进入都重问一遍这台机器现在什么样：上一趟离开之后使用者可能去别处上了锁、加了主播，
  // 缓存的画面会把已经做完的那一步画成没做，而那正是这一页唯一要回答的问题
  else if (name === 'setup') openSetup();
  else if (plugin || topPage) { api('/status').then(renderStatus); callPage(plugin || topPage, 'refresh'); }

  // 点名要看某一块时不回顶：滚到顶再滚下去，屏幕会先跳一下
  if (!card) window.scrollTo(0, 0);
}

/**
 * 跳到某个旧页签现在所在的位置
 * @param name 旧页签名，或插件页标识
 */
function switchTab(name) {
  const hash = TAB_HASH[name] || (pages.some(item => item.meta.id === name) ? '#/settings/' + name : '#/home');
  // 地址没变就不会有 hashchange，此时直接走一遍——否则点第二次「前往」毫无反应
  if (location.hash === hash) applyRoute();
  else location.hash = hash;
}

// 包一层：事件回调的第一个参数是 HashChangeEvent，直接挂上去会被当成 withData
window.addEventListener('hashchange', () => applyRoute());

// 点已经选中的那一项，地址没变就不会有 hashchange。页签时代点同一个页签是会重取的，
// 这里把那个动作接回来——不接的话，「再点一下看看最新的」这条路会悄悄消失
document.querySelectorAll('#nav a').forEach(a => {
  a.addEventListener('click', () => { if (location.hash === a.getAttribute('href')) applyRoute(); });
});

$('#save').addEventListener('click', save);
$('#discard').addEventListener('click', discard);
$('#cfg-path').addEventListener('click', copyConfigPath);
$('#test-send').addEventListener('click', sendTestMessage);
// 「稍后再说」：地址由 href 带去首页，这里只记下别再把他转回来。
// 记在这里而不是 setup.js 里，是因为拦人的那一条判断也在这里——两处各记一份的话，
// 记下了却仍被转回来，表现是这个链接点了没反应
$('#setup-later').addEventListener('click', () => { later = true; });
// 让机器人重新去问一遍：群是随时会变的，而缓存住的名单会让人对着一个已经退了的群发测试消息
$('#test-refresh').addEventListener('click', () => loadTargets(true));
$('#selftest-run').addEventListener('click', runSelfTest);
// 「添加主播」与整棵树的接线都在 push.js 里：它建出来的那些控件不写在 index.html 上，
// 在这里按 id 取只会取到 null
// 搜索与「只看改过的」只改可见性，不重绘：重绘会丢掉正在编辑的那一格，
// 而使用者常常是一边改一边搜下一项
$('#set-search').addEventListener('input', filterSettings);
$('#only-changed').addEventListener('change', filterSettings);
$('#show-keys').addEventListener('change', toggleKeyNames);
$('#toggle-push').addEventListener('click', togglePush);
/**
 * 未绑定验证器时的引导卡片
 *
 * 必须让用户先输一次验证码才算绑定成功——少了这一步，扫码没扫上的人会以为绑好了，
 * 下次登录被自己的二次验证挡在门外，而那时已经没有界面可以撤销了。
 */
async function renderTotpSetup() {
  const box = $('#totp-setup');
  const setup = await api('/auth/totp/setup');
  if (!setup.success) return;

  box.style.display = '';
  box.innerHTML =
    '<h3>建议绑定验证器</h3>'
    + '<p>面板开到公网后，只有口令这一道防线。用任意验证器应用扫码，再输一次它给出的数字即可。</p>'
    + '<div class="totp-body">'
    + (setup.qrCode ? '<img src="data:image/png;base64,' + esc(setup.qrCode) + '" alt="二维码">' : '')
    + '<div class="totp-side">'
    + '<label>不方便扫码时手动输入这串密钥</label>'
    + '<code>' + esc(setup.secret) + '</code>'
    + '<div class="totp-confirm">'
    + '<input id="totp-code" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="6 位数字">'
    + '<button type="button" id="totp-enroll">确认绑定</button>'
    + '<button type="button" id="totp-skip">暂不绑定</button>'
    + '</div><span class="status" id="totp-msg"></span>'
    + '</div></div>';

  $('#totp-enroll').addEventListener('click', async () => {
    const r = await api('/auth/totp/enroll', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({code: $('#totp-code').value})
    });
    if (r.success) {
      box.style.display = 'none';
      say(r.message, 'ok');
      return;
    }
    $('#totp-msg').textContent = r.message;
    $('#totp-msg').className = 'status err';
  });

  $('#totp-skip').addEventListener('click', async () => {
    await api('/auth/totp/skip', {method: 'POST'});
    box.style.display = 'none';
    // 只跳过这一次登录。要永久关掉得去改 starbot.core.config-ui.auth.totp，
    // 那是个该显式做出的决定，不该由一次「等会儿再说」代劳
    say('本次登录不再提示。要永久关闭请改配置项 auth.totp');
  });
}

$('#logout').addEventListener('click', async () => {
  await api('/auth/logout', {method: 'POST'});
  location.reload();
});
$('#logout-all').addEventListener('click', async () => {
  if (!await ask({title: '继续？',
    body: '将注销所有设备上的登录，包括当前这一个。'})) return;
  await api('/auth/logout?all=true', {method: 'POST'});
  location.reload();
});
// 先注入 DOM 再绑定，否则 bindBotForm 取到的是 null
$('#bot-form').innerHTML = botFormHtml('bot');
bindBotForm('bot');

// NapCat 控制台入口。凭据没配好就不显示这一块——
// 显示了点进去才报错，比不显示更难懂
api('/napcat/state')
  .then(state => { if (state.configured) $('#napcat-entry').style.display = ''; })
  .catch(() => {});
// 走引导页而不是直接跳 WebUI：凭据要由同源脚本写进 localStorage，
// 服务端下发再多头也写不进浏览器的存储
$('#napcat-open').addEventListener('click', () => { location.href = '/config/napcat-bootstrap'; });

// 登录态必须先于正式载入取到：CSRF 令牌从这里来，缺了它所有写请求都会被拒。
// 取不到也照常载入——未启用口令登录时本就没有令牌，读接口不受影响
api('/auth/state')
  .then(state => {
    store.csrfToken = state.csrfToken || '';
    // 签发只读口令要重新校验一次凭据，验证码框显示与否照这一位来，不照配置项猜
    store.totpRequired = !!state.totpRequired;
    // 配置文件在不在。进首页该不该转到初始设置不看这一位（看五步），仍收下以免别处读它
    store.setupDone = typeof state.setupDone === 'boolean' ? state.setupDone : null;
    $('#auth-actions').style.display = state.enabled ? '' : 'none';
    // 「登录与安全」那一组要按这几位决定摆哪一版（改口令还是重设口令、开关在哪一档），
    // 因此必须赶在下面那趟整体载入之前交进去——那一趟里就要画它了
    setAuthState(state);
    // 从后门进来的那一次，控制台顶上常驻一条。它不随翻页消失：
    // 那道门开着这件事没有任何其它现象
    $('#op-banner').style.display = state.operatorSession ? '' : 'none';
    if (state.totpSetupNeeded) renderTotpSetup();
  })
  .catch(() => {})
  // 五步做了没要 /status 与 /login。跟登录态一起取，applyRoute 才问得着「该不该落到初始设置」
  .then(() => Promise.all([
    api('/status').catch(() => null),
    api('/login').catch(() => null),
  ]).then(([status, login]) => {
    homeStatus = status;
    homeLogin = login;
  }))
  // 插件页要先挂上去，随后那一趟整体载入才有东西可刷
  .finally(() => mountPages().finally(() => { applyRoute(); load(); }));

// 先按地址栏摆好版式，不等接口回来
//
// 把这一步一并放进上面那条链里的话，接口慢或者不通时屏幕上一页都不显示——
// 六个 .page 默认都是收起的，而「哪一页该展开」这件事本身不需要任何接口就知道。
// 这一趟只摆版式不取数据：数据由上面那条链走完之后的整体载入去取
applyRoute(false);
