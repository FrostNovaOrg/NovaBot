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
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.阻塞等待上限;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.复现等待上限;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.阻塞最小余量;
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

        // 🔴 窗口的起点要**等到复现成立**再开，不是等一个拍出来的固定时长。
        //    拍固定时长的话，阻塞若在窗口开了之后才成立，排在慢客户端**前面**那几条
        //    会在窗口内收到那一轮 ping——判据 1 的修前红就成了掷骰子（实测 3 轮里绿过 1 轮）。
        //
        //    能这么等，正是因为先验尺是**相无关**的：它问「有没有人卡在关闭帧的写里」，
        //    修前是心跳线程、修后是发送线程，两相都成立。
        //    换成等「心跳线程被钉住」就不行了——那是修好之后永远等不到的东西。
        Thread.sleep(关闭帧_CLIENT_TIMEOUT);
        long 等起 = System.currentTimeMillis();
        if (慢客户端读) {
            // 阴性对照：慢端一直在读，**本来就不该有人卡住**。
            // 这里不空等那个上限——等一个注定不来的东西，等满了也只是慢，说明不了任何事。
            // 换成一句反向读数：短暂确认之后仍然没有人卡在关闭帧的写里。
            Thread.sleep(200);
            观测起 = System.currentTimeMillis();
            读数("开窗-阴性对照", Map.of(
                    "先睡毫秒", 关闭帧_CLIENT_TIMEOUT + 200,
                    "此刻复现成立", NovaEvent慢消费者台架.复现成立(先验尺_有人卡在关闭帧的写里()),
                    "🔴 这一格要的正是不成立", "慢端正常读时没有阻塞可复现；"
                            + "这里若成立，说明夹具把不该卡的也卡住了，四条全绿就不作数"));
            return;
        }
        while (!NovaEvent慢消费者台架.复现成立(先验尺_有人卡在关闭帧的写里())
                && System.currentTimeMillis() - 等起 < 复现等待上限) {
            Thread.sleep(20);
        }
        long 等了 = System.currentTimeMillis() - 等起;
        观测起 = System.currentTimeMillis();
        读数("开窗-等复现成立", Map.of(
                "先睡毫秒", 关闭帧_CLIENT_TIMEOUT, "又等了毫秒", 等了,
                "等到上限就不等了", 等了 >= 复现等待上限,
                "此刻复现成立", NovaEvent慢消费者台架.复现成立(先验尺_有人卡在关闭帧的写里()),
                "🔴 为什么不是固定时长", "阻塞在窗口开了之后才成立的话，前簇那几条会在窗口内"
                        + "收到那一轮 ping，判据 1 的修前红就成了掷骰子"));
    }

    private List<后排尺.一条> 收尺() throws Exception {
        Thread.sleep(判据等待);
        return 尺.收(观测起, System.currentTimeMillis());
    }

    // ══════════════════════════ 先验尺 ══════════════════════════

    /**
     * 每一格判据的<b>前提戳</b>：这一跑复现成立吗
     * <p>
     * 🔴 判据 1～4 问的都是「洞在的时候，别人还好不好」。<b>洞不在，这一问就没有意义</b>——
     * 而「没复现出来」和「修好了」在判据的绿上长得一模一样。
     * 所以每一格开头都要盖一次戳，不是只在判据 0 那一格盖。
     * <p>
     * 分簇尺是<b>修前</b>的戳（它量的是遍历被截断，修好之后本就不该亮）；
     * 这把先验尺是<b>修前修后都要的</b>戳。
     * <b>只在红时才检查的前提，会让绿变成不带前提的绿。</b>
     *
     * @param 格名 落读数用
     * @return 先验尺的全量读数
     */
    private List<Map<String, Object>> 前提戳(String 格名) {
        List<Map<String, Object>> 尺读 = 先验尺_有人卡在关闭帧的写里();
        读数(格名 + "-前提戳", Map.of(
                "复现成立", NovaEvent慢消费者台架.复现成立(尺读),
                "卡住的线程条数", 尺读.size(),
                "实录", String.valueOf(尺读)));
        assertTrue(NovaEvent慢消费者台架.复现成立(尺读),
                "这一跑没有任何人卡在关闭帧的写里 —— **复现根本没成立**，这一格不算数。"
                        + "没复现出阻塞时，判据的绿和修好了的绿长得一样。");
        assertTrue(!NovaEvent慢消费者台架.尺没见过的状态(尺读),
                "先验尺见到了它没见过的线程状态：**这把尺没见过这个形态**，"
                        + "它量出来的东西不作数。先查这个状态是怎么来的。实录：" + 尺读);
        return 尺读;
    }

    /**
     * 收尾戳：窗口走完之后，阻塞<b>还</b>在吗
     * <p>
     * 🔴 前提只在<b>开窗那一刻</b>成立是不够的。判据 1～4 问的都是
     * 「洞<b>在的那段时间里</b>，别人还好不好」——阻塞若在窗口中途自行解开，
     * 闸会在剩下的时间里自己办完，判据当场变绿，<b>而那不是修好了，是这一跑没撑住</b>。
     * <p>
     * 实测撞到过两次：2703ms／窗口 3000ms，2973ms／窗口 3000ms——
     * 后者只差 27ms。
     * <p>
     * 🔴 <b>另开一格量「阻塞能撑多久」挡不住它</b>：那一格量的是<b>它自己那一跑</b>，
     * 判据是另一个夹具实例、另一次阻塞。尺和判据量的不是同一次事件，
     * 尺再准也管不着判据那一跑——前提得在<b>本格自己的窗口</b>上验。
     *
     * @param 格名 落读数用
     */
    private void 收尾戳(String 格名) {
        List<Map<String, Object>> 尺读 = 先验尺_有人卡在关闭帧的写里();
        boolean 还卡着 = NovaEvent慢消费者台架.复现成立(尺读);
        读数(格名 + "-收尾戳", Map.of(
                "窗口走完后阻塞还在", 还卡着,
                "观测窗口毫秒", 判据等待,
                "🔴 为什么要收尾再看一次", "阻塞中途解开的话，闸会在剩下的时间里自己办完，"
                        + "判据变绿——而那不是修好了，是这一跑没撑住"));
        assertTrue(还卡着, "窗口（" + 判据等待 + "ms）还没走完，阻塞就自行解开了。"
                + "剩下的时间里闸能自己办完，这一格的结论**不算数**——"
                + "不是修好了，是这一跑没撑住。（机制：对端不读，但环回 TCP 的接收缓冲"
                + "会被内核随时间放大，窗口一开那 125 字节就写出去了。）");
    }


    @Test
    @DisplayName("先验尺：这一跑里有人卡在关闭帧的写里（复现成立）")
    void 先验尺_有人卡在关闭帧写里() throws Exception {
        支起(false);
        List<Map<String, Object>> 尺读 = 先验尺_有人卡在关闭帧的写里();
        读数("先验尺-关闭帧", Map.of(
                "有人卡在关闭帧的写里", NovaEvent慢消费者台架.复现成立(尺读),
                "卡住的线程条数", 尺读.size(),
                "实录", String.valueOf(尺读),
                "🔴 这把尺只量现象", "卡住的是**哪条**线程是判据 0 的事，不是这把尺的事。"
                        + "把归属写进复现尺，修好那天它必死——而死掉的尺让「修好了」"
                        + "和「压根没灌满」长得一样。"));
        assertTrue(NovaEvent慢消费者台架.复现成立(尺读),
                "这一跑没有任何人卡在关闭帧的写里 —— **复现根本没成立**。"
                        + "拿不到它就不许采信下面几条判据。");
        assertTrue(!NovaEvent慢消费者台架.尺没见过的状态(尺读),
                "线程状态不在白名单内。这不是红也不是绿：**这把尺没见过这个形态**，"
                        + "它量出来的东西不作数。实录：" + 尺读);
    }

    @Test
    @DisplayName("余量尺：这一次阻塞撑得过观测窗口（判据 2／3 的红全靠它）")
    void 余量尺_阻塞撑得过观测窗口() throws Exception {
        支起(false);
        前提戳("余量尺");

        long 撑了 = NovaEvent慢消费者台架.阻塞撑了多久(阻塞等待上限);
        long 余量 = 撑了 - 判据等待;
        读数("余量尺-阻塞时长", Map.of(
                "阻塞撑了毫秒", 撑了, "观测窗口毫秒", 判据等待,
                "余量毫秒", 余量, "要求的最小余量毫秒", 阻塞最小余量,
                "等到上限就不等了", 撑了 >= 阻塞等待上限,
                "🔴 为什么要量这个", "判据 2／3 的修前红全靠「阻塞撑得过窗口」。"
                        + "撑不过的那一轮，闸会在窗口内自己办完，红就变成绿——"
                        + "而那不是修好了，是这一跑没撑住。曾实测到 2703ms 对 3000ms 窗口，"
                        + "只差 297ms。"));

        assertTrue(余量 >= 阻塞最小余量,
                "这一次阻塞只撑了 " + 撑了 + "ms，观测窗口 " + 判据等待 + "ms，余量 "
                        + 余量 + "ms 不到要求的 " + 阻塞最小余量 + "ms。"
                        + "余量不够时，判据 2／3 的修前红是**掷骰子**：阻塞早解开一点，"
                        + "闸就在窗口内自己办完了。这一跑不算数——不是修好了，是没撑住。");
    }

    // ══════════════════════════ 判据 0 ══════════════════════════

    @Test
    @DisplayName("判据 0：卡住的线程里没有一条是共享心跳线程")
    void 判据0_卡住的不是共享心跳线程() throws Exception {
        支起(false);
        List<Map<String, Object>> 尺读 = 前提戳("判据0");

        // 🔴 判的是「**没有任何一条**是心跳线程」，不是「抽中的那条不是」。
        //    尺收全量正是为了这一句：只看第一条命中的话，同时有两条卡住时
        //    可能抽中发送线程而判绿，而这一格自称判的是前者。
        List<Map<String, Object>> 心跳那几条 = 尺读.stream()
                .filter(x -> ((Long) x.get("线程号")) == 台.心跳线程.getId())
                .toList();
        读数("判据0-归属", Map.of(
                "卡住的线程条数", 尺读.size(),
                "卡住的线程名", 尺读.stream().map(x -> String.valueOf(x.get("线程名"))).toList(),
                "夹具登记的心跳线程号", 台.心跳线程.getId(),
                "夹具登记的心跳线程名", 台.心跳线程.getName(),
                "其中是共享心跳线程的条数", 心跳那几条.size(),
                "🔴 身份怎么判的", "与夹具登记在案的那条线程比线程号，不按名字前缀猜；"
                        + "且判的是**全量里没有一条**，不是「抽中的那条不是」"));

        assertTrue(心跳那几条.isEmpty(), "关闭帧的阻塞写把**全局共享的心跳线程**（"
                + 台.心跳线程.getName() + "）钉住了。它一停，所有连接的 ping、认证时限、"
                + "回补窗口一起停；还多一层——遍历是顺序的，排在它后面的连接这一轮连 ping 都轮不到。"
                + "实录：" + 心跳那几条);
    }

    // ══════════════════════════ 四条判据 ══════════════════════════

    @Test
    @DisplayName("判据 1：清理一个写不动的客户端时，健康连接仍收得到 ping")
    void 判据1_健康连接仍收得到ping() throws Exception {
        支起(false);
        前提戳("判据1");
        List<后排尺.一条> 条们 = 收尺();
        收尾戳("判据1");
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
        前提戳("判据2");
        读 r = 台.探2_认证闸关得掉(沉默);
        收尾戳("判据2");
        读数("判据2-认证闸", Map.of("此刻连接数", 台.连接数(),
                "认证闸时限毫秒", 关闭帧_AUTH, "实际关闭耗时毫秒", r.耗时毫秒()));

        assertTrue(r.绿(), "清理那个写不动的客户端时，未认证连接在 " + 判据等待
                + " 毫秒内没有被关——认证闸（" + 关闭帧_AUTH + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("判据 3：清理一个写不动的客户端时，其它连接仍能转进实时流")
    void 判据3_他连仍能转进实时流() throws Exception {
        支起(false);
        前提戳("判据3");
        台.stream.publish(NovaEvent慢消费者台架.事件());
        long deadline = System.currentTimeMillis() + 判据等待;
        while (System.currentTimeMillis() < deadline && !他连见过.见过("danmaku")) {
            Thread.sleep(20);
        }
        收尾戳("判据3");
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
        前提戳("判据4");

        // 🔴 判据 4 还有一条**与相无关**的前提：这一跑里**真的有人排在后面**。
        //    慢客户端要是恰好排在遍历的最末尾，后排一个人都没有，
        //    「没有人陪葬」这句话就是空真——换个 id（比如 slow-8）修后照样绿，
        //    而这条绿证不了「后排不陪葬」。
        Map<String, Object> 序 = NovaEvent慢消费者台架.算序(慢客户端id, 健康连接数, "silent", "other");
        long 算出来的后排 = (Long) 序.get("算出来·排在后面的");
        读数("判据4-后排存在吗", Map.of("算序", 序, "算出来的后排条数", 算出来的后排));
        assertTrue(算出来的后排 > 0, "这一组 id 算出来的遍历次序里，慢客户端后面**一个健康连接都没有**："
                + 序 + "。后排是空的，「后排不陪葬」这一格就是空真——换个慢客户端 id 再来。");

        List<后排尺.一条> 条们 = 收尺();
        收尾戳("判据4");
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
        // 🔴 分簇尺是**修前**的戳：它量的是「遍历被截断在中间」，修好之后本就不该亮。
        //    所以修后它退化成一簇（间隔 ~1ms）**只作读数记录、不作断言**——
        //    那本身就是修好了的旁证。修前的用法在下面。
        读数("分簇尺", Map.of("前簇条数", 分.前簇条数(), "后簇条数", 分.后簇条数(),
                "两簇间隔毫秒", 分.两簇间隔毫秒(), "亮", 分.亮(), "说明", 分.说明(),
                "🔴 这把尺是修前的戳", "修后它不亮是应该的，不作断言；修后这一格的戳是先验尺"));

        // 🔴 停摆了就必须能指出「排序那层确实被碰到了」，否则红的不是判据 4 要量的东西。
        //    这一支只在**修前**走得到（修后没有连接超限）。
        if (!超了.isEmpty()) {
            assertTrue(分.亮(), "有连接停摆了，但各连接的最后 ping 时刻聚成一簇——"
                    + "这一跑没碰到排序那层，红的不是判据 4 要量的东西。" + 分.说明());

            // 算序是**选料器**，分簇尺是**裁判**。两者不符就不出结论——
            // 多半是换了 JDK（散列变了），算出来的次序当场作废。
            // 🔴 只在分簇亮着时对表：写成「不亮就算相符」的话，修后那道对表是**恒真**的，
            //    它看起来在把关，其实一次都没跑过。
            boolean 符 = (long) (Long) 序.get("算出来·健康连接排在前面的") == 分.前簇条数()
                    && 算出来的后排 == 分.后簇条数();
            读数("算序-选料器对表", Map.of("算序", 序, "实测前簇", 分.前簇条数(),
                    "实测后簇", 分.后簇条数(), "算序与实测相符", 符,
                    "🔴 何时对表", "只在分簇尺亮着（＝修前）时对；不亮时不对表，也不折算成相符"));
            assertTrue(符, "算出来的遍历次序与这一跑的实测对不上："
                    + 序 + "，实测 " + 分.前簇条数() + " 前／" + 分.后簇条数() + " 后。"
                    + "选料的依据当场作废（多半是换了 JDK），先查因，不要挑一个好看的写上去。");
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
