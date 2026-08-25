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
    /**
     * 判据的观测窗口
     * <p>
     * 🔴 <b>3000ms 曾经太长。</b>阻塞撑不过窗口时，闸会在剩下的时间里自己办完，
     * 修前的红当场变绿——实测撞到过两次：2703ms 与 2973ms，
     * 后者离 3000 只差 27ms。
     * <p>
     * 现在取 1200ms：实测阻塞最短约 2700ms，余 ~1500ms。
     * 光靠这个数不够——它是**分布的下沿**，不是保证；所以每一格另有**收尾戳**，
     * 窗口走完时再确认一次阻塞还在，撑不住的那一跑记作不算数。
     * <p>
     * 窗口够不够用：心跳周期 200ms，1200ms 是 6 轮 ping；
     * 认证闸 1000ms、回补窗口 1000ms，都在窗口内。
     */
    static final long 判据等待 = 1_200;

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
     * 判据 4 的余量：一条健康连接的相邻两次 ping，允许比心跳周期多等多久（超出即算「陪葬」）
     * <p>
     * <b>这个数是量出来的</b>：修后连跑 20 轮、120 个读数，取「间隔<b>超出周期</b>那部分」
     * 的 p99 再乘二——p99 挡住常态抖动，乘二给比本机慢的跑测试环境留一档。
     * <p>
     * 实测（本机 arm64 macOS，JDK 17.0.20，周期 200ms，健康连接 6 条）：
     * 间隔 204／207／210（最小／中位／最大），<b>超出周期 4／7／10</b>，p99＝10 → 余量 20，
     * 判据上限 220ms。
     * <p>
     * 🔴 取的是<b>超出周期</b>那部分，不是间隔本身。拿间隔的 p99（210）乘二会把余量定成 420、
     * 上限 620ms——而这个洞的签名是「漏掉一轮」≈400ms，<b>上限比洞还宽，判据就永远绿了</b>。
     * 量出来的阈值一样可以是错的，错在拿错了量纲，而它和对的那个长得一样。
     * <p>
     * 🔴 定完阈值还要验它<b>抓不抓得住洞</b>：220 &lt; 400，检出余地 180ms。
     * <p>
     * 什么时候要重量：换机器、换 JDK、改心跳周期、改健康连接条数，四者任一。
     */
    static final long 后排余量 = 20;

    /**
     * 「漏掉一轮」这个洞在读数上长什么样：这一条要多等<b>一个整周期</b>
     * <p>
     * 判据 4 的上限必须<b>小于</b>它，否则那一格从出生起就永远绿。
     */
    static final long 洞的签名_漏一轮 = PING * 2;

    /**
     * 判据 4 实际在用的上限
     * <p>
     * 🔴 单独取出来命名，是为了让「上限」有一个<b>能被断言的名字</b>：
     * 阈值的自验尺得装在<b>生效值</b>上，不是装在算它的那个脚本里。
     * 算它的脚本再严，谁把上面那个 {@code 后排余量} 从 20 改成 200，判据当场永远绿，
     * 而全套读数一声不吭——<b>护栏装在门口，而门在别处。</b>
     */
    static final long 判据4上限 = PING + 后排余量;

    /**
     * 余量尺等阻塞解开的上限：等到这么久还卡着就不等了
     * <p>
     * 只是个封顶，不是判据；判据看的是 {@link #阻塞最小余量}。
     */
    static final long 阻塞等待上限 = 8_000;

    /**
     * 开窗前等「复现成立」的上限：等到这么久还没成立就不等了，让判据自己去红
     * <p>
     * 🔴 上限存在的意义是<b>不许无限等</b>：等不到就该红在「复现没成立」上，
     * 而不是把夹具挂死在那里，让人以为是别的问题。
     */
    static final long 复现等待上限 = 3_000;

    /**
     * 阻塞时长至少要比观测窗口多出这么多，判据 2／3 的修前红才算钉得住
     * <p>
     * 🔴 这个数是<b>量出来的</b>：见台架自检里那一格的推导与实测分布。
     * 实测到过 2703ms 阻塞对 3000ms 窗口——只差 297ms，那一跑的修前红当场变成绿。
     * <b>「卡住能撑满窗口」是个从来没人量过的默认。</b>
     */
    static final long 阻塞最小余量 = 800;

    static final NovaEventEndpoint.Timings 关闭帧时限 = new NovaEventEndpoint.Timings(
            PING, 关闭帧_CLIENT_TIMEOUT, 关闭帧_AUTH, 关闭帧_GRACE);

    final NovaEventStream stream;

    final NovaEventEndpoint endpoint;

    final EventStreamTokenService tokens;

    final NovaEventSlowConsumerTest.SocketSession 慢客户端;

    private final List<NovaEventSlowConsumerTest.SocketSession> 真会话 = new ArrayList<>();

    /**
     * 夹具<b>登记在案</b>的那条共享心跳线程
     * <p>
     * 🔴 判据 0 的身份判定以它为准，<b>不按名字前缀猜</b>：名字是端点内部的线程工厂给的，
     * 哪天改了措辞，按前缀比会静悄悄地判成「不是心跳线程」——那是一个假绿。
     * <p>
     * 必须在<b>任何连接被灌满之前</b>登记：登记的办法是往那条线程上派一件小活问它是谁，
     * 而它一旦被钉住，这件活就永远排不上号。
     */
    final Thread 心跳线程;

    private static Thread 登记心跳线程(NovaEventEndpoint endpoint) {
        try {
            java.lang.reflect.Field f = NovaEventEndpoint.class.getDeclaredField("heartbeats");
            f.setAccessible(true);
            java.util.concurrent.ScheduledExecutorService ex =
                    (java.util.concurrent.ScheduledExecutorService) f.get(endpoint);
            return ex.submit(Thread::currentThread).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("登记不到那条共享心跳线程 —— 判据 0 的身份判定就没有依据，"
                    + "这一跑不算数。不许退回按名字前缀猜：名字对不上时那是静悄悄的假绿。", e);
        }
    }

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
        this.心跳线程 = 登记心跳线程(this.endpoint);
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

    /**
     * 算序：把这一组 id 原样放进一个 {@code ConcurrentHashMap}，直接算出遍历次序
     * <p>
     * 🔴 <b>算序是选料器，分簇尺是裁判。</b> 算的是「次序应该是什么」——它依赖 JDK 的散列实现，
     * 换个 JDK 就可能不作数；分簇尺量的是「这一跑实际发生了什么」。
     * 两者<b>不一致即举手</b>（{@code 算序与实测不符}＝true），由外部收集方判 ABORT 查因，
     * 不许挑一个好看的写上去。
     */
    static Map<String, Object> 算序(String 慢id, int 健康数, String... 别的) {
        java.util.Map<String, Integer> m = new ConcurrentHashMap<>();
        for (int i = 0; i < 健康数; i++) {
            m.put("healthy-" + i, 1);
        }
        m.put(慢id, 1);
        for (String k : 别的) {
            m.put(k, 1);
        }
        List<String> 序 = new ArrayList<>(m.keySet());
        int 位 = 序.indexOf(慢id);
        long 前 = 序.subList(0, 位).stream().filter(x -> x.startsWith("healthy-")).count();
        long 后 = 序.subList(位 + 1, 序.size()).stream().filter(x -> x.startsWith("healthy-")).count();
        Map<String, Object> 出 = new LinkedHashMap<>();
        出.put("慢客户端 id", 慢id);
        出.put("位次", 位 + "/" + 序.size());
        出.put("算出来·健康连接排在前面的", 前);
        出.put("算出来·排在后面的", 后);
        出.put("JDK", System.getProperty("java.version") + " / " + System.getProperty("java.vm.name"));
        出.put("🔴", "算序只是选料器；这一跑到底怎么排，以分簇尺的实测为准");
        return 出;
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
     * 关闭帧那把尺认的线程状态<b>白名单</b>
     * <p>
     * 🔴 写成名单，不写成「不是 {@code BLOCKED}」：后者会对<b>没见过的状态默默放行</b>——
     * {@code TIMED_WAITING}、或某个将来 JDK 的新形态，都会带着完全正确的栈帧悄悄过尺。
     * 量的是「在不在名单里」，不是「是不是那个已知的坏形态」。
     * <p>
     * {@code BLOCKED} 不在名单里，是因为它是<b>另一个洞</b>的形状（在等一把 Java 锁）——
     * 分界写在尺上，不写在心里。
     */
    static final java.util.Set<Thread.State> 关闭帧_状态白名单 =
            java.util.Set.of(Thread.State.WAITING, Thread.State.RUNNABLE);

    /**
     * 同一份线程转储里，<b>持着 {@code NioSocketImpl} 那把写锁</b>的线程
     * <p>
     * 判别法：栈里有 {@code NioSocketImpl.write}，而<b>那一帧之上没有</b>
     * {@code ReentrantLock.lock}／{@code AQS.acquire}——也就是它已经<b>进了</b>锁，
     * 不是在等锁。
     * <p>
     * 🔴 取到它，「心跳线程在等 socket 写锁」才从推断变成实录：
     * 它等的是谁，名字与栈都钉在读数里。取不到就写明取不到，不含糊过去。
     */
    static Map<String, Object> 持写锁的线程() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            List<String> 摘 = new ArrayList<>();
            int 写帧 = -1;
            boolean 在等锁 = false;
            StackTraceElement[] fs = e.getValue();
            for (int i = 0; i < fs.length; i++) {
                String line = fs[i].getClassName() + "." + fs[i].getMethodName();
                摘.add(line);
                if (写帧 < 0 && line.equals("sun.nio.ch.NioSocketImpl.write")) {
                    写帧 = i;
                }
                if (写帧 < 0 && (line.endsWith("ReentrantLock.lock")
                        || line.endsWith("AbstractQueuedSynchronizer.acquire"))) {
                    在等锁 = true;   // 这几帧在 write 之上 ＝ 它在等这把锁，不是持有
                }
            }
            if (写帧 >= 0 && !在等锁) {
                Map<String, Object> 出 = new LinkedHashMap<>();
                出.put("线程名", e.getKey().getName());
                出.put("线程状态", e.getKey().getState().name());
                出.put("栈摘录", 摘.subList(0, Math.min(10, 摘.size())));
                return 出;
            }
        }
        return null;
    }

    /**
     * 先验尺：这一跑里<b>有人</b>卡在关闭帧的写里 —— 复现成立
     * <p>
     * 🔴 <b>复现尺量现象，判据量归属。</b>这把尺只问「关闭帧的写有没有真的卡住」，
     * 不问卡住的是<b>哪条</b>线程——那是判据 0 的事。
     * <p>
     * 原先它问的是「<b>心跳线程</b>卡在关闭帧写里」，把病灶的归属写进了现象的复现里。
     * 那样的尺在修好那天必死：修复要拿掉的正是「卡住的是心跳线程」这件事，
     * 于是修后它必红，而<b>一把死掉的尺让「修好了」和「压根没灌满」长得一样</b>。
     * 劈开之后修前修后都该绿，每一轮的数都自带「复现成立」的戳。
     * <p>
     * 状态走<b>白名单</b>：{@code {WAITING, RUNNABLE}} ＋ socket 写帧 ＋ {@code sendCloseFrame} 帧。
     * 不写成「<b>不是</b> {@code BLOCKED}」——那样会对没见过的状态默默放行：
     * {@code TIMED_WAITING}、或某个将来 JDK 的新形态，都会带着完全正确的栈帧悄悄过尺。
     * <b>名单外则尺自己举手</b>（{@code 尺没见过的状态}＝true），既不算红也不算绿，
     * 由外部收集方判 ABORT 带实录。
     * <p>
     * 实测：状态是 {@code WAITING}——它停在 {@code NioSocketImpl} 自己那把写锁上，
     * 不是自己停在 write 系统调用里。照「必须 RUNNABLE」写会次次假红。
     *
     * <p>
     * 🔴 <b>收全量，不是「第一条命中」。</b> {@code Thread.getAllStackTraces()} 的次序不定；
     * 只返回撞见的第一条，判据 0 判的就成了「<b>抽中的这条</b>不是心跳线程」，
     * 而它自称判的是「<b>没有</b>心跳线程卡在关闭帧写里」——两句话在绿的时候长得一样，
     * 只在「同时有两条卡住」那天分岔。
     *
     * @return 每条卡住的线程一份读数（名字／线程号／状态／栈摘录／它在等谁）；没人卡住时空表
     */
    static List<Map<String, Object>> 先验尺_有人卡在关闭帧的写里() {
        List<Map<String, Object>> 全部 = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            List<String> 摘 = new ArrayList<>();
            boolean 在写 = false;
            boolean 在发关闭帧 = false;
            for (StackTraceElement f : e.getValue()) {
                String line = f.getClassName() + "." + f.getMethodName();
                摘.add(line);
                if (line.contains("Socket") && line.endsWith(".write")) {
                    在写 = true;
                }
                if (line.contains("NovaEventEndpoint$Client")
                        && (f.getMethodName().equals("sendCloseFrame")
                            || f.getMethodName().equals("close"))) {
                    在发关闭帧 = true;
                }
            }
            if (!(在写 && 在发关闭帧)) {
                continue;
            }
            Thread.State st = e.getKey().getState();
            Map<String, Object> 出 = new LinkedHashMap<>();
            出.put("线程名", e.getKey().getName());
            出.put("线程号", e.getKey().getId());
            出.put("线程状态", st.name());
            出.put("状态在白名单内", 关闭帧_状态白名单.contains(st));
            出.put("尺没见过的状态", !关闭帧_状态白名单.contains(st));
            出.put("白名单", 关闭帧_状态白名单.stream().map(Enum::name).sorted().toList());
            出.put("栈摘录", 摘.subList(0, Math.min(14, 摘.size())));
            Map<String, Object> 持 = 持写锁的线程();
            出.put("它在等谁（持 NioSocketImpl 写锁的线程）",
                    持 == null ? "取不到——同一份转储里没找出持锁的那条" : 持);
            出.put("JDK", System.getProperty("java.version") + " / "
                    + System.getProperty("java.vm.name"));
            全部.add(出);
        }
        return 全部;
    }

    /**
     * 这一次阻塞<b>撑了多久</b>：从此刻起，等到再没有人卡在关闭帧的写里
     * <p>
     * 🔴 每一条「在窗口内没发生」型的判据（认证闸、转实时流）都<b>默认阻塞撑得过那个窗口</b>。
     * 那个默认从来没人量过——实测到过一次 2703ms 自行解开、窗口 3000ms，
     * 只差 297ms，于是那一轮的「修前红」变成了绿。
     * <b>撑不撑得过窗口，是量出来的，不是拍的。</b>
     * <p>
     * 机制上它本来就会自行解开：对端不读，但环回 TCP 的接收缓冲会随时间被内核放大，
     * 窗口一开，那 125 字节就写出去了。所以这个数是<b>分布</b>，要留余量。
     *
     * @param 上限毫秒 等到这么久还卡着就不等了，返回上限（读数里记成「至少这么久」）
     * @return 阻塞实际撑了多少毫秒
     */
    static long 阻塞撑了多久(long 上限毫秒) throws InterruptedException {
        long 起 = System.currentTimeMillis();
        while (System.currentTimeMillis() - 起 < 上限毫秒) {
            if (!复现成立(先验尺_有人卡在关闭帧的写里())) {
                return System.currentTimeMillis() - 起;
            }
            Thread.sleep(25);
        }
        return 上限毫秒;
    }

    /**
     * 先验尺的一句话结论：这一跑复现成立吗
     *
     * @param 尺读 {@link #先验尺_有人卡在关闭帧的写里()} 的返回
     * @return 有人卡在关闭帧的写里就为真
     */
    static boolean 复现成立(List<Map<String, Object>> 尺读) {
        return 尺读 != null && !尺读.isEmpty();
    }

    /**
     * 尺见到了它没见过的线程状态吗
     * <p>
     * 🔴 这不是红也不是绿：<b>这把尺没见过这个形态</b>，它量出来的东西不作数，
     * 由外部收集方判 ABORT 带实录。
     */
    static boolean 尺没见过的状态(List<Map<String, Object>> 尺读) {
        return 尺读 != null && 尺读.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("尺没见过的状态")));
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
        // 🔴 值里再出现一个叫「读数」的键，就会把**名字**顶掉，
        //    而顶掉之后那条读数看起来跟正常的一模一样——外面按名字找就永远找不到它。
        //    （已经踩过一次：先验尺那条把栈实录塞在「读数」键里，定余量那 20 轮第一轮就停。）
        if (值.containsKey("读数")) {
            throw new IllegalArgumentException("读数「" + 名 + "」的值里有一个叫「读数」的键，"
                    + "它会把名字顶掉。换个键名（比如「实录」）——顶掉之后没人看得出来。");
        }
        JSONObject j = new JSONObject();
        j.put("读数", 名);
        j.putAll(值);
        System.out.println(读数标记 + j.toJSONString());
    }
}
