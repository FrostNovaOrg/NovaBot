package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class BilibiliApiUtilGuardInfosTest {
    @ParameterizedTest(name = "guard_resources 使用 list 对象：{0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("数组和旧 list 对象都能读出三档大航海信息")
    void readsAllThreeGuards(boolean wrapped) {
        JSONArray resources = new JSONArray();
        String[] names = {"总督", "提督", "舰长"};
        for (int i = 0; i < names.length; i++) {
            resources.add(JSONObject.of("level", i + 1, "name", names[i], "img", "https://img.example/frame" + i + ".png"));
        }
        JSONObject data = JSONObject.of("guard_resources", wrapped ? JSONObject.of("list", resources) : resources);
        BilibiliApiUtil api = new BilibiliApiUtil(mock(HttpUtil.class), new NovaBilibiliProperties(), mock(BilibiliRiskMetrics.class)) {
            @Override
            public JSONObject requestBilibiliApi(String url) {
                return data;
            }
        };
        assertEquals(Map.of("总督", "https://img.example/frame0.png", "提督", "https://img.example/frame1.png",
                "舰长", "https://img.example/frame2.png"), api.getGuardInfos(), "两种形状都应读出三项名称和图片");
    }
}
