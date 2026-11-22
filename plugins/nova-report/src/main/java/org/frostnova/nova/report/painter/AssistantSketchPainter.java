package org.frostnova.nova.report.painter;

import com.github.weisj.jsvg.parser.SVGLoader;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.Sketch;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.SketchRejected;
import org.frostnova.nova.report.util.FontUtil;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 让转图在独立堆中运行，超时可以终止整个进程。 */
final class AssistantSketchPainter {
    private static final Semaphore RENDER_SLOT = new Semaphore(1, true);
    private static final int QUEUE_SECONDS = 10;
    private static final int STARTUP_SECONDS = 10;
    private static final int RENDER_SECONDS = 3;
    private static final int MAX_INPUT_BYTES = 16 * 1024;
    private static final String UNAVAILABLE = "暂时无法生成手绘图";
    private static final String STARTUP_TIMEOUT = "手绘暂时画不了";
    private static final String RENDER_TIMEOUT = "手绘转图超时，请简化图形";

    static Optional<String> render(Sketch sketch, FontUtil fonts) {
        if (sketch.svg().length() > MAX_INPUT_BYTES || sketch.svg().getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES)
            throw new SketchRejected("手绘 SVG 过长");
        var started = new CompletableFuture<Process>();
        var ready = new CompletableFuture<Long>();
        var transfer = new FutureTask<>(() -> launchAndExchange(sketch, fonts, started, ready));
        Thread worker = new Thread(transfer, "assistant-sketch-png");
        worker.setDaemon(true);

        boolean ownsSlot = false;
        boolean receivedReady = false;
        try {
            if (!RENDER_SLOT.tryAcquire(QUEUE_SECONDS, TimeUnit.SECONDS))
                throw new SketchRejected("已有手绘图正在生成，请稍后再试");
            ownsSlot = true;
            long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_SECONDS);
            worker.start();
            ownsSlot = false;

            long readyAt = ready.get(startupDeadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (readyAt - startupDeadline > 0) throw new SketchRejected(STARTUP_TIMEOUT);
            receivedReady = true;
            long renderDeadline = readyAt + TimeUnit.SECONDS.toNanos(RENDER_SECONDS);
            var result = transfer.get(renderDeadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (result.finishedAt() - renderDeadline > 0) throw new SketchRejected(RENDER_TIMEOUT);
            return switch (result.exit()) {
                case 0 -> Optional.of(Base64.getEncoder().encodeToString(result.png()));
                case AssistantSketchRaster.BLANK -> throw new SketchRejected("手绘图没有可见内容");
                case AssistantSketchRaster.MISSING_GLYPH -> throw new SketchRejected("手绘文字含有内置字体无法显示的字符");
                case AssistantSketchRaster.INVALID -> throw new SketchRejected("无法读取这份手绘 SVG");
                default -> throw new SketchRejected(UNAVAILABLE);
            };
        } catch (TimeoutException error) {
            throw new SketchRejected(receivedReady ? RENDER_TIMEOUT : STARTUP_TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SketchRejected(UNAVAILABLE);
        } catch (ExecutionException error) {
            throw new SketchRejected(UNAVAILABLE);
        } finally {
            if (ownsSlot) RENDER_SLOT.release();
            // 若启动尚未返回，禁止后来起好的进程接收绘图请求。
            started.complete(null);
            Process child = started.getNow(null);
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    private static Result launchAndExchange(Sketch sketch, FontUtil fonts, CompletableFuture<Process> started,
                                            CompletableFuture<Long> ready)
            throws IOException, URISyntaxException, InterruptedException {
        Process child = null;
        try {
            Path font = fonts.bundledChineseFontFile();
            if (started.isDone()) throw new IOException("Sketch startup expired");
            child = start(font);
            if (!started.complete(child)) throw new IOException("Sketch startup expired");
            return exchange(sketch, child, ready);
        } finally {
            if (!ready.isDone()) ready.completeExceptionally(new IOException("Sketch process did not become ready"));
            // 直到子进程退出才允许下一张转图，防止超时后的进程叠加内存。
            if (child != null && child.isAlive()) child.destroyForcibly().onExit().thenRun(RENDER_SLOT::release);
            else RENDER_SLOT.release();
        }
    }

    private static Process start(Path font) throws IOException, URISyntaxException {
        String java = File.separatorChar == '\\' ? "java.exe" : "java";
        var executable = Path.of(System.getProperty("java.home"), "bin", java);
        String classpath = location(AssistantSketchRaster.class) + File.pathSeparator + location(SVGLoader.class);
        return new ProcessBuilder(executable.toString(), "-Djava.awt.headless=true",
                "-Xmx64m", "-XX:MaxDirectMemorySize=8m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError",
                "-cp", classpath, AssistantSketchRaster.class.getName(), font.toAbsolutePath().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Result exchange(Sketch sketch, Process child, CompletableFuture<Long> ready)
            throws IOException, InterruptedException {
        try (var output = child.getInputStream()) {
            if (output.read() != AssistantSketchRaster.READY) throw new IOException("Sketch process did not become ready");
            ready.complete(System.nanoTime());
            try (var input = new DataOutputStream(child.getOutputStream())) {
                input.writeUTF(sketch.title());
                input.writeUTF(sketch.svg());
            }
            byte[] png = output.readNBytes(AssistantSketchRaster.MAX_PNG_BYTES + 1);
            if (png.length > AssistantSketchRaster.MAX_PNG_BYTES) throw new IOException("PNG exceeds its canvas budget");
            int exit = child.waitFor();
            if (exit == 0 && png.length == 0) throw new IOException("PNG data is missing");
            return new Result(exit, png, System.nanoTime());
        }
    }

    private record Result(int exit, byte[] png, long finishedAt) { }
}
