package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.Sender;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * StarBot 推送平台服务，各推送平台实现应调用 addSender 方法将相关信息注册至 StarBot 中
 */
@Slf4j
@Service
public class NovaSenderService {
    private final NovaCoreProperties properties;

    private final Map<String, Sender> senders = new HashMap<>();

    /**
     * 适配器登记时自报的显示名，只给人看。没报过的平台不进这张表，
     * {@link #displayName(String)} 回落成标识串本身。
     */
    private final Map<String, String> displayNames = new HashMap<>();

    @Autowired
    public NovaSenderService(NovaCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 从配置文件加载推送平台
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        properties.getSender().forEach(this::addSender);
        log.info("已加载 {} 个推送平台: [{}]", senders.size(), senders.values().stream().map(Sender::getName).collect(Collectors.joining(", ")));
    }

    /**
     * 根据推送平台名称获取推送平台信息
     * @param name 推送平台名称
     * @return 推送平台信息
     */
    public Optional<Sender> getSender(String name) {
        return Optional.ofNullable(senders.get(name));
    }

    /**
     * 获取全部已注册的推送平台名称
     * <p>
     * 供推送配置的保存前校验使用：平台名写错时应在保存时就指出来，而不是运行期才以
     * 「未找到推送平台配置, 已丢弃消息」的形式表现出来。
     * @return 已注册的推送平台名称
     */
    public Set<String> getSenderNames() {
        return Set.copyOf(senders.keySet());
    }

    /**
     * 添加推送平台
     * @param sender 推送平台信息
     */
    public synchronized void addSender(@NonNull Sender sender) {
        addSender(sender, null);
    }

    /**
     * 添加推送平台，并记下给人看的名字
     * <p>
     * 显示名由适配器在登记时自报，不是配置项。没报或报空时，界面与接口都回落成标识串。
     * @param sender 推送平台信息
     * @param displayName 给人看的名字；空则不记
     */
    public synchronized void addSender(@NonNull Sender sender, String displayName) {
        if (sender.getName() == null) {
            throw new IllegalArgumentException("推送平台名称不能为空, 请检查 application.yml 配置文件");
        }

        if (senders.containsKey(sender.getName())) {
            throw new IllegalArgumentException("已存在名称为 " + sender.getName() + " 的推送平台, 请检查 application.yml 配置文件中是否存在重名的推送平台配置");
        }

        senders.put(sender.getName(), sender);
        if (displayName != null && !displayName.isBlank()) {
            displayNames.put(sender.getName(), displayName.trim());
        }
        log.info("已注册推送平台 {}", sender.getName());
    }

    /**
     * 推送平台给人看的名字
     * <p>
     * 适配器登记时自报过就用那一份；没报过、报空、或这个标识根本没登记过，
     * 都回落成标识串本身，不会给出空或 null。
     * @param platform 推送平台标识
     * @return 显示名；{@code platform} 为 null 时给空串
     */
    public String displayName(String platform) {
        if (platform == null) {
            return "";
        }
        String name = displayNames.get(platform);
        return (name == null || name.isBlank()) ? platform : name;
    }
}
