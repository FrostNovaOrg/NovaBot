package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挑选推送目标那两支接口
 *
 * <h2>为什么需要校验「已配置」这一栏</h2>
 * 挑选界面靠它把已经配过的那几条标出来。标错的两个方向代价不同：漏标会让使用者重复配一遍
 * （数据源那一侧会拦），<b>误标则会让他以为配过了而其实没配</b>——那位主播开播时没有推送，
 * 而界面上一切正常。所以阳性与阴性两侧都要钉住。
 */
@DisplayName("挑选推送目标")
class ConfigUiPushTargetsControllerTest {
    private static final String SENDER = "bot-a";

    private static final Instant FETCHED_AT = Instant.parse("2026-09-04T00:00:00Z");

    private PushTargetDirectory directory;

    private AbstractDataSource dataSource;

    private ConfigUiPushTargetsController controller;

    @BeforeEach
    void setUp() {
        directory = mock(PushTargetDirectory.class);
        dataSource = mock(AbstractDataSource.class);
        controller = new ConfigUiPushTargetsController(List.of(directory), List.of(), dataSource);

        when(dataSource.getAllUsers()).thenReturn(List.of());
        when(directory.snapshots(false)).thenReturn(List.of(snapshot(SENDER, FETCHED_AT, false)));
        when(directory.snapshots(true)).thenReturn(List.of(snapshot(SENDER, FETCHED_AT, false)));
    }

    private static PushTargetDirectory.Snapshot snapshot(String sender, Instant fetchedAt, boolean stale) {
        return new PushTargetDirectory.Snapshot(sender,
                List.of(new PushTargetDirectory.Group(10000002L, "开播提醒", 42, Boolean.TRUE),
                        new PushTargetDirectory.Group(10000003L, "测试群", 7, Boolean.FALSE)),
                List.of(new PushTargetDirectory.Friend(10000004L, "甲", "运维"),
                        new PushTargetDirectory.Friend(10000005L, "乙", "")),
                fetchedAt, fetchedAt, stale, stale ? "连不上机器人" : null);
    }

    /**
     * 数据源里配了一条：把开播提醒群作为某位主播的推送通道
     */
    private void datasourceHasGroup(String platform, long num) {
        PushTarget target = new PushTarget();
        target.setPlatform(platform);
        target.setType(PushTargetType.GROUP);
        target.setNum(num);

        PushUser user = new PushUser();
        user.setUid(1L);
        user.setPlatform("some-live-platform");
        user.setTargets(new ArrayList<>(List.of(target)));

        when(dataSource.getAllUsers()).thenReturn(List.of(user));
    }

    private JSONObject body(String type, String q) {
        ResponseEntity<JSONObject> response = controller.targets(type, q);
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    private JSONArray items(String type, String q) {
        return body(type, q).getJSONArray("items");
    }

    @Test
    @DisplayName("群一条一条列出来, 字段齐")
    void listsGroups() {
        JSONArray items = items("group", null);

        assertEquals(2, items.size());

        JSONObject first = items.getJSONObject(0);
        assertEquals(SENDER, first.getString("sender"));
        assertEquals(10000002L, first.getLong("num"));
        assertEquals("开播提醒", first.getString("name"));
        assertEquals(42, first.getInteger("memberCount").intValue());
        assertEquals(Boolean.TRUE, first.getBoolean("admin"));
        assertEquals(Boolean.FALSE, first.getBoolean("configured"));
    }

    @Test
    @DisplayName("好友一条一条列出来, 昵称与备注分开给")
    void listsFriends() {
        JSONArray items = items("friend", null);

        assertEquals(2, items.size());

        JSONObject first = items.getJSONObject(0);
        assertEquals(SENDER, first.getString("sender"));
        assertEquals(10000004L, first.getLong("num"));
        assertEquals("甲", first.getString("nickname"));
        assertEquals("运维", first.getString("remark"));
        assertEquals(Boolean.FALSE, first.getBoolean("configured"));
    }

    @Test
    @DisplayName("已经配过的那一条标成已配置")
    void marksConfiguredTargets() {
        datasourceHasGroup(SENDER, 10000002L);

        JSONArray items = items("group", null);

        assertEquals(Boolean.TRUE, items.getJSONObject(0).getBoolean("configured"));
        assertEquals(Boolean.FALSE, items.getJSONObject(1).getBoolean("configured"), "没配过的不能跟着一起标");
    }

    /**
     * 🔴 号相同但推送平台不同，是两个不同的目标。只按号比对的话，
     * 装了第二个机器人之后，两边同号的群会互相标成「已配置」。
     */
    @Test
    @DisplayName("⚠️ 号相同而推送平台不同的不算已配置")
    void configuredIsScopedToSender() {
        datasourceHasGroup("bot-b", 10000002L);

        assertEquals(Boolean.FALSE, items("group", null).getJSONObject(0).getBoolean("configured"));
    }

    /**
     * 🔴 好友与群的号在各自的命名空间里，允许相同。只按号比对会把一个已配好友
     * 标到一个同号的群上。
     */
    @Test
    @DisplayName("⚠️ 配的是同号好友时, 同号的群不算已配置")
    void configuredIsScopedToType() {
        PushTarget target = new PushTarget();
        target.setPlatform(SENDER);
        target.setType(PushTargetType.FRIEND);
        target.setNum(10000002L);

        PushUser user = new PushUser();
        user.setUid(1L);
        user.setPlatform("some-live-platform");
        user.setTargets(new ArrayList<>(List.of(target)));
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        assertEquals(Boolean.FALSE, items("group", null).getJSONObject(0).getBoolean("configured"));
    }

    @Test
    @DisplayName("按名筛")
    void filtersByName() {
        JSONArray items = items("group", "测试");

        assertEquals(1, items.size());
        assertEquals(10000003L, items.getJSONObject(0).getLong("num"));
    }

    @Test
    @DisplayName("按号筛, 给一截也认")
    void filtersByNumber() {
        JSONArray items = items("group", "0003");

        assertEquals(1, items.size());
        assertEquals(10000003L, items.getJSONObject(0).getLong("num"));
    }

    @Test
    @DisplayName("好友按备注也筛得到")
    void filtersFriendsByRemark() {
        JSONArray items = items("friend", "运维");

        assertEquals(1, items.size());
        assertEquals(10000004L, items.getJSONObject(0).getLong("num"));
    }

    @Test
    @DisplayName("关键字留空或全是空白时不筛")
    void blankKeywordDoesNotFilter() {
        assertEquals(2, items("group", "").size());
        assertEquals(2, items("group", "   ").size());
    }

    @Test
    @DisplayName("一条也筛不到时给空表, 不报错")
    void noMatchYieldsEmptyList() {
        assertEquals(0, items("group", "对不上的字").size());
    }

    @Test
    @DisplayName("type 不是 group 或 friend 时回 400 并说清能填什么")
    void rejectsUnknownType() {
        for (String type : new String[]{null, "", "groups", "GROUP"}) {
            ResponseEntity<JSONObject> response = controller.targets(type, null);

            assertEquals(400, response.getStatusCode().value(), "type=" + type);
            assertEquals(Boolean.FALSE, response.getBody().getBoolean("success"));
            assertTrue(response.getBody().getString("message").contains("group"), "要说清能填什么");
            assertEquals("type 只能是 group 或 friend", response.getBody().getString("message"));
        }
    }

    @Test
    @DisplayName("表级带上取回时刻与是否过期, 并逐平台列出来")
    void reportsFreshness() {
        JSONObject body = body("group", null);

        assertEquals(FETCHED_AT.toString(), body.getString("fetchedAt"));
        assertFalse(body.getBoolean("stale"));

        JSONArray senders = body.getJSONArray("senders");
        assertEquals(1, senders.size());
        assertEquals(SENDER, senders.getJSONObject(0).getString("sender"));
        assertEquals(2, senders.getJSONObject(0).getInteger("groups").intValue());
        assertEquals(2, senders.getJSONObject(0).getInteger("friends").intValue());
        assertNull(senders.getJSONObject(0).getString("message"));
    }

    /**
     * 🔴 表级的两个字段必须往保守里取。取最新的时刻、或者「全都过期才算过期」，
     * 都会让一张半新半旧的表在界面上看起来是全新的。
     */
    @Test
    @DisplayName("⚠️ 多平台时取回时刻取最早的, 有一个过期就整表算过期")
    void tableLevelFreshnessIsConservative() {
        Instant older = FETCHED_AT.minusSeconds(3600);
        when(directory.snapshots(false)).thenReturn(List.of(
                snapshot(SENDER, FETCHED_AT, false),
                snapshot("bot-b", older, true)));

        JSONObject body = body("group", null);

        assertEquals(older.toString(), body.getString("fetchedAt"));
        assertTrue(body.getBoolean("stale"));
        assertEquals(2, body.getJSONArray("senders").size(), "是哪个平台过期的要看得出来");
        assertEquals("连不上机器人", body.getJSONArray("senders").getJSONObject(1).getString("message"));
    }

    @Test
    @DisplayName("一次都没取成过时取回时刻为空, 表算过期")
    void neverFetchedHasNoTimestamp() {
        when(directory.snapshots(false)).thenReturn(List.of(
                new PushTargetDirectory.Snapshot(SENDER, List.of(), List.of(), null, FETCHED_AT, true, "连不上机器人")));

        JSONObject body = body("group", null);

        assertNull(body.getString("fetchedAt"));
        assertTrue(body.getBoolean("stale"));
        assertEquals(0, body.getJSONArray("items").size());
    }

    @Test
    @DisplayName("刷新走强制重取那一路")
    void refreshForcesRefetch() {
        when(directory.snapshots(true)).thenReturn(List.of(snapshot(SENDER, FETCHED_AT, false)));

        JSONObject body = controller.refresh();

        assertTrue(body.getBoolean("success"));
        assertEquals(FETCHED_AT.toString(), body.getString("fetchedAt"));
        assertFalse(body.getBoolean("stale"));
    }

    /**
     * 挑选界面只负责挑，配与不配由推送页那一侧写。这一条钉住它不会顺手改到数据源上
     */
    @Test
    @DisplayName("⚠️ 只读数据源, 一个字也不往里写")
    void neverWritesToDatasource() {
        datasourceHasGroup(SENDER, 10000002L);

        items("group", null);
        controller.refresh();

        verify(dataSource, never()).add(ArgumentMatchers.<PushUser>any());
        verify(dataSource, never()).update(ArgumentMatchers.<PushUser>any());
        verify(dataSource, never()).remove(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("通用路 /api/bot/targets 与刷新路按常量挂上")
    void genericTargetsPathIsMapped() throws Exception {
        assertEquals("/config/api/bot/targets", ConfigUiPushTargetsController.TARGETS_PATH);
        assertEquals("/config/api/bot/targets/refresh", ConfigUiPushTargetsController.REFRESH_PATH);

        GetMapping get = ConfigUiPushTargetsController.class
                .getMethod("targets", String.class, String.class)
                .getAnnotation(GetMapping.class);
        PostMapping post = ConfigUiPushTargetsController.class
                .getMethod("refresh")
                .getAnnotation(PostMapping.class);

        assertTrue(Set.of(get.value()).contains(ConfigUiPushTargetsController.TARGETS_PATH));
        assertTrue(Set.of(post.value()).contains(ConfigUiPushTargetsController.REFRESH_PATH));

        JSONObject body = body("group", null);
        assertEquals(Boolean.TRUE, body.getBoolean("success"));
        assertEquals(2, body.getJSONArray("items").size());
    }

    @Test
    @DisplayName("旧 GET／POST 已撤（无映射即 404），通用路仍 200")
    void legacyTargetsPathGone() throws Exception {
        GetMapping get = ConfigUiPushTargetsController.class
                .getMethod("targets", String.class, String.class)
                .getAnnotation(GetMapping.class);
        PostMapping post = ConfigUiPushTargetsController.class
                .getMethod("refresh")
                .getAnnotation(PostMapping.class);

        assertEquals(List.of(ConfigUiPushTargetsController.TARGETS_PATH), List.of(get.value()),
                "GET 只挂通用路；多一条即旧路未撤");
        assertEquals(List.of(ConfigUiPushTargetsController.REFRESH_PATH), List.of(post.value()),
                "POST 只挂通用路；多一条即旧路未撤");
        for (String v : get.value()) {
            assertFalse(v.contains("/onebot/"), "GET 不得再挂 /onebot/ 旧路: " + v);
        }
        for (String v : post.value()) {
            assertFalse(v.contains("/onebot/"), "POST 不得再挂 /onebot/ 旧路: " + v);
        }

        JSONObject body = body("group", null);
        assertEquals(Boolean.TRUE, body.getBoolean("success"));
        assertEquals(2, body.getJSONArray("items").size());
        assertEquals(FETCHED_AT.toString(), body.getString("fetchedAt"));

        JSONObject refreshed = controller.refresh();
        assertEquals(Boolean.TRUE, refreshed.getBoolean("success"));
    }
}
