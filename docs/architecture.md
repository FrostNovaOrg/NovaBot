# 架构说明

面向要改这个项目的人。目标是把「改错会出问题、但从代码里看不出来」的约定写清楚——
能就地写成注释的都已经写在代码里了，这里只留跨文件、跨模块的部分。

- 想知道怎么用：[用户手册](user-guide.md)
- 想知道出问题怎么办：[排障与 FAQ](troubleshooting.md)
- 想知道跑起来占多少资源：[性能实测](performance.md)

## 1. 模块与依赖方向

```
                 ┌──────────────┐
                 │ starbot-core │  事件总线 / 插件加载 / 数据源 / 消息发送 / 绘图 / 配置界面
                 └──────┬───────┘
        ┌───────────────┼────────────────┐
        │               │                │
┌───────┴──────┐ ┌──────┴────────┐ ┌─────┴─────────────────┐
│ starbot-     │ │ starbot-      │ │ 第三方插件            │
│ bilibili     │ │ onebot-       │ │ (templates/ 下有模板) │
│              │ │ adapter       │ └───────────────────────┘
│ 采集侧       │ │ 投递侧        │        ┌──────────────────────────────┐
└──────────────┘ └──────┬────────┘        │ napcat-extension              │
                        └─────────────────┤ 依赖 onebot-adapter           │
                                          └──────────────────────────────┘
```

**依赖方向只有一个：插件依赖核心，核心永不依赖插件。**

这条约束不是洁癖。核心要能在只装了部分插件时正常启动——只装 OneBot 适配器不装哔哩哔哩、
或者反过来，都得跑得起来。核心一旦 `import` 了插件的类，缺少该插件时就是 `NoClassDefFoundError`。

需要核心调用插件能力时，一律走 SPI：核心定义接口，插件实现并注册为 Bean，
核心用 `ObjectProvider<T>` 取（没有实现时得到空流，而不是启动失败）。

| SPI | 位置 | 谁实现 | 用途 |
|---|---|---|---|
| `HealthProbe` | `core.health` | 各模块 | 汇总健康状况，供总览页、告警共用 |
| `AccountLoginProvider` | `core.account` | 哔哩哔哩 | 界面内扫码登录、退出登录 |
| `BotConnectionTester` | `core.account` | OneBot 适配器 | 连通性测试与连接参数回填 |
| `AlertChannel` | `core.alert` | 核心（邮件、QQ） | 告警投递 |

新增一个跨模块能力时，先问「核心需不需要 import 插件的类」。需要，就说明该抽成 SPI。

## 2. 事件流

```
采集                       事件总线                  匹配                渲染         投递
─────────────────────────  ────────────────────────  ─────────────────  ──────────  ──────────────
直播间 WebSocket 长连接 ┐                            StarBotHandler-     各 Push-    StarBotMessage-
动态轮询               ├→ ApplicationEventPublisher  Listener            Handler     Sender
备用直播状态轮询       ┘   (Interruptible-           ↓                   ↓           ↓
                           EventMulticaster)         按 uid 查数据源     生成文本    队列 → OneBot
                                                     按事件类型匹配      与图片      HTTP 接口
                                                     已配置的处理器
```

要点：

- **事件总线就是 Spring 的 `ApplicationEventPublisher`**，没有另造一套。
  `InterruptibleEventMulticaster` 只加了一件事：事件被标记为 `stopped` 后不再投给后续监听器，
  供插件拦截事件。
- **匹配发生在 `StarBotHandlerListener`**：按 `事件平台 + uid` 从数据源找到 `PushUser`，
  再遍历其 `targets → messages`，事件类与 `message.eventClass` 相同才调用对应处理器。
  配错 uid 的表现是「什么都不发生」，因为这里根本匹配不上——这也是配置界面要在添加主播时
  先拉昵称让人确认的原因。
- **处理器抛异常只记日志，不会中断其他处理器**。一个目标配置有误不应连累其他目标。
- **`datasource.json` 里写的是处理器全限定类名**，由 `StarBotEventHandlerService` 在
  `ContextRefreshedEvent` 时建立 `类名 → 实例` 的映射。类名写错在运行期表现为
  「不存在的事件处理器」日志，因此保存配置时就会校验（见第 4 节）。

## 3. 并发与生命周期

### 线程池清单

| 位置 | 类型 | 由谁关闭 |
|---|---|---|
| `networkThreadPool` | `ThreadPoolTaskExecutor` Bean | Spring |
| `oneBotThreadPool` | `ThreadPoolTaskExecutor` Bean | Spring |
| `bilibiliTaskScheduler` | `ThreadPoolTaskScheduler` Bean | Spring |
| `JsonDataSource.executor` / `.scheduler` | 手工创建 | 自身的 `@PreDestroy` |
| `StarBotMessageSender.executor` | 手工创建 | 自身的 `@PreDestroy` |
| `DefaultLiveDataService.scheduler` | 手工创建 | 自身监听 `ContextClosedEvent` 时关闭 |

**手工创建的线程池必须自己关。** Spring 只负责它自己创建的 Bean；`Executors.newXxx()` 出来的
线程池是非守护线程，不关就会拖着 JVM 不退出。新增线程池时一并加 `@PreDestroy`。

`DefaultLiveDataService` 是个例外，它在 `ContextClosedEvent` 里关：那里还要做一次收尾保存，
必须先停掉自动保存的定时任务，否则两者会同时写同一个文件。

### 停机时序

这是本项目踩过最贵的坑，写在这里避免重蹈：

```
SIGTERM
  ↓
发布 ContextClosedEvent          ← 此时业务代码还在跑
  ↓
lifecycleProcessor.onClose()     ← 停 SmartLifecycle，默认最多等 30 秒
  ↓
销毁单例 Bean（@PreDestroy）
```

关键点：

1. **`ContextClosedEvent` 早于 `SmartLifecycle.stop()`**，所以「感知停机」要监听前者。
2. **`SmartLifecycle.stop()` 只是等待，不会中断线程**。一个跑在 `Thread.sleep` 里的轮询循环
   不会因为停机而醒来，Spring 会老实等满 30 秒超时。曾经的现象是「SIGTERM 后进程要 31 秒才退出」，
   而首次部署尚未扫码时必然命中——因为扫码轮询是个可能持续数分钟的循环。
3. 因此**所有长时间等待都不要用 `Thread.sleep`**，改用一个由 `ContextClosedEvent` 放行的
   `CountDownLatch.await(timeout)`：正常超时返回 `false`，收到停机信号立即返回 `true`。
   参考 `BilibiliAccountService#sleep`。

### 状态可见性

跨线程读写的字段一律 `volatile`（登录态、uid、待扫码内容等）。这些字段由调度线程写、
由 Web 线程（配置界面）读，不加 `volatile` 时界面可能长时间读到旧值。

## 4. 配置体系

### 单一事实来源

```
配置类的字段 + Javadoc
  ↓ spring-boot-configuration-processor（编译期）
META-INF/spring-configuration-metadata.json
  ↓ ConfigurationMetadataService（运行期读取）
配置界面的字段、说明、默认值、类型校验
```

**配置界面的字段不是手工维护的。** 在配置类里加一个字段并写好 Javadoc，界面就自动多一项；
删掉字段，界面自动少一项。因此：

- 配置项的说明**写在 Javadoc 里**，不要写在别处
- 用 `@ConfigLevel` 标注分层（`BASIC` / `COMMON` / `ADVANCED`），默认 `ADVANCED`（界面折叠）
- 类型校验也取自同一份元数据，不需要另行维护类型表

### 三条不变量

`ConfigurationConsistencyTest`（在 `starbot-bilibili` 模块，因为它在反应堆中最后构建，
能读到全部模块的元数据）在构建时强制以下三条，破坏任一条都会让构建失败：

1. **不存在声明了却从未生效的配置项**——即改了没反应的虚空配置
2. **`dist/templates/application.yml` 中不含已删除的配置项**
3. **每个配置项都有中文说明**——界面依赖它生成字段提示

这个测试已经拦下过两次真实的回归。改配置类时如果它红了，先看是不是真的漏了什么，
而不是先想怎么让它变绿。

### 配置文件写入

`ConfigurationFileService` 采用**逐行定位、就地替换**，而不是「反序列化再序列化」。
后者会把用户文件里的注释、顺序、缩进全部抹掉。代价是要自己处理 YAML 的缩进规则，
其中一条容易搞错：**列表项的缩进比列表键更深**，不能假定二者相等。

每次写入前自动备份（保留最近 10 份），界面可回滚。

### 配置写坏了怎么办

`application.yml` 语法错误会让 Spring 直接启动失败，那时配置界面也起不来——人就被锁在门外了。
为此 `StarBotCoreApplication` 捕获**仅限配置类**的启动失败（`BindException`、snakeyaml 异常、
`ConfigDataResourceNotFoundException` 等），转而启动安全模式。

安全模式**刻意不是 Spring 应用**，用的是 JDK 自带的 `HttpServer`：一份坏掉的 `application.yml`
会让第二个 Spring 上下文以同样的方式失败，那就毫无意义。它只绑回环、带随机令牌、
只提供「看/改/回滚 application.yml」。

## 5. 插件机制

### 注册

插件的组件用 **`@StarBotComponent`**，不是 Spring 的 `@Component`。
`StarBotPluginLoader`（一个 `BeanDefinitionRegistryPostProcessor`）扫描插件 jar 中带该注解的类
并注册为 Bean 定义。写成 `@Component` 的类不会被扫到，表现是启动时报「找不到某个依赖的 Bean」。

### 类加载

`StarBotClassLoader` 是**插件优先**（先问插件加载器，找不到再委派父加载器），
与 JDK 默认的双亲委派相反。这样插件可以携带与核心不同版本的依赖。

代价与限制：

- 插件与核心**共享**核心导出的类（事件、模型、SPI 接口），这些必须由父加载器加载，
  否则会出现「同名不同类」的 `ClassCastException`
- 插件之间的隔离是**不完全的**：它们共用 `plugins-lib/` 目录下的依赖

### 依赖下载

插件声明的依赖若缺失，`StarBotPluginDependencyDownloader` 会下载到 `plugins-lib/`，
然后以**退出码 90** 退出，由 `start.sh` 的循环重新拉起——新 jar 无法在已运行的 JVM 里生效。
下载带 SHA-1 校验与路径穿越防护，写入用「临时文件 + 原子移动」。

不在程序内部 fork 子进程重启：父进程得一直驻留等子进程结束，白占一份内存，
systemd 下的进程树也不正确。

### 可监听的事件类型

内置推送处理器只覆盖开播、下播、动态三类。**弹幕、礼物等事件不带默认处理器**，
需要自己写插件用 `@EventListener` 监听。

直播事件在 `com.starlwr.bot.bilibili.event.live` 包下：

| 事件 | 触发时机 |
|---|---|
| `BilibiliLiveOnEvent` / `BilibiliLiveOffEvent` | 开播 / 下播 |
| `BilibiliDanmuEvent` / `BilibiliEmojiEvent` | 弹幕 / 表情弹幕 |
| `BilibiliEnterRoomEvent` / `BilibiliFollowEvent` / `BilibiliShareEvent` | 进入直播间 / 关注 / 分享 |
| `BilibiliLikeEvent` / `BilibiliLikeUpdateEvent` | 点赞 / 点赞数更新 |
| `BilibiliFreeGiftEvent` / `BilibiliPaidGiftEvent` / `BilibiliRandomGiftEvent` | 免费礼物 / 付费礼物 / 盲盒 |
| `BilibiliSuperChatEvent` | 醒目留言 |
| `BilibiliGovernorEvent` / `BilibiliCommanderEvent` / `BilibiliCaptainEvent` | 总督 / 提督 / 舰长 |
| `BilibiliConnectedEvent` / `BilibiliDisconnectedEvent` | 直播间长连接建立 / 断开 |

动态事件只有一个：`com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent`。

## 6. 反射相关的坑

一处**只有踩过才知道**的行为：标注了 `@Configuration` 的类会被 CGLIB 代理，
代理类上只有合成字段，直接反射拿不到真正的配置字段。反射前必须先还原：

```java
Class<?> type = ClassUtils.getUserClass(bean);
```

`ConfigurationLevelResolver` 就因为漏了这一步，只解析到 17 个字段中的 8 个。

## 7. 测试约定

- `@DisplayName` 用中文，描述**行为**而不是方法名
- 断言带消息，说明「为什么这样才对」，而不只是「期望 X 实际 Y」
- **不要用 mock 造出现实中不存在的前提**。曾经有一个校验缺陷因为单元测试里 mock 出了
  真实系统中并不存在的元数据而被漏掉，端到端跑一遍才发现
- 涉及时间的测试用假调度器/假时钟，不要 `Thread.sleep` 等真实时间
- 测试 fixture 里的隐性依赖**就地写注释**。例如 `ConfigurationFileServiceTest` 的 YAML
  fixture 中不能加入 `starbot.bilibili.dynamic.auto-save-image`，因为另一个用例依赖该键不存在——
  这条注释就写在 fixture 上方

### 界面 JS 怎么验证

配置界面的 `<script>` 无法用浏览器面板打开（本机策略拦截 localhost），替代做法是：

1. 抽出 `<script>` 内容，用 `node --check` 查语法
2. 用**真实接口返回的数据**在 node 里跑同一段渲染表达式并逐条断言
   （从 HTML 源码里正则抠出待测函数，而不是手抄一份副本——手抄的副本永远是对的）
3. 结构性检查：所有 `$('#id')` 引用的 id 都能对应到 DOM 定义，页签按钮与 section 一一对应

## 8. 构建

```bash
./build.sh [--clean|--skip-tests]
```

**分两步构建**：`build-tools/starbot-plugin-processor` 是各插件模块在 build 阶段要调用的
Maven 插件，而 Maven 不支持在同一 reactor 内构建并使用同一个插件，因此必须先单独安装它。
`build.sh` 已处理这一点——直接 `mvn package` 会失败。

产物在 `dist/build/`，其中 `application.yml` 来自 `dist/templates/`。
**本机联调用的令牌只写在 `dist/build/`**（构建产物，已 gitignore），不要写进 `dist/templates/`。
