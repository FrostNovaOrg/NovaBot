package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.后排尺;
import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.PING;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.健康连接数;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.关闭帧_AUTH;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.关闭帧_CLIENT_TIMEOUT;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.关闭帧_GRACE;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.关闭帧时限;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.判据等待;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.后排余量;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.慢客户端id;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.先验尺_有人卡在关闭帧的写里;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读数;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时清理时的关闭帧不许钉住全局共享的心跳线程
 * <p>
 * 与「慢消费者」那一组同源、<b>不同洞</b>：那一组是锁——阻塞写与 {@code goLive} 共用
 * per-client 监视器；这一组<b>不经由任何锁</b>——{@code heartbeat()} 顺序遍历连接，
 * 对超时的那条直接调 {@code close}，而真 WebSocket 的 close 要发一帧关闭帧，
 * 落到写不动的 socket 上就是阻塞写。上一组的写锁一点也挡不住它。
 * <p>
 * 伤害面比「线程卡住」多一层：遍历是顺序的，卡住时<b>排在后面的连接这一轮连 ping 都轮不到</b>，
 * 而谁排后面由 id 的散列定、不归我们挑。判据 4 专量这一层。
 */
@DisplayName("超时清理时的关闭帧不许钉住共享心跳线程")
class NovaEvent关闭帧钉线程Test {

    @TempDir
    Path dir;

    private NovaEvent慢消费者台架 台;

    private 后排尺 尺;

    private NovaEventEndpointTest.FakeSession 沉默;

    private NovaEventEndpointTest.FakeSession 他连;

    private NovaEvent慢消费者台架.保活 他连见过;

    /** 观测窗口的起点：**卡住那一刻已经过去**之后的一个固定时刻 */
    private long 观测起;

    @AfterEach
    void 收摊() {
        if (尺 != null) {
            尺.close();
            尺 = null;
        }
        if (台 != null) {
            台.close();
            台 = null;
        }
    }

    /**
     * 支起台架
     * <p>
     * 🔴 <b>次序要紧</b>：健康连接必须<b>先</b>接上并开始收 ping，慢客户端<b>后</b>接。
     * 反过来的话，清理会在健康连接接上之前就触发，它们一个 ping 也没收到过，
     * 分簇尺无从分簇——<b>那看起来像夹具搭不起来，其实是次序排反了</b>。
     */
    private void 支起(boolean 慢客户端读) throws Exception {
        台 = new NovaEvent慢消费者台架(dir, 慢客户端读, 关闭帧时限, 慢客户端id, true);

        Map<String, NovaEventEndpointTest.FakeSession> 健康们 = new LinkedHashMap<>();
        for (int i = 0; i < 健康连接数; i++) {
            健康们.put("healthy-" + i, 台.连并认证("healthy-" + i));
        }
        尺 = new 后排尺(台.endpoint, 健康们);

        Thread.sleep(PING * 3);        // 让健康连接先实实在在收几轮 ping

        if (慢客户端读) {
            // 阴性对照：对端一直在读，**灌不满**是应该的，别去灌。
            // 头一版照阳性那条路走，撞了「10 秒内灌不满」——那不是发现问题，是路走错了。
            台.endpoint.afterConnectionEstablished(台.慢客户端);
            台.认证(台.慢客户端, 台.tokens.issue("正常读的客户端"));
        } else {
            台.支起慢客户端();           // 灌满 → 连 → 认证；它不回 pong，故会被超时清理
        }

        // 🔴 沉默连接与他连在**慢客户端支起之后**才接上：它们的闸要在卡住那一刻还悬着。
        //    接得太早的话，卡住之前它们自己就办完了，判据 2／3 会在洞还在时变绿。
        沉默 = 台.连("silent");
        他连 = 台.连并认证("other");
        他连见过 = 台.保活跑者(他连);

        // 🔴 等的是一个**固定时刻**，修前修后都等同样长。
        //    等「直到心跳线程被钉住」的话，修好之后这一等会永远等下去。
        Thread.sleep(关闭帧_CLIENT_TIMEOUT + 300);
        观测起 = System.currentTimeMillis();
    }

    private List<后排尺.一条> 收尺() throws Exception {
        Thread.sleep(判据等待);
        return 尺.收(观测起, System.currentTimeMillis());
    }

    // ══════════════════════════ 先验尺 ══════════════════════════

    @Test
    @DisplayName("先验尺：这一跑里有人卡在关闭帧的写里（复现成立）")
    void 先验尺_有人卡在关闭帧写里() throws Exception {
        支起(false);
        Map<String, Object> 尺读 = 先验尺_有人卡在关闭帧的写里();
        读数("先验尺-关闭帧", Map.of(
                "有人卡在关闭帧的写里", 尺读 != null,
                "实录", String.valueOf(尺读),
                "🔴 这把尺只量现象", "卡住的是**哪条**线程是判据 0 的事，不是这把尺的事。"
                        + "把归属写进复现尺，修好那天它必死——而死掉的尺让「修好了」"
                        + "和「压根没灌满」长得一样。"));
        assertNotNull(尺读, "这一跑没有任何人卡在关闭帧的写里 —— **复现根本没成立**。"
                + "拿不到它就不许采信下面几条判据：没复现出阻塞时，判据的绿和修好了的绿长得一样。");
        assertTrue(!Boolean.TRUE.equals(尺读.get("尺没见过的状态")),
                "线程状态 " + 尺读.get("线程状态") + " 不在白名单 " + 尺读.get("白名单") + " 内。"
                        + "这不是红也不是绿：**这把尺没见过这个形态**，它量出来的东西不作数。"
                        + "先查这个状态是怎么来的，再决定要不要把它收进名单。实录：" + 尺读);
    }

    // ══════════════════════════ 判据 0 ══════════════════════════

    @Test
    @DisplayName("判据 0：卡住的那条不是共享心跳线程")
    void 判据0_卡住的不是共享心跳线程() throws Exception {
        支起(false);
        Map<String, Object> 尺读 = 先验尺_有人卡在关闭帧的写里();
        assertNotNull(尺读, "复现没成立（没有人卡在关闭帧的写里），判据 0 无从判 —— "
                + "这一跑不算数，不折算成绿");

        long 卡住的 = (Long) 尺读.get("线程号");
        // 🔴 身份按**夹具登记在案**的那条心跳线程比，不按名字前缀猜：
        //    名字是端点内部线程工厂给的，哪天改了措辞，按前缀比会静悄悄判成「不是心跳线程」。
        boolean 是心跳线程 = 卡住的 == 台.心跳线程.getId();
        读数("判据0-归属", Map.of(
                "卡住那条线程号", 卡住的, "卡住那条线程名", String.valueOf(尺读.get("线程名")),
                "夹具登记的心跳线程号", 台.心跳线程.getId(),
                "夹具登记的心跳线程名", 台.心跳线程.getName(),
                "卡住的是共享心跳线程", 是心跳线程,
                "🔴 身份怎么判的", "与夹具登记在案的那条线程比线程号，不按名字前缀猜"));

        assertTrue(!是心跳线程, "关闭帧的阻塞写把**全局共享的心跳线程**（"
                + 台.心跳线程.getName() + "）钉住了。它一停，所有连接的 ping、认证时限、"
                + "回补窗口一起停；还多一层——遍历是顺序的，排在它后面的连接这一轮连 ping 都轮不到。");
    }


    // ══════════════════════════ 四条判据 ══════════════════════════

    @Test
    @DisplayName("判据 1：清理一个写不动的客户端时，健康连接仍收得到 ping")
    void 判据1_健康连接仍收得到ping() throws Exception {
        支起(false);
        List<后排尺.一条> 条们 = 收尺();
        long 收到过的 = 条们.stream().filter(c -> c.ping数() > 0).count();
        读数("判据1-ping", Map.of("此刻连接数", 台.连接数(), "心跳间隔毫秒", PING,
                "观测窗口毫秒", 判据等待,
                "窗口内收到过 ping 的健康连接数", 收到过的,
                "健康连接总数", 条们.size()));

        assertTrue(收到过的 > 0, "清理那个写不动的客户端时，" + 条们.size()
                + " 条健康连接在 " + 判据等待 + " 毫秒里**一个 ping 都没收到**——"
                + "心跳线程卡在它的关闭帧写里了（心跳周期 " + PING + " 毫秒）");
    }

    @Test
    @DisplayName("判据 2：清理一个写不动的客户端时，未认证连接仍在时限内被关")
    void 判据2_认证闸仍然关得掉() throws Exception {
        支起(false);
        读 r = 台.探2_认证闸关得掉(沉默);
        读数("判据2-认证闸", Map.of("此刻连接数", 台.连接数(),
                "认证闸时限毫秒", 关闭帧_AUTH, "实际关闭耗时毫秒", r.耗时毫秒()));

        assertTrue(r.绿(), "清理那个写不动的客户端时，未认证连接在 " + 判据等待
                + " 毫秒内没有被关——认证闸（" + 关闭帧_AUTH + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("判据 3：清理一个写不动的客户端时，其它连接仍能转进实时流")
    void 判据3_他连仍能转进实时流() throws Exception {
        支起(false);
        台.stream.publish(NovaEvent慢消费者台架.事件());
        long deadline = System.currentTimeMillis() + 判据等待;
        while (System.currentTimeMillis() < deadline && !他连见过.见过("danmaku")) {
            Thread.sleep(20);
        }
        boolean 到了 = 他连见过.见过("danmaku");
        读数("判据3-转实时流", Map.of("此刻连接数", 台.连接数(),
                "回补窗口毫秒", 关闭帧_GRACE, "他连收到事件", 到了));

        assertTrue(到了, "清理那个写不动的客户端时，其它连接在 " + 判据等待
                + " 毫秒内没能转进实时流——goLive（回补窗口 " + 关闭帧_GRACE
                + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("判据 4：后排不陪葬 —— 没有一条连接因为别人被清理而少收一轮")
    void 判据4_后排不陪葬() throws Exception {
        支起(false);
        List<后排尺.一条> 条们 = 收尺();
        后排尺.分簇 分 = 后排尺.分簇尺(尺.收全程());

        long 上限 = PING + 后排余量;
        List<String> 超了 = new ArrayList<>();
        List<Long> 逐条迟到 = new ArrayList<>();
        for (后排尺.一条 c : 条们) {
            逐条迟到.add(c.最大间隔毫秒());
            if (c.最大间隔毫秒() > 上限) {
                超了.add(c.id() + "＝" + c.最大间隔毫秒() + "ms");
            }
        }
        读数("判据4-后排", Map.of("此刻连接数", 台.连接数(),
                "心跳间隔毫秒", PING, "余量毫秒", 后排余量, "上限毫秒", 上限,
                "逐条迟到毫秒", 逐条迟到, "超出上限的", 超了));
        读数("分簇尺", Map.of("前簇条数", 分.前簇条数(), "后簇条数", 分.后簇条数(),
                "两簇间隔毫秒", 分.两簇间隔毫秒(), "亮", 分.亮(), "说明", 分.说明()));

        // 🔴 算序是**选料器**，分簇尺是**裁判**。
        //    算序告诉我们该挑哪个 id 当慢客户端（它得真能把健康连接切成两截）；
        //    这一跑到底怎么排，只认分簇尺的实测。两者不符就不出结论——
        //    多半是换了 JDK（散列变了），算出来的次序当场作废。
        Map<String, Object> 序 = NovaEvent慢消费者台架.算序(慢客户端id, 健康连接数, "silent", "other");
        boolean 符 = !分.亮()
                || ((long) (Long) 序.get("算出来·健康连接排在前面的") == 分.前簇条数()
                    && (long) (Long) 序.get("算出来·排在后面的") == 分.后簇条数());
        读数("算序-选料器", Map.of("算序", 序, "实测前簇", 分.前簇条数(),
                "实测后簇", 分.后簇条数(), "算序与实测相符", 符));
        assertTrue(符, "算出来的遍历次序与这一跑的实测对不上："
                + 序 + "，实测 " + 分.前簇条数() + " 前／" + 分.后簇条数() + " 后。"
                + "选料的依据当场作废（多半是换了 JDK），先查因，不要挑一个好看的写上去。");

        // 🔴 停摆了就必须能指出「排序那层确实被碰到了」，否则红的不是判据 4 要量的东西。
        if (!超了.isEmpty()) {
            assertTrue(分.亮(), "有连接停摆了，但各连接的最后 ping 时刻聚成一簇——"
                    + "这一跑没碰到排序那层，红的不是判据 4 要量的东西。" + 分.说明());
        }

        assertTrue(超了.isEmpty(), "有连接因为**别人**被清理而少收了轮次："
                + 超了 + "（上限 " + 上限 + "ms ＝ 心跳周期 " + PING + " ＋ 余量 " + 后排余量 + "）");
    }

    // ══════════════════════════ 阴性对照 ══════════════════════════

    @Test
    @DisplayName("阴性对照：同一夹具、慢客户端正常读 —— 四条判据必须全绿")
    void 阴性对照_客户端正常读时四条全绿() throws Exception {
        支起(true);
        台.stream.publish(NovaEvent慢消费者台架.事件());
        List<后排尺.一条> 条们 = 收尺();
        读 二 = 台.探2_认证闸关得掉(沉默);

        long 上限 = PING + 后排余量;
        long 收到过的 = 条们.stream().filter(c -> c.ping数() > 0).count();
        List<String> 超了 = new ArrayList<>();
        for (后排尺.一条 c : 条们) {
            if (c.最大间隔毫秒() > 上限) {
                超了.add(c.id() + "＝" + c.最大间隔毫秒() + "ms");
            }
        }
        读数("阴性对照", Map.of("此刻连接数", 台.连接数(), "慢客户端是否正常读", true,
                "判据1", 收到过的 > 0, "判据2", 二.绿(),
                "判据3", 他连见过.见过("danmaku"), "判据4", 超了.isEmpty(),
                "判据4 超出上限的", 超了));

        assertTrue(收到过的 > 0, "阴性对照：客户端正常读时判据 1 也不绿，说明红的是夹具本身把服务端跑垮了");
        assertTrue(二.绿(), "阴性对照：客户端正常读时判据 2 也不绿");
        assertTrue(他连见过.见过("danmaku"), "阴性对照：客户端正常读时判据 3 也不绿");
        assertTrue(超了.isEmpty(), "阴性对照：客户端正常读时判据 4 也不绿：" + 超了);
    }
}
