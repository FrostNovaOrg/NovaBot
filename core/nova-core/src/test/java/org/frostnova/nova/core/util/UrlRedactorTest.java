package org.frostnova.nova.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常起因链剥地址
 * <p>
 * 这组判据要挡的是一个具体的故障：<b>起因链成环的异常一进日志就栈溢出</b>
 * （A 的起因是 B、B 的起因又是 A，少见但真会发生），原本要看的错误反倒被
 * StackOverflowError 吞掉。链照常逐层剥地址，只在成环的那条边上截断。
 */
@DisplayName("异常起因链剥地址")
class UrlRedactorTest {

    /**
     * 成环构造：A、B 互为起因。递归不记走过的对象时，redact(A) 会无限递归。
     */
    @Test
    @DisplayName("起因链成环时剥完不栈溢出，两层印文都剥了地址")
    void stopsWhenCauseChainFormsACycle() {
        Exception a = new Exception("A failed https://api.example.com/secret1");
        Exception b = new Exception("B failed https://other.example.com/secret2");
        a.initCause(b);
        b.initCause(a);

        Throwable stripped = UrlRedactor.redact(a);

        // 能走到断言本身就是判据的一半：没有栈溢出
        assertTrue(stripped.toString().contains("api.example.com"),
                "主机名要留下排障:\n" + stripped);
        assertFalse(stripped.toString().contains("secret1"),
                "A 层路径没剥掉:\n" + stripped);
        Throwable second = stripped.getCause();
        assertNotNull(second, "B 这一层丢了:\n" + stripped);
        assertTrue(second.toString().contains("other.example.com"),
                "B 层主机名要留下:\n" + second);
        assertFalse(second.toString().contains("secret2"),
                "B 层路径没剥掉:\n" + second);
        assertNull(second.getCause(), "B 的起因绕回 A，环上那条边要截断:\n" + second);
    }

    @Test
    @DisplayName("正常的三层起因链照旧三层都在")
    void keepsThreeLevelCauseChain() {
        Exception third = new Exception("L3 https://deep.example.com/x");
        Exception second = new Exception("L2", third);
        Exception first = new Exception("L1", second);

        Throwable stripped = UrlRedactor.redact(first);

        assertTrue(stripped.toString().contains("L1"), "第一层丢了:\n" + stripped);
        Throwable level2 = stripped.getCause();
        assertNotNull(level2, "第二层丢了:\n" + stripped);
        assertTrue(level2.toString().contains("L2"), "第二层印文不对:\n" + level2);
        Throwable level3 = level2.getCause();
        assertNotNull(level3, "第三层丢了:\n" + level2);
        assertTrue(level3.toString().contains("deep.example.com"),
                "第三层主机名要留下:\n" + level3);
        assertFalse(level3.toString().contains("https"), "第三层连 scheme 一起剥:\n" + level3);
        assertFalse(level3.toString().contains("/x"), "第三层路径没剥掉:\n" + level3);
        assertNull(level3.getCause(), "三层之外不该再有:\n" + level3);
    }

    /**
     * 抓的故障：地址里密钥前面带 {@code '} 这类正则收边界要停的字符时，
     * 只靠正则会把字符后面那段（正是密钥）留在印文里。
     * 带上已知地址后先按字面换主机名，拦得住。
     */
    @Test
    @DisplayName("带已知地址时先按字面换主机名，正则停住的字符后面那段也不留")
    void knownAddressIsStrippedEvenWhereTheRegexStops() {
        String text = "I/O error on GET request for \"http://api.example.com/'/secret1/barkgroup\": null";

        // 对照：不带已知地址时正则在 ' 上停，密钥还留着（正则与 hostOf 不改，这条钉住的就是这个事实）
        assertTrue(UrlRedactor.redact(text).contains("secret1"),
                "正则若连这里都收得住，下面的「不漏」证明不了是已知地址那一手在起作用:\n" + UrlRedactor.redact(text));

        String withKnown = UrlRedactor.redact(text, "http://api.example.com/'/secret1/barkgroup?group=x");

        assertFalse(withKnown.contains("secret1"), "已知地址没换成主机名，密钥还留着:\n" + withKnown);
        assertFalse(withKnown.contains("barkgroup"), "路径跟着留下来了:\n" + withKnown);
        assertFalse(withKnown.contains("group=x"), "查询串跟着留下来了:\n" + withKnown);
        assertTrue(withKnown.contains("api.example.com"), "主机名要留下排障:\n" + withKnown);
    }

    @Test
    @DisplayName("已知地址为空或 null 时与不带已知地址完全一样")
    void emptyOrNullKnownAddressesBehaveExactlyAsBefore() {
        String text = "A https://api.example.com/p/secret1?q=1 B http://x.test/'/secret2 C";

        assertEquals(UrlRedactor.redact(text), UrlRedactor.redact(text, (String[]) null),
                "null 应与不带已知地址完全一样");
        assertEquals(UrlRedactor.redact(text), UrlRedactor.redact(text, new String[0]),
                "空表应与不带已知地址完全一样");
        assertEquals(UrlRedactor.redact(text), UrlRedactor.redact(text, new String[]{null, ""}),
                "全是空项应与不带已知地址完全一样");
    }

    @Test
    @DisplayName("起因链每一层的印文都按已知地址换过，不只是最外层")
    void everyCauseLayerGetsTheKnownAddressStripped() {
        String address = "https://api.example.com/'/secret1/barkgroup?group=x";
        Exception cause = new Exception("read " + address + " timed out");
        Exception outer = new Exception("I/O error on GET request for \"" + address + "\": boom", cause);
        assertTrue(outer.getMessage().contains("secret1") && cause.getMessage().contains("secret1"),
                "注入没注入到密钥，说明这把尺子量错了地方");

        Throwable stripped = UrlRedactor.redact(outer, address);

        assertFalse(stripped.toString().contains("secret1"), "最外层还漏着:\n" + stripped);
        Throwable second = stripped.getCause();
        assertNotNull(second, "起因这一层丢了:\n" + stripped);
        assertFalse(second.toString().contains("secret1"), "起因那一层还漏着:\n" + second);
        assertTrue(second.toString().contains("api.example.com"), "主机名要留下排障:\n" + second);
    }
}
