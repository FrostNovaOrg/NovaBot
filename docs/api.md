# 程序接口约定（`/nova/api/v1/`）

给「别的程序来调 NovaBot」用的统一接口面：地址前缀、鉴权方式、返回格式、错误代码都在这一份里定死，
以后的对外接口一律挂在这个前缀下、照这份约定办。

现在这个前缀下只有一个底座自带的自查接口 `GET /nova/api/v1/me`；
插件提供的接口挂在 `/nova/api/v1/<插件的段名>/` 下，以插件自己的说明为准。

怎么在控制台签发、吊销访问令牌，另一台机器怎么连，见使用说明
[附录 B「让脚本或别的程序调用 NovaBot」](https://frostnovaorg.github.io/NovaBot/appendix-b-advanced.html)。

## 地址前缀

```
http://<NovaBot 所在机器>:<端口>[<部署前缀>]/nova/api/v1/
```

端口与控制台相同（默认 7827）。配了 `server.servlet.context-path` 时，前缀接在它后面。
版本号写在路径里：日后有不兼容的改动会另开 `v2`，`v1` 下已有的接口不改语义。

## 鉴权

只认一种写法：

```
Authorization: Bearer <访问令牌>
```

- 访问令牌在控制台「访问令牌」页签发，形如 `nvb_` 后接 43 个字符（32 字节随机数的 base64url）。
- **不认** 控制台的会话 Cookie，**不认** 地址栏里的 `?token=`、`access_token=` 之类参数，也不认别的请求头。
  地址栏里的东西会进反向代理日志与浏览器历史；Cookie 是浏览器自动附上的，认它就等于让任何网页都能借你的登录调接口。
- 反过来，访问令牌也**进不了控制台**：控制台只认登录会话和启动令牌。
- 令牌的权限始终跟着**所属账号此刻的状态**：管理员改了成员名下的主播，成员的令牌从下一个请求起按新名单算；
  成员被改成管理员，令牌也随之按管理员算；管理员被改成成员，令牌也随之按成员算（含下文「仅管理员」的范围）。

### 令牌什么时候失效

| 情形 | 结果 | 能不能恢复 |
|---|---|---|
| 过了有效期 | 401 | 不能，重新签发 |
| 在控制台吊销 | 401 | 不能，重新签发 |
| 所属账号被停用或删除 | 名下全部令牌**一并吊销**，401 | 不能：账号重新启用后，旧令牌仍是已吊销，要重新签发 |
| 所属账号是成员，而成员此刻登录不了控制台（例如单点登录关着） | 暂不可用，401 | 能：成员能登录了，下一个请求起照常可用，不用重签 |

成员只有单点登录一条路进控制台，所以「成员此刻登录不了」就是单点登录此刻没开（统一登录没配、或控制台的登录口令被撤掉）。
管理员的令牌不受这一条影响。控制台总开关关着时谁也登不进，这时按配置判：设了登录口令、开着统一登录，
成员的令牌照常可用——控制台关着时这个前缀照常服务，见下文「控制台关着时」。
控制台列表里这种令牌显示「暂不可用」并写明原因。

成员此刻登录不了时的回包，与账号被停用、删除时一字不差（401 `unauthenticated`，同一句说明）。

### 来源地址

控制台设置页的「允许访问的来源 IP」名单（`novabot.core.config-ui.allow-ips`）对这个前缀同样生效，
取来源地址的口径与控制台完全一致（同一段代码）。默认只放行本机两条。名单之外的来源回 403 `source_not_allowed`。

**这份名单是与控制台共用的**：把一台远程机器加进名单，让它能调这个前缀，它同时也就能打开控制台的登录页。
控制台照样要登录，但登录页对它敞开了。只想让远程程序调接口、不想让那台机器碰控制台，用 SSH 隧道
（见使用说明附录 B「从另一台机器连」），名单不用改。

#### 放在反向代理后面时

NovaBot 认的来源是**直接连进来的那一端**。放在反向代理后面时，直接连进来的永远是代理自己：

- **不开转发头**（默认）：所有调用方都算成代理的地址。名单只看得到代理，**拦不住外面的人**——
  代理在本机时，默认那两条本机地址就等于对代理背后的所有人放行；无效令牌的限速也是**所有调用方共用一个桶**，
  一个写错令牌的脚本刷到上限，别的调用方出示无效令牌也一起收 429（出示有效令牌的照常可用）。
- **开转发头**（`application.yml` 里设 `server.forward-headers-strategy`）：改用代理送下来的 `X-Forwarded-For` 认来源。
  这时代理必须**覆写**这个头，只写真实对端，不能追加在客户端自带的那一段后面——追加的话，
  客户端自己写一个 `X-Forwarded-For: 127.0.0.1` 就能冒充本机。Nginx：

  ```nginx
  proxy_set_header X-Forwarded-For $remote_addr;
  ```

  不要用 `$proxy_add_x_forwarded_for`，那是追加。Caddy 不配 `trusted_proxies` 时默认就是覆写。

`server.forward-headers-strategy` 有两种写法，差在「信不信、取哪一段」：

| 写法 | 谁来认 | 什么时候信这个头 | 取哪一段 |
|---|---|---|---|
| `native` | 内嵌的 Tomcat | 只在直接连进来的那一端是本机或内网地址时才信（`server.tomcat.remoteip.internal-proxies`，默认本机与私有网段）；外面直连带来的这个头不理 | 从右往左，跳过本机与内网地址，取第一个外网地址 |
| `framework` | Spring 的过滤器 | 谁连进来都信 | 最左侧那一段；还认 `Forwarded` 头，而且先看它 |

用 `framework` 时，NovaBot 的端口必须只有代理连得上（例如只监听 `127.0.0.1`），代理还要去掉客户端带来的
`Forwarded` 头（Nginx：`proxy_set_header Forwarded "";`）。拿不准就用 `native`。两种写法都要求代理覆写 `X-Forwarded-For`。

推送接口那边的「信任反向代理」（`trust-proxy`）是推送接口自己的开关，对这个前缀不起作用。

### 控制台关着时

控制台被关掉（`novabot.core.config-ui.enabled: false`）时，这个前缀照常服务，来源名单照样生效；
但令牌的签发与吊销都在控制台里，**关着时既签不了新令牌，也吊销不了旧令牌**。这时令牌泄露了：

- 马上收紧「允许访问的来源 IP」（改 `novabot.core.config-ui.allow-ips`），把拿到令牌的那一方挡在外面；
- 再打开控制台，在「访问令牌」页吊销那一把（或停用所属账号，名下令牌一并吊销）。

### 无效令牌限速

出示了令牌、却没通过校验（不存在、已吊销、已过期、账号已停用或删除、成员此刻登录不了控制台）的请求按来源地址计数：
同一来源 1 分钟内超过 10 次，之后的**无效**请求回 429 `too_many_requests`，带 `Retry-After`（秒），窗口过了自动恢复。

- 出示**有效**令牌的请求不看这个计数：同一台机器上一个写错令牌的脚本，不会把拿着正确令牌的程序一起挡在外面。
- 这个计数与控制台登录的失败锁定是两份，互不影响。
- 没带令牌的请求直接回 401，不计数。
- IPv4 按单个地址算；IPv6 按 /64 算一个来源（同一个 /64 里换着地址，照样算同一个）。
- 最多记 1 万个来源。满了只删已经过了窗口的、和窗口开得最早的那些，**正在被限的来源不删**：
  刷一大批别的来源，也不能把一个被限的来源提前放出来。
- 它不省处理：有效还是无效，要先比对一遍才知道，被限的请求照样比对，只是回 429 而不是 401。
  它管的是日志不被刷满（一个窗口只记第一次与开始被限的那一次），以及让调用方知道该停下来查令牌。

## 返回格式

所有响应都是 `application/json`，状态码照实给。

成功：

```json
{"ok": true, "data": { ... }}
```

失败：

```json
{"ok": false, "error": {"code": "unauthenticated", "message": "访问令牌无效或已吊销"}}
```

`code` 是给程序分辨用的稳定英文代码，`message` 是给人看的中文说明（措辞可能调整，不要按它分派）。

列表类接口的 `data` 统一是：

```json
{"items": [ ... ], "next": "<游标>"}
```

没有下一页时 `next` 为 `null`（字段一定在）。取下一页时把 `next` 原样作为查询参数 `cursor` 带回去。

所有响应都带 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`，
不带任何跨域放行头（浏览器页面里的脚本跨域调不了这个前缀）。

## 错误代码

| HTTP | `error.code` | 什么时候 |
|---|---|---|
| 400 | `bad_request` | 路径写法不对（含分号、反斜杠、编码过的斜杠、`.`／`..` 段；各段解码之后是 `.`、`..`，或含斜杠、反斜杠、控制字符，例如 `%2e%2e`、`%0a`），请求体不是 JSON 对象，参数不对 |
| 401 | `unauthenticated` | 没带令牌；令牌不存在、已吊销、已过期；所属账号已停用或已删除；所属账号是成员而成员此刻登录不了控制台 |
| 403 | `source_not_allowed` | 来源地址不在「允许访问的来源 IP」名单里 |
| 403 | `insufficient_scope` | 令牌有效，但没勾这个接口要求的范围 |
| 403 | `forbidden` | 接口要的是「仅管理员」的范围，而令牌所属的账号此刻不是管理员；其余的权限不足（由具体接口说明） |
| 404 | `not_found` | 没有这个接口；或要的对象不存在、对这个令牌不可见（两者回同样的话） |
| 405 | `method_not_allowed` | 地址对，请求方法不对 |
| 409 | `conflict` | 对象此刻的状态不允许这样做 |
| 429 | `too_many_requests` | 同一来源连续出示无效令牌太多次，见上文 |
| 500 | `internal_error` | 服务端处理出错（处理器抛了异常或 `Error`、返回值转不成回包），回包是固定的一句话，没有堆栈与内部路径，详情只在服务端日志里 |

401 响应带 `WWW-Authenticate: Bearer realm="NovaBot"`。

## 范围

范围是令牌能做哪一类事的开关，由插件登记（标识形如 `report:read`，另有中文名和一句说明），
签发令牌时在控制台逐项勾选。接口各自写明要哪一项范围，令牌没勾就回 403 `insufficient_scope`。
范围只收窄、不放大：勾了范围，成员的令牌仍只看得见自己名下的主播。

有的范围标着**仅管理员**：成员签发时勾不了（签发页上置灰并说明，接口上回 403）；
调用时还按令牌所属账号**此刻**的角色再核一次——管理员被改成成员之后，这个账号以前签的带这种范围的令牌，
调要这项范围的接口回 403 `forbidden`，别的范围照常。

不勾任何范围的令牌只能调自查接口 `/me`。现在还没有插件登记范围，所以签发页上没有可勾的项。

## 成员的令牌看得到什么

- 管理员（含本地管理员）的令牌：全部主播。
- 成员的令牌：只有管理员分给这个成员的那几位主播，判法与控制台给成员过滤主播是同一段代码。
  别人的主播对它来说「不存在」，按 404 回，不透露那位主播在不在。

## `GET /nova/api/v1/me`

任何有效令牌都能调，不要求范围。

```bash
curl -H "Authorization: Bearer nvb_..." http://127.0.0.1:7827/nova/api/v1/me
```

```json
{
  "ok": true,
  "data": {
    "account": {"id": "local-admin", "displayName": "本地管理员", "role": "ADMIN"},
    "token": {"id": "5a17c0de00000001", "name": "备份脚本", "scopes": [], "expiresAt": null},
    "streamers": [
      {"platform": "bilibili", "uid": 123456, "name": "示例主播"}
    ]
  }
}
```

- `account.role`：`ADMIN` 或 `MEMBER`。
- `token.id` 是令牌的编号，与控制台列表里那一行对得上；它不是令牌本身，也推不出令牌。
- `token.expiresAt`：到期时刻（ISO 8601，UTC），不过期为 `null`。
- `streamers`：对这把令牌可见的主播，含已停用的；名字取不到时为 `null`。

## 访问令牌的保管

- 落盘只有令牌的 SHA-256 摘要（运行状态文件 `state.json` 的 `api-tokens` 一段），没有明文；
  比对用常量时间。明文只在签发那一刻显示一次，遗失了只能吊销再签。
- 控制台列表、操作记录、日志里只出现令牌的编号，不出现明文，也不出现摘要。
- 每个账号最多 20 把有效令牌（已吊销、已过期的不算）。名字可以重复。
- 签发、吊销都记进控制台的操作记录（动作 `API_TOKEN_ISSUE`、`API_TOKEN_REVOKE`，对象里是令牌编号与所属账号）。
  停用、删除账号时一并吊销的，记在那一条（`ACCOUNT_DISABLE`、`ACCOUNT_DELETE`）的对象里，只有把数 `revokedTokens`。

## 插件怎么挂接口

插件实现 `org.frostnova.nova.core.api.NovaApiProvider` 并注册为 Bean：

```java
@NovaComponent
public class ReportApi implements NovaApiProvider {
    @Override public String segment() { return "report"; }

    @Override public List<NovaApiScope> scopes() {
        return List.of(new NovaApiScope("report:read", "读取直播报告", "读取各场直播报告的数据"),
                NovaApiScope.adminOnly("report:rebuild", "重算全部报告", "按现有数据重算所有主播的直播报告"));
    }

    @Override public List<NovaApiRoute> routes() {
        return List.of(NovaApiRoute.get("streamers/{platform}/{uid}/latest", "report:read", request -> {
            long uid = Long.parseLong(request.path("uid"));
            if (!request.caller().canSee(request.path("platform"), uid)) {
                throw NovaApiException.notFound("没有这位主播");
            }
            return Map.of("uid", uid);
        }), NovaApiRoute.post("rebuild", "report:rebuild", request -> Map.of("queued", true)));
    }
}
```

**全实例的操作用「仅管理员」的范围，按主播的数据用 `canSee`。**

- 动的是整个实例（改全局设置、重算所有主播的东西、看全局的运行状态）：把范围登记成仅管理员——
  `NovaApiScope.adminOnly(id, 中文名, 说明)`，或 `new NovaApiScope(id, 中文名, 说明, true)`。
  成员签不出带它的令牌，管理员降成成员后旧令牌也调不动它，这两件底座都替你核了，处理器里不用再判角色。
- 按主播划分的数据（某位主播的报告、场次、观众）：范围照常登记成人人可勾，处理器里用
  `request.caller().canSee(platform, uid)` 逐个筛——成员只见名下的，管理员全部可见。
  别为了「成员不该看别人的主播」把范围标成仅管理员：那样成员连自己名下的也看不了。

- 鉴权、来源名单、无效令牌限速、范围核对（含仅管理员）、返回格式与错误包装都由底座做；
  处理器拿到的 `request.caller()` 是已认证的调用者：账号编号、显示名、角色、令牌名与范围，
  以及 `canSee(platform, uid)`——某平台某主播对这个调用者是否可见（管理员全部可见，成员只见名下）。
  `hasScope(id)` 说的是「此刻用得了」：仅管理员的范围，账号此刻不是管理员时为 false。
- 处理器返回的对象放进 `data`；列表接口返回 `NovaApiPage.of(items, next)`；
  要回错误就抛 `NovaApiException`（`badRequest`、`forbidden`、`notFound`、`conflict`，或自定状态码与代码）。
  抛别的异常（含 `Error`）、或返回的对象转不成 JSON，一律回 500 `internal_error`，异常内容不进响应，只进服务端日志。
- 路径变量拿到的是解码之后的值；`.`、`..`，或含斜杠、反斜杠、控制字符的段到不了处理器，底座先回 400。
- 段名、范围标识、接口路径的写法有规矩（小写字母开头；路径变量写成 `{name}`），
  每个接口要求的范围必须是本插件自己登记的。登记不合规的那一家整个不予采用，日志里点名说明，程序照常启动。
