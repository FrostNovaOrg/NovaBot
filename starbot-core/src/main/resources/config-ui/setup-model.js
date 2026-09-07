/**
 * 初始设置五步的判定：走到哪、放不放行、进度条上画什么、初始值那几行写什么
 *
 * 一个 DOM 也不碰、一个请求也不发——这一页上真正会把人卡住的东西全在这里：
 * 哪一步不许跳过、少了什么不许往下走、「重新跑一遍」之后从第几步接着走。
 * 混在渲染代码里的话，这几件事就只能靠人打开一台没配过的机器手点，
 * 而放行条件写反了<b>不会有任何报错</b>，只是那一步变成点一下就过。
 *
 * 五步各自成不成立由 {@link setupSteps} 现算（在 home-model.js 里），本文件不另判一遍：
 * 首页那条待办与这一页的进度条说的是同一件事，两份实现分叉的表现是
 * 首页写着「完成了 3 步」而这一页停在第 2 步，且两处的代码看起来都对。
 *
 * 本文件里没有任何一个直播平台的名字：第三步按通名说「直播平台」，
 * 平台叫什么由 /api/login 里那个显示名带进来。写死一个，没装它的人会看见一步他走不到的流程。
 */

import {setupSteps} from './home-model.js';
import {mailAlertConfigured} from './alert-model.js';

/**
 * 五步，闭集
 *
 * {@code skippable} 是「界面上给不给跳过按钮」，不是「不填也能过」——
 * 第 3 步跳过等于选了免登录（要先过一段后果确认），第 4 步跳过等于不加主播（同样要确认），
 * 两者的放行仍归 {@link canAdvance} 判。
 *
 * 🔴 第 1、2 步不给跳过按钮：没上锁的控制台谁都进得来，没连上机器人的 NovaBot
 * 一条消息也发不出去——这两件事跳过之后，剩下三步做了也没有意义。
 * 第 1 步曾经也给过「先跳过」，后来撤了：跳过它之后，剩下四步配好的东西全摆在一扇开着的门后面。
 */
export const SETUP_STEPS = [
  {key: 'lock', title: '给控制台上把锁', skippable: false},
  {key: 'bot', title: '连上 QQ 机器人', skippable: false},
  {key: 'account', title: '登录直播平台', skippable: true},
  {key: 'streamer', title: '第一位主播，推到哪', skippable: true},
  {key: 'test', title: '发一条试试', skippable: false},
];

const BUILTIN_STEP_KEYS = new Set(SETUP_STEPS.map(step => step.key));

/**
 * 内置五步加上插件申报的向导步骤
 *
 * 只收 {@code slot === 'setup_step'} 的页。插在内置「主播」之后、「试发」之前，
 * 按 order 升序（同 order 按 id）。无插件页时返回 {@link SETUP_STEPS} 本身。
 * @param pages /api/pages 的 pages 清单
 * @return {{key: string, title: string, skippable?: boolean, plugin?: boolean}[]}
 */
export function withPluginSteps(pages) {
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
  if (!extra.length) return SETUP_STEPS;
  const streamerAt = SETUP_STEPS.findIndex(step => step.key === 'streamer');
  return [...SETUP_STEPS.slice(0, streamerAt + 1), ...extra, ...SETUP_STEPS.slice(streamerAt + 1)];
}

/**
 * 各步各自成立了没有
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param sent 第 5 步的事实，来自 /api/setup/state；不知道时传 null
 * @param pluginDone 插件步事实，{@code {key: boolean}}；缺这一位或该键不为 true 都算假
 * @param pages /api/pages 的 pages 清单；缺＝无插件
 * @return {boolean[]} 与步骤表等长的布尔
 */
export function stepFacts(status, login, sent, pluginDone, pages) {
  return setupSteps(status || {}, login || {}, sent, pages, pluginDone);
}

/**
 * 完成了几步
 * @param status /api/status 回包
 * @param login /api/login 回包
 * @param sent 第 5 步的事实；不知道时传 null
 * @return {number} 0 到 5
 */
export function setupProgress(status, login, sent, pluginDone, pages) {
  return stepFacts(status, login, sent, pluginDone, pages).filter(Boolean).length;
}

/**
 * 步骤表上每一步都齐了吗
 * @param facts {@link stepFacts} 的结果
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {boolean} 齐了为 true
 */
export function allDone(facts, steps) {
  const table = steps || SETUP_STEPS;
  return (facts || []).length === table.length && facts.every(Boolean);
}

/**
 * 这一步放不放行
 *
 * 拦下来时连理由一起给：只回一个 false 的话，屏幕上的表现是「下一步这个按钮点不动」，
 * 而使用者看不出还差什么。五条理由各写各的——抄成同一句的话，
 * 第 2 步与第 4 步要人做的事完全不同，却写着同一行字。
 *
 * 认不出来的步号一律不放行：这张表将来多一步少一步的时候，宁可卡住也不许默默放过去。
 * 按步骤表上的 key 判，不按写死的下标——插件步插进来之后，试发不再是第 5 格。
 * @param index 第几步，0 起
 * @param draft 这一页此刻手里的东西
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {{ok: boolean, reason: string}} 放行与否，以及拦住的理由
 */
export function canAdvance(index, draft, steps) {
  const it = draft || {};
  const step = (steps || SETUP_STEPS)[index];
  if (!step) return stop('认不出这一步，不放行');

  switch (step.key) {
    case 'lock':
      return it.locked ? pass()
        : stop('先设一把控制台口令。这一步不能跳过——没上锁的控制台，任何能连上这台机器的人都进得来');
    case 'bot':
      return it.botOk ? pass()
        : stop('先按「测试连接」，通过了才能往下走。这一步不能跳过——连不上机器人，一条消息也发不出去');
    case 'account':
      return it.accountsReady || it.anonymousConfirmed ? pass()
        : stop('扫码登录，或者点「不登录，先用免登录模式」并确认那一段后果');
    case 'streamer':
      if (it.noStreamerConfirmed) return pass();
      if (!it.streamer) return stop('先填 uid 找到一位主播；确实不想现在加的话，点「先不加主播」');
      return (it.targets || []).length ? pass()
        : stop('至少选一个群或一位好友，否则这位主播的开播通知没有地方可去');
    case 'test':
      return it.sent ? pass() : stop('先发一条试试，群里看得到才说明整条链路是通的');
    default:
      return step.plugin ? pass() : stop('认不出这一步，不放行');
  }
}

const pass = () => ({ok: true, reason: ''});

const stop = reason => ({ok: false, reason});

/**
 * 进度条上每一步画成什么样
 *
 * 记号有先后：<b>事实成立就是成立，跳过的记号盖不住它</b>——使用者跳过了第 4 步，
 * 回头又把主播加上了，那一格该变成完成而不是一直挂着「跳过了」。
 * 唯一排在事实前面的是免登录：那一步确实「定了」，但它定的是一个有后果的决定，
 * 打勾会让人以为登录成功了。
 * @param facts {@link stepFacts} 的结果
 * @param skips 每一步被跳过的方式，'' / 'skip' / 'anon'
 * @param current 正在做第几步
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {{state: string, note: string}[]} 每步的记号与那行小字
 */
export function railMarks(facts, skips, current, steps) {
  return (steps || SETUP_STEPS).map((step, i) => {
    if ((skips || [])[i] === 'anon') return {state: 'warn', note: '免登录模式'};
    if ((facts || [])[i]) return {state: 'done', note: '完成'};
    if ((skips || [])[i]) return {state: 'skip', note: '跳过了'};
    if (i === current) return {state: 'current', note: '正在做'};
    return {state: 'idle', note: ''};
  });
}

/**
 * 从第几步接着走
 *
 * 🔴 「重新跑一遍初始设置」按下之后一律回到第一步。不回的话，那个按钮点完停在原地，
 * 与「点了没反应」在屏幕上长得一样。
 * @param facts {@link stepFacts} 的结果
 * @param rerun 有没有人要求重来一遍
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {number} 落在第几步
 */
export function startAt(facts, rerun, steps) {
  if (rerun) return 0;
  const table = steps || SETUP_STEPS;
  const at = (facts || []).findIndex(done => !done);
  return at < 0 ? table.length - 1 : at;
}

/**
 * 「已替你定好的初始值（都可改）」那几行
 *
 * 🔴 每一行都从现值算，不写死一张表：写死的话，一台刚刚被使用者关掉推送的机器上，
 * 这一行照样写着「开」——而这一段的全部意义正是让他<b>看见</b>这台机器现在是什么样。
 *
 * 表里只有核心自己的配置键。「备用直播推送」那一行落在直播平台插件的键上，
 * 核心界面里写不得它的键名（格1 契约），因此不在这里；它归那个插件自己的连接卡。
 * @param configValues /api/values 的 values，键 → 值。<b>参数名不叫 values</b>：
 *        那是 store 上一份共享状态的名字，在界面文件里裸着出现即为 ReferenceError，
 *        因此有一条判据在盯着它——它当场逮住了这一处
 * @param commandCount 运行期认得的命令条数，数不到时传 null
 * @return {{label: string, text: string, href: string, key: string}[]} 每行
 */
export function initialRows(configValues, commandCount) {
  const at = configValues || {};
  const read = key => String(at[key] ?? '').trim();

  const quietStart = read('starbot.core.push.quiet-start');
  const quietEnd = read('starbot.core.push.quiet-end');
  const retention = Number(read('starbot.core.timeline.retention-days'));
  const alerted = !!read('starbot.core.alert.webhook-url') || !!read('starbot.core.alert.qq-num')
    || mailAlertConfigured(read('starbot.core.mail.default-to'), read('spring.mail.host'));

  return [
    row('推送总开关', read('starbot.core.push.enabled') === 'false' ? '关' : '开',
      '#/settings', 'starbot.core.push.enabled'),
    row('静音时段', quietStart && quietEnd ? quietStart + ' – ' + quietEnd : '未设（半夜被吵到再来设）',
      '#/settings', 'starbot.core.push.quiet-start'),
    // 金额可见没有一个总的配置键：它按会话来，默认群聊隐藏、私聊显示，改在推送页上
    row('金额可见', '群聊隐藏、私聊显示', '#/push', ''),
    row('命令', (commandCount ? commandCount + ' 条' : '') + '全开、只认 @ 机器人',
      '#/settings', 'starbot.core.command.admins'),
    row('告警', alerted ? '已配' : '未配。建议配一条 Webhook，QQ 掉线时只有它能叫到你',
      '#/settings', 'starbot.core.alert.webhook-url'),
    // 0 与负数是「不自动清理」，不是「留 0 天」——后者读起来像日志当天就没了
    row('日志保留', Number.isFinite(retention) && retention > 0 ? retention + ' 天' : '不自动清理',
      '#/settings', 'starbot.core.timeline.retention-days'),
  ];
}

const row = (label, text, href, key) => ({label, text, href, key});

/**
 * 走完之后那三行小结
 *
 * 三行分别答：机器人连上没有、直播平台这一头定成了什么、推给谁。
 * <b>跳过的那几步如实写出后果</b>——「还没加主播」这台 NovaBot 起来什么都不做，
 * 而一句「初始设置完成」会让人以为它已经在干活了。
 * @param facts {@link stepFacts} 的结果
 * @param skips 每一步被跳过的方式
 * @param counts {accounts, streamers, targets}
 * @param steps 步骤表，缺省 {@link SETUP_STEPS}
 * @return {string[]} 三行
 */
export function summaryLines(facts, skips, counts, steps) {
  const it = counts || {};
  const accounts = it.accounts || [];
  const table = steps || SETUP_STEPS;
  const fact = key => {
    const i = table.findIndex(step => step.key === key);
    return i >= 0 && !!(facts || [])[i];
  };

  const account = accounts.length
    ? accounts.map(one => (one.displayName || '直播平台') + ' '
      + (one.loggedIn ? '已登录' : (one.disabledReason ? '免登录模式（动态推送与自动关注不可用）' : '还没登录')))
      .join(' · ')
    : '没有装任何直播平台插件，这台 NovaBot 采不到直播事件';

  const streamer = fact('streamer') && it.streamers
    ? it.streamers + ' 位主播 → ' + (it.targets || 0) + ' 个推送目标'
    : '还没加主播——这台 NovaBot 起来暂时什么都不做';

  return [
    fact('bot') ? 'QQ 机器人 已连上' : 'QQ 机器人 还没连上，推送发不出去',
    account,
    streamer,
  ];
}
