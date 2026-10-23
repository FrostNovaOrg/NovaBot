package org.frostnova.nova.report.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.painter.*;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliLiveReportRedrawerTest {
    private BilibiliLiveReportRedrawer redrawer;
    private BilibiliLiveReportReplayPainter painter;
    private LiveReportRenderCache cache;
    private PushTarget channel;
    private LiveDetail detail;
    private JSONObject params;
    private ReportPalette night;

    @BeforeEach void setup() {
        painter = mock(BilibiliLiveReportReplayPainter.class);
        redrawer = new BilibiliLiveReportRedrawer(mock(NovaCommonPainterFactory.class), mock(BilibiliApiUtil.class),
                mock(FontUtil.class), new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class), mock(ReportImageDiskCache.class)) {
            @Override protected BilibiliLiveReportReplayPainter replay(LiveDetail detail) { return painter; }
        };
        cache = new LiveReportRenderCache();
        channel = new PushTarget(); channel.setPlatform("qq-onebot"); channel.setType(PushTargetType.GROUP); channel.setNum(1001L);
        var message = new PushMessage(); message.setHandler(BilibiliLiveReportPushHandler.class.getName());
        params = JSONObject.of("cover", false, "message", "以前的文字{uname}{url}", "danmu_ranking", 3);
        message.setParamsJsonObject(params); channel.setMessages(List.of(message));
        detail = new LiveDetail(1, "bilibili", 1L, "主播示例", 9L, 100, 200, 1,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
        var visibility = mock(RevenueVisibilityService.class); when(visibility.isVisible(channel)).thenReturn(false);
        var templates = mock(PushTemplateDefaults.class); when(templates.paramsOf(any())).thenAnswer(call -> new JSONObject());
        var handler = mock(BilibiliLiveReportPushHandler.class); when(handler.legacyClassNames()).thenReturn(List.of());
        var palettes = mock(ReportPalettes.class);
        var preset = ReportPalette.preset("night").orElseThrow(); night = preset.palette();
        when(palettes.forChannel(channel)).thenReturn(preset);
        ReflectionTestUtils.setField(redrawer, "revenue", visibility);
        ReflectionTestUtils.setField(redrawer, "templates", templates);
        ReflectionTestUtils.setField(redrawer, "handler", handler);
        ReflectionTestUtils.setField(redrawer, "palettes", palettes);
        ReflectionTestUtils.setField(redrawer, "rendered", cache);
        when(painter.render(any(), any())).thenReturn(Optional.of(new byte[]{65, 66, 67}));
    }

    @Test void cacheHitSendsOriginalImageWithoutReplay() {
        cache.put("bilibili", 1L, 100, BilibiliLiveReportOptions.of(params, false), night, "T1JJR0lOQUw=");
        assertEquals("{image_base64=T1JJR0lOQUw=}", redrawer.channelContent(detail, channel).orElseThrow());
        verifyNoInteractions(painter);
    }

    @Test void replayUsesSavedLayoutRevenueAndNightPalette() {
        assertEquals("{image_base64=QUJD}", redrawer.channelContent(detail, channel).orElseThrow());
        var options = ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);
        verify(painter).render(options.capture(), same(night));
        assertFalse(options.getValue().isCover());
        assertFalse(options.getValue().isShowRevenue());
        assertEquals(3, options.getValue().getDanmuRanking());
        assertEquals("以前的文字{uname}{url}", params.getString("message"));
    }

    @Test void changedAppearanceCannotReuseOriginalImage() {
        cache.put("bilibili", 1L, 100, BilibiliLiveReportOptions.of(params, false), ReportPalette.DEFAULT, "T0xE");
        cache.put("bilibili", 1L, 100, BilibiliLiveReportOptions.of(params, true), night, "U0VDUkVU");
        cache.put("bilibili", 1L, 100, new BilibiliLiveReportOptions(), night, "T0xE");
        assertEquals("{image_base64=QUJD}", redrawer.channelContent(detail, channel).orElseThrow());
        verify(painter).render(any(), same(night));
    }

    @Test void failedImageUsesEscapedTextWithSameRevenueVisibility() {
        when(painter.render(any(), any())).thenReturn(Optional.empty());
        when(painter.textReport(anyString(), any(), any())).thenReturn("报告{at=all}");
        assertEquals("报告｛at=all｝", redrawer.channelContent(detail, channel).orElseThrow());
        verify(painter).textReport(eq("bilibili"), any(), argThat(options -> !options.isShowRevenue()));
    }
}
