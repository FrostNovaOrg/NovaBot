package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 一位主播的自动弹幕设置。未列入 features 的功能默认关闭。 */
public record RoomSettings(boolean enabled, int perMinute, Map<String, Boolean> features, List<Plan> plans, ThanksSettings thanks) {
    public static final String TIMED = "timed";
    public static final RoomSettings DEFAULT = new RoomSettings(false, 6, Map.of(), List.of());

    public RoomSettings(boolean enabled, int perMinute, Map<String, Boolean> features, List<Plan> plans) {
        this(enabled, perMinute, features, plans, ThanksSettings.DEFAULT);
    }

    public RoomSettings {
        if (perMinute < 1 || perMinute > 20) throw new IllegalArgumentException("每分钟上限应为 1 至 20 条");
        features = Map.copyOf(features);
        plans = List.copyOf(plans);
        if (plans.size() > 10) throw new IllegalArgumentException("每位主播最多 10 条计划");
        var ids = new HashSet<String>();
        for (Plan plan : plans) {
            if (!ids.add(plan.id())) throw new IllegalArgumentException("计划标识重复");
        }
    }

    public boolean allows(String feature) {
        return enabled && Boolean.TRUE.equals(features.get(feature));
    }

    public record Plan(String id, boolean enabled, int minutes, List<String> texts, boolean random, boolean skipQuiet) {
        public Plan {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("计划标识无效");
            if (minutes < 2) throw new IllegalArgumentException("计划间隔至少 2 分钟");
            if (texts.isEmpty()) throw new IllegalArgumentException("计划至少需要一条文字");
            texts = texts.stream().map(String::strip).toList();
            if (texts.stream().anyMatch(s -> s.isEmpty() || s.contains("\n") || s.contains("\r"))) {
                throw new IllegalArgumentException("每条文字要单独一行，不能为空");
            }
        }
    }

    public static RoomSettings parse(JSONObject json) {
        if (json == null) throw new IllegalArgumentException("缺少主播设置");
        Map<String, Boolean> switches = new HashMap<>();
        if (json.containsKey("features")) {
            if (!(json.get("features") instanceof Map)) throw new IllegalArgumentException("功能开关格式不正确");
            JSONObject features = json.getJSONObject("features");
            for (String key : features.keySet()) switches.put(key, flag(features, key, false));
        }
        List<Plan> plans = new ArrayList<>();
        if (json.containsKey("plans")) {
            if (!(json.get("plans") instanceof List)) throw new IllegalArgumentException("计划列表格式不正确");
            JSONArray rows = json.getJSONArray("plans");
            for (int i = 0; i < rows.size(); i++) {
                if (!(rows.get(i) instanceof Map)) throw new IllegalArgumentException("计划格式不正确");
                JSONObject row = rows.getJSONObject(i);
                if (!(row.get("texts") instanceof List<?> lines) || !lines.stream().allMatch(String.class::isInstance)) {
                    throw new IllegalArgumentException("请填写计划文字");
                }
                plans.add(new Plan(row.getString("id"), flag(row, "enabled", false), number(row, "minutes", 10),
                        lines.stream().map(String.class::cast).toList(), flag(row, "random", false), flag(row, "skipQuiet", true)));
            }
        }
        return new RoomSettings(flag(json, "enabled", false), number(json, "perMinute", 6), switches, plans, ThanksSettings.parse(json));
    }

    static boolean flag(JSONObject json, String key, boolean fallback) {
        if (!json.containsKey(key)) return fallback;
        if (json.get(key) instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("开关必须为开或关");
    }

    static int number(JSONObject json, String key, int fallback) {
        if (!json.containsKey(key)) return fallback;
        if (json.get(key) instanceof Integer value) return value;
        throw new IllegalArgumentException("间隔和上限必须是整数");
    }
}
