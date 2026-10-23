import {readFileSync, existsSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => existsSync(join(ui, name)) ? readFileSync(join(ui, name), 'utf8') : '';
const failures = [];
let checks = 0;
async function check(problem, test) {
  checks++;
  try { await test(); } catch (error) { failures.push(problem + '：' + error.message); }
}

// 使用小型 DOM 执行页面模块，直接点按钮并检查请求与屏幕上的字。
class Node {
  constructor(tag = 'div', cls = '') {
    this.tagName = tag; this.className = cls; this.children = []; this.dataset = {};
    this.listeners = {}; this.attributes = {}; this.hidden = false; this.disabled = false;
    this.checked = false; this._text = '';
    const classes = new Set(cls.split(/\s+/).filter(Boolean));
    this.classList = {contains: name => classes.has(name), toggle: (name, on) => {
      if (on) classes.add(name); else classes.delete(name);
    }};
  }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(n => n.textContent).join(''); }
  replaceChildren(...nodes) { this._text = ''; this.children = nodes; }
  appendChild(node) { this.children.push(node); return node; }
  setAttribute(key, value) { this.attributes[key] = String(value); }
  removeAttribute(key) { delete this.attributes[key]; if (key === 'href') delete this.href; }
  querySelector(selector) { return walk(this).find(n => selector === '#' + n.id); }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  querySelectorAll(selector) {
    return walk(this).filter(n => selector === 'input:checked' ? n.tagName === 'input' && n.checked
      : selector === 'button' ? n.tagName === 'button'
      : selector === 'input' ? n.tagName === 'input' : false);
  }
  async click() { if (!this.disabled) await (this.onclick || this.listeners.click)?.(); }
}
function walk(root) { return root.children.flatMap(node => [node, ...walk(node)]); }
function find(root, action) {
  const node = walk(root).find(n => n.dataset.action === action || n.textContent === action);
  assert.ok(node, '缺少操作 ' + action); return node;
}
const base = {role: 'ADMIN', accountId: 'local-admin', authenticated: true, enabled: true, oidcEnabled: true};
const account = (id, role, status, extra = {}) => ({id, role, status, displayName: id, email: id + '@example.com',
  streamers: [], lastLoginAt: '2026-10-20T01:02:03Z', ...extra});
const entries = [account('local-admin', 'ADMIN', 'ENABLED'), account('enabled', 'MEMBER', 'ENABLED',
  {streamers: ['bilibili:11', 'other:99']}), account('admin', 'ADMIN', 'ENABLED'),
  account('disabled', 'MEMBER', 'DISABLED'), account('pending', 'MEMBER', 'PENDING')];
function subject(file, reply = {}, confirm = true) {
  const requests = [], questions = [], opened = [];
  const box = new Node();
  const controls = {'#set-search': {value: ''}, '#only-changed': {checked: false}};
  if (file === 'personal-menu.js') {
    for (const [tag, id, label] of [['summary', 'personal-name', ''], ['a', 'account-center', '账户中心'],
      ['button', 'logout', '退出'], ['p', 'personal-result', '']]) {
      const node = new Node(tag); node.id = id; node.textContent = label;
      if (id === 'logout') node.dataset.action = 'logout';
      box.appendChild(node);
    }
  }
  const window = {location: {href: '', reload: () => opened.push('reload')},
    open: (...args) => opened.push(args)};
  let dirty = 0;
  const api = async (path, options = {}) => {
    requests.push({path, ...options});
    if (reply[path]) return typeof reply[path] === 'function' ? reply[path](options) : reply[path];
    if (path === '/accounts') return {success: true, accounts: entries, managementUri: 'https://accounts.example.com/manage'};
    if (path === '/datasource/users') return {success: true, users: [
      {platform: 'bilibili', uid: '11', enabled: true}, {platform: 'other', uid: '22', enabled: false}]};
    if (path.includes('/streamer/name?')) return {success: true, uname: path.includes('bilibili') ? '主播甲' : ''};
    return {success: true};
  };
  const src = read(file).replace(/^import .*?;\s*$/gm, '').replace(/export /g, '');
  const exports = file === 'settings-members.js' ? '{renderMembers, memberGroup, setMemberAuthState, filterMembers: typeof filterMembers === "function" ? filterMembers : undefined}' : '{renderPersonalMenu}';
  const shared = {role: 'ADMIN'};
  const mod = new Function('el', 'api', 'ask', 'window', 'hasUnsavedChanges', 'store', '$', src + '\nreturn ' + exports)(
    (tag, cls) => new Node(tag, cls), api,
    async options => { questions.push(options); return confirm; }, window, () => dirty > 0, shared, selector => controls[selector] || box);
  return {...mod, store: shared, controls, box, requests, questions, opened, window, setDirty: value => { dirty = value; }};
}
async function members(reply, confirm) {
  const s = subject('settings-members.js', reply, confirm);
  await s.renderMembers(s.box, base); return s;
}
function row(s, id) { const n = walk(s.box).find(n => n.dataset.accountId === id); assert.ok(n, '缺少账号行 ' + id); return n; }

// 抓已有搜索词时，成员读完或操作后重画仍被隐藏。
await check('成员读完和停用后按当前搜索词重新显示', async () => {
  let finishRead, notifyRead;
  let reading = new Promise(resolve => { notifyRead = resolve; });
  const s = subject('settings-members.js', {'/accounts': () => new Promise(resolve => {
    finishRead = resolve;
    notifyRead();
  })});
  s.controls['#set-search'].value = 'enabled@example.com';
  const render = s.renderMembers(s.box, base);
  s.filterMembers(s.controls['#set-search'].value, false);
  assert.equal(s.box.classList.contains('hide'), true);
  finishRead({success: true, accounts: entries});
  await render;
  assert.equal(s.box.classList.contains('hide'), false, '成员读完应显示邮箱匹配的组');

  reading = new Promise(resolve => { notifyRead = resolve; });
  const disable = find(row(s, 'enabled'), 'disable').click();
  await reading;
  s.filterMembers(s.controls['#set-search'].value, false);
  assert.equal(s.box.classList.contains('hide'), true);
  finishRead({success: true, accounts: entries.map(a => a.id === 'enabled' ? {...a, status: 'DISABLED'} : a)});
  await disable;
  assert.equal(s.box.classList.contains('hide'), false, '停用后重画应仍显示邮箱匹配的组');
  assert.ok(s.requests.some(request => request.path === '/accounts/enabled/disable'));
});
// 抓管理员进设置后仍找不到等待授权的新成员，以及身份、状态或主播名字写错。
await check('待授权排最前，身份状态和主播名字可辨认', async () => {
  const s = await members();
  const rows = walk(s.box).filter(n => n.dataset.accountId);
  assert.equal(rows[0].dataset.accountId, 'pending');
  for (const [id, words] of [['local-admin', ['本地管理员', '已启用']], ['admin', ['管理员', '已启用']],
    ['enabled', ['成员', '已启用', '主播甲', 'other:99', 'enabled@example.com', '2026']],
    ['disabled', ['成员', '已停用']], ['pending', ['成员', '待授权', '对方已经用统一登录进来过一次，等你授权。']]]) {
    for (const word of words) assert.ok(row(s, id).textContent.includes(word), '缺少 ' + word);
  }
});
// 抓停用或待授权的管理员没有启用按钮，或者误要求给管理员选主播。
for (const [status, label] of [['DISABLED', '启用'], ['PENDING', '授权并启用']]) {
  await check('管理员 ' + status + ' 直接' + label, async () => {
    const s = await members({'/accounts': {success: true, accounts: [account('recover-admin', 'ADMIN', status)]}});
    const r = row(s, 'recover-admin');
    assert.equal(r.querySelectorAll('input').length, 0);
    const enable = find(r, 'enable'); assert.equal(enable.textContent, label); await enable.click();
    assert.deepEqual(s.requests.filter(n => n.method === 'POST').map(n => n.path), ['/accounts/recover-admin/enable']);
    assert.equal(s.questions.length, 0);
  });
}
// 抓本地管理员出现改身份按钮，点后才得知不能降级。
await check('本地管理员行没有改身份按钮', async () => {
  const s = await members();
  assert.ok(!walk(row(s, 'local-admin')).some(n => n.dataset.action === 'role'), '本地管理员不应显示改身份按钮');
});
// 抓名字不可用时把已有授权写成未指定；只检查指定主播单元格。
await check('指定主播单元格保留无名字主播的平台与 uid', async () => {
  const s = await members(); const cell = row(s, 'enabled').children[3];
  assert.ok(cell.textContent.includes('other:99'), '指定主播单元格应包含 other:99，实际：' + cell.textContent);
  assert.ok(!cell.textContent.includes('未指定主播'));
});
// 抓成员提示将对方预设为男性。
await check('成员提示使用对方', async () => {
  const s = await members({}, false); await find(row(s, 'enabled'), 'disable').click();
  assert.ok(s.questions[0].body.includes('对方会立刻'));
});
// 抓主播列表过滤掉停用主播，或者启用时写入后端认不出的标识。
await check('勾两位主播后按平台与 uid 保存，再启用', async () => {
  const s = await members(); const r = row(s, 'pending');
  const inputs = r.querySelectorAll('input'); assert.equal(inputs.length, 2);
  assert.ok(r.textContent.includes('主播甲') && r.textContent.includes('bilibili:11') && r.textContent.includes('other:22'));
  inputs.forEach(n => { n.checked = true; }); await find(r, 'enable').click();
  const writes = s.requests.filter(n => n.method === 'POST');
  assert.deepEqual(writes.map(n => n.path), ['/accounts/pending/streamers', '/accounts/pending/enable']);
  assert.deepEqual(JSON.parse(writes[0].body), {streamers: ['bilibili:11', 'other:22']});
});
// 抓不选主播就启用后，成员进来空空如也却事先没得到提醒。
await check('零主播启用先确认，取消不发请求', async () => {
  const s = await members({}, false); await find(row(s, 'pending'), 'enable').click();
  assert.ok(s.questions.some(n => n.body === '没指定主播，对方进来什么都看不到。'));
  assert.equal(s.requests.filter(n => n.method === 'POST').length, 0);
});
// 抓确认空授权后仍无法启用。
await check('确认后允许零主播启用', async () => {
  const s = await members(); await find(row(s, 'disabled'), 'enable').click();
  assert.equal(s.questions.length, 1);
  assert.ok(s.requests.some(n => n.path === '/accounts/disabled/enable'));
});
// 抓改主播看起来保存了、实际没有生效，或误丢已经移出推送配置的授权。
await check('已启用成员可立即保存主播授权', async () => {
  const s = await members(); const r = row(s, 'enabled'); await find(r, 'edit-streamers').click();
  const inputs = r.querySelectorAll('input');
  assert.ok(inputs.some(n => n.value === 'other:99' && n.checked));
  inputs.forEach(n => { n.checked = n.value === 'other:22'; }); await find(r, 'save-streamers').click();
  assert.deepEqual(JSON.parse(s.requests.find(n => n.path === '/accounts/enabled/streamers').body), {streamers: ['other:22']});
});
// 抓管理员手滑停用正在用的账号，没有机会取消。
await check('停用先确认，取消后不写入', async () => {
  const s = await members({}, false); await find(row(s, 'enabled'), 'disable').click();
  assert.ok(s.questions[0].body.includes('立刻') && s.questions[0].body.includes('下线'));
  assert.ok(!s.requests.some(n => n.path.endsWith('/disable')));
});
// 抓正在用的成员或管理员行出现删除操作。
await check('删除仅用于待授权和停用成员，并先确认', async () => {
  const s = await members({}, false);
  for (const id of ['pending', 'disabled']) await find(row(s, id), 'delete').click();
  for (const id of ['enabled', 'admin', 'local-admin']) assert.ok(!walk(row(s, id)).some(n => n.dataset.action === 'delete'));
  assert.equal(s.questions.length, 2); assert.ok(!s.requests.some(n => n.method === 'DELETE'));
});
// 抓停用和删除确认后按钮无效，或者调用错账号。
await check('确认后停用和删除各调用对应接口', async () => {
  const s = await members(); await find(row(s, 'enabled'), 'disable').click(); await find(row(s, 'disabled'), 'delete').click();
  assert.ok(s.requests.some(n => n.path === '/accounts/enabled/disable' && n.method === 'POST'));
  assert.ok(s.requests.some(n => n.path === '/accounts/disabled' && n.method === 'DELETE'));
});
// 抓敏感操作被要求本地登录后，页面既不说原因也不说明如何继续。
for (const [action, path, id] of [['role', '/accounts/enabled/role', 'enabled'],
  ['bind', '/accounts/local/bind', 'local-admin'], ['unbind', '/accounts/local/binding', 'local-admin']]) {
  await check(action + ' 显示本地登录原话与处理方法', async () => {
    const reply = {[path]: {success: false, message: '这一步要先用本地方式重新登录'}};
    if (action === 'unbind') reply['/accounts'] = {success: true, accounts: [account('local-admin', 'ADMIN', 'ENABLED', {issuer: 'https://accounts.example.com', subject: 'local'})]};
    const s = await members(reply); await find(row(s, id), action).click();
    assert.ok(s.box.textContent.includes('这一步要先用本地方式重新登录'));
    assert.ok(s.box.textContent.includes('退出后用口令重新登录一次'));
  });
}
// 抓绑定按钮没有跳到后端给的账户中心登录地址。
await check('绑定跳转 authorizationUrl，角色切换发送目标角色', async () => {
  const s = await members({'/accounts/local/bind': {success: true, authorizationUrl: 'https://accounts.example.com/authorize'}});
  await find(row(s, 'local-admin'), 'bind').click(); assert.equal(s.window.location.href, 'https://accounts.example.com/authorize');
  await find(row(s, 'enabled'), 'role').click();
  assert.deepEqual(JSON.parse(s.requests.find(n => n.path === '/accounts/enabled/role').body), {role: 'ADMIN'});
  await find(row(s, 'admin'), 'role').click();
  assert.deepEqual(JSON.parse(s.requests.find(n => n.path === '/accounts/admin/role').body), {role: 'MEMBER'});
});
// 抓接口失败被吞掉，甚至主播保存失败仍然启用账号。
await check('保存失败显示原话，并停止后续启用', async () => {
  const s = await members({'/accounts/pending/streamers': {success: false, message: '账号未能写入磁盘，请稍后再试'}});
  await find(row(s, 'pending'), 'enable').click();
  assert.ok(s.box.textContent.includes('账号未能写入磁盘，请稍后再试'));
  assert.ok(!s.requests.some(n => n.path === '/accounts/pending/enable'));
});
// 抓新建成员没有打开账户中心，管理员不知道何时会在列表中出现。
await check('有管理地址时新建成员新开页面，并说明首次登录', async () => {
  const s = await members(); await find(s.box, 'new-member').click();
  assert.equal(s.opened[0][0], 'https://accounts.example.com/manage'); assert.equal(s.opened[0][1], '_blank');
  assert.ok(s.box.textContent.includes('在账户中心建好账号后，让对方用统一登录进来一次，这里就会出现一行待授权。'));
});
// 抓管理地址没配时出现空链接，或者只显示按钮而不说怎么配。
await check('无管理地址时只说明如何新建和配置管理地址', async () => {
  const s = await members({'/accounts': {success: true, accounts: entries, managementUri: ''}});
  assert.ok(!walk(s.box).some(n => n.dataset.action === 'new-member'));
  assert.ok(s.box.textContent.includes('在账户中心建好账号后') && s.box.textContent.includes('管理地址'));
});
// 抓没开统一登录仍列空成员表或请求账号接口。
await check('未开统一登录时只有开启说明', async () => {
  const s = subject('settings-members.js'); await s.renderMembers(s.box, {...base, oidcEnabled: false});
  assert.equal(s.requests.length, 0); assert.ok(s.box.textContent.includes('账户中心地址') && s.box.textContent.includes('客户端编号') && s.box.textContent.includes('密钥'));
  assert.ok(!walk(s.box).some(n => n.tagName === 'button'));
});
// 抓成员能看到管理区，页面还偷偷请求全部账号。
await check('成员不渲染成员区，也不请求账号列表', async () => {
  const s = subject('settings-members.js'); s.store.role = 'MEMBER'; s.setMemberAuthState({...base, role: 'MEMBER'});
  assert.equal(s.memberGroup(), null); await s.renderMembers(s.box, {...base, role: 'MEMBER'});
  assert.equal(s.requests.length, 0); assert.equal(s.box.textContent, '');
});
// 抓显示名拿错，以及本地管理员显示成统一登录昵称。
await check('个人菜单显示当前人，本地管理员单独标识', async () => {
  const s = subject('personal-menu.js'); s.renderPersonalMenu(s.box, {...base, accountId: 'member', displayName: '成员甲'});
  assert.ok(s.box.textContent.includes('成员甲'));
  s.renderPersonalMenu(s.box, {...base, displayName: '另一昵称'}); assert.ok(s.box.textContent.includes('本地管理员'));
});
// 抓本地会话也出现账户中心，或者空地址仍能点进去。
await check('账户中心仅在统一登录会话且有地址时出现', async () => {
  for (const [loginMethod, managementUri, expected] of [['oidc', 'https://accounts.example.com/manage', true],
    ['password', 'https://accounts.example.com/manage', false], ['oidc', '', false]]) {
    const s = subject('personal-menu.js'); s.renderPersonalMenu(s.box, {...base, loginMethod, managementUri});
    const link = walk(s.box).find(n => n.tagName === 'a' && n.textContent === '账户中心' && !n.hidden);
    assert.equal(!!link, expected); if (link) assert.equal(link.href, managementUri);
  }
});
// 抓退出丢掉未保存草稿；取消确认不得退出。
await check('退出前检查未保存改动，取消保留页面', async () => {
  const countSource = read('core.js').match(/export function hasUnsavedChanges\(\) \{[\s\S]*?\n\}/)?.[0];
  assert.ok(countSource, '缺少跨页草稿检查');
  const store = {dirty: {}};
  const sources = new Map([['push', {changeCount: () => 1}]]);
  const count = new Function('store', 'changeSources', countSource.replace('export ', '') + '\nreturn hasUnsavedChanges;')(store, sources);
  assert.equal(count(), true, '别页的草稿也要拦退出');
  sources.clear(); assert.equal(count(), false);
  store.dirty.setting = 'changed'; assert.equal(count(), true);
  const s = subject('personal-menu.js', {}, false); s.setDirty(1); s.renderPersonalMenu(s.box, base);
  await find(s.box, 'logout').click(); assert.ok(s.questions[0].body.includes('未保存'));
  assert.equal(s.requests.length, 0); assert.equal(s.opened.length, 0);
});
// 抓确认后或没有草稿时退出按钮无效。
await check('确认后与无草稿时可退出', async () => {
  for (const dirty of [0, 1]) {
    const s = subject('personal-menu.js'); s.setDirty(dirty); s.renderPersonalMenu(s.box, base); await find(s.box, 'logout').click();
    assert.equal(s.questions.length, dirty); assert.equal(s.requests[0].path, '/auth/logout'); assert.deepEqual(s.opened, ['reload']);
  }
});
// 抓模块写好了但设置页、登录态或侧栏从未接上。
await check('成员区和个人菜单接到页面与登录态', async () => {
  for (const [file, pattern] of [['settings.js', /memberGroup\(\)/], ['main.js', /setMemberAuthState\(state\)/],
    ['main.js', /renderPersonalMenu\(/], ['index.html', /id="personal-menu"/],
    ['personal-menu.js', /hasUnsavedChanges\(\)/], ['core.js', /export function hasUnsavedChanges\(/]]) {
    assert.ok(pattern.test(read(file)), file + ' 缺少 ' + pattern);
  }
});

await check('成员组与目录随搜索和仅显示已修改项收起', async () => {
  const s = await members();
  const link = new Node();
  link.getAttribute = () => 'members';
  const controls = {'#set-search': {value: ''}, '#only-changed': {checked: false}, '#set-count': new Node()};
  const document = {
    querySelectorAll: selector => selector === '#grp-nav [data-grp-link]' ? [link] : [],
    querySelector: selector => selector.startsWith('.setgrp[') ? s.box : null,
  };
  const source = read('settings.js');
  const body = source.slice(source.indexOf('export function filterSettings()'), source.indexOf('\n/**', source.indexOf('export function filterSettings()'))).replace('export ', '');
  const filter = new Function('$', 'document', 'store', 'closeHelp', 'filterCards', 'filterAuthCards',
    'filterMembers', 'advancedShown', 'filterKey', 'watchCurrentGroup', 'arranged',
    'let advancedDismissed = null, advancedChosen = false;\n' + body + '\nreturn filterSettings;')(
      key => controls[key] || null, document, {schema: [], ungrouped: []}, () => {}, () => 0, () => 0,
      s.filterMembers, () => false, () => '', () => {}, {common: [], advanced: []});
  for (const [query, changed, visible] of [['静音', false, false], ['成员', false, true],
    ['enabled@example.com', false, true], ['', false, true], ['', true, false], ['成员', true, false]]) {
    controls['#set-search'].value = query;
    controls['#only-changed'].checked = changed;
    filter();
    assert.equal(!s.box.classList.contains('hide'), visible, '成员组：' + query + '/' + changed);
    assert.equal(!link.classList.contains('hide'), visible, '目录：' + query + '/' + changed);
  }
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const failure of failures) console.log('  红：' + failure);
process.exit(failures.length ? 1 : 0);
