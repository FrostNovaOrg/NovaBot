package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置内容校验器
 * <p>
 * 存在的意义是防止使用者把自己锁在门外：推送配置写坏后，运行期只会表现为
 * 「这个主播没有推送」，排查成本极高，因此必须在保存时就拦下。
 */
@Slf4j
@Service
public class ConfigurationValidator {
    /**
     * 单次校验最多报告的问题数
     * <p>
     * 一处结构性错误往往会连带出大量衍生问题，全部列出反而淹没真正的原因。
     */
    private static final int MAX_ISSUES = 10;

    private final NovaEventHandlerService handlerService;

    @Autowired
    public ConfigurationValidator(NovaEventHandlerService handlerService) {
        this.handlerService = handlerService;
    }

    /**
     * 校验 datasource.json 内容
     * <p>
     * 除 JSON 格式外还检查语义：处理器类名与推送平台写错时，运行期只会表现为「这个主播没有推送」，
     * 排查成本极高，因此必须在保存时就拦下。
     * @param content 文件内容
     * @param knownPlatforms 已注册的推送平台名
     * @return 问题列表，为空表示通过
     */
    public List<String> validateDatasource(String content, Set<String> knownPlatforms) {
        JSONArray users;
        try {
            users = JSON.parseArray(content);
        } catch (Exception e) {
            return List.of("内容不是合法的 JSON 数组，已拒绝保存");
        }

        if (users == null) {
            return List.of("内容不是合法的 JSON 数组，已拒绝保存");
        }

        List<String> issues = new ArrayList<>();
        Set<String> seenUsers = new HashSet<>();
        // 取「认得的」而不是「已注册的」：处理器换过包名之后，老配置里写的是旧名，
        // 运行期按别名回落照样认得出。这里若按主表拦，就成了「跑得起来却存不下去」——
        // 使用者被自己那份还能用的配置挡在保存之外，而界面不会告诉他该改哪儿
        Set<String> handlers = handlerService.getAcceptedHandlerClasses();

        for (int i = 0; i < users.size() && issues.size() < MAX_ISSUES; i++) {
            JSONObject user;
            try {
                user = users.getJSONObject(i);
            } catch (Exception e) {
                issues.add("第 " + (i + 1) + " 个主播不是对象结构");
                continue;
            }

            String where = "第 " + (i + 1) + " 个主播";

            Long uid = user.getLong("uid");
            if (uid == null) {
                issues.add(where + "缺少 uid，或 uid 不是数字");
            }

            String platform = user.getString("platform");
            if (platform == null || platform.isBlank()) {
                issues.add(where + "缺少 platform");
            }

            if (uid != null && platform != null && !seenUsers.add(platform + ":" + uid)) {
                issues.add(where + "（uid " + uid + "）重复配置，同一平台下的同一 uid 只能出现一次");
            }

            checkTargets(user.getJSONArray("targets"), where, knownPlatforms, handlers, issues);
        }

        return issues;
    }

    /**
     * 校验推送目标
     */
    private void checkTargets(JSONArray targets, String where, Set<String> knownPlatforms, Set<String> handlers, List<String> issues) {
        if (targets == null) {
            return;
        }

        for (int i = 0; i < targets.size() && issues.size() < MAX_ISSUES; i++) {
            JSONObject target;
            try {
                target = targets.getJSONObject(i);
            } catch (Exception e) {
                issues.add(where + "的第 " + (i + 1) + " 个推送目标不是对象结构");
                continue;
            }

            String at = where + "的第 " + (i + 1) + " 个推送目标";

            String platform = target.getString("platform");
            if (platform == null || platform.isBlank()) {
                issues.add(at + "缺少 platform");
            } else if (!knownPlatforms.isEmpty() && !knownPlatforms.contains(platform)) {
                issues.add(at + "的推送平台 " + platform + " 未配置，可用的有: " + String.join("、", knownPlatforms));
            }

            // 取值须与 PushTargetType 的 code 一致：FRIEND(0)、GROUP(1)。
            // 不能凭直觉写成「1 群聊、2 私聊」——2 会被解析为 UNKNOWN，运行期直接丢弃该消息
            Integer type = target.getInteger("type");
            if (type == null || (type != PushTargetType.FRIEND.getCode() && type != PushTargetType.GROUP.getCode())) {
                issues.add(at + "的 type 必须为 " + PushTargetType.GROUP.getCode() + "（群聊）或 "
                        + PushTargetType.FRIEND.getCode() + "（私聊）");
            }

            if (target.getLong("num") == null) {
                issues.add(at + "缺少 num（群号或用户号），或其不是数字");
            }

            checkChannelSettings(target, at, issues);
            checkMessages(target.getJSONArray("messages"), at, handlers, issues);
        }
    }

    private void checkChannelSettings(JSONObject target, String at, List<String> issues) {
        Object revenue = target.get("revenueVisible");
        if (revenue != null && !(revenue instanceof Boolean)) issues.add(at + "的 revenueVisible 必须为 true、false 或 null");
        Object quiet = target.get("quietHours");
        if (quiet != null) {
            if (!(quiet instanceof JSONObject q)) issues.add(at + "的 quietHours 必须为对象或 null");
            else {
                Object mode = q.get("mode");
                if (!(mode instanceof String) || !Set.of("follow", "custom", "off").contains(mode)) {
                    issues.add(at + "的 quietHours.mode 必须为 follow、custom 或 off");
                } else if ("custom".equals(mode) && (!quietTime(q.get("start")) || !quietTime(q.get("end"))
                        || java.util.Objects.equals(q.get("start"), q.get("end")))) {
                    issues.add(at + "的 quietHours 起止必须为不同的 HH:mm 时刻");
                }
                for (String field : List.of("start", "end")) {
                    if (q.get(field) != null && !quietTime(q.get(field))) issues.add(at + "的 quietHours." + field + " 必须为 HH:mm");
                }
            }
        }
        Object disabled = target.get("disabledCommands");
        if (disabled != null) {
            if (!(disabled instanceof JSONArray list)) issues.add(at + "的 disabledCommands 必须为用法名数组或 null");
            else for (Object key : list) {
                if (!(key instanceof String) || !org.frostnova.nova.core.model.ChannelSettings.COMMAND_KEYS.contains(key)) {
                    issues.add(at + "的 disabledCommands 包含不可关闭的用法：" + key);
                    break;
                }
            }
        }
    }

    private static boolean quietTime(Object value) {
        return value instanceof String text && text.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
    }

    /**
     * 校验推送消息
     */
    private void checkMessages(JSONArray messages, String at, Set<String> handlers, List<String> issues) {
        if (messages == null) {
            return;
        }

        for (int i = 0; i < messages.size() && issues.size() < MAX_ISSUES; i++) {
            JSONObject message;
            try {
                message = messages.getJSONObject(i);
            } catch (Exception e) {
                issues.add(at + "的第 " + (i + 1) + " 条推送消息不是对象结构");
                continue;
            }

            String handler = message.getString("handler");
            if (handler == null || handler.isBlank()) {
                issues.add(at + "的第 " + (i + 1) + " 条推送消息缺少 handler");
                continue;
            }

            // 处理器在容器就绪后才注册完毕，集合为空时说明尚未加载完，此时不做判定以免误报
            if (!handlers.isEmpty() && !handlers.contains(handler)) {
                issues.add(at + "的处理器 " + handler + " 未注册，请检查类名是否写错或对应插件是否已加载");
            }
        }
    }
}
