/**
 * 界面主题：深色／浅色／跟随系统，以及配色：经典／粉蓝／暖阳
 *
 * 偏好记在当前浏览器（localStorage），不进服务端配置——外观是「这台浏览器、这双眼睛」
 * 的事，不是机器人配置；多台设备各存各的，互不覆盖。
 *
 * 浅深三态与 product-ui.css 的明暗开关一一对应：
 *   跟随系统＝不设 data-theme，由系统媒体查询定；
 *   浅色＝data-theme="light"，压住媒体查询那一块；
 *   深色＝data-theme="dark"，走手动那一块。
 * 配色落在 data-palette 上，与 data-theme 互不相干：换配色不动浅深，换浅深不动配色。
 *
 * 🔴 键名刻意避开任何像口令的词。口令明文永远不写浏览器存储
 *    （见 ConfigUiFrontendTest），这里开的那道缝只许走 THEME_KEY、PALETTE_KEY 两把键，
 *    只许存 light／dark 与三个配色 id 这几个字面值。
 *    index.html、login.html 与 NapCat 进入页的 head 脚本用的是同两把键。
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
 * 同一页里别的部件想知道主题换了，可以听 window 上的这个事件（detail.theme 是新值）。
 * 设置页那三颗钮走 subscribeTheme，不靠它。
 */
export const THEME_CHANGE_EVENT = 'novabot-theme-change';

/** 订阅者：只改各自的钮，不重画页面 */
const themeListeners = new Set();

/**
 * 归一：只认 light／dark，别的一律按跟随系统
 * @param value 任意值
 * @return {'auto'|'light'|'dark'}
 */
function normalizeTheme(value) {
  return value === THEME_LIGHT || value === THEME_DARK ? value : THEME_AUTO;
}

/**
 * 读本浏览器记着的主题偏好
 * 读不到、读出错、读出别的值，一律按跟随系统
 *
 * 每次都现读 localStorage，不在模块里另记一份：另一个标签改了它，这里读到的就是新的；
 * 存不下的那一次，刷新后读回来的也照实是跟随系统。
 * @return {'auto'|'light'|'dark'}
 */
export function readTheme() {
  try {
    return normalizeTheme(localStorage.getItem(THEME_KEY));
  } catch (e) {
    return THEME_AUTO;
  }
}

/**
 * 订阅主题变化：本页点了钮、或另一个标签换了主题，都会叫一次
 *
 * 订阅者只该改自己那几颗钮的状态。<b>不许借此重画页面</b>：设置页上可能正有一格填到一半
 * 还没保存，重画会把它连同焦点一起清掉——而触发它的是另一个标签里的一次点击，使用者无从察觉。
 * @param listener (theme) => void
 * @return {Function} 取消订阅
 */
export function subscribeTheme(listener) {
  themeListeners.add(listener);
  return () => themeListeners.delete(listener);
}

/**
 * 把一个主题画到根元素上，再告诉订阅者
 * @param v 已归一的值
 */
function showTheme(v) {
  if (v === THEME_AUTO) document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', v);
  for (const listener of themeListeners) {
    try {
      listener(v);
    } catch (e) {
      // 一个订阅者出错不挡别的，也不挡换肤
    }
  }
}

/**
 * 用上一个主题偏好：记进本浏览器，并当场改 data-theme
 *
 * 跟随系统会去掉 data-theme——不删的话页面会锁在上一次手动选的那一个。
 * 存不下也要当场换肤，只是刷新后回到跟随系统。
 * 别的标签由浏览器的 storage 事件通知（见文件末尾），这里不用管。
 * @param value 'auto' | 'light' | 'dark'，别的值按跟随系统
 */
export function applyTheme(value) {
  const v = normalizeTheme(value);
  try {
    if (v === THEME_AUTO) localStorage.removeItem(THEME_KEY);
    else if (v === THEME_DARK) localStorage.setItem(THEME_KEY, THEME_DARK);
    else localStorage.setItem(THEME_KEY, THEME_LIGHT);
  } catch (e) {
    // 存不下不挡换肤
  }
  showTheme(v);
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function'
    && typeof CustomEvent === 'function') {
    window.dispatchEvent(new CustomEvent(THEME_CHANGE_EVENT, {detail: {theme: v}}));
  }
}

/**
 * 另一个标签改了偏好：浏览器在<b>别的</b>标签上发 storage 事件，本页跟着换
 *
 * 只认这一把键。key 为 null 是对方调了 localStorage.clear()：那把键也没了，该回到跟随系统，
 * 因此照常处理，而不是当成不相干的事件丢掉——丢掉的话这一页会一直锁在手动选的那一档。
 * 不取事件里带来的新值，一律现读一次本页的偏好：别的存储区（sessionStorage）发来同名键的事件，
 * 读回来的仍是 localStorage 里那一份，什么也不会变；也不必在这里另写一处去碰存储。
 * @param event storage 事件
 */
function onThemeStorage(event) {
  if (!event || (event.key !== THEME_KEY && event.key !== null)) return;
  showTheme(readTheme());
}

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', onThemeStorage);
}

/** 配色在浏览器本地的键 */
export const PALETTE_KEY = 'novabot-palette';

/** 经典：升级前的那一套 */
export const PALETTE_CLASSIC = 'classic';

/** 粉蓝：没选过配色时就是它 */
export const PALETTE_PINK_BLUE = 'pink-blue';

/** 暖阳 */
export const PALETTE_WARM = 'warm';

/** 订阅者：只改各自的钮，不重画页面（道理同 themeListeners） */
const paletteListeners = new Set();

/**
 * 归一：只认三个配色 id，别的一律按粉蓝
 * @param value 任意值
 * @return {'classic'|'pink-blue'|'warm'}
 */
function normalizePalette(value) {
  return value === PALETTE_CLASSIC || value === PALETTE_WARM ? value : PALETTE_PINK_BLUE;
}

/**
 * 读本浏览器记着的配色
 * 读不到、读出错、读出别的值，一律按粉蓝——没选过配色的人（包括升级上来的）看到的就是粉蓝
 * @return {'classic'|'pink-blue'|'warm'}
 */
export function readPalette() {
  try {
    return normalizePalette(localStorage.getItem(PALETTE_KEY));
  } catch (e) {
    return PALETTE_PINK_BLUE;
  }
}

/**
 * 订阅配色变化：本页点了钮、或另一个标签换了配色，都会叫一次
 *
 * 与 subscribeTheme 同一条规矩：订阅者只改自己那几颗钮，<b>不许借此重画页面</b>。
 * @param listener (palette) => void
 * @return {Function} 取消订阅
 */
export function subscribePalette(listener) {
  paletteListeners.add(listener);
  return () => paletteListeners.delete(listener);
}

/**
 * 把一个配色画到根元素上，再告诉订阅者
 * @param v 已归一的值
 */
function showPalette(v) {
  document.documentElement.setAttribute('data-palette', v);
  for (const listener of paletteListeners) {
    try {
      listener(v);
    } catch (e) {
      // 一个订阅者出错不挡别的，也不挡换配色
    }
  }
}

/**
 * 用上一个配色：记进本浏览器，并当场改 data-palette
 *
 * 选了粉蓝也照记一笔，而不是删键：以后默认配色若换了，选过粉蓝的人仍是粉蓝。
 * 存不下也要当场换，只是刷新后回到粉蓝。别的标签由 storage 事件通知（见文件末尾）。
 * @param value 'classic' | 'pink-blue' | 'warm'，别的值按粉蓝
 */
export function applyPalette(value) {
  const v = normalizePalette(value);
  try {
    if (v === PALETTE_CLASSIC) localStorage.setItem(PALETTE_KEY, PALETTE_CLASSIC);
    else if (v === PALETTE_WARM) localStorage.setItem(PALETTE_KEY, PALETTE_WARM);
    else localStorage.setItem(PALETTE_KEY, PALETTE_PINK_BLUE);
  } catch (e) {
    // 存不下不挡换配色
  }
  showPalette(v);
}

/**
 * 另一个标签换了配色：本页跟着换。key 为 null（对方清空了本地存储）同样现读一次，回到粉蓝
 * @param event storage 事件
 */
function onPaletteStorage(event) {
  if (!event || (event.key !== PALETTE_KEY && event.key !== null)) return;
  showPalette(readPalette());
}

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', onPaletteStorage);
}
