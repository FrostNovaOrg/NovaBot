package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuntimeStateOperationAuditTest {
    @TempDir Path directory;

    @Test @DisplayName("清空订阅后无法定位主播与群：记录真实主播平台及群通道代码，不把订阅类型当通道种类")
    void subscriptionRemovalIdentifiesStreamerAndChannel() throws Exception {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        var state = new NovaStateStore(properties);
        var subscriptions = new AtSubscriptionService(state);
        subscriptions.subscribe("qq-onebot", 456L, 123L, "live", 789L);
        var source = mock(AbstractDataSource.class);
        when(source.getAllUsers()).thenReturn(List.of());
        var quiet = new SessionQuietHoursService(state);
        var controller = new RuntimeStateController(mock(CommandDispatcher.class), new CommandSettingsService(state),
                subscriptions, state, source, new RevenueVisibilityService(state), mock(LiveDataService.class),
                new StreamerNames(new LiveSessionArchive(properties)), quiet, new PushGate(properties, quiet));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConfigUiOperationAdvice())
                .addFilters(new ConfigUiOperationFilter(new ConfigUiOperationStore(properties))).build();
        mvc.perform(post("/config/api/state/subscription")
                .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"qq-onebot\",\"num\":456,\"streamerUid\":123,\"type\":\"live\"}"))
                .andExpect(jsonPath("$.success").value(true));
        assertTrue(subscriptions.all().isEmpty());
        try (var files = Files.list(directory.resolve("console-audit"))) {
            var lines = Files.readAllLines(files.findFirst().orElseThrow());
            assertEquals(1, lines.size());
            var row = JSONObject.parseObject(lines.get(0));
            assertEquals("SUBSCRIPTION_REMOVE", row.getString("action"));
            var target = row.getJSONObject("target");
            assertEquals(JSONObject.of("platform", "bilibili", "uid", "123"), target.getJSONArray("streamers").getJSONObject(0));
            assertEquals(JSONObject.of("platform", "qq-onebot", "type", "1", "num", "456"), target.getJSONArray("channels").getJSONObject(0));
            assertEquals("live", target.getString("subscriptionType"));
        }
    }
}
