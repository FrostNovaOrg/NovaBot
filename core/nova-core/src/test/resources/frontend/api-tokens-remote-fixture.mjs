/**
 * 访问令牌页「从别的机器访问」那几条连法的夹具
 *
 * 量的是 config-ui/api-tokens-model.js 里的 remoteRoutes 与 lanStatus：地址按回包里实际的端口、
 * 部署前缀与内网地址拼，内网那一条按监听地址、来源名单与开关如实说通不通。
 * 来源名单已放行所有地址时，顶部提示开关不起作用，开关旁不再说「只对名单里的地址开放」。
 * 两者不碰 DOM，可以单独喂值直接跑。
 *
 * 由 ApiTokensRemoteModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条逐条印出来。
 */

import {groupBadge, lanStatus, localStatus, publicHint, remoteNotice, remoteRoutes} from '../../../main/resources/config-ui/api-tokens-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function ok(value, what) {
  eq(!!value, true, what);
}

/** 什么都没改时服务端回的样子 */
function state(over) {
  return Object.assign({
    port: 7827, apiPath: '/nova/api/v1/',
    listen: {address: '127.0.0.1', scope: 'loopback', restartPending: false},
    lan: ['192.168.1.20'], allowListBeyondLocal: false,
    groups: [{segment: 'outbox', name: '外发箱', description: '', open: false}],
  }, over);
}

const plainPage = {protocol: 'http:', hostname: '127.0.0.1', host: '127.0.0.1:7827'};

// ---------- 一、地址 ----------
const fresh = remoteRoutes(state(), '', plainPage);
eq(fresh.local.url, 'http://127.0.0.1:7827/nova/api/v1/', '本机：回环地址加实际端口');
eq(fresh.lan.urls, ['http://192.168.1.20:7827/nova/api/v1/'], '内网：这台机器的内网地址');
eq(fresh.internet.url, 'https://<你的域名>/nova/api/v1/', '公网：经反向代理的加密地址');
eq(fresh.internet.current, null, '从本机打开控制台时不猜公网地址');
eq(fresh.tunnel.url, 'http://127.0.0.1:7827/nova/api/v1/', '隧道：来访机器上连自己的回环');
ok(fresh.tunnel.command.includes('-L 7827:127.0.0.1:7827'), '隧道命令按实际端口写');

const prefixed = remoteRoutes(state({port: 9000}), '/novabot', plainPage);
eq(prefixed.local.url, 'http://127.0.0.1:9000/novabot/nova/api/v1/', '部署前缀拼在端口后面、/nova 前面');
eq(prefixed.lan.urls, ['http://192.168.1.20:9000/novabot/nova/api/v1/'], '内网那一条同样带前缀');
eq(prefixed.internet.url, 'https://<你的域名>/novabot/nova/api/v1/', '公网那一条同样带前缀');

const unknownLan = remoteRoutes(state({lan: []}), '', plainPage);
eq(unknownLan.lan.urls, ['http://<这台机器的内网地址>:7827/nova/api/v1/'], '认不出内网地址时写占位，不编一个');
eq(unknownLan.lan.known, false, '并标出没认出来');

const v6 = remoteRoutes(state({lan: ['fd00::5'], listen: {address: 'fd00::5', scope: 'specific'}}), '', plainPage);
eq(v6.lan.urls, ['http://[fd00::5]:7827/nova/api/v1/'], 'IPv6 地址加方括号');

const viaProxy = remoteRoutes(state(), '', {protocol: 'https:', hostname: 'bot.example.com', host: 'bot.example.com'});
eq(viaProxy.internet.current, 'https://bot.example.com/nova/api/v1/',
  '经 https 公网地址打开控制台时，给出同一个域名下的接口地址（页面另写明要转发了才通）');

// ---------- 二、内网通不通 ----------
const closed = lanStatus(fresh.lan);
eq(closed.level, 'err', '监听地址只限本机：如实写连不上');
ok(closed.text.includes('现在连不上'), '说「现在连不上」');
eq(closed.steps.length, 2, '列出要做的两步');
ok(closed.steps[0].includes('监听地址') && closed.steps[0].includes('重启'), '第一步：改监听地址并重启');
ok(closed.steps[1].includes('允许访问的来源 IP') && closed.steps[1].includes('打开'), '第二步：名单或开关二选一');

const listening = state({listen: {address: '0.0.0.0', scope: 'all', restartPending: false}});
const refused = lanStatus(remoteRoutes(listening, '', plainPage).lan);
eq(refused.level, 'warn', '监听开了，但名单只有本机、一组也没开：来访机器会被拒');
ok(refused.text.includes('403'), '写明会收到 403');

const opened = lanStatus(remoteRoutes(state({listen: listening.listen,
  groups: [{segment: 'outbox', name: '外发箱', open: true}]}), '', plainPage).lan);
eq(opened.level, 'ok', '开了一组：通');
ok(opened.text.includes('已打开') && !opened.text.includes('全部接口'), '只说开了的那几组，不说成全部接口');
ok(opened.text.includes('令牌'), '仍提醒要带令牌');

const listed = lanStatus(remoteRoutes(state({listen: listening.listen, allowListBeyondLocal: true}), '', plainPage).lan);
eq(listed.level, 'ok', '名单里有本机以外的地址：通');
ok(listed.text.includes('全部接口'), '名单里的机器调得了全部接口');

const pending = remoteRoutes(state({listen: {address: '127.0.0.1', scope: 'loopback', restartPending: true}}), '', plainPage);
eq(pending.lan.restartPending, true, '监听地址改了还没重启：带出这一位，页面另写一句');

// ---------- 三、只听某一个具体地址 ----------
// 127.0.0.1 上没人听：本机与隧道都得连那个地址，而程序看到的来访者是那个地址、不是回环
const onlyLan = {address: '192.168.1.20', scope: 'specific', restartPending: false, allowListed: false};
const pinned = remoteRoutes(state({listen: onlyLan, lan: ['192.168.1.20']}), '/novabot', plainPage);
eq(pinned.local.url, 'http://192.168.1.20:7827/novabot/nova/api/v1/', '本机：连监听的那个地址，不是 127.0.0.1');
ok(pinned.tunnel.command.includes('-L 7827:192.168.1.20:7827'), '隧道的远端目标也是那个地址');
eq(pinned.tunnel.url, 'http://127.0.0.1:7827/novabot/nova/api/v1/', '隧道在来访机器上仍连自己的回环');
eq(pinned.lan.urls, ['http://192.168.1.20:7827/novabot/nova/api/v1/'], '内网那一条照旧');
const pinnedText = localStatus(pinned.local);
ok(!pinnedText.local.includes('不用改任何设置') && !pinnedText.tunnel.includes('不用改任何设置'),
  '不再说「不用改任何设置」');
ok(pinnedText.local.includes('127.0.0.1 连不上'), '写明 127.0.0.1 连不上');
ok(pinnedText.local.includes('不是本机回环') && pinnedText.local.includes('403'), '如实说本机来访不是回环、不在名单里会被拒');
eq(pinnedText.level, 'warn', '不在名单里：提示');

const pinnedListed = localStatus(remoteRoutes(state({listen: Object.assign({}, onlyLan, {allowListed: true})}), '',
  plainPage).local);
eq(pinnedListed.level, 'ok', '那个地址在名单里：通');
ok(pinnedListed.local.includes('在「允许访问的来源 IP」里'), '写明它在名单里');

const pinnedOpen = localStatus(remoteRoutes(state({listen: onlyLan,
  groups: [{segment: 'outbox', name: '外发箱', open: true}]}), '', plainPage).local);
ok(pinnedOpen.local.includes('已打开的那几组'), '不在名单、但开了组：只说开了的那几组调得了');

const pinnedV6 = remoteRoutes(state({listen: {address: 'fd00::5', scope: 'specific', allowListed: false}}), '', plainPage);
eq(pinnedV6.local.url, 'http://[fd00::5]:7827/nova/api/v1/', 'IPv6 监听地址加方括号');
ok(pinnedV6.tunnel.command.includes('-L 7827:[fd00::5]:7827'), '隧道目标的 IPv6 也加方括号');

const everywhere = localStatus(remoteRoutes(state({listen: {address: '0.0.0.0', scope: 'all'}}), '', plainPage).local);
ok(everywhere.local.includes('不用改任何设置'), '监听全部网卡时照旧：本机不用改设置');
eq(remoteRoutes(state({listen: {address: '0.0.0.0', scope: 'all'}}), '', plainPage).local.url,
  'http://127.0.0.1:7827/nova/api/v1/', '监听全部网卡时本机照旧连 127.0.0.1');
ok(localStatus(fresh.local).tunnel.includes('本机来访'), '只听回环时隧道照旧算本机来访');

// ---------- 四、来源名单已放行所有地址 ----------
// 名单里有 0.0.0.0/0 或 ::/0 时开关关着也不拦人：页面不能再让人以为「关着＝没对外开」
const closedGroup = {segment: 'outbox', name: '外发箱', open: false};
const openGroup = {segment: 'outbox', name: '外发箱', open: true};
eq(remoteNotice(state()), '', '默认名单：顶部不出提示');
eq(groupBadge(closedGroup, state()), {level: '', text: '只对名单里的地址开放'}, '默认名单：开关旁的字照旧');
eq(groupBadge(openGroup, state()), {level: 'ok', text: '已对任何地址开放'}, '默认名单：开着的照旧');
eq(remoteNotice(state({allowListBeyondLocal: true})), '', '名单里加了别的地址但没全放：不出提示');
eq(publicHint(state()), '转发了也不等于放通：名单以外的来访者调不调得了，看上面的开关。', '默认名单：公网那句照旧');

const admitsAll = state({allowListBeyondLocal: true, allowListAdmitsAll: true});
const notice = remoteNotice(admitsAll);
ok(notice.includes('已经放行所有地址'), '全放：顶部提示说名单已放行所有地址');
ok(notice.includes('关着也不会拦人') && notice.includes('令牌'), '说清开关关着也不拦人、有令牌就能调');
ok(notice.includes('收窄'), '说清要先把名单收窄，开关才起作用');
const badge = groupBadge(closedGroup, admitsAll);
ok(!badge.text.includes('只对名单里的地址开放'), '全放：开关旁不再写「只对名单里的地址开放」');
eq(badge.level, 'warn', '全放：开关旁的字是提醒色');
ok(badge.text.includes('不起作用'), '全放：开关旁写明开关此刻不起作用');
ok(!publicHint(admitsAll).includes('看上面的开关'), '全放：公网那句不再说「看上面的开关」');

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
