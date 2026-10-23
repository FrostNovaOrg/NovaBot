package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/** 设置页收到的 schema 必须带出真字段的候选项及显示名。 */
@DisplayName("扫码登录方式的候选写法送到设置页")
class QrLoginModeChoicesTest {
    @TempDir
    Path dir;

    @Test
    @DisplayName("扫码登录方式带出 tv、web 两项，凭据文件路径不带")
    void qrLoginModeCarriesItsChoices() throws IOException {
        try (var context = context()) {
            JSONObject schema = controller(context, new ConfigurationMetadataService()).schema();
            assertEquals(List.of("tv", "web"), field(schema,
                    "novabot.bilibili.account.qr-code-login-mode").getJSONArray("choices").toJavaList(String.class));
            assertNull(field(schema, "novabot.bilibili.account.cookie-path").get("choices"));
        }
    }

    @Test
    @DisplayName("告警目标显示群和好友，取值仍为 1 和 0")
    void alertTypeCarriesValuesAndLabels() throws IOException {
        try (var context = context()) {
            JSONObject item = field(controller(context, new ConfigurationMetadataService()).schema(),
                    "novabot.adapter.onebot.alert.type");
            assertEquals(List.of("1", "0"), item.getJSONArray("choices").toJavaList(String.class));
            assertEquals(JSONObject.of("1", "群", "0", "好友"), item.getJSONObject("choiceLabels"));
            assertNull(item.get("unit"));
            assertFalse(item.getString("description").contains("不要填 2"));
        }
    }

    @Test
    @DisplayName("版本号、端口、数据库编号与收件号码没有计量单位")
    void identifiersHaveNoUnit() throws IOException {
        try (var context = context()) {
            JSONObject schema = controller(context, new ConfigurationMetadataService()).schema();
            for (String name : List.of("novabot.core.config-ui.agreement.accepted-version",
                    "spring.data.redis.port", "spring.data.redis.database", "spring.mail.port", "server.port",
                    "novabot.adapter.onebot.alert.num")) {
                JSONObject item = field(schema, name);
                assertNull(item.get("unit"), name);
                assertFalse(item.getString("description").contains("单位："), name);
            }
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.register(NovaCoreProperties.class, NovaBilibiliProperties.class, OneBotAdapterPluginProperties.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(AnnotationConfigApplicationContext context,
                                          ConfigurationMetadataService metadata) throws IOException {
        return controller(context, metadata, new ConfigurationLabelResolver(context));
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(AnnotationConfigApplicationContext context,
                                          ConfigurationMetadataService metadata,
                                          ConfigurationLabelResolver labelResolver) throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "novabot:\n  core:\n    config-ui:\n      enabled: true\n",
                StandardCharsets.UTF_8);
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        return new ConfigUiController(
                metadata,
                new ConfigurationFileService(config),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                new ConfigurationLevelResolver(context),
                labelResolver,
                new ConfigurationEffectResolver(context),
                new ConfigurationDangerResolver(context),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));
    }

    private static JSONObject field(JSONObject schema, String name) {
        JSONArray groups = schema.getJSONArray("groups");
        for (int i = 0; i < groups.size(); i++) {
            JSONArray fields = groups.getJSONObject(i).getJSONArray("fields");
            for (int j = 0; j < fields.size(); j++) {
                JSONObject item = fields.getJSONObject(j);
                if (name.equals(item.getString("name"))) {
                    return item;
                }
            }
        }
        JSONArray ungrouped = schema.getJSONArray("ungrouped");
        if (ungrouped != null) {
            for (int i = 0; i < ungrouped.size(); i++) {
                JSONObject item = ungrouped.getJSONObject(i);
                if (name.equals(item.getString("name"))) {
                    return item;
                }
            }
        }
        throw new AssertionError("schema 里没有 " + name);
    }
}
