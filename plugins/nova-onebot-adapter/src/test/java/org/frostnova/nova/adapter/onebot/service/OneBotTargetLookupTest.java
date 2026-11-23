package org.frostnova.nova.adapter.onebot.service;

import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OneBotTargetLookupTest {
    static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);
    OneBotTargetDirectory directory;
    OneBotTargetLookup lookup;
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
    }
    OneBotTargetDirectory.Snapshot snapshot() {
        return new OneBotTargetDirectory.Snapshot("qq", List.of(new OneBotTargetDirectory.Group(81001, "Shared group", 12, true)),
                List.of(new OneBotTargetDirectory.Friend(91001, "Friend", "private remark")), now.get(), now.get(), false, null);
    }
    @Test void findsCachedGroupWithoutRefreshing() {
        var result = lookup.lookup("qq", PushTargetType.GROUP, 81001, MEMBER);
        assertTrue(result.found());
        assertEquals("Shared group", result.name());
        verify(directory, never()).snapshot(anyString(), anyBoolean());
    }

    @Test void friendsAndMissingNumbersHaveSpecificResults() {
        var friend = lookup.lookup("qq", PushTargetType.FRIEND, 91001, MEMBER);
        assertTrue(friend.found());
        assertEquals("Friend", friend.name());
        var missingGroup = lookup.lookup("qq", PushTargetType.GROUP, 89999, MEMBER);
        assertFalse(missingGroup.found());
        assertEquals("机器人不在这个群", missingGroup.message());
        var missingFriend = lookup.lookup("qq", PushTargetType.FRIEND, 99999, MEMBER);
        assertFalse(missingFriend.found());
        assertTrue(missingFriend.message().contains("机器人没有这个好友"));
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
