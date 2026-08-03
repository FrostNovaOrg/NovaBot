package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 哔哩哔哩用户信息，在通用用户信息之上补充粉丝勋章、大航海与荣耀等级
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliUserInfo extends UserInfo {
    /**
     * 粉丝勋章
     */
    private FansMedal fansMedal;

    /**
     * 大航海信息
     */
    private Guard guard;

    /**
     * 荣耀等级
     */
    private Integer honorLevel;

    public BilibiliUserInfo(Long uid, String uname) {
        super(uid, uname);
    }

    public BilibiliUserInfo(Long uid, String uname, String face) {
        super(uid, uname, face);
    }
}
