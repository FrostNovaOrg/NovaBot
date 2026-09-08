package com.starlwr.bot.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试资源目录须真的出现在测试类路径上
 * <p>
 * 这一格量的是构建配置，不是业务代码。{@code maven-resources-plugin} 的 {@code resources}
 * 与 {@code testResources} 两个目标共用同一个参数名 {@code resources}：把它配在
 * <b>插件层</b>的 {@code configuration} 里，两个目标就都读到同一份值，于是
 * {@code src/test/resources} 被 {@code src/main/resources} 顶掉，
 * <b>放进测试资源目录的文件根本不会出现在测试类路径上</b>。
 * <p>
 * 这种失效不报错也不警告：{@code target/test-classes} 里躺着的是主资源，
 * 而想读测试资源的人只会看到一句 {@code FileNotFoundException}，
 * 十有八九会顺手改成按相对路径读盘绕过去——绕过去之后，
 * 从工作目录不是模块根目录的地方跑测试就会失败，而且是下一个人去查。
 * <p>
 * 所以这里立一格：只问「测试资源读不读得到」，不问业务对错。
 */
@DisplayName("测试资源类路径")
class TestResourcesOnClasspathTest {
    /**
     * 取一个测试资源目录下确实存在的文件作为探针
     */
    private static final String PROBE = "/configuration-baseline/config-keys.txt";

    @Test
    @DisplayName("src/test/resources 下的文件应能从类路径读到")
    void shouldExposeTestResourcesOnClasspath() throws Exception {
        URL located = TestResourcesOnClasspathTest.class.getResource(PROBE);

        assertNotNull(located, "测试资源 " + PROBE + " 不在测试类路径上"
                + "：多半是构建把 src/main/resources 顶到了 src/test/resources 的位置");

        try (InputStream in = TestResourcesOnClasspathTest.class.getResourceAsStream(PROBE)) {
            assertNotNull(in, "定位得到却读不出内容: " + PROBE);
            assertTrue(new String(in.readAllBytes()).contains("novabot.core."),
                    "读到的不是预期的那份基线文件: " + PROBE);
        }
    }

    @Test
    @DisplayName("主资源不应被搬进测试类路径顶替测试资源")
    void shouldNotShipMainResourcesAsTestResources() {
        assertNotNull(TestResourcesOnClasspathTest.class.getResource(PROBE),
                "测试资源缺席时，本格与上一格同时红，指向的是同一处构建配置");
    }
}
