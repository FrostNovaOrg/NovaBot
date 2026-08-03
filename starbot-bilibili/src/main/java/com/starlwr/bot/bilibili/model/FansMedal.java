package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 粉丝勋章
 * <p>
 * 除勋章本身的名称与等级外，还携带勋章所属主播的信息，因此继承主播信息。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class FansMedal extends LiveStreamerInfo {
    /**
     * 勋章名称
     */
    private String name;

    /**
     * 勋章等级
     */
    private Integer level;

    /**
     * 勋章是否点亮
     */
    private Boolean lighted;

    public FansMedal(Long uid, String uname, Long roomId, String name, Integer level, Boolean lighted) {
        super(uid, uname, roomId);
        this.name = name;
        this.level = level;
        this.lighted = lighted;
    }

    public FansMedal(Long uid, String uname, Long roomId, String face, String name, Integer level, Boolean lighted) {
        super(uid, uname, roomId, face);
        this.name = name;
        this.level = level;
        this.lighted = lighted;
    }
}
