package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.service.TotalDataStorage;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 把保存下来的配置改动落到运行中的程序上
 * <p>
 * 绝大多数配置项是启动时读一次就不再回头看的，改了只能等重启。但有几项<b>「等重启」等于没有这项功能</b>：
 * 「临时静音」要的就是现在生效，为此重启一次会把正在采集的场次打断；告警接收人配错了的时候，
 * 正需要告警的往往就是此刻。
 * <p>
 * <b>能即时生效的键是这里这份显式名单，不是推断出来的。</b>「把新值写回配置对象」这个动作本身
 * 到处都能做，但它只在<b>读取方每次都重新读</b>的前提下才真的管用——启动时把值抄进自己字段的组件，
 * 改了配置对象它也看不见。哪几项满足这个前提是一件需要逐项确认的事，写成名单才有地方确认；
 * 靠「凡是标了即时生效的都试着写回去」，就会出现界面说已生效而实际没有的情形，
 * 且<b>没有任何提示说它没生效</b>。
 * <p>
 * 名单与字段上的 {@code @ConfigEffect} 标注必须一一对应，两边对不上时构建会红
 * （判据在 {@code ConfigurationConsistencyTest}）。登录口令与二次验证开关标的也是即时生效，
 * 但落地在 {@code /config/api/auth} 专用口，不进本名单——通用保存若把它们写回，
 * 一枚已登录会话就能换掉门。
 *
 * <h2>不在名单里的那两类</h2>
 * 「命令开关」与「金额可见」同样是即时生效的，但它们<b>不是 application.yml 里的配置项</b>——
 * 两者都按会话记在运行状态里，走运行状态接口那条路，本类够不着也不该够得着。
 */
@Slf4j
@Service
public class RuntimeConfigurationApplier {
    /**
     * 配置项名 → 把新值写回运行中的配置对象
     */
    private static final Map<String, BiConsumer<NovaCoreProperties, String>> APPLIERS = new LinkedHashMap<>();

    static {
        // ---- 推送总开关 ----
        // PushGate 每次判断都重新读这一项，因此写回即生效
        APPLIERS.put("novabot.core.push.enabled", (properties, value) ->
                properties.getPush().setEnabled(Boolean.parseBoolean(value)));
        // 每次跟提示前都现读，写回即生效。关掉时不认领，留给以后打开时的第一条
        APPLIERS.put("novabot.core.push.first-push-tip", (properties, value) ->
                properties.getPush().setFirstPushTip(Boolean.parseBoolean(value)));

        // ---- 静音时段 ----
        // 同上，PushGate 每条推送都现读一次起止时刻
        APPLIERS.put("novabot.core.push.quiet-start", (properties, value) ->
                properties.getPush().setQuietStart(value));
        APPLIERS.put("novabot.core.push.quiet-end", (properties, value) ->
                properties.getPush().setQuietEnd(value));

        // ---- 告警接收人 ----
        APPLIERS.put("novabot.core.alert.webhook-url", (properties, value) ->
                properties.getAlert().setWebhookUrl(value));
        APPLIERS.put("novabot.core.mail.default-to", (properties, value) ->
                properties.getMail().setDefaultTo(value));

        // ---- 配置备份保留份数 ----
        // 下次保存时现读，写回即生效
        APPLIERS.put("novabot.core.config-ui.backup-keep", (properties, value) ->
                properties.getConfigUi().setBackupKeep(
                        TimestampedFileBackup.clamp(Integer.parseInt(value.trim()))));
    }

    /**
     * 得经累计数据存储那一侧才落得下的配置项
     * <p>
     * 这几项同样不在配置对象上：{@code spring.data.redis.*} 是框架的键，
     * 写回 {@link NovaCoreProperties} 无处可写。真正认这几个值的是
     * {@link TotalDataStorage}——它按新参数就地换一个后端，因此地址填好即可用，
     * 不必为此重启一次（重启会把正在采集的场次打断）。
     * <p>
     * 单列一张表，理由同下面那张：上面那张的签名只拿得到配置对象。
     */
    private static final Map<String, BiConsumer<TotalDataStorage, String>> REDIS_APPLIERS = Map.of(
            "spring.data.redis.host", TotalDataStorage::applyHost,
            "spring.data.redis.port", (storage, value) -> storage.applyPort(Integer.parseInt(value.trim())),
            "spring.data.redis.password", TotalDataStorage::applyPassword,
            "spring.data.redis.database", (storage, value) -> storage.applyDatabase(Integer.parseInt(value.trim())));

    /**
     * 即时生效、但落地动作不在本类的配置项
     * <p>
     * 有几项<b>根本不经过设置页那条保存通道</b>：它们是列表，而设置页按设计不展示列表元素，
     * 改它们只能走各自的专门入口。落地动作因此也在那个入口里，本类的签名（一个键、一个字符串）
     * 也接不住一整份列表。
     * <p>
     * 🔴 <b>但「即时生效」这句话只有一张表。</b>本表与上面那张一起构成
     * {@link #supportedKeys()}——也就是「保存之后不需要重启的键」的全部。少了这张表的话，
     * 这几项要么被迫标成「重启后生效」（界面白让人重启一次，而它其实已经生效了），
     * 要么标成即时生效而无处对账（判据只能睁一只眼，从此谁标都行）。
     * <p>
     * 值是<b>谁去落地</b>，写清楚才对得上账：光有一份键名清单，等于给这几项签了免检。
     * <p>
     * 核心自有表为空。平台相关的项由 {@link RuntimeConfigurationApplierContributor#appliedElsewhere()} 申报。
     */
    private static final Map<String, String> APPLIED_ELSEWHERE = Map.of();

    /**
     * 保存过、但要等重启才生效的配置项
     * <p>
     * 记在进程里而不是浏览器里，「等到重启」这件事才是准的：重启之后进程换了一个，这份记录随之消失，
     * 界面上那条提示自然收起。记在浏览器里的话，换台机器打开就看不见，
     * 而重启之后它还挂着——那条提示会一直在，直到有人手动点掉。
     */
    private final Set<String> pendingRestart = Collections.synchronizedSet(new LinkedHashSet<>());

    private final NovaCoreProperties properties;

    /**
     * 累计数据存储，判据台架里可能没有
     */
    private final TotalDataStorage totalDataStorage;

    /**
     * 插件申报的即时生效项。核心自有表不在这里。
     */
    private final Map<String, Consumer<String>> contributedAppliers;

    /**
     * 插件申报的「另有专门入口落地」项。核心自有表不在这里。
     */
    private final Map<String, String> contributedAppliedElsewhere;

    /**
     * 事件时间线
     * <p>
     * 🔴 <b>只记改了哪一组，不记改成了什么。</b>时间线是逐行落在磁盘上的
     * {@code timeline/*.jsonl}，而经这里过的键里就有 Redis 口令与告警接收人；
     * 把取值抄进去，等于给配置文件里那几项做了一份不设防的副本。
     * 「昨天谁动了推送那一组」这个问题，光靠组名就答得了。
     */
    private final TimelineWriter timeline;

    @Autowired
    public RuntimeConfigurationApplier(NovaCoreProperties properties, TotalDataStorage totalDataStorage,
                                       ObjectProvider<RuntimeConfigurationApplierContributor> contributors,
                                       TimelineWriter timeline) {
        this(properties, totalDataStorage, contributors.orderedStream().toList(), timeline);
    }

    RuntimeConfigurationApplier(NovaCoreProperties properties, TotalDataStorage totalDataStorage,
                                Collection<RuntimeConfigurationApplierContributor> contributors,
                                TimelineWriter timeline) {
        this.properties = properties;
        this.totalDataStorage = totalDataStorage;
        Contributed contributed = mergeContributions(contributors);
        this.contributedAppliers = contributed.appliers();
        this.contributedAppliedElsewhere = contributed.elsewhere();
        this.timeline = timeline;
    }

    private record Contributed(Map<String, Consumer<String>> appliers, Map<String, String> elsewhere) {
    }

    private static Contributed mergeContributions(
            Collection<RuntimeConfigurationApplierContributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return new Contributed(Map.of(), Map.of());
        }

        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        Map<String, String> elsewhere = new LinkedHashMap<>();
        for (RuntimeConfigurationApplierContributor contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            mergeAppliers(contributor.appliers(), appliers, elsewhere);
            mergeElsewhere(contributor.appliedElsewhere(), appliers, elsewhere);
        }
        return new Contributed(Collections.unmodifiableMap(appliers), Collections.unmodifiableMap(elsewhere));
    }

    private static void mergeAppliers(Map<String, Consumer<String>> declared,
                                      Map<String, Consumer<String>> appliers,
                                      Map<String, String> elsewhere) {
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, Consumer<String>> entry : declared.entrySet()) {
            String key = entry.getKey();
            Consumer<String> applier = entry.getValue();
            if (key == null || applier == null) {
                continue;
            }
            rejectDuplicate(key, appliers, elsewhere);
            appliers.put(key, applier);
        }
    }

    private static void mergeElsewhere(Map<String, String> declared,
                                       Map<String, Consumer<String>> appliers,
                                       Map<String, String> elsewhere) {
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String key = entry.getKey();
            String who = entry.getValue();
            if (key == null || who == null) {
                continue;
            }
            rejectDuplicate(key, appliers, elsewhere);
            elsewhere.put(key, who);
        }
    }

    private static void rejectDuplicate(String key,
                                        Map<String, Consumer<String>> appliers,
                                        Map<String, String> elsewhere) {
        if (APPLIERS.containsKey(key)
                || APPLIED_ELSEWHERE.containsKey(key)
                || REDIS_APPLIERS.containsKey(key)
                || appliers.containsKey(key)
                || elsewhere.containsKey(key)) {
            throw new IllegalStateException("即时生效配置项 " + key + " 被写了两次");
        }
    }

    /**
     * 判据台架的构造口：要哪几个侧件按需给（包内可见：台架都在同包）
     * <p>
     * 台架里带不带累计数据存储，是<b>每条判据各取所需</b>的事；
     * 缺省就是「都没有」：与真实的「也没配累计存储」那一形一致。
     * @param properties 配置对象
     * @return 构造器
     */
    static Bench bench(NovaCoreProperties properties) {
        return new Bench(properties);
    }

    /**
     * {@link #bench} 的构造器
     */
    static final class Bench {
        private final NovaCoreProperties properties;
        private TotalDataStorage totalDataStorage;
        private Collection<RuntimeConfigurationApplierContributor> contributors = List.of();
        private TimelineWriter timeline = TimelineWriter.NONE;

        private Bench(NovaCoreProperties properties) {
            this.properties = properties;
        }

        /**
         * 带上累计数据存储
         * @param totalDataStorage 累计数据存储
         * @return 本构造器
         */
        Bench totalDataStorage(TotalDataStorage totalDataStorage) {
            this.totalDataStorage = totalDataStorage;
            return this;
        }

        /**
         * 带上插件申报的即时生效项
         * @param contributors 插件申报
         * @return 本构造器
         */
        Bench contributors(Collection<RuntimeConfigurationApplierContributor> contributors) {
            this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
            return this;
        }

        /**
         * 带上时间线写入口，缺省是不记
         * <p>
         * 缺省不记与别的侧件同法：大多数判据问的是「落没落下去」，
         * 那几条不该因为要记一条时间线而各自准备一个收集器。
         * @param timeline 时间线写入口
         * @return 本构造器
         */
        Bench timeline(TimelineWriter timeline) {
            this.timeline = timeline == null ? TimelineWriter.NONE : timeline;
            return this;
        }

        /**
         * @return 按给出的侧件装配好的实例
         */
        RuntimeConfigurationApplier build() {
            return new RuntimeConfigurationApplier(properties, totalDataStorage, contributors, timeline);
        }
    }

    /**
     * 核心自有名单与贡献者申报合并后的即时生效键
     * <p>
     * 构建期那道尺在别的模块里，够不着判据台架的包内构造口，走这一条。
     * @param contributors 插件申报，空或 null 时即核心自有名单
     * @return 保存之后不需要重启的配置项名
     */
    public static Set<String> supportedKeys(Collection<RuntimeConfigurationApplierContributor> contributors) {
        // 这一支只把几张表的键名并起来，一个字也不往运行中的程序上落，因此没有可记的
        return new RuntimeConfigurationApplier(new NovaCoreProperties(), null, contributors,
                TimelineWriter.NONE).supportedKeys();
    }

    /**
     * 名单里有哪些键
     * @return 保存之后不需要重启的配置项名，含 {@link #APPLIED_ELSEWHERE} 里那些
     */
    public Set<String> supportedKeys() {
        Set<String> keys = new LinkedHashSet<>(APPLIERS.keySet());
        keys.addAll(APPLIED_ELSEWHERE.keySet());
        keys.addAll(REDIS_APPLIERS.keySet());
        keys.addAll(contributedAppliers.keySet());
        keys.addAll(contributedAppliedElsewhere.keySet());
        return Collections.unmodifiableSet(keys);
    }

    /**
     * 把一批已写入配置文件的改动尽量落到运行中的程序上
     * <p>
     * 落不下的（不在名单里，或值的形式不对）一律计入待重启记在本实例上，改对了的键当场划掉。
     * @param changes 已写入的配置项名到取值
     * @return 其中要等重启才生效的那些，按传入顺序
     */
    public List<String> applyAndTrack(Map<String, String> changes) {
        List<String> restartRequired = new ArrayList<>();
        List<String> applied = new ArrayList<>();

        for (Map.Entry<String, String> change : changes.entrySet()) {
            Runnable applier = resolve(change.getKey(), change.getValue());
            if (applier == null) {
                restartRequired.add(change.getKey());
                continue;
            }

            try {
                applier.run();
                log.info("配置项 {} 已即时生效", change.getKey());
                pendingRestart.remove(change.getKey());
                applied.add(change.getKey());
            } catch (RuntimeException e) {
                // 值的形式不对时不当作已生效：界面写「已生效」而实际没变，比多重启一次糟得多
                log.warn("配置项 {} 的取值 {} 无法即时生效, 已按需重启处理: {}",
                        change.getKey(), change.getValue(), e.toString());
                restartRequired.add(change.getKey());
            }
        }

        // 两种结局各记一条：合成一条「保存了」的话，「明明改了却没生效」
        // 与「改了、等重启」在日志页上长得一样，而后者是要人去点重启的
        record(TimelineEventType.SETTINGS_APPLIED, "已即时生效", applied);
        record(TimelineEventType.SETTINGS_RESTART_PENDING, "要等重启才生效", restartRequired);

        pendingRestart.addAll(restartRequired);
        return restartRequired;
    }

    /**
     * 把一批键记成一条时间线，<b>只写组名与条数</b>
     * <p>
     * 🔴 取值一个字都不进来，理由见 {@link #timeline}。连完整键名也不写：
     * {@code spring.data.redis.password} 这样的键名本身就把「这台机器的 Redis 有口令」
     * 说了出去，而日志页是登录后随手就能翻的一页。
     * @param type 记成哪一类
     * @param what 一句人话里的动词部分
     * @param keys 本次的配置项名，空表时不记
     */
    private void record(TimelineEventType type, String what, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }

        Set<String> groups = new LinkedHashSet<>();
        for (String key : keys) {
            groups.add(groupOf(key));
        }

        timeline.record(TimelineEvent.of(type, TimelineEvent.Level.INFO)
                .text("改了" + String.join("、", groups) + " " + keys.size() + " 项，" + what)
                .detail("groups", String.join(",", groups))
                .detail("count", String.valueOf(keys.size()))
                .build());
    }

    /**
     * 配置项名属于哪一组：去掉最后一段
     * <p>
     * 最后一段正是「改的是哪一项」，而组名答的是「动的是哪一摊」。
     * 没有点号的键（不该出现，但外部传进来的东西不该让这里抛）整段当组名。
     */
    private static String groupOf(String key) {
        int cut = key.lastIndexOf('.');
        return cut <= 0 ? key : key.substring(0, cut);
    }

    /**
     * 找出把这一项落到运行中的程序上的那个动作
     * <p>
     * 登录口令、二次验证与启动令牌通道不在这里落地：通用保存若直接改，一枚已登录会话就能换掉门。
     * 那几项走专用口。出现在本方法里时按需重启处理——不静静跳过，也不动手改门。
     * @param name 配置项名
     * @param value 取值
     * @return 落地动作，落不下时为 null
     */
    private Runnable resolve(String name, String value) {
        if (ConfigUiAuthService.isDedicatedAuthKey(name)) {
            log.warn("配置项 {} 不能经通用保存改，请走登录与安全专用口", name);
            return null;
        }

        BiConsumer<NovaCoreProperties, String> applier = APPLIERS.get(name);
        if (applier != null) {
            return () -> applier.accept(properties, value);
        }

        BiConsumer<TotalDataStorage, String> redis = REDIS_APPLIERS.get(name);
        if (redis != null) {
            return totalDataStorage == null ? null : () -> redis.accept(totalDataStorage, value);
        }

        Consumer<String> contributed = contributedAppliers.get(name);
        if (contributed != null) {
            return () -> contributed.accept(value);
        }

        return null;
    }

    /**
     * 至今保存过、仍在等重启的配置项
     * @return 配置项名
     */
    public List<String> getPendingRestart() {
        synchronized (pendingRestart) {
            return List.copyOf(pendingRestart);
        }
    }
}
