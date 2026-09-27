package org.frostnova.nova.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * 换上目标文件的几个边：权限、新建默认、建件被拒、换名被拒、属主属组、符号链接
 * <p>
 * 换名写入的保护对象是凭据与配置这一类「写坏了要重新扫码或起不来」的文件，
 * 而换名这条路自己也有边：目标被单独挂载进容器或目录只读时换不上名，目标是
 * 符号链接（含悬空）时不能把链接顶成普通文件，原件权限紧的时候临时文件从建
 * 出来那一刻起就得一样紧，原件属主属组与进程的不同时换名会悄悄拆掉原安排。
 */
@DisplayName("换上目标文件")
class DurableFilesTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("0600 的原件换上后仍是 0600")
    void replaceKeepsOwnerOnlyPermissions() throws IOException {
        Path target = dir.resolve("cookies.json");
        Files.writeString(target, "old\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));

        DurableFiles.replace(target, "new\n");

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target), "仅属主可读写的原件保存后被放宽了");
        assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8), "内容应已换上");
    }

    @Test
    @DisplayName("0644 的原件换上后仍是 0644，权限跟随原件而不是一刀切")
    void replaceKeepsSharedPermissions() throws IOException {
        Path target = dir.resolve("application.yml");
        Files.writeString(target, "old\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        DurableFiles.replace(target, "new\n");

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
                Files.getPosixFilePermissions(target), "0644 的原件不该被悄悄收紧或放宽");
        assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8), "内容应已换上");
    }

    /**
     * 换名被拒的注入法：先放一份残留的临时文件让写入阶段照常走完，再把目录收成只读——
     * 这之后改名（要目录写权限）被拒，而直接写一个已存在的文件（只要文件自身可写）仍然可行，
     * 与单文件挂载的效果相同
     */
    @Test
    @DisplayName("换名被拒时退回直接写：内容正确，并记一条 WARN")
    void replaceFallsBackToDirectWriteWhenRenameIsRefused() throws IOException {
        Path target = dir.resolve("application.yml");
        Files.writeString(target, "old\n", StandardCharsets.UTF_8);
        Files.createFile(DurableFiles.temporary(target));
        Set<PosixFilePermission> originalDirPermissions = Files.getPosixFilePermissions(dir);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DurableFiles.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Files.setPosixFilePermissions(dir,
                    PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                assertDoesNotThrow(() -> DurableFiles.replace(target, "new\n"),
                        "换名被拒不该让保存失败：直接写本来就行，存不上比换不上更糟");
            } finally {
                Files.setPosixFilePermissions(dir, originalDirPermissions);
            }

            assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8),
                    "退回直接写就该把内容写对");
            List<String> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(1, warnings.size(), "应记一条 WARN 说明退回了直接写: " + warnings);
            assertTrue(warnings.get(0).contains("application.yml"),
                    "WARN 里要带上实际写的文件名: " + warnings.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("目标是符号链接时写到它指向的真文件，链接仍是链接")
    void replaceThroughSymbolicLinkKeepsTheLink() throws IOException {
        Path real = dir.resolve("real-application.yml");
        Files.writeString(real, "old\n", StandardCharsets.UTF_8);
        Path link = dir.resolve("application.yml");
        Files.createSymbolicLink(link, real);

        DurableFiles.replace(link, "new\n");

        assertTrue(Files.isSymbolicLink(link), "链接被顶成了普通文件，指向关系丢了");
        assertEquals("new\n", Files.readString(real, StandardCharsets.UTF_8), "真文件应已更新");
    }

    /**
     * 目录只读而文件可写时，建临时文件那一步就被拒了，而直接写一个已存在的文件一直行——
     * 这一部署从能存变成了存不上。注入法不预放残留的临时文件，
     * 让建件这一步真被拒，与「目录不可写、文件可写」的现场一致。
     */
    @Test
    @DisplayName("目录只读建不出临时文件时也退回直接写：内容正确，并记一条 WARN")
    void replaceFallsBackToDirectWriteWhenCreatingTempIsRefused() throws IOException {
        Path target = dir.resolve("application.yml");
        Files.writeString(target, "old\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> originalDirPermissions = Files.getPosixFilePermissions(dir);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DurableFiles.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                assertDoesNotThrow(() -> DurableFiles.replace(target, "new\n"),
                        "建临时文件被拒不该让保存失败：直接写本来就行，存不上比换不上更糟");
            } finally {
                Files.setPosixFilePermissions(dir, originalDirPermissions);
            }

            assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8),
                    "退回直接写就该把内容写对");
            assertFalse(Files.exists(DurableFiles.temporary(target)), "建不出的临时文件不该留个影子");
            List<String> warnings = warningsOf(appender);
            assertEquals(1, warnings.size(), "应记一条 WARN 说明退回了直接写: " + warnings);
            assertTrue(warnings.get(0).contains("application.yml"),
                    "WARN 里要带上实际写的文件名: " + warnings.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 建临时文件这一步就报磁盘满：磁盘满时直接写同样写不进，退回只会先把原件
     * 截断、再一个字节都写不进，留下一份空的。必须照常抛出，原件一字节不动。
     * <p>
     * 注入法：真盘写满做不到只坏「建件」这一步，也不该在测试里写满真盘——把
     * java.nio.file.Files 桩住、其余调用照真实行为走，只让建临时文件抛磁盘满
     * 的错；退回直写若被走到，也照真实磁盘满的样子先把原件截断、再抛同一个错
     * （与写满的小文件系统上实测到的现场一致）。
     */
    @Test
    @DisplayName("建临时文件这一步就报磁盘满时照常抛出，原件一字节不动")
    void replaceThrowsAndKeepsOriginalWhenCreatingTempHitsDiskFull() throws IOException {
        Files.writeString(dir.resolve("application.yml"), "old-config-intact\n", StandardCharsets.UTF_8);
        // replace 一进来就顺着符号链接把目标解析成真路径（macOS 的临时目录挂在 /var 下，
        // 真路径在 /private/var），桩要按真路径打才打得到
        Path target = dir.resolve("application.yml").toRealPath();
        Path temp = DurableFiles.temporary(target);

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createFile(eq(temp))).thenThrow(diskFull(temp));
            files.when(() -> Files.createFile(eq(temp), org.mockito.ArgumentMatchers.<FileAttribute<?>>any()))
                    .thenThrow(diskFull(temp));
            files.when(() -> Files.writeString(eq(target), eq("new\n"), eq(StandardCharsets.UTF_8)))
                    .thenAnswer(fallback -> {
                        try (FileChannel truncated = FileChannel.open(target,
                                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                            // 打开即截断：照真实磁盘满的样子，直接写第一步就把原件清掉，随后写不进
                        }
                        throw diskFull(target);
                    });

            IOException thrown = assertThrows(IOException.class, () -> DurableFiles.replace(target, "new\n"),
                    "磁盘满时建临时文件失败必须抛给调用方：退回直接写也写不进，只会先把原件截断掉");
            assertEquals("old-config-intact\n", Files.readString(target, StandardCharsets.UTF_8),
                    "退回直接写会先把原件截断再写不进，留下一份空的");
            assertTrue(thrown.getMessage().contains(temp.getFileName().toString()),
                    "抛出的该是建临时文件那一步的错，不是退回直接写之后的错: " + thrown.getMessage());
        }
    }

    /**
     * 目录路径里带 read-only 字样、建临时文件这一步报磁盘满：按报错文字认只读时，
     * {@code FileSystemException} 的消息是「文件路径: 原因」，路径也混进了比对，
     * 磁盘满会被误认成只读文件系统而退回直接写，把原件先截断掉。只认挂载标志后，
     * 这个目录挂载得普通，磁盘满照常抛出，原件一字节不动。
     * <p>
     * 注入法与磁盘满那格同：只桩建临时文件抛磁盘满的错、其余照真实行为走，
     * 退回直写若被走到、桩照真实磁盘满的样子先把原件截断、再抛同一个错。
     */
    @Test
    @DisplayName("目录路径里带 read-only 字样时，建件报磁盘满照常抛出，原件一字节不动")
    void replaceThrowsAndKeepsOriginalWhenDirectoryNamedReadOnlyHitsDiskFull() throws IOException {
        Path base = dir.resolve("mnt-read-only");
        Files.createDirectories(base);
        Files.writeString(base.resolve("application.yml"), "old-config-intact\n", StandardCharsets.UTF_8);
        // replace 一进来就顺着符号链接把目标解析成真路径，桩要按真路径打才打得到
        Path target = base.resolve("application.yml").toRealPath();
        Path temp = DurableFiles.temporary(target);

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createFile(eq(temp))).thenThrow(diskFull(temp));
            files.when(() -> Files.createFile(eq(temp), org.mockito.ArgumentMatchers.<FileAttribute<?>>any()))
                    .thenThrow(diskFull(temp));
            files.when(() -> Files.writeString(eq(target), eq("new\n"), eq(StandardCharsets.UTF_8)))
                    .thenAnswer(fallback -> {
                        try (FileChannel truncated = FileChannel.open(target,
                                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                            // 打开即截断：照真实磁盘满的样子，直接写第一步就把原件清掉，随后写不进
                        }
                        throw diskFull(target);
                    });

            IOException thrown = assertThrows(IOException.class, () -> DurableFiles.replace(target, "new\n"),
                    "路径里带字样不等于只读文件系统：必须抛给调用方，退回直接写只会先把原件截断掉");
            assertEquals("old-config-intact\n", Files.readString(target, StandardCharsets.UTF_8),
                    "路径里的 read-only 字样把磁盘满误认成只读、退回直接写，原件被截成空");
            assertTrue(thrown.getMessage().contains(temp.getFileName().toString()),
                    "抛出的该是建临时文件那一步的错，不是退回直接写之后的错: " + thrown.getMessage());
        }
    }

    /**
     * 悬空链接：指向的件还不存在。直接写会顺着链接把目标件建出来；
     * 换名这条路若只认「指向的件存在」的链接，就会把链接顶成普通文件，
     * 目标件也没建出来——比直接写还糟。
     */
    @Test
    @DisplayName("悬空链接保住：写到它指向的件，链接仍是链接，目标件被建出来")
    void replaceThroughDanglingLinkCreatesTheTarget() throws IOException {
        Path real = dir.resolve("real-application.yml");
        Path link = dir.resolve("application.yml");
        Files.createSymbolicLink(link, real);

        DurableFiles.replace(link, "new\n");

        assertTrue(Files.isSymbolicLink(link), "悬空链接被顶成了普通文件，指向关系丢了");
        assertTrue(Files.isRegularFile(real), "链接指向的件该照旧被建出来");
        assertEquals("new\n", Files.readString(real, StandardCharsets.UTF_8), "内容该写进链接指向的件");
    }

    /**
     * 换名会换 inode：属主属组跟着临时文件的那一份（进程用户与它的主组）走。
     * 原件的属组被特意改成与新建文件属组不同的另一个，保存后不该被悄悄换掉——
     * 「管理员组可编辑」那一类安排一丢，原本能改这份配置的账号从此改不了。
     * <p>
     * 环境里挑不出第二个能改过去的属组时（进程只属于一个组）本格跳过：
     * 那是前提搭不起来，不是待验的行为出了错。
     */
    @Test
    @DisplayName("原件属组与新建文件属组不同时，保存后属组不变")
    void replaceKeepsOriginalGroupWhenNewFilesGetAnotherGroup() throws IOException {
        Path target = dir.resolve("application.yml");
        Files.writeString(target, "old\n", StandardCharsets.UTF_8);
        Path probe = dir.resolve("probe.txt");
        Files.writeString(probe, "p\n", StandardCharsets.UTF_8);
        String groupOfNewFiles = groupOf(probe);
        org.junit.jupiter.api.Assumptions.assumeTrue(moveToAnyOtherGroup(target, groupOfNewFiles),
                "进程只属于一个组，搭不出属组不同的原件");
        String originalGroup = groupOf(target);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DurableFiles.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DurableFiles.replace(target, "new\n");

            assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8), "保存该把内容写对");
            assertEquals(originalGroup, groupOf(target),
                    "换名把原件的属组换掉了，原本能改这份配置的账号从此改不了");
            List<String> warnings = warningsOf(appender);
            assertEquals(1, warnings.size(), "应记一条 WARN 说明退回了直接写: " + warnings);
            assertTrue(warnings.get(0).contains("application.yml"),
                    "WARN 里要带上实际写的文件名: " + warnings.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 不指定新建默认时，新建的件跟系统默认权限走：与直接写出来的一样宽。
     * 不含秘密的件不必一律收成仅属主可读写——同机别的账号或按别的用户跑的
     * 脚本会因此读不了它。
     */
    @Test
    @DisplayName("不指定新建默认时，新建的件跟系统默认权限走")
    void replaceWithoutDefaultFollowsSystemDefaultForNewFiles() throws IOException {
        Path target = dir.resolve("datasource.json");
        Path probe = dir.resolve("probe.txt");
        Files.writeString(probe, "p\n", StandardCharsets.UTF_8);

        DurableFiles.replace(target, "new\n");

        assertEquals(Files.getPosixFilePermissions(probe), Files.getPosixFilePermissions(target),
                "不含秘密的件新建时该跟直接写一样宽，没必要更紧");
        assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8), "内容应已换上");
    }

    /**
     * 指定仅属主可读写为新建默认：含秘密的件一建出来就该是这份权限，
     * 不存在先按默认建出来再收紧的宽窗口
     */
    @Test
    @DisplayName("指定仅属主可读写为新建默认时，新建的件就是 0600")
    void replaceWithOwnerOnlyDefaultCreatesOwnerOnlyNewFile() throws IOException {
        Path target = dir.resolve("application.yml");

        DurableFiles.replace(target, "new\n", DurableFiles.OWNER_ONLY);

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target), "含秘密的件新建时就得仅属主可读写");
        assertEquals("new\n", Files.readString(target, StandardCharsets.UTF_8), "内容应已换上");
    }

    private static List<String> warningsOf(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static FileSystemException diskFull(Path file) {
        return new FileSystemException(file.toString(), null, "No space left on device");
    }

    /**
     * 退回直接写是先截断再写：写到一半出错，配置文件只剩半截，程序下次起不来，
     * 而明文换哈希那次不留备份，也没有件可退。写失败时要把原字节写回去，原来的错照抛。
     * <p>
     * 注入法：换名被拒那格的办法把路引到退回直写（残留临时文件＋目录只读），
     * 再把 java.nio.file.Files 桩住、其余照真实行为走，只让直接写那一步先截断、
     * 写进前半截、再抛 IO 错——真盘上做不出「写到一半坏」。
     */
    @Test
    @DisplayName("退回直接写写到一半出错时写回原文：目标逐字节同写之前，原来的错照抛")
    void directWriteFailingHalfwayRestoresOriginal() throws IOException {
        byte[] original = "server:\n  port: 7827\n".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("application.yml"), original);
        Path target = dir.resolve("application.yml").toRealPath();
        Files.createFile(DurableFiles.temporary(target));
        Set<PosixFilePermission> originalDirPermissions = Files.getPosixFilePermissions(dir);
        IOException halfway = new IOException("Input/output error");

        List<String> logged;
        IOException thrown;
        try (LogCapture capture = new LogCapture();
             MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.writeString(eq(target), eq("server:\n  port: 7828\n"), eq(StandardCharsets.UTF_8)))
                    .thenAnswer(direct -> writeHalfThenFail(target, halfway));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                thrown = assertThrows(IOException.class,
                        () -> DurableFiles.replace(target, "server:\n  port: 7828\n"),
                        "直接写失败了就该抛给调用方");
            } finally {
                Files.setPosixFilePermissions(dir, originalDirPermissions);
            }
            logged = capture.messages();
        }
        assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(target, StandardCharsets.UTF_8),
                "写到一半出错后目标该写回原样，现在是半截");
        assertTrue(thrown == halfway, "抛出的该是直接写那一步原来的错: " + thrown);
        assertTrue(logged.stream().anyMatch(message -> message.contains("已写回原样")),
                "日志该说已写回原样: " + logged);
    }

    /**
     * 写回原文也失败时没有别的路可走：原来的错照抛（写回的错挂在 suppressed 上），
     * 日志点出路径、说原件可能已不完整，让人知道去看哪一份。
     */
    @Test
    @DisplayName("退回直接写失败、写回原文也失败时原来的错照抛，日志说原件可能已不完整")
    void directWriteAndRestoreBothFailingReportsIncompleteOriginal() throws IOException {
        Files.writeString(dir.resolve("application.yml"), "server:\n  port: 7827\n", StandardCharsets.UTF_8);
        Path target = dir.resolve("application.yml").toRealPath();
        Files.createFile(DurableFiles.temporary(target));
        Set<PosixFilePermission> originalDirPermissions = Files.getPosixFilePermissions(dir);
        IOException halfway = new IOException("Input/output error");
        IOException restoreFailed = new IOException("restore refused");

        List<String> logged;
        IOException thrown;
        try (LogCapture capture = new LogCapture();
             MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.writeString(eq(target), eq("server:\n  port: 7828\n"), eq(StandardCharsets.UTF_8)))
                    .thenAnswer(direct -> writeHalfThenFail(target, halfway));
            files.when(() -> Files.write(eq(target), any(byte[].class))).thenThrow(restoreFailed);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                thrown = assertThrows(IOException.class,
                        () -> DurableFiles.replace(target, "server:\n  port: 7828\n"),
                        "直接写失败了就该抛给调用方");
            } finally {
                Files.setPosixFilePermissions(dir, originalDirPermissions);
            }
            logged = capture.messages();
        }
        assertTrue(thrown == halfway, "抛出的该是直接写那一步原来的错，不是写回的错: " + thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(restoreFailed),
                "写回的错该挂在原来那个错的 suppressed 上: " + List.of(thrown.getSuppressed()));
        assertTrue(logged.stream().anyMatch(message -> message.contains("可能已不完整")
                        && message.contains(target.toString())),
                "日志该点出路径并说原件可能已不完整: " + logged);
    }

    /**
     * 照真实「写到一半出错」的样子：先截断、写进前半截，再抛
     */
    private static Object writeHalfThenFail(Path target, IOException failure) throws IOException {
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap("serv".getBytes(StandardCharsets.UTF_8)));
        }
        throw failure;
    }

    /**
     * 收 DurableFiles 的日志（各级都收），关掉时摘下
     */
    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DurableFiles.class);
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

    private static String groupOf(Path file) throws IOException {
        return Files.readAttributes(file, PosixFileAttributes.class).group().getName();
    }

    /**
     * 把文件的属组改成与 notThisGroup 不同的一个进程所属组；挑不出时返回 false
     */
    private static boolean moveToAnyOtherGroup(Path file, String notThisGroup) throws IOException {
        String groups;
        try {
            Process id = new ProcessBuilder("id", "-Gn").start();
            try (var in = id.getInputStream()) {
                groups = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            id.waitFor();
        } catch (IOException | InterruptedException noId) {
            return false;
        }
        UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
        for (String candidate : groups.split("\\s+")) {
            if (candidate.isBlank() || candidate.equals(notThisGroup)) {
                continue;
            }
            try {
                GroupPrincipal group = lookup.lookupPrincipalByGroupName(candidate);
                Files.getFileAttributeView(file, PosixFileAttributeView.class).setGroup(group);
                if (!groupOf(file).equals(notThisGroup)) {
                    return true;
                }
            } catch (IOException notPermitted) {
                // 不在这个组里或系统查不到这个组名，换下一个候选
            }
        }
        return false;
    }
}
