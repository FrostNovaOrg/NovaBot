package org.frostnova.nova.adapter.onebot.service;

import org.frostnova.nova.core.config.ui.PushTargetLookup;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Exact lookup with an account-wide refresh budget shared by lookups and saves. */
@NovaComponent
public class OneBotTargetLookup implements PushTargetLookup {
    private final OneBotTargetDirectory directory;
    private final Clock clock;
    private final Map<String, Instant> refreshed = new HashMap<>();

    @Autowired
    public OneBotTargetLookup(OneBotTargetDirectory directory) { this(directory, Clock.systemUTC()); }

    OneBotTargetLookup(OneBotTargetDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    @Override public boolean supports(String platform) { return directory.supports(platform); }

    @Override public Result lookup(String platform, PushTargetType type, long num, ConfigUiAccount account) {
        if (!supports(platform)) return new Result(false, null, "推送平台不可用");
        boolean member = account != null && account.role() == ConfigUiAccount.Role.MEMBER;
        var snapshot = directory.cachedSnapshot(platform);
        boolean expired = snapshot == null || snapshot.attemptedAt() == null
                || !clock.instant().isBefore(snapshot.attemptedAt().plus(OneBotTargetDirectory.TTL));
        Result cached = find(snapshot, type, num);
        if (cached.found() && !expired) return cached;
        if (member && !reserveRefresh(account.id()))
            return new Result(cached.found(), cached.name(),
                    (cached.found() ? "" : cached.message() + "，") + "刚查过，请稍后再试");
        return find(directory.snapshot(platform, true), type, num);
    }

    private synchronized boolean reserveRefresh(String account) {
        Instant now = clock.instant();
        refreshed.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().plusSeconds(60)));
        return refreshed.putIfAbsent(account, now) == null;
    }

    private Result find(OneBotTargetDirectory.Snapshot snapshot, PushTargetType type, long num) {
        if (snapshot != null) {
            if (type == PushTargetType.GROUP) {
                for (var group : snapshot.groups()) if (group.num() == num) return new Result(true, group.name(), null);
            } else if (type == PushTargetType.FRIEND) {
                for (var friend : snapshot.friends()) if (friend.num() == num) return new Result(true, friend.nickname(), null);
            }
        }
        String message = type == PushTargetType.GROUP ? "机器人不在这个群" : "机器人没有这个好友";
        if (snapshot == null || snapshot.stale()) message += "（名单暂时无法更新，请稍后再试）";
        return new Result(false, null, message);
    }
}
