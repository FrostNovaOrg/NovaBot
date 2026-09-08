package com.starlwr.bot.adapter.onebot.extension.napcat.http;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.exception.OneBotApiException;
import com.starlwr.bot.adapter.onebot.extension.napcat.annotation.NapcatApi;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 把一次 {@link NapcatHttpAdapter} 方法调用翻译成一次 HTTP 往返
 * <p>
 * 三条分派路线，缺一不可：
 * <ol>
 *     <li>{@code toString}／{@code hashCode}／{@code equals} —— 动态代理会把这三支也交到这里来，
 *         不接住的话它们会被当成「没标注解的方法」而抛异常，连往集合里放一个代理都做不到</li>
 *     <li>标了 {@link NapcatApi} 的 —— 照注解上的地址打出去</li>
 *     <li>其余一律拒绝 —— 与其猜一个地址打出去，不如当场说不认识</li>
 * </ol>
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
            return call(method.getAnnotation(NapcatApi.class), (OneBotSender) args[0], (JSONObject) args[1]);
        }

        throw new UnsupportedOperationException("不支持的方法 " + method);
    }

    /**
     * 打一次 NapCat 扩展接口
     * @param api 接口声明，地址从这里取
     * @param sender 推送平台信息，决定打给谁、带哪个 Token
     * @param params 请求参数，原样送出
     * @return 应答里的 {@code data} 段，对面没给时为 {@code null}
     */
    private JSONObject call(NapcatApi api, OneBotSender sender, JSONObject params) {
        String url = "http://" + sender.getOneBotAddress() + ":" + sender.getOneBotHttpPort() + api.url();

        // 请求参数按平台配的长度截断再进日志：推送正文可以很长，整段抄进 debug 日志会把它冲掉
        log.debug("NapcatApi <- : {} {}", api.url(), StringUtil.getOmitString(params.toJSONString(), sender.getDebugLogMaxLength()));
        JSONObject result = http.postJson(url, authorization(sender), params);
        log.debug("NapcatApi -> : {} {}", api.url(), result.toJSONString());

        if (result.getInteger("retcode") != 0) {
            throw new OneBotApiException(api.url(), params, result.getInteger("retcode"), result.getString("message"));
        }

        return result.getJSONObject("data");
    }

    /**
     * 本次请求的鉴权头，用的是这个推送平台自己的 OneBot Token
     * <p>
     * 一台机器上可以连着多个 OneBot 实例，Token 各不相同，因此不能提到字段里缓存一份
     */
    private Map<String, String> authorization(OneBotSender sender) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + sender.getOneBotHttpToken());
        return headers;
    }
}
