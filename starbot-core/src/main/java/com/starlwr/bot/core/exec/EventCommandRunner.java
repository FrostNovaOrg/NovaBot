package com.starlwr.bot.core.exec;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 事件触发外部命令
 * <p>
 * 打开的是那类我们想不到的用法：开播时启动录播、上舰时写一行台账、被切流时给自己发条提醒。
 * 与其为每一种猜测各做一个功能，不如给出一个能接任何东西的出口。
 *
 * <h2>为什么不经过 shell</h2>
 * 命令以<b>参数数组</b>执行，绝不走 {@code sh -c}。事件里带的是主播昵称、弹幕正文这类
 * 完全由陌生人决定的内容，一旦拼进命令行字符串，一个昵称叫 {@code ; rm -rf ~} 的观众
 * 就能让脚本执行任意命令。走参数数组时，这段文本无论长什么样都只是<b>一个参数</b>，
 * 注入在结构上就不成立——这不是过滤得够不够干净的问题，是根本不给它机会。
 *
 * <h2>其余几条约束</h2>
 * <ul>
 *     <li><b>默认关闭。</b>这是个能执行任意程序的口子，得由使用者明确打开。</li>
 *     <li><b>有超时。</b>脚本卡住不会拖住任何东西，到点就杀。</li>
 *     <li><b>有并发上限。</b>弹幕这类事件一秒能来几十条，没有上限的话一次刷屏
 *         就等于一次 fork 炸弹。超限直接丢弃并留下日志，而不是排成一条越积越长的队——
 *         等到十分钟后才执行的「开播时启动录播」没有任何意义。</li>
 *     <li><b>在独立线程池里跑。</b>事件分发是同步的，脚本跑得慢不该连累推送。</li>
 * </ul>
 */
@Slf4j
@Component
public class EventCommandRunner {
    /**
     * 单次记录的命令输出上限，超出部分截断
     * <p>
     * 脚本可能打印几十兆日志，原样写进日志文件既没用又危险。
     */
    private static final int MAX_OUTPUT_CHARS = 2000;

    /**
     * 传给命令的事件 JSON 长度上限
     */
    private static final int MAX_JSON_CHARS = 8000;

    private final StarBotCoreProperties properties;

    private final ExecutorService executor;

    /**
     * 并发许可。用信号量而不是有界队列，是为了让超限的那次<b>立刻失败并留下日志</b>，
     * 而不是排在队尾等到事件早已过期才执行
     */
    private final Semaphore permits;

    @Autowired
    public EventCommandRunner(StarBotCoreProperties properties) {
        this.properties = properties;
        this.permits = new Semaphore(Math.max(1, properties.getExec().getMaxConcurrent()));
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "event-command");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 事件到达时执行匹配的命令
     */
    @EventListener
    public void onEvent(StarBotExternalBaseEvent event) {
        StarBotCoreProperties.Exec exec = properties.getExec();
        if (!exec.isEnabled() || exec.getRules().isEmpty()) {
            return;
        }

        for (StarBotCoreProperties.ExecRule rule : exec.getRules()) {
            if (rule.isEnabled() && matches(rule, event)) {
                submit(rule, event);
            }
        }
    }

    /**
     * 判断事件是否命中规则
     * <p>
     * 沿继承链逐级比对类名，因此配 {@code LiveOnEvent} 能同时命中各平台的
     * {@code BilibiliLiveOnEvent}——按平台细分是少数需求，按事件种类配才是常态。
     * 类名写简名或全限定名都可以。
     */
    boolean matches(StarBotCoreProperties.ExecRule rule, StarBotExternalBaseEvent event) {
        String configured = rule.getEvent();
        if (configured == null || configured.isBlank()) {
            return false;
        }

        for (Class<?> type = event.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (configured.equals(type.getSimpleName()) || configured.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提交一次执行
     */
    private void submit(StarBotCoreProperties.ExecRule rule, StarBotExternalBaseEvent event) {
        List<String> command = resolve(rule.getCommand(), event);
        if (command.isEmpty()) {
            log.warn("事件命令规则 {} 没有配置可执行的命令, 已跳过", rule.getEvent());
            return;
        }

        if (!permits.tryAcquire()) {
            log.warn("事件命令并发已达上限 {}, 本次 {} 不再执行", properties.getExec().getMaxConcurrent(), rule.getEvent());
            return;
        }

        executor.execute(() -> {
            try {
                run(command, rule);
            } finally {
                permits.release();
            }
        });
    }

    /**
     * 把占位符替换为事件内容
     * <p>
     * <b>逐个参数替换，绝不拼成一整行。</b>替换结果无论含空格、引号还是分号，
     * 都仍然是原来那一个参数。
     */
    List<String> resolve(List<String> template, StarBotExternalBaseEvent event) {
        if (template == null) {
            return List.of();
        }

        LiveStreamerInfo source = event.getSource();
        List<String> command = new ArrayList<>(template.size());
        for (String argument : template) {
            if (argument == null) {
                continue;
            }
            command.add(argument
                    .replace("{event}", event.getClass().getSimpleName())
                    .replace("{platform}", nullToEmpty(event.getPlatform()))
                    .replace("{uid}", source == null ? "" : nullToEmpty(source.getUid()))
                    .replace("{uname}", source == null ? "" : nullToEmpty(source.getUname()))
                    .replace("{room_id}", source == null ? "" : nullToEmpty(source.getRoomId()))
                    .replace("{timestamp}", String.valueOf(event.getTimestamp()))
                    .replace("{json}", json(event)));
        }
        return command;
    }

    /**
     * 执行一次命令
     */
    private void run(List<String> command, StarBotCoreProperties.ExecRule rule) {
        int timeout = Math.max(1, properties.getExec().getTimeout());
        Process process = null;

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            // 命令不该从任何地方读输入：脚本一旦等在 stdin 上就会一直挂到超时
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

            process = builder.start();

            // 输出必须与等待并行地读走。管道缓冲只有几十 KB，脚本一旦写满就会阻塞在
            // 写操作上永远退不出来——于是「等它结束再读输出」会把一个正常的脚本
            // 拖成一次超时强杀，而日志里看不出任何原因
            Process started = process;
            Future<String> pending = executor.submit(() -> read(started.getInputStream()));

            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                pending.cancel(true);
                log.warn("事件命令 {} 超过 {} 秒未结束, 已强制结束", command.get(0), timeout);
                return;
            }

            String output = drain(pending);
            int code = process.exitValue();
            if (code == 0) {
                log.debug("事件命令 {} 执行完毕{}", command.get(0), output.isEmpty() ? "" : ", 输出: " + output);
            } else {
                log.warn("事件命令 {} 以状态码 {} 结束{}", command.get(0), code, output.isEmpty() ? "" : ", 输出: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            // 命令不存在、没有执行权限之类，属于配置问题，说清是哪条规则
            log.error("执行事件命令失败, 规则 {} 的命令为 {}: {}", rule.getEvent(), command.get(0), e.getMessage());
        }
    }

    /**
     * 取回并行读出的命令输出
     * <p>
     * 进程已经退出，管道随即到达 EOF，正常情况下这里立刻就有结果。
     * 留一点余量而不是无限等，是为了不让读取线程的任何意外把工作线程也拖住。
     */
    private String drain(Future<String> pending) {
        try {
            return pending.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            pending.cancel(true);
            return "";
        }
    }

    /**
     * 读取命令输出，只留开头一段，但<b>必须一直读到流结束</b>
     * <p>
     * 读够上限就撒手不管是不行的：管道缓冲只有几十 KB，没人再读的话脚本会阻塞在
     * 下一次写操作上，于是一个只是话多的正常脚本会被我们当成卡死强杀。
     * 丢弃与截断都在这一侧做，让对面始终能把话说完。
     */
    private String read(InputStream stream) throws IOException {
        byte[] head = new byte[MAX_OUTPUT_CHARS * 4];
        byte[] chunk = new byte[8192];
        int kept = 0;
        boolean truncated = false;

        int read;
        while ((read = stream.read(chunk)) != -1) {
            int room = head.length - kept;
            if (room <= 0) {
                truncated = true;
                continue;
            }
            int copy = Math.min(room, read);
            System.arraycopy(chunk, 0, head, kept, copy);
            kept += copy;
            truncated |= copy < read;
        }

        String text = new String(head, 0, kept, StandardCharsets.UTF_8).strip();
        if (text.length() > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS);
            truncated = true;
        }
        return truncated ? text + "…（已截断）" : text;
    }

    /**
     * 事件的 JSON 表示，过长时不传
     * <p>
     * 弹幕类事件序列化出来可能很大，塞进参数会撞上系统的参数长度上限，
     * 让本来能跑的命令莫名其妙地失败。
     */
    private String json(StarBotExternalBaseEvent event) {
        try {
            String text = JSON.toJSONString(event);
            return text.length() <= MAX_JSON_CHARS ? text : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 空输入设备。Windows 上是 NUL，其余平台是 /dev/null
     */
    private static File nullDevice() {
        return new File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
