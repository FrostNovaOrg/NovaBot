package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 配置内容校验器
 * <p>
 * 存在的意义是防止使用者把自己锁在门外：配置界面写坏 application.yml 后，下次启动程序起不来，
 * 而配置界面本身也随程序一起挂掉，远程部署时就只能 SSH 进去手工改文件。
 * 因此宁可在保存时拒绝，也不能让明显错误的内容落盘。
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

    private final ConfigurationMetadataService metadataService;

    private final StarBotEventHandlerService handlerService;

    @Autowired
    public ConfigurationValidator(ConfigurationMetadataService metadataService, StarBotEventHandlerService handlerService) {
        this.metadataService = metadataService;
        this.handlerService = handlerService;
    }

    /**
     * 校验 application.yml 内容
     * @param content 文件内容
     * @return 问题列表，为空表示通过
     */
    public List<String> validateApplicationYaml(String content) {
        if (content == null || content.isBlank()) {
            return List.of("内容为空，已拒绝保存");
        }

        Map<String, Object> root;
        try {
            Object parsed = new Yaml().load(content);
            if (parsed == null) {
                return List.of("内容不包含任何配置项，已拒绝保存");
            }
            if (!(parsed instanceof Map)) {
                return List.of("配置文件的顶层必须是键值对结构");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            root = map;
        } catch (MarkedYAMLException e) {
            return List.of(describe(e));
        } catch (Exception e) {
            return List.of("YAML 解析失败: " + e.getMessage());
        }

        return checkTypes(root);
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
        Set<String> handlers = handlerService.getRegisteredHandlerClasses();

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
                issues.add(at + "缺少 num（群号或 QQ 号），或其不是数字");
            }

            checkMessages(target.getJSONArray("messages"), at, handlers, issues);
        }
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

    /**
     * 按编译期元数据声明的类型检查各配置项的取值
     * <p>
     * 复用既有的元数据管线，因此新增配置项时本校验自动覆盖，无需同步维护另一份类型表。
     */
    private List<String> checkTypes(Map<String, Object> root) {
        List<String> issues = new ArrayList<>();

        // 类型表含框架自身的配置项，因此 server.port 这类不在界面上展示、但同样写错就起不来的
        // 配置也在覆盖范围内。键名以配置文件中书写的形式为准；Spring 的宽松绑定允许 camelCase
        // 等写法，那些写法在此查不到类型会被跳过而非误报——校验宁可漏报也不能拦下合法内容
        Map<String, String> types = metadataService.getKnownTypes();

        flatten("", root, (key, value) -> {
            if (issues.size() >= MAX_ISSUES || value == null) {
                return;
            }

            String type = types.get(key);
            if (type == null) {
                return;
            }

            describeMismatch(key, value, type).ifPresent(issues::add);
        });

        return issues;
    }

    /**
     * 判断取值与声明类型是否匹配
     * @return 不匹配时返回问题描述
     */
    private Optional<String> describeMismatch(String key, Object value, String type) {
        String text = String.valueOf(value);

        if (isIntegerType(type)) {
            try {
                Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return Optional.of(key + " 需要整数，当前为「" + text + "」");
            }
        } else if (isBooleanType(type) && !"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            return Optional.of(key + " 需要 true 或 false，当前为「" + text + "」");
        }

        return Optional.empty();
    }

    private boolean isIntegerType(String type) {
        return "int".equals(type) || "long".equals(type)
                || "java.lang.Integer".equals(type) || "java.lang.Long".equals(type);
    }

    private boolean isBooleanType(String type) {
        return "boolean".equals(type) || "java.lang.Boolean".equals(type);
    }

    /**
     * 将嵌套的配置结构展开为「完整路径 -> 取值」
     * <p>
     * 列表不展开：其元素多为对象结构，逐项检查的收益不足以抵消实现复杂度，
     * 这类结构由界面标记为只读并引导至配置文件页签编辑。
     */
    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> node, BiConsumer<String, Object> sink) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, sink);
            } else if (!(value instanceof List)) {
                sink.accept(key, value);
            }
        }
    }

    /**
     * 把 YAML 解析异常整理成一句能指到行的说明
     */
    private String describe(MarkedYAMLException e) {
        Mark mark = e.getProblemMark();
        String problem = e.getProblem() == null ? "格式错误" : e.getProblem();

        if (mark == null) {
            return "YAML 解析失败: " + problem;
        }

        // snakeyaml 的行号从 0 开始，此处转为与编辑器一致的从 1 开始
        return "第 " + (mark.getLine() + 1) + " 行 YAML 解析失败: " + problem;
    }
}
