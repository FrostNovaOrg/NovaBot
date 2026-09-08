package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

/**
 * 会话级金额可见性
 * <p>
 * 主播想看的和能给大群看的不是同一份东西：收益、每位观众送了多少钱，这些是经营与消费信息，
 * 而弹幕数、词云、互动曲线是氛围。此前这两类混在一起，只要出报告就一并露出去。
 * <p>
 * <b>为什么是会话级而不是报告的一个开关</b>：金额的出口不止报告一个。
 * {@code 直播间数据}、{@code 数据排行榜 礼物} 这些聊天命令谁都能在群里打，
 * 它们不属于任何推送目标，读不到报告的版式配置。把「能不能看到金额」定义成会话的属性，
 * 才能同时管住推送与命令——否则关掉报告里的金额之后，一句命令就全漏回来了。
 * <p>
 * <b>默认私聊可见、群聊隐藏。</b>没配过的群按隐藏处理，是因为反过来的默认值一旦错了，
 * 错误的形式是「已经发出去了」，没有补救手段。
 * <p>
 * 会话的身份沿用「平台 + 会话号」，与命令开关、控制台的会话列表一致。
 * 理论上同一平台下群号与账号相同会撞在一起，但整个控制台的会话概念都是这么定义的，
 * 此处再引入第二套键会让同一行既可编辑又编辑不到。
 */
@Service
public class RevenueVisibilityService {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "RevenueVisibility";

    private final StarBotStateStore store;

    @Autowired
    public RevenueVisibilityService(StarBotStateStore store) {
        this.store = store;
    }

    /**
     * 判断指定会话能否看到金额
     * @param platform 推送平台
     * @param type 会话类型，为 null 时按群聊处理
     * @param num 会话号
     * @return 是否可见
     */
    public boolean isVisible(@NonNull String platform, PushTargetType type, @NonNull Long num) {
        Boolean explicit = explicit(platform, num);
        return explicit == null ? defaultFor(type) : explicit;
    }

    /**
     * 读取显式设置
     * @param platform 推送平台
     * @param num 会话号
     * @return 显式设置的值，从未设置过时为 null
     */
    public Boolean explicit(@NonNull String platform, @NonNull Long num) {
        return store.read(NAMESPACE, key(platform, num), data -> data.getBoolean(key(platform, num)))
                .orElse(null);
    }

    /**
     * 设置指定会话的金额可见性
     * @param platform 推送平台
     * @param num 会话号
     * @param visible 是否可见，传 null 表示清除设置、回到按会话类型取的默认值
     */
    public void set(@NonNull String platform, @NonNull Long num, Boolean visible) {
        store.write(NAMESPACE, data -> {
            if (visible == null) {
                data.remove(key(platform, num));
            } else {
                data.put(key(platform, num), visible);
            }
        });
    }

    /**
     * 列出所有显式设置过的会话
     * <p>
     * 只列显式设置：把按默认值处理的会话也列出来，就分不清「配过」与「碰巧默认是这样」，
     * 而这两者在改了默认值之后会走向不同的结果。
     * @return 各会话的设置，按平台与会话号排序
     */
    public List<Setting> all() {
        JSONObject data = store.namespace(NAMESPACE);
        List<Setting> result = new ArrayList<>();

        for (String key : data.keySet()) {
            // 键为「平台:会话号」。平台名可能含连字符（qq-onebot）但不含冒号，
            // 会话号必为数字，因此从右侧切一刀即可还原
            int split = key.lastIndexOf(':');
            if (split <= 0) {
                continue;
            }

            try {
                Boolean visible = data.getBoolean(key);
                if (visible == null) {
                    continue;
                }
                result.add(new Setting(key.substring(0, split), Long.parseLong(key.substring(split + 1)), visible));
            } catch (Exception ignored) {
                // 手工编辑状态文件时可能混入非法键，跳过即可
            }
        }

        result.sort(Comparator.comparing(Setting::platform).thenComparingLong(Setting::num));
        return result;
    }

    /**
     * 未显式设置时的默认值
     */
    public boolean defaultFor(PushTargetType type) {
        return type == PushTargetType.FRIEND;
    }

    private String key(String platform, Long num) {
        return platform + ":" + num;
    }

    /**
     * 某个会话的金额可见性设置
     *
     * @param platform 推送平台
     * @param num 会话号
     * @param visible 是否可见
     */
    public record Setting(String platform, Long num, boolean visible) {
    }
}
