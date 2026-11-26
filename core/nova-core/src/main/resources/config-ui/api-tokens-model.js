/**
 * 访问令牌页的判定：有效期选项、状态说明、排序、签发请求体
 *
 * 全是纯函数，一个 DOM 也不碰，可以单独喂夹具跑。视图接线在 api-tokens.js。
 */

/** 有效期选项；days 为 null 是不过期。默认选中的是 90 天 */
export const VALIDITY = [
  {days: null, label: '不过期'},
  {days: 30, label: '30 天'},
  {days: 90, label: '90 天'},
  {days: 365, label: '365 天'},
];

export const DEFAULT_VALIDITY = 90;

/** 列表里「状态」一格的字；暂不可用的原因另起一行，见 {@link unavailableText} */
export function stateText(token) {
  if (token.state === 'revoked') return '已吊销';
  if (token.state === 'expired') return '已过期';
  if (token.state === 'unavailable') return '暂不可用';
  return '有效';
}

/** 暂不可用的原因；别的状态为空串 */
export function unavailableText(token) {
  return token.state === 'unavailable' ? (token.unavailableReason || '所属账号此刻不能使用访问令牌') : '';
}

/** 这一行给不给「吊销」按钮：已吊销、已过期的不给；暂不可用的照给，恢复之前也能作废它 */
export function revocable(token) {
  return token.state === 'active' || token.state === 'unavailable';
}

/** 还没作废的在上（有效、暂不可用），同一档里新签的在上：要吊销的多半是刚签错的那一把 */
export function sortTokens(tokens) {
  const rank = token => (revocable(token) ? 0 : 1);
  return (tokens || []).slice().sort((a, b) => rank(a) - rank(b) || (b.issuedAt || 0) - (a.issuedAt || 0));
}

/**
 * 签发页上这一项范围勾不勾得了
 * @param scope 范围（含 adminOnly）
 * @param admin 当前账号是不是管理员
 */
export function scopeSelectable(scope, admin) {
  return !scope.adminOnly || !!admin;
}

/** 所属账号的显示名；本地管理员与已删除的账号由服务端给好名字。停用的账号注一句，那把令牌此刻用不了 */
export function ownerText(token) {
  const owner = token.owner || {};
  const status = owner.status === 'DISABLED' ? '（已停用）' : '';
  return (owner.displayName || owner.id || '—') + status;
}

/** 范围标识换成中文名；登记表里找不到的（插件卸了）原样显示标识 */
export function scopeText(ids, scopes) {
  if (!ids || !ids.length) return '仅自查';
  const names = new Map((scopes || []).map(scope => [scope.id, scope.name]));
  return ids.map(id => names.get(id) || id).join('、');
}

/**
 * 签发请求体
 * @param name 名字（已去首尾空白）
 * @param scopes 勾选的范围标识
 * @param days 有效期天数，空串或 null 为不过期
 */
export function issueBody(name, scopes, days) {
  const value = days === '' || days === null || days === undefined ? null : Number(days);
  return {name, scopes: scopes.slice(), expiresInDays: value};
}

/** 时间戳（毫秒）印成年月日时分；没有为「—」 */
export function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

/** 时间戳（毫秒）只印年月日；没有为「—」。签发、到期那两格到日就够，表格才放得下吊销按钮 */
export function fmtDate(ms) {
  return ms ? fmtTime(ms).slice(0, 10) : '—';
}

/** 到期一格：不过期写「不过期」 */
export function expiryText(token) {
  return token.expiresAt ? fmtDate(token.expiresAt) : '不过期';
}

/** 地址里的主机：IPv6 加方括号 */
function urlHost(host) {
  return host.includes(':') && !host.startsWith('[') ? '[' + host + ']' : host;
}

/** 打开控制台用的主机是不是本机 */
function loopbackHost(hostname) {
  const host = String(hostname || '').toLowerCase();
  return host === 'localhost' || host === '[::1]' || host === '::1' || /^127\./.test(host);
}

/**
 * 「从别的机器访问」那一块的四种连法，值全取自服务端回包与部署前缀
 * @param state GET /api-tokens/remote-access 的回包
 * @param prefix 部署前缀（core.js 的 PREFIX），没配时为空串
 * @param page 打开控制台用的地址，取 {protocol, hostname, host}
 * @return {{local, lan, internet, tunnel}}
 */
export function remoteRoutes(state, prefix, page) {
  const s = state || {};
  const port = s.port || 7827;
  const path = (prefix || '') + (s.apiPath || '/nova/api/v1/');
  const listen = s.listen || {};
  const hosts = s.lan || [];
  const anyOpen = (s.groups || []).some(group => group.open);
  const lanListening = listen.scope === 'all' || listen.scope === 'specific';
  const admitted = anyOpen || !!s.allowListBeyondLocal;
  const p = page || {};
  const current = p.protocol === 'https:' && p.hostname && !loopbackHost(p.hostname)
    ? 'https://' + p.host + path : null;
  // 只听某一个地址时 127.0.0.1 上没人听：本机与隧道都得连那个地址，而那样连进来的在程序看来也不是回环
  const specific = listen.scope === 'specific' && !!listen.address;
  const localHost = specific ? urlHost(listen.address) : '127.0.0.1';
  return {
    local: {url: 'http://' + localHost + ':' + port + path, specific, address: specific ? listen.address : '',
      listed: !!listen.allowListed, anyOpen},
    lan: {
      urls: (hosts.length ? hosts : ['<这台机器的内网地址>']).map(host => 'http://' + urlHost(host) + ':' + port + path),
      known: hosts.length > 0,
      state: !lanListening ? 'closed' : admitted ? 'ready' : 'refused',
      anyOpen,
      listed: !!s.allowListBeyondLocal,
      address: listen.address || '',
      restartPending: !!listen.restartPending,
      port,
    },
    internet: {url: 'https://<你的域名>' + path, current},
    tunnel: {
      command: 'ssh -N -L ' + port + ':' + localHost + ':' + port + ' <用户名>@<这台机器的地址>',
      url: 'http://127.0.0.1:' + port + path,
    },
  };
}

/**
 * 本机与 SSH 隧道那两条的说明
 *
 * 监听全部网卡或只听回环时：本机连 127.0.0.1，在程序看来就是本机，不用改任何设置。
 * 只听某一个地址时：127.0.0.1 连不上，本机与隧道都连那个地址，而程序看到的来访者是那个地址、不是回环，
 * 通不通要看它在不在「允许访问的来源 IP」里、上面开没开组。
 * @param local remoteRoutes(...).local
 * @return {{level: 'ok'|'warn'|'', local: string, tunnel: string}}
 */
export function localStatus(local) {
  if (!local.specific) {
    return {level: '', local: '不用改任何设置。', tunnel: '在 NovaBot 看来它就是本机来访，不用改任何设置。'};
  }
  const who = '在程序看来来访者是 ' + local.address + '，不是本机回环：';
  const verdict = local.listed
    ? '它在「允许访问的来源 IP」里，带有效的访问令牌即可。'
    : local.anyOpen
      ? '它不在「允许访问的来源 IP」里，上面已打开的那几组带有效令牌可以调，其余的会收到 403；要调全部，把它加进名单。'
      : '它不在「允许访问的来源 IP」里，现在会收到 403：把它加进名单，或者在上面打开要调的那组接口。';
  return {
    level: local.listed ? 'ok' : 'warn',
    local: '监听地址是 ' + local.address + '，只在这个地址上听，127.0.0.1 连不上，同一台机器上也要用这个地址。' + who + verdict,
    tunnel: '隧道另一头连的是 ' + local.address + '（127.0.0.1 上没人听），' + who + verdict,
  };
}

/**
 * 内网那一条现在通不通，以及还差哪几步
 * @param lan remoteRoutes(...).lan
 * @return {{level: 'ok'|'warn'|'err', text: string, steps: string[]}}
 */
export function lanStatus(lan) {
  const steps = [];
  if (lan.state === 'closed') {
    steps.push('到「设置」把「监听地址」改成 0.0.0.0（或这台机器的内网地址），保存后重启 NovaBot');
    steps.push('来访的机器要么把它的地址加进「允许访问的来源 IP」，要么在上面打开它要调的那组接口');
    return {level: 'err',
      text: '现在连不上：监听地址是 ' + (lan.address || '127.0.0.1') + '，只有本机连得上。要从内网连，先做这两步：',
      steps};
  }
  const listen = '监听地址是 ' + (lan.address || '0.0.0.0') + '，内网的机器连得到端口 ' + lan.port + '。';
  if (lan.state === 'refused') {
    steps.push('把来访机器的地址加进「允许访问的来源 IP」（它就能调全部接口，也能打开控制台的登录页）');
    steps.push('或者在上面打开它要调的那组接口（只开那一组）');
    return {level: 'warn',
      text: listen + '但「允许访问的来源 IP」里只有本机，上面也没有打开任何一组，来访的机器现在会收到 403。二选一：',
      steps};
  }
  const who = [];
  if (lan.listed) who.push('地址在「允许访问的来源 IP」里的机器可以调全部接口');
  if (lan.anyOpen) who.push('别的机器可以调上面已打开的那几组');
  return {level: 'ok', text: listen + who.join('；') + '。都要带有效的访问令牌；这台机器的防火墙也要放行这个端口。',
    steps};
}

/**
 * 「从别的机器访问」顶部那条提示：来源名单已经放行所有地址时，下面的开关关着也不拦人
 * @param state GET /api-tokens/remote-access 的回包
 * @return 要提示的话；名单没有全放时为空串
 */
export function remoteNotice(state) {
  if (!(state && state.allowListAdmitsAll)) return '';
  return '「允许访问的来源 IP」已经放行所有地址，下面的开关关着也不会拦人：任何能连到这里的人，'
    + '只要有有效的访问令牌，就能调全部接口。想让这些开关起作用，先到「设置」把「允许访问的来源 IP」收窄到具体的地址或网段'
    + '（这份名单也决定控制台能从哪里打开，收窄时留下你自己正在用的地址）。';
}

/**
 * 每组开关旁的那个状态标
 * @param group 回包里的一组
 * @param state 回包
 * @return {{level: 'ok'|'warn'|'', text: string}}
 */
export function groupBadge(group, state) {
  if (group.open) return {level: 'ok', text: '已对任何地址开放'};
  if (state && state.allowListAdmitsAll) return {level: 'warn', text: '开关此刻不起作用：来源名单已放行所有地址'};
  return {level: '', text: '只对名单里的地址开放'};
}

/** 公网那一条里「转发了通不通」那一句 */
export function publicHint(state) {
  return state && state.allowListAdmitsAll
    ? '来源名单已放行所有地址：转发了就通，只靠访问令牌把关。'
    : '转发了也不等于放通：名单以外的来访者调不调得了，看上面的开关。';
}
