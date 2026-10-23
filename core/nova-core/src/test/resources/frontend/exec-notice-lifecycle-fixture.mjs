import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const main = readFileSync(new URL('main.js', ui), 'utf8');
const source = main.slice(main.indexOf('// 根据当前身份'), main.indexOf('// 页面在身份和插件清单到达后'));
class Element {
  writes = 0;
  children = [];
  value = '';
  style = new Proxy({display: 'none'}, {set: (target, key, value) => {
    this.writes++;
    target[key] = value;
    return true;
  }});
  get textContent() { return this.value + this.children.map(child => child.textContent ?? child).join(''); }
  set textContent(value) { this.writes++; this.value = value; this.children = []; }
  set innerHTML(value) { this.textContent = value; }
  append(...children) { this.writes++; this.children.push(...children); }
  addEventListener() {}
}
function events() {
  const listeners = new Map();
  return {
    addEventListener(name, listener) {
      if (!listeners.has(name)) listeners.set(name, []);
      listeners.get(name).push(listener);
    },
    async dispatch(name) {
      for (const listener of listeners.get(name) || []) listener({type: name});
      await settle();
    },
  };
}
const window = events();
const document = Object.assign(events(), {
  visibilityState: 'visible',
  createElement: () => new Element(),
  querySelector: () => ({content: ''}),
});
globalThis.document = document;
const {renderExecNotice} = await import(new URL('exec-notice.js', ui));
const elements = new Map();
const $ = key => {
  if (!elements.has(key)) elements.set(key, new Element());
  return elements.get(key);
};
let now = 0;
const timers = new Map();
let nextTimer = 0;
const schedule = (callback, delay, repeat) => {
  const id = ++nextTimer;
  timers.set(id, {callback, delay, repeat, due: now + delay});
  return id;
};
async function settle() { await new Promise(resolve => setImmediate(resolve)); }
async function advance(end) {
  while (true) {
    const entry = [...timers.entries()].filter(([, timer]) => timer.due <= end)
      .sort((a, b) => a[1].due - b[1].due)[0];
    if (!entry) break;
    const [id, timer] = entry;
    now = timer.due;
    if (timer.repeat) timer.due += timer.delay;
    else timers.delete(id);
    timer.callback();
    await settle();
  }
  now = end;
}
let role = 'ADMIN';
let rules = [{key: 'novabot.core.exec.rules[0]', event: 'LiveOnEvent'}];
let pending = null;
let unavailable = false;
const requests = [];
const noop = () => {};
const dependencies = {
  $, window, document, renderExecNotice, store: {},
  api: async path => {
    requests.push({path, at: now});
    if (unavailable) throw new Error('unavailable');
    if (path === '/auth/state') return {role};
    if (path === '/exec/notice') return pending ? await pending : {success: true, rules};
    return {};
  },
  applyRoleView: noop, setAuthState: noop, setMemberAuthState: noop,
  renderPersonalMenu: noop, bindBotForm: noop, botFormHtml: noop, loadBotConsole: noop,
  mountPages: async () => {}, applyRoute: noop, load: noop,
  setTimeout: (callback, delay) => schedule(callback, delay, false),
  clearTimeout: id => timers.delete(id),
  setInterval: (callback, delay) => schedule(callback, delay, true),
};
const lifecycle = new Function(...Object.keys(dependencies),
  'let homeStatus, homeLogin;\n' + source + '\nreturn {start: startConsole, refresh: loadExecNotice};')
  (...Object.values(dependencies));
await settle();
const box = $('#exec-notice');
const shown = () => box.style.display !== 'none' && !!box.textContent;
let checks = 0;
function check(value) { checks++; assert.ok(value); }

if (process.argv[2] === 'idle') {
  requests.length = 0;
  await advance(13 * 3600 * 1000);
  for (const request of requests) console.log('request ' + request.at);
  console.log('elapsed ' + now);
} else if (process.argv[2] === 'activity') {
  check(shown());
  for (const [target, event] of [[document, 'click'], [document, 'keydown'],
    [window, 'focus'], [document, 'visibilitychange']]) {
    for (const nextRole of ['MEMBER', undefined, 'MEMBER', 'ADMIN']) {
      role = nextRole;
      requests.length = 0;
      await target.dispatch(event);
      check(shown() === (role === 'ADMIN'));
      check(role === 'ADMIN' || !requests.some(request => request.path === '/exec/notice'));
      check(role === 'ADMIN' || box.textContent === '');
    }
  }
  document.visibilityState = 'hidden';
  requests.length = 0;
  await document.dispatch('visibilitychange');
  check(requests.length === 0);
  document.visibilityState = 'visible';
  let release;
  pending = new Promise(resolve => { release = resolve; });
  await document.dispatch('click');
  role = 'MEMBER';
  await window.dispatch('focus');
  release({success: true, rules});
  await settle();
  check(!shown() && box.textContent === '');
} else if (process.argv[2] === 'stable') {
  const original = [...box.children];
  box.writes = 0;
  let release;
  pending = new Promise(resolve => { release = resolve; });
  const refresh = lifecycle.refresh();
  await settle();
  check(shown() && box.writes === 0);
  release({success: true, rules: rules.map(rule => ({...rule}))});
  await refresh;
  check(box.writes === 0 && box.children.every((child, i) => child === original[i]));
  pending = null;
  box.writes = 0;
  await lifecycle.start();
  await settle();
  check(shown() && box.writes === 0);
  rules = [{key: 'novabot.core.exec.rules[1]', event: 'LiveOffEvent'}];
  await lifecycle.refresh();
  check(box.textContent.includes(rules[0].key) && !box.textContent.includes('rules[0]'));
  rules = [];
  await lifecycle.refresh();
  check(!shown() && box.textContent === '');
  box.writes = 0;
  await lifecycle.refresh();
  check(box.writes === 0);
  rules = [{key: 'novabot.core.exec.rules[0]'}];
  await lifecycle.refresh();
  unavailable = true;
  await lifecycle.refresh();
  check(!shown() && box.textContent === '');
  unavailable = false;
  await lifecycle.refresh();
  unavailable = true;
  await lifecycle.start();
  check(!shown() && box.textContent === '');
}
if (process.argv[2] !== 'idle') console.log('跑了 ' + checks + ' 格，红 0 格');
