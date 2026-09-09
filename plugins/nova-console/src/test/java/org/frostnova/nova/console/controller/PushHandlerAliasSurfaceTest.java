package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.service.HandlerPackageNames;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.NovaEventHandlerService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/handlers} 每一项带上它的旧全类名，且逐字取自运行期那张别名表
 * <p>
 * 处理器搬过包之后，老使用者的 {@code datasource.json} 里那一条写的仍是旧名。界面按真类名
 * 严格比就对不上：开关显示成「关」而机器人照推、取消勾选删不掉那一条、旧名下的自定义模板
 * 读不到而页面报「默认模板」——<b>三样都不报错</b>。界面要归一，前提是这一支把旧名交给它。
 * <p>
 * 🔴 判的是<b>同源</b>不是「有这一栏」：在控制器里另手写一份旧名清单，这一栏照样有值，
 * 而两份清单迟早对不上——那正是这个毛病本来的成因。因此逐项与
 * {@link NovaEventHandlerService#getLegacyClassNames()} 比。
 */
@DisplayName("处理器清单带旧全类名")
class PushHandlerAliasSurfaceTest {
    private static final String LEGACY_MOVED = "old.pkg.MovedHandler";

    private static final String LEGACY_MOVED_OLDER = "older.pkg.MovedHandler";

    @TempDir
    Path dir;

    /**
     * 按容器里真有这两个处理器的样子建一份处理器表，不 mock 别名那一段——
     * mock 掉的话，量的就只是「控制器有没有调这个方法」，而不是两处读的是不是同一张表
     */
    private static NovaEventHandlerService service() {
        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        beans.put("moved", new Moved());
        beans.put("stayed", new Stayed());

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);

        NovaEventHandlerService service = new NovaEventHandlerService(context);
        service.onContextRefreshedEvent();
        return service;
    }

    private JSONObject handlers(NovaEventHandlerService service) {
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

    /**
     * 清单里认一项
     */
    private static JSONObject itemOf(JSONObject body, String className) {
        JSONArray items = body.getJSONArray("handlers");
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (className.equals(item.getString("className"))) {
                return item;
            }
        }
        return null;
    }

    @Test
    @DisplayName("搬过家的那一项带着它的旧名, 且与运行期那张表逐字相同")
    void movedHandlerCarriesItsLegacyNames() {
        NovaEventHandlerService service = service();
        JSONObject item = itemOf(handlers(service), Moved.class.getName());
        assertNotNull(item, "清单里找不到这个处理器, 下面每一格都无从量起");

        List<String> expected = service.getLegacyClassNames().get(Moved.class.getName());
        List<String> wanted = new ArrayList<>(List.of(LEGACY_MOVED, LEGACY_MOVED_OLDER,
                HandlerPackageNames.toOldPackage(Moved.class.getName())));
        wanted.sort(null);
        assertEquals(wanted, expected,
                "别名表本身就没有这两串, 这一格量的是另一件事");
        assertEquals(expected, item.getJSONArray("aliases").toJavaList(String.class),
                "接口面上的旧名与运行期认处理器用的那张表对不上, "
                        + "界面按接口面归一而机器人按运行期的表推送, 两边各说各的");
    }

    @Test
    @DisplayName("没声明过旧名的那一项只带包根迁移那一条, 栏仍在")
    void handlerWithoutLegacyNamesGetsAnEmptyList() {
        JSONObject item = itemOf(handlers(service()), Stayed.class.getName());
        assertNotNull(item, "清单里找不到这个处理器");

        assertTrue(item.containsKey("aliases"),
                "缺这一栏与一张空表在前端 `|| []` 底下长得一样, "
                        + "「这个处理器没搬过家」与「这一版后端还不答这一问」就此分不开");
        assertEquals(List.of(HandlerPackageNames.toOldPackage(Stayed.class.getName())),
                item.getJSONArray("aliases").toJavaList(String.class),
                "包根迁过之后每个现行类都有一条旧根别名, 不该再混进别的名字");
    }

    @Test
    @DisplayName("旧名不许混进 className, 勾选项仍只发真类名")
    void legacyNamesNeverBecomeAnEntryOfTheirOwn() {
        JSONArray items = handlers(service()).getJSONArray("handlers");
        assertEquals(2, items.size(), "旧名各自占了一项, 界面上会多出几条查无此处理器的通知");

        for (int i = 0; i < items.size(); i++) {
            String className = items.getJSONObject(i).getString("className");
            assertTrue(List.of(Moved.class.getName(), Stayed.class.getName()).contains(className),
                    "清单里出现了一个不是真类名的 className: " + className);
        }
    }

    /**
     * 只为让处理器表建得起来：本类量的是名字，处理什么事件与之无关
     */
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

    /** 搬过两次家 */
    private static final class Moved extends FakeHandler {
        @Override
        public List<String> legacyClassNames() {
            return List.of(LEGACY_MOVED_OLDER, LEGACY_MOVED);
        }
    }

    /** 没搬过家 */
    private static final class Stayed extends FakeHandler {
    }
}
