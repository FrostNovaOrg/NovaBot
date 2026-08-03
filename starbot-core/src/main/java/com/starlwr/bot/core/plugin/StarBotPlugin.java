package com.starlwr.bot.core.plugin;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * StarBot 插件信息
 */
@Getter
@Setter
@NoArgsConstructor
public class StarBotPlugin {
    /**
     * 插件 Jar
     */
    private File jarFile;

    /**
     * 插件元数据
     */
    private StarBotPluginMeta meta;

    /**
     * 依赖列表
     */
    private List<Dependency> dependencies = new ArrayList<>();

    /**
     * 依赖的其他插件列表
     */
    private List<Dependency> pluginDependencies = new ArrayList<>();

    /**
     * 组件全类名列表
     */
    private List<String> componentClassNames = new ArrayList<>();

    /**
     * 组件类列表
     */
    private List<Class<?>> componentClasses = new ArrayList<>();

    /**
     * 获取插件唯一标识符
     * @return 插件唯一标识符
     */
    public String getId() {
        return meta.getId();
    }
}
