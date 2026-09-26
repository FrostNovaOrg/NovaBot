# 附录 B　进阶玩法

正文按「在界面上点」写；这一本附录收那些界面之外、偶尔用得上的东西。

## 处理器类名

[第 6 章](06-add-streamer-and-push.md)的通知开关在界面上点就行，用不到类名。
要用自定义插件提供的处理器、或直接手写配置文件 `datasource.json` 时，才需要
下表的全文类名——文件里要写全限定名，照抄即可：

| 处理器全限定名 | 界面上的名字 | 触发时机 | 可用占位符 |
|---|---|---|---|
| `org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler` | 开播通知 | 开播 | `{uname}` `{title}` `{cover}` `{url}` `{at}` `{next}` `{at=all}` |
| `org.frostnova.nova.bilibili.handler.BilibiliLiveOffPushHandler` | 下播通知 | 下播 | `{uname}` `{time}` `{url}` `{next}` `{at=all}` |
| `org.frostnova.nova.report.handler.BilibiliDynamicPushHandler` | 动态通知 | 动态更新 | `{uname}` `{action}` `{url}` `{picture}` `{at}` `{next}` `{at=all}` |
| `org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler` | 下播报告 | 下播 | `{uname}` `{report}` `{url}` `{next}` `{at=all}` |

**前缀不止一种**：动态通知与下播报告住在报告插件里（`org.frostnova.nova.report.handler.`），
另两个在哔哩哔哩插件里。`{next}` 的意思是「从这里分成两条消息」——在界面的模板
编辑器里不用写它，多建一张卡就是多一条（见第 6 章）。

> [!NOTE] 从 starbot 时代升上来的老配置不用急着改：旧全名（`com.starlwr.bot.`
> 开头的）仍被认，日志里会提醒一句「这个处理器现在叫什么、建议改过来」。
> 其中动态通知与下播报告原先住在哔哩哔哩插件包下，照上表改过来即可，
> 改之前推送照常。
