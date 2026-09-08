package com.starlwr.bot.adapter.onebot.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 耗时窗只收推送类调用
 *
 * <h2>为什么名单类必须免记</h2>
 * {@link OneBotConnectionState} 的耗时窗是一个 20 格的定长环，健康探针拿它的中位数判「推送变慢」。
 * 名单类接口（群列表／好友列表／群成员信息）是批量拉取：{@code OneBotTargetDirectory} 一轮
 * 要打「群数加三」次请求。这些调用又多又慢——慢本来就是全量拉取的常态——
 * 每 TTL 一轮就够把 20 格整环冲掉。于是环里只剩名单拉取的样本，
 * 🔴 <b>「推送变慢」的探针从此量的是名单拉取而不是推送：没变慢也说变慢，
 * 真变慢了反而被这些样本稀释，两边一起失真，而连通性检查全程看不出异常。</b>
 * 名单拉取快不快，不该由推送耗时窗来答。
 *
 * <h2>这一格的判别点是样本数，不是样本值</h2>
 * 免记与误记在「名单调用之后样本数动没动」上分得开，不必真睡出 5 秒的样本来。
 * 名单调用在生产里各要好几秒，是误报成立的燃料；在这里用打点次数代表它——
 * 燃料多省不影响「这扇门该不该开」的读数。
 */
@DisplayName("耗时窗只收推送类调用")
class OneBotLatencyWindowTest {
    private HttpUtil http;

    private OneBotConnectionState state;

    private OneBotHttpAdapter adapter;

    private final OneBotSender sender = sender();

    private static OneBotSender sender() {
        OneBotSender sender = new OneBotSender();
        sender.setName("qq-onebot");
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("token");
        return sender;
    }

    @BeforeEach
    void setUp() {
        http = mock(HttpUtil.class);
        state = new OneBotConnectionState();
        OneBotHttpAdapterProxy proxy = new OneBotHttpAdapterProxy(http, state);
        adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                proxy);
    }

    /**
     * 让 OneBot 对任意请求回一段固定的原文；返回值这一格用不到，三种形状共用一段就够
     */
    private void replyWith(String json) {
        when(http.postJson(anyString(), anyMap(), any())).thenReturn(JSON.parseObject(json));
    }

    @Test
    @DisplayName("⚠️ 打满一轮名单类调用之后, 耗时窗里仍只有那三条推送样本")
    void listCallsStayOutOfTheLatencyWindow() {
        replyWith("{\"status\":\"ok\",\"retcode\":0,\"data\":[]}");

        for (int i = 0; i < 3; i++) {
            adapter.sendGroupMsg(sender, new JSONObject());
        }
        assertEquals(3, samples(), "铺垫：推送类调用照记，三条都进了耗时窗");

        // 二十是环长：打满一轮，正好够把旧样本全冲掉
        for (int i = 0; i < 20; i++) {
            adapter.getGroupList(sender, new JSONObject());
            adapter.getFriendList(sender, new JSONObject());
            adapter.getGroupMemberInfo(sender, new JSONObject());
        }

        assertEquals(3, samples(), "名单类调用进了耗时窗。它们每轮全量拉取又多又慢，"
                + "20 格的环一轮就被冲掉，「推送变慢」的探针从此量的是名单拉取而不是推送");
    }

    /**
     * 耗时窗里现在积累的样本数
     */
    private int samples() {
        return state.all().get(sender.getName()).getLatency().count();
    }
}
