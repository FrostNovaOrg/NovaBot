package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.enums.PushTargetType;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

/**
 * 金额显示统一按主播通道读取，默认群聊隐藏、好友显示。
 * 旧会话接口保留给升级迁移与旧状态展示，推送和命令不再从它们继承设置。
 */
@Service
public class RevenueVisibilityService {
    /**
     * 状态存储中的命名空间
     */
    public static final String NAMESPACE = "RevenueVisibility";

    private final NovaStateStore store;

    @Autowired
    public RevenueVisibilityService(NovaStateStore store) {
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
        Boolean explicit = explicit(platform, type, num);
        return explicit == null ? defaultFor(type) : explicit;
    }

    /** 按主播通道读取金额；不再继承旧会话设置。 */
    public boolean isVisible(org.frostnova.nova.core.model.PushTarget target) {
        return target != null && target.getRevenueVisible() != null ? target.getRevenueVisible()
                : defaultFor(target == null ? null : target.getType());
    }

    /**
     * 读取显式设置
     * @param platform 推送平台
     * @param type 会话类型，不是群或好友时一律当没设过
     * @param num 会话号
     * @return 显式设置的值，从未设置过时为 null
     */
    public Boolean explicit(@NonNull String platform, PushTargetType type, @NonNull Long num) {
        String key = SessionKey.of(platform, type, num);
        if (key == null) {
            return null;
        }
        return store.read(NAMESPACE, key, data -> data.getBoolean(key)).orElse(null);
    }

    /**
     * 设置指定会话的金额可见性
     * @param platform 推送平台
     * @param type 会话类型，只能是群或好友
     * @param num 会话号
     * @param visible 是否可见，传 null 表示清除设置、回到按会话类型取的默认值
     */
    public void set(@NonNull String platform, PushTargetType type, @NonNull Long num, Boolean visible) {
        String key = SessionKey.require(platform, type, num);
        store.write(NAMESPACE, data -> {
            if (visible == null) {
                data.remove(key);
            } else {
                data.put(key, visible);
            }
        });
    }

    /**
     * 列出所有显式设置过的会话
     * <p>
     * 只列显式设置：把按默认值处理的会话也列出来，就分不清「配过」与「碰巧默认是这样」，
     * 而这两者在改了默认值之后会走向不同的结果。
     * @return 各会话的设置，按平台、会话号与会话类型排序
     */
    public List<Setting> all() {
        JSONObject data = store.namespace(NAMESPACE);
        List<Setting> result = new ArrayList<>();

        for (String key : data.keySet()) {
            // 旧键「平台:会话号」与迁移标记都还原不出会话，跳过
            SessionKey.Parsed parsed = SessionKey.parse(key);
            if (parsed == null) {
                continue;
            }

            try {
                Boolean visible = data.getBoolean(key);
                if (visible == null) {
                    continue;
                }
                result.add(new Setting(parsed.platform(), parsed.type(), parsed.num(), visible));
            } catch (Exception ignored) {
                // 手工编辑状态文件时可能混入非法值，跳过即可
            }
        }

        result.sort(Comparator.comparing(Setting::platform).thenComparingLong(Setting::num)
                .thenComparing(Setting::type));
        return result;
    }

    /**
     * 未显式设置时的默认值
     */
    public boolean defaultFor(PushTargetType type) {
        return type == PushTargetType.FRIEND;
    }

    /**
     * 某个会话的金额可见性设置
     *
     * @param platform 推送平台
     * @param type 会话类型
     * @param num 会话号
     * @param visible 是否可见
     */
    public record Setting(String platform, PushTargetType type, Long num, boolean visible) {
    }
}
