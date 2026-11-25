package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantAudienceCacheTest {
    private static final String PLATFORM = "bilibili";
    private static final String ACCOUNT = "member";
    private static final long FIRST = 1L;
    // 与 FIRST 的哈希相同，覆盖主播键冲突时的等候顺序。
    private static final long SECOND = 1L << 32;
    private static final long THIRD = 3L;

    @TempDir Path directory;
    private AssistantQueryService queries;
    private AssistantAskService service;

    @BeforeEach void setup() {
        var clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC);
        var client = mock(AssistantClient.class);
        var question = mock(AssistantClient.Question.class);
        when(client.beginQuestion(eq(PLATFORM), anyLong())).thenReturn(question);
        when(question.settings()).thenReturn(new AssistantProperties().snapshot());
        when(question.budgetStatus())
                .thenReturn(new AssistantClient.BudgetStatus(null, BigDecimal.ZERO, BigDecimal.TEN, null));
        when(question.totalCostYuan()).thenReturn(BigDecimal.ZERO);
        when(question.complete(any(), any())).thenReturn(new AssistantClient.Response(
                "没有这份数据", null, List.of(), new AssistantClient.Usage(0, 0, 0), BigDecimal.ZERO));
        queries = mock(AssistantQueryService.class);
        service = new AssistantAskService(queries, client, new AssistantProperties(), new AssistantLocalStore(directory, clock), clock);
    }

    @AfterEach void close() { service.close(); }

    // 同时问几位主播时，并行读档建表可能撑满内存，重启会打断直播录制。
    @Test void differentStreamersBuildAudienceTablesInTurn() throws Exception {
        var firstReadStarted = new CountDownLatch(1);
        var releaseFirstRead = new CountDownLatch(1);
        var secondQuestionStarted = new CountDownLatch(1);
        var secondReadStarted = new CountDownLatch(1);
        when(queries.localAudience(PLATFORM, FIRST, List.of())).thenAnswer(invocation -> {
            firstReadStarted.countDown();
            if (!releaseFirstRead.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("第一张表的读取未获准继续");
            return Map.of();
        });
        when(queries.localAudience(PLATFORM, SECOND, List.of())).thenAnswer(invocation -> {
            secondReadStarted.countDown();
            return Map.of();
        });
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> ask(FIRST));
            assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS));
            var second = callers.submit(() -> {
                secondQuestionStarted.countDown();
                ask(SECOND);
            });
            assertTrue(secondQuestionStarted.await(5, TimeUnit.SECONDS));
            boolean readsOverlap = secondReadStarted.await(5, TimeUnit.SECONDS);
            releaseFirstRead.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertFalse(readsOverlap, "第一张表仍在读取时，第二张表已经开始读取");
            verify(queries).localAudience(PLATFORM, SECOND, List.of());
        } finally {
            releaseFirstRead.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // 十分钟内轮流问几位主播时，旧表占着内存，可能让程序重启并打断直播录制。
    @Test void thirdAudienceTableEvictsTheFirstBuilt() {
        ask(FIRST);
        ask(SECOND);
        ask(FIRST);
        ask(THIRD);

        ask(SECOND);
        ask(THIRD);
        verify(queries).localAudience(PLATFORM, SECOND, List.of());
        verify(queries).localAudience(PLATFORM, THIRD, List.of());
        ask(FIRST);
        verify(queries, times(2)).localAudience(PLATFORM, FIRST, List.of());
    }

    private void ask(long uid) {
        var result = service.ask(PLATFORM, uid, ACCOUNT, JSONObject.of("question", "观众 991234560 的表现怎样"));
        assertTrue(result.getBooleanValue("answered"), result::toJSONString);
    }
}
