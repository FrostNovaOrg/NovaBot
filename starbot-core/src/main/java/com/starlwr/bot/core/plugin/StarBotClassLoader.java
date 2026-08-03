package com.starlwr.bot.core.plugin;

import lombok.extern.slf4j.Slf4j;

/**
 * StarBot 类加载器
 */
@Slf4j
public class StarBotClassLoader extends ClassLoader {
    private final ClassLoader pluginClassLoader;

    public StarBotClassLoader(ClassLoader pluginClassLoader, ClassLoader parent) {
        super(parent);
        this.pluginClassLoader = pluginClassLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            Class<?> clazz = pluginClassLoader.loadClass(name);
            if (resolve) {
                resolveClass(clazz);
            }
            return clazz;
        } catch (ClassNotFoundException ignored) {
        }

        return super.loadClass(name, resolve);
    }
}
