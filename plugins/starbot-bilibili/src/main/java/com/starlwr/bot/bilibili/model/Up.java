package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Objects;

/**
 * UP 主信息
 * <p>
 * 以 uid 作为相等性判据，便于直接放入集合去重。uid 定义在父类中，Lombok 无法在
 * {@code @EqualsAndHashCode(of = ...)} 中引用父类字段，因此手工实现。
 */
@NoArgsConstructor
@ToString(callSuper = true)
public class Up extends LiveStreamerInfo {
    /**
     * 粉丝数。与昵称、房间号同出于主播信息接口的一份响应，由那一趟顺路捎回；
     * 不写回推送用户——那个模型要读写、比对与落盘，见 DataSourceService#getFansCount
     */
    @Getter
    @Setter
    private Long fans;

    public Up(Long uid, String uname, Long roomId) {
        super(uid, uname, roomId);
    }

    public Up(Long uid, String uname, Long roomId, String face) {
        super(uid, uname, roomId, face);
    }

    public Up(Long uid, String uname, Long roomId, String face, Long fans) {
        super(uid, uname, roomId, face);
        this.fans = fans;
    }

    public Up(PushUser user) {
        super(user.getUid(), user.getUname(), user.getRoomId(), user.getFace());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Up other)) {
            return false;
        }
        return Objects.equals(getUid(), other.getUid());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getUid());
    }
}
