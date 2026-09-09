package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.StarBotEventHandlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 推送页接口：全局开关、处理器清单、默认模板、最近推送
 * <p>
 * 从核心配置界面拆出：这些端点只服务推送页，随控制台插件走。
 * 路径不变，仍挂在 {@code /config/api/…} 下。
 */
@Slf4j
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH)
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class PushController {
    /**
     * 推送记录的时间格式
     */
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RuntimeConfigurationApplier runtimeApplier;

    private final ConfigurationFileService fileService;

    private final StarBotEventHandlerService handlerService;

    private final PushTemplateDefaults templateDefaults;

    private final PushActivityRecorder activityRecorder;

    public PushController(RuntimeConfigurationApplier runtimeApplier,
                          ConfigurationFileService fileService,
                          StarBotEventHandlerService handlerService,
                          PushTemplateDefaults templateDefaults,
                          PushActivityRecorder activityRecorder) {
        this.runtimeApplier = runtimeApplier;
        this.fileService = fileService;
        this.handlerService = handlerService;
        this.templateDefaults = templateDefaults;
        this.activityRecorder = activityRecorder;
    }

    /**
     * 切换全局推送开关
     * <p>
     * 同时改写内存中的配置与配置文件：只改文件要等重启才生效，而「临时静音」这个诉求
     * 恰恰要求立即生效；只改内存则重启后又会悄悄恢复推送。
     * @param body 请求体，enabled 字段为目标状态
     * @return 切换结果
     */
    @PostMapping("/api/push/toggle")
    public JSONObject togglePush(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();
        boolean enabled = Boolean.TRUE.equals(body.getBoolean("enabled"));

        // 走与设置页保存同一条通道，而不是在这里再写一次 setEnabled：
        // 「这一项怎么落到运行中的程序上」有两处实现的话，改了其中一处的另一处不会跟着变，
        // 而两条路在界面上看起来是同一个开关
        Map<String, String> change = Map.of("novabot.core.push.enabled", String.valueOf(enabled));
        runtimeApplier.applyAndTrack(change);

        try {
            fileService.write(change);
            result.put("success", true);
            result.put("message", enabled ? "已恢复推送" : "已暂停全部推送");
            log.info("配置界面已{}全局推送", enabled ? "恢复" : "暂停");
        } catch (IOException e) {
            // 内存中的开关已生效，仅是没能持久化，如实告知而不是笼统报失败
            log.error("持久化全局推送开关失败", e);
            result.put("success", true);
            result.put("message", (enabled ? "已恢复推送" : "已暂停全部推送") + "，但写入配置文件失败，重启后将恢复原状");
        }

        return result;
    }

    /**
     * 已注册的推送处理器
     * <p>
     * 界面据此渲染「推送哪些事件」的勾选项。处理器的全限定类名属于实现细节，
     * 不该要求使用者手抄，此处把它连同展示名一并给出，由界面完成映射。
     * <p>
     * 每一项还带上它的<b>旧全类名</b>：处理器搬过包之后，老使用者的 {@code datasource.json}
     * 里写的仍是旧名，界面按真类名严格比就对不上——开关显示成「关」而机器人照推，
     * 旧名下的自定义模板读不到而页面报「默认模板」，三样都不报错。旧名逐字取自
     * {@link StarBotEventHandlerService#getLegacyClassNames()}，也就是运行期认处理器用的那张表：
     * 在这里另手写一份的话，两份清单迟早对不上，而那正是这个毛病本来的成因。
     * @return 处理器列表
     */
    @GetMapping("/api/handlers")
    public JSONObject handlers() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        Map<String, List<String>> legacyNames = handlerService.getLegacyClassNames();

        JSONArray items = new JSONArray();
        handlerService.getRegisteredHandlers().forEach((className, handler) -> {
            JSONObject item = new JSONObject();
            item.put("className", className);
            // 没有旧名时给一张空表而不是不给这一栏：缺栏与空表在前端 `|| []` 底下长得一样，
            // 而「这个处理器没搬过家」与「这一版后端还不答这一问」要人做的事不是同一件
            item.put("aliases", legacyNames.getOrDefault(className, List.of()));
            item.put("displayName", handler.displayName());
            item.put("description", handler.description());
            item.put("platform", handler.platform());
            item.put("placeholders", handler.placeholders());
            item.put("attachments", handler.attachmentPlaceholders());
            // 「默认」是这台机器此刻的默认（出厂默认盖上控制台改过的那几个键），
            // 不是出厂默认：界面拿它判「这个通道用的是默认还是自定义」，
            // 拿出厂默认去判的话，改过默认模板之后每一个通道都会显示成「自定义」
            item.put("defaultParams", templateDefaults.paramsOf(handler));
            // 出厂默认另给一份：默认模板那一页要答「这一项改过没有」与「恢复出厂」
            item.put("factoryParams", handler.getDefaultParams());
            item.put("options", handler.options());
            items.add(item);
        });

        items.sort(Comparator.comparing(item -> ((JSONObject) item).getString("className")));
        result.put("handlers", items);
        return result;
    }

    /**
     * 读这台机器改过的默认模板
     * <p>
     * 回的是<b>相对出厂默认的覆盖</b>，没改过的处理器整个不出现。整份默认参数在
     * {@code /api/handlers} 的 {@code defaultParams} 里，这一支答的是另一个问题：
     * 「哪几项是人改过的」——两者合一的话，「改成了与出厂一样的值」与「没改过」
     * 就再也分不开，而默认模板那一页上「恢复出厂」该不该亮正是靠这个分。
     * @return 覆盖参数
     */
    @GetMapping("/api/templates")
    public JSONObject templates() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("defaults", templateDefaults.all());
        return result;
    }

    /**
     * 改某一类通知的默认模板
     * <p>
     * 一次一个处理器，传的是<b>整份覆盖</b>：某个键不在里面即回到出厂默认，
     * 空对象即整类回到出厂默认。改这里等于改<b>所有用默认的通道</b>，
     * 因此校验不过时一个字也不写——半份默认模板会一次落到一批群上。
     * @param body 请求体，className 为处理器全类名，params 为整份覆盖
     * @return 保存结果
     */
    @PostMapping("/api/templates")
    public ResponseEntity<JSONObject> saveTemplate(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String className = body.getString("className");
        Optional<org.frostnova.nova.core.handler.NovaEventHandler> handler =
                className == null ? Optional.empty() : handlerService.getHandler(className);
        if (handler.isEmpty()) {
            result.put("success", false);
            result.put("message", "没有这样一个推送处理器: " + className);
            return ResponseEntity.badRequest().body(result);
        }

        List<String> issues = templateDefaults.save(handler.get(), body.getJSONObject("params"));
        if (!issues.isEmpty()) {
            result.put("success", false);
            result.put("message", "默认模板有误，已拒绝保存");
            result.put("issues", issues);
            return ResponseEntity.ok(result);
        }

        result.put("success", true);
        result.put("message", "已保存，用默认模板的通道跟着一起变");
        log.info("配置界面已更新默认模板: {}", className);
        return ResponseEntity.ok(result);
    }

    /**
     * 最近的推送记录
     * <p>
     * 「刚才那条推了吗」「为什么没推」此前只能翻 journalctl。
     * @return 推送记录，按时间倒序
     */
    @GetMapping("/api/push-history")
    public JSONObject pushHistory() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray records = new JSONArray();
        activityRecorder.getHistory().forEach(record -> {
            JSONObject item = new JSONObject();
            item.put("at", HISTORY_TIME.format(record.at()));
            item.put("platform", record.platform());
            item.put("target", record.target());
            item.put("summary", record.summary());
            item.put("success", record.success());
            item.put("reason", record.reason());
            records.add(item);
        });

        result.put("records", records);
        return result;
    }
}
