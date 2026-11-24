package org.frostnova.nova.adapter.qqbot;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.command.SuperAdministratorResolver;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.SessionMemberNames;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@NovaComponent
final class QqBotMembers implements SessionMemberNames, SuperAdministratorResolver {
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    private static final Pattern CODE_WINDOW = Pattern.compile(
            "(?=([" + CODE_ALPHABET + "]{" + CODE_LENGTH + "}))", Pattern.CASE_INSENSITIVE);
    private static final int MAX_WRONG_ATTEMPTS = 5;
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final BotMembers EMPTY = new BotMembers(Map.of(), Map.of());
    private static final String UNKNOWN_NICKNAME = "（昵称未知）";

    private final QqBotSettings settings;
    private final Clock clock;
    private final QqBotMembersFile file;
    private final SecureRandom random = new SecureRandom();
    // 只保留旧码的带盐摘要，重发错写的旧码时也能在分发前遮盖。
    private final Set<String> issuedCodeFingerprints = new HashSet<>();
    private final MessageDigest codeDigest = sha256();
    private final byte[] codeSalt = new byte[16];

    // 名字与权限按 AppID 分开。认领码和失败次数只留在内存中。
    private Map<String, BotMembers> bots;
    private PendingCode pending;
    private int wrongAttempts;

    QqBotMembers(QqBotSettings settings, ObjectProvider<Clock> clocks) {
        this.settings = settings;
        random.nextBytes(codeSalt);
        clock = clocks.getIfAvailable(Clock::systemUTC);
        file = new QqBotMembersFile(settings.directory());
        try {
            bots = file.load();
        } catch (IOException | RuntimeException e) {
            log.warn("无法读取官方机器人群成员资料");
        }
    }

    @Override
    public boolean supports(String platform) {
        return QqBotPluginAutoConfiguration.PLATFORM.equals(platform);
    }

    @Override
    public synchronized Map<Long, String> displayNames(String platform, PushTargetType type,
                                                       Long sessionNum, Collection<Long> memberUids) {
        if (!supports(platform) || type != PushTargetType.GROUP || bots == null) {
            return Map.of();
        }
        var seen = forApp(settings.snapshot().appId()).groups().getOrDefault(sessionNum, Map.of());
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long uid : memberUids) {
            if (uid == null) continue;
            SeenMember member = seen.get(uid);
            if (member != null && member.nickname() != null) {
                names.put(uid, member.nickname());
            }
        }
        return names;
    }

    @Override
    public synchronized boolean isSuperAdministrator(long senderUid) {
        var credentials = settings.snapshot().credentials();
        return credentials.configured() && bots != null && forApp(credentials.appId()).claims().containsKey(senderUid);
    }

    synchronized void observe(String appId, long group, long member, String nickname, Instant seenAt) {
        if (bots == null) return;
        BotMembers current = forApp(appId);
        var groupMembers = current.groups().getOrDefault(group, Map.of());
        SeenMember previous = groupMembers.get(member);
        if (previous != null && seenAt.isBefore(previous.seenAt())) {
            return;
        }
        if (nickname == null && previous != null) {
            nickname = previous.nickname();
        }
        var next = new SeenMember(maskClaimCodes(nickname), seenAt);
        if (!next.equals(previous)) {
            try {
                save(appId, new BotMembers(with(current.groups(), group, with(groupMembers, member, next)), current.claims()));
            } catch (IllegalStateException e) {
                log.warn("无法保存官方机器人群成员资料");
            }
        }
    }

    synchronized IssuedCode generate() {
        requireReadable();
        var credentials = settings.snapshot().credentials();
        if (!credentials.configured()) {
            throw new IllegalArgumentException("请先保存官方机器人凭据");
        }
        byte[] code = new byte[CODE_LENGTH];
        do {
            for (int i = 0; i < code.length; i++) {
                code[i] = (byte) CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length()));
            }
        } while (pending != null && MessageDigest.isEqual(pending.value(), code));
        Instant expiresAt = clock.instant().plus(CODE_LIFETIME);
        pending = new PendingCode(code, expiresAt, credentials);
        issuedCodeFingerprints.add(codeFingerprint(code));
        wrongAttempts = 0;
        return new IssuedCode(new String(code, StandardCharsets.US_ASCII), expiresAt);
    }

    synchronized boolean invalidateMalformedClaim(QqBotSettings.Credentials credentials, String content, String candidate) {
        // 正文恰好是「认领」加一个等长词时，交给正常兑换流程。
        if (content == null || (candidate != null && candidate.length() == CODE_LENGTH)) return false;
        PendingCode active = activeCode(clock.instant());
        if (active == null || active.credentials() != credentials) return false;

        var codes = CODE_WINDOW.matcher(content);
        while (codes.find()) {
            if (MessageDigest.isEqual(active.value(), normalizedCode(codes.group(1)))) {
                pending = null;
                return true;
            }
        }
        return false;
    }

    synchronized Claim redeem(QqBotSettings.Credentials credentials, long group, long member,
                              String nickname, String candidate) {
        Instant now = clock.instant();
        PendingCode active = activeCode(now);
        if (active == null || active.credentials() != credentials) {
            return null;
        }
        if (!MessageDigest.isEqual(active.value(), normalizedCode(candidate))) {
            if (++wrongAttempts >= MAX_WRONG_ATTEMPTS) pending = null;
            return null;
        }
        requireReadable();
        String appId = credentials.appId();
        BotMembers current = forApp(appId);
        SeenMember seen = current.groups().getOrDefault(group, Map.of()).get(member);
        String displayName = nickname != null ? nickname : seen == null ? null : seen.nickname();
        Claim claim = new Claim(group, displayName == null ? UNKNOWN_NICKNAME : maskClaimCodes(displayName), now);
        save(appId, new BotMembers(current.groups(), with(current.claims(), member, claim)));
        pending = null;
        return claim;
    }

    synchronized void revoke(long member) {
        requireReadable();
        String appId = settings.snapshot().appId();
        BotMembers current = forApp(appId);
        var claims = new LinkedHashMap<>(current.claims());
        if (claims.remove(member) != null) {
            save(appId, new BotMembers(current.groups(), claims));
        }
    }

    synchronized ClaimState claimState() {
        var code = activeCode(clock.instant());
        Map<Long, Claim> claims = bots == null ? Map.of() : forApp(settings.snapshot().appId()).claims();
        return new ClaimState(code == null ? null : code.expiresAt(), claims);
    }

    private PendingCode activeCode(Instant now) {
        if (pending != null && (!now.isBefore(pending.expiresAt())
                || pending.credentials() != settings.snapshot().credentials())) {
            pending = null;
        }
        return pending;
    }

    synchronized String maskClaimCodes(String text) {
        if (text == null || issuedCodeFingerprints.isEmpty()) return text;
        var codes = CODE_WINDOW.matcher(text);
        var masked = new StringBuilder();
        int end = 0;
        while (codes.find()) {
            if (!issuedCodeFingerprints.contains(codeFingerprint(normalizedCode(codes.group(1))))) continue;
            int start = codes.start();
            if (start >= end) masked.append(text, end, start).append('…');
            end = start + CODE_LENGTH;
        }
        return end == 0 ? text : masked.append(text, end, text.length()).toString();
    }

    private String codeFingerprint(byte[] code) {
        codeDigest.update(codeSalt);
        return HexFormat.of().formatHex(codeDigest.digest(code));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
    }

    private static byte[] normalizedCode(String candidate) {
        // 始终把固定长度的字节数组交给比较函数。非 ASCII 字符不能匹配认领码。
        byte[] result = new byte[CODE_LENGTH];
        if (candidate.length() == CODE_LENGTH) {
            for (int i = 0; i < result.length; i++) {
                char c = candidate.charAt(i);
                if (c >= 'a' && c <= 'z') c -= 'a' - 'A';
                result[i] = c < 128 ? (byte) c : 0;
            }
        }
        return result;
    }

    private BotMembers forApp(String appId) {
        return bots.getOrDefault(appId, EMPTY);
    }

    private void requireReadable() {
        if (bots == null) throw new IllegalStateException("无法读取官方机器人群成员资料");
    }

    private void save(String appId, BotMembers members) {
        var updated = with(bots, appId, members);
        try {
            file.save(updated);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("无法保存官方机器人群成员资料");
        }
        bots = updated;
    }

    private static <K, V> Map<K, V> with(Map<K, V> original, K key, V value) {
        var next = new LinkedHashMap<>(original);
        next.put(key, value);
        return next;
    }

    record SeenMember(String nickname, Instant seenAt) {
        SeenMember { Objects.requireNonNull(seenAt); }
    }

    record Claim(long groupNum, String nickname, Instant claimedAt) {
        Claim {
            Objects.requireNonNull(nickname);
            Objects.requireNonNull(claimedAt);
        }
    }

    record BotMembers(Map<Long, Map<Long, SeenMember>> groups, Map<Long, Claim> claims) {
        BotMembers {
            groups = Map.copyOf(groups);
            claims = Map.copyOf(claims);
        }
    }

    record ClaimState(Instant expiresAt, Map<Long, Claim> claims) {}

    record IssuedCode(String code, Instant expiresAt) {
        @Override
        public String toString() {
            return "超管认领码";
        }
    }

    private record PendingCode(byte[] value, Instant expiresAt, QqBotSettings.Credentials credentials) {}
}
