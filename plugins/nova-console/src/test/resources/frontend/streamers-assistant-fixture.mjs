import assert from 'node:assert/strict';
import {register} from 'node:module';
import {readFileSync} from 'node:fs';
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const markupWrites = [];
const walk = node => node ? [node, ...node.children.flatMap(walk)] : [];
function matches(node, selector) {
  if (selector.startsWith('#')) return node.id === selector.slice(1);
  if (selector.startsWith('.')) return node.className.split(' ').includes(selector.slice(1));
  const attr = /^\[data-([\w-]+)="([^"]+)"\]$/.exec(selector);
  return attr ? node.dataset[attr[1]] === attr[2] : node.tagName === selector;
}
class Element {
  constructor(tag) {
    this.tagName = tag; this.children = []; this.attrs = {}; this.dataset = {}; this.listeners = {};
    this.className = ''; this.ownText = ''; this.value = ''; this.style = {setProperty(k, v) { this[k] = v; }};
    this.classList = {
      contains: name => this.className.split(' ').includes(name),
      add: (...names) => { this.className += ' ' + names.join(' '); },
      remove: name => { this.className = this.className.split(' ').filter(item => item !== name).join(' '); },
      toggle: (name, on) => { if (on) this.classList.add(name); else this.classList.remove(name); },
    };
  }
  append(...nodes) { nodes.forEach(node => this.appendChild(typeof node === 'string' ? Object.assign(new Element('span'), {textContent: node}) : node)); }
  appendChild(node) { node.remove(); node.parentNode = this; this.children.push(node); return node; }
  insertBefore(node, before) { node.remove(); node.parentNode = this; this.children.splice(Math.max(0, this.children.indexOf(before)), 0, node); }
  replaceChildren(...nodes) { this.children.forEach(node => { node.parentNode = null; }); this.children = []; this.ownText = ''; this.append(...nodes); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(node => node !== this); this.parentNode = null; }
  set textContent(value) { this.replaceChildren(); this.ownText = String(value); }
  get textContent() { return this.ownText + this.children.map(node => node.textContent).join(''); }
  set innerHTML(value) { markupWrites.push(String(value)); this.textContent = value; }
  get innerHTML() { return this.textContent; }
  setAttribute(key, value) { this.attrs[key] = String(value); }
  getAttribute(key) { return this.attrs[key] ?? null; }
  removeAttribute(key) { delete this.attrs[key]; }
  querySelectorAll(selector) { return walk(this).slice(1).filter(node => matches(node, selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  contains(node) { return walk(this).includes(node); }
  get firstChild() { return this.children[0]; }
  get isConnected() { return this === document.body || !!this.parentNode?.isConnected; }
  getBoundingClientRect() { return {width: 640, height: 180, left: 0, top: 100, bottom: 280}; }
  focus() { document.activeElement = this; }
  showModal() { this.open = true; }
  close() { this.open = false; return this.fire('close'); }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  async fire(type, fields = {}) { for (const fn of this.listeners[type] || []) await fn({target: this, preventDefault() {}, ...fields}); }
}
let selectedText = '', copiedText = '';
globalThis.document = {
  body: new Element('body'), head: new Element('head'), documentElement: new Element('html'),
  hidden: false, activeElement: null, addEventListener() {},
  createElement: tag => new Element(tag), createElementNS: (_, tag) => new Element(tag),
  querySelector(selector) { return this.body.querySelector(selector); },
  querySelectorAll(selector) { return this.body.querySelectorAll(selector); },
  createRange() { return {selectNodeContents(node) { this.node = node; }}; },
};
globalThis.window = {getSelection: () => ({removeAllRanges() {}, addRange(range) { selectedText = range.node.textContent; }}),
  matchMedia: () => ({matches: false}), scrollY: 0, scrollTo(_x, y) { this.lastScroll = y; }};
globalThis.location = {hash: '#/streamers/sample/1', reload() {}};
globalThis.ResizeObserver = class { observe() {} disconnect() {} };
globalThis.setInterval = () => 1;
globalThis.clearInterval = () => {};
for (const name of ['local' + 'Storage', 'session' + 'Storage']) Object.defineProperty(globalThis, name, {
  get() { throw new Error('浏览器持久存储不可用'); },
});
Object.defineProperty(globalThis, 'navigator', {configurable: true, value: {clipboard: {async writeText(text) { copiedText = text; }}}});
for (const id of ['sv-list', 'sv-detail', 'sv-session', 'sd-back', 'sd-head', 'sd-body', 'sx-back', 'sx-title', 'sx-body', 'status-text']) {
  const node = new Element('div'); node.id = id; document.body.append(node);
}
const {store} = await import('../../../../../../core/nova-core/src/main/resources/config-ui/store.js');
store.role = 'ADMIN'; store.csrfToken = 'sample-token';
let state = {enabled: true, available: true, reasons: [], questionsLeftToday: 9};
let stateError = null, stateReads = 0, stateGate = null, askGate = null;
let reply = {answered: true, label: '由人工智能生成', answer: '样例回答\n下一行', answerForHistory: '原样历史',
  steps: [{function: 'trend', arguments: {}, source: '场次趋势，与主播页同一算法'}], unverifiedNumbers: ['12'],
  costYuan: 0.01230001, remaining: {questionsToday: 8, spentYuan: 1.2300001, monthlyBudgetYuan: 2}};
const initialReply = reply;
let askError = null, httpStatus = 200;
const requests = [];
globalThis.fetch = async (url, options) => {
  const response = body => ({status: httpStatus, ok: httpStatus < 400, json: async () => body});
  if (url.endsWith('/assistant/state')) {
    stateReads++;
    if (stateGate) await stateGate;
    if (stateError) throw stateError;
    return response(state);
  }
  if (url.endsWith('/assistant/ask')) {
    requests.push({url, options, body: JSON.parse(options.body)});
    if (askGate) await askGate;
    if (askError) throw askError;
    return response(reply);
  }
  if (url.endsWith('/status')) return response({});
  if (/\/streamers\/sample\/\d+$/.test(url)) return response({success: true, platform: 'sample', uid: 1, uname: '样例主播', configured: true, living: false, overview: {}});
  return response({success: false, message: '暂无数据'});
};
const moduleUrl = new URL('../../../main/resources/config-ui-pages/streamers-assistant.js', import.meta.url);
let ui, importFailure;
try { ui = await import(moduleUrl); } catch (error) { importFailure = error; }
const byClass = (node, name) => node.querySelectorAll('.' + name);
const buttons = node => node.querySelectorAll('button');
const button = (node, text) => buttons(node).find(item => item.textContent === text);
const host = () => { const node = new Element('div'); document.body.append(node); return node; };
let uid = 10;
async function mount(identity = {platform: 'sample', uid: ++uid}) {
  assert.ok(ui, '问答抽屉模块应能加载：' + (importFailure?.code || ''));
  const node = host(), view = ui.mountAssistant(node, identity);
  await view.ready;
  return {node, view, identity};
}
async function ask(mounted, question = '这场表现如何？') {
  const input = mounted.node.querySelector('textarea');
  input.value = question;
  await input.fire('input');
  await mounted.node.querySelector('form').fire('submit');
}
async function until(predicate, message) {
  const end = Date.now() + 10000;
  while (!predicate()) {
    assert.ok(Date.now() < end, message);
    await new Promise(resolve => setImmediate(resolve));
  }
}
const checks = {
  // 错误若带结论标识或复制按钮，使用者会把失败说明当作模型回答。
  async failures() {
    const mounted = await mount();
    for (const reason of ['timeout', 'key_rejected', 'provider_balance', 'daily_limit', 'disabled', 'no_key', 'not_acknowledged', 'usage_unreadable', 'monthly_budget']) {
      const before = stateReads;
      reply = {answered: false, reason, answer: '这次没有回答：' + reason, remaining: {questionsToday: 0}};
      if (reason === 'daily_limit') state = {...state, available: false, reasons: ['daily_limit']};
      await ask(mounted);
      const turn = byClass(mounted.node, 'sa-turn').at(-1);
      assert.match(turn.textContent, /这次没有回答/);
      assert.ok(!turn.textContent.includes('由人工智能生成'), reason + ' 不应有结论标识');
      assert.equal(button(turn, '复制'), undefined, reason + ' 不应有复制按钮');
      assert.equal(turn.querySelectorAll('a').length, ['key_rejected', 'provider_balance', 'disabled', 'no_key', 'not_acknowledged', 'usage_unreadable', 'monthly_budget'].includes(reason) ? 1 : 0);
      if (reason === 'daily_limit') {
        await until(() => mounted.node.querySelector('textarea').disabled && stateReads > before, '次数耗尽后应重读状态');
        assert.match(mounted.node.textContent, /今天的提问次数用完/);
      }
      state = {...state, available: true, reasons: []};
      mounted.view.destroy();
      const again = await mount(mounted.identity); Object.assign(mounted, again);
    }
    for (const status of [400, 404]) {
      httpStatus = status; reply = {success: false, message: '问题不符合要求'};
      await ask(mounted);
      assert.match(byClass(mounted.node, 'sa-turn').at(-1).textContent, /问题不符合要求/);
    }
    httpStatus = 200; askError = new TypeError('offline'); await ask(mounted);
    assert.match(byClass(mounted.node, 'sa-turn').at(-1).textContent, /无法连接到服务器/);
    assert.equal(button(mounted.node, '复制'), undefined);
    const count = requests.length;
    await ask(mounted, '   '); assert.equal(requests.length, count, '空白问题不能发出');
    askError = null;
    await ask(mounted, '😀'.repeat(301));
    assert.equal([...requests.at(-1).body.question].length, 300, '按字符数截断，不能按字符串长度截断');
    assert.equal(requests.at(-1).options.headers['X-CSRF-Token'], 'sample-token');
    assert.match(mounted.node.textContent, /回答由人工智能生成，可能出错；数字以报告为准。/);
    for (const [reason, message] of Object.entries({disabled: '智能助手还没开启。', no_key: '还没填模型密钥。',
      not_acknowledged: '还没确认配置者说明。', usage_unreadable: '用量记录读不出来，请检查后再试。',
      monthly_budget: '本月额度已用完。', daily_limit: '今天的提问次数用完了，明天再来。'})) {
      state = {...state, available: false, reasons: [reason]};
      const unavailable = await mount();
      assert.ok(unavailable.node.textContent.includes(message));
      assert.ok(unavailable.node.querySelector('textarea').disabled);
      assert.ok(button(unavailable.node, '提问').disabled);
      assert.equal(unavailable.node.querySelectorAll('a').length, reason === 'daily_limit' ? 0 : 1);
      assert.match(unavailable.node.textContent, /回答由人工智能生成，可能出错；数字以报告为准。/);
    }
  },
  // 第四条历史或失败记录进请求，会使追问被后台拒绝。
  async history() {
    let mounted = await mount();
    const successful = [];
    for (let i = 1; i <= 6; i++) {
      const question = '问题' + i;
      reply = i === 2 ? {answered: false, reason: 'timeout', answer: '暂未回答', answerForHistory: '失败历史'}
        : {answered: true, answer: '屏幕回答' + i, answerForHistory: '专用历史' + i};
      await ask(mounted, question);
      assert.deepEqual(requests.at(-1).body.history, successful.slice(-3));
      if (i !== 2) successful.push({question, answerForHistory: reply.answerForHistory});
    }
    httpStatus = 400; reply = {success: false, message: '问题不符合要求'}; await ask(mounted, '被拒绝的问题');
    httpStatus = 200; askError = new TypeError('offline'); await ask(mounted, '断网的问题'); askError = null;
    reply = {answered: true, answer: '超长历史也正常显示', answerForHistory: '😀'.repeat(8001)};
    await ask(mounted, '超长');
    await ask(mounted, '再追问');
    assert.deepEqual(requests.at(-1).body.history, successful.slice(-3), '长度超过 16000 的历史跳过');
    assert.match(mounted.node.textContent, /超长历史也正常显示/);
    reply = {answered: true, answer: '边界历史', answerForHistory: 'h'.repeat(16000)};
    await ask(mounted, '边界');
    let resolveAsk;
    askGate = new Promise(resolve => { resolveAsk = resolve; });
    reply = {answered: true, answer: '关闭后回来的回答', answerForHistory: '关闭历史', remaining: {questionsToday: 6}};
    const pending = ask(mounted, '关闭之前');
    await until(() => requests.at(-1)?.body.question === '关闭之前', '提问应发出');
    assert.equal(requests.at(-1).body.history.at(-1).answerForHistory.length, 16000);
    assert.ok(mounted.node.querySelector('textarea').disabled);
    assert.match(mounted.node.textContent, /正在查数并回答/);
    const count = requests.length; await ask(mounted, '重复提交'); assert.equal(requests.length, count);
    mounted.view.destroy(); mounted.node.remove();
    let resolveState; stateGate = new Promise(resolve => { resolveState = resolve; });
    const reopened = host(), reopenedView = ui.mountAssistant(reopened, mounted.identity);
    resolveAsk(); await pending; askGate = null;
    resolveState(); await reopenedView.ready; stateGate = null;
    mounted = {node: reopened, view: reopenedView, identity: mounted.identity};
    assert.match(mounted.node.textContent, /今天还能问 6 次/, '较晚到达的状态不能盖掉提问回包中的剩余次数');
    assert.match(mounted.node.textContent, /关闭后回来的回答/);
    const other = await mount({platform: 'another', uid: mounted.identity.uid});
    assert.equal(byClass(other.node, 'sa-turn').length, 0, '平台不同不能串对话');
    const fresh = await import(moduleUrl.href + '?reload=1');
    const freshHost = host(); await fresh.mountAssistant(freshHost, mounted.identity).ready;
    assert.equal(byClass(freshHost, 'sa-turn').length, 0, '页面重新载入后内存对话清空');
    await button(mounted.node, '清空对话').fire('click');
    assert.equal(byClass(mounted.node, 'sa-turn').length, 0);
    assert.equal(button(mounted.node, '清空对话'), undefined);
  },
  // 弹幕原话可能像网页标签，问题、回答、来源和报错都不能进入网页解析器。
  async text() {
    const mounted = await mount();
    const payload = '<img src=x onerror=alert(1)>';
    reply = {...reply, answer: payload + '\n第二行', steps: [{source: payload}], unverifiedNumbers: [payload]};
    await ask(mounted, payload);
    const turn = byClass(mounted.node, 'sa-turn')[0];
    assert.ok(turn.textContent.includes(payload + '\n第二行'));
    assert.equal(turn.querySelectorAll('img').length, 0);
    assert.equal(markupWrites.filter(value => value.includes(payload)).length, 0, '后台文本不能写入网页代码');
    const fold = turn.querySelector('details');
    assert.equal(fold.open || false, false);
    assert.match(fold.textContent, /查了 1 项数据/);
    assert.ok(fold.textContent.includes(payload));
    assert.match(turn.textContent, /这几个数字没能在查到的数据里核对上/);
    assert.match(turn.textContent, /由人工智能生成 · 仅供参考/);
    assert.match(turn.textContent, /本问约 ¥0.0123 · 本月已用 ¥1.23，上限 ¥2/);
    const css = readFileSync(moduleUrl, 'utf8');
    assert.match(css, /white-space:\s*pre-wrap/, '换行必须在浏览器里保留');
    reply = {answered: false, reason: 'timeout', answer: payload}; await ask(mounted, payload);
    reply = {success: false, message: payload}; await ask(mounted, payload);
    assert.equal(markupWrites.filter(value => value.includes(payload)).length, 0);
  },
  // 复制缺少提示句时，离开控制台的回答失去来源说明；普通网页访问也应能手动复制。
  async copy() {
    const mounted = await mount(); await ask(mounted);
    const expected = reply.answer + '\n（以上内容由人工智能生成，仅供参考）';
    await button(mounted.node, '复制').fire('click'); assert.equal(copiedText, expected);
    navigator.clipboard = undefined;
    await button(mounted.node, '复制').fire('click'); assert.equal(selectedText, expected);
    selectedText = ''; navigator.clipboard = {async writeText() { throw new Error('denied'); }};
    await button(mounted.node, '复制').fire('click'); assert.equal(selectedText, expected);
  },
  // 后台即使回了费用字段，成员也不能看到金额或管理设置链接。
  async member() {
    store.role = 'MEMBER';
    const mounted = await mount(); await ask(mounted);
    assert.doesNotMatch(mounted.node.textContent, /¥|本问约|本月已用|上限/);
    assert.equal(mounted.node.querySelectorAll('a').length, 0);
    for (const reason of ['disabled', 'no_key', 'not_acknowledged', 'usage_unreadable', 'monthly_budget', 'key_rejected', 'provider_balance']) {
      reply = {answered: false, reason, answer: '暂时不能回答'}; await ask(mounted);
      const turn = byClass(mounted.node, 'sa-turn').at(-1);
      assert.match(turn.textContent, /请联系管理员/);
      assert.equal(turn.querySelectorAll('a').length, 0);
    }
    state = {...state, available: false, reasons: ['no_key', 'not_acknowledged', 'usage_unreadable', 'monthly_budget', 'daily_limit']};
    mounted.view.destroy();
    const unavailable = await mount(mounted.identity);
    assert.equal((unavailable.node.textContent.match(/智能助手暂时不能用，请联系管理员。/g) || []).length, 1);
    assert.match(unavailable.node.textContent, /本月额度已用完，请联系管理员/);
    assert.match(unavailable.node.textContent, /今天的提问次数用完/);
    assert.equal(unavailable.node.querySelectorAll('a').length, 0);
    assert.ok(unavailable.node.querySelector('textarea').disabled);
    assert.ok(button(unavailable.node, '提问').disabled);
  },
  // 总开关关闭时成员不应看到入口；管理员需要入口才能知道应去哪里开启。
  async entry() {
    assert.ok(ui, '问答抽屉模块应能加载');
    const page = await import('../../../main/resources/config-ui-pages/streamers.js');
    const entry = () => document.querySelector('#sd-head').querySelectorAll('a').find(node => node.textContent === '智能助手');
    state = {enabled: false, available: false, reasons: ['disabled'], questionsLeftToday: 0};
    store.role = 'MEMBER'; await page.refresh();
    await until(() => stateReads > 0, '成员页头应读取状态');
    assert.equal(entry(), undefined);
    location.hash += '?panel=assistant'; await page.refresh();
    let dialog = document.body.querySelector('dialog');
    assert.match(dialog.textContent, /管理员还没开启智能助手。/);
    assert.equal(dialog.querySelectorAll('a').length, 0);
    assert.ok(dialog.querySelector('textarea').disabled);
    page.leave(); store.role = 'ADMIN'; await page.refresh();
    assert.ok(entry()); assert.match(entry().href, /panel=assistant/);
    dialog = document.body.querySelector('dialog');
    assert.equal(dialog.querySelector('h2').textContent, '智能助手');
    assert.match(dialog.textContent, /智能助手还没开启。/);
    const settingsLink = dialog.querySelectorAll('a').find(node => node.href === '#/settings?card=assistant');
    assert.ok(settingsLink, '开启入口要指向智能助手设置分组');
    await settingsLink.fire('click'); location.hash = settingsLink.href;
    const target = new Element('section'); target.dataset.grp = 'assistant'; target.className = 'hide'; document.body.append(target);
    const coreSource = name => readFileSync(new URL('../../../../../../core/nova-core/src/main/resources/config-ui/' + name, import.meta.url), 'utf8');
    const declaration = (source, name) => {
      const found = source.match(new RegExp('(?:export )?function ' + name + '\\([^]*?\\n}'));
      assert.ok(found, '应能执行实际的 ' + name);
      return found[0].replace(/^export /, '');
    };
    const settings = coreSource('settings.js'), main = coreSource('main.js');
    const {openCards} = await import('../../../../../../core/nova-core/src/main/resources/config-ui/settings-alert.js');
    const headerHeight = new Function('window', declaration(settings, 'headerHeight') + '; return headerHeight;')(window);
    const focusGroup = new Function('$', 'document', 'window', 'headerHeight', 'openCards',
      declaration(settings, 'focusGroup') + '; return focusGroup;')(selector => document.querySelector(selector), document, window, headerHeight, openCards);
    const focusCard = new Function('focusGroup', 'focusStation', declaration(main, 'focusCard') + '; return focusCard;')(focusGroup, () => {});
    focusCard('settings', new URLSearchParams(location.hash.split('?')[1]).get('card'), false);
    assert.equal(target.dataset.fold, '0', '点设置链接后应展开智能助手组');
    assert.equal(target.classList.contains('hide'), false);
    assert.equal(window.lastScroll, 100, '滚动到智能助手组');
    page.leave(); location.hash = '#/streamers/sample/1';
    stateError = new TypeError('offline'); store.role = 'MEMBER'; await page.refresh();
    assert.equal(entry(), undefined, '状态读取失败时成员没有入口');
    page.leave(); store.role = 'ADMIN'; location.hash += '?panel=assistant'; await page.refresh();
    assert.ok(entry(), '状态读取失败时管理员仍有入口');
    assert.match(document.body.querySelector('dialog').textContent, /无法连接到服务器/);
    stateError = null; state = {enabled: true, available: true, reasons: [], questionsLeftToday: 9};
    page.leave(); store.role = 'MEMBER';
    let resolveState; stateGate = new Promise(resolve => { resolveState = resolve; });
    location.hash = '#/streamers/sample/1'; const loading = page.refresh();
    await until(() => document.querySelector('#sd-head').textContent.includes('样例主播'), '页头应先出现');
    assert.equal(entry(), undefined, '状态没回来之前成员没有入口');
    resolveState(); await loading; stateGate = null;
    await until(entry, '开启后成员入口应出现');
    location.hash = entry().href; await page.refresh();
    let mounted = {node: document.body.querySelector('dialog')}; await ask(mounted, '范围切换后仍保留');
    assert.match(mounted.node.textContent, /今天还能问 8 次/);
    const previousHash = location.hash;
    location.hash = location.hash.replace(/days=\d+/, 'days=7'); assert.notEqual(location.hash, previousHash);
    await page.refresh();
    assert.match(document.body.querySelector('dialog').textContent, /范围切换后仍保留/);
    assert.match(entry().href, /days=7/, '页头入口应保留当前天数范围');
    mounted = {node: document.body.querySelector('dialog')};
    let resolveAsk; askGate = new Promise(resolve => { resolveAsk = resolve; });
    const pending = ask(mounted, '关掉抽屉才回答');
    await until(() => requests.at(-1)?.body.question === '关掉抽屉才回答', '关闭前提问应已发出');
    await button(document.body.querySelector('dialog'), '关闭').fire('click');
    await page.refresh(); assert.equal(document.body.querySelector('dialog'), null);
    resolveAsk(); await pending; askGate = null;
    location.hash = entry().href; await page.refresh();
    assert.match(document.body.querySelector('dialog').textContent, /范围切换后仍保留/);
    const lateTurn = byClass(document.body.querySelector('dialog'), 'sa-turn').at(-1);
    assert.match(lateTurn.textContent, /关掉抽屉才回答/);
    assert.match(lateTurn.textContent, /样例回答/);
    page.leave();
  },
};
let count = 0, failures = 0;
for (const [name, check] of Object.entries(checks)) {
  if (process.env.ASSISTANT_CASE && process.env.ASSISTANT_CASE !== name) continue;
  count++;
  store.role = 'ADMIN'; state = {enabled: true, available: true, reasons: [], questionsLeftToday: 9};
  reply = initialReply; askError = null; stateError = null; httpStatus = 200;
  navigator.clipboard = {async writeText(text) { copiedText = text; }};
  try { await check(); process.stdout.write('绿 ' + name + '\n'); }
  catch (error) { failures++; process.stdout.write('红 ' + name + '：' + error.stack + '\n'); }
}
process.stdout.write('跑了 ' + count + ' 格，红 ' + failures + ' 格\n');
process.exitCode = failures ? 1 : 0;
