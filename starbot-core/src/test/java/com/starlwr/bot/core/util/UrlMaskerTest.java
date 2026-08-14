package com.starlwr.bot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地址打码
 * <p>
 * 这组判据要挡的是一类具体的错：<b>凭据从查询串溜进日志</b>。
 * 它不会让任何功能变坏——日志照写、请求照发——所以靠人复查拦不住。
 */
@DisplayName("地址打码")
class UrlMaskerTest {
    private static final String JCT = "abc123def456ghi789";

    @Test
    @DisplayName("csrf（bili_jct 的一半）不许原样出现")
    void masksCsrf() {
        String masked = UrlMasker.mask("https://api.bilibili.com/x/info?csrf=" + JCT);
        assertFalse(masked.contains(JCT), "凭据仍在: " + masked);
        assertTrue(masked.contains("csrf=***"), masked);
    }

    @Test
    @DisplayName("扫码轮询令牌不许原样出现")
    void masksQrCodeKey() {
        String masked = UrlMasker.mask("https://passport.bilibili.com/poll?qrcode_key=KEY123&source=main");
        assertFalse(masked.contains("KEY123"), masked);
        // 🔴 同一条里的非凭据参数必须原样留着：全打掉等于让日志失去排障价值，
        //    而没有排障价值的日志会被连同保护一起关掉
        assertTrue(masked.contains("source=main"), masked);
    }

    @Test
    @DisplayName("裹在异常信息里的地址同样要打码")
    void masksUrlInsideExceptionMessage() {
        String message = "I/O error on GET request for \"https://x.com/a?csrf=" + JCT + "\": timeout";
        assertFalse(UrlMasker.mask(message).contains(JCT), UrlMasker.mask(message));
    }

    @Test
    @DisplayName("不该打的一个都不打")
    void keepsHarmlessParameters() {
        String url = "https://api.bilibili.com/x/live?room_id=12345&wts=1702204169&platform=web";
        assertEquals(url, UrlMasker.mask(url));
    }

    /**
     * 🔴 阳性对照：这把尺子自己得先能失败。
     * 一个「原样返回」的实现能让上面每一条「不含凭据」的断言都通过吗？
     * 不能——但一个「全删」的实现能。所以这里从反面钉住：
     * 没有凭据键时输出必须逐字符不变，有凭据键时必须真的变了。
     */
    @Test
    @DisplayName("判据能分辨「打了码」与「什么都没做」")
    void theRulerDistinguishesMaskingFromNoOp() {
        String withSecret = "https://x.com/a?csrf=" + JCT;
        String withoutSecret = "https://x.com/a?room_id=12345";

        assertFalse(UrlMasker.mask(withSecret).equals(withSecret), "含凭据时输出必须变");
        assertEquals(withoutSecret, UrlMasker.mask(withoutSecret), "不含凭据时输出必须不变");
    }

    @Test
    @DisplayName("null 与空串原样返回，不制造新的异常路径")
    void tolerantOfEmptyInput() {
        assertNull(UrlMasker.mask(null));
        assertEquals("", UrlMasker.mask(""));
    }

    @Test
    @DisplayName("键名大小写不影响判定")
    void caseInsensitiveKeys() {
        assertFalse(UrlMasker.mask("https://x.com/a?CSRF=" + JCT).contains(JCT));
    }
}
