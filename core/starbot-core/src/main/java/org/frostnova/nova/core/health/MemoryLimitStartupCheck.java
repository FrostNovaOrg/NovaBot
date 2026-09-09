package org.frostnova.nova.core.health;

import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 启动时比对容器内存上限与 JVM 自己的上限
 * <p>
 * 起因是 2026-08-10 的一次生产故障：发行的 systemd 单元里 {@code MemoryHigh=768M}，
 * 而 JVM 的实际稳态峰值是 896M。<b>{@code MemoryHigh} 是节流，不是警戒线</b>——
 * 超过它之后内核不杀进程，而是对每一次内存分配施加睡眠惩罚并强制同步回收。
 * <p>
 * 于是服务热起来之后余生都在被节流，表现为整个进程被均匀拖慢数秒：
 * 本机回环的 HTTP 调用要 2~11 秒（{@code curl} 打同一地址只要 1.3 毫秒），
 * 而 CPU 几乎不动、堆完全健康、无 swap、无 GC 异常。
 * <b>没有一处会报错，六项健康探针全绿，而带图的推送已经在丢。</b>
 * <p>
 * 这个错配是可以在启动时算出来的：两个数都摆在那里，只是从来没有人把它们放在一起看。
 * 与 {@code templateIsParseable} 同一个路子——能在启动时算出来的错，不要留到线上靠人排查。
 * <p>
 * 本检查只写日志，不阻止启动：配得不合适仍然能跑，只是会慢；
 * 而误判（比如读不到 cgroup、或者估算的余量对某些部署偏保守）不该拦住任何人开机。
 */
@Slf4j
@NovaComponent
public class MemoryLimitStartupCheck {
    /**
     * 堆与元空间之外还要留的余量：线程栈、代码缓存、GC 元数据、直接内存
     * <p>
     * 取 150M 与发行单元注释里的估算一致，也和实测对得上：
     * {@code -Xmx512m + MaxMetaspaceSize192m + 150M ≈ 854M}，实测峰值 896M。
     */
    static final long SLACK_BYTES = 150L * 1024 * 1024;

    /**
     * cgroup v2 里表示「不限制」的字面量
     */
    private static final String UNLIMITED = "max";

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        try {
            check().ifPresent(log::warn);
        } catch (Exception e) {
            // 自检本身绝不能影响启动
            log.debug("内存上限自检未能完成: {}", e.getMessage());
        }
    }

    /**
     * 读取当前进程的 cgroup 内存上限并与 JVM 上限比对
     * @return 配得不够时返回给人看的说明，否则为空
     */
    private Optional<String> check() {
        Optional<Path> dir = cgroupDirectory();
        if (dir.isEmpty()) {
            return Optional.empty();
        }

        Long high = readLimit(dir.get().resolve("memory.high"));
        Long max = readLimit(dir.get().resolve("memory.max"));
        if (high == null && max == null) {
            return Optional.empty();
        }

        // 用 maxMemory() 而不是解析 -Xmx：它是堆真正能涨到的上限。
        // 两者会差一点（SerialGC 下 -Xmx512m 实际约 495M，少掉一个 survivor 区），
        // 所以日志里的数字会比配置值略小，这不是算错。差额远小于上面 150M 的余量
        return advise(high, max, Runtime.getRuntime().maxMemory(), maxMetaspaceBytes());
    }

    /**
     * 判定逻辑
     * <p>
     * 与 I/O 分开，好在测试里直接喂数字。
     * @param high cgroup 的 {@code memory.high}，不限制时为 {@code null}
     * @param max cgroup 的 {@code memory.max}，不限制时为 {@code null}
     * @param heapMax 堆上限，即 {@code -Xmx}
     * @param metaspaceMax 元空间上限，未设置时为 {@code null}
     * @return 配得不够时返回说明，否则为空
     */
    static Optional<String> advise(Long high, Long max, long heapMax, Long metaspaceMax) {
        long needed = heapMax + (metaspaceMax == null ? 0 : metaspaceMax) + SLACK_BYTES;

        // 硬上限不够是更严重的一种：堆真涨上去会被 SIGKILL，连优雅停机都没有
        if (max != null && max < needed) {
            return Optional.of(String.format(
                    "容器内存硬上限 MemoryMax=%s 低于 JVM 可能占用的 %s（堆 %s + 元空间 %s + 线程栈与直接内存约 %s）。"
                            + "堆真涨到上限时进程会被 SIGKILL，不给优雅停机的机会，"
                            + "配合 Restart=on-failure 就是崩溃循环。请抬高 MemoryMax，或同步调低 -Xmx",
                    mb(max), mb(needed), mb(heapMax), mb(metaspaceMax == null ? 0 : metaspaceMax), mb(SLACK_BYTES)));
        }

        if (high != null && high < needed) {
            return Optional.of(String.format(
                    "容器内存软上限 MemoryHigh=%s 低于 JVM 可能占用的 %s（堆 %s + 元空间 %s + 线程栈与直接内存约 %s）。"
                            + "MemoryHigh 是节流而不是警戒线：超过之后内核会对每一次内存分配施加睡眠惩罚并强制回收，"
                            + "整个进程会被均匀拖慢数秒，而 CPU 不动、堆看着完全健康，极难排查。"
                            + "请把 MemoryHigh 抬到 %s 以上（这不增加内存需求，那些内存本来就要用），"
                            + "或同步调低 -Xmx。查看是否正在被节流：cat /sys/fs/cgroup/<本服务的 cgroup>/memory.events 里的 high 计数",
                    mb(high), mb(needed), mb(heapMax), mb(metaspaceMax == null ? 0 : metaspaceMax),
                    mb(SLACK_BYTES), mb(needed)));
        }

        return Optional.empty();
    }

    /**
     * 找到本进程所属的 cgroup v2 目录
     * <p>
     * 有 cgroup 命名空间时（多数容器）根目录就是 {@code /sys/fs/cgroup}；
     * 直接跑在宿主机上时要按 {@code /proc/self/cgroup} 里的路径往下找。
     * cgroup v1、非 Linux、读不到，一律返回空——自检不适用就不说话。
     */
    private Optional<Path> cgroupDirectory() {
        try {
            Path root = Path.of("/sys/fs/cgroup");
            if (!Files.isDirectory(root)) {
                return Optional.empty();
            }

            Path self = Path.of("/proc/self/cgroup");
            if (Files.isReadable(self)) {
                for (String line : Files.readAllLines(self)) {
                    // cgroup v2 只有一行，形如 0::/system.slice/starbot.service
                    if (line.startsWith("0::")) {
                        String relative = line.substring(3);
                        Path dir = relative.isBlank() || "/".equals(relative)
                                ? root
                                : root.resolve(relative.substring(1));
                        if (Files.isDirectory(dir)) {
                            return Optional.of(dir);
                        }
                    }
                }
            }

            return Files.exists(root.resolve("memory.max")) ? Optional.of(root) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 读一个 cgroup 上限文件
     * @return 字节数；文件不存在、内容是 {@code max} 或读不动时为 {@code null}
     */
    private Long readLimit(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            String value = Files.readString(path).strip();
            return UNLIMITED.equals(value) ? null : Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从启动参数里取 {@code -XX:MaxMetaspaceSize}
     * <p>
     * 没显式设置时元空间上限实际是「不限」，此处按 0 计入——
     * 那种情况下容器上限本就挡不住元空间，不该由本检查来假装能算准。
     * @return 字节数，未设置时为 {@code null}
     */
    private Long maxMetaspaceBytes() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : args) {
            if (arg.startsWith("-XX:MaxMetaspaceSize=")) {
                return parseSize(arg.substring("-XX:MaxMetaspaceSize=".length()));
            }
        }
        return null;
    }

    /**
     * 解析 {@code 192m} / {@code 1G} / {@code 201326592} 这类写法
     */
    static Long parseSize(String raw) {
        try {
            String value = raw.strip();
            if (value.isEmpty()) {
                return null;
            }

            char unit = value.charAt(value.length() - 1);
            long multiplier = switch (Character.toLowerCase(unit)) {
                case 'k' -> 1024L;
                case 'm' -> 1024L * 1024;
                case 'g' -> 1024L * 1024 * 1024;
                default -> 1L;
            };

            String digits = multiplier == 1L ? value : value.substring(0, value.length() - 1);
            return Long.parseLong(digits.strip()) * multiplier;
        } catch (Exception e) {
            return null;
        }
    }

    private static String mb(long bytes) {
        return (bytes / 1024 / 1024) + "M";
    }
}
