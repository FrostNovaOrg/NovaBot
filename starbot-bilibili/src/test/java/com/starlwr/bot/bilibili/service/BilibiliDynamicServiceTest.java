package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushUser;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态推送测试
 * <p>
 * 不发起网络请求：动态列表由桩实现直接给出，关注的是哪些动态会被放行。
 */
@DisplayName("动态推送")
class BilibiliDynamicServiceTest {
    private static final long UID = 10001L;

    private StarBotBilibiliProperties properties;

    private BilibiliApiUtil api;

    private final List<BilibiliDynamicUpdateEvent> published = new ArrayList<>();

    /**
     * 被捕获的轮询任务，由测试自行驱动，不真的起定时器
     */
    private Runnable poll;

    @BeforeEach
    void setUp() {
        properties = new StarBotBilibiliProperties();
        // 关掉自动关注，让调度器上只挂着轮询这一个任务，便于捕获
        properties.getDynamic().setAutoFollow(false);

        api = mock(BilibiliApiUtil.class);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.isLoggedIn()).thenReturn(true);

        // 用真实实现而非 mock：ApplicationEventPublisher 的 publishEvent 有 Object 与
        // ApplicationEvent 两个重载，事件类继承自后者，只桩住 Object 那个会静默漏掉全部事件
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof BilibiliDynamicUpdateEvent update) {
                published.add(update);
            }
        };

        TaskScheduler scheduler = mock(TaskScheduler.class);

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname("测试UP主");
        user.setPlatform(LivePlatform.BILIBILI.getName());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(user));

        BilibiliDynamicService service = new BilibiliDynamicService(api, accountService, properties, publisher, scheduler);
        service.start(dataSource);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), any(Duration.class));
        this.poll = task.getValue();
    }

    @Test
    @DisplayName("默认不推送开播动态")
    void skipsLiveDynamicByDefault() {
        pushRound(dynamic("1", "DYNAMIC_TYPE_LIVE_RCMD"));

        assertTrue(published.isEmpty(), "开播动态默认应被跳过, 否则会和开播推送重复");
    }

    @Test
    @DisplayName("开关打开后推送开播动态")
    void pushesLiveDynamicWhenEnabled() {
        properties.getDynamic().setPushLiveDynamic(true);

        pushRound(dynamic("1", "DYNAMIC_TYPE_LIVE_RCMD"));

        assertEquals(1, published.size());
        assertEquals("开播了", published.get(0).getAction());
    }

    @Test
    @DisplayName("跳过开播动态不影响其它类型")
    void keepsOtherDynamics() {
        pushRound(dynamic("1", "DYNAMIC_TYPE_LIVE_RCMD"), dynamic("2", "DYNAMIC_TYPE_WORD"));

        assertEquals(1, published.size());
        assertEquals("https://t.bilibili.com/2", published.get(0).getUrl());
    }

    @Test
    @DisplayName("转发开播动态照常推送")
    void keepsForwardOfLiveDynamic() {
        Dynamic forward = dynamic("1", "DYNAMIC_TYPE_FORWARD");
        forward.setOrigin(dynamic("2", "DYNAMIC_TYPE_LIVE_RCMD"));

        pushRound(forward);

        assertEquals(1, published.size(), "转发是 UP 主自己的动作, 不该被开播动态的规则连坐");
        assertEquals("转发了动态", published.get(0).getAction());
    }

    /**
     * 先空跑一轮建立基线，再喂入待测动态跑第二轮
     * <p>
     * 首轮只记 ID 不推送，且记过的 ID 不会再推，所以待测动态必须在第二轮才首次出现。
     */
    private void pushRound(Dynamic... dynamics) {
        when(api.getDynamicUpdateList()).thenReturn(List.of());
        poll.run();

        when(api.getDynamicUpdateList()).thenReturn(List.of(dynamics));
        poll.run();
    }

    private Dynamic dynamic(String id, String type) {
        Dynamic dynamic = new Dynamic();
        dynamic.setId(id);
        dynamic.setType(type);

        JSONObject author = new JSONObject();
        author.put("mid", UID);
        author.put("name", "测试UP主");
        author.put("pub_ts", Instant.now().getEpochSecond());

        JSONObject modules = new JSONObject();
        modules.put("module_author", author);
        dynamic.setModules(modules);

        return dynamic;
    }
}
