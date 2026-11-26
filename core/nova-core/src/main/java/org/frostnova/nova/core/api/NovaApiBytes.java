package org.frostnova.nova.core.api;

import org.springframework.http.MediaType;

import java.util.Objects;

/** 处理器返回此对象时，响应体为指定内容类型的原始字节。 */
public record NovaApiBytes(MediaType contentType, byte[] bytes) {
    public NovaApiBytes {
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(bytes);
    }
}
