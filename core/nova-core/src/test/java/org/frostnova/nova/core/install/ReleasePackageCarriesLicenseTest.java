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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发行包顶层带 LICENSE 与 NOTICE
 * <p>
 * 发行包里有随程序发布的字体，这些字体的许可要求每一份拷贝都附上版权声明与许可；
 * 本项目自身的许可证也该跟着程序走，而不是只留在源码仓库里。
 * 发行包是 build.sh 把产物目录整个打成 tar 得到的，所以两份文件要在打包那一行之前拷进产物目录顶层。
 */
@DisplayName("发行包顶层带 LICENSE 与 NOTICE")
class ReleasePackageCarriesLicenseTest {
    /** 打包那一行：把产物目录整个压成 tar */
    private static final Pattern PACK_LINE = Pattern.compile("^\\s*(?:\\w+=\\S+\\s+)*tar\\s.*-C\\s+\"\\$OUT\"\\s+\\.\\s*$");

    @Test
    @DisplayName("build.sh 在打包之前把 LICENSE 与 NOTICE 拷进产物目录顶层")
    void buildScriptCopiesLicenseAndNoticeBeforePacking() throws IOException, InterruptedException {
        Path root = repoRoot();
        Path script = root.resolve("build.sh");
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);

        int packLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (PACK_LINE.matcher(lines.get(i)).matches()) {
                packLine = i;
                break;
            }
        }
        assertTrue(packLine >= 0, "锚: build.sh 里找不到把产物目录打成 tar 的那一行, 这条检查没对准");

        List<String> failures = new ArrayList<>();
        for (String file : List.of("LICENSE", "NOTICE")) {
            if (!Files.isRegularFile(root.resolve(file))) {
                failures.add("仓库根目录没有 " + file);
            }
            boolean copied = lines.subList(0, packLine).stream().anyMatch(line -> copiesIntoOutput(line, file));
            if (!copied) {
                failures.add("build.sh 第 " + (packLine + 1) + " 行打包之前没有把 " + file + " 拷进 \"$OUT/\"");
            }
        }
        checkParses(failures, script);

        assertTrue(failures.isEmpty(), "发行包缺许可文件, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 一行未注释掉的 {@code cp}，参数里有这个文件，目标是产物目录顶层
     */
    private static boolean copiesIntoOutput(String line, String file) {
        String code = line.strip();
        if (!code.startsWith("cp ")) {
            return false;
        }
        List<String> words = List.of(code.split("\\s+"));
        return words.subList(1, words.size() - 1).contains(file)
                && List.of("\"$OUT/\"", "\"$OUT\"", "$OUT/", "$OUT").contains(words.get(words.size() - 1));
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
