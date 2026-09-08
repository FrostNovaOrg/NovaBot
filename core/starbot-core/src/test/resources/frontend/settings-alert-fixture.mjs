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
 *
 * 由 SettingsAlertViewTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

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

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
