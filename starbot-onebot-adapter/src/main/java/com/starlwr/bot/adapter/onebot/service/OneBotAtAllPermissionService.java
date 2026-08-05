package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.sender.AtAllPermissionResolver;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @全体成员 权限判定
 * <p>
 * <b>存在的理由是一个 QQ 的漏洞。</b>2026-08-05 实测：一个在群里只是普通成员、
 * 客户端里根本点不出 @全体成员 的账号，<b>经 OneBot 接口发送 {@code [CQ:at,qq=all]}
 * 却能真的 @ 到全体</b>，并照常扣减每日额度——服务端这一路没有做客户端那道权限检查。
 * <p>
 * 能做不等于该做：绕过权限强行 @ 全体属于滥用，有招致账号风控的风险，
 * 而风控的代价是整个推送链路停摆。因此这里主动补上客户端那道检查：
 * <b>机器人不是群主或管理员时，把 @全体成员 摘掉</b>。
 * <p>
 * 判据用的是群内角色而非 {@code get_group_at_all_remain} 的 {@code can_at_all}——
 * 后者实测在无权限的群里同样返回 {@code true}，它报的是额度不是权限。
 * <p>
 * 查不出角色时**放行**：宁可保持原有行为，也不要因为一次接口抖动就悄悄吞掉
 * 使用者明确配置过的 @全体成员。
 */
@Slf4j
@StarBotComponent
public class OneBotAtAllPermissionService implements AtAllPermissionResolver {
    /**
     * 判定结果的缓存时长
     * <p>
     * 群内角色变动很少，但也不该永久缓存——被提为管理员后总得能生效。
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final OneBotHttpAdapter http;

    /**
     * 发送服务反过来经核心的发送器间接依赖本类，直接注入会形成循环依赖
     */
    private final ObjectProvider<OneBotHttpService> httpService;

    /**
     * 「平台:群号」到判定结果的缓存
     * <p>
     * 条目数至多等于配置过的群数，是个很小的量，因此不引入缓存库，
     * 自带过期时间的 map 足够——为几行逻辑给插件模块加一个依赖不划算。
     */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OneBotAtAllPermissionService(OneBotHttpAdapter http, ObjectProvider<OneBotHttpService> httpService) {
        this.http = http;
        this.httpService = httpService;
    }

    @Override
    public boolean supports(@NonNull String platform) {
        return resolveSender(platform) != null;
    }

    @Override
    public boolean canAtAll(@NonNull String platform, @NonNull Long num) {
        OneBotSender sender = resolveSender(platform);
        // supports 与本方法之间平台可能已被移除，取不到就放行
        return sender == null || canAtAll(sender, num);
    }

    private OneBotSender resolveSender(String platform) {
        OneBotHttpService service = httpService.getIfAvailable();
        return service == null ? null : service.getSender(platform);
    }

    /**
     * 判断机器人在指定群里能否 @全体成员
     * @param sender 推送平台信息
     * @param groupId 群号
     * @return 是否允许；查不出时按允许处理
     */
    public boolean canAtAll(@NonNull OneBotSender sender, @NonNull Long groupId) {
        String key = sender.getName() + ":" + groupId;
        Cached cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.allowed();
        }

        boolean allowed = resolve(sender, groupId);
        cache.put(key, new Cached(allowed, Instant.now()));
        return allowed;
    }

    /**
     * 一条带过期时间的判定结果
     */
    private record Cached(boolean allowed, Instant at) {
        boolean expired() {
            return Instant.now().isAfter(at.plus(CACHE_TTL));
        }
    }

    /**
     * 查询机器人在群里的角色并据此判定
     */
    private boolean resolve(OneBotSender sender, Long groupId) {
        try {
            Long selfId = selfId(sender);
            if (selfId == null) {
                log.debug("未能取得 {} 的登录账号, @全体成员 权限判定放行", sender.getName());
                return true;
            }

            JSONObject params = new JSONObject()
                    .fluentPut("group_id", groupId)
                    .fluentPut("user_id", selfId);
            JSONObject member = http.getGroupMemberInfo(sender, params);
            String role = member == null ? null : member.getString("role");

            boolean allowed = "owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
            if (!allowed) {
                log.info("机器人在群 {} 中的角色为 {}, 没有 @全体成员 的权限, 相关占位符将被移除。" +
                        "绕过权限强行 @ 全体有风控风险, 如确需使用请先把机器人设为群管理员", groupId, role);
            }
            return allowed;
        } catch (Exception e) {
            // 接口抖动不该悄悄吞掉使用者明确配置过的 @全体成员
            log.warn("查询机器人在群 {} 的角色失败, @全体成员 权限判定放行: {}", groupId, e.getMessage());
            return true;
        }
    }

    /**
     * 取得机器人自己的账号
     */
    private Long selfId(OneBotSender sender) {
        return Optional.ofNullable(http.getLoginInfo(sender, new JSONObject()))
                .map(info -> info.getLong("user_id"))
                .orElse(null);
    }
}
