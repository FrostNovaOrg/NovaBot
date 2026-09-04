package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.service.TotalDataStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

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
 * （判据在 {@code ConfigurationConsistencyTest}）。
 *
 * <h2>不在名单里的那两类</h2>
 * 「命令开关」与「金额可见」同样是即时生效的，但它们<b>不是 application.yml 里的配置项</b>——
 * 两者都按会话记在运行状态里，走 {@link RuntimeStateController} 那条路，本类够不着也不该够得着。
 */
@Slf4j
@Service
public class RuntimeConfigurationApplier {
    /**
     * 配置项名 → 把新值写回运行中的配置对象
     */
    private static final Map<String, BiConsumer<StarBotCoreProperties, String>> APPLIERS = new LinkedHashMap<>();

    static {
        // ---- 推送总开关 ----
        // PushGate 每次判断都重新读这一项，因此写回即生效
        APPLIERS.put("starbot.core.push.enabled", (properties, value) ->
                properties.getPush().setEnabled(Boolean.parseBoolean(value)));

        // ---- 静音时段 ----
        // 同上，PushGate 每条推送都现读一次起止时刻
        APPLIERS.put("starbot.core.push.quiet-start", (properties, value) ->
                properties.getPush().setQuietStart(value));
        APPLIERS.put("starbot.core.push.quiet-end", (properties, value) ->
                properties.getPush().setQuietEnd(value));

        // ---- 告警接收人 ----
        // QQ 这一路的收件人是「平台 + 类型 + 号码」三项合起来的一个地址，
        // 只让号码即时生效而类型要等重启，改一次群/私聊就会发到上一个地址去
        APPLIERS.put("starbot.core.alert.qq-platform", (properties, value) ->
                properties.getAlert().setQqPlatform(value));
        APPLIERS.put("starbot.core.alert.qq-type", (properties, value) ->
                properties.getAlert().setQqType(Integer.parseInt(value.trim())));
        APPLIERS.put("starbot.core.alert.qq-num", (properties, value) ->
                properties.getAlert().setQqNum(value == null || value.isBlank() ? null : Long.parseLong(value.trim())));
        APPLIERS.put("starbot.core.alert.webhook-url", (properties, value) ->
                properties.getAlert().setWebhookUrl(value));
        APPLIERS.put("starbot.core.mail.default-to", (properties, value) ->
                properties.getMail().setDefaultTo(value));
    }

    /**
     * 得经累计数据存储那一侧才落得下的配置项
     * <p>
     * 这几项同样不在配置对象上：{@code spring.data.redis.*} 是框架的键，
     * 写回 {@link StarBotCoreProperties} 无处可写。真正认这几个值的是
     * {@link TotalDataStorage}——它按新参数就地换一个后端，因此地址填好即可用，
     * 不必为此重启一次（重启会把正在采集的场次打断）。
     * <p>
     * 单列一张表，理由同下面那张：上面那张的签名只拿得到配置对象。
     * <p>
     * ⚠️ {@code database} 不在其中，仍按需重启。它与这三项一起改时，
     * 换上去的后端用的是<b>新地址与旧库号</b>，界面也照实说 database 还欠一次重启——
     * 这是一句真话，但它读起来像半件事没办完，收口时一并定去留。
     */
    private static final Map<String, BiConsumer<TotalDataStorage, String>> REDIS_APPLIERS = Map.of(
            "spring.data.redis.host", TotalDataStorage::applyHost,
            "spring.data.redis.port", (storage, value) -> storage.applyPort(Integer.parseInt(value.trim())),
            "spring.data.redis.password", TotalDataStorage::applyPassword);

    /**
     * 得经登录校验那一侧才落得下的配置项
     * <p>
     * 这两项写回配置对象<b>没有用</b>：认口令的是 {@code ConfigUiAuthService} 里那个哈希，
     * 不是配置对象上那一行。只写配置对象的话，界面会照着「即时生效」的声明说已生效，
     * 而门上认的还是旧的那一把——<b>而这件事从界面上看不出任何异常</b>。
     * <p>
     * 单列一张表而不是塞进上面那张：上面那张的签名只拿得到配置对象，
     * 为这两项把签名改宽，等于让每一条都有能力去动登录校验。
     */
    private static final Map<String, BiConsumer<ConfigUiAuthService, String>> AUTH_APPLIERS = Map.of(
            "starbot.core.config-ui.auth.password", ConfigUiAuthService::applyConfiguredPassword,
            "starbot.core.config-ui.auth.totp", (auth, value) -> auth.applyConfiguredTotp(Boolean.parseBoolean(value)));

    /**
     * 即时生效、但落地动作不在本类的配置项
     * <p>
     * 有几项<b>根本不经过设置页那条保存通道</b>：它们是列表，而设置页按设计不展示列表元素，
     * 改它们只能走各自的专门入口。落地动作因此也在那个入口里，本类的签名（一个键、一个字符串）
     * 也接不住一整份列表。
     * <p>
     * 🔴 <b>但「即时生效」这句话只有一张表。</b>本表与上面两张一起构成
     * {@link #supportedKeys()}——也就是「保存之后不需要重启的键」的全部。少了这张表的话，
     * 这几项要么被迫标成「重启后生效」（界面白让人重启一次，而它其实已经生效了），
     * 要么标成即时生效而无处对账（判据只能睁一只眼，从此谁标都行）。
     * <p>
     * 值是<b>谁去落地</b>，写清楚才对得上账：光有一份键名清单，等于给这几项签了免检。
     */
    private static final Map<String, String> APPLIED_ELSEWHERE = Map.of(
            // 机器人连接：/api/setup/bot 保存时经 BotConnectionTester#apply 当场重建连接，
            // 判据在适配器一侧（连接建起来没有、换了地址旧连接断没断）
            "starbot.adapter.onebot.senders",
            "/api/setup/bot 保存时经 BotConnectionTester#apply 当场重建连接");

    /**
     * 保存过、但要等重启才生效的配置项
     * <p>
     * 记在进程里而不是浏览器里，「等到重启」这件事才是准的：重启之后进程换了一个，这份记录随之消失，
     * 界面上那条提示自然收起。记在浏览器里的话，换台机器打开就看不见，
     * 而重启之后它还挂着——那条提示会一直在，直到有人手动点掉。
     */
    private final Set<String> pendingRestart = Collections.synchronizedSet(new LinkedHashSet<>());

    private final StarBotCoreProperties properties;

    /**
     * 登录校验，配置界面关掉时不存在
     * <p>
     * 用 {@code ObjectProvider} 而不是直接注入：本类是每台实例都有的，而登录校验那个 bean
     * 只在配置界面开着时才存在。直接注入等于让「关掉配置界面」这条路起不来。
     */
    private final Supplier<ConfigUiAuthService> authService;

    /**
     * 累计数据存储，判据台架里可能没有
     */
    private final TotalDataStorage totalDataStorage;

    @Autowired
    public RuntimeConfigurationApplier(StarBotCoreProperties properties, ObjectProvider<ConfigUiAuthService> authService,
                                       TotalDataStorage totalDataStorage) {
        this(properties, (Supplier<ConfigUiAuthService>) authService::getIfAvailable, totalDataStorage);
    }

    /**
     * 不带登录校验的那一支，供判据台架用
     * <p>
     * 台架里量的是「配置写回运行中的配置对象」这件事，与登录校验无关；而它此时的行为
     * <b>与真实的「配置界面被关掉」那一形一致</b>——两项口令配置落不下去，按需重启处理。
     */
    RuntimeConfigurationApplier(StarBotCoreProperties properties) {
        this(properties, () -> null, null);
    }

    /**
     * 带登录校验的那一支，供判据台架用
     */
    RuntimeConfigurationApplier(StarBotCoreProperties properties, ConfigUiAuthService authService) {
        this(properties, () -> authService, null);
    }

    /**
     * 带累计数据存储的那一支，供判据台架用
     */
    RuntimeConfigurationApplier(StarBotCoreProperties properties, TotalDataStorage totalDataStorage) {
        this(properties, () -> null, totalDataStorage);
    }

    private RuntimeConfigurationApplier(StarBotCoreProperties properties, Supplier<ConfigUiAuthService> authService,
                                        TotalDataStorage totalDataStorage) {
        this.properties = properties;
        this.authService = authService;
        this.totalDataStorage = totalDataStorage;
    }

    /**
     * 名单里有哪些键
     * @return 保存之后不需要重启的配置项名，含 {@link #APPLIED_ELSEWHERE} 里那些
     */
    public static Set<String> supportedKeys() {
        Set<String> keys = new LinkedHashSet<>(APPLIERS.keySet());
        keys.addAll(AUTH_APPLIERS.keySet());
        keys.addAll(APPLIED_ELSEWHERE.keySet());
        keys.addAll(REDIS_APPLIERS.keySet());
        return Collections.unmodifiableSet(keys);
    }

    /**
     * 把一批已写入配置文件的改动尽量落到运行中的程序上
     * <p>
     * 落不下的（不在名单里，或值的形式不对）一律计入待重启，并记在本实例上直到进程结束。
     * @param changes 已写入的配置项名到取值
     * @return 其中要等重启才生效的那些，按传入顺序
     */
    public List<String> applyAndTrack(Map<String, String> changes) {
        List<String> restartRequired = new ArrayList<>();

        for (Map.Entry<String, String> change : changes.entrySet()) {
            Runnable applier = resolve(change.getKey(), change.getValue());
            if (applier == null) {
                restartRequired.add(change.getKey());
                continue;
            }

            try {
                applier.run();
                log.info("配置项 {} 已即时生效", change.getKey());
            } catch (RuntimeException e) {
                // 值的形式不对时不当作已生效：界面写「已生效」而实际没变，比多重启一次糟得多
                log.warn("配置项 {} 的取值 {} 无法即时生效, 已按需重启处理: {}",
                        change.getKey(), change.getValue(), e.toString());
                restartRequired.add(change.getKey());
            }
        }

        pendingRestart.addAll(restartRequired);
        return restartRequired;
    }

    /**
     * 找出把这一项落到运行中的程序上的那个动作
     * <p>
     * 登录校验那一侧拿不到时回 null，也就是按需重启处理：配置界面被整个关掉的实例里
     * 没有这个 bean，而那种实例本来也没有设置页可以保存这两项。
     * <b>不静静跳过</b>——跳过等于对着一个没落下去的改动说「已生效」。
     * @param name 配置项名
     * @param value 取值
     * @return 落地动作，落不下时为 null
     */
    private Runnable resolve(String name, String value) {
        BiConsumer<StarBotCoreProperties, String> applier = APPLIERS.get(name);
        if (applier != null) {
            return () -> applier.accept(properties, value);
        }

        BiConsumer<TotalDataStorage, String> redis = REDIS_APPLIERS.get(name);
        if (redis != null) {
            return totalDataStorage == null ? null : () -> redis.accept(totalDataStorage, value);
        }

        BiConsumer<ConfigUiAuthService, String> auth = AUTH_APPLIERS.get(name);
        if (auth == null) {
            return null;
        }

        ConfigUiAuthService service = authService.get();
        return service == null ? null : () -> auth.accept(service, value);
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
