/**
 * 首页「今日」卡那几组纯函数的夹具
 *
 * 量的是控制台插件里 today-model.js 的三件事：三个数格该写什么、第三格的明细怎么排怎么截、
 * 群名前面加不加平台前缀，以及那一格摊开／收起的 HTML。它们全不碰 DOM，
 * 因此可以在 node 上直接喂值跑。
 *
 * 三组是本卡判据的重头：
 *   · 「—」与 0 分得开——0 看起来像今天一次都没用，而「—」说的是这台机器此刻无从谈起；
 *   · 不限额那一档不画分母——画成 3/0 会让人以为今天已经用完了；
 *   · 群名前缀按全量明细判，不按显示的前 5 行判——只按前 5 行判的话，
 *     第 6 个群来自另一个平台时，屏幕上五行「群 11」分不出是谁家的。
 *
 * 由 TodayModelTest 与 tools/today-model-check.sh 拉起，退码 0 ＝ 全绿。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本。
 */

import {register} from 'node:module';

// 被测模块引了宿主的 './core.js'，先把那个名字指回核心那一份再动态 import
register(new URL('./alias-core-modules.mjs', import.meta.url));

const {todayAtAllMarkup, todayModel} =
  await import('../plugins/nova-console/src/main/resources/config-ui-pages/today-model.js');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 一份平常的运行状态：今天推了 14 条、失败 1 条、推送没暂停 */
function status(over) {
  return Object.assign({today: {sent: 14, failed: 1}, pushEnabled: true}, over || {});
}

// ── 前两个数格与开关 ──────────────────────────────────────────────────
eq(todayModel(status()).sent, 14, '推送条数取 status.today.sent');
eq(todayModel(status()).failed, 1, '失败条数取 status.today.failed');
eq(todayModel({}).sent, 0, '没有 today 块时写 0，不写「—」——那两个数确实是 0');
eq(todayModel(null).failed, 0, '连回包都没有时也是 0');
eq(todayModel(status()).pushOn, true, '没暂停');
eq(todayModel(status({pushEnabled: false})).pushOn, false, '明确暂停了才算暂停');
eq(todayModel({}).pushOn, true, '缺这一栏不许读成已暂停：那会让开关先画成「恢复推送」再自己变回去');

// ── 第三格：@全体成员 已用 ────────────────────────────────────────────
function tileOf(quota) {
  return todayModel(status(), quota).atAll || {value: null, label: null, details: [], more: 0};
}

function quota(bots, sessions) {
  return {success: true, date: '2026-09-04', bots: bots || [], sessions: sessions || []};
}

function bot(over) {
  return Object.assign({platform: 'onebot', used: 0, limit: 10, limited: true}, over);
}

function group(num, used, over) {
  return Object.assign({platform: 'onebot', num, used, limit: 20, limited: true}, over);
}

// 格三态
eq(tileOf(quota([bot({used: 3, limit: 10, limited: true})])).value, '3/10',
  '限额：账号已用画分母');
eq(tileOf(quota([bot({used: 3, limit: 0, limited: false})])).value, '3',
  '不限额：只显已用、不画分母');
eq(tileOf(quota([])).value, '—', '无机器人显「—」');
eq(tileOf(null).value, '—', '接口失败显「—」');
eq(tileOf(undefined).value, '—', '没交额度回包显「—」');
eq(tileOf({success: false}).value, '—', '回包声明失败显「—」');

// 多账号合计：两路都限额则分母相加；一路不限额则整格不画分母
eq(tileOf(quota([
  bot({platform: 'a', used: 3, limit: 10, limited: true}),
  bot({platform: 'b', used: 2, limit: 10, limited: true}),
])).value, '5/20', '两个限额账号合计已用与上限');
eq(tileOf(quota([
  bot({platform: 'a', used: 3, limit: 10, limited: true}),
  bot({platform: 'b', used: 2, limit: 0, limited: false}),
])).value, '5', '有一个不限额则整格不画分母');

// 明细：按 used 降序、最多 5 行、其余报还有 N 个群
const six = tileOf(quota([bot({used: 1})], [
  group(11, 1), group(12, 8), group(13, 3), group(14, 8), group(15, 0), group(16, 5),
]));
eq(six.details.map(item => item.num), [12, 14, 16, 13, 11],
  '明细按已用降序取前 5，used 相同的保持原序');
eq(six.more, 1, '第 6 个群进「还有 N 个群」');
eq((six.details[0] || {}).text, '8/20', '限额群的明细也画分母');
eq((tileOf(quota([bot()], [group(11, 4, {limited: false, limit: 0})])).details[0] || {}).text, '4',
  '不限额群的明细不画分母');
eq(tileOf(quota([bot()], [group(11, 1)])).more, 0, '不超过 5 个群时没有「还有」');

eq(tileOf(quota([bot({used: 3})])).label, '@全体成员 已用', '格的标签');

eq(tileOf(quota([bot({used: 3, limit: 10, limited: true})])).value.includes('／'), false,
  '限额格不含全角斜线');
eq((six.details[0] || {}).text.includes('／'), false, '明细不含全角斜线');

eq((tileOf(quota([bot({used: 1})], [group(11, 1, {platform: 'qq-onebot'})])).details[0] || {}).who,
  '群 11', '单平台无前缀');
eq((tileOf(quota([bot({used: 1})], [
  group(11, 3, {platform: 'qq-onebot', platformName: 'QQ'}),
  group(12, 1, {platform: 'other-bot'}),
])).details[0] || {}).who, 'QQ 群 11', '双平台 QQ 前缀');
eq((tileOf(quota([bot({used: 1})], [
  group(11, 3, {platform: 'alpha-bot'}),
  group(12, 1, {platform: 'beta-bot'}),
])).details[0] || {}).who, 'alpha-bot 群 11', '未知平台标识');

const twoPlat = tileOf(quota([bot({used: 1})], [
  group(11, 1, {platform: 'alpha-bot', platformName: '甲'}),
  group(12, 1, {platform: 'beta-bot', platformName: '乙'}),
]));
eq((twoPlat.details[0] || {}).who !== '群 11' && (twoPlat.details[1] || {}).who !== '群 12', true,
  '两平台各 1 行：两行 who 均带前缀');
eq((twoPlat.details[0] || {}).who, '甲 群 11', '两平台各 1 行：第一行人话前缀');
eq((twoPlat.details[1] || {}).who, '乙 群 12', '两平台各 1 行：第二行人话前缀');

const hiddenKind = tileOf(quota([bot({used: 1})], [
  group(11, 10, {platform: 'alpha-bot', platformName: '甲'}),
  group(12, 9, {platform: 'alpha-bot', platformName: '甲'}),
  group(13, 8, {platform: 'alpha-bot', platformName: '甲'}),
  group(14, 7, {platform: 'alpha-bot', platformName: '甲'}),
  group(15, 6, {platform: 'alpha-bot', platformName: '甲'}),
  group(16, 1, {platform: 'beta-bot', platformName: '乙'}),
]));
eq((hiddenKind.details[0] || {}).who, '甲 群 11',
  '前 5 行同平台、第 6 行另一平台：按全量判定前缀');
eq(hiddenKind.more, 1, '第 6 个群仍进「还有」');

const twin = tileOf(quota([bot({used: 4})], [
  group(11, 3, {platform: 'alpha-bot'}),
  group(11, 1, {platform: 'beta-bot'}),
]));
const whoA = (twin.details[0] || {}).who;
const whoB = (twin.details[1] || {}).who;
checks++;
if (whoA === whoB) {
  failures.push('同号异平台两条明细文本不应相同：得到 '
    + JSON.stringify(whoA) + ' 与 ' + JSON.stringify(whoB));
}
const twinHtml = todayAtAllMarkup(twin, false);
checks++;
if (!(whoA && whoB && twinHtml.includes(String(whoA)) && twinHtml.includes(String(whoB)))) {
  failures.push('明细 HTML 应带上两条不同的平台群名，得到 ' + twinHtml);
}

const emptyHtml = todayAtAllMarkup(tileOf(quota([bot({used: 0})])), false);
checks++;
if (emptyHtml.includes('<button')) {
  failures.push('无明细不应渲染 button，得到 ' + emptyHtml);
}
const closedHtml = todayAtAllMarkup(tileOf(quota([bot({used: 1})], [group(11, 1)])), false);
checks++;
if (!closedHtml.includes('aria-expanded="false"')) {
  failures.push('有明细收起时应 aria-expanded="false"，得到 ' + closedHtml);
}
const openedHtml = todayAtAllMarkup(tileOf(quota([bot({used: 1})], [group(11, 1)])), true);
checks++;
if (!openedHtml.includes('aria-expanded="true"')) {
  failures.push('有明细摊开后应 aria-expanded="true"，得到 ' + openedHtml);
}


console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
if (failures.length) {
  console.error('\n今日卡对不上 ' + failures.length + ' 处：');
  failures.forEach(line => console.error('  ' + line));
  process.exit(1);
}
