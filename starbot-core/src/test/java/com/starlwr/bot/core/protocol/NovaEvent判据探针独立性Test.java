package com.starlwr.bot.core.protocol;

import com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.读;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.AUTH;
import static com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.CLIENT_TIMEOUT;
import static com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.GRACE;
import static com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.PING;
import static com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.破坏;
import static com.starlwr.bot.core.protocol.NovaEvent慢消费者台架.读数;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条判据互不代劳
 * <p>
 * 修后三条全绿。<b>但「三条都绿」有两种成因：真的三件事都好着，或者三条探针其实只在量同一件事。</b>
 * 后者在全绿时和前者长得一模一样。这三条要各自盯住三件<b>不同</b>的事，不是一件事的三种说法。
 * <p>
 * 这一组逐条把<b>只属于该条</b>的下游人为破坏掉（把它的时限推到 {@link NovaEvent慢消费者台架#破坏}
 * 毫秒，那件事在判据窗口内一定不会发生），然后要求：<b>对应那条单独红，另两条不动</b>。
 * <ul>
 *   <li>判据 1 的下游 —— 周期性 ping（{@code pingInterval}）</li>
 *   <li>判据 2 的下游 —— 未认证超时（{@code authTimeout}）</li>
 *   <li>判据 3 的下游 —— 回补窗口到点后的 {@code goLive}（{@code resumeGrace}）</li>
 * </ul>
 * 破坏的是<b>被测端点的时限</b>，不是探针：探针一旦跟着被改，这一组量的就成了别的东西。
 * <p>
 * 🔴 三条探针取自 {@link NovaEvent慢消费者台架}，与 {@link NovaEventSlowConsumerTest} 用的是
 * <b>同一份</b>。抄一份过来的话，这里证明的独立性对真正在用的那三条一句都不算。
 */
@DisplayName("三条判据互不代劳")
class NovaEvent判据探针独立性Test {

    @TempDir
    Path dir;

    private NovaEvent慢消费者台架 台;

    @AfterEach
    void 收摊() {
        if (台 != null) {
            台.close();
            台 = null;
        }
    }

    /**
     * 在「慢客户端已支起」的修后全绿态下，用给定时限跑完三条探针。
     *
     * @param 时限 已被破坏一处的时限组
     */
    private boolean[] 三条(NovaEventEndpoint.Timings 时限) throws Exception {
        台 = new NovaEvent慢消费者台架(dir, false, 时限);
        台.支起慢客户端();

        NovaEventEndpointTest.FakeSession 健康 = 台.连并认证("healthy");
        NovaEventEndpointTest.FakeSession 沉默 = 台.连("silent");
        NovaEventEndpointTest.FakeSession 他连 = 台.连并认证("other");

        读 一 = 台.探1_健康客户端收得到ping(健康);
        读 三 = 台.探3_他连转进实时流(他连);
        读 二 = 台.探2_认证闸关得掉(沉默);
        return new boolean[]{一.绿(), 二.绿(), 三.绿()};
    }

    private void 判(String 破坏的是, boolean[] 绿, int 该红的那条) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("破坏的下游", 破坏的是);
        m.put("该红的判据", 该红的那条);
        m.put("判据1", 绿[0]);
        m.put("判据2", 绿[1]);
        m.put("判据3", 绿[2]);
        读数("独立性-" + 破坏的是, m);

        for (int i = 0; i < 3; i++) {
            int 条 = i + 1;
            if (条 == 该红的那条) {
                assertFalse(绿[i], "破坏了「" + 破坏的是 + "」，判据 " + 条 + " 却还是绿的——"
                        + "它没在量自己那条下游，它的绿说明不了那件事还好着");
            } else {
                assertTrue(绿[i], "只破坏了「" + 破坏的是 + "」，判据 " + 条 + " 也跟着红了——"
                        + "三条判据没有各量各的，红一片时分不清到底是哪件事坏了");
            }
        }
    }

    @Test
    @DisplayName("只掐掉周期性 ping —— 只有判据 1 该红")
    void 破坏心跳周期_只有判据1该红() throws Exception {
        判("周期性 ping", 三条(new NovaEventEndpoint.Timings(破坏, CLIENT_TIMEOUT, AUTH, GRACE)), 1);
    }

    @Test
    @DisplayName("只掐掉未认证超时 —— 只有判据 2 该红")
    void 破坏认证时限_只有判据2该红() throws Exception {
        判("未认证超时", 三条(new NovaEventEndpoint.Timings(PING, CLIENT_TIMEOUT, 破坏, GRACE)), 2);
    }

    @Test
    @DisplayName("只掐掉回补窗口到点的 goLive —— 只有判据 3 该红")
    void 破坏回补窗口_只有判据3该红() throws Exception {
        判("回补窗口到点的 goLive", 三条(new NovaEventEndpoint.Timings(PING, CLIENT_TIMEOUT, AUTH, 破坏)), 3);
    }
}
