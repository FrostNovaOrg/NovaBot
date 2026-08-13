# 在本机把真源跑起来

给要对接[事件输出](user-guide.md#事件输出)的下游用：**零凭据**，不用扫码、不用配 QQ 机器人，
十分钟内在自己的 Mac 或 Linux 上跑出一条真实的直播事件流。

全程只读，不会推送任何消息，也不会碰你的哔哩哔哩账号——匿名模式压根不读凭据。

## 0. 需要什么

| | |
|---|---|
| Java | **17**。装了更高版本也要显式指向 17，本项目不在 21/26 上构建 |
| 网络 | 能直连哔哩哔哩 |
| 别的 | 没了。不需要 QQ、不需要 NapCat、不需要哔哩哔哩账号 |

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # macOS，Linux 按自己的路径改
```

## 1. 构建

```bash
./build.sh
```

**必须走 `build.sh`**，直接 `mvn package` 或 `mvn -pl <模块>` 会失败，原因见
[架构说明](architecture.md#10-构建)。产物在 `dist/build/`。

构建会跑全部测试，第一次大约两分钟。**看输出的最后几行**确认 `BUILD SUCCESS`——
中间会滚过很多 ERROR 级别的日志行，那些是测试在验证异常分支，不是失败。

## 2. 配置

把 `dist/build/` 整个拷到一个工作目录，然后改 `application.yml` 三处：

```yaml
server:
  port: 7830                  # 随便挑个没被占的

starbot:
  bilibili:
    account:
      anonymous: true         # ← 免登录。不读凭据、不弹二维码
    event-stream:
      enabled: true           # ← 打开事件输出
      path: /nova/events
```

`datasource.json` 填一个房间的**主播 uid**（不是房间号）：

```json
[{"uid": <主播uid>, "platform": "bilibili", "targets": []}]
```

`targets` 留空就是[纯监听房间](user-guide.md#只采集不推送的房间)：照常连接、照常出事件、
**一条 QQ 消息都不发**。这正是你要的形态。

> 房间号 → uid 的换算：
> ```bash
> curl -s 'https://api.live.bilibili.com/room/v1/Room/get_info?room_id=<房间号>' | grep -o '"uid":[0-9]*'
> ```

### 挑一个不会白等的房间

选房间只有一条硬要求：**它得正在播**。下播的房间长连接能连上，但一条业务消息都不会来，
看起来和「程序坏了」一模一样。

```bash
curl -s 'https://api.live.bilibili.com/room/v1/Room/get_info?room_id=<房间号>' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print("uid",d["uid"],"live_status",d["live_status"],"online",d["online"])'
```

`live_status`：**1 = 正在播**，2 = 轮播（放录像，不会有弹幕），0 = 没播。

🔴 **本文里的房间号与 uid 全部是占位值，照抄连不上任何房间。**
这里不列具体房间——写死几个真实房间等于把「这个仓库的人在盯哪些直播间」记录下来，
而房间随时会停播，列表本来也会过期。

自己挑一个的办法：打开 <https://live.bilibili.com/> 任一分区，
点进一个**正在播且人多**的房间，地址栏末段就是房间号，代入上面那条 `get_info` 取 uid。
挑分区时优先选常年有大房间的（如英雄联盟、虚拟主播），验证起来不用等。

**别照抄弹幕速率去做容量估算。** 人气值和弹幕量没有关系——实测有 75 万人气每分钟 0.1 条的
房间，也有 29 万人气每分钟 180 条的房间。

## 3. 起

```bash
java -Dloader.path=lib,plugins-lib -jar StarBotCore.jar
```

`-Dloader.path` **不能省**，少了它启动会报 `NoClassDefFoundError: SpringApplication`。
（用附带的 `./start.sh` 则不用管，它已经带上了。）

日志里这四行说明一切正常：

```
事件输出已启用, 地址: ws://127.0.0.1:<server.port>/nova/events, 仅接受本机连接
匿名模式：个人主播的直播间实测只能拿到约一成弹幕（七格实测 8.8%~12.5%）…
动态推送与自动关注已禁用: 当前为匿名模式，它们必须有登录态
StarBotBilibili 已就绪（匿名模式）
已连接到直播间 <房间号>
```

OneBot 那两行 ERROR（`未配置 OneBot HTTP Token`）**可以无视**：你没配 QQ 机器人，
它就抱怨一句，不影响采集。

## 4. 接上去

```
ws://127.0.0.1:7830/nova/events
```

**只接受回环连接**，且这一点与 `server.address` 无关——即使监听地址改成 `0.0.0.0`，
事件流也只认本机。要从别的机器读就自己建 SSH 隧道。

握手后服务端立刻发 `hello`，里面有 `sessionId`、`capabilities`、`lastSeq`、`bufferedFrom`，
以及一个协议之外的 `notes` 数组——那里写着我们与协议文档的口径差异（点赞未聚合、
`isAdmin` 的真实含义等）。**排查问题前先读它**。

想回补就在 **2 秒内**发 `resume`；不发的话服务端到点自动转入实时流。
迟到的 `resume` 会收到一份新的 `hello`，按它重来即可。

一个 30 行的最小客户端：

```python
import json, websockets, asyncio          # pip install websockets

async def main():
    async with websockets.connect("ws://127.0.0.1:7830/nova/events") as ws:
        async for raw in ws:
            msg = json.loads(raw)
            if msg["kind"] == "ping":
                await ws.send(json.dumps({"v": msg["v"], "kind": "pong"}))
                continue
            print(msg["kind"], msg.get("seq"), msg.get("data"))

asyncio.run(main())
```

**`pong` 必须回。** 三个心跳周期（45 秒）不回话，服务端会把你当成已经死掉的连接断开。

## 5. 匿名源的数据长什么样

对着真数据开发之前先知道这些，否则会照着一份不完整的样本设计界面：

| | 匿名 | 登录 |
|---|---|---|
| 弹幕会不会少 | **可能少很多。** 实测有房间只有 4.3% | 实测 100% |
| 弹幕的 `user.uid` | 恒为 `"0"` | 真实 uid |
| 弹幕的 `user.name` | 只留首字，形如 `b***` | 完整昵称 |
| 弹幕的 `user.face`、`user.medal` | **是真的**，照常下发 | 同左 |
| 点赞的 `user` | **uid 与昵称都是完整的** | 同左 |
| `enter`（进房） | **量很大**，见下 | 同左 |
| `follow` / `share` | 有，但稀少（关注比分享常见得多） | 同左 |

**抹除是按消息类型来的，不是整条连接一刀切。** 别把「匿名源」整体当成「没有 uid」，
要按 `kind` 分别看。

`user.uid` 恒为字符串，`"0"` 是**合法值**而不是缺失——它就是下游认出「这一路是匿名源」的依据。

### ⚠️ 做容量估算时别漏掉 `enter`

**它是仅次于弹幕的第二大类。** 下游实测（网游区某房间、约 34 万在线、匿名模式）：
**5 分钟 629 条，约 120 条/分。**

> 这一行此前写的是「`enter` / `follow` / `share` 恒为 0 条」。那句话在 `63af58f` 时是**对的**
> ——平台把进房消息换成了 protobuf 承载的 `INTERACT_WORD_V2`，旧解析器认不出，
> 这三类事件确实一条都出不来，而且不报错。`0632ce8` 已经修好，本文档当时停在修复之前。
> **照旧版本做的人会往两个方向走偏**：要么以为自己配错了，
> 要么在容量估算里整个漏掉一类占比接近一半的事件。

`enter` 的量随房间差别极大（人来人往的大房间刷屏，冷清房间几乎没有），
**别拿上面这个数当通用值**，按自己的目标房间量一遍。

## 6. 收拾

`Ctrl-C` 即可，优雅停机实测 0.7 秒。工作目录下会留下 `data.json`、`state.json`、
`logs/`，直接删掉整个目录就干净了。**不会有 `cookies.json`** —— 匿名模式从头到尾没碰过凭据。
