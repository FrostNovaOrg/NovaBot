package org.frostnova.nova.core.safemode;

import org.frostnova.nova.core.config.ui.TimestampedFileBackup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全模式保存配置时的带时间戳备份
 * <p>
 * 保留份数尽量照 yml 里配的走（novabot.core.config-ui.backup-keep），读不到时
 * 才退回组件默认份数——这里钉住的是「照配置走、读不到有兜底」这件事本身。
 */
@DisplayName("安全模式保存配置的备份")
class SafeModeServerBackupTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("经保存路径写一次：备份目录多出一份且名与组件同形")
    void applySaveCreatesAComponentShapedBackup() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        assertEquals(0, stampedBackupNames(config).size(), "保存前不应已有带戳备份");
        assertEquals(null, server.applySave("ok: true\n"), "合法 YAML 应保存成功");

        List<String> names = stampedBackupNames(config);
        assertEquals(1, names.size(), "经保存路径写一次应多出一份备份");
        assertTrue(names.get(0).matches("application\\.yml\\.\\d{8}-\\d{6}(-\\d+)?\\.bak"),
                "备份名应与组件生成的同形: " + names.get(0));
        assertEquals("ok: true\n", Files.readString(config, StandardCharsets.UTF_8),
                "本体应是这次保存的内容");
    }

    @Test
    @DisplayName("连存 12 次：备份名与组件同形，只留 10 份")
    void safeModeBackupsShareTheComponentShapeAndKeepTen() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        for (int i = 0; i < 12; i++) {
            Files.writeString(config, "v" + i, StandardCharsets.UTF_8);
            server.backupBeforeSave();
        }

        List<String> names = stampedBackupNames(config);
        assertEquals(10, names.size(), "安全模式的保存也应按保留份数清旧备份");
        assertTrue(Files.exists(config), "配置本体必须在盘上");
        assertEquals("v11", Files.readString(config, StandardCharsets.UTF_8), "本体内容应仍是最后一次写入");
        for (String name : names) {
            assertTrue(name.matches("application\\.yml\\.\\d{8}-\\d{6}(-\\d+)?\\.bak"),
                    "备份名应与组件生成的同形: " + name);
        }
    }

    @Test
    @DisplayName("配置写 backup-keep: 3：连存 5 次只留 3 份")
    void safeModeBackupsFollowConfiguredKeep() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: 3
                """, StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        for (int i = 0; i < 5; i++) {
            server.backupBeforeSave();
        }

        List<String> names = stampedBackupNames(config);
        assertEquals(3, names.size(), "配置写的保留份数应被采用, 实际留了 " + names.size() + " 份");
    }

    @Test
    @DisplayName("配置写 backup-keep: 500：按 1–100 收口，连存 101 次恰留 100 份，返回值也收口到 100")
    void safeModeBackupsClampOversizedKeep() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: 500
                """, StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        for (int i = 0; i < 101; i++) {
            server.backupBeforeSave();
        }

        List<String> names = stampedBackupNames(config);
        assertEquals(TimestampedFileBackup.MAX_KEEP, names.size(),
                "超界的保留份数应按上限 " + TimestampedFileBackup.MAX_KEEP + " 收口, 实际留了 " + names.size() + " 份");
        assertEquals(TimestampedFileBackup.MAX_KEEP, server.resolveBackupKeep(),
                "resolveBackupKeep 应把 500 收口到 " + TimestampedFileBackup.MAX_KEEP + " 再返回, 实际回了 " + server.resolveBackupKeep());
    }

    @Test
    @DisplayName("backup-keep 收口断返回值：500→100、0→1、-1→1，界上 100 与界下 1 照用")
    void resolveBackupKeepClampsReturnValue() throws IOException {
        List<String> unresolved = new ArrayList<>();
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: 500
                """, 100, "500 应按上限 100 收口");
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: 0
                """, 1, "0 应按下限 1 收口");
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: -1
                """, 1, "-1 应按下限 1 收口");
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: 100
                """, 100, "阳性对照: 界上沿 100 照用");
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: 1
                """, 1, "阳性对照: 界下沿 1 照用");

        assertTrue(unresolved.isEmpty(),
                () -> "份数收口五问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    @Test
    @DisplayName("backup-keep 认带引号数字与驼峰键：\"5\"→5、configUi.backupKeep 5→5 与 \"7\"→7、读不到回默认 10")
    void resolveBackupKeepReadsQuotedNumberAndCamelCaseKey() throws IOException {
        List<String> unresolved = new ArrayList<>();
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: "5"
                """, 5, "带引号的 5 应被认出");
        askKeep(unresolved, """
                novabot:
                  core:
                    configUi:
                      backupKeep: 5
                """, 5, "驼峰键 configUi.backupKeep 的 5 应被认出");
        askKeep(unresolved, """
                novabot:
                  core:
                    configUi:
                      backupKeep: "7"
                """, 7, "驼峰键带引号的 7 应被认出");
        askKeep(unresolved, "seed: 1\n", TimestampedFileBackup.DEFAULT_KEEP,
                "没配 backup-keep 应回默认份数 " + TimestampedFileBackup.DEFAULT_KEEP);
        askKeep(unresolved, """
                novabot:
                  core:
                    config-ui:
                      backup-keep: "abc"
                """, TimestampedFileBackup.DEFAULT_KEEP,
                "非数字串应回默认份数 " + TimestampedFileBackup.DEFAULT_KEEP);

        assertTrue(unresolved.isEmpty(),
                () -> "读法五问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    @Test
    @DisplayName("只有 starbot 根时 backup-keep 视为空，回默认份数")
    void onlyLegacyRootIsTreatedAsEmptyForBackupKeep() throws IOException {
        List<String> unresolved = new ArrayList<>();
        askKeep(unresolved, """
                starbot:
                  core:
                    config-ui:
                      backup-keep: 3
                """, TimestampedFileBackup.DEFAULT_KEEP,
                "只有 starbot 根应视为空、回默认份数");
        assertTrue(unresolved.isEmpty(),
                () -> "只有旧根视为空未过: " + String.join("; ", unresolved));
    }

    /**
     * 写一份配置、问一次 {@code resolveBackupKeep()} 的返回值；逐问各自捕获、末尾汇总，一问红不许短路其余问
     */
    private void askKeep(List<String> unresolved, String yaml, int expected, String question) throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, yaml, StandardCharsets.UTF_8);
        try {
            assertEquals(expected, new SafeModeServer(config, "测试用的启动失败原因").resolveBackupKeep(), question);
        } catch (AssertionError e) {
            unresolved.add(e.getMessage());
        }
    }

    /**
     * 使用者故障：目录不让建新文件、配置文件本身可写（只读的容器根上单独挂一个可写的
     * 配置文件就是这种部署）时，此前安全模式的每次保存都在「同目录建带时间戳的备份」
     * 这一步先被拒，配置改不了——而安全模式本就是配置坏了才进来的，改不了等于把人锁在门外。
     * 改后该照常存上，目录里不添备份件与临时件；安全模式没有日志页，这一条得进工程日志。
     */
    @Test
    @DisplayName("目录不让建文件、文件可写：保存照样成，不留备份与临时件，工程日志记「没留备份」")
    void applySaveSkipsBackupWhenDirectoryRefusesNewFiles() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(dir);
        List<String> logged;
        try (LogCapture capture = new LogCapture()) {
            try {
                Files.setPosixFilePermissions(dir,
                        java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
                org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dir),
                        "当前用户无视目录权限（如 root），不让建新文件的目录设不出来");
                assertEquals(null, server.applySave("ok: true\n"), "目录不让建新文件不该挡住保存");
            } finally {
                Files.setPosixFilePermissions(dir, original);
            }
            logged = capture.messages();
        }

        assertEquals("ok: true\n", Files.readString(config, StandardCharsets.UTF_8),
                "本体应是这次保存的内容");
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("application.yml"),
                    files.map(path -> path.getFileName().toString()).sorted().toList(),
                    "目录里只该有配置文件本身：跳过的备份建不出、退回直接写也建不出临时件");
        }
        assertTrue(logged.stream().anyMatch(message -> message.contains("没留备份")),
                "安全模式没有日志页, 工程日志该记一条没留备份: " + logged);
    }

    private static List<String> stampedBackupNames(Path config) throws IOException {
        String prefix = config.getFileName() + ".";
        try (Stream<Path> files = Files.list(config.getParent())) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .filter(name -> name.length() > prefix.length() + ".bak".length())
                    .filter(name -> name.substring(prefix.length(), name.length() - ".bak".length())
                            .matches("\\d{8}-\\d{6}(-\\d+)?"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 收安全模式这一处的工程日志（各级都收），关掉时摘下
     */
    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SafeModeServer.class);
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();

        LogCapture() {
            appender.start();
            logger.addAppender(appender);
        }

        List<String> messages() {
            return appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
        }
    }
}
