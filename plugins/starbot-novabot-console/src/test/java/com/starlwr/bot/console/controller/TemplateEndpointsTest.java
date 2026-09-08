package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.NovaExternalBaseEvent;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认模板接口：读、写、拒收
 * <p>
 * {@code GET/POST /api/templates} 改的是<b>所有用默认的通道</b>。接线没接到、
 * 400 支没走到、校验失败却写了盘——这三件事在界面上都只是「按了保存没反应」
 * 或「保存成功但群里的模板没变」，功能测试点一遍看不出来。
 */
@DisplayName("默认模板接口")
class TemplateEndpointsTest {
    private static final String FACTORY = "{uname} 正在直播 {title}";

    @TempDir
    Path dir;

    private PushTemplateDefaults defaults;

    private FakeHandler handler;

    private PushController controller;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        defaults = new PushTemplateDefaults(properties);
        handler = new FakeHandler();

        StarBotEventHandlerService handlers = mock(StarBotEventHandlerService.class);
        when(handlers.getHandler(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (FakeHandler.class.getName().equals(name)) {
                return Optional.of(handler);
            }
            return Optional.empty();
        });

        controller = controller(properties, defaults, handlers);
    }

    @Test
    @DisplayName("没改过时读出来是空覆盖，文件也不必存在")
    void getReturnsEmptyWhenUntouched() {
        JSONObject body = controller.templates();
        assertTrue(body.getBooleanValue("success"));
        assertTrue(body.getJSONObject("defaults").isEmpty(), "没人改过时覆盖该是空的");
        assertFalse(Files.exists(dir.resolve("template-defaults.json")),
                "没人改过时不该凭空建出 template-defaults.json");
    }

    @Test
    @DisplayName("写下去的覆盖能读回来")
    void postThenGetRoundTrips() {
        JSONObject req = new JSONObject()
                .fluentPut("className", FakeHandler.class.getName())
                .fluentPut("params", new JSONObject().fluentPut("message", "{uname} 开播了"));

        ResponseEntity<JSONObject> saved = controller.saveTemplate(req);
        assertEquals(200, saved.getStatusCode().value());
        assertTrue(saved.getBody().getBooleanValue("success"), saved.getBody().toString());

        JSONObject read = controller.templates();
        JSONObject stored = read.getJSONObject("defaults").getJSONObject(FakeHandler.class.getName());
        assertEquals("{uname} 开播了", stored.getString("message"));
        assertTrue(Files.exists(dir.resolve("template-defaults.json")), "写完之后文件该在");
    }

    @Test
    @DisplayName("没有这个处理器时 400，一个字也不写")
    void unknownHandlerIs400() {
        JSONObject req = new JSONObject()
                .fluentPut("className", "no.such.Handler")
                .fluentPut("params", new JSONObject().fluentPut("message", "不该落盘"));

        ResponseEntity<JSONObject> response = controller.saveTemplate(req);
        assertEquals(400, response.getStatusCode().value());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertFalse(Files.exists(dir.resolve("template-defaults.json")),
                "拒收的那一次不许留下半份默认模板");
    }

    @Test
    @DisplayName("校验不过时把 issues 交出来，且一个字也不写")
    void invalidTemplateReturnsIssuesWithoutWriting() {
        JSONObject req = new JSONObject()
                .fluentPut("className", FakeHandler.class.getName())
                .fluentPut("params", new JSONObject().fluentPut("message", "{uname} {tittle}"));

        ResponseEntity<JSONObject> response = controller.saveTemplate(req);
        assertEquals(200, response.getStatusCode().value(), "校验失败不是 400：处理器在，只是内容写错");
        assertFalse(response.getBody().getBooleanValue("success"));
        assertTrue(response.getBody().containsKey("issues"), "issues 必须上抛，界面才知道哪一格写错");
        assertTrue(response.getBody().getJSONArray("issues").toString().contains("{tittle}"));
        assertFalse(Files.exists(dir.resolve("template-defaults.json")),
                "拒收的那一次不许留下半份默认模板");
    }

    @SuppressWarnings("unchecked")
    private PushController controller(StarBotCoreProperties properties,
                                     PushTemplateDefaults templateDefaults,
                                     StarBotEventHandlerService handlers) {
        return new PushController(
                mock(com.starlwr.bot.core.config.ui.RuntimeConfigurationApplier.class),
                mock(com.starlwr.bot.core.config.ui.ConfigurationFileService.class),
                handlers,
                templateDefaults,
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class));
    }

    private static final class FakeHandler implements NovaEventHandler {
        @Override
        public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
        }

        @Override
        public Class<? extends NovaExternalBaseEvent> getEventType() {
            return NovaExternalBaseEvent.class;
        }

        @Override
        public JSONObject getDefaultParams() {
            JSONObject params = new JSONObject();
            params.put("message", FACTORY);
            return params;
        }

        @Override
        public List<String> placeholders() {
            return List.of("{uname}", "{title}", "{cover}", "{url}", "{at}", "{next}", "{at=all}");
        }

        @Override
        public List<String> attachmentPlaceholders() {
            return List.of("{cover}");
        }

        @Override
        public List<HandlerOption> options() {
            return List.of();
        }
    }
}
