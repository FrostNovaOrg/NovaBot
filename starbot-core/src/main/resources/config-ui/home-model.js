/**
 * 首页视图模型
 *
 * 把 /api/status、/api/login、/api/timeline、/api/at-all/quota 四份回包算成屏幕上要显示的东西：
 * 链路三段的灯色与说明、六项探针、顶部横条、待办、现在与今日（含 @全体成员 已用）、今天发生了什么。
 *
 * 刻意不碰 DOM，也不发请求。首页要在八种情形下都说得对——正常、首次安装、机器人掉线、
 * 直播平台掉登录、直播间断流、推送变慢、静音时段中、已暂停推送——而这八种情形
 * 在真机上凑齐一次的代价极高：要么等故障发生，要么去改线上配置。算成纯函数之后，
 * 喂八份回包跑一遍就能逐条对答案（tools/home-model-check.sh），渲染那一层只管把算好的摆上去。
 *
 * 本文件里没有任何一个直播平台的名字：站名取自运行时下发的账号显示名，
 * 说明取自探针自己给的那句话。写死一个平台名，这一页就替一件可能没装的东西说了话。
 */

import {esc} from './core.js';

/** 同义 core.js 的 term：有键用词，无键用中性兜底。模型不读全局。 */
function word(terms, key, fallback) {
  return (terms && terms[key]) || fallback;
}

/** 同义 core.js 的 phrase：词在则套进 withTerm，词缺则整句退成中性 without。 */
function say(terms, key, withTerm, without) {
  const v = terms && terms[key];
  return v ? withTerm(v) : without;
}

/**
 * 内置五步，闭集
 *
 * {@code skippable} 是「界面上给不给跳过按钮」，不是「不填也能过」——
 * 第 3 步跳过等于选了免登录（要先过一段后果确认），第 4 步跳过等于不加主播（同样要确认）。
 * 第 1、2 步不给跳过按钮：没上锁的控制台谁都进得来，没连上机器人一条消息也发不出去。
 * 插件步插在「主播」之后、「试发」之前，见 {@link withPluginSteps}。
 */
export const SETUP_STEPS = [
  {key: 'lock', title: '给控制台上把锁', skippable: false},
  {key: 'bot', title: '连上机器人', skippable: false},
  {key: 'account', title: '登录直播平台', skippable: true},
  {key: 'streamer', title: '第一位主播，推到哪', skippable: true},
  {key: 'test', title: '发一条试试', skippable: false},
];

const BUILTIN_STEP_KEYS = new Set(SETUP_STEPS.map(step => step.key));

/**
 * 内置五步加上插件申报的向导步骤
 *
 * 只收 {@code slot === 'setup_step'} 的页。插在内置锚点之后、「试发」之前，
 * 按 order 升序（同 order 按 id）。无插件页时返回 {@link SETUP_STEPS} 本身。
 * @param pages /api/pages 的 pages 清单
 * @param terms 词表，缺则机器人那一步仍说「连上机器人」
 * @param afterKey 插件步插在哪一步之后，缺省 {@code 'streamer'}
 * @return {{key: string, title: string, skippable?: boolean, plugin?: boolean}[]}
 */
export function withPluginSteps(pages, terms, afterKey) {
  const extra = (Array.isArray(pages) ? pages : [])
    .filter(page => page
      && page.slot === 'setup_step'
      && page.id
      && !BUILTIN_STEP_KEYS.has(page.id))
    .slice()
    .sort((a, b) => {
      const order = (Number(a.order) || 0) - (Number(b.order) || 0);
      return order !== 0 ? order : String(a.id).localeCompare(String(b.id));
    })
    .map(page => ({key: page.id, title: page.displayName, plugin: true}));
  let table = SETUP_STEPS;
  if (extra.length) {
    const want = afterKey || 'streamer';
    let at = SETUP_STEPS.findIndex(step => step.key === want);
    if (at < 0) at = SETUP_STEPS.findIndex(step => step.key === 'streamer');
    table = [...SETUP_STEPS.slice(0, at + 1), ...extra, ...SETUP_STEPS.slice(at + 1)];
  }
  return table.map(step => step.key !== 'bot' ? step : Object.assign({}, step, {
    title: say(terms, 'bot.platform', v => '连上 ' + v + ' 机器人', '连上机器人'),
  }));
}

/**
 * 探针级别 → 灯色
 *
 * 探针那一侧的取值是闭集。认不出的一律当熄灯而不是当正常：一个读不懂的级别
 * 被显示成绿灯，比显示成灰的更危险。
 */
const LAMP_OF = {OK: 'ok', DEGRADED: 'warn', DOWN: 'err'};

/**
 * 灯色由轻到重
 *
 * 「熄灯」排在最轻的一端，它既不是好也不是坏，而是「这一段此刻无从谈起」——
 * 还没连上机器人、正处在静音时段，都是这种。
 */
const SEVERITY = ['off', 'ok', 'warn', 'err'];

/**
 * 一组灯里最重的那个，空组为熄灯
 * @param levels 灯色
 * @return {string} 最重的灯色
 */
export function worstLamp(levels) {
  let worst = 'off';
  for (const level of levels) {
    if (SEVERITY.indexOf(level) > SEVERITY.indexOf(worst)) worst = level;
  }
  return worst;
}

/**
 * 取某一档范围内的探针
 *
 * 按探针自报的 scope 归并，不按名字认：名字是随时会改的东西，
 * 按名字分档的判据在探针改个显示名的那天静默失效，而失效方向是「这一段从此永远绿着」。
 *
 * 连接页也要读同一批探针（见 links-model.js），因此导出去而不是各写一份：
 * 「哪个级别算黄、认不出的级别算什么」这条规则有两份实现的话，改了其中一份的另一份不会跟着变，
 * 而两页说的是同一件事。
 * @param status /api/status 回包
 * @param scope 范围，BOT / PLATFORM / SYSTEM
 * @return 该范围内的探针
 */
export function probesIn(status, scope) {
  return (status.health || [])
    .filter(item => item.scope === scope)
    .map(item => ({
      name: item.name || '',
      lamp: LAMP_OF[item.level] || 'off',
      summary: item.summary || '',
      advice: item.advice || '',
      // 「登录掉了」与「连不上」要人做的事完全不同，前者非得有人去扫码不可。
      // 这一位由探针自己声明，见 HealthProbe#loginState
      loginState: !!item.loginState,
    }));
}

/**
 * 一段链路：灯色取该档探针里最重的那个，说明取那盏灯所属探针自己写的那句话
 * @param probes 该档探针
 * @param fallback 一个探针都没有时说的话
 * @return 灯色与说明
 */
function segment(probes, fallback) {
  const lamp = worstLamp(probes.map(item => item.lamp));
  const source = probes.find(item => item.lamp === lamp);
  return {level: lamp, caption: source ? source.summary : fallback};
}

/**
 * 这台机器上的直播平台叫什么
 *
 * 只装了一个平台时就写它的名字，装了多个或一个都没装时用通名——
 * 界面文件里不写死任何一个平台名，装了哪个是运行期才知道的事。
 * @param login /api/login 回包
 * @return {string} 站名
 */
function platformName(login) {
  const accounts = (login && login.accounts) || [];
  return accounts.length === 1 ? (accounts[0].displayName || '直播平台') : '直播平台';
}

/**
 * 初始设置各步各自成立了没有
 *
 * <b>这组布尔是这条规则唯一的一份实现</b>：首页那条待办要拿它算「N 步里完成了 M 步」
 * （N 为步骤表长度，无插件时为 5），初始设置页要拿它画进度条与决定从第几步接着走。
 * 两处各判一遍的话，同一台机器上首页说「完成了 3 步」而设置页停在第 2 步，
 * 而两边的代码看起来都对。摆在本文件里是因为前四步全部读探针（见 {@link probesIn}），
 * 设置页那一侧反过来引它。
 *
 * 第 5 步「发一条试试」没有任何服务端事实可推——这台机器有没有真的发过一条，
 * 只有初始设置页自己记得住（见 /api/setup/state 的 testSentAt）。因此它由调用方交进来：
 * 不交（{@code null}）时按「前四步都成立」算，那是首页那条待办的读法——
 * 能把消息推出去的前提正是前四步都成立，换成「今天推成功过」的话，
 * 这条待办会在每天零点自己复活，催一件早就做完的事。
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param sent 第 5 步的事实；不知道时传 null
 * @param pages /api/pages 的 pages 清单；缺＝无插件
 * @param pluginDone 插件步事实，{@code {key: boolean}}；缺键＝假
 * @return {boolean[]} 与步骤表等长，各步成立与否
 */
export function setupSteps(status, login, sent, pages, pluginDone) {
  const accounts = (login && login.accounts) || [];
  // 上锁：设了控制台口令（通行密钥跟着口令登录走，没有口令时它签出来的会话打不开任何门）
  const lock = !!status.locked;
  // 连上机器人：这一档的探针没有报红
  const bot = worstLamp(probesIn(status, 'BOT').map(item => item.lamp)) === 'ok';
  // 登录直播平台：每个平台要么登录了，要么被配置明确关掉了（例如免登录模式）。
  // 一个平台插件都没装时这一步不是「没做完」，是没得做，因此算它已定
  const account = accounts.every(item => !!(item.loggedIn || item.disabledReason));
  const streamer = ((status.users || []).length > 0);
  const first = [lock, bot, account, streamer];
  const test = sent === null || sent === undefined ? first.every(Boolean) : !!sent;
  const builtin = {lock, bot, account, streamer, test};
  const done = pluginDone || {};
  return withPluginSteps(pages).map(step => {
    if (step.plugin) return done[step.key] === true;
    return !!builtin[step.key];
  });
}

/**
 * 初始设置这几步走完了几步
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param pages /api/pages 的 pages 清单；缺＝无插件
 * @param pluginDone 插件步事实；缺＝无插件
 * @return {number} 完成的步数，含插件步
 */
export function setupDone(status, login, pages, pluginDone) {
  return setupSteps(status, login, null, pages, pluginDone).filter(Boolean).length;
}

/**
 * 打开控制台该不该直接落到初始设置页
 * <p>
 * 只看五步完成了 0 步。配置文件在不在不算：同意使用协议就会写出 application.yml，
 * 若按文件在不在判，刚装好的机器会停在空首页。
 * <p>
 * 「稍后再说」由调用方自己记（只活在这一趟里），本函数不看。
 * status 还没取到时回 false：当成「没配过」会让已经配好的机器先闪一下初始设置页。
 * @param status /api/status 回包；还没有时传 null
 * @param login /api/login 回包
 * @return {boolean} 该落到初始设置页为 true
 */
export function shouldOpenSetup(status, login) {
  if (!status) return false;
  return setupDone(status, login) === 0;
}

/**
 * 顶部横条
 *
 * 一次只出一条，按「使用者此刻最需要知道哪件事」排序：暂停排在静音前面，
 * 因为两者可以同时成立，而「所有推送都被丢弃」比「这个时段不推」更要紧。
 * @return 横条，没有要说的返回 null
 */
function banner(status, chain, fresh) {
  const quiet = status.quiet || {};

  if (status.pushEnabled === false) {
    return {
      kind: 'paused', level: 'err',
      text: '已暂停，所有推送都会被丢弃',
      action: {text: '恢复推送', href: ''},
    };
  }

  if (quiet.active) {
    return {
      kind: 'quiet', level: 'dim',
      text: '静音中 ' + quiet.start + ' – ' + quiet.end + ' · 期间推送直接丢弃，不攒着',
      action: {text: '去改静音时段', href: '#/settings'},
    };
  }

  if (fresh) {
    return {
      kind: 'setup', level: 'warn',
      text: '还没配置完 · 初始设置五步走完，就能收到第一条推送',
      action: {text: '去初始设置', href: '#/setup'},
    };
  }

  if (chain.bot.level === 'err') {
    return {
      kind: 'botdown', level: 'err',
      text: '机器人连接不可用 · ' + chain.bot.caption,
      action: {text: '去连接页', href: '#/links'},
    };
  }

  if (chain.bot.level === 'warn') {
    return {
      kind: 'botslow', level: 'warn',
      text: chain.bot.caption,
      action: {text: '去连接页', href: '#/links'},
    };
  }

  if (chain.platform.level === 'err' || chain.platform.level === 'warn') {
    return {
      kind: 'platform', level: chain.platform.level === 'err' ? 'err' : 'warn',
      text: chain.platform.caption,
      action: {text: '去连接页', href: '#/links'},
    };
  }

  return null;
}

/**
 * 这台机器没开累计数据
 *
 * 首页那条软待办与主播详情顶上那条小横条<b>问的是同一件事</b>，因此判定只留这一份。
 * 各写各的话，两处会在同一台机器上给出不同的答案——而最常见的分叉是有人把某一处写成
 * {@code !status.totalDataAvailable}：那样一来，接口还没回来（值为 undefined）的那一瞬间
 * 也会被算成「没开」，屏幕上先闪一条说这台机器不行的话，随后又自己消失。
 *
 * 三态里只有明确的 false 才算没开：true 是开着，缺这一栏（旧版服务端）是不知道，
 * 而「不知道」不许读成「没开」。
 * @param status /api/status 回包
 * @return {boolean} 确实没开时为 true
 */
export function totalDataOff(status) {
  return (status || {}).totalDataAvailable === false;
}

/**
 * Webhook 或邮件配好了没有
 * <p>
 * 首页那条「QQ 告警有死角」催的是掉线时还有一路能叫到人，QQ 那路本身会一起掉，
 * 所以不算。设置页药丸各卡仍各报各的；这条待办只看 Webhook 与邮件两位。
 * 邮件那一位由服务端按收件＋SMTP 主机同一口径下发，这里不另判。
 * @param status /api/status 回包
 * @return {boolean} Webhook 或邮件至少一路已配
 */
export function alertConfigured(status) {
  const alerts = (status || {}).alerts || {};
  return !!(alerts.webhook || alerts.mail);
}

/**
 * 「今日」第三格：账号维度的 @全体成员 已用
 * <p>
 * 数字取自 /api/at-all/quota。无机器人或接口失败时写「—」，不编一个 0/10——
 * 0 看起来像今天一次都没用，而「—」说的是这台机器此刻无从谈起。
 * {@code limited} 为假时不画分母：上限是 0 或负数在配额服务里都是「不限」，
 * 画成 3/0 会让人以为今天已经用完了。
 * @param quota /api/at-all/quota 回包；没有或失败时传 null
 * @return {{value: string, label: string, details: object[], more: number}}
 */
export function atAllTile(quota) {
  const empty = {value: '—', label: '@全体成员 已用', details: [], more: 0};
  if (!quota || quota.success === false) return empty;

  const bots = Array.isArray(quota.bots) ? quota.bots : [];
  if (!bots.length) return empty;

  const used = bots.reduce((n, item) => n + Number(item.used || 0), 0);
  const capped = bots.every(item => item.limited);
  const limit = bots.reduce((n, item) => n + (item.limited ? Number(item.limit || 0) : 0), 0);
  const value = capped ? used + '/' + limit : String(used);

  const sessions = Array.isArray(quota.sessions) ? quota.sessions.slice() : [];
  sessions.sort((a, b) => Number(b.used || 0) - Number(a.used || 0));
  const shown = sessions.slice(0, 5);
  const kinds = new Set(sessions.map(item => item.platform || '').filter(Boolean));
  const named = kinds.size >= 2;
  return {
    value,
    label: '@全体成员 已用',
    details: shown.map(item => {
      const rowUsed = Number(item.used || 0);
      const platform = item.platform || '';
      const label = item.platformName || platform;
      const prefix = named && platform ? label + ' 群 ' : '群 ';
      return {
        platform,
        num: item.num,
        used: rowUsed,
        limit: Number(item.limit || 0),
        limited: !!item.limited,
        text: item.limited ? rowUsed + '/' + Number(item.limit || 0) : String(rowUsed),
        who: prefix + item.num,
      };
    }),
    more: Math.max(0, sessions.length - shown.length),
  };
}

/**
 * 今日第三格的 HTML。expanded 只在有明细时有意义。
 * <p>
 * 有明细才是按钮（能开合、带 aria-expanded）；没明细与旁边两格一样是普通格，
 * 点了不会摊开任何东西。
 * @param tile {@link atAllTile} 的返回值
 * @param expanded 此刻是否摊开
 * @return {string}
 */
export function todayAtAllMarkup(tile, expanded) {
  const cell = tile || {value: '—', label: '@全体成员 已用', details: [], more: 0};
  const details = cell.details || [];
  const rows = details.map(item =>
    '<div class="stat-row"><span>' + esc(item.who || ('群 ' + item.num)) + '</span><span>'
    + esc(item.text) + '</span></div>').join('')
    + (cell.more ? '<div class="stat-more">还有 ' + esc(cell.more) + ' 个群</div>' : '');
  const inner = '<div class="stat-v">' + esc(cell.value) + '</div>'
    + '<div class="stat-l">' + esc(cell.label) + '</div>'
    + (rows ? '<div class="stat-drop">' + rows + '</div>' : '');
  if (!details.length) {
    return '<div class="stat">' + inner + '</div>';
  }
  return '<button class="stat stat-exp' + (expanded ? ' open' : '')
    + '" type="button" id="today-atall" aria-expanded="' + (expanded ? 'true' : 'false') + '">'
    + inner + '</button>';
}

/**
 * 待办
 *
 * 只放「要人动手，不动就一直不好」的事。会自己恢复的异常不进这里——
 * 它们在探针那一栏里逐条列着，混进待办只会让这张单子长到没人看。
 */
function todos(status, login, chain, fresh, pages, pluginDone, terms) {
  // 「初始设置还没完成」只在刚装好那一档出现，而且此时它是唯一的一条：
  // 那五步里第 1 步就是上锁、第 2 步就是连机器人，再摆几条说同一件事的待办，
  // 使用者会以为是几件事。
  //
  // 🔴 它<b>不</b>由「此刻各项健不健康」推出来。掉一次登录就说「初始设置没做完」，
  //    等于把一次运行期故障说成使用者没配好，而他明明配过——那条待办会在故障期间
  //    一直挂着催他重走一遍五步，而重走一遍并不能让登录态自己回来。
  if (fresh) {
    const table = withPluginSteps(pages);
    const done = setupDone(status, login, pages, pluginDone);
    return [{
      key: 'setup',
      title: '初始设置还没完成 · ' + table.length + ' 步里完成了 ' + done + ' 步',
      // 不写「走完就生效，不用重启」：连接参数是进程启动时按配置注册的，
      // 第 2 步存下来之后要重启一次那条连接才真的建立起来。写一句不成立的承诺，
      // 换来的是使用者在第 4 步对着一份空名单猜自己填错了什么
      body: '上锁 → 连机器人 → 登录直播平台 → 加第一位主播 → 发一条试试。每一步做完当场落盘。',
      action: '去继续', href: '#/setup', soft: false,
    }];
  }

  const list = [];

  if (chain.bot.level === 'err') {
    list.push({
      key: 'bot',
      title: say(terms, 'bot.impl', v => '重新登录 ' + v, '重新登录机器人'),
      body: chain.bot.advice || chain.bot.caption,
      action: '去连接页', href: '#/links', soft: false,
    });
  }

  if (!status.locked) {
    list.push({
      key: 'lock',
      title: '控制台还没上锁',
      body: '没设口令、也没登记通行密钥，能打到这个端口的人都进得来。',
      action: '去上锁', href: '#/settings', soft: false,
    });
  }

  if (totalDataOff(status)) {
    list.push({
      key: 'total',
      title: '累计数据没开',
      body: '群里只能查本场，「直播间总数据」「总数据排行榜」这两条不会出现在菜单里。'
        + '要开得给 NovaBot 配一个累计存储，找运维。',
      action: '去配', href: '#/settings', soft: true,
    });
  }

  if (!alertConfigured(status)) {
    list.push({
      key: 'webhook',
      title: say(terms, 'bot.platform', v => v + ' 告警有死角，建议再配 Webhook',
        '机器人告警有死角，建议再配 Webhook'),
      body: say(terms, 'bot.platform',
        v => '机器人掉线时 ' + v + ' 那路叫不到你，Webhook 或邮件配好其中一路这条就消失',
        '机器人掉线时告警那路叫不到你，Webhook 或邮件配好其中一路这条就消失'),
      action: '去配', href: '#/settings?card=alert', soft: true,
    });
  }

  // 有新版。排在最后：它是软的——不升级机器照常跑，不该把「重新登录 NapCat」这类
  // 不动就一直不好的事压下去。href 指向站外说明，渲染那一侧按 http 开头给它新开一页。
  // 首装机器到不了这里：服务器对配置文件还没建立的机器不下发 update 块，
  // 而 fresh 那一支在更前面就整段返回了
  if (status.update) {
    list.push({
      key: 'update',
      title: '有新版 ' + status.update.latestVersion,
      body: '控制台不做在线更新：到服务器上换 jar 重启就是了。',
      action: '看完整说明', href: status.update.url, soft: true,
    });
  }

  return list;
}

/**
 * 「现在」：谁在播
 *
 * 主播的推送目标数从 users 那份清单里取：live 只回答「谁在播」，
 * 两份数据同属一次 /api/status，不会出现「在播的是新的、推送目标是旧的」这种画面。
 */
function now(status, chain, fresh) {
  const users = status.users || [];
  const byKey = new Map(users.map(item => [item.platform + ':' + item.uid, item]));

  const live = (status.live || []).map(item => {
    const user = byKey.get(item.platform + ':' + item.uid) || {};
    return {
      uid: item.uid, uname: item.uname || user.uname || String(item.uid),
      roomId: item.roomId, platform: item.platform,
      since: item.since || 0, targets: user.targets || 0,
    };
  });

  let text = '';
  if (fresh) text = '还没有配置任何主播，配好之后这里会显示谁在播。';
  else if (!live.length) text = '现在没有人在播 · 共监听 ' + users.length + ' 位主播';

  // 断流那一档要在这里加一句：报告里的数字会缺一块，而缺的那块不会自己写在图上。
  // 判据是「直播平台侧有非登录态的探针发黄或发红」——登录掉了不影响弹幕采集，
  // 两者不能混为一谈，因此按探针自报的 loginState 分，不按名字认
  const gap = probesIn(status, 'PLATFORM')
    .some(item => !item.loginState && (item.lamp === 'warn' || item.lamp === 'err'));

  return {
    live, text,
    note: (gap && live.length)
      ? '有直播间正在断流：本场报告会标注采集缺口，弹幕与礼物计数只是下界。'
      : '',
    paused: status.pushEnabled === false && live.length > 0,
  };
}

/**
 * 「今天发生了什么」短条
 *
 * 失败与告警一条不落地置顶，其余按时间补到八条为止。首页只留这一段，
 * 筛选、搜索、翻天都在日志页——两处各摆一套筛选，改了一处另一处就开始骗人。
 * @param timeline /api/timeline 回包
 * @return 要显示的事件
 */
function shortStrip(timeline) {
  const events = (timeline && timeline.events) || [];
  const bad = events.filter(item => item.level && item.level !== 'info');
  const rest = events.filter(item => !item.level || item.level === 'info');
  return bad.concat(rest.slice(0, Math.max(0, Math.min(6, 8 - bad.length))));
}

/**
 * 算出首页此刻该显示什么
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param timeline /api/timeline?date=今天 回包
 * @param quota /api/at-all/quota 回包；没有或失败时可不传
 * @return 首页视图模型
 */
export function homeModel(status, login, timeline, quota, pages, pluginDone, terms) {
  const state = status || {};
  const account = login || {};
  const words = terms || {};

  // 刚装好、还没连上任何机器人的那一档。判据是「一个推送平台都没注册出来」——
  // 配置文件里写了但 Token 空着的那种也算没有，因为它同样发不出消息。
  // 不按探针那句「未配置任何机器人」认：那是给人读的一句话，改一个字判据就失效
  const fresh = (state.senders || []).length === 0 && (state.users || []).length === 0;

  const platform = probesIn(state, 'PLATFORM');
  const self = probesIn(state, 'SYSTEM');
  const bot = probesIn(state, 'BOT');

  const chain = {
    platform: Object.assign(
      {station: platformName(account), sub: '账号与直播间', advice: ''},
      segment(platform, '还没登录直播平台')),
    self: Object.assign(
      {station: 'NovaBot', sub: '本机', advice: ''},
      segment(self, '还没开始干活')),
    bot: Object.assign(
      {station: word(words, 'bot.platform', '机器人'), sub: word(words, 'bot.targets', '会话'), advice: ''},
      segment(bot, '还没连上机器人')),
  };

  // 建议那句话跟着最重的那盏灯走：横条与待办都要用它，各自再算一遍就会分叉
  chain.platform.advice = (platform.find(item => item.lamp === chain.platform.level) || {}).advice || '';
  chain.self.advice = (self.find(item => item.lamp === chain.self.level) || {}).advice || '';
  chain.bot.advice = (bot.find(item => item.lamp === chain.bot.level) || {}).advice || '';

  if (fresh) {
    // 什么都还没配的时候，三段一律熄灯：此时报红是在说「坏了」，而它没坏，是还没开始
    chain.platform.level = 'off';
    chain.self.level = 'off';
    chain.bot.level = 'off';
  } else if (state.pushEnabled === false) {
    chain.bot.level = 'off';
    chain.bot.caption = '已暂停 · 所有推送都会被丢弃';
  } else if (state.quiet && state.quiet.active) {
    // 静音期间这一段判熄灯而不是判正常：链路本身通着，是这一段此刻按配置不走消息，
    // 而绿灯会让人以为「推送在正常发出去」
    chain.bot.level = 'off';
    chain.bot.caption = '静音 · ' + state.quiet.start + ' – ' + state.quiet.end + ' 期间推送直接丢弃';
  }

  return {
    fresh,
    chain,
    // 六项探针照后端给的顺序原样列出，这一栏不重排也不筛
    probes: (state.health || []).map(item => ({
      name: item.name || '',
      lamp: LAMP_OF[item.level] || 'off',
      summary: item.summary || '',
      advice: item.advice || '',
    })),
    banner: banner(state, chain, fresh),
    todos: todos(state, account, chain, fresh, pages, pluginDone, words),
    now: now(state, chain, fresh),
    today: {
      sent: (state.today && state.today.sent) || 0,
      failed: (state.today && state.today.failed) || 0,
      atAll: atAllTile(quota),
    },
    events: shortStrip(timeline),
    // 空态那句话分两种：刚装好的机器与「今天真的没发生什么」不是一回事
    eventsEmpty: fresh
      ? '今天还没有任何事件。配置完成、收到第一条推送之后，这里会一条条记下来。'
      : '这一天没有记录。',
    // 刻意不叫 pushEnabled：那个名字属于 store 上的共享状态，
    // 界面文件里裸着出现即为 ReferenceError，因此有一条判据在盯着它（见 ConfigUiFrontendTest）。
    // 这里换个名字，而不是去给那条判据开一个豁免——豁免多了它就形同虚设
    pushOn: state.pushEnabled !== false,
  };
}

/**
 * 首页链路图上某一站，点下去该去哪
 *
 * 「本机」去本页健康自检那一块，另外两站去连接页对应的卡。
 * 落点写在这一份而不是写在渲染代码里：渲染那边写死 {@code #/links?card=} 的话，
 * 本机那一站会跟着跳去连接页，而连接页上没有它的卡——地址栏变了、屏幕没动，
 * 看起来像页面卡住了。
 *
 * 未知的站名回空串，不编一个去处。
 */
export const STATION_HREF = {
  platform: '#/links?card=platform',
  self: '#/home?card=probes',
  bot: '#/links?card=bot',
};

/** 首页健康自检那一张卡的 id，与 index.html 上那一块对得上 */
export const PROBE_ANCHOR = 'home-probes';

/**
 * @param station 站名，platform / self / bot
 * @return {string} 地址，没有去处时为空串
 */
export function stationHref(station) {
  return STATION_HREF[station] || '';
}
