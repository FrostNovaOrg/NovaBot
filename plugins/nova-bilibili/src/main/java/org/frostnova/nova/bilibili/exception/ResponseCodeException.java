package org.frostnova.nova.bilibili.exception;

import lombok.Getter;

/**
 * 响应错误代码异常，表示服务器返回了非 0 的业务错误代码
 */
@Getter
public class ResponseCodeException extends RuntimeException {
    /**
     * 业务错误代码
     */
    private final int code;

    /** B 站返回的原始说明，不含异常格式前缀。 */
    private final String responseMessage;

    public ResponseCodeException(int code, String message) {
        super("接口返回错误代码 " + code + ": " + message);
        this.code = code;
        this.responseMessage = message;
    }
}
