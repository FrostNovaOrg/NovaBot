package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 一条通知「@ 谁」的模式
 * <p>
 * 三值闭集，落在推送消息的 {@code params} 里，键名 {@code at_mode}，取值即 {@link #key()}：
 * <ul>
 *     <li>{@code subscribers} —— 只 @ 订阅了这条提醒的人</li>
 *     <li>{@code all} —— @全体成员</li>
 *     <li>{@code all_or_subscribers} —— @全体成员，被摘掉的那一次改 @ 订阅的人</li>
 * </ul>
 * <b>闭集而不是自由字符串</b>：读的那一端（配置界面的下拉、推送处理器、命令的菜单联动）
 * 各有各的判断，取值一多一少只有真发生过那一种配置的人才看得见。
 *
 * <h2>与旧键 {@code at_all} 的关系</h2>
 * 旧配置里只有一个布尔 {@code at_all}，它说得出「@ 全体」，说不出「@ 不成时怎么办」。
 * 新键<b>不写进默认参数</b>，于是「存在」就等于「使用者在新界面上选过」，
 * 判定顺序因此是没有歧义的一条：<b>选过就按选的来，没选过才回头看旧键</b>。
 * 反过来把新键写进默认参数的话，旧配置里的 {@code at_all: true} 会被一个
 * 谁都没选过的默认值顶掉，表现是升级之后 @全体成员 安静地不再生效。
 */
@Slf4j
public enum AtMode {
    /**
     * @ 订阅的人
     */
    SUBSCRIBERS("subscribers"),

    /**
     * @全体成员
     */
    ALL("all"),

    /**
     * @全体成员，不行就 @ 订阅的人
     */
    ALL_OR_SUBSCRIBERS("all_or_subscribers");

    /**
     * 推送参数中的键名
     */
    public static final String PARAM_KEY = "at_mode";

    /**
     * 旧的布尔键名，只读不写
     */
    public static final String LEGACY_PARAM_KEY = "at_all";

    private final String key;

    AtMode(String key) {
        this.key = key;
    }

    /**
     * 写进配置的取值字面
     * @return 取值
     */
    public String key() {
        return key;
    }

    /**
     * 按取值字面解析，认不出时返回 {@code null}
     * <p>
     * 不抛异常：一个拼错的取值不该让整条推送发不出去。认不出的处置在 {@link #of} 里，
     * 那里会说出来——安静地当成默认值，与「配对了」在结果上分不出。
     * @param key 取值字面，不区分大小写
     * @return 模式，认不出时为 {@code null}
     */
    public static AtMode parse(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        for (AtMode mode : values()) {
            if (mode.key.equalsIgnoreCase(key.trim())) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 从推送参数里取出本条通知的 @ 模式
     * @param params 推送参数，可为 null
     * @return 模式，没配过时为 {@link #SUBSCRIBERS}
     */
    public static AtMode of(JSONObject params) {
        if (params == null) {
            return SUBSCRIBERS;
        }

        String raw = params.getString(PARAM_KEY);
        AtMode mode = parse(raw);
        if (mode != null) {
            return mode;
        }
        if (raw != null && !raw.isBlank()) {
            log.warn("推送参数 {} 的取值 {} 认不出, 已按未配置处理; 可选值: {}, {}, {}",
                    PARAM_KEY, raw, SUBSCRIBERS.key, ALL.key, ALL_OR_SUBSCRIBERS.key);
        }

        return params.getBooleanValue(LEGACY_PARAM_KEY) ? ALL : SUBSCRIBERS;
    }
}
