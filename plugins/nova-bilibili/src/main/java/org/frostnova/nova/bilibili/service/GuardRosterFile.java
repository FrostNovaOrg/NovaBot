package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.model.GuardMedal;
import org.frostnova.nova.bilibili.model.GuardMember;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一场下播时的大航海名单，写成显式字段的一份 JSON。
 * <p>
 * 颜色存成 {@code #RRGGBBAA}，不把绘图用的颜色对象原样塞进文件：
 * 那种写法换一个库，已经留下的文件就读不回来。
 */
public final class GuardRosterFile {
    public static final String NAME = "guards.json";

    private GuardRosterFile() {
    }

    /**
     * 读回来的一份名单
     * @param at 取得时刻（毫秒）
     * @param total 平台给的总人数
     * @param members 名单
     */
    public record Parsed(long at, int total, List<GuardMember> members) {
    }

    /**
     * 写成一份 JSON
     * @param at 取得时刻（毫秒）
     * @param total 平台给的总人数
     * @param members 名单，空则不该来写
     * @return JSON 文本
     */
    public static String toJson(long at, int total, List<GuardMember> members) {
        JSONObject json = new JSONObject();
        json.put("at", at);
        json.put("total", total);
        JSONArray list = new JSONArray();
        if (members != null) {
            for (GuardMember member : members) {
                JSONObject one = new JSONObject();
                one.put("uid", member.uid());
                one.put("name", member.name() == null ? "" : member.name());
                one.put("level", member.level());
                one.put("score", member.score());
                if (member.medal() != null) {
                    one.put("medal", medalJson(member.medal()));
                }
                list.add(one);
            }
        }
        json.put("members", list);
        return json.toJSONString();
    }

    /**
     * 从文件内容读回名单。认不出时为空，不当成「一个人都没有」
     * @param body 文件内容
     * @return 名单，解析不了时为空
     */
    public static Optional<Parsed> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JSONObject json = JSON.parseObject(body);
            if (json == null) {
                return Optional.empty();
            }
            List<GuardMember> members = new ArrayList<>();
            JSONArray list = json.getJSONArray("members");
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    GuardMember member = member(list.getJSONObject(i));
                    if (member != null) {
                        members.add(member);
                    }
                }
            }
            return Optional.of(new Parsed(json.getLongValue("at"), json.getIntValue("total"), List.copyOf(members)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static JSONObject medalJson(GuardMedal medal) {
        JSONObject json = new JSONObject();
        json.put("name", medal.name() == null ? "" : medal.name());
        json.put("level", medal.level());
        json.put("lit", medal.lit());
        putHex(json, "start", medal.start());
        putHex(json, "end", medal.end());
        putHex(json, "border", medal.border());
        putHex(json, "text", medal.text());
        if (medal.guardIcon() != null && !medal.guardIcon().isBlank()) {
            json.put("guardIcon", medal.guardIcon());
        }
        return json;
    }

    private static void putHex(JSONObject json, String key, Color color) {
        if (color == null) {
            return;
        }
        json.put(key, String.format("#%02X%02X%02X%02X",
                color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha()));
    }

    private static GuardMember member(JSONObject json) {
        if (json == null || json.getLong("uid") == null) {
            return null;
        }
        String name = json.getString("name");
        return new GuardMember(json.getLongValue("uid"), name == null ? "" : name,
                json.getIntValue("level"), json.getLongValue("score"), medal(json.getJSONObject("medal")));
    }

    private static GuardMedal medal(JSONObject json) {
        if (json == null) {
            return null;
        }
        String name = json.getString("name");
        Boolean lit = json.getBoolean("lit");
        String icon = json.getString("guardIcon");
        return new GuardMedal(
                name == null ? "" : name,
                json.getIntValue("level"),
                lit == null || lit,
                color(json.getString("start"), new Color(0x3F, 0xB4, 0xF6)),
                color(json.getString("end"), new Color(0x3F, 0xB4, 0xF6)),
                color(json.getString("border"), new Color(0x5F, 0xC7, 0xF4)),
                color(json.getString("text"), Color.WHITE),
                icon == null || icon.isBlank() ? null : icon);
    }

    private static Color color(String raw, Color fallback) {
        if (raw == null) {
            return fallback;
        }
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() != 6 && hex.length() != 8) {
            return fallback;
        }
        try {
            int red = Integer.parseInt(hex.substring(0, 2), 16);
            int green = Integer.parseInt(hex.substring(2, 4), 16);
            int blue = Integer.parseInt(hex.substring(4, 6), 16);
            int alpha = hex.length() == 8 ? Integer.parseInt(hex.substring(6, 8), 16) : 255;
            return new Color(red, green, blue, alpha);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
