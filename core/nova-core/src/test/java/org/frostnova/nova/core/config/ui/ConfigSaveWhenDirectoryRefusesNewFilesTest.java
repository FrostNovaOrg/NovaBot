package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.timeline.TimelineEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * 目录不让建新文件、配置文件本身可写时的保存
 * <p>
 * 使用者故障：只读的容器根上单独挂一个可写的配置文件就是这种部署。写盘那一步
 * （换名的临时件建不出时退回直接写）本来走得通，但保存前的备份建在同一个目录里，
 * 此前每次保存都在备份这一步先被拒，页面只回「保存失败」，配置怎么都改不了。
 */
@DisplayName("目录不让建新文件时的保存")
class ConfigSaveWhenDirectoryRefusesNewFilesTest {

    private static final String TEMPLATE = """
            server:
              port: 7827

            novabot:
              core:
                push:
                  quiet-start:
            """;

    /**
     * 备份名里那一截时间戳；{@link #BACKUP_CLOCK} 由它定，断言里也用它拼出
     * detail 里那一份建不出的备份件的名字
     */
    private static final String STAMP = "20260905-100000";

    /** 定住的钟：跳过备份那一条的 detail 就能整句钉死，不用猜时间戳 */
    private static final Clock BACKUP_CLOCK = Clock.fixed(
            LocalDateTime.parse(STAMP, DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    .toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @TempDir
    Path dir;

    /**
     * 使用者故障：设置页存一项，此前在「同目录建带时间戳的备份」这一步先被拒，整次保存失败。
     * 改后该照常存上；目录里不该多出备份件或临时件（备份建不出就跳过、临时件建不出就退回直接写）；
     * 日志页该有一条「没留备份」说清这次没留和原因。
     */
    @Test
    @DisplayName("设置页存一项：照样存上，目录里不添备份件与临时件，日志页记一条「没留备份」")
    void settingsSaveSucceedsWithoutBackupAndRecordsIt() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        List<TimelineEvent> recorded = new ArrayList<>();
        ConfigurationFileService service = new ConfigurationFileService(
                config, () -> TEMPLATE, () -> 10, BACKUP_CLOCK, recorded::add);

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dir),
                    "当前用户无视目录权限（如 root），不让建新文件的目录设不出来");
            assertEquals(List.of("server.port"), service.write(Map.of("server.port", "7001")),
                    "目录不让建新文件不该挡住保存");
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }

        assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("port: 7001"),
                "配置该真的存上");
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("application.yml"),
                    files.map(path -> path.getFileName().toString()).sorted().toList(),
                    "目录里只该有配置文件本身：跳过的备份建不出、退回直接写也建不出临时件");
        }

        assertEquals(1, recorded.size(), "日志页该记一条没留备份, 多了会把真出事的那些淹掉: " + recorded);
        TimelineEvent event = recorded.get(0);
        // 整句钉死：只说这次没留备份和为什么，不说保存成没成——那由回话说；
        // detail 点出建不出的是哪一份备份件，不带 Java 类名
        assertEquals("配置没留备份：所在目录建不出新文件（没有权限或文件系统只读）", event.text());
        assertEquals("建不出备份文件 " + config.resolveSibling("application.yml." + STAMP + ".bak"),
                event.detail().get("reason"), "detail 该说人话并点出是哪一份: " + event.detail());
        assertEquals(TimelineEvent.Level.WARN, event.level());
        assertEquals("没留备份", event.type().getDescription());

        // 阴性对照：权限照常的目录里，下一次保存照旧留备份、一条不多记
        service.write(Map.of("server.port", "7002"));
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(2, names.size(), "正常保存该留下一份备份: " + names);
            assertTrue(names.stream().anyMatch(name -> name.endsWith(".bak")), "备份件该在: " + names);
        }
        assertEquals(1, recorded.size(), "正常保存不该再记没留备份: " + recorded);
    }

    /**
     * Windows 上这一步被拒时，异常同时带源、目标两个路径，从异常里取路径取到的是源，
     * 也就是配置文件本身。detail 若照它说，日志页会把配置文件说成建不出的备份件，
     * 使用者照着去查配置文件自己的权限，查错了地方——真正建不出的是同目录里那份
     * 带时间戳的备份。
     */
    @Test
    @DisplayName("Windows 形的拒绝（异常带源、目标两个路径）：detail 点备份件本身，不把配置文件说成备份件")
    void windowsShapedRefusalNamesTheBackupFileNotTheConfigFile() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        List<TimelineEvent> recorded = new ArrayList<>();
        ConfigurationFileService service = new ConfigurationFileService(
                config, () -> TEMPLATE, () -> 10, BACKUP_CLOCK, recorded::add);

        Path backupFile = config.resolveSibling("application.yml." + STAMP + ".bak");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(any(Path.class), any(Path.class)))
                    .thenThrow(new AccessDeniedException(config.toString(), backupFile.toString(), null));
            assertEquals(List.of("server.port"), service.write(Map.of("server.port", "7001")),
                    "目录其实可写，保存该照常存上");
        }

        assertEquals(1, recorded.size(), "日志页该记一条没留备份: " + recorded);
        assertEquals("建不出备份文件 " + backupFile, recorded.get(0).detail().get("reason"),
                "detail 该点没建出来的那份备份件，不该把配置文件说成备份件: " + recorded.get(0).detail());
    }

    /**
     * 跳过备份只限「目录建不出新文件」这一类失败；磁盘满这一类保存本身也写不成，
     * 照旧拦下整次保存，不许把「备份失败」悄悄咽掉
     */
    @Test
    @DisplayName("备份失败不是建不出新文件那一类时（如磁盘满）照旧挡住保存，配置一个字不动")
    void otherBackupFailuresStillBlockTheSave() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        List<TimelineEvent> recorded = new ArrayList<>();
        ConfigurationFileService service = new ConfigurationFileService(
                config, () -> TEMPLATE, () -> 10, BACKUP_CLOCK, recorded::add);

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(any(Path.class), any(Path.class)))
                    .thenThrow(new FileSystemException(config.toString(), null, "模拟的磁盘满"));
            assertThrows(IOException.class, () -> service.write(Map.of("server.port", "7001")),
                    "别的备份失败该照旧拦下整次保存");
        }

        assertEquals(TEMPLATE, Files.readString(config, StandardCharsets.UTF_8), "没存上就不许动配置");
        assertTrue(recorded.isEmpty(), "被拦下的失败不该记没留备份: " + recorded);
    }
}
