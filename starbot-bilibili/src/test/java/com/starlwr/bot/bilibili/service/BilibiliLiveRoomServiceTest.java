package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getLive().setEnableConnectLiveRoom(false);
        BilibiliLiveRoomService service = service(scheduler, properties);

        service.sync(emptyDataSource());

        verify(scheduler, times(0)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    /**
     * 构造被测服务（默认配置）
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler) {
        return service(scheduler, new StarBotBilibiliProperties());
    }

    /**
     * 构造被测服务
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler, StarBotBilibiliProperties properties) {
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
                new BilibiliRiskMetrics()
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
