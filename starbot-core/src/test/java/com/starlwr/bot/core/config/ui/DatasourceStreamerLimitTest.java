package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.service.StarBotSenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 控制台保存推送配置时的主播数上限
 * <p>
 * 上限在数据源那一层已经拦得住，但那层只会拒收并写一行日志——使用者在界面上按了保存、
 * 页面回「已保存」，超出的那几位却从此不会被监控，而<b>界面上看不出任何异常</b>。
 * 所以保存这一步必须自己把话说清楚：拒绝写入，并当场说明上限是多少、这次提交了多少位。
 */
@DisplayName("控制台推送配置的主播数上限")
class DatasourceStreamerLimitTest {
    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private ConfigUiController controller;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());

        // 结构校验是另一条关卡，本组用例一律放行，只看语义这一关
        ConfigurationValidator validator = mock(ConfigurationValidator.class);
        when(validator.validateDatasource(anyString(), any())).thenReturn(List.of());

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSenderNames()).thenReturn(Set.of());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenReturn(Stream.empty());

        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                validator,
                senderService,
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.service.EventStreamTokenService.class));
    }

    @Test
    @DisplayName("提交超过 10 位启用的主播应以 400 回绝, 并说明上限与本次提交数")
    void shouldRejectMoreThanTenEnabledStreamers() {
        ResponseEntity<JSONObject> response = save(datasource(11, 0));

        assertEquals(400, response.getStatusCode().value(), "超员属于提交内容的问题，应当以 400 回绝而不是照单收下");

        JSONObject body = response.getBody();
        assertNotNull(body, "回绝也要带上说明，否则界面只能显示一句「保存失败」");
        assertEquals(false, body.getBooleanValue("success"));

        String message = body.getString("message");
        assertTrue(message.contains("10 位"), "必须说清上限是多少: " + message);
        assertTrue(message.contains("11 位"), "必须说清这次提交了多少位，使用者才知道该删几个: " + message);
    }

    @Test
    @DisplayName("停用的主播不计入上限, 10 位启用加 2 位停用应保存成功")
    void shouldCountOnlyEnabledStreamers() {
        // 停用的条目只是留在配置里备查，运行期并不会被监控，计入上限等于凭空缩小可用名额
        ResponseEntity<JSONObject> response = save(datasource(10, 2));

        assertEquals(200, response.getStatusCode().value(), "未超员的提交应当正常保存");
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getBooleanValue("success"), "实际为: " + response.getBody());
    }

    @Test
    @DisplayName("运行状态应带上主播数上限, 供界面显示「已用 / 上限」")
    void statusShouldCarryStreamerLimit() {
        assertEquals(10, controller.status().getIntValue("streamerLimit"), "界面上的上限得由后端给，不能各写一份");
    }

    /**
     * 保存推送配置
     * @param content 推送配置内容
     * @return 保存结果
     */
    private ResponseEntity<JSONObject> save(String content) {
        return controller.saveDatasource(new JSONObject().fluentPut("content", content));
    }

    /**
     * 构造一份推送配置，前若干位启用、其余停用
     * @param enabled 启用的主播数
     * @param disabled 停用的主播数
     * @return 推送配置内容
     */
    private String datasource(int enabled, int disabled) {
        JSONArray users = new JSONArray();
        for (int i = 0; i < enabled + disabled; i++) {
            users.add(new JSONObject()
                    .fluentPut("uid", 10000 + i)
                    .fluentPut("platform", "bilibili")
                    .fluentPut("enabled", i < enabled)
                    .fluentPut("targets", new JSONArray()));
        }
        return users.toJSONString();
    }
}
