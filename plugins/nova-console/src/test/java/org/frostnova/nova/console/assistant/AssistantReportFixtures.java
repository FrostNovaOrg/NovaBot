package org.frostnova.nova.console.assistant;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.painter.ReportImageDiskCache;
import org.frostnova.nova.report.service.BilibiliLiveReportRedrawer;
import org.frostnova.nova.report.util.FontUtil;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Mockito.mock;

final class AssistantReportFixtures {
    static BilibiliLiveReportRedrawer redrawer(Path directory) {
        var core = new NovaCoreProperties();
        core.getDatasource().setJsonPath(directory.resolve("datasource.json").toString());
        core.getPaint().getFonts().add("内置");
        var fonts = new FontUtil(new DefaultResourceLoader(), core);
        fonts.init();
        var build = new Properties();
        build.setProperty("name", "NovaBot");
        build.setProperty("version", "6.0.3");
        var factory = new NovaCommonPainterFactory(new BuildProperties(build), core, fonts);
        var api = mock(BilibiliApiUtil.class);
        var revenue = new RevenueVisibilityService(new NovaStateStore(core));
        var redrawer = new BilibiliLiveReportRedrawer(factory, api, fonts, new NovaBilibiliProperties(),
                mock(LiveRoomInfoHistory.class), mock(ReportImageDiskCache.class));
        ReflectionTestUtils.setField(redrawer, "revenue", revenue);
        ReflectionTestUtils.setField(redrawer, "templates", new PushTemplateDefaults(core));
        ReflectionTestUtils.setField(redrawer, "handler", new BilibiliLiveReportPushHandler(api, null, null, revenue));
        return redrawer;
    }

    static LiveDetail detail(long uid, long start) {
        long end = start + 120_000;
        return new LiveDetail(LiveDetail.VERSION, "bilibili", uid, "测试主播甲", 1L, start, end, 120,
                Map.of(BilibiliLiveMetric.DANMU_COUNT, 106d, BilibiliLiveMetric.GIFT_VALUE, 128.5d,
                        BilibiliLiveMetric.GIFT_PAID, 128.5d),
                Map.of(BilibiliLiveMetric.DANMU_USERS, 20),
                Map.of(BilibiliLiveMetric.ONLINE_COUNT, Map.of(start, 12d, start + 60_000, 31d, end, 24d),
                        BilibiliLiveMetric.GIFT_VALUE, Map.of(start, 0d, start + 60_000, 128.5d)),
                Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }
}
