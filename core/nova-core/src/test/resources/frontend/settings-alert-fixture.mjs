/**
 * 邮件告警卡：选预设后药丸要跟着变；认预设要把端口算进去；初值问运行值
 *
 * 程序给输入框赋值不触发 input，药丸若只听 input 就会停在「未配置」。
 * 只按服务器名认预设时，端口对不上也会把自定义栏藏起来，465 和 587 从界面上看不见。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 药丸初值答的是「此刻真的能发吗」：/api/status 说不配而文件里两栏都填着
 * （spring.mail.host 可以被环境变量越过文件），初值仍要写「未配置」。
 * ④切的是接线段（草稿判定义到卡片入列前）并真跑：初值走没走 /status 由行为说话，
 * 不靠「源码里有没有那个函数」——函数在而接线断掉时，只切函数本身的格是绿的。
 * ⑤/status 取不到时药丸写「运行值未取到」，不退回草稿判；⑥status 在途时敲键，
 * 草稿回评先行接管，后到的运行值不覆盖那次回评。
 * 发件账号与发件授权码两框按卡片里的调用真建出来：授权码不承接浏览器已存的登录口令，
 * 账号不承接登录名，两框各有自己的 name。设置页口令型机密框同样不承接已存口令。
 * 自己敲进授权码的字仍记成一笔改动。
 * 告警地址框同法：Bark、Server 酱把推送密钥拼在地址里，地址照口令框处理，
 * 不明文摆在画面上；自己敲进新地址仍记成改动，保存照常能落盘。
 * 清除态重画不丢：挂着清除还没保存时一重画仍是清除态，点一次即撤回。
 * ⑭清除钮那句话挂着清除时是看得见的字；⑮Webhook 药丸按保存后会怎样说（留空＝保持原值）。
 *
 * 由 SettingsAlertViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {CLEAR, MASK, secretDraft} from '../../../main/resources/config-ui/settings-model.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'settings-alert.js'), 'utf8');
const model = readFileSync(join(ui, 'alert-model.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个空行」。
 */
function bracedFrom(text, marker) {
  const start = text.indexOf(marker);
  if (start < 0) return '';
  const open = text.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    const prev = i > 0 ? text[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && text[i + 1] === '/') {
      const nl = text.indexOf('\n', i);
      i = nl < 0 ? text.length : nl;
      continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return '';
}

function constArrow(text, name) {
  return bracedFrom(text, 'const ' + name + ' = ');
}

const sample = [
  'const other = 1;',
  'const sampleFn = () => {',
  '  const a = 1;',
  '',
  '  return a + 2;',
  '};',
  '',
  'const next = 3;',
].join('\n');
const sampleBody = constArrow(sample, 'sampleFn');
eq(sampleBody.includes('return a + 2') && sampleBody.includes('const a = 1')
  && !sampleBody.includes('const next') && sampleBody.length > 0,
  true, '阳性对照：函数体内含空行仍整段取到，命中 > 0');

function loadPresets() {
  const body = bracedFrom(src, 'const MAIL_PRESETS = ');
  if (!body) throw new Error('no MAIL_PRESETS');
  return new Function(body + '\nreturn MAIL_PRESETS;')();
}

function loadPillState() {
  const body = bracedFrom(src, 'function pillState');
  if (!body) throw new Error('no pillState');
  return new Function(body + '\nreturn pillState;')();
}

function loadMailConfigured() {
  const body = bracedFrom(model, 'export function mailAlertConfigured').replace(/^export\s+/, '');
  if (!body) throw new Error('no mailAlertConfigured');
  return new Function(body + '\nreturn mailAlertConfigured;')();
}

function loadApplyMail(MAIL_PRESETS, mailPreset, mailCustom, host, port, setValue, mailReady) {
  const body = constArrow(src, 'applyMail');
  if (!body) throw new Error('no applyMail');
  return new Function(
    'MAIL_PRESETS', 'mailPreset', 'mailCustom', 'host', 'port', 'setValue', 'mailReady',
    body + '\nreturn applyMail;')(
    MAIL_PRESETS, mailPreset, mailCustom, host, port, setValue, mailReady);
}

function harness() {
  const MAIL_PRESETS = loadPresets();
  const pillState = loadPillState();
  const mailAlertConfigured = loadMailConfigured();
  const pill = {className: 'pill', textContent: '未配置'};
  const mailCustom = {
    classList: {
      hide: false,
      toggle(_name, force) { this.hide = !!force; },
    },
  };
  const host = {value: ''};
  const port = {value: ''};
  const to = {value: 'a@example.invalid'};
  const mailPreset = {value: ''};
  const dirty = {};
  const setValue = (name, value) => { dirty[name] = String(value); };
  let readyCalls = 0;
  const mailReady = () => {
    readyCalls++;
    pillState(pill, mailAlertConfigured(to.value, host.value));
  };
  const applyMail = loadApplyMail(
    MAIL_PRESETS, mailPreset, mailCustom, host, port, setValue, mailReady);
  return {
    MAIL_PRESETS, applyMail, mailPreset, mailCustom, host, port, dirty, pill,
    ready() { return readyCalls; },
  };
}

let q2 = 'missing';
try {
  const h = harness();
  const name = Object.keys(h.MAIL_PRESETS)[0];
  const shape = h.MAIL_PRESETS[name];
  h.mailPreset.value = name;
  h.applyMail();
  q2 = h.pill.textContent === '已配置'
    && String(h.pill.className).includes('ok')
    && h.host.value === shape.host
    && String(h.port.value) === String(shape.port)
    && h.dirty['spring.mail.host'] === shape.host
    && h.mailCustom.classList.hide === true
    && h.ready() > 0;
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, '② 选预设后药丸已配置且 host/port 写入');

let q3 = 'missing';
try {
  const h = harness();
  const name = Object.keys(h.MAIL_PRESETS)[0];
  h.mailPreset.value = name;
  h.applyMail();
  const afterPreset = h.ready();
  h.pill.textContent = 'SENTINEL';
  h.pill.className = 'SENTINEL';
  h.mailPreset.value = '自定义';
  h.applyMail();
  q3 = h.pill.textContent === 'SENTINEL'
    && h.pill.className === 'SENTINEL'
    && h.mailCustom.classList.hide === false
    && h.ready() === afterPreset;
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, '③ 切回自定义档不刷预设药丸');

const from = src.indexOf('mailPreset.value =');
const findAt = from < 0 ? -1 : src.indexOf('.find(', from);
const to = findAt < 0 ? -1 : src.indexOf('|| CUSTOM', findAt);
const recognize = from >= 0 && findAt > from && to > findAt
  ? src.slice(from, to + '|| CUSTOM'.length)
  : '';
eq(recognize.length > 0, true, '找得到认预设');
eq(recognize.includes("'spring.mail.port'"), true, '认预设要比端口');

// ④⑤⑥ 药丸初值与接线：切段从草稿判的定义切到卡片入列前，桩里真跑
function loadPillUnknown() {
  const body = bracedFrom(src, 'function pillUnknown');
  if (!body) return () => {};
  return new Function(body + '\nreturn pillUnknown;')();
}

/**
 * 切出药丸接线段：从草稿判的定义起，到卡片入列那行前
 *
 * 两端的锚在「初值走草稿」的旧形态里也在：旧码有这些函数与行，只是最后一行
 * 初值写法不同——所以这段在旧形态里也能真跑，红的是行为差异，不是缺锚。
 */
function wiringFrom(text) {
  const start = text.indexOf('const mailReady = ');
  const end = start < 0 ? -1 : text.indexOf('wrap.appendChild(mail.card)', start);
  return start >= 0 && end > start ? text.slice(start, end) : '';
}

/**
 * 真跑接线段
 *
 * draftTookOver 以形参槽传进切段：段内对它的赋值与闭包读的是同一个形参，
 * 与产品码里那个 let 同效。mailPillFromStatus 也切真件——量的是接线，
 * 函数体本身的各条路径由④⑤⑥顺带各走一趟。
 */
function runWiring(wiring, pill, to, host, fromStatus) {
  new Function('pillState', 'mailAlertConfigured', 'to', 'host', 'mail',
    'mailPillFromStatus', 'draftTookOver', wiring)(
    loadPillState(), loadMailConfigured(), to, host, {pill}, fromStatus, false);
}

function loadMailPillFromStatus(api) {
  const body = bracedFrom(src, 'async function mailPillFromStatus');
  // 初值走草稿的旧形态没有这个函数：给个替身，走没走过由 asked 与药丸读数说话
  if (!body) return async () => { throw new Error('no mailPillFromStatus'); };
  return new Function('api', 'pillState', 'pillUnknown',
    body + '\nreturn mailPillFromStatus;')(api, loadPillState(), loadPillUnknown());
}

/** 敲键用的输入桩：记下监听器，好让测试自己拨一次 input */
function stubInput(value) {
  return {
    value,
    handlers: [],
    addEventListener(_type, fn) { this.handlers.push(fn); },
    input() { for (const fn of this.handlers) fn(); },
  };
}

// 切段里那趟 /status 是异步的：跳一个宏任务，微任务清空后读数才落定
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

// ④ 接线：初值必须经 /status——桩说未配置而两栏都填着，药丸仍「未配置」。
//    初值接回草稿判（mailReady()）时红在行为：药丸被写成「已配置」、/status 没被问
let q4 = 'missing';
try {
  const pill = {className: 'pill', textContent: '未配置'};
  let asked = '';
  const api = async path => { asked = path; return {alerts: {mail: false}}; };
  const wiring = wiringFrom(src);
  if (!wiring) throw new Error('no wiring');
  runWiring(wiring, pill, stubInput('a@example.invalid'), stubInput('smtp.example.invalid'),
    loadMailPillFromStatus(api));
  await settle();
  q4 = asked === '/status' && pill.textContent === '未配置'
    && !String(pill.className).includes('ok');
} catch (e) {
  q4 = 'error:' + e.message;
}
eq(q4, true, '④ 初值经 /status：桩说未配置而两栏都填着，药丸仍未配置');

// ⑤ /status 取不到：药丸置「未知」态，不退回草稿判——小字写着「以运行值为准」
let q5 = 'missing';
try {
  const pill = {className: 'pill', textContent: '未配置'};
  const api = async () => { throw new Error('status down'); };
  const wiring = wiringFrom(src);
  if (!wiring) throw new Error('no wiring');
  runWiring(wiring, pill, stubInput('a@example.invalid'), stubInput('smtp.example.invalid'),
    loadMailPillFromStatus(api));
  await settle();
  q5 = pill.textContent === '运行值未取到' && !String(pill.className).includes('ok');
} catch (e) {
  q5 = 'error:' + e.message;
}
eq(q5, true, '⑤ /status 取不到：药丸写「运行值未取到」，不退回草稿判');

// ⑥ status 在途时敲键：草稿回评先行接管，后到的运行值不覆盖那次回评
let q6 = 'missing';
try {
  const pill = {className: 'pill', textContent: '未配置'};
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const api = async () => { await gate; return {alerts: {mail: false}}; };
  const wiring = wiringFrom(src);
  if (!wiring) throw new Error('no wiring');
  const host = stubInput('smtp.example.invalid');
  runWiring(wiring, pill, stubInput('a@example.invalid'), host, loadMailPillFromStatus(api));
  host.input();
  release();
  await settle();
  q6 = pill.textContent === '已配置' && String(pill.className).includes('ok');
} catch (e) {
  q6 = 'error:' + e.message;
}
eq(q6, true, '⑥ status 在途敲键：草稿回评接管，后到的运行值不覆盖它');

/**
 * 最小元素桩：记下属性、子节点与 input/change 监听，供卡片建框后直接读
 */
function node(tag, cls) {
  return {
    tag, className: cls || '',
    textContent: '', type: '', value: '', placeholder: '',
    autocomplete: '', name: '',
    attrs: {}, kids: [], listeners: {}, style: {},
    classList: {toggle() {}, add() {}},
    setAttribute(key, value) {
      this.attrs[key] = String(value);
      if (key === 'autocomplete') this.autocomplete = String(value);
      if (key === 'name') this.name = String(value);
    },
    appendChild(child) { this.kids.push(child); return child; },
    append(...children) { this.kids.push(...children); },
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
  };
}

function collectInputs(root, acc) {
  const out = acc || [];
  if (root && root.tag === 'input') out.push(root);
  for (const child of (root && root.kids) || []) collectInputs(child, out);
  return out;
}

function loadFn(text, marker, params, args) {
  const body = bracedFrom(text, marker);
  if (!body) throw new Error('no ' + marker);
  return new Function(...params, body + '\nreturn ' + marker.slice(marker.indexOf(' ') + 1).replace(/\(.*/, '') + ';')(...args);
}

const settingsSrc = readFileSync(join(ui, 'settings.js'), 'utf8');

/** 有自己的 name，且不叫浏览器用来认登录框的那两个名字 */
function ownName(name) {
  return typeof name === 'string' && name.length > 0 && name !== 'username' && name !== 'password';
}

let accountAc = 'missing';
let codeAc = 'missing';
let codeType = 'missing';
let accountNamed = 'missing';
let codeNamed = 'missing';
let namesDiffer = 'missing';
let typedCode = 'missing';
try {
  const store = {values: {}, dirty: {}};
  const setValue = loadFn(src, 'function setValue(', ['store', 'markDirty', 'MASK', 'CLEAR', 'secretDraft'],
    [store, () => {}, MASK, CLEAR, secretDraft]);
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [node, () => '', setValue, () => {}, store, CLEAR]);
  const start = src.indexOf("field(mail.body, '发件账号'");
  const end = start < 0 ? -1 : src.indexOf('mail.body.appendChild(mailCustom)', start);
  const calls = start >= 0 && end > start ? src.slice(start, end) : '';
  if (!calls) throw new Error('no mail sender fields');
  const mail = {body: node('div')};
  new Function('field', 'mail', calls)(field, mail);
  const inputs = collectInputs(mail.body);
  const account = inputs.find(i => i.attrs['aria-label'] === '发件账号');
  const code = inputs.find(i => i.attrs['aria-label'] === '发件授权码');
  if (!account || !code) throw new Error('sender inputs missing');
  accountAc = account.autocomplete;
  codeAc = code.autocomplete;
  codeType = code.type;
  accountNamed = ownName(account.name);
  codeNamed = ownName(code.name);
  namesDiffer = account.name !== code.name;
  code.value = 'typed-by-hand';
  for (const fn of code.listeners.input || []) fn();
  typedCode = store.dirty['spring.mail.password'] === 'typed-by-hand';
} catch (e) {
  const err = 'error:' + e.message;
  accountAc = codeAc = codeType = accountNamed = codeNamed = namesDiffer = typedCode = err;
}
eq(codeAc, 'new-password', '发件授权码 autocomplete');
eq(accountAc, 'off', '发件账号 autocomplete');
eq(codeType, 'password', '发件授权码是口令框');
eq(accountNamed, true, '发件账号的 name 不叫 username 或 password');
eq(codeNamed, true, '发件授权码的 name 不叫 username 或 password');
eq(namesDiffer, true, '发件账号与发件授权码的 name 各不相同');
eq(typedCode, true, '自己敲进发件授权码仍记成改动');

let urlType = 'missing';
let urlAc = 'missing';
let urlNamed = 'missing';
let typedUrl = 'missing';
try {
  const store = {values: {}, dirty: {}};
  const setValue = loadFn(src, 'function setValue(', ['store', 'markDirty', 'MASK', 'CLEAR', 'secretDraft'],
    [store, () => {}, MASK, CLEAR, secretDraft]);
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [node, () => '', setValue, () => {}, store, CLEAR]);
  const start = src.indexOf("const url = field(hook.body, '地址'");
  const end = start < 0 ? -1 : src.indexOf('hook.body.appendChild(hookCustom)', start);
  const calls = start >= 0 && end > start ? src.slice(start, end) : '';
  if (!calls) throw new Error('no webhook address field');
  const hook = {body: node('div'), pill: node('div')};
  new Function('field', 'hook', 'pillState', 'hookPillState', calls)(field, hook, () => {}, () => {});
  const input = collectInputs(hook.body).find(i => i.attrs['aria-label'] === '地址');
  if (!input) throw new Error('address input missing');
  urlType = input.type;
  urlAc = input.autocomplete;
  urlNamed = ownName(input.name);
  input.value = 'https://api.day.app/another-key/';
  for (const fn of input.listeners.input || []) fn();
  typedUrl = store.dirty['novabot.core.alert.webhook-url'] === 'https://api.day.app/another-key/';
} catch (e) {
  const err = 'error:' + e.message;
  urlType = urlAc = urlNamed = typedUrl = err;
}
eq(urlType, 'password', '告警地址是口令框');
eq(urlAc, 'new-password', '告警地址 autocomplete');
eq(urlNamed, true, '告警地址的 name 不叫 username 或 password');
eq(typedUrl, true, '自己敲进新地址仍记成改动');

let secretType = 'missing';
let secretAc = 'missing';
try {
  const buildControl = loadFn(settingsSrc, 'function buildControl(', ['el', 'bindPasswordReveal'], [node, () => {}]);
  const input = buildControl({sensitive: true, label: '机密'}, '***', node('div'));
  if (!input) throw new Error('no secret input');
  secretType = input.type;
  secretAc = input.autocomplete;
} catch (e) {
  secretType = secretAc = 'error:' + e.message;
}
eq(secretType, 'password', '设置页机密框 type');
eq(secretAc, 'new-password', '设置页 type=password 的机密框 autocomplete');

// ---------- 机密项留空＝没动、清除显式说 ----------
// 病：告警卡「发件授权码」框写着「留空＝保持原值」，可删光遮点保存会把已存的授权码删掉。
// 于是留空改成没动，清掉改走「清除」钮送清除标记。

function loadSetValue(storeStub, markDirty) {
  return loadFn(src, 'function setValue(', ['store', 'markDirty', 'MASK', 'CLEAR', 'secretDraft'],
    [storeStub, markDirty, MASK, CLEAR, secretDraft]);
}

let blankKeeps = 'missing';
try {
  const storeStub = {values: {'spring.mail.password': MASK}, dirty: {}};
  loadSetValue(storeStub, () => {})('spring.mail.password', '');
  blankKeeps = storeStub.dirty['spring.mail.password'] === undefined;
} catch (e) {
  blankKeeps = 'error:' + e.message;
}
eq(blankKeeps, true, '⑦ 授权码删光遮点＝留空＝没动：改动账里不该出现这一项');

let blankSpaces = 'missing';
try {
  const storeStub = {values: {'spring.mail.password': MASK}, dirty: {}};
  loadSetValue(storeStub, () => {})('spring.mail.password', '   ');
  blankSpaces = storeStub.dirty['spring.mail.password'] === undefined;
} catch (e) {
  blankSpaces = 'error:' + e.message;
}
eq(blankSpaces, true, '⑧ 只剩空白同样是留空＝没动');

let ordinaryBlank = 'missing';
try {
  const storeStub = {values: {'novabot.core.mail.default-to': 'a@example.invalid'}, dirty: {}};
  loadSetValue(storeStub, () => {})('novabot.core.mail.default-to', '');
  ordinaryBlank = storeStub.dirty['novabot.core.mail.default-to'] === '';
} catch (e) {
  ordinaryBlank = 'error:' + e.message;
}
eq(ordinaryBlank, true, '⑨ 非机密项清空照旧记账，规矩没被顺手改掉');

let typedSecret = 'missing';
try {
  const storeStub = {values: {'spring.mail.password': MASK}, dirty: {}};
  loadSetValue(storeStub, () => {})('spring.mail.password', 'another-secret');
  typedSecret = storeStub.dirty['spring.mail.password'] === 'another-secret';
} catch (e) {
  typedSecret = 'error:' + e.message;
}
eq(typedSecret, true, '⑩ 自己敲进新授权码照常记成一笔改动');

let clearBtnExists = 'missing';
let clearBtnFirst = 'missing';
let clearBtnUndo = 'missing';
let clearInput, clearStore, clearBtn;
try {
  clearStore = {values: {'spring.mail.password': MASK}, dirty: {}};
  const setValue = loadSetValue(clearStore, () => {});
  const valueOf = name => clearStore.dirty[name] !== undefined
    ? clearStore.dirty[name] : (clearStore.values[name] || '');
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [node, valueOf, setValue, () => {}, clearStore, CLEAR]);
  const box = node('div');
  clearInput = field(box, '发件授权码', 'spring.mail.password', {type: 'password', ph: '留空＝保持原值'});
  const wrap = box.kids[0];
  clearBtn = wrap.kids.find(k => k.tag === 'button');
  clearBtnExists = clearBtn ? clearBtn.textContent : 'no button';
} catch (e) {
  clearBtnExists = 'error:' + e.message;
}
eq(clearBtnExists, '清除', '⑪a 已存值的机密项旁有「清除」钮');
if (clearBtn && clearInput) {
  for (const fn of clearBtn.listeners.click || []) fn();
  clearBtnFirst = [clearStore.dirty['spring.mail.password'], clearInput.disabled, clearInput.placeholder, clearBtn.textContent].join('|');
}
eq(clearBtnFirst, [CLEAR, true, '保存后清除', '撤回清除'].join('|'), '⑪b 点一下：记清除标记、框显示「保存后清除」');
if (clearBtn && clearInput) {
  for (const fn of clearBtn.listeners.click || []) fn();
  clearBtnUndo = [clearStore.dirty['spring.mail.password'] === undefined, clearInput.disabled, clearBtn.textContent].join('|');
}
eq(clearBtnUndo, [true, false, '清除'].join('|'), '⑪c 再点：撤回清除，回到没改');
let noClearWhenEmpty = 'missing';
try {
  const storeStub = {values: {'spring.mail.password': ''}, dirty: {}};
  const setValue = loadSetValue(storeStub, () => {});
  const valueOf = name => storeStub.dirty[name] !== undefined
    ? storeStub.dirty[name] : (storeStub.values[name] || '');
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [node, valueOf, setValue, () => {}, storeStub, CLEAR]);
  const box = node('div');
  field(box, '发件授权码', 'spring.mail.password', {type: 'password', ph: '留空＝保持原值'});
  const wrap = box.kids[0];
  noClearWhenEmpty = !wrap.kids.some(k => k.tag === 'button');
} catch (e) {
  noClearWhenEmpty = 'error:' + e.message;
}
eq(noClearWhenEmpty, true, '⑫ 本来没值的机密项不画「清除」钮：没得清');

// ⑬ 重画后仍是清除态，点一次即撤回
// 病：挂着清除还没保存时一重画（切走再回来），框里又显示那串清除标记、钮变回「清除」，
// 「保存后清除」不见了——看着像没动，一保存照样删键；要撤回还得连点两下
let repaintClear = 'missing';
try {
  const storeStub = {values: {'spring.mail.password': MASK}, dirty: {'spring.mail.password': CLEAR}};
  const setValue = loadSetValue(storeStub, () => {});
  const valueOf = name => storeStub.dirty[name] !== undefined
    ? storeStub.dirty[name] : (storeStub.values[name] || '');
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [node, valueOf, setValue, () => {}, storeStub, CLEAR]);
  const box = node('div');
  const input = field(box, '发件授权码', 'spring.mail.password', {type: 'password', ph: '留空＝保持原值'});
  const wrap = box.kids[0];
  const btn = wrap.kids.find(k => k.tag === 'button');
  const painted = [input.value, input.disabled, input.placeholder, btn ? btn.textContent : 'no button'].join('|');
  for (const fn of (btn && btn.listeners.click) || []) fn();
  const undone = [input.value, input.disabled, input.placeholder, btn.textContent,
    storeStub.dirty['spring.mail.password'] === undefined].join('|');
  repaintClear = painted + ' || ' + undone;
} catch (e) {
  repaintClear = 'error:' + e.message;
}
eq(repaintClear, ['', true, '保存后清除', '撤回清除'].join('|') + ' || '
  + [MASK, false, '留空＝保持原值', '清除', true].join('|'),
  '⑬ 重画后仍是清除态（框空且禁用、写着「保存后清除」、钮是「撤回清除」），点一次即撤回');

/** 带真 classList 的元素桩：看得见＝有字且没挂 hide */
function liveNode(tag, cls) {
  const n = node(tag, cls);
  const set = new Set(String(cls || '').split(/\s+/).filter(Boolean));
  n.classList = {
    toggle(name, force) {
      const on = force === undefined ? !set.has(name) : !!force;
      if (on) set.add(name); else set.delete(name);
    },
    add(...names) { for (const name of names) set.add(name); },
    contains(name) { return set.has(name); },
  };
  return n;
}

/** 看得见的字（不含悬停提示 title） */
function visible(root, out) {
  const acc = out || [];
  if (!root || root.classList.contains && root.classList.contains('hide')) return acc;
  if (root.textContent && !(root.kids && root.kids.length)) acc.push(root.textContent);
  for (const child of root.kids || []) visible(child, acc);
  return acc;
}

// ⑭ 清除钮那句话看得见
// 病：「保存后这一项就删掉了，要再用得重新填」只挂在悬停提示里，触屏上点了清除看不到，
// 不知道保存后会删。挂着清除时这句要作为字摆在这一栏里，撤回后收起
const CLEAR_NOTE = '保存后这一项就删掉了，要再用得重新填';
let alertNote = 'missing';
try {
  const storeStub = {values: {'spring.mail.password': MASK}, dirty: {}};
  const setValue = loadSetValue(storeStub, () => {});
  const valueOf = name => storeStub.dirty[name] !== undefined
    ? storeStub.dirty[name] : (storeStub.values[name] || '');
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [liveNode, valueOf, setValue, () => {}, storeStub, CLEAR]);
  const says = box => visible(box).includes(CLEAR_NOTE) ? 'note' : 'no note';
  const box = liveNode('div');
  field(box, '发件授权码', 'spring.mail.password', {type: 'password', ph: '留空＝保持原值'});
  const btn = box.kids[0].kids.find(k => k.tag === 'button');
  const before = says(box);
  for (const fn of (btn && btn.listeners.click) || []) fn();
  const clearing = says(box);
  const again = liveNode('div');
  field(again, '发件授权码', 'spring.mail.password', {type: 'password', ph: '留空＝保持原值'});
  const repainted = says(again);
  for (const fn of (btn && btn.listeners.click) || []) fn();
  alertNote = [before, clearing, repainted, says(box)].join('|');
} catch (e) {
  alertNote = 'error:' + e.message;
}
eq(alertNote, ['no note', 'note', 'note', 'no note'].join('|'),
  '⑭ 告警卡点清除后那句话是看得见的字，重画仍在，撤回即收起');

// ⑮ Webhook 药丸按「保存后会怎样」说
// 病：药丸只看框里有没有字。地址是机密项，留空＝保持原值，删光遮点时药丸跳「未配置」，
// 保存后地址却照旧留着；点清除时药丸又不动，看不出保存后就没了。
// 切的是地址框建起到卡片入列那一段（含初值那行）真跑，读的是药丸
const HOOK = 'novabot.core.alert.webhook-url';

function hookCard(values, dirty) {
  const storeStub = {values: Object.assign({}, values), dirty: Object.assign({}, dirty)};
  const setValue = loadSetValue(storeStub, () => {});
  const valueOf = name => storeStub.dirty[name] !== undefined
    ? storeStub.dirty[name] : (storeStub.values[name] || '');
  const field = loadFn(src, 'function field(', ['el', 'valueOf', 'setValue', 'markDirty', 'store', 'CLEAR'],
    [liveNode, valueOf, setValue, () => {}, storeStub, CLEAR]);
  const pillState = loadPillState();
  const hookBody = bracedFrom(src, 'function hookPillState(');
  const hookPillState = hookBody
    ? new Function('store', 'CLEAR', 'pillState', hookBody + '\nreturn hookPillState;')(storeStub, CLEAR, pillState)
    : () => { throw new Error('no hookPillState'); };
  const presets = new Function(bracedFrom(src, 'const WEBHOOK_PRESETS = ') + '\nreturn WEBHOOK_PRESETS;')();
  const start = src.indexOf("const url = field(hook.body, '地址'");
  const end = start < 0 ? -1 : src.indexOf('wrap.appendChild(hook.card)', start);
  if (start < 0 || end < start) throw new Error('no webhook wiring');
  const hook = {body: liveNode('div'), pill: {className: 'pill', textContent: '未配置'}};
  new Function('field', 'hook', 'pillState', 'hookPillState', 'hookCustom', 'preset', 'WEBHOOK_PRESETS',
    'CUSTOM', 'valueOf', 'setValue', src.slice(start, end))(
    field, hook, pillState, hookPillState, liveNode('div'), liveNode('select'), presets,
    '自定义', valueOf, setValue);
  const input = collectInputs(hook.body).find(i => i.attrs['aria-label'] === '地址');
  const btn = collect(hook.body, 'button').find(b => b.textContent === '清除' || b.textContent === '撤回清除');
  const read = () => hook.pill.textContent + (String(hook.pill.className).includes('ok') ? '+ok'
    : String(hook.pill.className).includes('warn') ? '+warn' : '');
  const type = text => {
    input.value = text;
    for (const fn of input.listeners.input || []) fn();
  };
  const click = () => { for (const fn of (btn && btn.listeners.click) || []) fn(); };
  return {read, type, click};
}

function collect(root, tag, out) {
  const acc = out || [];
  if (root && root.tag === tag) acc.push(root);
  for (const child of (root && root.kids) || []) collect(child, tag, acc);
  return acc;
}

let hookPill = 'missing';
try {
  const seen = [];
  const card = hookCard({[HOOK]: MASK}, {});
  seen.push('存着:' + card.read());
  card.type('');
  seen.push('删光遮点:' + card.read());
  card.type('https://api.day.app/another-key/');
  seen.push('填新地址:' + card.read());
  card.type('');
  card.click();
  seen.push('点清除:' + card.read());
  card.click();
  seen.push('撤回清除:' + card.read());
  seen.push('重画挂着清除:' + hookCard({[HOOK]: MASK}, {[HOOK]: CLEAR}).read());
  const none = hookCard({}, {});
  seen.push('没存过:' + none.read());
  none.type('https://api.day.app/new-key/');
  seen.push('没存过填了:' + none.read());
  hookPill = seen.join('|');
} catch (e) {
  hookPill = 'error:' + e.message;
}
eq(hookPill, ['存着:已配置+ok', '删光遮点:已配置+ok', '填新地址:已配置+ok', '点清除:保存后清除+warn',
  '撤回清除:已配置+ok', '重画挂着清除:保存后清除+warn', '没存过:未配置', '没存过填了:已配置+ok'].join('|'),
  '⑮ Webhook 药丸说保存后会怎样：删光遮点仍已配置，挂着清除写「保存后清除」，没存过才是未配置');
console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
