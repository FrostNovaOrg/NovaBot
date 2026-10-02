package org.frostnova.nova.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 工作目录里的一把锁，让同一个目录同时只跑一份。
 * <p>
 * 锁是操作系统的独占锁。进程还在，锁就在；进程退出，包括被杀掉，由系统放锁，不会留下死锁。
 * 通道和锁都留在静态字段上：一旦被回收，锁会在进程还活着的时候松开。
 */
final class SingleInstanceLock {

    /**
     * 工作目录里的锁件名。备份不必带；程序停着的时候可以删。
     */
    static final String FILE_NAME = "novabot.lock";

    private static FileChannel heldChannel;

    private static FileLock heldLock;

    private SingleInstanceLock() {
    }

    /**
     * 入口第一件事：占锁。锁被占着时见 {@link #whenHeld}。
     *
     * @param workDir 工作目录
     * @return 可以往下启动时为 true；锁被占着、不再往下启动时为 false
     */
    static boolean acquire(Path workDir) {
        Path lockFile = workDir.toAbsolutePath().resolve(FILE_NAME);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                closeQuietly(channel);
                channel = null;
                return whenHeld(lockFile);
            }
            heldChannel = channel;
            heldLock = fileLock;
            channel = null;
            return true;
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            return whenHeld(lockFile);
        } catch (IOException e) {
            closeQuietly(channel);
            whenUnavailable(lockFile);
            return true;
        }
    }

    /**
     * 进程还在、锁还在手里。通道或锁被回收时这里会变成否。
     *
     * @return 当前是否占着锁
     */
    static boolean holding() {
        FileLock lock = heldLock;
        FileChannel channel = heldChannel;
        return lock != null && lock.isValid() && channel != null && channel.isOpen();
    }

    /**
     * 这个目录已经有一份在运行：向标准错误写一句说明，然后以非 0 退出。
     * <p>
     * 不往下启动，因此也不会进安全模式，不会改数据。
     *
     * @param lockFile 锁件路径，写进说明里
     * @return 不再往下启动
     */
    static boolean whenHeld(Path lockFile) {
        System.err.println("这个目录已有一份 NovaBot 在运行（" + lockFile + "）。要换版本请先停掉它。");
        System.exit(1);
        return false;
    }

    /**
     * 锁件打不开，或这个文件系统不支持这种锁：说明一句，然后照常启动。
     * 不能因为锁出错就让程序起不来。
     *
     * @param lockFile 锁件路径，写进说明里
     */
    static void whenUnavailable(Path lockFile) {
        System.err.println("没法确认这个目录是不是已经有一份 NovaBot 在运行（"
                + lockFile + " 取不到），照常启动。");
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // 没锁上的通道关上失败，也不改变后面退不退出
        }
    }
}
