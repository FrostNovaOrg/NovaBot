package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.StreamerReference;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceService;
import org.frostnova.nova.core.datasource.DataSourceServiceConfig;
import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 哔哩哔哩数据源服务
 * <p>
 * 推送配置中通常只填写 uid，昵称、直播间号与头像需要在加载时向接口补全。
 * 核心通过 {@link DataSourceServiceConfig} 上的平台名找到本实现，缺少该实现时
 * 对应平台的推送配置会被整体丢弃。
 */
@Slf4j
@NovaComponent
@DataSourceServiceConfig(name = "bilibili")
public class BilibiliDataSourceService implements DataSourceService {
    /**
     * 个人空间链接中的 uid
     * <p>
     * 优先按该模式提取：链接里往往还带有 spm_id_from 之类含数字的参数，
     * 单纯取「第一串数字」会取错。
     */
    private static final Pattern SPACE_URL_UID = Pattern.compile("space\\.bilibili\\.com/(\\d{1,19})");

    /**
     * 直播间链接中的房间号，短号与真实房间号都是这一串数字
     */
    private static final Pattern LIVE_URL_ROOM = Pattern.compile("live\\.bilibili\\.com/(\\d{1,19})");

    private final BilibiliApiUtil api;

    /**
     * 房间号晚到时用来通知直播间连接重新同步。测试不关心这一路时为空
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 到点补发时用来按平台和 uid 取当前推送配置里的那位。没有挂上数据源时为空
     */
    private final Supplier<AbstractDataSource> dataSource;

    public BilibiliDataSourceService(BilibiliApiUtil api) {
        this(api, null);
    }

    public BilibiliDataSourceService(BilibiliApiUtil api, ApplicationEventPublisher eventPublisher) {
        this(api, eventPublisher, () -> null);
    }

    /**
     * 运行时用这个。数据源用 {@link ObjectProvider} 取，避免和数据源互相等着对方先造出来
     */
    @Autowired
    public BilibiliDataSourceService(BilibiliApiUtil api, ApplicationEventPublisher eventPublisher,
                                      ObjectProvider<AbstractDataSource> dataSources) {
        this(api, eventPublisher, dataSources::getIfAvailable);
    }

    public BilibiliDataSourceService(BilibiliApiUtil api, ApplicationEventPublisher eventPublisher,
                                      Supplier<AbstractDataSource> dataSource) {
        this.api = api;
        this.eventPublisher = eventPublisher;
        this.dataSource = dataSource == null ? () -> null : dataSource;
    }

    @Override
    public void completePushUser(PushUser user) {
        // 粉丝数与昵称、房间号出自同一份响应，那一趟已顺路把它带回来，此处只是用不上。
        // 这一路是推送配置里的主播。房间号晚到时通知重新同步，把直播间连上。
        fillStreamer(user, true);
    }

    /**
     * 补全主播信息，粉丝数随同一趟带回
     * <p>
     * 与 {@link #completePushUser} 是同一趟接口调用：粉丝数就躺在补全那份响应里
     * （{@code follower_num}），不为它另打一趟——控制台「找一下」要的正是这一趟。
     * 查到的是临时对象，不在推送配置里，补上房间号不发数据源变更，也不让直播间连接重新同步。
     */
    @Override
    public StreamerWithFans completeStreamerWithFans(PushUser user) {
        return fillStreamer(user, false);
    }

    /**
     * 向接口补全一位主播
     * @param user 待补全的推送用户
     * @param notifyWhenRoomAppears 房间号从无到有时是否发数据源变更。推送配置里的主播为真，控制台查询为假
     * @return 补全后的主播与粉丝数
     */
    private StreamerWithFans fillStreamer(PushUser user, boolean notifyWhenRoomAppears) {
        if (user == null || user.getUid() == null) {
            return new StreamerWithFans(user, null);
        }

        Long roomBefore = user.getRoomId();
        try {
            Up up = api.getUpInfoByUid(user.getUid());

            if (StringUtil.isBlank(user.getUname())) {
                user.setUname(up.getUname());
            }
            if (user.getRoomId() == null) {
                user.setRoomId(up.getRoomId());
            }
            if (StringUtil.isBlank(user.getFace())) {
                user.setFace(up.getFace());
            }
            if (notifyWhenRoomAppears && roomBefore == null && user.getRoomId() != null) {
                publishRoomReady(user);
            }
            return new StreamerWithFans(user, up.getFans());
        } catch (RiskCooldownException e) {
            if (notifyWhenRoomAppears) {
                // 添加或启动时只走这一趟。冷却结束前没有下一轮，到点要自己再补一次
                api.scheduleReplay(e.getEndpoint(), "complete-user:" + user.getUid(), () -> replayConfiguredUser(user));
                log.error("补全 uid {} 的信息被风控拦下, 冷却结束后再补一次: {}", user.getUid(), e.getMessage());
            } else {
                // 控制台查询这一次没补上就返回，不登记到点补发
                log.error("补全 uid {} 的信息被风控拦下: {}", user.getUid(), e.getMessage());
            }
            return new StreamerWithFans(user, null);
        } catch (Exception e) {
            // 补全失败不应导致该主播被整体丢弃：直播间号缺失只影响直播推送，动态推送仍可正常工作
            log.error("补全 uid {} 的信息失败, 该主播的直播推送可能不可用: {}", user.getUid(), e.getMessage());
            return new StreamerWithFans(user, null);
        }
    }

    /**
     * 冷却到点后补推送配置里的主播。
     * <p>
     * 冷却期间保存配置会另解析出一个对象，并用同一个键盖掉这次补发。资料还空、推送目标没改时，
     * 新对象不进入配置。到点按平台和 uid 取配置里当时的那位来补，不补当初记下的那个对象；
     * 配置里已经没有这位（冷却期间被删掉）就不再补。没有挂上数据源时无从查找，仍补传入的这位。
     */
    private void replayConfiguredUser(PushUser captured) {
        AbstractDataSource source = dataSource.get();
        if (source == null) {
            completePushUser(captured);
            return;
        }
        if (captured.getUid() == null || captured.getPlatform() == null) {
            return;
        }
        Optional<PushUser> current = source.getUser(captured.getPlatform(), captured.getUid());
        if (current.isEmpty()) {
            log.info("uid {} 已不在推送配置里, 冷却结束后不再补", captured.getUid());
            return;
        }
        completePushUser(current.get());
    }

    /**
     * 推送配置里的主播，房间号从没有变成有。直播间连接只在启动时同步一次，
     * 晚到的房间号再发一次数据源变更，已有的监听会按当前配置重新同步；
     * 已经连上的房间由那次同步自己跳过。控制台查询不走这里。
     */
    private void publishRoomReady(PushUser user) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishEvent(new NovaDataSourceChangeEvent(user, Instant.now()));
    }

    /**
     * 获取粉丝数
     * <p>
     * ⚠️ 它与 {@link #completePushUser} 打的是<b>同一个接口</b>（主播信息）。
     * 两者都要的场合（控制台「找一下」）应改走 {@link #completeStreamerWithFans}，
     * 那一趟把粉丝数顺路带回；这个口子留给只要粉丝数的场合——在补全旁边再调它
     * 一次，就退回了两趟。
     * @param uid UID
     * @return 粉丝数，取不到时为空
     */
    @Override
    public Optional<Long> getFansCount(Long uid) {
        if (uid == null) {
            return Optional.empty();
        }

        try {
            return api.getFansCount(uid);
        } catch (Exception e) {
            // 查不到粉丝数不该让整次查询失败：昵称与直播间号才是确认主播身份的主要依据
            log.debug("获取 uid {} 的粉丝数失败: {}", uid, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void completePushUsers(List<PushUser> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        log.info("开始补全 {} 个哔哩哔哩主播的信息", users.size());
        users.forEach(this::completePushUser);
        log.info("哔哩哔哩主播信息补全完毕");
    }

    @Override
    public Optional<PushUser> lookupByRoomId(Long roomId) {
        return Optional.ofNullable(lookupByRoomIdWithFans(roomId).user());
    }

    /**
     * 按直播间号查主播，粉丝数随同一趟带回
     * <p>
     * 房间号先由房间信息接口解析成 uid，再按 uid 取主播信息——粉丝数在后者那份
     * 响应里顺路带回，不另打一趟。找不到或接口失败时给空，不加重试——查主播口
     * 会把空收成原来那句「未查到」。
     */
    @Override
    public StreamerWithFans lookupByRoomIdWithFans(Long roomId) {
        if (roomId == null) {
            return new StreamerWithFans(null, null);
        }

        try {
            Up up = api.getUpInfoByRoomId(roomId);
            if (up == null || StringUtil.isBlank(up.getUname())) {
                return new StreamerWithFans(null, null);
            }

            PushUser user = new PushUser();
            user.setUid(up.getUid());
            user.setUname(up.getUname());
            user.setRoomId(up.getRoomId() != null ? up.getRoomId() : roomId);
            user.setFace(up.getFace());
            user.setPlatform(BilibiliPlatform.BILIBILI.id());
            return new StreamerWithFans(user, up.getFans());
        } catch (Exception e) {
            log.debug("按直播间号 {} 查询主播失败: {}", roomId, e.getMessage());
            return new StreamerWithFans(null, null);
        }
    }

    /**
     * 从一段文本里认出本平台的主播
     * <p>
     * 两种链接各指一种编号：个人空间链接里的是 uid，直播间链接里的是直播间号（可能是短号）。
     * 先判直播间链接：两条模式的域名不同，谁先判都一样，这个次序沿用原先在控制台一侧的写法。
     * 纯数字不在这里认——那不是本平台的链接，各平台一样对待。
     */
    @Override
    public Optional<StreamerReference> parseStreamerLink(String text) {
        if (text == null) {
            return Optional.empty();
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        Matcher live = LIVE_URL_ROOM.matcher(trimmed);
        if (live.find()) {
            return Optional.of(StreamerReference.roomId(Long.parseLong(live.group(1))));
        }

        Matcher space = SPACE_URL_UID.matcher(trimmed);
        if (space.find()) {
            return Optional.of(StreamerReference.uid(Long.parseLong(space.group(1))));
        }

        return Optional.empty();
    }

    /**
     * 平台名称，与推送配置中的 platform 字段对应
     * @return 平台名称
     */
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }
}
