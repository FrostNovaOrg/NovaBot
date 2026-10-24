// 登录状态尚未返回、请求失败、重复点击与后退恢复都应给出可见反馈。
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';

const login = readFileSync(process.argv[2], 'utf8');
const read = name => readFileSync(new URL('../../../main/resources/config-ui/' + name, import.meta.url), 'utf8');
let checks = 0;
function eq(actual, expected, message) { assert.deepEqual(actual, expected, message); checks++; }
function fn(name) {
  const source = login.match(new RegExp('^  function ' + name + '\\([^]*?^  }', 'm'))?.[0];
  assert.ok(source, '页面缺少 ' + name);
  return source;
}

const stateFetch = login.match(/^  fetch\(CONSOLE \+ '\/api\/auth\/state'\)[^]*?^    \.catch\([^\n]*$/m)?.[0];
assert.ok(stateFetch, '缺少状态请求');
for (const failure of [false, true]) {
  let resolveState;
  let rejectState;
  const pending = new Promise((resolve, reject) => { resolveState = resolve; rejectState = reject; });
  const visible = new Set(['#login-startup']);
  let painted = false;
  const context = {
    CONSOLE: '/novabot/config', fetch: () => pending,
    hide: selector => visible.delete(selector), show: selector => visible.add(selector),
    paint() { painted = true; }, showLogin() { visible.add('#login'); },
    showAgreement() { visible.add('#agreement'); }, enterConsole() {},
  };
  const finished = runInNewContext(fn('apply') + '\n' + stateFetch, context);
  eq(visible.has('#login-startup'), true, '状态未返回时保持加载内容');
  if (failure) rejectState(new TypeError('offline'));
  else resolveState({json: async () => ({enabled: true, authenticated: false})});
  await finished;
  eq(visible.has('#login-startup'), false, '请求结束后收起加载内容');
  eq(visible.has('#login'), true, '成功或失败均摆出登录入口');
  if (failure) eq(painted, true, '断网仍重画口令表单');
}

for (const clicked of ['oidc', 'oidc-create']) {
  const elements = new Map();
  for (const [id, label] of [['oidc', '使用统一登录'], ['oidc-create', '用统一身份注册']]) {
    const classes = new Set();
    const status = {textContent: label};
    elements.set('#' + id, {disabled: false, status,
      querySelector: () => status,
      classList: {add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value)},
      addEventListener(name, listener) { this[name] = listener; },
    });
  }
  const window = new EventTarget();
  const location = {href: ''};
  const bindings = login.match(/^  window.addEventListener\('pageshow', resetOidcButtons\);[^]*?(?=^  \$\('#passkey'\))/m)?.[0];
  assert.ok(bindings, '后退返回时缺少按钮恢复');
  runInNewContext(fn('resetOidcButtons') + '\n' + fn('startOidc') + '\n' + bindings,
    {$: selector => elements.get(selector), window, location, CONSOLE: '/novabot/config'});
  elements.get('#' + clicked).click();
  const target = '/novabot/config/api/oidc/login' + (clicked === 'oidc-create' ? '?create=1' : '');
  eq(location.href, target, '保留登录或注册目标');
  for (const button of elements.values()) eq(button.disabled, true, '跳转中两个入口均不能重复点击');
  const button = elements.get('#' + clicked);
  eq(button.status.textContent, '正在前往账户中心…', '点击立即显示跳转提示');
  eq(button.classList.contains('nova-pending'), true, '跳转中显示动效');
  elements.get(clicked === 'oidc' ? '#oidc-create' : '#oidc').click();
  eq(location.href, target, '重复点击不能改变去向');
  window.dispatchEvent(new Event('pageshow'));
  for (const [id, label] of [['oidc', '使用统一登录'], ['oidc-create', '用统一身份注册']]) {
    const restored = elements.get('#' + id);
    eq(restored.disabled, false, '后退后恢复可点');
    eq(restored.status.textContent, label, '后退后恢复原文');
    eq(restored.classList.contains('nova-pending'), false, '后退后停止动效');
  }
}

// 首帧主题选择直接运行页面里的脚本；存储不可用时仍能跟随系统。
for (const [name, html] of [['login', login], ['console', read('index.html')], ['transition', read('oidc-transition.html')]]) {
  const source = html.match(/<script>([^]*?)<\/script>/)?.[1];
  assert.ok(source, name + ' 缺少主题脚本');
  for (const theme of [null, 'light', 'dark', 'invalid']) {
    for (const palette of [null, 'pink-blue', 'classic', 'warm', 'invalid']) {
      for (const blocked of [false, true]) {
        const attrs = {};
        runInNewContext(source, {
          localStorage: {getItem(key) { if (blocked) throw new Error('blocked'); return key === 'novabot-theme' ? theme : palette; }},
          document: {documentElement: {setAttribute(key, value) { attrs[key] = value; }}},
        });
        eq(attrs['data-theme'], !blocked && ['light', 'dark'].includes(theme) ? theme : undefined, name + ' 明暗');
        eq(attrs['data-palette'], !blocked && ['pink-blue', 'classic', 'warm'].includes(palette) ? palette : undefined, name + ' 配色');
      }
    }
  }
}

const css = read('loading-ui.css');
assert.match(css, /button\.nova-pending:disabled\s*\{[^}]*background:\s*var\(--line\);[^}]*color:\s*var\(--dim\)/);
checks++;
assert.match(css, /@media\s*\(prefers-reduced-motion:\s*reduce\)[^]*?animation:\s*none/);
checks++;
const index = read('index.html');
assert.match(index, /id="startup-message" role="status">正在加载…/);
assert.match(index, /id="startup-retry"[^>]*>重试/);
const timer = [...index.matchAll(/<script>([^]*?)<\/script>/g)].map(match => match[1]).find(source => source.includes('30000'));
assert.ok(timer);
let timeout;
const message = {textContent: '正在加载…'};
runInNewContext(timer, {window: {},
  document: {getElementById: () => message}, setTimeout(callback, ms) { timeout = {callback, ms}; }});
eq(timeout.ms, 30000, '保留脚本加载超时');
timeout.callback();
eq(message.textContent.includes('强制刷新'), true, '脚本加载超时提示仍可用');

// 身份请求挂起时，15 秒提示与重试仍能驱动真实的控制台启动函数。
const main = read('main.js');
const startSource = main.slice(main.indexOf('let startupAttempt;'),
  main.indexOf("$('#startup-retry').addEventListener"));
const retryBinding = main.match(/^\$\('#startup-retry'\)\.addEventListener\('click', startConsole\);/m)?.[0];
assert.ok(retryBinding);
const startupElements = new Map();
const $ = selector => {
  if (!startupElements.has(selector)) startupElements.set(selector, {style: {}, textContent: '',
    addEventListener(name, listener) { this[name] = listener; }});
  return startupElements.get(selector);
};
const requests = [];
const timers = [];
const noop = () => {};
const store = {};
const start = runInNewContext(startSource + '\n' + retryBinding + '\nstartConsole;', {
  $, store, AbortController, Promise,
  api(path, options) {
    if (path !== '/auth/state') return Promise.resolve({});
    return new Promise(resolve => requests.push({resolve, signal: options.signal}));
  },
  setTimeout(callback, ms) { const timer = {callback, ms}; timers.push(timer); return timer; },
  clearTimeout(timer) { if (timer) timer.cleared = true; },
  loadExecNotice: noop, applyRoleView: noop, setAuthState: noop, setMemberAuthState: noop,
  renderPersonalMenu: noop, botFormHtml: noop, bindBotForm: noop, loadBotConsole: noop,
  mountPages: async () => {}, applyRoute: noop, load: noop,
});
const first = start();
eq(timers[0].ms, 15000, '控制台身份请求超时仍为 15 秒');
eq($('#startup-retry').style.display, 'none', '开始加载时重试按钮收起');
timers[0].callback();
eq($('#startup-message').textContent.includes('刷新页面重试'), true, '保留身份请求超时提示');
eq($('#startup-retry').style.display, '', '超时后显示重试');
const second = $('#startup-retry').click();
eq(requests.length, 2, '点击重试重新查询身份');
eq(requests[0].signal.aborted, true, '重试取消上次请求');
eq($('#startup-message').textContent, '正在加载…', '重试恢复加载文字');
for (const request of requests) request.resolve({role: 'MEMBER'});
await Promise.all([first, second]);
eq($('#startup').style.display, 'none', '加载完成收起首屏');
eq(store.authReady, true, '加载完成进入控制台');
console.log('跑了 ' + checks + ' 格，红 0 格');
