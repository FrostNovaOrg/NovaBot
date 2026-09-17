package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 视图模型尺的阴性对照必须活着：整块注释、只注释红句都要被判不活，注释里的假对照不算数。
 */
@DisplayName("视图模型尺阴性对照")
class ModelCheckerNegativeControlTest {

    private static final String BLOCK_HEADER = "# —— 阴性对照：这一格自己得先证明它分得出红绿 ——";
    private static final String RED_ECHO = "echo \"阴性对照 红";
    private static final String GREEN_ECHO = "echo \"阴性对照 绿";

    /**
     * 非注释行里含红／绿阴性对照 echo 的行数。非注释行＝去掉行首空白后第一个字符不是 {@code #}。
     * 两数都恰为 1 才算这把尺的对照活着。
     */
    static int[] countNegativeControlEchoes(String source) {
        int red = 0;
        int green = 0;
        for (String line : source.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (!trimmed.isEmpty() && trimmed.charAt(0) == '#') {
                continue;
            }
            if (line.contains(RED_ECHO)) {
                red++;
            }
            if (line.contains(GREEN_ECHO)) {
                green++;
            }
        }
        return new int[] { red, green };
    }

    @Test
    @DisplayName("十三把尺阴性对照活着：整块注释、假对照、只注释红句")
    void negativeControlsStayAliveUnderThreeMutations() {
        List<String> red = new ArrayList<>();
        List<Path> checkers;
        try {
            checkers = listModelCheckers();
        } catch (IOException e) {
            fail("列尺失败: " + e.getMessage());
            return;
        }

        try {
            if (checkers.size() < 13) {
                fail("列到的尺 " + checkers.size() + " 把，应 ≥ 13");
            }
            List<String> dead = new ArrayList<>();
            for (Path checker : checkers) {
                int[] counts = countNegativeControlEchoes(read(checker));
                if (counts[0] != 1 || counts[1] != 1) {
                    dead.add(named(checker, counts));
                }
            }
            if (!dead.isEmpty()) {
                fail("对照不活: " + String.join("、", dead));
            }
        } catch (Throwable e) {
            red.add("① " + message(e));
        }

        try {
            List<String> missingHeader = new ArrayList<>();
            List<String> stillAlive = new ArrayList<>();
            for (Path checker : checkers) {
                String mutated = commentOutNegativeControlBlock(read(checker));
                if (mutated == null) {
                    missingHeader.add(checker.getFileName().toString());
                    continue;
                }
                int[] counts = countNegativeControlEchoes(mutated);
                if (counts[0] == 1 && counts[1] == 1) {
                    stillAlive.add(checker.getFileName().toString());
                }
            }
            if (!missingHeader.isEmpty()) {
                fail("未见块头: " + String.join("、", missingHeader));
            }
            if (!stillAlive.isEmpty()) {
                fail("整块注释后仍判活: " + String.join("、", stillAlive));
            }
        } catch (Throwable e) {
            red.add("② " + message(e));
        }

        try {
            List<String> changed = new ArrayList<>();
            for (Path checker : checkers) {
                String source = read(checker);
                int[] original = countNegativeControlEchoes(source);
                String mutated = source
                        + "\n# echo \"阴性对照 绿（注释里的假对照）\""
                        + "\n    # echo \"阴性对照 红（缩进的注释）\" >&2";
                int[] after = countNegativeControlEchoes(mutated);
                if (after[0] != original[0] || after[1] != original[1]) {
                    changed.add(checker.getFileName()
                            + " 原文红=" + original[0] + " 绿=" + original[1]
                            + " 突变后红=" + after[0] + " 绿=" + after[1]);
                }
            }
            if (!changed.isEmpty()) {
                fail("注释假对照改变读数: " + String.join("、", changed));
            }
        } catch (Throwable e) {
            red.add("③ " + message(e));
        }

        try {
            List<String> stillAlive = new ArrayList<>();
            for (Path checker : checkers) {
                int[] counts = countNegativeControlEchoes(commentOutRedEchoLine(read(checker)));
                if (counts[0] == 1 && counts[1] == 1) {
                    stillAlive.add(checker.getFileName().toString());
                }
            }
            if (!stillAlive.isEmpty()) {
                fail("只注释红句后仍判活: " + String.join("、", stillAlive));
            }
        } catch (Throwable e) {
            red.add("④ " + message(e));
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    static List<Path> listModelCheckers() throws IOException {
        Path tools = repoRoot().resolve("tools");
        if (!Files.isDirectory(tools)) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(tools)) {
            return listed
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("-model-check.sh"))
                    .sorted()
                    .toList();
        }
    }

    private static String commentOutNegativeControlBlock(String source) {
        String[] lines = source.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals(BLOCK_HEADER)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = lines.length;
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].startsWith("# —— ")) {
                end = i;
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (i >= start && i < end) {
                out.append("# ");
            }
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static String commentOutRedEchoLine(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (lines[i].contains(RED_ECHO)) {
                out.append("# ");
            }
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String named(Path checker, int[] counts) {
        return checker.getFileName() + " 红=" + counts[0] + " 绿=" + counts[1];
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
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
