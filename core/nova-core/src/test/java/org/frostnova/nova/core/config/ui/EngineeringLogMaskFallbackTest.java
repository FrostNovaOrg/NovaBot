package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打码自己出了错时，那一行整行换成掩码照出，别的行照常。
 * <p>
 * 判法认不出的形状永远会比名单多，而「看日志」这件事不能跟着判法一起停摆——恰恰是出了事
 * 的时候最需要打开这一页。一行打不出来就把那一行整行换掉；真会漏的东西换成了掩码，只是
 * 那一行剩下的排障线索也没了，所以只在打码抛异常时才发生。
 */
@DisplayName("工程日志打码兜底")
class EngineeringLogMaskFallbackTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("打码对某一行必抛错时，四条路都照常出页：那一行整行是掩码，别的行原样")
    void swapsTheWholeLineForTheMaskWhenMaskingItselfBlowsUp() throws IOException {
        Path file = dir.resolve("starbot.log");
        Files.writeString(file, String.join(System.lineSeparator(),
                "2026-09-04 20:05:01.100  INFO 1 --- [main] x : 开机自检",
                "2026-09-04 20:06:01.100 ERROR 1 --- [main] x : 事故现场 boom-sign",
                "2026-09-04 20:07:01.100  INFO 1 --- [main] x : 收尾") + System.lineSeparator(),
                StandardCharsets.UTF_8);

        EngineeringLogService broken = new EngineeringLogService() {
            @Override
            String maskForPage(String line) {
                if (line.contains("boom-sign")) {
                    throw new IllegalStateException("打码在这一行上必抛错");
                }
                return super.maskForPage(line);
            }
        };

        List<String> tail = broken.tail(file, 10).lines();
        assertEquals(EngineeringLogService.MASK, tail.get(1), "抛错那一行整行换掩码（读尾部）");
        assertTrue(tail.get(0).contains("开机自检") && tail.get(2).contains("收尾"), "别的行照常（读尾部）");

        List<String> since = broken.since(file, 0L).lines();
        assertEquals(EngineeringLogService.MASK, since.get(1), "抛错那一行整行换掩码（跟随）");
        assertTrue(since.get(0).contains("开机自检") && since.get(2).contains("收尾"), "别的行照常（跟随）");

        EngineeringLogService.Window window = broken.around(file, LocalTime.of(20, 6), 1);
        assertEquals(EngineeringLogService.MASK, window.lines().get(window.highlight()),
                "抛错那一行整行换掩码（定位）");
        assertTrue(window.lines().get(0).contains("开机自检") && window.lines().get(2).contains("收尾"),
                "别的行照常（定位）");

        List<String> scan = broken.scan(file, 10, Set.of("error")).lines();
        assertEquals(1, scan.size(), "整天回扫照常出页");
        assertEquals(EngineeringLogService.MASK, scan.get(0), "抛错那一行整行换掩码（整天回扫）");
    }
}
