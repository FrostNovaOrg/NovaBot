package com.starlwr.bot.core.service;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 累计数据存储：配没配、连不连得上，都在运行期回答
 * <p>
 * 5.0 的做法是启动时按 {@code spring.data.redis.host} 有没有值择一注册两个 Bean 中的一个。
 * 那等于把「有没有累计数据」这件事<b>钉死在进程启动的那一刻</b>：新装的机器在控制台里填好地址、
 * 保存成功、界面说「已生效」，而菜单里那两条「总」字命令要等到下次重启才出现——
 * 中间这段时间没有任何地方说得清为什么。反过来 Redis 半夜挂了，进程会一直宣称累计数据可用，
 * 查出来的却是一片 0，<b>而 0 与「真的没人送礼」长得一模一样</b>。
 * <p>
 * 所以判定改成两问：<b>配了没有</b>（地址有值）与<b>此刻连不连得上</b>（探活）。
 * 两问都过才叫可用；地址改了就地换一个后端，连不上就降级，连回来就自己恢复，全程不重启。
 *
 * <h2>探活为什么带缓存、为什么不在请求线程上做</h2>
 * 这个判定是被<b>高频问</b>的：每开一次菜单、每刷一次首页、每执行一条命令都要问一遍。
 * 每次都真去 PING 一趟，等于把一次网络往返挂在这些路径上。因此结果缓存
 * {@value #PROBE_CACHE_MILLIS} 毫秒——这个数是「掉线后最迟多久界面开始说实话」与
 * 「多久问一次不算打扰」之间的取舍：几秒的滞后在使用者眼里察觉不到，
 * 而再长就会出现「Redis 早连上了，菜单还是不列」这种解释不清的画面。
 * <p>
 * 但缓存只挡得住<b>问得勤</b>，挡不住<b>探得慢</b>：主机丢包（地址不可达、包被丢弃）
 * 时连接根本建立不起来，一次探活要等满连接超时才肯死心。谁在请求线程上等这一趟，
 * 谁就把首页与菜单挂住整整一个超时。因此探活一律在<b>后台线程</b>上做：
 * 请求线程只读缓存，缓存过期就派一趟后台探活、照报上一个已知值；
 * 另有一条后台定时刷新，没人问也按窗口期重探，掉线不靠下一次打开菜单才被发现。
 */
@Slf4j
@Service
public class TotalDataStorage implements DisposableBean {
    /**
     * 探活结果的缓存时长（毫秒）
     */
    public static final long PROBE_CACHE_MILLIS = 5_000L;

    /**
     * 连不上时，一条命令最多等多久
     * <p>
     * 框架默认 60 秒。那个默认值配的是「后台任务偶尔慢一次」，而这里的调用方是首页与菜单：
     * Redis 一挂，使用者点开控制台就是一分钟白屏。
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 建立连接本身最多等多久
     * <p>
     * 命令超时管的是「连上了之后一条指令等多久」，管不到建连那一段——
     * 主机丢包（地址不可达或包被丢弃）时，建连会一直等到内核放弃为止。
     * 客户端默认 10 秒；探活挪到后台后这一趟不再挂住任何页面，但 10 秒
     * 意味着后台探活线程一次占满 10 秒、掉线要两个窗口才说得清，收到与命令同级。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 累计存储的连接参数
     *
     * @param host 地址，留空表示没配这个能力
     * @param port 端口
     * @param password 密码，未设密码时为空
     * @param database 库号
     */
    public record Settings(String host, int port, String password, int database) {
        /**
         * 默认值，与框架自身的默认一致
         */
        public static final Settings UNSET = new Settings(null, 6379, null, 0);

        /**
         * 填了地址才算配过
         * @return 配过为 true
         */
        public boolean configured() {
            return host != null && !host.isBlank();
        }

        /**
         * 能写进日志与界面的一句话
         * <p>
         * <b>不含密码</b>：这句话会出现在健康自检的摘要里，而那一页是给人看的。
         * @return 形如 {@code 127.0.0.1:6379/0}
         */
        public String describe() {
            return host + ":" + port + (database == 0 ? "" : "/" + database);
        }
    }

    /**
     * 按一组参数造一个连接工厂
     * <p>
     * 单列成一个可替换的口子，是因为判据要在<b>不装 Redis</b> 的机器上量「地址改了会不会换后端」
     * 与「连不上会不会降级」——这两件事跟 Redis 本身没关系，跟本类怎么换后端有关系。
     * 公开而非包内可见：菜单那一条判据在另一个模块里。
     */
    @FunctionalInterface
    public interface ConnectionFactories {
        /**
         * @param settings 连接参数，调用方保证 {@link Settings#configured()} 为真
         * @return 连接工厂
         */
        RedisConnectionFactory create(Settings settings);
    }

    private final ConnectionFactories factories;

    private final LongSupplier clock;

    /**
     * 探活在哪儿跑
     * <p>
     * 可注入是为判据：量「请求线程不等探活」要能把探活派到一个<b>收下但先不跑</b>的执行器上，
     * 那样调用返回时探活压根还没发生——比拿秒表量耗时确定得多。
     */
    private final Executor probeExecutor;

    /**
     * 自己起的那个后台探活线程，销毁时收掉；判据台架里为 null（台架的执行器不是线程）
     */
    private final ScheduledExecutorService ownedProbe;

    /**
     * 一趟探活还在跑时不重复派
     * <p>
     * 没有它，「主机丢包」那一档会让每次判定都再派一趟：一趟还没等满连接超时死心，
     * 下一趟又排上了——排队的都是注定白等的。
     */
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    private final Object lock = new Object();

    private volatile Settings settings;

    /**
     * 当前后端，没配或建不起来时为空
     */
    private volatile RedisTotalDataStore store;

    private volatile boolean probed;

    private volatile boolean reachable;

    private volatile long probedAt;

    @Autowired
    public TotalDataStorage(Environment environment) {
        this(readFrom(environment), TotalDataStorage::lettuce, System::currentTimeMillis, daemonProbeThread());
    }

    /**
     * 供判据用的那一支：连接工厂与钟都换成假的
     * <p>
     * 钟也要能换：探活带缓存，而「缓存到期之后才重新探」这件事拿真钟量得靠等——
     * 等出来的判据在慢机器上会偶尔红，那种红比不量还糟。
     * <p>
     * 公开是因为判据不止在本包：健康自检、菜单、运行期换后端那几处的台架都从这一支进
     * （三参与四参的区别只在探活执行器——四参那支只有本包的判据用）。
     * 探活执行器是「当场直跑」的那一个：判据要的是确定的读数，这一支保持旧语义——
     * 调用返回时探活已经做完。
     * @param initial 起始连接参数
     * @param factories 建连接工厂的那一手
     * @param clock 当前时刻（毫秒）
     */
    public TotalDataStorage(@NonNull Settings initial, @NonNull ConnectionFactories factories,
                            @NonNull LongSupplier clock) {
        this(initial, factories, clock, Runnable::run);
    }

    /**
     * 供判据用的那一支，探活执行器可换（包内可见：判据都在同包）
     * <p>
     * 传一个收下任务但不跑的执行器，量出来的就是「调用返回时探活还没发生」；
     * 传后台线程，量出来的就是生产那一支的形态。
     * @param initial 起始连接参数
     * @param factories 建连接工厂的那一手
     * @param clock 当前时刻（毫秒）
     * @param probeExecutor 探活在哪儿跑
     */
    TotalDataStorage(@NonNull Settings initial, @NonNull ConnectionFactories factories,
                     @NonNull LongSupplier clock, @NonNull Executor probeExecutor) {
        this.settings = initial;
        this.factories = factories;
        this.clock = clock;
        this.probeExecutor = probeExecutor;
        this.ownedProbe = probeExecutor instanceof ScheduledExecutorService scheduler ? scheduler : null;
        if (ownedProbe != null) {
            // 生产那一支：传进来的执行器就是自己的后台线程，定时刷新也挂它身上。
            // 被派的探活与定时那一趟共用一条线程，排队而不并发，去重那边正好挡住重活
            ownedProbe.scheduleWithFixedDelay(this::refresh, PROBE_CACHE_MILLIS, PROBE_CACHE_MILLIS, TimeUnit.MILLISECONDS);
        }
        rebuild();
    }

    /**
     * 生产那一支的后台探活线程：单线程、守护
     * <p>
     * 守护是刻意的：探活是判定，不是数据，不值得为一个判定拦住进程退出。
     */
    private static ScheduledExecutorService daemonProbeThread() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "total-data-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 累计数据此刻可不可用
     * <p>
     * 「配了」与「连得上」都要为真。菜单列不列那两条、首页那条待办出不出、
     * {@code /api/status} 的 {@code totalDataAvailable} 是什么，问的都是这一个方法。
     * <p>
     * 这个方法<b>从不碰网络</b>：只读缓存；缓存过期就派一趟后台探活、照报上一个已知值。
     * 还没探过（刚起或刚换后端）时答不可用——宁可让紧接着的下一次轮询说真话，
     * 也不让请求线程等一趟可能等满连接超时的网络。
     * @return 可用为 true
     */
    public boolean isAvailable() {
        RedisTotalDataStore current = store;
        if (current == null) {
            return false;
        }

        long now = clock.getAsLong();
        if (probed && now - probedAt < PROBE_CACHE_MILLIS) {
            return reachable;
        }

        // 缓存过期或还没探过：派一趟后台探活，这里照报上一个已知值
        kickProbe();
        return reachable;
    }

    /**
     * 真去探一趟，把结果记进缓存
     * <p>
     * 被派出去的、后台线程定时跑的，都是它。
     */
    void probeNow() {
        synchronized (lock) {
            RedisTotalDataStore latest = store;
            boolean ok = latest != null && latest.reachable();
            if (probed && ok != reachable) {
                // 两个方向都记一行：掉线那一刻与恢复那一刻，在别处都没有痕迹
                if (ok) {
                    log.info("累计数据存储 {} 已恢复，「总数据」类查询重新可用", settings.describe());
                } else {
                    log.warn("累计数据存储 {} 连不上，已降级为只有本场数据", settings.describe());
                }
            }
            reachable = ok;
            probed = true;
            probedAt = clock.getAsLong();
        }
    }

    /**
     * 后台定时那一趟：缓存过期就探，没人问也按窗口期刷新
     * <p>
     * 没有它，掉线要等下一次有人打开菜单才被发现——「没人问」不该等于「不用知道」。
     */
    private void refresh() {
        if (store == null) {
            return;
        }

        long now = clock.getAsLong();
        if (probed && now - probedAt < PROBE_CACHE_MILLIS) {
            return;
        }

        kickProbe();
    }

    /**
     * 派一趟探活到后台；一趟没跑完时不重复派
     * <p>
     * 派不出去（执行器正在销毁）也不抛：探活本来就允许失败，这一趟没派成的
     * 后果只是缓存多旧一会儿，不值得把异常甩给刚好来问的请求线程。
     */
    private void kickProbe() {
        if (!probeInFlight.compareAndSet(false, true)) {
            return;
        }

        try {
            probeExecutor.execute(() -> {
                try {
                    probeNow();
                } finally {
                    probeInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            probeInFlight.set(false);
        }
    }

    /**
     * 配过累计存储没有
     * <p>
     * 与 {@link #isAvailable()} 分开问：<b>没配是正常的部署形态，连不上是故障</b>，
     * 健康自检对这两者的说法完全不同，而它们在「查不到累计数据」这个现象上一模一样。
     * @return 填了地址为 true
     */
    public boolean isConfigured() {
        return settings.configured();
    }

    /**
     * 当前连的是哪儿，供日志与界面显示
     * @return 形如 {@code 127.0.0.1:6379}，没配时为空串
     */
    public String describeTarget() {
        Settings snapshot = settings;
        return snapshot.configured() ? snapshot.describe() : "";
    }

    /**
     * 此刻可用的后端，不可用时为空
     * <p>
     * 拿到的是<b>问过一次探活之后</b>的后端：连不上时一律回空，让调用方走「没有累计数据」那条路，
     * 而不是拿着一个连不上的后端去查，查回来一片 0。
     * @return 后端，不可用时为 null
     */
    public RedisTotalDataStore active() {
        return isAvailable() ? store : null;
    }

    /**
     * 换一个地址，就地生效
     * @param host 地址，留空表示不用累计存储
     */
    public void applyHost(String host) {
        replace(new Settings(host, settings.port(), settings.password(), settings.database()));
    }

    /**
     * 换一个端口，就地生效
     * @param port 端口
     */
    public void applyPort(int port) {
        replace(new Settings(settings.host(), port, settings.password(), settings.database()));
    }

    /**
     * 换一个密码，就地生效
     * @param password 密码，未设密码时留空
     */
    public void applyPassword(String password) {
        replace(new Settings(settings.host(), settings.port(), password, settings.database()));
    }

    /**
     * 换一个库号，就地生效
     * @param database 库号
     */
    public void applyDatabase(int database) {
        replace(new Settings(settings.host(), settings.port(), settings.password(), database));
    }

    @Override
    public void destroy() {
        // 先收后台线程再拆后端：定时那一趟若正卡在建连上，这里会把它打断，
        // 免得进程退出后还留着一条等超时的线程
        if (ownedProbe != null) {
            ownedProbe.shutdownNow();
        }

        synchronized (lock) {
            RedisTotalDataStore current = store;
            store = null;
            if (current != null) {
                current.shutdown();
            }
        }
    }

    /**
     * 参数没变就什么都不做
     * <p>
     * 保存一次配置会把改动逐项送进来，三项里改了一项就重建一次的话，
     * 一次保存会连着建三个连接工厂；而没变的那两项本来一次都不该动它。
     */
    private void replace(Settings next) {
        synchronized (lock) {
            if (next.equals(settings)) {
                return;
            }
            settings = next;
            rebuild();
        }
    }

    /**
     * 按当前参数换一个后端
     * <p>
     * 换完<b>不当场探活</b>，只把探活结果作废：新地址通不通由下一次有人问或后台定时
     * 派出的那一趟去探，在这里探等于把一次可能长达数秒的网络等待挂在「保存配置」那个按钮上。
     * <p>
     * 建不起来时后端置空（表现为「连不上」），而不是留着上一个继续用：
     * 留着的话，配置文件里写的是新地址，跑着的是旧地址，<b>而界面会说已生效</b>。
     */
    private void rebuild() {
        Settings snapshot = settings;
        RedisTotalDataStore previous = store;
        RedisTotalDataStore next = null;

        if (snapshot.configured()) {
            try {
                next = new RedisTotalDataStore(factories.create(snapshot));
            } catch (Exception e) {
                log.error("按 Redis {} 建累计数据存储失败", snapshot.describe(), e);
            }
        }

        store = next;
        probed = false;
        reachable = false;

        if (previous != null) {
            previous.shutdown();
        }

        if (next != null) {
            log.info("累计数据存储已指向 Redis {}，「总数据」类查询在连得上时可用", snapshot.describe());
        } else if (snapshot.configured()) {
            log.warn("累计数据存储 {} 未能建立，「总数据」类查询不可用", snapshot.describe());
        } else {
            log.info("未配置累计数据存储，只有本场数据；在设置页「采集」组填 spring.data.redis.host 即可启用");
        }
    }

    /**
     * 起始参数取自配置，与框架读同一批键
     */
    private static Settings readFrom(Environment environment) {
        return new Settings(
                environment.getProperty("spring.data.redis.host"),
                environment.getProperty("spring.data.redis.port", Integer.class, 6379),
                environment.getProperty("spring.data.redis.password"),
                environment.getProperty("spring.data.redis.database", Integer.class, 0));
    }

    /**
     * 自己造连接工厂，不借框架自动配好的那一个
     * <p>
     * 框架那一个的参数是启动时绑定的，改不动——而本类存在的理由正是「地址能在运行期换」。
     * 两个来源混用会更糟：有时用框架那个、有时用自己造的，出问题时说不清连的到底是哪儿。
     * <p>
     * 包内可见是给接线判据用的：判据造一个工厂、读回它的建连与命令超时（只读配置面，
     * 不碰网络——建连是惰性的，要 {@code afterPropertiesSet} 之后才真发生）。
     */
    static RedisConnectionFactory lettuce(Settings settings) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(settings.host(), settings.port());
        standalone.setDatabase(settings.database());
        if (settings.password() != null && !settings.password().isBlank()) {
            standalone.setPassword(RedisPassword.of(settings.password()));
        }

        // 建连超时 spring-data-redis 没有直设口，只能下到 lettuce 的 ClientOptions 这一层；
        // lettuce 坐标本模块已自持声明（版本由 parent BOM 管），不再只靠 starter 传递带进来
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(CONNECT_TIMEOUT)
                                .build())
                        .build())
                .commandTimeout(COMMAND_TIMEOUT)
                .shutdownTimeout(Duration.ofMillis(200))
                .build();

        return new LettuceConnectionFactory(standalone, client);
    }
}
