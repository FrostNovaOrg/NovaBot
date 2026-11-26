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
- 令牌的权限始终跟着**所属账号此刻的状态**：账号被停用或删除，令牌从下一个请求起失效；
  管理员改了成员名下的主播，成员的令牌从下一个请求起按新名单算；成员被改成管理员，令牌也随之按管理员算。

### 来源地址

控制台设置页的「允许访问的来源 IP」名单（`novabot.core.config-ui.allow-ips`）对这个前缀同样生效，
取来源地址的口径与控制台完全一致（同一段代码）。默认只放行本机两条。名单之外的来源回 403 `source_not_allowed`。

控制台被关掉（`novabot.core.config-ui.enabled: false`）时，这个前缀照常服务；只是签不了新的令牌。

### 无效令牌限速

出示了令牌、却没通过校验（不存在、已吊销、已过期、账号已停用或删除）的请求按来源地址计数：
同一来源 1 分钟内超过 10 次，之后的**无效**请求回 429 `too_many_requests`，带 `Retry-After`（秒），窗口过了自动恢复。

- 出示**有效**令牌的请求不看这个计数：同一台机器上一个写错令牌的脚本，不会把拿着正确令牌的程序一起挡在外面。
- 这个计数与控制台登录的失败锁定是两份，互不影响。
- 没带令牌的请求直接回 401，不计数。

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
| 400 | `bad_request` | 路径写法不对（含分号、编码过的斜杠、`.`／`..` 段），请求体不是 JSON 对象，参数不对 |
| 401 | `unauthenticated` | 没带令牌；令牌不存在、已吊销、已过期；所属账号已停用或已删除 |
| 403 | `source_not_allowed` | 来源地址不在「允许访问的来源 IP」名单里 |
| 403 | `insufficient_scope` | 令牌有效，但没勾这个接口要求的范围 |
| 403 | `forbidden` | 其余的权限不足（由具体接口说明） |
| 404 | `not_found` | 没有这个接口；或要的对象不存在、对这个令牌不可见（两者回同样的话） |
| 405 | `method_not_allowed` | 地址对，请求方法不对 |
| 409 | `conflict` | 对象此刻的状态不允许这样做 |
| 429 | `too_many_requests` | 同一来源连续出示无效令牌太多次，见上文 |
| 500 | `internal_error` | 服务端处理出错，详情只在服务端日志里 |

401 响应带 `WWW-Authenticate: Bearer realm="NovaBot"`。

## 范围

范围是令牌能做哪一类事的开关，由插件登记（标识形如 `report:read`，另有中文名和一句说明），
签发令牌时在控制台逐项勾选。接口各自写明要哪一项范围，令牌没勾就回 403 `insufficient_scope`。
范围只收窄、不放大：勾了范围，成员的令牌仍只看得见自己名下的主播。

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

## 插件怎么挂接口

插件实现 `org.frostnova.nova.core.api.NovaApiProvider` 并注册为 Bean：

```java
@NovaComponent
public class ReportApi implements NovaApiProvider {
    @Override public String segment() { return "report"; }

    @Override public List<NovaApiScope> scopes() {
        return List.of(new NovaApiScope("report:read", "读取直播报告", "读取各场直播报告的数据"));
    }

    @Override public List<NovaApiRoute> routes() {
        return List.of(NovaApiRoute.get("streamers/{platform}/{uid}/latest", "report:read", request -> {
            long uid = Long.parseLong(request.path("uid"));
            if (!request.caller().canSee(request.path("platform"), uid)) {
                throw NovaApiException.notFound("没有这位主播");
            }
            return Map.of("uid", uid);
        }));
    }
}
```

- 鉴权、来源名单、无效令牌限速、范围核对、返回格式与错误包装都由底座做；
  处理器拿到的 `request.caller()` 是已认证的调用者：账号编号、显示名、角色、令牌名与范围，
  以及 `canSee(platform, uid)`——某平台某主播对这个调用者是否可见（管理员全部可见，成员只见名下）。
- 处理器返回的对象放进 `data`；列表接口返回 `NovaApiPage.of(items, next)`；
  要回错误就抛 `NovaApiException`（`badRequest`、`forbidden`、`notFound`、`conflict`，或自定状态码与代码）。
  抛别的异常一律回 500 `internal_error`，异常内容不进响应。
- 段名、范围标识、接口路径的写法有规矩（小写字母开头；路径变量写成 `{name}`），
  每个接口要求的范围必须是本插件自己登记的。登记不合规的那一家整个不予采用，日志里点名说明，程序照常启动。
