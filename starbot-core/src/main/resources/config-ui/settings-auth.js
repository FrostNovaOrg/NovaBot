/**
 * 「登录与安全」那一组：改密码、二次验证开关、通行密钥、重新跑一遍初始设置
 *
 * 这一组里有三个配置键<b>不走普通的设置保存</b>（密码、二次验证开关、二次验证密钥）：
 * 它们改的时候要先过一道门——改密码要旧密码，关二次验证要现在的验证码，开二次验证
 * 要先绑上验证器。摊成普通行交给底部保存的话，那三道门就只剩「填个格子按保存」，
 * 而底部那个保存按钮既不知道要问什么，也没地方问。
 *
 * 于是这四项仍走自己的流程与端点，只是版式和其他设置项同一套横行；改完<b>当场生效</b>，不进改动条。
 */

import {ask} from './confirm.js';
import {$, api, el, esc, phrase, say, switchControl} from './core.js';
import {bindPasswordReveal} from './password-reveal.js';
import {loadPasskeys, registerPasskey} from './passkeys.js';
import {store} from './store.js';

/**
 * 这张卡吃掉哪几个配置键
 *
 * 设置页那边据它把这几项从普通行里摘走，卡片这边据它把这几件事摆进来。
 * 两处各写一份的话，摘走了却没人摆的那一项会凭空消失。
 */
export const AUTH_CARD_FIELDS = new Set([
  'starbot.core.config-ui.auth.password',
  'starbot.core.config-ui.auth.totp',
  'starbot.core.config-ui.auth.totp-secret',
]);

/** 登录状态里与这张卡有关的那几位，由 main.js 在取到 /auth/state 之后交进来 */
const authState = {enabled: false, totpEnabled: false, operatorSession: false};

/**
 * 记下登录状态
 *
 * 行按这几位决定弹窗里摆哪一版：令牌会话不要求现在的密码（他拿不出旧的），
 * 其余要填现在的密码。
 * @param state /auth/state 的响应
 */
export function setAuthState(state) {
  authState.enabled = !!state.enabled;
  authState.totpEnabled = !!state.totpEnabled;
  authState.operatorSession = !!state.operatorSession;
}

/**
 * 给密码框接上共用的显示／隐藏按钮。按钮先建出来再赋 id，id 必须是字面量——
 * 闭集那一格按源码里的 `.id = '…'` 认落点。
 * @param input 密码框
 * @return 那颗按钮，调用方赋 id
 */
function attachEye(input) {
  const wrap = el('div', 'secret');
  const eye = el('button');
  eye.type = 'button';
  input.parentNode.insertBefore(wrap, input);
  wrap.append(input, eye);
  bindPasswordReveal(input, eye);
  return eye;
}

/** 行里那行键名，「显示键名」打开时才露出来 */
function keyLine(box, name) {
  const line = el('div', 'keyname');
  line.textContent = name;
  box.appendChild(line);
}

/**
 * 和其他设置项同一套横行：左标题＋说明，右一颗控件
 */
function authRow(id, title, desc) {
  const row = el('div', 'field setitem');
  row.id = id;

  const meta = el('div', 'meta');
  const head = el('div', 'si-hd');
  const name = el('b');
  name.textContent = title;
  head.appendChild(name);
  meta.appendChild(head);
  if (desc) {
    const note = el('div', 'desc');
    note.textContent = desc;
    meta.appendChild(note);
  }
  row.appendChild(meta);

  const cell = el('div');
  row.appendChild(cell);
  return {row, meta, cell};
}

function report(box, res) {
  box.textContent = res.message || (res.success ? '好了' : '没能办成');
  box.className = 'al-r ' + (res.success ? 'ok' : 'err');
}

/**
 * 改密码那一行
 *
 * 用启动令牌进来的人摆的是另一版：他拿不出现在的密码，那正是他走这条路的原因。
 * 两版分别打到 password/change 与 password/reset —— 后者只认令牌会话，
 * 合成一条「现在的密码可以不填」的路子就等于给每一枚会话都开了免旧密码改密码的口子。
 */
function passwordCard() {
  const operator = authState.operatorSession;
  const box = authRow('auth-password', '密码',
    operator
      ? '你是用启动令牌进来的，直接设一个新口令即可。设完口令后通道会自动关掉。'
      : '改完当场生效，别处已经登录的会话会一并注销，当前这一个留着。');
  keyLine(box.meta, 'starbot.core.config-ui.auth.password');

  const button = el('button', 'ghost');
  button.type = 'button';
  button.id = 'pwd-save';
  button.textContent = '修改密码';
  box.cell.appendChild(button);

  button.addEventListener('click', async () => {
    const fields = [];
    if (!operator) fields.push({label: '现在的密码', id: 'pwd-current', type: 'password'});
    fields.push({label: '新密码', id: 'pwd-next', type: 'password'});
    fields.push({label: '再输一遍', id: 'pwd-again', type: 'password'});

    const pending = ask({
      title: '修改密码',
      body: operator
        ? '设完之后启动令牌通道会自动关掉。'
        : phrase('bot.impl',
          v => '至少 8 个字符。填的是这个控制台的密码，不是 ' + v + ' 界面的口令。',
          '至少 8 个字符。填的是这个控制台的密码，不是机器人程序界面的口令。'),
      fields,
      danger: false,
    });
    const host = document.querySelector('.ask-fields');
    const current = host && host.querySelector('#pwd-current');
    if (current) {
      const currentEye = attachEye(current);
      currentEye.id = 'pwd-current-reveal';
    }
    const next = host && host.querySelector('#pwd-next');
    const nextEye = attachEye(next);
    nextEye.id = 'pwd-next-reveal';
    const again = host && host.querySelector('#pwd-again');
    const againEye = attachEye(again);
    againEye.id = 'pwd-again-reveal';

    const filled = await pending;
    if (!filled) return;
    if (filled['pwd-next'] !== filled['pwd-again']) {
      say('两次输入的新密码不一样', 'err');
      return;
    }

    button.disabled = true;
    try {
      const body = operator ? {next: filled['pwd-next']} : {current: filled['pwd-current'], next: filled['pwd-next']};
      const res = await api(operator ? '/auth/password/reset' : '/auth/password/change', {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body),
      });
      say(res.message || (res.success ? '好了' : '没能办成'), res.success ? 'ok' : 'err');
    } catch (e) {
      say('没能改成：' + e.message, 'err');
    }
    button.disabled = false;
  });

  return box.row;
}

/**
 * 二次验证那张卡
 *
 * 开与关各有一道门：开要先扫码绑定并输一次码（少了这一步，扫码没扫上的人会以为绑好了，
 * 下次登录被自己的二次验证挡在门外）；关要输一次现在的码（少了这一步，
 * 一枚被偷走的会话 Cookie 就能把这道防线卸掉）。
 */
function totpCard() {
  const on = authState.totpEnabled;
  const box = authRow('auth-totp', '二次验证',
    '只管口令登录这条路。用通行密钥登录不经过这一步——私钥一直在你自己的设备上，'
    + '而设备在签名之前已经问过一次指纹或面容了。');
  box.cell.className = 'boolcell';
  const {label, input, text} = switchControl('totp-switch', on, '二次验证');
  box.cell.appendChild(label);
  keyLine(box.meta, 'starbot.core.config-ui.auth.totp');
  keyLine(box.meta, 'starbot.core.config-ui.auth.totp-secret');

  // 开与关各自要问的那一段，另起整行摆在开关下面，不套进输入栏容器
  const flow = el('div', 'totp-flow');
  flow.id = 'totp-flow';
  box.row.appendChild(flow);

  const result = el('div', 'al-r');
  box.row.appendChild(result);

  const settle = state => {
    input.checked = state;
    text.textContent = state ? '已启用' : '已关闭';
    authState.totpEnabled = state;
    store.totpRequired = state;
    flow.innerHTML = '';
  };

  input.addEventListener('change', () => {
    flow.innerHTML = '';
    result.textContent = '';
    if (input.checked) enrollFlow(flow, result, settle);
    else disableFlow(flow, result, settle);
  });

  return box.row;
}

function recoverToggle(result, settle, prev, e) {
  report(result, {success: false, message: '请求没发出去：' + e.message});
  settle(prev);
}

/**
 * 从关拨到开：先绑定，再算开
 */
async function enrollFlow(flow, result, settle) {
  try {
    const setup = await api('/auth/totp/setup');
    if (!setup.success) {
      report(result, setup);
      settle(false);
      return;
    }

    flow.innerHTML =
      '<p class="al-note">用任意验证器应用扫这个码，再填上它给出的 6 位数字。取消就退回原样。</p>'
      + '<div class="totp-body">'
      + (setup.qrCode ? '<img src="data:image/png;base64,' + esc(setup.qrCode) + '" alt="二维码">' : '')
      + '<div class="totp-side">'
      + '<label for="totp-enroll-code">不方便扫码时手动输入这串密钥</label>'
      + '<code>' + esc(setup.secret) + '</code>'
      + '<div class="totp-confirm">'
      + '<input id="totp-enroll-code" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="6 位数字">'
      + '<button type="button" id="totp-enroll-ok">确认开启</button>'
      + '<button type="button" id="totp-enroll-no">取消</button>'
      + '</div></div></div>';

    $('#totp-enroll-no').addEventListener('click', () => settle(false));
    $('#totp-enroll-ok').addEventListener('click', async () => {
      try {
        const res = await api('/auth/totp/enroll', {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({code: $('#totp-enroll-code').value}),
        });
        report(result, res);
        // 没绑上就把开关拨回去：留在「已启用」上会让人以为绑好了，而下次登录他进不来
        if (res.success) settle(true);
      } catch (e) { recoverToggle(result, settle, false, e); }
    });
  } catch (e) { recoverToggle(result, settle, false, e); }
}

/**
 * 从开拨到关：先要一次现在的码
 */
function disableFlow(flow, result, settle) {
  flow.innerHTML =
    '<p class="al-note">关掉的是一整道防线。填一次验证器现在给的 6 位数字，'
    + '证明它此刻就在你手上。关掉之后那把密钥会一并清掉，验证器里那一条可以删了。</p>'
    + '<div class="totp-confirm">'
    + '<input id="totp-off-code" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="6 位数字">'
    + '<button type="button" id="totp-off-ok">确认关闭</button>'
    + '<button type="button" id="totp-off-no">取消</button>'
    + '</div>';

  $('#totp-off-no').addEventListener('click', () => settle(true));
  $('#totp-off-ok').addEventListener('click', async () => {
    try {
      const res = await api('/auth/totp/disable', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({code: $('#totp-off-code').value}),
      });
      report(result, res);
      settle(!res.success);
    } catch (e) { recoverToggle(result, settle, true, e); }
  });
}

/**
 * 通行密钥那张卡
 *
 * <b>每次重画都重新建出来</b>，不是把 index.html 里那一块搬进来：设置页会整体重绘
 * （保存过一次、放弃一次改动都会），而重绘的第一步是把组容器清空——搬进去的那一块
 * 会跟着一起没掉，此后按 id 取到的是 null，那一块就<b>安静地从页面上消失</b>了。
 *
 * 登记与列表的实现仍在 passkeys.js，这里只管它长在哪儿、什么时候建出来。
 */
function passkeyCard() {
  const box = authRow('auth-passkey', '通行密钥',
    '登记之后，登录时按一下指纹或面容即可，不用再输动态验证码——'
    + '私钥一直在你自己的设备上，这台机器只存得下一把开不了门的公钥。'
    + '通行密钥认的是当前访问地址，换了地址（如从局域网换成域名）要重新登记。');

  const add = el('button', 'ghost');
  add.type = 'button';
  add.id = 'passkey-add';
  add.textContent = '登记这台设备';
  box.cell.appendChild(add);
  // 把按钮本身传进去，不靠那边按 id 取：初始设置页第 1 步也要登记一把，
  // 两处按 id 取就得共用一个 id，而重复 id 取到的永远是靠前的那一个
  add.addEventListener('click', () => registerPasskey(add));

  const wrap = el('div', 'tblwrap');
  const list = el('div');
  list.id = 'passkey-list';
  wrap.appendChild(list);
  box.row.appendChild(wrap);

  loadPasskeys(list, add);
  return box.row;
}

/**
 * 重新跑一遍初始设置
 *
 * 只落一个标记再跳过去，<b>不清任何东西</b>：使用者按这个按钮是想再走一遍流程，
 * 不是想把机器人清空。确认框里写的也是这个意思。
 */
function rerunCard() {
  const box = authRow('auth-rerun', '重新跑一遍初始设置',
    // 第 3 步在定稿里写的是平台名。核心界面不出现平台名（格1 契约），
    // 而装了哪个直播平台是运行期才知道的事——写死一个，没装它的人会看见一步他走不到的流程
    '五步：上锁、连机器人、登录直播平台、加主播、发一条试试。已经配好的东西不会被清掉。');

  const button = el('button', 'ghost');
  button.type = 'button';
  button.id = 'setup-rerun';
  button.textContent = '重新跑一遍';
  box.cell.appendChild(button);

  const result = el('div', 'al-r');
  box.row.appendChild(result);

  button.addEventListener('click', async () => {
    if (!await ask({title: '要走吗？',
      body: '会从第一步开始走一遍，已经配好的东西不会被清掉。'})) return;

    button.disabled = true;
    try {
      const res = await api('/setup/rerun', {method: 'POST'});
      if (res.success) {
        say(res.message, 'ok');
        location.hash = '#/setup';
      } else {
        report(result, res);
      }
    } catch (e) {
      report(result, {success: false, message: '没能标记：' + e.message});
    }
    button.disabled = false;
  });

  return box.row;
}

/**
 * 建出「登录与安全」组里那几行
 *
 * 通行密钥那一块原本写在 index.html 里，这里把它挪进来——它讲的同样是
 * 「这台机器怎么被访问」，与改密码、二次验证是一件事的三个面。
 * @return {HTMLElement} 行容器
 */
export function authCards() {
  const wrap = el('div');
  wrap.id = 'auth-cards';

  // 没配密码的那一形态里没有会话可言，改密码、二次验证与通行密钥全都无从谈起——
  // 摆一个改不了任何东西的表单，比不摆更让人费解。上锁那一步归初始设置页
  if (!authState.enabled) {
    const note = el('p', 'hint');
    note.id = 'auth-unlocked';
    note.textContent = '这台机器还没设密码，因此不出登录页，进来就是控制台。'
      + '要上锁的话，到初始设置页走第 1 步。';
    wrap.appendChild(note);
    return wrap;
  }

  wrap.appendChild(passwordCard());
  wrap.appendChild(totpCard());
  wrap.appendChild(passkeyCard());
  wrap.appendChild(rerunCard());
  return wrap;
}

/**
 * 搜索与「只看改过的」也要能筛到这几行
 *
 * 行里装的同样是配置项。搜「密码」时只剩这一块，比整组原样立着有用得多。
 * @param query 搜索词
 * @param onlyChanged 是否只看改过的
 * @return {number} 显示出来的行里一共有几项配置
 */
export function filterAuthCards(query, onlyChanged) {
  const box = $('#auth-cards');
  if (!box) return 0;

  const q = String(query || '').trim().toLowerCase();
  let shown = 0;

  for (const card of box.querySelectorAll('.setitem, .hint')) {
    const keys = [...card.querySelectorAll('.keyname')].map(k => k.textContent);
    const text = (card.textContent || '').toLowerCase();
    // 这几项不进改动条（改完当场生效），因此「改过」问的只能是「与出厂设定不同」
    const changed = keys.some(k => store.values[k] !== undefined && String(store.values[k]) !== '');
    const hit = (!q || text.includes(q)) && (!onlyChanged || changed);
    card.classList.toggle('hide', !hit);
    if (hit) shown += keys.length;
  }

  box.classList.toggle('hide', !box.querySelector('.setitem:not(.hide), .hint:not(.hide)'));
  return shown;
}
