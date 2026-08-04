package com.starlwr.bot.core.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.service.StarBotStateStore;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 命令开关
 * <p>
 * 按会话记录被禁用的命令。同一个机器人服务多个群时，各群对「机器人该多话还是少话」
 * 的期待并不相同，因此开关是按群而非全局。
 */
@Service
public class CommandSettingsService {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "DisabledCommands";

    private final StarBotStateStore store;

    @Autowired
    public CommandSettingsService(StarBotStateStore store) {
        this.store = store;
    }

    /**
     * 判断命令在指定会话中是否已被禁用
     * @param platform 推送平台
     * @param num 会话号
     * @param command 命令名
     * @return 是否已禁用
     */
    public boolean isDisabled(@NonNull String platform, @NonNull Long num, @NonNull String command) {
        return store.read(NAMESPACE, key(platform, num), data -> data.getJSONObject(key(platform, num)))
                .map(disabled -> disabled.containsKey(command))
                .orElse(false);
    }

    /**
     * 禁用命令
     * @param platform 推送平台
     * @param num 会话号
     * @param command 命令名
     * @return 是否发生了变化（原本已禁用时为 false）
     */
    public boolean disable(@NonNull String platform, @NonNull Long num, @NonNull String command) {
        if (isDisabled(platform, num, command)) {
            return false;
        }

        store.write(NAMESPACE, data -> {
            data.putIfAbsent(key(platform, num), new JSONObject());
            data.getJSONObject(key(platform, num)).put(command, 1);
        });
        return true;
    }

    /**
     * 启用命令
     * @param platform 推送平台
     * @param num 会话号
     * @param command 命令名
     * @return 是否发生了变化（原本就启用时为 false）
     */
    public boolean enable(@NonNull String platform, @NonNull Long num, @NonNull String command) {
        if (!isDisabled(platform, num, command)) {
            return false;
        }

        store.write(NAMESPACE, data -> {
            JSONObject disabled = data.getJSONObject(key(platform, num));
            if (disabled != null) {
                disabled.remove(command);
            }
        });
        return true;
    }

    private String key(String platform, Long num) {
        return platform + ":" + num;
    }
}
