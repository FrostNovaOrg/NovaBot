# 用户手册

NovaBot 监听哔哩哔哩 UP 主的开播、下播与动态更新，通过 OneBot 协议把消息推送到 QQ 群或好友。

出问题时请看[排障与 FAQ](troubleshooting.md)。

## 1. 它需要哪些东西

```
哔哩哔哩  ──拉取──▶  NovaBot  ──HTTP──▶  OneBot 实现  ──▶  QQ 群 / 好友
（一个账号）        （本程序）        （NapCat 等）
```

| 你需要准备 | 说明 |
|---|---|
| 一台能长期开机的机器 | Linux / macOS / Windows 均可，1 GB 内存足够 |
| Java 17 或更高版本 | 一键安装脚本会自动装 |
| 一个 OneBot 实现 | 推荐 [NapCat](https://github.com/NapNeko/NapCatQQ)，它负责真正登录 QQ 并收发消息 |
| 一个哔哩哔哩账号 | **建议用小号**。用于读取动态流，原因见 [SECURITY.md](../SECURITY.md) |

NovaBot 自己不登录 QQ，只是把消息交给 OneBot 实现去发。所以先把 NapCat 跑起来并登录好 QQ，
再装 NovaBot。

**只推直播不推动态的话，哔哩哔哩账号可以不登录**：直播状态是公开信息，不需要登录态。

## 2. 安装

### Linux 一键安装

```bash
./install.sh
```

脚本会依次完成：检查并安装 Java 17、构建、安装到 `/opt/starbot`、生成配置、创建 systemd 服务。

可选参数：

| 参数 | 作用 |
|---|---|
| `--dir /srv/novabot` | 指定安装目录（默认 `/opt/starbot`） |
| `--port 7827` | 指定服务端口 |
| `--no-service` | 跳过 systemd 服务创建 |

启动并看日志：

```bash
sudo systemctl start starbot && sudo journalctl -u starbot -f
```

### 手动安装

```bash
./build.sh
```

产物在 `dist/build/`，把整个目录拷到目标机器，然后：

```bash
./start.sh
```

> 构建必须走 `build.sh` 而不是直接 `mvn package`，原因见[架构说明](architecture.md#8-构建)。

### 容器部署

```bash
./build.sh
docker build -f dist/templates/Dockerfile -t starbot:3.0 dist/build
docker run -d --name starbot --restart unless-stopped \
  -v starbot-data:/app -p 127.0.0.1:7827:7827 starbot:3.0
```

**卷必须挂在 `/app`。** 配置、登录凭据、推送规则、插件依赖全都写在工作目录下，
挂到 `/app/data` 之类的子目录等于什么都没持久化，容器一重建就得重新扫码。

升级换新镜像即可，卷里的配置与登录态会保留。其余说明见
[dist/templates/Dockerfile](../dist/templates/Dockerfile) 顶部的注释。

## 3. 首次配置

启动日志里有两样东西：

1. **配置界面地址**，形如 `http://127.0.0.1:7827/config?token=xxxx`
2. **登录二维码**（终端字符画）

用浏览器打开前者。界面默认只监听本机回环地址，**如果 NovaBot 装在远程服务器上**，
先在自己的电脑上建隧道：

```bash
ssh -L 7827:127.0.0.1:7827 用户名@服务器地址
```

然后在本机浏览器打开同一个地址。

打开后总览页顶部就是**首次配置向导**，四步，每步都会当场验证：

| 步骤 | 做什么 | 怎么算过 |
|---|---|---|
| 1 连接机器人 | 填 NapCat 的地址、端口、Token | 点「测试连接」显示实现版本与登录的 QQ 号 |
| 2 登录哔哩哔哩 | 用手机客户端扫二维码 | 页面自动变为「已登录」并显示 uid |
| 3 添加主播 | 输入 uid 或粘贴个人空间链接 | 页面显示已配置的主播数 |
| 4 发送测试消息 | 在「机器人」页发一条真消息 | 群里真的收到 |

四步都完成后向导会自动收起，之后随时可以展开重新检查。

**第 4 步不能跳过。** 群号写错、Token 不匹配、机器人不在群里——这几种错误的表现完全一样：
什么都不发生。发一条真消息是唯一能当场分辨的办法。

## 4. 界面导览

| 页签 | 回答什么问题 |
|---|---|
| **总览** | 系统现在好不好？健康自检、最近推送、暂停推送、立即自检 |
| **推送规则** | 我要推谁、推到哪、推什么内容 |
| **机器人** | QQ 这一侧连通吗？连接参数、连通性测试、发送测试消息 |
| **哔哩哔哩** | 账号还在线吗？登录状态、扫码登录、退出登录、监听中的直播间 |
| **设置** | 调参数。分层展示，原始配置文件编辑收在「高级」里 |

### 推送规则怎么配

不需要手写 JSON：

1. 在输入框填 uid 或直接粘贴个人空间链接（`https://space.bilibili.com/12345678`）
2. 界面会先把**昵称和头像**拉出来让你确认——避免 uid 打错一位却配了个陌生人
3. 确认后卡片出现，勾选「开播 / 下播 / 动态」
4. 添加推送目标：选机器人、选群聊还是私聊、填群号或 QQ 号
5. 点右下角保存

想改推送文案，勾选事件后点「编辑模板」，占位符做成了可点击的标签，右侧实时预览。

> 需要用自定义插件提供的处理器时，用工具栏右侧的「高级：编辑原始 JSON」。

### 设置页的分层

配置项有几十个，但真正决定系统能不能跑起来的只有几个。界面默认只显示必填与常用项，
勾选「包含高级选项」才展开其余的。**默认不显示的那些通常真的不需要改。**

修改后点保存，改动会逐行写入 `application.yml`，**你原有的注释、顺序、格式都会保留**。
保存前会校验 YAML 语法与字段类型，并自动留一份带时间戳的备份（保留最近 10 份，可在界面回滚）。

**大部分配置项要重启才生效**，「暂停全部推送」是个例外，它立即生效。

## 5. 消息模板

| 占位符 | 适用事件 | 含义 |
|---|---|---|
| `{uname}` | 全部 | UP 主昵称 |
| `{title}` | 开播 | 直播间标题 |
| `{cover}` | 开播 | 直播间封面图 |
| `{url}` | 开播、下播、动态 | 跳转链接 |
| `{time}` | 下播 | 本场直播时长 |
| `{action}` | 动态 | 动态动作，如「投稿了视频」 |
| `{picture}` | 动态 | 渲染出的动态图片 |
| `{next}` | 全部 | 消息分条 |
| `{at=all}` | 全部 | @全体成员，仅群聊有效 |

内置处理器与各自可用的占位符：

| 处理器 | 触发时机 | 可用占位符 |
|---|---|---|
| `BilibiliLiveOnPushHandler` | 开播 | `{uname}` `{title}` `{cover}` `{url}` |
| `BilibiliLiveOffPushHandler` | 下播 | `{uname}` `{time}` `{url}` |
| `BilibiliDynamicPushHandler` | 动态更新 | `{uname}` `{action}` `{url}` `{picture}` |

在 `datasource.json` 里它们要写全限定名，前缀是 `com.starlwr.bot.bilibili.handler.`。
用界面配置时不需要接触这些类名。

## 6. 常用设置

### 静音时段

半夜被机器人吵醒是这类工具最常见的抱怨。设置页搜「静音」：

```yaml
starbot:
  core:
    push:
      quiet-start: "23:00"
      quiet-end: "08:00"     # 允许跨零点
```

两项任一为空即视为不启用。

### 出问题时通知我

登录失效、连接中断、队列积压默认只写日志。要主动收到通知，配置告警接收方：

```yaml
starbot:
  core:
    alert:
      enabled: true
      qq-platform: qq-onebot   # 填 senders 里的某个 name
      qq-type: 0               # 1 群聊、0 私聊
      qq-num: 你的QQ号
```

> `qq-type` 与推送目标的 `type` 是同一套编码：**1 是群聊，0 是私聊**。填其他数字告警发不出去。

同一个问题在 `convergence-interval`（默认 1 小时）内只告警一次，避免故障持续时反复刷屏。

### 手动编辑配置文件

`application.yml` 与 `datasource.json` 都可以直接编辑，改完重启即可。
`datasource.json` 的完整示例见发行包里的 `datasource.example.json`。

推送目标的 `type`：**1 为群聊，0 为私聊**，`num` 是对应的群号或 QQ 号。填其他数字会被当作
「未知类型」在运行期丢弃，因此界面在保存时就会拦下。

## 7. 升级

### 从本项目的旧版本升级

用 `install.sh` 或容器部署时下面这些都由脚本处理，手工升级才需要照做：

1. 停止服务
2. **备份 `application.yml`、`datasource.json`、`cookies.json`、`cookies.key`**
3. 用新版本的产物替换 `StarBotCore.jar` 与 `lib/`
4. `plugins/` **不要整个替换**——里面可能有你自己放的第三方插件，覆盖等于把它们卸载。
   只替换内置的那三个插件，并删掉它们的旧版本文件（同一插件留下两个版本会被同时加载）
5. 保留原有的 `application.yml` 与 `datasource.json`
6. 启动，看日志有没有「未知配置项」之类的告警

配置项若有删改，构建时的一致性测试会拦住，因此升级后配置一般可以直接沿用。

### 从上游 StarBot 3.0-beta8 迁移

**必须改一处**：`datasource.json` 中推送内容的指定方式变了。

```jsonc
// beta8 二进制版：按事件类名
{ "event": "com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent" }

// 本项目（跟随上游公开源码）：按处理器类名
{ "handler": "com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler" }
```

其余配置键名保持兼容，`cookies.json` 也可直接沿用（首次启动会自动迁移为加密存储）。

### 凭据会不会掉

会。**目前扫码登录的凭据到期后需要重新扫码**，大约一个月一次。

程序内置了哔哩哔哩官方的凭据续期机制，也默认开启（`starbot.bilibili.account.auto-refresh-cookie`），
但它现在跑不起来，原因是个已知限制：

> 续期需要一个「持久化刷新口令」，它只在登录成功那一刻由服务端下发一次。
> 实测**扫码登录时服务端会把这个字段返回为空**，而同一账号在浏览器里登录则拿得到
> （localStorage 的 `ac_time_value` 有值）。已排除的原因包括请求参数与设备标识不完整、
> User-Agent 过旧，均补齐后仍为空。
>
> 拿不到口令时续期会直接跳过，不会有任何副作用，只是没法自动续。
> 这一状态会显示在「总览」和「哔哩哔哩」页的健康自检里：
> **「正常（uid xxx），但未取得刷新口令，无法自动续期」**。

已验证可用的部分：续期判断接口、`CorrespondPath` 的 RSA-OAEP 签名与实时刷新口令的获取，
都在真实账号上跑通了。缺的只是入口那一步。

掉登录的表现是动态推送静默停止（直播推送不受影响），健康自检会明确告警，重新扫码即可。

## 8. 安全须知

- 配置界面**默认只监听本机回环地址**并要求访问令牌。要从外网访问，请走 SSH 隧道，
  不要直接把端口暴露到公网
- 推送接口有 Token 鉴权与来源 IP 白名单，默认只放行本机
- `cookies.json` 等同于哔哩哔哩账号的完整控制权，默认加密存储，密钥在 `cookies.key`。
  **两个文件都要妥善保管，也都不要提交到任何仓库**
- 详见 [SECURITY.md](../SECURITY.md)

## 9. 资源占用

空载实测常驻内存 105–110 MB，默认 JVM 参数已针对小内存机器调校。
完整实测数据与测量条件见[性能实测](performance.md)。
