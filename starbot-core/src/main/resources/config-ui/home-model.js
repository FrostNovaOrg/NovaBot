/**
 * 首页视图模型
 *
 * 把 /api/status、/api/login、/api/timeline 三份回包算成屏幕上要显示的东西：
 * 链路三段的灯色与说明、六项探针、顶部横条、待办、现在与今日、今天发生了什么。
 *
 * 刻意不碰 DOM，也不发请求。首页要在八种情形下都说得对——正常、首次安装、机器人掉线、
 * 直播平台掉登录、直播间断流、推送变慢、静音时段中、已暂停推送——而这八种情形
 * 在真机上凑齐一次的代价极高：要么等故障发生，要么去改线上配置。算成纯函数之后，
 * 喂八份回包跑一遍就能逐条对答案（tools/home-model-check.sh），渲染那一层只管把算好的摆上去。
 *
 * 本文件里没有任何一个直播平台的名字：站名取自运行时下发的账号显示名，
 * 说明取自探针自己给的那句话。写死一个平台名，这一页就替一件可能没装的东西说了话。
 */

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
 * 初始设置这五步走完了几步
 *
 * 第 5 步「发一条试试」没有独立的判定依据：这台机器有没有真的发过一条试试，
 * 现在没有任何地方记着（那件事归初始设置页自己记）。此处按「前四步都成立」算——
 * 能把消息推出去的前提正是前四步都成立。换成「今天推成功过」的话，
 * 这条待办会在每天零点自己复活，催一件早就做完的事。
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @return {number} 完成的步数，0 到 5
 */
export function setupDone(status, login) {
  const accounts = (login && login.accounts) || [];
  const steps = [
    // 上锁：设了控制台口令（通行密钥跟着口令登录走，没有口令时它签出来的会话打不开任何门）
    !!status.locked,
    // 连上机器人：这一档的探针没有报红
    worstLamp(probesIn(status, 'BOT').map(item => item.lamp)) === 'ok',
    // 登录直播平台：每个平台要么登录了，要么被配置明确关掉了（例如匿名模式）。
    // 一个平台插件都没装时这一步不是「没做完」，是没得做，因此算它已定
    accounts.every(item => !!(item.loggedIn || item.disabledReason)),
    // 第一位主播
    ((status.users || []).length > 0),
  ];

  const done = steps.filter(Boolean).length;
  return done === steps.length ? 5 : done;
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
 * 待办
 *
 * 只放「要人动手，不动就一直不好」的事。会自己恢复的异常不进这里——
 * 它们在探针那一栏里逐条列着，混进待办只会让这张单子长到没人看。
 */
function todos(status, login, chain, fresh) {
  // 「初始设置还没完成」只在刚装好那一档出现，而且此时它是唯一的一条：
  // 那五步里第 1 步就是上锁、第 2 步就是连机器人，再摆几条说同一件事的待办，
  // 使用者会以为是几件事。
  //
  // 🔴 它<b>不</b>由「此刻各项健不健康」推出来。掉一次登录就说「初始设置没做完」，
  //    等于把一次运行期故障说成使用者没配好，而他明明配过——那条待办会在故障期间
  //    一直挂着催他重走一遍五步，而重走一遍并不能让登录态自己回来。
  if (fresh) {
    const done = setupDone(status, login);
    return [{
      key: 'setup',
      title: '初始设置还没完成 · 5 步里完成了 ' + done + ' 步',
      body: '上锁 → 连机器人 → 登录直播平台 → 加第一位主播 → 发一条试试。走完就生效，不用重启。',
      action: '去继续', href: '#/setup', soft: false,
    }];
  }

  const list = [];

  if (chain.bot.level === 'err') {
    list.push({
      key: 'bot',
      title: '重新登录 NapCat',
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

  if (status.totalDataAvailable === false) {
    list.push({
      key: 'total',
      title: '累计数据没开',
      body: '群里只能查本场，「直播间总数据」「总数据排行榜」这两条不会出现在菜单里。'
        + '要开得给 NovaBot 配一个累计存储，找运维。',
      action: '去配', href: '#/settings', soft: true,
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
 * @return 首页视图模型
 */
export function homeModel(status, login, timeline) {
  const state = status || {};
  const account = login || {};

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
      {station: 'QQ', sub: '群与好友', advice: ''},
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
    todos: todos(state, account, chain, fresh),
    now: now(state, chain, fresh),
    today: {
      sent: (state.today && state.today.sent) || 0,
      failed: (state.today && state.today.failed) || 0,
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
