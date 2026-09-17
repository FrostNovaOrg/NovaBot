package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.ResponseCodeException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动关注
 * <p>
 * 不发起网络请求：关注列表翻页与关注操作由假接口给出，分页按请求里的 ps／pn 切，
 * 对已关注的账号再发关注回 22014，与平台一致。数的是真正打出去的翻页请求与关注请求。
 * uid 一律是占位值。
 */
@DisplayName("自动关注")
class BilibiliAutoFollowTest {
    private static final long LOGIN_UID = 90001L;

    private static final long OTHER_LOGIN_UID = 90002L;

    /**
     * 平台上已关注 60 个账号：默认每页 50 个，要翻两页
     */
    private static final List<Long> SIXTY_FOLLOWED = LongStream.rangeClosed(1001, 1060).boxed().toList();

    /**
     * 排在第 1 页的已关注主播
     */
    private static final long ON_PAGE_ONE = 1003L;

    /**
     * 排在第 2 页的已关注主播
     */
    private static final long ON_PAGE_TWO = 1055L;

    /**
     * 还没关注的主播
     */
    private static final long NOT_FOLLOWED = 2001L;

    private FakeRelationApi api;

    private BilibiliAccountService account;

    private AbstractDataSource dataSource;

    private BilibiliDynamicService service;

    private Runnable followTask;

    private Logger apiLogger;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        api = new FakeRelationApi();
        api.following.addAll(SIXTY_FOLLOWED);

        account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        when(account.getLoginUid()).thenReturn(LOGIN_UID);

        dataSource = mock(AbstractDataSource.class);
        configure(ON_PAGE_ONE, ON_PAGE_TWO);

        // 自动关注保持默认开启：调度器上挂两个任务，先动态轮询、后自动关注
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        service = new BilibiliDynamicService(api, account, properties, event -> {
        }, scheduler);
        service.start(dataSource);

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).scheduleAtFixedRate(tasks.capture(), any(Duration.class));
        followTask = tasks.getAllValues().get(1);

        logs = new ListAppender<>();
        logs.start();
        apiLogger = (Logger) LoggerFactory.getLogger(BilibiliApiUtil.class);
        serviceLogger = (Logger) LoggerFactory.getLogger(BilibiliDynamicService.class);
        apiLogger.addAppender(logs);
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        apiLogger.detachAppender(logs);
        serviceLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("关注列表第 2 页取失败：本轮一个都不补关注，排在第 2 页的已关注主播不会被再关注一遍")
    void secondPageFailureFollowsNobody() {
        api.failingPages.add(2);

        followTask.run();

        assertEquals(List.of(), api.followRequests,
                "关注列表没取全时不该发关注请求，实际发了 " + api.followRequests.size() + " 次: " + api.followRequests
                        + "，其中平台回「已经关注」" + api.alreadyFollowingReplies + " 次");
        assertTrue(warnContaining("本轮不补关注") != null,
                "没取全要写一条 WARN 说明本轮不补关注，实际日志: " + messages());
    }

    @Test
    @DisplayName("关注列表第 1 页就取失败：不当作一个都没关注，本轮不补关注")
    void firstPageFailureFollowsNobody() {
        api.failingPages.add(1);

        followTask.run();

        assertEquals(List.of(), api.followRequests,
                "第 1 页取失败时不该发关注请求，实际发了 " + api.followRequests.size() + " 次: " + api.followRequests
                        + "，其中平台回「已经关注」" + api.alreadyFollowingReplies + " 次");
    }

    @Test
    @DisplayName("登录账号 uid 暂时取不到：不拿空表去比，本轮不拉关注列表也不补关注")
    void unknownLoginUidFollowsNobody() {
        when(account.getLoginUid()).thenReturn(null);

        followTask.run();

        assertEquals(List.of(), api.followRequests,
                "取不到登录账号时不该发关注请求，实际发了 " + api.followRequests.size() + " 次: " + api.followRequests);
        assertEquals(List.of(), api.pageRequests, "取不到登录账号时不该翻关注列表，实际翻了: " + api.pageRequests);
    }

    @Test
    @DisplayName("推送名单没变：下一轮不再拉关注列表")
    void unchangedRosterDoesNotRefetch() {
        followTask.run();
        followTask.run();

        assertEquals(List.of(1, 2), api.pageRequests,
                "名单没变时两轮合起来只该翻一遍关注列表（两页），实际翻页: " + api.pageRequests);
    }

    @Test
    @DisplayName("推送名单里新加了主播：下一轮就拉关注列表，只关注新加的这一位")
    void newStreamerIsFollowedNextRound() {
        configure(ON_PAGE_ONE);
        followTask.run();

        configure(ON_PAGE_ONE, NOT_FOLLOWED);
        followTask.run();

        assertEquals(List.of(1, 2, 1, 2), api.pageRequests, "新加主播后下一轮应重新翻关注列表，实际翻页: " + api.pageRequests);
        assertEquals(List.of(NOT_FOLLOWED), api.followRequests, "只该关注新加的主播，实际: " + api.followRequests);
    }

    @Test
    @DisplayName("推送名单里新加了主播：补拉过一遍之后名单没再变，再下一轮不再拉关注列表")
    void addedStreamerIsNotRefetchedAgain() {
        configure(ON_PAGE_ONE);
        followTask.run();

        configure(ON_PAGE_ONE, NOT_FOLLOWED);
        followTask.run();
        followTask.run();

        assertEquals(List.of(1, 2, 1, 2), api.pageRequests,
                "新加主播后只该补翻一遍关注列表，之后名单没变就不该再翻，实际翻页: " + api.pageRequests);
    }

    @Test
    @DisplayName("关注列表取失败后：下一轮照样再拉，取全了才补关注，已关注的不重复关注")
    void failedFetchRetriesNextRound() {
        configure(ON_PAGE_ONE, NOT_FOLLOWED);
        api.failingPages.add(1);
        followTask.run();

        api.failingPages.clear();
        followTask.run();

        assertEquals(List.of(NOT_FOLLOWED), api.followRequests,
                "取全之后只该关注没关注的那位，实际: " + api.followRequests
                        + "，其中平台回「已经关注」" + api.alreadyFollowingReplies + " 次");
    }

    @Test
    @DisplayName("换了登录账号：下一轮重新拉新账号的关注列表")
    void switchedAccountRefetches() {
        followTask.run();

        when(account.getLoginUid()).thenReturn(OTHER_LOGIN_UID);
        followTask.run();

        assertTrue(api.listedAccounts.contains(OTHER_LOGIN_UID),
                "换号后应翻新账号的关注列表，实际翻过的账号: " + api.listedAccounts);
    }

    @Test
    @DisplayName("换了登录账号：新账号的关注列表拉过一遍之后，再下一轮不再拉")
    void switchedAccountIsNotRefetchedAgain() {
        followTask.run();

        when(account.getLoginUid()).thenReturn(OTHER_LOGIN_UID);
        followTask.run();
        followTask.run();

        assertEquals(List.of(1, 2, 1, 2), api.pageRequests,
                "换号后只该把新账号的关注列表翻一遍，之后不该再翻，实际翻页: " + api.pageRequests);
    }

    @Test
    @DisplayName("名单不变时关注列表每小时复核一次：不满一小时不拉，满一小时拉，在平台上被取消的关注会补上")
    void rechecksHourly() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service.followConfiguredUps(start);
        assertEquals(List.of(1, 2), api.pageRequests, "首轮应翻一遍关注列表，实际: " + api.pageRequests);

        api.following.remove(Long.valueOf(ON_PAGE_ONE));

        service.followConfiguredUps(start.plus(Duration.ofMinutes(59)).plusSeconds(59));
        assertEquals(List.of(1, 2), api.pageRequests, "不满一小时不该再翻，实际: " + api.pageRequests);
        assertEquals(List.of(), api.followRequests, "不满一小时不该补关注，实际: " + api.followRequests);

        service.followConfiguredUps(start.plus(Duration.ofHours(1)));
        assertEquals(List.of(1, 2, 1, 2), api.pageRequests, "满一小时应复核一遍，实际: " + api.pageRequests);
        assertEquals(List.of(ON_PAGE_ONE), api.followRequests, "复核发现被取消的关注应补上，实际: " + api.followRequests);
    }

    @Test
    @DisplayName("每小时复核时关注列表取失败：不等下一个小时，下一轮照样再拉")
    void failedRecheckRetriesNextRound() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service.followConfiguredUps(start);

        api.failingPages.add(1);
        Instant recheck = start.plus(Duration.ofHours(1));
        service.followConfiguredUps(recheck);

        api.failingPages.clear();
        service.followConfiguredUps(recheck.plusSeconds(30));

        assertEquals(List.of(1, 2, 1, 1, 2), api.pageRequests,
                "复核取失败后下一轮应重新翻关注列表，不该等满下一个小时，实际翻页: " + api.pageRequests);
    }

    @Test
    @DisplayName("关注时平台回「已经关注」：记为已关注，不记错误")
    void alreadyFollowingIsNotAnError() {
        configure(NOT_FOLLOWED);
        // 关注列表还没刷出这一位，平台上其实已经关注了
        api.hiddenFromList.add(NOT_FOLLOWED);

        followTask.run();

        assertEquals(List.of(NOT_FOLLOWED), api.followRequests, "应发一次关注请求，实际: " + api.followRequests);
        List<String> errors = logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(List.of(), errors, "平台回「已经关注」不该记 ERROR，实际: " + errors);
        assertTrue(messages().stream().anyMatch(message -> message.contains("已在关注")),
                "应写明这一位已在关注中，实际日志: " + messages());
    }

    private void configure(long... uids) {
        List<PushUser> users = new ArrayList<>();
        for (long uid : uids) {
            PushUser user = new PushUser();
            user.setUid(uid);
            user.setUname("主播" + uid);
            user.setPlatform(BilibiliPlatform.BILIBILI.id());
            users.add(user);
        }
        when(dataSource.getUsers(anyString())).thenReturn(users);
    }

    private String warnContaining(String keyword) {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(keyword))
                .findFirst()
                .orElse(null);
    }

    private List<String> messages() {
        return logs.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    /**
     * 假接口：只认关注列表翻页与关注操作两路，别的请求一律判错
     */
    private static final class FakeRelationApi extends BilibiliApiUtil {
        private static final Pattern VMID = Pattern.compile("[?&]vmid=(\\d+)");

        private static final Pattern PAGE_SIZE = Pattern.compile("[?&]ps=(\\d+)");

        private static final Pattern PAGE = Pattern.compile("[?&]pn=(\\d+)");

        /**
         * 平台上已关注、且关注列表里查得到的账号，按列表先后排
         */
        final List<Long> following = new ArrayList<>();

        /**
         * 平台上已关注、但关注列表还没刷出来的账号
         */
        final Set<Long> hiddenFromList = new HashSet<>();

        /**
         * 取这些页时回 -503
         */
        final Set<Integer> failingPages = new HashSet<>();

        /**
         * 打出去的翻页请求，记页码
         */
        final List<Integer> pageRequests = new ArrayList<>();

        /**
         * 翻页请求所查的账号
         */
        final List<Long> listedAccounts = new ArrayList<>();

        /**
         * 打出去的关注请求，记对象 uid
         */
        final List<Long> followRequests = new ArrayList<>();

        /**
         * 平台回「已经关注」的次数
         */
        int alreadyFollowingReplies;

        FakeRelationApi() {
            super(mock(HttpUtil.class), new NovaBilibiliProperties(), mock(BilibiliRiskMetrics.class));
            setCookies(new Cookies("placeholder-sess", "placeholder-jct", "placeholder-buvid"));
        }

        @Override
        public JSONObject requestBilibiliApi(String url, String method, Map<String, String> headers,
                                             Map<String, Object> params) {
            if (url.contains("/x/relation/followings")) {
                return listPage(url);
            }
            if (url.contains("/x/relation/modify")) {
                return follow(params);
            }
            throw new AssertionError("自动关注不该请求别的接口: " + url);
        }

        private JSONObject listPage(String url) {
            listedAccounts.add(number(VMID, url));
            int size = (int) number(PAGE_SIZE, url);
            int page = (int) number(PAGE, url);
            pageRequests.add(page);
            if (failingPages.contains(page)) {
                throw new ResponseCodeException(-503, "服务暂不可用");
            }

            JSONArray list = new JSONArray();
            int from = (page - 1) * size;
            for (int i = from; i < Math.min(following.size(), from + size); i++) {
                JSONObject item = new JSONObject();
                item.put("mid", following.get(i));
                item.put("uname", "主播" + following.get(i));
                list.add(item);
            }
            JSONObject data = new JSONObject();
            data.put("list", list);
            return data;
        }

        private JSONObject follow(Map<String, Object> params) {
            long uid = ((Number) params.get("fid")).longValue();
            followRequests.add(uid);
            if (following.contains(uid) || hiddenFromList.contains(uid)) {
                alreadyFollowingReplies++;
                throw new ResponseCodeException(22014, "已经关注用户，无法重复关注");
            }
            following.add(0, uid);
            return new JSONObject();
        }

        private static long number(Pattern pattern, String url) {
            Matcher matcher = pattern.matcher(url);
            assertTrue(matcher.find(), "请求地址里缺 " + pattern.pattern() + ": " + url);
            return Long.parseLong(matcher.group(1));
        }
    }
}
