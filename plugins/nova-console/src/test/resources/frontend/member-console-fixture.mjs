import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import {resolve, dirname, basename} from 'node:path';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../../..');
const core = resolve(root, 'core/nova-core/src/main/resources/config-ui');
const plugin = resolve(root, 'plugins/nova-console/src/main/resources/config-ui-pages');
const mode = process.argv[2] || 'requests';
const recovery = ['slow-plugins', 'retry-plugins', 'retry-member-plugins', 'plugin-version', 'plugin-version-next', 'missing-main', 'fallback-started'].includes(mode);
const startup = mode.startsWith('loading-') || mode.startsWith('sidebar-');
const role = mode === 'retry-member-plugins' ? 'MEMBER' : (recovery || mode === 'sidebar-admin' || (mode.startsWith('loading-') && !mode.startsWith('loading-member-')) || mode === 'admin') ? 'ADMIN' : mode === 'legacy' ? undefined : mode === 'unknown' ? 'OTHER' : 'MEMBER';
class Element {
  constructor(tag = 'div') {
    this.tagName = tag; this.children = []; this.attrs = {}; this.dataset = {}; this.style = {};
    this.listeners = {}; this.className = ''; this.hidden = false; this.ownText = '';
    this.classList = {toggle: (name, force) => {
      const names = new Set(this.className.split(/\s+/).filter(Boolean));
      const on = force === undefined ? !names.has(name) : force;
      if (on) names.add(name); else names.delete(name);
      this.className = [...names].join(' '); return on;
    }, add: name => this.classList.toggle(name, true), remove: name => this.classList.toggle(name, false),
    contains: name => this.className.split(/\s+/).includes(name)};
  }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  append(...children) { children.forEach(child => this.appendChild(typeof child === 'string' ? Object.assign(new Element('text'), {textContent: child}) : child)); }
  insertBefore(child, before) { child.parentNode = this; const at = this.children.indexOf(before); this.children.splice(at < 0 ? this.children.length : at, 0, child); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); }
  replaceChildren(...children) { this.children = []; this.ownText = ''; this.append(...children); }
  setAttribute(key, value) {
    this.attrs[key] = String(value);
    if (key === 'id') this.id = value;
    if (key === 'class') this.className = value;
    if (key === 'hidden') this.hidden = true;
    if (key === 'href') this.href = value;
    if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    if (key === 'style') for (const pair of value.split(';')) { const [k,v] = pair.split(':'); if (k && v) this.style[k.trim()] = v.trim(); }
  }
  getAttribute(key) { return key === 'href' ? this.href : this.attrs[key]; }
  removeAttribute(key) { delete this.attrs[key]; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  scrollIntoView() {} focus() {} contains(node) { return walk(this).includes(node); }
  get firstChild() { return this.children[0]; }
  get lastChild() { return this.children.at(-1); }
  get textContent() { return this.ownText + this.children.map(node => node.textContent).join(''); }
  set textContent(value) { this.ownText = String(value ?? ''); this.children = []; }
  set innerHTML(html) { this.raw = html; this.children = []; this.ownText = ''; parse(html, this); }
  get innerHTML() { return this.raw || this.textContent; }
  querySelectorAll(selector) {
    const parts = selector.split(/\s+/);
    let found = [this];
    for (const part of parts) found = found.flatMap(node => walk(node).slice(1).filter(item => matches(item, part)));
    return [...new Set(found)];
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}
const walk = node => [node, ...node.children.flatMap(walk)];
function matches(node, selector) {
  if (selector.startsWith('#')) return node.id === selector.slice(1);
  if (selector.startsWith('.')) return node.classList.contains(selector.slice(1));
  const attr = /^(.*?)\[([^=]+)(?:="([^"]*)")?\]$/.exec(selector);
  if (attr) return (!attr[1] || node.tagName === attr[1]) && (attr[3] === undefined ? node.attrs[attr[2]] !== undefined : node.getAttribute(attr[2]) === attr[3]);
  return node.tagName === selector;
}
function parse(html, host) {
  const stack = [host];
  for (const token of html.replace(/<!--[\s\S]*?-->/g, '').match(/<[^>]+>|[^<]+/g) || []) {
    if (/^<\//.test(token)) { if (stack.length > 1) stack.pop(); continue; }
    if (token.startsWith('<!')) continue;
    if (token.startsWith('<')) {
      const tag = /^<([\w-]+)/.exec(token)?.[1]; if (!tag) continue;
      const node = new Element(tag);
      for (const attr of token.slice(tag.length + 1).matchAll(/([\w:-]+)(?:="([^"]*)")?/g)) node.setAttribute(attr[1], attr[2] ?? '');
      stack.at(-1).appendChild(node);
      if (!/\/>$/.test(token) && !['input','meta','link','img','br','hr'].includes(tag)) stack.push(node);
    } else stack.at(-1).appendChild(Object.assign(new Element('text'), {textContent: token}));
  }
}
const document = new Element('document');
parse(readFileSync(resolve(core, 'index.html'), 'utf8'), document);
document.getElementById = id => document.querySelector('#' + id);
document.createElement = tag => new Element(tag);
document.createElementNS = (_, tag) => new Element(tag);
document.documentElement = document.querySelector('html');
document.head = document.querySelector('head'); document.body = document.querySelector('body');
const $ = selector => document.querySelector(selector);
const visible = node => !!node && !node.hidden && node.style.display !== 'none' && (!node.classList.contains('page') || node.classList.contains('on')) && (!node.parentNode || visible(node.parentNode));
const events = {};
const heldPath = mode.startsWith('loading-') ? '/' + mode.replace(/^loading-(member-)?/, '') : '/auth/state';
let hold = startup, releaseHeld;
let startupTimer;
let now = 0, nextTimer = 0;
const timers = new Map();
const advance = ms => {
  const end = now + ms;
  while (true) {
    const next = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
    if (!next) break;
    now = next[1].at; timers.delete(next[0]); next[1].fn();
  }
  now = end;
};
const startupTimeout = (fn, ms, ...args) => {
  if (recovery) { const id = ++nextTimer; timers.set(id, {at: now + ms, fn: () => fn(...args)}); return id; }
  if (ms === 15000) { startupTimer = fn; return 'startup'; }
  return setTimeout(fn, ms, ...args);
};
const location = {_hash: mode === 'nav' ? '#/settings' : '#/home', reload() {},
  get hash() { return this._hash; },
  set hash(value) { if (this._hash === value) return; this._hash = value;
    Promise.resolve().then(() => { for (const fn of events.hashchange || []) fn(); });
  },
};
const window = {location, addEventListener: (name, fn) => { (events[name] ||= []).push(fn); }, scrollTo() {}};
const own = {uid: 71001, platform: 'bilibili', uname: 'Owner A', enabled: true, targets: 3};
const memberStatus = {success: true, version: '1.2.3', users: [own], live: [{...own, roomId: 81001}], today: {sent: 2, failed: 1}, locked: false, pushEnabled: false, totalDataAvailable: false, quiet: {}};
const adminStatus = {...memberStatus, version: '1.2.3', runtime: {heapUsedMb: 12, heapMaxMb: 64, threads: 8}, health: [], senders: [], alerts: {}};
const requests = [], errors = [];
const auth = {authenticated: true, enabled: true, accountId: 'member-a', role, email: 'member@example.com'};
const pages = [{id: 'push', displayName: '推送', script: 'push.js', slot: 'top'}, {id: 'streamers', displayName: '主播', script: 'streamers.js', slot: 'top'}, {id: 'today', displayName: '今日', script: 'today.js', slot: 'home_card'}, {id: 'advanced', displayName: '高级插件', script: 'advanced.js', slot: 'settings'}];
let pending = 0;
const fetch = async (url, options = {}) => {
  pending++; requests.push([options.method || 'GET', String(url)]);
  const path = String(url).split('/api')[1] || '';
  if (hold && path === heldPath) { pending--; await new Promise(done => { releaseHeld = done; }); pending++; }
  const body = path === '/auth/state' ? auth : path === '/status' ? (role === 'MEMBER' ? memberStatus : adminStatus)
    : path === '/pages' ? {pages, accessiblePages: ['home', 'push', 'streamers']}
    : path.startsWith('/timeline') ? {events: [{at: Date.now(), type: 'PUSH_BLOCKED_WORD', level: 'WARN', streamer: 'Owner A', text: '自家屏蔽词事件'}]}
    : path === '/login' ? {success: false, message: '成员不能使用这一项'}
    : path === '/at-all/quota' ? {success: true, sessions: [{platform: 'qq', num: 81001, used: 3, remaining: 7}], bots: []}
    : path === '/streamers' ? {success: true, streamers: [], days: 7}
    : path.startsWith('/streamers/') ? {success: false, message: '暂无归档'}
    : {success: true};
  if (path === '/status' && location.hash.includes('/streamers/')) await new Promise(done => setImmediate(done));
  pending--; return {status: path === '/login' ? 403 : 200, json: async () => body};
};
const context = vm.createContext({document, location, window, fetch, console, URL, URLSearchParams, Date, Set, Map, Promise,
  setTimeout: startupTimeout, clearTimeout: id => { if (recovery) timers.delete(id); else if (id === 'startup') startupTimer = null; else clearTimeout(id); }, setInterval, clearInterval, AbortController});
const stubs = {
  'bot.js': ['bindBotForm','botFormHtml','fillBotForms'],
  'links.js': ['focusStation','loadTargets','mountLinkCard','refreshLinks','sendTestMessage'],
  'log.js': ['loadLog','stopFollow','syncLogView'],
  'settings-auth.js': ['setAuthState'],
  'settings.js': ['copyConfigPath','discard','filterSettings','focusGroup','renderConfigPath','renderGeneral','save','stopWatchingGroups','toggleKeyNames'],
  'setup.js': ['notePlatformsArrived','openSetup','stopSetupPolling'],
  'tokens.js': ['clearIssuedToken','loadTokens'],
  'push.js': ['render','refresh'], 'advanced.js': ['render','refresh'],
};
const modules = new Map();
const imports = [];
let pluginHeld = false;
const delayedPlugins = mode === 'slow-plugins' || mode === 'retry-plugins' || mode === 'retry-member-plugins' || mode === 'fallback-started';
const assetBase = 'https://console.example/novabot/config/assets/'
  + (mode === 'plugin-version-next' ? '1792584000000' : '1792497600000') + '/';
if (recovery) {
  for (const script of readFileSync(resolve(core, 'index.html'), 'utf8').replace(/<!--[\s\S]*?-->/g, '').matchAll(/<script>([\s\S]*?)<\/script>/g)) {
    vm.runInContext(script[1], context);
  }
}
function getModule(path) {
  if (modules.has(path)) return modules.get(path);
  const names = stubs[basename(path)];
  const module = names ? new vm.SyntheticModule(names, function() { for (const name of names) this.setExport(name, (...args) => {
    if (name === 'renderConfigPath') { $('#cfg-path').textContent = args[0]; }
    return name === 'botFormHtml' ? '' : undefined;
  }); }, {context, identifier: path}) : new vm.SourceTextModule(readFileSync(path, 'utf8'), {
    context, identifier: path,
    initializeImportMeta: meta => { meta.url = assetBase + basename(path); },
    importModuleDynamically: async specifier => {
      imports.push(specifier);
      if (delayedPlugins && !pluginHeld && basename(specifier) === 'streamers.js') {
        pluginHeld = true;
        await new Promise(done => startupTimeout(done, 20000));
      }
      const loaded = getModule(find(specifier)); if (loaded.status === 'unlinked') await loaded.link(link); if (loaded.status === 'linked') await loaded.evaluate(); return loaded; },
  });
  modules.set(path, module); return module;
}
function find(specifier) {
  const name = basename(specifier);
  return resolve(existsSync(resolve(core, name)) ? core : plugin, name);
}
const link = specifier => getModule(find(specifier));
async function settle() {
  const deadline = Date.now() + 10000;
  while (true) {
    const before = requests.length;
    await new Promise(done => setImmediate(done));
    if (pending === 0 && requests.length === before) return;
    assert.ok(Date.now() < deadline, '请求应在限时内完成');
  }
}
const main = getModule(resolve(core, 'main.js'));
let initialAdminLinks;
// 身份未知时出现管理入口，会让成员误以为自己能打开日志、连接和设置。
if (mode.startsWith('sidebar-')) initialAdminLinks = ['log','links','settings'].filter(id => visible($('#nav').querySelector('[data-page="' + id + '"]')));
await main.link(link); if (mode !== 'missing-main') await main.evaluate(); await settle();
const store = mode === 'missing-main' ? null : getModule(resolve(core, 'store.js')).namespace.store;
const overview = mode === 'missing-main' ? null : getModule(resolve(core, 'overview.js')).namespace;
const go = async hash => { location.hash = hash; await settle(); };
let checks = 0, failed = 0;
async function check(name, fn) { checks++; try { await fn(); console.log('绿 ' + name); } catch (e) { failed++; console.log('红 ' + name + '：' + e.message); } }
// 网慢时提示不能丢掉仍在下载的脚本，否则等齐后设置仍点不开。
if (mode === 'slow-plugins') await check('插件加载 20 秒后首屏恢复', async () => {
  assert.ok(pluginHeld, '应等在插件脚本');
  advance(15000); await settle();
  assert.match($('#startup-message').textContent, /控制台没加载出来/);
  assert.ok(visible($('#startup-retry')));
  advance(5000); await settle();
  assert.ok(!visible($('#startup')), '20 秒载完应收起卡片');
  assert.equal(store.authReady, true);
  assert.ok(visible($('#nav').querySelector('[data-page="settings"]')));
  await go('#/settings');
  assert.ok(visible($('#page-settings')), '设置应能打开');
});
// 等插件脚本时重试，已建出的推送、主播入口不能各挂两遍，旧加载也不能覆盖新结果。
if (mode === 'retry-plugins' || mode === 'retry-member-plugins') await check('重试后插件页与导航各一份', async () => {
  assert.ok(pluginHeld);
  advance(15000); await settle();
  assert.ok(visible($('#startup-retry')));
  for (const fn of $('#startup-retry').listeners.click || []) fn();
  await settle();
  assert.ok(!visible($('#startup')));
  advance(5000); await settle();
  for (const id of ['push', 'streamers']) {
    assert.equal($('#nav').querySelectorAll('a').filter(a => a.dataset.page === id).length, 1, id + ' 导航');
    assert.equal(document.querySelectorAll('#page-' + id).length, 1, id + ' 容器');
  }
  assert.equal(document.querySelectorAll('#today').length, 1, '首页插件卡');
  assert.equal(document.querySelectorAll('#advanced').length, role === 'ADMIN' ? 1 : 0, '设置插件页');
  assert.equal($('#page-tabs').children.length, role === 'ADMIN' ? 1 : 0, '设置插件入口');
  assert.equal(store.authReady, true);
});
// 插件若回到无版本地址，会继续用旧模块拼新版首屏；相对导入也必须跟随主入口的版本。
if (mode.startsWith('plugin-version')) await check('插件与主入口使用同一个版本目录', async () => {
  assert.equal(imports.length, pages.length);
  for (const [i, url] of imports.entries()) {
    assert.equal(url, assetBase + pages[i].script);
    assert.equal(new URL('./core.js', url).href, assetBase + 'core.js');
  }
});
// 首页与向导也会另取插件脚本，任一处漏掉版本都会装出另一份共享状态。
if (mode.startsWith('plugin-version')) for (const name of ['overview.js', 'setup.js']) {
  await check('插件脚本版本 ' + name, async () => {
    const source = readFileSync(resolve(core, name), 'utf8');
    const expression = source.match(/await import\(([^\n]+)\);/)[1];
    let requested;
    const probe = new vm.SourceTextModule(
      'const meta = {script: "plugin-step.js"}; const CONSOLE = "/novabot/config"; await import(' + expression + ');', {
        context, initializeImportMeta: meta => { meta.url = assetBase + name; },
        importModuleDynamically: async url => {
          requested = url;
          const module = new vm.SyntheticModule([], function () {}, {context});
          await module.link(() => {}); await module.evaluate(); return module;
        },
      });
    await probe.link(() => {}); await probe.evaluate();
    assert.equal(requested, assetBase + 'plugin-step.js', name + ' 插件地址');
  });
}
// 主入口取不到或旧脚本不认当前页面时，用户不能永远面对加载卡。
if (mode === 'missing-main') await check('主脚本未执行时提示强制刷新', () => {
  advance(29999);
  assert.match($('#startup-message').textContent, /正在加载/);
  advance(1);
  assert.match($('#startup-message').textContent, /控制台没加载出来，请强制刷新页面/);
  assert.match($('#startup-message').textContent, /Cmd\+Shift\+R/);
  assert.match($('#startup-message').textContent, /Ctrl\+F5/);
});
// 正常入口一开始启动就要撤掉后备提示，后续接口等待不该被说成脚本未运行。
if (mode === 'fallback-started') await check('正常启动取消页面后备提示', async () => {
  advance(30000); await settle();
  assert.doesNotMatch($('#startup-message').textContent, /强制刷新/);
  assert.ok(!visible($('#startup')));
});
// 某个首屏接口不回时，主区应给出等待、超时与可恢复的重试入口。
if (mode.startsWith('loading-')) await check('首屏等待、15 秒超时与重试 ' + heldPath, async () => {
  assert.ok(releaseHeld, '应已等在指定接口');
  assert.match($('#main').textContent, /正在加载…/);
  assert.ok(!visible($('#page-home')), '身份与清单齐备前不展开首页');
  assert.equal(typeof startupTimer, 'function', '应设置 15 秒期限');
  startupTimer();
  const retry = walk($('#main')).find(node => node.tagName === 'button' && visible(node) && node.textContent === '重试');
  assert.match($('#main').textContent, /控制台没加载出来，请刷新页面重试。/);
  assert.ok(retry, '应显示重试按钮');
  const before = requests.filter(([,path]) => path.endsWith('/auth/state')).length;
  hold = false;
  for (const fn of retry.listeners.click || []) fn();
  await settle();
  assert.equal(requests.filter(([,path]) => path.endsWith('/auth/state')).length, before + 1);
  assert.ok(visible($('#page-home')), '重试成功后展开首页');
  if (role === 'ADMIN') assert.equal($('#run-mem').textContent, '12 / 64 MB');
  else assert.ok(!visible($('#home-events').querySelector('a')));
  releaseHeld(); await settle();
  assert.equal($('#nav').querySelectorAll('a').filter(link => link.dataset.page === 'push').length, 1, '旧请求返回不能重复挂页');
});
// 成员一进来不应闪过管理入口；只有确认管理员身份才显示这三项。
if (mode.startsWith('sidebar-')) await check('身份前隐藏管理导航 ' + role, async () => {
  assert.deepEqual(initialAdminLinks, []);
  hold = false; releaseHeld(); await settle();
  const shown = ['log','links','settings'].filter(id => visible($('#nav').querySelector('[data-page="' + id + '"]')));
  assert.deepEqual(shown, role === 'ADMIN' ? ['log','links','settings'] : []);
});
if (mode === 'source') {
  const sourceUrl = /const SOURCE_URL = '([^']+)'/.exec(readFileSync(resolve(core, 'overview.js'), 'utf8'))[1];
  await check('成员侧栏保留源码入口', () => {
    assert.ok(visible($('.verline')));
    assert.equal($('#side-source').href, sourceUrl);
    assert.equal($('#side-version').textContent, 'NovaBot 1.2.3');
    assert.equal($('#personal-menu').querySelector('#personal-about a').href, sourceUrl);
    assert.ok(!visible($('.runbox')));
  });
  for (const version of ['1.2.3', undefined]) await check('成员源码说明与版本 ' + version, () => {
    overview.renderStatus({...memberStatus, version, update: {latestVersion: '9.9.9'}});
    assert.equal($('#side-version').textContent, version ? 'NovaBot ' + version : 'NovaBot');
    const about = $('#personal-menu').querySelector('#personal-about');
    assert.ok(about);
    assert.match(about.textContent, /AGPL-3.0.*源码/);
    assert.ok(about.textContent.startsWith(version ? 'NovaBot v' + version : 'NovaBot ·'));
    const link = about.querySelector('a');
    assert.equal(link.href, sourceUrl);
    assert.equal(link.getAttribute('target'), '_blank');
    assert.equal(link.getAttribute('rel'), 'noopener noreferrer');
    assert.equal(link.getAttribute('referrerpolicy'), 'no-referrer');
  });
  await check('成员不显示升级提示', () => {
    $('#side-update').style.display = '';
    $('#update-pop').style.display = '';
    overview.renderStatus({...memberStatus, update: {latestVersion: '9.9.9'}});
    assert.equal($('#side-update').style.display, 'none');
    assert.equal($('#update-pop').style.display, 'none');
  });
}
// 成员点击全部事件会被弹回首页，因此首页不能提供这个死链；管理员仍需保留。
if (mode === 'home' || mode === 'admin') await check('全部事件入口 ' + role, () => {
  assert.equal(visible($('#home-events').querySelector('a')), role === 'ADMIN');
});
// 成员点日志、设置会看到空页；导航与直接地址都应只开放名下可用页面。
if (mode === 'nav') await check('成员导航与直接地址', async () => {
  assert.deepEqual(Array.from($('#nav').querySelectorAll('a')).filter(visible).map(a => a.dataset.page), ['home','push','streamers']);
  assert.ok(visible($('#side-manual'))); assert.ok(visible($('#personal-menu')));
  assert.equal($('#page-tabs').children.length, 0);
  for (const hash of ['#/settings','#/settings/advanced','#/log','#/links','#/setup']) { await go(hash); assert.equal(location.hash, '#/home', hash); }
});
// 成员状态缺少实例字段，首页不能显示 undefined、空自检成功或实例信息。
if (mode === 'home') await check('成员首页只画名下直播与事件', async () => {
  await overview.refreshHome();
  const text = walk(document).filter(visible).map(node => node.ownText).join('');
  assert.doesNotMatch(text, /undefined|0 项全部正常|堆内存|线程|磁盘剩余|健康自检|连接状态/);
  assert.match($('#now-box').textContent, /Owner A/); assert.doesNotMatch($('#now-box').textContent, /Other B|推送已暂停/);
  assert.match($('#home-timeline').textContent, /自家屏蔽词事件/);
  assert.ok(!visible($('#cfg-path')), '成员不显示配置文件路径');
  const before = requests.length; await overview.runSelfTest(); assert.equal(requests.length, before);
});
// 登录失败对象不能让成员被当成尚未配置实例的管理员。
if (mode === 'setup') await check('成员不会进入初始设置', () => {
  location.hash = '#/home';
  assert.equal(main.namespace.considerSetupRedirect({...memberStatus, users: [], live: []}, {success: false, message: '成员不能使用这一项'}), false);
  assert.equal(location.hash, '#/home');
  assert.ok(!requests.some(([,path]) => path.endsWith('/login')), '成员不请求登录状态');
});
// 成员需要今天的推送数字，但不能看到或触发实例推送总开关。
if (mode === 'today') await check('成员今日数字与开关', async () => {
  assert.ok($('#today'));
  const today = getModule(resolve(plugin, 'today.js')).namespace;
  today.status(memberStatus); await today.refresh();
  assert.match($('#today-stats').textContent, /2推送（条）1失败（条）/);
  assert.match($('#today-stats').textContent, /3@全体成员 已用/);
  assert.ok($('#today').querySelector('#toggle-push') === null, '成员不显示推送总开关');
  assert.doesNotMatch($('#today').textContent, /暂停全部推送|恢复推送|推送正常/);
  const before = requests.length; await today.togglePush(); assert.equal(requests.length, before);
});
// 账户中心不提供显示名时仍需能认出当前账号。
if (mode === 'personal') await check('个人名称依次退到邮箱和编号', () => {
  const personal = getModule(resolve(core, 'personal-menu.js')).namespace;
  personal.renderPersonalMenu($('#personal-menu'), auth); assert.equal($('#personal-name').textContent, auth.email);
  personal.renderPersonalMenu($('#personal-menu'), {...auth, email: ''}); assert.equal($('#personal-name').textContent, auth.accountId);
  personal.renderPersonalMenu($('#personal-menu'), {...auth, accountId: 'local-admin'}); assert.equal($('#personal-name').textContent, '本地管理员');
});
// 共享身份不能被设置页保留的旧副本覆盖。
if (mode === 'identity') await check('设置页读取共享身份', () => {
  assert.equal(store.role, 'MEMBER');
  store.role = 'MEMBER';
  const members = getModule(resolve(core, 'settings-members.js')).namespace;
  members.setMemberAuthState({...auth, role: 'ADMIN'});
  assert.equal(members.memberGroup(), null);
  store.role = undefined; assert.ok(members.memberGroup());
  store.role = 'OTHER'; assert.ok(members.memberGroup());
});
// 主播详情不应向成员提示修改全实例的累计数据配置。
if (mode === 'streamers' || mode === 'requests') {
  await go('#/home'); await overview.refreshHome();
  await go('#/streamers'); await go('#/streamers/bilibili/71001');
  if (mode === 'streamers') await check('成员主播横幅无实例设置', () => {
    assert.ok(requests.some(([,path]) => path.includes('/streamers/bilibili/71001')), '主播详情必须已请求');
    assert.ok($('#sd-banner') === null, '成员不显示累计数据的实例横幅');
    assert.doesNotMatch($('#sd-body').textContent, /undefined/);
    assert.doesNotMatch($('#status-text').textContent, /页面发生错误|页面加载失败/);
  });
}
// 管理员的导航、资源读数和今日开关仍应保留。
if (['admin', 'legacy', 'unknown'].includes(mode)) await check('管理员保留原版导航首页', () => {
  assert.deepEqual(Array.from($('#nav').querySelectorAll('a')).filter(visible).map(a => a.dataset.page), ['home','log','links','push','streamers','settings']);
  assert.equal($('#run-mem').textContent, '12 / 64 MB'); assert.ok($('#toggle-push'));
  assert.ok(requests.some(([,path]) => path.endsWith('/schema')));
});
for (const [method, path] of requests) console.log('REQUEST ' + method + ' ' + path);
console.log('跑了 ' + checks + ' 格，红 ' + failed + ' 格');
if (mode !== 'missing-main') getModule(resolve(plugin, 'streamers.js')).namespace.leave?.();
if (mode === 'requests') { assert.ok(requests.some(([,path]) => path.endsWith('/streamers'))); assert.ok(requests.some(([,path]) => path.includes('/streamers/bilibili/71001'))); }
if (failed || errors.length) process.exitCode = 1;
