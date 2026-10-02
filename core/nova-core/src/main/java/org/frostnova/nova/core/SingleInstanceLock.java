package org.frostnova.nova.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.function.Consumer;

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

    /**
     * 候命开关：系统属性 {@code novabot.standby}，没有则看环境变量 {@code NOVABOT_STANDBY}。
     * 只有 true（忽略大小写）算开，默认关。
     */
    static final String STANDBY_PROPERTY = "novabot.standby";

    /**
     * 与 {@link #STANDBY_PROPERTY} 同一件事的环境变量名。
     */
    static final String STANDBY_ENV = "NOVABOT_STANDBY";

    /**
     * 等锁上限的秒数。系统属性 {@code novabot.standby.wait-seconds}，默认 300（5 分钟）。
     */
    static final String WAIT_SECONDS_PROPERTY = "novabot.standby.wait-seconds";

    /**
     * 还没过门就起不来（配置写坏也算）。
     * 退码 74：后面的升级脚本见到这个码就改走普通重启。
     */
    static final int EXIT_BEFORE_GATE = 74;

    /**
     * 锁件打不开，或这个文件系统不支持这种锁。候命分不清旧的那份还在不在。
     * 退码 75：升级脚本见到这个码就改走普通重启。
     */
    static final int EXIT_LOCK_UNKNOWN = 75;

    /**
     * 等到上限还没拿到锁。
     * 退码 76：升级脚本见到这个码就改走普通重启。
     */
    static final int EXIT_WAIT_EXPIRED = 76;

    /**
     * 收到停机信号、还没过门。与服务单元里把 143 当作正常退出的那一档相同。
     */
    static final int EXIT_SIGNAL = 143;

    private static final long DEFAULT_WAIT_SECONDS = 300;

    private static final long POLL_MILLIS = 50;

    private static FileChannel heldChannel;

    private static FileLock heldLock;

    /**
     * 还没拿到锁时留着的那一根通道。等锁就在这根上重试，不再另开、另关。
     */
    private static FileChannel pendingChannel;

    /**
     * 同一进程里多开的通道不能关：关上可能把已经拿着的锁一起放掉。留着引用，避免被回收时关上。
     */
    private static FileChannel spareChannel;

    private static Path lockPath;

    private static long startedAtNanos;

    private static long passedAtNanos;

    private static volatile boolean passedGate;

    /**
     * 正在门里等锁。这段时间停机钩子只做标记，由等待循环自己结束进程。
     */
    private static volatile boolean inGateWait;

    private static volatile boolean stopRequested;

    /**
     * 怎么退出。生产环境结束进程；测试换成记下退码的替身，免得测试进程跟着停。
     */
    static Consumer<Integer> exit = status -> System.exit(status);

    private SingleInstanceLock() {
    }

    /**
     * 入口第一件事：占锁。锁被占着时见 {@link #whenHeld}。
     *
     * @param workDir 工作目录
     * @return 可以往下启动时为 true；锁被占着、不再往下启动时为 false
     */
    /**
     * 候命开着时，在入口、{@code run} 之前挂上。钩子只做标记；还没进门里等锁时直接结束。
     * 门里等锁的那一段由等待循环自己看标记并结束，避免和框架的停机钩子互相卡住。
     */
    static void armStopHook() {
        if (!standbyEnabled()) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested = true;
            if (!passedGate && !inGateWait) {
                Runtime.getRuntime().halt(EXIT_SIGNAL);
            }
        }, "novabot-standby-stop"));
    }

    static void markStart() {
        startedAtNanos = System.nanoTime();
    }

    static long startedAt() {
        return startedAtNanos;
    }

    static long passedAt() {
        return passedAtNanos;
    }

    static boolean hasPassed() {
        return passedGate;
    }

    static void markPassed() {
        passedAtNanos = System.nanoTime();
        passedGate = true;
    }

    static boolean standbyEnabled() {
        String property = System.getProperty(STANDBY_PROPERTY);
        if (property != null) {
            return "true".equalsIgnoreCase(property);
        }
        String env = System.getenv(STANDBY_ENV);
        return env != null && "true".equalsIgnoreCase(env);
    }

    /**
     * 进入门里的等待。必须在打出「正在等」之前调用，停机钩子这之后不再自行结束进程。
     */
    static void beginWait() {
        inGateWait = true;
    }

    /**
     * 等到这把锁落到自己手里。调用前须已 {@link #beginWait()}。
     * <p>
     * 停机钩子在这段里只做标记、不结束进程，所以每轮先看标记。
     * 改成只睡眠、不看标记的话，停机信号会一直卡到旧的那份放锁之后。
     *
     * @return 等锁用了多少纳秒
     */
    static long awaitLock() {
        long waitStart = System.nanoTime();
        long limit = waitLimitNanos();
        try {
            while (true) {
                if (stopRequested) {
                    Runtime.getRuntime().halt(EXIT_SIGNAL);
                }
                if (holding()) {
                    break;
                }
                if (System.nanoTime() - waitStart >= limit) {
                    long seconds = Math.max(1, (System.nanoTime() - waitStart) / 1_000_000_000L);
                    System.err.println("等了 " + seconds + " 秒，这个目录里那一份还没退出（"
                            + lockPath + "），先不接手。");
                    System.err.flush();
                    Runtime.getRuntime().halt(EXIT_WAIT_EXPIRED);
                }
                takePendingLock();
                if (stopRequested) {
                    Runtime.getRuntime().halt(EXIT_SIGNAL);
                }
                if (holding()) {
                    break;
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    if (stopRequested) {
                        Runtime.getRuntime().halt(EXIT_SIGNAL);
                    }
                }
            }
            return System.nanoTime() - waitStart;
        } finally {
            inGateWait = false;
        }
    }

    static String seconds(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000_000.0);
    }

    static boolean acquire(Path workDir) {
        Path lockFile = workDir.toAbsolutePath().resolve(FILE_NAME);
        lockPath = lockFile;
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                if (standbyEnabled()) {
                    pendingChannel = channel;
                    channel = null;
                    explainStandby(lockFile);
                    return true;
                }
                closeQuietly(channel);
                channel = null;
                return whenHeld(lockFile);
            }
            heldChannel = channel;
            heldLock = fileLock;
            channel = null;
            return true;
        } catch (OverlappingFileLockException e) {
            if (standbyEnabled() && holding()) {
                spareChannel = channel;
                channel = null;
                return true;
            }
            closeQuietly(channel);
            return whenHeld(lockFile);
        } catch (IOException e) {
            closeQuietly(channel);
            if (standbyEnabled()) {
                explainUnknown(lockFile);
                System.err.flush();
                Runtime.getRuntime().halt(EXIT_LOCK_UNKNOWN);
                return false;
            }
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
        // 备用通道被关上时，已经拿到的锁也可能一起没了
        if (spareChannel != null && !spareChannel.isOpen()) {
            return false;
        }
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
        exit.accept(1);
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

    /**
     * 候命开着、锁被占：不退出。这句话不写「已有一份在运行」，免得和直接退出的那句混在一起。
     */
    private static void explainStandby(Path lockFile) {
        System.err.println("这个目录已经有一份 NovaBot 在跑（" + lockFile
                + "）。先准备好，等它退出再接手。");
    }

    private static void explainUnknown(Path lockFile) {
        System.err.println("没法确认这个目录是不是已经有一份 NovaBot 在跑（"
                + lockFile + " 取不到），不往下接手。");
    }

    private static void takePendingLock() {
        FileChannel channel = pendingChannel;
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            FileLock fileLock = channel.tryLock();
            if (fileLock != null) {
                heldChannel = channel;
                heldLock = fileLock;
                pendingChannel = null;
            }
        } catch (OverlappingFileLockException e) {
            // 同一进程已经拿着时，holding() 下一轮会放行
        } catch (IOException e) {
            System.err.println("没法确认这个目录是不是已经有一份 NovaBot 在跑（"
                    + lockPath + " 取不到），不往下接手。");
            System.err.flush();
            Runtime.getRuntime().halt(EXIT_LOCK_UNKNOWN);
        }
    }

    private static long waitLimitNanos() {
        String raw = System.getProperty(WAIT_SECONDS_PROPERTY);
        long seconds = DEFAULT_WAIT_SECONDS;
        if (raw != null) {
            try {
                seconds = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                seconds = DEFAULT_WAIT_SECONDS;
            }
        }
        if (seconds < 1) {
            seconds = 1;
        }
        return seconds * 1_000_000_000L;
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
