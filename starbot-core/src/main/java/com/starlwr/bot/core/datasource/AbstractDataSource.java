package com.starlwr.bot.core.datasource;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.exception.DataSourceException;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据源抽象类
 */
@Slf4j
public abstract class AbstractDataSource {
    protected final ApplicationEventPublisher eventPublisher;

    private final DataSourceServiceRegistry dataSourceServiceRegistry;

    private final StarBotEventHandlerService handlerService;

    protected final List<PushUser> users = new ArrayList<>();

    private final Map<String, Map<Long, PushUser>> userMap = new HashMap<>();

    @Autowired
    public AbstractDataSource(ApplicationEventPublisher eventPublisher, DataSourceServiceRegistry dataSourceServiceRegistry, StarBotEventHandlerService handlerService) {
        this.eventPublisher = eventPublisher;
        this.dataSourceServiceRegistry = dataSourceServiceRegistry;
        this.handlerService = handlerService;
    }


    /**
     * 加载数据源，读取完毕后需调用 add 方法将推送用户添加至数据源中
     * PushUser 仅须填充 uid, platform, enabled, targets 字段
     * PushTarget 仅须填充 user, platform, type, num, enabled, messages 字段
     * PushMessage 仅须填充 target, event, handler, params, enabled 字段
     */
    public abstract void load();

    /**
     * 添加推送用户
     * @param user 推送用户
     */
    public synchronized void add(@NonNull PushUser user) {
        add(Collections.singletonList(user));
    }

    /**
     * 批量添加推送用户
     * @param users 推送用户列表
     */
    public synchronized void add(@NonNull List<PushUser> users) {
        users.removeIf(user -> !user.getEnabled());

        if (new HashSet<>(users).size() != users.size()) {
            throw new DataSourceException("推送用户列表中存在重复的用户");
        }

        for (PushUser user: users) {
            if (this.userMap.containsKey(user.getPlatform()) && this.userMap.get(user.getPlatform()).containsKey(user.getUid())) {
                throw new DataSourceException("数据源中已存在该推送用户 (平台: " + user.getPlatform() + ", UID: " + user.getUid() + "), 无法重复添加");
            }
        }

        for (PushUser user: users) {
            user.getTargets().removeIf(target -> !target.getEnabled());
            for (PushTarget target: user.getTargets()) {
                target.getMessages().removeIf(message -> !message.getEnabled());
            }
        }

        Map<String, List<PushUser>> platformMap = users.stream().collect(Collectors.groupingBy(PushUser::getPlatform));
        Set<String> notSupportedPlatforms = new HashSet<>();
        for (String platform: platformMap.keySet()) {
            dataSourceServiceRegistry.getDataSourceService(platform).ifPresentOrElse(
                    service -> service.completePushUsers(platformMap.get(platform)),
                    () -> {
                        log.warn("未找到数据源服务实现类: {}, 请安装相应平台推送插件", platform);
                        notSupportedPlatforms.add(platform);
                    }
            );
        }

        users.removeIf(user -> notSupportedPlatforms.contains(user.getPlatform()));

        this.users.addAll(users);
        for (PushUser user: users) {
            this.userMap.computeIfAbsent(user.getPlatform(), k -> new HashMap<>()).put(user.getUid(), user);

            initPushMessageParams(user);

            log.info("新增推送用户: (UID: {}, 昵称: {}, 房间号: {}, 平台: {})", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());

            StarBotDataSourceAddEvent event = new StarBotDataSourceAddEvent(user, Instant.now());
            eventPublisher.publishEvent(event);
        }
    }

    /**
     * 移除推送用户
     * @param user 推送用户
     */
    public synchronized void remove(@NonNull PushUser user) {
        if (!this.userMap.containsKey(user.getPlatform()) || !this.userMap.get(user.getPlatform()).containsKey(user.getUid())) {
            throw new DataSourceException("数据源中不存在该推送用户 (平台: " + user.getPlatform() + ", UID: " + user.getUid() + "), 无需移除");
        }

        dataSourceServiceRegistry.getDataSourceService(user.getPlatform())
                .orElseThrow(() -> new DataSourceException("未找到数据源服务实现类: " + user.getPlatform()))
                .completePushUser(user);

        this.userMap.get(user.getPlatform()).remove(user.getUid());
        if (this.userMap.get(user.getPlatform()).isEmpty()) {
            this.userMap.remove(user.getPlatform());
        }
        this.users.remove(user);

        log.info("移除推送用户: (UID: {}, 昵称: {}, 房间号: {}, 平台: {})", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());

        StarBotDataSourceRemoveEvent event = new StarBotDataSourceRemoveEvent(user, Instant.now());
        eventPublisher.publishEvent(event);
    }

    /**
     * 更新推送用户
     * @param user 推送用户
     */
    public synchronized void update(@NonNull PushUser user) {
        update(Collections.singletonList(user));
    }

    /**
     * 批量更新推送用户
     * @param users 推送用户列表
     */
    public synchronized void update(@NonNull List<PushUser> users) {
        List<PushUser> adds = new ArrayList<>();
        List<PushUser> removes = new ArrayList<>();
        List<PushUser> updates = new ArrayList<>();

        for (PushUser user : users) {
            if (user.getEnabled() && (!this.userMap.containsKey(user.getPlatform()) || !this.userMap.get(user.getPlatform()).containsKey(user.getUid()))) {
                adds.add(user);
            } else if (!user.getEnabled() && this.userMap.containsKey(user.getPlatform()) && this.userMap.get(user.getPlatform()).containsKey(user.getUid())) {
                removes.add(user);
            } else {
                updates.add(user);
            }
        }

        add(adds);
        for (PushUser user : removes) {
            remove(user);
        }

        updates.removeIf(user -> !user.getEnabled());
        for (PushUser user: updates) {
            user.getTargets().removeIf(target -> !target.getEnabled());
            for (PushTarget target: user.getTargets()) {
                target.getMessages().removeIf(message -> !message.getEnabled());
            }
        }

        Map<String, List<PushUser>> platformMap = updates.stream().collect(Collectors.groupingBy(PushUser::getPlatform));
        Set<String> notSupportedPlatforms = new HashSet<>();
        for (String platform: platformMap.keySet()) {
            dataSourceServiceRegistry.getDataSourceService(platform).ifPresentOrElse(
                    service -> service.completePushUsers(platformMap.get(platform)),
                    () -> {
                        log.warn("未找到数据源服务实现类: {}, 请安装相应平台推送插件", platform);
                        notSupportedPlatforms.add(platform);
                    }
            );
        }

        updates.removeIf(user -> notSupportedPlatforms.contains(user.getPlatform()));

        for (PushUser user : updates) {
            PushUser oldUser = this.userMap.get(user.getPlatform()).get(user.getUid());
            if (oldUser == null) {
                throw new DataSourceException("数据源中不存在该推送用户 (平台: " + user.getPlatform() + ", UID: " + user.getUid() + "), 无法更新");
            }

            if (oldUser.same(user)) {
                continue;
            }

            this.userMap.get(user.getPlatform()).put(user.getUid(), user);

            initPushMessageParams(user);

            boolean unameChanged = !Objects.equals(oldUser.getUname(), user.getUname());
            boolean faceChanged = !Objects.equals(oldUser.getFace(), user.getFace());
            if (unameChanged) {
                log.info("推送用户 (UID: {}, 房间号: {}, 平台: {}) 昵称由 {} 更新为 {}", user.getUid(), user.getRoomIdString(), user.getPlatform(), oldUser.getUname(), user.getUname());
            }
            if (faceChanged) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 头像由 {} 更新为 {}", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform(), oldUser.getFace(), user.getFace());
            }
            if (!unameChanged && !faceChanged) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 推送配置已更新", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());
            }

            StarBotDataSourceUpdateEvent event = new StarBotDataSourceUpdateEvent(oldUser, user, Instant.now());
            eventPublisher.publishEvent(event);
        }
    }

    /**
     * 初始化推送消息参数
     * @param user 推送用户
     */
    private void initPushMessageParams(@NonNull PushUser user) {
        for (PushTarget target: user.getTargets()) {
            for (PushMessage message: target.getMessages()) {
                Optional<StarBotEventHandler> optionalHandler = handlerService.getHandler(message.getHandler());
                if (optionalHandler.isPresent()) {
                    StarBotEventHandler handler = optionalHandler.get();
                    message.setHandlerInstance(handler);
                    message.setEventClass(handler.getEventType());
                    message.setParamsJsonObject(handler.getDefaultParams());
                } else {
                    message.setHandlerInstance(null);
                    message.setEventClass(null);
                    message.setParamsJsonObject(null);
                    log.error("不存在的事件处理器: {}, 请检查推送配置", message.getHandler());
                    continue;
                }

                if (StringUtil.isNotBlank(message.getParams())) {
                    try {
                        JSONObject params = JSON.parseObject(message.getParams());
                        for (Map.Entry<String, Object> entry : params.entrySet()) {
                            message.getParamsJsonObject().put(entry.getKey(), entry.getValue());
                        }
                    } catch (Exception e) {
                        log.error("解析推送消息参数失败, 请检查格式是否正确: {}", message.getParams(), e);
                    }
                }
            }

            target.getMessages().removeIf(message -> message.getHandlerInstance() == null);
        }
    }

    /**
     * 获取推送用户列表
     * @return 推送用户列表
     */
    public List<PushUser> getAllUsers() {
        return new ArrayList<>(users);
    }

    /**
     * 根据直播平台获取推送用户列表
     * @param livePlatform 直播平台
     * @return 推送用户列表
     */
    public List<PushUser> getUsers(@NonNull String livePlatform) {
        return Optional.ofNullable(this.userMap.get(livePlatform))
                .map(map -> new ArrayList<>(map.values()))
                .orElse(new ArrayList<>());
    }

    /**
     * 根据直播平台和 UID 获取推送用户
     * @param livePlatform 直播平台
     * @param uid UID
     * @return 推送用户
     */
    public Optional<PushUser> getUser(@NonNull String livePlatform, @NonNull Long uid) {
        if (this.userMap.containsKey(livePlatform)) {
            return Optional.ofNullable(this.userMap.get(livePlatform).get(uid));
        } else {
            return Optional.empty();
        }
    }
}
