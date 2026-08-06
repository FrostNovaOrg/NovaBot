/**
 * 各页签共享的可变状态。
 *
 * 为什么要装进一个对象而不是各自 export 一个 let：ES module 的导入是**只读绑定**，
 * `import { tab }` 之后再 `tab = 'push'` 会直接抛 TypeError。挂在对象上改属性则不受限，
 * 也让「这是跨页签共享的东西」在读代码时一眼可见——散落的裸全局做不到这一点。
 */
export const store = {
  /** 配置项字段表，来自编译期生成的元数据 */
  schema: [],
  /** application.yml 里的当前值 */
  values: {},
  /** 设置页尚未保存的改动 */
  dirty: {},
  /** 当前页签 */
  tab: 'overview',
  /** 写请求要带的 CSRF 令牌，登录后由 /auth/state 下发 */
  csrfToken: '',
  /** datasource.json 解析结果，改动直接作用其上以保留表单未覆盖的字段 */
  pushData: [],
  /** 已注册的推送处理器 */
  handlerList: [],
  /** 已配置的机器人平台 */
  senderList: [],
  /** 推送规则页是否处于直接编辑 JSON 的模式 */
  advancedMode: false,
  /** 使用者手动展开或收起过向导，此后不再自动折叠 */
  wizardTouched: false,
  /** 全局推送开关的当前状态 */
  pushEnabled: true,
  /** 扫码登录的轮询计时器 */
  accountTimer: null,
};
