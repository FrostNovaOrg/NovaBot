/**
 * 把插件页脚本里那些「宿主模块」的相对名指回核心那一份
 *
 * 插件页与宿主的界面文件跑在同一张页面上，因此它引宿主模块时写的是 './core.js' 这样的
 * 相对名——浏览器把它解析成 /config/assets/core.js，而那个出口先取核心的 config-ui/。
 * node 夹具加载的是源文件，两边不在同一个目录里，不接这个钩子就是一句 ERR_MODULE_NOT_FOUND。
 *
 * 🔴 按「核心那边有没有同名文件」判，不按写死的一张名单：写死的话，插件页哪天多引一个
 * 宿主模块，夹具当场炸在 import 上，而炸的那句话与这件事毫无关系。核心没有的名字一律放行，
 * 于是插件页引自己的同目录文件（'./today-model.js' 这类）照常走原路。
 *
 * 用法：夹具在动态 import 被测模块之前
 *   import {register} from 'node:module';
 *   register(new URL('<相对路径>/alias-core-modules.mjs', import.meta.url));
 */

import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const CORE_UI = '../starbot-core/src/main/resources/config-ui/';

export async function resolve(specifier, context, nextResolve) {
  if (/^\.\/[A-Za-z0-9_-]+\.js$/.test(specifier)) {
    const url = new URL(CORE_UI + specifier.slice(2), import.meta.url);
    if (existsSync(fileURLToPath(url))) {
      return {url: url.href, shortCircuit: true};
    }
  }
  return nextResolve(specifier, context);
}
