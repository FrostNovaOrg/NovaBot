package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.NovaExternalBaseEvent;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 旧全类名那张表反着读得出来，而且读到的与运行期认处理器的是同一张
 * <p>
 * 配置界面要认出「使用者这一条配的就是这一类通知」，靠的就是这张表。它若与
 * {@link StarBotEventHandlerService#getHandler(String)} 回落时用的那一张分了叉，
 * 表现是界面上写着「关」而机器人照推——两处的代码看起来都对，点一遍界面也看不出异常。
 * <p>
 * 因此本类量的不是「有没有这个方法」，而是<b>两处读的是不是同一张表</b>：
 * 逐条拿反读出来的旧名去问 {@code getHandler}，答的必须正是它挂着的那个处理器。
 */
@DisplayName("处理器旧名表")
class HandlerAliasTableTest {
    private static final String LEGACY_A1 = "old.pkg.AlphaHandler";

    private static final String LEGACY_A2 = "older.pkg.AlphaHandler";

    private static final String LEGACY_B = "old.pkg.BetaHandler";

    /**
     * 按容器里真有这几个处理器的样子建一份处理器表
     */
    private static StarBotEventHandlerService service(NovaEventHandler... handlers) {
        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        for (NovaEventHandler handler : handlers) {
            beans.put(handler.getClass().getName(), handler);
        }

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);

        StarBotEventHandlerService service = new StarBotEventHandlerService(context);
        service.onContextRefreshedEvent();
        return service;
    }

    @Test
    @DisplayName("反读出来的旧名，逐条都取得到它挂着的那个处理器")
    void everyLegacyNameResolvesBackToItsHandler() {
        Alpha alpha = new Alpha();
        Beta beta = new Beta();
        StarBotEventHandlerService service = service(alpha, beta);

        Map<String, List<String>> table = service.getLegacyClassNames();
        assertFalse(table.isEmpty(), "一条旧名也没反读出来, 这条判据此刻什么都没量");

        for (Map.Entry<String, List<String>> entry : table.entrySet()) {
            for (String legacy : entry.getValue()) {
                NovaEventHandler got = service.getHandler(legacy).orElse(null);
                assertTrue(got != null, "反读出来的 " + legacy + " 在运行期反而查不到处理器, 两处不是同一张表");
                assertEquals(entry.getKey(), got.getClass().getName(),
                        legacy + " 反读时挂在 " + entry.getKey() + " 名下, 运行期却解到了别的处理器");
            }
        }
    }

    @Test
    @DisplayName("一个处理器的多个旧名都在, 按字典序")
    void allLegacyNamesOfOneHandlerAreListed() {
        StarBotEventHandlerService service = service(new Alpha(), new Beta());

        assertEquals(List.of(LEGACY_A1, LEGACY_A2), service.getLegacyClassNames().get(Alpha.class.getName()),
                "搬过两次家的处理器少报一个旧名, 停在那一版上的配置就此在界面上消失");
        assertEquals(List.of(LEGACY_B), service.getLegacyClassNames().get(Beta.class.getName()));
    }

    @Test
    @DisplayName("没搬过家的处理器不出现在这张表里")
    void handlerWithoutLegacyNamesIsAbsent() {
        StarBotEventHandlerService service = service(new Alpha(), new Plain());

        assertFalse(service.getLegacyClassNames().containsKey(Plain.class.getName()),
                "没有旧名的处理器占了一行, 读的那一端分不出「没搬过家」与「旧名没报上来」");
        assertEquals(Set.of(Alpha.class.getName()), service.getLegacyClassNames().keySet());
    }

    @Test
    @DisplayName("旧名不许混进主表, 主表仍只有真类名")
    void legacyNamesStayOutOfTheMainTable() {
        StarBotEventHandlerService service = service(new Alpha(), new Beta());

        assertEquals(Set.of(Alpha.class.getName(), Beta.class.getName()),
                service.getRegisteredHandlerClasses(),
                "旧名混进主表, 界面的勾选项与随包示例就会把一个已经不存在的类名重新发给使用者");
        assertTrue(service.getAcceptedHandlerClasses().containsAll(List.of(LEGACY_A1, LEGACY_A2, LEGACY_B)),
                "保存前校验那一份必须认得旧名, 否则老配置一保存就被拦下");
    }

    @Test
    @DisplayName("某个旧名如今正是另一个处理器的真类名时, 不报成旧名")
    void aNameTakenByARealHandlerIsNotReportedAsLegacy() {
        Claimer claimer = new Claimer();
        Plain plain = new Plain();
        StarBotEventHandlerService service = service(claimer, plain);

        assertFalse(service.getLegacyClassNames().containsKey(Claimer.class.getName()),
                "把一个还在的真类名报成别人的旧名, 界面会把两类通知认成同一类");
        assertSame(plain, service.getHandler(Plain.class.getName()).orElse(null),
                "这一串该解到它自己那个处理器");
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
    private static final class Alpha extends FakeHandler {
        @Override
        public List<String> legacyClassNames() {
            return List.of(LEGACY_A2, LEGACY_A1);
        }
    }

    /** 搬过一次家 */
    private static final class Beta extends FakeHandler {
        @Override
        public List<String> legacyClassNames() {
            return List.of(LEGACY_B);
        }
    }

    /** 没搬过家 */
    private static final class Plain extends FakeHandler {
    }

    /** 报了一个如今仍活着的真类名当自己的旧名——搬家搬错了，不是一种可以静默处理的情形 */
    private static final class Claimer extends FakeHandler {
        @Override
        public List<String> legacyClassNames() {
            return List.of(Plain.class.getName());
        }
    }
}
