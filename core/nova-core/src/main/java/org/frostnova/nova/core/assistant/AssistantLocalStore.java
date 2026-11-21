package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.frostnova.nova.core.enums.PushTargetType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.LongFunction;

/** 本机观众代号密钥及只含发送元数据的记录，与模型密钥使用同一目录。 */
public final class AssistantLocalStore {
    private final Path aliasKey;
    private final Path transmissions;
    private final Clock clock;
    // 仅在本机内存保留近期昵称，追问无需再查数即可还原；不写入发送记录。
    private final java.util.Map<String, String> nicknames = new java.util.LinkedHashMap<>(64, 0.75f, true);

    public AssistantLocalStore(Path directory, Clock clock) {
        aliasKey = directory.resolve("assistant-alias.key");
        transmissions = directory.resolve("assistant-transmissions.jsonl");
        this.clock = clock;
    }

    public synchronized String alias(String platform, Long uid) {
        return encodeAlias(aliasMac(), platform + ":" + uid);
    }

    public record QqSource(String entry, String account, String sessionType, String sessionNum, String sessionKey) { }

    public QqSource qqSource(String entry, String platform, PushTargetType type, long num, long asker) {
        Mac mac = aliasMac();
        String account = encodeAlias(mac, "问者-", "qq-asker:" + asker, 12);
        boolean group = type == PushTargetType.GROUP;
        String sessionType = group ? "group" : "friend";
        // 好友会话号就是对方的账号，也用代号记录。
        String sessionNum = group ? Long.toString(num) : encodeAlias(mac, "好友-", "qq-friend:" + num, 12);
        return new QqSource(entry, account, sessionType, sessionNum, platform + ":" + sessionType + ":" + sessionNum);
    }

    /** 为单次查询复用同一密钥；返回的编码器仅供该查询线程使用。 */
    public LongFunction<String> aliases(String platform) {
        Mac mac = aliasMac();
        String prefix = platform + ":";
        return uid -> encodeAlias(mac, prefix + uid);
    }

    private static String encodeAlias(Mac mac, String identity) {
        return encodeAlias(mac, "观众-", identity, 3);
    }

    private static String encodeAlias(Mac mac, String prefix, String identity, int bytes) {
        return prefix + HexFormat.of().formatHex(mac.doFinal(identity.getBytes(StandardCharsets.UTF_8)), 0, bytes);
    }

    private synchronized Mac aliasMac() {
        try {
            if (!Files.exists(aliasKey)) {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                AssistantFiles.write(aliasKey, Base64.getEncoder().encodeToString(bytes));
            }
            AssistantFiles.restrict(aliasKey);
            byte[] secret = Base64.getDecoder().decode(Files.readString(aliasKey).strip());
            if (secret.length != 32) throw new IllegalStateException();
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return mac;
            } finally { java.util.Arrays.fill(secret, (byte) 0); }
        } catch (Exception error) { throw new AssistantException("智能助手观众代号密钥无法读写，请检查后再试"); }
    }

    public synchronized void rememberNickname(String alias, String nickname) {
        nicknames.put(alias, nickname);
        if (nicknames.size() > 4096) nicknames.remove(nicknames.keySet().iterator().next());
    }

    public synchronized java.util.Map<String, String> nicknames() { return java.util.Map.copyOf(nicknames); }

    /** 记录发送元数据与固定错误字段。 */
    public synchronized void record(String account, String platform, Long uid, java.util.List<String> tools,
                                    java.util.List<String> dataTypes, java.math.BigDecimal cost, String endpoint,
                                    AssistantException failure) {
        record(account, platform, uid, tools, dataTypes, cost, endpoint, failure, null);
    }

    public synchronized void record(String account, String platform, Long uid, java.util.List<String> tools,
                                    java.util.List<String> dataTypes, java.math.BigDecimal cost, String endpoint,
                                    AssistantException failure, QqSource qq) {
        try {
            Instant now = clock.instant();
            Instant cutoff = now.atZone(ZoneOffset.UTC).minusYears(3).toInstant();
            StringBuilder retained = new StringBuilder();
            if (Files.exists(transmissions)) {
                AssistantFiles.restrict(transmissions);
                try (var lines = Files.lines(transmissions)) {
                    lines.forEach(line -> {
                        JSONObject entry = JSON.parseObject(line);
                        if (!Instant.parse(entry.getString("at")).isBefore(cutoff)) retained.append(line).append('\n');
                    });
                }
            }
            JSONObject entry = JSONObject.of("at", now.toString(), "account", account, "platform", platform, "uid", uid,
                    "tools", tools, "dataTypes", dataTypes, "costYuan", cost, "endpoint", endpoint);
            if (qq != null) {
                entry.put("entry", qq.entry());
                entry.put("sessionType", qq.sessionType());
                entry.put("sessionNum", qq.sessionNum());
            }
            if (failure != null) {
                entry.put("reason", failure.reason());
                var error = failure.providerError();
                if (error != null) entry.put("providerError", JSONObject.of("type", error.type(), "code", error.code(), "param", error.param()));
            }
            retained.append(entry.toJSONString(JSONWriter.Feature.WriteNulls)).append('\n');
            AssistantFiles.write(transmissions, retained.toString());
        } catch (Exception error) { throw new AssistantException("智能助手发送记录无法读写，请检查后再试"); }
    }
}
