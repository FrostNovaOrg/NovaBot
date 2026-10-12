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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 抓的用户故障：常用的整组掉进高级（新标注没进 schema、或核心那几节的级别落了空），
     * 行内短说明与问号里的整段说明对不上号，静音时段认不出是时刻、只能手敲。
     * 没标短说明的项两栏都是 null，界面照旧在行内摆整段说明——插件不标也不少字。
     */
    @Test
    @DisplayName("schema 带出级别、短说明、问号里的整段说明与值的种类；没标的为 null")
    void schemaExposesLevelSummaryAndFormat() throws IOException {
        try (AnnotationConfigApplicationContext context = context()) {
            JSONObject schema = controller(context, new ConfigurationMetadataService()).schema();

            JSONObject quietStart = field(schema, "novabot.core.push.quiet-start");
            assertEquals("COMMON", quietStart.getString("level"));
            assertEquals("TIME_OF_DAY", quietStart.getString("format"));
            assertEquals(quietStart.getString("description"), quietStart.getString("help"),
                    "问号里放的就是整段说明");

            JSONObject queue = field(schema, "novabot.core.alert.retry-queue-size");
            assertEquals("ADVANCED", queue.getString("level"));
            assertEquals("队列满后丢弃最旧的告警，并记录日志。", queue.getString("summary"));
            assertNull(queue.get("help"), "标了不给问号的，help 为 null");

            // 核心那一节（直播数据）的字段同样标得上级别：常用的留在常用，路径进高级
            assertEquals("COMMON", field(schema, "novabot.core.live.save-live-data").getString("level"));
            JSONObject dataPath = field(schema, "novabot.core.live.live-data-path");
            assertEquals("ADVANCED", dataPath.getString("level"));
            assertEquals("PATH", dataPath.getString("format"));

            JSONObject port = field(schema, "server.port");
            assertNull(port.get("summary"), "没标短说明：summary 为 null，界面照旧摆整段说明");
            assertNull(port.get("help"));
            assertNull(port.get("format"));
        }
    }

    @Test
    @DisplayName("插件项的中文名与单位原样进 schema（名字来路由插件模块自证）")
    void pluginLabelAndUnitPassThroughSchema() throws IOException {
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getFields()).thenReturn(List.of(
                new ConfigurationMetadataService.ConfigurationField(
                        "novabot.bilibili.account.anonymous",
                        "java.lang.Boolean",
                        "完全不使用登录凭据运行",
                        false),
                new ConfigurationMetadataService.ConfigurationField(
                        "novabot.bilibili.network.api-retry-interval",
                        "java.lang.Integer",
                        "接口请求失败后的重试间隔，单位：毫秒",
                        3000)));
        try (AnnotationConfigApplicationContext context = context()) {
            ConfigurationLabelResolver labels = new ConfigurationLabelResolver(context) {
                @Override
                public Map<String, String> getLabels() {
                    Map<String, String> result = new HashMap<>(super.getLabels());
                    result.put("novabot.bilibili.account.anonymous", "匿名模式");
                    return Map.copyOf(result);
                }
            };
            JSONObject schema = controller(context, metadata, labels).schema();
            assertEquals("匿名模式",
                    field(schema, "novabot.bilibili.account.anonymous").getString("label"));
            assertEquals("毫秒",
                    field(schema, "novabot.bilibili.network.api-retry-interval").getString("unit"));
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
