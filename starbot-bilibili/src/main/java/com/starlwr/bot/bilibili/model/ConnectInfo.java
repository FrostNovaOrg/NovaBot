package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播间长连接信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ConnectInfo {
    /**
     * 认证令牌
     */
    private String token;

    /**
     * 可用的服务器地址列表
     */
    private List<ConnectAddress> addresses = new ArrayList<>();

    /**
     * 判断连接信息是否可用
     * @return 连接信息是否可用
     */
    public boolean isAvailable() {
        return token != null && !token.isBlank() && addresses != null && !addresses.isEmpty();
    }
}
