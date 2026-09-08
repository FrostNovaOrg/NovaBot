# 拿实抓语料回放协议校验

[更新日志](../CHANGELOG.md)里「N 条实抓报文映射出 M 条信封、违例 0」这个数字，
是靠这份流程跑出来的。**写进对外文档的数字得有人能重新跑出来**——
上一份语料（4360 条）随临时目录一起删了，那个数字就再也复现不出来，这份 runbook 是为了不再发生第二次。

跑它的场合：改了 `NovaEventMapper` 或协议校验规则、升了协议版本号、
或者要在文档里更新那组数字。

## 0. 语料不进仓库

语料里有**真实观众的 uid 与昵称**，属于他人的个人信息。因此：

- 语料只留在本机，**不提交、不进制品、不贴进汇报**；
- 需要给别人复现时给这份 runbook，让对方用自己的采集，别传语料；
- 单元测试里的样本一律用占位昵称，那些才是可以进仓库的。

对应地，回放测试 `NovaProtocolCorpusTest` **默认不跑**，只有显式给出语料路径时才启用。

## 1. 采一份语料

任意一台机器上按[《在本机把真源跑起来》](runbook-local-source.md)起一个匿名实例，
`application.yml` 里打开原始报文日志：

```yaml
starbot:
  bilibili:
    account:
      anonymous: true               # 不用凭据
    live:
      live-room-raw-message-log: true
```

再把控制台级别调到 `DEBUG`（`starbot.core.log.console: DEBUG`），
平台的每一条报文就会原样出现在日志里。

**房间要挑杂一些。** 同一个分区的报文形状高度相似，
十万条同质语料的说服力不如两千条跨分区的：虚拟、娱乐、网游赛事各来几个，
匿名态与登录态各有一些，冷清房与高流量房各有一些。
当前记录在更新日志里的那组数字来自 **13 个直播间、15 次采集**。

## 2. 抽成语料文件

每行一条，`msg` 放**平台原始报文的原文**：

```json
{"room": 12345, "msg": "{\"cmd\":\"DANMU_MSG\",\"info\":[...]}"}
```

原文一字不改是这件事的关键——校验的对象必须是平台真给的那份，
我们自己重新序列化过一遍的不算，那样等于用自己的输出验自己的输入。

日志行里报文从 `{"cmd":` 开始，取到行尾即可。读不出来的行（日志被截断）
要单独计数报出来，别当成语料的一部分悄悄跳过。

## 3. 回放

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn -B -Pinstall install -Dtest=NovaProtocolCorpusTest -DfailIfNoTests=false \
    -Dsurefire.failIfNoSpecifiedTests=false -Dnovabot.corpus=/绝对路径/corpus.jsonl
```

⚠️ **必须是 `install`，不能用 `mvn test`。** 插件模块在 build 阶段要调用
`starbot-plugin-processor`，而它需要 `starbot-core` 是一个 jar；停在 `test` 阶段时
core 只有 `target/classes`，于是报 `core/starbot-core/target/classes (Is a directory)`。
这跟 `mvn -pl <模块>` 失败是同一个原因，见[架构说明](architecture.md#10-构建)。

输出形如：

```
语料 25513 条报文 → 解析出事件 20676 个（其中归并器迟发 0 个）→ 信封 16183 条；
不受支持/解析不出/被归并器判重丢弃 4837 条、解析出但映射器不输出 4493 个
按类型: {danmaku=5360, enter=10254, follow=6, gift=139, guard=8, like=391, share=5, superchat=20}
解析出但映射器不输出的事件: {BilibiliLikeUpdateEvent=716, BilibiliLiveOffEvent=1,
                             BilibiliOnlineRankCountUpdateEvent=2385, BilibiliWatchedUpdateEvent=1391}
整条表情弹幕 577 条; 内联表情弹幕 104 条、共 109 项; count 与正文吻合 109 项、不吻合 0 项
```

**「映射器不输出」那一栏要摊开看。** 漏送和有意不送在总数里长得一模一样，
只有按事件类名列出来才分得清——上面那四类是点赞更新、下播、在线排名、看过人数，
它们本就不进事件流（房间级统计走另一条路）。这一栏冒出别的类名就是漏送。

## 4. 这个检查覆盖什么、不覆盖什么

**覆盖**：每条信封的字段齐全性与类型（含「允许为 null 但不允许不存在」）、
`kind` 与字段集是否对得上、内联表情的 `count` 与正文实际出现次数是否一致，
以及**序列化后再校验一遍**。

序列化那一遍必须**与真实出口用同一组参数**：fastjson2 默认丢弃空值，
而协议要求空值键存在。出口（`NovaEventStream`、`NovaEventEndpoint`）用的是
`JSONWriter.Feature.WriteNulls`，回放测试也必须带上它——
第一次写这条测试时漏了，于是刷出 17093 处「缺少 medal/emoji/replyTo」，
那是测试自己的问题，不是产物的。

**不覆盖**：seq 编号与回补（那些在 `NovaEventStreamTest`）、握手、心跳、
以及「平台字段的语义对不对」——校验器只知道协议怎么写，不知道平台的本意。
语料回放能抓的是**没想到的形状**，抓不了理解错了的字段。
