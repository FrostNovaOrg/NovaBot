package com.starlwr.bot.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 推送平台信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Sender {
    /**
     * 推送平台名称
     */
    private String name;

    /**
     * 推送平台接口完整地址，例如：http://localhost:3000/onebot/send
     */
    private String url;

    /**
     * 推送平台接口 Token，适用于对接端无 Token 机制时，在推送平台接口处验证 Token，若对接端已有 Token 机制，则无需设置此处
     */
    private String token = "";

    /**
     * 消息发送间隔时间，单位：毫秒
     */
    private int delay;

    public Sender(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public Sender(String name, String url, int delay) {
        this.name = name;
        this.url = url;
        this.delay = delay;
    }

    public Sender(String name, String url, String token, int delay) {
        this.name = name;
        this.url = url;
        this.token = token;
        this.delay = delay;
    }
}
