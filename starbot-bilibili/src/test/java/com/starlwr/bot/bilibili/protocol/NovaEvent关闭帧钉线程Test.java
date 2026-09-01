package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.后排尺;
import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.后排读;
import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.序读;
import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.推进读;
import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.钉住闸;
import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.阻塞读;
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
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.千分格成串;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.复现等待格数;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.慢客户端id;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.先验尺_有人卡在关闭帧的写里;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.洞的签名_落后_千分格;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.观测格数;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读数;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.落后容忍_千分格;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.量健康连接推进;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.量后簇落后;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.阻塞余量_千分格;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.阻塞等待格数;
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
 *
 * <h2>🔴 这一组的尺一律判相对，不判绝对</h2>
 * 从前判据 4 拿墙钟绝对时长当尺：每条健康连接的相邻两次 ping 不许超过「心跳周期＋余量」
 * ＝ 300ms。<b>机器一有负载，判的就不是代码是机器</b>——实测在空载下已经量到六条健康连接
 * <b>齐齐 320ms</b>。六条一起偏移是「整机被推了一把」的形状，不是「谁被落下了」的形状，
 * 而那把绝对尺分不出这两件事。
 * <p>
 * 现在每一格量的都是<b>差</b>与<b>比</b>：
 * <ul>
 *   <li>判据 4 量「后簇比前簇少收轮次这个状态<b>持续</b>了多久」——负载把两簇一起拖慢，差不动；</li>
 *   <li>判据 1／2／3 的窗口以 {@link NovaEvent慢消费者台架.对照钟} 的<b>格</b>计——
 *       那把表和心跳同机器、同负载，只是不经过心跳线程：机器慢它跟着慢，心跳被钉住它照走；</li>
 *   <li>判据 0 本来就只比线程号，与时间无关，量法不动。</li>
 * </ul>
 * 还留着的那几个数（{@link NovaEvent慢消费者台架#落后容忍_千分格} 等）都放在<b>只挡量级错</b>
 * 的位置上：两边差着好几倍，线放在中间，且线的来历写在常量的注释里、由台架自检当场断言。
 *
 * <h2>🔴 每一格都配一格阳性对照</h2>
 * 判据 0～4 问的都是「洞不在的时候别人好不好」，而<b>「洞不在」和「格子不咬人」的绿长得一样</b>。
 * 所以每一格另有一格：用 {@link NovaEvent慢消费者台架.钉住闸} 把共享心跳线程<b>真钉住</b>一次，
 * 看那一格红不红，红完就把钉子拔掉。阳性对照走的是<b>判据在走的那一份量法</b>
 * （{@code 量健康连接推进}／{@code 量后簇落后}／同一支探针），不另写一份差不多的——
 * 两把尺量同一件事必生漂移，漂移之后阳性对照证的就不是判据那一格了。
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

    /** 判据 4 的两簇：排在被清理那条前面／后面的健康连接 */
    private 序读 序;

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
     * 两簇无从比较——<b>那看起来像夹具搭不起来，其实是次序排反了</b>。
     */
    private void 支起(boolean 慢客户端读) throws Exception {
        台 = new NovaEvent慢消费者台架(dir, 慢客户端读, 关闭帧时限, 慢客户端id, true);

        Map<String, NovaEventEndpointTest.FakeSession> 健康们 = new LinkedHashMap<>();
        for (int i = 0; i < 健康连接数; i++) {
            健康们.put("healthy-" + i, 台.连并认证("healthy-" + i));
        }
        尺 = new 后排尺(台.endpoint, 健康们);

        // 让健康连接先实实在在收几轮 ping。等的是**对照格**不是毫秒：
        // 机器慢时该多等一会儿，等的是「心跳该走过几轮了」，不是「墙上过了多久」。
        台.钟.走过(3);

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

        序 = NovaEvent慢消费者台架.算序(慢客户端id, 健康连接数, "silent", "other");

        // 🔴 这一觉睡的是**端点自己那条时限**：超时清理按墙钟到点，负载改不了它到点的时刻，
        //    所以这里仍旧是毫秒。睡完之后**等复现成立**那一段才按对照格算。
        Thread.sleep(关闭帧_CLIENT_TIMEOUT);
        if (慢客户端读) {
            // 阴性对照：慢端一直在读，**本来就不该有人卡住**。
            // 这里不空等那个上限——等一个注定不来的东西，等满了也只是慢，说明不了任何事。
            // 换成一句反向读数：短暂确认之后仍然没有人卡在关闭帧的写里。
            台.钟.走过(1);
            读数("开窗-阴性对照", Map.of(
                    "先睡毫秒", 关闭帧_CLIENT_TIMEOUT,
                    "又等了对照格", 1,
                    "此刻复现成立", NovaEvent慢消费者台架.复现成立(先验尺_有人卡在关闭帧的写里()),
                    "🔴 这一格要的正是不成立", "慢端正常读时没有阻塞可复现；"
                            + "这里若成立，说明夹具把不该卡的也卡住了，四条全绿就不作数"));
            return;
        }
        // 🔴 窗口的起点要**等到复现成立**再开，不是等一个拍出来的固定时长。
        //    拍固定时长的话，阻塞若在窗口开了之后才成立，排在慢客户端**前面**那几条
        //    会在窗口内收到那一轮 ping——判据 1 的修前红就成了掷骰子。
        //
        //    能这么等，正是因为先验尺是**相无关**的：它问「有没有人卡在关闭帧的写里」，
        //    修前是心跳线程、修后是发送线程，两相都成立。
        //    换成等「心跳线程被钉住」就不行了——那是修好之后永远等不到的东西。
        long 等起 = System.currentTimeMillis();
        Object 成了 = 台.钟.等到(复现等待格数,
                () -> NovaEvent慢消费者台架.复现成立(先验尺_有人卡在关闭帧的写里()) ? Boolean.TRUE : null);
        读数("开窗-等复现成立", Map.of(
                "先睡毫秒", 关闭帧_CLIENT_TIMEOUT,
                "又等了毫秒", System.currentTimeMillis() - 等起,
                "等的预算（对照格）", 复现等待格数,
                "等到上限就不等了", 成了 == null,
                "此刻复现成立", NovaEvent慢消费者台架.复现成立(先验尺_有人卡在关闭帧的写里()),
                "🔴 为什么不是固定时长", "阻塞在窗口开了之后才成立的话，前簇那几条会在窗口内"
                        + "收到那一轮 ping，判据 1 的修前红就成了掷骰子"));
    }

    /**
     * 阳性对照共用的夹具：慢客户端正常读（不制造真阻塞），钉子由钉住闸自己下
     * <p>
     * 🔴 钉子的 id <b>算着挑</b>，不写死一个顺眼的：位次由散列定，
     * 写死的那个换个 JDK 就落到最末尾，两簇有一边空掉——而空掉之后它看起来还是绿的。
     */
    private 钉住闸 支起并钉住() throws Exception {
        支起(true);
        序读 挑 = NovaEvent慢消费者台架.挑个居中的id("pin-", 健康连接数,
                List.of(慢客户端id, "silent", "other"));
        assertTrue(挑 != null, "挑不出一个落在健康连接中间的钉子 id —— "
                + "两簇总有一边是空的，判据 4 的阳性对照就成了空真。先查算序（多半是换了 JDK）。");
        序 = 挑;
        return 台.new 钉住闸(挑.慢id());
    }

    // ══════════════════════════ 先验尺 ══════════════════════════

    /**
     * 每一格判据的<b>前提戳</b>：这一跑复现成立吗
     * <p>
     * 🔴 判据 1～4 问的都是「洞在的时候，别人还好不好」。<b>洞不在，这一问就没有意义</b>——
     * 而「没复现出来」和「修好了」在判据的绿上长得一模一样。
     * 所以每一格开头都要盖一次戳，不是只在判据 0 那一格盖。
     * <p>
     * 这把先验尺是<b>修前修后都要的</b>戳（它问的是「有没有人卡在关闭帧的写里」，
     * 修前是心跳线程、修后是发送线程，两相都成立）。
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
     * 实测撞到过两次：2703ms／窗口 3000ms，2973ms／窗口 3000ms——后者只差 27ms。
     * 观测窗口后来改成按对照格开、且只开 {@link NovaEvent慢消费者台架#观测格数} 格，
     * 正是为了让它稳稳落在阻塞之内。
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
                "观测窗口（对照格）", 观测格数,
                "🔴 为什么要收尾再看一次", "阻塞中途解开的话，闸会在剩下的时间里自己办完，"
                        + "判据变绿——而那不是修好了，是这一跑没撑住"));
        assertTrue(还卡着, "窗口（" + 观测格数 + " 个对照格）还没走完，阻塞就自行解开了。"
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
    @DisplayName("余量尺：这一次阻塞撑得过观测窗口（判据 2／3／4 的修前红全靠它）")
    void 余量尺_阻塞撑得过观测窗口() throws Exception {
        支起(false);
        前提戳("余量尺");

        阻塞读 r = NovaEvent慢消费者台架.阻塞撑了多久(台.钟, 阻塞等待格数);
        Map<String, Object> 读 = new LinkedHashMap<>(r.读数());
        读.put("🔴 为什么要量这个", "判据的修前红全靠「阻塞撑得过窗口」。撑不过的那一轮，"
                + "闸会在窗口内自己办完，红就变成绿——而那不是修好了，是这一跑没撑住。"
                + "曾实测到 2703ms 对 3000ms 窗口，只差 297ms。");
        读.put("🔴 这是全套里唯一被负载往坏处推的量", "阻塞那两三秒是内核按**时间**放大接收缓冲"
                + "放出来的，不随 CPU 负载变长；而窗口按对照格算、格随负载变长。"
                + "所以窗口只开 " + 观测格数 + " 格，留出的正是这一格量的余量。");
        读数("余量尺-阻塞时长", 读);

        assertTrue(r.余量_千分格() >= 阻塞余量_千分格,
                "这一次阻塞只撑了 " + r.撑了毫秒() + "ms ＝ " + 千分格成串(r.撑了_千分格())
                        + " 个对照格（本跑一格 " + r.段().本跑格长毫秒() + "ms），观测窗口 "
                        + 观测格数 + " 格，余量 " + 千分格成串(r.余量_千分格())
                        + " 格不到要求的 " + 千分格成串(阻塞余量_千分格) + " 格。"
                        + "余量不够时，判据的修前红是**掷骰子**：阻塞早解开一点，"
                        + "闸就在窗口内自己办完了。这一跑不算数——不是修好了，是没撑住。");
    }

    // ══════════════════════════ 判据 0 ══════════════════════════

    /**
     * 判据 0 的量法：卡住的那几条线程里，有没有一条是夹具登记在案的共享心跳线程
     * <p>
     * 🔴 <b>这一格天生就是相对的</b>：它比的是线程号，一个毫秒都不掺。
     * 负载再高也改不了「卡住的是哪条线程」，所以它不用改量法——
     * <b>本来就没病的格子不该跟着动刀。</b>
     */
    private List<Map<String, Object>> 量心跳线程有没有被钉住(String 格名,
                                                  List<Map<String, Object>> 尺读) {
        // 🔴 判的是「**没有任何一条**是心跳线程」，不是「抽中的那条不是」。
        //    尺收全量正是为了这一句：只看第一条命中的话，同时有两条卡住时
        //    可能抽中发送线程而判绿，而这一格自称判的是前者。
        List<Map<String, Object>> 心跳那几条 = 尺读.stream()
                .filter(x -> ((Long) x.get("线程号")) == 台.心跳线程.getId())
                .toList();
        读数(格名 + "-归属", Map.of(
                "卡住的线程条数", 尺读.size(),
                "卡住的线程名", 尺读.stream().map(x -> String.valueOf(x.get("线程名"))).toList(),
                "夹具登记的心跳线程号", 台.心跳线程.getId(),
                "夹具登记的心跳线程名", 台.心跳线程.getName(),
                "其中是共享心跳线程的条数", 心跳那几条.size(),
                "🔴 身份怎么判的", "与夹具登记在案的那条线程比线程号，不按名字前缀猜；"
                        + "且判的是**全量里没有一条**，不是「抽中的那条不是」"));
        return 心跳那几条;
    }

    @Test
    @DisplayName("判据 0：卡住的线程里没有一条是共享心跳线程")
    void 判据0_卡住的不是共享心跳线程() throws Exception {
        支起(false);
        List<Map<String, Object>> 尺读 = 前提戳("判据0");
        List<Map<String, Object>> 心跳那几条 = 量心跳线程有没有被钉住("判据0", 尺读);

        assertTrue(心跳那几条.isEmpty(), "关闭帧的阻塞写把**全局共享的心跳线程**（"
                + 台.心跳线程.getName() + "）钉住了。它一停，所有连接的 ping、认证时限、"
                + "回补窗口一起停；还多一层——遍历是顺序的，排在它后面的连接这一轮连 ping 都轮不到。"
                + "实录：" + 心跳那几条);
    }

    @Test
    @DisplayName("阳性对照·判据 0：真把心跳线程钉住时，这一格必须红")
    void 阳性对照_判据0() throws Exception {
        try (钉住闸 钉 = 支起并钉住()) {
            List<Map<String, Object>> 心跳那几条 =
                    量心跳线程有没有被钉住("阳性对照-判据0", 先验尺_有人卡在关闭帧的写里());
            assertTrue(!心跳那几条.isEmpty(),
                    "心跳线程已经被钉住了，判据 0 的量法却一条都没认出来——**这一格不咬人**。"
                            + "它的绿从此说明不了任何事。先查身份判定（线程号是怎么登记的）。");
        }
    }

    // ══════════════════════════ 四条判据 ══════════════════════════

    @Test
    @DisplayName("判据 1：清理一个写不动的客户端时，健康连接仍收得到 ping")
    void 判据1_健康连接仍收得到ping() throws Exception {
        支起(false);
        前提戳("判据1");
        推进读 r = 量健康连接推进(尺, 台.钟, 观测格数);
        收尾戳("判据1");
        Map<String, Object> 读 = new LinkedHashMap<>(r.读数());
        读.put("此刻连接数", 台.连接数());
        读.put("心跳间隔毫秒", PING);
        读.put("🔴 量的是推进不是绝对间隔", "问的是「对照钟走了 " + 观测格数
                + " 格，心跳走了没有」——两把表比着看，比的是同一份负载");
        读数("判据1-ping", 读);

        assertTrue(r.推进了的条数() > 0, "清理那个写不动的客户端时，" + r.总条数()
                + " 条健康连接在对照钟走过 " + 观测格数 + " 格（实测 " + r.段().墙钟毫秒()
                + "ms）里**一轮 ping 都没推进**——心跳线程卡在它的关闭帧写里了。"
                + "对照钟与心跳同机器同负载，它走得动而心跳走不动，就不是机器慢。");
    }

    @Test
    @DisplayName("阳性对照·判据 1：真把心跳线程钉住时，这一格必须红")
    void 阳性对照_判据1() throws Exception {
        try (钉住闸 钉 = 支起并钉住()) {
            推进读 r = 量健康连接推进(尺, 台.钟, 观测格数);
            读数("阳性对照-判据1", r.读数());
            assertTrue(钉.还钉着(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(r.推进了的条数() == 0,
                    "心跳线程已经被钉住了，却还有 " + r.推进了的条数() + " 条健康连接在推进 ping——"
                            + "**这一格不咬人**，它的绿说明不了任何事。实录：" + r.读数());
        }
    }

    @Test
    @DisplayName("判据 2：清理一个写不动的客户端时，未认证连接仍在时限内被关")
    void 判据2_认证闸仍然关得掉() throws Exception {
        支起(false);
        前提戳("判据2");
        long 起 = System.currentTimeMillis();
        Object 关了 = 台.钟.等到(观测格数, () -> 沉默.closedWith);
        收尾戳("判据2");
        读数("判据2-认证闸", Map.of("此刻连接数", 台.连接数(),
                "认证闸时限毫秒", 关闭帧_AUTH, "观测窗口（对照格）", 观测格数,
                "关上了", 关了 != null, "实际关闭耗时毫秒", System.currentTimeMillis() - 起,
                "🔴 窗口为什么按格算", "认证闸是派在心跳线程上的一次性活；问的是"
                        + "「对照钟走了 " + 观测格数 + " 格，那件活办了没有」，不是「几毫秒内办没办」"));

        assertTrue(关了 != null, "清理那个写不动的客户端时，未认证连接在对照钟走过 "
                + 观测格数 + " 格里没有被关——认证闸（" + 关闭帧_AUTH
                + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("阳性对照·判据 2：真把心跳线程钉住时，这一格必须红")
    void 阳性对照_判据2() throws Exception {
        try (钉住闸 钉 = 支起并钉住()) {
            // 🔴 要一条**闸还悬着**的未认证连接：钉子下去之前就被关掉的那条，
            //    证不了「钉住时关不掉」，只证得了「它早就关掉了」。
            NovaEventEndpointTest.FakeSession 新沉默 = 台.连("silent-2");
            Object 关了 = 台.钟.等到(观测格数, () -> 新沉默.closedWith);
            读数("阳性对照-判据2", Map.of("认证闸时限毫秒", 关闭帧_AUTH,
                    "观测窗口（对照格）", 观测格数, "关上了", 关了 != null));
            assertTrue(钉.还钉着(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(关了 == null,
                    "心跳线程已经被钉住了，未认证连接却还是被关掉了——**这一格不咬人**。"
                            + "认证闸没派在那条被钉住的线程上，或者探针问错了东西。");
        }
    }

    @Test
    @DisplayName("判据 3：清理一个写不动的客户端时，其它连接仍能转进实时流")
    void 判据3_他连仍能转进实时流() throws Exception {
        支起(false);
        前提戳("判据3");
        台.stream.publish(NovaEvent慢消费者台架.事件());
        Object 到了 = 台.钟.等到(观测格数, () -> 他连见过.见过("danmaku") ? Boolean.TRUE : null);
        收尾戳("判据3");
        读数("判据3-转实时流", Map.of("此刻连接数", 台.连接数(),
                "回补窗口毫秒", 关闭帧_GRACE, "观测窗口（对照格）", 观测格数,
                "他连收到事件", 到了 != null));

        assertTrue(到了 != null, "清理那个写不动的客户端时，其它连接在对照钟走过 "
                + 观测格数 + " 格里没能转进实时流——goLive（回补窗口 " + 关闭帧_GRACE
                + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("阳性对照·判据 3：真把心跳线程钉住时，这一格必须红")
    void 阳性对照_判据3() throws Exception {
        try (钉住闸 钉 = 支起并钉住()) {
            // 🔴 同判据 2：要一条**回补窗口还悬着**的连接。钉子下去之前就 goLive 了的那条，
            //    早已在实时流上，收得到事件证不了任何事。
            NovaEventEndpointTest.FakeSession 新他连 = 台.连并认证("other-2");
            NovaEvent慢消费者台架.保活 新他连见过 = 台.保活跑者(新他连);
            台.stream.publish(NovaEvent慢消费者台架.事件());
            Object 到了 = 台.钟.等到(观测格数, () -> 新他连见过.见过("danmaku") ? Boolean.TRUE : null);
            读数("阳性对照-判据3", Map.of("回补窗口毫秒", 关闭帧_GRACE,
                    "观测窗口（对照格）", 观测格数, "他连收到事件", 到了 != null));
            assertTrue(钉.还钉着(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(到了 == null,
                    "心跳线程已经被钉住了，新连接却还是转进了实时流——**这一格不咬人**。"
                            + "goLive 没派在那条被钉住的线程上，或者探针问错了东西。");
        }
    }

    /**
     * 判据 4 的两簇先要都不空
     * <p>
     * 🔴 判据 4 有一条<b>与相无关</b>的前提：这一跑里<b>真的有人排在后面</b>。
     * 被清理那条要是恰好排在遍历的最末尾，后排一个人都没有，
     * 「没有人陪葬」这句话就是空真——换个 id（比如 slow-8）修后照样绿，
     * 而这条绿证不了「后排不陪葬」。
     * <p>
     * 前簇同样不能空：<b>前簇是后簇的对照</b>。没有前簇就没有「比谁」，
     * 那把相对的尺当场退回成绝对的。
     */
    private void 两簇都不空(String 格名) {
        读数(格名 + "-两簇", 序.读数());
        assertTrue(!序.后簇().isEmpty(), "这一组 id 算出来的遍历次序里，被清理那条后面"
                + "**一个健康连接都没有**：" + 序.读数() + "。后排是空的，「后排不陪葬」"
                + "这一格就是空真——换个 id 再来。");
        assertTrue(!序.前簇().isEmpty(), "这一组 id 算出来的遍历次序里，被清理那条前面"
                + "**一个健康连接都没有**：" + 序.读数() + "。没有前簇就没有对照，"
                + "这把相对的尺当场退回成绝对的——换个 id 再来。");
    }

    @Test
    @DisplayName("判据 4：后排不陪葬 —— 后簇不许持续落在前簇后面")
    void 判据4_后排不陪葬() throws Exception {
        支起(false);
        前提戳("判据4");
        两簇都不空("判据4");

        后排读 r = 量后簇落后(尺, 序.前簇(), 序.后簇(), 台.钟, 观测格数);
        收尾戳("判据4");
        Map<String, Object> 读 = new LinkedHashMap<>(r.读数());
        读.put("此刻连接数", 台.连接数());
        读.put("各条轮次", 尺.各条轮次());
        读数("判据4-后排", 读);

        assertTrue(r.最长落后_千分格() < 落后容忍_千分格, NovaEvent慢消费者台架.判据4失败语(r));
    }

    @Test
    @DisplayName("阳性对照·判据 4：真把心跳线程钉在遍历中间时，这一格必须红")
    void 阳性对照_判据4() throws Exception {
        try (钉住闸 钉 = 支起并钉住()) {
            两簇都不空("阳性对照-判据4");
            后排读 r = 量后簇落后(尺, 序.前簇(), 序.后簇(), 台.钟, 观测格数);
            读数("阳性对照-判据4", r.读数());
            assertTrue(钉.还钉着(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(r.最长落后_千分格() >= 落后容忍_千分格,
                    "心跳线程已经被钉在遍历中间了，后簇却没有持续落后——**这一格不咬人**，"
                            + "它的绿说明不了任何事。最长落后 " + 千分格成串(r.最长落后_千分格())
                            + " 格，线 " + 千分格成串(落后容忍_千分格) + " 格。实录：" + r.读数());
            assertTrue(r.最长落后_千分格() >= 洞的签名_落后_千分格 / 2,
                    "后簇是落后了，但只落后了 " + 千分格成串(r.最长落后_千分格())
                            + " 格，离洞的签名（" + 千分格成串(洞的签名_落后_千分格)
                            + " 格，＝整段窗口）差得远。真被钉住时后簇要等整段阻塞才轮得到，"
                            + "所以这更像**钉子没钉在遍历中间**——先查钉住闸走的是哪一支 close。");
        }
    }

    // ══════════════════════════ 阴性对照 ══════════════════════════

    @Test
    @DisplayName("阴性对照：同一夹具、慢客户端正常读 —— 四条判据必须全绿")
    void 阴性对照_客户端正常读时四条全绿() throws Exception {
        支起(true);
        两簇都不空("阴性对照");
        台.stream.publish(NovaEvent慢消费者台架.事件());

        推进读 一 = 量健康连接推进(尺, 台.钟, 观测格数);
        Object 二 = 台.钟.等到(观测格数, () -> 沉默.closedWith);
        boolean 三 = 他连见过.见过("danmaku");
        后排读 四 = 量后簇落后(尺, 序.前簇(), 序.后簇(), 台.钟, 观测格数);

        List<String> 不绿的 = new ArrayList<>();
        if (一.推进了的条数() <= 0) {
            不绿的.add("判据1");
        }
        if (二 == null) {
            不绿的.add("判据2");
        }
        if (!三) {
            不绿的.add("判据3");
        }
        if (四.最长落后_千分格() >= 落后容忍_千分格) {
            不绿的.add("判据4");
        }
        读数("阴性对照", Map.of("此刻连接数", 台.连接数(), "慢客户端是否正常读", true,
                "判据1", 一.读数(), "判据2 关上了", 二 != null, "判据3 收到事件", 三,
                "判据4", 四.读数(), "不绿的", 不绿的));

        assertTrue(一.推进了的条数() > 0, "阴性对照：客户端正常读时判据 1 也不绿，"
                + "说明红的是夹具本身把服务端跑垮了。实录：" + 一.读数());
        assertTrue(二 != null, "阴性对照：客户端正常读时判据 2 也不绿");
        assertTrue(三, "阴性对照：客户端正常读时判据 3 也不绿");
        assertTrue(四.最长落后_千分格() < 落后容忍_千分格,
                "阴性对照：客户端正常读时判据 4 也不绿。"
                        + "🔴 这一格从前是拿墙钟绝对间隔判的（心跳周期＋余量＝300ms），"
                        + "空载都会撞上——实测六条健康连接齐齐 320ms，**六条一起偏移是机器的形状**。"
                        + "现在判的是后簇比前簇落后多久。若还是红在这里，"
                        + "那才真是夹具把服务端跑垮了。" + NovaEvent慢消费者台架.判据4失败语(四));
    }
}
