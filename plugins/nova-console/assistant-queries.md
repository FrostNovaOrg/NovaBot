# 智能助手查数服务

入口为 `org.frostnova.nova.console.assistant.AssistantQueryService`，由 Spring 注入，沿用控制台现有启用条件。所有入口返回 fastjson2 `JSONObject`，前两个参数均为 `String platform, Long uid`，只读这一位主播。没有新增接口或配置。

所有结果都有 `source`（中文数据出处）。无法提供数据时，对应数据字段为 null，`reason` 说明原因。空文件与不存在的文件不同：前者可以确认为零条，后者未知。时间戳为 Unix 毫秒，时长为秒，金额为元，比例 0.5 表示 50%。统计日边界沿用主播页。调用方序列化时应启用 `JSONWriter.Feature.WriteNulls`，保留未知值。

普通列表封装为 `{items, total, truncated}`：`items` 为返回内容，`total` 为裁剪前数量，`truncated` 表示没有展示全部内容。趋势、粉丝和贡献者保留其专用数组字段，在同一对象给出 `total`、`truncated`。周期结果的辅助列表保留原数组，在旁边追加 `<字段名>Total`、`<字段名>Truncated`；`daily` 至多 360 项，其他辅助列表至多 120 项。裁剪只改变返回列表，不改变统计数值。

## 场次列表

```java
JSONObject sessions(String platform, Long uid, int days, int limit)
```

`days` 为 1–360；`limit` 夹到 1–20。按窗口内开播时刻筛选，新的在前。已知开播时刻的当前场次也会出现，替代同开播时刻的旧归档。顶层为 `source`、`days`（窗口天数）、`items`、`total`、`truncated`。

每场字段如下，已下播场次直接复用主播页的 JSON 转换，额外派生指标沿用同一平台目录：

| 字段 | 含义 |
| --- | --- |
| platform / uid / uname / roomId | 平台、主播标识、归档昵称、房间号；当前场次身份附属信息未知时为 null。 |
| startTime / endTime | 开播、下播时刻；尚未下播时 endTime 为 null。 |
| durationSeconds / effectiveSeconds | 时长、有效采集时长；尚未下播时均为 null。 |
| listQuality | 名单完整度，与主播页相同；当前未归档场次为 null。 |
| metrics | 归档的各项指标原值，未知项不补零。 |
| userCounts | 归档的各计分表参与人数原值。 |
| hasUserSets | 是否留下过名单；原始观众名单不返回。 |
| hasPeaks / peaks | 是否有峰值记录、各指标的 `{at, value}` 峰值。 |
| titles | 标题与分区轨迹的列表封装，至多 120 条；每条为 at、title、area。 |
| titleChangeCount | 全场标题改动次数，初始标题不算改动。 |
| endReason / endReasonText / interrupted | 下播原因码、人话原因、是否被中断；尚未下播时均为 null。 |
| maintenanceGapSeconds / roomOutageSeconds | 停机缺口与单房断线缺口，分开给出，不相加；当前未归档场次为 null。 |
| revenue | 按主播页 revenueKeys 相加的流水。 |
| messages | 平台目录指定的弹幕计数原值。 |
| audience | 同主播页将观众名单取并集后的去重人数；没有名单为 null。 |
| peakOnline | online_count 的峰值；没有该峰值记录为 null。 |
| unfinished | 是否尚未下播。 |
| reason | 存在未知字段时说明原因。 |

## 单场概况与对比

```java
JSONObject session(String platform, Long uid, long start)
```

| 字段 | 含义 |
| --- | --- |
| current | 指定开播时刻的场次，字段同场次列表。 |
| previous | 它之前紧邻的一场，字段同上；没有则为 null。 |
| median | 之前至多五场已下播场次的逐项中位数，忽略各项未知值。 |
| median.sampleCount | 采用的已下播场次数。 |
| median.metricSampleCounts | 每个指标实际有值的样本数，可能小于 sampleCount。 |
| versusPrevious / versusMedian | 与上一场、中位数的比较，按指标名组织。 |
| *.difference / *.changeRatio | 当前值减基准值、差除以基准绝对值；未知值不比较，零基准变化率为 null。 |
| sampleInsufficient | sampleCount 小于 3。 |
| unfinished | 当前场次是否尚未下播。 |

中位数和比较涵盖 `durationSeconds`、`effectiveSeconds`、`revenue`、`messages`、`audience`、`peakOnline` 六项。标题和分区为文字，不参与数值比较。

## 在线曲线与掉人时段

```java
JSONObject curve(String platform, Long uid, long start, String metric, int maxPoints)
```

`metric` 为 null 或空白时使用明细的 `online_count` 键。`maxPoints` 夹到 2–120；原曲线不足两点时原样返回。数据来自 `LiveDetailArchive.read` 的 series，不用当前内存曲线补历史明细。

| 字段 | 含义 |
| --- | --- |
| metric | 实际查询的曲线键。 |
| curve | 曲线点的列表封装；每条 at 为时刻，value 为数值；均匀抽稀并保留首末点。 |
| drops | 掉人时段的列表封装，最多三段，按下降人数降序且互不重叠。 |
| drops.total | 以终点去重的正下降候选数，每个终点先取十分钟内的最大下降；重叠候选也计入总数。 |
| drops.selection | 候选选择规则说明；并列最大下降优先耗时更短者。 |
| drops.items[].fromAt / fromValue | 下降起点时刻与人数。 |
| drops.items[].toAt / toValue | 下降终点时刻与人数，与起点相距至多十分钟。 |
| drops.items[].drop / dropRatio | 下降人数、下降人数除以起点人数。 |
| titles | 本场标题、分区轨迹的列表封装，每条 at、title、area，最多 120 条。 |

掉人计算在完整曲线上完成，独立于曲线抽稀；未归档的曲线返回 null 和 reason。

## 时间窗弹幕

```java
JSONObject danmu(String platform, Long uid, long start, long fromAt, long toAt, int limit)
```

窗口为 `[fromAt, toAt)`，fromAt 不早于 start。limit 夹到 1–30。逐行读取本场 danmu.jsonl，最多扫描 500000 行，不调用收集全场原文的读取方法。原话按文件顺序取样，不保证是窗口内最新发言。

| 字段 | 含义 |
| --- | --- |
| fromAt / toAt | 本次查询的窗口边界。 |
| total | 窗口内有效记录条数，包含三种发言类型。 |
| speakers | 窗口内已知 uid 的去重发言人数；匿名 uid 不编造成一位观众。 |
| byType | DANMU、EMOJI、SUPER_CHAT 各有多少条。 |
| items | 至多 limit 条原话；每条 at、uid、uname、type、text。 |
| items[].text | 截到最多 60 个 Unicode 字符，表情字符不会被拆断。 |
| truncated | 原话样本不全，或文件超过扫描上限。 |
| words | 高频词列表封装，最多十项；每项 word、count。 |
| partial / invalidLines | 是否含跳过坏行或超过扫描上限、跳过的坏行数。 |
| reason | 文件缺失或统计只能给出下界时的说明。 |

词频使用 Java 中文词边界切分，忽略纯标点和空白，拉丁字母转小写；按次数降序、词面升序并列排序。未知类型与既有归档读法一致，归入 DANMU。内存中保留计数、去重 uid、词频和受限样本，不保留全场原文。

## 收入构成与贡献者

```java
JSONObject revenue(String platform, Long uid, long start)
```

| 字段 | 含义 |
| --- | --- |
| totalAmount | 按主播页平台目录的 revenueKeys 计算的总额。 |
| components | 总额所用各指标键与对应金额，可与 totalAmount 核对。 |
| gift / giftPaid | 礼物价值、礼物观众实扣；未记录为 null。 |
| superChat / guard | 醒目留言、大航海价值；未记录为 null。 |
| guardOpen / guardRenew | 大航海新开、续费价值，从事件的 ot、val 求和；必须与 guard 总额核对一致，否则均为 null。 |
| guardReason | 大航海金额无法拆分的原因，包括旧档缺操作类型。 |
| paymentReason | 无法确认完整实扣付费人数的原因。 |
| payingUsers | 实扣大于零的礼物、大航海事件及醒目留言发送者去重人数；事件金额须与归档核对，醒目留言条数须与原文核对；证据不足为 null。 |
| contributors | 前十位正金额贡献者；每项 uid、uname、amount，按金额降序、uid 升序。 |
| total / truncated | 正金额贡献者总数、是否有贡献者未展示。 |
| top3Share / top10Share | 前三、前十贡献金额除以场次总额；总额为零或没有榜单时为 null。 |
| concentrated | top3Share 大于等于 0.5；无法求占比时为 null。 |
| unfinished | 是否尚未下播；尚未归档的贡献榜不编造为零人。 |
| basis | 总额与实扣口径的说明。 |
| reason | 分人流水榜或拆分数据缺失的说明。 |

当前主播页目录实际使用 `gift_value + super_chat_value + guard_value`，分人流水榜也沿用价值口径；`gift_paid` 单独保存礼物实扣。背包礼物、盲盒等会使两者不同。本服务保留主播页总额算法并明确给出这一区别，不能将 totalAmount 解读为实扣总额。payingUsers 单独根据实扣证据计算，不拿正价值贡献者人数代替；旧事件缺少实扣金额（包括未记录 pay 的盲盒事件）时，宁可给 null。

## 周期汇总

```java
JSONObject insights(String platform, Long uid, int days)
```

直接复用 `StreamerController.insights`，days 只接受 7、30、90、180、360。顶层 `success` 表示查询成功，`days` 为参数，`living` 为主播页判断的当前直播状态，`insights` 为本期表现；另有公共 `source`、缺失时的 `reason`。

| insights 字段 | 含义 |
| --- | --- |
| from / to | 本期时间边界。 |
| metricsKnown | 平台是否提供统计口径。 |
| sessions / durationSeconds | 场次数、累计时长。 |
| revenue / messages / audience / interactionAudience | 流水、弹幕数、去重观众数、去重互动观众数。 |
| composition.firstRecorded / returning | 首次有记录的观众数、回访观众数。 |
| firstRecordedAt | 最早场次的开播时刻。 |
| coverage.complete / anonymous / unknown / missing | 名单完整、含匿名、匿名情况未知、无名单的场次数。 |
| fans | 粉丝变化；change、fromAt、fromValue、toAt、toValue、partial 分别为变化量、起点时刻与数值、终点时刻与数值、是否只覆盖部分窗口。 |
| daily | 逐日数值；每项 date、sessions、durationSeconds、revenue、messages、audience、follows、starts 分别为日期、场次数、时长、流水、弹幕数、观众数、新增关注、开播时刻列表。 |
| follows | 新增关注次数。 |
| capture.effectiveSeconds / gapSeconds / gapSessions | 有效时长、缺口时长、有缺口场次数。 |
| monitoring.recording / watchedSeconds / elapsedSeconds / observableDays / rangeDays | 是否记录监听、实际监听秒数、已过秒数、可证实天数、窗口天数。 |
| previous | 上期同进度结果，字段同本期，不再嵌套上期和比较。 |
| comparisons | 主播页五项比较；各项 changeRatio、reasonCode、reason 为变化比例、无法比较原因码和说明。 |
| comparisonNote | 比较窗口说明。 |

## 趋势

```java
JSONObject trend(String platform, Long uid, String period, int days)
```

直接复用 `StreamerController.rangeTrend`。period 为 day、week、month；days 与周期汇总相同。顶层为 `success`、`days`、`trend` 和公共出处字段。

`trend.period` 为分桶周期，`buckets` 为最近至多六十桶，`total`、`truncated` 为裁剪前桶数和截断标记。`metrics` 为指标说明（key、name、unit、money），`metricsKnown` 表示有可累加指标说明。

每桶的 `label`、`start`、`end` 为标签与边界；`sessions`、`durationSeconds`、`effectiveSeconds` 为场次数、时长、有效时长；`metrics`、`userTimes` 为指标合计和参与人次；`revenue`、`messages`、`audience`、`follows` 为流水、弹幕数、观众数、关注数；`starts` 为开播时刻列表；`coverage` 同周期汇总。day 桶还有 `monitoring`（recording、watchedSeconds、elapsedSeconds）和 `status`（主播页日历状态）。

## 粉丝变化

```java
JSONObject fans(String platform, Long uid, int days)
```

直接复用 `StreamerController.fans`，days 与周期汇总相同。顶层为 `success`、`days`、`fans` 和公共出处字段。`fans.points` 为最近至多 120 个 `{at, value}` 快照；`total`、`truncated` 为裁剪前快照数和截断标记；`change` 为窗口内粉丝净变化；`partial` 表示快照不能完整覆盖窗口。没有任何可用数据时 fans 为 null，reason 说明原因。

## 筛选与分组汇总

模型工具 `aggregate` 查询已归档的 B 站事件、场次、逐分钟人数、弹幕和观众活动。主播由调用方绑定。问到数量、合计、排行和分布时优先使用这个工具，以工具计算的数字作答。

| 参数 | 取值 |
| --- | --- |
| `kinds`（必填） | `gift` 礼物、`box` 盲盒、`guard` 上舰、`super_chat` 醒目留言、`follow` 关注、`pk_settle` PK 结算、`session` 场次、`minute` 逐分钟人数、`danmu` 文字与表情弹幕、`audience_activity` 观众活动。前四种可以合选，合选时允许 `count`、`sum:amount`、`distinctAudience`、`distinctPayingAudience`。 |
| `from`、`to`（必填） | `YYYY-MM-DD` 的统计日，首尾都含，最多 366 天。 |
| `filters` | 至多 12 个 `{field, op, value}` 条件，须同时满足。取值见下表。 |
| `groupBy` | 至多两个不同的键。`day` 为统计日，`week` 为该统计日所在周的周一日期，`month` 为该统计日所在月。`hour` 为北京时间钟点（0–23），`weekday` 为该统计日的星期几（周一为 1）。`session` 为开播时间戳，`kind` 为记录种类。也可用 `name`、`openedGiftName`、`audience`、`guardOperation`、`guardLevel`、`pkResult`。`audienceAge` 适用于带观众的记录，以 `new`、`returning` 表示新老观众。`minute` 用于逐分钟或逐条明细，可搭配 `session`。省略或传空数组时只汇总一次。 |
| `bucketMinutes` | 选 `minute` 分组时可填 1–1440 的整数，默认 1。以开播所在的分钟为第一组起点，分组值为各组起点的毫秒时间戳。查询窗外的分钟不计入，跨窗的组仍保留原起点。 |
| `metrics`（必填） | 1–8 个不同的汇总列。`count` 计记录条数，`distinctAudience` 计已识别的不同观众，`distinctPayingAudience` 计付费事件或观众活动里的不同付费观众。`sum:quantity`、`sum:amount` 分别求个数和金额合计。场次的 `durationSeconds`、`effectiveSeconds`、`messages` 也支持 `sum:`。单选记录种类时，任一适用的数值字段都可用 `max:字段`、`min:字段`、`avg:字段`。 |
| `sort` | `{by, direction}`，以选中的一个分组键或汇总列排序，方向为 `asc` 或 `desc`。默认按分组键升序，未知值始终在最后。并列按分组顺序固定排列，其中观众用本机顺序。 |
| `limit` | 可选，排序后取前 1–50 行。省略时最多 59 条数据行，加上合计共 60 行。 |

| 筛选字段 | 适用记录与取值 |
| --- | --- |
| `name`、`openedGiftName` | `name` 为礼物名或盒名，`openedGiftName` 为盒里开出的礼物名。支持 `eq`（等于）和 `contains`（包含），区分大小写。 |
| `quantity` | 礼物、盲盒、上舰记录里的个数。 |
| `amount` | 礼物、盲盒、上舰、醒目留言、场次或观众活动的金额。观众活动里的非流水记录金额为 0。 |
| `pkMyVotes`、`pkOpponentVotes` | PK 结算时的本方票数、各对手票数之和。任一对手缺票数时，后者为未知。 |
| `pkResult` | `win` 胜、`loss` 负、`draw` 平。 |
| `guardOperation`、`guardLevel` | 上舰操作为 `open` 新开、`renew` 续费。等级为 `captain` 舰长、`commander` 提督、`governor` 总督。 |
| `audience` | 礼物、盲盒、上舰、醒目留言、关注、弹幕或观众活动可用已有的观众代号（`观众-xxxxxx`）筛选或分组。 |
| `text` | `danmu` 的文字或表情名，支持 `eq`、`contains`，区分大小写。 |
| `startTime`、`endTime`、`durationSeconds`、`effectiveSeconds` | `startTime` 适用于所有种类，用开播毫秒时间戳配合 `eq` 可选一场。其余三项为场次的下播时间戳、全场时长与有效采集秒数。 |
| `messages`、`peakOnline`、`watched`、`sessionAudience` | 场次弹幕条数、在线峰值、看过人数、名单去重人数。 |
| `online`、`watched` | `minute` 的该分钟最高在线人数和最高看过人数。 |

数值条件填写数字，支持 `gt` 大于、`gte` 大于等于、`lt` 小于、`lte` 小于等于、`eq` 等于。PK 结果、上舰细分、观众代号只支持 `eq`。合选时，每个筛选和分组字段须适用于选中的所有种类。

例如，查 11 月本方票数过 100 的胜场。

```json
{
  "kinds": ["pk_settle"],
  "from": "2026-11-01",
  "to": "2026-11-30",
  "filters": [
    {"field": "pkResult", "op": "eq", "value": "win"},
    {"field": "pkMyVotes", "op": "gt", "value": 100}
  ],
  "metrics": ["count"]
}
```

- `columns` 为列名，`rows` 为数据行，`totals` 为同列顺序的合计行。`danmu` 只按 `day` 分组时逐日出行，事件按 `minute` 分组时逐直播时段出行。可读明细内没有匹配记录的时段计 0，缺少明细的时段为 null。其他分组通常只列有匹配记录的组。合计覆盖全部匹配记录，平均和去重人数按原始匹配记录重新计算。`groupCount` 为裁剪前行数，`truncated` 与 `truncationNote` 说明截断。
- `from`、`to` 是实际采用的日期，`fromAt` 含起点，`toAtExclusive` 排除终点。北京时间早上 6 点切换统计日。事件按发生时刻归日，场次按开播时刻整场归日。跨早上 6 点的场次，付费事件和场次流水可能归入不同统计日，回答每天流水时说明采用的是事件发生日还是整场的开播日。`liveUnarchived` 标明时间窗内有未归档的在播场次，`liveNote` 说明这些数据未计入。
- 逐分钟人数读每场 `detail.json` 的 `series.online_count` 和 `series.watched_count`。直播区间含开播时刻及其所在分钟，排除恰在分钟边界上的下播时刻，按格起点归日、钟点和星期。已采到的 0 正常参与计算。平均、最大、最小分别跳过各自缺采的分钟，全缺时显示 null。`expectedMinutes` 为选中场次在日期范围内的直播分钟数，`missingMinutes.online`、`missingMinutes.watched` 为数值筛选前各自缺采的分钟数。`minuteNote` 给出口径。
- 礼物、盲盒、上舰、关注、PK 结算读 `events.jsonl`。弹幕和醒目留言读 `danmu.jsonl`，`danmu` 汇总 `DANMU`、`EMOJI`，`super_chat` 汇总 `SUPER_CHAT`。关键词只用于筛选，正文不进入结果。金额读 `val`。逐条文件在首次写入时创建。文件不存在时，若场次汇总明确为零，或已有 `detail.json` 封存标记且汇总无相应记录，则按空明细处理。其余缺少的文件跳过，`missingDetailsCount` 是受影响的场次数，`missingDetails` 最多列出 60 场的开播时刻和缺少的文件名。逐条明细全缺时，合计为 null。坏行跳过并计入 `invalidLines`。无法判断条件的记录计入 `unknownFilterRows`。命中但缺少汇总字段的记录计入 `unknownMetricRows`，受影响的汇总列返回 null。
- `audience_activity` 读 `sessions.jsonl` 的参与者名单 `userSets`、`events.jsonl` 和 `danmu.jsonl`。`count` 包括逐条活动及每场仅在名单里出现的观众各一次，可用 `distinctAudience` 跨场去重。`audienceAge` 用同一主播全部已归档场次中的首次出现统计日，进房、弹幕、送礼等均可成为首次记录。名单没有逐人的时刻，同场有逐条记录时取最早的逐条时刻，其余名单人员以开播时刻估算。首次在查询范围内为 `new`，范围之前为 `returning`。`historyFrom` 为最早留档记录所在的统计日，即使整个查询窗在它之前也会返回。`historyNote` 提示起点之前来过的人可能计为新观众。
- 首次出现索引按主播逐场建立。直播数据目录下的 `assistant-audience-history/` 保存带版本的本地派生文件，包含观众编号、首次统计日和场次进度。切换主播或重启后可继续使用。追加归档时只处理新场的名单和明细。已处理的场次被替换、移除或补录时重建该主播的索引。删除派生文件或索引版本变化时会从归档重建。
- 每问可用约 20 秒建立索引，也受整问查数时限约束。到时会保存已完成的场次，回包的 `reason` 说明「观众首次出现记录还在建立，已完成 X/Y 场，请稍后再问」，并附 `historyCompletedSessions`、`historyTotalSessions`。下次查询从已保存的进度续建。`historyScannedRecords` 统计本次建索引读取的逐条行数与名单条目数，`historyInvalidLines` 为索引中跳过的历史坏行数（非零时返回）。
- 金额单位为元，按到手价值计算。盲盒用开出物价值，背包礼物也计入流水。场次使用控制台平台目录的流水口径。调用方通过 `AssistantQueryContext.amountVisible()` 提供可见范围，控制台允许查金额。金额隐藏时，金额条件和汇总返回「这里不显示金额」，纯计数查询仍可执行。
- 付费观众按正实扣去重。礼物及上舰用 `pay`，盲盒缺 `pay` 时用存档盒价 `bp` 估算，醒目留言用 `val`。可归因的记录实扣无法判断时，该组付费人数为 null。`payingAudienceBasis` 随结果说明口径。
- 发给模型的观众列只含代号，页面回答沿用本机映射恢复昵称。观众或新老分组、代号筛选、去重观众和付费人数排除拒绝名单。观众分组或新老分组的合计也排除拒绝名单，未做这些分组或代号筛选时，金额合计采用控制台流水的观众范围。
- 查询窗口内选中场次的逐条明细行数、需要处理的分钟数与名单条目共用 500000 条扫描上限，包含读到的其他种类及坏行。首次出现索引使用独立额度。窗口查询超过上限时提示缩短日期。超长归档记录或分组超过内存限制时也返回原因。
