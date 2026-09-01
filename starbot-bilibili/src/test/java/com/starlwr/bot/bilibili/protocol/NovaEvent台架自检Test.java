package com.starlwr.bot.bilibili.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.PING;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.千分格成串;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.像真钉住的下沿_千分格;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.洞的签名_落后_千分格;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.观测格数;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读数;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.落后容忍_千分格;
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
     * 判据 4 改判相对之后，唯一还留着的那个数是
     * {@link NovaEvent慢消费者台架#落后容忍_千分格}——「后簇落后多久算陪葬」。
     * 谁把它从 400 改成 4000，判据 4 当场变成永远绿的，而全套读数一声不吭。
     * <p>
     * <b>护栏装在门口，而门在别处。</b>所以这一格就地量生效值。
     */
    @Test
    @DisplayName("判据 4 的线必须小于「后簇陪葬」的签名，否则那一格永远绿")
    void 落后容忍抓得住洞() {
        读数("台架自检-判据4的线", Map.of(
                "观测窗口（对照格）", 观测格数,
                "线（格）", 千分格成串(落后容忍_千分格),
                "洞的签名（格）", 千分格成串(洞的签名_落后_千分格),
                "检出余地（格）", 千分格成串(洞的签名_落后_千分格 - 落后容忍_千分格),
                "🔴 量的是生效值", "这里用的就是判据 4 在用的那两个常量，不是复制品",
                "🔴 线是怎么来的", "不是从噪声里量的，是从两边的量级里分的："
                        + "修后的落后是遍历中间的快照假象（微秒到几十毫秒，远不到一格），"
                        + "修前是整段阻塞（顶满整段窗口）。线放在中间，只挡量级错。"));

        assertTrue(落后容忍_千分格 < 洞的签名_落后_千分格,
                "判据 4 的线 " + 千分格成串(落后容忍_千分格) + " 格不小于「后簇陪葬」的签名 "
                        + 千分格成串(洞的签名_落后_千分格) + " 格（＝顶满整段观测窗口）——"
                        + "这个阈值抓不住它要抓的东西，那一格会永远绿。"
                        + "改 落后容忍_千分格（" + 落后容忍_千分格 + "）之前先想清楚这一条。");

        // 🔴 光「小于」不够：线要留在<b>量级</b>的中间。贴着签名的线，
        //    机器抖一下就够到签名附近，两边的余地一样窄——那时它挡的又成了机器。
        assertTrue(落后容忍_千分格 * 2 <= 洞的签名_落后_千分格,
                "判据 4 的线 " + 千分格成串(落后容忍_千分格) + " 格虽然小于签名 "
                        + 千分格成串(洞的签名_落后_千分格) + " 格，但没留下一倍以上的余地——"
                        + "线要放在两个量级的**中间**，贴着签名放，机器抖一下就够得着。");
    }

    /**
     * 🔴 说法线要<b>高于</b>红绿线，否则两支诊断塌成一支
     * <p>
     * 判据 4 红有两种成因，红形长得一模一样：后簇真被落下了（落后到<b>格</b>这个量级），
     * 与某条投递线程被剥了一会儿 CPU（落后只比红绿线高一点）。失败语靠
     * {@link NovaEvent慢消费者台架#像真钉住的下沿_千分格} 把两者分开。
     * <p>
     * 那条说法线要是<b>掉到红绿线上或以下</b>，凡红必被说成「更像真被落下了」——
     * <b>看着还有两支，其实只剩一支，而这一支永远说同一句话</b>。
     * 它不会红、不会抛，只会在往后每一条假红上把人指向错的方向。所以就地钉住。
     * <p>
     * 还要<b>低于</b>洞的签名：说法线要是爬到签名之上，真被钉住反而落进「机器抖」那一档——
     * <b>两支说反了比没有更费事</b>。
     */
    @Test
    @DisplayName("说法线必须夹在红绿线与洞的签名之间，否则失败语要么塌成一支、要么说反")
    void 说法线夹在两者之间() {
        读数("台架自检-两条线", Map.of(
                "红绿线（格）", 千分格成串(落后容忍_千分格),
                "说法线（格）", 千分格成串(像真钉住的下沿_千分格),
                "洞的签名（格）", 千分格成串(洞的签名_落后_千分格),
                "「更像机器抖了一下」那一档的宽度（格）",
                千分格成串(像真钉住的下沿_千分格 - 落后容忍_千分格),
                "心跳周期毫秒", PING,
                "🔴 量的是生效值", "这里用的就是失败语在用的那两个常量，不是复制品"));

        assertTrue(像真钉住的下沿_千分格 > 落后容忍_千分格,
                "说法线 " + 千分格成串(像真钉住的下沿_千分格) + " 格没有高于红绿线 "
                        + 千分格成串(落后容忍_千分格) + " 格——"
                        + "凡是红的都会被说成「更像真被落下了」，两支诊断塌成一支。"
                        + "它不会让任何一格变红，只会在往后每一条假红上把人指错方向。");

        assertTrue(像真钉住的下沿_千分格 < 洞的签名_落后_千分格,
                "说法线 " + 千分格成串(像真钉住的下沿_千分格) + " 格不低于洞的签名 "
                        + 千分格成串(洞的签名_落后_千分格) + " 格——"
                        + "真被钉住反而会被说成「更像机器抖了一下」，两支说反了。");
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
