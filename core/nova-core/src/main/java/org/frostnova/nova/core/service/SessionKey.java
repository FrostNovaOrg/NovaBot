package org.frostnova.nova.core.service;

import org.frostnova.nova.core.enums.PushTargetType;

/**
 * 会话设置在状态存储里的键：「平台:会话类型码:会话号」
 * <p>
 * 金额可见、命令开关、静音时段三处共用这一把键。群号与好友账号取自两个互不相干的号段，
 * 同一平台下撞号时只按号码记，一边的设置就会套到另一边头上。
 * <p>
 * 旧版的键是「平台:会话号」，不带类型。升级时由 {@link SessionSettingsTypeMigration} 抄成新键，
 * 旧键原样留着供回退到旧版时读；这里的 {@link #parse} 不认旧键，列表里也就不会出现它们。
 */
public final class SessionKey {
    private SessionKey() {
    }

    /**
     * 拼出键
     * @param platform 推送平台
     * @param type 会话类型
     * @param num 会话号
     * @return 键；类型不是群或好友时为 null，调用方按「没有这条设置」处理
     */
    public static String of(String platform, PushTargetType type, Long num) {
        if (platform == null || num == null || !known(type)) {
            return null;
        }
        return platform + ":" + type.getCode() + ":" + num;
    }

    /**
     * 拼出键，类型不是群或好友时抛出
     * <p>
     * 写入走这一口：类型认不出时宁可报错，也不猜一边写进去。
     */
    public static String require(String platform, PushTargetType type, Long num) {
        String key = of(platform, type, num);
        if (key == null) {
            throw new IllegalArgumentException("会话类型只能是群或好友，收到的是 " + type);
        }
        return key;
    }

    /**
     * 类型是不是群或好友
     */
    public static boolean known(PushTargetType type) {
        return type == PushTargetType.FRIEND || type == PushTargetType.GROUP;
    }

    /**
     * 还原新键
     * <p>
     * 平台名可能含连字符但不含冒号，类型码与会话号必为数字，因此从右侧切两刀即可还原。
     * @param key 键
     * @return 还原出的会话，旧键、标记键与认不出的键为 null
     */
    public static Parsed parse(String key) {
        if (key == null) {
            return null;
        }
        int numAt = key.lastIndexOf(':');
        if (numAt <= 0) {
            return null;
        }
        int typeAt = key.lastIndexOf(':', numAt - 1);
        if (typeAt <= 0) {
            return null;
        }
        try {
            PushTargetType type = PushTargetType.of(Integer.parseInt(key.substring(typeAt + 1, numAt)));
            long num = Long.parseLong(key.substring(numAt + 1));
            if (!known(type)) {
                return null;
            }
            return new Parsed(key.substring(0, typeAt), type, num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 还原旧键「平台:会话号」
     * @param key 键
     * @return 还原出的平台与号，不是旧键形状时为 null
     */
    public static Parsed parseLegacy(String key) {
        if (key == null) {
            return null;
        }
        int numAt = key.lastIndexOf(':');
        if (numAt <= 0 || key.lastIndexOf(':', numAt - 1) >= 0) {
            return null;
        }
        try {
            return new Parsed(key.substring(0, numAt), null, Long.parseLong(key.substring(numAt + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 键里还原出的会话
     *
     * @param platform 推送平台
     * @param type 会话类型，旧键里没有时为 null
     * @param num 会话号
     */
    public record Parsed(String platform, PushTargetType type, long num) {
    }
}
