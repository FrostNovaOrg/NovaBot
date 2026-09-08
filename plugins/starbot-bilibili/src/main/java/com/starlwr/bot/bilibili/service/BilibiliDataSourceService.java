package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.StreamerReference;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.datasource.DataSourceService;
import com.starlwr.bot.core.datasource.DataSourceServiceConfig;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
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
@StarBotComponent
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

    @Autowired
    public BilibiliDataSourceService(BilibiliApiUtil api) {
        this.api = api;
    }

    @Override
    public void completePushUser(PushUser user) {
        // 粉丝数与昵称、房间号出自同一份响应，那一趟已顺路把它带回来，此处只是用不上
        completeStreamerWithFans(user);
    }

    /**
     * 补全主播信息，粉丝数随同一趟带回
     * <p>
     * 与 {@link #completePushUser} 是同一趟接口调用：粉丝数就躺在补全那份响应里
     * （{@code follower_num}），不为它另打一趟——控制台「找一下」要的正是这一趟。
     */
    @Override
    public StreamerWithFans completeStreamerWithFans(PushUser user) {
        if (user == null || user.getUid() == null) {
            return new StreamerWithFans(user, null);
        }

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
            return new StreamerWithFans(user, up.getFans());
        } catch (Exception e) {
            // 补全失败不应导致该主播被整体丢弃：直播间号缺失只影响直播推送，动态推送仍可正常工作
            log.error("补全 uid {} 的信息失败, 该主播的直播推送可能不可用: {}", user.getUid(), e.getMessage());
            return new StreamerWithFans(user, null);
        }
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
