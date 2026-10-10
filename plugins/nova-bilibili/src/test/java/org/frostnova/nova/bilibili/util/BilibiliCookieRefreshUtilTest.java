package org.frostnova.nova.bilibili.util;

import org.frostnova.nova.bilibili.model.Cookies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 哔哩哔哩 Cookie 续期工具测试
 * <p>
 * 续期链路本身需要真实登录态，无法在单元测试中跑通；能离线验证的只有这几个纯函数，
 * 因此这里覆盖得尽量密——尤其是 OAEP 的填充参数，写错了在真实环境中的表现只是
 * correspond 页面返回 404，完全看不出是加密参数的问题。
 */
@DisplayName("Cookie 续期工具")
class BilibiliCookieRefreshUtilTest {
    @Test
    @DisplayName("CorrespondPath 应为小写十六进制且长度与 1024 位密钥相符")
    void shouldGenerateHexCorrespondPath() {
        String path = BilibiliCookieRefreshUtil.correspondPath(1684466082562L);

        // 1024 位 RSA 的密文固定为 128 字节，十六进制编码后 256 个字符
        assertEquals(256, path.length(), "密文长度应与 1024 位密钥一致");
        assertTrue(path.matches("[0-9a-f]+"), "应为小写十六进制: " + path);
    }

    @Test
    @DisplayName("同一时间戳每次生成的 CorrespondPath 都不同")
    void shouldGenerateDifferentPathEachTime() {
        long timestamp = 1684466082562L;

        // OAEP 含随机填充，因此不存在固定的期望值，也无法用固定向量做断言
        assertNotEquals(BilibiliCookieRefreshUtil.correspondPath(timestamp),
                BilibiliCookieRefreshUtil.correspondPath(timestamp),
                "OAEP 的随机填充应使每次输出都不同");
    }

    @Test
    @DisplayName("加密须使用 SHA-256 的 MGF1, 不得退回 JDK 默认的 SHA-1")
    void shouldUseSha256ForMgf1() throws Exception {
        // 官方页面用 WebCrypto 的 RSA-OAEP，其 MGF1 摘要跟随 hash 参数，即 SHA-256；
        // 而 JDK 的 OAEPWithSHA-256AndMGF1Padding 在不传参数时 MGF1 用的是 SHA-1。
        // 用本地密钥对调产品的加密入口，再按官方参数独立解密，即可离线验证产品用的确实是 SHA-256。
        KeyPair keyPair = generateKeyPair();
        long timestamp = 1684466082562L;
        byte[] cipherText = HexFormat.of().parseHex(
                BilibiliCookieRefreshUtil.correspondPath(timestamp, keyPair.getPublic()));

        assertEquals("refresh_" + timestamp,
                new String(decrypt(keyPair, MGF1ParameterSpec.SHA256, cipherText), StandardCharsets.UTF_8));

        // 反过来用 MGF1-SHA1 解密必须失败，否则说明上面那次成功只是「两边都错得一样」
        assertThrows(Exception.class, () -> decrypt(keyPair, MGF1ParameterSpec.SHA1, cipherText),
                "MGF1 摘要不同却能解开, 说明参数未真正生效");
    }

    @Test
    @DisplayName("加密的明文应为 refresh_ 加上传入的时间戳")
    void shouldEncryptRefreshPrefixedTimestamp() throws Exception {
        KeyPair keyPair = generateKeyPair();
        // 取一个远离当前时间的值，产品若改用本机时钟会立刻对不上
        long timestamp = 1700000000000L;

        String path = BilibiliCookieRefreshUtil.correspondPath(timestamp, keyPair.getPublic());

        assertTrue(path.matches("[0-9a-f]+"), "应为小写十六进制: " + path);
        int modulusBytes = (((RSAPublicKey) keyPair.getPublic()).getModulus().bitLength() + 7) / 8;
        assertEquals(modulusBytes * 2, path.length(), "密文长度应与密钥长度一致");

        byte[] plain = decrypt(keyPair, MGF1ParameterSpec.SHA256, HexFormat.of().parseHex(path));
        assertEquals("refresh_" + timestamp, new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("能从 correspond 页面中提取实时刷新口令")
    void shouldParseRefreshCsrf() {
        String html = """
                <!DOCTYPE html>
                <html lang="zh-Hans">
                <body>
                  <div id="1-name">b0cc8411ded2f9db2cff2edb3123acac</div>
                  <div id="token-iframe-app"></div>
                </body>
                </html>""";

        assertEquals(Optional.of("b0cc8411ded2f9db2cff2edb3123acac"),
                BilibiliCookieRefreshUtil.parseRefreshCsrf(html));
    }

    @Test
    @DisplayName("correspondPath 无效时页面不含口令, 应返回空而非抛异常")
    void shouldReturnEmptyForPageWithoutCsrf() {
        assertEquals(Optional.empty(), BilibiliCookieRefreshUtil.parseRefreshCsrf("<html><body>404</body></html>"));
        assertEquals(Optional.empty(), BilibiliCookieRefreshUtil.parseRefreshCsrf(""));
        assertEquals(Optional.empty(), BilibiliCookieRefreshUtil.parseRefreshCsrf(null));
    }

    @Test
    @DisplayName("按 Set-Cookie 更新凭据时应保留未下发的字段")
    void shouldKeepFieldsAbsentFromSetCookie() {
        Cookies current = new Cookies("old-sess", "old-jct", "device-buvid", "old-token");

        Cookies updated = BilibiliCookieRefreshUtil.applySetCookies(current, List.of(
                "SESSDATA=new-sess; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=new-jct; Path=/; Domain=.bilibili.com",
                "DedeUserID=19805387116684; Path=/",
                "sid=abcdefg; Path=/"));

        assertEquals("new-sess", updated.getSessData());
        assertEquals("new-jct", updated.getBiliJct());
        // 刷新接口不下发这两项，整体替换会把设备标识与刷新口令一并丢掉
        assertEquals("device-buvid", updated.getBuvid3());
        assertEquals("old-token", updated.getRefreshToken());
    }

    @Test
    @DisplayName("Set-Cookie 为空或格式异常时不应破坏原凭据")
    void shouldTolerateMalformedSetCookie() {
        Cookies current = new Cookies("sess", "jct", "buvid", "token");

        assertEquals("sess", BilibiliCookieRefreshUtil.applySetCookies(current, null).getSessData());
        assertEquals("sess", BilibiliCookieRefreshUtil.applySetCookies(current, List.of()).getSessData());
        assertEquals("sess", BilibiliCookieRefreshUtil
                .applySetCookies(current, List.of("", "no-equals-sign", "SESSDATA=; Path=/")).getSessData());
    }

    @Test
    @DisplayName("更新凭据不应改动传入的原对象")
    void shouldNotMutateInput() {
        Cookies current = new Cookies("old-sess", "old-jct", "buvid", "old-token");

        BilibiliCookieRefreshUtil.applySetCookies(current, List.of("SESSDATA=new-sess"));

        // doRefresh 在新凭据验证失败时要能原样换回旧凭据，前提是旧对象没被就地改掉
        assertEquals("old-sess", current.getSessData());
    }

    @Test
    @DisplayName("生成的密文应可被十六进制解析回 128 字节")
    void shouldProduceDecodableHex() {
        byte[] decoded = HexFormat.of().parseHex(BilibiliCookieRefreshUtil.correspondPath(1684466082562L));
        assertEquals(128, decoded.length);
    }

    /**
     * 现生成一对 RSA 密钥，只留在内存里
     */
    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * 按官方页面的参数解密：OAEP、SHA-256、MGF1 摘要由调用方指定、默认 label
     * <p>
     * 参数在这里独立写明，不取自产品，否则产品写错时两边会错得一样。
     */
    private static byte[] decrypt(KeyPair keyPair, MGF1ParameterSpec mgf1, byte[] cipherText) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(),
                new OAEPParameterSpec("SHA-256", "MGF1", mgf1, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(cipherText);
    }
}
