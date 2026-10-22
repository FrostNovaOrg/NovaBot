# 附录 E　接统一登录账户中心

想让主播本人或公会运营用自己的账号登录控制台，可以接一个支持标准 OpenID Connect（简称 OIDC）的账户中心。已有账户中心时，先按前两节核对和登记，再到 NovaBot 设置页填写；还没有时，可以从本附录的 Keycloak 示例开始。

## 账户中心要满足什么

| 核对项 | 要求 |
| --- | --- |
| 发现文档 | 在 `<issuer>/.well-known/openid-configuration` 提供 JSON 文档。Issuer 是账户中心的身份签发地址，例如 `https://accounts.example.com/realms/novabot`；不能带查询串，必须与文档中的 `issuer` 逐字相同，末尾斜杠也要一致。拼发现文档地址时，issuer 末尾若有斜杠，先去掉再拼。 |
| HTTPS 与证书 | **账户中心必须用 HTTPS，连本机试用也一样；证书要受 NovaBot 所在机器的 Java 运行环境信任。** 发现文档里的 `authorization_endpoint`、`token_endpoint`、`jwks_uri` 也必须是完整的 HTTPS 地址。地址不能含用户名、密码或 `#` 片段。回环地址允许 HTTP 的例外只适用于 NovaBot 的回调地址。 |
| 登录流程 | 支持授权码流程与 PKCE，挑战方法为 `S256`。 |
| 客户端认证 | 只用 `client_secret_basic`，即用客户端 ID 和密钥认证；仅支持公开客户端或其他认证方法的账户中心不能直接接入。 |
| 身份令牌签名 | 支持 `RS256`（RSA 至少 2048 位）、`ES256`（P-256）、`EdDSA`（Ed25519）。其他算法，例如 `ES384`，登不进来。公钥通过发现文档的 `jwks_uri` 提供，令牌头须有对应的 `kid`。 |
| 名字与邮箱 | 显示名和邮箱只取身份令牌（ID token）中的 `name`、`email`，不会另取用户资料接口，也不会拿用户名或 `sub` 当名字。普通统一登录账号没有 `name` 时，设置页成员列表用邮箱代替；邮箱也没有时显示 NovaBot 的账号编号。左栏个人名称也依次使用显示名、邮箱和账号编号。 |
| 刷新凭据 | 应签发 `refresh_token`；默认授权范围含 `offline_access`，用来申请它。账户中心还须在停用、删除用户时让其刷新凭据失效，并在刷新时用 `invalid_grant` 拒绝已撤销的凭据。NovaBot 每 4 分钟复核一次，收到这种拒绝后注销该统一登录会话，通常几分钟内下线。复核时账户中心发回的身份令牌换了 `iss` 或 `sub`，也会注销这个会话。 |

> [!WARNING] 没有 `refresh_token` 也可能登录成功，但无法完成有效的定期复核；会话最晚到 `access_token` 的 `expires_in` 到期即失效，没有提供该值时按一小时计算。控制台自身的会话期限或闲置期限可能让它更早失效，届时要重新登录。

账户中心或网络暂时故障、客户端配置错误、令牌格式或签名校验失败时，复核不会据此注销现有会话，而是留到下轮重试。因此“几分钟内下线”的前提是刷新请求能到达账户中心，且账户中心明确返回 `invalid_grant`（刷新凭据已失效）；不能把断网当成停用用户的办法。

## 在账户中心登记 NovaBot

为 NovaBot 单独登记一个 OIDC 客户端，保存账户中心分配的客户端 ID 和密钥。下面以控制台入口 `https://console.example.com/config` 为例；这里说的“控制台地址”是 `/config` 之前的部署地址，例如 `https://console.example.com`，有应用前缀时则是 `https://console.example.com/app`。

### 回调地址

登记 `<控制台地址>/config/api/oidc/callback`，不要把 `/config` 拼两次。

| 部署方式 | 要登记的完整回调地址 |
| --- | --- |
| 无应用前缀 | `https://console.example.com/config/api/oidc/callback` |
| 应用前缀为 `/app` | `https://console.example.com/app/config/api/oidc/callback` |

NovaBot 的“统一登录 · 回调地址”可以留空，此时从收到请求的协议、服务器主机名、端口，加上应用配置的前缀及 `/config/api/oidc/callback` 推导。HTTPS 的 443、HTTP 的 80 会省略，其他端口会保留。HTTP 回调只允许 `127.0.0.1`、`::1`、`localhost` 这些回环地址，其余必须 HTTPS；**这不代表账户中心可以用 HTTP**。

反向代理后面建议显式填写浏览器能访问的完整 HTTPS 回调地址，并在账户中心登记完全相同的值。否则 NovaBot 看到的可能是内网主机、端口或 HTTP；它不会自行解析 `Forwarded`、`X-Forwarded-*` 请求头来还原对外地址。应用前缀以本程序的 `server.servlet.context-path` 为准。

### 后台登出地址（可选）

登记 `<控制台地址>/config/api/oidc/backchannel-logout`。例如有 `/app` 前缀时是 `https://console.example.com/app/config/api/oidc/backchannel-logout`。

这一项可选。不配后台登出，只要账户中心撤销刷新凭据并在刷新时返回 `invalid_grant`，停用或删除的人仍会在复核时下线，只是慢几分钟。配了以后，账户中心可以直接通知 NovaBot 注销对应的统一登录会话。

**账户中心必须连得到控制台，并且它的出口 IP 要加入 `novabot.core.config-ui.allow-ips` 白名单。** 此入口也受白名单管，默认仅允许本机；白名单不匹配会返回 403。

账户中心应以 POST 表单（`application/x-www-form-urlencoded`）发送 `logout_token`。连接正常却被拒绝时，让账户中心管理员核对下表：

| 令牌部分 | NovaBot 接受的内容 |
| --- | --- |
| 签名与头 | 使用前述三种签名算法及可查到的 `kid`；`typ` 可以不带，带了只接受 `logout+jwt` 或 `JWT`，大小写须一致。 |
| `iss`、`aud` | `iss` 与所填 issuer 逐字相同；`aud` 是本客户端 ID，或包含它的非空字符串数组。 |
| `exp`、`iat` | 两项都必填，值为整数秒时间戳。`exp` 不能过期，`iat` 不能在未来且须早于 `exp`；允许 60 秒时钟偏差。若带 `nbf`，也不能超过当前时间加 60 秒。 |
| `jti` | 必填的非空唯一令牌编号，最长 512 个字符；重复提交同一令牌会被拒绝。 |
| `events` | 必须是对象，并含 `http://schemas.openid.net/event/backchannel-logout`，这个键对应的值也必须是对象（通常是 `{}`）。 |
| `sid` 或 `sub` | 至少有一项非空。带 `sid` 时只注销这个会话标识对应的统一登录会话；同时带 `sub` 时也要匹配。只有 `sub` 时注销该身份的全部统一登录会话。账户中心发送 `sid` 时，登录时的身份令牌也要提供同一个 `sid`。 |
| `nonce` | 不许带，带了就拒绝。 |

有效令牌即使没有匹配的会话也返回空体 200；令牌无效返回空体 400，不给具体校验细节。请求过密返回 429；没设口令或没启用统一登录时返回 404。后台登出不会注销口令登录的会话。

### 授权范围

默认值是 `openid profile email offline_access`，在账户中心允许客户端申请这些范围，再原样填到 NovaBot；范围之间用空格分隔。

| 范围 | 为什么要它 |
| --- | --- |
| `openid` | 表示使用 OIDC 身份登录，NovaBot 要求必须包含。 |
| `profile` | 申请名字等基本资料；还要让账户中心把 `name` 放进身份令牌，NovaBot 才能显示名字。 |
| `email` | 申请邮箱；同样要让账户中心把 `email` 放进身份令牌。 |
| `offline_access` | 申请刷新凭据，使浏览器没活动时也能继续复核身份。是否签发 `refresh_token` 还取决于账户中心的客户端权限与策略，只写这个范围并不能保证拿到。 |

## 在 NovaBot 设置页填那七项

先设置控制台登录口令，并用口令重新登录一次，再在 10 分钟内改这组设置。**要用统一登录，先开口令登录，再打开客户端开关、填 Issuer、客户端 ID 和密钥。** 保存后要重启 NovaBot 才生效。

在[设置页](12-settings.md)查找以下标签。表里的键均接在 `novabot.core.config-ui.oidc.` 后面，供手改配置文件时对照。

| 设置项标签 | 键 | 怎么填 |
| --- | --- | --- |
| 统一登录 · 客户端开关 | `enabled` | 默认关闭；要接账户中心就打开，并填写下面的 Issuer、客户端 ID 和密钥。 |
| 统一登录 · Issuer | `issuer` | 填发现文档中的 `issuer` 原文，例如 `https://accounts.example.com/realms/novabot`；不是发现文档地址，也不是账户中心管理台地址。留空时不会启用统一登录。 |
| 统一登录 · 客户端 ID | `client-id` | 填账户中心为 NovaBot 登记的客户端 ID，不是个人用户名。 |
| 统一登录 · 客户端密钥 | `client-secret` | 填该客户端的密钥，用于 `client_secret_basic`。已有密钥时，留空或保持遮掩值表示不修改已保存的密钥。 |
| 统一登录 · 授权范围 | `scopes` | 默认 `openid profile email offline_access`；空格分隔，必须包含 `openid`。建议保留默认四项。 |
| 统一登录 · 回调地址 | `redirect-uri` | 填上一节在账户中心登记的完整回调地址；留空时由请求推导，反向代理后面建议显式填。回环地址允许 HTTP，其余必须 HTTPS。 |
| 统一登录 · 账户管理页 | `management-uri` | 可留空；要填就填以 `https://` 开头的完整管理页地址，不能含用户名、密码或 `#` 片段。填账户中心里管理用户、新建账号的网页地址，不是以 `/.well-known/openid-configuration` 结尾的发现文档地址。例如 `https://accounts.example.com/admin/novabot/console/`。 |

重启后，登录页顶部会显示全宽主按钮“使用统一登录”；账户中心支持注册时，下面还有“用统一身份注册”次按钮和注册说明。通行密钥（登记过才显示）改为次按钮，“使用密码登录”折在下面，点开就是口令表单；账户中心出问题时，管理员仍可用控制台自己的口令进来处理。

填了“账户管理页”以后，用统一登录进入控制台时，左栏个人菜单会有“账户中心”；管理员的设置页“成员”一块会有“新建成员”，点击跳到账户中心。留空就没有这两个跳转入口。

发现文档尚未取到时先不显示注册按钮，NovaBot 会在后台加载；取到以后重新打开登录页即可看到支持的入口。账户中心暂时连不上不会拖住登录页。

接好以后，新身份第一次用统一登录进来，会出现在设置页“成员”一块里，状态为待授权；登录页会提示“已登记，请等管理员授权。管理员授权后，再点「使用统一登录」进来即可。”成员如何授权与分配主播，见[第 15 章　成员：让主播和公会运营自己管理](15-members.md)。

## 装一个现成的开源账户系统（以 Keycloak 为例）

以下按 **Keycloak 26.8** 的英文界面写；26.0–26.5 有两处位置不同，在对应步骤里注明。数据库、安装、HTTPS 证书和反向代理按 [Keycloak 官方入门文档](https://www.keycloak.org/guides#getting-started)、[生产配置](https://www.keycloak.org/server/configuration-production)、[TLS 配置](https://www.keycloak.org/server/enabletls)和[反向代理配置](https://www.keycloak.org/server/reverseproxy)完成。账户中心对外地址示例统一用 `https://accounts.example.com`；不要直接拿 HTTP 开发模式去接 NovaBot，即使两者在同一台机器上。

### 建 realm 与客户端

1. 用 Keycloak 管理员进入管理台，在左栏点 `Manage realms` → `Create realm`，弹窗里填 `Realm name`（例如 `novabot`），点 `Create`，并切换到这个 realm。这个入口从 26.2 起使用；26.0–26.1 在左栏顶上的 realm 下拉里点 `Create realm`。后面的用户和客户端都建在这里。
2. 到 `Clients`，选择 `Create client`；`Client type` 选 `OpenID Connect`，`Client ID` 填 `novabot-console`。
3. `Client authentication` 默认关闭，要打开，打开后客户端才有 `Credentials` 页签。启用 `Standard flow`（标准授权码流程）。建议同时要求 PKCE，因为 NovaBot 每次登录都会带 S256 挑战：26.6 起，在 `Capability config` 里打开 `Require PKCE`（一开就是 S256），再确认 `PKCE Method` 是 `S256`；26.3–26.5 在同一处把 `PKCE Method` 选 `S256`；26.0–26.2 则在客户端建好后，到 `Advanced` 页签 → `Advanced settings`，把 `Proof Key for Code Exchange Code Challenge Method` 选 `S256`。
4. 在 `Valid redirect URIs` 填完整回调地址，例如 `https://console.example.com/config/api/oidc/callback`。有 `/app` 前缀就填 `https://console.example.com/app/config/api/oidc/callback`。保存客户端。
5. 如需后台登出，等客户端建好后，进入 `Settings` 页签的 `Logout settings` 一节；新建向导里没有这些项。先关掉 `Front channel logout`，新客户端默认开着它；开着时，26.1 起看不到下面几项，而且任何 26.x 都不会给这个客户端发后台登出。在 `Backchannel logout URL` 填对应的 `https://console.example.com/config/api/oidc/backchannel-logout`。`Backchannel logout session required` 默认开启，保持开着，登出通知才带会话 `sid`；也核对登录时的身份令牌含 `sid`。`Backchannel logout revoke offline sessions` 默认关闭，保持默认。有应用前缀时，回调和后台登出地址都要带上它。
6. 在客户端 `Credentials` 中确认 `Client Authenticator` 使用 `Client Id and Secret`，复制 `Client Secret`，与 `Client ID` 一起填回 NovaBot。NovaBot 会以 `client_secret_basic` 发送密钥，不能把客户端配成不需要密钥的公开客户端。

Keycloak 26 的登出令牌带齐前面表里的各项：头里的 `typ` 是 `logout+jwt`，有 `iat`、`jti`、`events`、`sub`，不带 `nonce`，`exp` 是签发后 120 秒。`sid` 只在 `Backchannel logout session required` 开着时才带；关掉就只剩 `sub`，NovaBot 会注销这个身份的全部统一登录会话。

两台机器都要开启自动对时。登出令牌签发 2 分钟就过期，NovaBot 只容 60 秒钟差：Keycloak 的钟比 NovaBot 快 1 分钟以上，或者慢 3 分钟上下，后台登出就会被拒。后台登出能否接通还取决于账户中心能否访问控制台、IP 白名单。

### 核对签名、资料与离线访问

1. 到客户端 `Advanced` 页签 → `Fine grain OpenID Connect configuration`，找到 `ID token signature algorithm`。留空就用 realm 默认的 `RS256`，留空或选 `RS256` 都行；下拉里的 `PS256`、`HS256` 等算法 NovaBot 不支持。登出令牌也用这个算法签。
2. 在客户端的 `Client scopes` 中核对 `profile`、`email`，以及用于离线访问的 `offline_access`。新 realm 里，`profile`、`email` 是 `Default`，`offline_access` 是 `Optional`；NovaBot 默认的授权范围点名要了 `offline_access`，所以能拿到离线刷新凭据。用户默认就有 `offline_access` 角色（含在 `default-roles-<realm 名>` 里），一般不用另配。资料映射要把 `name` 和 `email` 加进身份令牌；若调整映射，核对 `Add to ID token` 已开启。
3. 到 `Users` 建一个试用账号：空 realm 里按钮叫 `Create new user`，有了用户以后才是 `Add user`。填好 `Username`、`First name`、`Last name`、`Email`；`First name`、`Last name` 至少填一个，身份令牌里才有 `name`，只填用户名不足以让 NovaBot 显示这个名字；不填 `Email` 就没有 `email`。到用户的 `Credentials` 页签 → `Set password` 设置登录密码；弹窗里的 `Temporary` 默认开启，用户第一次登录时要先改密码，试用账号可以关掉。

在 Keycloak 里停用或删除用户以后，刷新凭据会以 `invalid_grant` 被拒，NovaBot 复核时几分钟内就让这个人下线。

关于资料映射、离线访问和登出设置，参照 [Keycloak 最新版管理手册](https://www.keycloak.org/docs/latest/server_admin/index.html)。不要只核对输入框：实际登录后还应确认身份令牌带名字、邮箱，且账户中心确实签发了刷新凭据。

### 把地址填回 NovaBot

Issuer 的形式是 `https://<Keycloak 地址>/realms/<realm 名>`，本例为 `https://accounts.example.com/realms/novabot`。先打开 `https://accounts.example.com/realms/novabot/.well-known/openid-configuration`，将其中 `issuer` 原文填回 NovaBot。

“账户管理页”可以填 Keycloak 管理台里这个 realm 的用户管理页，供管理员新建用户；这不是用户自己的个人资料页。部分版本的用户页地址含 `#` 路由片段，NovaBot 不接受这种地址，可填不带片段的 realm 管理台入口 `https://accounts.example.com/admin/novabot/console/`，进去后选 `Users`。如果用的是 master realm 的管理员，就填实际使用的管理台入口，例如 `https://accounts.example.com/admin/master/console/`，进入后切到 `novabot` realm 再选 `Users`。打开页面仍需 Keycloak 的管理权限，填这个地址不会授予管理权限。

## 自查与常见问题

### 装好后先看发现文档

先在浏览器打开 `<issuer>/.well-known/openid-configuration`，也检查 NovaBot 所在机器能否访问它并信任证书。

| 文档中的项 | 先看什么 |
| --- | --- |
| `issuer` | 与 NovaBot 填的逐字相同，尤其是 realm 名、路径和末尾斜杠。 |
| `authorization_endpoint`、`token_endpoint`、`jwks_uri` | 都存在，均为完整 HTTPS 地址，使用能访问的对外主机名。 |
| `prompt_values_supported` | 包含 `create` 才显示“用统一身份注册”；没有此项或不含 `create` 不影响统一登录。 |
| `response_types_supported` | 若文档列出这一项，必须包含 `code`。 |
| `code_challenge_methods_supported` | 若列出，必须包含 `S256`。 |
| `token_endpoint_auth_methods_supported` | 若列出，必须包含 `client_secret_basic`。 |

后三项即使没有列出，账户中心实际也仍须支持对应能力。然后重启 NovaBot，从登录页点“使用统一登录”试一次；新身份出现“已登记，请等管理员授权。管理员授权后，再点「使用统一登录」进来即可。”，就说明身份验证和登记已经走通，不是登录配置失败。

### 登录页提示是什么意思

下面左栏与登录页原文一致。后三项只在管理员绑定身份时出现，这里仅用于辨认结果。

| 登录页提示 | 原因与处理 |
| --- | --- |
| 已登记，请等管理员授权。管理员授权后，再点「使用统一登录」进来即可。 | 身份已验证，但 NovaBot 账号仍待授权；管理员到设置页“成员”查看。 |
| 账号已停用，请联系管理员 | 此账号在 NovaBot 中已停用；请管理员核对成员状态。 |
| 管理员还没同意使用协议 | NovaBot 的使用协议尚未由管理员同意；管理员先用口令进入处理。 |
| 统一登录的客户端配置有误，请联系管理员 | 本地配置不完整、回调地址不合要求，或账户中心拒绝客户端 ID、密钥或客户端认证；核对七项、客户端登记及授权范围中的 `openid`。 |
| 统一登录暂时不可用，请稍后再试 | 账户中心连接失败、限流或暂时故障，也可能是 NovaBot 登录请求过密或繁忙；稍后再试，再查网络与账户中心状态。 |
| 统一登录已取消，请重试 | 账户中心返回拒绝授权；确认愿意授权后重新从登录页发起。 |
| 统一登录失败，请重试或联系管理员 | 登录过程过期、浏览器关联信息不匹配、授权响应或令牌校验失败等；从登录页重新开始，仍失败再核对 issuer、回调地址、签名、公钥、机器时间和账户中心响应。 |
| 这一步要先用本地方式重新登录 | 绑定时原来的本地登录已不满足最近登录要求；重新用口令登录后再操作。 |
| 身份已属于其他账号，请先处理那个账号 | 要绑定的统一登录身份已属于 NovaBot 的其他账号；先由管理员核对归属。 |
| 统一登录身份已绑定 | 管理员的身份绑定已成功。 |

### 登不进来时看哪里

先记下登录页的完整提示和时间，再按[第 11 章](11-where-to-see-what-happened.md)查看日志。搜索“配置界面拒绝了来自”和“来源 IP 不在白名单内”，可查浏览器或账户中心是否被白名单拦住；登录成功会记录“配置界面登录”及“方式: oidc”。

已登录的人迟迟没有因停用而下线时，找“统一登录复核失败”和“统一登录复核恢复”，结合时间、账号与原因分类检查账户中心是否撤销了刷新凭据、网络是否通畅。连续同类失败只记录首次，恢复时再记一条，不是每 4 分钟都重复写。日志不记录令牌；浏览器登录失败和后台登出被拒也不会逐项记录校验详情，不要以为没有报错日志就一定成功。进一步查账户中心的客户端登录事件和服务日志，按前面的发现文档与令牌清单核对。
