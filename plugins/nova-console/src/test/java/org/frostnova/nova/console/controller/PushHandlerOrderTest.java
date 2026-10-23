package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.handler.BilibiliLiveOffPushHandler;
import org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.handler.WeeklyReportPushHandler;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.report.handler.BilibiliDynamicPushHandler;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.handler.BilibiliOfflineRewardDigestPushHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/handlers} 按一场直播的先后排：开播 → 下播 → 下播报告 → 下播打赏播报 → 动态 → 周报
 * <p>
 * 用户那头的毛病：通道一览、主播页的小标、默认模板的标签与通道页的开关都按这一份清单的顺序画，
 * 而这份清单从前按全类名排，屏幕上成了「下播通知、开播通知、运营周报、动态通知、下播报告……」——
 * 下播排在开播前面，周报夹在中间，找一种通知要从头扫到尾。
 * <p>
 * 顺序由各处理器自报（{@link NovaEventHandler#displayOrder()}），界面不认任何一种通知的名字；
 * 没报的第三方处理器排在这几种后面，彼此仍按类名排，顺序每次一样。
 */
@DisplayName("处理器清单按一场直播的先后排")
class PushHandlerOrderTest {
    @TempDir
    Path dir;

    private JSONArray handlers(NovaEventHandler... beans) {
        Map<String, NovaEventHandler> map = new LinkedHashMap<>();
        for (int i = 0; i < beans.length; i++) {
            map.put("handler" + i, beans[i]);
        }
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(map);
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
        return controller.handlers().getJSONArray("handlers");
    }

    private static List<String> names(JSONArray items) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            out.add(items.getJSONObject(i).getString("displayName"));
        }
        return out;
    }

    @Test
    @DisplayName("六种内置通知按开播、下播、报告、打赏、动态、周报排，与登记的先后无关")
    void builtInNoticesFollowTheStream() {
        // 故意倒着登记：量的是清单自己的排法，不是容器恰好给的顺序
        JSONArray items = handlers(
                mock(WeeklyReportPushHandler.class, CALLS_REAL_METHODS),
                mock(BilibiliDynamicPushHandler.class, CALLS_REAL_METHODS),
                mock(BilibiliOfflineRewardDigestPushHandler.class, CALLS_REAL_METHODS),
                mock(BilibiliLiveReportPushHandler.class, CALLS_REAL_METHODS),
                mock(BilibiliLiveOffPushHandler.class, CALLS_REAL_METHODS),
                mock(BilibiliLiveOnPushHandler.class, CALLS_REAL_METHODS));

        assertEquals(List.of("开播通知", "下播通知", "下播报告", "下播打赏播报", "动态通知", "运营周报"),
                names(items), "通知的先后不合一场直播的顺序");
    }

    @Test
    @DisplayName("没报顺序的第三方处理器排在内置几种后面，彼此按类名排")
    void undeclaredHandlersComeLast() {
        // 这两个第三方处理器的全类名排在周报前面：按类名排的话它们会插到内置通知中间
        JSONArray items = handlers(new Zeta(), new Alpha(),
                mock(WeeklyReportPushHandler.class, CALLS_REAL_METHODS),
                mock(BilibiliLiveOnPushHandler.class, CALLS_REAL_METHODS));

        assertEquals(List.of("开播通知", "运营周报", "Alpha", "Zeta"), names(items),
                "没报顺序的处理器插到了内置通知中间，或彼此的先后每次不一样");
    }

    /** 只为让处理器表建得起来：本类量的是顺序，处理什么事件与之无关 */
    private abstract static class FakeHandler implements NovaEventHandler {
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
    }

    private static final class Alpha extends FakeHandler {
    }

    private static final class Zeta extends FakeHandler {
    }
}
