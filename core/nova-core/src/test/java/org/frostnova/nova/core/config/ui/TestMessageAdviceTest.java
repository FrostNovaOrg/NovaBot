package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 试发失败的排查提示指向还存在的页面
 * <p>
 * 提示里原先写着「对照『运行状态』页排查」，而导航早改成了首页／日志／连接／设置四页——
 * 照着这句话找页的人会扑一个空，排查也就停在那一步。机器人那张卡在「连接」页上，
 * 提示要指得到它；这句话在连接页与首次设置向导两处显示，向导里人不在连接页上，
 * 措辞不能写「本页」「上方」这类只有站在连接页上才讲得通的词。
 */
@DisplayName("试发失败排查提示指向连接页")
class TestMessageAdviceTest {
    @TempDir
    Path dir;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());

        NovaSenderService senderService = mock(NovaSenderService.class);
        when(senderService.getSenderNames()).thenReturn(java.util.Set.of());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(java.util.List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenReturn(Stream.empty());

        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senderService,
                messageSenderFailingWith("group not found"),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                // 不 mock 这三个具体类：内联 mock 要改写字节码，clean 构建下实测会抛「could not instrument」。
                // 空上下文即可，本组用例不看生效时机与危险标注
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new org.frostnova.nova.core.service.PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));
    }

    /**
     * 推送接口那头回了错（code 非 0）：最常见的失败档，排查提示在这一档给出
     * @param message 接口回的说明
     * @return 回错的那只发送器
     */
    private static NovaMessageSender messageSenderFailingWith(String message) {
        NovaMessageSender sender = mock(NovaMessageSender.class);
        when(sender.sendNow(any())).thenReturn(new JSONObject()
                .fluentPut("code", 1404)
                .fluentPut("message", message));
        return sender;
    }

    @Test
    @DisplayName("发送失败时的 advice 指向连接页的机器人卡, 不再提不存在的运行状态页")
    void adviceShouldPointAtTheLinksPage() {
        JSONObject result = controller.testMessage(new JSONObject()
                .fluentPut("platform", "bilibili")
                .fluentPut("type", 1)
                .fluentPut("num", 123456L));

        assertFalse(result.getBooleanValue("success"), "接口回错时这一趟算失败");
        assertTrue(result.getString("message").contains("发送失败"), "失败说明照旧带上接口那头的话");

        String advice = result.getString("advice");
        assertNotNull(advice, "失败这一档要给排查提示");
        assertFalse(advice.contains("运行状态"), "导航里没有这一页，提它等于指一条死路: " + advice);
        assertTrue(advice.contains("连接"), "提示要指到连接页: " + advice);
        assertTrue(advice.contains("机器人"), "提示要指到连接页的机器人卡: " + advice);
        assertFalse(advice.contains("本页"), "这句话也在首次设置向导里显示，不能写只有站在连接页上才讲得通的词: " + advice);
        assertFalse(advice.contains("上方"), "同上，向导里没有「上方」那张卡: " + advice);
    }
}
