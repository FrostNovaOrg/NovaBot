package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 首次推送提示这一件在没有数据源时也必须装得起来
 * <p>
 * 数据源由平台插件提供，核心自己不起一个。只有核、没有插件时，
 * 若把数据源当成必填依赖，整个进程就起不来——而「还没接平台」
 * 本来就是一种合法的运行形态。就绪回调在没有数据源时应当直接返回，
 * 不能把空依赖当成已经补记过。
 */
@DisplayName("首次推送提示的容器装配")
class FirstPushTipServiceWiringTest {
    @TempDir
    Path stateDir;

    @Test
    @DisplayName("甲静音时不认领首推提示，乙在同群不静音时照常提示")
    @SuppressWarnings("unchecked")
    void firstTipUsesOriginalChannel() {
        var props = new NovaCoreProperties();
        var gate = new PushGate(props);
        var source = Mockito.mock(AbstractDataSource.class);
        var user = new PushUser(); user.setUid(1L);
        var a = new PushTarget(); a.setUser(user);
        a.setPlatform("sample"); a.setType(PushTargetType.GROUP); a.setNum(100L);
        var quiet = new PushTarget.QuietHours(); quiet.setMode("custom");
        quiet.setStart(LocalTime.now().minusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        quiet.setEnd(LocalTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        a.setQuietHours(quiet);
        var second = new PushUser(); second.setUid(2L);
        var b = new PushTarget(); b.setUser(second); b.setPlatform("sample"); b.setType(a.getType()); b.setNum(100L);
        var off = new PushTarget.QuietHours(); off.setMode("off"); b.setQuietHours(off);
        user.setTargets(List.of(a)); second.setTargets(List.of(b));
        Mockito.when(source.getAllUsers()).thenReturn(List.of(user, second));
        ReflectionTestUtils.setField(gate, "dataSource", source);
        var tips = Mockito.mock(FirstPushTipService.class);
        Mockito.when(tips.enabled()).thenReturn(true);
        Mockito.when(tips.claim("sample", a.getType(), 100L)).thenReturn(true);
        Mockito.when(tips.text(a.getType())).thenReturn("菜单提示");
        var http = Mockito.mock(HttpUtil.class);
        Mockito.when(http.postJson(ArgumentMatchers.anyString(), ArgumentMatchers.anyMap(), ArgumentMatchers.anyMap()))
                .thenReturn(JSONObject.of("code", 0));
        ObjectProvider<AtAllPermissionResolver> resolvers = Mockito.mock(ObjectProvider.class);
        Mockito.when(resolvers.iterator()).thenAnswer(i -> Collections.emptyIterator());
        var sender = new NovaMessageSender(http, Mockito.mock(NovaSenderService.class),
                new PushActivityRecorder(TimelineWriter.NONE), gate, TimelineWriter.NONE,
                Mockito.mock(AtAllQuotaService.class), resolvers, tips);
        var platform = new Sender(); platform.setUrl("https://bot.example.test/send");
        try {
            var message = Message.create("sample", a.getType(), 100L, "通知").get(0);
            message.setChannel(a);
            ReflectionTestUtils.invokeMethod(sender, "tipAfterFirstPush", platform, message);
            Mockito.verify(tips, Mockito.never()).claim("sample", a.getType(), 100L);
            message.setChannel(b);
            ReflectionTestUtils.invokeMethod(sender, "tipAfterFirstPush", platform, message);
            Mockito.verify(tips).claim("sample", a.getType(), 100L);
            Mockito.verify(http).postJson(ArgumentMatchers.anyString(), ArgumentMatchers.anyMap(), ArgumentMatchers.anyMap());
        } finally { sender.shutdown(); }
    }

    @Test
    @DisplayName("没有数据源 bean 时上下文起得来，就绪回调也不抛")
    void contextStartsWithoutDataSourceBean() {
        WiringConfig.stateDir = stateDir;
        new ApplicationContextRunner()
                .withUserConfiguration(WiringConfig.class)
                .withBean(FirstPushTipService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FirstPushTipService.class);
                    assertDoesNotThrow(() ->
                            context.getBean(FirstPushTipService.class).onApplicationReadyEvent());
                });
    }

    /**
     * 只给状态仓与配置两个侧件，故意不登记数据源
     */
    @Configuration
    static class WiringConfig {
        static Path stateDir;

        @Bean
        NovaCoreProperties novaCoreProperties() {
            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(stateDir.resolve("data.json").toString());
            return properties;
        }

        @Bean
        NovaStateStore novaStateStore(NovaCoreProperties properties) {
            return new NovaStateStore(properties);
        }
    }
}
