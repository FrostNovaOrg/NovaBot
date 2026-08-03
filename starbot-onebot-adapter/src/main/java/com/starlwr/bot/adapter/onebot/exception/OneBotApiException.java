package com.starlwr.bot.adapter.onebot.exception;

import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;

/**
 * OneBot 接口异常
 */
@Getter
public class OneBotApiException extends RuntimeException {
    private final String api;

    private final JSONObject params;

    private final int code;

    private final String msg;

    public OneBotApiException(String api, JSONObject params, Integer code, String msg) {
        super();
        this.api = api;
        this.params = params;
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMessage() {
        return "OneBot API 请求异常, 接口: " + api + ", 请求参数: " + params.toJSONString() + ", 错误码: " + code + ", 信息: " + msg;
    }
}
