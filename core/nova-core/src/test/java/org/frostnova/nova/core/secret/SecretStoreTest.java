package org.frostnova.nova.core.secret;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SecretStoreTest {
    private static final String NAME = "example-secret";
    private static final String SECRET = "sample-prefix-only-for-tests-42";

    @TempDir
    Path root;

    @Test
    @DisplayName("备份数据目录时不会连同密钥明文一起拷走")
    void backupContainsNoPlaintextAndStorageIsOwnerOnly() throws IOException {
        Path directory = root.resolve("plugin").resolve("secrets");
        new SecretStore(directory, NAME).save(SECRET);

        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                // 单字节字符集逐字节保留文件内容。密钥的字符都在 ASCII 范围内。
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains(SECRET), "文件包含完整密钥 " + file.getFileName());
            }
        }
        try (var paths = Files.list(directory)) {
            assertEquals(Set.of(NAME + ".enc", NAME + "-master.key"),
                    Set.copyOf(paths.map(path -> path.getFileName().toString()).toList()));
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            var ownerReadWrite = PosixFilePermissions.fromString("rw-------");
            var ownerDirectory = PosixFilePermissions.fromString("rwx------");
            assertEquals(ownerReadWrite, Files.getPosixFilePermissions(encrypted(directory)));
            assertEquals(ownerReadWrite, Files.getPosixFilePermissions(master(directory)));
            assertEquals(ownerDirectory, Files.getPosixFilePermissions(directory));
            assertEquals(ownerDirectory, Files.getPosixFilePermissions(directory.getParent()));
        }
    }

    @ParameterizedTest(name = "是否替换主密钥文件 {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("主密钥被替换或删除时，异常和日志都不会泄露密钥")
    void unusableMasterProducesSafeError(boolean replace) throws IOException {
        var store = new SecretStore(root, NAME);
        store.save(SECRET);
        if (replace) {
            var wrongKey = Base64.getDecoder().decode(Files.readString(master(root)));
            wrongKey[0] ^= 1;
            Files.writeString(master(root), Base64.getEncoder().encodeToString(wrongKey));
        } else {
            Files.delete(master(root));
        }

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger appLogger = (Logger) LoggerFactory.getLogger("org.frostnova.nova");
        Level previousLevel = appLogger.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        rootLogger.addAppender(logs);
        appLogger.addAppender(logs);
        appLogger.setLevel(Level.TRACE);
        try {
            String marker = "密钥存储日志检查";
            appLogger.trace(marker);
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().equals(marker)));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> store.use(value -> fail("损坏的密钥不能交给调用者")));
            assertTrue(error.getMessage().contains("密钥文件无法读写或解密"));
            assertTrue(error.getMessage().contains("请重新填写"));
            assertFalse(error.getMessage().contains(SECRET));
            assertNull(error.getCause());
            assertEquals(0, error.getSuppressed().length);
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains(SECRET));
                if (event.getThrowableProxy() != null) {
                    assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(SECRET));
                }
            }
        } finally {
            appLogger.setLevel(previousLevel);
            appLogger.detachAppender(logs);
            rootLogger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void readsSavedSecretAndMasksShortAndLongValues() throws IOException {
        var store = new SecretStore(root, NAME);
        assertFalse(store.isSet());
        assertEquals("", store.masked());

        store.save(SECRET);
        var reopened = new SecretStore(root, NAME);
        assertTrue(reopened.isSet());
        assertTrue(reopened.<Boolean>use(SECRET::equals));
        assertEquals("sample…42", reopened.masked());

        store.save("12345678");
        assertEquals("…", reopened.masked());
        byte[] originalMaster = Files.readAllBytes(master(root));
        reopened.clear();
        assertFalse(reopened.isSet());
        assertEquals("", reopened.masked());
        assertFalse(Files.exists(encrypted(root)));
        assertArrayEquals(originalMaster, Files.readAllBytes(master(root)));
    }

    @Test
    void savingTheSameSecretUsesADifferentIv() throws IOException {
        var store = new SecretStore(root, NAME);
        store.save(SECRET);
        var before = JSON.parseObject(Files.readString(encrypted(root)));
        store.save(SECRET);
        var after = JSON.parseObject(Files.readString(encrypted(root)));

        assertEquals(Set.of("version", "iv", "payload", "masked"), after.keySet());
        assertEquals(1, after.getIntValue("version"));
        assertEquals(12, Base64.getDecoder().decode(after.getString("iv")).length);
        assertEquals(32, Base64.getDecoder().decode(Files.readString(master(root))).length);
        assertEquals(SECRET.length() + 16, Base64.getDecoder().decode(after.getString("payload")).length);
        assertNotEquals(before.getString("iv"), after.getString("iv"));
        assertTrue(store.<Boolean>use(SECRET::equals));
    }

    @Test
    void invalidValuesLeaveBothFilesUnchanged() throws IOException {
        var store = new SecretStore(root, NAME);
        store.save(SECRET);
        byte[] originalEncrypted = Files.readAllBytes(encrypted(root));
        byte[] originalMaster = Files.readAllBytes(master(root));

        for (String invalid : new String[]{null, "", "has space", "has\nline", "\u007f", "é", "x".repeat(8193)}) {
            assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
            assertArrayEquals(originalEncrypted, Files.readAllBytes(encrypted(root)));
            assertArrayEquals(originalMaster, Files.readAllBytes(master(root)));
        }

        String maxLength = "!-x".repeat(2730) + "x~";
        store.save(maxLength);
        assertTrue(store.<Boolean>use(maxLength::equals));
    }

    @Test
    void savingWithOnlyCiphertextReplacesItAndGeneratesANewMaster() throws IOException {
        var store = new SecretStore(root, NAME);
        store.save(SECRET);
        String originalMaster = Files.readString(master(root));
        Files.delete(master(root));

        assertThrows(IllegalArgumentException.class, () -> store.save(""));
        assertTrue(Files.exists(encrypted(root)));
        assertFalse(Files.exists(master(root)));

        store.save("replacement");
        assertNotEquals(originalMaster, Files.readString(master(root)));
        assertTrue(store.<Boolean>use("replacement"::equals));
    }

    @Test
    void useDoesNotExposeAnExceptionContainingTheSecret() {
        var store = new SecretStore(root, NAME);
        store.save(SECRET);
        Function<String, Void> consumer = value -> {
            throw new IllegalArgumentException(value);
        };

        var error = assertThrows(IllegalStateException.class, () -> store.use(consumer));
        assertFalse(error.getMessage().contains(SECRET));
        assertNull(error.getCause());
    }

    private static Path encrypted(Path directory) {
        return directory.resolve(NAME + ".enc");
    }

    private static Path master(Path directory) {
        return directory.resolve(NAME + "-master.key");
    }
}
