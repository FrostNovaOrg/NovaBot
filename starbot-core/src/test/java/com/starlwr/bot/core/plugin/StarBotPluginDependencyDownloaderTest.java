package com.starlwr.bot.core.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件依赖下载器测试
 * <p>
 * 依赖坐标取自插件自带的 dependency.json，属于第三方可控输入，而下载下来的 jar
 * 下次启动就会被加载进 JVM——下载环节等同于代码执行入口，坐标校验是第一道闸。
 */
@DisplayName("插件依赖下载器")
class StarBotPluginDependencyDownloaderTest {
    private final StarBotPluginDependencyDownloader downloader =
            new StarBotPluginDependencyDownloader(null, null, null, null, null);

    @Test
    @DisplayName("正常的 Maven 坐标应通过校验")
    void acceptsNormalCoordinate() {
        assertTrue(downloader.isValidCoordinate(dep("org.yaml", "snakeyaml", "2.5")));
        assertTrue(downloader.isValidCoordinate(dep("com.alibaba.fastjson2", "fastjson2", "2.0.60")));
        assertTrue(downloader.isValidCoordinate(dep("org.junit-pioneer", "junit_pioneer", "1.0.0-RC1")));
    }

    @Test
    @DisplayName("含路径穿越字符的坐标应被拒绝")
    void rejectsPathTraversal() {
        assertFalse(downloader.isValidCoordinate(dep("org.x", "../../evil", "1.0")));
        assertFalse(downloader.isValidCoordinate(dep("../..", "x", "1.0")));
        assertFalse(downloader.isValidCoordinate(dep("org.x", "x", "1.0/../../etc/passwd")));
    }

    @Test
    @DisplayName("含分隔符或空白的坐标应被拒绝")
    void rejectsSeparatorsAndBlanks() {
        assertFalse(downloader.isValidCoordinate(dep("org/x", "x", "1.0")));
        assertFalse(downloader.isValidCoordinate(dep("org.x", "x y", "1.0")));
        assertFalse(downloader.isValidCoordinate(dep("org.x", "x", "")));
        assertFalse(downloader.isValidCoordinate(dep(null, "x", "1.0")));
    }

    @Test
    @DisplayName("落盘路径必须位于依赖目录之内")
    void resolvesInsidePluginsLib() throws IOException {
        assertTrue(downloader.resolveTarget(dep("org.yaml", "snakeyaml", "2.5"))
                .endsWith("plugins-lib/snakeyaml-2.5.jar"));
    }

    @Test
    @DisplayName("仓库相对路径应按 Maven 布局拼接")
    void buildsMavenLayoutPath() {
        assertEquals("org/yaml/snakeyaml/2.5/snakeyaml-2.5.jar",
                downloader.relativePath(dep("org.yaml", "snakeyaml", "2.5")));
    }

    private Dependency dep(String group, String artifact, String version) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(group);
        dependency.setArtifactId(artifact);
        dependency.setVersion(version);
        return dependency;
    }
}
