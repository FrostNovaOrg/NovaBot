package com.starlwr.bot.core.model;

import lombok.*;

/**
 * 用户信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class UserInfo {
    /**
     * UID
     */
    private Long uid;

    /**
     * 昵称
     */
    private String uname;

    /**
     * 头像
     */
    private String face;

    public UserInfo(Long uid, String uname) {
        this.uid = uid;
        this.uname = uname;
    }

    public UserInfo(Long uid, String uname, String face) {
        this.uid = uid;
        this.uname = uname;
        this.face = face;
    }
}
