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
| `management-uri` | 空 | 填账户中心里管理用户、新建账号的网页地址，不是以 `/.well-known/openid-configuration` 结尾的发现文档地址；可留空，留空时不显示“账户中心”和“新建成员”。要填就填以 `https://` 开头的完整地址 |

关闭开关或 issuer 留空时，客户端不访问账户中心。密钥通过现有 SensitiveFields 遮掩，经通用配置保存入口修改这组设置时，要求签发未满 10 分钟的本地登录会话；修改后重启生效。遮掩值和留空不会覆盖旧密钥，显式清除仍遵循现有机密配置规则。

未填写回调地址时，使用 servlet 请求的 scheme、serverName、serverPort，加容器配置的 context path 和 `/config/api/oidc/callback`；默认 HTTPS 端口 443 和默认 HTTP 端口 80 均省略。例如请求来源为 `https://console.example.com`、应用前缀为 `/app` 时，结果为 `https://console.example.com/app/config/api/oidc/callback`。客户端不自行解析 Forwarded 或 X-Forwarded-* 头；代理部署建议显式填写对外 HTTPS 地址。回调接口为 `/config/api/oidc/callback`。回调地址的 HTTP 仅允许回环地址（127.0.0.1、::1、localhost），其余必须 HTTPS。

`startLogin(request)` 返回授权地址、state 和过期时间。state、nonce、code_verifier 均由 SecureRandom 生成，各含 256 位随机数。事务在服务器内存保存 10 分钟，最多同时保留 1024 个；重启会丢弃事务。`completeLogin(state, code, callbackIssuer)` 在任何令牌请求前原子消费 state，包括失败的回调。未收到 iss 参数时传 null；发现文档声明支持授权响应 iss 时缺失也拒绝，收到时必须逐字匹配 issuer。

浏览器流程使用独立、短期的 HttpOnly、SameSite=Lax 关联 Cookie 绑定发起登录的浏览器，在调用 completeLogin 前核对；Secure 跟随请求连接。现有 SameSite=Strict 管理员会话 Cookie 不承担这个用途。Login 及其中的令牌和 nonce 只在服务端保存，不序列化给浏览器，也不写日志。

发现文档和公钥缓存 1 小时。仅从经过 issuer 核对的发现文档读取 jwks_uri，按 kid 查公钥。首次加载之后，未知 kid 可立即触发一次重取，此后每 30 秒最多重取一次，失败也计入限频。公钥过期后取不到新钥匙时停止验证，不使用过期缓存。支持 Ed25519/EdDSA、RS256（RSA 至少 2048 位）、ES256（P-256）。使用 JDK Signature、KeyFactory 和 fastjson2，没有额外密码学依赖。ID 令牌核对签名、iss、sub、aud/azp、exp、iat、nonce，并为时间校验保留 60 秒偏差。

`completeLogin` 返回的 Identity 用 issuer 和 subject 共同认人，name、email 只供显示。`refresh(previousLogin)` 返回续期后的 Login；允许账户中心省略新的 ID 令牌和 refresh_token。返回 ID 令牌时重新验签、核对声明，要求 subject 不变；如包含 nonce，要求与原登录一致。未轮换 refresh_token 时保留旧值。

异常通过 `OidcException.kind()` 分类：`TOKEN_REJECTED` 仅表示账户中心明确返回 invalid_grant；invalid_client 或 unauthorized_client 属于 `CONFIGURATION`，表示客户端配置错误；`PROVIDER_UNAVAILABLE` 表示网络故障、HTTP 429/5xx 或暂时不可用。后者不能当作凭据被撤销。格式损坏等其他响应属于 `PROVIDER_ERROR`，本地校验失败属于 `INVALID_TOKEN` 或 `INVALID_LOGIN`，续期时已验签的身份变化单独归为 `IDENTITY_CHANGED`。异常不带令牌响应正文、error_description 或包含凭据的底层异常。


## 续期复核

控制台在应用运行期间每秒查看到期的统一登录会话，每次复核后 4 分钟再次用 refresh_token 复核，为 5 分钟内复核留出调度与请求时间。全局最多同时兑换 4 个，每个会话同时只能有一次兑换；没有统一登录会话时不请求账户中心，关停时关闭调度与工作线程。续期不会延长控制台会话原有的绝对期限或闲置期限。

账户中心明确返回 invalid_grant，或续期 ID 令牌通过签名和其它校验后 issuer、subject 与原身份不一致，会立即注销该统一登录会话，下个请求须重新登录。成功后保存新的 Login；账户中心轮换 refresh_token 时下一轮使用新值，未返回新值则沿用旧值。

网络断开、超时、HTTP 429、5xx、temporarily_unavailable，以及 invalid_client、unauthorized_client 等客户端配置错误，都保留会话，下轮重试。其它账户中心错误、格式错误、签名、aud 等令牌校验失败也不注销。同一会话连续同类失败只记录首次和恢复，日志写显示名、账号编号与错误分类，不含令牌或 sid。本地管理员绑定的身份同样复核，但口令、通行密钥和启动令牌会话不受影响。

没有 refresh_token 的会话无法复核，到 access_token 的 expires_in 到期即失效；未提供 expires_in 时按一小时计算，届时须重新登录。OIDC 配置目前重启生效，重启会清空全部内存会话；运行期间如果切回令牌形态，原有统一登录会话同样失效。

## 后台登出

在账户中心登记 `POST /config/api/oidc/backchannel-logout`。将控制台回调地址末尾的 `/callback` 换成 `/backchannel-logout` 即可，例如 `https://console.example.com/app/config/api/oidc/callback` 对应 `https://console.example.com/app/config/api/oidc/backchannel-logout`，保留对外域名和部署前缀。

账户中心以表单提交 logout_token。控制台验证签名、issuer、audience、iat、exp、登出事件、身份及 jti，拒绝 nonce、无签名令牌、不支持的 typ 与重放。允许 60 秒时钟偏差；jti 在 exp 加偏差的期限内保留，最多 4096 条，满额时拒绝新令牌而不淘汰仍有效的记录。带 sid 时只注销同 issuer、sid 的统一登录会话，同时带 sub 则也须匹配；只有 sub 时注销该身份全部统一登录会话。成功和未匹配会话均回空体 200；无效令牌回空体 400，不透露校验细节；响应均带 `Cache-Control: no-store`。此入口使用统一登录限频，超限回 429。未设置本地口令或未启用统一登录时回 404。

控制台必须能被账户中心连接，并将账户中心的出口 IP 加入 `novabot.core.config-ui.allow-ips`；默认仅允许本机。IP 白名单先于登出处理，不匹配仍回 403，此接口不绕过白名单。账户中心连不到控制台时，仍通过最多约 5 分钟一次的续期复核发现凭据失效；网络或账户中心持续故障期间会保留现有会话。

## 保留 offline_access

默认 scopes 中的 offline_access 用于向账户中心申请 refresh_token，让控制台能够在浏览器没有活动时继续核对身份是否有效。删掉它可能导致登录成功却拿不到 refresh_token，只能等 access_token 过期后要求重新登录，无法进行定期续期复核。请保留此 scope，并在账户中心为客户端允许离线访问；实际是否签发 refresh_token 仍由账户中心策略决定。
