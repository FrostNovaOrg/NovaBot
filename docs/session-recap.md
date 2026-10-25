# 场次事实与跨场规律

`SessionRecapService.calculate(platform, uid, startTime)` 位于 `nova-core`，只读取已经建立的观众账本与归档，不更新账本。调用方应先调用 `AudienceLedgerService.updateStreamer`。未入账的目标场次返回空结果，读取账本或事件失败会抛出异常。

结果类型是 `analytics.SessionRecap.Result`，包含 `facts` 与 `patterns`。计算返回全部达到条件的规律候选，后续展示方再选至多两条；这里不生成文字或发送消息。

每个 `Item` 包含 `key`、`streamer(platform, uid)`、`session`（目标开播毫秒）、`values`、`sample(sessions, from)`、`people`、`evidence(path, selector)`。`strength` 仅规律有值，事实为 null。金额为元，比例为 0–1，时刻为毫秒。`from` 是实际使用场次的最早开播毫秒，证据路径相对直播归档根目录。涉及新人识别的样本包含此前用于排除已见观众的全部账本场次。

## 共同规则

所有场次先截断到目标开播时刻，再做分组、取最近场次、判断样本成熟。合格场次同时要求账本 `qualified=true` 与 `listQuality=complete`。主播本人、编号零与当前拒绝名单中的人不会进入观众计数或名单。名单不完整的场次仍提供“此前已经见过此人”的证据，但不产生新人批次，也不参与回流窗口、常客或时段与 PK 规律。

目标之前账本不到十场时，不产生新人或回流结果；历史新人批次也要在各自开播前已有十场记录。新人判断检查此前所有账本场次，而不限于合格场次。零是已知结果时可以输出；字段、事件文件或分母缺失时不填假零。

## 事实

| key | 计算 |
| --- | --- |
| `duration` | `sessions.jsonl` 的 `startTime`、`endTime`、`durationSeconds / 60`，分别为 `start`、`end`、`durationMinutes`。结束时刻或时长无效则省略。 |
| `watched_rank` | 主播页直接展示的归档指标 `watched_count`，输出 `watched`；在截至目标最近十个合格场次中，`rank=1+严格大于本场的场数`，并列同名次；`outOf` 是该窗口有此指标的场数。目标不合格时仍可与这个窗口比较，但目标不加入窗口。 |
| `gift_rank` | 同上，直接取主播页的 `gift_value` 为 `gift`；不加醒目留言、大航海、付款或盲盒盈亏。 |
| `newcomers` | 目标须合格且记录足够。当前观众此前从未入账的人数为 `newcomers`，其中 `spoke=true` 的人数为 `newcomerSpoke`。 |
| `fan_club` | 明细 `gifts` 按名字精确匹配“粉丝团灯牌”，累加 `count` 为 `fanClub`；没有匹配项不出。 |
| `follows` | 当前 `events.jsonl` 中 `t=follow` 的行数为 `follows`。文件存在且为空时为零，文件缺失时不出。 |
| `returning_newcomers` | 上一合格场次中开口的新人总数为 `previousSpoke`，与当前合格场次观众求交集为 `returned`，交集编号升序放在 `people`。上一场记录不足时不出。 |
| `top_gifters` | 明细 `rankings.gift_users` 先过滤主播与拒绝名单，再按金额降序、编号升序取前三。编号放 `people`，对应金额列表放 `values.gift`，顺序一一对应。 |
| `top_words` | 明细 `words` 按频次降序、同频按词升序取前三，词列表放 `values.words`。 |

前三项与排名都使用原有归档字段；灯牌、礼物榜、热词读取的 `LiveDetail` 与归档报告图共用。关注按事件行计，不用关注观众去重数替代。

## 规律

| key | 计算与样本 |
| --- | --- |
| `newcomer_speak_return` | 只纳入截至目标已完整观察后续三个合格场次的新人批次，每位新人只计一次。开口组回流人数/开口人数为 `spokeReturn`，未开口组同理为 `silentReturn`；人数为 `spokeN`、`silentN`。开口组至少三十人。`todayRate` 是本场新人开口比例；`usualRate` 是目标之前最近十个合格场次中已知且新人分母非零的开口率中位数。本场无新人、未开口组无人或没有可计算的平时开口率时，不输出无法比较的规律。 |
| `slot_compare` | 按东八区开播小时除以六分四段，选择场数最多的两段，同场数按时段先后。各至少三场，所有入选场次的看过与礼物指标都已知。`slotA/B` 为凌晨、上午、下午、晚上之一；`sessionsA/B` 为场数，`watchedA/B` 与 `giftA/B` 为各自中位数。 |
| `pk_share` | 按事件 `pk` 编号配对 `pk_open` 与 `pk_settle`，区间含开不含结算，截在开播与下播之间，重叠区间合并。分别累加 `gift/box/guard` 的 `val`，除以对应区间总分钟数，得 `pkGiftPerMin` 与 `normalGiftPerMin`。非 PK 分母也包含有完整事件记录的无 PK 合格场次。至少三个场次有 PK，数量为 `pkSessions`；事件文件缺失或有未结算 PK 的场次不参与比较。 |
| `regulars_absent` | 截至目标最近五个合格场次之前的合格场次为历史段，出现至少三场且占历史段至少四成者为常客，人数为 `regulars`。其中最近五场全没出现者为 `absent`，名单按历史出现场数降序、同场数按编号升序。常客至少十人；目标不合格时最近五场取此前合格场次。 |

中位数在偶数样本时取中间两数的平均数。强度先检查任一组是否不足门槛两倍，是则 `weak`；否则比较两组数值，大值至少为小值两倍且大值非零则 `strong`，其余为 `medium`。两组均零是 `medium`，正数对零是 `strong`。新人按两组人数、门槛三十比较回流率；时段按两段场数、门槛三比较看过中位数；PK 按有 PK 场数、门槛三比较分钟礼物。常客先以十人的两倍判断 `weak`，再判断缺席是否至少一半，分别为 `strong` 或 `medium`。

未完整观察三场的新人批次整体暂不进入回流率，避免仅纳入已经回来的新人造成比例偏高。已知数据不足以求比例、指标缺失或 PK 未结算时省略对应规律，保留其余可算结果。`trying_delta` 尚未实现。
