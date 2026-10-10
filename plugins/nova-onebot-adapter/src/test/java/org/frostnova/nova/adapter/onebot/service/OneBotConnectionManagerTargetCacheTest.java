package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.controller.OneBotController;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 换了登录的 QQ 号之后，挑推送目标不能还拿旧号的名单
 *
 * <h2>这里守的是什么</h2>
 * 名单按平台名缓存十分钟。同一个平台换成另一个 QQ 号之后，十分钟内挑推送目标
 * 看到的还是旧号的群和好友，选了就推不出去。手动点刷新能好，但没人知道要点。
 * 保存连接就是「这条连接现在换人了」的那一刻，那一刻要清掉这个平台的名单。
 */
@DisplayName("换号保存连接后名单不再是旧号的")
class OneBotConnectionManagerTargetCacheTest {
    private static final String SENDER = "qq-onebot";

    private OneBotHttpAdapter http;

    private OneBotTargetDirectory directory;

    private OneBotConnectionManager manager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        http = mock(OneBotHttpAdapter.class);

        OneBotSender sender = new OneBotSender();
        sender.setName(SENDER);
        sender.setApi("/send");
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotWebsocketPort(3001);
        sender.setOneBotHttpToken("token");
        sender.setOneBotWebsocketToken("ws-token");

        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.setSenders(List.of(sender));
        properties.getDetect().setEnableHttpDetect(false);
        properties.getDetect().setEnableWebsocketDetect(false);

        OneBotConnectionState state = new OneBotConnectionState();

        OneBotHttpService httpService = mock(OneBotHttpService.class);
        when(httpService.getSender(SENDER)).thenReturn(sender);

        OneBotController controller = mock(OneBotController.class);
        when(controller.register(any())).thenReturn(true);
        OneBotWebsocketService websocketService = mock(OneBotWebsocketService.class);

        directory = new OneBotTargetDirectory(http, httpService, properties);

        ObjectProvider<OneBotTargetDirectory> targets = mock(ObjectProvider.class);
        when(targets.getIfAvailable()).thenReturn(directory);
        manager = new OneBotConnectionManager(properties, controller, websocketService, httpService, state, targets);
    }

    /**
     * 🔴 保存连接之后名单缓存原样留着，挑目标时看到的还是旧号的群和好友。
     */
    @Test
    @DisplayName("⚠️ 换号保存连接后挑目标现取名单, 不再是旧号的")
    void applyClearsCachedTargetList() {
        when(http.getLoginInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));
        when(http.getGroupList(any(), any())).thenReturn(groupRows(10000002L, "旧号的群"));
        when(http.getFriendList(any(), any())).thenReturn(new JSONArray());

        OneBotTargetDirectory.Snapshot before = only();
        assertEquals(10000002L, before.groups().get(0).num(), before.toString());
        assertEquals("旧号的群", before.groups().get(0).name());

        // 换了个 QQ 号登录，实现回的是新号的群
        when(http.getGroupList(any(), any())).thenReturn(groupRows(20000002L, "新号的群"));

        manager.apply("127.0.0.1", 3000, 3001, "new-token", "new-ws-token");

        OneBotTargetDirectory.Snapshot after = only();
        verify(http, times(2)).getGroupList(any(), any());
        assertEquals(20000002L, after.groups().get(0).num(), "名单该是新号的: " + after);
        assertEquals("新号的群", after.groups().get(0).name());
    }

    private OneBotTargetDirectory.Snapshot only() {
        List<OneBotTargetDirectory.Snapshot> snapshots = directory.snapshots(false);
        assertEquals(1, snapshots.size(), "配了一个推送平台就该有一份名单");
        return snapshots.get(0);
    }

    private static JSONArray groupRows(long num, String name) {
        JSONArray rows = new JSONArray();
        JSONObject row = new JSONObject();
        row.put("group_id", num);
        row.put("group_name", name);
        rows.add(row);
        return rows;
    }
}
