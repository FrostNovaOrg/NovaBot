package com.starlwr.bot.bilibili.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.PING;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.判据4上限;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.后排余量;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.洞的签名_漏一轮;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读数;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台架自己那几把尺
 * <p>
 * 量的是<b>台架好不好使</b>，不是被测物好不好使——两件事混在同一组绿里，
 * 尺坏了的那天没人看得出来。
 */
@DisplayName("台架自己的几把尺")
class NovaEvent台架自检Test {

    /**
     * 🔴 阈值的自验尺要装在<b>生效值</b>上
     * <p>
     * 判据 4 真正在用的上限是这几个 Java 常量算出来的。算这个数的那个脚本里另有一道自验，
     * 但它管的是<b>推导</b>：谁直接把 {@link NovaEvent慢消费者台架#后排余量} 从 20 改成 200，
     * 那道自验一声不吭，而判据 4 当场变成永远绿的。
     * <p>
     * <b>护栏装在门口，而门在别处。</b>所以这一格就地量生效值。
     */
    @Test
    @DisplayName("判据 4 的上限必须小于「漏掉一轮」的签名，否则那一格永远绿")
    void 判据4上限抓得住洞() {
        读数("台架自检-判据4上限", Map.of(
                "心跳周期毫秒", PING, "后排余量毫秒", 后排余量,
                "判据4上限毫秒", 判据4上限,
                "洞的签名毫秒（漏一轮）", 洞的签名_漏一轮,
                "检出余地毫秒", 洞的签名_漏一轮 - 判据4上限,
                "🔴 量的是生效值", "这里用的就是判据 4 在用的那几个常量，不是复制品"));

        assertTrue(判据4上限 < 洞的签名_漏一轮,
                "判据 4 的上限 " + 判据4上限 + "ms 不小于「漏掉一轮」的签名 "
                        + 洞的签名_漏一轮 + "ms（＝多等一个整周期）——"
                        + "这个阈值抓不住它要抓的东西，那一格会永远绿。"
                        + "改 后排余量（" + 后排余量 + "）之前先想清楚这一条。");
    }

    /**
     * 🔴 值里再出现一个叫「读数」的键，会把<b>名字</b>顶掉
     * <p>
     * 顶掉之后那条读数看起来跟正常的一模一样——外面按名字找就永远找不到它。
     * 这一格钉的就是那个形态：它真咬中过一次（定余量那 20 轮停在「没报先验尺读数」，
     * 查了才发现是名字被值顶掉了）。
     */
    @Test
    @DisplayName("读数：值里再带一个「读数」键就当场抛（它会把名字顶掉）")
    void 读数_撞名即抛() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> 读数("先验尺-关闭帧", Map.of("读数", "顶掉名字的那个值")));
        assertTrue(e.getMessage().contains("顶掉"),
                "失败语要说清后果（名字会被顶掉），不能只说「键重复」——"
                        + "只说重复的话，下一个人会以为换个写法就行了。实录：" + e.getMessage());
    }

    /** 阴性对照：只验「撞名会抛」证不了它在分辨。 */
    @Test
    @DisplayName("读数：不带那个键时照常出一条，不抛")
    void 读数_不撞名时不抛() {
        assertDoesNotThrow(() -> 读数("台架自检-阴性对照", Map.of("实录", "x")));
    }
}
