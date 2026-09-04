package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 工程日志接口
 * <p>
 * 「日志」页的子页要的那一份：本机日志文件的尾巴。与 {@link TimelineController} 不是一回事——
 * 那一份记的是使用者关心的事，这一份是程序自己记的流水。
 * <p>
 * <b>只有 NovaBot 自己这一份</b>。机器人那头（NapCat 等 OneBot 实现）的日志不进控制台，
 * 页面上只留一个「打开它自己的界面」的入口：把别人的日志代理进来，等于替一个我们
 * 既管不着、也说不清它写了什么的程序背书。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/engineering-log")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class EngineeringLogController {
    private final EngineeringLogService service;

    @Autowired
    public EngineeringLogController(EngineeringLogService service) {
        this.service = service;
    }

    /**
     * 日志文件尾部
     * @param limit 最多几行，留空按默认值
     * @return 已打码的若干行，以及读的是哪一份、上面还有没有
     */
    @GetMapping
    public JSONObject log(@RequestParam(defaultValue = "0") int limit) {
        JSONObject result = new JSONObject();
        result.put("success", true);
        // 报的是真正生效的那个数，不是请求里写的那个：要了十万行只给两千时，
        // 照着请求的数写会让界面看起来像是「日志一共就这么多」
        result.put("limit", service.effectiveLimit(limit));
        result.put("lines", new JSONArray());

        Optional<Path> file = service.file();
        if (file.isEmpty()) {
            // 「读不到」不许读成「没问题」：这一页会因此显示成一份空日志，而空日志是个正当状态
            result.put("available", false);
            result.put("message", "这台机器的日志没有写进文件，控制台读不到。"
                    + "日志此刻只在启动它的那个终端里。");
            return result;
        }

        result.put("available", true);
        result.put("path", file.get().toString());

        try {
            EngineeringLogService.Tail tail = service.tail(file.get(), limit);
            result.put("lines", tail.lines());
            result.put("more", tail.more());
            result.put("size", tail.size());
        } catch (IOException e) {
            log.warn("读取日志文件 {} 失败: {}", file.get(), e.getMessage());
            result.put("success", false);
            result.put("message", "读不了日志文件: " + e.getMessage());
        }

        return result;
    }
}
