package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.PasswordHash;
import org.frostnova.nova.core.config.ui.auth.TotpGenerator;
import org.frostnova.nova.core.util.QrCodeUtil;
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
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiAuthController {
    /**
     * 登录口令所在的配置项，界面上填的明文会被哈希后写回此处
     */
    public static final String PASSWORD_PROPERTY = ConfigUiAuthService.PASSWORD_PROPERTY;

    /**
     * 新口令的最短长度
     * <p>
     * 拦的是「把口令改成 1234 之后忘了自己改过」。不设上限、不要求混字符：
     * 那类规则逼出来的是写在便利贴上的口令，而这台面板的正门另有通行密钥与二次验证。
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

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
    private static final String AGREEMENT_VERSION_PROPERTY = "novabot.core.config-ui.agreement.accepted-version";

    private static final String AGREEMENT_TIME_PROPERTY = "novabot.core.config-ui.agreement.accepted-at";

    private static final String AGREEMENT_BY_PROPERTY = "novabot.core.config-ui.agreement.accepted-by";

    private final ConfigUiAuthService authService;

    private final ConfigurationFileService fileService;

    private final NovaCoreProperties.ConfigUi.Auth properties;

    /**
     * 使用协议的同意记录
     * <p>
     * 拿的是配置里那一份本体而不是它的副本：点了同意之后要<b>当场</b>放行，
     * 安全过滤器读的也是同一个对象，不必等下次重启。
     */
    private final NovaCoreProperties.ConfigUi.Agreement agreement;

    public ConfigUiAuthController(ConfigUiAuthService authService, ConfigurationFileService fileService, NovaCoreProperties properties) {
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

        // 配置文件在不在。界面进首页该不该转到初始设置<b>不再看这一位</b>：同意使用协议
        // 就会写出 application.yml，按文件在不在判的话刚装好的机器会停在空首页。
        // 那一跳改看五步完成了 0 步（home-model 的 shouldOpenSetup）。这一栏仍下发，
        // 旧界面认它时「没这一栏不跳」；取值仍是文件在不在。
        //
        // 配置文件那一侧拿不到时<b>不作答</b>：这一位只有在、不在两种取值，
        // 而「答不上来」不是其中之一。填 false 会把还在用旧界面的人钉死在初始设置页上；
        // 填 true 则是拿一个没有根据的值去答一个本来查得到的问题。
        if (fileService != null) {
            result.put("setupDone", fileService.exists());
        }

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
        // 设置页那个开关显示在哪一档，照这一位来而不是照配置项猜：
        // 绑定与关闭都当场生效，配置文件里那一行要到下次重启才被读一遍
        result.put("totpEnabled", authService.totpEnabled());

        // 这个来源还要被锁多少秒。
        //
        // 🔴 给的是「还剩多少」而不是「什么时候解锁」：后者要拿浏览器的钟去减，
        // 而那台电脑的钟未必准——屏幕上的倒计时会因此差出几分钟，甚至是负的。
        result.put("lockedSeconds", remainingLockSeconds(request));

        // 是不是凭启动令牌进来的。控制台顶部那条常驻提醒照这一位显示——
        // 那条通道绕过了口令与二次验证，进来之后第一件事就该是改口令再把它关掉
        result.put("operatorSession", session
                .map(value -> value.getChannel() == ConfigUiSession.Channel.OPERATOR_TOKEN)
                .orElse(false));

        return result;
    }

    /**
     * 这个来源还要被锁多少秒
     * <p>
     * 向上取整：剩 0.4 秒时回 0 会让界面把表单放开，而下一次提交仍然会被拒。
     * @param request 请求
     * @return 剩余秒数，未锁定时为 0
     */
    private long remainingLockSeconds(HttpServletRequest request) {
        Duration remaining = authService.remainingLockout(request.getRemoteAddr());
        return remaining.isZero() || remaining.isNegative() ? 0 : (remaining.toMillis() + 999) / 1000;
    }

    /**
     * 二次验证敏感操作被限流或锁定时的回包
     * <p>
     * 与登录同一把桶，话术却不能写成「登录失败」：人正在关二次验证或绑验证器，
     * 说登录会让他以为走错了口。
     */
    private JSONObject refuseSensitiveTotp(ConfigUiAuthService.CredentialCheck check, HttpServletRequest request) {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("lockedSeconds", remainingLockSeconds(request));
        if (check.verdict() == ConfigUiAuthService.Verdict.LOCKED_OUT) {
            long minutes = Math.max(1, check.retryAfter().toMinutes());
            result.put("message", "尝试次数过多，请在 " + minutes + " 分钟后重试");
        } else {
            result.put("message", "服务器正忙，请稍后重试");
        }
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

        // 🔴 <b>写不进去也照常放行</b>：人已经看过并点了同意，这件事已经发生了；
        // 写盘失败的后果只是下次启动还要再点一次，而反过来让一次写盘失败把人挡在控制台外面，
        // 是把一件小事办成了故障。撤回那一侧的取舍正好相反，见 {@link #revokeAgreement}
        if (persistAgreement(ConfigUiAgreement.VERSION, agreement.getAcceptedAt(), agreement.getAcceptedBy())) {
            log.info("配置界面: 已同意第 {} 版使用协议, 时间 {}, 通道 {}, 来源 {}",
                    ConfigUiAgreement.VERSION, agreement.getAcceptedAt(), agreement.getAcceptedBy(),
                    request.getRemoteAddr());
        } else {
            log.warn("配置界面的使用协议同意记录未能保存, 下次启动会再次要求确认");
        }

        return ResponseEntity.ok(state(request));
    }

    /**
     * 撤回对使用协议的同意
     * <p>
     * 撤回之后这台机器回到<b>未签态</b>：{@link ConfigUiAgreement#required} 转真，
     * 安全过滤器那道闸随之关上，所有人（包括别处仍开着的会话）都要重新同意才进得了控制台。
     * 不必在这里逐条去关什么——闸只此一处，撤回只负责把记录抹掉。
     * <p>
     * <b>三道门</b>：
     * <ul>
     *   <li><b>要有身份</b>——与「同意」同理，撤回撤的是一次签字，门外的人撤不了别人的字</li>
     *   <li><b>得先同意过</b>——撤一个不存在的同意，写下的是一行没有对应事实的记录</li>
     *   <li><b>先落盘再认</b>——与 accept 相反：那一侧写盘失败照常放行（人确实看过了），
     *       而这一侧写盘失败若照样放行，盘上还写着同意，重启之后它<b>就又作数了</b>，
     *       而屏幕上撤回那一刻显示的是「已撤回」</li>
     * </ul>
     * 点撤回的那一把会话当场注销：人刚说了「我不同意了」，不该还捏着一把开着的钥匙。
     * 别处的会话不动——那是「注销全部设备」那颗按钮的事，协议这道闸管的是控制台，不是回收钥匙。
     * @return 撤回之后的结果
     */
    @PostMapping("/agreement/revoke")
    public ResponseEntity<JSONObject> revokeAgreement(HttpServletRequest request) {
        JSONObject result = new JSONObject();

        ConfigUiSession.Channel channel = ConfigUiSession.Channel.fromWire(
                String.valueOf(request.getAttribute(ConfigUiSecurityFilter.CHANNEL_ATTRIBUTE)));
        if (channel == null) {
            result.put("success", false);
            result.put("message", "请先登录，再撤回对使用协议的同意");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }

        if (ConfigUiAgreement.required(agreement)) {
            result.put("success", false);
            result.put("message", "这台机器本来就没有同意过使用协议");
            return ResponseEntity.badRequest().body(result);
        }

        // 落盘用的是「未签」那一组值，而不是内存里当前那一组：先改内存再写、写失败再改回来的话，
        // 中间那一小段时间里别的请求读到的是一个并没有发生的撤回
        if (!persistAgreement(0, "", "")) {
            result.put("success", false);
            result.put("message", "保存失败，同意记录没有改动");
            return ResponseEntity.internalServerError().body(result);
        }

        agreement.setAcceptedVersion(0);
        agreement.setAcceptedAt("");
        agreement.setAcceptedBy("");
        authService.logout(sessionId(request));

        log.warn("配置界面: 已撤回对使用协议的同意, 控制台自此要求重新确认, 通道 {}, 来源 {}",
                channel.wire(), request.getRemoteAddr());

        result.put("success", true);
        result.put("message", "已撤回。这台机器回到未同意使用协议的状态，控制台要重新同意才进得来");
        return ResponseEntity.ok(result);
    }

    /**
     * 把同意记录写回配置文件
     * <p>
     * 同意与撤回<b>共用这一条写路</b>：各写一份的话，「撤回要不要连时间一起抹掉」这件事
     * 迟早会有两个答案，而多留下的那一行是一份指向已被撤回的同意的凭据。
     * <p>
     * 收的是三个显式的值而不是读内存里那一份：撤回要先落盘再改内存（写不进去就当没撤过），
     * 读内存的写法逼着调用方先改后写，而那正是这一侧不能接受的顺序。
     * <p>
     * 通道写进配置，来源 IP 只写进日志：配置文件里那三行是给使用者看的凭据，
     * 多一个他看不懂也用不上的地址只会碍事；而排查「这是谁点的」时要的恰恰是日志里那一行。
     * @param version 要写下的版本号，撤回时为 0
     * @param acceptedAt 要写下的时间，撤回时为空串
     * @param acceptedBy 要写下的通道，撤回时为空串
     * @return 真的写进文件时为 true
     */
    private boolean persistAgreement(int version, String acceptedAt, String acceptedBy) {
        if (fileService == null) {
            return false;
        }

        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(AGREEMENT_VERSION_PROPERTY, String.valueOf(version));
        changes.put(AGREEMENT_TIME_PROPERTY, acceptedAt);
        changes.put(AGREEMENT_BY_PROPERTY, acceptedBy);

        try {
            fileService.write(changes);
            return true;
        } catch (Exception e) {
            log.warn("配置界面的使用协议记录未能写入配置文件: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 取绑定验证器所需的二维码与密钥
     * @return 密钥、otpauth 链接与二维码图片
     */
    @GetMapping("/totp/setup")
    public JSONObject totpSetup() {
        JSONObject result = new JSONObject();

        // 看的是「绑得了吗」而不是「要不要提示他去绑」：设置页里把二次验证从关拨到开，
        // 走的正是先绑后开这条路，此刻那一位还在「关」上
        if (!authService.canEnrollTotp()) {
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
    public JSONObject totpEnroll(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (!authService.canEnrollTotp()) {
            result.put("success", false);
            result.put("message", "无需绑定验证器");
            return result;
        }

        ConfigUiAuthService.CredentialCheck gate = authService.beginSensitiveTotp(request.getRemoteAddr());
        if (!gate.ok()) {
            return refuseSensitiveTotp(gate, request);
        }

        String secret = authService.verifyPending(body.getString("code")).orElse(null);
        if (secret == null) {
            authService.failSensitiveTotp(request.getRemoteAddr());
            result.put("success", false);
            result.put("message", "验证码不正确，请确认手机时间是否准确后重试");
            result.put("lockedSeconds", remainingLockSeconds(request));
            return result;
        }

        // 先落盘再启用：反过来的话，写文件失败会让界面说「绑好了」而重启后又要重新绑，
        // 中间这段时间登录要输的还是一个没人记得的密钥。
        // 开关那一位与密钥一起写：只写密钥的话，从设置页拨开的那一次重启后又变回关着
        try {
            fileService.write(new LinkedHashMap<>(Map.of(
                    ConfigUiAuthService.TOTP_SECRET_PROPERTY, secret,
                    ConfigUiAuthService.TOTP_PROPERTY, "true")));
        } catch (IOException e) {
            log.error("写入二次验证密钥失败", e);
            authService.succeedSensitiveTotp(request.getRemoteAddr());
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return result;
        }

        authService.activateTotp(secret);
        authService.succeedSensitiveTotp(request.getRemoteAddr());
        authService.logoutOthers(sessionId(request));
        result.put("success", true);
        result.put("message", "已绑定，下次登录需要输入动态验证码");

        return result;
    }

    /**
     * 关掉二次验证
     * <p>
     * <b>必须先输一次现在的验证码。</b>关掉的是一整道防线，而这个动作只需要一次点击——
     * 一枚被偷走的会话 Cookie 若能直接把它卸掉，那道防线保护的其实只是「口令没泄漏」这一种情形。
     * 要求现码等于要求「此刻验证器就在你手上」。
     * <p>
     * 密钥一并清掉，见 {@code ConfigUiAuthService#disableTotp}。
     * @param body 请求体，code 字段为验证器给出的六位数字
     * @return 关闭结果
     */
    @PostMapping("/totp/disable")
    public ResponseEntity<JSONObject> totpDisable(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (!authService.totpRequired()) {
            result.put("success", false);
            result.put("message", "二次验证本来就没开着");
            return ResponseEntity.badRequest().body(result);
        }

        ConfigUiAuthService.CredentialCheck gate = authService.beginSensitiveTotp(request.getRemoteAddr());
        if (!gate.ok()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(refuseSensitiveTotp(gate, request));
        }

        if (!authService.verifyCurrentCode(body.getString("code"))) {
            authService.failSensitiveTotp(request.getRemoteAddr());
            result.put("success", false);
            result.put("message", "验证码不正确，请确认手机时间是否准确后重试");
            result.put("lockedSeconds", remainingLockSeconds(request));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }

        // 先落盘再关：写文件失败时二次验证维持原样，界面照实说没关成——
        // 反过来（先关再写）会出现「关掉了，重启后又要输码」，而那时验证器里那把密钥已经没了
        try {
            fileService.write(new LinkedHashMap<>(Map.of(
                    ConfigUiAuthService.TOTP_PROPERTY, "false",
                    ConfigUiAuthService.TOTP_SECRET_PROPERTY, "")));
        } catch (IOException e) {
            log.error("关闭二次验证时写入配置失败", e);
            authService.succeedSensitiveTotp(request.getRemoteAddr());
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }

        authService.disableTotp();
        authService.succeedSensitiveTotp(request.getRemoteAddr());
        authService.logoutOthers(sessionId(request));
        result.put("success", true);
        result.put("message", "已关闭。下次登录只要口令，验证器里那一条可以删掉了");

        return ResponseEntity.ok(result);
    }

    /**
     * 上第一把锁
     * <p>
     * 这台机器还没设过口令时唯一的一条路。<b>没有它，「初始设置第一步：上锁」走不通</b>——
     * 改口令那条路要旧口令，重设那条路要启动令牌会话，而刚装好的实例两样都没有。
     * <p>
     * <b>凭什么放这个人进来：</b>此刻这台面板还是令牌形态，来人是拿着启动日志里那个令牌
     * （或它换下的 Cookie）过了安全过滤器才走到这里的，而能读到启动日志的人对这台机器
     * 本来就有完全控制权。已经上过锁之后这条路整个关掉——留着它等于给面板开第二扇门，
     * 而那扇门不要旧口令。
     * @param body 请求体，next 为要设的口令
     * @return 结果
     */
    @PostMapping("/password/set")
    public ResponseEntity<JSONObject> setPassword(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (authService.isEnabled()) {
            result.put("success", false);
            result.put("message", "这台机器已经上过锁了，改口令请填现在的口令");
            return ResponseEntity.badRequest().body(result);
        }

        JSONObject replaced = replacePassword(body.getString("next"), request);
        if (!Boolean.TRUE.equals(replaced.getBoolean("success"))) {
            return ResponseEntity.ok(replaced);
        }

        log.warn("配置界面: 已设下第一把口令, 访问令牌自此不再是凭据, 来源: {}", request.getRemoteAddr());
        replaced.put("message", "已上锁。这台机器从现在起要口令才进得来，地址栏里的令牌不再管用");

        // 令牌形态没有会话 Cookie。上锁之后过滤器切到口令形态，不在这一趟下发会话，
        // 下一步接口一律 401，整页刷新落到登录页，初始设置就断在第一步。
        ConfigUiSession session = authService.issueForPassword(request.getRemoteAddr());
        authService.logoutOthers(session.getId());
        replaced.put("csrfToken", session.getCsrfToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(session.getId(), request).toString())
                .body(replaced);
    }

    /**
     * 改口令
     * <p>
     * 要旧口令：一枚被偷走的会话 Cookie 若能直接换掉口令，真正的主人就被锁在了门外，
     * 而他手上那把口令看起来只是「突然不对了」。
     * @param body 请求体，current 为现在的口令，next 为新口令
     * @return 结果
     */
    @PostMapping("/password/change")
    public ResponseEntity<JSONObject> changePassword(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (!authService.isEnabled()) {
            result.put("success", false);
            result.put("message", "这台机器还没设过密码，请到初始设置里上锁");
            return ResponseEntity.badRequest().body(result);
        }

        char[] current = Optional.ofNullable(body.getString("current")).orElse("").toCharArray();
        try {
            if (!authService.matchesPassword(current)) {
                log.warn("配置界面改口令时旧口令不符, 来源: {}", request.getRemoteAddr());
                result.put("success", false);
                result.put("message", "现在的口令不对");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
            }
        } finally {
            Arrays.fill(current, '\0');
        }

        return ResponseEntity.ok(replacePassword(body.getString("next"), request));
    }

    /**
     * 用启动令牌进来之后重设口令
     * <p>
     * <b>只有令牌会话用得了。</b>忘记口令的人拿不出旧口令，而他能读到启动日志——
     * 那本身就证明他对这台机器有完全控制权。反过来，让任何一把会话都能免旧口令重设，
     * 等于把「偷一枚 Cookie」升级成「拿走这台面板」。
     * @param body 请求体，next 为新口令
     * @return 结果
     */
    @PostMapping("/password/reset")
    public ResponseEntity<JSONObject> resetPassword(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (!authService.isEnabled()) {
            result.put("success", false);
            result.put("message", "这台机器还没设过密码，请到初始设置里上锁");
            return ResponseEntity.badRequest().body(result);
        }

        boolean operator = authService.validate(sessionId(request))
                .map(session -> session.getChannel() == ConfigUiSession.Channel.OPERATOR_TOKEN)
                .orElse(false);
        if (!operator) {
            result.put("success", false);
            result.put("message", "这条路只给用启动令牌进来的那一次用，改口令请填现在的口令");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }

        return ResponseEntity.ok(replacePassword(body.getString("next"), request));
    }

    /**
     * 换上新口令：校验、落盘、当场生效、收回别处的会话
     * <p>
     * 三条路（上第一把锁、改口令、令牌重设）走到这里就没有区别了，因此只此一份——
     * 各写一份的话，「改口令要不要注销别处的会话」这件事迟早会有两个答案。
     * @param next 新口令明文
     * @param request 请求，用于留下当前这一把会话
     * @return 结果
     */
    private JSONObject replacePassword(String next, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        String plain = next == null ? "" : next.strip();
        if (plain.length() < MIN_PASSWORD_LENGTH) {
            result.put("success", false);
            result.put("message", "新口令至少 " + MIN_PASSWORD_LENGTH + " 个字符");
            return result;
        }

        char[] chars = plain.toCharArray();
        String hashed;
        try {
            hashed = PasswordHash.hash(chars);
        } finally {
            // 明文用完即抹，不留在堆里等垃圾回收
            Arrays.fill(chars, '\0');
        }

        // 🔴 先落盘再认：反过来的话，写文件失败会让这台机器认一个<b>只存在于内存里</b>的口令，
        // 而使用者记下的正是它——下次重启后他会发现自己进不来，且没有任何提示解释为什么
        try {
            fileService.write(Map.of(PASSWORD_PROPERTY, hashed));
        } catch (Exception e) {
            log.error("写入新的登录口令失败", e);
            result.put("success", false);
            result.put("message", "保存失败，口令没有改动: " + e.getMessage());
            return result;
        }

        authService.applyPasswordHash(hashed);
        // 内存里那一份跟着走：下次启动打不打印令牌地址读的是它，
        // 两处对不上的表现是「上了锁的实例照旧在启动日志里印一个等同于口令的地址」
        properties.setPassword(hashed);
        closeOperatorTokenChannel();
        int revoked = authService.logoutOthers(sessionId(request));

        result.put("success", true);
        result.put("revoked", revoked);
        result.put("message", revoked > 0
                ? "口令已改。别处那 " + revoked + " 个登录已经一并注销"
                : "口令已改。下次登录用新口令");

        return result;
    }

    /**
     * 上锁之后关掉「忘记口令」的启动令牌通道
     * <p>
     * 那条通道<b>绕过口令与二次验证</b>，且令牌走地址栏、会进反向代理的访问日志。它存在的理由
     * 只有一个——忘记口令时还进得来；而人刚刚才设下一把口令，这个理由此刻正好不成立。
     * <p>
     * 不是「设了口令就永远不许开」：使用者随时可以把这一项改回 true 重启。关掉的是
     * <b>「上了锁却还留着一扇不问口令的门，而这件事没有任何现象」</b>那一形。
     * <p>
     * 写不进文件也照样关掉内存里那一位并写日志：门此刻确实关上了，只是重启后会回来——
     * 反过来（写成功了内存没关）才是真正说不清的那一种。
     */
    private void closeOperatorTokenChannel() {
        if (!properties.isOperatorToken()) {
            return;
        }

        properties.setOperatorToken(false);
        try {
            fileService.write(Map.of(ConfigUiAuthService.OPERATOR_TOKEN_PROPERTY, "false"));
            log.warn("配置界面: 已上锁, 「忘记口令」的启动令牌通道随之关闭");
        } catch (Exception e) {
            log.warn("配置界面: 「忘记口令」的启动令牌通道已在本次运行中关闭, 但没能写回配置文件, 重启后会重新打开: {}",
                    e.getMessage());
        }
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
                // 这一次是不是把自己锁进去了，由服务端说了算：界面自己数错误次数是数不准的，
                // 锁定按来源计，同一个来源上别的浏览器试错的那几次，这一边根本看不见。
                // 现算而不是拿 outcome.retryAfter()——刚好第 N 次失败时那一位还是零，
                // 而此刻锁定已经生效了
                result.put("lockedSeconds", remainingLockSeconds(request));
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
