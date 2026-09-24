/**
 * 界面主题：深色／浅色／跟随系统
 *
 * 偏好记在当前浏览器（localStorage），不进服务端配置——外观是「这台浏览器、这双眼睛」
 * 的事，不是机器人配置；多台设备各存各的，互不覆盖。
 *
 * 三态与 app.css 的三块一一对应：
 *   跟随系统＝不设 data-theme，由系统媒体查询定；
 *   浅色＝data-theme="light"，压住媒体查询那一块；
 *   深色＝data-theme="dark"，走手动那一块。
 *
 * 🔴 键名刻意避开任何像口令的词。口令明文永远不写浏览器存储
 *    （见 ConfigUiFrontendTest），这里开的那道缝只许走 THEME_KEY 一把键、
 *    只许存 light／dark 两个字面值。index.html 的 head 脚本用的是同一把键。
 */

/** 偏好在浏览器本地的键 */
export const THEME_KEY = 'novabot-theme';

/** 跟随系统：不设 data-theme */
export const THEME_AUTO = 'auto';

/** 手动浅色 */
export const THEME_LIGHT = 'light';

/** 手动深色 */
export const THEME_DARK = 'dark';

/**
 * 读本浏览器记着的主题偏好
 * 读不到、读出错、读出别的值，一律按跟随系统
 * @return {'auto'|'light'|'dark'}
 */
export function readTheme() {
  try {
    const raw = localStorage.getItem(THEME_KEY);
    return raw === THEME_LIGHT || raw === THEME_DARK ? raw : THEME_AUTO;
  } catch (e) {
    return THEME_AUTO;
  }
}

/**
 * 用上一个主题偏好：记进本浏览器，并当场改 data-theme
 *
 * 跟随系统会去掉 data-theme——不删的话页面会锁在上一次手动选的那一个。
 * 存不下也要当场换肤，只是刷新后回到跟随系统。
 * @param value 'auto' | 'light' | 'dark'，别的值按跟随系统
 */
export function applyTheme(value) {
  const v = value === THEME_LIGHT || value === THEME_DARK ? value : THEME_AUTO;
  try {
    if (v === THEME_AUTO) localStorage.removeItem(THEME_KEY);
    else if (v === THEME_DARK) localStorage.setItem(THEME_KEY, THEME_DARK);
    else localStorage.setItem(THEME_KEY, THEME_LIGHT);
  } catch (e) {
    // 存不下不挡换肤
  }
  if (v === THEME_AUTO) document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', v);
}
