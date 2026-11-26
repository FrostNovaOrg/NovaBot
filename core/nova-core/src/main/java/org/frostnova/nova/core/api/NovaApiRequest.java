package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 交给插件处理器的一次请求
 * <p>
 * 认证、来源、范围都已核过；这里只剩处理器自己要读的东西：调用者、路径变量、查询参数、请求体。
 * 不提供请求头与 Cookie：令牌在请求头里，处理器不该经手它。
 */
public final class NovaApiRequest {
    /** 请求体上限：这一层的接口收的是参数，不是文件 */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final HttpServletRequest servlet;

    private final NovaApiCaller caller;

    private final Map<String, String> variables;

    private JSONObject body;

    NovaApiRequest(HttpServletRequest servlet, NovaApiCaller caller, Map<String, String> variables) {
        this.servlet = servlet;
        this.caller = caller;
        this.variables = Map.copyOf(variables);
    }

    public NovaApiCaller caller() {
        return caller;
    }

    public String method() {
        return servlet.getMethod();
    }

    /**
     * 路径变量，例如登记的是 {@code streamers/{uid}}，这里按 {@code uid} 取
     * @param name 变量名
     * @return 值；没有这个变量为 null
     */
    public String path(String name) {
        return variables.get(name);
    }

    /**
     * 查询参数，同名多值时取第一个
     * @param name 参数名
     * @return 值；没有为 null
     */
    public String query(String name) {
        return servlet.getParameter(name);
    }

    /**
     * 请求体，按 JSON 对象读
     * <p>
     * 没有请求体时为空对象；不是 JSON 对象、超过上限时由底座回 400。
     * @return 请求体
     */
    public JSONObject body() {
        if (body != null) {
            return body;
        }
        try {
            byte[] bytes = servlet.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw NovaApiException.badRequest("请求体太大，上限 " + (MAX_BODY_BYTES / 1024) + " KB");
            }
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                body = new JSONObject();
                return body;
            }
            Object parsed = JSON.parse(text);
            if (!(parsed instanceof JSONObject json)) {
                throw NovaApiException.badRequest("请求体应为一个 JSON 对象");
            }
            body = json;
            return body;
        } catch (JSONException malformed) {
            throw NovaApiException.badRequest("请求体不是合法的 JSON");
        } catch (IOException unreadable) {
            throw NovaApiException.badRequest("请求体读不出来");
        }
    }
}
