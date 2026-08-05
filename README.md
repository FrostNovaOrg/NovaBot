<img src="docs/assets/logo.svg" alt="NovaBot" height="56">

哔哩哔哩直播与动态推送机器人。监听 UP 主的开播、下播与动态更新，通过 OneBot 协议推送到 QQ 群或好友。

**文档**：[用户手册](docs/user-guide.md) · [排障与 FAQ](docs/troubleshooting.md) ·
[架构说明](docs/architecture.md) · [安全说明](SECURITY.md) · [性能实测](docs/performance.md) ·
[更新日志](CHANGELOG.md)

## 特点

- **配置不用写 JSON**。内置配置界面，输入 uid 或粘贴空间链接即可添加主播，
  勾选事件、编辑模板、发测试消息都在界面里完成，每一步当场验证
- **配置界面的字段由代码生成**。读取编译期产出的配置元数据，说明直接取自 Javadoc——
  新增配置项界面自动出现，不存在「界面和代码对不上」
- **改配置不会毁掉你的文件**。逐行写入 `application.yml`，注释、顺序与格式完整保留，
  写入前校验语法与类型，保留最近 10 份带时间戳的备份。配置写坏导致启动失败时会进入安全模式，
  仍能在浏览器里改回来
- **出问题看得见**。健康自检逐项给出状态与修复建议；「发送测试消息」回显接口原始响应，
  把「群号写错 / Token 不对 / OneBot 没启动 / 机器人不在群里」这四种表现完全相同的错误区分开
- **默认不裸奔**。配置界面与推送接口默认仅监听回环并要求访问令牌，支持 IP 白名单与频率限制，
  登录凭据以 AES-256-GCM 加密存储
- **下播自动出数据报告图**。封面横幅、数据卡片、互动曲线、五类排行榜（含观众头像）、
  弹幕词云。版式**按推送目标分别配置**——同一场直播推给不同群可以各展示各的，
  冷清场次会自动收紧、不留空洞
- **收益不会跟着氛围一起发出去**。「谁能看到金额」是**会话自己的属性**，默认私聊显示、群聊隐藏，
  同时管住下播报告与全部数据查询命令。关掉后报告并不残缺：卡片改用人数与条数，
  曲线保留形状只是不标峰值，金额榜整榜不出——大群拿到的是词云与热闹，不是账本
- **群里能直接问**。19 条聊天命令：绑定自己的账号后查「我的数据」、拉排行榜、
  订阅 `开播@我`、直播中随时拉一份实时报告图。命令可按群开关，
  且只有群主 / 群管理员 / 你配置的超管能动
- **后台看得到运营趋势**。按周或月看场次、时长与互动数据，可只看某一位主播。
  这一页刻意不给两个数字：计分表相加是**人次**不是人数，开播时的粉丝数等快照指标**不参与累加**——
  一个看似合理的错数比没有数更糟
- **面向小内存机器**。JVM 参数按 1 GB 内存的 VPS 调校

## 快速开始

Linux 上一条命令完成安装——自动检查并安装 JDK 17 与中文字体、构建、创建 systemd 服务：

```bash
./install.sh
```

启动并查看日志：

```bash
sudo systemctl start starbot && sudo journalctl -u starbot -af
```

日志里有**配置界面地址**（含访问令牌）和**登录二维码**。打开前者，总览页顶部的四步向导
会带着你完成机器人连接、扫码登录、添加主播、发测试消息。

装在远程服务器上时，先在本机建立隧道再访问：

```bash
ssh -L 7827:127.0.0.1:7827 用户名@服务器地址
```

其余用法见 `./install.sh --help`，手动部署与容器部署见[用户手册](docs/user-guide.md)。

## 构建

需要 JDK 17+ 与 Maven 3.9+：

```bash
./build.sh
```

产物在 `dist/build/`：`StarBotCore.jar` 是主程序，`lib/` 为核心依赖，
`plugins/` 与 `plugins-lib/` 为插件及其依赖。可选 `--skip-tests`、`--clean`。

> 构建分两步：`starbot-plugin-processor` 是各插件模块在 build 阶段调用的 Maven 插件，
> 而 Maven 不支持在同一 reactor 内构建并使用同一个插件，因此需先单独安装。`build.sh` 已处理。

## 插件开发

复制 [templates/starbot-example-plugin](templates/starbot-example-plugin) 作为起点。
插件用 `@StarBotComponent` 注册组件（**不是** Spring 的 `@Component`），用 `@EventListener` 监听事件；
实现 `StarBotEventHandler` 即可作为推送处理器。

弹幕、礼物、上舰等事件不带默认处理器，需自行监听——
[可监听的事件类型](docs/architecture.md#可监听的事件类型)列出了全部事件。
模块划分、类加载规则、并发与生命周期约定见[架构说明](docs/architecture.md)。

## 资源占用

空载常驻内存 105–110 MB；**带负载**（4.1.0，监听 2 位主播、直播间长连接与 OneBot 双通道均已建立、
Redis 已启用）实测约 476 MB——**按后者规划机器内存**，512 MB 的机器不够用。
这个量级随主播数与功能开启情况变化明显，详见[性能实测](docs/performance.md)。

## 关于上游

本仓库源自 [StarBot](https://github.com/Starlwr/StarBot)（作者 [LWR](https://github.com/Starlwr)），
在其基础上整合为单一 Maven 工程并做了大量重构，含一项**推送接口鉴权的安全修复**。
包名与配置键沿用 `com.starlwr` / `starbot`，上游生态的第三方插件可直接使用。

完整的改动清单见 [CHANGELOG.md](CHANGELOG.md)，与上游的对应关系与命名取舍见 [NOTICE](NOTICE)，
安全修复详情见 [SECURITY.md](SECURITY.md)。

## 许可证

AGPL-3.0，见 [LICENSE](LICENSE)。
