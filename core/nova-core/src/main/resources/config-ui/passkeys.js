/**
 * 设置页里的通行密钥：登记、列表与删除
 *
 * 只做接线，版式从简。登录页那一侧另有一份同样的字节转换——login.html 是一张
 * 不取任何外部资源的独立页面（安全过滤器在未登录时直接把它吐出来），
 * 从这里 import 会让它多一次请求，而那次请求恰好发生在还没登录的时候。
 */

import {ask} from './confirm.js';
import {$, api, el, esc, say} from './core.js';
import {bindPasswordReveal} from './password-reveal.js';

/**
 * 浏览器把凭据里的二进制都表示成 ArrayBuffer，而接口两侧一律用 base64url。
 * 转换只此一处，免得各调用点各写一遍——写错的表现是「验证失败」，与按错指纹长得一样。
 */
const toBuffer = value => Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));

const toBase64Url = buffer => btoa(String.fromCharCode(...new Uint8Array(buffer)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/**
 * 这个浏览器支不支持通行密钥
 *
 * 接口只在安全上下文（https 或 localhost）里存在。内网 http 访问时它直接是 undefined——
 * 不判这一句的话，点下去只会抛一个使用者看不懂的异常
 */
const supported = () => typeof window.PublicKeyCredential === 'function';

/**
 * 画出已登记的通行密钥
 *
 * 🔴 <b>列表节点与登记按钮由调用方传进来</b>，不在这里按 id 取：设置页那张卡是游离节点，
 * 建出来时还没挂进文档。按 id 从 document 取会静默拿不到，卡片里既没有已登记的设备，
 * 也没有「还没有登记过通行密钥」那句提示——登记后列表空白。
 * 登记按钮那一路已经是调用方传入；列表这一路必须同样处理。
 *
 * 缺参时才退回按 id 取一次，只为兼容。
 * @param box 列表容器
 * @param addButton 登记按钮；浏览器不支持通行密钥、或这个地址用不了时置灰它
 */
export async function loadPasskeys(box, addButton) {
  if (!box) box = $('#passkey-list');
  if (!addButton) addButton = $('#passkey-add');
  if (!box) return;

  if (!supported()) {
    box.innerHTML = '<p class="hint">这个浏览器（或这个地址）用不了通行密钥。'
      + '通行密钥只在 https 或 localhost 下可用。</p>';
    if (addButton) addButton.disabled = true;
    return;
  }

  const data = await api('/auth/passkeys');
  const list = data.passkeys || [];

  // 浏览器给了接口、地址却是 IP 时，点「登记」必然失败：进页就置灰，并把原因印在列表之前。
  // 原因句用服务端那一句，判 IP 的只在服务端一处；已登记的照常列出，删除不受地址影响
  const blocked = data.usable === false;
  if (blocked && addButton) addButton.disabled = true;
  const notice = blocked ? '<p class="hint">' + esc(data.unusableReason || '这个地址用不了通行密钥。') + '</p>' : '';

  if (!list.length) {
    box.innerHTML = notice || '<p class="hint">还没有登记过通行密钥。登记之后，登录时按一下指纹或面容即可，'
      + '<b>不用再输动态验证码</b>。</p>';
    return;
  }

  box.innerHTML = notice + '<table><thead><tr><th>名字</th><th>登记时间</th><th>上次使用</th><th></th></tr></thead><tbody>'
    + list.map(item => '<tr><td>' + esc(item.name) + '</td><td>' + time(item.createdAt) + '</td><td>'
      + (item.lastUsedAt ? time(item.lastUsedAt) : '还没用过')
      + '</td><td><button type="button" class="ghost" data-id="' + esc(item.id) + '">删除</button></td></tr>').join('')
    + '</tbody></table>';
  const table = box.querySelector('table');
  table.style.width = 'max-content';
  table.style.minWidth = '100%';

  // 回调带上当次的容器与按钮：删除之后要重画的就是接线这一张，不认「最近一次」
  box.querySelectorAll('button[data-id]').forEach(
    button => button.addEventListener('click', () => remove(button.dataset.id, box, addButton)));
}

/**
 * 时间只显示到分钟：秒对使用者判断「这条能不能删」毫无帮助
 */
function time(value) {
  const at = new Date(value);
  return isNaN(at) ? esc(value) : esc(at.toLocaleString('zh-CN', {hour12: false}).replace(/:\d\d$/, ''));
}

/**
 * 这个地址眼下登记得了通行密钥吗：登记不了回一句原因，登记得了回 null
 *
 * 给初始设置页那只登记钮进页就判：浏览器没给接口时点下去什么也不发生，
 * 地址是 IP 时要点了才从登记参数那一条的失败回包里得知。两样都该进页就说。
 * 判 IP 的只在服务端一处：这里取列表那一条的 usable 与原因句，不另开接口
 */
export async function registerBlockedReason() {
  if (!supported()) return '这个浏览器（或这个地址）用不了通行密钥。通行密钥只在 https 或 localhost 下可用。';
  const data = await api('/auth/passkeys');
  return data.usable === false ? (data.unusableReason || '这个地址用不了通行密钥。') : null;
}

/**
 * 给弹层里的密码框接上显隐按钮
 *
 * settings-auth.js 那份 attachEye 不能反过来用：它已经 import 本件，
 * 再 import 回去就成环。共用的只有 password-reveal.js 里的判定与接线。
 * @param input 密码框
 */
function attachEyeForAsk(input) {
  const wrap = el('div', 'secret');
  const eye = el('button');
  eye.type = 'button';
  eye.id = 'pwd-passkey-current-reveal';
  input.parentNode.insertBefore(wrap, input);
  wrap.append(input, eye);
  bindPasswordReveal(input, eye);
  return eye;
}

/**
 * 登记一把通行密钥
 *
 * 🔴 <b>按钮由调用方传进来</b>，不在这里按 id 取：初始设置页第 1 步也要登记一把，
 * 而它与设置页那个按钮同时存在于这份文档里。按 id 取的话两处必须共用一个 id，
 * 那是重复 id——document 里重复 id 不报错，取到的永远是靠前的那一个，
 * 表现是使用者在初始设置页点了按钮，转圈的却是设置页上那个他看不见的按钮。
 * @param trigger 触发这次登记的按钮，登记期间置灰它；没有时传 null
 */
export async function registerPasskey(trigger) {
  if (!supported()) return;

  const button = trigger || $('#passkey-add');
  if (button) button.disabled = true;

  try {
    // 先要现在的密码，再取登记参数：顺序反了的话，输错的人在认证器里
    // 留下一把没人认领的钥匙，比多问一次更糟
    const pending = ask({
      title: '登记通行密钥',
      body: '登记前要再输一次现在的密码，证明是你本人。',
      fields: [{label: '现在的密码', id: 'pwd-passkey-current', type: 'password'}],
      danger: false,
    });
    const host = document.querySelector('.ask-fields');
    const currentInput = host && host.querySelector('#pwd-passkey-current');
    if (currentInput) attachEyeForAsk(currentInput);
    const filled = await pending;
    // 取消了就是取消：还没向认证器要参数，不会留下一把没人认领的钥匙
    if (!filled) return;
    const options = await api('/auth/passkey/register/options', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({current: filled['pwd-passkey-current']}),
    });
    if (!options.success) {
      say(options.message, 'err');
      return;
    }

    // 先让系统确认（指纹、面容、或手机上按一下），再问名字：
    // 反过来的话，使用者起完名字才发现设备不支持，那个名字白起了
    const credential = await navigator.credentials.create({
      publicKey: {
        challenge: toBuffer(options.challenge),
        rp: options.rp,
        user: {
          id: toBuffer(options.user.id),
          name: options.user.name,
          displayName: options.user.displayName,
        },
        pubKeyCredParams: options.pubKeyCredParams,
        timeout: options.timeout,
        attestation: options.attestation,
        authenticatorSelection: options.authenticatorSelection,
        excludeCredentials: (options.excludeCredentials || []).map(
          item => ({type: 'public-key', id: toBuffer(item.id)})),
      },
    });

    const nameLimit = options.nameLimit || 40;
    const named = await ask({
      title: '给这把通行密钥起个名字',
      fields: [{label: '给这把通行密钥起个名字', value: '我的设备', maxlength: nameLimit}],
      danger: false,
    });
    // 取消了就是取消：这一步之后才提交，因此认证器上那把钥匙不会留下一个没人认领的登记
    if (!named) return;
    const name = Object.values(named)[0];

    const result = await api('/auth/passkey/register/verify', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        id: credential.id,
        name,
        response: {
          clientDataJSON: toBase64Url(credential.response.clientDataJSON),
          attestationObject: toBase64Url(credential.response.attestationObject),
        },
      }),
    });

    say(result.message, result.success ? 'ok' : 'err');
    // 登记成功后重画：按钮是点进来那一颗，列表取文档里现行的那张——
    // 设置页的卡在场就重画它；初始设置那一步没有列表，画不进也不必画
    if (result.success) await loadPasskeys(null, trigger);
  } catch (e) {
    // 使用者按了取消也会走到这里。不说成「出错了」——那会让人以为设备有问题
    say(e.name === 'NotAllowedError' ? '已取消登记' : '登记失败：' + e.message,
      e.name === 'NotAllowedError' ? '' : 'err');
  } finally {
    if (button) button.disabled = false;
  }
}

/**
 * 删除按钮接线时把当次那一对传进来，删完重画的就是同一张——
 * 卡若已被重画换掉，也不去认全局的「最近一次」
 */
async function remove(id, box, addButton) {
  if (!await ask({title: '确定删除？',
    body: '删了以后这台设备就只能用密码进。'})) return;

  const result = await api('/auth/passkeys/' + encodeURIComponent(id), {method: 'DELETE'});
  say(result.message, result.success ? 'ok' : 'err');
  await loadPasskeys(box, addButton);
}
