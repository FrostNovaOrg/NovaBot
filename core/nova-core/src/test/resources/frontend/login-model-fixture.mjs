/**
 * 登录页显隐矩阵与锁定文案的夹具
 *
 * 量的是 config-ui/login-model.js：登录页上「摆什么、折什么、禁什么、写哪句话」这四件事。
 * 它们由三个互不相干的条件决定——这台机器登记过通行密钥没有、二次验证开着没有、
 * 这个来源此刻是不是被锁着——因此一共八格。八格里只有一格是使用者最常见的那一格，
 * 靠人打开页面手点，点得到的永远是那一格。
 *
 * 由 LoginModelTest 拉起，退码 0 ＝ 全绿；非 0 ＝ 有格子红了，红的那几条会逐条印出来。
 * 引用路径是相对的，量的是源码树里的那一份，不是构建产物里的副本——也正是登录页
 * 在服务端拼进自己那张页面里的同一份字节。
 */

import {loginView, lockText, loginControlState, landing} from '../../../main/resources/config-ui/login-model.js';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/** 把一格的四个判定收成一行，便于逐格对照 */
function view(over) {
  const v = loginView(Object.assign({hasPasskey: false, totpRequired: false, lockedSeconds: 0}, over));
  return {passkey: v.passkey, folded: v.passwordFolded, code: v.code, disabled: v.disabled};
}

// ---------- 一、八格矩阵（通行密钥有/无 × 二次验证开/关 × 锁定中） ----------
//
// 三条规则各管一维，互不牵连：
//   通行密钥登记过 ⇒ 摆主按钮，口令那条路折起来成为第二条路
//   二次验证开着   ⇒ 出 6 位框
//   这个来源锁着   ⇒ 整张表单禁用（通行密钥那条路也一样——锁定按来源计，不按凭据种类计）
const CELLS = [
  [{}, {passkey: false, folded: false, code: false, disabled: false}, '无钥·无码·未锁'],
  [{totpRequired: true}, {passkey: false, folded: false, code: true, disabled: false}, '无钥·有码·未锁'],
  [{lockedSeconds: 90}, {passkey: false, folded: false, code: false, disabled: true}, '无钥·无码·锁定'],
  [{totpRequired: true, lockedSeconds: 90},
    {passkey: false, folded: false, code: true, disabled: true}, '无钥·有码·锁定'],
  [{hasPasskey: true}, {passkey: true, folded: true, code: false, disabled: false}, '有钥·无码·未锁'],
  [{hasPasskey: true, totpRequired: true},
    {passkey: true, folded: true, code: true, disabled: false}, '有钥·有码·未锁'],
  [{hasPasskey: true, lockedSeconds: 90},
    {passkey: true, folded: true, code: false, disabled: true}, '有钥·无码·锁定'],
  [{hasPasskey: true, totpRequired: true, lockedSeconds: 90},
    {passkey: true, folded: true, code: true, disabled: true}, '有钥·有码·锁定'],
];
for (const [state, expected, name] of CELLS) eq(view(state), expected, '矩阵 ' + name);

// 通行密钥登录不经二次验证（清单 §二），因此那个按钮的显示与 totpRequired 无关：
// 上面第 5 格与第 6 格的 passkey 都是 true，这一条把那件事单独说一遍
eq(view({hasPasskey: true, totpRequired: true}).passkey,
  view({hasPasskey: true, totpRequired: false}).passkey, '通行密钥按钮不受二次验证影响');

for (const hasPasskey of [false, true]) {
  for (const oidcCreateSupported of [undefined, false, true]) {
    const v = loginView({oidcEnabled: true, oidcCreateSupported, hasPasskey});
    eq(v.oidc, true, '统一登录显示');
    eq(v.oidcCreate, !!oidcCreateSupported, '注册只在支持 create 时显示');
    eq(v.passwordFolded, true, '统一登录启用时口令折起');
    eq(v.passkeySecondary, hasPasskey, '统一登录时通行密钥为次按钮');
    eq(v.firstButton, 'oidc', '折起后聚焦第一颗可见按钮');
  }
}
eq(loginView({oidcCreateSupported: true}).oidcCreate, false, '关闭统一登录时注册不出');
for (const hasPasskey of [false, true]) {
  eq(loginView({hasPasskey, oidcEnabled: false, oidcCreateSupported: true}).passwordFolded,
    hasPasskey, '关闭统一登录后保留原有折叠规则');
}

// 统一登录与通行密钥的四种组合：本地入口的位置、摘要与初始焦点。
for (const oidcEnabled of [false, true]) {
  for (const hasPasskey of [false, true]) {
    const v = loginView({oidcEnabled, hasPasskey});
    eq({otherMethodsFolded: v.otherMethodsFolded, passkey: v.passkey,
      passwordFolded: v.passwordFolded, foldLabel: v.foldLabel,
      passkeySecondary: v.passkeySecondary, passwordSecondary: v.passwordSecondary,
      passkeyDivider: v.passkeyDivider, firstButton: v.firstButton,
      otherMethodsFirstControl: v.otherMethodsFirstControl},
    {otherMethodsFolded: oidcEnabled, passkey: hasPasskey,
      passwordFolded: oidcEnabled || hasPasskey,
      foldLabel: oidcEnabled ? '其他登录方式' : '使用密码登录',
      passkeySecondary: oidcEnabled && hasPasskey, passwordSecondary: oidcEnabled,
      passkeyDivider: hasPasskey && !oidcEnabled,
      firstButton: oidcEnabled ? 'oidc' : (hasPasskey ? 'passkey' : 'password'),
      otherMethodsFirstControl: hasPasskey ? 'passkey' : 'password'},
    '登录入口组合 oidc=' + oidcEnabled + ', passkey=' + hasPasskey);
  }
}

// 要求本地重登时，管理员应直接看到本地入口，摘要仍可收起。
for (const oidcEnabled of [false, true]) {
  for (const hasPasskey of [false, true]) {
    for (const oidcResult of ['', 'local_required', 'failed', 'pending', 'denied', 'bound']) {
      const v = loginView({oidcEnabled, hasPasskey, oidcResult});
      eq(!!v.passwordInitiallyOpen, oidcEnabled && oidcResult === 'local_required',
        '本地重登初始展开 oidc=' + oidcEnabled + ', passkey=' + hasPasskey + ', result=' + oidcResult);
      eq(v.passwordFolded, oidcEnabled || hasPasskey, '本地重登保留摘要与开合');
    }
  }
}

// ---------- 二、锁定判定 ----------
eq(loginView({lockedSeconds: 0}).locked, false, '剩 0 秒不算锁定');
eq(loginView({lockedSeconds: 1}).locked, true, '剩 1 秒就算锁定');
// 服务端不该给负数，但界面不能因为一个负数就把表单永久禁用
eq(loginView({lockedSeconds: -5}).locked, false, '负数不当锁定');
eq(loginView({}).locked, false, '没给这一位时不当锁定');
eq(loginView({lockedSeconds: null}).locked, false, 'null 不当锁定');

// ---------- 三、锁定文案：必须含实值秒数 ----------
eq(lockText(0), '', '没锁定时不出文案');
eq(lockText(-3), '', '负数同样不出文案');
eq(lockText(45).includes('45 秒'), true, '不足一分钟按秒写');
eq(lockText(45).includes('分'), false, '不足一分钟不写「分」');
eq(lockText(120).includes('2 分'), true, '整分钟按分写');
eq(lockText(120).includes('秒'), false, '整分钟不拖一个「0 秒」');
eq(lockText(125).includes('2 分 5 秒'), true, '零头分秒都写');
eq(lockText(1).includes('1 秒'), true, '只剩一秒也写出来');
// 那句话本身：锁定期内输对也拒，这一条必须说出来——不说的话，
// 使用者会以为是口令记错了，转头去把一个根本没问题的口令改掉
eq(lockText(60).includes('输入正确也会被拒绝'), true, '文案说明锁定期内输对也会被拒');
eq(lockText(60).includes('请勿因此修改密码'), true, '文案劝住「去改密码」这个动作');
// 拿变量拼出来的秒数是这条判据的要害：写死一个「15 分钟」的话，
// 屏幕上那个数字与真实剩余时间无关，而两者长得一模一样
eq(lockText(7) === lockText(8), false, '不同剩余时长给出不同文案');

// ---------- 四、锁定时五颗控件的 disabled（闭集） ----------
//
// 键就是页面上那五颗控件的 id。少一项等于那一颗锁定期仍能点——
// 眼睛漏了的那一版，锁定期仍能把口令揭开。
const CONTROL_IDS = ['passkey', 'password', 'password-reveal', 'code', 'submit'];

function controls(over) {
  return loginControlState(loginView(Object.assign(
    {hasPasskey: false, totpRequired: false, lockedSeconds: 0}, over)));
}

function keysOf(state) {
  return Object.keys(state).sort();
}

const lockedControls = controls({lockedSeconds: 90});
eq(keysOf(lockedControls), CONTROL_IDS.slice().sort(), '锁定态键集恰五个');
for (const id of CONTROL_IDS) eq(lockedControls[id], true, '锁定 90 秒时 ' + id + ' 禁');

const freeControls = controls({lockedSeconds: 0});
eq(keysOf(freeControls), CONTROL_IDS.slice().sort(), '未锁定键集恰五个');
for (const id of CONTROL_IDS) eq(freeControls[id], false, '未锁定时 ' + id + ' 不禁');

// ---------- 五、落点 ----------
//
// 登录成功后要去的地方。安全过滤器把登录页原地吐在 /config 上（地址栏不变），
// 因此「当前已经是那个地址、只是带个片段」时，replace 落在同址上只做片段导航，
// 页面不重取，人进不了控制台——那一档要改成整页重载。
eq(landing('https://accounts.example.com/config#/settings', '#/settings', '/config').reload, true, '同址带片段：replace 只做片段导航，须改重载');
eq(landing('https://accounts.example.com/config', '', '/config'), {reload: false, url: '/config'}, '无片段时不重载，落点是 /config');
eq(landing('https://accounts.example.com/config?token=x', '', '/config').reload, false, '带 query 的现址与落点不同，replace 照常整页导航');

// 断网时通行密钥登录不能露出浏览器的英文；取消和设备错误仍按各自原因处理。
const loginHtml = readFileSync(new URL('../../../main/resources/config-ui/login.html', import.meta.url), 'utf8');
const passkeyLogin = loginHtml.match(/^  async function loginWithPasskey\(\) \{[\s\S]*?^  }/m)?.[0];
if (!passkeyLogin) throw new Error('登录页没有找到 loginWithPasskey');

async function passkeyResult(failedRequest, credentialError) {
  const msg = {textContent: ''};
  const button = {disabled: false};
  let requests = 0;
  let entered = false;
  const context = {
    TypeError, CONSOLE: '/novabot/config',
    $: selector => selector === '#msg' ? msg : button,
    markInvalidCredentials() {}, paint() { button.disabled = false; },
    enterConsole() { entered = true; },
    toBuffer: value => value, toBase64Url: value => value,
    navigator: {credentials: {get: async () => {
      if (credentialError) throw credentialError;
      return {id: 'key', response: {clientDataJSON: 'client', authenticatorData: 'auth', signature: 'sig'}};
    }}},
    fetch: async () => {
      requests++;
      if (requests === failedRequest) throw new TypeError('Failed to fetch');
      return {json: async () => ({success: true, challenge: 'challenge'})};
    },
  };
  await runInNewContext(passkeyLogin + '\nloginWithPasskey();', context);
  eq(button.disabled, false, '通行密钥请求结束后按钮恢复');
  return {text: msg.textContent, requests, entered};
}

for (const [request, name] of [[1, '取挑战'], [2, '验证']]) {
  const result = await passkeyResult(request);
  eq(result.text, '无法使用通行密钥：无法连接到服务器', '通行密钥' + name + '断网显示中文');
  eq(result.requests, request, '通行密钥' + name + '断网不再发后续请求');
  eq(result.entered, false, '断网不进入控制台');
}
eq((await passkeyResult(0, {name: 'NotAllowedError', message: 'cancelled'})).text, '', '通行密钥取消不提示错误');
eq((await passkeyResult(0, new Error('设备不可用'))).text, '无法使用通行密钥：设备不可用', '通行密钥设备错误保留原因');
eq((await passkeyResult(0, new TypeError('设备参数错误'))).text, '无法使用通行密钥：设备参数错误', '非请求的 TypeError 保留原因');
eq((await passkeyResult(0)).entered, true, '通行密钥正常验证仍进入控制台');

const paint = loginHtml.match(/^  function paint\(\) \{[\s\S]*?^  }/m)?.[0];
const showLogin = loginHtml.match(/^  function showLogin\(state\) \{[\s\S]*?^  }/m)?.[0];
const focusOtherMethod = loginHtml.match(/^  function focusOtherMethod\(\) \{[\s\S]*?^  }/m)?.[0];
const passwordSubmit = loginHtml.match(/^  \$\('#form'\)\.addEventListener\('submit', async e => \{[\s\S]*?^  }\);/m)?.[0];
if (!focusOtherMethod) throw new Error('登录页缺少 focusOtherMethod');
const toggleListener = loginHtml.match(/^  \$\('#pwd-fold'\)\.addEventListener\('toggle', focusOtherMethod\);/m)?.[0] || '';
if (!paint || !showLogin || !passwordSubmit) throw new Error('登录页缺少 paint 或 showLogin');

for (const oidcEnabled of [false, true]) {
  for (const hasPasskey of [false, true]) {
    for (const supported of [undefined, false, true]) {
      for (const lockedSeconds of [0, 90]) {
        for (const oidcResult of ['', 'local_required', 'failed', 'pending', 'denied', 'bound']) {
          const elements = new Map();
          let focused = '';
          for (const match of loginHtml.matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)) {
            const events = new EventTarget();
            const classes = new Set(match[0].match(/\bclass="([^"]*)"/)?.[1].split(' ') || []);
            elements.set('#' + match[1], {
              addEventListener: events.addEventListener.bind(events),
              dispatchEvent: events.dispatchEvent.bind(events),
              insertBefore(child) { child.parentElement = this; },
              textContent: '', value: '',
              disabled: false, open: match[0].includes(' open'),
              focus() { focused = match[1]; },
              classList: {
                contains: name => classes.has(name),
                toggle(name, force) {
                  if (force) classes.add(name); else classes.delete(name);
                },
              },
            });
          }
          const $ = selector => elements.get(selector);
          $('#passkey-field').parentElement = $('#login');
          let submitPassword;
          $('#form').addEventListener = (name, handler) => { submitPassword = handler; };
          const state = {oidcEnabled, oidcCreateSupported: supported, lockedSeconds, totpRequired: true};
          const context = {
            $, loginView, loginControlState, state, URLSearchParams,
            location: {search: oidcResult ? '?oidc=' + oidcResult : ''}, show: selector => $(selector).classList.toggle('hidden', false),
            tick() {}, offerPasskey() {}, markInvalidCredentials() {},
            CONSOLE: '/config', fetch: async () => ({json: async () => ({
              success: false, credentialsRejected: true, message: '密码或验证码不正确'
            })}),
          };
          runInNewContext(`let hasPasskey = ${hasPasskey}, folded = false, totp = false,
            lockedSeconds = 0, oidcEnabled = false, oidcCreateSupported = false, oidcResult = null, focusedButton = '';
            ${paint}\n${focusOtherMethod}\n${toggleListener}\n${passwordSubmit}\n${showLogin}\nshowLogin(state);`, context);
          const label = JSON.stringify(state) + ', passkey=' + hasPasskey + ', result=' + oidcResult;
          const localRequired = oidcEnabled && oidcResult === 'local_required';
          eq($('#oidc-field').classList.contains('hidden'), !oidcEnabled, '统一登录显隐 ' + label);
          eq($('#passkey-field').parentElement === $(oidcEnabled ? '#local-passkey' : '#login'),
            true, '通行密钥与说明位于正确的折内或卡片上 ' + label);
          eq($('#login-fold-label')?.textContent, oidcEnabled ? '其他登录方式' : '使用密码登录', '折叠入口文案 ' + label);
          eq($('#oidc-create-field').classList.contains('hidden'), !(oidcEnabled && !!supported), '注册显隐 ' + label);
          eq($('#passkey-field').classList.contains('hidden'), !hasPasskey, '通行密钥显隐 ' + label);
          eq($('#passkey').classList.contains('secondary'), oidcEnabled && hasPasskey, '通行密钥样式 ' + label);
          eq($('#submit').classList.contains('secondary'), oidcEnabled, '口令提交样式 ' + label);
          eq($('#pwd-fold').open, localRequired || !(oidcEnabled || hasPasskey), '口令折叠 ' + label);
          eq($('#pwd-fold').classList.contains('bare'), !(oidcEnabled || hasPasskey), '摘要行显隐 ' + label);
          eq($('#passkey-or').classList.contains('hidden'), !(hasPasskey && !oidcEnabled), '分隔线显隐 ' + label);
          if (oidcEnabled || hasPasskey) {
            eq(focused, localRequired && !lockedSeconds ? (hasPasskey ? 'passkey' : 'password') : (oidcEnabled ? 'oidc' : 'passkey'), '焦点落在首颗可见按钮 ' + label);
            $('#pwd-fold').open = false;
            $('#pwd-fold').dispatchEvent(new Event('toggle'));
            runInNewContext('paint();', context);
            eq($('#pwd-fold').open, false, '手动收起后重画仍收起 ' + label);
            focused = oidcEnabled ? 'oidc' : focused;
            $('#pwd-fold').open = true;
            if (oidcEnabled) {
              $('#pwd-fold').dispatchEvent(new Event('toggle'));
              eq(focused, lockedSeconds > 0 ? 'oidc' : (hasPasskey ? 'passkey' : 'password'),
                '展开后聚焦可用的首个本地控件 ' + label);
            }
            await submitPassword({preventDefault() {}});
            eq($('#pwd-fold').open, true, '口令失败后输入框仍展开 ' + label);
            eq($('#msg').textContent.includes('密码或验证码'), true, '口令失败提示保留 ' + label);
            eq($('#msg').classList.contains('hidden'), false, '口令失败提示可见 ' + label);
            runInNewContext('totp = true; lockedSeconds = 90; paint(); lockedSeconds--; paint();', context);
            eq($('#pwd-fold').open, true, '验证码与倒计时重画不收起 ' + label);
            eq($('#code-field').classList.contains('hidden'), false, '动态验证码仍可见 ' + label);
            runInNewContext('lockedSeconds = state.lockedSeconds; paint();', context);
            eq($('#pwd-fold').open, true, '重画不收起手动展开的口令 ' + label);
          }
          if (localRequired && !hasPasskey) {
            runInNewContext('hasPasskey = true; paint();', context);
            eq(focused, lockedSeconds ? 'oidc' : 'passkey', '稍后探测到通行密钥后焦点跟随可用入口 ' + label);
          }
          for (const id of CONTROL_IDS) eq($('#' + id).disabled, lockedSeconds > 0, '锁定控件 ' + id + label);
          for (const id of ['oidc', 'oidc-create']) eq($('#' + id).disabled, false, '统一登录不受本地锁定影响 ' + id + label);
        }
      }
    }
  }
}

// ---------- 报数 ----------
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
