import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const ui = new URL('../core/nova-core/src/main/resources/config-ui/', import.meta.url);
const encodeModule = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
const source = readFileSync(process.argv[2], 'utf8');
const secret = 'fixture-browser-secret';
const markupName = '群"><img src=x onerror="bad()">';
const failure = '<b>请求失败</b>';
const groupNumber = '1123456789012345';
const memberNumber = '2123456789012345';
const claimCode = '23456789';
const claimTime = '2026-11-24T10:00:00Z';
const expiry = '2026-11-24T10:10:00Z';
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
let welcomeEnabled = true;
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
  } else if (reached === 'welcome' && options.method !== 'POST') {
    assert.equal(options.cache, 'no-store');
    response = {success: true, enabled: welcomeEnabled};
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
    } else if (reached === 'welcome') {
      assert.equal(typeof body.enabled, 'boolean');
      welcomeEnabled = response.enabled = body.enabled;
    } else if (reached === 'admin-claims/code') {
      state.claimCodeActive = true;
      state.claimCodeExpiresAt = expiry;
      response.code = claimCode;
      response.expiresAt = expiry;
    } else if (reached === 'admin-claims/' + memberNumber + '/revoke') {
      state.claimedAdmins = [];
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
const welcomeToggle = new Node();
welcomeToggle.checked = welcomeToggle.defaultChecked = false;
welcomeToggle.disabled = true;
const welcome = new Node([welcomeToggle]);
const welcomeSave = new Node();
welcomeSave.disabled = true;
const welcomeResult = new Node();
const groups = new Node();
const reload = new Node();
const result = new Node();
const unconfigured = new Node();
const elements = {'app-id': appId, secret: password, millis, credentials, interval, groups,
  welcome, 'welcome-enabled': welcomeToggle, 'welcome-save': welcomeSave, 'welcome-result': welcomeResult,
  refresh: reload, result, unconfigured, load: new Node(),
  'claim-status': new Node(), issued: new Node(), 'claim-command': new Node(),
  'claimed-admins': new Node(), 'claim-result': new Node(), 'claim-generate': new Node()};
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

assert.match(section.innerHTML, /机器人进群时发欢迎语/);
assert.equal(welcomeToggle.checked, true);
assert.equal(welcomeSave.disabled, false);
welcomeToggle.checked = false;
await refresh();
assert.equal(welcomeToggle.checked, false);
assert.equal(welcomeEnabled, true);
await submit(welcome);
assert.deepEqual(posted.at(-1), ['welcome', {enabled: false}]);
assert.match(welcomeResult.textContent, /已保存/);
assert.equal(welcomeToggle.defaultChecked, false);
welcomeToggle.checked = true;
await submit(welcome);
assert.deepEqual(posted.at(-1), ['welcome', {enabled: true}]);
assert.equal(welcomeToggle.defaultChecked, true);
console.log('进群欢迎语：默认开启、刷新保留草稿、关闭和开启保存通过');

assert.match(section.innerHTML, /官方机器人超级管理员/);
assert.match(elements['claim-status'].textContent, /没有有效的认领码/);
assert.match(elements['claimed-admins'].innerHTML, /还没有人认领/);
await submit(elements['claim-generate']);
assert.deepEqual(posted.at(-1), ['admin-claims/code', {}]);
assert.equal(elements.issued.hidden, false);
assert.equal(elements['claim-command'].textContent, '认领 ' + claimCode);
assert.match(elements['claim-status'].textContent, /认领码有效，过期时间/);
await refresh();
assert.equal(elements.issued.hidden, true);
assert.equal(elements['claim-command'].textContent, '');
assert.match(elements['claim-status'].textContent, /认领码有效，过期时间/);

state.claimCodeActive = false;
state.claimedAdmins = [{memberNum: memberNumber, nickname: '<b>群昵称</b>', groupName: markupName, claimedAt: claimTime}];
await refresh();
const claimedAdmins = elements['claimed-admins'];
assert.match(claimedAdmins.innerHTML, /&lt;b&gt;群昵称&lt;\/b&gt;/);
assert.match(claimedAdmins.innerHTML, /群&quot;&gt;&lt;img/);
assert.match(claimedAdmins.innerHTML, /认领时间.*datetime="2026-11-24T10:00:00Z"/);
assert.doesNotMatch(claimedAdmins.innerHTML, /<img|<b>群昵称/);
const revoke = new Node();
revoke.dataset = {memberNum: memberNumber};
await submit(claimedAdmins, revoke);
assert.deepEqual(posted.at(-1), ['admin-claims/' + memberNumber + '/revoke', {}]);
assert.match(claimedAdmins.innerHTML, /还没有人认领/);
console.log('超管认领：生成时显示认领码，刷新后只显示状态，名单转义与撤销通过');

store.tab = 'setup';
const setupAppId = new Node();
const setupSecret = new Node();
const setupCredentials = new Node([setupAppId, setupSecret]);
const setupElements = {'app-id': setupAppId, secret: setupSecret, credentials: setupCredentials,
  result: new Node(), unconfigured: new Node(), load: new Node()};
const setupSection = {querySelector: selector => setupElements[selector.slice('#setup-qqbot-'.length)]};
const connectionChanges = [];
render(setupSection, {onConnectionChange: saving => connectionChanges.push(saving)});
assert.match(setupSection.innerHTML, /IP 白名单留空.*群主或管理员/);
assert.doesNotMatch(setupSection.innerHTML, /发送间隔|进群时发欢迎语|已加入的群|超级管理员|认领码/);
setupAppId.value = '87654321';
setupSecret.value = secret;
await submit(setupCredentials);
assert.deepEqual(posted.at(-1), ['credentials', {appId: '87654321', appSecret: secret}]);
assert.equal(setupSecret.value, '');
assert.deepEqual(connectionChanges, [true, false]);
assert.equal(linkRefreshes, 2);

store.tab = 'bot';
await refresh();
assert.equal(appId.value, '87654321');
assert.equal(setupAppId.value, '87654321');
console.log('引导官方机器人表单：复用凭据保存、通报连接状态变化，与连接页独立刷新');
