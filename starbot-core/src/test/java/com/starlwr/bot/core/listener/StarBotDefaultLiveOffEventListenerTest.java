package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.analytics.LiveHighlightFinder;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.SeriesPeak;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import com.starlwr.bot.core.service.LiveInterventionTracker;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.LiveSessionArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 正常下播归档主路
 * <p>
 * {@code LiveOffArchiveTest}（真依赖）量的是<b>归档内容</b>算得对不对；
 * 本台架把五个依赖全换成假的，量的是监听器这一层<b>主路的编排</b>：
 * 下播事实（状态、终点、并入累计）按什么顺序落、归档在什么条件下跳过。
 * 两把尺互补：真依赖那边换掉实现时这里不红，这里编排变动时那边也不红。
 */
@DisplayName("下播监听：正常下播主路")
class StarBotDefaultLiveOffEventListenerTest {
    private static final String PLATFORM = "bilibili";
    private static final long UID = 42L;
    private static final long ROOM_ID = 1001L;
    private static final long START = 1_700_000_000_000L;
    private static final long END = START + 3_600_000L;

    private LiveDataService liveDataService;
    private LiveSessionArchive archive;
    private LiveInterventionTracker interventionTracker;
    private LiveRoomInfoHistory roomInfoHistory;
    private LiveDetailArchive details;
    private StarBotDefaultLiveOffEventListener listener;

    @BeforeEach
    void setUp() {
        liveDataService = mock(LiveDataService.class);
        archive = mock(LiveSessionArchive.class);
        interventionTracker = mock(LiveInterventionTracker.class);
        roomInfoHistory = mock(LiveRoomInfoHistory.class);
        details = mock(LiveDetailArchive.class);
        listener = new StarBotDefaultLiveOffEventListener(
                liveDataService, archive, interventionTracker, roomInfoHistory, details);
    }

    private static LiveOffEvent liveOffAt(long at) {
        return new LiveOffEvent(PLATFORM, new LiveStreamerInfo(UID, "主播甲", ROOM_ID), Instant.ofEpochMilli(at));
    }

    /**
     * 阳：正常下播把三件事实与两份归档都落全
     * <p>
     * 状态翻掉、终点写下、本场并入累计，这三件是「下播发生了」的事实；
     * 场次归档与明细留档各留一份，起止时长同一套。归档里的字段逐项对值，
     * 换算错位（毫秒当秒、起止倒置）在这一步就会现形。
     */
    @Test
    @DisplayName("阳：状态/终点/并入三件事实落全，场次与明细两份归档对值")
    void normalLiveOffRecordsFactsAndArchivesBothCopies() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);

        listener.onLiveOffEvent(liveOffAt(END));

        verify(liveDataService).setLiveStatus(PLATFORM, UID, false);
        verify(liveDataService).setLiveEndTime(PLATFORM, UID, END);
        verify(liveDataService).mergeLiveDataIntoTotal(PLATFORM, UID);

        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        LiveSession session = sessions.getValue();
        assertEquals(PLATFORM, session.platform());
        assertEquals(UID, session.uid());
        assertEquals("主播甲", session.uname());
        assertEquals(ROOM_ID, session.roomId());
        assertEquals(START, session.startTime());
        assertEquals(END, session.endTime());
        assertEquals(3600, session.durationSeconds());
        assertEquals(LiveEndReason.NORMAL, session.endReason());

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        LiveDetail detail = detailCaptor.getValue();
        assertEquals(LiveDetail.VERSION, detail.version());
        assertEquals(PLATFORM, detail.platform());
        assertEquals(UID, detail.uid());
        assertEquals(START, detail.startTime());
        assertEquals(END, detail.endTime());
        assertEquals(3600, detail.durationSeconds());
    }

    /**
     * 阴：没有开播时刻就不归档，但下播事实照落
     * <p>
     * 程序在直播中途才启动时算不出时长，归档只会污染统计——场次与明细两份都不写。
     * 但「已经下播了」是独立的事实：状态照翻、终点照记、已采到的那点数据照并入，
     * 否则这一场的数据要等到下一次开播才会被清掉，期间报告读到的是僵尸在播状态。
     */
    @Test
    @DisplayName("阴：无开播时刻不归档，状态/终点/并入照落")
    void noStartTimeSkipsArchivesButStillRecordsTheLiveOff() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.empty());

        listener.onLiveOffEvent(liveOffAt(END));

        verify(archive, never()).append(any());
        verify(details, never()).store(any());

        verify(liveDataService).setLiveStatus(PLATFORM, UID, false);
        verify(liveDataService).setLiveEndTime(PLATFORM, UID, END);
        verify(liveDataService).mergeLiveDataIntoTotal(PLATFORM, UID);
    }

    /**
     * 摘下监听器落下的全部 WARN 原文（格式化后），INFO 不进这张单子
     */
    private static List<String> captureWarns(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StarBotDefaultLiveOffEventListener.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 分支一：非正常下播要喊出来
     * <p>
     * 被平台切断的那一场，时长不代表正常水平——场次照归档（统计口径仍要这一场），
     * 但告警必须落，且那句话里带着成因，读日志的人不必再猜这一场为什么短。
     */
    @Test
    @DisplayName("分支①：endReason≠NORMAL 落一条带成因的 WARN，场次照归档")
    void cutOffLiveOffWarnsWithReasonAndStillArchives() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.CUT_OFF);

        List<String> warns = captureWarns(() -> listener.onLiveOffEvent(liveOffAt(END)));

        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        assertEquals(LiveEndReason.CUT_OFF, sessions.getValue().endReason());

        assertEquals(1, warns.size(), "恰一条 WARN，实际: " + warns);
        assertTrue(warns.get(0).contains("被平台切断"), "告警带成因，实际: " + warns.get(0));
        assertTrue(warns.get(0).contains("不代表正常水平"), "告警说明时长失真，实际: " + warns.get(0));
    }

    /**
     * 分支二：停机与断线两笔缺口各自喊、各自存
     * <p>
     * 两句告警分开落：两段成因不同（程序停的／这个房间自己断的），混成一句就没法各自追。
     * 两个秒数分开存进场次：停机期间所有房间都在断，两段必然重叠，加起来是重复计数。
     * 各喂一笔，两句话都得在，两个独立字段都得是喂进去的那个数。
     */
    @Test
    @DisplayName("分支②：停机/断线各落一句 WARN，秒数各进自己的场次字段")
    void downtimeAndRoomOutageEachWarnAndStoreTheirOwnSeconds() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);
        when(liveDataService.downtimeWithin(START, END)).thenReturn(30_000L);
        when(liveDataService.roomOutageWithin(PLATFORM, UID, START, END)).thenReturn(45_000L);

        List<String> warns = captureWarns(() -> listener.onLiveOffEvent(liveOffAt(END)));

        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        assertEquals(30, sessions.getValue().maintenanceGapSeconds(), "停机秒数入 maintenanceGapSeconds");
        assertEquals(45, sessions.getValue().roomOutageSeconds(), "断线秒数入 roomOutageSeconds");

        assertEquals(2, warns.size(), "停机与断线各一句，实际: " + warns);
        assertTrue(warns.stream().anyMatch(w -> w.contains("因程序停机未采集") && w.contains("30")),
                "停机那句带着秒数，实际: " + warns);
        assertTrue(warns.stream().anyMatch(w -> w.contains("因直播间断线未采集") && w.contains("45")),
                "断线那句带着秒数，实际: " + warns);
    }

    /**
     * 分支三：峰值的时刻与取值必须出自同一格
     * <p>
     * 峰是曲线上最高的那一格：值取哪一格的，时刻就得是哪一格的。把最高的那一格
     * 放在序列中间、两头压低——分两趟各求一次的写法会指错时刻，这里当场现形。
     * 场次与明细两份归档里留的是同一个峰。
     */
    @Test
    @DisplayName("分支③：峰值的时刻与取值同格，场次与明细同源")
    void peakMomentAndValueComeFromTheSameBucketInBothArchives() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);
        when(liveDataService.getLiveSeriesMetrics(PLATFORM, UID)).thenReturn(Set.of("人气"));
        when(liveDataService.getLiveSeries(PLATFORM, UID, "人气")).thenReturn(Map.of(
                START + 60_000, 5.0,
                START + 120_000, 137.0,
                START + 180_000, 5.0));

        listener.onLiveOffEvent(liveOffAt(END));

        SeriesPeak want = new SeriesPeak(START + 120_000, 137.0);
        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        assertEquals(want, sessions.getValue().peak("人气").orElseThrow());

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        assertEquals(want, detailCaptor.getValue().peaks().get("人气"));
    }

    /**
     * 分支四：明细的榜取全长，缺口两份合并到互不重叠
     * <p>
     * 榜要全量：报告只画前几名是版面所限，留档也砍前几名就把长尾永久丢掉。
     * 参与人数喂 30、取榜只答得出 30 条那一次调用——取少了这份留档就是残的。
     * 缺口两份喂一段重叠（停机 [60s,120s)、断线 [90s,180s)）：合并后重叠的 30 秒
     * 归停机、断线只剩 [120s,180s)，两份相加不再是比整场还长的缺口。
     */
    @Test
    @DisplayName("分支④：榜按参与人数取全长，重叠缺口归停机、合并后互不重叠")
    void detailKeepsFullRankingAndMergesOverlappingGapsIntoDisjointOnes() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);
        when(liveDataService.getLiveMetricUserCounts(PLATFORM, UID)).thenReturn(Map.of("弹幕", 30));
        when(liveDataService.getLiveUserRanking(PLATFORM, UID, "弹幕", 30)).thenReturn(
                IntStream.rangeClosed(1, 30).mapToObj(i -> new UserScore((long) i, "观众" + i, i)).toList());
        when(liveDataService.downtimeIntervals(START, END)).thenReturn(List.of(
                new LiveGap(START + 60_000, START + 120_000, LiveGap.Reason.MAINTENANCE)));
        when(liveDataService.roomOutageIntervals(PLATFORM, UID, START, END)).thenReturn(List.of(
                new LiveGap(START + 90_000, START + 180_000, LiveGap.Reason.STREAM_LOSS)));

        listener.onLiveOffEvent(liveOffAt(END));

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        LiveDetail detail = detailCaptor.getValue();
        assertEquals(30, detail.ranking("弹幕").size(), "榜的条数＝参与人数，一条不少");
        assertEquals(List.of(
                new LiveGap(START + 60_000, START + 120_000, LiveGap.Reason.MAINTENANCE),
                new LiveGap(START + 120_000, START + 180_000, LiveGap.Reason.STREAM_LOSS)),
                detail.gaps());
    }

    /**
     * 残留一：高能时刻只数计入弹幕条数的那几类
     * <p>
     * 文字弹幕与表情弹幕计入密度，付费留言不算——把它算进去，一条 30 元的留言
     * 在曲线上就等价于一句「哈哈」。明细里看不到 danmuSeries 本身，高能时刻就是
     * 那张表的出口：计为弹幕的那一分钟应被挑出，且取值只含前者；付费留言独占的
     * 那一分钟不应出现。过滤条件改坏（把非弹幕也计入）时，取值会被抬高、不该在的
     * 分钟也会进表。
     */
    @Test
    @DisplayName("残留①：highlights 只含计入弹幕的分钟，取值不含付费留言")
    void highlightsKeepOnlyDanmuAndEmojiBuckets() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);

        long countedAt = START + 10 * 60_000L;
        long ignoredAt = START + 20 * 60_000L;
        List<DanmuRecord> records = new ArrayList<>();
        records.addAll(copies(countedAt, DanmuRecord.Type.DANMU, 16));
        records.addAll(copies(countedAt, DanmuRecord.Type.EMOJI, 4));
        records.addAll(copies(countedAt, DanmuRecord.Type.SUPER_CHAT, 30));
        records.addAll(copies(ignoredAt, DanmuRecord.Type.SUPER_CHAT, 50));
        when(details.readDanmu(eq(PLATFORM), eq(UID), eq(START))).thenReturn(records);

        listener.onLiveOffEvent(liveOffAt(END));

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        List<LiveHighlightFinder.Highlight> highlights = detailCaptor.getValue().highlights();
        long countedBucket = countedAt / LiveDataService.SERIES_BUCKET_MILLIS * LiveDataService.SERIES_BUCKET_MILLIS;
        long ignoredBucket = ignoredAt / LiveDataService.SERIES_BUCKET_MILLIS * LiveDataService.SERIES_BUCKET_MILLIS;
        assertEquals(1, highlights.size(), "只应挑出计入弹幕的那一分钟，实际: " + highlights);
        assertEquals(countedBucket, highlights.get(0).at());
        assertEquals(20.0, highlights.get(0).value(), "取值＝文字+表情，不含同分钟的付费留言");
        assertFalse(highlights.stream().anyMatch(h -> h.at() == ignoredBucket),
                "付费留言独占的分钟不应进高能时刻，实际: " + highlights);
    }

    /**
     * 残留二：时钟回拨时时长夹到 0，场次与明细仍归档
     * <p>
     * 下播时刻早于开播时刻（时钟回拨或数据异常）时，时长必须是 0 而不是负数——
     * 统计里出现负时长会把平均值、合计全部带歪。没有开播时刻才跳过归档；
     * 有开播时刻只是顺序反了，这一场仍然发生过，两份归档都要留下。
     */
    @Test
    @DisplayName("残留②：下播早于开播时 duration 夹 0，场次与明细仍归档")
    void clockRollbackClampsDurationToZeroAndStillArchives() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);

        long endBeforeStart = START - 60_000L;
        listener.onLiveOffEvent(liveOffAt(endBeforeStart));

        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        LiveSession session = sessions.getValue();
        assertEquals(START, session.startTime());
        assertEquals(endBeforeStart, session.endTime());
        assertEquals(0, session.durationSeconds(), "回拨须夹到 0，不能把负数写进统计");

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        assertEquals(0, detailCaptor.getValue().durationSeconds(), "明细时长与场次同一套夹 0");
    }

    /**
     * 残留三：全程无数据的曲线不进归档序列表，也不产生峰值
     * <p>
     * 「这条曲线一个点都没有」与「峰值为 0」是两回事。空表写进序列表会让读的人
     * 以为这场采过这条曲线只是全是零；峰值项缺席才表示没有可取的峰。
     * 另一条有点的曲线仍应在表里、仍应有峰——免得空判把整张表都吞掉。
     */
    @Test
    @DisplayName("残留③：空序列不入归档表，peaks 无该键；有点的曲线照留")
    void emptySeriesDoesNotEnterArchiveOrPeaks() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);
        when(liveDataService.getLiveSeriesMetrics(PLATFORM, UID)).thenReturn(Set.of("人气", "空序列"));
        when(liveDataService.getLiveSeries(PLATFORM, UID, "人气")).thenReturn(Map.of(START + 60_000, 5.0));
        when(liveDataService.getLiveSeries(PLATFORM, UID, "空序列")).thenReturn(Map.of());

        listener.onLiveOffEvent(liveOffAt(END));

        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        LiveSession session = sessions.getValue();
        assertEquals(new SeriesPeak(START + 60_000, 5.0), session.peak("人气").orElseThrow());
        assertTrue(session.peak("空序列").isEmpty(), "空序列不产生峰值项");

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        LiveDetail detail = detailCaptor.getValue();
        assertTrue(detail.series().containsKey("人气"), "有点的曲线仍应在序列表");
        assertFalse(detail.series().containsKey("空序列"), "全程无数据的曲线不应写进归档序列表");
        assertFalse(detail.peaks().containsKey("空序列"), "空序列不在 peaks");
        assertEquals(new SeriesPeak(START + 60_000, 5.0), detail.peaks().get("人气"));
    }

    /**
     * 分支五：明细里排行榜抛 RuntimeException 时，场次归档已经落下，异常不得冒出监听者
     * <p>
     * 排行榜是明细组装里第一处会向监听者冒 RuntimeException 的调用，场次归档在它之前。
     * 冒出去的话，同一事件上排在后面的监听者整段跳过，下播推送与报告都不会跑。
     */
    @Test
    @DisplayName("分支⑤：明细归档抛 RuntimeException 时场次归档已落、监听者不抛")
    void archiveDetailRuntimeExceptionDoesNotEscapeAndSessionArchiveStays() {
        List<String> reds = new ArrayList<>();

        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);
        when(liveDataService.getLiveMetricUserCounts(PLATFORM, UID)).thenReturn(Map.of("弹幕", 1));
        when(liveDataService.getLiveUserRanking(PLATFORM, UID, "弹幕", 1))
                .thenThrow(new RuntimeException("ranking boom"));

        try {
            listener.onLiveOffEvent(liveOffAt(END));
        } catch (RuntimeException e) {
            reds.add("① onLiveOffEvent 抛了: " + e);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            verify(archive, times(1)).append(any());
        } catch (AssertionError e) {
            reds.add("② archive.append 应恰一次: " + e.getMessage());
        }

        setUp();
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);
        try {
            listener.onLiveOffEvent(liveOffAt(END));
            verify(details, times(1)).store(any());
        } catch (RuntimeException e) {
            reds.add("③ 不打桩时抛了: " + e);
        } catch (AssertionError e) {
            reds.add("③ 不打桩时 details.store 应恰一次: " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    private static List<DanmuRecord> copies(long at, DanmuRecord.Type type, int n) {
        return IntStream.range(0, n).mapToObj(i -> new DanmuRecord(at, 1L, "观众甲", "话", type)).toList();
    }
}
