package com.starlwr.bot.adapter.onebot.http;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.annotation.OneBotApi;
import com.starlwr.bot.adapter.onebot.exception.OneBotApiException;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * StarBot OneBot HTTP 服务代理
 */
@Slf4j
public class OneBotHttpAdapterProxy implements InvocationHandler {
    private final HttpUtil http;

    private final OneBotConnectionState state;

    public OneBotHttpAdapterProxy(HttpUtil http, OneBotConnectionState state) {
        this.http = http;
        this.state = state;
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

        if (method.isAnnotationPresent(OneBotApi.class)) {
            OneBotApi api = method.getAnnotation(OneBotApi.class);
            if (api != null) {
                OneBotSender sender = (OneBotSender) args[0];
                JSONObject params = (JSONObject) args[1];

                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + sender.getOneBotHttpToken());
                String apiBaseUrl = "http://" + sender.getOneBotAddress() + ":" + sender.getOneBotHttpPort();

                log.debug("OneBotApi <- : {} {}", api.url(), StringUtil.getOmitString(params.toJSONString(), sender.getDebugLogMaxLength()));
                String url = apiBaseUrl + api.url();

                // 每个 OneBot 接口调用都从这里过，是记录往返耗时唯一不会漏的地方。
                // 耗时是一个健康维度：接口调得通但每次要好几秒时，图片推送会因为
                // 没有工作线程去读那个大请求体而超时丢弃，而连通性检查全程看不出异常
                long startTime = System.currentTimeMillis();
                JSONObject result = http.postJson(url, headers, params);
                state.recordLatency(sender.getName(), System.currentTimeMillis() - startTime);

                log.debug("OneBotApi -> : {} {}", api.url(), result.toJSONString());

                if (result.getInteger("retcode") != 0) {
                    throw new OneBotApiException(api.url(), params, result.getInteger("retcode"), result.getString("message"));
                }

                // data 有对象与数组两种形状：查一条信息回对象，列群／列好友回数组。
                // 按方法声明的返回类型分流，而不是一律当对象取。
                //
                // 🔴 一律当对象取<b>不会报错</b>：2026-09-04 实测，fastjson 的 getJSONObject
                // 读到一个数组时静默返回 null。于是列表接口一条不少地取回来了，
                // 而调用方拿到的是 null——报出来的是某处的空指针，与「这个接口返回的是列表」
                // 之间隔着一段没人愿意查第二遍的距离。抛异常反倒是好的那种失败。
                return JSONArray.class.equals(method.getReturnType())
                        ? result.getJSONArray("data")
                        : result.getJSONObject("data");
            }
        }

        throw new UnsupportedOperationException("不支持的方法 " + method);
    }
}
