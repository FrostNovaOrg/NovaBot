package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.model.GuardMember;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * 大航海名单翻页
 */
@DisplayName("大航海名单翻页")
class BilibiliApiUtilGuardListTest {
    private static final Pattern PAGE = Pattern.compile("[?&]page=(\\d+)");

    @Test
    @DisplayName("两页 20+5 去重后正好 25 条")
    void paginatesTwoPagesAndDedupes() {
        BilibiliApiUtil api = new BilibiliApiUtil(
                mock(HttpUtil.class), new NovaBilibiliProperties(), mock(BilibiliRiskMetrics.class)) {
            @Override
            public JSONObject requestBilibiliApi(String url) {
                int page = pageOf(url);
                JSONObject data = new JSONObject();
                JSONObject info = new JSONObject();
                info.put("num", 25);
                info.put("page", 2);
                data.put("info", info);

                JSONArray list = new JSONArray();
                JSONArray top3 = new JSONArray();
                if (page == 1) {
                    for (int i = 1; i <= 20; i++) {
                        list.add(member(i, "u" + i, 3, i));
                    }
                    for (int i = 1; i <= 3; i++) {
                        top3.add(member(i, "u" + i, 3, i));
                    }
                } else if (page == 2) {
                    for (int i = 21; i <= 25; i++) {
                        list.add(member(i, "u" + i, 3, i));
                    }
                } else {
                    fail("不该再翻到第 " + page + " 页: " + url);
                }
                data.put("list", list);
                data.put("top3", top3);
                return data;
            }
        };

        Optional<List<GuardMember>> fetched = api.getGuardList(20002L, 10001L);
        assertTrue(fetched.isPresent(), "两页都成功时名单不该为空");
        List<GuardMember> members = fetched.get();
        assertEquals(25, members.size(), "去重后应正好 25 条，实际 " + members.size());

        Set<Long> uids = new HashSet<>();
        for (GuardMember member : members) {
            assertTrue(uids.add(member.uid()), "uid " + member.uid() + " 重复了");
        }
    }

    private static int pageOf(String url) {
        Matcher matcher = PAGE.matcher(url);
        assertTrue(matcher.find(), "请求地址里没有 page=: " + url);
        return Integer.parseInt(matcher.group(1));
    }

    private static JSONObject member(long uid, String name, int level, long score) {
        JSONObject item = new JSONObject();
        JSONObject uinfo = new JSONObject();
        uinfo.put("uid", uid);
        JSONObject base = new JSONObject();
        base.put("name", name);
        uinfo.put("base", base);
        JSONObject guard = new JSONObject();
        guard.put("level", level);
        uinfo.put("guard", guard);
        item.put("uinfo", uinfo);
        item.put("score", score);
        return item;
    }
}
