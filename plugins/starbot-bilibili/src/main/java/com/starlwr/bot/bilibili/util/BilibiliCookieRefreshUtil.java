package com.starlwr.bot.bilibili.util;

import com.starlwr.bot.bilibili.model.Cookies;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 哔哩哔哩 Cookie 续期相关的纯函数工具
 * <p>
 * 续期链路本身涉及网络与状态，放在 {@link BilibiliApiUtil} 中；此处只放不依赖任何状态的部分，
 * 以便单独测试——续期是一次性且不可回退的操作，能离线验证的环节要尽量离线验证。
 */
@UtilityClass
public class BilibiliCookieRefreshUtil {
    /**
     * 官方 Web 端用于生成 CorrespondPath 的 RSA 公钥
     * <p>
     * 逆向自官方首页加载的 wasm 模块，公开资料见
     * <a href="https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html">bilibili-API-collect</a>。
     */
    private static final String PUBLIC_KEY_BASE64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg"
            + "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71"
            + "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40"
            + "JNrRuoEUXpabUzGB8QIDAQAB";

    /**
     * 官方页面用 WebCrypto 的 RSA-OAEP 加密，hash 指定为 SHA-256
     * <p>
     * <b>MGF1 的摘要必须显式指定为 SHA-256。</b>WebCrypto 中 RSA-OAEP 的 MGF1 跟随 hash 参数，
     * 而 JDK 的 {@code OAEPWithSHA-256AndMGF1Padding} 在不传参数时，MGF1 用的是 <b>SHA-1</b>。
     * 只写变换名不传 {@link OAEPParameterSpec} 就会算出服务端无法识别的密文，
     * 而现象只是 correspond 页面返回 404，从表象上完全看不出是填充参数的问题。
     */
    private static final String CIPHER_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    /**
     * 从 correspond 页面提取实时刷新口令
     * <p>
     * 页面由服务端渲染，口令放在 id 为 1-name 的 div 内。此处只用正则而不引入 HTML 解析器：
     * 目标片段结构固定，为一个字段引入解析依赖并不划算。
     */
    private static final Pattern REFRESH_CSRF_PATTERN =
            Pattern.compile("id=\"1-name\"[^>]*>\\s*([0-9a-fA-F]{4,64})\\s*<");

    /**
     * 生成 CorrespondPath
     * <p>
     * 将 {@code refresh_<毫秒时间戳>} 用官方公钥做 RSA-OAEP 加密，密文按小写 Base16 编码。
     * OAEP 含随机填充，因此同一时间戳每次生成的结果都不同，这是正常的。
     * @param timestampMillis 毫秒时间戳，须取自服务端返回的 timestamp 字段
     * @return CorrespondPath
     */
    public static String correspondPath(long timestampMillis) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey(), oaepParameterSpec());

            byte[] encrypted = cipher.doFinal(("refresh_" + timestampMillis).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("生成 CorrespondPath 失败", e);
        }
    }

    /**
     * 构造 OAEP 参数
     * <p>
     * 单独抽出来是为了让测试能用同一份参数解密，从而真正验证 MGF1 摘要没有退回 SHA-1。
     * @return OAEP 参数
     */
    public static OAEPParameterSpec oaepParameterSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    /**
     * 解析官方公钥
     * @return 公钥
     * @throws Exception 解析失败时抛出
     */
    private static PublicKey publicKey() throws Exception {
        byte[] der = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * 从 correspond 页面中提取实时刷新口令
     * @param html 页面内容
     * @return 实时刷新口令，未能提取时返回空
     */
    public static Optional<String> parseRefreshCsrf(String html) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = REFRESH_CSRF_PATTERN.matcher(html);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * 按 Set-Cookie 响应头更新凭据
     * <p>
     * 刷新接口只下发 SESSDATA、bili_jct 等少数几项，buvid3 与 refreshToken 不在其中，
     * 因此以原凭据为底，逐项覆盖而非整体替换——整体替换会把设备标识丢掉。
     * @param current 当前凭据
     * @param setCookies Set-Cookie 响应头
     * @return 更新后的凭据
     */
    public static Cookies applySetCookies(@NonNull Cookies current, List<String> setCookies) {
        Cookies updated = new Cookies(current.getSessData(), current.getBiliJct(),
                current.getBuvid3(), current.getRefreshToken());

        if (setCookies == null) {
            return updated;
        }

        for (String header : setCookies) {
            if (header == null || header.isBlank()) {
                continue;
            }

            // Set-Cookie 形如 "SESSDATA=xxx; Path=/; Domain=.bilibili.com; HttpOnly"，只取第一段
            String pair = header.split(";", 2)[0].trim();
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }

            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (value.isEmpty()) {
                continue;
            }

            switch (name) {
                case "SESSDATA" -> updated.setSessData(value);
                case "bili_jct" -> updated.setBiliJct(value);
                case "buvid3" -> updated.setBuvid3(value);
                default -> {
                    // 其余项（DedeUserID、sid 等）本项目不使用，忽略即可
                }
            }
        }

        return updated;
    }
}
