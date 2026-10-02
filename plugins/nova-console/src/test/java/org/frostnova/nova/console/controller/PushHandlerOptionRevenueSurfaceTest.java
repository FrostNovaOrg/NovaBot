package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/handlers} 把版式项的「随金额」栏交给前端
 * <p>
 * 版式区照这一栏灰掉不出图的项。栏位要是没随清单序列化出去，插件那边标得再对，
 * 控制台也拿不到——两头都不会报错，只是灰从来不出来。这里钉的是<b>这一跳</b>：
 * 处理器自报的 options 经控制器交出去之后，栏位还在、值没走样。
 * 表上标了什么、画图是不是真照标的来，由报告插件那边另一把尺对着量，两层分开。
 * <p>
 * 用工厂方法造的项当第三方插件那一半：它们没标随金额，序列化出去该是「无关」，
 * 而不是栏位整个消失——前端没法把「没这一栏」与「无关」区分开时，只能一律当无关。
 */
@DisplayName("处理器清单带版式项的随金额栏")
class PushHandlerOptionRevenueSurfaceTest {
    private static final String CLASS_NAME = "demo.MarkedHandler";

    @TempDir
    Path dir;

    private JSONObject handlers() {
        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        beans.put(CLASS_NAME, new Marked());
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);
        NovaEventHandlerService service = new NovaEventHandlerService(context);
        service.onContextRefreshedEvent();

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        PushController controller = new PushController(
                mock(RuntimeConfigurationApplier.class),
                mock(ConfigurationFileService.class),
                service,
                new PushTemplateDefaults(properties),
                mock(PushActivityRecorder.class));
        return controller.handlers();
    }

    @Test
    @DisplayName("🔴 工厂方法造的项缺省为「无关」，且这一栏真的序列化出去了")
    void factoryBuiltOptionsCarryUnrelatedByDefault() {
        JSONObject result = handlers();
        String json = JSON.toJSONString(result);

        List<String> red = new java.util.ArrayList<>();
        try {
            assertTrue(result.getBooleanValue("success"), "清单接口没回成功");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        JSONArray options = null;
        try {
            JSONObject item = result.getJSONArray("handlers").getJSONObject(0);
            options = item.getJSONArray("options");
            assertTrue(options != null && !options.isEmpty(), "这一项没有版式项，这一格什么都没量到");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (options != null) {
            try {
                JSONObject first = options.getJSONObject(0);
                assertEquals("UNRELATED", first.getString("revenueVisibility"),
                        "工厂方法造的项该缺省为「无关」，序列化出去却是："
                                + first.getString("revenueVisibility") + "（没这一栏时为 null）");
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
            try {
                assertTrue(json.contains("revenueVisibility"),
                        "整份回包里找不到 revenueVisibility——栏位没有随清单序列化出去，前端的灰无从谈起");
            } catch (Throwable t) {
                red.add("④ " + t.getMessage());
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("🔴 标了随金额的项，值与原因句原样过清单这一跳")
    void markedOptionSurvivesTheHop() {
        JSONObject result = handlers();
        JSONArray options = result.getJSONArray("handlers").getJSONObject(0).getJSONArray("options");

        // 第 0、1 项是工厂方法造的（无关），第 2 项带全九栏
        JSONObject marked = options.getJSONObject(2);
        assertEquals("ONLY_WHEN_SHOWN", marked.getString("revenueVisibility"), "随金额的值走了样");
        assertEquals("隐藏金额的会话里整榜不出", marked.getString("revenueNote"), "原因句没原样到前端");
        assertEquals("plain_switch", options.getJSONObject(0).getString("key"), "清单顺序不该被这里改动");
    }

    private static final class Marked implements NovaEventHandler {
        @Override
        public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
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
        public List<HandlerOption> options() {
            return List.of(
                    HandlerOption.bool("plain_switch", "普通开关", "第三方插件没标随金额的项", true),
                    HandlerOption.integer("plain_number", "普通数字", "同样没标", 5, 0, 20),
                    // 与报告插件那张表同一种标法：全九栏构造，随金额＋原因句
                    new HandlerOption("gift_ranking", "流水排行", "展示前几名，0 为不展示",
                            HandlerOption.Type.INTEGER, 5, 0, 20,
                            HandlerOption.RevenueVisibility.ONLY_WHEN_SHOWN, "隐藏金额的会话里整榜不出"));
        }
    }
}
