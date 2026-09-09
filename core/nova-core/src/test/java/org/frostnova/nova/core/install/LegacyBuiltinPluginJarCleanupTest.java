package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置插件改名后，安装脚本须清掉上一版旧名 jar，否则新旧两份会被一起加载。
 * 第三方插件不在表内，须原样留下。
 */
@DisplayName("安装脚本清理旧名内置插件 jar")
class LegacyBuiltinPluginJarCleanupTest {

    private static final String[] OLD_SUFFIXES = {
            "bilibili",
            "onebot-adapter",
            "onebot-adapter-napcat-extension",
            "report",
            "novabot-console"
    };

    private static final String LOOP =
            "for old in bilibili onebot-adapter onebot-adapter-napcat-extension report novabot-console; do";

    @Test
    @DisplayName("跑安装那段后只剩新名内置插件与第三方 jar")
    void leftoverOldBuiltinJarsAreRemovedAndThirdPartyKept(@TempDir Path tmp)
            throws IOException, InterruptedException {
        String install = read(repoRoot().resolve("install.sh"));
        String entry = read(repoRoot().resolve("dist/templates/docker-entrypoint.sh"));
        assertTrue(install.contains(LOOP) && install.contains("下一发行版删此表"),
                "install.sh 缺少旧名内置插件清理表");
        assertTrue(entry.contains(LOOP) && entry.contains("下一发行版删此表"),
                "docker-entrypoint.sh 缺少旧名内置插件清理表");

        Path srcPlugins = tmp.resolve("src/plugins");
        Path dstPlugins = tmp.resolve("dst/plugins");
        Files.createDirectories(srcPlugins);
        Files.createDirectories(dstPlugins);
        Files.createFile(dstPlugins.resolve("third-party-1.0.jar"));
        for (String old : OLD_SUFFIXES) {
            Files.createFile(dstPlugins.resolve("starbot-" + old + "-5.1.0.jar"));
            Files.createFile(srcPlugins.resolve(newArtifact(old) + "-5.3.0.jar"));
        }

        Path script = tmp.resolve("run-cleanup.sh");
        Files.writeString(script, """
                set -euo pipefail
                SRC="%s"
                DST="%s"
                for jar in "$SRC"/*.jar; do
                    [ -f "$jar" ] || continue
                    artifact="$(basename "$jar" | sed -E 's/-[0-9][^-]*\\.jar$//')"
                    case "$artifact" in
                        *.jar) continue ;;
                    esac
                    find "$DST" -maxdepth 1 -type f -name "$artifact-[0-9]*.jar" -delete
                done
                %s
                    find "$DST" -maxdepth 1 -type f -name "starbot-$old-[0-9]*.jar" -delete
                done
                cp -f "$SRC"/*.jar "$DST/"
                """.formatted(srcPlugins, dstPlugins, LOOP), StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("bash", script.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "清理脚本超时:\n" + output);
        assertEquals(0, process.exitValue(), "清理脚本退码非 0:\n" + output);

        Set<String> left;
        try (Stream<Path> stream = Files.list(dstPlugins)) {
            left = stream.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> expected = new TreeSet<>(List.of(
                "nova-bilibili-5.3.0.jar",
                "nova-onebot-adapter-5.3.0.jar",
                "nova-onebot-adapter-napcat-extension-5.3.0.jar",
                "nova-report-5.3.0.jar",
                "nova-console-5.3.0.jar",
                "third-party-1.0.jar"));
        assertEquals(expected, left, "安装那段跑完后目录里应只剩新名内置插件与第三方");
    }

    private static String newArtifact(String oldSuffix) {
        if ("novabot-console".equals(oldSuffix)) {
            return "nova-console";
        }
        return "nova-" + oldSuffix;
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

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
