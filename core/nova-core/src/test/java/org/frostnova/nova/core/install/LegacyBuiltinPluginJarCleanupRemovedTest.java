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
 * 5.4 起两脚本不再清理旧名内置插件 jar：构件改名已隔一个发行版，双名期的清理表
 * 与「下一发行版删此表」注释一并删除。从 5.2 或更早直接升级到本版的使用者，
 * 请先手动删除 plugins/ 下的 starbot-*.jar（CHANGELOG 有说明）。
 */
@DisplayName("安装脚本已删旧名内置插件清理表")
class LegacyBuiltinPluginJarCleanupRemovedTest {

    @Test
    @DisplayName("两脚本均无清理表字样，且 bash -n 通过")
    void cleanupTableIsRemovedFromBothScripts() throws IOException, InterruptedException {
        List<String> failures = new ArrayList<>();

        Path root = repoRoot();
        Path install = root.resolve("install.sh");
        Path entry = root.resolve("dist/templates/docker-entrypoint.sh");

        checkAbsent(failures, install);
        checkAbsent(failures, entry);
        checkParses(failures, install);
        checkParses(failures, entry);

        assertTrue(failures.isEmpty(),
                "旧名内置插件清理表应已删净，红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    private static void checkAbsent(List<String> failures, Path script) throws IOException {
        String text = Files.readString(script, StandardCharsets.UTF_8);
        if (text.contains("starbot-$old")) {
            failures.add(script + " 仍含 starbot-$old");
        }
        if (text.contains("下一发行版删此表")) {
            failures.add(script + " 仍含「下一发行版删此表」注释");
        }
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
