package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 直播间连接管理服务测试
 * <p>
 * sync 自热重载接入后会被反复调用，这里以桩替身覆盖重复调用下的幂等性。
 * 真实的连接建立需要网络与登录态，属真机验证范畴，不在本测试内。
 */
@DisplayName("直播间连接管理服务")
class BilibiliLiveRoomServiceTest {
    @Test
    @DisplayName("反复同步不应重复注册风控检测周期任务")
    void repeatedSyncShouldScheduleRiskDetectionOnce() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);

        AbstractDataSource dataSource = emptyDataSource();
        service.sync(dataSource);
        service.sync(dataSource);
        service.sync(dataSource);

        verify(scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    @DisplayName("关闭长连接时同步应直接返回且不注册风控检测")
    void syncShouldNoOpWhenDisabled() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getLive().setEnableConnectLiveRoom(false);
        BilibiliLiveRoomService service = service(scheduler, properties);

        service.sync(emptyDataSource());

        verify(scheduler, times(0)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    @DisplayName("推送配置里的直播间超过上限时只连接靠前的 10 间, 被截掉的点名写进日志")
    void syncShouldConnectAtMostTenRooms() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BilibiliLiveRoomService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            TaskScheduler scheduler = mock(TaskScheduler.class);
            BilibiliLiveRoomService service = service(scheduler);

            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getUsers(anyString())).thenReturn(users(1, 11));

            service.sync(dataSource);

            // 上限写死 10 而不是引用常量：这里要钉住的正是「上限是 10」这条产品结论
            List<Runnable> queued = queuedConnects(scheduler);
            assertEquals(10, queued.size(), "超出上限的直播间不该被排进建连队列");

            queued.forEach(Runnable::run);

            assertEquals(10, service.getManagedRoomCount(), "纳入连接管理的直播间数不得超过上限");
            assertTrue(service.getStatus(1011L).isEmpty(), "排在第 11 位的直播间不应被接管");

            // 「配了却没连上」若不在日志里记录，只会表现为某个直播间从此没有任何事件，
            // 而界面上一切正常——这种缺口是查不出来的
            String warn = warnContaining(appender, "上限");
            assertNotNull(warn, "被截掉的直播间必须点名，实际日志: " + appender.list);
            assertTrue(warn.contains("10 位"), "日志要说清上限是多少: " + warn);
            assertTrue(warn.contains("房间号: 1011"), "日志要带上被截掉的房间号: " + warn);
            assertTrue(warn.contains("UID: 11"), "日志要带上被截掉的 uid: " + warn);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("名额已满时新直播间被拒连, 已在管理中的直播间重连不受影响")
    void connectShouldRefuseNewRoomWhenFull() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);

        AbstractDataSource first = mock(AbstractDataSource.class);
        when(first.getUsers(anyString())).thenReturn(users(1, 10));
        service.sync(first);

        // 热重载换了一批主播，而上一批的建连任务还压在闸门里没放行。
        // 两次同步各自都没超过上限，只有等它们一起执行时才撞上名额——
        // 这正是 sync 那道闸拦不住、只能由 connect 兜底的那一种
        AbstractDataSource second = mock(AbstractDataSource.class);
        when(second.getUsers(anyString())).thenReturn(users(11, 10));
        service.sync(second);

        List<Runnable> queued = queuedConnects(scheduler);
        assertEquals(20, queued.size(), "两次同步各排十间，单次都未超限，都应排进队列");

        queued.forEach(Runnable::run);

        assertEquals(10, service.getManagedRoomCount(), "兜底必须挡住后到的那十间");
        assertTrue(service.getStatus(1001L).isPresent(), "先占到名额的直播间应留在管理中");
        assertTrue(service.getStatus(1011L).isEmpty(), "名额已满时后到的直播间不应被接管");

        // 重连走的正是同一条 connect 路径。若上限把它一并挡住，
        // 满员时任何一间断线都再也连不回来，而日志里只会说「已达上限」
        queued.get(0).run();

        assertTrue(service.getStatus(1001L).isPresent(), "已在管理中的直播间重连不该被上限挡下");
        assertEquals(10, service.getManagedRoomCount(), "重连不应改变管理中的直播间数");
    }

    /**
     * 取出排进闸门等待放行的建连任务
     * <p>
     * 调度器是桩，任务不会自己跑，因此先收下来再由用例决定何时执行。
     * 不让桩同步执行：首连失败后排出的重连会立刻再回到桩里，无限递归下去
     * @param scheduler 调度器桩
     * @return 已排队的建连任务
     */
    private List<Runnable> queuedConnects(TaskScheduler scheduler) {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeast(0)).schedule(tasks.capture(), any(Instant.class));
        return tasks.getAllValues();
    }

    /**
     * 找出第一条含指定内容的 WARN 日志
     * @param appender 日志收集器
     * @param keyword 关键字
     * @return 日志内容，没有时为 null
     */
    private String warnContaining(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
                                  String keyword) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(keyword))
                .findFirst()
                .orElse(null);
    }

    /**
     * 构造若干个已启用的哔哩哔哩推送用户，uid 自 startUid 起递增，房间号为 1000 + uid
     * <p>
     * 顺序是判据的一部分：截断要按数据源给出的先后取，否则「留下哪几间」每次启动都可能不同
     * @param startUid 起始 uid
     * @param count 用户数
     * @return 推送用户列表
     */
    private List<PushUser> users(int startUid, int count) {
        List<PushUser> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long uid = startUid + i;
            PushUser user = new PushUser();
            user.setUid(uid);
            user.setUname("主播" + uid);
            user.setRoomId(1000L + uid);
            user.setPlatform(BilibiliPlatform.BILIBILI.id());
            user.setEnabled(true);
            user.setTargets(new ArrayList<>());
            users.add(user);
        }
        return users;
    }

    /**
     * 构造被测服务（默认配置）
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler) {
        return service(scheduler, new NovaBilibiliProperties());
    }

    /**
     * 构造被测服务
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler, NovaBilibiliProperties properties) {
        return new BilibiliLiveRoomService(
                mock(BilibiliApiUtil.class),
                mock(BilibiliEventParser.class),
                properties,
                mock(ApplicationEventPublisher.class),
                scheduler,
                mock(BilibiliLiveStateGate.class),
                // 用真实闸门而不是 mock：首连的错开间隔现在由它产生，
                // mock 掉就等于把被测行为一起 mock 没了
                new BilibiliConnectGate(properties, scheduler),
                new BilibiliRiskMetrics(),
                new org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest(properties, scheduler),
                mock(LiveDataService.class)
        );
    }

    /**
     * 构造一个不含任何推送用户的数据源桩
     */
    private AbstractDataSource emptyDataSource() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of());
        return dataSource;
    }
}
