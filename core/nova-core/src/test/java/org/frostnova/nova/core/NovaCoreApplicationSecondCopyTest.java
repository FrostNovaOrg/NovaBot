package org.frostnova.nova.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Permission;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一个工作目录里已经有一份在运行时，第二份不该再往下启动
 * <p>
 * 抓的是第二份照样启动并动盘：目录里这份锁还被占着，再走一遍入口，
 * 仍然进到启动。启动接着就会建索引、清过期、改配置、清报告图缓存，
 * 两份一起改数据、群里重复回话、推送发两遍。
 * <p>
 * 占锁在本进程里先做。走到 {@code SpringApplication.run} 即算进了启动——动盘都在这一步之后。
 * 锁空的那一格是对照：没有人占着时，入口照旧往下启动。
 */
@DisplayName("同一个目录已有一份在运行时，第二份退出、不往下启动")
class NovaCoreApplicationSecondCopyTest {

    /**
     * 工作目录里的锁件名
     */
    private static final String LOCK_FILE_NAME = "novabot.lock";

    /**
     * 退出被拦住时带出的退码。入口若真去退出，测试进程不能跟着停
     */
    private static final class ExitIntercepted extends SecurityException {
        final int code;

        private ExitIntercepted(int code) {
            super(Integer.toString(code));
            this.code = code;
        }
    }

    /**
     * 走一遍入口之后看到的三件事：退了没有、退码、有没有进到启动
     */
    private static final class Launch {
        boolean exited;
        int code;
        boolean started;
        String stderr = "";
    }

    @Test
    @DisplayName("第二份照样启动并动盘：锁已被占时应退出，且不走到启动")
    void secondCopyStillStartsWhenLockIsHeld(@TempDir Path dir) throws Exception {
        Path lockFile = dir.toAbsolutePath().resolve(LOCK_FILE_NAME);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertNotNull(held, "本进程应先占住锁件");

            Launch launch = launch(dir, true);

            assertFalse(launch.started, "锁已被占，不应走到 SpringApplication.run。标准错误：\n" + launch.stderr);
            assertTrue(launch.exited, "锁已被占应退出。标准错误：\n" + launch.stderr);
            assertNotEquals(0, launch.code, "退出码应为非 0");
            assertTrue(launch.stderr.contains("这个目录已有一份 NovaBot 在运行"),
                    "说明应写明这个目录已有一份在运行。标准错误：\n" + launch.stderr);
            assertTrue(launch.stderr.contains(lockFile.toString()),
                    "说明应写出锁件路径。标准错误：\n" + launch.stderr);
            assertTrue(launch.stderr.contains("要换版本请先停掉它"),
                    "说明应写明要换版本请先停掉正在运行的那一份。标准错误：\n" + launch.stderr);
        }
    }

    @Test
    @DisplayName("锁空：照常走到启动")
    void emptyLockStillStarts(@TempDir Path dir) throws Exception {
        assertEquals(LOCK_FILE_NAME, SingleInstanceLock.FILE_NAME);

        Launch launch = launch(dir, false);

        assertTrue(launch.started, "没有人占锁时应照常走到启动。标准错误：\n" + launch.stderr);
        assertFalse(launch.exited, "没有人占锁时不应退出");
        assertTrue(SingleInstanceLock.holding(), "往下启动之后应一直占着这把锁");
    }

    @Test
    @DisplayName("另一进程占着锁：退码非 0，说明在标准错误上，不进启动")
    void otherProcessHoldingLockExitsBeforeStart(@TempDir Path dir) throws Exception {
        Path lockFile = dir.toAbsolutePath().resolve(LOCK_FILE_NAME);
        Path errFile = dir.resolve("stderr.txt");
        Path outFile = dir.resolve("stdout.txt");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertNotNull(channel.tryLock(), "本进程应先占住锁件，留给另一个进程去撞");

            Path argsFile = dir.resolve("java-args.txt");
            Files.writeString(argsFile, "-cp\n" + System.getProperty("java.class.path") + "\n"
                    + NovaCoreApplication.class.getName() + "\n");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + argsFile.toAbsolutePath())
                    .directory(dir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()))
                    .redirectError(ProcessBuilder.Redirect.to(errFile.toFile()))
                    .start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String stderr = Files.readString(errFile);
            String stdout = Files.readString(outFile);
            assertTrue(finished, "另一进程占着锁时第二份应退出。标准错误：\n" + stderr
                    + "\n标准输出：\n" + stdout);
            assertNotEquals(0, process.exitValue(), "退出码应为非 0");
            assertTrue(stderr.contains("这个目录已有一份 NovaBot 在运行"),
                    "说明应写明这个目录已有一份在运行。标准错误：\n" + stderr);
            assertTrue(stderr.contains(lockFile.toString()) || stderr.contains(lockFile.toRealPath().toString()),
                    "说明应写出锁件路径。标准错误：\n" + stderr);
            assertTrue(stderr.contains("要换版本请先停掉它"),
                    "说明应写明要换版本请先停掉正在运行的那一份。标准错误：\n" + stderr);
            assertFalse(stdout.contains("Starting "),
                    "不该进到启动。标准输出：\n" + stdout);
        }
    }

    @Test
    @DisplayName("锁取不到：说明一句后照常走到启动")
    void unavailableLockStillStarts(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("missing-parent");
        Launch launch = launch(missing, false);
        Path lockFile = missing.toAbsolutePath().resolve(LOCK_FILE_NAME);

        assertTrue(launch.started, "锁取不到时应照常走到启动。标准错误：\n" + launch.stderr);
        assertFalse(launch.exited, "锁取不到时不应退出");
        assertTrue(launch.stderr.contains("照常启动"),
                "应说明照常启动。标准错误：\n" + launch.stderr);
        assertTrue(launch.stderr.contains(lockFile.toString()),
                "说明应写出锁件路径。标准错误：\n" + launch.stderr);
    }

    /**
     * 把工作目录换到临时目录再走入口。要看退码时拦住退出，避免测试进程停掉
     */
    private static Launch launch(Path workDir, boolean trapExit) throws Exception {
        Launch launch = new Launch();
        AtomicBoolean started = new AtomicBoolean(false);
        String previousDir = System.getProperty("user.dir");
        PrintStream previousErr = System.err;
        SecurityManager previousManager = System.getSecurityManager();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setProperty("user.dir", workDir.toAbsolutePath().toString());
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        if (trapExit) {
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkPermission(Permission perm) {
                }

                @Override
                public void checkExit(int status) {
                    throw new ExitIntercepted(status);
                }
            });
        }
        try (MockedStatic<SpringApplication> ignored = Mockito.mockStatic(SpringApplication.class,
                invocation -> {
                    if ("run".equals(invocation.getMethod().getName())) {
                        started.set(true);
                    }
                    return Mockito.RETURNS_DEFAULTS.answer(invocation);
                })) {
            try {
                NovaCoreApplication.main(new String[0]);
            } catch (ExitIntercepted intercepted) {
                launch.exited = true;
                launch.code = intercepted.code;
            }
        } finally {
            System.setErr(previousErr);
            System.setSecurityManager(previousManager);
            System.setProperty("user.dir", previousDir);
            launch.started = started.get();
            launch.stderr = err.toString(StandardCharsets.UTF_8);
        }
        return launch;
    }
}
