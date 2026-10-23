package org.frostnova.nova.core.model;

import com.alibaba.fastjson2.annotation.JSONField;
import org.frostnova.nova.core.enums.PushTargetType;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 推送目标
 */
@Getter
@Setter
public class PushTarget {
    /**
     * 关联的推送用户
     * <p>
     * 反向引用，序列化时须跳过，否则与 {@link PushUser#getTargets()} 构成环
     */
    @JSONField(serialize = false)
    private PushUser user;

    /**
     * 推送平台
     */
    private String platform;

    /**
     * 推送目标类型
     */
    private PushTargetType type;

    /**
     * 账号或群号，根据推送目标类型而定
     */
    private Long num;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 关联的推送消息
     */
    private List<PushMessage> messages = new ArrayList<>();

    /** 金额显示；null 使用群聊隐藏、好友显示的默认值。 */
    private Boolean revenueVisible;

    /** 静音设置；null 跟随全局。 */
    private QuietHours quietHours;

    /** 被关闭的命令用法名；null 或空列表表示全开。 */
    private List<String> disabledCommands;

    /** 报告图配色的预设 id；null 跟默认模板走。 */
    private String reportPalette;

    @Getter
    @Setter
    public static class QuietHours {
        private String mode;
        private String start;
        private String end;

        @Override
        public boolean equals(Object other) {
            return other instanceof QuietHours q && Objects.equals(mode, q.mode)
                    && Objects.equals(start, q.start) && Objects.equals(end, q.end);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, start, end);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PushTarget target)) return false;
        return Objects.equals(platform, target.platform) && type == target.type && Objects.equals(num, target.num);
    }

    @Override
    public int hashCode() {
        return Objects.hash(platform, type, num);
    }

    @Override
    public String toString() {
        return "PushTarget(" + "platform=" + platform + ", type=" + type.name() + ", num=" + num + ", enabled=" + enabled + ", messages=" + messages + ")";
    }

    /**
     * 检查当前推送目标是否与另一个推送目标完全相同
     * @param other 另一个推送目标
     * @return 是否完全相同
     */
    public boolean same(PushTarget other) {
        return equals(other) && messages.equals(other.messages)
                && Objects.equals(revenueVisible, other.revenueVisible)
                && Objects.equals(quietHours, other.quietHours)
                && Objects.equals(disabledCommands, other.disabledCommands)
                && Objects.equals(reportPalette, other.reportPalette);
    }
}
