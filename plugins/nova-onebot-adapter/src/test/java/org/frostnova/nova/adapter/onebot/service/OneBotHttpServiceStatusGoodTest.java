package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动、保存连接、重连那一刻的体检也看 {@code get_status} 的 good
 *
 * <h2>这里守的是什么</h2>
 * 实现自己报「运行异常」时，启动、保存连接或重连的那一刻若只记「HTTP 正常」，
 * 健康页要等下一轮定期体检（默认 300 秒）才翻回异常；关掉定期检测就一直显示正常，
 * 告警也跟着晚或不来。体检与定期体检必须用同一个判法。
 */
@DisplayName("体检看实现自身的 good")
class OneBotHttpServiceStatusGoodTest {
    private static final String SENDER_NAME = "qq-onebot";

    private OneBotConnectionState state;

    private OneBotAdapterPluginProperties properties;

    private OneBotSender sender;

    private ThreadPoolTaskScheduler scheduler;

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        state = new OneBotConnectionState();
        properties = new OneBotAdapterPluginProperties();
        // 这里量的是体检那一刻的读数，定期检测只会把结论冲掉
        properties.getDetect().setEnableHttpDetect(false);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.initialize();

        sender = new OneBotSender();
        sender.setName(SENDER_NAME);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("token");
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * 🔴 实现自己说「我跑得不好」时，体检只看见版本与登录信息取得到就记「HTTP 正常」。
     * 使用者在健康页看到的是正常，而推送可能已经在丢。
     */
    @Test
    @DisplayName("⚠️ 体检那一刻实现自身报异常, 立刻记「实现自身状态异常」, 账号照旧看 online")
    void checkLooksAtGoodFlag() {
        OneBotHttpService service = serviceWith(statusOf(false, true));

        service.check(sender);

        OneBotConnectionState.Status http = http();
        assertEquals(OneBotConnectionState.Kind.SERVICE_ABNORMAL, http.kind(),
                "实现自身报异常就该当场记异常, 不能等到定期体检: " + http);
        assertEquals("实现自身状态异常", http.detail(), http.toString());

        OneBotConnectionState.Status account = account();
        assertEquals(OneBotConnectionState.Kind.OK, account.kind(),
                "账号在线是另一件事, 不跟着 good 走: " + account);
    }

    @Test
    @DisplayName("阴性对照：good=true 时记正常, 那句话带版本与登录账号")
    void checkOkCarriesVersionAndAccount() {
        OneBotHttpService service = serviceWith(statusOf(true, true));

        service.check(sender);

        OneBotConnectionState.Status http = http();
        assertEquals(OneBotConnectionState.Kind.OK, http.kind(), http.toString());
        assertTrue(http.detail().contains("1.2.3"), http.toString());
        assertTrue(http.detail().contains("bot"), http.toString());
    }

    private OneBotHttpService serviceWith(OneBotHttpAdapter http) {
        return new OneBotHttpService(scheduler, executor, properties, http, new OneBotMessageConverter(), state);
    }

    /**
     * 版本、登录信息都取得到，唯独 {@code get_status} 里 good 与 online 按入参
     */
    private OneBotHttpAdapter statusOf(boolean good, boolean online) {
        OneBotHttpAdapter http = mock(OneBotHttpAdapter.class);
        when(http.getVersionInfo(any(), any()))
                .thenReturn(new JSONObject().fluentPut("app_version", "1.2.3"));
        when(http.getLoginInfo(any(), any()))
                .thenReturn(new JSONObject().fluentPut("nickname", "bot").fluentPut("user_id", 10001L));
        when(http.getStatus(any(), any()))
                .thenReturn(new JSONObject().fluentPut("good", good).fluentPut("online", online));
        return http;
    }

    private OneBotConnectionState.Status http() {
        return state.all().get(SENDER_NAME).getHttp();
    }

    private OneBotConnectionState.Status account() {
        return state.all().get(SENDER_NAME).getAccount();
    }
}
