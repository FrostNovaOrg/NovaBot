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
 * 推送页左树：群名后面的小标不许被挤成一字一行的竖条
 * <p>
 * 左栏 236px 宽，群名带两颗小标（「N 类关着」「N 条命令被关」）摆不下同一行；从前那组小标
 * 封顶 96px，两颗挤进去字被压成一两个字一行竖着排。夹具按字面宽算左树各行，量的是
 * 「一颗小标一行字、放不下整组换到名字下面一行并与名字左对齐、名字截断也留四五个字」。
 * <p>
 * 小标的描边记在核心界面那份样式表里，那件不在本模块。这里由类加载器从测试类路径上把它
 * 取到（取不到就红），把绝对路径经环境变量交给夹具——夹具那边见不到路径时也红，不跳过。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页左树小标不挤成竖条")
class PushTreeMarksTest {
    /**
     * 相对本模块目录；测试在模块目录下跑
     */
    private static final Path FIXTURE = Path.of("src/test/resources/frontend/push-tree-marks-fixture.mjs");

    @Test
    @DisplayName("小标一行字不裁不折，放不下整组换到名字下面一行")
    void pushTreeMarksStayOnOneLine() throws IOException, InterruptedException, URISyntaxException {
        URL url = PushTreeMarksTest.class.getClassLoader().getResource("config-ui/app.css");
        assertNotNull(url, "核心界面的 app.css 不在本模块测试的类路径上，这一格什么也没量");
        FrontendFixture.runFromModule(FIXTURE.toString(), "推送页左树小标",
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
