package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 「@我」订阅
 * <p>
 * 记录哪些人希望在某位主播开播（或发动态）时被 @。数据由群成员自己产生，
 * 因此存在运行状态文件而非使用者手写的 {@code datasource.json}——
 * 程序去改配置文件会覆盖人的编辑意图。
 * <p>
 * 订阅是「群 + 主播 + 类型」三者的组合：同一个人可能只想被某位主播的开播提醒，
 * 而不想收到其动态提醒。
 */
@Service
public class AtSubscriptionService {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "AtSubscriptions";

    /**
     * 单个订阅名单的人数上限
     * <p>
     * @ 的人数过多时消息会被平台截断甚至拒发，且刷屏本身也构成打扰。
     */
    private static final int LIMIT = 200;

    private final StarBotStateStore store;

    @Autowired
    public AtSubscriptionService(StarBotStateStore store) {
        this.store = store;
    }

    /**
     * 订阅
     * @param platform 推送平台
     * @param num 会话号
     * @param streamerUid 主播 UID
     * @param type 订阅类型，如 live / dynamic
     * @param userUid 订阅者账号
     * @return 订阅结果
     */
    public Result subscribe(@NonNull String platform, @NonNull Long num, @NonNull Long streamerUid,
                            @NonNull String type, @NonNull Long userUid) {
        if (contains(platform, num, streamerUid, type, userUid)) {
            return Result.ALREADY;
        }
        if (list(platform, num, streamerUid, type).size() >= LIMIT) {
            return Result.FULL;
        }

        store.write(NAMESPACE, data -> {
            String key = key(platform, num, streamerUid, type);
            data.putIfAbsent(key, new JSONObject());
            data.getJSONObject(key).put(String.valueOf(userUid), 1);
        });
        return Result.OK;
    }

    /**
     * 取消订阅
     * @return 订阅结果，未订阅过时为 {@link Result#ALREADY}
     */
    public Result unsubscribe(@NonNull String platform, @NonNull Long num, @NonNull Long streamerUid,
                              @NonNull String type, @NonNull Long userUid) {
        if (!contains(platform, num, streamerUid, type, userUid)) {
            return Result.ALREADY;
        }

        store.write(NAMESPACE, data -> {
            JSONObject users = data.getJSONObject(key(platform, num, streamerUid, type));
            if (users != null) {
                users.remove(String.valueOf(userUid));
            }
        });
        return Result.OK;
    }

    /**
     * 取得订阅名单
     * @return 订阅者账号列表，无人订阅时为空列表
     */
    public List<Long> list(@NonNull String platform, @NonNull Long num, @NonNull Long streamerUid, @NonNull String type) {
        JSONObject users = store.namespace(NAMESPACE).getJSONObject(key(platform, num, streamerUid, type));
        if (users == null) {
            return List.of();
        }

        List<Long> result = new ArrayList<>(users.size());
        for (String key : users.keySet()) {
            try {
                result.add(Long.parseLong(key));
            } catch (NumberFormatException ignored) {
                // 手工编辑状态文件时可能混入非法键，跳过即可
            }
        }
        return result;
    }

    /**
     * 是否已订阅
     */
    public boolean contains(@NonNull String platform, @NonNull Long num, @NonNull Long streamerUid,
                            @NonNull String type, @NonNull Long userUid) {
        JSONObject users = store.namespace(NAMESPACE).getJSONObject(key(platform, num, streamerUid, type));
        return users != null && users.containsKey(String.valueOf(userUid));
    }

    private String key(String platform, Long num, Long streamerUid, String type) {
        return platform + ":" + num + ":" + streamerUid + ":" + type;
    }

    /**
     * 订阅操作的结果
     */
    public enum Result {
        /**
         * 操作成功
         */
        OK,

        /**
         * 状态本就如此，无需改变
         */
        ALREADY,

        /**
         * 名单已满
         */
        FULL
    }
}
