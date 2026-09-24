package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.model.GuardListFetch;
import org.frostnova.nova.bilibili.model.GuardMedal;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 没配下播报告的场次也要留下大航海名单；取失败不能留下空文件。
 */
@DisplayName("下播留下大航海名单")
class BilibiliGuardRosterKeeperTest {
    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();

    private static final long UID = 19_604_318_752_096L;

    private static final long ROOM = 47_615_208_934_771L;

    private static final long AUDIENCE = 19_000_000_000_011L;

    private static final long START = 1_700_000_222_000L;

    @TempDir
    Path temp;

    @Test
    @DisplayName("没配下播报告：这场的名单仍然留下，而且明细落盘之后还能写")
    void roomWithoutReportStillKeepsTheRoster() throws Exception {
        Order order = BilibiliGuardRosterKeeper.class
                .getMethod("onLiveOff", BilibiliLiveOffEvent.class)
                .getAnnotation(Order.class);
        EventListener listener = BilibiliGuardRosterKeeper.class
                .getMethod("onLiveOff", BilibiliLiveOffEvent.class)
                .getAnnotation(EventListener.class);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        GuardMedal medal = new GuardMedal("星云", 21, true,
                new Color(0x3F, 0xB4, 0xF6, 0x99),
                new Color(0x11, 0x22, 0x33, 0xFF),
                new Color(0x5F, 0xC7, 0xF4, 0xFF),
                new Color(0xFF, 0xFF, 0xFF, 0xFF),
                "https://pic.example.invalid/guard.png");
        when(api.getGuardList(ROOM, UID)).thenReturn(Optional.of(List.of(
                new GuardMember(AUDIENCE, "星港", 3, 1280, medal))));

        LiveDataService data = mock(LiveDataService.class);
        when(data.getLiveStartTime(anyString(), eq(UID))).thenReturn(Optional.of(START));
        Path session = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START);
        Files.createDirectories(session);
        Files.writeString(session.resolve("detail.json"), "{}");

        BilibiliGuardRosterKeeper keeper = new BilibiliGuardRosterKeeper(api, archive(), data, Runnable::run);
        BilibiliLiveOffEvent event = new BilibiliLiveOffEvent(new LiveStreamerInfo(UID, "主播甲", ROOM));
        keeper.onLiveOff(event);
        keeper.onLiveOff(event);

        Path file = session.resolve("guards.json");
        assertAll(
                () -> assertNotNull(listener, "下播时进不来，没配报告的场次不会留名单"),
                () -> assertNotNull(order, "取名单没有排到下播文字之后"),
                () -> assertTrue(order != null && order.value() > 0, "取名单排在下播文字之前，文字推送会被拖住"),
                () -> assertTrue(Files.isRegularFile(file), "没配下播报告的这场，名单什么都没留下"),
                () -> assertTrue(Files.readString(file).contains("星港"), "留下的名单里没有这个人"),
                () -> assertTrue(Files.readString(file).contains("星云"), "粉丝牌没有留在名单里"),
                () -> assertTrue(GuardRosterFile.parse(Files.readString(file))
                        .map(parsed -> parsed.total() == 1).orElse(false),
                        "留下的人数不是这份名单的人数"),
                () -> verify(api, never()).getGuardCount(anyLong(), anyLong()),
                () -> verify(api, times(1)).getGuardList(ROOM, UID));
    }

    @Test
    @DisplayName("取名单失败或拿到空表：不留空文件")
    void failedFetchDoesNotLeaveAnEmptyRoster() {
        long failed = UID + 1;
        long empty = UID + 2;
        long broken = UID + 3;
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getGuardList(ROOM, failed)).thenReturn(Optional.empty());
        when(api.getGuardList(ROOM, empty)).thenReturn(Optional.of(List.of()));
        when(api.getGuardList(ROOM, broken)).thenThrow(new IllegalStateException("接口坏了"));

        LiveDataService data = mock(LiveDataService.class);
        when(data.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.of(START));
        BilibiliGuardRosterKeeper keeper = new BilibiliGuardRosterKeeper(api, archive(), data, Runnable::run);

        keeper.onLiveOff(new BilibiliLiveOffEvent(new LiveStreamerInfo(failed, "主播甲", ROOM)));
        keeper.onLiveOff(new BilibiliLiveOffEvent(new LiveStreamerInfo(empty, "主播甲", ROOM)));
        keeper.onLiveOff(new BilibiliLiveOffEvent(new LiveStreamerInfo(broken, "主播甲", ROOM)));

        assertAll(
                () -> assertFetchLeftNothing(api, failed),
                () -> assertFetchLeftNothing(api, empty),
                () -> assertFetchLeftNothing(api, broken));
    }

    @Test
    @DisplayName("没配下播报告：别的下播监听不用等名单取完")
    void otherListenersDoNotWaitForRoster() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicLong otherAt = new AtomicLong();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getGuardList(ROOM, UID)).thenAnswer(invocation -> {
            fetchEntered.countDown();
            releaseFetch.await(3, TimeUnit.SECONDS);
            return Optional.of(List.of(new GuardMember(AUDIENCE, "星港", 3, 1280)));
        });
        LiveDataService data = mock(LiveDataService.class);
        when(data.getLiveStartTime(anyString(), eq(UID))).thenReturn(Optional.of(START));
        BilibiliGuardRosterKeeper keeper = new BilibiliGuardRosterKeeper(api, archive(), data);
        BilibiliLiveOffEvent event = new BilibiliLiveOffEvent(new LiveStreamerInfo(UID, "主播甲", ROOM));

        Thread dispatcher = new Thread(() -> {
            keeper.onLiveOff(event);
            otherAt.set(System.nanoTime());
        });
        dispatcher.start();

        boolean entered = fetchEntered.await(2, TimeUnit.SECONDS);
        boolean otherBeforeRelease = false;
        if (entered) {
            long deadline = System.nanoTime() + 300_000_000L;
            while (System.nanoTime() < deadline && otherAt.get() == 0L) {
                Thread.sleep(10);
            }
            otherBeforeRelease = otherAt.get() != 0L;
        }
        releaseFetch.countDown();
        dispatcher.join(5_000);
        Path file = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START).resolve("guards.json");
        long fileDeadline = System.nanoTime() + 2_000_000_000L;
        while (!Files.isRegularFile(file) && System.nanoTime() < fileDeadline) {
            Thread.sleep(10);
        }
        boolean finishedWhileFetchHeld = otherBeforeRelease;
        assertAll(
                () -> assertTrue(entered, "下播后没有去取名单"),
                () -> assertTrue(finishedWhileFetchHeld,
                        "没配报告推送的直播间，下播时别的监听要等名单取完"),
                () -> assertTrue(Files.isRegularFile(file), "名单取完没有留下"));
    }

    @Test
    @DisplayName("没配下播报告：名单没取全时，留下的人数仍是首页报的总数")
    void shortListKeepsTheCountFromTheFirstPage() throws Exception {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getGuardList(ROOM, UID)).thenReturn(Optional.of(new GuardListFetch(
                List.of(new GuardMember(AUDIENCE, "星港", 3, 40)), 1280)));
        LiveDataService data = mock(LiveDataService.class);
        when(data.getLiveStartTime(anyString(), eq(UID))).thenReturn(Optional.of(START));
        BilibiliGuardRosterKeeper keeper = new BilibiliGuardRosterKeeper(api, archive(), data, Runnable::run);
        keeper.onLiveOff(new BilibiliLiveOffEvent(new LiveStreamerInfo(UID, "主播甲", ROOM)));

        Path file = temp.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START).resolve("guards.json");
        int total = Files.isRegularFile(file)
                ? GuardRosterFile.parse(Files.readString(file)).map(GuardRosterFile.Parsed::total).orElse(-1)
                : -1;
        assertAll(
                () -> assertEquals(1280, total, "名单没取全时，留下的人数比首页报的总数少"),
                () -> verify(api, never()).getGuardCount(anyLong(), anyLong()));
    }

    private void assertFetchLeftNothing(BilibiliApiUtil api, long uid) {
        Path file = temp.resolve("details").resolve(PLATFORM + "-" + uid + "-" + START).resolve("guards.json");
        long fetches = mockingDetails(api).getInvocations().stream()
                .filter(invocation -> "getGuardList".equals(invocation.getMethod().getName()))
                .filter(invocation -> Long.valueOf(uid).equals(invocation.getArgument(1)))
                .count();
        boolean left = Files.exists(file);
        assertFalse(fetches == 0 || left,
                "取名单失败，却留下了一份空名单，重画会变成没人上舰（取过=" + fetches + "，文件在=" + left + "）");
    }

    private LiveDetailArchive archive() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        properties.getLive().setLiveDataPath(temp.resolve("data.json").toString());
        return new LiveDetailArchive(properties);
    }
}
