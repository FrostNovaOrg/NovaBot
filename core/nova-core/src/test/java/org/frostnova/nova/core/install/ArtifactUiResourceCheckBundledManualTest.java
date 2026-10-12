package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物尺「随包手册」一格分得清「这次没带」与「链断了」
 * <p>
 * 抓的用户故障：没装 python3 的机器上，构建按规矩不带随包手册（控制台的「使用说明」
 * 退到线上手册）；这把尺若把「整块缺席」一律判红，那种构建就永远过不了产物这一关，
 * 于是有人会把这一格拆了——拆掉之后「生成过手册、包里却一项没有」那种静默丢链也没人
 * 量了，版本对不上的老手册就是这么回来的。
 * <p>
 * 这里在沙箱里搭一个能被这把尺量完的最小产物（核心一件界面、一个登记了一页的插件、
 * NovaBot.jar 用真的 zip 写），只动 manual/dist 与 jar 里手册条目的有无，看四种形态
 * 各判成什么。
 */
@DisplayName("产物尺「随包手册」格：这次没带不红，生成过而没有才红")
class ArtifactUiResourceCheckBundledManualTest {

    /** 没生成手册时那一格印的说法。尺改了措辞这里就红——这句正是使用者要读到的那句 */
    private static final String SKIPPED_LINE = "这次没带手册";

    @Test
    @DisplayName("生成了手册、包里也带着：绿，项数对得上")
    void generatedManualInJarIsGreen(@TempDir Path sandbox) throws Exception {
        Path out = scaffold(sandbox, true, "index.html", "manual.css");

        Result result = runCheck(sandbox, out);

        assertEquals(0, result.exit, "该判绿，输出:\n" + result.output);
        assertTrue(result.output.contains("随包手册 绿 jar内2项"), "手册那一格没报绿，输出:\n" + result.output);
    }

    @Test
    @DisplayName("生成了手册、包里一项没有：红——打进包的那条链断了")
    void generatedManualMissingFromJarIsRed(@TempDir Path sandbox) throws Exception {
        Path out = scaffold(sandbox, true);

        Result result = runCheck(sandbox, out);

        assertEquals(1, result.exit, "该判红（退码 1），实际退码 " + result.exit + "，输出:\n" + result.output);
        assertTrue(result.output.contains("随包手册 红"), "手册那一格没报红，输出:\n" + result.output);
        assertTrue(result.output.contains("一个都没打进包"), "红的那句没说清是整块没进包，输出:\n" + result.output);
    }

    @Test
    @DisplayName("这次没生成手册（manual/dist 不在）、包里也没有：不红，说清这次没带")
    void skippedManualIsNotRedWhenNothingWasGenerated(@TempDir Path sandbox) throws Exception {
        Path out = scaffold(sandbox, false);

        Result result = runCheck(sandbox, out);

        assertEquals(0, result.exit, "按规矩跳过的那次构建不该被判红，输出:\n" + result.output);
        assertTrue(result.output.contains(SKIPPED_LINE), "没说清这次没带手册，输出:\n" + result.output);
        assertFalse(result.output.contains("随包手册 红"), "这次没带被当成了丢链，输出:\n" + result.output);
    }

    @Test
    @DisplayName("没生成手册、包里却混着手册条目：红——那是上一次构建的残留")
    void staleManualEntriesWithoutSourceAreRed(@TempDir Path sandbox) throws Exception {
        Path out = scaffold(sandbox, false, "index.html");

        Result result = runCheck(sandbox, out);

        assertEquals(1, result.exit, "该判红（退码 1），实际退码 " + result.exit + "，输出:\n" + result.output);
        assertTrue(result.output.contains("随包手册 红"), "手册那一格没报红，输出:\n" + result.output);
        assertTrue(result.output.contains("残留"), "红的那句没说清条目是残留，输出:\n" + result.output);
    }

    /**
     * 搭一个能被这把尺从头量到尾的最小产物树：tools/ 里放真的尺（两把脚本照拷，
     * 不在仓库本体上跑）、核心界面一件、一个登记了一页的插件模块；manual/dist 与
     * jar 里的手册条目按场景给。
     */
    private static Path scaffold(Path sandbox, boolean withManualDist, String... jarManualEntries)
            throws IOException {
        Path tools = Files.createDirectories(sandbox.resolve("tools"));
        Files.copy(repoRoot().resolve("tools").resolve("artifact-ui-resource-check.sh"),
                tools.resolve("artifact-ui-resource-check.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoRoot().resolve("tools").resolve("console-page-registry.sh"),
                tools.resolve("console-page-registry.sh"), StandardCopyOption.REPLACE_EXISTING);

        Path coreUi = Files.createDirectories(sandbox.resolve("core/nova-core/src/main/resources/config-ui"));
        Files.writeString(coreUi.resolve("index.html"), "<html>console</html>\n", StandardCharsets.UTF_8);

        Files.writeString(Files.createDirectories(sandbox.resolve("plugins/mymod")).resolve("pom.xml"),
                "<project/>", StandardCharsets.UTF_8);
        Path main = Files.createDirectories(sandbox.resolve("plugins/mymod/src/main/java"));
        Files.writeString(main.resolve("Demo.java"),
                "class Demo { String page = \"config-ui-pages/page.js\"; }", StandardCharsets.UTF_8);

        if (withManualDist) {
            Path dist = Files.createDirectories(sandbox.resolve("manual/dist"));
            Files.writeString(dist.resolve("index.html"), "<html>manual</html>\n", StandardCharsets.UTF_8);
            Files.writeString(dist.resolve("manual.css"), "body{}\n", StandardCharsets.UTF_8);
        }

        Path out = Files.createDirectories(sandbox.resolve("out"));
        Files.createDirectories(out.resolve("plugins"));
        Map<String, String> coreEntries = new LinkedHashMap<>();
        coreEntries.put("BOOT-INF/classes/config-ui/index.html", "<html>console</html>\n");
        for (String name : jarManualEntries) {
            coreEntries.put("BOOT-INF/classes/config-ui-manual/" + name, "<html>manual</html>\n");
        }
        zip(out.resolve("NovaBot.jar"), coreEntries);
        Map<String, String> pluginEntries = new LinkedHashMap<>();
        pluginEntries.put("config-ui-pages/page.js", "// page\n");
        zip(out.resolve("plugins/mymod-1.0.0.jar"), pluginEntries);
        return out;
    }

    private record Result(int exit, String output) {
    }

    private static Result runCheck(Path sandbox, Path out) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash",
                sandbox.resolve("tools").resolve("artifact-ui-resource-check.sh").toString(),
                out.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "尺跑了 60 秒还没结束:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private static void zip(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(file);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(os)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
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
