package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * @全体成员 只占真发出去的那一次
 * <p>
 * 推送接口连不上的时候，开播提醒一条都出不去，当天的次数却被扣掉。
 * 等接口恢复，后面的开播提醒就不再 @ 全体。本组把这件事钉住：
 * 没发出去的退回，发出去的照旧占一次。
 */
@DisplayName("发出去才占 @全体成员 的次数")
class AtAllSendQuotaTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 88001001L;

    @Test
    @DisplayName("连不上时发出的不算次数，恢复后下一条仍带着 @全体成员")
    void outageDoesNotUseUpTheDay() {
        NovaCoreProperties properties = new NovaCoreProperties();
        // 三次失败就该把当天的次数用完；不退的话，第四条会被摘掉
        properties.getPush().setAtAllDailyLimit(3);

        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, properties);
        doThrow(new IllegalStateException("连接被拒绝")).when(http).postJson(anyString(), any(), any());

        for (int i = 0; i < 3; i++) {
            JSONObject failed = wired.sender.sendNow(atAll());
            assertNotEquals(0, failed.getInteger("code"), "这条不该送达到");
        }

        assertEquals(0, wired.quota.used(PLATFORM, GROUP), "三次都没发出去，该群已用次数应仍是 0");
        assertEquals(0, wired.quota.usedByBot(PLATFORM), "三次都没发出去，各群共用的次数也应仍是 0");

        doAnswer(invocation -> {
            Map<String, Object> params = invocation.getArgument(2);
            return new JSONObject().fluentPut("code", 0).fluentPut("id", "m4").fluentPut("content", params.get("content"));
        }).when(http).postJson(anyString(), any(), any());

        JSONObject fourth = wired.sender.sendNow(atAll());

        assertEquals(0, fourth.getInteger("code"));
        assertTrue(String.valueOf(fourth.getString("content")).contains("{at=all}"),
                "恢复后这一条应仍带着 @全体成员 发出去，实际: " + fourth.getString("content"));
        assertEquals(1, wired.quota.used(PLATFORM, GROUP), "只有发出去的这一次才计入该群");
        assertEquals(1, wired.quota.usedByBot(PLATFORM), "只有发出去的这一次才计入共用次数");
    }

    @Test
    @DisplayName("被拦下、没发出去的那条不占次数")
    void cancelledMessageDoesNotUseTheQuota() {
        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, new NovaCoreProperties());
        Message message = atAll();
        message.addOnBeforeSendInterceptor(ignored -> false);

        assertNull(wired.sender.sendNow(message));
        assertEquals(0, wired.quota.used(PLATFORM, GROUP), "被拦下的这条没发出去，该群已用次数应不变");
        assertEquals(0, wired.quota.usedByBot(PLATFORM));
    }

    @Test
    @DisplayName("发送过程中出错、没发出去的那条不占次数")
    void errorDuringSendDoesNotUseTheQuota() {
        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, new NovaCoreProperties());
        Message message = atAll();
        message.addOnBeforeSendInterceptor(ignored -> {
            throw new IllegalStateException("发送中断");
        });

        assertThrows(IllegalStateException.class, () -> wired.sender.sendNow(message));
        assertEquals(0, wired.quota.used(PLATFORM, GROUP), "出错没发出去，该群已用次数应不变");
        assertEquals(0, wired.quota.usedByBot(PLATFORM));
    }

    @Test
    @DisplayName("昨天没发出去的那次，不减少今天已经用掉的次数")
    void yesterdayFailureDoesNotReduceToday() {
        LocalDate today = LocalDate.of(2026, 9, 25);
        LocalDate yesterday = today.minusDays(1);
        AtomicReference<LocalDate> day = new AtomicReference<>(yesterday);

        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, new NovaCoreProperties());
        AtomicInteger calls = new AtomicInteger();
        when(http.postJson(anyString(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                // 失败落在第二天，这一天里已经另有一条真发出去
                day.set(today);
                JSONObject sentToday = wired.sender.sendNow(atAll());
                assertEquals(0, sentToday.getInteger("code"), "今天这条应发出去");
                return new JSONObject().fluentPut("code", -1).fluentPut("message", "连不上");
            }
            return new JSONObject().fluentPut("code", 0).fluentPut("id", "m-today");
        });

        try (MockedStatic<LocalDate> clock = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            clock.when(() -> LocalDate.now(any(ZoneId.class))).thenAnswer(invocation -> day.get());

            JSONObject failed = wired.sender.sendNow(atAll());

            assertNotEquals(0, failed.getInteger("code"));
            assertEquals(1, wired.quota.used(PLATFORM, GROUP), "昨天没发出去的那次，不应减少今天的已用次数");
            assertEquals(1, wired.quota.usedByBot(PLATFORM));
        }
    }

    @Test
    @DisplayName("额度用完被摘掉的那条本来就没扣，也不退")
    void strippedForExhaustionIsNotRefunded() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setAtAllDailyLimit(1);

        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, properties);
        when(http.postJson(anyString(), any(), any()))
                .thenReturn(new JSONObject().fluentPut("code", 0).fluentPut("id", "m1"));

        wired.sender.sendNow(atAll());
        Message second = atAll();
        wired.sender.sendNow(second);

        assertFalse(second.getContent().contains("{at=all}"), "用完之后应摘掉 @全体成员");
        assertEquals(1, wired.quota.used(PLATFORM, GROUP), "被摘掉的那条不应把已用次数退回去");
        assertEquals(1, wired.quota.usedByBot(PLATFORM));
    }

    @Test
    @DisplayName("前几次没连上、最后一次送达的，仍然占一次")
    void successAfterRetriesStillCounts() {
        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, new NovaCoreProperties());
        AtomicInteger attempts = new AtomicInteger();
        when(http.postJson(anyString(), any(), any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("连接被拒绝");
            }
            Map<String, Object> params = invocation.getArgument(2);
            return new JSONObject().fluentPut("code", 0).fluentPut("id", "m1").fluentPut("content", params.get("content"));
        });

        JSONObject result = wired.sender.sendNow(atAll());

        assertEquals(0, result.getInteger("code"));
        assertTrue(String.valueOf(result.getString("content")).contains("{at=all}"));
        assertEquals(1, wired.quota.used(PLATFORM, GROUP));
        assertEquals(1, wired.quota.usedByBot(PLATFORM));
    }

    @Test
    @DisplayName("含图消息文字送达时，这次 @全体成员 仍占一次")
    void deliveredTextStillCounts() {
        HttpUtil http = mock(HttpUtil.class);
        Wired wired = wire(http, new NovaCoreProperties());
        when(http.postJson(anyString(), any(), any()))
                .thenReturn(new JSONObject().fluentPut("code", 2).fluentPut("message", "下载文件失败: Not Found"))
                .thenReturn(new JSONObject().fluentPut("code", 0).fluentPut("id", "m2"));

        Message message = Message.create(PLATFORM, PushTargetType.GROUP, GROUP,
                "{at=all} 开播啦 {image_url=https://example.com/cover.jpg}").get(0);
        JSONObject result = wired.sender.sendNow(message);

        assertNotEquals(0, result.getInteger("code"), "原消息的响应仍是失败");
        assertEquals(1, wired.quota.used(PLATFORM, GROUP), "文字送到了，这次应计入");
        assertEquals(1, wired.quota.usedByBot(PLATFORM));
    }

    private Message atAll() {
        return Message.create(PLATFORM, PushTargetType.GROUP, GROUP, "{at=all} 开播啦").get(0);
    }

    private Wired wire(HttpUtil http, NovaCoreProperties properties) {
        Sender target = new Sender();
        target.setName(PLATFORM);
        target.setUrl("http://127.0.0.1:7827/onebot/send");
        target.setDelay(0);

        NovaSenderService senderService = mock(NovaSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

        AtAllQuotaService quota = new AtAllQuotaService(properties);
        NovaMessageSender sender = new NovaMessageSender(http, senderService,
                new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties),
                TimelineWriter.NONE, quota, resolvers,
                new FirstPushTipService(new org.frostnova.nova.core.service.NovaStateStore(properties)));
        return new Wired(sender, quota);
    }

    private record Wired(NovaMessageSender sender, AtAllQuotaService quota) {
    }
}
