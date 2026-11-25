package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;

/** 照常传输 JSON，供框架调试日志使用的字符串隐去正文。 */
public final class ConsoleJsonBody extends JSONObject {
    public ConsoleJsonBody() {}

    public ConsoleJsonBody(JSONObject body) {
        super(body);
    }

    @Override
    public String toString() {
        return "（正文已隐藏）";
    }
}
