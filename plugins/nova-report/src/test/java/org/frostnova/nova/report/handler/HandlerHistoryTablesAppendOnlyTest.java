package org.frostnova.nova.report.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler;
import org.frostnova.nova.report.painter.BilibiliDynamicPainter;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.handler.NovaEventHandlerPushMessageInitializer;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.HandlerPackageNames;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两张历史值表只进不出
 * <p>
 * 旧默认表认得某一版，没改过的推送才能迁到新默认；旧名表认得搬家前的全类名，
 * 旧配置才能读回来。从这两张表里删掉一条，还停在那一版上的配置就安静地不推、
 * 或安静地回出厂默认。本格钉的是「在册快照里的每一条现表都还在」，以及
 * 「旧名加旧默认同条配置」这一路真的走迁移。
 */
@DisplayName("历史值两表只进不出")
class HandlerHistoryTablesAppendOnlyTest {
    /**
     * 开播通知历史上发过的两版默认模板（47b7549b 现值）
     */
    private static final List<String> SNAPSHOT_LIVE_ON_DEFAULTS = List.of(
            "{at}{uname} 正在直播 {title}\n{url}{next}{cover}",
            "{uname} 正在直播 {title}\n{url}{next}{cover}");

    /**
     * 动态通知历史上发过的两版默认模板（47b7549b 现值）
     */
    private static final List<String> SNAPSHOT_DYNAMIC_DEFAULTS = List.of(
            "{at}{uname} {action}\n{url}{next}{picture}",
            "{uname} {action}\n{url}{next}{picture}");

    /**
     * 问②用的那一版：去掉 {at}、仍带 {next} 的动态旧默认
     */
    private static final String SNAPSHOT_DYNAMIC_DEFAULT =
            "{uname} {action}\n{url}{next}{picture}";

    @TempDir
    Path dir;

    @Test
    @DisplayName("在册快照 ⊆ 现表；旧名加旧默认同条配置走真迁移；比较函数自证")
    void snapshotStaysInTablesAndLegacyPairMigrates() {
        BilibiliLiveOnPushHandler liveOn = new BilibiliLiveOnPushHandler(
                mock(BilibiliApiUtil.class), mock(NovaMessageSender.class),
                mock(AtSubscriptionService.class), mock(LiveDataService.class));
        BilibiliDynamicPushHandler dynamic = new BilibiliDynamicPushHandler(
                mock(BilibiliApiUtil.class), mock(BilibiliDynamicPainter.class),
                mock(NovaMessageSender.class), mock(AtSubscriptionService.class),
                mock(LiveDataService.class));
        BilibiliLiveReportPushHandler report = new BilibiliLiveReportPushHandler(
                mock(BilibiliApiUtil.class), mock(NovaMessageSender.class),
                mock(BilibiliLiveReportPainter.class), mock(RevenueVisibilityService.class));

        List<String> red = new ArrayList<>();

        try {
            List<String> missing = new ArrayList<>();
            missing.addAll(namedMissing(liveOn, "supersededDefaults.message",
                    SNAPSHOT_LIVE_ON_DEFAULTS,
                    liveOn.supersededDefaults().getOrDefault("message", List.of())));
            missing.addAll(namedMissing(dynamic, "supersededDefaults.message",
                    SNAPSHOT_DYNAMIC_DEFAULTS,
                    dynamic.supersededDefaults().getOrDefault("message", List.of())));
            missing.addAll(namedMissing(dynamic, "legacyClassNames",
                    List.of(HandlerPackageNames.oldBot("bilibili.handler.BilibiliDynamicPushHandler")),
                    dynamic.legacyClassNames()));
            missing.addAll(namedMissing(report, "legacyClassNames",
                    List.of(HandlerPackageNames.oldBot("bilibili.handler.BilibiliLiveReportPushHandler")),
                    report.legacyClassNames()));
            assertEquals(List.of(), missing, "在册快照有而现表没有: " + missing);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            String oldName = HandlerPackageNames.oldBot("bilibili.handler.BilibiliDynamicPushHandler");
            JSONObject saved = new JSONObject();
            saved.put("message", SNAPSHOT_DYNAMIC_DEFAULT);

            PushMessage message = new PushMessage();
            message.setHandler(oldName);
            message.setParams(saved.toJSONString());

            NovaEventHandlerPushMessageInitializer initializer =
                    new NovaEventHandlerPushMessageInitializer(service(liveOn, dynamic, report),
                            templateDefaults());
            assertTrue(initializer.initialize(message),
                    "旧名 " + oldName + " 认不到处理器, 还停在旧名上的动态推送不发也不报错");
            String now = dynamic.getDefaultParams().getString("message");
            assertEquals(now, message.getParamsJsonObject().getString("message"),
                    "旧名加旧默认同条配置没迁到新默认, 存着的仍是「" + SNAPSHOT_DYNAMIC_DEFAULT + "」");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            List<String> current = List.copyOf(SNAPSHOT_LIVE_ON_DEFAULTS);
            List<String> minusOne = current.subList(1, current.size());
            assertEquals(List.of(current.get(0)), missingFrom(current, minusOne),
                    "现表去掉一条应恰报那一条");
            List<String> plusOne = new ArrayList<>(current);
            plusOne.add("{not-a-shipped-default}");
            assertEquals(List.of(), missingFrom(current, plusOne),
                    "现表多一条应报 0（⊆ 仍成立）");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            for (String line : red) {
                System.out.println("红: " + line);
            }
            fail(red.size() + " 问红：\n" + String.join("\n", red));
        }
    }

    /**
     * 快照里有、现表里没有的条目。这就是 ⊆ 的补集。
     */
    static List<String> missingFrom(List<String> snapshot, Collection<String> current) {
        List<String> missing = new ArrayList<>();
        for (String item : snapshot) {
            if (!current.contains(item)) {
                missing.add(item);
            }
        }
        return missing;
    }

    private static List<String> namedMissing(NovaEventHandler handler, String table,
                                             List<String> snapshot, Collection<String> current) {
        List<String> named = new ArrayList<>();
        for (String item : missingFrom(snapshot, current)) {
            named.add(handler.getClass().getSimpleName() + "·" + table + "·" + item);
        }
        return named;
    }

    private NovaEventHandlerService service(NovaEventHandler liveOn, NovaEventHandler dynamic,
                                            NovaEventHandler report) {
        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        beans.put("bilibiliLiveOnPushHandler", liveOn);
        beans.put("bilibiliDynamicPushHandler", dynamic);
        beans.put("bilibiliLiveReportPushHandler", report);

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);

        NovaEventHandlerService built = new NovaEventHandlerService(context);
        built.onContextRefreshedEvent();
        return built;
    }

    private PushTemplateDefaults templateDefaults() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        return new PushTemplateDefaults(properties);
    }
}
