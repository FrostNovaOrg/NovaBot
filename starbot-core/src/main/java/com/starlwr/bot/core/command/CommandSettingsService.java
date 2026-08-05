package com.starlwr.bot.core.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.service.StarBotStateStore;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /**
     * 列出各会话被禁用的命令
     * <p>
     * 命令开关只能在群里改，而**改动的痕迹此前只留在状态文件里**：群管理员关掉一条命令后，
     * 机器人的主人在界面上完全看不出来，只会觉得「这个群的机器人怎么不吭声了」。
     * @return 各会话的禁用清单，按平台与会话号排序
     */
    public List<Disabled> all() {
        JSONObject data = store.namespace(NAMESPACE);
        List<Disabled> result = new ArrayList<>();

        for (String key : data.keySet()) {
            // 键为「平台:会话号」。平台名可能含连字符（qq-onebot）但不含冒号，
            // 会话号必为数字，因此从右侧切一刀即可还原
            int split = key.lastIndexOf(':');
            if (split <= 0) {
                continue;
            }

            try {
                JSONObject commands = data.getJSONObject(key);
                if (commands == null || commands.isEmpty()) {
                    // 启用命令只是移除键，空对象会留下，此时该会话没有任何禁用项
                    continue;
                }
                result.add(new Disabled(key.substring(0, split),
                        Long.parseLong(key.substring(split + 1)), List.copyOf(commands.keySet())));
            } catch (Exception ignored) {
                // 手工编辑状态文件时可能混入非法键，跳过即可
            }
        }

        result.sort(Comparator.comparing(Disabled::platform).thenComparingLong(Disabled::num));
        return result;
    }

    private String key(String platform, Long num) {
        return platform + ":" + num;
    }

    /**
     * 某个会话中被禁用的命令
     * @param platform 推送平台
     * @param num 会话号
     * @param commands 被禁用的命令名
     */
    public record Disabled(String platform, long num, List<String> commands) {
    }
}
