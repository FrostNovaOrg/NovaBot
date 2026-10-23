import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const html = readFileSync(new URL('index.html', ui), 'utf8');
const main = readFileSync(new URL('main.js', ui), 'utf8');
const states = JSON.parse(process.argv[2]);
let checks = 0;

const markup = html.match(/<div\b[^>]*id="session-check-notice"[^>]*>[\s\S]*?<\/div>/)?.[0];
assert.ok(markup);
assert.match(markup, /class="notice"/);
assert.match(markup, /display:\s*none/);
assert.equal(markup.slice(markup.indexOf('>') + 1, markup.lastIndexOf('</div>')).trim(), process.argv[3]);
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
  return $('#session-check-notice').style.display !== 'none';
}

for (const [state, visible] of [[states[0], false], [states[1], true], [states[2], false], [states[3], false],
  [{role: 'MEMBER', sessionCheckSetupNeeded: true}, false],
  [{sessionCheckSetupNeeded: true}, false]]) {
  assert.equal(await render(state), visible);
  checks++;
}
console.log('跑了 ' + checks + ' 格，红 0 格');
