package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接口应答缺 data 的记账
 */
@DisplayName("接口应答缺 data 的记账")
class BilibiliApiUtilDataMissingTest {

    @Test
    @DisplayName("data 为 null 记 API_DATA_MISSING：返回仍为空对象、detail 只含端点不含 query、逐次计数按端点去重换样本")
    void dataMissingRecordedWithoutQuery() {
        List<String> reds = new ArrayList<>();
        BilibiliRiskMetrics riskMetrics = new BilibiliRiskMetrics();
        HttpUtil http = mock(HttpUtil.class);
        JSONObject okWithoutData = new JSONObject();
        okWithoutData.put("code", 0);
        String url = "https://api.example.com/room/v1/Info?roomid=47731877194803";
        when(http.getJson(eq(url), any())).thenReturn(okWithoutData);
        BilibiliApiUtil api = new BilibiliApiUtil(http, new NovaBilibiliProperties(), riskMetrics);

        try {
            JSONObject data = api.requestBilibiliApi(url);
            assertTrue(data.isEmpty(), "缺 data 时返回值仍应是空对象（行为不变），实际: " + data);
            assertEquals(1, riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, Duration.ofMinutes(1)),
                    "首见应恰好记一次");
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("/room/v1/Info"), "detail 应含端点路径，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertFalse(detail.contains("?"), "detail 不得含 query 起始符，实际: " + detail);
            assertFalse(detail.contains("roomid"), "detail 不得含查询参数名，实际: " + detail);
            assertFalse(detail.contains("47731877194803"), "detail 不得含查询参数值，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            ConcurrentHashMap<String, AtomicLong> ledger = new ConcurrentHashMap<>();
            for (int i = 0; i < 10; i++) {
                BilibiliApiUtil.noteDataMissing("https://api.example.com/a?csrf=SECRET_TOKEN", riskMetrics, ledger);
            }
            // 同端点不同 query 必须只算一名：否则 query 里的房间号与凭据就等于变相进了健康页
            BilibiliApiUtil.noteDataMissing("https://api.example.com/a?roomid=2", riskMetrics, ledger);
            assertEquals(12, riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, Duration.ofMinutes(1)),
                    "计数是发生次数不是写入次数（11 次另加端到端首见一次），实际 "
                            + riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, Duration.ofMinutes(1)));
            String sample = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(sample.contains("count=10"), "样本应只在量级处换，实际: " + sample);
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        try {
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertFalse(detail.contains("SECRET_TOKEN"), "detail 不得含 query 里的凭据，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("④ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "四问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
