package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.StreamerNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

/** 智能助手设置；鉴权和写操作的 CSRF 检查由配置界面统一执行。 */
@RestController
@RequestMapping("/config/api/assistant")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class AssistantController {
    private final AssistantClient client;
    private final AbstractDataSource dataSource;
    private final StreamerNames streamerNames;

    public AssistantController(AssistantClient client, AbstractDataSource dataSource, StreamerNames streamerNames) {
        this.client = client;
        this.dataSource = dataSource;
        this.streamerNames = streamerNames;
    }

    @GetMapping("/status")
    public AssistantClient.Status status(HttpServletRequest request) {
        ConfigUiAccess.admin(request);
        return client.status((platform, uid) -> streamerNames.uname(platform, uid,
                dataSource.getUser(platform, uid).map(PushUser::getUname).orElse(null)));
    }

    @PostMapping("/key")
    public JSONObject key(HttpServletRequest request) {
        var account = ConfigUiAccess.admin(request);
        client.validateAndSaveKey(account.id(), readKey(request));
        return success();
    }

    @DeleteMapping("/key")
    public JSONObject deleteKey(HttpServletRequest request) {
        ConfigUiAccess.admin(request);
        client.deleteKey();
        return success();
    }

    @PostMapping("/test")
    public JSONObject test(HttpServletRequest request) {
        var account = ConfigUiAccess.admin(request);
        client.testSettings(account.id());
        return success();
    }

    @PostMapping("/acknowledge")
    public JSONObject acknowledge(HttpServletRequest request) {
        var account = ConfigUiAccess.admin(request);
        client.acknowledge(account.id());
        return success();
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<JSONObject> error(AssistantException error) {
        return ResponseEntity.badRequest().body(JSONObject.of("success", false, "message", error.getMessage()));
    }

    /** 在内部解析，避免框架在请求体转换失败时把原文写入诊断日志。 */
    private static String readKey(HttpServletRequest request) {
        byte[] bytes = null;
        try {
            bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) throw new AssistantException("模型密钥请求内容过长");
            JSONObject json = JSON.parseObject(bytes);
            if (json == null || !(json.get("key") instanceof String key)) throw new AssistantException("请填写模型密钥");
            return key;
        } catch (AssistantException error) { throw error; }
        catch (Exception error) { throw new AssistantException("模型密钥请求格式不正确"); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private static JSONObject success() { return JSONObject.of("success", true); }
}
