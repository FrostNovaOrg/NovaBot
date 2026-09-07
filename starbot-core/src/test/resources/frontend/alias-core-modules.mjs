/**
 * 插件页脚本对宿主模块写 './x.js'，浏览器里解析到 /config/assets/x.js。
 * node 夹具加载的是源文件，要把这个名字指回核心那一份。
 */
export async function resolve(specifier, context, nextResolve) {
  if (specifier === './home-model.js') {
    return {
      url: new URL('../../../main/resources/config-ui/home-model.js', import.meta.url).href,
      shortCircuit: true,
    };
  }
  return nextResolve(specifier, context);
}
