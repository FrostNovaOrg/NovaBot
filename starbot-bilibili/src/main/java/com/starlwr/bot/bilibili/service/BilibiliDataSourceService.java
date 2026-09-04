package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.DataSourceServiceConfig;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

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
    private final BilibiliApiUtil api;

    @Autowired
    public BilibiliDataSourceService(BilibiliApiUtil api) {
        this.api = api;
    }

    @Override
    public void completePushUser(PushUser user) {
        if (user == null || user.getUid() == null) {
            return;
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
        } catch (Exception e) {
            // 补全失败不应导致该主播被整体丢弃：直播间号缺失只影响直播推送，动态推送仍可正常工作
            log.error("补全 uid {} 的信息失败, 该主播的直播推送可能不可用: {}", user.getUid(), e.getMessage());
        }
    }

    /**
     * 获取粉丝数
     * <p>
     * ⚠️ 它与 {@link #completePushUser} 打的是<b>同一个接口</b>（主播信息），
     * 因此查一次主播会往平台去两趟。没有把两者并成一次，是因为补全推送用户
     * 在加载配置时对每一位主播都会跑一遍，而粉丝数只有添加主播那一屏要——
     * 并起来会让每次重载配置都多拉一份没人看的数据。
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

    /**
     * 平台名称，与推送配置中的 platform 字段对应
     * @return 平台名称
     */
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }
}
