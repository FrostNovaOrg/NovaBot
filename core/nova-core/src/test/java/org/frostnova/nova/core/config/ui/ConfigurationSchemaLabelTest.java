package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.properties.EventStreamProperties;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * schema 把中文名和单位交给界面
 * <p>
 * 标题不再是键名末段；单位从说明里抽出，说明原文不动。
 */
@DisplayName("设置页 schema 的中文名与单位")
class ConfigurationSchemaLabelTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("quiet-start 中文名、convergence-interval 单位为秒、enabled 无单位")
    void schemaExposesChineseNameAndUnit() throws IOException {
        try (AnnotationConfigApplicationContext context = context()) {
            ConfigUiController controller = controller(context, new ConfigurationMetadataService());
            JSONObject schema = controller.schema();
            assertEquals("静音时段 · 开始",
                    field(schema, "novabot.core.push.quiet-start").getString("label"));
            assertEquals("秒",
                    field(schema, "novabot.core.alert.convergence-interval").getString("unit"));
            assertNull(field(schema, "novabot.core.push.enabled").get("unit"));
        }
    }

    @Test
    @DisplayName("未标注的插件项，label 仍为键名末段")
    void unlabeledPluginFieldFallsBackToLeaf() throws IOException {
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getFields()).thenReturn(List.of(
                new ConfigurationMetadataService.ConfigurationField(
                        "novabot.bilibili.account.anonymous",
                        "java.lang.Boolean",
                        "完全不使用登录凭据运行",
                        false)));
        try (AnnotationConfigApplicationContext context = context()) {
            JSONObject item = field(controller(context, metadata).schema(),
                    "novabot.bilibili.account.anonymous");
            assertEquals("anonymous", item.getString("label"));
        }
    }

    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(NovaCoreProperties.class, EventStreamProperties.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(AnnotationConfigApplicationContext context,
                                          ConfigurationMetadataService metadata) throws IOException {
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
                new ConfigurationLabelResolver(context),
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
