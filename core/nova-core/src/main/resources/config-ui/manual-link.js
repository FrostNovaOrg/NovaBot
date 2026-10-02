/**
 * 侧栏「使用说明」打开哪一章，以及站外链接怎么开
 *
 * 站点网址只写在这里。页面上再抄一份的话，改站点时会漏掉一处，
 * 漏掉的那一处仍把本页地址带去别的网站——控制台地址里常有访问令牌。
 */

/** 用户手册网站。每章一个 html，目录页是 index.html */
export const MANUAL_SITE = 'https://frostnovaorg.github.io/NovaBot/';

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
 * 按地址栏算出手册网址
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
  if (name === 'settings' && parts[1]) return MANUAL_SITE + 'index.html';
  return MANUAL_SITE + (CHAPTER[name] || 'index.html');
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
