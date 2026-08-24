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
import java.util.List;
import java.util.Map;

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

    final NovaEventStream stream;

    final NovaEventEndpoint endpoint;

    final EventStreamTokenService tokens;

    final NovaEventSlowConsumerTest.SocketSession 慢客户端;

    private final List<NovaEventSlowConsumerTest.SocketSession> 真会话 = new ArrayList<>();

    NovaEvent慢消费者台架(Path dir, boolean 慢客户端读, NovaEventEndpoint.Timings 时限) throws IOException {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        this.tokens = new EventStreamTokenService(properties);
        this.stream = new NovaEventStream(64);
        this.endpoint = new NovaEventEndpoint(stream, tokens, 时限);
        this.慢客户端 = new NovaEventSlowConsumerTest.SocketSession("slow", 1024, 1024, 慢客户端读);
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
        long 起点 = System.currentTimeMillis();
        Thread.sleep(GRACE + 100);
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
