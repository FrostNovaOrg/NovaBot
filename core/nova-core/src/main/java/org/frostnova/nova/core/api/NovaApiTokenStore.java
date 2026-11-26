package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.lang.SecureToken;
import org.frostnova.nova.core.service.NovaStateStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 访问令牌的签发、吊销与校验
 *
 * <h2>存在哪</h2>
 * 运行状态文件 {@code state.json}（与直播数据同目录）里的 {@value #NAMESPACE} 一段，
 * 与控制台的账号表同一个文件。一把令牌一条，键是编号。
 *
 * <h2>只存摘要</h2>
 * 令牌是 32 字节随机数，落盘的只有它的 SHA-256 摘要。随机串没有字典可查，
 * 摘要足以防住「文件被人拿走、令牌被直接读出来」；也因此明文<b>只在签发那一刻存在</b>，
 * 之后谁也取不回来。校验时把出示的令牌算一次摘要，与表里每一条都用常量时间比一遍，
 * 不在第一条对上时提前返回。
 *
 * <h2>摘要也不出这个类</h2>
 * 控制台列表、吊销、操作记录、日志一律只用另起的编号。摘要露出去不能直接拿来调接口，
 * 但它等于给离线比对开了一个入口，没有任何一处需要它。
 *
 * <h2>最后使用时刻</h2>
 * 每次通过校验都记，但只改内存、不立即写盘，由运行状态的定时保存带下去；
 * 距上次记下不到一分钟的不再改，免得每个请求都动一次运行状态。
 */
@Slf4j
public class NovaApiTokenStore {
    /** 运行状态里的命名空间 */
    public static final String NAMESPACE = "api-tokens";

    /** 令牌明文的开头，便于在配置、脚本里一眼认出它是什么 */
    public static final String PREFIX = "nvb_";

    /** 每个账号最多同时持有的有效令牌数（未吊销且未过期） */
    public static final int MAX_ACTIVE_PER_ACCOUNT = 20;

    /** 每个账号保留的已吊销、已过期记录数，再多就从最早的删起 */
    static final int MAX_INACTIVE_PER_ACCOUNT = 50;

    /** 令牌名字的最大长度 */
    public static final int MAX_NAME_LENGTH = 40;

    /** 最后使用时刻多久写一次 */
    private static final Duration USE_GRANULARITY = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NovaStateStore state;

    private final Clock clock;

    /** 签发的结果：记录本身与只此一次的明文 */
    public record Issued(NovaApiToken token, String plaintext) {
    }

    /** 签发被拒，message 是给人看的说明 */
    public static class LimitReached extends RuntimeException {
        public LimitReached(String message) {
            super(message, null, false, false);
        }
    }

    public NovaApiTokenStore(NovaStateStore state) {
        this(state, Clock.systemUTC());
    }

    public NovaApiTokenStore(NovaStateStore state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    Instant now() {
        return clock.instant();
    }

    /**
     * 给一个账号签发一把令牌
     * @param accountId 所属账号
     * @param name      名字，去掉首尾空白后不能为空，最长 {@value #MAX_NAME_LENGTH} 个字
     * @param scopes    勾选的范围，调用方先核对过都是已登记的
     * @param validity  有效期，null 为不过期
     * @return 记录与明文，明文只此一次
     * @throws LimitReached 这个账号的有效令牌已满
     * @throws IllegalArgumentException 名字不合规
     * @throws IllegalStateException 没能写进磁盘
     */
    public synchronized Issued issue(String accountId, String name, List<String> scopes, Duration validity) {
        String label = name == null ? "" : name.strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("请给令牌起个名字，日后好认出是哪一把");
        }
        if (label.codePointCount(0, label.length()) > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("令牌名字最长 " + MAX_NAME_LENGTH + " 个字");
        }
        Instant now = now();
        List<NovaApiToken> all = list();
        long active = all.stream().filter(t -> t.accountId().equals(accountId) && t.active(now)).count();
        if (active >= MAX_ACTIVE_PER_ACCOUNT) {
            throw new LimitReached("每个账号最多 " + MAX_ACTIVE_PER_ACCOUNT
                    + " 把有效的访问令牌，这个账号已经满了。请先吊销不再使用的令牌，再签发新的。");
        }

        String plaintext = PREFIX + SecureToken.generate();
        String id;
        do {
            id = newId();
        } while (find(all, id).isPresent());
        Instant expiresAt = validity == null ? null : now.plus(validity);
        NovaApiToken token = new NovaApiToken(id, accountId, label, scopes, digest(plaintext),
                now, expiresAt, null, null);

        JSONObject data = state.namespace(NAMESPACE);
        data.put(id, json(token));
        prune(data, accountId, now);
        persistOrRollBack(data);
        return new Issued(token, plaintext);
    }

    /**
     * 吊销一把令牌
     * <p>
     * 已经吊销过的原样返回，不算失败：点了两次不该让人以为出了问题。
     * <b>吊销先在内存里生效</b>，下一个请求起就不认；写盘失败时不回滚，由定时保存再写，
     * 并告诉调用方这一次没写进磁盘——反过来回滚的话，一把本人想作废的令牌会因为磁盘一时写不进而继续能用。
     * @param id 编号
     * @return 吊销后的记录与是否写进了磁盘；没有这把令牌时为空
     */
    public synchronized Optional<Revoked> revoke(String id) {
        JSONObject data = state.namespace(NAMESPACE);
        Optional<NovaApiToken> found = find(list(), id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        NovaApiToken token = found.get();
        if (token.revoked()) {
            return Optional.of(new Revoked(token, true, false));
        }
        NovaApiToken updated = token.revokedAt(now());
        data.put(id, json(updated));
        state.write(NAMESPACE, target -> {
            target.clear();
            target.putAll(data);
        });
        boolean saved = state.trySave();
        if (!saved) {
            log.error("访问令牌 {} 已吊销, 但没能写进磁盘, 定时保存时会再试", id);
        }
        return Optional.of(new Revoked(updated, saved, true));
    }

    /**
     * 吊销的结果
     * @param token   吊销后的记录
     * @param saved   是否写进了磁盘
     * @param changed 这一次是否真的改了（已经吊销过的为 false）
     */
    public record Revoked(NovaApiToken token, boolean saved, boolean changed) {
    }

    /** 全部令牌，含已吊销、已过期的，按签发时刻排 */
    public synchronized List<NovaApiToken> list() {
        JSONObject data = state.namespace(NAMESPACE);
        List<NovaApiToken> tokens = new ArrayList<>();
        for (String id : data.keySet()) {
            try {
                tokens.add(read(id, data.getJSONObject(id)));
            } catch (RuntimeException error) {
                // 只说有一条坏了，不说内容：那里面有摘要
                log.warn("访问令牌表里有一条读不懂, 已跳过, 这一条不会被认");
            }
        }
        tokens.sort(Comparator.comparing(NovaApiToken::issuedAt).thenComparing(NovaApiToken::id));
        return List.copyOf(tokens);
    }

    public synchronized Optional<NovaApiToken> find(String id) {
        return find(list(), id);
    }

    /**
     * 按出示的明文找到那把令牌
     * <p>
     * 只管「是哪一把」，不管它此刻还能不能用：吊销、过期、账号状态由调用方判，
     * 那几种拒绝要分别说清是哪一种。
     * @param presented 出示的令牌明文，可为 null
     * @return 对上的那条记录；没对上为空
     */
    public synchronized Optional<NovaApiToken> match(String presented) {
        List<NovaApiToken> all = list();
        byte[] candidate = digest(presented == null ? "" : presented).getBytes(StandardCharsets.US_ASCII);
        NovaApiToken matched = null;
        for (NovaApiToken token : all) {
            // 不在第一条对上时提前返回：比较次数与对没对上无关
            if (MessageDigest.isEqual(token.digest().getBytes(StandardCharsets.US_ASCII), candidate) && matched == null) {
                matched = token;
            }
        }
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matched);
    }

    /**
     * 记下一次使用
     * <p>
     * 只改内存，由运行状态的定时保存带下去；距上次记下不到一分钟的不改。
     * @param token 刚通过校验的那把
     */
    public synchronized void used(NovaApiToken token) {
        Instant now = now().truncatedTo(ChronoUnit.SECONDS);
        if (token.lastUsedAt() != null && Duration.between(token.lastUsedAt(), now).compareTo(USE_GRANULARITY) < 0) {
            return;
        }
        state.write(NAMESPACE, target -> {
            JSONObject current = target.getJSONObject(token.id());
            if (current != null) {
                current.put("lastUsedAt", now.toString());
            }
        });
    }

    private void prune(JSONObject data, String accountId, Instant now) {
        List<NovaApiToken> inactive = new ArrayList<>();
        for (String id : data.keySet()) {
            try {
                NovaApiToken token = read(id, data.getJSONObject(id));
                if (token.accountId().equals(accountId) && !token.active(now)) {
                    inactive.add(token);
                }
            } catch (RuntimeException ignored) {
                // 坏行由 list() 报，这里不重复
            }
        }
        if (inactive.size() <= MAX_INACTIVE_PER_ACCOUNT) {
            return;
        }
        inactive.sort(Comparator.comparing(NovaApiToken::issuedAt));
        for (int i = 0; i < inactive.size() - MAX_INACTIVE_PER_ACCOUNT; i++) {
            data.remove(inactive.get(i).id());
        }
    }

    private void persistOrRollBack(JSONObject data) {
        JSONObject previous = state.namespace(NAMESPACE);
        state.write(NAMESPACE, target -> {
            target.clear();
            target.putAll(data);
        });
        if (!state.trySave()) {
            state.write(NAMESPACE, target -> {
                target.clear();
                target.putAll(previous);
            });
            throw new IllegalStateException("访问令牌没能写进磁盘，请检查数据目录后重试");
        }
    }

    private static Optional<NovaApiToken> find(List<NovaApiToken> tokens, String id) {
        return tokens.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private static String newId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 令牌明文的 SHA-256 摘要，十六进制小写 */
    static String digest(String plaintext) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("本机不支持 SHA-256", e);
        }
    }

    private static JSONObject json(NovaApiToken token) {
        JSONObject json = new JSONObject();
        json.put("accountId", token.accountId());
        json.put("name", token.name());
        json.put("scopes", new JSONArray(token.scopes()));
        json.put("digest", token.digest());
        json.put("issuedAt", token.issuedAt().toString());
        json.put("expiresAt", token.expiresAt() == null ? null : token.expiresAt().toString());
        json.put("lastUsedAt", token.lastUsedAt() == null ? null : token.lastUsedAt().toString());
        json.put("revokedAt", token.revokedAt() == null ? null : token.revokedAt().toString());
        return json;
    }

    private static NovaApiToken read(String id, JSONObject json) {
        String digest = json.getString("digest");
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("摘要缺失或不合规");
        }
        String accountId = json.getString("accountId");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("没有所属账号");
        }
        List<String> scopes = json.getList("scopes", String.class);
        return new NovaApiToken(id, accountId, json.getString("name"), scopes == null ? List.of() : scopes, digest,
                Instant.parse(json.getString("issuedAt")), instant(json, "expiresAt"), instant(json, "lastUsedAt"),
                instant(json, "revokedAt"));
    }

    private static Instant instant(JSONObject json, String key) {
        String value = json.getString(key);
        return value == null ? null : Instant.parse(value);
    }
}
