package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 新版检查
 * <p>
 * 定期问一次配置的来源地址「最新的 release 是哪个」，比出「比当前跑着的新」才让结果
 * 出现在 {@code /api/status} 里，侧栏药丸与首页软待办都由那一个字段驱动。
 * <b>检查只读不写</b>：控制台不做在线更新——换了 jar 重启就是更新，
 * 让程序改自己既做不可靠（下载到一半断电就是一台起不来的机器），也不是本项目想要的形态。
 * <p>
 * 取不到结果时<b>静默</b>，只落一行 debug：来源被墙、限流、格式改版，对使用这台机器的人来说
 * 都不是故障——「查不到新版」如果变成控制台上的一条红，比不查更烦人。已取到过的结果不因
 * 后续失败清掉：那个版本确实发布过，提示它仍然是真的。
 * <p>
 * 「知道了，这版先不提醒」按版本记在运行状态里而不是浏览器里：控制台可能从任何一台设备
 * 打开，记在浏览器里等于换台电脑就要再点一次；而发布更新的版本时
 * {@link VersionOrder} 自然比出「比被跳过的那个新」，提醒随之回来。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class UpdateCheckService {
    /**
     * 「先不提醒」记在运行状态的哪个命名空间与键下
     */
    private static final String SKIP_NAMESPACE = "UpdateCheck";
    private static final String SKIP_KEY = "skippedVersion";

    /**
     * 启动后第一次检查推迟这些秒：让数据源连接、推送装配这些正事先做完
     */
    private static final long INITIAL_DELAY_SECONDS = 30;

    /**
     * 两次检查的间隔，单位：小时
     * <p>
     * release 不是分钟级的东西，一天看一次足够新鲜；问得更勤只会更快撞上来源的限流。
     */
    private static final long INTERVAL_HOURS = 24;

    /**
     * 更新说明取前几行
     */
    private static final int NOTES_LINES = 3;

    private final NovaCoreProperties properties;

    private final StarBotStateStore state;

    /**
     * 构建信息，版本号从这里来
     * <p>
     * 与控制台其余部分同一条规矩：从源码直接跑时没有 build-info，此时不知道自己是哪个版本，
     * 「有没有比它新的」就无从比起——不检查，也不提示。
     */
    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * 取数口。生产里是 {@link HttpUtil}，抽成函数口是为了让检查逻辑可以对着假来源验证：
     * 有新版、没有新版、取不到，这三种走的是同一段解析与比较代码
     */
    private final SourceClient source;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * 最近一次取到的发布版本，没取到过为 null
     */
    private volatile String latestVersion;

    private volatile List<String> latestNotes = List.of();

    private volatile String latestUrl;

    @Autowired
    public UpdateCheckService(NovaCoreProperties properties, HttpUtil http, StarBotStateStore state,
                              ObjectProvider<BuildProperties> buildProperties) {
        this(properties, state, buildProperties, http::getJson);
    }

    UpdateCheckService(NovaCoreProperties properties, StarBotStateStore state,
                       ObjectProvider<BuildProperties> buildProperties, SourceClient source) {
        this.properties = properties;
        this.state = state;
        this.buildProperties = buildProperties;
        this.source = source;
    }

    /**
     * 启动后延迟一次，此后每日一次
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        long intervalSeconds = TimeUnit.HOURS.toSeconds(INTERVAL_HOURS);
        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("update-check");
            checkNow();
        }, INITIAL_DELAY_SECONDS, intervalSeconds, TimeUnit.SECONDS);
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        scheduler.shutdownNow();
    }

    /**
     * 检查一次新版
     * <p>
     * 供调度器与需要立刻看结果的场合共用。任何一步不满足（功能关着、不知道自己什么版本、
     * 取不到、认不出取回的东西）都安静返回，不抛出去——调用方是定时任务，抛出去的后果
     * 是把这一次调度变成日志里的一行栈。
     */
    public void checkNow() {
        NovaCoreProperties.ConfigUi.Update update = properties.getConfigUi().getUpdate();
        if (!update.isEnabled()) {
            return;
        }
        String current = currentVersion();
        if (current.isBlank()) {
            return;
        }

        JSONObject release;
        try {
            release = source.fetch(update.getSource());
        } catch (Exception e) {
            log.debug("新版检查未取到结果: {}", update.getSource(), e);
            return;
        }
        if (release == null) {
            log.debug("新版检查未取到结果: {}", update.getSource());
            return;
        }

        String tag = Optional.ofNullable(release.getString("tag_name")).orElse("").strip();
        if (tag.isEmpty()) {
            // 取回来了但认不出版本号：来源格式改版或被代理改写。与取不到同样对待，
            // 只是这里没有异常可带，把认不出的原因写进那一行 debug
            log.debug("新版检查取回的内容里没有 tag_name: {}", update.getSource());
            return;
        }

        latestVersion = tag;
        latestNotes = notesOf(release.getString("body"));
        latestUrl = Optional.ofNullable(release.getString("html_url")).orElse("");
        log.debug("新版检查完成, 当前 {} 最新 {}", current, tag);
    }

    /**
     * 此刻该不该提示新版
     * <p>
     * 四个条件都成立才提示：取到过最新版本、它比当前的新、它也比被「先不提醒」跳过的那个新、
     * 当前版本本身非空。跳过的比对用 {@link VersionOrder} 而不是字符串相等：
     * 「跳过 1.11.0」不该挡住 1.12.0，也不该被 {@code v1.11.0} 这种带前缀的写法绕过。
     * @return 该提示的新版，不该提示时为空
     */
    public Optional<UpdateInfo> pendingUpdate() {
        String latest = latestVersion;
        String current = currentVersion();
        if (latest == null || current.isBlank() || VersionOrder.compare(latest, current) <= 0) {
            return Optional.empty();
        }

        String skipped = state.read(SKIP_NAMESPACE, SKIP_KEY, data -> data.getString(SKIP_KEY)).orElse("");
        if (!skipped.isBlank() && VersionOrder.compare(latest, skipped) <= 0) {
            return Optional.empty();
        }

        return Optional.of(new UpdateInfo(latest, latestNotes, latestUrl));
    }

    /**
     * 记下「这个版本先不提醒」
     * <p>
     * 只认当前正被提示着的那个版本：跳过动作来自一次页面点击，隔了几天才送达的请求
     * 或乱填的版本号不该被记成使用者的选择。记下后立即落盘——这是使用者亲手做的决定，
     * 丢了的后果是同一个版本明天再来提醒一遍，而落盘只要几十毫秒。
     * @param version 要跳过的版本号
     * @return 记下了没有。没记下说明它不是当前正被提示的版本，界面据此重取一次状态
     */
    public boolean skip(String version) {
        String latest = latestVersion;
        String wanted = version == null ? "" : version.strip();
        if (latest == null || wanted.isEmpty() || !wanted.equals(latest)) {
            return false;
        }

        state.write(SKIP_NAMESPACE, data -> data.put(SKIP_KEY, wanted));
        state.save();
        return true;
    }

    /**
     * 当前程序版本
     * <p>
     * 真源是构建期生成的 build-info，与侧栏显示的版本同一处取，两处各查各的迟早说两种话
     */
    private String currentVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "" : Optional.ofNullable(build.getVersion()).orElse("");
    }

    /**
     * 更新说明取前几行非空行
     * <p>
     * release 说明动辄几十行，侧栏面板放不下也不该放全量——「这次更新大概是什么」三行
     * 足够做出去不去看完部的决定，完整说明走链接。
     */
    private static List<String> notesOf(String body) {
        List<String> lines = new ArrayList<>();
        if (body != null) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty()) {
                    lines.add(stripped);
                }
                if (lines.size() >= NOTES_LINES) {
                    break;
                }
            }
        }
        return List.copyOf(lines);
    }

    /**
     * 取回一个地址上的 release 信息
     * <p>
     * 独立成口而不是直接调 {@link HttpUtil}：解析与比较逻辑不关心字节从哪来
     */
    @FunctionalInterface
    interface SourceClient {
        /**
         * 取回的 release JSON，取不到时抛异常或返回 null 均可，由调用方统一按失败处理
         * @param url 来源地址
         * @return release 信息
         */
        JSONObject fetch(String url);
    }

    /**
     * 一次该提示的新版：版本、说明前几行、完部链接
     */
    public record UpdateInfo(String version, List<String> notes, String url) {
    }
}
