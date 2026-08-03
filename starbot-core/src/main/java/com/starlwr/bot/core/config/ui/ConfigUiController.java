package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置界面接口
 * <p>
 * 界面所需的字段表来自编译期生成的配置元数据，当前值与保存动作直接作用于 application.yml，
 * 因此界面与配置文件始终一致：不存在界面上有而配置文件里没有的字段，也不存在改了界面而文件未变的情况。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH)
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiController {
    /**
     * 配置界面根路径
     */
    public static final String BASE_PATH = "/config";

    private final ConfigurationMetadataService metadataService;

    private final ConfigurationFileService fileService;

    private final StarBotCoreProperties properties;

    private final AbstractDataSource dataSource;

    @Autowired
    public ConfigUiController(ConfigurationMetadataService metadataService,
                              ConfigurationFileService fileService,
                              StarBotCoreProperties properties,
                              AbstractDataSource dataSource) {
        this.metadataService = metadataService;
        this.fileService = fileService;
        this.properties = properties;
        this.dataSource = dataSource;
    }

    /**
     * 配置界面页面
     * @return 页面内容
     * @throws IOException 读取页面资源失败时抛出
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() throws IOException {
        try (var stream = new ClassPathResource("config-ui/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .body(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 配置项字段表
     * <p>
     * 字段来自各模块编译期生成的配置元数据，包含类型、默认值与取自 Javadoc 的中文说明。
     * @return 按分组组织的字段表
     */
    @GetMapping("/api/schema")
    public JSONObject schema() {
        JSONArray groups = new JSONArray();

        metadataService.getGroupedFields().forEach((group, fields) -> {
            JSONArray items = new JSONArray();
            for (ConfigurationMetadataService.ConfigurationField field : fields) {
                JSONObject item = new JSONObject();
                item.put("name", field.name());
                item.put("label", field.name().substring(field.name().lastIndexOf('.') + 1));
                item.put("widget", field.widget());
                item.put("description", field.description());
                item.put("defaultValue", field.defaultValue());
                items.add(item);
            }

            JSONObject node = new JSONObject();
            node.put("group", group);
            node.put("title", groupTitle(group));
            node.put("fields", items);
            groups.add(node);
        });

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("groups", groups);
        return result;
    }

    /**
     * 当前配置值，取自 application.yml
     * @return 键值映射
     */
    @GetMapping("/api/values")
    public JSONObject values() {
        JSONObject result = new JSONObject();

        try {
            result.put("success", true);
            result.put("values", fileService.read());
        } catch (IOException e) {
            log.error("读取配置文件失败", e);
            result.put("success", false);
            result.put("message", "读取配置文件失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 保存配置值到 application.yml
     * @param body 待保存的键值
     * @return 保存结果
     */
    @PostMapping("/api/values")
    public JSONObject save(@RequestBody Map<String, String> body) {
        JSONObject result = new JSONObject();

        try {
            int changed = fileService.write(body);
            result.put("success", true);
            result.put("changed", changed);
            result.put("message", changed == 0 ? "没有需要保存的改动" : "已保存 " + changed + " 项，重启后生效");
        } catch (IOException e) {
            log.error("保存配置文件失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 读取 application.yml 原始内容
     * @return 原始内容
     */
    @GetMapping("/api/raw")
    public JSONObject raw() {
        JSONObject result = new JSONObject();

        try {
            result.put("success", true);
            result.put("content", fileService.readRaw());
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "读取失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 覆盖写入 application.yml 原始内容
     * @param body 请求体，content 字段为新内容
     * @return 保存结果
     */
    @PostMapping("/api/raw")
    public JSONObject saveRaw(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        try {
            fileService.writeRaw(body.getString("content"));
            result.put("success", true);
            result.put("message", "已保存，重启后生效");
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 读取推送配置 datasource.json
     * @return 推送配置
     */
    @GetMapping("/api/datasource")
    public JSONObject datasource() {
        JSONObject result = new JSONObject();

        try {
            Path path = Path.of(properties.getDatasource().getJsonPath());
            String content = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "[]";

            result.put("success", true);
            result.put("content", content);
        } catch (IOException e) {
            log.error("读取推送配置失败", e);
            result.put("success", false);
            result.put("message", "读取失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 保存推送配置 datasource.json
     * <p>
     * 写入前会校验 JSON 格式，避免把一个无法解析的文件写进去导致下次启动失败。
     * @param body 请求体，content 字段为新内容
     * @return 保存结果
     */
    @PostMapping("/api/datasource")
    public JSONObject saveDatasource(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String content = body.getString("content");
        try {
            JSON.parseArray(content);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "内容不是合法的 JSON 数组，已拒绝保存");
            return result;
        }

        try {
            Path path = Path.of(properties.getDatasource().getJsonPath());
            if (Files.exists(path)) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);

            result.put("success", true);
            result.put("message", properties.getDatasource().isJsonAutoReload()
                    ? "已保存，配置将自动重新加载"
                    : "已保存，重启后生效");
            log.info("配置界面已更新推送配置");
        } catch (IOException e) {
            log.error("保存推送配置失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 运行状态
     * @return 当前已加载的推送用户与运行信息
     */
    @GetMapping("/api/status")
    public JSONObject status() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        List<Map<String, Object>> users = new ArrayList<>();
        dataSource.getAllUsers().forEach(user -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uid", user.getUid());
            item.put("uname", user.getUname());
            item.put("roomId", user.getRoomId());
            item.put("platform", user.getPlatform());
            item.put("enabled", user.getEnabled());
            item.put("targets", user.getTargets().size());
            users.add(item);
        });

        Runtime runtime = Runtime.getRuntime();
        JSONObject runtimeInfo = new JSONObject();
        runtimeInfo.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        runtimeInfo.put("heapMaxMb", runtime.maxMemory() / 1024 / 1024);
        runtimeInfo.put("threads", Thread.activeCount());
        runtimeInfo.put("processors", runtime.availableProcessors());

        result.put("users", users);
        result.put("runtime", runtimeInfo);
        return result;
    }

    /**
     * 为配置分组生成中文标题
     * @param group 分组键，例如 starbot.bilibili.live
     * @return 中文标题
     */
    private String groupTitle(String group) {
        Map<String, String> titles = Map.ofEntries(
                Map.entry("starbot.core.network-thread", "核心 · 网络线程"),
                Map.entry("starbot.core.log", "核心 · 日志"),
                Map.entry("starbot.core.network", "核心 · 网络"),
                Map.entry("starbot.core.datasource", "核心 · 数据源"),
                Map.entry("starbot.core.plugin", "核心 · 插件"),
                Map.entry("starbot.core.live", "核心 · 直播"),
                Map.entry("starbot.core.paint", "核心 · 绘图"),
                Map.entry("starbot.core.mail", "核心 · 邮件告警"),
                Map.entry("starbot.core.config-ui", "核心 · 配置界面"),
                Map.entry("starbot.adapter.onebot", "OneBot 适配器"),
                Map.entry("starbot.adapter.onebot.security", "OneBot 适配器 · 安全"),
                Map.entry("starbot.adapter.onebot.security.rate-limit", "OneBot 适配器 · 频率限制"),
                Map.entry("starbot.adapter.onebot.websocket-thread", "OneBot 适配器 · 线程"),
                Map.entry("starbot.adapter.onebot.detect", "OneBot 适配器 · 可用性检测"),
                Map.entry("starbot.adapter.onebot.extension.napcat", "NapCat 扩展"),
                Map.entry("starbot.bilibili.bilibili-thread", "哔哩哔哩 · 线程"),
                Map.entry("starbot.bilibili.debug", "哔哩哔哩 · 调试"),
                Map.entry("starbot.bilibili.network", "哔哩哔哩 · 网络"),
                Map.entry("starbot.bilibili.account", "哔哩哔哩 · 账号与凭据"),
                Map.entry("starbot.bilibili.live", "哔哩哔哩 · 直播"),
                Map.entry("starbot.bilibili.dynamic", "哔哩哔哩 · 动态")
        );

        return titles.getOrDefault(group, group);
    }
}
