package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 通行密钥的登记与校验
 * <p>
 * 整条路零依赖：CBOR 解码、COSE 公钥还原与验签都用 JDK 自带的东西，
 * 见 {@link CborReader}、{@link CoseKey}。
 *
 * <h2>登记（要先进得来）</h2>
 * 先发一个一次性挑战，浏览器拿去让认证器建一对钥匙，回来时带着 {@code attestationObject}。
 * 只收 {@code none} 形式的证明——收别的形式意味着要去验一条证书链，而那条链回答的是
 * 「这把钥匙出自哪家厂商的哪一批硬件」。<b>单用户面板不需要这个答案</b>：
 * 使用者自己刚刚在自己的设备上按了一次指纹，这件事本身就是他要的全部保证。
 *
 * <h2>登录（不必先进得来）</h2>
 * 同样先发挑战，认证器用私钥签「认证器数据 ‖ 客户端数据的哈希」，这一侧用存着的公钥验。
 * 验过就签发会话，<b>不再另要动态码</b>，理由见 {@link ConfigUiAuthService#issueForPasskey}。
 * 二次验证开着时，认证器必须已经确认使用者身份（指纹、面容或 PIN），只按一下不够。
 *
 * <h2>每一趟都要判的四件事</h2>
 * <ol>
 *   <li><b>挑战</b>——是不是这一侧刚发出去的那一个，且没被用过</li>
 *   <li><b>origin</b>——浏览器写进客户端数据的地址是不是本站。少这一条，
 *       攻击者站点上骗来的一次签名可以直接拿来登本站</li>
 *   <li><b>rpId</b>——认证器认为自己在为哪个域服务。它写在签名<b>覆盖的字节里</b>，
 *       与上一条不是同一件事：上一条信的是浏览器，这一条信的是认证器</li>
 *   <li><b>人在场</b>——认证器确实让人按了一下，而不是被脚本静默调用</li>
 * </ol>
 * 登录时还多一条：<b>签名计数器不得倒退</b>，见 {@link #signCountAccepted}。
 * 二次验证开着时再多一条：认证器必须已经确认使用者身份，见 {@link AuthenticatorData#userVerified()}。
 */
@Slf4j
public class PasskeyService {
    /**
     * 名字最长多少个字
     * <p>
     * 按<b>码点</b>数而不是 {@code String.length()}：后者把一个表情算作两个，
     * 于是使用者数着屏幕上的 21 个字却被告知超了 40。
     */
    public static final int MAX_NAME_LENGTH = 40;

    /**
     * 最多能登记几把
     * <p>
     * 一个人手上的设备是有数的，取 16 已经很宽。设上限是因为这张表落在运行状态文件里，
     * 而登记这条路虽然要先进得来，却没有别的东西拦着反复调用。
     */
    private static final int MAX_CREDENTIALS = 16;

    /**
     * 没起名字时用它
     */
    private static final String DEFAULT_NAME = "通行密钥";

    /**
     * 客户端数据里那两个仪式名，写死在规范里
     */
    private static final String TYPE_REGISTER = "webauthn.create";

    private static final String TYPE_LOGIN = "webauthn.get";

    /**
     * 只收这一种证明形式，理由见类注释
     */
    private static final String ATTESTATION_NONE = "none";

    /**
     * 给浏览器的超时，单位：毫秒。它管的是「认证器界面弹多久」，与挑战自己的有效期是两件事
     */
    private static final int CLIENT_TIMEOUT_MILLIS = 60_000;

    /**
     * 验证器应用与浏览器里显示的名字
     * <p>
     * 单用户面板没有账号可言，用户标识固定即可——它只是让浏览器把这几把钥匙归到一条记录下。
     */
    private static final String DISPLAY_NAME = "NovaBot 控制台";

    private static final String USER_NAME = "novabot";

    /**
     * 登录失败一律回这一句
     * <p>
     * 不分「没有这把钥匙」「签名不对」「计数器倒退」：分开说等于告诉对方哪一步已经过了。
     * 真正的原因写进日志——排查的人看得到，试探的人看不到。
     */
    private static final String LOGIN_FAILED = "这把通行密钥没能通过验证，请换一把或改用口令登录";

    /**
     * 二次验证开着、认证器却只确认了「人在场」时回这一句
     * <p>
     * 和 {@link #LOGIN_FAILED} 分开：那一句故意不说是哪一步不对；
     * 这一句要让人知道该换一把会认指纹／面容／PIN 的设备，或改用口令加动态码。
     */
    private static final String LOGIN_NEEDS_USER_VERIFICATION =
            "这把通行密钥没有确认使用者身份。请用需要指纹、面容或 PIN 的设备，或改用口令加动态码登录";

    private final PasskeyStore store;

    private final ConfigUiAuthService authService;

    private final PasskeyChallenges challenges = new PasskeyChallenges();

    private final Supplier<Instant> clock;

    public PasskeyService(PasskeyStore store, ConfigUiAuthService authService) {
        this(store, authService, Instant::now);
    }

    PasskeyService(PasskeyStore store, ConfigUiAuthService authService, Supplier<Instant> clock) {
        this.store = store;
        this.authService = authService;
        this.clock = clock;
    }

    /**
     * 登记一把新钥匙前，先要一份参数
     * @param relyingParty 本站此刻的 rpId 与 origin
     * @return 交给 {@code navigator.credentials.create} 的那份参数
     */
    public JSONObject registerOptions(PasskeyRelyingParty relyingParty) {
        JSONObject result = new JSONObject();

        if (!relyingParty.usable()) {
            result.put("success", false);
            result.put("message", relyingParty.unusableReason());
            return result;
        }

        List<PasskeyCredential> existing = store.list();
        if (existing.size() >= MAX_CREDENTIALS) {
            result.put("success", false);
            result.put("message", "最多只能登记 " + MAX_CREDENTIALS + " 把通行密钥，请先删掉不用的那些");
            return result;
        }

        JSONObject rp = new JSONObject();
        rp.put("id", relyingParty.rpId());
        rp.put("name", DISPLAY_NAME);

        JSONObject user = new JSONObject();
        user.put("id", PasskeyBytes.encode(USER_NAME.getBytes(StandardCharsets.UTF_8)));
        user.put("name", USER_NAME);
        user.put("displayName", DISPLAY_NAME);

        JSONArray params = new JSONArray();
        for (int algorithm : new int[]{-7, -257, -8}) {
            JSONObject entry = new JSONObject();
            entry.put("type", "public-key");
            entry.put("alg", algorithm);
            params.add(entry);
        }

        JSONObject selection = new JSONObject();
        // 尽量建成设备上认得出使用者的那种钥匙，但不强求：强求的话，一部分安全密钥会直接建不出来，
        // 而使用者只会看到浏览器弹一句他看不懂的错误
        selection.put("residentKey", "preferred");
        selection.put("userVerification", "preferred");

        result.put("success", true);
        result.put("challenge", challenges.issue(PasskeyChallenges.Purpose.REGISTER, clock.get()));
        result.put("rp", rp);
        result.put("user", user);
        result.put("pubKeyCredParams", params);
        result.put("timeout", CLIENT_TIMEOUT_MILLIS);
        result.put("attestation", ATTESTATION_NONE);
        result.put("authenticatorSelection", selection);
        // 已经登记过的排除掉，否则同一台设备会建出第二把，而使用者以为自己换了一把新的
        result.put("excludeCredentials", descriptors(existing));
        result.put("nameLimit", MAX_NAME_LENGTH);

        return result;
    }

    /**
     * 收下认证器建好的那把钥匙
     * @param body 客户端回传的响应
     * @param relyingParty 本站此刻的 rpId 与 origin
     * @return 登记结果
     */
    public JSONObject registerVerify(JSONObject body, PasskeyRelyingParty relyingParty) {
        JSONObject result = new JSONObject();

        try {
            String name = normalizeName(body.getString("name"));

            JSONObject response = body.getJSONObject("response");
            if (response == null) {
                throw new IllegalArgumentException("响应里没有 response");
            }

            byte[] clientDataBytes = PasskeyBytes.decode(response.getString("clientDataJSON"));
            checkClientData(clientDataBytes, TYPE_REGISTER, PasskeyChallenges.Purpose.REGISTER, relyingParty);

            Map<Object, Object> attestation = CborReader.readMap(PasskeyBytes.decode(response.getString("attestationObject")));
            Object format = attestation.get("fmt");
            if (!ATTESTATION_NONE.equals(format)) {
                throw new IllegalArgumentException("只接受 none 形式的证明，收到的是 " + format);
            }

            Object authDataValue = attestation.get("authData");
            if (!(authDataValue instanceof byte[] authDataBytes)) {
                throw new IllegalArgumentException("证明里没有认证器数据");
            }

            AuthenticatorData authData = AuthenticatorData.parse(authDataBytes, true);
            checkAuthenticator(authData, relyingParty);

            String id = PasskeyBytes.encode(authData.getCredentialId());
            if (store.find(id).isPresent()) {
                result.put("success", false);
                result.put("message", "这把通行密钥已经登记过了");
                return result;
            }

            // 上限在发参数时判过一次，这里再判一次：两次请求之间可以插进任意多次登记
            if (store.list().size() >= MAX_CREDENTIALS) {
                result.put("success", false);
                result.put("message", "最多只能登记 " + MAX_CREDENTIALS + " 把通行密钥，请先删掉不用的那些");
                return result;
            }

            Instant now = clock.get();
            PasskeyCredential credential = new PasskeyCredential(
                    id, name,
                    PasskeyBytes.encode(authData.getCredentialPublicKey().getPublicKey().getEncoded()),
                    authData.getCredentialPublicKey().getAlgorithm(),
                    authData.getSignCount(), now, null);
            store.save(credential);

            log.info("配置界面已登记一把通行密钥: {}", name);
            result.put("success", true);
            result.put("message", "已登记「" + name + "」，下次登录可以直接用它");
            result.put("passkey", describe(credential));
        } catch (IllegalArgumentException e) {
            log.warn("配置界面登记通行密钥失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("配置界面登记通行密钥异常", e);
            result.put("success", false);
            result.put("message", "登记失败，请重试");
        }

        return result;
    }

    /**
     * 用通行密钥登录前，先要一份参数
     * <p>
     * 这条路<b>不要求已登录</b>，因此它回的东西必须经得起「谁都能问一句」：
     * 里面只有一个刚发的随机挑战，以及已登记的凭据 ID。凭据 ID 不是秘密——
     * 它本来就存在使用者的设备上，浏览器每次登录都会把它发出来。
     * @param relyingParty 本站此刻的 rpId 与 origin
     * @return 交给 {@code navigator.credentials.get} 的那份参数
     */
    public JSONObject loginOptions(PasskeyRelyingParty relyingParty) {
        JSONObject result = new JSONObject();

        if (!relyingParty.usable()) {
            result.put("success", false);
            result.put("message", relyingParty.unusableReason());
            return result;
        }

        List<PasskeyCredential> credentials = store.list();
        if (!available() || credentials.isEmpty()) {
            // 一把都没登记时如实说「用不了」，前端据此不显示那个按钮——
            // 摆一个点了必然失败的按钮，比没有这个按钮更让人不知所措
            result.put("success", false);
            result.put("message", "这台机器上还没有登记过通行密钥");
            return result;
        }

        result.put("success", true);
        result.put("challenge", challenges.issue(PasskeyChallenges.Purpose.LOGIN, clock.get()));
        result.put("rpId", relyingParty.rpId());
        result.put("timeout", CLIENT_TIMEOUT_MILLIS);
        // 二次验证开着时浏览器也得去要 UV；只改这一项不够，真正拒在 loginVerify
        result.put("userVerification", authService.totpRequired() ? "required" : "preferred");
        result.put("allowCredentials", descriptors(credentials));

        return result;
    }

    /**
     * 验一次通行密钥登录
     * @param body 客户端回传的响应
     * @param relyingParty 本站此刻的 rpId 与 origin
     * @param clientIp 来源 IP
     * @return 登录结果
     */
    public PasskeyLogin loginVerify(JSONObject body, PasskeyRelyingParty relyingParty, String clientIp) {
        if (!available()) {
            return PasskeyLogin.failure("这台机器没有启用口令登录，通行密钥暂时用不上");
        }

        // 锁定是按来源计的，与口令那条路共用一份：不问这一句的话，
        // 被锁住的地址转头就能在这里继续试
        Duration lockout = authService.remainingLockout(clientIp);
        if (!lockout.isZero()) {
            return PasskeyLogin.failure("登录失败次数过多，请在 " + Math.max(1, lockout.toMinutes()) + " 分钟后重试");
        }

        try {
            JSONObject response = body.getJSONObject("response");
            if (response == null) {
                throw new IllegalArgumentException("响应里没有 response");
            }

            PasskeyCredential credential = store.find(body.getString("id"))
                    .orElseThrow(() -> new IllegalArgumentException("这把通行密钥没有登记过"));

            byte[] clientDataBytes = PasskeyBytes.decode(response.getString("clientDataJSON"));
            checkClientData(clientDataBytes, TYPE_LOGIN, PasskeyChallenges.Purpose.LOGIN, relyingParty);

            byte[] authDataBytes = PasskeyBytes.decode(response.getString("authenticatorData"));
            AuthenticatorData authData = AuthenticatorData.parse(authDataBytes, false);
            checkAuthenticator(authData, relyingParty);

            byte[] signed = PasskeyBytes.concat(authDataBytes, PasskeyBytes.sha256(clientDataBytes));
            if (!verifySignature(credential, signed, PasskeyBytes.decode(response.getString("signature")))) {
                throw new IllegalArgumentException("签名不正确");
            }

            if (!signCountAccepted(credential.signCount(), authData.getSignCount())) {
                throw new IllegalArgumentException("签名计数器倒退了（存 " + credential.signCount()
                        + "，来 " + authData.getSignCount() + "），这一次可能是重放");
            }

            if (authService.totpRequired() && !authData.userVerified()) {
                authService.recordFailedAttempt(clientIp);
                log.warn("配置界面通行密钥登录失败, 来源: {}, 原因: 二次验证开着但认证器未确认使用者身份", clientIp);
                return PasskeyLogin.failure(LOGIN_NEEDS_USER_VERIFICATION);
            }

            Instant now = clock.get();
            store.save(credential.used(authData.getSignCount(), now));

            log.info("配置界面: 已用通行密钥「{}」登录, 来源: {}", credential.name(), clientIp);
            return PasskeyLogin.success(authService.issueForPasskey(clientIp));
        } catch (Exception e) {
            authService.recordFailedAttempt(clientIp);
            log.warn("配置界面通行密钥登录失败, 来源: {}, 原因: {}", clientIp, e.getMessage());
            return PasskeyLogin.failure(LOGIN_FAILED);
        }
    }

    /**
     * 已登记的钥匙都有哪些
     * @return 名字、登记时间与上次使用时间
     */
    public JSONArray list() {
        JSONArray result = new JSONArray();
        store.list().forEach(credential -> result.add(describe(credential)));
        return result;
    }

    /**
     * 删掉一把
     * @param id 凭据 ID
     * @return 原本是否存在
     */
    public boolean delete(String id) {
        boolean removed = store.remove(id);
        if (removed) {
            log.info("配置界面已删除一把通行密钥: {}", id);
        }
        return removed;
    }

    /**
     * 通行密钥这条路此刻管不管用
     * <p>
     * 🔴 它跟着<b>口令登录</b>的开关走，而不是自成一档。理由在安全过滤器那一层：
     * 未配口令时面板走的是「令牌即凭据」那一形态，<b>它根本不看会话</b>——
     * 此时就算验过通行密钥并签发了会话，浏览器带着那把会话回来也一样进不去。
     * 让这条路在那种形态下如实说「用不上」，好过发一把打不开任何门的钥匙。
     */
    private boolean available() {
        return authService.isEnabled();
    }

    /**
     * 签名计数器这一次能不能接受
     * <p>
     * 规范给的意思是：计数器用来发现凭据被克隆——克隆出来的那一把不知道原件已经数到哪儿了，
     * 于是会报一个偏小的值。因此<b>只要在用计数器，就必须严格递增</b>。
     * <p>
     * 🔴 但<b>两边都是 0 时不能按这条判</b>。相当一部分认证器（尤其是手机、电脑上的
     * 系统钥匙串）根本不维护计数器，永远报 0。照「小于等于存值就拒」办的话，
     * 这类钥匙<b>第一次登录之后就再也用不了</b>——而那恰好是今天最常见的一类通行密钥。
     * 这不是把规矩放宽：计数器没在用，它就提供不了任何信息，拿一个恒为 0 的值去判单调，
     * 判的是编码习惯不是重放。
     * @param stored 上次记下的值
     * @param presented 这次报上来的值
     * @return 是否接受
     */
    private boolean signCountAccepted(long stored, long presented) {
        if (stored == 0 && presented == 0) {
            return true;
        }
        return presented > stored;
    }

    private boolean verifySignature(PasskeyCredential credential, byte[] signed, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance(CoseKey.signatureAlgorithm(credential.algorithm()));
        verifier.initVerify(CoseKey.restore(credential.algorithm(), PasskeyBytes.decode(credential.publicKey())));
        verifier.update(signed);
        return verifier.verify(signature);
    }

    /**
     * 校验浏览器写下的那份客户端数据
     * <p>
     * 三件事：办的是不是这个仪式、挑战是不是刚发出去的那一个、地址是不是本站。
     */
    private void checkClientData(byte[] clientDataBytes, String expectedType,
                                 PasskeyChallenges.Purpose purpose, PasskeyRelyingParty relyingParty) {
        JSONObject clientData;
        try {
            clientData = JSONObject.parseObject(new String(clientDataBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("客户端数据不是合法的 JSON");
        }

        if (clientData == null || !expectedType.equals(clientData.getString("type"))) {
            throw new IllegalArgumentException("客户端数据里的仪式类型不对，应为 " + expectedType);
        }

        // 跨源的 iframe 里办的仪式一律不认：那种页面的地址栏写着别人的域名，
        // 而使用者以为自己是在给那个站点按指纹
        if (Boolean.TRUE.equals(clientData.getBoolean("crossOrigin"))) {
            throw new IllegalArgumentException("不接受跨站页面里发起的验证");
        }

        if (!challenges.consume(clientData.getString("challenge"), purpose, clock.get())) {
            throw new IllegalArgumentException("挑战串已失效或已经用过一次，请重试");
        }

        if (!relyingParty.origin().equals(clientData.getString("origin"))) {
            throw new IllegalArgumentException("来源地址不是本站，收到的是 " + clientData.getString("origin"));
        }
    }

    /**
     * 校验认证器自己写下的那一段
     */
    private void checkAuthenticator(AuthenticatorData authData, PasskeyRelyingParty relyingParty) {
        if (!authData.matchesRpId(relyingParty.rpId())) {
            throw new IllegalArgumentException("这把通行密钥不是为 " + relyingParty.rpId() + " 建的");
        }

        if (!authData.userPresent()) {
            throw new IllegalArgumentException("认证器没有确认「人在场」，这一次可能是脚本静默发起的");
        }
    }

    /**
     * 名字规整：去空白、空则给默认名、超长则拒
     */
    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return DEFAULT_NAME;
        }

        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名字最长 " + MAX_NAME_LENGTH + " 个字");
        }

        return name;
    }

    private JSONArray descriptors(List<PasskeyCredential> credentials) {
        JSONArray list = new JSONArray();
        for (PasskeyCredential credential : credentials) {
            JSONObject entry = new JSONObject();
            entry.put("type", "public-key");
            entry.put("id", credential.id());
            list.add(entry);
        }
        return list;
    }

    /**
     * 一把钥匙对外长什么样
     * <p>
     * <b>公钥不出现在这里。</b>它虽然不是秘密，但界面上摆一串谁也读不懂的字符只有坏处；
     * 而真正要给使用者看的是「这是哪台设备、什么时候登记的、上次什么时候用过」——
     * 最后一项正是他判断「这一条能不能删」的依据。
     */
    private JSONObject describe(PasskeyCredential credential) {
        JSONObject entry = new JSONObject();
        entry.put("id", credential.id());
        entry.put("name", credential.name());
        entry.put("createdAt", credential.createdAt().toString());
        entry.put("lastUsedAt", Optional.ofNullable(credential.lastUsedAt()).map(Instant::toString).orElse(null));
        return entry;
    }

    /**
     * 一次通行密钥登录的结果
     * @param session 成功时的会话
     * @param message 失败原因，成功时为 null
     */
    public record PasskeyLogin(ConfigUiSession session, String message) {
        public boolean success() {
            return session != null;
        }

        static PasskeyLogin success(ConfigUiSession session) {
            return new PasskeyLogin(session, null);
        }

        static PasskeyLogin failure(String message) {
            return new PasskeyLogin(null, message);
        }
    }
}
