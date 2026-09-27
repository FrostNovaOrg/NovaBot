package org.frostnova.nova.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 起因链成环（A 的起因是 B、B 的起因又是 A）时不再无限递归：修这之前
     * 一进日志就 StackOverflowError，原本的错误被吞掉。链照常逐层打码，
     * 只在成环的那条边上截断。
     */
    @Test
    @DisplayName("起因链成环时打码不栈溢出，两层印文都打了码")
    void stopsWhenCauseChainFormsACycle() {
        Exception a = new Exception("A https://x.com/a?csrf=" + JCT);
        Exception b = new Exception("B https://y.com/b?csrf=" + JCT);
        a.initCause(b);
        b.initCause(a);

        Throwable sanitized = UrlMasker.sanitize(a);

        // 能走到断言本身就是判据的一半：没有栈溢出
        assertFalse(sanitized.toString().contains(JCT), "A 层仍带凭据:\n" + sanitized);
        assertTrue(sanitized.toString().contains("csrf=***"),
                "应当看得出这里原本有过一个 csrf 参数:\n" + sanitized);
        Throwable second = sanitized.getCause();
        assertNotNull(second, "B 这一层丢了:\n" + sanitized);
        assertFalse(second.toString().contains(JCT), "B 层仍带凭据:\n" + second);
        assertNull(second.getCause(), "B 的起因绕回 A，环上那条边要截断:\n" + second);
    }

    @Test
    @DisplayName("正常的三层起因链照旧三层都在")
    void keepsThreeLevelCauseChain() {
        Exception third = new Exception("L3 https://x.com/c?csrf=" + JCT);
        Exception second = new Exception("L2", third);
        Exception first = new Exception("L1", second);

        Throwable sanitized = UrlMasker.sanitize(first);

        assertTrue(sanitized.toString().contains("L1"), "第一层丢了:\n" + sanitized);
        Throwable level2 = sanitized.getCause();
        assertNotNull(level2, "第二层丢了:\n" + sanitized);
        assertTrue(level2.toString().contains("L2"), "第二层印文不对:\n" + level2);
        Throwable level3 = level2.getCause();
        assertNotNull(level3, "第三层丢了:\n" + level2);
        assertFalse(level3.toString().contains(JCT), "第三层仍带凭据:\n" + level3);
        assertTrue(level3.toString().contains("csrf=***"),
                "应当看得出这里原本有过一个 csrf 参数:\n" + level3);
        assertNull(level3.getCause(), "三层之外不该再有:\n" + level3);
    }
}
