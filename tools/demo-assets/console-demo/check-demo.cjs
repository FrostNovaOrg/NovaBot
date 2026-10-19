// Exercise the demo boundary with disposable responses, independent of the recordings.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
let checks = 0;
function equal(actual, expected) { assert.deepEqual(actual, expected); checks++; }

async function main() {
  const files = new Map([
    ['auth.json', '{"authenticated":false,"totpRequired":true,"totpSetupNeeded":true,"csrfToken":"sample"}'],
    ['targets.json', '{"fetchedAt":"sample","stale":false,"senders":[]}'],
    ['error.json', '{"error":"null nickname"}'],
    ['sorted.json', '{"value":"sorted"}'],
    ['preview.png', new Uint8Array([137, 80, 78, 71])],
    ['hidden.png', new Uint8Array([137, 80, 78, 72])],
    ['lookup.json', '{"success":false,"message":"未查到主播"}'],
  ]);
  const rows = [
    ['GET', '/auth/state', '', 'auth.json', 200, 'application/json'],
    ['GET', '/bot/targets', 'type=group', 'targets.json', 200, 'application/json'],
    ['GET', '/streamer/name', 'uid=1&platform=sample', 'error.json', 500, 'application/json'],
    ['GET', '/sorted', 'a=two+words&b=%2F', 'sorted.json', 200, 'application/json'],
    ['POST', '/report/preview', '', 'preview.png', 200, 'image/png'],
  ].map(([method, path, query, file, status, contentType]) => ({method, path: '/config/api' + path,
    query, file: 'data/' + file, status, contentType}));
  rows.push({...rows.at(-1), body: {revenueVisible: false}, file: 'data/hidden.png'});
  const channel = {platform: 'qq-onebot', type: 1, num: 990000000301};
  for (const revenueVisible of [false, true]) {
    rows.push({...rows[4], body: {...channel, revenueVisible},
      file: revenueVisible ? 'data/preview.png' : 'data/hidden.png'});
  }
  rows.push({method: 'POST', path: '/config/api/streamer/lookup', query: '',
    body: {platform: 'bilibili', uid: '1'}, file: 'data/lookup.json', status: 200, contentType: 'application/json'});
  const timers = new Map();
  const events = [], nodes = new Map(), warnings = [], network = [], opened = [];
  const document = {
    readyState: 'complete',
    getElementById: id => nodes.get(id),
    createElement: () => ({style: {}, setAttribute() {}}),
    body: {appendChild(node) { nodes.set(node.id, node); }},
    addEventListener(type, handler) { events.push({type, handler}); },
  };
  const context = vm.createContext({document, location: {href: 'http://localhost/config/', origin: 'http://localhost'},
    setTimeout: (fn, delay) => { equal(delay, 4000); const id = timers.size + 1; timers.set(id, fn); return id; },
    clearTimeout: id => timers.delete(id), addEventListener: (type, handler) => events.push({type, handler}),
    Response, Request, URL, URLSearchParams, TextDecoder, console: {warn: line => warnings.push(line)},
    NOVA_CONSOLE_DEMO: {epochMs: Date.parse('2026-01-15T00:30:00+08:00'), responses: rows},
    fetch: async input => {
      const path = String(input);
      network.push(path);
      return new Response(files.get(path.split('/').at(-1)) || 'asset');
    }, open: (...args) => opened.push(args),
  });
  vm.runInContext('window = globalThis', context);
  vm.runInContext(fs.readFileSync(__dirname + '/demo.js', 'utf8'), context);
  const request = (path, options) => context.fetch('/config/api' + path, options);
  const state = await (await request('/auth/state')).json();
  equal(state.authenticated, true); equal(state.totpRequired, false); equal(state.totpSetupNeeded, false);
  equal(state.csrfToken, 'sample');
  equal(vm.runInContext('new Date().getDate()', context), 15);
  equal(vm.runInContext('new Date().getHours()', context), 0);
  equal(vm.runInContext('Date.now() === new Date().getTime()', context), true);
  equal(vm.runInContext('new Date(0).getTime()', context), 0);
  equal(vm.runInContext('Date.parse("1970-01-01T00:00:00Z")', context), 0);
  equal(await (await request('/sorted?b=%2f&a=two%20words')).json(), {value: 'sorted'});
  const error = await request('/streamer/name?platform=sample&uid=1');
  equal(error.status, 500); equal(await error.json(), {error: 'null nickname'});
  equal((await context.fetch(new Request('http://localhost/config/api/auth/state'))).status, 200);
  const lookup = await request('/streamer/lookup', {method: 'POST', body: '{"uid":"1","platform":"bilibili"}'});
  equal((await lookup.json()).message, '未查到主播');
  equal(nodes.get('console-demo-notice').hidden, true);
  const before = network.length;
  for (const method of ['POST', 'PUT', 'DELETE']) {
    const result = await request('/values', {method, body: '{"theme":"test"}'});
    equal(result.status, 200); equal((await result.json()).success, true);
  }
  equal(network.length, before);
  equal((await (await request('/auth/logout?all=true', {method: 'POST'})).json()).count, 0);
  equal((await (await request('/bot/targets/refresh', {method: 'POST'})).json()).stale, false);
  equal((await (await request('/state/commands', {method: 'POST', body: '{"commands":["a"]}'})).json()).changed, 1);
  for (const path of ['/auth/passkey/register/options', '/event-tokens', '/napcat/credential']) {
    const reply = await (await request(path, {method: 'POST'})).json();
    equal(reply.success, false); equal(reply.message.includes('演示'), true);
  }
  equal((await (await context.fetch('/nova/readonly-token', {method: 'POST'})).json()).message.includes('演示'), true);
  const preview = await request('/report/preview', {method: 'POST', body: '{}'});
  equal(preview.headers.get('Content-Type'), 'image/png');
  equal(Array.from(new Uint8Array(await preview.arrayBuffer())), [137, 80, 78, 71]);
  const hidden = await request('/report/preview', {method: 'POST', body: '{"revenueVisible":false}'});
  equal(Array.from(new Uint8Array(await hidden.arrayBuffer())), [137, 80, 78, 72]);
  for (const revenueVisible of [false, true]) {
    const edited = await request('/report/preview', {method: 'POST',
      body: JSON.stringify({params: {showRevenue: true}, ...channel, revenueVisible})});
    equal(edited.status, 200);
    equal(edited.headers.get('Content-Type'), 'image/png');
    equal(Array.from(new Uint8Array(await edited.arrayBuffer())),
      revenueVisible ? [137, 80, 78, 71] : [137, 80, 78, 72]);
    equal(nodes.get('console-demo-notice').textContent,
      '演示模式：预览显示录制的默认版式，改动不会重新绘制');
    equal(nodes.get('console-demo-notice').hidden, false);
    for (const fn of timers.values()) fn();
    equal(nodes.get('console-demo-notice').hidden, true);
  }
  await request('/report/preview', {method: 'POST',
    body: JSON.stringify({...channel, revenueVisible: false, params: {showRevenue: false}})});
  for (const {type, handler} of events) if (type === 'hashchange') handler();
  equal(nodes.get('console-demo-notice').hidden, true);
  for (const body of [
    {...channel, num: 990000000999, revenueVisible: false, params: {}},
    {...channel, revenueVisible: null, params: {}},
  ]) {
    const missingPreview = await request('/report/preview', {method: 'POST', body: JSON.stringify(body)});
    equal(missingPreview.ok, false);
  }
  const beforeMiss = network.length;
  for (const url of ['/config/api/missing?date=tomorrow', 'https://example.com/config/api/auth/state', '/unknown']) {
    const reply = await context.fetch(url);
    equal(reply.status, 404); equal((await reply.json()).success, false);
  }
  equal(network.length, beforeMiss); equal(warnings.length, 3);
  const linkEvent = {target: {closest: selector => selector === 'a'
    ? {href: 'http://localhost/config/api/sorted?a=two+words&b=%2F'} : null}, preventDefault() {}};
  for (const {handler} of events) handler(linkEvent);
  equal(opened[0][0], '/console-demo/data/sorted.json');
  let stopped = false;
  const deviceEvent = {target: {closest: selector => selector === 'button' ? {textContent: '登记通行密钥'} : null},
    preventDefault() {}, stopImmediatePropagation() { stopped = true; }};
  for (const {handler} of events) handler(deviceEvent);
  equal(stopped, true);
  equal(nodes.get('console-demo-notice').textContent.includes('真实设备'), true);
  for (const fn of timers.values()) fn();
  equal(nodes.get('console-demo-notice').hidden, true);
  for (const {type, handler} of events) if (type === 'click') handler(deviceEvent);
  equal(nodes.get('console-demo-notice').hidden, false);
  for (const {type, handler} of events) if (type === 'hashchange') handler();
  equal(nodes.get('console-demo-notice').hidden, true);
  equal(nodes.get('console-demo-banner')?.textContent, '演示模式：改动不会保存');
  console.log(`Console demo transport: PASS (${checks} assertions)`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
