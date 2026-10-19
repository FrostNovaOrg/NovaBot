/* Loaded only by the static export, before any console modules. */
(() => {
  'use strict';
  const recording = window.NOVA_CONSOLE_DEMO;
  const nativeFetch = window.fetch.bind(window);
  const NativeDate = window.Date;
  const message = '演示模式：改动不会保存';
  const deviceMessage = '演示模式：此功能需要真实设备或服务，离线演示不执行。';
  const queryKey = search => {
    const params = new URLSearchParams(search);
    params.sort();
    return params.toString();
  };
  const key = (method, path, query) => method + ' ' + path + '?' + queryKey(query);
  const responses = new Map(recording.responses.map(row => [key(row.method, row.path, row.query), row]));
  const json = (body, status = 200) => new Response(JSON.stringify(body), {
    status, headers: {'Content-Type': 'application/json; charset=utf-8'},
  });
  const notice = text => {
    const status = document.getElementById('console-demo-notice');
    if (status) {
      status.textContent = text === message ? '' : text;
      status.hidden = !status.textContent;
    }
  };

  // Keep both today's date and elapsed-time labels at the recording instant.
  function DemoDate(...args) {
    if (!new.target) return new DemoDate().toString();
    return Reflect.construct(NativeDate, args.length ? args : [recording.epochMs], new.target);
  }
  Object.setPrototypeOf(DemoDate, NativeDate);
  DemoDate.prototype = Object.create(NativeDate.prototype, {constructor: {value: DemoDate}});
  DemoDate.now = () => recording.epochMs;
  // The recording uses Shanghai time, including on browsers in other timezones.
  for (const part of ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds']) {
    DemoDate.prototype['get' + part] = function () {
      return new NativeDate(this.getTime() + 8 * 3600000)['getUTC' + part]();
    };
  }
  DemoDate.prototype.getTimezoneOffset = () => -480;
  window.Date = DemoDate;

  const read = async row => {
    const response = await nativeFetch('/console-demo/' + row.file);
    if (!response.ok) return json({success: false, message: '演示数据文件缺失'}, 404);
    const body = await response.arrayBuffer();
    if (row.path === '/config/api/auth/state') {
      const state = JSON.parse(new TextDecoder().decode(body));
      Object.assign(state, {authenticated: true, totpRequired: false, totpSetupNeeded: false,
        totpEnabled: false, agreementRequired: false});
      return json(state);
    }
    return new Response(body, {status: row.status, headers: {'Content-Type': row.contentType}});
  };

  // These shapes follow the success branches of the corresponding controllers.
  // No changes are retained in memory, localStorage, or the exported files.
  async function write(path, url, options) {
    notice(message);
    let body = {};
    try { body = JSON.parse(options.body || '{}'); } catch (_) { /* no JSON body */ }
    const ok = extra => json({success: true, message, ...extra});
    if (path === '/report/preview') {
      notice('演示模式：预览显示录制的默认版式，改动不会保存');
      return read(responses.get(key('POST', '/config/api/report/preview', '')));
    }
    if (path === '/values') return ok({changed: Object.keys(body).length,
      restartRequired: [], restartPending: []});
    if (path === '/state/commands') return ok({changed: (body.commands || []).length});
    if (path === '/setup/test-bot') return ok({advice: ''});
    if (path === '/setup/bot') return ok({live: true});
    if (path === '/setup/test-sent') return ok({testSentAt: recording.recordedAt});
    if (path === '/setup/rerun') return ok({rerunRequestedAt: recording.recordedAt});
    if (path === '/setup/rerun/consumed') return json({success: true});
    if (path === '/auth/logout') return json(url.searchParams.get('all') === 'true'
      ? {success: true, count: 0} : {success: true});
    if (/^\/auth\/password\/(set|reset|change)$/.test(path)) return ok({revoked: 0, csrfToken: 'demo-csrf'});
    if (path === '/auth/totp/enroll' || path === '/auth/totp/disable') return ok({csrfToken: 'demo-csrf'});
    if (path === '/auth/login') return json({success: true, csrfToken: 'demo-csrf'});
    if (path === '/auth/agreement/accept') return read(responses.get(key('GET', '/config/api/auth/state', '')));
    if (path === '/auth/totp/skip' || path === '/version/skip') return json({success: true});
    if (path === '/alert/test') {
      const channel = url.searchParams.get('channel');
      return ok({channel, name: {qq: '机器人', webhook: 'Webhook', mail: '邮件'}[channel] || channel,
        status: 'DELIVERED'});
    }
    if (path === '/bot/targets/refresh') {
      const row = responses.get(key('GET', '/config/api/bot/targets', 'type=group'));
      const {fetchedAt, stale, senders} = await (await read(row)).json();
      return json({success: true, fetchedAt, stale, senders});
    }
    if (path === '/test-message') return ok({raw: []});
    if (path === '/streamer/lookup') {
      notice(deviceMessage);
      return json({success: false, message: deviceMessage});
    }
    if (['/push/toggle', '/datasource', '/templates', '/login/logout', '/state/command',
      '/state/revenue', '/state/quiet', '/state/subscription', '/auth/agreement/revoke'].includes(path)
      || /^\/weekly-report\/[^/]+\/\d+\/trial$/.test(path)
      || /^\/event-tokens\/[^/]+\/revoke$/.test(path)
      || /^\/auth\/passkeys\/[^/]+$/.test(path)) return ok();
    return null;
  }

  window.fetch = async (input, options = {}) => {
    const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url, location.href);
    const method = (options.method || input.method || 'GET').toUpperCase();
    const path = url.pathname;
    // Assets may be fetched normally, but no remote requests or unknown writes escape.
    if (url.origin === location.origin && method === 'GET'
      && (path.startsWith('/config/assets/') || path.startsWith('/console-demo/'))) return nativeFetch(input, options);
    if (url.origin === location.origin && (path === '/nova/readonly-token'
      || path.startsWith('/config/api/auth/passkey/') || path === '/config/api/event-tokens'
        && method !== 'GET' || path === '/config/api/napcat/credential')) {
      notice(deviceMessage);
      // Both the direct token call and api() display this response as a plain message.
      return json({success: false, message: deviceMessage});
    }
    if (url.origin === location.origin && path.startsWith('/config/api/')) {
      if (method === 'GET') {
        const row = responses.get(key(method, path, url.search));
        if (row) return read(row);
      } else if (['POST', 'PUT', 'DELETE'].includes(method)) {
        const response = await write(path.slice('/config/api'.length), url, options);
        if (response) return response;
      }
    }
    console.warn('Console demo: unrecorded request ' + method + ' ' + path + url.search);
    return json({success: false, message: '离线演示未录制此请求', items: [], records: [], days: []}, 404);
  };

  function mount() {
    const banner = document.createElement('div');
    banner.id = 'console-demo-banner';
    banner.textContent = message;
    banner.style.cssText = 'position:fixed;right:12px;bottom:8px;z-index:2147483647;'
      + 'padding:4px 9px;background:#292437;color:#fff;border-radius:5px;font:12px/1.6 sans-serif;max-width:90vw';
    document.body.appendChild(banner);
    const detail = document.createElement('div');
    detail.id = 'console-demo-notice';
    detail.setAttribute('role', 'status');
    detail.style.cssText = banner.style.cssText + ';bottom:38px';
    detail.hidden = true;
    document.body.appendChild(detail);
    // A normal navigation/image request does not pass through fetch. Preview links
    // point to the same raw response file; unsupported services get a local notice.
    document.addEventListener('click', event => {
      const link = event.target.closest('a');
      if (!link) return;
      const url = new URL(link.href, location.href);
      if (url.origin !== location.origin || url.pathname.startsWith('/config/manual')) {
        event.preventDefault(); notice(deviceMessage); return;
      }
      if (!url.pathname.startsWith('/config/api/')) return;
      event.preventDefault();
      const row = responses.get(key('GET', url.pathname, url.search));
      if (row) window.open('/console-demo/' + row.file, '_blank', 'noopener');
      else { console.warn('Console demo: unrecorded navigation ' + url.pathname + url.search); notice('离线演示未录制此请求'); }
    }, true);
    // Stop before a platform authenticator is requested, without changing passkeys.js.
    document.addEventListener('click', event => {
      const button = event.target.closest('button');
      if (button && (/通行密钥/.test(button.textContent) || button.id === 'tk-issue')) {
        event.preventDefault(); event.stopImmediatePropagation(); notice(deviceMessage);
      }
    }, true);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, {once: true});
  else mount();
})();
