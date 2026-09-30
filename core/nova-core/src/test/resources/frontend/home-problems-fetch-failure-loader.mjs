/**
 * 加载首页脚本时，把入口那一份换成替身。
 *
 * 真入口会顺带拉起连接页、设置页并在解析时绑按钮。夹具只量首页取数，
 * 那些页不在这一格里。
 */

const stub = new URL('./home-problems-fetch-failure-main-stub.mjs', import.meta.url);

export async function resolve(specifier, context, nextResolve) {
  if (specifier === './main.js') {
    return {url: stub.href, shortCircuit: true};
  }
  return nextResolve(specifier, context);
}
