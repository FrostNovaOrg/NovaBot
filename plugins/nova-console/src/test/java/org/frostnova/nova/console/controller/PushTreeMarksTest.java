package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 推送页左树：群名后面的小标不许被挤成一字一行的竖条
 * <p>
 * 左栏 236px 宽，群名带两颗小标（「N 类关着」「N 条命令被关」）摆不下同一行；从前那组小标
 * 封顶 96px，两颗挤进去字被压成一两个字一行竖着排。夹具按字面宽算左树各行，量的是
 * 「一颗小标一行字、放不下整组换到名字下面一行并与名字左对齐、名字截断也留四五个字」。
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
    void pushTreeMarksStayOnOneLine() throws IOException, InterruptedException {
        String fromRoot = FrontendFixture.repoRoot().relativize(FIXTURE.toAbsolutePath().normalize()).toString();
        FrontendFixture.run(fromRoot, "推送页左树小标");
    }
}
