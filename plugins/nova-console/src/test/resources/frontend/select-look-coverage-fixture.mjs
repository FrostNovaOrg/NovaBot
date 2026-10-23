/**
 * 没单写过样式的下拉框也有控制台那一副样子：圆角、边框、底色、左内边距、高度与设置页的文本框一致
 *
 * 用户那头的故障：通道页「通道规则」里的报告图配色、推送页默认模板顶上的报告图配色、设置页的枚举项
 * （例如扫码登录方式），显示成浏览器默认的直角灰框，文字贴左边，比旁边的框矮一截，换深色也不跟着变。
 * 全站从前只有一条下拉框规则，只管箭头与右侧留位，没被哪页单独写过的下拉框就落回浏览器默认。
 *
 * 本尺真跑推送页：push.js 的 render、默认模板页、通道页都是产品码那一份，两枚报告图配色下拉
 * 落在哪一层由它们自己摆。设置页的枚举项照 settings.js 的 buildRow 摆（.group.setgrp > .grp-body >
 * .field.setitem > 控件格 > select），旁边放一枚同一格里的文本框作对照。摆好之后把核心的 app.css
 * 与推送页自己那份样式按先后、权重叠一遍（只算静止状态），读出每枚下拉框最终生效的边框、圆角、底色、
 * 左内边距与最小高度：一项没有规则管就是浏览器默认，红；与设置页文本框那一副不同，也红。
 *
 * app.css 在核心那边，绝对路径由壳经环境变量 APP_CSS 递进来；递不到、读不出就红，不跳过。
 * 由 SelectChevronCoverageTest 拉起。
 */

import {readFileSync} from 'node:fs';
import {register} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {MiniNode, ancestry, cmp, installDom, rulesOf, selectorMatches, specificity} from './console-select-dom.mjs';

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

const {html, app, section} = installDom('page-push');

// —— 样式：只看这五项。简写照浏览器的规矩拆成分项，没写到的分项回到初始值 ——

const STYLES = new Set(['solid', 'dashed', 'dotted', 'double', 'none', 'hidden', 'groove', 'ridge', 'inset', 'outset']);

function tokens(value) {
  const out = [];
  let depth = 0;
  let buf = '';
  for (const c of value) {
    if (c === '(') depth++;
    if (c === ')') depth--;
    if (depth === 0 && /\s/.test(c)) {
      if (buf) out.push(buf);
      buf = '';
    } else {
      buf += c;
    }
  }
  if (buf) out.push(buf);
  return out;
}

function effectsOf(decls) {
  const out = [];
  for (const [prop, raw] of Object.entries(decls)) {
    const important = /!\s*important\s*$/i.test(raw);
    const value = raw.replace(/!\s*important\s*$/i, '').trim();
    const push = (name, v) => out.push({prop: name, value: v, important});
    if (prop === 'border') {
      let width = 'medium';
      let style = 'none';
      let color = 'currentcolor';
      for (const t of tokens(value)) {
        if (STYLES.has(t)) style = t;
        else if (/^(\d|\.|thin$|medium$|thick$)/.test(t)) width = t;
        else color = t;
      }
      push('边框', [width, style, color]);
    } else if (prop === 'border-width' || prop === 'border-style' || prop === 'border-color') {
      push(prop, value);
    } else if (prop === 'border-radius') {
      push('圆角', value);
    } else if (prop === 'background-color') {
      push('底色', value);
    } else if (prop === 'background') {
      const color = tokens(value).find(t => /^(var\(--(?!select-chevron)|#|rgba?\(|color-mix\(|transparent$)/.test(t));
      push('底色', color || 'transparent');
    } else if (prop === 'padding') {
      const v = tokens(value);
      push('左内边距', v[3] ?? v[1] ?? v[0]);
    } else if (prop === 'padding-left') {
      push('左内边距', value);
    } else if (prop === 'min-height' || prop === 'height') {
      push(prop === 'height' ? '高度' : '最小高度', value);
    }
  }
  return out;
}

const RULES = [];
rulesOf(coreCss, 'app.css', RULES);
rulesOf((pushSrc.match(/const PAGE_STYLE = `([\s\S]*?)`;/) || [])[1] || '', 'push.js', RULES);

/** 叠完之后的五项；边框由简写与三个分项按先后、权重叠出来 */
function look(node) {
  const best = {};
  for (const rule of RULES) {
    if (!selectorMatches(node, rule.selector)) continue;
    const spec = specificity(rule.selector);
    for (const eff of effectsOf(rule.decls)) {
      const rank = [eff.important ? 1 : 0, ...spec, rule.order];
      const parts = eff.prop === '边框' ? ['border-width', 'border-style', 'border-color'] : [eff.prop];
      parts.forEach((part, i) => {
        const value = eff.prop === '边框' ? eff.value[i] : eff.value;
        if (!best[part] || cmp(rank, best[part].rank) >= 0) best[part] = {rank, value};
      });
    }
  }
  const read = prop => (best[prop] ? best[prop].value : '(没有规则)');
  return {
    '边框': [read('border-width'), read('border-style'), read('border-color')].join(' '),
    '圆角': read('圆角'),
    '底色': read('底色'),
    '左内边距': read('左内边距'),
    '高度': best['高度'] ? best['高度'].value : read('最小高度'),
  };
}

// 设置页文本框那一副：没单写过样式的下拉框都该是它
const WANT = {
  '边框': '1px solid var(--line)', '圆角': 'var(--r-ctl)', '底色': 'var(--ground)', '左内边距': '9px', '高度': '36px',
};

// —— 设置页：照 settings.js 的 buildRow 摆一行枚举项、一行文本项 ——

const settings = app.appendChild(new MiniNode('section'));
settings.className = 'page';
settings.id = 'page-settings';
function settingsCell() {
  const group = settings.appendChild(new MiniNode('div'));
  group.className = 'group setgrp';
  const body = group.appendChild(new MiniNode('div'));
  body.className = 'grp-body';
  const row = body.appendChild(new MiniNode('div'));
  row.className = 'field setitem';
  return row.appendChild(new MiniNode('div'));
}
const enumSelect = settingsCell().appendChild(new MiniNode('select'));
const textInput = settingsCell().appendChild(new MiniNode('input'));
textInput.setAttribute('type', 'text');
eq(look(textInput), WANT, '〇 设置页的文本框（对照）是这一副');
eq(look(enumSelect), WANT, '① 设置页的枚举项（如扫码登录方式）与同列的文本框一副样子');

// —— 推送页：默认模板顶上的报告图配色、通道规则里的报告图配色 ——

const HANDLER = 'x.Report';
const presets = ['pink-blue', 'classic', 'warm'].map((id, i) => ({id, name: ['粉蓝', '经典', '暖阳'][i],
  colors: {background: '#123456', card: '#234567', text: '#345678', accent: '#456789'}}));
const user = {uid: 1, platform: 'bilibili', _uname: '主播甲', targets: [
  {platform: 'qq', type: 1, num: 11, messages: [{handler: HANDLER}]}]};
globalThis.window = {getSelection: () => null};
globalThis.URL.createObjectURL = () => 'blob:preview';
globalThis.URL.revokeObjectURL = () => {};
globalThis.fetch = async url => {
  let result = {success: true};
  if (url.endsWith('/report/palettes')) result = {success: true, presets, default: 'pink-blue'};
  else if (url.endsWith('/datasource/users/bilibili/1')) result = {success: true, user: structuredClone(user), version: 'v1'};
  else if (url.endsWith('/datasource/users')) result = {success: true, users: [structuredClone(user)]};
  return {status: 200, ok: true, json: async () => result, blob: async () => new Blob(['png'])};
};

const byLabel = label => html.querySelectorAll('select').find(node => node.getAttribute('aria-label') === label);
const pushPage = [
  ['② 推送页默认模板顶上的报告图配色', '默认报告图配色', 'default', ''],
  ['③ 通道页「通道规则」里的报告图配色', '通道报告图配色', '1', 'qq|1|11'],
];
try {
  const {store} = await import('./store.js');
  const push = await import(join(pages, 'push.js'));
  store.handlerList = [{className: HANDLER, displayName: '下播报告', aliases: [], placeholders: ['{report}'],
    attachments: [], factoryParams: {}, defaultParams: {}, reportLayout: true, options: []}];
  store.role = 'ADMIN';
  store.platforms = [];
  push.render(section);
  await push.loadPushPage();
  push.setPushEntries([user]);
  for (const [what, label, uid, channel] of pushPage) {
    await push.showPush(uid, channel);
    const select = byLabel(label);
    eq(select ? select.isConnected : '(页上没画出这枚下拉框)', true, what + ' 画在页上（' + (select ? ancestry(select) : '没找到') + '）');
    if (select) eq(look(select), WANT, what + ' 与设置页的文本框一副样子');
  }
} catch (err) {
  failures.push('载入 push.js、打开默认模板或通道页就抛了：' + err.stack);
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
