package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据的值里再套着凭据键的形状。
 * <p>
 * 一行日志里出现「password=token=abc」这种里外都是凭据的键值，四条读路都要照常出页、
 * 整段遮成「键=掩码」；没见过的层层套形状一行都不许抛异常，凭据值一个都不许漏出去。
 * 分隔把引号吃进去、里层凭据键被外层的值连键带人吞进掩码的那一族也在这里：
 * 搜寻不落回值里再找一遍，它名下的值就漏在掩码外头。
 */
@DisplayName("工程日志打码：凭据值里套凭据键")
class EngineeringLogNestedCredentialTest {

    @TempDir
    Path dir;

    private final EngineeringLogService service = new EngineeringLogService();

    @Test
    @DisplayName("凭据的值里再套一个凭据键，四条读路照常出页，整段遮成键=掩码")
    void masksACredentialKeyNestedInsideACredentialValue() throws IOException {
        Path file = dir.resolve("starbot.log");
        Files.writeString(file, String.join(System.lineSeparator(),
                "2026-09-04 20:05:01.100  INFO 1 --- [main] x : 开机自检",
                "2026-09-04 20:06:01.100 ERROR 1 --- [main] x : 登录失败 password=token=abc",
                "2026-09-04 20:07:01.100 ERROR 1 --- [main] x : 回包带 SESSDATA=a:token=b",
                "2026-09-04 20:08:01.100 ERROR 1 --- [main] x : 换发 credential=SESSDATA=xyz",
                "2026-09-04 20:09:01.100  INFO 1 --- [main] x : 收尾") + System.lineSeparator(),
                StandardCharsets.UTF_8);

        List<String> tail = service.tail(file, 10).lines();
        assertEquals(5, tail.size(), "读尾部要照常出页：日志里夹这一行时，这一页默认就打不开");
        assertWholeCredentialMasked(tail.get(1), "password", "token=abc");
        assertWholeCredentialMasked(tail.get(2), "SESSDATA", "a:token=b");
        assertWholeCredentialMasked(tail.get(3), "credential", "SESSDATA=xyz");
        assertTrue(tail.get(0).contains("开机自检") && tail.get(4).contains("收尾"),
                "没夹凭据的行照常显示（读尾部）");

        List<String> since = service.since(file, 0L).lines();
        assertEquals(5, since.size(), "跟随要照常出页");
        assertWholeCredentialMasked(since.get(2), "SESSDATA", "a:token=b");

        EngineeringLogService.Window window = service.around(file, LocalTime.of(20, 7), 1);
        assertEquals(3, window.lines().size(), "定位要照常出页");
        assertWholeCredentialMasked(window.lines().get(window.highlight()), "SESSDATA", "a:token=b");

        List<String> scan = service.scan(file, 10, Set.of("error")).lines();
        assertEquals(3, scan.size(), "整天回扫要照常出页");
        assertWholeCredentialMasked(scan.get(0), "password", "token=abc");
        assertWholeCredentialMasked(scan.get(1), "SESSDATA", "a:token=b");
        assertWholeCredentialMasked(scan.get(2), "credential", "SESSDATA=xyz");
    }

    @Test
    @DisplayName("分隔把引号吃进去时，被吞进掩码的那个凭据键名下的值照样要遮")
    void masksTheValueClaimedByAKeySwallowedBehindAQuotedSeparator() throws IOException {
        Path file = dir.resolve("starbot.log");
        Files.writeString(file, String.join(System.lineSeparator(),
                "2026-09-04 20:05:01.100  INFO 1 --- [main] x : 开机自检",
                "2026-09-04 20:06:01.100 ERROR 1 --- [main] x : 登录 x=token\":secret = FAKEv",
                "2026-09-04 20:06:02.100 ERROR 1 --- [main] x : 回包 k=auth\":password: FAKEv",
                "2026-09-04 20:06:03.100 ERROR 1 --- [main] x : 记事 note=token\" : password = FAKEv",
                "2026-09-04 20:06:04.100 ERROR 1 --- [main] x : 配置 x=token\"=secret : FAKEv",
                "2026-09-04 20:06:05.100 ERROR 1 --- [main] x : 载入 cfg=\"auth:password = FAKEv\"",
                "2026-09-04 20:07:01.100  INFO 1 --- [main] x : 收尾") + System.lineSeparator(),
                StandardCharsets.UTF_8);

        // 引号贴着凭据键、再接分隔：里头那个凭据键被外层的值连键带人吞掉，它名下的
        // 值落在了掩码外头——正是回扫落在值尾之后漏出去的那一族形状
        List<String> tail = service.tail(file, 10).lines();
        assertEquals(7, tail.size(), "读尾部要照常出页");
        assertNoLeakedValue(tail, "读尾部");
        List<String> since = service.since(file, 0L).lines();
        assertEquals(7, since.size(), "跟随要照常出页");
        assertNoLeakedValue(since, "跟随");
        EngineeringLogService.Window window = service.around(file, LocalTime.of(20, 6), 5);
        assertEquals(7, window.lines().size(), "定位要照常出页");
        assertNoLeakedValue(window.lines(), "定位");
        List<String> scan = service.scan(file, 10, Set.of("error")).lines();
        assertEquals(5, scan.size(), "整天回扫要照常出页");
        assertNoLeakedValue(scan, "整天回扫");
    }

    @Test
    @DisplayName("五种层层套凭据的长行, 各量一趟, 都该一眨眼过")
    void masksDeeplyLayeredCredentialLinesQuickly() {
        // 凭据的值里每一层都再套一个凭据键、整段一路吃到行尾：搜寻落回值里之后，套着的
        // 那一层不许再把剩下的整段值从头量一遍——那样 16 万字一行就要等上几秒，一页里
        // 夹上几十行这样的行，日志页一两分钟都打不开
        List<String> units = List.of(
                "password=token=",
                "x:token:",
                "password=\"token=",
                "x : token : ",
                "SESSDATA=a:");
        List<String> problems = new ArrayList<>();
        for (String unit : units) {
            StringBuilder line = new StringBuilder(unit);
            while (line.length() < 160_000) {
                line.append(unit);
            }
            line.append("FAKEdeep");
            long start = System.nanoTime();
            String masked = EngineeringLogService.mask(line.toString());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.println("层层套凭据 " + line.length() + " 字（单元 " + unit + "…），打码实测 " + elapsedMs + " 毫秒");
            if (masked.contains("FAKEdeep")) {
                problems.add("层层套的凭据值漏出去了（单元 " + unit + "）");
            }
            if (masked.contains(EngineeringLogService.MASK + EngineeringLogService.MASK)) {
                problems.add("一段值出了连排的两道掩码（单元 " + unit + "），遮成开头："
                        + masked.substring(0, Math.min(40, masked.length())));
            }
            if (elapsedMs >= 50) {
                problems.add("16 万字层层套一行本该一眨眼，实测 " + elapsedMs + " 毫秒（单元 " + unit + "）");
            }
        }
        assertTrue(problems.isEmpty(), String.join("；", problems));
    }

    @Test
    @DisplayName("层层套凭据的一段值只出一道掩码, 掩码道数不随层数涨")
    void emitsASingleMaskForALayeredCredentialValue() {
        // 里外都是凭据的整段值由一道掩码盖到底：套几层就排几道的话，掩码的道数
        // 就把嵌套层数透了出去，输出最长还能涨到原文的一倍半
        assertEquals("password=" + EngineeringLogService.MASK,
                EngineeringLogService.mask("password=token=secret=credential=SESSDATA=bili_jct=abc"),
                "六层层层套该遮成一道掩码");
        assertEquals("token=" + EngineeringLogService.MASK,
                EngineeringLogService.mask("token=password=token=password=token=abc"),
                "层数换成五层，仍是一道掩码");
    }

    /** 凭据值一次都不许出现，掩码要真的下过手 */
    private static void assertNoLeakedValue(List<String> lines, String path) {
        for (String line : lines) {
            assertFalse(line.contains("FAKEv"), path + "上凭据值漏出去了：" + line);
        }
        assertTrue(String.join("\n", lines).contains(EngineeringLogService.MASK),
                path + "上一处掩码都见不到，像是没遮过");
    }

    @Test
    @DisplayName("几万行没见过的层层套形状：一行都不许抛异常，凭据值一个都不许漏")
    void neverThrowsOrLeaksOnUnseenLayeredShapes() {
        String marker = "Qz7MarkedValue";
        Random random = new Random(20260925L);
        List<String> lines = new ArrayList<>();
        Collections.addAll(lines,
                "token=token=abc123",
                "password=token=abc",
                "SESSDATA=a:token=b",
                "refresh_token=access_token=abc",
                "credential=SESSDATA=FAKE1",
                "password:token:abc",
                "x=token=a=token=b",
                "a=token=b=password=c");
        int longest = 0;
        for (int i = 0; i < 50000; i++) {
            String line = i % 500 == 499
                    ? deepLayeredLine(random, marker, 2000)
                    : randomLayeredLine(random, marker);
            longest = Math.max(longest, line.length());
            lines.add(line);
        }
        for (String line : lines) {
            String masked = EngineeringLogService.mask(line);
            assertFalse(masked.contains(marker), "凭据值漏出去了：" + line + " → " + masked);
        }
        System.out.println("层层套形状 " + lines.size() + " 行跑完，最长一行 " + longest + " 字，无一抛错、记号零出现");
    }

    /** 里外都按凭据遮：键名留着、值整段换成掩码、原文不露 */
    private static void assertWholeCredentialMasked(String line, String key, String raw) {
        assertTrue(line.contains(key + "=" + EngineeringLogService.MASK),
                "该遮成「键=掩码」：" + line);
        assertFalse(line.contains(raw), "值不许原样露出：" + line);
    }

    private static final String[] CRED_KEYS = {
            "token", "password", "credential", "secret", "access_token", "refresh_token", "csrf", "auth"};
    private static final String[] PLAIN_KEYS = {"x", "note", "value", "data", "level", "user", "room", "abc"};
    private static final String[] SEPS = {"=", ":", ": ", " = ", "=\""};
    // 值段里的连接符只有 = 与 ：——裸引号会结束外层凭据的值段，那种位置的值前后都不会遮；
    // 引号紧贴着键、后头再接 = 或 ： 的那一族不在此列，见 QUOTE_LINKS
    private static final String[] LINKS = {"=", ":"};
    // 键尾带引号、再接分隔的那一形态：分隔把引号吃进去，紧跟着的凭据键被外层的值
    // 连键带人吞掉——它名下的值要等搜寻落回掩码里，才轮得到按自己的名字被遮
    private static final String[] QUOTE_LINKS = {"\"=", "\":", "\" =", "\" :"};
    // 带空格的分隔：外层的值在这里断开，断口后头跟着的那一对才是真正要认的键值对
    private static final String[] SPACE_SEPS = {" = ", " : ", " =", " :", "= ", ": "};
    private static final String[] JUNKS = {"abc123", "0x1f", "hello", "true", "null", "2233", "9f8e", "v1", "a=b", "x:y", "e=m"};
    private static final String[] DELIMS = {" ", ", ", ";", " & ", "?"};

    /** 一行：可选的时刻头，接几段键值，段与段之间用会结束值段的分隔隔开 */
    private static String randomLayeredLine(Random random, String marker) {
        StringBuilder line = new StringBuilder();
        if (random.nextInt(5) < 2) {
            line.append("2026-09-04 20:0").append(random.nextInt(10)).append(":01.100 ")
                    .append(random.nextBoolean() ? "ERROR" : " INFO ").append(" 1 --- [main] x : ");
        }
        int segments = 1 + random.nextInt(5);
        for (int s = 0; s < segments; s++) {
            if (s > 0) {
                line.append(DELIMS[random.nextInt(DELIMS.length)]);
            }
            appendSegment(line, random, marker);
        }
        return line.toString();
    }

    /** 一行只有一段凭据键值，但值里层层套到很深，专量长行也不抛、不漏 */
    private static String deepLayeredLine(Random random, String marker, int depth) {
        StringBuilder line = new StringBuilder("2026-09-04 20:07:01.100 ERROR 1 --- [main] x : ");
        line.append(CRED_KEYS[random.nextInt(CRED_KEYS.length)]);
        line.append(SEPS[random.nextInt(SEPS.length)]);
        line.append(marker);
        for (int d = 0; d < depth; d++) {
            line.append(LINKS[random.nextInt(LINKS.length)]);
            appendPiece(line, random, marker);
        }
        return line.toString();
    }

    /** 一段键值：键普通或凭据；凭据段的值以统一记号开头，后头随机再套几层 */
    private static void appendSegment(StringBuilder into, Random random, String marker) {
        boolean credential = random.nextBoolean();
        into.append(credential
                ? CRED_KEYS[random.nextInt(CRED_KEYS.length)]
                : PLAIN_KEYS[random.nextInt(PLAIN_KEYS.length)]);
        into.append(SEPS[random.nextInt(SEPS.length)]);
        if (credential) {
            into.append(marker);
        }
        if (random.nextInt(4) == 0) {
            appendSwallowedPair(into, random, marker);
        }
        while (random.nextInt(3) == 0) {
            into.append(LINKS[random.nextInt(LINKS.length)]);
            appendPiece(into, random, marker);
        }
    }

    /** 一对被引号与分隔连在一起的凭据：前一个凭据键的值恰好是后一个凭据键的名字，
     * 后一个的值以统一记号开头——外层把前一对遮掉之后，要落回值里才找得到后一对 */
    private static void appendSwallowedPair(StringBuilder into, Random random, String marker) {
        into.append(CRED_KEYS[random.nextInt(CRED_KEYS.length)]);
        into.append(QUOTE_LINKS[random.nextInt(QUOTE_LINKS.length)]);
        into.append(CRED_KEYS[random.nextInt(CRED_KEYS.length)]);
        into.append(SPACE_SEPS[random.nextInt(SPACE_SEPS.length)]);
        into.append(marker);
    }

    /** 值段里的一块；碰到凭据键就立刻接上统一记号——记号只许出现在凭据的值里 */
    private static void appendPiece(StringBuilder into, Random random, String marker) {
        int kind = random.nextInt(8);
        if (kind < 4) {
            into.append(JUNKS[random.nextInt(JUNKS.length)]);
        } else if (kind < 6) {
            into.append(PLAIN_KEYS[random.nextInt(PLAIN_KEYS.length)]);
        } else {
            into.append(CRED_KEYS[random.nextInt(CRED_KEYS.length)]);
            into.append(LINKS[random.nextInt(LINKS.length)]);
            into.append(marker);
        }
    }
}
