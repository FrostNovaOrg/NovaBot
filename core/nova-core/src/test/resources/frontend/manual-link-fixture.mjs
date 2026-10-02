/**
 * 侧栏「使用说明」：按当前页算出手册网址，外链不把本页地址交出去
 *
 * 控制台地址里常带访问令牌。外链只写 noopener 时，新页拿不到本窗口，
 * 来源信息照样走，令牌会跟去别的网站。
 *
 * 由 ManualLinkTest 拉起。量的是源码树里那一份，不是构建产物里的副本。
 */

import {readFileSync, readdirSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {extAttrs, manualHref} from '../../../main/resources/config-ui/manual-link.js';

const ui = join(dirname(fileURLToPath(import.meta.url)), '../../../main/resources/config-ui');
const site = 'https://frostnovaorg.github.io/NovaBot/';

const failures = [];
let checks = 0;

function eq(actual, expected, what) {
  checks++;
  if (actual !== expected) {
    failures.push(what + '：得到 ' + JSON.stringify(actual) + '，应为 ' + JSON.stringify(expected));
  }
}

function has(actual, needle, what) {
  checks++;
  if (!String(actual).includes(needle)) {
    failures.push(what + '：缺少 ' + needle + '，得到 ' + JSON.stringify(actual));
  }
}

const chapter = {
  home: '05-console-tour.html',
  log: '11-where-to-see-what-happened.html',
  links: '14-troubleshooting.html',
  settings: '12-settings.html',
  setup: '04-first-open.html',
  push: '06-add-streamer-and-push.html',
  streamers: '09-stream-reports.html',
};

eq(manualHref('#/home'), site + chapter.home, '#/home');
eq(manualHref('#/log'), site + chapter.log, '#/log');
eq(manualHref('#/log/eng'), site + chapter.log, '#/log/eng 仍是日志页');
eq(manualHref('#/links'), site + chapter.links, '#/links');
eq(manualHref('#/links?card=platform'), site + chapter.links, '连接页带 card 仍是同一章');
eq(manualHref('#/settings'), site + chapter.settings, '#/settings');
eq(manualHref('#/settings?card=push'), site + chapter.settings, '设置页带 card 仍是同一章');
eq(manualHref('#/setup'), site + chapter.setup, '#/setup');
eq(manualHref('#/push'), site + chapter.push, '#/push');
eq(manualHref('#/push/default'), site + chapter.push, '#/push/default');
eq(manualHref('#/push/123456/7'), site + chapter.push, '#/push/<uid>/<号>');
eq(manualHref('#/streamers'), site + chapter.streamers, '#/streamers');
eq(manualHref('#/streamers/bilibili/1'), site + chapter.streamers, '主播页子路由仍是同一章');
eq(manualHref('#/no-such-page'), site + 'index.html', '不认识的页去目录');
eq(manualHref('#/settings/later-plugin'), site + 'index.html', '设置页下后加的插件页去目录');
eq(manualHref(''), site + chapter.home, '空地址当首页');

const attrs = extAttrs('https://example.com/manual');
has(attrs, 'noopener', '外链属性');
has(attrs, 'noreferrer', '外链属性');
has(attrs, 'referrerpolicy="no-referrer"', '外链属性');
has(attrs, 'target="_blank"', '外链属性');
eq(extAttrs('/config'), '', '站内地址不加外链属性');
eq(extAttrs(''), '', '空地址不加外链属性');

const html = readFileSync(join(ui, 'index.html'), 'utf8');
const anchor = html.match(/<a\b[^>]*id="side-manual"[^>]*>[\s\S]*?<\/a>/);
checks++;
if (!anchor) {
  failures.push('index.html 没有 #side-manual「使用说明」');
} else {
  has(anchor[0], '使用说明', '侧栏入口');
  has(anchor[0], 'noopener', '侧栏入口');
  has(anchor[0], 'noreferrer', '侧栏入口');
  has(anchor[0], 'referrerpolicy="no-referrer"', '侧栏入口');
  has(anchor[0], 'target="_blank"', '侧栏入口');
  checks++;
  if (anchor[0].includes('data-page=')) {
    failures.push('使用说明写进了路由入口（data-page），切页时会被当成一页去点亮');
  }
}

const mainJs = readFileSync(join(ui, 'main.js'), 'utf8');
has(mainJs, 'manualHref(location.hash)', '侧栏入口要按地址栏改 href');
has(mainJs, "from './manual-link.js'", 'main.js 要引入手册网址');
has(readFileSync(join(ui, 'overview.js'), 'utf8'), 'extAttrs(', '源码地址与更新说明要走外链属性');

const files = readdirSync(ui).filter(name => name.endsWith('.js') || name.endsWith('.html'));
for (const name of files) {
  const lines = readFileSync(join(ui, name), 'utf8').split('\n');
  lines.forEach((line, i) => {
    if (line.includes('noopener') && !line.includes('noreferrer')) {
      checks++;
      failures.push(name + ':' + (i + 1) + ' 外链只有 noopener，没有 noreferrer');
    }
  });
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const line of failures) console.log('  红：' + line);
process.exit(failures.length ? 1 : 0);
