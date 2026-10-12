/**
 * 侧栏「使用说明」打开哪一章，以及站外链接怎么开
 *
 * 手册是随程序自带的那一份，从控制台底下的只读出口取（登录后可看，不需要外网），
 * 与别的本站地址一样带上部署前缀：前缀取 core.js 从 <meta name="nova-prefix">
 * 读出来的那一个常量，不在这里再读一次。
 */

import {CONSOLE} from './core.js';

/** 手册出口。包里没带手册时，这一头会自己退到 GitHub Pages 上同一章 */
const MANUAL_ROOT = `${CONSOLE}/manual/`;

/**
 * 页名 → 章文件
 *
 * 推送页的默认模板、某一位主播、单个通道都还是推送页，同一章。
 * 主播页的场次与详情、日志页按天翻和工程日志，也仍是原来那一页。
 * 不在表里的名字去目录，包括以后插件另加的顶级页。
 */
const CHAPTER = {
  home: '05-console-tour.html',
  log: '11-where-to-see-what-happened.html',
  links: '14-troubleshooting.html',
  settings: '12-settings.html',
  setup: '04-first-open.html',
  push: '06-add-streamer-and-push.html',
  streamers: '09-stream-reports.html',
};

/**
 * 按地址栏算出手册地址
 *
 * 认的是地址栏原文，不认「认不出的路由被画成首页」之后的那个名字：
 * 画成首页、说明却打开首页那一章，点进去就不是这一页的章。
 * 设置页底下再挂一段的是插件折页，那一章并不讲它，去目录。
 * @param {string} hash 地址栏，可以带 # 也可以不带
 * @return {string}
 */
export function manualHref(hash) {
  const raw = String(hash || '').replace(/^#\/?/, '');
  const cut = raw.indexOf('?');
  const path = cut < 0 ? raw : raw.slice(0, cut);
  const parts = path.split('/').filter(Boolean);
  const name = parts[0] || 'home';
  if (name === 'settings' && parts[1]) return MANUAL_ROOT + 'index.html';
  return MANUAL_ROOT + (CHAPTER[name] || 'index.html');
}

/**
 * 站外链接的属性：新标签打开，新页拿不到本窗口，也不把本页地址交出去
 *
 * 只写 noopener 与 noreferrer 里的一个时，旧浏览器仍会把带令牌的地址送出去，
 * 或让新页拿到本窗口。站内地址不加，免得同一站点的跳转被当成外链。
 * @param {string} url
 * @return {string} 拼进开始标签的属性；站内或空地址则是空串
 */
export function extAttrs(url) {
  if (!/^https?:\/\//i.test(String(url || ''))) return '';
  return ' target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer"';
}
