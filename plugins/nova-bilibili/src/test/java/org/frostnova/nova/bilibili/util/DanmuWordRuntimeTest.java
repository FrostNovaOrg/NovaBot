package org.frostnova.nova.bilibili.util;

import org.frostnova.nova.core.lang.StringUtil;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DanmuWordRuntimeTest {
    @Test
    void extractsWordsWithoutTheReferenceJar() throws Exception {
        URL[] classpath = {
                DanmuWordUtil.class.getProtectionDomain().getCodeSource().getLocation(),
                StringUtil.class.getProtectionDomain().getCodeSource().getLocation()
        };
        try (URLClassLoader loader = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
            Class<?> wordUtil = loader.loadClass(DanmuWordUtil.class.getName());
            wordUtil.getMethod("warmUp").invoke(null);
            var extractWords = wordUtil.getMethod("extractWords", String.class);
            assertEquals(List.of("主播", "唱歌", "好听", "dog"),
                    extractWords.invoke(null, "主播今天唱歌真好听 DOG"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.huaban.analysis.jieba.WordDictionary"));
        }
    }
}
