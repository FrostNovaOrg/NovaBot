package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.config.ui.auth.TotpGenerator;
import com.starlwr.bot.core.util.QrCodeUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 配置界面登录接口
 * <p>
 * 只在配置了登录口令时才真正起作用。未配置时 {@code /state} 会如实回报「未启用」，
 * 前端据此不显示登录页。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/auth")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiAuthController {
    /**
     * 登录口令所在的配置项，界面上填的明文会被哈希后写回此处
     */
    public static final String PASSWORD_PROPERTY = ConfigUiAuthService.PASSWORD_PROPERTY;

    /**
     * 二次验证密钥所在的配置项，绑定成功后写回此处
     */
    private static final String TOTP_SECRET_PROPERTY = "starbot.core.config-ui.auth.totp-secret";

    /**
     * 验证器应用中显示的服务名与账号名
     * <p>
     * 单用户面板没有账号可言，账号名固定即可——它只是让用户在验证器的一长串条目里认出这一条。
     */
    private static final String TOTP_ISSUER = "NovaBot";

    private static final String TOTP_ACCOUNT = "控制台";

    /**
     * 二维码边长，单位：像素
     */
    private static final int QR_CODE_IMAGE_SIZE = 320;

    /**
     * 使用协议的同意记录所在的配置项，点了「同意并继续」后写回此处
     */
    private static final String AGREEMENT_VERSION_PROPERTY = "starbot.core.config-ui.agreement.accepted-version";

    private static final String AGREEMENT_TIME_PROPERTY = "starbot.core.config-ui.agreement.accepted-at";

    private static final String AGREEMENT_BY_PROPERTY = "starbot.core.config-ui.agreement.accepted-by";

    private final ConfigUiAuthService authService;

    private final ConfigurationFileService fileService;

    private final StarBotCoreProperties.ConfigUi.Auth properties;

    /**
     * 使用协议的同意记录
     * <p>
     * 拿的是配置里那一份本体而不是它的副本：点了同意之后要<b>当场</b>放行，
     * 安全过滤器读的也是同一个对象，不必等下次重启。
     */
    private final StarBotCoreProperties.ConfigUi.Agreement agreement;

    public ConfigUiAuthController(ConfigUiAuthService authService, ConfigurationFileService fileService, StarBotCoreProperties properties) {
        this.authService = authService;
        this.fileService = fileService;
        this.properties = properties.getConfigUi().getAuth();
        this.agreement = properties.getConfigUi().getAgreement();
    }

    /**
     * 查询登录状态
     * <p>
     * 前端每次加载都要问一次：要不要登录、有没有登录、要不要二次验证码。
     * @return 登录状态
     */
    @GetMapping("/state")
    public JSONObject state(HttpServletRequest request) {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("enabled", authService.isEnabled());
        result.put("totpRequired", authService.totpRequired());

        // 协议这一问与「有没有登录」是两件事，未登录也如实回答：
        // 前端要靠这两个值决定摆出口令表单还是协议面板
        result.put("agreementRequired", ConfigUiAgreement.required(agreement));
        result.put("agreementVersion", ConfigUiAgreement.VERSION);

        // 不再看 isEnabled：未配口令的那一形态本来就没有会话，问一句返回空而已，
        // 而拿它当前提会让「凭启动令牌换来的会话」在这条接口上显示成未登录
        Optional<ConfigUiSession> session = authService.validate(sessionId(request));

        result.put("authenticated", session.isPresent());
        // CSRF 令牌只发给已经持有该会话的人，它本身不是秘密，但发给未登录者没有任何意义
        session.ifPresent(value -> result.put("csrfToken", value.getCsrfToken()));

        // 已登录但还没绑验证器时提示去绑，本次登录按掉过就不再提
        result.put("totpSetupNeeded", authService.totpPending()
                && session.map(value -> !value.isTotpSetupDismissed()).orElse(false));

        return result;
    }

    /**
     * 取使用协议全文
     * <p>
     * 文案不随接口下发第二份副本，界面拿到什么就显示什么——
     * 页面里再抄一遍的话，改了文件而没改页面，使用者同意的与文件里写的就不是同一份东西了。
     * @return 协议版本与全文
     */
    @GetMapping("/agreement")
    public JSONObject agreement() {
        JSONObject result = new JSONObject();
        result.put("version", ConfigUiAgreement.VERSION);

        try {
            result.put("text", ConfigUiAgreement.text());
            result.put("success", true);
        } catch (IOException e) {
            log.error("读取使用协议文案失败", e);
            result.put("success", false);
            result.put("message", "读取使用协议失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 同意使用协议
     * <p>
     * <b>必须先通过身份校验</b>：同意是一个签字动作，签字的人得先被认出来。
     * 反过来（未登录也能点）的话，任何能连上控制台端口的程序都替使用者签得下去，
     * 而那行记录一旦写下，使用者本人就再也不会被问第二次——记录也就证明不了任何事。
     * <p>
     * 认人这件事由安全过滤器办，通道名从它那里接过来：判两遍就会有两个答案，
     * 其中一个迟早与实际放行的依据对不上。
     * @return 同意之后的登录状态
     */
    @PostMapping("/agreement/accept")
    public ResponseEntity<JSONObject> acceptAgreement(HttpServletRequest request) {
        ConfigUiSession.Channel channel = ConfigUiSession.Channel.fromWire(
                String.valueOf(request.getAttribute(ConfigUiSecurityFilter.CHANNEL_ATTRIBUTE)));

        if (channel == null) {
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("message", "请先登录，再确认使用协议");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }

        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        agreement.setAcceptedBy(channel.wire());
        persistAgreement(request.getRemoteAddr());

        return ResponseEntity.ok(state(request));
    }

    /**
     * 把同意记录写回配置文件
     * <p>
     * 与口令哈希写回是同一副形状：先认下、再落盘，<b>写不进去也照常放行</b>——
     * 人已经看过并点了同意，这件事已经发生了；写盘失败的后果只是下次启动还要再点一次，
     * 而反过来让一次写盘失败把人挡在控制台外面，是把一件小事办成了故障。
     * <p>
     * 通道写进配置，来源 IP 只写进日志：配置文件里那三行是给使用者看的凭据，
     * 多一个他看不懂也用不上的地址只会碍事；而排查「这是谁点的」时要的恰恰是日志里那一行。
     * @param clientIp 点同意的那一端的地址
     */
    private void persistAgreement(String clientIp) {
        if (fileService == null) {
            log.warn("配置界面的使用协议同意记录未能保存, 下次启动会再次要求确认");
            return;
        }

        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(AGREEMENT_VERSION_PROPERTY, String.valueOf(ConfigUiAgreement.VERSION));
        changes.put(AGREEMENT_TIME_PROPERTY, agreement.getAcceptedAt());
        changes.put(AGREEMENT_BY_PROPERTY, agreement.getAcceptedBy());

        try {
            fileService.write(changes);
            log.info("配置界面: 已同意第 {} 版使用协议, 时间 {}, 通道 {}, 来源 {}",
                    ConfigUiAgreement.VERSION, agreement.getAcceptedAt(), agreement.getAcceptedBy(), clientIp);
        } catch (Exception e) {
            log.warn("配置界面的使用协议同意记录未能写入配置文件, 下次启动会再次要求确认: {}", e.getMessage());
        }
    }

    /**
     * 取绑定验证器所需的二维码与密钥
     * @return 密钥、otpauth 链接与二维码图片
     */
    @GetMapping("/totp/setup")
    public JSONObject totpSetup() {
        JSONObject result = new JSONObject();

        if (!authService.totpPending()) {
            result.put("success", false);
            result.put("message", "无需绑定验证器");
            return result;
        }

        String secret = authService.pendingSecret();
        String uri = TotpGenerator.provisioningUri(secret, TOTP_ACCOUNT, TOTP_ISSUER);

        result.put("success", true);
        // 密钥一并给出：有些验证器不方便扫码，得手动输入
        result.put("secret", secret);
        result.put("uri", uri);
        QrCodeUtil.generateQrCodeAndGetBase64(uri, QR_CODE_IMAGE_SIZE).ifPresent(qr -> result.put("qrCode", qr));

        return result;
    }

    /**
     * 确认绑定
     * <p>
     * 必须先输一次验证码才算绑定成功。少了这一步，用户以为扫上了、实际没扫上，
     * 下次登录就被自己的二次验证挡在门外。
     * @param body 请求体，code 字段为验证器给出的六位数字
     * @return 绑定结果
     */
    @PostMapping("/totp/enroll")
    public JSONObject totpEnroll(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        if (!authService.totpPending()) {
            result.put("success", false);
            result.put("message", "无需绑定验证器");
            return result;
        }

        String secret = authService.verifyPending(body.getString("code")).orElse(null);
        if (secret == null) {
            result.put("success", false);
            result.put("message", "验证码不正确，请确认手机时间是否准确后重试");
            return result;
        }

        // 先落盘再启用：反过来的话，写文件失败会让界面说「绑好了」而重启后又要重新绑，
        // 中间这段时间登录要输的还是一个没人记得的密钥
        try {
            fileService.write(Map.of(TOTP_SECRET_PROPERTY, secret));
        } catch (IOException e) {
            log.error("写入二次验证密钥失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return result;
        }

        authService.activateTotp(secret);
        result.put("success", true);
        result.put("message", "已绑定，下次登录需要输入动态验证码");

        return result;
    }

    /**
     * 暂不绑定
     * <p>
     * 只对本次登录有效。写进配置就成了永久关闭，而那是个该显式做出的决定。
     */
    @PostMapping("/totp/skip")
    public JSONObject totpSkip(HttpServletRequest request) {
        authService.validate(sessionId(request)).ifPresent(session -> session.setTotpSetupDismissed(true));

        JSONObject result = new JSONObject();
        result.put("success", true);

        return result;
    }

    /**
     * 登录
     * @param body 请求体，含 password 与可选的 code
     * @return 登录结果，成功时下发会话 Cookie 与 CSRF 令牌
     */
    @PostMapping("/login")
    public ResponseEntity<JSONObject> login(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (!authService.isEnabled()) {
            result.put("success", false);
            result.put("message", "未启用口令登录");
            return ResponseEntity.badRequest().body(result);
        }

        // 协议那道闸不在这里，而在这一步之后：先认出人，再问他同不同意。
        // 挡在登录之前的话，点同意的就未必是使用者本人了，而那行记录也就说明不了任何事。
        // 此刻发出去的会话除了看协议什么也打不开，安全过滤器那一层会把控制台一直关着
        char[] password = Optional.ofNullable(body.getString("password")).orElse("").toCharArray();
        try {
            ConfigUiAuthService.LoginResult outcome = authService.login(password, body.getString("code"), request.getRemoteAddr());

            if (!outcome.success()) {
                result.put("success", false);
                result.put("message", outcome.message());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
            }

            result.put("success", true);
            result.put("csrfToken", outcome.session().getCsrfToken());

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(outcome.session().getId(), request).toString())
                    .body(result);
        } finally {
            // 明文口令用完即抹，不留在堆里等垃圾回收
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 注销
     * @param all 是否注销全部设备上的会话
     */
    @PostMapping("/logout")
    public ResponseEntity<JSONObject> logout(@RequestParam(defaultValue = "false") boolean all, HttpServletRequest request) {
        JSONObject result = new JSONObject();
        result.put("success", true);

        if (all) {
            result.put("count", authService.logoutAll());
        } else {
            authService.logout(sessionId(request));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie(request).toString())
                .body(result);
    }

    private String sessionId(HttpServletRequest request) {
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(c -> ConfigUiSecurityFilter.SESSION_COOKIE.equals(c.getName()))
                        .findFirst())
                .map(jakarta.servlet.http.Cookie::getValue)
                .orElse(null);
    }

    /**
     * 构造会话 Cookie
     * <p>
     * {@code HttpOnly} 挡住页面脚本读取，{@code SameSite=Strict} 让跨站请求根本带不上它。
     * <p>
     * {@code Secure} 跟随当前连接是否为 https：面板部署在反向代理之后时，
     * 需要配置 {@code server.forward-headers-strategy}，否则这里看到的永远是明文连接，
     * Cookie 就不会带上 {@code Secure}。
     */
    private ResponseCookie sessionCookie(String value, HttpServletRequest request) {
        return ResponseCookie.from(ConfigUiSecurityFilter.SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(ConfigUiController.BASE_PATH)
                .maxAge(Duration.ofHours(Math.max(1, properties.getSessionHours())))
                .build();
    }

    private ResponseCookie expiredCookie(HttpServletRequest request) {
        return ResponseCookie.from(ConfigUiSecurityFilter.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(ConfigUiController.BASE_PATH)
                .maxAge(0)
                .build();
    }
}
