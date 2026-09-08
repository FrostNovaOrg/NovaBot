package com.starlwr.bot.adapter.onebot.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群列表与好友列表这两支接口的解析
 *
 * <h2>为什么单给这两支立判据</h2>
 * 此前六支接口的 {@code data} 都是一个对象，代理因此一律按对象取。
 * 这两支<b>回的是数组</b>——同一段取法在这里<b>不报错，只是安静地拿到 null</b>。
 * 2026-09-04 实测：把分流改回「一律按对象取」之后，下面三条解析判据全部炸在
 * 空指针上，而不是解析异常——错处离原因很远。
 * 一个不报错的错处最容易在后续改动里被复原，所以这里既钉住新的两支解析得对，
 * 也钉住旧的那几支没被这次分流改坏。
 */
@DisplayName("群列表与好友列表接口")
class OneBotListApiTest {
    private HttpUtil http;

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
        OneBotHttpAdapterProxy proxy = new OneBotHttpAdapterProxy(http, new OneBotConnectionState());
        adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                proxy);
    }

    /**
     * 让 OneBot 对任意请求回一段固定的原文
     */
    private void replyWith(String json) {
        when(http.postJson(anyString(), anyMap(), any())).thenReturn(JSON.parseObject(json));
    }

    @Test
    @DisplayName("群列表按数组解析, 群号、群名、人数逐项取到")
    void parsesGroupList() {
        replyWith("""
                {"status":"ok","retcode":0,"data":[
                  {"group_id":10000002,"group_name":"开播提醒","member_count":42,"max_member_count":200},
                  {"group_id":10000003,"group_name":"测试群","member_count":7,"max_member_count":200}
                ]}
                """);

        JSONArray groups = adapter.getGroupList(sender, new JSONObject());

        assertEquals(2, groups.size());
        assertEquals(10000002L, groups.getJSONObject(0).getLong("group_id"));
        assertEquals("开播提醒", groups.getJSONObject(0).getString("group_name"));
        assertEquals(42, groups.getJSONObject(0).getInteger("member_count").intValue());
        assertEquals("测试群", groups.getJSONObject(1).getString("group_name"));
    }

    @Test
    @DisplayName("好友列表按数组解析, 账号、昵称、备注逐项取到")
    void parsesFriendList() {
        replyWith("""
                {"status":"ok","retcode":0,"data":[
                  {"user_id":10000004,"nickname":"甲","remark":"运维"},
                  {"user_id":10000005,"nickname":"乙","remark":""}
                ]}
                """);

        JSONArray friends = adapter.getFriendList(sender, new JSONObject());

        assertEquals(2, friends.size());
        assertEquals(10000004L, friends.getJSONObject(0).getLong("user_id"));
        assertEquals("甲", friends.getJSONObject(0).getString("nickname"));
        assertEquals("运维", friends.getJSONObject(0).getString("remark"));
        assertEquals("", friends.getJSONObject(1).getString("remark"));
    }

    @Test
    @DisplayName("一个群都没有时回空数组, 不是 null")
    void parsesEmptyGroupList() {
        replyWith("{\"status\":\"ok\",\"retcode\":0,\"data\":[]}");

        JSONArray groups = adapter.getGroupList(sender, new JSONObject());

        assertEquals(0, groups.size());
    }

    @Test
    @DisplayName("⚠️ 回对象的那几支照旧按对象取, 没被这次分流改坏")
    void objectShapedApisAreUntouched() {
        replyWith("{\"status\":\"ok\",\"retcode\":0,\"data\":{\"user_id\":10000001,\"nickname\":\"机器人\"}}");

        JSONObject info = adapter.getLoginInfo(sender, new JSONObject());

        assertInstanceOf(JSONObject.class, info);
        assertEquals(10000001L, info.getLong("user_id"));
        assertEquals("机器人", info.getString("nickname"));
    }

    @Test
    @DisplayName("data 缺席时回 null, 不抛异常")
    void missingDataYieldsNull() {
        replyWith("{\"status\":\"ok\",\"retcode\":0}");

        assertNull(adapter.getGroupList(sender, new JSONObject()));
    }

    @Test
    @DisplayName("两支接口各打各的地址")
    void callsTheRightUrls() {
        replyWith("{\"status\":\"ok\",\"retcode\":0,\"data\":[]}");

        adapter.getGroupList(sender, new JSONObject());
        adapter.getFriendList(sender, new JSONObject());

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(http, times(2)).postJson(urls.capture(), anyMap(), any());

        assertEquals("http://127.0.0.1:3000/get_group_list", urls.getAllValues().get(0));
        assertEquals("http://127.0.0.1:3000/get_friend_list", urls.getAllValues().get(1));
    }

    /**
     * 这一条守的是「请求头里带的是本平台的 Token」，与列表本身无关，
     * 但两支新接口是第一次由别的调用方（挑选界面）触发，顺手钉住
     */
    @Test
    @DisplayName("请求带上本平台的 OneBot Token")
    void carriesTheSenderToken() {
        replyWith("{\"status\":\"ok\",\"retcode\":0,\"data\":[]}");

        adapter.getGroupList(sender, new JSONObject());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(http).postJson(anyString(), headers.capture(), any());

        assertEquals("Bearer token", headers.getValue().get("Authorization"));
    }
}
