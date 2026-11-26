package org.frostnova.nova.core.api;

import java.util.regex.Pattern;

/**
 * 访问令牌可以勾选的一项范围
 * <p>
 * 插件在 {@link NovaApiProvider#scopes()} 里登记，签发令牌时作为一个可勾选项列出来；
 * 接口在 {@link NovaApiRoute} 上写明自己要哪一项，令牌没勾就回 403。
 *
 * <h2>仅管理员</h2>
 * 整个实例层面的操作（改全局设置、动所有主播的东西）登记成 {@code adminOnly} 的范围：
 * 成员签发时勾不了；分发时还按令牌所属账号<b>此刻</b>的角色再核一次，
 * 管理员被改成成员之后，这个账号以前签的带这种范围的令牌调对应接口回 403。
 * 按主播划分的数据不用这个标记，用 {@link NovaApiCaller#canSee(String, long)} 按调用者筛。
 *
 * @param id          标识，小写字母开头，只含小写字母、数字、点、冒号与连字符，例如 {@code report:read}
 * @param name        中文名，签发页上勾选框旁边那几个字
 * @param description 一句说明：勾上它之后程序能做什么
 * @param adminOnly   只有管理员能勾、只有管理员的令牌能用
 */
public record NovaApiScope(String id, String name, String description, boolean adminOnly) {
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

    /** 管理员与成员都能勾的范围 */
    public NovaApiScope(String id, String name, String description) {
        this(id, name, description, false);
    }

    /**
     * 仅管理员的范围
     * @param id          标识
     * @param name        中文名
     * @param description 一句说明
     * @return 标了 {@code adminOnly} 的范围
     */
    public static NovaApiScope adminOnly(String id, String name, String description) {
        return new NovaApiScope(id, name, description, true);
    }
}
