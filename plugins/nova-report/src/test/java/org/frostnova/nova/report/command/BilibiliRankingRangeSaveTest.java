package org.frostnova.nova.report.command;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.BilibiliRankingApplier;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.ConfigurationLabelResolver;
import org.frostnova.nova.core.config.ui.ConfigurationEffectResolver;
import org.frostnova.nova.core.config.ui.ConfigurationDangerResolver;
import org.frostnova.nova.core.config.ui.ConfigurationLevelResolver;
import org.frostnova.nova.core.config.ui.ConfigurationMetadataService;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.config.ui.UpdateCheckService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 排行榜两项配置填出界的值时，保存要回人看得懂的提示
 * <p>
 * 前 N 名填 0、十万，整图高度上限填一百，照存不误的话，下一张图要么空着要么拉得老长。
 * 保存时就该拦下、说清该填哪个范围，而不是写进配置文件再由出图那侧悄悄兜底。
 */
@DisplayName("排行榜两项配置：填出界的值保存时回明确提示")
class BilibiliRankingRangeSaveTest {
    private static final String TOP_N_KEY = BilibiliRankingApplier.TOP_N_KEY;

    private static final String HEIGHT_LIMIT_KEY = BilibiliRankingApplier.HEIGHT_LIMIT_KEY;

    /**
     * 写进配置文件的每一次改动（登录口令自己写的那次也算在内，这里只看带排行榜键的那些）
     */
    private final List<Map<String, String>> written = new ArrayList<>();

    private ConfigUiController controller;

    @BeforeEach
    void setUp() throws IOException {
        written.clear();

        ConfigurationFileService fileService = mock(ConfigurationFileService.class);
        // 存得下的那一路要回写出的键；回 null 会被当成「一个都没改」接着往下走
        when(fileService.write(any())).thenAnswer(invocation -> {
            Map<String, String> changes = invocation.getArgument(0);
            written.add(new LinkedHashMap<>(changes));
            return new ArrayList<>(changes.keySet());
        });

        NovaCoreProperties properties = new NovaCoreProperties();
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword("correct horse battery staple");
        auth.setTotp(false);

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);

        controller = controller(properties, authService, fileService, new NovaBilibiliProperties());
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(NovaCoreProperties properties, ConfigUiAuthService authService,
                                          ConfigurationFileService fileService,
                                          NovaBilibiliProperties bilibiliProperties) {
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        // 这两项是真配置项、登记在设置页元数据里：白名单放它们过去，专靠取值范围那一层拦
        when(metadata.getKnownTypes()).thenReturn(Map.of(
                TOP_N_KEY, "java.lang.Integer",
                HEIGHT_LIMIT_KEY, "java.lang.Integer"));

        ObjectProvider<RuntimeConfigurationApplierContributor> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(new BilibiliRankingApplier(bilibiliProperties)));
        RuntimeConfigurationApplier applier =
                new RuntimeConfigurationApplier(properties, null, provider, TimelineWriter.NONE);

        return new ConfigUiController(
                metadata,
                fileService,
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(ApplicationContext.class)),
                applier,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                authService,
                new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

    @Test
    @DisplayName("前 N 名填 0：回提示说清范围，本批不落盘")
    void topNBelowRangeIsRejectedWithClearPrompt() throws IOException {
        assertRejected(TOP_N_KEY, "0", "最多列出名次", "1 到 500");
    }

    @Test
    @DisplayName("前 N 名填 100000：回提示说清范围，本批不落盘")
    void topNAboveRangeIsRejectedWithClearPrompt() throws IOException {
        assertRejected(TOP_N_KEY, "100000", "最多列出名次", "1 到 500");
    }

    @Test
    @DisplayName("整图高度上限填 100：回提示说清范围，本批不落盘")
    void heightLimitBelowRangeIsRejectedWithClearPrompt() throws IOException {
        assertRejected(HEIGHT_LIMIT_KEY, "100", "整图高度上限", "300 到 30000");
    }

    @Test
    @DisplayName("填的不是整数：回提示说清该填什么，本批不落盘")
    void nonIntegerValueIsRejectedWithClearPrompt() throws IOException {
        assertRejected(TOP_N_KEY, "五十名", "最多列出名次", "整数");
    }

    @Test
    @DisplayName("范围里的值照旧能存（阳性对照）")
    void inRangeValuesStillSave() {
        ResponseEntity<JSONObject> raw = controller.save(Map.of(
                TOP_N_KEY, "50",
                HEIGHT_LIMIT_KEY, "10000"));
        JSONObject result = raw.getBody();

        assertAll(
                () -> assertEquals(200, raw.getStatusCode().value(),
                        "范围里的值该存得下, 实际 body=" + result.toJSONString()),
                () -> assertTrue(result.getBooleanValue("success"),
                        "范围里的值该存得下, 实际 message=" + result.getString("message")),
                () -> assertTrue(written.stream().anyMatch(map -> map.containsKey(TOP_N_KEY)),
                        "范围里的值该真写进配置文件, 实际只写过 " + written));
    }

    /**
     * 出界的值：整批拒、说清该填多少，一个字都不写进配置文件
     */
    private void assertRejected(String key, String value, String labelFragment, String rangeFragment) {
        ResponseEntity<JSONObject> raw = controller.save(Map.of(key, value));
        JSONObject result = raw.getBody();

        assertAll(
                () -> assertEquals(400, raw.getStatusCode().value(),
                        "出界的值整批该拒, 实际 body=" + result.toJSONString()),
                () -> assertFalse(result.getBooleanValue("success"),
                        "出界的值不该存下, 实际 message=" + result.getString("message")),
                () -> assertTrue(result.getString("message") != null
                                && result.getString("message").contains(labelFragment),
                        "提示要写清是哪一项, 实际 message=" + result.getString("message")),
                () -> assertTrue(result.getString("message") != null
                                && result.getString("message").contains(rangeFragment),
                        "提示要说清该填多少, 实际 message=" + result.getString("message")),
                () -> assertTrue(written.stream().noneMatch(map -> map.containsKey(key)),
                        "出界的值写进配置文件就等于门已经开了, 实际写过 " + written));
    }
}
