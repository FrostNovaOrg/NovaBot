package org.frostnova.nova.interact.danmaku;

import java.util.Locale;

/**
 * 发弹幕用哪个账号
 * <p>
 * 整个实例一个选项，存在配置项 {@code novabot.bilibili.interact.danmaku.account} 里，取值就是 {@link #value()}。
 */
public enum DanmakuAccountChoice {
    /**
     * 单独的发言账号（默认）：在连接页「哔哩哔哩发言账号」卡上另登录的那个号
     */
    SPEAKER("speaker", "单独的发言账号", "发言账号"),

    /**
     * 和监听直播间用的同一个账号：请求经监听账号那一个接口工具发出，共用它的风控冷却
     */
    LISTENER("listener", "和监听直播间用的同一个账号", "监听账号");

    private final String value;

    private final String label;

    private final String shortName;

    DanmakuAccountChoice(String value, String label, String shortName) {
        this.value = value;
        this.label = label;
        this.shortName = shortName;
    }

    /**
     * 配置文件里的写法
     * @return 写法
     */
    public String value() {
        return value;
    }

    /**
     * 界面上的选项名
     * @return 选项名
     */
    public String label() {
        return label;
    }

    /**
     * 一句话里称呼它的短名
     * @return 短名
     */
    public String shortName() {
        return shortName;
    }

    /**
     * 按配置文件里的写法解析，认不出时为 null
     * @param value 写法，不区分大小写、两头空白不计
     * @return 选项，认不出时为 null
     */
    public static DanmakuAccountChoice parse(String value) {
        if (value == null) {
            return null;
        }
        String wanted = value.strip().toLowerCase(Locale.ROOT);
        for (DanmakuAccountChoice choice : values()) {
            if (choice.value.equals(wanted)) {
                return choice;
            }
        }
        return null;
    }

    /**
     * 按写法解析，认不出时回到默认的单独发言账号
     * <p>
     * 认不出就退回发言账号，不退回监听账号：选错了的代价只该落在发言账号上。
     * @param value 写法
     * @return 选项
     */
    public static DanmakuAccountChoice of(String value) {
        DanmakuAccountChoice choice = parse(value);
        return choice == null ? SPEAKER : choice;
    }
}
