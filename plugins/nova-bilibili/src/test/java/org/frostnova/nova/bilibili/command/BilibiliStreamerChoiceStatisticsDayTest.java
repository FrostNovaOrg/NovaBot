package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("选主播的统计日")
class BilibiliStreamerChoiceStatisticsDayTest {
    @ParameterizedTest(name = "{0}，{1} 来源，今天播过={4}")
    @CsvSource({
            "凌晨三点, archive, 2026-08-09T19:00:00Z, 2026-08-09T15:00:00Z, true",
            "凌晨三点, end, 2026-08-09T19:00:00Z, 2026-08-09T15:00:00Z, true",
            "早上七点, archive, 2026-08-09T23:00:00Z, 2026-08-09T21:00:00Z, false",
            "早上七点, end, 2026-08-09T23:00:00Z, 2026-08-09T21:00:00Z, false"
    })
    @DisplayName("凌晨问昨晚主播仍在今天播过，六点后凌晨已下播主播退出该档")
    void ranksByStatisticsDay(String name, String source, String now, String start, boolean playedToday) {
        long started = Instant.parse(start).toEpochMilli();
        long ended = started + 30 * 60_000L;
        LiveSession session = new LiveSession("bilibili", 1001L, "主播甲", 2001L,
                started, ended, 1800, Map.of(), Map.of());
        LiveSessionArchive archive = mock(LiveSessionArchive.class);
        when(archive.find(anyLong(), anyLong())).thenAnswer(invocation ->
                "archive".equals(source) && started >= invocation.<Long>getArgument(0)
                        && started < invocation.<Long>getArgument(1) ? List.of(session) : List.of());
        LiveDataService live = mock(LiveDataService.class);
        when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        when(live.getLiveEndTime(anyString(), anyLong()))
                .thenReturn("end".equals(source) ? Optional.of(ended) : Optional.empty());
        PushUser user = new PushUser();
        user.setUid(1001L);
        BilibiliStreamerChoice choice = new BilibiliStreamerChoice(live, archive,
                Clock.fixed(Instant.parse(now), ZoneId.of("America/Los_Angeles")));

        BilibiliStreamerChoice.Ranked ranked = choice.rank(List.of(user));
        assertEquals(playedToday ? List.of("· 今天播过") : List.of(""), ranked.labels());
        assertEquals(playedToday ? 1 : 0, ranked.tiered());
    }
}
