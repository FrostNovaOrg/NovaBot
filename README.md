<img src="docs/assets/logo.svg" alt="NovaBot" height="56">

哔哩哔哩直播与动态推送机器人。监听 UP 主的开播、下播与动态更新，通过 OneBot 协议推送到 QQ。

本仓库是 [StarBot](https://github.com/Starlwr/StarBot)（作者 [LWR](https://github.com/Starlwr)）的整理版本，遵循 AGPL-3.0
发布。相对上游的改动见 [NOTICE](NOTICE) 与 [CHANGELOG.md](CHANGELOG.md)，其中包含一项**推送接口鉴权的安全修复**，
详见 [SECURITY.md](SECURITY.md)。

> 包名、构件坐标与配置键仍沿用 `starbot` / `com.starlwr`，以保持与上游生态第三方插件的兼容；
> NovaBot 只是本仓库对外的名称。两者的取舍见 [NOTICE](NOTICE)。

**文档**：[用户手册](docs/user-guide.md) · [排障与 FAQ](docs/troubleshooting.md) ·
[架构说明](docs/architecture.md) · [安全说明](SECURITY.md) · [性能实测](docs/performance.md)

## 与上游的关系

上游将 3.0 拆分为多个独立仓库，本仓库将它们整合为单一 Maven 多模块工程，并补齐了文档、测试与 CI：

| 模块 | 说明 | 上游仓库 |
|---|---|---|
| `starbot-core` | 核心：事件总线、插件加载、数据源、消息发送、绘图基础 | [StarBotCore](https://github.com/Starlwr/StarBotCore) |
| `starbot-bilibili` | 哔哩哔哩插件：直播长连接、动态推送、图片绘制 | 未公开，本仓库为独立实现 |
| `starbot-onebot-adapter` | OneBot 适配器：把推送消息投递给 NapCat 等实现 | [StarBotOneBotAdapterPlugin](https://github.com/Starlwr/StarBotOneBotAdapterPlugin) |
| `starbot-onebot-adapter-napcat-extension` | NapCat 扩展：@全体成员 次数不足时改用群待办 | [StarBotOneBotAdapterNapcatExtensionPlugin](https://github.com/Starlwr/StarBotOneBotAdapterNapcatExtensionPlugin) |
| `build-tools/starbot-plugin-processor` | 构建期 Maven 插件，生成插件描述文件 | [StarBotPluginProcessor](https://github.com/Starlwr/StarBotPluginProcessor) |
| `templates/starbot-example-plugin` | 第三方插件开发模板 | [StarBotExamplePlugin](https://github.com/Starlwr/StarBotExamplePlugin) |

构件坐标与版本号与上游保持一致（`com.starlwr:starbot-core:3.0.0` 等），因此针对上游开发的第三方插件可直接使用。

## 构建

需要 JDK 17 或更高版本与 Maven 3.9 或更高版本。

```bash
./build.sh
```

产物位于 `dist/build/`，目录结构如下：

```
StarBotCore.jar      主程序
lib/                 核心依赖
plugins/             插件
plugins-lib/         插件依赖
```

可选参数：`--skip-tests` 跳过测试，`--clean` 构建前清理。

> 构建分两步：`build-tools/starbot-plugin-processor` 是各插件模块在 build 阶段调用的 Maven 插件，
> Maven 不支持在同一 reactor 内构建并使用同一个插件，因此需先单独安装。`build.sh` 已处理这一点。

## 快速开始

Linux 上一条命令完成安装（自动检查并安装 JDK 17、构建、创建 systemd 服务）：

```bash
./install.sh
```

随后启动并查看日志：

```bash
sudo systemctl start starbot && sudo journalctl -u starbot -f
```

日志中会输出两样东西：**配置界面地址**（含一次性访问令牌）和**登录二维码**。
用浏览器打开前者，总览页顶部的四步向导会带着你完成机器人连接、扫码登录、添加主播、
发一条测试消息——每步都当场验证，不必配完再猜哪里错了。

若 NovaBot 装在远程服务器上，先在本机建立隧道再访问配置界面：

```bash
ssh -L 7827:127.0.0.1:7827 用户名@服务器地址
```

`install.sh` 支持 `--dir` 指定安装目录、`--port` 指定端口、`--no-service` 跳过服务创建，
详见 `./install.sh --help`。

## 配置界面

启动后访问日志中输出的地址即可打开内置配置界面，五个页签按「此刻想干什么」划分：

| 页签 | 回答什么问题 |
|---|---|
| 总览 | 系统现在好不好？健康自检、最近推送、暂停推送；未配置完时顶部是四步向导 |
| 推送规则 | 我要推谁、推到哪、推什么内容 |
| 机器人 | QQ 这一侧连通吗？连接参数、连通性测试、发送测试消息 |
| 哔哩哔哩 | 账号还在线吗？登录状态、扫码登录、监听中的直播间 |
| 设置 | 调参数。分层展示，原始配置文件编辑收在「高级」内 |

**推送规则不必手写 JSON**：输入 uid 或直接粘贴个人空间链接，界面会先拉取昵称与直播间号
让你确认是不是要的那个人，再勾选「开播 / 下播 / 动态」并填写推送目标即可。
消息模板的占位符做成了可点击插入的标签，右侧实时预览。

**设置页的字段不是手工维护的**，而是由程序读取编译期生成的配置元数据自动生成。因此：
代码里新增配置项，界面自动出现；删除配置项，界面自动消失；配置项的说明直接取自代码里的 Javadoc。
保存时改动逐行写入 `application.yml`，**原有的注释、顺序与格式完整保留**，
并在保存前校验 YAML 语法与字段类型，同时保留最近 10 份带时间戳的备份可供回滚。
配置写坏导致启动失败时会自动进入安全模式，仍可在浏览器里改回来。

**能当场验证配置是否正确**：健康自检逐项给出状态与修复建议，「发送测试消息」直接发一条
并回显接口原始响应——群号写错、Token 不匹配、OneBot 未启动、机器人不在群里这四类错误，
否则表现完全一样：什么都不发生。

配置界面默认仅监听本机回环地址并要求访问令牌，安全说明见 [SECURITY.md](SECURITY.md)。

界面各页的详细用法见[用户手册](docs/user-guide.md)。

## 手动部署

### 前置条件

1. **Java 17+**
2. **一个 OneBot 实现**，例如 [NapCat](https://github.com/NapNeko/NapCatQQ)，用于实际收发 QQ 消息
3. **一个哔哩哔哩账号**，用于读取动态流（建议使用小号，原因见 [SECURITY.md](SECURITY.md)）

> 只推直播不推动态时，哔哩哔哩账号可以不登录——直播状态是公开信息。

### 配置

绝大多数情况下用配置界面即可，以下是手工编辑的对照。编辑 `application.yml`：

```yaml
server:
  port: 7827
  address: 127.0.0.1     # 仅监听回环，务必不要改为 0.0.0.0 除非你清楚后果

starbot:
  adapter:
    onebot:
      senders:
        - name: qq-onebot
          api: /send
          one-bot-address: 127.0.0.1
          one-bot-http-port: 3000
          one-bot-websocket-port: 3001
          one-bot-http-token: <与 NapCat 中配置的一致>
          one-bot-websocket-token: <与 NapCat 中配置的一致>
          # api-token 留空时启动会自动生成随机 Token，本机部署无需配置
```

编辑 `datasource.json` 配置要推送的 UP 主与推送目标。发行包中该文件默认为空数组 `[]`，
可直接启动；完整示例见同目录下的 `datasource.example.json`：

```json
[
  {
    "uid": 12345678,
    "platform": "bilibili",
    "targets": [
      {
        "platform": "qq-onebot",
        "type": 1,
        "num": 987654321,
        "messages": [
          { "handler": "com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler" },
          { "handler": "com.starlwr.bot.bilibili.handler.BilibiliLiveOffPushHandler" },
          { "handler": "com.starlwr.bot.bilibili.handler.BilibiliDynamicPushHandler" }
        ]
      }
    ]
  }
]
```

> **注意**：3.0-beta8 及更早的发行版使用 `event` 字段指定事件类名。上游随后把推送配置
> 改为按处理器指定，本仓库跟随该设计，因此需填 `handler`（处理器类的全限定名）。
> 从旧版升级时需要相应调整 `datasource.json`。

`type` 为 `1` 表示群聊、`0` 表示私聊，`num` 为对应的群号或 QQ 号。

> 该取值对应代码中的 `PushTargetType`（`GROUP(1)`、`FRIEND(0)`）。填入其他数字会被解析为「未知」，
> 运行期直接丢弃对应消息，因此配置界面在保存时即会拦下。

### 运行

```bash
./start.sh
```

首次启动会在终端打印二维码，使用哔哩哔哩客户端扫码登录。凭据会加密保存至 `cookies.json`，
后续启动无需重复登录。

生产环境建议使用 systemd 托管，配置见 [dist/templates/starbot.service](dist/templates/starbot.service)。

容器部署：

```bash
docker build -f dist/templates/Dockerfile -t starbot:3.0 dist/build
docker run -d --name starbot --restart unless-stopped \
  -v starbot-data:/app -p 127.0.0.1:7827:7827 starbot:3.0
```

卷必须挂在 `/app`——配置与登录凭据都写在工作目录下，挂到子目录不会持久化任何东西。
详见[用户手册](docs/user-guide.md#容器部署)。

## 消息模板

推送内容支持在 `datasource.json` 的 `params` 中自定义，可用占位符：

| 占位符 | 适用事件 | 含义 |
|---|---|---|
| `{uname}` | 全部 | UP 主昵称 |
| `{title}` | 开播 | 直播间标题 |
| `{cover}` | 开播 | 直播间封面 |
| `{url}` | 开播、下播、动态 | 跳转链接 |
| `{time}` | 下播 | 本场直播时长 |
| `{action}` | 动态 | 动态动作，如「投稿了视频」 |
| `{picture}` | 动态 | 渲染出的动态图片 |
| `{next}` | 全部 | 消息分条 |
| `{at=all}` | 全部 | @全体成员，仅群聊有效 |

## 内置推送处理器

`datasource.json` 的 `handler` 字段填写处理器类的全限定名：

| 处理器 | 触发时机 | 可用占位符 |
|---|---|---|
| `com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler` | 开播 | `{uname}` `{title}` `{cover}` `{url}` |
| `com.starlwr.bot.bilibili.handler.BilibiliLiveOffPushHandler` | 下播 | `{uname}` `{time}` `{url}` |
| `com.starlwr.bot.bilibili.handler.BilibiliDynamicPushHandler` | 动态更新 | `{uname}` `{action}` `{url}` `{picture}` |

## 事件类型

弹幕、礼物等事件不带默认处理器，需自行编写插件监听。可用的事件类型：

**直播** — `com.starlwr.bot.bilibili.event.live.` 下的
`BilibiliLiveOnEvent`、`BilibiliLiveOffEvent`、`BilibiliDanmuEvent`、`BilibiliEmojiEvent`、
`BilibiliEnterRoomEvent`、`BilibiliFollowEvent`、`BilibiliShareEvent`、`BilibiliLikeEvent`、
`BilibiliLikeUpdateEvent`、`BilibiliFreeGiftEvent`、`BilibiliPaidGiftEvent`、`BilibiliRandomGiftEvent`、
`BilibiliSuperChatEvent`、`BilibiliGovernorEvent`、`BilibiliCommanderEvent`、`BilibiliCaptainEvent`、
`BilibiliConnectedEvent`、`BilibiliDisconnectedEvent`

**动态** — `com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent`

## 插件开发

复制 [templates/starbot-example-plugin](templates/starbot-example-plugin) 作为起点。插件通过
`@StarBotComponent` 注册组件（**不是** Spring 的 `@Component`），通过 `@EventListener` 监听事件；
实现 `StarBotEventHandler` 即可作为推送处理器，在 `datasource.json` 的 `handler` 字段中按全限定类名引用。

构建插件前需先安装本工程：

```bash
./build.sh --skip-tests
```

模块划分、事件流、并发与生命周期约定、配置体系与测试约定见[架构说明](docs/architecture.md)。

## 资源占用

空载实测常驻内存 105–110 MB。默认 JVM 参数已针对小内存机器调校（见 `start.sh`），
完整的实测数据、测量条件与调优手段见 [docs/performance.md](docs/performance.md)。

## 许可证

AGPL-3.0，见 [LICENSE](LICENSE)。

依据 AGPL-3.0 第 13 条，如果你修改本程序并通过网络向他人提供服务，必须向使用者提供你所修改版本的完整源码。
