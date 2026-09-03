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
  core:
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

> 🔴 **本节的时序只覆盖 `require-token: false`**（第 2 节那份配置就是）。
> 开了口令时服务端**不会先发 `hello`**，照本节写的客户端会卡住——
> 见本节末尾「[开了口令时的时序](#开了口令时的时序)」。

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

### 开了口令时的时序

上面那份客户端在 `require-token: true` 下会**卡住不动，直到 10 秒后被断开**——
它在等 `hello`，而服务端在等它先开口。**开了口令时服务端在认证成功前什么都不发。**

```
1. 连上                              服务端静默，不发 hello
2. → {"kind":"auth","token":"<口令>"}   必须由客户端先发，10 秒内
3. ← {"kind":"hello", …}             认证成功后才发
4. → {"kind":"resume","data":{"fromSeq":<n>}}   想回补就在收到 hello 后 2 秒内
5. ← 实时事件
```

上面那个最小客户端补一句就能用——在 `async with` 之后、`async for` 之前：

```python
        await ws.send(json.dumps({"kind": "auth", "token": TOKEN}))
```

`token` 是唯一必填项，带上 `v` 也可以（本端不校验它）。几个容易踩的点：

- **超时那一路不发任何解释帧。** 没在 10 秒内出示的连接直接被关，
  关闭码 `POLICY_VIOLATION`、原因「未在时限内认证」——**不会有 `auth_failed`**，
  因为没人出示过任何东西。口令错才先发 `auth_failed` 再关。
- **认证之前除 `auth` 外一律不理会**，`resume` 尤其：它能问出缓冲窗口从哪一条起，
  那是关于这个部署的信息。所以 `resume` 只能排在 `hello` 之后。
- **2 秒的回补窗口从 `hello` 发出那一刻起算**，不和 10 秒认证窗口抢时间。
- **别把口令放进握手**：`Sec-WebSocket-Protocol`、`Authorization` 一律拒收（HTTP 400），
  `?token=` 也不接受。

口令怎么签见[使用说明](user-guide.md#开了口令时客户端怎么认证)。

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
