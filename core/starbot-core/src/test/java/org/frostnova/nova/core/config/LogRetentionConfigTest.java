package org.frostnova.nova.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志保留：三棵日志树都得有保留期与体积兜底
 * <p>
 * 不写保留期时 logback <b>一天不删</b>，日志会一直堆到把盘写满；而这些文件里逐条带着
 * 观众昵称与 uid，堆着不只是占地方，还是在一台机器上越攒越多的旁人身份数据。
 * <p>
 * 🔴 <b>更容易被绕过的是另一条：{@code totalSizeCap} 必须与 {@code maxHistory} 同时写才生效。</b>
 * 只给上限、不给天数，logback 不抛异常也不影响启动，只在配置期的状态流里留一行
 * {@code 'maxHistory' is not set, ignoring 'totalSizeCap' option}（logback 1.5.21 实测），
 * 然后把上限当没看见。一行淹在启动噪声里的 WARN 拦不住它——
 * 与「留空的日志级别退回 DEBUG」是同一类失效：配置看着是对的，行为不是。
 * <p>
 * <b>钉什么、不钉什么</b>：天数钉死，因为两档制（排障资产 30 天、事件调试 7 天）是一个决定，
 * 依据是内容性质而非体积，改它该是明着改；上限只钉<b>在场</b>不钉数值，
 * 因为那个数按「当前日量 × 保留天数 × 10」随实测重算，钉死数值只会逼着后来人先改判据。
 */
@DisplayName("日志保留策略")
class LogRetentionConfigTest {
    /** 日志树目录 -> 该留几天。两档制：排障资产 30 天，事件调试 7 天 */
    private static final Map<String, Integer> EXPECTED_DAYS =
            Map.of("logs", 30, "NetworkDebug", 30, "EventDebug", 7);

    private static final Pattern ROLLING_POLICY = Pattern.compile(
            "<rollingPolicy[^>]*>(.*?)</rollingPolicy>", Pattern.DOTALL);
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "<fileNamePattern>\\$\\{LOG_HOME}/([^/]+)/");

    /** 一棵日志树读出来的三个格：目录、保留天数（缺则 null）、体积上限（缺则 null） */
    private record Tree(String directory, Integer maxHistory, String totalSizeCap) {
    }

    private static Map<String, Tree> treesOf(String xml) {
        Map<String, Tree> trees = new LinkedHashMap<>();
        Matcher policy = ROLLING_POLICY.matcher(xml);
        while (policy.find()) {
            String body = policy.group(1);
            Matcher name = FILE_NAME_PATTERN.matcher(body);
            if (!name.find()) {
                continue;
            }
            trees.put(name.group(1), new Tree(name.group(1),
                    valueOf(body, "maxHistory") == null ? null : Integer.valueOf(valueOf(body, "maxHistory")),
                    valueOf(body, "totalSizeCap")));
        }
        return trees;
    }

    private static String valueOf(String body, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(body);
        return m.find() ? m.group(1).strip() : null;
    }

    private Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private String logbackXml() throws IOException {
        return Files.readString(root().resolve("core/starbot-core/src/main/resources/logback.xml"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("判据自己先能认出漏写的那一格")
    void theRulerRecognisesAMissingSetting() {
        String missingBoth = """
                <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                    <fileNamePattern>${LOG_HOME}/logs/starbot-%d{yyyy-MM-dd}.log</fileNamePattern>
                </rollingPolicy>
                """;
        Tree bare = treesOf(missingBoth).get("logs");
        assertNotNull(bare, "认不出这棵树，下面几条判据就是恒真绿");
        assertEquals(null, bare.maxHistory(), "漏写保留期时必须读成 null，不能读成默认值");
        assertEquals(null, bare.totalSizeCap(), "漏写体积上限时必须读成 null");

        String capWithoutHistory = """
                <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                    <fileNamePattern>${LOG_HOME}/logs/starbot-%d{yyyy-MM-dd}.log</fileNamePattern>
                    <totalSizeCap>1GB</totalSizeCap>
                </rollingPolicy>
                """;
        Tree lonelyCap = treesOf(capWithoutHistory).get("logs");
        assertEquals(null, lonelyCap.maxHistory(), "只写上限不写天数时上限会被忽略，判据必须逮得住");
        assertEquals("1GB", lonelyCap.totalSizeCap());
    }

    @Test
    @DisplayName("三棵日志树一棵都不能漏")
    void everyLogTreeIsCovered() throws IOException {
        Map<String, Tree> trees = treesOf(logbackXml());
        assertEquals(EXPECTED_DAYS.keySet(), trees.keySet(),
                "日志树的数量或去向变了，保留策略要跟着重定，不能让新树裸奔: " + trees.keySet());
    }

    @Test
    @DisplayName("两档制：排障资产 30 天，事件调试 7 天")
    void retentionFollowsTheTwoTierPolicy() throws IOException {
        Map<String, Tree> trees = treesOf(logbackXml());
        EXPECTED_DAYS.forEach((directory, days) -> {
            Tree tree = trees.get(directory);
            assertNotNull(tree, "日志树 " + directory + " 不见了");
            assertEquals(days, tree.maxHistory(),
                    directory + " 的保留天数不对。分档依据是内容性质不是体积："
                            + "事件调试日志逐条带着观众身份，只留 7 天；排障资产留 30 天");
        });
    }

    @Test
    @DisplayName("每棵树都要有体积兜底，且必须与保留天数同时在场")
    void everyTreeHasASizeCap() throws IOException {
        treesOf(logbackXml()).forEach((directory, tree) -> {
            assertTrue(tree.totalSizeCap() != null && !tree.totalSizeCap().isEmpty(),
                    directory + " 少了 totalSizeCap：某天量突然翻十倍就会把盘写满");
            assertNotNull(tree.maxHistory(),
                    directory + " 写了 totalSizeCap 却没写 maxHistory。"
                            + "logback 此时只在配置期状态流里留一行 WARN，照常启动，把上限当没看见");
        });
    }
}
