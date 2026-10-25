# 观众账本

账本在 `nova-core` 中提供给后续跨场统计使用，目前没有页面、配置项、下播回调、启动补漏或定时更新。归档仍是数据来源，账本只是派生文件。

## 存储与重建

位置为 `novabot.core.live.live-data-path` 所在目录下的 `audience-ledger/`，与 `sessions.jsonl`、`details/` 并列。使用 UTF-8 纯文件，不需要数据库或原生库。

```text
audience-ledger/
  privacy.json
  <platform>-<streamerUid>/
    index.json
    <startTime>.jsonl
```

`index.json` 记录 `version`、`platform`、`streamerUid`、`ledgerStart`。起点是最早入账的场次，包含没有名单的旧场，不能理解为主播首次开播或观众第一次来过。`privacy.json` 保存本次更新已处理的拒绝名单编号，不保存昵称。

每份场次 JSONL 首行是 `{version: 1, session: {...}}`。`session` 保存平台、主播编号、开播毫秒、`hasUserSets`、`qualified`、`listQuality`。后面的每行是一位观众在本场的记录，按观众编号升序排列。一个场次即使没有名单也有首行；不能通过后面没有观众行推断零人。缺失或空对象的 `userSets` 与 `LiveSession.hasUserSets()` 一致，表示缺名单；具有空数组名单的对象表示已记录名单但无人。

观众行包含 `platform`、`streamerUid`、`startTime`、`viewerUid`、`nickname`、`streamer`、`entered`、`liked`、`spoke`、`firstDanmuAt`、`danmuCount`、`followed`、`giftYuan`、`guardLevel`、`guardMonths` 和逐次上舰的 `guards`。无昵称、无首条弹幕、无上舰等级时相应值为空。金额用十进制定点数累加，单位为元；月数只认归档声明的「月」和「年」，年乘十二，其他单位的月数未知。没有上舰时月数为零，只要有一次期限未知，合计月数也未知。逐次记录保留时刻、等级、原始数量、单位和换算月数；`guardLevel` 为本场最后一次上舰等级。

文件按场分开，更新只在内存保留一场的名单和聚合结果。弹幕与事件逐行读取，不积攒原文；场次归档第一遍仅保留场次键和最后一行的位置编号，第二遍逐场处理。历史查询逐场扫描派生行，不读取原始弹幕。这样内存不会随主播累计观众数或弹幕总数增长。当前没有额外的按观众倒排文件，单个观众的全历史查询耗时随该主播账本行数增长。

账本文件可整份删除，再调用 `rebuild()` 重建。它不含原始归档之外的资料，不需要单独备份；应备份原始 `sessions.jsonl` 和 `details/`。如果原始明细已经按保留期删除，对应部分不能再重建，删除账本前须确认原始归档仍在。重建先写同级的 `audience-ledger.building/`，成功后换上；旧目录短暂存为 `audience-ledger.previous/`，随后删除。遇到换名中断，下一次显式调用或查询会恢复旧目录；同时清除未完成的重建目录和临时文件，避免中断残件保留已排除观众的副本。这只恢复文件，不扫描归档补数据。异常会向调用方抛出，不把失败当成零观众。

## 入口与查询

服务类是 `org.frostnova.nova.core.service.AudienceLedgerService`，数据类型在 `org.frostnova.nova.core.analytics.AudienceLedger`。

| 方法 | 用途 |
| --- | --- |
| `updateStreamer(platform, uid)` | 补指定主播尚未入账的已封存场次；同时清除所有主播账本中的拒绝名单观众 |
| `rebuild()` | 从当前归档整份重建所有主播的账本 |
| `sessions(platform, uid)` | 按开播时刻升序返回场次属性，包括缺名单、不合格的场次 |
| `ledgerStart(platform, uid)` | 返回最早入账场次；无场次时为空 |
| `sessionRows(platform, uid, startTime)` | 返回该场全部观众行；须结合场次属性区分未入账、缺名单与空名单 |
| `viewerHistory(platform, uid, viewerUid)` | 返回账本起点、全部来场行，以及每次来场之前的场数、首次和上次场次 |

查询不会触发增量更新。服务内的维护和查询共用进程内锁，避免查询看见重建或清理到一半的目录；部署应只由一个进程维护同一数据目录。

## 统计口径

只接受有 `detail.json`、没有 `resumed` 的场次，判法与 `LiveDetailArchive` 一致。已入账后又续播的场次，在下一次更新时撤下；再次封存后重新纳入。`sessions.jsonl` 同平台、同主播、同开播时刻重复时用最后一行。其余已入账场次不重读明细；修改原始封存归档后须整份重建才能反映修订。

观众范围严格来自该场 `userSets` 所有名单的并集。编号零和拒绝名单中的观众不入账；主播本人保留并标记 `streamer=true`，后续新观众统计应排除。先取 `rankings.enter_users` 的昵称作为回退，再从弹幕和事件取时间最后的非空昵称；同毫秒以文件后行优先，两文件同毫秒时事件优先。弹幕文件中的所有类型都计入开口和条数，包括 `DANMU`、`EMOJI`、`SUPER_CHAT`。事件的 `follow` 表示关注；`gift`、`box`、`guard` 累加 `val`，不使用付款 `pay` 或盲盒盈亏 `pft`。

`qualified` 严格要求时长大于 600 秒、结束原因不是 `UNCLOSED`、有名单。`listQuality` 直接调用 `StreamerInsights.listQuality`，与主播页共用 `missing`、`unknown`、`anonymous`、`complete` 四种值。来场史包含所有入账场次；是否只统计合格场次由后续规则明确选择，不在此处默默过滤。

拒绝名单复用 `AssistantProperties.excludedUids`。新增排除编号后，下一次任意主播更新都会物理移除所有已有账本中的对应行；查询还会即时过滤当前拒绝名单。拒绝名单缩减时，请调用 `rebuild()` 恢复此前没有保存的行，增量更新会明确提示重建。清理失败会抛错，重试可继续清理。

这些接口保留了逐场质量、账本起点、主播标记与观众逐次经历，后续可以据此选择合格场次、排除主播本人、辨别首次有记录和真正新人，再计算跨场返回情况。本功能不计算规律，也不生成小结或发送私聊。
