import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const ui = new URL('../core/nova-core/src/main/resources/config-ui/', import.meta.url);
const encodeModule = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
const source = readFileSync(process.argv[2], 'utf8');
const secret = 'fixture-browser-secret';
const markupName = '群"><img src=x onerror="bad()">';
const failure = '<b>请求失败</b>';
const groupNumber = '1123456789012345';
const warning = '每分钟 60 条只对做过个人认证或企业认证的机器人有效，没认证的超频会被拒';

globalThis.document = {querySelector: selector => selector === 'meta[name="nova-prefix"]' ? {content: '/test'} : null};
for (const key of ['localStorage', 'sessionStorage', 'indexedDB']) {
  Object.defineProperty(globalThis, key, {get() { throw new Error('页面不应访问浏览器存储：' + key); }});
}

const {store} = await import(new URL('store.js', ui));
store.tab = 'bot';
store.csrfToken = 'fixture-csrf';
let linkRefreshes = 0;
globalThis.noteLinkRefresh = () => { linkRefreshes++; };
const links = encodeModule('export function refreshLinks() { globalThis.noteLinkRefresh(); }');
const {render, refresh} = await import(encodeModule(source
  .replace("'./core.js'", JSON.stringify(new URL('core.js', ui).href))
  .replace("'./store.js'", JSON.stringify(new URL('store.js', ui).href))
  .replace("'./links.js'", JSON.stringify(links))));

const state = {success: true, configured: false, intervalMillis: 2000, groups: []};
const prefix = '/test/config/api/qqbot/';
let reached = '/';
const posted = [];
globalThis.fetch = async (path, options) => {
  assert.ok(path.startsWith(prefix), path);
  reached = path.slice(prefix.length);
  let response;
  if (reached === 'state') {
    assert.equal(options.cache, 'no-store');
    response = state;
  } else {
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['X-CSRF-Token'], 'fixture-csrf');
    const body = JSON.parse(options.body);
    posted.push([reached, body]);
    response = {success: true};
    if (reached === 'credentials') {
      state.configured = true;
      state.appId = body.appId;
      state.secretMasked = 'fi…et';
      state.groups = [{num: groupNumber, name: markupName, role: 'OWNER', memberCount: 42,
        proactive: 'DISABLED', lastFailure: failure}, {num: '2', name: '旧群', removed: true}];
    } else if (reached === 'interval') {
      state.intervalMillis = response.intervalMillis = body.millis;
      response.warning = warning;
    } else {
      assert.equal(reached, 'groups/' + groupNumber + '/name');
      state.groups[0].name = body.name;
    }
  }
  return {status: 200, json: async () => response};
};

class Node {
  constructor(inputs = []) {
    this.listeners = new Map();
    this.inputs = inputs;
    this.value = this.defaultValue = '';
  }
  addEventListener(event, handler) { this.listeners.set(event, handler); }
  querySelector(selector) { return selector === 'input' ? this.inputs[0] : undefined; }
  querySelectorAll(selector) { return selector === 'input' ? this.inputs : []; }
}

const appId = new Node();
const password = new Node();
const millis = new Node();
const credentials = new Node([appId, password]);
const interval = new Node([millis]);
const groups = new Node();
const reload = new Node();
const result = new Node();
const unconfigured = new Node();
const elements = {'app-id': appId, secret: password, millis, credentials, interval, groups,
  refresh: reload, result, unconfigured, load: new Node()};
const section = {querySelector: selector => elements[selector.slice('#qqbot-'.length)]};
render(section);
await refresh();
assert.match(section.innerHTML, /id="qqbot-secret" type="password" autocomplete="new-password">/);
assert.doesNotMatch(section.innerHTML, /fixture-browser-secret|value="fi…et"/);
assert.equal(unconfigured.hidden, false);
assert.match(section.innerHTML, /请填写 AppID 和 AppSecret/);
assert.match(groups.innerHTML, /尚未加入群/);

async function submit(host, target = host) {
  let prevented = false;
  const submitter = new Node();
  await host.listeners.get('submit')({target, submitter, preventDefault() { prevented = true; }});
  assert.ok(prevented);
  assert.equal(submitter.disabled, false);
}

appId.value = '12345678';
password.value = secret;
await submit(credentials);
assert.deepEqual(posted[0], ['credentials', {appId: '12345678', appSecret: secret}]);
assert.equal(reached, 'state');
assert.equal(password.value, '');
assert.equal(password.placeholder, '已保存：fi…et');
assert.ok(unconfigured.hidden);
assert.equal(linkRefreshes, 1);
assert.match(groups.innerHTML, /群&quot;&gt;&lt;img src=x onerror=&quot;bad\(\)&quot;&gt;/);
assert.match(groups.innerHTML, /&lt;b&gt;请求失败&lt;\/b&gt;/);
assert.doesNotMatch(groups.innerHTML, /<img|<b>请求失败/);
assert.match(groups.innerHTML, /<td>42<\/td><td>是<\/td>/);
assert.match(groups.innerHTML, /未开.*手机 QQ 进群.*机器人主动在群聊内发言/);
assert.ok(groups.innerHTML.indexOf('data-group-number="' + groupNumber + '"') < groups.innerHTML.indexOf('已移出'));

millis.valueAsNumber = 1000;
await submit(interval);
assert.deepEqual(posted[1], ['interval', {millis: 1000}]);
assert.equal(millis.value, '1000');
assert.equal(result.textContent, warning);

const groupInput = new Node();
groupInput.value = '主播一群';
const groupForm = new Node([groupInput]);
groupForm.dataset = {groupNumber};
await submit(groups, groupForm);
assert.deepEqual(posted[2], ['groups/' + groupNumber + '/name', {name: '主播一群'}]);
assert.match(groups.innerHTML, /value="主播一群"/);
await reload.listeners.get('click')();
assert.equal(linkRefreshes, 2);
assert.equal(password.value, '');
console.log('官方机器人卡：空态、密码遮罩与清空、群名转义、间隔提醒、改名和状态刷新通过');
