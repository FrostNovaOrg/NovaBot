package com.starlwr.bot.core.config.ui.napcat;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigurationFileService;
import com.starlwr.bot.core.config.ui.auth.TotpGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 替使用者换取 NapCat WebUI 的凭据
 * <p>
 * NapCat 的 WebUI 反代到本控制台路径下之后，外层已经有一道门（反代的 {@code auth_request}
 * → 本控制台会话，要求完整的口令 + 二次验证）。但 NapCat 自己还有一道，
 * 于是使用者要在同一个域名下登录两次、维护两套口令与两个验证器条目。
 * <b>第二道门挡不住已经过了第一道门的人</b>——6099 只听回环，能直连的人早就有 shell，
 * 也就读得到 journal 里的 token 与配置里的密钥；同一台机器上的两个因子，
 * 对已进门者本就不是两个因子。
 * <p>
 * 所以这里把 NapCat 的那道门从「人的第二因子」改成「NovaBot 持有的机器凭据」，
 * 使用者只在 NovaBot 这一侧配置与操作。
 *
 * <h2>为什么不能存一份凭据了事</h2>
 * 🔴 <b>NapCat 的 Credential 只活 3600 秒</b>（读它的实现得到的，不是文档）。
 * 存下来长期用，过期时不会有任何征兆——症状是 WebUI 某一刻突然要求登录，
 * 而那看起来像是「配置错了」。所以这里只缓存到临过期前，需要时现换。
 *
 * <h2>持有的是哈希不是 token</h2>
 * NapCat 的登录接口收的是 {@code SHA-256(token + ".napcat")}，且它是个静态值。
 * <b>它与 token 在权限上完全等价</b>，存哈希不构成任何加密意义上的保护；
 * 这么做的理由只有一个——原文在 NapCat 自己的配置里本来就有，
 * NovaBot 不必再造第二份副本。<b>一个从未持有过的值是最干净的状态。</b>
 */
@Slf4j
public class NapCatCredentialService {
    /**
     * 换算登录哈希时拼在 token 后面的那一段
     * <p>
     * 不是我们选的，是 NapCat 的实现就这么算：{@code sha256(token + ".napcat")}。
     * 改动它等于换一个对不上的哈希，而失败长得和「token 填错了」一模一样。
     */
    private static final String HASH_SUFFIX = ".napcat";

    public static final String TOKEN_PROPERTY = "starbot.core.config-ui.napcat.token";

    public static final String TOKEN_HASH_PROPERTY = "starbot.core.config-ui.napcat.token-hash";

    /**
     * 凭据的实际寿命（秒），取自 NapCat 的 {@code MAX_CREDENTIAL_VALID_SECONDS}
     */
    private static final Duration CREDENTIAL_LIFETIME = Duration.ofSeconds(3600);

    /**
     * 提前多久就当它过期
     * <p>
     * 卡着 3600 秒用，等于把「刚好在这一秒过期」留给使用者去撞。留五分钟余量，
     * 代价是每小时多换一次——那是一个回环 POST。
     */
    private static final Duration RENEW_MARGIN = Duration.ofMinutes(5);

    /**
     * 一个窗口内最多向 NapCat 发起几次登录
     * <p>
     * 🔴 <b>这道闸必须在服务端，页面里的那道挡不住它要挡的东西。</b>
     * 边界⑤ 说的害处是「把 NapCat 的登录限流打满」——那是一个<b>全局</b>资源，
     * 而页面里的计数器<b>一刷新就清零、多开一个标签页就各算各的</b>。
     * 一个刷新就能重置的上限，对它要保护的东西不构成上限。
     * <p>
     * 取 3 看的是正常用法的上界：引导页最多用掉两次（取一把 + 那把不管用时重换一把），
     * 续登一次用一把。<b>正常使用碰不到 3，而失控的循环第 4 次就会被拦下。</b>
     * <p>
     * 与 {@code LoginThrottle} 同理，这里做的是<b>速率限制不是锁定</b>：
     * 桶随时间自己回满，攻击或 bug 一停，等一会儿就能正常用，<b>不留惩罚</b>。
     */
    private static final int MINT_BURST = 3;

    /**
     * 上面那 {@value #MINT_BURST} 次额度回满所需的时间
     */
    private static final Duration MINT_WINDOW = Duration.ofMinutes(5);

    private final RestTemplate restTemplate;

    private final String baseUrl;

    /**
     * 登录哈希，未配置时为 null
     */
    private final String tokenHash;

    /**
     * NapCat 侧的 2FA 密钥，它没开 2FA 时为 null
     */
    private final String totpSecret;

    private volatile String cachedCredential;

    private volatile Instant mintedAt;

    /**
     * 取当前时刻。构造时注入是为了判据能把时间往前拨，
     * <b>而不是靠 sleep 去等一个五分钟的窗口</b>——那种判据慢到最后一定会被人关掉。
     */
    private final Supplier<Instant> clock;

    /**
     * 剩余额度，见 {@link #MINT_BURST}
     */
    private double mintTokens = MINT_BURST;

    private Instant mintRefilledAt;

    /**
     * 反向见证，见 {@link NapCatRouteWitness}
     */
    private final NapCatRouteWitness routeWitness;

    public NapCatCredentialService(StarBotCoreProperties.ConfigUi.NapCat properties,
                                   ConfigurationFileService fileService, RestTemplate restTemplate) {
        this(properties, fileService, restTemplate, Instant::now);
    }

    NapCatCredentialService(StarBotCoreProperties.ConfigUi.NapCat properties,
                            ConfigurationFileService fileService, RestTemplate restTemplate,
                            Supplier<Instant> clock) {
        this(properties, fileService, restTemplate, clock, null);
    }

    /**
     * @param witness 传 null 时按配置自己造一个。留这个口子只为一件事：
     *                让判据能塞进一个<b>必然出岔子</b>的见证，验「见证垮了签发也不垮」——
     *                够不着的守卫等于没有守卫
     */
    NapCatCredentialService(StarBotCoreProperties.ConfigUi.NapCat properties,
                            ConfigurationFileService fileService, RestTemplate restTemplate,
                            Supplier<Instant> clock, NapCatRouteWitness witness) {
        this.restTemplate = restTemplate;
        this.baseUrl = trimTrailingSlash(properties.getAddress());
        this.tokenHash = resolveHash(properties, fileService);
        this.totpSecret = blankToNull(properties.getTotpSecret());
        this.clock = clock;
        this.routeWitness = witness != null ? witness : new NapCatRouteWitness(this.baseUrl, restTemplate);
    }

    /**
     * 反向见证，供判据证明它确实被调用过
     */
    public NapCatRouteWitness routeWitness() {
        return routeWitness;
    }

    /**
     * 一次签发的结局
     * <p>
     * 分型是为了让界面能说对话：<b>「太频繁，等一会儿」与「配置不对，去改配置」
     * 要引向完全不同的动作</b>，混成一句「换不出来」等于把使用者支去查一个没毛病的地方。
     */
    public enum Outcome {
        /** 拿到了 */
        OK,
        /** 没配 token */
        NOT_CONFIGURED,
        /** 短时间内换得太多次，被本机的速率闸拦下 */
        THROTTLED,
        /** 向 NapCat 换取失败，原因在服务端日志里 */
        FAILED
    }

    /**
     * @param outcome 结局
     * @param credential 凭据，仅 {@link Outcome#OK} 时非空
     */
    public record Issued(Outcome outcome, String credential) {
        static Issued of(String credential) {
            return new Issued(Outcome.OK, credential);
        }

        static Issued failed(Outcome outcome) {
            return new Issued(outcome, null);
        }

        /**
         * 记录组件的读取器不许换返回类型，所以取值另起一个名字
         */
        public Optional<String> asOptional() {
            return Optional.ofNullable(credential);
        }
    }

    /**
     * 签发一把凭据
     * @param forceRenew 手上那把不管用了，强制换新的
     */
    public synchronized Issued issue(boolean forceRenew) {
        if (!isConfigured()) {
            return Issued.failed(Outcome.NOT_CONFIGURED);
        }
        if (!forceRenew) {
            Optional<String> cached = cached();
            if (cached.isPresent()) {
                return Issued.of(cached.get());
            }
        } else {
            cachedCredential = null;
            mintedAt = null;
        }
        if (!tryAcquireMint(clock.get())) {
            return Issued.failed(Outcome.THROTTLED);
        }
        return mint().map(Issued::of).orElseGet(() -> Issued.failed(Outcome.FAILED));
    }

    /**
     * 速率闸：还有额度吗
     * <p>
     * 与 {@code LoginThrottle} 的全局桶同一个形状：按时间线性回满，取不到就直接拒绝，<b>不排队</b>。
     * 排队会把「太频繁」变成「很慢」，而后者查起来要难得多。
     */
    synchronized boolean tryAcquireMint(Instant now) {
        if (mintRefilledAt == null) {
            mintRefilledAt = now;
        }
        double elapsedSeconds = Duration.between(mintRefilledAt, now).toMillis() / 1000.0;
        if (elapsedSeconds > 0) {
            mintTokens = Math.min(MINT_BURST,
                    mintTokens + elapsedSeconds * MINT_BURST / MINT_WINDOW.toSeconds());
            mintRefilledAt = now;
        }
        if (mintTokens < 1.0) {
            log.warn("向 NapCat 换取凭据过于频繁, 已拦下本次（上限 {} 次 / {} 分钟）。"
                    + "若反复出现, 检查是否有页面在循环重试", MINT_BURST, MINT_WINDOW.toMinutes());
            return false;
        }
        mintTokens -= 1.0;
        return true;
    }

    private Optional<String> cached() {
        if (cachedCredential != null && mintedAt != null
                && Duration.between(mintedAt, clock.get()).compareTo(CREDENTIAL_LIFETIME.minus(RENEW_MARGIN)) < 0) {
            return Optional.of(cachedCredential);
        }
        return Optional.empty();
    }

    /**
     * 有没有配好，可以代登录
     */
    public boolean isConfigured() {
        return tokenHash != null;
    }

    /**
     * 取一把可用的凭据，必要时现换
     * @return 凭据；未配置时为空
     */
    public synchronized Optional<String> credential() {
        return issue(false).asOptional();
    }

    /**
     * 手上那把不管用了，换一把
     * <p>
     * 🔴 <b>调用方只许调一次，不许循环。</b>凭据换不出来时，原因几乎总是配置不对
     * （token 改过、2FA 开了却没填密钥），而那类原因重试一百次也是同一个结果——
     * 循环换来的不是成功，是把 NapCat 的登录限流打满，然后连人工登录都进不去。
     * @return 新凭据；换不出来时为空
     */
    public synchronized Optional<String> renew() {
        return issue(true).asOptional();
    }

    private Optional<String> mint() {
        JSONObject body = new JSONObject();
        body.put("hash", tokenHash);
        if (totpSecret != null) {
            // 字段名是 totpCode。NapCat 那边缺 hash 时报的是「token is empty」——
            // 字段叫 hash、报错说 token，按报错去猜字段名会永远猜错
            body.put("totpCode", TotpGenerator.currentCode(totpSecret, Instant.now()));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(baseUrl + "/api/auth/login",
                    new HttpEntity<>(body.toString(), headers), String.class);
        } catch (Exception e) {
            // 不打请求体：里面有哈希与验证码，两者都是凭据
            log.warn("向 NapCat 换取凭据失败, 地址: {}: {}", baseUrl, e.getMessage());
            return Optional.empty();
        }

        JSONObject payload = parse(response.getBody());
        if (payload == null) {
            log.warn("NapCat 的登录响应不是合法 JSON, 状态码: {}", response.getStatusCode());
            return Optional.empty();
        }

        JSONObject data = payload.getJSONObject("data");
        // 🔴 NapCat 鉴权失败也回 HTTP 200，错误只在响应体里。只看状态码会一律读成成功
        if (data == null) {
            log.warn("NapCat 拒绝了代登录: {}", payload.getString("message"));
            return Optional.empty();
        }

        // require2FA 是成功形不是错误形：它在说「密码对了，还差验证码」。
        // 走到这里意味着 NapCat 开了 2FA 而我们这边没配密钥——要说清楚该去填哪
        if (Boolean.TRUE.equals(data.getBoolean("require2FA"))) {
            log.error("NapCat 开启了二次验证, 但 {} 未配置其密钥, 无法代登录",
                    "starbot.core.config-ui.napcat.totp-secret");
            return Optional.empty();
        }

        String credential = data.getString("Credential");
        if (credential == null || credential.isBlank()) {
            log.warn("NapCat 的登录响应里没有 Credential 字段");
            return Optional.empty();
        }

        cachedCredential = credential;
        mintedAt = clock.get();
        // 只记「换到了」，不记凭据本身
        log.info("已替使用者换取 NapCat WebUI 凭据, 有效期 {} 分钟", CREDENTIAL_LIFETIME.toMinutes());

        // 见证挂在这里而不是另起一个调度器：这一刻本来就要与 WebUI 通信，
        // 多两跳是本机回环；凭据每小时最多换一次，频率天然合适。
        // 代价（长期没人开面板时见证不跑）是明知的：那时也没有会话需要救
        try {
            routeWitness.witness();
        } catch (RuntimeException e) {
            // 🔴 见证是来报信的，不是来把门关上的（边界②：失败回落到现状）。
            // 它自己出了岔子，最坏的结果应当是「这次没见证成」，
            // 而不是「凭据签不出来了」——后者会让使用者被关在 NapCat 门外
            log.warn("NapCat 路由见证过程中出错, 不影响本次凭据签发: {}", e.getMessage());
        }

        return Optional.of(credential);
    }

    private static JSONObject parse(String body) {
        try {
            return body == null ? null : JSONObject.parseObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拿到登录哈希：配置里填了明文 token 就当场换算并写回，明文不留在盘上
     */
    private static String resolveHash(StarBotCoreProperties.ConfigUi.NapCat properties,
                                      ConfigurationFileService fileService) {
        String plain = blankToNull(properties.getToken());
        String existing = blankToNull(properties.getTokenHash());

        if (plain == null) {
            return existing;
        }

        String hashed = hash(plain);
        if (fileService == null) {
            log.warn("NapCat 的 token 仍以明文保存在配置文件中");
            return hashed;
        }

        try {
            // 两项一起写：只写哈希不清明文，等于配置里同时躺着两份等价凭据
            fileService.write(Map.of(TOKEN_HASH_PROPERTY, hashed, TOKEN_PROPERTY, ""));
            log.info("NapCat 的 token 已换算为登录哈希保存, 配置文件中不再有明文");
        } catch (Exception e) {
            log.warn("NapCat 的 token 未能换算保存, 文件中仍是明文: {}", e.getMessage());
        }
        return hashed;
    }

    /**
     * NapCat 的登录哈希：{@code sha256(token + ".napcat")} 的十六进制
     */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((token + HASH_SUFFIX).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
