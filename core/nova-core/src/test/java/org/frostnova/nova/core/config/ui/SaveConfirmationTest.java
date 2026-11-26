package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 插件申报了「存之前先问一句」的取值：没确认就一项不写，确认了照常写
 * <p>
 * 抓的用户故障：推送接口的来源名单换成只有外部地址，保存即生效，本机上的程序从下一个请求起
 * 就推不进来，而保存时没有任何提示。这里用一个申报了确认的插件桩量保存口本身：
 * 要问的值没带确认时配置文件一个字不动、回包带上要问的话；带了确认照常写；
 * 不用问的值直接写；写错的值照旧整批拒绝，不走这一问。
 */
@DisplayName("保存前先确认：没确认不写文件，确认了照常写")
class SaveConfirmationTest {
    private static final String KEY = "server.port";
    private static final String RISKY = "80";
    private static final String QUESTION = "改成 80 端口要先问一句，确定吗？";
    private static final String CONSOLE_ALLOW_IPS = "novabot.core.config-ui.allow-ips";

    @TempDir
    Path dir;

    private Path config;

    private ConfigUiController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        config = dir.resolve("application.yml");
        Files.writeString(config, "server:\n  port: 7827\n");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        RuntimeConfigurationApplierContributor asking = new RuntimeConfigurationApplierContributor() {
            @Override
            public Map<String, Consumer<String>> appliers() {
                return Map.of();
            }

            @Override
            public Map<String, Function<String, String>> valueValidators() {
                return Map.of(KEY, value -> value.matches("\\d+") ? null : KEY + "：请填写整数");
            }

            @Override
            public Map<String, Function<String, String>> saveConfirmations() {
                return Map.of(KEY, value -> RISKY.equals(value) ? QUESTION : null);
            }
        };
        controller = new ConfigUiController(
                new ConfigurationMetadataService(),
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
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).contributors(List.of(asking)).build(),
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

    @Test
    @DisplayName("要问的值没带确认：一项不写，回包带上要问的那句话")
    void unconfirmedRiskyValueIsNotWritten() throws Exception {
        // 抓的故障：只含外部地址的新名单不问就写，本机推送当场被挡
        String before = Files.readString(config);
        ResponseEntity<JSONObject> response = controller.save(Map.of(KEY, RISKY, "server.address", "0.0.0.0"));

        JSONObject body = response.getBody();
        assertEquals(200, response.getStatusCode().value());
        assertFalse(body.getBooleanValue("success"));
        assertEquals(1, body.getJSONArray("confirm").size());
        JSONObject question = body.getJSONArray("confirm").getJSONObject(0);
        assertEquals(KEY, question.getString("name"));
        assertEquals(QUESTION, question.getString("body"));
        assertEquals(before, Files.readString(config), "没确认的一批，连同一批里不用问的那项也不写");
    }

    @Test
    @DisplayName("点了确定（带 confirmed）：照常写")
    void confirmedRiskyValueIsWritten() throws Exception {
        ResponseEntity<JSONObject> response = controller.save(Map.of(KEY, RISKY), true);

        assertTrue(response.getBody().getBooleanValue("success"), response.getBody()::toString);
        assertNull(response.getBody().get("confirm"));
        assertEquals(RISKY, new ConfigurationFileService(config).read().get(KEY));
    }

    @Test
    @DisplayName("不用问的值：直接写，不回问话")
    void ordinaryValueIsWrittenWithoutAsking() throws Exception {
        // 抓的故障：名单里留着本机地址也被拦下来问，用户每存一次都要多点一下，久了就不看直接点确定
        ResponseEntity<JSONObject> response = controller.save(Map.of(KEY, "7828"));

        assertTrue(response.getBody().getBooleanValue("success"), response.getBody()::toString);
        assertNull(response.getBody().get("confirm"));
        assertEquals("7828", new ConfigurationFileService(config).read().get(KEY));
    }

    @Test
    @DisplayName("写错的值：照旧整批拒绝，不走这一问")
    void invalidValueIsRejectedNotAsked() throws Exception {
        // 抓的故障：写错的名单先弹一句「确定吗」，点了确定才说写错了，问了等于白问
        String before = Files.readString(config);
        ResponseEntity<JSONObject> response = controller.save(Map.of(KEY, "八十"));

        assertEquals(400, response.getStatusCode().value());
        assertNull(response.getBody().get("confirm"));
        assertEquals(before, Files.readString(config));
    }

    @Test
    @DisplayName("没动列表项、只改了别的项：配置文件里不会多出那一项")
    void untouchedListSettingStaysOutOfTheFile() throws Exception {
        // 抓的故障：设置页把默认名单画进框里之后，存整页时把没动过的那一项也写进文件，
        // 从此程序默认值再变它也跟不上
        assertEquals(List.of("127.0.0.1/32", "::1/128"), new ConfigurationMetadataService().getFields().stream()
                .filter(field -> field.name().equals(CONSOLE_ALLOW_IPS)).findFirst().orElseThrow().defaultValue());

        assertTrue(controller.save(Map.of(KEY, "7828")).getBody().getBooleanValue("success"));

        assertFalse(Files.readString(config).contains("allow-ips"), Files.readString(config));
        assertFalse(new ConfigurationFileService(config).read().containsKey(CONSOLE_ALLOW_IPS));
    }
}
