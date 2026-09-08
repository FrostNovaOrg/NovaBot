/**
 * 设置页视图模型的三档对照，外加配置文件路径复制口
 *
 * 搜索、只看改过、危险项围栏——这几件事在真机上凑齐一次的代价极高：
 * 「说明里含关键字但标题不含」靠人手点点不出来。视图模型因此被切成纯函数
 * （config-ui/settings-model.js，不碰 DOM），本文件喂它几份字段表条目对答案。
 *
 * 路径复制切 settings.js 的 renderConfigPath／copyConfigPath，按花括号配平截到块尾后真执行。
 *
 * 用 node 直接跑：
 *   node tools/settings-model-check.mjs
 * 退码 0 即各档全对；任一档对不上打印差异并以 1 退出。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {
  MASK, dangerOf, isDangerous, defaultText, isChanged, isVisible, effectOf,
} from '../core/starbot-core/src/main/resources/config-ui/settings-model.js';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

function field(over) {
  return Object.assign({
    name: 'novabot.core.push.enabled', label: 'enabled', widget: 'boolean',
    description: '全局推送开关', defaultValue: true, effect: 'IMMEDIATE', sensitive: false,
  }, over);
}

// —— 档一：默认值与改过 ——
eq(defaultText(field({widget: 'boolean', defaultValue: true})), '开', '布尔默认值显示成开');
eq(defaultText(field({widget: 'string', defaultValue: null})), '未设', '没有默认值的说未设');
eq(isChanged(field({widget: 'boolean', defaultValue: true}), 'true'), false, '与默认值相同不算改过');
eq(isChanged(field({widget: 'boolean', defaultValue: true}), 'false'), true, '与默认值不同算改过');
eq(isChanged(field({sensitive: true, widget: 'string', defaultValue: null}), MASK), true,
  '机密项配过就算改过');

// —— 档二：搜索与只看改过 ——
const quiet = field({
  name: 'novabot.core.push.quiet-start', label: 'quiet-start', widget: 'string',
  description: '静音时段开始，期间的推送直接丢弃', defaultValue: null,
});
eq(isVisible(quiet, '', '静音', false), true, '按说明里的词搜得到');
eq(isVisible(quiet, '', '词云', false), false, '搜不相干的词搜不到');
eq(isVisible(quiet, '', '静音', true), false, '只看改过时，没改过的即使搜中也不显示');
eq(isVisible(quiet, '23:00', '静音', true), true, '只看改过时，改过且搜中才显示');

// —— 档三：危险项与生效时机 ——
const listen = field({
  name: 'server.address', label: 'address', widget: 'string', defaultValue: '127.0.0.1',
  danger: {value: '0.0.0.0', title: '监听地址改成 0.0.0.0？', consequence: '会把接口暴露到网络上。'},
});
eq(isDangerous(listen), true, '带声明的是危险项');
eq(dangerOf(listen, '127.0.0.1'), null, '改到不危险的那一档不问');
eq(dangerOf(listen, '0.0.0.0').title, '监听地址改成 0.0.0.0？', '改到危险的那一档要先问一句');
eq(effectOf('IMMEDIATE'), {immediate: true, text: '立即生效'}, '标了即时的显示立即生效');
eq(effectOf(null), {immediate: false, text: '重启生效'}, '没标过的按需重启显示');

// —— 档四：路径未确定时不把占位文字送进剪贴板 ——
const settingsSrc = readFileSync(join(dirname(fileURLToPath(import.meta.url)),
  '../core/starbot-core/src/main/resources/config-ui/settings.js'), 'utf8');

/**
 * 从 marker 起，按花括号配平截到该块的闭合括号（含声明本身）
 *
 * 字符串与注释里的花括号不算层。截到块尾，不按「下一个 function」。
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

function loadPathFns(navigator, $, say) {
  const render = bracedFrom(settingsSrc, 'function renderConfigPath');
  const copy = bracedFrom(settingsSrc, 'async function copyConfigPath');
  if (!render || !copy) throw new Error('no function');
  return new Function('navigator', '$', 'say',
    render + '\n' + copy + '\nreturn {renderConfigPath, copyConfigPath};')(navigator, $, say);
}

async function runCopy(statusPath, opts) {
  const options = opts || {};
  const writes = [];
  const said = [];
  const node = {textContent: '', dataset: {}};
  const $ = () => node;
  const say = (text, kind) => { said.push({text, kind}); };
  const navigator = {
    clipboard: {
      writeText: async (text) => {
        writes.push(text);
        if (options.clipboardThrow) throw new Error('denied');
      }
    }
  };
  const fns = loadPathFns(navigator, $, say);
  fns.renderConfigPath(statusPath);
  if (options.poisonText !== undefined) node.textContent = options.poisonText;
  await fns.copyConfigPath();
  return {writes, said};
}

const COPY_HINT = '路径还没确定，没法复制';
const SAMPLE_PATH = '/opt/novabot/application.yml';

let q1 = 'missing';
try {
  const r = await runCopy(SAMPLE_PATH, {poisonText: '（未能确定）'});
  q1 = r.writes.length === 1 && r.writes[0] === SAMPLE_PATH
    && r.said.length === 1 && r.said[0].text === '已复制路径' && r.said[0].kind === 'ok';
} catch (e) {
  q1 = 'error:' + e.message;
}
eq(q1, true, '①有路径→writeText 1 次且入参＝该路径');

let q2 = 'missing';
try {
  const r = await runCopy(undefined, {poisonText: SAMPLE_PATH});
  q2 = r.writes.length === 0
    && r.said.length === 1 && r.said[0].text === COPY_HINT && r.said[0].kind === 'err';
} catch (e) {
  q2 = 'error:' + e.message;
}
eq(q2, true, '②未确定→writeText 零次且 say 出提示');

let q3 = 'missing';
try {
  const r = await runCopy(SAMPLE_PATH, {clipboardThrow: true});
  q3 = r.writes.length === 1 && r.writes[0] === SAMPLE_PATH
    && r.said.length === 1
    && r.said[0].text === '这个浏览器不让脚本写剪贴板，请手动复制：' + SAMPLE_PATH
    && r.said[0].kind === 'err';
} catch (e) {
  q3 = 'error:' + e.message;
}
eq(q3, true, '③剪贴板抛错→仍走原兜底分支');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
