/**
 * 推送页左树：群名后面的小标不许被挤成一字一行的竖条
 *
 * 左栏宽 236px 时，一颗群名带两颗小标（「N 类关着」「N 条命令被关」）放不下同一行。
 * 从前那一组小标封顶 96px，两颗挤进去，字被压成一两个字一行竖着排，读不出来。
 * 这里按字面宽把左树各行算一遍，量的是「一颗小标一行字、放不下整组换到名字下面」
 * 这几条规矩在样式里成不成立，顺带算出演示那两颗换行后并排放不放得下。
 *
 * 一颗字宽取 -apple-system（SF）与苹方在 12.5px 下的字面宽：中文字宽 = 字号，
 * 数字与空格按 SF 量出来的数。小标描边 1px 在核心的 app.css 里，这里一并读。
 * 由 PushTreeMarksTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ui = join(here, '../../../main/resources/config-ui-pages');
const src = readFileSync(join(ui, 'push.js'), 'utf8');
const coreCss = readFileSync(
  join(here, '../../../../../../core/nova-core/src/main/resources/config-ui/app.css'), 'utf8');

const failures = [];
let checks = 0;

function ok(cond, what) {
  checks++;
  if (!cond) failures.push(what);
}

function eq(actual, expected, what) {
  checks++;
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) failures.push(what + '：得到 ' + a + '，应为 ' + b);
}

const style = (src.match(/const PAGE_STYLE = `([\s\S]*?)`;/) || [])[1] || '';

function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '');
}

function decls(block) {
  const out = {};
  for (const part of stripComments(block).split(';')) {
    const i = part.indexOf(':');
    if (i < 0) continue;
    out[part.slice(0, i).trim().toLowerCase()] = part.slice(i + 1).trim();
  }
  return out;
}

function rule(css, selector) {
  const at = css.indexOf(selector + '{');
  if (at < 0) return {};
  const open = css.indexOf('{', at);
  const end = css.indexOf('}', open);
  return end < 0 ? {} : decls(css.slice(open + 1, end));
}

function px(value) {
  const m = String(value || '').match(/^(-?[\d.]+)(px)?$/);
  return m ? Number(m[1]) : null;
}

function num(value) {
  const m = String(value || '').match(/^(-?[\d.]+)(em|px)?$/);
  return m ? {n: Number(m[1]), unit: m[2] || ''} : null;
}

// ---- 一颗小标的字面宽（12.5px 字号下的 px 数）----
const DIGIT = {'0': 7.6, '1': 5.6, '2': 7.1, '3': 7.4, '4': 7.5, '5': 7.3, '6': 7.8, '7': 6.8, '8': 7.4, '9': 7.7};
const SPACE = 2.6;
const CJK = 12.5;

function textWidth(text) {
  let w = 0;
  for (const ch of text) {
    w += ch === ' ' ? SPACE : (DIGIT[ch] !== undefined ? DIGIT[ch] : CJK);
  }
  return w;
}

const basePill = rule(coreCss, '.nv-pill');
const treePill = rule(style, '.tn-marks .nv-pill');
const padX = px((treePill['padding'] || basePill['padding'] || '').split(/\s+/)[1])
  ?? px((basePill['padding'] || '').split(/\s+/)[1]);
const border = px((basePill['border'] || '').split(/\s+/)[0]);

function pillWidth(text) {
  return textWidth(text) + 2 * (padX ?? 0) + 2 * (border ?? 0);
}

// ---- 左树各行的可用宽（px）----
const wrap = rule(style, '.pushwrap');
const ptree = rule(style, '.ptree');
const tnode = rule(style, '.tnode');
const tchan = rule(style, '.tnode.tchan');
const ico = rule(style, '.tnode .tn-ico');
const caret = rule(style, '.tnode .caret');
const body = rule(style, '.tnode .tn-body');
const nm = rule(style, '.tnode .tn-nm');
const marks = rule(style, '.tnode .tn-marks');

const col = px(((wrap['grid-template-columns'] || '').match(/^(\d+px)/) || [])[1]);
const ptreeIn = col - 2 * px((ptree['border'] || '0').split(/\s+/)[0]) - 2 * px((ptree['padding'] || '0').split(/\s+/)[0]);
const tnodePad = (tnode['padding'] || '').split(/\s+/).map(px);
const tchanPadLeft = px(tchan['padding-left'] ?? tnodePad[3]) ?? tnodePad[1];
const tchanPadRight = tnodePad[1];
const rowGap = px((tnode['gap'] || '').split(/\s+/)[0]);
const bodyGap = px((body['gap'] || '').split(/\s+/)[0]);
const marksGap = px((marks['gap'] || '').split(/\s+/)[0]);

const chanLine = ptreeIn - tchanPadLeft - tchanPadRight - px(ico['width']) - rowGap;
const streamerLine = ptreeIn - 2 * tnodePad[1] - px(caret['width']) - rowGap;

// ---- ① 一颗小标一行字：不断行、不裁半颗 ----
{
  const nowrap = treePill['white-space'] ?? marks['white-space'];
  eq(nowrap, 'nowrap', '① 小标里的字要 nowrap，不许折成一字一行');
  eq(treePill['flex'], 'none', '① 小标要 flex:none，不许被压窄');
  ok(!px(marks['max-width']), '① 小标那组不许再封顶限宽（原先 96px 就是把字压成竖条的那一刀）');
  ok((marks['overflow'] || '') !== 'hidden', '① 小标那组不许 overflow:hidden 裁掉半颗');
}

// ---- ② 放不下时整组换到名字下面一行，左边与名字对齐 ----
{
  eq(body['display'], 'flex', '② 名字与小标要装在同一个行盒里');
  eq(body['flex-wrap'] ?? (tnode['flex-wrap']), 'wrap', '② 那个行盒要能换行，放不下才整组下去');
  const streamerFn = (src.match(/function streamerNode[\s\S]*?\n}/) || [])[0] || '';
  const channelFn = (src.match(/function channelNode[\s\S]*?\n}/) || [])[0] || '';
  for (const [name, fn] of [['主播行', streamerFn], ['群行', channelFn]]) {
    ok(fn.includes('tn-body'), name + ' 要有 tn-body 那层');
    ok(fn.includes('.appendChild(name)') && fn.includes('.appendChild(marks)'),
      name + ' 的名字与小标都装进 tn-body，换行后才与名字左对齐');
    ok(!fn.includes('node.appendChild(name)') && !fn.includes('node.appendChild(marks)'),
      name + ' 的名字与小标不许直接挂在行首那一层');
  }
}

// ---- ③ 群名截断也留四五个字 ----
{
  eq(nm['text-overflow'], 'ellipsis', '③ 群名放不下时用省略号截断');
  eq(nm['white-space'], 'nowrap', '③ 群名一行排完，不许折行');
  const min = num(nm['min-width']);
  const fontPx = px(tchan['font-size'] ?? tnode['font-size']);
  ok(min && min.unit === 'em' && min.n >= 4.5,
    '③ 群名 min-width 至少 4.5em（四五个字），得到 ' + JSON.stringify(nm['min-width']));
  ok(min && fontPx && min.n * fontPx >= 4.5 * fontPx, '③ 群名最小宽按字算够四五个字');
}

// ---- ④ 演示那两颗小标换行后并排放得下 ----
{
  const a = pillWidth('2 类关着');
  const b = pillWidth('1 条命令被关');
  const need = a + (marksGap ?? 0) + b;
  ok(need <= chanLine,
    '④ 「2 类关着」+「1 条命令被关」并排要放得下群名下面那一行：要 ' + need.toFixed(1)
    + 'px，那一行 ' + chanLine.toFixed(1) + 'px');
  // 同一行摆下名字（四五个字）+ 两颗的，才是没换行的误判
  const nameMin = (num(nm['min-width'])?.n ?? 5) * px(tchan['font-size'] ?? 13.5);
  ok(nameMin + (bodyGap ?? 0) + need > chanLine,
    '④ 演示那一行本来就该换行：名字' + nameMin.toFixed(1) + 'px 加两颗 ' + need.toFixed(1)
    + 'px 超过 ' + chanLine.toFixed(1) + 'px');
}

// ---- ⑤ 主播行最长那颗「只采集，不推送」照同一规矩 ----
{
  const one = pillWidth('只采集，不推送');
  ok(one <= streamerLine, '⑤ 「只采集，不推送」自己一行要放得下：要 ' + one.toFixed(1)
    + 'px，主播行 ' + streamerLine.toFixed(1) + 'px');
  const nameMin = (num(nm['min-width'])?.n ?? 5) * px(tnode['font-size'] ?? 14);
  const sameLine = nameMin + (bodyGap ?? 0) + one;
  if (sameLine <= streamerLine) {
    ok(true, '⑤ 与五字名同行放得下，走同行');
  } else {
    ok(one <= streamerLine, '⑤ 与五字名同行放不下就整组换行，换行后那行放得下');
  }
  const short = pillWidth('已停用');
  ok(nameMin + (bodyGap ?? 0) + short <= streamerLine,
    '⑤ 「已停用」要能和群名同一行：要 ' + (nameMin + (bodyGap ?? 0) + short).toFixed(1)
    + 'px，主播行 ' + streamerLine.toFixed(1) + 'px');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
