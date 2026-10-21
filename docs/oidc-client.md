# OpenID Connect 客户端

客户端由 `OidcClient` 提供，使用授权码和 PKCE S256。控制台仅在设置本地口令且开启 OIDC 后提供入口；新身份登记为待授权成员，由管理员启用。

配置前缀为 `novabot.core.config-ui.oidc`，修改后重启生效：

| 键 | 默认值 | 用途 |
| --- | --- | --- |
| `enabled` | `false` | 客户端开关 |
| `issuer` | 空 | HTTPS issuer，必须与发现文档逐字相同 |
| `client-id` | 空 | 账户中心登记的客户端 ID |
| `client-secret` | 空 | client_secret_basic 使用的密钥 |
| `scopes` | `openid profile email offline_access` | 空格分隔，必须包含 openid |
| `redirect-uri` | 空 | 显式回调地址；HTTP 仅允许回环地址 |
| `management-uri` | 空 | 供成员管理跳转使用，可不填 |

关闭开关或 issuer 留空时，客户端不访问账户中心。密钥通过现有 SensitiveFields 遮掩，经通用配置保存入口修改这组设置时，要求签发未满 10 分钟的本地登录会话；修改后重启生效。遮掩值和留空不会覆盖旧密钥，显式清除仍遵循现有机密配置规则。

未填写回调地址时，使用 servlet 请求的 scheme、serverName、serverPort，加容器配置的 context path 和 `/config/api/oidc/callback`；默认 HTTPS 端口 443 省略。例如请求来源为 `https://console.example.com`、应用前缀为 `/app` 时，结果为 `https://console.example.com/app/config/api/oidc/callback`。客户端不自行解析 Forwarded 或 X-Forwarded-* 头；代理部署建议显式填写对外 HTTPS 地址。回调接口为 `/config/api/oidc/callback`。回调地址的 HTTP 仅允许回环地址（127.0.0.1、::1、localhost），其余必须 HTTPS。

`startLogin(request)` 返回授权地址、state 和过期时间。state、nonce、code_verifier 均由 SecureRandom 生成，各含 256 位随机数。事务在服务器内存保存 5 分钟，最多同时保留 1024 个；重启会丢弃事务。`completeLogin(state, code, callbackIssuer)` 在任何令牌请求前原子消费 state，包括失败的回调。未收到 iss 参数时传 null；发现文档声明支持授权响应 iss 时缺失也拒绝，收到时必须逐字匹配 issuer。

浏览器流程使用独立、短期的 HttpOnly、SameSite=Lax 关联 Cookie 绑定发起登录的浏览器，在调用 completeLogin 前核对；Secure 跟随请求连接。现有 SameSite=Strict 管理员会话 Cookie 不承担这个用途。Login 及其中的令牌和 nonce 只在服务端保存，不序列化给浏览器，也不写日志。

发现文档和公钥缓存 1 小时。仅从经过 issuer 核对的发现文档读取 jwks_uri，按 kid 查公钥。首次加载之后，未知 kid 可立即触发一次重取，此后每 30 秒最多重取一次，失败也计入限频。公钥过期后取不到新钥匙时停止验证，不使用过期缓存。支持 Ed25519/EdDSA、RS256（RSA 至少 2048 位）、ES256（P-256）。使用 JDK Signature、KeyFactory 和 fastjson2，没有额外密码学依赖。ID 令牌核对签名、iss、sub、aud/azp、exp、iat、nonce，并为时间校验保留 60 秒偏差。

`completeLogin` 返回的 Identity 用 issuer 和 subject 共同认人，name、email 只供显示。`refresh(previousLogin)` 返回续期后的 Login；允许账户中心省略新的 ID 令牌和 refresh_token。返回 ID 令牌时重新验签、核对声明，要求 subject 不变；如包含 nonce，要求与原登录一致。未轮换 refresh_token 时保留旧值。

异常通过 `OidcException.kind()` 分类：`TOKEN_REJECTED` 仅表示账户中心明确返回 invalid_grant；invalid_client 或 unauthorized_client 属于 `CONFIGURATION`，表示客户端配置错误；`PROVIDER_UNAVAILABLE` 表示网络故障、HTTP 429/5xx 或暂时不可用。后者不能当作凭据被撤销。格式损坏等其他响应属于 `PROVIDER_ERROR`，本地校验失败属于 `INVALID_TOKEN` 或 `INVALID_LOGIN`。异常不带令牌响应正文、error_description 或包含凭据的底层异常。
