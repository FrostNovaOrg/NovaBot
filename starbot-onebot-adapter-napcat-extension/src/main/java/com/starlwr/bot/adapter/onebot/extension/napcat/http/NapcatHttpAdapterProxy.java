package com.starlwr.bot.adapter.onebot.extension.napcat.http;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.exception.OneBotApiException;
import com.starlwr.bot.adapter.onebot.extension.napcat.annotation.NapcatApi;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * StarBot Napcat HTTP 扩展服务代理
 */
@Slf4j
public class NapcatHttpAdapterProxy implements InvocationHandler {
    private final HttpUtil http;

    public NapcatHttpAdapterProxy(HttpUtil http) {
        this.http = http;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "toString":
                    return this.toString();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
            }
        }

        if (method.isAnnotationPresent(NapcatApi.class)) {
            NapcatApi api = method.getAnnotation(NapcatApi.class);
            if (api != null) {
                OneBotSender sender = (OneBotSender) args[0];
                JSONObject params = (JSONObject) args[1];

                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + sender.getOneBotHttpToken());
                String apiBaseUrl = "http://" + sender.getOneBotAddress() + ":" + sender.getOneBotHttpPort();

                log.debug("NapcatApi <- : {} {}", api.url(), StringUtil.getOmitString(params.toJSONString(), sender.getDebugLogMaxLength()));
                String url = apiBaseUrl + api.url();
                JSONObject result = http.postJson(url, headers, params);
                log.debug("NapcatApi -> : {} {}", api.url(), result.toJSONString());

                if (result.getInteger("retcode") != 0) {
                    throw new OneBotApiException(api.url(), params, result.getInteger("retcode"), result.getString("message"));
                }

                return result.getJSONObject("data");
            }
        }

        throw new UnsupportedOperationException("不支持的方法 " + method);
    }
}
