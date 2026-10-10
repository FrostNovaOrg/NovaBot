/**
 * 连接页机器人表单：测试连接还没回来时改了参数，旧结果不得放行改过的参数
 *
 * 用户故障：点了测试连接，回包前把地址改错；旧的成功结果一回来就解开保存，
 * 点下去存的是没测过的错地址。
 * 整个 bot.js 去掉 import 后真执行：点「测试连接」、改格子、让接口回包都真走一遍，
 * 量保存按钮的状态与提示。对照：回包前没改，测通照旧解开保存，存的是测过的那一套。
 *
 * 由 BotFormStaleResultTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const src = readFileSync(join(ui, 'bot.js'), 'utf8');

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function fakeDom() {
  const byId = {};
  function el() {
    return {
      disabled: false,
      value: '',
      textContent: '',
      className: '',
      listeners: {},
      addEventListener(type, fn) {
        if (!this.listeners[type]) this.listeners[type] = [];
        this.listeners[type].push(fn);
      }
    };
  }
  function $(sel) {
    const id = String(sel).replace(/^#/, '');
    if (!byId[id]) byId[id] = el();
    return byId[id];
  }
  return {$, byId};
}

/** 接口桩：测试请求挂着，等手动回包；保存请求记下来并当场回成功 */
function fakeApi() {
  const calls = [];
  let release = null;
  async function api(path, opts) {
    calls.push({path, body: opts && opts.body ? JSON.parse(opts.body) : null});
    if (path === '/setup/test-bot') {
      return new Promise(resolve => { release = resolve; });
    }
    return {success: true, message: '已保存'};
  }
  return {api, calls, answer: res => release(res)};
}

/** 去掉 import 与 export 后整份执行，拿到绑定好的一张表单 */
function loadForm() {
  const body = src.replace(/^import .*$/gm, '').replace(/^export /gm, '');
  const {$, byId} = fakeDom();
  const fake = fakeApi();
  const mod = new Function('$', 'api', 'term', body + '\nreturn {bindBotForm};')(
    $, fake.api, (key, fallback) => fallback);
  ['addr', 'hport', 'htoken', 'wport', 'wtoken', 'test', 'save', 'out'].forEach(k => $('#bot-' + k));
  byId['bot-addr'].value = '10.0.0.1';
  byId['bot-hport'].value = '3000';
  byId['bot-wport'].value = '3001';
  byId['bot-save'].disabled = true;
  mod.bindBotForm('bot');
  return {byId, fake};
}

function click(node) {
  return Promise.all((node.listeners.click || []).map(fn => fn()));
}

function type(node, value) {
  node.value = value;
  (node.listeners.input || []).forEach(fn => fn());
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

// ① 回包前改了地址，旧的成功回包不得解开保存，提示仍要求重测
let stale = 'missing';
try {
  const {byId, fake} = loadForm();
  const pending = click(byId['bot-test']);
  await flush();
  type(byId['bot-addr'], '10.0.0.99');
  fake.answer({success: true, message: '连接正常', advice: ''});
  await pending;
  stale = {
    saveDisabled: byId['bot-save'].disabled,
    out: byId['bot-out'].textContent,
    tested: fake.calls.filter(c => c.path === '/setup/test-bot').map(c => c.body.address),
  };
} catch (e) {
  stale = 'error:' + e.message;
}
eq(stale, {saveDisabled: true, out: '参数改了，请重新测试连接', tested: ['10.0.0.1']},
  '① 测试挂着时改地址、再回成功：保存仍禁用，提示仍是重新测试');

// ② 对照：回包前没动，测通照旧解开保存，点保存发出去的是测过的那一套
let fresh = 'missing';
try {
  const {byId, fake} = loadForm();
  const pending = click(byId['bot-test']);
  await flush();
  fake.answer({success: true, message: '连接正常', advice: ''});
  await pending;
  const saveDisabled = byId['bot-save'].disabled;
  await click(byId['bot-save']);
  fresh = {
    saveDisabled,
    out: byId['bot-out'].textContent,
    saved: fake.calls.filter(c => c.path === '/setup/bot').map(c => c.body.address),
  };
} catch (e) {
  fresh = 'error:' + e.message;
}
eq(fresh, {saveDisabled: false, out: '已保存', saved: ['10.0.0.1']},
  '② 对照：回包前没改，测通解开保存，存的是测过的地址');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
