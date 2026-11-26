package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public record ThanksSettings(int giftWindowSeconds, boolean giftIncludeFree, BigDecimal giftMinYuan,
                            int followWindowSeconds, int shareWindowSeconds, Map<String, String> templates) {
    static final ThanksSettings DEFAULT = new ThanksSettings(8, false, BigDecimal.ZERO, 30, 30, Map.of());

    public ThanksSettings {
        if (giftWindowSeconds < 3 || giftWindowSeconds > 60) throw new IllegalArgumentException("礼物合并窗口应为 3 至 60 秒");
        if (followWindowSeconds < 10 || followWindowSeconds > 120) throw new IllegalArgumentException("关注合并窗口应为 10 至 120 秒");
        if (shareWindowSeconds < 10 || shareWindowSeconds > 120) throw new IllegalArgumentException("分享合并窗口应为 10 至 120 秒");
        if (giftMinYuan.signum() < 0) throw new IllegalArgumentException("礼物最低金额不能小于 0 元");
        var overrides = new HashMap<String, String>();
        for (ThanksKind kind : ThanksKind.values()) {
            String template = kind.template.override(templates.get(kind.key));
            if (!template.isEmpty()) overrides.put(kind.key, template);
        }
        templates = Map.copyOf(overrides);
    }

    static ThanksSettings parse(JSONObject room) {
        if (!room.containsKey("thanks")) return DEFAULT;
        if (room.get("thanks") instanceof ThanksSettings saved) return saved;
        if (!(room.get("thanks") instanceof Map)) throw new IllegalArgumentException("答谢设置格式不正确");
        JSONObject options = room.getJSONObject("thanks");
        var templates = new HashMap<String, String>();
        if (options.containsKey("templates")) {
            if (!(options.get("templates") instanceof Map<?, ?> values)) throw new IllegalArgumentException("答谢话术格式不正确");
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                    throw new IllegalArgumentException("答谢话术必须是文字");
                }
                templates.put(key, value);
            }
        }
        if (options.containsKey("giftMinYuan") && !(options.get("giftMinYuan") instanceof Number)) {
            throw new IllegalArgumentException("礼物最低金额必须是数字");
        }
        return new ThanksSettings(RoomSettings.number(options, "giftWindowSeconds", DEFAULT.giftWindowSeconds),
                RoomSettings.flag(options, "giftIncludeFree", DEFAULT.giftIncludeFree),
                options.containsKey("giftMinYuan") ? options.getBigDecimal("giftMinYuan") : DEFAULT.giftMinYuan,
                RoomSettings.number(options, "followWindowSeconds", DEFAULT.followWindowSeconds),
                RoomSettings.number(options, "shareWindowSeconds", DEFAULT.shareWindowSeconds), templates);
    }
}
