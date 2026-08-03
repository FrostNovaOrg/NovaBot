package com.starlwr.bot.core.model;

import com.alibaba.fastjson2.annotation.JSONField;
import com.starlwr.bot.core.enums.PushTargetType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 推送目标
 */
@Profile("mysql")
@Getter
@Setter
@Entity
@Table(name = "starbot_push_target")
public class PushTarget {
    /**
     * ID，数据库类数据源使用
     */
    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的推送用户
     */
    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    @JSONField(serialize = false)
    private PushUser user;

    /**
     * 推送平台
     */
    @Column(name = "platform")
    private String platform;

    /**
     * 推送目标类型
     */
    @Column(name = "type")
    @Enumerated(EnumType.ORDINAL)
    private PushTargetType type;

    /**
     * 账号或群号，根据推送目标类型而定
     */
    @Column(name = "num")
    private Long num;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    private Boolean enabled;

    /**
     * 关联的推送消息
     */
    @OneToMany(mappedBy = "target", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PushMessage> messages = new ArrayList<>();

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
        return equals(other) && messages.equals(other.messages);
    }
}
