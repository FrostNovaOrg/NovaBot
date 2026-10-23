/**
 * 控制台的下拉框一律用自画的箭头，挂在 body 下的抽屉里那个也一样
 *
 * 用户那头的故障：装了两个直播平台，点「＋ 添加主播」，抽屉里挑平台的下拉框还是浏览器原生的箭头，
 * 和页上其余下拉框两个样。箭头那条规则从前只认 #app 里面的下拉框，而抽屉为了盖住整页挂在 body 上；
 * 抽屉自己那条 .dwform select{background:…} 又用简写把背景图冲掉了。
 *
 * 本尺真跑：假 DOM 会照字面解析 innerHTML，push.js 的 render、ensureDrawer、addStreamer 都是
 * 产品码那一份，抽屉挂在哪、下拉框落在哪一层都由它们自己摆。下拉框摆好之后，把核心的 app.css
 * 与推送页自己那份样式按先后、权重叠一遍（只算静止状态：:hover、:focus 这类不算命中），
 * 读出最终生效的背景图、appearance 与右侧留位，与页里的下拉框比：
 * ① 抽屉里挑平台的下拉框（挂在 body 下、不在 #app 里）；
 * ② 推送页窄屏用的那个下拉框（在 #app 里，对照）；
 * ③ 直接挂到 body 下的任一弹层里的下拉框（今天的确认框里没有下拉框，将来放进去也得一样）。
 *
 * app.css 在核心那边，绝对路径由壳经环境变量 APP_CSS 递进来；递不到、读不出就红，不跳过。
 * 由 SelectChevronCoverageTest 拉起。push.js 量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {
  MiniNode, ancestry, cmp, installDom, rulesOf, selectorMatches, specificity,
} from './console-select-dom.mjs';

// 插件页引宿主模块写的是 './core.js' 这样的相对名，node 上得先把它指回核心那一份
register(new URL('../../../../../../tools/alias-core-modules.mjs', import.meta.url));

const pages = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui-pages');
const pushSrc = readFileSync(join(pages, 'push.js'), 'utf8');

const coreCssPath = process.env.APP_CSS || '';
let coreCss;
try {
  coreCss = readFileSync(coreCssPath, 'utf8');
} catch {
  console.log('跑了 1 格，红 1 格');
  console.log('  红：核心的 app.css 取不到（环境变量 APP_CSS 指着 ' + (coreCssPath || '空') + '）');
  process.exit(1);
}

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

// —— 假 DOM：抽屉、页面骨架都是产品码自己写出来的那一份（见 console-select-dom.mjs）——

const {html, body, section} = installDom('page-push');

/** 一条声明落到这三项上各是什么；简写 background 会把背景图一并改掉，padding 简写会改右侧留位 */
function effectsOf(decls) {
  const out = [];
  const push = (prop, raw) => {
    const important = /!\s*important\s*$/i.test(raw);
    out.push({prop, value: raw.replace(/!\s*important\s*$/i, '').trim(), important});
  };
  for (const [prop, raw] of Object.entries(decls)) {
    if (prop === 'appearance' || prop === '-webkit-appearance') push(prop, raw);
    else if (prop === 'background-image') push('background-image', raw);
    else if (prop === 'background') {
      const m = /var\(--select-chevron\)|url\([^)]*\)/.exec(raw);
      push('background-image', (m ? m[0] : 'none') + (/!\s*important\s*$/i.test(raw) ? ' !important' : ''));
    } else if (prop === 'padding-right') push('padding-right', raw);
    else if (prop === 'padding') {
      const important = /!\s*important\s*$/i.test(raw);
      const vals = raw.replace(/!\s*important\s*$/i, '').trim().split(/\s+/);
      push('padding-right', (vals[1] ?? vals[0]) + (important ? ' !important' : ''));
    }
  }
  return out;
}

const RULES = [];
rulesOf(coreCss, 'app.css', RULES);
const pageStyle = (pushSrc.match(/const PAGE_STYLE = `([\s\S]*?)`;/) || [])[1] || '';
rulesOf(pageStyle, 'push.js', RULES);

/** 叠完之后这个下拉框上生效的三项，连带每一项赢下来的是哪条选择器 */
function computed(node) {
  const best = {};
  for (const rule of RULES) {
    if (!selectorMatches(node, rule.selector)) continue;
    const spec = specificity(rule.selector);
    for (const eff of effectsOf(rule.decls)) {
      const rank = [eff.important ? 1 : 0, ...spec, rule.order];
      const prev = best[eff.prop];
      if (!prev || cmp(rank, prev.rank) >= 0) best[eff.prop] = {rank, value: eff.value, selector: rule.selector};
    }
  }
  const read = prop => (best[prop] ? best[prop].value : '(没有规则)');
  return {
    '背景图': read('background-image'),
    'appearance': read('appearance'),
    '-webkit-appearance': read('-webkit-appearance'),
    '右侧留位': read('padding-right'),
    '箭头那条规则': best['background-image'] ? best['background-image'].selector : '(没有)',
  };
}


const WANT = {
  '背景图': 'var(--select-chevron)', 'appearance': 'none', '-webkit-appearance': 'none', '右侧留位': '34px',
};

function look(node) {
  const got = computed(node);
  const rule = got['箭头那条规则'];
  delete got['箭头那条规则'];
  return {got, rule};
}

// —— 载入产品码，照用户那样点「＋ 添加主播」 ——

let push = null;
let store = null;
try {
  push = await import(join(pages, 'push.js'));
  store = (await import('./store.js')).store;
  push.render(section);
  store.platforms = ['bilibili', 'douyin'];
  push.addStreamer();
} catch (err) {
  failures.push('载入 push.js、建页或打开添加主播就抛了：' + err.message);
}

const rules = {};

// ① 故障本身：抽屉挂在 body 上，挑平台的下拉框不在 #app 里，箭头规则够不着它，抽屉自己的背景简写又冲掉了背景图
const drawerSelect = html.querySelector('#add-platform');
eq(drawerSelect ? ancestry(drawerSelect).includes('div#app') : '(抽屉里没画出挑平台的下拉框)', false,
  '① 抽屉里挑平台的下拉框挂在 #app 外面（' + (drawerSelect ? ancestry(drawerSelect) : '没找到') + '）');
if (drawerSelect) {
  const {got, rule} = look(drawerSelect);
  rules['①'] = rule;
  eq(got, WANT, '① 「添加主播」抽屉里挑平台的下拉框用自画箭头、右侧给箭头留位');
}

// ② 对照：推送页里的下拉框（在 #app 里）
const navSelect = html.querySelector('#push-nav');
eq(navSelect ? ancestry(navSelect).includes('div#app') : '(推送页没画出下拉框)', true, '② 推送页的下拉框在 #app 里');
if (navSelect) {
  const {got, rule} = look(navSelect);
  rules['②'] = rule;
  eq(got, WANT, '② 推送页里的下拉框用自画箭头、右侧给箭头留位');
}

// ③ 直接挂到 body 下的任一弹层：今天的确认框里没有下拉框，将来放进去也得一样
const layer = body.appendChild(new MiniNode('div'));
layer.className = 'ask';
const loose = layer.appendChild(new MiniNode('select'));
{
  const {got, rule} = look(loose);
  rules['③'] = rule;
  eq(got, WANT, '③ 挂在 body 下的弹层里的下拉框用自画箭头、右侧给箭头留位');
}

// 三处是同一条箭头规则，不是各配各的
eq(new Set(Object.values(rules)).size, 1, '三处的箭头出自同一条规则（' + JSON.stringify(rules) + '）');

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
