package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.RequestFailedException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 关注列表翻页遇到缺 list 字段的那一页
 * <p>
 * 抓的用户故障：接口某一页少了 list 字段时，翻页把「取不到」当成「翻到底了」，
 * 交出去的是残表——自动关注拿残表去比，把排在没取到那几页里的已关注主播当成没关注，又关注一遍。
 * <p>
 * 口径（缺 list 与空 list 是两件事，别再合并成一条分支）：
 * <ul>
 *   <li><b>缺 list 字段（null）＝取不全</b>：抛 RequestFailedException，说清第几页缺，
 *       不返回已取到的那几页</li>
 *   <li><b>list 是空数组＝翻到底了</b>：照旧 break，返回已取到的</li>
 * </ul>
 * 应答里的 total 不用（含义没核过）。不发真请求，翻页由假接口按 ps／pn 切。
 */
@DisplayName("关注列表翻页缺 list 字段")
class BilibiliApiUtilFollowingListTest {
    private static final long LOGIN_UID = 90001L;

    /**
     * 60 个关注、每页 50 个：第 1 页满、第 2 页 10 个
     */
    private static final List<Long> SIXTY_FOLLOWED = new ArrayList<>();

    static {
        for (long uid = 1001; uid <= 1060; uid++) {
            SIXTY_FOLLOWED.add(uid);
        }
    }

    @Test
    @DisplayName("第 2 页缺 list 字段：抛出说清第几页，不把第 1 页那几个当完整表交出去")
    void secondPageMissingListThrowsInsteadOfReturningAPartialList() {
        FakeFollowingsApi api = new FakeFollowingsApi();
        api.following.addAll(SIXTY_FOLLOWED);
        api.listlessPages.add(2);

        RequestFailedException e = assertThrows(RequestFailedException.class,
                () -> api.getFollowingUps(LOGIN_UID),
                "第 2 页缺 list 就是取不全，必须抛出——交出第 1 页那 50 个，"
                        + "自动关注就会把排在第 2 页的已关注主播当成没关注");

        assertTrue(e.getMessage().contains("第 2 页"), "报错要说清是第几页缺 list，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("list"), "报错要说清缺的是 list 字段，实际: " + e.getMessage());
        assertEquals(List.of(1, 2), api.pageRequests,
                "翻到缺 list 的那一页才停，实际翻页: " + api.pageRequests);
    }

    @Test
    @DisplayName("某页 list 是空数组：照旧当翻到底，返回已取到的那几页")
    void emptyListIsStillTheEndAndReturnsWhatWasFetched() {
        // 阴性对照：空数组是「翻到底了」的正常形状，不能跟着缺字段那条一起改红
        FakeFollowingsApi api = new FakeFollowingsApi();
        api.following.addAll(SIXTY_FOLLOWED);
        api.emptyListPages.add(2);

        List<Up> ups = api.getFollowingUps(LOGIN_UID);

        assertEquals(50, ups.size(), "第 2 页是空数组即翻到底，只该返回第 1 页那 50 个，实际: " + ups.size());
        assertEquals(1001L, ups.get(0).getUid().longValue(), "第 1 页的内容照旧，实际: " + ups.get(0).getUid());
        assertEquals(List.of(1, 2), api.pageRequests, "空数组那一页也要翻到，实际翻页: " + api.pageRequests);
    }

    /**
     * 假接口：只认关注列表翻页，别的请求一律判错
     */
    private static final class FakeFollowingsApi extends BilibiliApiUtil {
        private static final Pattern PAGE_SIZE = Pattern.compile("[?&]ps=(\\d+)");

        private static final Pattern PAGE = Pattern.compile("[?&]pn=(\\d+)");

        /**
         * 平台上的关注名单，按页切
         */
        final List<Long> following = new ArrayList<>();

        /**
         * 取这些页时应答里不带 list 字段
         */
        final Set<Integer> listlessPages = new HashSet<>();

        /**
         * 取这些页时应答里 list 是空数组
         */
        final Set<Integer> emptyListPages = new HashSet<>();

        /**
         * 打出去的翻页请求，记页码
         */
        final List<Integer> pageRequests = new ArrayList<>();

        FakeFollowingsApi() {
            super(mock(HttpUtil.class), new NovaBilibiliProperties(), mock(BilibiliRiskMetrics.class));
            setCookies(new Cookies("placeholder-sess", "placeholder-jct", "placeholder-buvid"));
        }

        @Override
        public JSONObject requestBilibiliApi(String url, String method, Map<String, String> headers,
                                             Map<String, Object> params) {
            if (!url.contains("/x/relation/followings")) {
                throw new AssertionError("不该请求别的接口: " + url);
            }
            int size = (int) number(PAGE_SIZE, url);
            int page = (int) number(PAGE, url);
            pageRequests.add(page);

            JSONObject data = new JSONObject();
            if (listlessPages.contains(page)) {
                return data;
            }
            JSONArray list = new JSONArray();
            if (!emptyListPages.contains(page)) {
                int from = (page - 1) * size;
                for (int i = from; i < Math.min(following.size(), from + size); i++) {
                    JSONObject item = new JSONObject();
                    item.put("mid", following.get(i));
                    item.put("uname", "主播" + following.get(i));
                    list.add(item);
                }
            }
            data.put("list", list);
            return data;
        }

        private static long number(Pattern pattern, String url) {
            Matcher matcher = pattern.matcher(url);
            assertTrue(matcher.find(), "请求地址里缺 " + pattern.pattern() + ": " + url);
            return Long.parseLong(matcher.group(1));
        }
    }
}
