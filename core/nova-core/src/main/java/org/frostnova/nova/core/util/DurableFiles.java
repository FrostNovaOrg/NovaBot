package org.frostnova.nova.core.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 把一份小文件换上去，或把读坏的原件改名留底。
 * <p>
 * 直接往目标上写是先截断再写：磁盘满或写到一半断电，文件就空了或只剩半截，
 * 重启之后只看得见一份空的。所以先把内容写进同目录的临时文件、刷盘，再改名换上。
 * 临时文件名固定跟着目标走，不另起一串带随机数的名字，失败重试也不会越积越多。
 */
public final class DurableFiles {
    private static final DateTimeFormatter BAD_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private DurableFiles() {
    }

    /**
     * 用一份完整内容换上目标文件。
     * <p>
     * 换名尽量是原子的。文件系统不支持原子改名时，退回普通替换。
     * @param target 要换上的文件
     * @param content 完整内容
     * @throws IOException 临时文件写不进去或换名失败时抛出，此时目标文件保持原样
     */
    public static void replace(Path target, String content) throws IOException {
        Path temp = temporary(target);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 把读坏的文件改名留在原目录，字节不动。
     * <p>
     * 名字是「原名.bad-时间」。同一毫秒里已经有一份时，后面加序号，不盖掉更早的那份。
     * @param source 坏文件
     * @return 留底之后的路径
     * @throws IOException 改名失败时抛出，坏文件仍在原处
     */
    public static Path quarantine(Path source) throws IOException {
        String stamp = BAD_STAMP.format(Instant.now());
        String prefix = source.getFileName() + ".bad-" + stamp;
        Path destination = source.resolveSibling(prefix);
        int extra = 2;
        while (Files.exists(destination)) {
            destination = source.resolveSibling(prefix + "-" + extra);
            extra++;
        }
        Files.move(source, destination);
        return destination;
    }

    /**
     * 临时文件与目标同目录，名字是目标文件名加 {@code .tmp}。
     */
    public static Path temporary(Path target) {
        Path name = target.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("没有文件名: " + target);
        }
        return target.resolveSibling(name.toString() + ".tmp");
    }
}
