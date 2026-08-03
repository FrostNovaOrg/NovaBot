package com.starlwr.bot.core.plugin;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * StarBot 插件元数据
 */
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class StarBotPluginMeta extends Dependency {
    /**
     * 插件名称
     */
    private String name;

    /**
     * 插件描述
     */
    private String description;

    /**
     * 插件主页 URL
     */
    private String url;

    /**
     * 插件作者
     */
    private String author;

    /**
     * 插件开源许可证
     */
    private String license;

    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
