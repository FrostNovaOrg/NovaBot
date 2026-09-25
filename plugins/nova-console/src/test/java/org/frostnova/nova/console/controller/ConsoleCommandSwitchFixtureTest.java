package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 控制台「本群设置 · 命令」那一行的开关，显示的是不是真事
 * <p>
 * 一条命令底下分几种用法之后，群里「禁用命令」关掉的是用法，不是命令正名。
 * 页面还按正名对账的话，群里已经关掉的那几种问法在屏幕上仍亮着——
 * 使用者照着页面以为没关，发了却回「本群已关闭」，两头对不上而屏幕上一切正常。
 * 本机没开累计数据时，累计那一半也该锁住，而不是照旧摆一个拨得动的开关。
 * <p>
 * 夹具<b>真跑</b>：画开关那一段用的是产品码那一份。只核对回包里有没有某个字段是不行的——
 * 字段在、画错了，照样绿。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("命令开关按用法显示，与群内禁用对得上")
class ConsoleCommandSwitchFixtureTest {

    @Test
    @DisplayName("群里关掉哪几格，页面就显示关着哪几格；用不上的那格锁住")
    void consoleSwitchesFollowUsageKeys() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "console-command-switch-fixture.mjs").toAbsolutePath();
        if (!Files.exists(fixture)) {
            fail("夹具不见了，当时工作目录是 " + Path.of("").toAbsolutePath());
        }
        String relative = FrontendFixture.repoRoot().relativize(fixture).toString();
        FrontendFixture.run(relative, "命令开关按用法显示");
    }
}
