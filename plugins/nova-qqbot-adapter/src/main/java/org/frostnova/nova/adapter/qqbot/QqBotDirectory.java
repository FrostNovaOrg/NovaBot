package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PreDestroy;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.PushTargetDirectory;
import org.frostnova.nova.core.config.ui.PushTargetLookup;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/** QQ 官方机器人群和群成员的本地编号与群资料。 */
@Slf4j
@NovaComponent
public class QqBotDirectory implements PushTargetDirectory, PushTargetLookup {
    private static final long NUMBER_BASE = 1_000_000_000_000_000L;
    private static final BigInteger NUMBER_RANGE = BigInteger.valueOf((1L << 53) - NUMBER_BASE);
    private static final DateTimeFormatter JOIN_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneOffset.ofHours(8));
    private static final Duration FAILURE_COOLDOWN = Duration.ofHours(1);
    private static final Duration UNAVAILABLE_COOLDOWN = Duration.ofHours(24);
    private static final Proactive UNKNOWN_SWITCH = new Proactive(ProactiveStatus.UNKNOWN, null, null);
    private static final BotEntries EMPTY = new BotEntries(Map.of(), Map.of(), Map.of());

    private final QqBotSettings settings;
    private final QqBotApiClient api;
    private final Clock clock;
    private final Executor executor;
    private final Function<byte[], byte[]> hash;
    private final QqBotDirectoryFile file;
    private final Map<String, BotEntries> bots;
    private final Map<FetchKey, Object> pending = new HashMap<>();

    @Autowired
    QqBotDirectory(QqBotSettings settings, QqBotApiClient api) {
        this(settings, api, Clock.systemUTC(), Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "qqbot-directory");
            thread.setDaemon(true);
            return thread;
        }), QqBotDirectory::sha256);
    }

    QqBotDirectory(QqBotSettings settings, QqBotApiClient api, Clock clock, Executor executor,
                   Function<byte[], byte[]> hash) {
        this.settings = settings;
        this.api = api;
        this.clock = clock;
        this.executor = executor;
        this.hash = hash;
        file = new QqBotDirectoryFile(settings.directory());
        bots = load();
    }

    @PreDestroy
    void stop() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    public synchronized long groupJoined(String openid, Instant at) {
        return observeGroup(openid, at, Membership.JOIN);
    }

    /** 补登记新群。已移出的群由重新加入操作恢复。 */
    public synchronized long groupSeen(String openid, Instant at) {
        return observeGroup(openid, at, Membership.SEEN);
    }

    public synchronized long groupRemoved(String openid, Instant at) {
        return observeGroup(openid, at, Membership.REMOVE);
    }

    private long observeGroup(String openid, Instant at, Membership change) {
        Objects.requireNonNull(at);
        String appId = configuredApp();
        BotEntries bot = entries(appId);
        long number = number("group", openid);
        GroupEntry previous = bot.groups().get(number);
        String existing = previous == null ? bots.values().stream().map(scope -> scope.groups().get(number))
                .filter(Objects::nonNull).map(GroupEntry::openid).findFirst().orElse(null) : previous.openid();
        checkCollision("group", number, openid, existing);
        GroupEntry current;
        if (previous == null || (previous.removed() && change == Membership.JOIN)) {
            current = joinedGroup(number, openid, at, previous);
        } else {
            current = previous.withLastSeenAt(later(previous.lastSeenAt(), at));
        }
        if (change == Membership.REMOVE) {
            current = current.withRemoved(true);
        }
        if (!current.equals(previous)) {
            save(appId, bot.withGroups(replacing(bot.groups(), number, current)));
        }
        if (current.removed()) {
            pending.keySet().removeIf(key -> key.appId().equals(appId) && key.number() == number);
        } else {
            schedule(appId, current);
        }
        return number;
    }

    private static GroupEntry joinedGroup(long number, String openid, Instant at, GroupEntry previous) {
        if (previous == null) {
            return new GroupEntry(number, openid, defaultName(at), NameSource.DEFAULT, at, at, false,
                    UNKNOWN_SWITCH, null, null, null, null, null, null);
        }
        GroupEntry group = previous.withJoinedAt(at).withLastSeenAt(later(previous.lastSeenAt(), at))
                .withRemoved(false).withProactive(UNKNOWN_SWITCH).withRole(null);
        if (group.botState() != null && group.botState().retryAt() == null) {
            group = group.withBotState(null);
        }
        return group.nameSource() == NameSource.DEFAULT ? group.withName(defaultName(at)) : group;
    }

    /** 仅用于在群里见到的成员。 */
    public synchronized long memberSeen(String openid, Instant at) {
        Objects.requireNonNull(at);
        String appId = configuredApp();
        BotEntries bot = entries(appId);
        long number = number("member", openid);
        MemberEntry previous = bot.members().get(number);
        String existing = previous == null ? bots.values().stream().map(scope -> scope.members().get(number))
                .filter(Objects::nonNull).map(MemberEntry::openid).findFirst().orElse(null) : previous.openid();
        checkCollision("member", number, openid, existing);
        MemberEntry current = previous == null ? new MemberEntry(number, openid, at, at)
                : new MemberEntry(number, openid, previous.firstSeenAt(), later(previous.lastSeenAt(), at));
        if (!current.equals(previous)) {
            save(appId, bot.withMembers(replacing(bot.members(), number, current)));
        }
        return number;
    }

    public synchronized Optional<GroupEntry> group(long number) {
        return Optional.ofNullable(currentEntries().groups().get(number));
    }

    synchronized Optional<GroupEntry> group(String appId, long number) {
        requireReadable();
        return Optional.ofNullable(entries(appId).groups().get(number));
    }

    public synchronized Optional<String> groupOpenId(long number) {
        return group(number).map(GroupEntry::openid);
    }

    public synchronized OptionalLong groupNumber(String openid) {
        long number = number("group", openid);
        GroupEntry group = currentEntries().groups().get(number);
        return group != null && group.openid().equals(openid) ? OptionalLong.of(number) : OptionalLong.empty();
    }

    public synchronized Optional<String> memberOpenId(long number) {
        return Optional.ofNullable(currentEntries().members().get(number)).map(MemberEntry::openid);
    }

    public synchronized OptionalLong memberNumber(String openid) {
        long number = number("member", openid);
        MemberEntry member = currentEntries().members().get(number);
        return member != null && member.openid().equals(openid) ? OptionalLong.of(number) : OptionalLong.empty();
    }

    public synchronized void renameGroup(long number, String name) {
        String title = Objects.requireNonNull(name).strip();
        if (title.codePointCount(0, title.length()) > 40) {
            throw new IllegalArgumentException("群名最多 40 字");
        }
        if (!title.isEmpty() && settings.snapshot().configured() && settings.useSecret(title::contains)) {
            throw new IllegalArgumentException("群名不能包含 AppSecret");
        }
        updateGroup(configuredApp(), number, group -> {
            if (!title.isEmpty()) {
                String apiName = group.nameSource() == NameSource.GROUP_INFO_API ? group.name() : group.apiName();
                return group.withApiName(apiName).withName(title).withNameSource(NameSource.MANUAL);
            }
            if (group.nameSource() != NameSource.MANUAL) return group;
            return group.apiName() == null
                    ? group.withName(defaultName(group.joinedAt())).withNameSource(NameSource.DEFAULT)
                    : group.withName(group.apiName()).withNameSource(NameSource.GROUP_INFO_API);
        });
    }

    synchronized List<GroupInfo> consoleGroups() {
        BotEntries bot = currentEntries();
        if (bot.groups().isEmpty()) return List.of();
        if (settings.snapshot().configured()) {
            try {
                return settings.useSecret(secret -> groupInfo(bot,
                        text -> text.contains(secret) ? "已隐藏" : maskKnown(text, bot)));
            } catch (IllegalStateException e) {
                return groupInfo(bot, text -> "已隐藏");
            }
        }
        return groupInfo(bot, text -> maskKnown(text, bot));
    }

    private static List<GroupInfo> groupInfo(BotEntries bot, Function<String, String> display) {
        return bot.groups().values().stream()
                .sorted(Comparator.comparing(GroupEntry::removed).thenComparing(GroupEntry::joinedAt)
                        .thenComparingLong(GroupEntry::number))
                .map(group -> new GroupInfo(group.number(), display.apply(group.name()), group.nameSource(),
                        group.memberCount(), group.role(), group.proactive().status(),
                        group.lastFailure() == null ? null : display.apply(group.lastFailure().reason()),
                        group.removed(), group.joinedAt().toString()))
                .toList();
    }

    public synchronized void recordProactive(long number, ProactiveStatus status, SwitchSource source, Instant at) {
        Objects.requireNonNull(status);
        Objects.requireNonNull(source);
        Objects.requireNonNull(at);
        updateGroup(configuredApp(), number, group -> withProactive(group, new Proactive(status, source, at)));
    }

    public synchronized void recordFailure(long number, String reason, Instant at) {
        Objects.requireNonNull(at);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("失败原因不能为空");
        }
        String appId = configuredApp();
        updateGroup(appId, number, group -> withFailure(group, new Failure(maskKnown(reason, entries(appId)), at)));
    }

    synchronized void recordSendResult(String appId, long number, Proactive proactive, Failure failure) {
        updateGroup(appId, number, group -> {
            if (proactive != null) {
                group = withProactive(group, proactive);
            }
            return failure == null ? group : withFailure(group, failure);
        });
    }

    private static GroupEntry withProactive(GroupEntry group, Proactive observed) {
        return group.proactive().at() == null || !observed.at().isBefore(group.proactive().at())
                ? group.withProactive(observed) : group;
    }

    private void updateGroup(String appId, long number, Function<GroupEntry, GroupEntry> update) {
        BotEntries bot = entries(appId);
        GroupEntry previous = bot.groups().get(number);
        if (previous == null) {
            throw new IllegalArgumentException("QQ 官方机器人群尚未登记");
        }
        GroupEntry current = update.apply(previous);
        if (!current.equals(previous)) {
            save(appId, bot.withGroups(replacing(bot.groups(), number, current)));
        }
    }

    @Override
    public String id() {
        return QqBotPluginAutoConfiguration.PLATFORM;
    }

    @Override
    public synchronized List<Snapshot> snapshots(boolean force) {
        requireReadable();
        BotEntries bot = currentEntries();
        Instant now = clock.instant();
        List<Group> groups = bot.groups().values().stream()
                .filter(group -> !group.removed())
                .map(group -> new Group(group.number(), maskKnown(group.name(), bot), group.memberCount(),
                        group.role() == null ? null : group.role() != BotRole.MEMBER))
                .toList();
        return List.of(new Snapshot(id(), groups, List.of(), now, now, false, null));
    }

    @Override
    public boolean supports(String platform) {
        return id().equals(platform);
    }

    @Override
    public synchronized Result lookup(String platform, PushTargetType type, long num, ConfigUiAccount account) {
        if (!supports(platform)) {
            return new Result(false, null, "推送平台不可用");
        }
        if (type == PushTargetType.FRIEND) {
            return new Result(false, null, "QQ 官方机器人暂不支持好友推送");
        }
        if (type != PushTargetType.GROUP) {
            return new Result(false, null, "不支持的推送目标类型");
        }
        BotEntries bot = currentEntries();
        GroupEntry group = bot.groups().get(num);
        if (group == null) {
            return new Result(false, null, "这个群还没对上号，在群里 @机器人 说一句即可");
        }
        if (group.removed()) {
            return new Result(false, null, "机器人已被移出这个群");
        }
        return new Result(true, maskKnown(group.name(), bot), null);
    }

    private void schedule(String appId, GroupEntry group) {
        BotEntries bot = entries(appId);
        for (Endpoint endpoint : Endpoint.values()) {
            FetchKey key = new FetchKey(appId, group.number(), endpoint);
            if (!canFetch(bot, group, endpoint) || pending.containsKey(key)) {
                continue;
            }
            Object ticket = new Object();
            pending.put(key, ticket);
            try {
                executor.execute(() -> fetch(key, ticket));
            } catch (RejectedExecutionException e) {
                pending.remove(key, ticket);
            }
        }
    }

    private boolean canFetch(BotEntries bot, GroupEntry group, Endpoint endpoint) {
        Instant now = clock.instant();
        Instant unavailable = bot.unavailableUntil().get(endpoint);
        Refresh previous = group.refresh(endpoint);
        return (unavailable == null || !now.isBefore(unavailable))
                && (previous == null || (previous.retryAt() != null && !now.isBefore(previous.retryAt())));
    }

    private void fetch(FetchKey key, Object ticket) {
        try {
            GroupEntry before;
            synchronized (this) {
                BotEntries bot = entries(key.appId());
                before = bot.groups().get(key.number());
                if (pending.get(key) != ticket || !key.appId().equals(settings.snapshot().appId())
                        || before == null || before.removed() || !canFetch(bot, before, key.endpoint())) {
                    return;
                }
            }
            JSONObject response = null;
            Failure failure = null;
            boolean unavailable = false;
            try {
                String path = groupPath(before.openid()) + "/" + key.endpoint().path;
                Object value = api.request("GET", path, null, key.appId());
                if (value instanceof JSONObject json) {
                    response = json;
                } else {
                    failure = new Failure("群资料接口回包格式不正确", clock.instant());
                }
            } catch (QqBotApiException e) {
                unavailable = e.code() == 11253 || e.code() == 40012010;
                int code = e.code() != 0 ? e.code() : e.httpStatus();
                String reason = unavailable ? "接口没开放"
                        : code == 0 ? "群资料接口请求失败" : "群资料接口请求失败（错误码 " + code + "）";
                failure = new Failure(reason, clock.instant());
            }
            synchronized (this) {
                if (pending.get(key) == ticket && key.appId().equals(settings.snapshot().appId())) {
                    applyFetch(key, before, response, failure, unavailable);
                }
            }
        } catch (RuntimeException e) {
            log.warn("无法更新 QQ 官方机器人群资料，群号 {}，接口 {}", key.number(), key.endpoint().path);
        } finally {
            synchronized (this) {
                pending.remove(key, ticket);
            }
        }
    }

    private void applyFetch(FetchKey key, GroupEntry before, JSONObject response, Failure failure, boolean unavailable) {
        BotEntries bot = entries(key.appId());
        GroupEntry group = bot.groups().get(key.number());
        Instant now = clock.instant();
        Instant retryAt = null;
        if (failure == null) {
            group = key.endpoint() == Endpoint.INFO ? applyInfo(group, response) : applyBotState(group, before, response, now);
        } else {
            group = withFailure(group, failure);
            retryAt = now.plus(unavailable ? UNAVAILABLE_COOLDOWN : FAILURE_COOLDOWN);
            if (unavailable) {
                bot = bot.withUnavailableUntil(replacing(bot.unavailableUntil(), key.endpoint(), retryAt));
            }
        }
        group = group.withRefresh(key.endpoint(), new Refresh(now, retryAt));
        save(key.appId(), bot.withGroups(replacing(bot.groups(), key.number(), group)));
    }

    private static GroupEntry applyInfo(GroupEntry group, JSONObject response) {
        if (response.get("group_name") instanceof String name && !name.isBlank()) {
            String title = mask(name.strip(), text(response, "union_group_openid"));
            group = group.withApiName(title);
            if (group.nameSource() != NameSource.MANUAL) {
                group = group.withName(title).withNameSource(NameSource.GROUP_INFO_API);
            }
        }
        if (response.get("group_member_num") instanceof Integer count && count >= 0) {
            group = group.withMemberCount(count);
        }
        return group;
    }

    private static GroupEntry applyBotState(GroupEntry group, GroupEntry before, JSONObject response, Instant now) {
        BotRole role = switch (text(response, "member_role")) {
            case "admin" -> BotRole.ADMIN;
            case "owner" -> BotRole.OWNER;
            case "member" -> BotRole.MEMBER;
            default -> null;
        };
        if (role != null) {
            group = group.withRole(role);
        }
        if (group.proactive() == before.proactive() && response.get("allow_proactive_msg") instanceof Boolean allowed) {
            var status = allowed ? ProactiveStatus.ENABLED : ProactiveStatus.DISABLED;
            group = group.withProactive(new Proactive(status, SwitchSource.GROUP_STATE_API, now));
        }
        return group;
    }

    private String configuredApp() {
        requireReadable();
        String appId = settings.snapshot().appId();
        if (appId.isEmpty()) {
            throw new IllegalStateException("尚未配置 QQ 官方机器人 AppID");
        }
        return appId;
    }

    private BotEntries currentEntries() {
        requireReadable();
        return entries(settings.snapshot().appId());
    }

    private BotEntries entries(String appId) {
        return bots.getOrDefault(appId, EMPTY);
    }

    private void requireReadable() {
        if (bots == null) {
            throw new IllegalStateException("QQ 官方机器人对照表无法读取");
        }
    }

    private Map<String, BotEntries> load() {
        try {
            Map<String, BotEntries> loaded = file.load();
            Map<Long, String> groups = new HashMap<>();
            Map<Long, String> members = new HashMap<>();
            for (BotEntries bot : loaded.values()) {
                bot.groups().forEach((num, group) -> checkNumber(groups, num, group.number(), "group", group.openid()));
                bot.members().forEach((num, member) -> checkNumber(members, num, member.number(), "member", member.openid()));
                Objects.requireNonNull(bot.unavailableUntil());
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            log.warn("无法读取 QQ 官方机器人对照表");
            return null;
        }
    }

    private void save(String appId, BotEntries updated) {
        Map<String, BotEntries> next = replacing(bots, appId, updated);
        try {
            file.save(next);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("QQ 官方机器人对照表无法保存");
        }
        bots.put(appId, updated);
    }

    private void checkNumber(Map<Long, String> known, long key, long stored, String kind, String openid) {
        String existing = known.putIfAbsent(key, openid);
        if (key != stored || stored != number(kind, openid) || (existing != null && !existing.equals(openid))) {
            throw new IllegalStateException("QQ 官方机器人对照表编号不一致");
        }
    }

    private long number(String kind, String openid) {
        if (openid == null || openid.isBlank()) {
            throw new IllegalArgumentException("QQ 官方机器人标识不能为空");
        }
        byte[] digest = hash.apply((kind + ":" + openid).getBytes(StandardCharsets.UTF_8));
        return NUMBER_BASE + new BigInteger(1, digest).mod(NUMBER_RANGE).longValueExact();
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算 QQ 官方机器人编号", e);
        }
    }

    private static void checkCollision(String kind, long number, String openid, String existing) {
        if (existing != null && !existing.equals(openid)) {
            log.error("QQ 官方机器人编号冲突，种类 {}，编号 {}", kind, number);
            throw new IllegalStateException("QQ 官方机器人编号冲突");
        }
    }

    private static String defaultName(Instant at) {
        return "未命名群（" + JOIN_TIME.format(at) + " 加入）";
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static GroupEntry withFailure(GroupEntry group, Failure failure) {
        return group.lastFailure() == null || !failure.at().isBefore(group.lastFailure().at())
                ? group.withLastFailure(failure) : group;
    }

    private static String text(JSONObject response, String key) {
        return response.get(key) instanceof String s ? s : "";
    }

    static String groupPath(String openid) {
        String segment = UriUtils.encodePathSegment(openid, StandardCharsets.UTF_8);
        if (segment.equals(".") || segment.equals("..")) {
            segment = segment.replace(".", "%2E");
        }
        return "/v2/groups/" + segment;
    }

    synchronized String maskKnown(String appId, String value) {
        return maskKnown(value, entries(appId));
    }

    private static String maskKnown(String value, BotEntries bot) {
        for (GroupEntry group : bot.groups().values()) {
            value = mask(value, group.openid());
        }
        for (MemberEntry member : bot.members().values()) {
            value = mask(value, member.openid());
        }
        return value;
    }

    private static String mask(String value, String openid) {
        if (openid.isEmpty() || !value.contains(openid)) {
            return value;
        }
        return value.replace(openid, maskOpenId(openid));
    }

    static String maskOpenId(String openid) {
        return openid.length() <= 8 ? "…" : openid.substring(0, 4) + "…" + openid.substring(openid.length() - 4);
    }

    private static <K, V> Map<K, V> replacing(Map<K, V> map, K key, V value) {
        Map<K, V> copy = new LinkedHashMap<>(map);
        copy.put(key, value);
        return copy;
    }

    @With
    record BotEntries(Map<Long, GroupEntry> groups, Map<Long, MemberEntry> members,
                      Map<Endpoint, Instant> unavailableUntil) {}

    @With
    public record GroupEntry(long number, String openid, String name, NameSource nameSource,
                             Instant joinedAt, Instant lastSeenAt, boolean removed, Proactive proactive,
                             BotRole role, Integer memberCount, Refresh info, Refresh botState, Failure lastFailure,
                             String apiName) {
        public GroupEntry {
            Objects.requireNonNull(openid);
            Objects.requireNonNull(name);
            Objects.requireNonNull(nameSource);
            Objects.requireNonNull(joinedAt);
            Objects.requireNonNull(lastSeenAt);
            Objects.requireNonNull(proactive);
        }

        Refresh refresh(Endpoint endpoint) {
            return endpoint == Endpoint.INFO ? info : botState;
        }

        GroupEntry withRefresh(Endpoint endpoint, Refresh result) {
            return endpoint == Endpoint.INFO ? withInfo(result) : withBotState(result);
        }

        @Override
        public String toString() {
            return "QQ 官方机器人群 " + number;
        }
    }

    record GroupInfo(long num, String name, NameSource nameSource, Integer memberCount, BotRole role,
                     ProactiveStatus proactive, String lastFailure, boolean removed, String joinedAt) {}

    record MemberEntry(long number, String openid, Instant firstSeenAt, Instant lastSeenAt) {
        MemberEntry {
            Objects.requireNonNull(openid);
            Objects.requireNonNull(firstSeenAt);
            Objects.requireNonNull(lastSeenAt);
        }

        @Override
        public String toString() {
            return "QQ 官方机器人群成员 " + number;
        }
    }

    public record Proactive(ProactiveStatus status, SwitchSource source, Instant at) {}
    public record Failure(String reason, Instant at) {}
    public record Refresh(Instant attemptedAt, Instant retryAt) {}
    public enum NameSource { DEFAULT, GROUP_INFO_API, MANUAL }
    public enum ProactiveStatus { UNKNOWN, ENABLED, DISABLED }
    public enum SwitchSource {
        GROUP_STATE_API, EVENT, MANUAL,
        @JsonProperty("发送成功") SEND_SUCCESS,
        @JsonProperty("发送报错") SEND_ERROR
    }
    public enum BotRole { ADMIN, OWNER, MEMBER }
    private enum Membership { JOIN, REMOVE, SEEN }

    enum Endpoint {
        INFO("info"),
        BOT_STATE("bot_state");

        private final String path;

        Endpoint(String path) {
            this.path = path;
        }
    }

    private record FetchKey(String appId, long number, Endpoint endpoint) {}
}
