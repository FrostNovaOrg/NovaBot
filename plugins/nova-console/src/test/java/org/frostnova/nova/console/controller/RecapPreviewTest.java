package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.console.RecapPreviewConsolePageProvider;
import org.frostnova.nova.core.analytics.SessionRecap.*;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecapPreviewTest {
    // All identities and nicknames are synthetic.
    static final long HOST = 910000001L, OTHER = 910000002L, VIEWER = 920000001L;
    static final long START = Instant.parse("2026-10-01T01:00:00Z").toEpochMilli(), DAY = 86_400_000L;
    @TempDir Path dir;
    NovaCoreProperties config;
    AssistantProperties privacy;
    LiveSessionArchive archive;
    AudienceLedgerService ledger;
    SessionRecapService recaps;
    RecapPreviewService service;

    @BeforeEach void setup() throws Exception {
        config = new NovaCoreProperties();
        config.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        config.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        privacy = new AssistantProperties();
        archive = new LiveSessionArchive(config);
        ledger = mock(AudienceLedgerService.class);
        recaps = mock(SessionRecapService.class);
        when(recaps.calculateBatch(anyString(), anyLong(), anyList())).thenAnswer(call ->
                ((List<Long>) call.getArgument(2)).stream().map(this::result).toList());
        service = new RecapPreviewService(archive, ledger, recaps, privacy);
    }

    void session(long uid, long start, String name, boolean sealed) throws Exception {
        var row = JSONObject.of("platform", "test", "uid", uid, "uname", name, "startTime", start,
                "endTime", start + 3_600_000, "durationSeconds", 3600, "endReason", "NORMAL",
                "userSets", Map.of("enter_users", List.of(VIEWER)), "anonymousCounts", Map.of(),
                "metrics", Map.of("gift_value", 269));
        Files.writeString(dir.resolve("sessions.jsonl"), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (sealed) {
            Path detail = Files.createDirectories(dir.resolve("details/test-" + uid + "-" + start));
            Files.writeString(detail.resolve("detail.json"), JSONObject.of("rankings", Map.of("gift_users", List.of(
                    Map.of("uid", VIEWER, "name", "观众甲", "score", 269)))).toJSONString());
        }
    }

    Result result(long start) {
        var streamer = new Streamer("test", HOST);
        var sample = new Sample(25, START - DAY * 25);
        return new Result(List.of(
                new Item("duration", streamer, start, Map.of("start", start, "end", start + 3_600_000,
                        "durationMinutes", 60), sample, null, List.of(), List.of()),
                new Item("gift_rank", streamer, start, Map.of("gift", 269), sample, null, List.of(), List.of()),
                new Item("top_gifters", streamer, start, Map.of("gift", List.of(269)), sample, null, List.of(VIEWER), List.of())),
                List.of(new Item("regulars_absent", streamer, start, Map.of("regulars", 30, "absent", 16), sample,
                        "strong", List.of(), List.of())),
                new Presentation(start, 25, "complete", start - DAY, Map.of(VIEWER, "观众甲"), Set.of()));
    }

    MockMvc mvc() {
        var platforms = mock(DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("test"));
        var ui = mock(ConfigUiController.class, CALLS_REAL_METHODS);
        ObjectProvider<ConsolePageProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(call -> Stream.of(new RecapPreviewConsolePageProvider()));
        ReflectionTestUtils.setField(ui, "pageProviders", providers);
        return MockMvcBuilders.standaloneSetup(new RecapPreviewController(service), ui)
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), config, platforms)).build();
    }

    @Test @DisplayName("成员即使分到主播也不能读两个接口或看到导航，避免泄露昵称和金额")
    void membersCannotReadOrNavigate() throws Exception {
        var member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "合成成员", null, null, null, List.of("test:" + HOST), Instant.EPOCH, null, true);
        var mvc = mvc();
        for (String path : List.of("/recap-preview", "/recap-preview/test/" + HOST))
            mvc.perform(get("/config/api" + path).requestAttr(ConfigUiAccess.ACCOUNT, member))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/config/api/pages").requestAttr(ConfigUiAccess.ACCOUNT, member))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages").isEmpty());
        mvc.perform(get("/config/api/pages").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                .andExpect(jsonPath("$.pages[0].displayName").value("小结样稿（试验）"))
                .andExpect(jsonPath("$.pages[0].slot").value("top"));
        verifyNoInteractions(ledger, recaps);
    }

    @Test @DisplayName("默认响应不含观众全名，显式开启才返回，避免截图泄露昵称")
    void endpointDefaultsToMaskedText() throws Exception {
        session(HOST, START, "合成主播", true);
        var mvc = mvc();
        var admin = ConfigUiAccount.local(Instant.EPOCH);
        String path = "/config/api/recap-preview/test/" + HOST;
        var masked = mvc.perform(get(path).requestAttr(ConfigUiAccess.ACCOUNT, admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(masked.contains("观众甲"));
        assertTrue(masked.contains("观**"));
        assertTrue(masked.contains("269"));
        var original = mvc.perform(get(path).param("showNicknames", "true").requestAttr(ConfigUiAccess.ACCOUNT, admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(original.contains("观众甲"));
        verify(ledger).updateStreamer("test", HOST);
        verify(recaps, times(1)).calculateBatch(anyString(), anyLong(), anyList());
    }

    @Test @DisplayName("五场按时间回放并接续实际说出的规律，避免误判发送频率")
    void replaysLatestFiveWithSpokenHistory() throws Exception {
        for (int i : List.of(5, 1, 3, 0, 4, 2)) session(HOST, START + DAY * i, "合成主播", true);
        var preview = service.preview("test", HOST, false);
        assertTrue(preview.success());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L).stream().map(i -> START + i * DAY).toList(),
                preview.sessions().stream().map(RecapPreviewService.Card::startTime).toList());
        for (int i = 0; i < 5; i++) {
            var card = preview.sessions().get(i);
            boolean spoken = i == 0 || i == 4;
            assertEquals(spoken, card.level2().message().contains("常来的 30 位"));
            assertEquals(!spoken, card.level2().notes().stream().anyMatch(n -> n.contains("3 场内说过")));
            assertFalse(card.level1().message().contains("常来的 30 位"));
        }
        var order = inOrder(ledger, recaps);
        order.verify(ledger).updateStreamer("test", HOST);
        order.verify(recaps).calculateBatch(eq("test"), eq(HOST), anyList());
    }

    @Test @DisplayName("未配置和停用主播都从封存归档取出，避免导入历史后样稿缺人")
    void includesUnconfiguredAndDisabledStreamers() throws Exception {
        Files.writeString(dir.resolve("datasource.json"), "[{\"platform\":\"test\",\"uid\":" + HOST + ",\"enabled\":false}]");
        session(HOST, START, "合成旧名", true);
        session(OTHER, START + DAY * 2, "合成外部主播", true);
        session(HOST, START + DAY, "合成新名", true);
        session(HOST, START + DAY * 3, "未封存", false);
        var list = service.streamers().streamers();
        assertEquals(List.of(OTHER, HOST), list.stream().map(RecapPreviewService.Streamer::uid).toList());
        assertEquals("合成新名", list.get(1).uname());
        assertEquals(2, list.get(1).sessionCount());
        assertEquals(START + DAY, list.get(1).latestStart());
        var realLedger = new AudienceLedgerService(config, privacy);
        var realRecaps = new SessionRecapService(realLedger, archive, new LiveDetailArchive(config), privacy);
        var real = new RecapPreviewService(archive, realLedger, realRecaps, privacy);
        assertTrue(real.preview("test", HOST, false).success());
        assertTrue(real.preview("test", OTHER, false).success());
        assertEquals(2, realLedger.sessions("test", HOST).size());
    }

    @Test @DisplayName("没有变化复用结果，新封存或拒绝名单变化会刷新，避免陈旧样稿")
    void cachesUntilArchiveOrExclusionsChange() throws Exception {
        session(HOST, START, "合成主播", true);
        assertTrue(service.preview("test", HOST, false).success());
        service.preview("test", HOST, true);
        verify(recaps, times(1)).calculateBatch(anyString(), anyLong(), anyList());
        session(HOST, START + DAY, "合成主播", true);
        assertEquals(2, service.preview("test", HOST, false).sessions().size());
        privacy.setExcludedUids(List.of(VIEWER));
        assertFalse(service.preview("test", HOST, true).sessions().get(0).level1().message().contains("观众甲"));
        verify(ledger, times(3)).updateStreamer("test", HOST);
        verify(recaps, times(3)).calculateBatch(anyString(), anyLong(), anyList());
    }

    @Test @DisplayName("重复归档只算一场，续播场次不列出，避免把半场和重复场算进样稿")
    void archiveListUsesFinalSealedSessions() throws Exception {
        session(HOST, START, "合成旧名", true);
        session(HOST, START, "合成新名", true);
        session(HOST, START + DAY, "合成主播", true);
        Files.writeString(dir.resolve("details/test-" + HOST + "-" + (START + DAY) + "/resumed"), "");
        Files.writeString(dir.resolve("sessions.jsonl"), "{broken}\n", StandardOpenOption.APPEND);
        var list = archive.sealedSessions();
        assertEquals(1, list.size());
        assertEquals("合成新名", list.get(0).uname());
        assertEquals(1, service.preview("test", HOST, false).sessions().size());
    }

    @Test @DisplayName("同主播并发共用生成，不同主播排队，避免同时打开翻倍占用内存")
    void coalescesRequestsAndQueuesOtherStreamers() throws Exception {
        session(HOST, START, "合成主播", true);
        session(OTHER, START, "合成主播乙", true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(recaps.calculateBatch(eq("test"), eq(HOST), anyList())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return List.of(result(START));
        });
        var pool = Executors.newFixedThreadPool(3);
        var secondThread = new AtomicReference<Thread>();
        var thirdThread = new AtomicReference<Thread>();
        try {
            var first = pool.submit(() -> service.preview("test", HOST, false));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> { secondThread.set(Thread.currentThread()); return service.preview("test", HOST, true); });
            var third = pool.submit(() -> { thirdThread.set(Thread.currentThread()); return service.preview("test", OTHER, false); });
            awaitWaiting(secondThread); awaitWaiting(thirdThread);
            verify(ledger, never()).updateStreamer("test", OTHER);
            release.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS).success());
            assertTrue(second.get(15, TimeUnit.SECONDS).success());
            assertTrue(third.get(15, TimeUnit.SECONDS).success());
            verify(recaps).calculateBatch(eq("test"), eq(HOST), anyList());
            verify(recaps).calculateBatch(eq("test"), eq(OTHER), anyList());
        } finally {
            release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    static void awaitWaiting(AtomicReference<Thread> thread) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (thread.get() == null || thread.get().getState() != Thread.State.WAITING) {
                if (Thread.currentThread().isInterrupted()) return;
                Thread.yield();
            }
        });
    }

    @Test @DisplayName("失败说明账本或计算哪步没成且能重试，避免堆栈出现在页面")
    void failuresAreReadableAndNotCached() throws Exception {
        session(HOST, START, "合成主播", true);
        doThrow(new IOException("private failure detail")).doNothing().when(ledger).updateStreamer("test", HOST);
        var failed = service.preview("test", HOST, false);
        assertFalse(failed.success()); assertTrue(failed.message().contains("补齐观众账本"));
        assertFalse(failed.message().contains("private"));
        when(recaps.calculateBatch(anyString(), anyLong(), anyList())).thenThrow(new IOException("private"));
        failed = service.preview("test", HOST, false);
        assertFalse(failed.success()); assertTrue(failed.message().contains("计算或整理"));
        doReturn(List.of(result(START))).when(recaps).calculateBatch(anyString(), anyLong(), anyList());
        assertTrue(service.preview("test", HOST, false).success());
    }

    @Test @DisplayName("前端展示正文与注并在重进时恢复打码，避免上次显名状态遗留")
    void frontendFixture() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/recap-preview-fixture.mjs", "小结样稿");
    }
}
