/**
 * 首页取数夹具用的入口替身。
 *
 * 真入口在解析时就会去绑整页上的按钮。这一格只量首页取数那一趟，
 * 三个函数留空：不改地址、不往各页转发。
 */

export function considerSetupRedirect() {
  return false;
}

export function pageStatus() {
}

export function refreshPages() {
}
