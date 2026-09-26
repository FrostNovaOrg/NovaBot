package org.frostnova.nova.bilibili.exception;

import lombok.Getter;

/**
 * 接口正处在风控冷却中，这一次没有发到哔哩哔哩
 */
@Getter
public class RiskCooldownException extends RequestFailedException {
    /**
     * 接口路径，不含查询参数
     */
    private final String endpoint;

    public RiskCooldownException(String endpoint, String message) {
        super(message);
        this.endpoint = endpoint;
    }
}
