/**
 * 侧栏入口的图标：插件带来的那几条也得有，且与内置四条同一副外壳
 *
 * 内置四条（首页／日志／连接／设置）的图标写死在 index.html 的 <nav> 里；推送与主播两页
 * 搬进插件之后改由 mountTopPage 建入口，而它只写文字——于是这两条自搬家之日起就是光秃秃的。
 * 补法是 SPI 出一项 icon()，插件只给壳里的形状，外壳由核心统一套上。
 *
 * 🔴 元素与属性的白名单不在这一侧，在服务端登记那一处（ConsolePages.icon）：
 *    不合规的在那里就退成空串，日志里点名。这里量的是「拿到什么就画成什么样」。
 *
 * 量法是真跑：从 main.js 里切出图标那一段与 mountTopPage 本身，喂一副假 DOM 执行，
 * 读它建出来的那条 <a> 的内容。只 includes 源码文本的话，「写了」与「画出来了」分不开。
 *
 * 各问自己 try/catch，末尾汇总红格数，不靠 assert 短路。
 * 由 NavIconTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
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

const main = read('main.js');
const mountSrc = bracedFrom(main, 'function mountTopPage(');

// 图标那一段整段切走：外壳、缺省图标与画图标那个函数摆在 mountTopPage 之前，
// 因此「从第一个常量到 mountTopPage 的声明处」正好是它们。逐个 const 去切的话，
// 哪天谁把表写成多行，切法就跟不上了——而跟不上的表现是这把尺自己炸在 new Function 上
const iconFrom = main.indexOf('const NAV_ICON_SHELL');
const iconTo = main.indexOf('function mountTopPage(');
const iconSrc = iconFrom >= 0 && iconTo > iconFrom ? main.slice(iconFrom, iconTo) : '';

/**
 * 一个够 mountTopPage 用的假元素：记得下自己的内容，也答得出串起来是什么样
 */
function node(tag, cls) {
  const self = {
    tag,
    className: cls || '',
    dataset: {},
    attrs: {},
    kids: [],
    raw: '',
    appendChild(child) {
      self.kids.push(child);
      return child;
    },
    append(...items) {
      for (const item of items) self.kids.push(item);
    },
    insertBefore(child, ref) {
      const at = self.kids.indexOf(ref);
      self.kids.splice(at < 0 ? self.kids.length : at, 0, child);
      return child;
    },
    querySelector(sel) {
      return self.kids.find(kid => kid && kid.dataset
        && sel === '[data-page="' + kid.dataset.page + '"]') || null;
    },
    addEventListener() {
    },
    getAttribute(name) {
      return name in self.attrs ? self.attrs[name] : null;
    },
    setAttribute(name, value) {
      self.attrs[name] = value;
    },
  };
  // innerHTML 与 textContent 都是「整块换掉」，赋值要清掉先前的孩子，
  // 否则「先写图标再补文字」与「写文字盖掉图标」在这把尺上会长得一样
  Object.defineProperty(self, 'innerHTML', {
    get: () => self.raw,
    set: value => {
      self.raw = String(value);
      self.kids.length = 0;
    },
  });
  Object.defineProperty(self, 'textContent', {
    get: () => self.kids.filter(kid => typeof kid === 'string').join(''),
    set: value => {
      self.raw = '';
      self.kids.length = 0;
      self.kids.push(String(value));
    },
  });
  return self;
}

function serialize(el) {
  return el.raw + el.kids.map(kid => (typeof kid === 'string' ? kid : serialize(kid))).join('');
}

/**
 * 真跑一趟 mountTopPage，回它建出来的那条入口与那一页的容器
 */
function mount(meta) {
  const nav = node('nav');
  const builtinSettings = node('a');
  builtinSettings.dataset.page = 'settings';
  nav.appendChild(builtinSettings);
  const host = node('main');

  const build = new Function('topPageIds', '$', 'el', 'applyRoute', 'location',
    iconSrc + '\n' + mountSrc + '\nreturn mountTopPage;');
  const mountTopPage = build(
    new Set(),
    sel => (sel === '#nav' ? nav : sel === '#main' ? host : null),
    (tag, cls) => node(tag, cls),
    () => {
    },
    {hash: ''});
  const section = mountTopPage(meta);

  const link = nav.kids.find(kid => kid !== builtinSettings) || null;
  return {link, html: link ? serialize(link) : '', section};
}

/** 图标里画得出形状的那几种元素，缺省图标至少得是其中之一，否则画的是个空壳 */
const SHAPE = /<(path|circle|rect|line|polyline|polygon)[\s/>]/;

ask('① 没申报图标的插件页，侧栏那一条也有 svg', () => {
  const {html} = mount({id: 'demo', displayName: '演示'});
  want(html.includes('<svg'), true, '入口内容 ' + JSON.stringify(html) + ' 含 <svg');
});

ask('② 缺省图标不是个空壳，壳里有形状', () => {
  const {html} = mount({id: 'demo', displayName: '演示'});
  want(SHAPE.test(html), true, '入口内容 ' + JSON.stringify(html) + ' 含形状元素');
});

ask('③ 图标画在文字前面，文字仍在', () => {
  const {html} = mount({id: 'demo', displayName: '演示'});
  const svgAt = html.indexOf('<svg');
  const textAt = html.indexOf('演示');
  want(textAt >= 0, true, '入口 ' + JSON.stringify(html) + ' 仍有文字');
  want(svgAt >= 0 && svgAt < textAt, true, 'svg 在文字之前');
});

ask('④ 外壳与内置四条逐字相同', () => {
  const {html} = mount({id: 'demo', displayName: '演示'});
  const mine = /<svg[^>]*>/.exec(html);
  want(mine !== null, true, '插件页入口有 svg 开壳');
  const nav = read('index.html').split('<nav')[1] || '';
  const builtin = /<svg[^>]*>/.exec(nav);
  want(builtin !== null, true, 'index.html 的 nav 里有内置图标可比');
  want(mine[0], builtin[0], '插件页图标外壳');
});

ask('⑤ 发下来的形状原样进壳，两笔也不掉', () => {
  // 两笔拼在一起：真图标就是这个形状（一个圆加一笔线）。只喂单个标签的话，
  // 「第二笔掉了」这种毛病会一路滑到屏幕上
  const icon = '<circle cx="8" cy="8" r="4.2"/><line x1="2" y1="8" x2="14" y2="8"/>';
  const {html} = mount({id: 'demo', displayName: '演示', icon});
  want(html.includes(icon), true, '入口内容 ' + JSON.stringify(html) + ' 含原样图标');
  want(html.includes('<rect'), false, '既然给了形状，就不该再画缺省那一个');
});

ask('⑥ 空图标画缺省，页照常登记', () => {
  const {html, section} = mount({id: 'demo', displayName: '演示', icon: '   '});
  want(SHAPE.test(html), true, '入口内容 ' + JSON.stringify(html) + ' 含形状');
  want(section !== null, true, '这一页照常拿得到渲染容器');
});

ask('⑦ 图标不是串时画缺省，不炸', () => {
  const {html, section} = mount({id: 'demo', displayName: '演示', icon: 42});
  want(SHAPE.test(html), true, '入口内容 ' + JSON.stringify(html) + ' 含形状');
  want(section !== null, true, '这一页照常拿得到渲染容器');
});

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
