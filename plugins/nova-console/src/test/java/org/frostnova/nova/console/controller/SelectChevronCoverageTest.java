package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 控制台的下拉框一律用自画的箭头，挂在 body 下的抽屉里那个也一样
 * <p>
 * 抓的故障：装了两个直播平台，点「＋ 添加主播」，抽屉里挑平台的下拉框还是浏览器原生的箭头。
 * 箭头规则从前只认 #app 里面的下拉框，而抽屉为了盖住整页挂在 body 上。
 * 夹具真跑推送页的 render 与 addStreamer，把核心样式与推送页样式按先后、权重叠一遍，
 * 读出那个下拉框上最终生效的背景图与右侧留位。
 * <p>
 * 箭头规则记在核心界面那份样式表里，那件不在本模块。这里由类加载器从测试类路径上把它
 * 取到（取不到就红），把绝对路径经环境变量交给夹具——夹具那边见不到路径时也红，不跳过。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("下拉框箭头覆盖到抽屉")
class SelectChevronCoverageTest {
    /**
     * 相对本模块目录；测试在模块目录下跑
     */
    private static final Path FIXTURE = Path.of("src/test/resources/frontend/select-chevron-coverage-fixture.mjs");

    @Test
    @DisplayName("「添加主播」抽屉里的下拉框与页里的用同一条箭头规则")
    void drawerSelectUsesTheSameChevron() throws IOException, InterruptedException, URISyntaxException {
        URL url = SelectChevronCoverageTest.class.getClassLoader().getResource("config-ui/app.css");
        assertNotNull(url, "核心界面的 app.css 不在本模块测试的类路径上，这一格什么也没量");
        FrontendFixture.runFromModule(FIXTURE.toString(), "下拉框箭头覆盖到抽屉",
                Map.of("APP_CSS", absolutePathOf(url).toString()));
    }

    /**
     * 类路径上那一份可能装在 jar 里，没有盘上路径；取不到就原样拷一份到临时文件再交出去。
     * 交给夹具的总是一个盘上绝对路径。
     */
    private static Path absolutePathOf(URL url) throws IOException, URISyntaxException {
        if ("file".equals(url.getProtocol())) {
            return Path.of(url.toURI()).toAbsolutePath().normalize();
        }
        Path copy = Files.createTempFile("nova-app-css", ".css");
        copy.toFile().deleteOnExit();
        try (InputStream in = url.openStream()) {
            Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        return copy;
    }
}
