import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const html = readFileSync(new URL('index.html', ui), 'utf8');
const main = readFileSync(new URL('main.js', ui), 'utf8');
const states = JSON.parse(process.argv[2]);
let checks = 0;

const markup = html.match(/<div\b[^>]*id="oidc-callback-notice"[^>]*>[\s\S]*?<\/div>/)?.[0];
assert.ok(markup);
assert.match(markup, /class="notice"/);
assert.match(markup, /display:\s*none/);
for (const text of ['设置 → 高级选项 → 登录与安全 · 高级', '统一登录 · 回调地址', '完全一致', '重启']) {
  assert.ok(markup.includes(text));
  checks++;
}
assert.ok(html.indexOf(markup) < html.indexOf('id="page-home"'));
checks++;

const source = main.slice(main.indexOf('let startupAttempt;'),
  main.indexOf("$('#startup-retry').addEventListener"));
assert.ok(source.includes('function startConsole()'));

async function render(state) {
  const elements = new Map();
  const $ = selector => {
    if (!elements.has(selector)) elements.set(selector, {style: {display: 'none'}});
    return elements.get(selector);
  };
  const noop = () => {};
  const dependencies = {
    renderExecNotice: noop, loadExecNotice: noop,
    $, api: async path => path === '/auth/state' ? state : {}, store: {},
    applyRoleView: noop, setAuthState: noop, setMemberAuthState: noop,
    renderPersonalMenu: noop, bindBotForm: noop, botFormHtml: noop, loadBotConsole: noop,
    mountPages: async () => {}, applyRoute: noop, load: noop,
    setTimeout: noop, clearTimeout: noop,
  };
  const start = new Function(...Object.keys(dependencies),
    'let homeStatus, homeLogin;\n' + source + '\nreturn startConsole;')(...Object.values(dependencies));
  await start();
  return $('#oidc-callback-notice').style.display !== 'none';
}

for (const [state, visible] of [[states[0], true], [states[1], false], [states[2], false],
  [{role: 'MEMBER', oidcCallbackSetupNeeded: true}, false],
  [{role: 'ADMIN', oidcCallbackSetupNeeded: false}, false]]) {
  assert.equal(await render(state), visible);
  checks++;
}
console.log('跑了 ' + checks + ' 格，红 0 格');
