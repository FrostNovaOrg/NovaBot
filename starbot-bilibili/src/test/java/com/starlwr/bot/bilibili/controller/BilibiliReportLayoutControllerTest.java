package com.starlwr.bot.bilibili.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPreviewPainter;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告版式的两支接口
 *
 * <h2>枚举那一支为什么要有判据</h2>
 * 「报告长什么样」那一屏靠它渲染开关。少列一项，那一项就<b>照样生效但没人配得到</b>；
 * 多列一项，界面上会出现一个勾了不算数的开关。两个方向都不会报错。
 * 项数与内容由 {@code BilibiliLiveReportLayoutOptionsTest} 对着类字段现算，
 * 这一组只钉<b>接口有没有把那张表原样交出去</b>——两层分开，谁也不替谁。
 *
 * <h2>预览那一支</h2>
 * 钉的是「入参真的被读进去了」：版式参数原样交给
 * {@link BilibiliLiveReportOptions#of}，而不是接口自己另解析一遍。
 * 各解析各的话，预览会在「越界值怎么夹」「缺项取什么默认」上悄悄给出与实际不同的图，
 * 而那正是所见即所得要防的事。
 */
@DisplayName("报告版式接口")
class BilibiliReportLayoutControllerTest {
    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private BilibiliLiveReportPreviewPainter preview;

    private RevenueVisibilityService revenueVisibility;

    private BilibiliReportLayoutController controller;

    @BeforeEach
    void setUp() {
        preview = mock(BilibiliLiveReportPreviewPainter.class);
        revenueVisibility = new RevenueVisibilityService(new StarBotStateStore(new StarBotCoreProperties()));
        controller = new BilibiliReportLayoutController(preview, revenueVisibility);

        when(preview.render(any())).thenReturn(Optional.of(FAKE_PNG));
    }

    @Test
    @DisplayName("🔴 路径挂在控制台底下，且不写平台名")
    void pathsSitUnderTheConsole() {
        assertEquals("/config/api/report/layout-options", BilibiliReportLayoutController.LAYOUT_OPTIONS_PATH);
        assertEquals("/config/api/report/preview", BilibiliReportLayoutController.PREVIEW_PATH);

        // 核心界面上不出现平台名：这两条路径是控制台的一部分，不该带 bilibili
        assertFalse(BilibiliReportLayoutController.LAYOUT_OPTIONS_PATH.contains("bilibili"));
        assertFalse(BilibiliReportLayoutController.PREVIEW_PATH.contains("bilibili"));
    }

    @Test
    @DisplayName("🔴 枚举接口把版式表原样交出去，一项不多一项不少")
    void enumeratesEveryLayoutOption() {
        JSONObject body = controller.layoutOptions();

        assertTrue(body.getBooleanValue("success"));
        JSONArray items = body.getJSONArray("items");

        List<HandlerOption> expected = BilibiliLiveReportOptions.layoutOptions();
        // 先证分母不是空的：表为空时下面逐项比对会在两个空表上白白通过
        assertFalse(expected.isEmpty(), "版式表是空的，这一格什么都没量到");
        assertEquals(expected.size(), items.size(), "接口交出去的项数与版式表对不上");

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            HandlerOption option = expected.get(i);

            assertEquals(option.key(), item.getString("key"), "第 " + i + " 项的键不对，顺序也算内容");
            assertEquals(option.label(), item.getString("label"));
            assertEquals(option.description(), item.getString("description"));
            assertEquals(option.defaultValue(), item.get("default"), option.key() + " 的默认值不对");
            keys.add(item.getString("key"));
        }

        System.out.println("枚举接口交出 " + items.size() + " 项：" + String.join("、", keys));
    }

    @Test
    @DisplayName("🔴 类型名小写，数字项带上下限、开关项不带")
    void reportsTypeAndRange() {
        JSONArray items = controller.layoutOptions().getJSONArray("items");

        int booleans = 0;
        int integers = 0;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            String type = item.getString("type");

            if ("boolean".equals(type)) {
                booleans++;
                assertNull(item.get("min"), item.getString("key") + " 是开关却带着下限");
                assertNull(item.get("max"), item.getString("key") + " 是开关却带着上限");
            } else if ("integer".equals(type)) {
                integers++;
                assertNotNull(item.get("min"), item.getString("key") + " 是数字项却没有下限");
                assertNotNull(item.get("max"), item.getString("key") + " 是数字项却没有上限");
            } else {
                throw new AssertionError("没见过的类型名: " + type + "（" + item.getString("key") + "）");
            }
        }

        assertTrue(booleans > 0, "一个开关项都没有，这一格什么都没量到");
        assertTrue(integers > 0, "一个数字项都没有，带不带上下限这一问就无从谈起");

        System.out.println("开关 " + booleans + " 项，数字 " + integers + " 项");
    }

    @Test
    @DisplayName("🔴 预览回 200 与 image/png")
    void previewReturnsPng() {
        ResponseEntity<byte[]> response = controller.preview(new JSONObject());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(FAKE_PNG.length, response.getBody() == null ? 0 : response.getBody().length);
    }

    @Test
    @DisplayName("🔴 预览把版式参数读进去了，且走的是出报告那一段解析码")
    void previewReadsTheGivenParams() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        // 越界值：夹到上限这件事必须由 of() 来做，接口不许自己另夹一遍
        params.put("danmu_ranking", 9999);

        controller.preview(params);

        verify(preview).render(org.mockito.ArgumentMatchers.argThat(options ->
                !options.isCover() && options.getDanmuRanking() == 20));
    }

    @Test
    @DisplayName("🔴 通道金额不可见时，预览按隐藏金额画")
    void previewHidesRevenueWhenChannelHidesIt() {
        JSONObject body = new JSONObject();
        body.put("platform", "qq-onebot");
        body.put("type", 1);
        body.put("num", 10000003L);

        controller.preview(body);

        verify(preview).render(org.mockito.ArgumentMatchers.argThat(options ->
                !options.isShowRevenue()));
    }

    @Test
    @DisplayName("🔴 没传通道时预览仍按金额可见画")
    void previewWithoutChannelKeepsRevenueVisible() {
        controller.preview(new JSONObject());

        verify(preview).render(org.mockito.ArgumentMatchers.argThat(
                BilibiliLiveReportOptions::isShowRevenue));
    }

    @Test
    @DisplayName("🔴 通道金额改为可见后，预览跟着画金额")
    void previewShowsRevenueWhenChannelShowsIt() {
        revenueVisibility.set("qq-onebot", 10000003L, true);

        JSONObject body = new JSONObject();
        body.put("platform", "qq-onebot");
        body.put("type", 1);
        body.put("num", 10000003L);

        controller.preview(body);

        verify(preview).render(org.mockito.ArgumentMatchers.argThat(
                BilibiliLiveReportOptions::isShowRevenue));
    }

    @Test
    @DisplayName("🔴 请求体为空时按全默认预览，而不是崩掉")
    void previewAcceptsAbsentBody() {
        ResponseEntity<byte[]> response = controller.preview(null);

        assertEquals(200, response.getStatusCode().value());
        verify(preview).render(org.mockito.ArgumentMatchers.argThat(BilibiliLiveReportOptions::isCover));
    }

    @Test
    @DisplayName("🔴 画不出来时回 500，不回一张空图")
    void previewFailsLoudly() {
        when(preview.render(any())).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.preview(new JSONObject());

        assertEquals(500, response.getStatusCode().value());
        assertNull(response.getBody(), "画不出来却回了内容，界面上会显示成一块白");
    }
}
