package org.frostnova.nova.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
