package com.starlwr.bot.core.safemode;

import com.starlwr.bot.core.config.ui.TimestampedFileBackup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全模式保存配置时的带时间戳备份
 * <p>
 * 保留份数尽量照 yml 里配的走（starbot.core.config-ui.backup-keep），读不到时
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
                starbot:
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
    @DisplayName("配置写 backup-keep: 500：按 1–100 收口，连存 3 次不报错、份数不越界")
    void safeModeBackupsClampOversizedKeep() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, """
                starbot:
                  core:
                    config-ui:
                      backup-keep: 500
                """, StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        for (int i = 0; i < 3; i++) {
            server.backupBeforeSave();
        }

        List<String> names = stampedBackupNames(config);
        assertTrue(names.size() <= TimestampedFileBackup.MAX_KEEP,
                "超界的保留份数应按上限 " + TimestampedFileBackup.MAX_KEEP + " 收口, 实际 " + names.size() + " 份");
        assertEquals(3, names.size(), "上限远未触到, 连存的 3 份都应在");
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
}
