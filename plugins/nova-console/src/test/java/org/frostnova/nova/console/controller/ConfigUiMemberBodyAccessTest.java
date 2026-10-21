package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.datasource.*;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.controller.BilibiliReportLayoutController;
import org.frostnova.nova.report.painter.BilibiliLiveReportPreviewPainter;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigUiMemberBodyAccessTest {
    private final ConfigUiAccount member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);
    private MockMvc mvc;
    private DataSourceService lookup;
    private AtSubscriptionService subscriptions;
    private BilibiliLiveReportPreviewPainter painter;

    @BeforeEach void setup() {
        var registry = mock(DataSourceServiceRegistry.class);
        lookup = mock(DataSourceService.class);
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(lookup));
        when(lookup.completeStreamerWithFans(any())).thenAnswer(call -> {
            PushUser user = call.getArgument(0); user.setUname("Streamer");
            return new DataSourceService.StreamerWithFans(user, 10L);
        });
        subscriptions = mock(AtSubscriptionService.class);
        var runtime = new RuntimeStateController(null, null, subscriptions, mock(NovaStateStore.class),
                mock(AbstractDataSource.class), null, null, null, null, null);
        painter = mock(BilibiliLiveReportPreviewPainter.class);
        when(painter.render(any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        mvc = MockMvcBuilders.standaloneSetup(new StreamerLookupController(registry), runtime,
                new BilibiliReportLayoutController(painter, mock(RevenueVisibilityService.class)))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class)))
                .setControllerAdvice(new ConfigUiMemberBodyAdvice()).build();
    }

    // 自家 uid 能查询，非自家 uid、直播间链接和其他平台不能触发对外查人。
    @Test void lookupUsesOnlyAssignedNumericUid() throws Exception {
        send("streamer/lookup", JSONObject.of("platform", "bilibili", "uid", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uid").value(1));
        clearInvocations(lookup);
        for (String uid : List.of("2", "3", "https://space.bilibili.com/1", "https://live.bilibili.com/1"))
            send("streamer/lookup", JSONObject.of("platform", "bilibili", "uid", uid)).andExpect(status().isNotFound());
        send("streamer/lookup", JSONObject.of("platform", "other", "uid", "1")).andExpect(status().isNotFound());
        verifyNoInteractions(lookup);
    }

    // 已授权 uid 查不到时，不能转而按同号直播间查出另一位主播。
    @Test void memberLookupNeverFallsBackToRoomNumber() throws Exception {
        doAnswer(call -> new DataSourceService.StreamerWithFans(call.getArgument(0), null))
                .when(lookup).completeStreamerWithFans(any());
        when(lookup.lookupByRoomIdWithFans(1L)).thenReturn(new DataSourceService.StreamerWithFans(new PushUser(), null));
        send("streamer/lookup", JSONObject.of("platform", "bilibili", "uid", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        verify(lookup, never()).lookupByRoomIdWithFans(any());
        mvc.perform(post("/config/api/streamer/lookup").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType("application/json").content("{\"platform\":\"bilibili\",\"uid\":\"1\"}"));
        verify(lookup).lookupByRoomIdWithFans(1L);
    }

    // 会话平台不是主播平台；只按名下 uid 判订阅，错误编号不能删别人的订阅。
    @Test void subscriptionChecksStreamerUidInsteadOfSessionPlatform() throws Exception {
        send("state/subscription", JSONObject.of("platform", "qq-onebot", "num", 1001,
                "streamerUid", 1, "type", "live")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(subscriptions).clear("qq-onebot", 1001L, 1L, "live");
        clearInvocations(subscriptions);
        for (Object uid : List.of(2, 3, 1.5, true, "bad"))
            send("state/subscription", JSONObject.of("platform", "qq-onebot", "num", 1001,
                    "streamerUid", uid, "type", "live")).andExpect(status().isNotFound());
        verifyNoInteractions(subscriptions);
    }

    // 带主播的报告只为本人画，纯版式预览仍能画，缺一半身份时拒绝渲染。
    @Test void previewRequiresCompleteOwnedIdentityOrNoIdentity() throws Exception {
        for (JSONObject body : List.of(new JSONObject(), JSONObject.of("streamerPlatform", "bilibili", "streamerUid", 1)))
            send("report/preview", body).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
        clearInvocations(painter);
        for (JSONObject body : List.of(JSONObject.of("streamerPlatform", "bilibili", "streamerUid", 2),
                JSONObject.of("streamerPlatform", "bilibili", "streamerUid", 3), JSONObject.of("streamerUid", 1),
                JSONObject.of("streamerPlatform", "bilibili")))
            send("report/preview", body).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("没有这位主播"));
        verifyNoInteractions(painter);
        mvc.perform(post("/config/api/report/preview").requestAttr(ConfigUiAccess.ACCOUNT, member))
                .andExpect(status().isOk());
    }

    private ResultActions send(String path, JSONObject body) throws Exception {
        return mvc.perform(post("/config/api/" + path).requestAttr(ConfigUiAccess.ACCOUNT, member)
                .contentType("application/json").content(body.toJSONString()));
    }
}
