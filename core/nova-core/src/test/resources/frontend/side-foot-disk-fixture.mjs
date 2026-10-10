/**
 * 侧栏底部两枚按钮（使用说明、GitHub 源码）与运行栏「磁盘剩余」那一行
 *
 * 量两件事：
 * ① 侧栏底部——「使用说明」一直是一行裸文字，与上面带图标的导航对不齐；版本位
 *    只是几个字，点不动。现在两枚都长成按钮：有边框、有图标、悬停变色、点得动
 *    （使用说明去手册网站，版本位那枚去源码仓库）。源码地址只能出自 SOURCE_URL
 *    那一份，界面里再抄一份的话，改仓库地址那天屏幕上不会有任何提示。
 * ② 磁盘剩余那一行——本格要抓的毛病是：磁盘快满时运行一栏照样一片正常色，
 *    使用者看不出来。数据与日志都写在数据目录那块盘上，写不进去的那一刻
 *    推送记录和报告才开始丢，那时已经晚了。所以档位不是 ok 时那一行必须
 *    变色，并带一句悬停说明。
 *
 * 量法是真跑：renderDiskRow 从 overview.js 里切出来，喂假元素与捏造的回包执行，
 * 读它写上去的类名与提示。只 includes 源码文本的话，「写了」与「画出来了」分不开。
 *
 * 各问自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 SideFootDiskTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const read = name => readFileSync(join(ui, name), 'utf8');

const failures = [];
let checks = 0;

function ask(what, fn) {
  checks++;
  try {
    fn();
  } catch (err) {
    failures.push(what + '：' + (err && err.message ? err.message : String(err)));
  }
}

function want(actual, expected, what) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) throw new Error(what + ' 得到 ' + a + '，应为 ' + b);
}

function has(actual, needle, what) {
  if (!String(actual).includes(needle)) {
    throw new Error(what + '：缺少 ' + JSON.stringify(needle) + '，得到 ' + JSON.stringify(String(actual).slice(0, 400)));
  }
}

/**
 * 从声明处按花括号配平截到块尾（含声明本身）。字符串与注释里的花括号不算层
 */
function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

/** 取一个按 id 找得到的开标签元素（含内部，不含配对闭标签之后的） */
function anchorOf(html, id) {
  const at = html.indexOf('id="' + id + '"');
  if (at < 0) throw new Error('index.html 里没有 #' + id);
  const open = html.lastIndexOf('<a ', at);
  const close = html.indexOf('</a>', at);
  if (open < 0 || close < 0) throw new Error('#' + id + ' 不是一枚 <a>');
  return html.slice(open, close);
}

const html = read('index.html');
const overview = read('overview.js');
const css = read('app.css');

ask('① 使用说明是一枚带图标的按钮：图标、悬停说明与外链属性都在', () => {
  const manual = anchorOf(html, 'side-manual');
  has(manual, '<svg', '使用说明的图标');
  has(manual, 'stroke-width="1.4"', '图标笔触与侧栏导航同一副');
  has(manual, '使用说明', '按钮文字');
  has(manual, 'title="打开使用说明（手册网站）"', '悬停说明');
  has(manual, 'aria-label="打开使用说明（手册网站）"', '读屏说明');
  has(manual, 'target="_blank"', '新开一页');
  has(manual, 'noopener noreferrer', '外链不带本页窗口与来源');
  has(manual, 'referrerpolicy="no-referrer"', '来源策略');
});

ask('② 版本位连同 GitHub 标是一枚按钮：图标在内、地址不写死在页面里', () => {
  const source = anchorOf(html, 'side-source');
  has(source, '<svg', 'GitHub 标');
  has(source, 'id="side-version"', '版本文字在按钮里');
  has(source, 'title="在 GitHub 上查看源码"', '悬停说明');
  has(source, 'aria-label="在 GitHub 上查看源码"', '读屏说明');
  has(source, 'target="_blank"', '新开一页');
  has(source, 'noopener noreferrer', '外链不带本页窗口与来源');
  has(source, 'referrerpolicy="no-referrer"', '来源策略');
  want(anchorOf(html, 'side-source').includes('href='), false, '地址由脚本给，页面不写死');
  want(html.includes('github.com/FrostNovaOrg'), false, '仓库地址不进界面文件，只进脚本那一份');
});

ask('③ 两枚按钮收在侧栏底部同一格里，换版药丸仍挨着版本位', () => {
  has(html, '<div class="side-foot">', '侧栏底部那一格');
  const footAt = html.indexOf('<div class="side-foot">');
  const manualAt = html.indexOf('id="side-manual"');
  const sourceAt = html.indexOf('id="side-source"');
  want(manualAt > footAt, true, '使用说明在侧栏底部那格里');
  want(sourceAt > footAt, true, '版本按钮在侧栏底部那格里');
  const verAt = html.indexOf('<div class="verline">');
  want(verAt > footAt, true, '版本那一行在侧栏底部那格里');
  want(sourceAt > verAt, true, '版本按钮在版本那一行上');
  want(html.indexOf('id="side-update"') > verAt, true, '新版药丸仍与版本位同一行');
  want(html.indexOf('id="update-pop"') > verAt, true, '药丸的小面板仍锚在那一行上');
});

ask('④ 源码地址只有 SOURCE_URL 一份，版本按钮的 href 从它取', () => {
  const url = 'https://github.com/FrostNovaOrg/NovaBot';
  want(overview.split(url).length - 1, 1, 'overview.js 里仓库地址出现次数');
  has(overview, "const SOURCE_URL = '" + url + "'", 'SOURCE_URL 那一份');
  has(overview, 'side-source', '脚本认得版本按钮');
  has(overview, 'href = SOURCE_URL', '按钮地址取自 SOURCE_URL，不另抄');
  has(overview, "$('#side-version').textContent", '版本文字仍写进按钮里');
});

ask('⑤ 运行栏有「磁盘剩余」那一行', () => {
  has(html, '磁盘剩余', '行标签');
  has(html, 'id="run-disk"', '那一格的值位');
  has(overview, "renderDiskRow($('#run-disk'), runtime)", '运行状态一到就画那一行');
});

ask('⑥ 两枚按钮有边框、悬停变色；磁盘那格变色用的类在样式里', () => {
  const link = css.slice(css.indexOf('.side-link{'), css.indexOf('.side-link{') + 400);
  has(link, 'border:', '按钮有边框');
  has(css, '.side-link:hover', '悬停变色');
  has(css, '.side-foot{', '侧栏底部那一格的样式');
  has(css, '.rb-r b.warn', '黄档的颜色');
  has(css, '.rb-r b.err', '红档的颜色');
});

// —— 真跑 renderDiskRow：喂假元素与捏造的回包，读它写上去的内容 ——

const fnSrc = bracedFrom(overview, 'function renderDiskRow(');
const renderDiskRow = fnSrc
  ? new Function(fnSrc + '\nreturn renderDiskRow;')()
  : null;

function draw(runtime) {
  const el = { textContent: '', className: '', title: '' };
  renderDiskRow(el, runtime);
  return el;
}

ask('⑦ 那一行画得出数字：满 1GB 记到小数一位，不足记 MB', () => {
  want(renderDiskRow !== null, true, 'overview.js 里切得出 renderDiskRow');
  want(draw({ diskFreeMb: 12800, diskLevel: 'ok' }).textContent, '12.5 GB', '12.8 万 MB 记成 GB');
  want(draw({ diskFreeMb: 1024, diskLevel: 'ok' }).textContent, '1.0 GB', '整 1GB 也进 GB 档');
  want(draw({ diskFreeMb: 850, diskLevel: 'warn' }).textContent, '850 MB', '不足 1GB 记 MB');
  want(draw({ diskFreeMb: 1023, diskLevel: 'warn' }).textContent, '1023 MB', '差 1MB 也不冒进 GB 档');
});

ask('⑧ 磁盘快满时那一行变色并带说明——本格要抓的毛病', () => {
  const warn = draw({ diskFreeMb: 1300, diskLevel: 'warn' });
  want(warn.className, 'warn', '黄档的类名');
  want(warn.title.includes('磁盘'), true, '黄档要带一句为什么的说明');
  const err = draw({ diskFreeMb: 120, diskLevel: 'err' });
  want(err.className, 'err', '红档的类名');
  want(err.title.includes('磁盘'), true, '红档要带一句为什么的说明');
  const ok = draw({ diskFreeMb: 12800, diskLevel: 'ok' });
  want(ok.className, '', '宽裕时不着色');
  want(ok.title, '', '宽裕时不带说明');
});

ask('⑨ 量不到时那一格是「—」，不是 0 也不是编出来的数', () => {
  const none = draw({});
  want(none.textContent, '—', '回包里没有这两个键时');
  want(none.className, '', '不着色');
  want(none.title, '', '不带说明');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
