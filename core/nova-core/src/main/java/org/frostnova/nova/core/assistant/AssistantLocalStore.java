package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

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
                return "观众-" + HexFormat.of().formatHex(mac.doFinal((platform + ":" + uid).getBytes(StandardCharsets.UTF_8))).substring(0, 6);
            } finally { java.util.Arrays.fill(secret, (byte) 0); }
        } catch (Exception error) { throw new AssistantException("智能助手观众代号密钥无法读写，请检查后再试"); }
    }

    public synchronized void rememberNickname(String alias, String nickname) {
        nicknames.put(alias, nickname);
        if (nicknames.size() > 4096) nicknames.remove(nicknames.keySet().iterator().next());
    }

    public synchronized java.util.Map<String, String> nicknames() { return java.util.Map.copyOf(nicknames); }

    /** 调用方只传账号、主播、函数名、数据种类与费用，不接收任何问答或弹幕原文。 */
    public synchronized void record(String account, String platform, Long uid, java.util.List<String> tools,
                                    java.util.List<String> dataTypes, java.math.BigDecimal cost) {
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
            retained.append(JSONObject.of("at", now.toString(), "account", account, "platform", platform, "uid", uid,
                    "tools", tools, "dataTypes", dataTypes, "costYuan", cost).toJSONString()).append('\n');
            AssistantFiles.write(transmissions, retained.toString());
        } catch (Exception error) { throw new AssistantException("智能助手发送记录无法读写，请检查后再试"); }
    }
}
