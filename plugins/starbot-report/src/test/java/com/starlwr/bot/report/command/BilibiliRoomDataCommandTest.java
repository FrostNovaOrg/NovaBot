package com.starlwr.bot.report.command;

import com.starlwr.bot.bilibili.command.BilibiliStreamerChoice;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.report.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「直播间数据」本场脚注：时长格式化结果为空时整句不出
 */
@DisplayName("直播间本场数据脚注")
class BilibiliRoomDataCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long FRIEND = 20001L;

    private static final Long SENDER = 40001L;

    @Test
    @DisplayName("不在播且起止同一秒：脚注整句不出")
    void footnoteIsNullWhenOffAndStartEqualsEnd() {
        long at = 1_700_000_000_000L;
        Fixture fixture = new Fixture(false, at, at);

        fixture.command.execute(context());

        assertNull(fixture.capturedFootnote());
    }

    @Test
    @DisplayName("在播但开播时刻在未来：脚注整句不出")
    void footnoteIsNullWhenLivingAndStartIsInTheFuture() {
        long start = System.currentTimeMillis() + 3_600_000L;
        Fixture fixture = new Fixture(true, start, null);

        fixture.command.execute(context());

        assertNull(fixture.capturedFootnote());
    }

    @Test
    @DisplayName("不在播、结束晚开播 90 秒：脚注为现行时长格式")
    void footnoteShowsNinetySecondsWhenEndedAfterStart() {
        long start = 1_700_000_000_000L;
        Fixture fixture = new Fixture(false, start, start + 90_000L);

        fixture.command.execute(context());

        assertEquals("直播时长 1 分 30 秒", fixture.capturedFootnote());
    }

    @Test
    @DisplayName("盲盒盈亏三分：零持平、正盈利、负亏损")
    void boxCardShowsBreakEvenProfitAndLoss() {
        assertAll(
                () -> {
                    String label = boxLabel(0);
                    assertEquals("盲盒 · 持平", label);
                    assertFalse(label.contains("¥"), label);
                },
                () -> {
                    String label = boxLabel(150);
                    assertTrue(label.contains("盈利"), label);
                    assertTrue(label.contains("150"), label);
                },
                () -> {
                    String label = boxLabel(-150);
                    assertTrue(label.contains("亏损"), label);
                    assertTrue(label.contains("150"), label);
                }
        );
    }

    private static String boxLabel(double boxProfit) {
        Fixture fixture = new Fixture(false, 1_700_000_000_000L, 1_700_000_090_000L, boxProfit);
        fixture.command.execute(context());
        return fixture.capturedBoxLabel();
    }

    private static CommandContext context() {
        return new CommandContext(PLATFORM, PushTargetType.FRIEND, FRIEND, SENDER,
                "直播间数据", List.of(), "直播间数据");
    }

    private static PushUser streamer() {
        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setUname("主播甲");
        user.setPlatform("bilibili");
        user.setEnabled(true);

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.FRIEND);
        target.setNum(FRIEND);
        target.setEnabled(true);
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 一次用例用的零件：在播状态与起止时刻按用例不同。
     * 卡片至少一条，否则走「还没有本场数据」文字路，画手根本不会被叫到。
     */
    private static class Fixture {
        private final BilibiliDataQueryPainter painter;

        private final BilibiliRoomLiveDataCommand command;

        Fixture(boolean living, long start, Long end) {
            this(living, start, end, 10.0);
        }

        Fixture(boolean living, long start, Long end, double boxProfit) {
            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer()));

            LiveDataService liveDataService = mock(LiveDataService.class);
            when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(living));
            when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.of(start));
            when(liveDataService.getLiveEndTime(anyString(), anyLong()))
                    .thenReturn(end == null ? Optional.empty() : Optional.of(end));
            when(liveDataService.getLiveMetric(anyString(), anyLong(), anyString())).thenReturn(10.0);
            when(liveDataService.getLiveMetric(anyString(), anyLong(), eq(BilibiliLiveMetric.BOX_PROFIT)))
                    .thenReturn(boxProfit);
            when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(1);

            painter = mock(BilibiliDataQueryPainter.class);
            when(painter.paintCards(any(), any(), nullable(String.class))).thenReturn(Optional.of("QUJD"));

            RevenueVisibilityService revenueVisibility = mock(RevenueVisibilityService.class);
            when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);

            command = new BilibiliRoomLiveDataCommand(dataSource, mock(BilibiliStreamerChoice.class),
                    liveDataService, painter, revenueVisibility);
        }

        String capturedFootnote() {
            ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
            verify(painter).paintCards(any(), any(), footnote.capture());
            return footnote.getValue();
        }

        String capturedBoxLabel() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BilibiliDataQueryPainter.DataCard>> cards =
                    ArgumentCaptor.forClass(List.class);
            verify(painter).paintCards(any(), cards.capture(), nullable(String.class));
            for (BilibiliDataQueryPainter.DataCard card : cards.getValue()) {
                if (card.label().startsWith("盲盒")) {
                    return card.label();
                }
            }
            throw new AssertionError("no box card");
        }
    }
}
