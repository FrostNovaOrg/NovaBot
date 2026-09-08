package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下播报告版式选项测试
 */
@DisplayName("报告版式选项")
class BilibiliLiveReportOptionsTest {
    @Test
    @DisplayName("参数为空时应全部取默认值")
    void usesDefaultsWhenParamsAbsent() {
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(null, true);

        assertTrue(options.isCover());
        assertTrue(options.isCards());
        assertTrue(options.isDanmuCloud());
        assertEquals(5, options.getDanmuRanking());
        // 盲盒两榜默认关闭：多数直播间没有盲盒数据
        assertEquals(0, options.getBoxRanking());
    }

    @Test
    @DisplayName("未填写的项应保留默认值，不被置为 false 或 0")
    void keepsDefaultsForUnspecifiedKeys() {
        JSONObject params = new JSONObject();
        params.put("cover", false);

        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);

        assertFalse(options.isCover(), "显式配置的项应生效");
        assertTrue(options.isCards(), "未配置的项应保留默认值");
        assertEquals(5, options.getGiftRanking());
    }

    @Test
    @DisplayName("排行榜名次数应按配置生效，0 表示不展示")
    void readsRankingCounts() {
        JSONObject params = new JSONObject();
        params.put("danmu_ranking", 10);
        params.put("gift_ranking", 0);

        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);

        assertEquals(10, options.getDanmuRanking());
        assertEquals(0, options.getGiftRanking());
    }

    @Test
    @DisplayName("越界的名次数应夹到合法区间而非让报告画崩")
    void clampsOutOfRangeCounts() {
        JSONObject params = new JSONObject();
        params.put("danmu_ranking", -3);
        params.put("gift_ranking", 9999);

        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);

        assertEquals(0, options.getDanmuRanking());
        assertEquals(20, options.getGiftRanking(), "应夹到上限 20");
    }
}
