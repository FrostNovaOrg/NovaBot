package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 超长输入下的三条读路：整份全是续行、单独一行几十 MB
 * <p>
 * 这几格要现造上百 MB 的文件、并压着很小的堆跑，放进常规整盘会拖慢每一趟构建，
 * 因此默认跳过，收尾实测那一趟用 {@code -Dengineering-log.huge=true -DargLine=-Xmx128m} 打开。
 * <p>
 * 打开的那趟量的是「会不会把内存吃光」。日志行的长度没有上限，而攒着日志的那台机器
 * 不一定有富余的内存——小堆上跑得完，才是真的跑得完。
 */
@DisplayName("工程日志超长输入")
class EngineeringLogHugeInputTest {
    private static final boolean ENABLED = Boolean.getBoolean("engineering-log.huge");

    @TempDir
    Path dir;

    @Test
    @DisplayName("小堆下翻一整份认不出几处行首的日志")
    void scansAWholeDayOfContinuationLines() throws IOException {
        assumeTrue(ENABLED, "默认跳过：收尾实测那一趟才打开");

        Path log = dir.resolve("all-continuation.log");
        byte[] head = "2026-09-25 09:00:00.000 ERROR 1 --- [main] o.f.n.demo err-at-the-top\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] frame = "\tat com.example.DeepFrame.run(DeepFrame.java:99)\n"
                .getBytes(StandardCharsets.UTF_8);
        long target = 200L * 1024 * 1024;
        try (OutputStream out = Files.newOutputStream(log)) {
            out.write(head);
            long written = head.length;
            while (written < target) {
                out.write(frame);
                written += frame.length;
            }
        }

        long start = System.nanoTime();
        List<String> lines = new EngineeringLogService().scan(log, 50, Set.of("error")).lines();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        String text = String.join("\n", lines);
        System.out.println("小堆上扫 200MB 全是续行的日志，实测 " + elapsedMs + " 毫秒，带回 "
                + lines.size() + " 行");
        assertTrue(lines.size() <= EngineeringLogService.MAX_CONT_LINES + 2,
                "留下的续行不许超过上限；得到 " + lines.size() + " 行");
        assertTrue(text.contains("err-at-the-top"), "翻到头那一条错误要找得回来");
        assertTrue(elapsedMs < 120_000, "宽裕的耗时上限也超了：实测 " + elapsedMs + " 毫秒");
    }

    @Test
    @DisplayName("小堆下单独一行 64MB：回扫、尾读、定位三条路")
    void readsPastOneHugeLine() throws IOException {
        assumeTrue(ENABLED, "默认跳过：收尾实测那一趟才打开");

        Path log = dir.resolve("one-huge-line.log");
        byte[] pad = "#".repeat(256 * 1024).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(log)) {
            out.write("2026-09-25 20:05:00.000  INFO 1 --- [main] x : 前一行\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("2026-09-25 20:07:00.000 ERROR 1 --- [main] x : err-huge "
                    .getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 256; i++) {
                out.write(pad);
            }
            out.write("\n2026-09-25 20:09:00.000  INFO 1 --- [main] x : 后一行\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        EngineeringLogService service = new EngineeringLogService();

        long scanStart = System.nanoTime();
        List<String> scanned = service.scan(log, 50, Set.of("error")).lines();
        long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
        System.out.println("小堆上回扫含 64MB 单行的日志，实测 " + scanMs + " 毫秒");
        assertTrue(String.join("\n", scanned).contains("err-huge"), "那一行要找得回来");
        assertTrue(scanned.get(0).length() <= EngineeringLogService.MAX_LINE_BYTES + 40,
                "回扫只留开头一段；得到 " + scanned.get(0).length() + " 字");
        assertTrue(scanned.get(0).contains("已截去"), "截掉了多少要写明");
        assertTrue(scanMs < 120_000, "宽裕的耗时上限也超了：实测 " + scanMs + " 毫秒");

        long tailStart = System.nanoTime();
        List<String> tail = service.tail(log, 10).lines();
        long tailMs = (System.nanoTime() - tailStart) / 1_000_000;
        System.out.println("小堆上尾读含 64MB 单行的日志，实测 " + tailMs + " 毫秒");
        assertTrue(tail.get(tail.size() - 1).contains("后一行"),
                "最后那条完整行要照常给出来");
        assertTrue(tailMs < 120_000, "宽裕的耗时上限也超了：实测 " + tailMs + " 毫秒");

        long aroundStart = System.nanoTime();
        EngineeringLogService.Window window = service.around(log, LocalTime.of(20, 7), 2);
        long aroundMs = (System.nanoTime() - aroundStart) / 1_000_000;
        System.out.println("小堆上定位含 64MB 单行的日志，实测 " + aroundMs + " 毫秒");
        String clipped = window.lines().get(window.highlight());
        assertTrue(clipped.contains("err-huge"), "开头那一段要留住");
        assertTrue(clipped.length() <= EngineeringLogService.MAX_LINE_BYTES + 40,
                "定位只留开头一段；得到 " + clipped.length() + " 字");
        assertTrue(clipped.contains("已截去"), "截掉了多少要写明");
        assertTrue(aroundMs < 120_000, "宽裕的耗时上限也超了：实测 " + aroundMs + " 毫秒");
    }
}
