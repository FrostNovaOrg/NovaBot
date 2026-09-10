package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.4 起日志文件名改用 novabot- 前缀、安装脚本恒装 novabot.service：
 * 双名期「沿用 starbot.service」分支删除，检测到旧 unit 时停用并换装。
 * 原 starbot-*.log 旧文件不再自动清理，可手动删除（CHANGELOG 有说明）。
 */
@DisplayName("日志名与服务名已去旧名")
class LegacyLogNameAndServiceUnitRemovedTest {

    @Test
    @DisplayName("logback 无 starbot- 且 novabot- 恰 3 处，install.sh 语法通过")
    void legacyLogNameAndServiceUnitAreRemoved() throws IOException, InterruptedException {
        List<String> failures = new ArrayList<>();

        // logback.xml 从源码目录读而不是从类路径读：install 那档构建会剥离它，
        // 类路径里那份是陈旧残留（同 QrCodeUtilTest 的理由）；也不在注释里写模块
        // 目录连串——新写模块路径要过边界尺格12 的在册账
        Path logback = Path.of("src/main/resources/logback.xml");
        Path install = repoRoot().resolve("install.sh");

        String text = Files.readString(logback, StandardCharsets.UTF_8);
        if (text.contains("starbot-")) {
            failures.add(logback + " 仍含 starbot-");
        }
        int current = text.split("novabot-", -1).length - 1;
        if (current != 3) {
            failures.add(logback + " novabot- 计数 " + current + "，应恰 3 处");
        }

        checkParses(failures, install);

        assertTrue(failures.isEmpty(),
                "日志名与服务名应已去旧名，红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    private static void checkParses(List<String> failures, Path script) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", "-n", script.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash -n 超时: " + script);
        if (process.exitValue() != 0) {
            failures.add("bash -n " + script + " 退码 " + process.exitValue() + ": "
                    + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.sh")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }
}
