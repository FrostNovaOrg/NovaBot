# 附录 D　从上游 StarBot 迁移

NovaBot 是上游 StarBot 的整理版本，配置与数据的格式有几处变化。
从上游 3.0-beta8 迁过来，**只有一处必须改**，其余是改名与搬家。
这一本附录把差异列全，照着做完就能接着用。

## 必须改一处：推送内容的指定方式

`datasource.json` 里推送内容的写法变了——beta8 按事件类名，本项目跟随上游公开源码、
按处理器类名：

```jsonc
// beta8 二进制版：按事件类名
{ "event": "org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent" }

// 本项目：按处理器类名
{ "handler": "org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler" }
```

每条推送内容都要照第二种写。要用的处理器全名见
[附录 B 的处理器类名表](appendix-b-advanced.md#处理器类名)。

## 配置根键改名

配置根键从 `starbot:` 改成了 `novabot:`，旧的不读。要手工改名，改完重启；
个别整节挪过位置的，启动日志会点名挪到哪。对照与做法见
[第 13 章的「配置要不要跟着改」](13-upgrade-and-backup.md#配置要不要跟着改)。
旧键里 5.4 起不再识别的不止根键一处（告警、事件输出等也挪了位），
启动日志的 WARN 会逐条说明没被读取、该挪到哪。

## 登录凭据可以沿用

`cookies.json` 可直接沿用：加密存储默认开着，文件若仍是明文，首次启动会迁成加密，
并把原文件备份为 `cookies.json.plain.bak`。不用重新扫码。那份备份是明文凭据，程序不读它，
确认能正常登录后就把它删掉。

## 插件与包名

- 包名已是 `org.frostnova.nova.*`，构件坐标已是 `org.frostnova.nova:nova-*`
- **上游 StarBot 的插件不能直接装上就用**，要按新坐标重新构建
- 旧处理器全名（`com.starlwr.bot.` 开头的）仍被认，日志里会提醒改成现名字，改之前推送照常
- 旧名字的内置插件文件（`starbot-*.jar`）与 `StarBotCore.jar` 要自己删，见[第 13 章](13-upgrade-and-backup.md)

直播数据不用转格式，`application.yml` 与 `datasource.json` 按上面改完就接着用。
归档（`sessions.jsonl`、`details/`）从 4.1.0 起才有，beta8 时代没有留过，不会追溯。

## 和上游的关系

本仓库是上游的整理版本，遵循 AGPL-3.0，含一项推送接口鉴权的安全修复。详见
[NOTICE](https://github.com/FrostNovaOrg/NovaBot/blob/main/NOTICE) 与
[更新日志](https://github.com/FrostNovaOrg/NovaBot/blob/main/CHANGELOG.md)。
