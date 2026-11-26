package org.frostnova.nova.adapter.outbox;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Streamer;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.model.Message.PushKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.*;
import java.util.List;

import static org.frostnova.nova.adapter.outbox.OutboxRecord.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OutboxRecordControllerTest {
    private static final String API = "/config/api/outbox/";
    private final Streamer owner = new Streamer("live", 101L, "青山");
    private final Streamer other = new Streamer("other", 101L, "同名");
    private final ConfigUiAccount admin = ConfigUiAccount.local(Instant.EPOCH);
    private final ConfigUiAccount member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "成员", null, null, null, List.of("live:101"), Instant.EPOCH, null, true);
    private final ConfigUiOperationStore audit = mock(ConfigUiOperationStore.class);
    private Instant now = Instant.parse("2026-01-12T10:00:00Z");
    @TempDir Path directory;
    private OutboxService service;
    private long conversation;
    private MockMvc mvc;

    @BeforeEach void start() {
        var database = new OutboxDatabase(directory);
        conversation = new OutboxConversations(database).create("<日间>🌻", "", 0, 0).id();
        var clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenAnswer(call -> now);
        when(clock.millis()).thenAnswer(call -> now.toEpochMilli());
        service = new OutboxService(database, new OutboxSettings(), clock, event -> {});
        var controller = new OutboxRecordController(service);
        var platforms = mock(DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("live", "other"));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ConfigUiOperationFilter(audit, List.of(controller)))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms)).build();
    }

    private long put(Streamer streamer, String text) {
        return service.enqueue(conversation, streamer, PushKind.LIVE_ON, text).get(0).id();
    }

    private ResultActions read(String path, ConfigUiAccount who) throws Exception {
        return mvc.perform(get(API + path).requestAttr(ConfigUiAccess.ACCOUNT, who));
    }

    private ResultActions act(long id, String action, ConfigUiAccount who) throws Exception {
        return mvc.perform(post(API + "records/" + id + "/" + action).requestAttr(ConfigUiAccess.ACCOUNT, who));
    }

    @Test @DisplayName("成员翻页时看到别人的通知，或最新通知被挤到旧记录后面")
    void newestVisibleFirstWithFilterAndCursor() throws Exception {
        long first = put(owner, "<早班> \"开始\"");
        long second = put(owner, "午班");
        long hidden = put(other, "别人的正文");
        service.claim(put(owner, "正在发"), "设备");
        read("records?status=PENDING&limit=1", member).andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1)).andExpect(jsonPath("$.records[0].id").value(second))
                .andExpect(jsonPath("$.hasMore").value(true));
        read("records?status=PENDING&beforeId=" + second + "&limit=1", member)
                .andExpect(jsonPath("$.records[0].segments[0].value").value("<早班> \"开始\""))
                .andExpect(jsonPath("$.records[0].conversationName").value("<日间>🌻"))
                .andExpect(jsonPath("$.records[0].id").value(first)).andExpect(jsonPath("$.hasMore").value(false));
        read("records?status=PENDING", admin).andExpect(jsonPath("$.records[0].id").value(hidden))
                .andExpect(jsonPath("$.records.length()").value(3));
        read("summary", member).andExpect(jsonPath("$.counts.PENDING").value(2))
                .andExpect(jsonPath("$.counts.SENDING").value(1));
        read("summary", admin).andExpect(jsonPath("$.counts.PENDING").value(3));
    }

    @Test @DisplayName("直接打开图片地址能看到别人主播的原图，或能判断那条私有记录是否存在")
    void imageConcealsHiddenRecords() throws Exception {
        long mine = put(owner, "图片{image_base64=AQID}");
        long hidden = put(other, "图片{image_base64=BAUG}");
        String path = "records/%d/images/1";
        read(path.formatted(mine), member).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
        read(path.formatted(hidden), admin).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{4, 5, 6}));
        var expected = read(path.formatted(999L), member).andExpect(status().isNotFound()).andReturn().getResponse();
        var actual = read(path.formatted(hidden), member).andExpect(status().isNotFound()).andReturn().getResponse();
        assertArrayEquals(expected.getContentAsByteArray(), actual.getContentAsByteArray());
        assertEquals(expected.getContentType(), actual.getContentType());
        read("records/" + mine + "/images/0", member).andExpect(status().isNotFound());
    }

    @Test @DisplayName("跨天的异常结果漏计、已经超过一天的还在概况里，或成员数到了别人的异常")
    void summaryUsesOutcomeTimeAndOwnership() throws Exception {
        long recent = put(owner, "重新处理");
        long old = put(owner, "过期");
        var claimed = service.claim(put(owner, "昨天超时"), "设备");
        service.claim(recent, "设备");
        service.receipt(recent, 1, FAILED, "昨天失败");
        service.receipt(service.claim(put(owner, "旧失败"), "设备").id(), 1, FAILED, "");
        now = now.plus(Duration.ofHours(25));
        service.requeue(recent);
        service.receipt(service.claim(recent, "设备").id(), 2, FAILED, "今天失败");
        put(owner, "刚过期");
        service.claim(put(owner, "刚超时"), "设备");
        service.receipt(service.claim(put(other, "别人失败"), "设备").id(), 1, FAILED, "");
        now = now.plus(Duration.ofMinutes(15));
        read("summary", member).andExpect(jsonPath("$.counts.FAILED").value(1))
                .andExpect(jsonPath("$.counts.EXPIRED").value(1)).andExpect(jsonPath("$.counts.UNKNOWN").value(1))
                .andExpect(jsonPath("$.counts.SENDING").value(0)).andExpect(jsonPath("$.counts.PENDING").value(0));
        read("summary", admin).andExpect(jsonPath("$.counts.FAILED").value(2));
        assertEquals(old, service.list(EXPIRED, 0, 1, owner::equals).get(0).id());
        assertEquals(claimed.id(), service.list(UNKNOWN, 0, 1, owner::equals).get(0).id());
    }

    @Test @DisplayName("成员改动投递状态、已发通知又发一次，或管理员的处理动作无法追查")
    void guardedActionsAndAudit() throws Exception {
        long id = put(owner, "私有通知正文");
        var claim = service.claim(id, "设备");
        service.receipt(id, claim.claimNumber(), FAILED, "私有结果说明");
        now = now.plusSeconds(60);
        act(id, "requeue", member).andExpect(status().isForbidden());
        act(id, "requeue", admin).andExpect(status().isOk());
        assertEquals(now.plusSeconds(600), service.list(PENDING, 0, 1, owner::equals).get(0).expiresAt());
        act(id, "void", member).andExpect(status().isForbidden());
        act(id, "void", admin).andExpect(status().isOk());
        assertTrue(service.list(PENDING, 0, 100, owner::equals).isEmpty());
        assertThrows(IllegalStateException.class, () -> service.claim(id, "设备"));
        long sent = put(owner, "已经送到");
        service.receipt(sent, service.claim(sent, "设备").claimNumber(), SENT, "");
        act(sent, "requeue", admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("已发")));
        var rows = ArgumentCaptor.forClass(JSONObject.class);
        verify(audit, times(5)).append(rows.capture());
        assertEquals(List.of("OUTBOX_REQUEUE", "OUTBOX_REQUEUE", "OUTBOX_VOID", "OUTBOX_VOID", "OUTBOX_REQUEUE"),
                rows.getAllValues().stream().map(row -> row.getString("action")).toList());
        assertEquals(List.of("REJECTED", "SUCCESS", "REJECTED", "SUCCESS", "REJECTED"),
                rows.getAllValues().stream().map(row -> row.getString("result")).toList());
        assertFalse(rows.getAllValues().toString().contains("私有"));
    }
}
