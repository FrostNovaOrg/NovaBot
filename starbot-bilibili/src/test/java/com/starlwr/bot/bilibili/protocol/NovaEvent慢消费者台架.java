package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.EventStreamTokenService;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 慢消费者用例的台架与三条判据的<b>探针本体</b>
 * <p>
 * 🔴 探针只写这一份。{@link NovaEventSlowConsumerTest}（量「修好没有」）与
 * {@link NovaEvent判据探针独立性Test}（量「三条判据互不代劳」）<b>共用</b>它——
 * <b>两把尺量同一件事必生漂移</b>，而这里漂移的后果是：独立性那组量的其实是另一支探针，
 * 于是它证明的独立性对真正在用的那三条<b>一句都不算</b>。
 * <p>
 * 台架自己也要能被拆开：{@link #支起慢客户端()} 不亮先验尺就直接判失败，
 * 因为<b>没复现出阻塞时，三条判据的绿和修好了的绿长得一样</b>。
 */
final class NovaEvent慢消费者台架 implements AutoCloseable {
    /** 读数行的前缀。改它要两头同改——外部收集方按这个串匹配 */
    static final String 读数标记 = "慢消费者读数 ";

    /** 心跳周期。取小值，好让「一个周期都没等到」在秒级窗口里看得出来 */
    static final long PING = 200;

    /** 认证时限 */
    static final long AUTH = 500;

    /** 回补窗口 */
    static final long GRACE = 300;

    /** 客户端超时。取大值：本组量的不是超时清理，别让它插进来 */
    static final long CLIENT_TIMEOUT = 60_000;

    /** 判据的等待上限。取心跳周期的十几倍——够宽，红了就不是「再等等就好了」 */
    static final long 判据等待 = 3_000;

    /**
     * 破坏用的大数。派到这个时限上的那件事在 {@link #判据等待} 内一定不会发生，
     * 而它<b>只</b>是那一条判据所量的下游。
     */
    static final long 破坏 = 60_000;

    static final NovaEventEndpoint.Timings 标准时限 =
            new NovaEventEndpoint.Timings(PING, CLIENT_TIMEOUT, AUTH, GRACE);

    // ══════ 「超时清理时的关闭帧」那一组：时限**反过来配** ══════
    // 上一组特意把超时清理挡在画面外（CLIENT_TIMEOUT 取 60 秒）；这一组要量的正是那一刻。
    // 🔴 认证闸与回补窗口都要**排在超时清理之后**到点，否则它们在卡住之前就自己办完了，
    //    判据 2／3 会在洞还在的时候变绿——那不是「修好了」，是「还没轮到出事」。

    /**
     * 断连时限：要它在测试里到点
     * <p>
     * 🔴 取值比「支起慢客户端」那一段耗时**多留 700 毫秒**：沉默连接与他连要在慢客户端支起
     * <b>之后</b>才接上，它们的闸才可能在卡住那一刻还悬着。头一版取 600 毫秒，
     * 支起那一段就花掉了 800 毫秒——<b>卡住之前认证闸与 goLive 都自己办完了，判据 2／3 假绿</b>。
     */
    static final long 关闭帧_CLIENT_TIMEOUT = 1200;

    /**
     * 认证闸：从沉默连接接上算起，要**晚于**卡住那一刻到点
     * <p>
     * 沉默连接在慢客户端支起之后才接上，那时距卡住还有约 700 毫秒；取 1000 毫秒即悬在卡住之后。
     */
    static final long 关闭帧_AUTH = 1000;

    /** 回补窗口：同上，要晚于卡住那一刻到点 */
    static final long 关闭帧_GRACE = 1000;

    /**
     * 关闭时真写多少字节。取协议里关闭帧载荷的上限。
     * <p>
     * 🔴 <b>量的东西要和要防的东西同尺寸。</b> 管道灌满时写 1 个字节也卡得住，
     * 但「1 字节卡住」证不了「真实关闭帧会卡住」。
     */
    static final int 关闭帧字节 = 125;

    /** 健康连接条数。要多于一条，后排那层伤害才有可能被排出来 */
    static final int 健康连接数 = 6;

    /**
     * 慢客户端的 id
     * <p>
     * 🔴 端点里的 {@code clients} 是 {@code ConcurrentHashMap}，遍历次序由 id 的散列定，
     * <b>同一组 id 每跑都一样</b>。这个 id 要是恰好散到最后，后排就永远是空的，
     * 分簇尺会次次不亮——而那看起来像「夹具搭不起来」，其实是「id 选得不巧」。
     * <p>
     * 这个值是<b>定出来的，不是挑顺眼的</b>：把这一组连接的 id 原样放进一个
     * {@code ConcurrentHashMap} 里，直接算出遍历次序，看慢客户端落在第几位。
     * {@code slow-0/1/2/3/6/7/10/11} 都排在六条健康连接<b>之前</b>（后排 6 条、前排 0 条），
     * {@code slow-8} 排在最后（前排 6 条、后排 0 条）——这两种都分不出簇。
     * {@code slow-4} 落在第 4 位：<b>前 2 条、后 4 条</b>，两边都不空。
     * <p>
     * 🔴 算出来之后仍要由 {@link 后排尺#分簇尺} 在真跑里确认。
     * 算的是「次序应该是什么」，分簇尺量的是「这一跑里实际发生了什么」——
     * 两者都要，因为前者依赖 JDK 的散列实现，换个 JDK 就可能不作数。
     */
    static final String 慢客户端id = "slow-4";

    /**
     * 判据 4 的余量：一条健康连接的相邻两次 ping 最多能隔多久（超出即算「陪葬」）
     * <p>
     * 🔴 <b>这个数此刻是暂定的。</b> 定稿要在<b>修后</b>连跑若干轮量出调度抖动分布再取，
     * 并把分布与取法一并记进读数——<b>拍出来的阈值和量出来的阈值长得一样，直到它假红那天</b>。
     */
    static final long 后排余量 = 250;

    static final NovaEventEndpoint.Timings 关闭帧时限 = new NovaEventEndpoint.Timings(
            PING, 关闭帧_CLIENT_TIMEOUT, 关闭帧_AUTH, 关闭帧_GRACE);

    final NovaEventStream stream;

    final NovaEventEndpoint endpoint;

    final EventStreamTokenService tokens;

    final NovaEventSlowConsumerTest.SocketSession 慢客户端;

    private final List<NovaEventSlowConsumerTest.SocketSession> 真会话 = new ArrayList<>();

    NovaEvent慢消费者台架(Path dir, boolean 慢客户端读, NovaEventEndpoint.Timings 时限) throws IOException {
        this(dir, 慢客户端读, 时限, "slow", false);
    }

    NovaEvent慢消费者台架(Path dir, boolean 慢客户端读, NovaEventEndpoint.Timings 时限,
                   String 慢id, boolean 关闭时真写) throws IOException {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        this.tokens = new EventStreamTokenService(properties);
        this.stream = new NovaEventStream(64);
        this.endpoint = new NovaEventEndpoint(stream, tokens, 时限);
        this.慢客户端 = new NovaEventSlowConsumerTest.SocketSession(
                慢id, 1024, 1024, 慢客户端读, 关闭时真写);
        真会话.add(this.慢客户端);
    }

    @Override
    public void close() {
        endpoint.shutdown();
        for (NovaEventSlowConsumerTest.SocketSession s : 真会话) {
            s.关掉();
        }
        真会话.clear();
    }

    // ══════════════════════════ 台架动作 ══════════════════════════

    /** 此刻端点上挂着几条连接。读数要带上它——同一条判据在 2 条连接和 200 条连接上不是一件事 */
    int 连接数() {
        return 已连上.size();
    }

    private final List<String> 已连上 = new ArrayList<>();

    NovaEventEndpointTest.FakeSession 连(String id) {
        已连上.add(id);
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession(id);
        endpoint.afterConnectionEstablished(session);
        return session;
    }

    void 认证(WebSocketSession session, String token) {
        endpoint.handleTextMessage(session, new TextMessage(
                "{\"kind\":\"auth\",\"v\":2,\"token\":\"" + token + "\"}"));
    }

    /**
     * 只回 pong、不记读数的保活跑者
     * <p>
     * 「超时清理」那一组把断连时限压到了几百毫秒。不回 pong 的连接会被<b>正常清理掉</b>，
     * 于是判据 2／3 在修后也红——那不是洞，是这条连接自己没活着。
     */
    保活 保活跑者(NovaEventEndpointTest.FakeSession s) {
        保活 记 = new 保活();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    JSONObject m = s.next();
                    if (m == null) {
                        continue;
                    }
                    // 🔴 跑者把队列抽干了，别人再去 next() 就抢不到——
                    //    所以「见过什么」由它自己记下来，不留第二个读队列的人。
                    记.见过.add(String.valueOf(m.getString("kind")));
                    endpoint.handleTextMessage(s, new TextMessage("{\"kind\":\"pong\"}"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ignored) {
                    return;
                }
            }
        }, "nova-test-keepalive-" + s.getId());
        t.setDaemon(true);
        t.start();
        return 记;
    }

    /** 保活跑者替这条连接记下「见过哪几种帧」 */
    static final class 保活 {
        private final java.util.Set<String> 见过 = ConcurrentHashMap.newKeySet();

        boolean 见过(String kind) {
            return 见过.contains(kind);
        }
    }

    /** 连上、认证、把 hello 收掉。返回这条会话 */
    NovaEventEndpointTest.FakeSession 连并认证(String id) throws Exception {
        NovaEventEndpointTest.FakeSession s = 连(id);
        认证(s, tokens.issue(id));
        if (s.next() == null) {
            fail("客户端 " + id + " 认证之后没收到 hello——台架自己就没起来，往下的读数都不算数");
        }
        return s;
    }

    /**
     * 把慢客户端支起来：灌满管道 → 连上 → 认证 → 等到它的发送线程真的卡在写里。
     *
     * @return 灌进去的字节数
     */
    long 支起慢客户端() throws Exception {
        已连上.add(慢客户端.getId());
        long 灌满起 = System.currentTimeMillis();
        long 灌 = 慢客户端.灌满();
        long 灌满耗时 = Math.max(1, System.currentTimeMillis() - 灌满起);
        endpoint.afterConnectionEstablished(慢客户端);
        认证(慢客户端, tokens.issue("慢客户端"));

        List<String> 栈 = 等到(判据等待, NovaEvent慢消费者台架::先验尺_发送线程确实卡在写里);
        if (栈 == null) {
            fail("先验尺不亮：没抓到「发送线程卡在 socket 写里且持着该客户端的监视器」这个读数。"
                    + "拿不到它就不许采信三条判据——没复现出阻塞时，三条判据的绿和修好了的绿长得一样。"
                    + "（已灌 " + 灌 + " 字节）");
        }
        // 🔴 等过慢客户端 goLive 的到点时刻再开量。
        //    不等的话，判据 1 会在心跳线程还没被派到 goLive 之前就收到 ping 而变绿——
        //    **那不是「修好了」，那是「还没轮到出事」**。
        //    这里等的是一个**固定时刻**（回补窗口 + 余量），修前修后都等同样长：
        //    等「直到心跳线程被钉住」的话，修好之后这一等会永远等下去。
        Thread.sleep(GRACE + 200);

        List<String> 心跳栈 = 心跳线程卡在别人的监视器上();
        读数("先验尺", Map.of(
                "灌入字节", 灌,
                // 发送速率与连接数：换台机器复现时，这两个数决定了背压是不是同一回事。
                // 🔴 速率是**量出来的**（灌满字节 ÷ 灌满耗时），不是配的——
                //    配一个数上去，机器换了它还是那个数。
                "灌满耗时毫秒", 灌满耗时,
                "发送速率字节每秒", 灌 * 1000L / 灌满耗时,
                "此刻连接数（支起慢客户端这一刻，健康连接尚未接入）", 连接数(),
                "发送线程栈摘录", 栈,
                "等过回补窗口毫秒", GRACE + 200,
                "此刻心跳线程被钉住吗", 心跳栈 != null,
                "心跳线程栈摘录", String.valueOf(心跳栈),
                "此刻卡了毫秒", 慢客户端.此刻卡了多久()));
        return 灌;
    }

    // ══════════════════════════ 三条判据的探针本体 ══════════════════════════

    /**
     * 一条判据的读数
     *
     * @param 绿       这条判据这一次是不是绿
     * @param 耗时毫秒 量到的耗时；红时为 -1
     */
    record 读(boolean 绿, long 耗时毫秒) {
    }

    /** 判据 1 的探针：健康客户端在 {@link #判据等待} 内收不收得到 ping */
    读 探1_健康客户端收得到ping(NovaEventEndpointTest.FakeSession 健康) throws Exception {
        long 起点 = System.currentTimeMillis();
        long deadline = 起点 + 判据等待;
        while (System.currentTimeMillis() < deadline) {
            JSONObject m = 健康.next();
            if (m == null) {
                break;
            }
            if ("ping".equals(m.getString("kind"))) {
                return new 读(true, System.currentTimeMillis() - 起点);
            }
        }
        return new 读(false, -1);
    }

    /** 判据 2 的探针：未认证连接在 {@link #判据等待} 内关不关得掉 */
    读 探2_认证闸关得掉(NovaEventEndpointTest.FakeSession 沉默) throws Exception {
        long 起点 = System.currentTimeMillis();
        CloseStatus 关 = 等到(判据等待, () -> 沉默.closedWith);
        return new 读(关 != null, 关 == null ? -1 : System.currentTimeMillis() - 起点);
    }

    /**
     * 判据 3 的探针：他连在 {@link #判据等待} 内转不转得进实时流
     * <p>
     * 🔴 这里睡的是<b>名义上的</b>回补窗口 {@link #GRACE}，不是端点上配着的那个值——
     * 破坏组把端点的回补窗口推到 {@link #破坏} 毫秒，探针要是跟着睡就变成睡一分钟，
     * 而<b>探针不该跟着被测物一起被破坏</b>。
     */
    读 探3_他连转进实时流(NovaEventEndpointTest.FakeSession 他连) throws Exception {
        return 探3_他连转进实时流(他连, GRACE);
    }

    读 探3_他连转进实时流(NovaEventEndpointTest.FakeSession 他连, long 回补窗口) throws Exception {
        long 起点 = System.currentTimeMillis();
        Thread.sleep(回补窗口 + 100);
        stream.publish(事件());

        long deadline = 起点 + 判据等待;
        while (System.currentTimeMillis() < deadline) {
            JSONObject m = 他连.next();
            if (m == null) {
                break;
            }
            if ("danmaku".equals(m.getString("kind"))) {
                return new 读(true, System.currentTimeMillis() - 起点);
            }
        }
        return new 读(false, -1);
    }

    static JSONObject 事件() {
        JSONObject j = new JSONObject();
        j.put("v", NovaEventMapper.PROTOCOL_VERSION);
        j.put("kind", "danmaku");
        j.put("ts", 1786111565000L);
        j.put("room", 47731877194803L);
        return j;
    }

    // ══════════════════════════ 后排尺（判据 4） ══════════════════════════

    /**
     * 判据 4 的量具：按连接分别记 ping 的到达时刻
     * <p>
     * 🔴 只记总数看不出「谁漏了」。判据 1～3 问「有没有人收到」，判据 4 问「<b>有没有人漏收</b>」，
     * 后者非按连接分开记不可。
     * <p>
     * 每条连接配一个跑者线程：收到任何一帧就<b>回一帧 pong</b>——
     * 这一组把断连时限压到了 {@link #关闭帧_CLIENT_TIMEOUT} 毫秒，不回 pong 的话
     * 健康连接自己也会被清理掉，那量的就成了别的事。
     */
    static final class 后排尺 implements AutoCloseable {
        /** 一条连接的读数 */
        record 一条(String id, long 最后ping时刻, long 最大间隔毫秒, int ping数) {
        }

        /** 分簇尺的读数：遍历有没有被截断在中间 */
        record 分簇(int 前簇条数, int 后簇条数, long 两簇间隔毫秒, boolean 亮, String 说明) {
        }

        private final Map<String, List<Long>> ping时刻 = new ConcurrentHashMap<>();
        private final List<Thread> 跑者们 = new ArrayList<>();
        private volatile boolean 停 = false;

        后排尺(NovaEventEndpoint 端点, Map<String, NovaEventEndpointTest.FakeSession> 们) {
            们.forEach((id, s) -> {
                ping时刻.put(id, java.util.Collections.synchronizedList(new ArrayList<>()));
                Thread t = new Thread(() -> {
                    while (!停) {
                        try {
                            JSONObject m = s.next();
                            if (m == null) {
                                continue;
                            }
                            if ("ping".equals(m.getString("kind"))) {
                                ping时刻.get(id).add(System.currentTimeMillis());
                            }
                            // 回一帧让 lastSeenAt 保鲜：这一组的断连时限只有几百毫秒
                            端点.handleTextMessage(s, new TextMessage("{\"kind\":\"pong\"}"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (RuntimeException ignored) {
                            // 连接已关等情况，跑者退场即可
                            return;
                        }
                    }
                }, "nova-test-poller-" + id);
                t.setDaemon(true);
                t.start();
                跑者们.add(t);
            });
        }

        @Override
        public void close() {
            停 = true;
            跑者们.forEach(Thread::interrupt);
        }

        /**
         * 收尺：算出每条连接在 [观测起, 观测止] 窗口内的最大 ping 间隔。
         * <p>
         * 🔴 窗口两端也算进间隔里：只算「相邻两次 ping 之间」的话，
         * <b>一次 ping 都没收到的那条连接会算出 0 间隔</b>，读起来像最健康的那条。
         */
        /**
         * 收全程：不设窗口，只为分簇尺用
         * <p>
         * 🔴 分簇尺读的是「<b>卡住之前</b>谁收到了那一轮、谁没收到」，
         * 而判据 4 的窗口是<b>卡住之后</b>那一段——窗口里一条 ping 都没有。
         * 拿窗口内的数据去分簇，只会得到「收到过 ping 的连接不足两条」。
         * 头一版就是这么错的：<b>分簇尺不亮，而不亮的原因是我喂错了数据</b>。
         */
        List<一条> 收全程() {
            return 收(0, Long.MAX_VALUE);
        }

        List<一条> 收(long 观测起, long 观测止) {
            List<一条> 出 = new ArrayList<>();
            new java.util.TreeMap<>(ping时刻).forEach((id, 们) -> {
                List<Long> 窗 = new ArrayList<>();
                synchronized (们) {
                    们.forEach(t -> {
                        if (t >= 观测起 && t <= 观测止) {
                            窗.add(t);
                        }
                    });
                }
                long 最大 = 0;
                long 上一次 = 观测起;
                for (long t : 窗) {
                    最大 = Math.max(最大, t - 上一次);
                    上一次 = t;
                }
                最大 = Math.max(最大, 观测止 - 上一次);
                出.add(new 一条(id, 窗.isEmpty() ? -1 : 窗.get(窗.size() - 1), 最大, 窗.size()));
            });
            return 出;
        }

        /**
         * 分簇尺：判据 4 自己的先验尺
         * <p>
         * 判据 4 天生依赖「被清理的那条排在遍历第几位」——它排最后的话后排没有人，
         * 判据 4 会在洞还在的时候变绿，而遍历次序由 id 的散列定、不归我们挑。
         * <p>
         * 🔴 故采信判据 4 之前必须量到<b>遍历确实被截断在中间</b>：
         * 各连接「最后一次收到 ping 的时刻」分成两簇、相差约一个心跳周期
         * （排在前面的收到了那一轮，排在后面的没收到）。
         * 全在一簇 → 这一跑<b>对判据 4 不算数</b>，不折算成绿。
         */
        static 分簇 分簇尺(List<一条> 条们) {
            List<Long> 时刻 = new ArrayList<>();
            条们.forEach(c -> {
                if (c.最后ping时刻() > 0) {
                    时刻.add(c.最后ping时刻());
                }
            });
            if (时刻.size() < 2) {
                return new 分簇(时刻.size(), 0, -1, false,
                        "收到过 ping 的连接不足两条，分不出簇——这一跑对判据 4 不算数");
            }
            时刻.sort(null);
            long 最大间隔 = 0;
            int 断点 = -1;
            for (int i = 1; i < 时刻.size(); i++) {
                if (时刻.get(i) - 时刻.get(i - 1) > 最大间隔) {
                    最大间隔 = 时刻.get(i) - 时刻.get(i - 1);
                    断点 = i;
                }
            }
            if (断点 < 1) {
                // 🔴 所有时刻一模一样（间隔全是 0）时找不出断点。
                //    不挡住的话，`前簇 = size - (-1)` 会算出**比连接数还多一条**，
                //    读数上去像是分出了簇——一个算错的数比不亮更难看出来。
                return new 分簇(时刻.size(), 0, 0, false,
                        "各连接的最后 ping 时刻完全相同，分不出断点——"
                                + "慢客户端在遍历次序里整个排在它们的一侧，后排是空的，"
                                + "这一跑对判据 4 不算数");
            }
            int 后 = 断点;                       // 时刻更早的那批 ＝ 排在被清理那条后面的
            int 前 = 时刻.size() - 断点;          // 时刻更晚的那批 ＝ 排在前面的
            boolean 亮 = 前 > 0 && 后 > 0 && 最大间隔 >= PING / 2;
            return new 分簇(前, 后, 最大间隔, 亮,
                    亮 ? "遍历被截断在中间：" + 前 + " 条排在前面（收到了那一轮）、"
                            + 后 + " 条排在后面（没收到），相差 " + 最大间隔 + " 毫秒"
                       : "各连接的最后 ping 时刻聚成一簇（最大间隔 " + 最大间隔 + " 毫秒 < "
                            + (PING / 2) + "）——要么没人排在后面、要么没人排在前面，"
                            + "排序那层伤害没被碰到，这一跑对判据 4 不算数");
        }
    }

    // ══════════════════════════ 先验尺 ══════════════════════════

    /**
     * 先验尺：慢客户端的发送线程<b>确实</b>卡在 socket 写里，且<b>正持着那个客户端的写锁</b>。
     * <p>
     * 🔴 拿不到这个读数就直接判失败，不进三条判据——
     * <b>没复现出阻塞时，三条判据的绿和修好了的绿长得一样。</b>
     *
     * @return 那条线程的栈摘录（不含任何值）
     */
    static List<String> 先验尺_发送线程确实卡在写里() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("nova-event-sender-")) {
                continue;
            }
            boolean 在写 = false;
            boolean 持锁 = false;
            List<String> 摘 = new ArrayList<>();
            for (StackTraceElement f : e.getValue()) {
                String line = f.getClassName() + "." + f.getMethodName();
                摘.add(line);
                if (line.contains("Socket") && f.getMethodName().contains("rite")) {
                    在写 = true;
                }
                if (line.endsWith("NovaEventEndpoint$Client.writeLocked")
                        || line.endsWith("NovaEventEndpoint$Client.writeDirect")) {
                    持锁 = true;
                }
            }
            if (在写 && 持锁) {
                return 摘.subList(0, Math.min(12, 摘.size()));
            }
        }
        return null;
    }

    /**
     * 先验尺（关闭帧那一组）：心跳线程<b>确实</b>卡在关闭帧的写里
     * <p>
     * 🔴 与「卡在别人的监视器上」那支<b>分界写死在这里</b>：这一支要求
     * 线程状态<b>不是</b> {@code BLOCKED}（BLOCKED 说明它在等一把 Java 锁，那是另一个洞），
     * 且栈里同时有 socket 写帧与 {@code Client.close}。
     * 两张单量两个洞，分界写在尺上而不是心里。
     *
     * @return 栈摘录与观测到的线程状态；没卡住时返回 null
     */
    static Map<String, Object> 心跳线程卡在关闭帧的写里() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("nova-event-heartbeat")) {
                continue;
            }
            if (e.getKey().getState() == Thread.State.BLOCKED) {
                continue;   // 那是「卡在别人的监视器上」，不是本支要量的
            }
            boolean 在写 = false;
            boolean 在关 = false;
            List<String> 摘 = new ArrayList<>();
            for (StackTraceElement f : e.getValue()) {
                String line = f.getClassName() + "." + f.getMethodName();
                摘.add(line);
                if (line.contains("Socket") && f.getMethodName().contains("rite")) {
                    在写 = true;
                }
                if (line.endsWith("NovaEventEndpoint$Client.close")) {
                    在关 = true;
                }
            }
            if (在写 && 在关) {
                Map<String, Object> 出 = new LinkedHashMap<>();
                出.put("线程状态", e.getKey().getState().name());
                出.put("栈摘录", 摘.subList(0, Math.min(14, 摘.size())));
                return 出;
            }
        }
        return null;
    }

    /** 心跳线程此刻卡在谁身上。返回栈摘录；没卡住时返回 null */
    static List<String> 心跳线程卡在别人的监视器上() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("nova-event-heartbeat")) {
                continue;
            }
            if (e.getKey().getState() != Thread.State.BLOCKED) {
                continue;
            }
            List<String> 摘 = new ArrayList<>();
            for (StackTraceElement f : e.getValue()) {
                摘.add(f.getClassName() + "." + f.getMethodName());
            }
            return 摘.subList(0, Math.min(8, 摘.size()));
        }
        return null;
    }

    static <T> T 等到(long 上限毫秒, java.util.function.Supplier<T> 探) throws Exception {
        long deadline = System.currentTimeMillis() + 上限毫秒;
        while (System.currentTimeMillis() < deadline) {
            T v = 探.get();
            if (v != null) {
                return v;
            }
            Thread.sleep(10);
        }
        return null;
    }

    /**
     * 读数打到标准输出，由外部收集
     * <p>
     * 🔴 前缀里<b>不带编号</b>。编号形状是「这里有一套编号系统」的招牌，
     * 公开面的源码字符串里不该有；改前缀要<b>两头同改</b>（这里与外部收集方的匹配串）。
     */
    static void 读数(String 名, Map<String, Object> 值) {
        JSONObject j = new JSONObject();
        j.put("读数", 名);
        j.putAll(值);
        System.out.println(读数标记 + j.toJSONString());
    }
}
