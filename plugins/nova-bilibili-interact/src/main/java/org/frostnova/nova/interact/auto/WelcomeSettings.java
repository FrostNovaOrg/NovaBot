package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.enums.RoomGuardLevel;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record WelcomeSettings(RoomGuardLevel minimumLevel, String guardTemplate, String listedTemplate, List<Person> people) {
    static final String GUARD = "guardWelcome";
    static final String LISTED = "listedWelcome";
    static final DanmakuTemplate GUARD_TEXT = new DanmakuTemplate("欢迎大航海进房", "欢迎{等级} {用户} 进入直播间", "等级");
    static final DanmakuTemplate LISTED_TEXT = new DanmakuTemplate("欢迎指定的人进房", "欢迎 {用户} 进入直播间");
    static final WelcomeSettings DEFAULT = new WelcomeSettings(RoomGuardLevel.CAPTAIN, "", "", List.of());

    public record Person(String uid, String alias, String template) {}

    public static Map<String, String> defaultTemplates() {
        return Map.of(GUARD, GUARD_TEXT.defaultText(), LISTED, LISTED_TEXT.defaultText());
    }

    static WelcomeSettings parse(JSONObject room) {
        if (!room.containsKey("welcome")) return DEFAULT;
        if (room.get("welcome") instanceof WelcomeSettings saved) return saved;
        if (!(room.get("welcome") instanceof Map)) throw new IllegalArgumentException("欢迎设置格式不正确");
        JSONObject options = room.getJSONObject("welcome");
        RoomGuardLevel minimum = switch (String.valueOf(options.getOrDefault("minimumLevel", "CAPTAIN"))) {
            case "CAPTAIN" -> RoomGuardLevel.CAPTAIN;
            case "COMMANDER" -> RoomGuardLevel.COMMANDER;
            case "GOVERNOR" -> RoomGuardLevel.GOVERNOR;
            default -> throw new IllegalArgumentException("请选择从哪一级起欢迎");
        };
        return new WelcomeSettings(minimum, GUARD_TEXT.override(text(options, "guardTemplate")),
                LISTED_TEXT.override(text(options, "listedTemplate")), parsePeople(options));
    }

    private static List<Person> parsePeople(JSONObject options) {
        if (!options.containsKey("people")) return List.of();
        if (!(options.get("people") instanceof List)) throw new IllegalArgumentException("欢迎名单格式不正确");
        JSONArray rows = options.getJSONArray("people");
        if (rows.size() > 50) throw new IllegalArgumentException("欢迎名单第 51 行超出上限，每位主播最多 50 人");
        var people = new ArrayList<Person>();
        var ids = new HashSet<Long>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                if (!(rows.get(i) instanceof Map)) throw new IllegalArgumentException("请填写 UID");
                JSONObject row = rows.getJSONObject(i);
                Long uid = ConfigUiStreamerId.positiveDecimal(row.get("uid"));
                if (uid == null) throw new IllegalArgumentException("UID 必须是正整数");
                if (!ids.add(uid)) throw new IllegalArgumentException("UID 重复");
                String alias = text(row, "alias");
                if (alias.contains("\n") || alias.contains("\r")) throw new IllegalArgumentException("称呼只能写一行");
                people.add(new Person(uid.toString(), alias, LISTED_TEXT.override(text(row, "template"))));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("欢迎名单第 " + (i + 1) + " 行：" + e.getMessage(), e);
            }
        }
        return List.copyOf(people);
    }

    private static String text(JSONObject json, String key) {
        Object value = json.get(key);
        if (value == null) return "";
        if (value instanceof String string) return string;
        throw new IllegalArgumentException("称呼和话术必须是文字");
    }
}
