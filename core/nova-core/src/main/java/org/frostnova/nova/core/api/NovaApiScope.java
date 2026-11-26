package org.frostnova.nova.core.api;

import java.util.regex.Pattern;

/**
 * 访问令牌可以勾选的一项范围
 * <p>
 * 插件在 {@link NovaApiProvider#scopes()} 里登记，签发令牌时作为一个可勾选项列出来；
 * 接口在 {@link NovaApiRoute} 上写明自己要哪一项，令牌没勾就回 403。
 *
 * @param id          标识，小写字母开头，只含小写字母、数字、点、冒号与连字符，例如 {@code report:read}
 * @param name        中文名，签发页上勾选框旁边那几个字
 * @param description 一句说明：勾上它之后程序能做什么
 */
public record NovaApiScope(String id, String name, String description) {
    static final Pattern ID = Pattern.compile("[a-z][a-z0-9.:-]{0,63}");

    public NovaApiScope {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("范围标识不合规: " + id);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("范围缺少中文名: " + id);
        }
        description = description == null ? "" : description;
    }
}
