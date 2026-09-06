package com.starlwr.bot.core.safemode;

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
 * 此刻主程序已经起不来，读不到 yml 里配的保留份数——备份份数与清旧份的行为
 * 因此只跟组件的默认值走，这里钉住的就是「跟组件走」这件事本身。
 */
@DisplayName("安全模式保存配置的备份")
class SafeModeServerBackupTest {

    @TempDir
    Path dir;

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
