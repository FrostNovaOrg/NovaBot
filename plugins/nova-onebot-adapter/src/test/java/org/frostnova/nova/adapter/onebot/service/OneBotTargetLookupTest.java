package org.frostnova.nova.adapter.onebot.service;

import org.frostnova.nova.adapter.onebot.controller.OneBotTargetLookupController;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OneBotTargetLookupTest {
    static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);
    OneBotTargetDirectory directory;
    OneBotTargetLookup lookup;
    MockMvc mvc;
    AtomicReference<Instant> now;

    @BeforeEach void setup() {
        directory = mock(OneBotTargetDirectory.class);
        when(directory.supports("qq")).thenReturn(true);
        when(directory.supports("qq-other")).thenReturn(true);
        now = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        lookup = new OneBotTargetLookup(directory, clock);
        when(directory.cachedSnapshot("qq")).thenReturn(snapshot());
        when(directory.snapshot("qq", false)).thenReturn(snapshot());
        when(directory.snapshot("qq", true)).thenReturn(snapshot());
        mvc = MockMvcBuilders.standaloneSetup(new OneBotTargetLookupController(lookup))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class))).build();
    }
    OneBotTargetDirectory.Snapshot snapshot() {
        return new OneBotTargetDirectory.Snapshot("qq", List.of(new OneBotTargetDirectory.Group(81001, "Shared group", 12, true)),
                List.of(new OneBotTargetDirectory.Friend(91001, "Friend", "private remark")), now.get(), now.get(), false, null);
    }
    ResultActions query(String type, String num) throws Exception {
        return mvc.perform(get("/config/api/bot/targets/lookup").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .param("platform", "qq").param("type", type).param("num", num));
    }

    // 精确查一个群不能告诉成员这个群是否正在为别的主播推送，也不能带出其它群。
    @Test void exactGroupResponseContainsOnlyIdentity() throws Exception {
        query("group", "81001").andExpect(status().isOk()).andExpect(jsonPath("$.found").value(true))
                .andExpect(content().json("{\"success\":true,\"found\":true,\"platform\":\"qq\",\"type\":\"group\",\"num\":81001,\"name\":\"Shared group\"}",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.configured").doesNotExist());
        verify(directory, never()).snapshot(anyString(), anyBoolean());
    }

    // 好友只返回昵称；找不到的群和好友要分别解释，不把搜索当列表。
    @Test void friendsAndMissingNumbersHaveSpecificResults() throws Exception {
        query("friend", "91001").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("Friend"))
                .andExpect(jsonPath("$.remark").doesNotExist()).andExpect(jsonPath("$.configured").doesNotExist());
        query("group", "89999").andExpect(jsonPath("$.found").value(false)).andExpect(jsonPath("$.message").value("机器人不在这个群"));
        query("friend", "99999").andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("机器人没有这个好友")));
    }

    // 关键字、局部名字、非整数和缺号码不能借精确查号口枚举名单。
    @Test void rejectsKeywordsAndMalformedNumbers() throws Exception {
        for (String num : List.of("Shared", "81*", "81.1", "0", "-1", "81001,91001", " 81001"))
            query("group", num).andExpect(status().isBadRequest());
        mvc.perform(get("/config/api/bot/targets/lookup").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .param("platform", "qq").param("type", "group").param("num", "81001").param("q", "Shared"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/config/api/bot/targets/lookup").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .param("platform", "qq").param("type", "group")).andExpect(status().isBadRequest());
        verify(directory, never()).snapshot(anyString(), anyBoolean());
    }

    // 连续查不同群、不同平台仍共用每账号一分钟一次的预算，限频期间缓存命中照常可用。
    @Test void refreshBudgetIsPerAccountAcrossPlatformsAndExpires() {
        assertFalse(lookup.lookup("qq", PushTargetType.GROUP, 89999, MEMBER).found());
        assertTrue(lookup.lookup("qq-other", PushTargetType.GROUP, 89999, MEMBER).message().contains("刚查过"));
        assertTrue(lookup.lookup("qq", PushTargetType.GROUP, 81001, MEMBER).found());
        now.set(now.get().plusSeconds(59));
        assertTrue(lookup.lookup("qq", PushTargetType.FRIEND, 99999, MEMBER).message().contains("稍后"));
        verify(directory, times(1)).snapshot("qq", true);
        verify(directory, never()).snapshot("qq-other", true);
        verify(directory, never()).snapshot(anyString(), eq(false));
        now.set(now.get().plusSeconds(1));
        lookup.lookup("qq", PushTargetType.GROUP, 89999, MEMBER);
        verify(directory, times(2)).snapshot("qq", true);
    }

    // 十分钟后即使号码还在旧快照里也应更新；限频时保留缓存结果并说明稍后再试。
    @Test void expiredSnapshotRefreshesUnlessAccountBudgetWasSpent() {
        now.set(now.get().plusSeconds(601));
        assertTrue(lookup.lookup("qq", PushTargetType.GROUP, 81001, MEMBER).found());
        verify(directory).snapshot("qq", true);
        var limited = lookup.lookup("qq", PushTargetType.GROUP, 81001, MEMBER);
        assertTrue(limited.found()); assertTrue(limited.message().contains("刚查过"));
        verify(directory, times(1)).snapshot("qq", true);
    }

    // 快照缺号时只强刷所查平台一次，刷新后查到即可返回；管理员不受成员限频影响。
    @Test void missRefreshesOnceAndAdministratorCanRefreshAgain() {
        when(directory.cachedSnapshot("qq")).thenReturn(null);
        assertTrue(lookup.lookup("qq", PushTargetType.GROUP, 81001, MEMBER).found());
        verify(directory).snapshot("qq", true);
        var admin = ConfigUiAccount.local(Instant.EPOCH);
        lookup.lookup("qq", PushTargetType.GROUP, 89999, admin);
        lookup.lookup("qq", PushTargetType.GROUP, 89999, admin);
        verify(directory, times(3)).snapshot("qq", true);
    }
}
