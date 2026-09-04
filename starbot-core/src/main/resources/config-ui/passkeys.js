/**
 * 设置页里的通行密钥：登记、列表与删除
 *
 * 只做接线，版式从简。登录页那一侧另有一份同样的字节转换——login.html 是一张
 * 不取任何外部资源的独立页面（安全过滤器在未登录时直接把它吐出来），
 * 从这里 import 会让它多一次请求，而那次请求恰好发生在还没登录的时候。
 */

import {$, api, el, esc, say} from './core.js';

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

export async function loadPasskeys() {
  const box = $('#passkey-list');
  if (!box) return;

  if (!supported()) {
    box.innerHTML = '<p class="hint">这个浏览器（或这个地址）用不了通行密钥。'
      + '通行密钥只在 https 或 localhost 下可用。</p>';
    $('#passkey-add').disabled = true;
    return;
  }

  const data = await api('/auth/passkeys');
  const list = data.passkeys || [];

  if (!list.length) {
    box.innerHTML = '<p class="hint">还没有登记过通行密钥。登记之后，登录时按一下指纹或面容即可，'
      + '<b>不用再输动态验证码</b>。</p>';
    return;
  }

  box.innerHTML = '<table><thead><tr><th>名字</th><th>登记时间</th><th>上次使用</th><th></th></tr></thead><tbody>'
    + list.map(item => '<tr><td>' + esc(item.name) + '</td><td>' + time(item.createdAt) + '</td><td>'
      + (item.lastUsedAt ? time(item.lastUsedAt) : '还没用过')
      + '</td><td><button type="button" class="ghost" data-id="' + esc(item.id) + '">删除</button></td></tr>').join('')
    + '</tbody></table>';

  box.querySelectorAll('button[data-id]').forEach(
    button => button.addEventListener('click', () => remove(button.dataset.id)));
}

/**
 * 时间只显示到分钟：秒对使用者判断「这条能不能删」毫无帮助
 */
function time(value) {
  const at = new Date(value);
  return isNaN(at) ? esc(value) : esc(at.toLocaleString('zh-CN', {hour12: false}).replace(/:\d\d$/, ''));
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
    const options = await api('/auth/passkey/register/options', {method: 'POST'});
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

    const name = prompt('给这把通行密钥起个名字，好在列表里认出它（最长 '
      + (options.nameLimit || 40) + ' 个字）', '我的设备');
    // 取消了就是取消：这一步之后才提交，因此认证器上那把钥匙不会留下一个没人认领的登记
    if (name === null) return;

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
    if (result.success) await loadPasskeys();
  } catch (e) {
    // 使用者按了取消也会走到这里。不说成「出错了」——那会让人以为设备有问题
    say(e.name === 'NotAllowedError' ? '已取消登记' : '登记失败：' + e.message,
      e.name === 'NotAllowedError' ? '' : 'err');
  } finally {
    if (button) button.disabled = false;
  }
}

async function remove(id) {
  if (!confirm('删了以后这台设备就只能用口令进。确定删除？')) return;

  const result = await api('/auth/passkeys/' + encodeURIComponent(id), {method: 'DELETE'});
  say(result.message, result.success ? 'ok' : 'err');
  await loadPasskeys();
}
