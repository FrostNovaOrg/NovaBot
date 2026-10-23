package org.frostnova.nova.core.exec;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 事件触发外部命令
 * <p>命令按参数数组在独立线程池执行，使用固定程序名，并检查参数格式。
 * 默认关闭，执行时间与并发数由配置指定。
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

    private static final List<String> FIELDS = List.of(
            "{event}", "{platform}", "{uid}", "{uname}", "{room_id}", "{timestamp}", "{json}");

    private final NovaCoreProperties properties;

    private final ExecutorService executor;

    /**
     * 并发许可。用信号量而不是有界队列，是为了让超限的那次<b>立刻失败并留下日志</b>，
     * 而不是排在队尾等到事件早已过期才执行
     */
    private final Semaphore permits;

    /**
     * 启动检查标出的待调整规则，控制台按它显示提醒
     */
    private volatile List<RuleToAdjust> rulesToAdjust = List.of();

    /**
     * 本次运行中已记过跳过日志的规则序号，每条只记一次
     */
    private final Set<Integer> skipped = ConcurrentHashMap.newKeySet();

    @Autowired
    public EventCommandRunner(NovaCoreProperties properties) {
        this.properties = properties;
        this.permits = new Semaphore(Math.max(1, properties.getExec().getMaxConcurrent()));
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "event-command");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动时检查各条规则
     * <p>
     * 第一项含占位符的规则提示改填固定路径；其余规则用一份示例事件按执行时的规则试算，
     * 试算时会被跳过的才提示调整。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkConfiguration() {
        NovaCoreProperties.Exec exec = properties.getExec();
        if (!exec.isEnabled()) {
            rulesToAdjust = List.of();
            return;
        }
        List<RuleToAdjust> marked = new ArrayList<>();
        NovaExternalBaseEvent sample = new NovaExternalBaseEvent("sample", new LiveStreamerInfo(1L, "-sample", 1L));
        for (int index = 0; index < exec.getRules().size(); index++) {
            NovaCoreProperties.ExecRule rule = exec.getRules().get(index);
            if (!rule.isEnabled() || rule.getCommand() == null) {
                continue;
            }
            String program = rule.getCommand().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (program == null) {
                continue;
            }
            if (firstField(program) >= 0) {
                log.warn("请调整 novabot.core.exec.rules[{}].command：第一项请填写固定程序或脚本路径，"
                        + "修改后重启；写法见使用说明附录 B", index);
                marked.add(new RuleToAdjust(index, rule.getEvent()));
            } else if (resolve(rule.getCommand(), sample).isEmpty()) {
                log.warn("请调整 novabot.core.exec.rules[{}].command：事件参数请放在单列的固定 -- 之后，"
                        + "或接在固定的选项名之后（如 --name={uname}、-n{uname}），也可改用 event-environment 读取事件；"
                        + "修改后重启，写法见使用说明附录 B", index);
                marked.add(new RuleToAdjust(index, rule.getEvent()));
            }
        }
        rulesToAdjust = List.copyOf(marked);
    }

    /**
     * 启动检查标出的待调整规则，按在配置里的序号排列；未检查或没有时为空
     */
    public List<RuleToAdjust> rulesToAdjust() {
        return rulesToAdjust;
    }

    /**
     * 事件到达时执行匹配的命令
     */
    @EventListener
    public void onEvent(NovaExternalBaseEvent event) {
        NovaCoreProperties.Exec exec = properties.getExec();
        if (!exec.isEnabled() || exec.getRules().isEmpty()) {
            return;
        }

        List<NovaCoreProperties.ExecRule> rules = exec.getRules();
        for (int index = 0; index < rules.size(); index++) {
            NovaCoreProperties.ExecRule rule = rules.get(index);
            if (rule.isEnabled() && matches(rule, event)) {
                submit(index, rule, event);
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
    boolean matches(NovaCoreProperties.ExecRule rule, NovaExternalBaseEvent event) {
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
    private void submit(int index, NovaCoreProperties.ExecRule rule, NovaExternalBaseEvent event) {
        List<String> command = resolve(rule.getCommand(), event);
        if (command.isEmpty()) {
            // 每条规则每次运行只记一次，免得事件密集时刷屏
            if (skipped.add(index)) {
                log.warn("外部程序触发规则 novabot.core.exec.rules[{}]（事件 {}）已跳过：command 为空或需要按新写法调整。"
                        + "请按使用说明附录 B 修改这条规则的 command 后重启；本次运行中这条规则再被跳过时不再记录",
                        index, rule.getEvent());
            }
            return;
        }

        if (!permits.tryAcquire()) {
            log.warn("事件命令并发已达上限 {}, 本次 {} 不再执行", properties.getExec().getMaxConcurrent(), rule.getEvent());
            return;
        }

        String eventJson = rule.isEventEnvironment() ? json(event) : null;
        executor.execute(() -> {
            try {
                run(command, rule, eventJson);
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
    List<String> resolve(List<String> template, NovaExternalBaseEvent event) {
        if (template == null) {
            return List.of();
        }

        LiveStreamerInfo source = event.getSource();
        List<String> command = new ArrayList<>(template.size());
        boolean options = true;
        for (String argument : template) {
            if (argument == null) {
                continue;
            }
            int field = firstField(argument);
            if (command.isEmpty() && field >= 0) {
                return List.of();
            }
            String value = argument
                    .replace("{event}", event.getClass().getSimpleName())
                    .replace("{platform}", nullToEmpty(event.getPlatform()))
                    .replace("{uid}", source == null ? "" : nullToEmpty(source.getUid()))
                    .replace("{uname}", source == null ? "" : nullToEmpty(source.getUname()))
                    .replace("{room_id}", source == null ? "" : nullToEmpty(source.getRoomId()))
                    .replace("{timestamp}", String.valueOf(event.getTimestamp()));
            if (value.contains("{json}")) {
                value = value.replace("{json}", json(event));
            }
            if (!command.isEmpty()) {
                if (argument.equals("--")) {
                    options = false;
                } else if (options && field >= 0 && value.length() > 1 && value.startsWith("-")
                        && !namesOption(argument, field)) {
                    return List.of();
                }
            }
            command.add(value);
        }
        return command;
    }

    private static int firstField(String argument) {
        int start = argument.indexOf('{');
        while (start >= 0) {
            for (String field : FIELDS) {
                if (argument.startsWith(field, start)) {
                    return start;
                }
            }
            start = argument.indexOf('{', start + 1);
        }
        return -1;
    }

    /**
     * 第一个占位符之前的固定部分是否已写完选项名：单个 - 加选项字母，或 --选项名=
     */
    private static boolean namesOption(String argument, int field) {
        if (argument.startsWith("--")) {
            int equals = argument.indexOf('=');
            return equals > 2 && equals < field;
        }
        return field > 1;
    }

    /**
     * 执行一次命令
     */
    private void run(List<String> command, NovaCoreProperties.ExecRule rule, String eventJson) {
        int timeout = Math.max(1, properties.getExec().getTimeout());
        Process process = null;

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (eventJson != null) {
                builder.environment().put("NOVABOT_EVENT_JSON", eventJson);
            }
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
                log.warn("事件命令 {} 超过 {} 秒未结束, 已强制结束命令本身; 它拉起的程序不受影响, 会继续运行", command.get(0), timeout);
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
    private String json(NovaExternalBaseEvent event) {
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

    /**
     * 一条待调整的规则
     * @param index 在 novabot.core.exec.rules 里的序号
     * @param event 规则配置的事件名
     */
    public record RuleToAdjust(int index, String event) {
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
