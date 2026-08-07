package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件输出协议 v1 的校验器
 * <p>
 * 手工对齐于 VRDash 仓库的 {@code packages/shared/src/protocol.ts}——那份 TypeScript
 * 是协议的唯一真相来源，本类是它在 Java 侧的可执行副本。
 * <p>
 * <b>为什么要有它。</b> 协议里大量字段是「可以为 null」而不是「可以不存在」，
 * 两者在 JSON 里差一个键，在客户端的类型校验里差一个报错，而在我们这边
 * 只差 fastjson2 的一个序列化开关。这种错不会让任何测试自然失败，
 * 只会让下游收到一堆过不了校验的消息——所以必须有一个东西逐条按协议对。
 */
final class NovaProtocolSchema {
    /**
     * 控制类：不带 seq / ts / room / user
     */
    private static final Set<String> CONTROL = Set.of("hello", "resume", "ping", "pong");

    /**
     * 房间级：带 seq / ts / room，不带 user
     */
    private static final Set<String> ROOM = Set.of("live_state", "source_state", "room_stat");

    /**
     * 用户级：全字段
     */
    private static final Set<String> USER = Set.of(
            "danmaku", "superchat", "gift", "guard", "enter", "follow", "share", "like");

    private NovaProtocolSchema() {
    }

    /**
     * 校验一条消息
     * @param envelope 消息
     * @return 违例说明，全部合规时为空列表
     */
    static List<String> violations(JSONObject envelope) {
        Violations violations = new Violations();

        if (envelope.getIntValue("v", -1) != NovaEventMapper.PROTOCOL_VERSION) {
            violations.add("v 必须等于 " + NovaEventMapper.PROTOCOL_VERSION);
        }

        String kind = envelope.getString("kind");
        if (kind == null) {
            violations.add("缺少 kind");
            return violations.list;
        }

        boolean control = CONTROL.contains(kind);
        boolean room = ROOM.contains(kind);
        boolean user = USER.contains(kind);
        if (!control && !room && !user) {
            violations.add("未知的 kind: " + kind);
            return violations.list;
        }

        if (control) {
            for (String absent : List.of("seq", "ts", "room", "user")) {
                if (envelope.containsKey(absent)) {
                    violations.add("控制类消息不该带 " + absent);
                }
            }
        } else {
            violations.requirePositive(envelope, "seq");
            violations.requirePositive(envelope, "ts");
            violations.requireNumber(envelope, "room");

            if (room && envelope.containsKey("user")) {
                violations.add("房间级消息不该带 user");
            }
            if (user) {
                violations.user(envelope.getJSONObject("user"));
            }
        }

        JSONObject data = envelope.getJSONObject("data");
        if (data == null && !"ping".equals(kind) && !"pong".equals(kind)) {
            violations.add(kind + " 缺少 data");
            return violations.list;
        }

        switch (kind) {
            case "hello" -> violations.hello(data);
            case "danmaku" -> violations.danmaku(data);
            case "superchat" -> violations.superChat(data);
            case "gift" -> violations.gift(data);
            case "guard" -> violations.guard(data);
            case "like" -> violations.requireNumber(data, "count");
            case "live_state" -> violations.liveState(data);
            case "source_state" -> violations.sourceState(data);
            case "room_stat" -> violations.roomStat(data);
            case "enter", "follow", "share" -> {
                if (!data.isEmpty()) {
                    violations.add(kind + " 的 data 应为空对象");
                }
            }
            default -> {
            }
        }

        return violations.list;
    }

    /**
     * 违例收集器
     */
    private static class Violations {
        private final List<String> list = new java.util.ArrayList<>();

        private void add(String message) {
            list.add(message);
        }

        /**
         * 字段必须存在且为数字。<b>null 不算数字</b>——协议声明为 number 的字段不可为空
         */
        private void requireNumber(JSONObject json, String key) {
            if (json == null || !json.containsKey(key)) {
                add("缺少 " + key);
                return;
            }
            if (!(json.get(key) instanceof Number)) {
                add(key + " 必须是数字, 实为 " + json.get(key));
            }
        }

        private void requirePositive(JSONObject json, String key) {
            requireNumber(json, key);
            if (json != null && json.get(key) instanceof Number number && number.longValue() <= 0) {
                add(key + " 必须为正数, 实为 " + number);
            }
        }

        private void requireString(JSONObject json, String key) {
            if (json == null || !json.containsKey(key)) {
                add("缺少 " + key);
                return;
            }
            if (!(json.get(key) instanceof String)) {
                add(key + " 必须是字符串, 实为 " + json.get(key));
            }
        }

        private void requireBoolean(JSONObject json, String key) {
            if (json == null || !json.containsKey(key)) {
                add("缺少 " + key);
                return;
            }
            if (!(json.get(key) instanceof Boolean)) {
                add(key + " 必须是布尔值, 实为 " + json.get(key));
            }
        }

        /**
         * 字段必须存在，值可以为 null
         * <p>
         * 「可以为 null」与「可以不存在」是两回事，这个方法专门盯住前者。
         */
        private void requirePresent(JSONObject json, String key) {
            if (json == null || !json.containsKey(key)) {
                add("缺少 " + key + "（协议允许它为 null, 但不允许它不存在）");
            }
        }

        private void user(JSONObject user) {
            if (user == null) {
                add("用户级消息缺少 user");
                return;
            }
            requireString(user, "uid");
            requireString(user, "name");
            requirePresent(user, "face");
            requireBoolean(user, "isAdmin");
            requireBoolean(user, "isAnchor");
            requirePresent(user, "medal");

            String idKind = user.getString("idKind");
            if (!"uid".equals(idKind) && !"openid".equals(idKind)) {
                add("idKind 只能是 uid 或 openid, 实为 " + idKind);
            }

            Integer guardLevel = user.getInteger("guardLevel");
            if (guardLevel == null || guardLevel < 0 || guardLevel > 3) {
                add("guardLevel 只能是 0~3, 实为 " + guardLevel);
            }

            JSONObject medal = user.getJSONObject("medal");
            if (medal != null) {
                requireString(medal, "name");
                requireNumber(medal, "level");
                requireBoolean(medal, "isLighted");
            }
        }

        private void hello(JSONObject data) {
            requireString(data, "sessionId");
            requireString(data, "source");
            requireNumber(data, "lastSeq");
            requireNumber(data, "bufferedFrom");

            JSONArray capabilities = data.getJSONArray("capabilities");
            if (capabilities == null) {
                add("hello 缺少 capabilities");
                return;
            }
            Set<String> known = Set.of("danmaku", "superchat", "gift", "guard",
                    "enter", "follow", "share", "like", "room_stat");
            for (int i = 0; i < capabilities.size(); i++) {
                if (!known.contains(capabilities.getString(i))) {
                    add("未知的 capability: " + capabilities.getString(i));
                }
            }
        }

        private void danmaku(JSONObject data) {
            requireString(data, "text");
            // msgId 可能是空串，但键必须在。空串表示源侧给不出，不是「id 恰好为空」
            requireString(data, "msgId");
            requirePresent(data, "emoji");
            requirePresent(data, "replyTo");

            JSONObject emoji = data.getJSONObject("emoji");
            if (emoji != null) {
                requireString(emoji, "url");
                requireNumber(emoji, "w");
                requireNumber(emoji, "h");
            }
            JSONObject replyTo = data.getJSONObject("replyTo");
            if (replyTo != null) {
                requireString(replyTo, "uid");
                requireString(replyTo, "name");
            }
        }

        private void superChat(JSONObject data) {
            requireString(data, "text");
            requireNumber(data, "rmb");
            requireNumber(data, "durationSec");
            requireNumber(data, "messageId");
            requireNumber(data, "startTs");
            requireNumber(data, "endTs");
        }

        private void gift(JSONObject data) {
            requireNumber(data, "giftId");
            requireString(data, "giftName");
            requireNumber(data, "num");
            requireBoolean(data, "paid");
            requireNumber(data, "rmb");
            requireNumber(data, "faceRmb");
            requireBoolean(data, "bagGift");
            requirePresent(data, "icon");
            requirePresent(data, "combo");
            requirePresent(data, "blindBox");

            JSONObject blindBox = data.getJSONObject("blindBox");
            if (blindBox != null) {
                requireString(blindBox, "boxName");
                requireNumber(blindBox, "boxRmb");
                requireString(blindBox, "wonGiftName");
                requireNumber(blindBox, "wonRmb");
            }
            JSONObject combo = data.getJSONObject("combo");
            if (combo != null) {
                requireString(combo, "id");
                requireNumber(combo, "count");
                requireNumber(combo, "timeoutMs");
            }
        }

        private void guard(JSONObject data) {
            requireString(data, "levelName");
            requireNumber(data, "num");
            requireString(data, "unit");
            requireNumber(data, "rmb");
            // 陪伴天数取不到时为 null，键必须在。一个假的 0 比没有数危险
            requirePresent(data, "companionDays");

            Integer level = data.getInteger("level");
            if (level == null || level < 1 || level > 3) {
                add("guard.level 只能是 1~3, 实为 " + level);
            }
        }

        private void liveState(JSONObject data) {
            requireBoolean(data, "live");
            requireString(data, "title");
            requirePresent(data, "startTs");
        }

        private void sourceState(JSONObject data) {
            String state = data.getString("state");
            if (!Set.of("connected", "reconnecting", "disconnected").contains(state)) {
                add("source_state.state 取值非法: " + state);
            }
        }

        private void roomStat(JSONObject data) {
            if (data.isEmpty()) {
                add("room_stat 三项全空时不该推送");
            }
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (!Set.of("watched", "online", "likeTotal").contains(entry.getKey())) {
                    add("room_stat 出现未知字段 " + entry.getKey());
                    continue;
                }
                // 这三项是可选字段（watched?）而非可空字段，显式的 null 是类型错误
                if (!(entry.getValue() instanceof Number)) {
                    add("room_stat." + entry.getKey() + " 必须是数字, 实为 " + entry.getValue());
                }
            }
        }
    }
}
