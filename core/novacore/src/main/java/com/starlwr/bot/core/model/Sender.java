package com.starlwr.bot.core.model;

import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

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

    /**
     * 进程内投递入口，为空时走 {@link #url} 发 HTTP
     * <p>
     * 适配器与核心在同一个进程里时，用它把消息直接交过去，不必绕本机 HTTP 一圈。
     * 详见 {@link LocalDelivery}。
     */
    @ToString.Exclude
    private LocalDelivery localDelivery;

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

    public Sender(String name, String url, String token, int delay, LocalDelivery localDelivery) {
        this(name, url, token, delay);
        this.localDelivery = localDelivery;
    }

    /**
     * 进程内投递
     * <p>
     * 适配器跑在核心的同一个进程里时，核心此前仍然把消息 POST 给自己的 HTTP 端口，
     * 由自己的控制器收下再转给下游。这一圈自环带来一个很难查的故障：
     * 服务端口的工作线程是有限的（默认 8 个），而控制器转发下游时是同步阻塞的；
     * 下游一慢，线程被占满，就<b>没有线程去读请求体</b>。
     * 体积小的文字消息一次写进 socket 缓冲区就完事，照常送达；
     * 而图片是几百 KB 的内联 base64，必须服务端一边读才写得完——于是卡满超时被丢弃。
     * <b>表现为「文字能发、图片发不出去」，而两端日志都看不出所以然。</b>
     * <p>
     * 同进程直调把这一圈去掉：没有 socket，就没有「谁来读请求体」的问题。
     * 对外的推送接口与它的鉴权照旧保留，供外部程序调用。
     */
    @FunctionalInterface
    public interface LocalDelivery {
        /**
         * 投递一条消息
         * @param headers 若走 HTTP 会带上的请求头，进程内投递通常用不到，保留以便实现方按需鉴权
         * @param params 消息参数，与 HTTP 请求体的字段完全一致
         * @return 投递结果，与 HTTP 接口的响应体结构一致
         */
        JSONObject deliver(Map<String, String> headers, Map<String, Object> params);
    }
}
