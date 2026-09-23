package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.exception.ResponseCodeException;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.util.FixedSizeSetQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态推送服务
 * <p>
 * 通过轮询当前账号的动态流发现新动态。由于动态流只包含已关注 UP 主的内容，
 * 需要先确保配置了动态推送的 UP 主均已被关注。
 */
@Slf4j
@NovaComponent
public class BilibiliDynamicService {
    /**
     * 已推送动态 ID 的记忆容量，用于跨轮次去重
     */
    private static final int PUSHED_CACHE_SIZE = 1000;

    /**
     * 开播动态的类型，即哔哩哔哩在 UP 主开播时自动生成的那条动态
     */
    private static final String LIVE_DYNAMIC_TYPE = "DYNAMIC_TYPE_LIVE_RCMD";

    /**
     * 关注列表的复核周期
     * <p>
     * 推送名单没有新主播时，关注列表隔这么久才拉一次，用来发现在哔哩哔哩上被取消的关注。
     * 自动关注间隔只决定多久看一次推送名单：以前每个间隔都把关注列表整份拉一遍，
     * 默认 30 秒一次，一天近三千次请求，拉回来的几乎总是同一份。
     */
    private static final Duration FOLLOWING_RECHECK_INTERVAL = Duration.ofHours(1);

    private final BilibiliApiUtil api;

    private final BilibiliAccountService accountService;

    private final NovaBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    /**
     * 已推送过的动态 ID
     */
    private final FixedSizeSetQueue<String> pushed = new FixedSizeSetQueue<>(PUSHED_CACHE_SIZE);

    /**
     * 是否已完成首轮采集
     * <p>
     * 首轮只记录动态 ID 而不推送，避免启动时把动态流里的历史动态全部补推一遍。
     */
    private volatile boolean initialized;

    private volatile AbstractDataSource dataSource;

    /**
     * 上一次完整核对关注列表时推送名单里的 uid，名单里出现不在此列的 uid 就立即再核对
     */
    private volatile Set<Long> checkedUids = Set.of();

    /**
     * 上一次完整核对关注列表所用的登录账号 uid，换了账号就立即再核对
     */
    private volatile Long checkedLoginUid;

    /**
     * 下一次复核关注列表的时刻，为空表示还没完整核对过
     */
    private volatile Instant nextFollowingCheckAt;

    @Autowired
    public BilibiliDynamicService(BilibiliApiUtil api,
                                  BilibiliAccountService accountService,
                                  NovaBilibiliProperties properties,
                                  ApplicationEventPublisher publisher,
                                  @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.api = api;
        this.accountService = accountService;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
    }

    /**
     * 启动动态推送
     * @param dataSource 数据源
     */
    public void start(AbstractDataSource dataSource) {
        this.dataSource = dataSource;

        scheduler.scheduleAtFixedRate(this::poll,
                Duration.ofSeconds(Math.max(5, properties.getDynamic().getApiRequestInterval())));

        if (properties.getDynamic().isAutoFollow()) {
            scheduler.scheduleAtFixedRate(this::followConfiguredUps,
                    Duration.ofSeconds(Math.max(10, properties.getDynamic().getAutoFollowInterval())));
        }

        log.info("动态推送已启动, 检测间隔 {} 秒", Math.max(5, properties.getDynamic().getApiRequestInterval()));
    }

    /**
     * 执行一轮动态检测
     */
    private void poll() {
        AbstractDataSource source = this.dataSource;
        if (source == null || !accountService.isLoggedIn()) {
            return;
        }

        List<Dynamic> dynamics;
        try {
            dynamics = api.getDynamicUpdateList();
        } catch (ResponseCodeException e) {
            if (e.getCode() == BilibiliApiUtil.CODE_NOT_LOGGED_IN) {
                // 立即复检以更新登录态并发出告警，不必干等到下一个复检周期。
                // 复检会把登录态置回未登录，本方法开头的判断随即拦下后续轮询，因此不会反复触发
                accountService.verify();
            } else {
                log.warn("获取动态列表失败, 接口返回错误代码 {}: {}", e.getCode(), e.getMessage());
            }
            return;
        } catch (Exception e) {
            log.debug("获取动态列表失败, 疑为网络故障: {}", e.getMessage());
            return;
        }

        Map<Long, Up> ups = source.getUsers(BilibiliPlatform.BILIBILI.id()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .map(Up::new)
                .filter(up -> up.getUid() != null)
                .collect(Collectors.toMap(Up::getUid, up -> up, (first, second) -> first));

        Instant earliest = Instant.now().minus(Duration.ofMinutes(Math.max(1, properties.getDynamic().getPushMinutes())));

        for (Dynamic dynamic : dynamics) {
            logDedupProbe(dynamic);

            if (dynamic.getId() == null || pushed.contains(dynamic.getId())) {
                continue;
            }

            pushed.add(dynamic.getId());

            if (!initialized) {
                continue;
            }

            // 开播动态和开播推送是同一件事的两条通道，两条都放行就会同一个群收到两遍。
            // 这里只看顶层类型：转发开播动态是 UP 主自己的动作，照常推送
            if (LIVE_DYNAMIC_TYPE.equals(dynamic.getType()) && !properties.getDynamic().isPushLiveDynamic()) {
                log.debug("已跳过开播动态: {}", dynamic.getUrl());
                continue;
            }

            Up up = dynamic.getAuthorUid().map(ups::get).orElse(null);
            if (up == null) {
                continue;
            }

            // 过旧的动态不再推送，避免关注新 UP 主时把其历史动态全部补推
            Instant publishTime = dynamic.getPublishTime().orElse(Instant.now());
            if (publishTime.isBefore(earliest)) {
                continue;
            }

            log.info("检测到 {} 的新动态: {}", up.getUname(), dynamic.getUrl());
            publisher.publishEvent(new BilibiliDynamicUpdateEvent(up, dynamic, describeAction(dynamic), dynamic.getUrl(), publishTime));
        }

        initialized = true;
    }

    /**
     * 依据动态类型描述其动作
     * @param dynamic 动态
     * @return 动作描述
     */
    private String describeAction(Dynamic dynamic) {
        if (dynamic.getType() == null) {
            return "发布了动态";
        }

        return switch (dynamic.getType()) {
            case "DYNAMIC_TYPE_AV" -> "投稿了视频";
            case "DYNAMIC_TYPE_FORWARD" -> "转发了动态";
            case "DYNAMIC_TYPE_ARTICLE" -> "投稿了专栏";
            case "DYNAMIC_TYPE_MUSIC" -> "投稿了音频";
            case LIVE_DYNAMIC_TYPE -> "开播了";
            case "DYNAMIC_TYPE_PGC", "DYNAMIC_TYPE_UGC_SEASON" -> "更新了番剧";
            default -> "发布了动态";
        };
    }

    private void followConfiguredUps() {
        followConfiguredUps(Instant.now());
    }

    /**
     * 关注配置中尚未关注的 UP 主
     * <p>
     * 动态流仅包含已关注 UP 主的动态，未关注则无法收到其动态更新。
     * <p>
     * 关注列表只在这几种时候拉：还没完整核对过、换了登录账号、推送名单里加了主播、
     * 距上次核对满复核周期。关注列表没取全时本轮一个都不补关注，下一轮再试——
     * 拿残表去比，没取到那几页里的已关注主播都会被当成没关注，再关注一遍。
     * <p>
     * 补关注失败的主播不单独重试，等下一次复核：按检查间隔反复重试只会把关注请求推得更密。
     * @param now 当前时刻
     */
    void followConfiguredUps(Instant now) {
        AbstractDataSource source = this.dataSource;
        if (source == null || !accountService.isLoggedIn()) {
            return;
        }

        Set<Long> configured = source.getUsers(BilibiliPlatform.BILIBILI.id()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .map(org.frostnova.nova.core.model.PushUser::getUid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // 删掉或停用后再加回的主播若仍留在已核对名单里，要等满一小时复核才补关注，这期间他的动态推不出来
        checkedUids = Set.copyOf(checkedUids.stream().filter(configured::contains).toList());

        if (configured.isEmpty()) {
            return;
        }

        Long loginUid = accountService.getLoginUid();
        Instant nextCheckAt = this.nextFollowingCheckAt;
        boolean due = nextCheckAt == null
                || !now.isBefore(nextCheckAt)
                || !java.util.Objects.equals(loginUid, checkedLoginUid)
                || !checkedUids.containsAll(configured);
        if (!due) {
            return;
        }

        Set<Long> following;
        try {
            following = api.getFollowingUps(loginUid).stream()
                    .map(Up::getUid)
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            log.warn("未能取得完整的关注列表, 本轮不补关注, 下一轮再试: {}", e.getMessage());
            return;
        }

        checkedUids = Set.copyOf(configured);
        checkedLoginUid = loginUid;
        nextFollowingCheckAt = now.plus(FOLLOWING_RECHECK_INTERVAL);

        configured.stream()
                .filter(uid -> !following.contains(uid))
                .forEach(api::followUp);
    }

    /**
     * 去重定键的取证探针（4.3.1 起，先证据后实现）
     * <p>
     * 要回答的只有一个问题：<b>同一条内容被推了两次时，两条记录的 {@code basic.rid_str}
     * 是不是同一个</b>。是则键定为它，不是则假说否掉、重新取证——
     * <b>键定死之前不写去重</b>。
     *
     * <h2>为什么只记三个字段，不开整份原始报文</h2>
     * 早先的写法是打开 {@code dynamic-raw-message-log}。那个开关打的是
     * <b>整份 feed 响应</b>——关注列表里所有人发的动态正文、昵称、图片地址，
     * 一轮一整份，全落进主日志。而假说要判的字段<b>早就解析在模型上了</b>
     * （{@code parseDynamic} 里有 {@code setBasic}），所以只记 id/rid/type 就够，
     * 证据完全等价，别人的正文一个字都不进日志。据此改裁窄探针。
     *
     * <h2>为什么是 INFO 不是 DEBUG</h2>
     * 取证要它进日志文件，而文件级别默认是 INFO。写成 DEBUG 就得把整个 DEBUG 打开，
     * 那又会把一堆别的东西拖进来——<b>为了看一行而开一扇门</b>。
     * 默认关闭，取证期间才开；取证毕即关并清理，探针行里的 rid/id 按关联信息处置。
     */
    private void logDedupProbe(Dynamic dynamic) {
        if (!properties.getDebug().isDynamicDedupProbe()) {
            return;
        }

        String rid = dynamic.getBasic() == null ? null : dynamic.getBasic().getString("rid_str");
        log.info("动态去重探针: id={} rid={} type={}", dynamic.getId(), rid, dynamic.getType());
    }
}
