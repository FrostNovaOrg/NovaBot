package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.GuardMedal;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下播时留下的大航海名单，重画要能用，多个推送目标不能各取一遍。
 */
@DisplayName("下播留下的大航海名单")
class GuardRosterRetainTest {
    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();

    private static final long UID = 19_604_318_752_096L;

    private static final long ROOM = 47_615_208_934_771L;

    private static final long AUDIENCE = 19_000_000_000_011L;

    private static final long START = 1_700_000_111_000L;

    private static final String ROSTER = """
            {
              "at": 1700000111000,
              "total": 42,
              "members": [
                {
                  "uid": 19000000000011,
                  "name": "星港",
                  "level": 3,
                  "score": 1280,
                  "medal": {
                    "name": "星云",
                    "level": 21,
                    "lit": true,
                    "start": "#3FB4F699",
                    "end": "#112233FF",
                    "border": "#5FC7F4FF",
                    "text": "#FFFFFFFF",
                    "guardIcon": "https://pic.example.invalid/guard.png"
                  }
                }
              ]
            }
            """;

    @TempDir
    Path temp;

    @Test
    @DisplayName("重画以前一场：当时留下了名单，重画仍能画出这个人和人数")
    void replayDrawsTheRosterThatWasSaved() throws Exception {
        Path session = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START);
        Files.createDirectories(session);
        Files.writeString(session.resolve("guards.json"), ROSTER);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        LiveStreamerInfo streamer = new LiveStreamerInfo(UID, "主播甲", ROOM);
        BilibiliLiveReportReplayPainter painter = replay(api, streamer);

        List<GuardMember> members = painter.guardList(ROOM, UID).orElse(List.of());
        GuardMember member = members.isEmpty() ? null : members.get(0);
        String text = painter.textReport(PLATFORM, streamer, new BilibiliLiveReportOptions());

        assertAll(
                () -> assertNotNull(member, "重画以前一场，当时有名单，重画出来没有"),
                () -> assertEquals(AUDIENCE, member == null ? 0L : member.uid()),
                () -> assertEquals("星港", member == null ? "" : member.name()),
                () -> assertEquals(3, member == null ? 0 : member.level()),
                () -> assertEquals(1280L, member == null ? 0L : member.score()),
                () -> assertNotNull(member == null ? null : member.medal(), "粉丝牌没有留在名单里"),
                () -> assertEquals("星云", member == null || member.medal() == null ? "" : member.medal().name()),
                () -> assertEquals(21, member == null || member.medal() == null ? 0 : member.medal().level()),
                () -> assertTrue(member != null && member.medal() != null && member.medal().lit(), "点亮状态丢了"),
                () -> assertEquals(new Color(0x3F, 0xB4, 0xF6, 0x99),
                        member == null || member.medal() == null ? null : member.medal().start()),
                () -> assertEquals(new Color(0x11, 0x22, 0x33, 0xFF),
                        member == null || member.medal() == null ? null : member.medal().end()),
                () -> assertEquals(new Color(0x5F, 0xC7, 0xF4, 0xFF),
                        member == null || member.medal() == null ? null : member.medal().border()),
                () -> assertEquals(new Color(0xFF, 0xFF, 0xFF, 0xFF),
                        member == null || member.medal() == null ? null : member.medal().text()),
                () -> assertEquals("https://pic.example.invalid/guard.png",
                        member == null || member.medal() == null ? "" : member.medal().guardIcon()),
                () -> assertEquals(Optional.of(42), painter.guardCount(ROOM, UID), "人数卡片没有当时的大航海人数"),
                () -> assertTrue(text.contains("星港"), "文字版名单里没有这个人"),
                () -> verifyNoInteractions(api));
    }

    @Test
    @DisplayName("三个推送目标：下播名单只向平台取一次，并留下一份")
    void threeTargetsFetchTheRosterOnce() throws Exception {
        LiveDataService data = savedLiveData();
        data.setLiveStartTime(PLATFORM, UID, START);
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getGuardList(ROOM, UID)).thenReturn(Optional.of(List.of(
                new GuardMember(AUDIENCE, "星港", 3, 1280))));
        when(api.getGuardCount(ROOM, UID)).thenReturn(Optional.of(42));

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                mock(NovaCommonPainterFactory.class), api, data, mock(FontUtil.class),
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class),
                new ReportImageDiskCache(temp.resolve("image-cache")));
        LiveStreamerInfo streamer = new LiveStreamerInfo(UID, "主播甲", ROOM);
        BilibiliLiveReportOptions options = new BilibiliLiveReportOptions();
        painter.textReport(PLATFORM, streamer, options);
        painter.textReport(PLATFORM, streamer, options);
        painter.textReport(PLATFORM, streamer, options);

        Path file = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START).resolve("guards.json");
        assertAll(
                () -> verify(api, times(1)).getGuardList(ROOM, UID),
                () -> assertTrue(Files.isRegularFile(file), "三个目标取完，这场的名单什么都没留下"),
                () -> assertTrue(Files.readString(file).contains("星港"), "留下的名单里没有这个人"));
    }

    @Test
    @DisplayName("空名单不能把重画画成没人上舰")
    void emptyRosterFileIsNotDrawnAsNobody() throws Exception {
        Path session = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START);
        Files.createDirectories(session);
        Files.writeString(session.resolve("guards.json"), """
                { "at": 1700000111000, "total": 0, "members": [] }
                """);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        LiveStreamerInfo streamer = new LiveStreamerInfo(UID, "主播甲", ROOM);
        BilibiliLiveReportReplayPainter painter = replay(api, streamer);
        String text = painter.textReport(PLATFORM, streamer, new BilibiliLiveReportOptions());

        assertAll(
                () -> assertTrue(painter.guardCount(ROOM, UID).isEmpty(), "重画把空名单画成了没人上舰"),
                () -> assertTrue(painter.guardList(ROOM, UID).orElse(List.of()).isEmpty(), "空名单仍画出了人"),
                () -> assertFalse(text.contains("大航海名单"), "空名单仍画出了名单"),
                () -> verifyNoInteractions(api));
    }

    @Test
    @DisplayName("直播中的实时报告不能把名单定格，下播留下的是下播当时的人")
    void liveCommandDoesNotFreezeTheRoster() throws Exception {
        LiveDataService data = savedLiveData();
        data.setLiveStartTime(PLATFORM, UID, START);
        data.setLiveStatus(PLATFORM, UID, true);
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        GuardMember midway = new GuardMember(AUDIENCE, "星港中场", 3, 1280);
        GuardMember closing = new GuardMember(AUDIENCE, "星港下播", 3, 4096);
        when(api.getGuardList(ROOM, UID)).thenReturn(Optional.of(List.of(midway)));

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                mock(NovaCommonPainterFactory.class), api, data, mock(FontUtil.class),
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class),
                new ReportImageDiskCache(temp.resolve("image-cache")));
        LiveStreamerInfo streamer = new LiveStreamerInfo(UID, "主播甲", ROOM);
        BilibiliLiveReportOptions options = new BilibiliLiveReportOptions();
        painter.textReport(PLATFORM, streamer, options);
        when(api.getGuardList(ROOM, UID)).thenReturn(Optional.of(List.of(closing)));
        String laterLive = painter.textReport(PLATFORM, streamer, options);

        data.setLiveStatus(PLATFORM, UID, false);
        painter.textReport(PLATFORM, streamer, options);

        Path file = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START).resolve("guards.json");
        String saved = Files.isRegularFile(file) ? Files.readString(file) : "";
        BilibiliLiveReportReplayPainter drawn = replay(mock(BilibiliApiUtil.class), streamer);
        String replayText = drawn.textReport(PLATFORM, streamer, options);

        assertAll(
                () -> assertTrue(laterLive.contains("星港下播"),
                        "直播中后续的实时报告不再现取"),
                () -> assertFalse(laterLive.contains("星港中场"),
                        "直播中后续的实时报告画出的还是刚才那一份名单"),
                () -> assertTrue(saved.contains("星港下播"),
                        "下播后留存的不是下播当时的名单"),
                () -> assertFalse(saved.contains("星港中场"),
                        "下播后留存与重画用的是直播中那一刻的名单"),
                () -> assertTrue(replayText.contains("星港下播") && !replayText.contains("星港中场"),
                        "重画出的是直播中那一刻的名单"),
                () -> verify(api, times(3)).getGuardList(ROOM, UID));
    }

    @Test
    @DisplayName("名单没取全时，重画的人数仍是下播报告上的人数")
    void shortRosterRedrawsTheCountShownOnTheReport() throws Exception {
        LiveDataService data = savedLiveData();
        data.setLiveStartTime(PLATFORM, UID, START);
        data.setLiveStatus(PLATFORM, UID, false);
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getGuardCount(ROOM, UID)).thenReturn(Optional.of(1280));
        when(api.getGuardList(ROOM, UID)).thenReturn(Optional.of(List.of(
                new GuardMember(AUDIENCE, "星港", 3, 40))));

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                mock(NovaCommonPainterFactory.class), api, data, mock(FontUtil.class),
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class),
                new ReportImageDiskCache(temp.resolve("image-cache")));
        LiveStreamerInfo streamer = new LiveStreamerInfo(UID, "主播甲", ROOM);
        Optional<Integer> onReport = painter.guardCount(ROOM, UID);
        painter.guardList(ROOM, UID);

        BilibiliLiveReportReplayPainter drawn = replay(mock(BilibiliApiUtil.class), streamer);
        assertAll(
                () -> assertEquals(Optional.of(1280), onReport, "下播报告上的人数不是接口报的总数"),
                () -> assertEquals(onReport, drawn.guardCount(ROOM, UID),
                        "名单没取全时，重画出来的人数比下播报告上的少"),
                () -> verify(api, times(1)).getGuardCount(ROOM, UID));
    }

    private BilibiliLiveReportReplayPainter replay(BilibiliApiUtil api, LiveStreamerInfo streamer) {
        LiveDetail detail = new LiveDetail(LiveDetail.VERSION, PLATFORM, streamer.getUid(), streamer.getUname(),
                streamer.getRoomId(), START, START + 60_000L, 60L,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), Map.of(), List.of());
        return new BilibiliLiveReportReplayPainter(
                mock(NovaCommonPainterFactory.class), api, mock(FontUtil.class),
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class), detail,
                new ReportImageDiskCache(temp.resolve("image-cache")));
    }

    private static LiveDataService savedLiveData() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        return new DefaultLiveDataService(properties);
    }
}
