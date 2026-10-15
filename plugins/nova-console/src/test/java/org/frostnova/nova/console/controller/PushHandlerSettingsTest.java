package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.handler.BilibiliOfflineRewardDigestPushHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("各类通知配置独立显示与保存")
class PushHandlerSettingsTest {
    @TempDir
    Path dir;

    private JSONObject handlers() {
        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        beans.put("report", new BilibiliLiveReportPushHandler(null, null, null, null));
        beans.put("digest", new BilibiliOfflineRewardDigestPushHandler(null, null, null, null));
        beans.put("extra", new ExtraNotice());
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);
        NovaEventHandlerService service = new NovaEventHandlerService(context);
        service.onContextRefreshedEvent();
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        return new PushController(mock(RuntimeConfigurationApplier.class), mock(ConfigurationFileService.class),
                service, new PushTemplateDefaults(properties), mock(PushActivityRecorder.class)).handlers();
    }

    @Test
    @DisplayName("清单明确声明报告版式，其他处理器默认是设置")
    void previewCapabilityComesFromHandler() {
        // 抓声明没有经过接口，导致真实报告失去预览或普通设置误用报告预览。
        for (Object raw : handlers().getJSONArray("handlers")) {
            JSONObject item = JSON.parseObject(JSON.toJSONString(raw));
            boolean report = item.getString("className").equals(BilibiliLiveReportPushHandler.class.getName());
            assertEquals(Boolean.valueOf(report), item.getBoolean("reportLayout"), item.getString("displayName"));
        }
    }

    @Test
    @DisplayName("真实处理器的门槛、版式和未声明的插件在页面上独立工作")
    void allEnabledHandlersHaveTheirOwnSettings() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/push-handler-settings-fixture.mjs",
                "各类通知配置", Map.of("HANDLERS", handlers().getJSONArray("handlers").toJSONString()));
    }

    private static final class ExtraNotice implements NovaEventHandler {
        @Override
        public void handle(NovaExternalBaseEvent event, PushMessage message) {
        }

        @Override
        public Class<? extends NovaExternalBaseEvent> getEventType() {
            return NovaExternalBaseEvent.class;
        }

        @Override
        public JSONObject getDefaultParams() {
            return new JSONObject();
        }

        @Override
        public String displayName() {
            return "额外通知";
        }

        @Override
        public List<HandlerOption> options() {
            return List.of(HandlerOption.integer("retries", "重试次数", "发送重试次数", 3, 0, 10));
        }
    }
}
