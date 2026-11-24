package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

final class QqBotResponse {
    private final int status;
    private final Object body;
    private final boolean malformed;
    private final int code;
    private final int errCode;

    private QqBotResponse(int status, Object body, boolean malformed) {
        this.status = status;
        this.body = body;
        this.malformed = malformed;
        this.code = readCode("code");
        this.errCode = readCode("err_code");
    }

    static QqBotResponse parse(QqBotHttpTransport.Response response) {
        String body = response.body();
        try {
            return new QqBotResponse(response.status(),
                    body == null || body.isBlank() ? null : JSON.parse(body), false);
        } catch (RuntimeException e) {
            return new QqBotResponse(response.status(), null, true);
        }
    }

    boolean successful() {
        return status >= 200 && status < 300 && code() == 0 && !malformed;
    }

    boolean hasCode(int value) {
        return code == value || errCode == value;
    }

    int code() {
        return code != 0 ? code : errCode;
    }

    int status() {
        return status;
    }

    Object body() {
        return body;
    }

    Object field(String key) {
        return body instanceof JSONObject json ? json.get(key) : null;
    }

    String text(String key) {
        return field(key) instanceof String value ? value : "";
    }

    private int readCode(String key) {
        Object value = field(key);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
