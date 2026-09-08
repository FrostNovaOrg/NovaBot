/**
 * 邮件告警卡：选预设后药丸要跟着变；认预设要把端口算进去；初值问运行值
 *
 * 程序给输入框赋值不触发 input，药丸若只听 input 就会停在「未配置」。
 * 只按服务器名认预设时，端口对不上也会把自定义栏藏起来，465 和 587 从界面上看不见。
 * 切段按花括号配平截到块尾后真执行，不是只 includes。
 * 药丸初值答的是「此刻真的能发吗」：/api/status 说不配而文件里两栏都填着
 * （spring.mail.host 可以被环境变量越过文件），初值仍要写「未配置」。
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

/**
 * 从 marker 起，取到本行行尾（含行尾分号）
 *
 * 表达式体箭头（const x = () => ...;）没有花括号，配平切段会越过本行、
 * 落到后面无关的块上切出非法段；旧形态的初值函数正是这种单行箭头。
 */
function lineFrom(text, marker) {
  const start = text.indexOf(marker);
  if (start < 0) return '';
  const nl = text.indexOf('\n', start);
  return text.slice(start, nl < 0 ? text.length : nl);
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

// ④ 药丸初值问运行值：status 说不配而文件里两栏都填着，初值仍写「未配置」。
//    新形态切段真执行（status 那趟成功时不许落回草稿判法）；改前形态（初值由草稿写）
//    落到 else 支真跑旧初值：两栏填好时药丸被写成「已配置」，这一格红——
//    红的是旧行为本身，不是切段失败
let q4 = 'missing';
try {
  const pill = {className: 'pill', textContent: '未配置'};
  const to = {value: 'a@example.invalid'};
  const host = {value: 'smtp.example.invalid'};
  const state = loadPillState();
  const configured = loadMailConfigured();
  let asked = '';
  let fallbackCalls = 0;
  const api = async path => { asked = path; return {alerts: {mail: false}}; };
  const fromDraft = () => { fallbackCalls++; state(pill, configured(to.value, host.value)); };
  const runtime = bracedFrom(src, 'async function mailPillFromStatus');
  if (runtime) {
    const mailPillFromStatus = new Function('api', 'pillState',
      runtime + '\nreturn mailPillFromStatus;')(api, state);
    await mailPillFromStatus(pill, fromDraft);
    q4 = asked === '/status' && fallbackCalls === 0
      && pill.textContent === '未配置' && !String(pill.className).includes('ok');
  } else {
    const line = lineFrom(src, 'const mailReady = ');
    if (!line) throw new Error('no mailReady');
    const mailReady = new Function('pillState', 'mailAlertConfigured', 'to', 'host', 'mail',
      line + '\nreturn mailReady;')(state, configured, to, host, {pill});
    mailReady();
    q4 = pill.textContent === '未配置' && !String(pill.className).includes('ok');
  }
} catch (e) {
  q4 = 'error:' + e.message;
}
eq(q4, true, '④ status 说不配而文件里 host 已填，药丸初值仍显示未配置');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
