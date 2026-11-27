# 附录 C　安全须知与资源占用

正文讲怎么把它用起来，这一本附录讲两件容易被略过的事：哪些地方不设防会出事，
以及机器要备多少资源。两节都不长，装好之后通读一遍即可。
完整的安全说明在仓库的 [SECURITY.md](https://github.com/FrostNovaOrg/NovaBot/blob/main/SECURITY.md)，
这里挑使用者必须知道的几条。

## 部署时必做的几件

**配置界面默认只监听本机回环地址**并要求访问令牌。要从外网访问，请走 SSH 隧道，
不要直接把端口暴露到公网：

```bash
ssh -L 7827:127.0.0.1:7827 用户名@服务器地址
```

**推送接口有口令鉴权与来源 IP 白名单，默认只放行本机。** 若确需从其他机器调用，
同时做到三点：给 `novabot.adapter.onebot.senders` 下的每个推送平台配强随机的 `api-token`、把来源地址加进 `novabot.adapter.onebot.security.allow-ips`、在防火墙上限制来源。
设置页「推送接口 · 允许的来源 IP」框里显示的是正在生效的名单，默认只有本机两条（127.0.0.1/32、::1/128），
外部地址加在这两条下面；新名单里没有本机地址时，保存前会先问一句。
新接入的程序建议改用程序接口里的「推送」一组（`/nova/api/v1/push/`，见[附录 B「发一条消息」](appendix-b-advanced.md#发一条消息)）：
令牌按管理员账号签发、可随时吊销，每次发送都记在时间线上，不必把一把长期有效的推送口令写进配置文件。

**`cookies.json` 等同于哔哩哔哩账号的完整控制权**，默认加密存储，密钥在 `cookies.key`。
两个文件都要妥善保管，也都不要提交到任何仓库；权限定期核对仍是 `600`。
向他人求助时，最稳妥的做法是只发日志，不发整个工作目录。非发目录不可时，先删掉这些——
工作目录里的：

- `application.yml` 与它保存时留下的备份（`application.yml.yyyyMMdd-HHmmss.bak` 这类名字）：
  里面有控制台访问令牌、两步验证密钥、OneBot 与推送接口的令牌、邮件授权码这类凭据
- `cookies.json`、`cookies.key` 与 `cookies.json.plain.bak`（明文凭据迁移为加密存储时留下的备份）
- `speaker-cookies.json`、`speaker-cookies.key`：直播间互动插件的发言账号凭据与密钥（见[第 16 章](16-live-room-interact.md#凭据存在哪)）
- `datasource.json`：写着推送到哪些群、哪些 QQ
- `qqbot/`：里面有 QQ 官方机器人的密文、主密钥、群和成员的对照表与超级管理员名单

以及数据目录里的（没挪过数据目录的话，它与工作目录是同一个）：

- `state.json`：群成员的 QQ 号与其绑定的哔哩哔哩 uid
- `data.json` 与 `sessions.jsonl`：观众 uid 与昵称、按场的观众名单
- `timeline/`：事件时间线，含主播名与推送目标的群号／QQ 号
- `details/`：每场的弹幕原文与发言人、排行名单、大航海名单，含观众 uid 与昵称

上面这些文件旁边，名字后面多了 `.bad-` 加时间或 `.tmp` 的同名件，内容和原件一样，也要一并删掉。

**`novabot.core.exec` 能执行任意程序，默认关闭。** 打开前请确认清楚要跑的是什么；
命令以参数数组执行而非交给 shell，因此昵称、弹幕内容逃不出参数去
（写法见[附录 B](appendix-b-advanced.md)）。

**用小号登录哔哩哔哩。** 程序默认开着「自动关注被监听的主播」，会主动用登录账号执行关注操作，
凭据一旦泄露，攻击者即取得该账号的完整控制权。QQ 那一侧同理：发消息的是
OneBot 实现登录的那个号，别拿主号用。

自动关注被 B 站明确拒绝（如对方隐私设置不让关注）时，每天再试一次；网络出错照旧下一小时再试。

**OneBot 实现（NapCat 等）那一侧的端口要自己管住。** 安装脚本不会、也无法替你配置它。
NapCat 一般开三个监听，三个都要绑回环并各自设令牌——尤其是 **6099 的 WebUI，
拿到它就等于拿到这个 QQ 号**（能换登录、能往任意群发消息、能读历史）：

| 端口（默认） | 是什么 | 风险 |
|---|---|---|
| 3000 | OneBot HTTP | 能借机器人发消息 |
| 3001 | OneBot WebSocket | 同上 |
| 6099 | WebUI | 等同于这个 QQ 号的完整控制权 |

装完自己核一遍，每一行都应是 `127.0.0.1` 而不是 `0.0.0.0`：

```bash
ss -tlnp | grep -E ':(3000|3001|6099)\b'
```

需要远程用 WebUI 扫码时建端口转发过去（`ssh -L 6099:127.0.0.1:6099 用户名@服务器地址`），
不要把 6099 放开。

**配置界面若要开到公网，必须启用密码登录。** 放开 `novabot.core.config-ui.allow-ips` 而不设密码，
等于把「改推送目标、看运行数据」的权限对全网敞开。这份名单在设置页「允许访问的来源 IP」里改，
保存后从下一个请求起生效；会把你自己挡在外面的名单、有一条写错的名单都存不进去，原名单照用。
设了密码之后：二次验证默认要求，密钥必须明文存放在配置里（所以 `application.yml`
的权限要收紧到 `600`）；必须走 https；忘记密码或验证器丢了，把 `novabot.core.config-ui.auth.operator-token`
改成 `true` 重启，用启动日志里的地址进去改完再改回 `false`。
这个启动令牌**等同于密码加验证器**，而且走地址栏、会进反向代理的访问日志，平日里不要开着。

启动日志里的地址按 `INFO` 级别输出。日志只看错误或已关闭时，先把
`novabot.core.log.console` 或 `novabot.core.log.file` 临时改为 `INFO` 后重启，
用完把日志设置改回原值，并把 `novabot.core.config-ui.auth.operator-token` 改回 `false`。

## QQ 官方机器人的凭据和群数据

- AppSecret 加密存在本机。NovaBot 在取令牌、检查凭据或处理群资料时会短暂解密。你可以在控制台填写或更换 AppSecret，保存后只显示遮罩，读不回原文。
- QQ 开放平台发的访问令牌只留在内存里。QQ 开放平台给的用户原始标识不会写进日志，也不会显示在控制台里。
- 外来文字里的 @全体 标签会被转义，当作普通文字发，不能借它 @全体成员。
- 超级管理员的认领码 10 分钟内有效，一次用完。请要认领的人本人在群里发，别提前把码转给别人。
- 这一版由 NovaBot 主动连 QQ 开放平台接收消息。将来如果改用开放平台发来的回调，请验证签名，通过后再处理消息。

## 观众数据与金额

程序会按用户记录直播间内的互动量（弹幕条数、礼物价值等），用于直播报告里的弹幕排行、流水排行、
醒目留言名单和大航海名单等，并缓存观众头像地址。
这些都是直播间里本就公开可见的信息，不涉及私聊内容或好友关系。
两点仍值得注意：

- **账号绑定已停用**：没有接口能证明某个 QQ 号与某个哔哩哔哩账号属于同一人，绑定只核对昵称防打错。5.1.0 起绑定与个人数据查询的命令一并撤掉，控制台也不再显示这一块。报告里的观众互动量按哔哩哔哩账号统计。
- **`state.json` 里的历史绑定仍可以反推出人**，换机器、发日志、交接部署时按上面说的处理

**金额是另一回事。** 直播收益、每位观众送了多少钱，属于主播的经营信息与观众的消费记录。
4.2.0 起「谁能看到金额」是会话自己的属性，**默认私聊显示、群聊隐藏**，且只能在控制台改——
若允许群里的人自己把它打开，这道设置就形同虚设。

设置页的「直播间原始消息调试日志」与「动态接口原始响应调试日志」会把原始消息完整写进
日志，不需要时保持关闭；这两项连同「动态去重排查日志」保存后当场开关，不用重启。
排障用的 `EventDebug/` 也含观众昵称与 uid，用完记得关掉
（见[第 14 章](14-troubleshooting.md)）。

## 在反向代理之后

若在 Nginx 之后部署，需显式开启 `trust-proxy`，否则来源 IP 始终是代理的地址：

```yaml
novabot:
  adapter:
    onebot:
      security:
        trust-proxy: true
```

也可以在设置页「推送接口 · 信任反向代理」里开。这一项和「推送接口 · 允许的来源 IP」「推送接口 · 鉴权失败审计日志」「推送接口 · 频率限制」
保存后不用重启：下一个请求起就按新名单放行、按新值认来源、记日志、限频，不会有一段新旧混用的时间。
来源 IP 名单里有一条写错、或者整份留空，保存时会被拒，原名单照用。
「推送接口 · 允许的来源 IP」框里显示的是正在生效的名单，默认只有本机两条（127.0.0.1/32、::1/128）；
新名单里没有本机地址时，保存前会先问一句：点「确定」照常保存，本机上的程序从下一个请求起推不进来；
点「取消」回到编辑，什么都不写，原名单照用。127.0.0.0/8 这类包含本机的网段也算有本机地址。
每分钟请求数和突发请求数改了仍要重启。

反代还要把浏览器发来的 `Host` 原样送下来，不然控制台的登录或保存会被拒（「请求来源不正确」），
写法见 SECURITY.md 的「关于反向代理」一节。

**只有在请求确实全部经过可信代理时才可开启**——直接暴露的服务开这一项，
等于让 IP 白名单可以被一个请求头绕过。开启后取的是 `X-Forwarded-For` 里
**最右侧**的那一段（最左侧完全由客户端说了算）。本仓库的反代模板不让客户端自带的该头送下来，
自己写 Nginx 时用 `proxy_set_header X-Forwarded-For $remote_addr;` 覆写，改它之前请先读
[SECURITY.md](https://github.com/FrostNovaOrg/NovaBot/blob/main/SECURITY.md) 的
「关于反向代理」一节。

### 控制台的来源地址

控制台放在反向代理后面、要按真实来访地址判来源名单、登录锁定与 https 时，
在 `application.yml` 里设 `server.forward-headers-strategy: framework`。开着它时，NovaBot 这样认转发头：

- **只认可信代理送来的 `X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Ssl`。** 直接连进来的那一端是可信代理时，
  才按它送来的这几个头认来访地址与协议（`X-Forwarded-For` 取最左一段）。
  别的机器直接连到 NovaBot 的端口、自己带上这几个头，一概不认，这几个头也从请求里去掉。
- **`X-Forwarded-Host`、`X-Forwarded-Port`、`X-Forwarded-Prefix` 谁送来的都不认**，也从请求里去掉：
  主机名以 `Host` 头为准；端口在代理送了 `X-Forwarded-Proto` 时取那个协议的默认端口，否则也以 `Host` 为准；
  路径前缀只取 `server.servlet.context-path`。
  所以反向代理要把浏览器送来的原始 `Host` 传给后端，Nginx 写 `proxy_set_header Host $http_host;`（下文的样板都这样写了）。
- **`Forwarded` 头一律不认**，谁送来的都一样。只发 `Forwarded`、不发 `X-Forwarded-*` 的反向代理，要改成发 `X-Forwarded-*`。
- **可信代理默认是本机回环与私有网段**：`127.0.0.0/8`、`::1`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、
  `169.254.0.0/16`、`100.64.0.0/10`、`fe80::/10`、`fc00::/7`，与 Tomcat 默认信任的是同一套。
  反向代理不在这些网段里时，把它的地址或网段写进设置页的「可信反向代理 · 追加网段」
  （`novabot.core.config-ui.trusted-proxies`，在高级设置里），每行一个，重启后生效；写错的那一条启动时跳过，日志里记一条警告。
- **反向代理要覆写 `X-Forwarded-For`、清掉 `Forwarded`**：`proxy_set_header X-Forwarded-For $remote_addr;` 与
  `proxy_set_header Forwarded "";`，下文的样板都这样写了。追加式的写法（`$proxy_add_x_forwarded_for`）会把客户端自己写的那一段
  留在最左边，原因见 SECURITY.md 的「关于反向代理」一节。

### 让代理服务使用控制台会话

代理可用 `GET /config/api/session-check` 检查会话是否有效；该接口接受所有有效角色。
管理后端应使用 `GET /config/api/admin-session-check`，仅已启用的管理员返回 `204`。
两者成功时均无响应体；未登录、会话过期、账号待授权或已停用时返回 `401`，
管理员检查遇到成员时返回 `403`。尚未完成使用协议确认时也返回 `403`。
本地口令、统一登录的管理员和启动令牌通道均可使用管理员检查。
使用原有检查地址后，本次运行会记一条日志提醒，并在管理员网页控制台的各页顶部显示接入提醒。
改好管理后端的代理检查地址并重启 NovaBot，且不再使用原有检查地址后，提醒不再出现。

Nginx 接入管理后端的示例（端口与后端地址按实际部署填写）：

```nginx
location = /config/_admin-check {
    internal;
    proxy_pass http://127.0.0.1:7827/config/api/admin-session-check;
    proxy_method GET;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Host $http_host;
    proxy_set_header Cookie $http_cookie;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Forwarded "";
}

location /config/admin/ {
    auth_request /config/_admin-check;
    proxy_pass http://127.0.0.1:9000/;
}
```

被代理服务的浏览器访问路径也应位于 `/config/` 下，会话 Cookie 才会随请求发送。
若配置了 `server.servlet.context-path`，上述对外路径、检查地址与 `auth_request` 地址均加上该前缀。
检查请求同样沿用来源 IP 白名单与会话活跃时间刷新约定。后端仍需执行自身的登录与角色检查。

### 让控制台打开 NapCat 界面

下面是 nginx 的完整 HTTPS 站点示例。它把 `/config` 转给本机的 NovaBot，
把 `/config/napcat/` 转给本机的 NapCat，把 `/nova/api/v1/` 转给 NovaBot 的程序接口（用不着可以删掉那一段，
见下文「让别的机器经反向代理调程序接口」）。需要 nginx 带有 `http_auth_request` 和 `http_sub` 模块。
把示例放到 `http` 块里（也可以放在由 `http` 块引入的站点文件中），换成自己的域名和证书路径。
示例用控制台默认的 `7827` 和 NapCat WebUI 默认的 `6099`，改过端口的按实际填写。
若已经有控制台站点，把 `map` 放在 `http` 块里，再把 NapCat 相关的 `location` 合进原站点的 `server` 块。

nginx 转发默认等 60 秒没收到响应数据就断开。开启智能助手「按难度调思考强度」后，控制台转发的 `proxy_read_timeout` 至少要与「难题限时（秒）」一样长，示例给默认的 180 秒多留了一点时间。

```nginx
# 浏览器打开网页时通常声明接收 text/html，没登录时可以把这类请求送回控制台。
map $http_accept $napcat_wants_html {
    default       0;
    "~*text/html" 1;
}

server {
    # 用同一个 HTTPS 站点提供控制台和 NapCat 界面，控制台的会话才能给代理用。
    listen 443 ssl;
    server_name example.com;
    ssl_certificate     /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    # 控制台照原路径转发，登录与来源检查需要原样的 Host 和代理写入的来源头。
    # X-Forwarded-For 只写 $remote_addr 一个地址（覆写，不追加）；客户端自己带来的 Forwarded 头清掉。
    location /config {
        proxy_pass http://127.0.0.1:7827;
        proxy_read_timeout 190s;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Forwarded "";
    }

    # 程序接口：只转发 /nova/api/v1/，带上真实来访地址，NovaBot 才认得出是谁在调。
    # 转发了不等于放通：经这里进来的都不算本机，调不调得了，看控制台「访问令牌」页的开关。
    # 客户端自己带来的 Forwarded 头去掉，免得与下面两个头说的不是同一个地址、被当成未知来访者。
    # 两个头都只写 $remote_addr 这一个地址：NovaBot 取 X-Forwarded-For 最右一段，而头里有一处不合规就整个不认。
    # /nova/events、/nova/readonly-token 不在这个前缀下，不要另外转发，交给最后那条 404。
    location /nova/api/v1/ {
        proxy_pass http://127.0.0.1:7827;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Forwarded "";
    }

    # NapCat 以 root 运行，在 nginx 就挡住网页终端、调试、文件管理、插件安装和在线更新。
    # NapCat 的路由不分大小写，用 ~* 拦下大小写不同的写法。
    location = /config/napcat/api/ws/terminal { return 403; }
    location ^~ /config/napcat/api/Debug/ { return 403; }
    location ~* ^/config/napcat/api/(debug|ws/terminal|log/terminal) { return 403; }
    location ~* ^/config/napcat/api/(file|plugin|updatenapcat) { return 403; }

    # 每个交给 NapCat 的请求先用 auth_request 问控制台，只有登录的管理员才能通过。
    # 检查地址只供 nginx 内部使用，子请求带上会话 Cookie 和与 /config 相同的来源头，不带请求体。
    # 检查时去掉 NapCat 的 Authorization 头，避免只用访问令牌的控制台把它当成控制台令牌，拒绝有效的会话 Cookie。
    location = /config/_admin-check {
        internal;
        proxy_pass http://127.0.0.1:7827/config/api/admin-session-check;
        proxy_method GET;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header Host $http_host;
        proxy_set_header Cookie $http_cookie;
        proxy_set_header Authorization "";
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Forwarded "";
    }

    # 直接访问不带尾斜杠的入口时，转到 WebUI 首页。
    location = /config/napcat { return 308 /config/napcat/webui/; }

    location /config/napcat/ {
        # NapCat 把调试以外的 WebSocket 升级都交给终端，这个路径下一律拒绝升级请求。
        if ($http_upgrade) { return 403; }

        auth_request /config/_admin-check;
        error_page 401 = @napcat_unauthorized;

        # proxy_pass 末尾的 / 会去掉对外的路径前缀，NapCat 收到的是 /webui/ 和 /api/。
        # NapCat 发出的重定向要带回这个前缀，控制台的会话 Cookie 则不用交给 NapCat。
        proxy_pass http://127.0.0.1:6099/;
        proxy_http_version 1.1;
        proxy_redirect http://127.0.0.1:6099/ /config/napcat/;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Cookie "";

        # 关掉响应缓冲并给长连接留出时间，让实时日志及时显示。
        proxy_buffering off;
        proxy_read_timeout 1h;

        # 清掉 Accept-Encoding，让 NapCat 回未压缩的内容。
        # sub_filter 才能把 HTML 和 JavaScript 里写死的根路径改到 /config/napcat/ 下。
        proxy_set_header Accept-Encoding "";
        sub_filter_types text/javascript;
        sub_filter_once off;
        sub_filter '"/webui/' '"/config/napcat/webui/';
        sub_filter '"/api'    '"/config/napcat/api';

        # 覆盖 NapCat 的缓存设置，浏览器和中间层都不保存这些响应。
        proxy_cache off;
        proxy_hide_header Cache-Control;
        add_header Cache-Control "private, no-store" always;
    }

    # 未登录时，网页请求跳回 /config 让用户登录，其他请求直接收到 401。
    location @napcat_unauthorized {
        add_header Cache-Control "private, no-store" always;
        if ($napcat_wants_html) { return 302 /config; }
        return 401;
    }

    # 其余路径不对外提供服务。
    location / { return 404; }
}
```

换好域名、证书和端口后，用 `nginx -t` 检查配置，再重载 nginx。

### 让别的机器经反向代理调程序接口

上面样板里的 `location /nova/api/v1/` 那一段，让外面的程序经 https 调 NovaBot 的程序接口
（例如另一台设备上的外发箱取件程序），地址是 `https://<你的域名>/nova/api/v1/`。几点要知道：

- **转发了不等于放通。** 程序接口默认只许本机上的程序调；经这一段进来的请求带着转发头，在 NovaBot 看来都不是本机
  （NovaBot 按转发头认出真实来访者，用来记日志、算无效令牌限速。开着 `server.forward-headers-strategy: framework` 时只用可信代理送来的 `X-Forwarded-For`，`X-Real-IP` 不拿来定地址。所以反向代理必须写 `X-Forwarded-For`，
  只写 `X-Real-IP` 的，开着 `framework` 时来访者会被当作未知来访者），默认收到 403。
  要让外面调某一组接口，管理员在控制台「访问令牌」页的「从别的机器访问」里打开那一组（仍须有效的访问令牌）。
- **「允许访问的来源 IP」不影响接口。** 那份名单只管控制台能从哪里打开：控制台放在公网上、名单放行所有地址时，
  接口照样只有本机和打开了的那几组调得了，不用为接口去收窄名单。
- **`$remote_addr` 得是外面来访者的真实地址。** 样板里转发头写 `$remote_addr`，前提是连到这台 nginx 的就是外面的来访者。
  nginx 前面还有一层隧道、加速服务或别的代理时，`$remote_addr` 是那一层的地址，NovaBot 会把所有人都认成同一个地址：
  日志里看不出是谁在调，一个人乱试令牌就会把所有人一起限速。这时要改用那一层送来的真实来访地址，或者在 nginx 上配好真实地址还原
  （`set_real_ip_from` 加 `real_ip_header`），只信那一层送来的头。改完从外面不带令牌访问一次
  没打开的那组接口（一组都没打开时用 `https://<你的域名>/nova/api/v1/me` 即可），应当收到 403，
  NovaBot 日志里「统一接口拒绝了来自 … 的访问」那一行写的应当是你那台设备的公网地址；
  收到 401 说明 nginx 没把转发头送到，外面的来访者被当成了本机。
- **转发头要写全。** 这一段写了 `X-Real-IP` 与 `X-Forwarded-For`、去掉了客户端带来的 `Forwarded`。
  一个转发头也不写的话，经代理进来的请求在 NovaBot 看来与本机直连一样，都算本机，开关拦不住它们。
- **只转发 `/nova/api/v1/`。** 事件流 `/nova/events` 与只读口令代签发 `/nova/readonly-token` 不在这个前缀下，
  它们另有自己的转发写法（见发行包里的 Caddy 模板），不要顺手把整个 `/nova` 转出去。
- **一定走 https。** 没有反向代理、直接把 NovaBot 的端口放到公网，令牌会明文经过网络。
- 控制台 `/config` 的来源判断不随这一段改变：没开 `server.forward-headers-strategy` 时它看的是直接连进来的那一端，
  也就是本机的 nginx；开着 `framework` 时看的是 nginx 送来的 `X-Forwarded-For`（见上文「控制台的来源地址」）。

只想让内网的机器调、或者用 SSH 隧道，不需要这一段，见[附录 B「从另一台机器连」](appendix-b-advanced.md#从另一台机器连)。

### 挂在路径前缀下

要把控制台挂在某个路径前缀下（例如 `https://example.com/novabot/config`），
把 Spring 的 `server.servlet.context-path` 设成那个前缀：

```yaml
server:
  servlet:
    context-path: /novabot
```

- 反代**原样转发路径**，不剥前缀：`/novabot/` 下的请求照原路径转给 NovaBot。
  页面、接口、登录 Cookie 都按这个前缀写，地址对不上时登录后会又回到登录页。
- NapCat 示例里 nginx 接收与生成的 `/config` 路径，以及控制台的会话检查地址，都加上前缀。
  程序接口那一段同样加上前缀，例如 `location /novabot/nova/api/v1/`。
  例如 NapCat 的对外路径改成 `/novabot/config/napcat/`，转给 NapCat 的地址仍保留尾斜杠。
- 反代剥掉前缀再转发、靠 `X-Forwarded-Prefix` 告诉 NovaBot 前缀的那种部署**不支持**：
  NovaBot 只认自己配置里的前缀，不认请求头里的。

## 资源占用

一句话：**按带负载的稳态峰值（约 896 MB）规划机器内存**。这个数是测过的那个场景里的峰值，不是上界；约 476 MB 只是同条件下较短时段的稳态常驻，按它去备会不够。512 MB 的机器不够用；
1 GB 的机器要把 `-Xmx` 与 systemd 的 `MemoryHigh` / `MemoryMax` **一起**往下调，只压其中一个
会让进程被内核直接杀掉或被节流拖慢；`MemoryHigh` / `MemoryMax` 用覆盖设置放在模板单元上，
别直接改单元文件（升级会被模板重写）。实测条件、数字与调法都在
[附录 A](appendix-a-measurements.md#内存要备多少：实测数字)。

启动脚本默认让回收后的堆更容易缩小，恢复 JDK 默认堆空闲比例的方法见
[附录 A 的启动参数说明](appendix-a-measurements.md#内存偏高时怎么量、怎么调)。

CPU 占用主要来自直播间长连接的心跳与动态轮询，监听的主播多时属正常。

## 报告漏洞与使用立场

安全问题**不要走公开渠道发**。请用仓库的 Security → Report a vulnerability
（私密漏洞报告）描述问题与复现步骤，收到后会尽快处理。

本项目面向中小型公会、个人势主播及其运营人员，用于自有或已获授权的直播间数据采集、
通知与运营分析。**不做风控对抗**：不伪造浏览器指纹、不伪造观看行为数据，
连接行为按「礼貌客户端」设计（指数退避、全局连接节流、不高频轮询、遵守平台限流反馈）。
任何「提高采集速度或密度」的需求都要先过这条线，大规模采集场景下不保证账号安全与数据完整性。

两件本项目无法根治、只能让它不静默发生的事：**NapCat 等 OneBot 实现属于非官方 QQ 协议
实现，存在账号被封禁的风险**；QQ 登录态可能在无预兆的情况下失效（服务端或 NapCat 重启后
有一定概率提示登录态已失效），需要重新扫码。OneBot 连接中断会触发健康告警，
配上不经过机器人链路的通道（Webhook / Bark 一类）才能在 QQ 那一侧已经停摆时把消息送出来。
