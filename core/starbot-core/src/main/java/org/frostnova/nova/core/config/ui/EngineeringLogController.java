package org.frostnova.nova.core.config.ui;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class EngineeringLogController {
    /**
     * 点名那一刻的写法：只到分钟
     * <p>
     * 不认秒：日志页上一条事件显示的就是「几点几分」，而使用者点过来要看的是那一分钟前后。
     */
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final EngineeringLogService service;

    @Autowired
    public EngineeringLogController(EngineeringLogService service) {
        this.service = service;
    }

    /**
     * 日志文件尾部
     * <p>
     * 三种读法共用这一条路，因为它们答的是同一个问题的三种问法：
     * 不带参数是「最近这些」；带 {@code at} 是「那一刻前后」；带 {@code since} 是「刚才之后又写了什么」。
     * 拆成三条路的话，「读哪一份文件」「怎么打码」这两件事就有了三份实现——
     * 而漏改一份的表现是那一路照常工作，只是没打码。
     * @param limit 最多几行，留空按默认值
     * @param d 看哪一天，格式 {@code YYYY-MM-DD}；留空即今天
     * @param at 定位到哪一分钟，格式 {@code HH:mm}；留空即不定位
     * @param since 上一次读到哪个字节，供「跟随最新」接着读；小于 0 即不接着读
     * @return 已打码的若干行，以及读的是哪一份、上面还有没有
     */
    @GetMapping
    public JSONObject log(@RequestParam(defaultValue = "0") int limit,
                          @RequestParam(required = false) String d,
                          @RequestParam(required = false) String at,
                          @RequestParam(defaultValue = "-1") long since) {
        JSONObject result = new JSONObject();
        result.put("success", true);
        // 报的是真正生效的那个数，不是请求里写的那个：要了十万行只给两千时，
        // 照着请求的数写会让界面看起来像是「日志一共就这么多」
        result.put("limit", service.effectiveLimit(limit));
        result.put("lines", new JSONArray());

        LocalDate day;
        LocalTime minute;
        try {
            day = d == null || d.isBlank() ? null : LocalDate.parse(d.trim());
            // 时刻不合格式就明说，而不是当成「没点名」去读尾巴：后者会显示一段与那一刻
            // 毫不相干的日志，而页面上「已定位到」那句话照常显示
            minute = at == null || at.isBlank() ? null : LocalTime.parse(at.trim(), MINUTE);
        } catch (DateTimeParseException e) {
            result.put("success", false);
            result.put("message", "日期应为 YYYY-MM-DD、时刻应为 HH:mm: " + e.getParsedString());
            return result;
        }

        result.put("date", day == null ? LocalDate.now().toString() : day.toString());
        result.put("at", minute == null ? null : at.trim());

        Optional<Path> file = service.file(day);
        if (file.isEmpty()) {
            // 「读不到」不许读成「没问题」：这一页会因此显示成一份空日志，而空日志是个正当状态。
            // 点名某一天而算不出落点时同样走这里——那与「那一天没有记录」不是一回事
            result.put("available", false);
            result.put("message", day == null
                    ? "这台机器的日志没有写进文件，控制台读不到。日志此刻只在启动它的那个终端里。"
                    : "这台机器的日志没有按天分文件，翻不了别的日子。");
            return result;
        }

        result.put("available", true);
        result.put("path", file.get().toString());

        try {
            if (minute != null) {
                window(result, file.get(), minute);
            } else if (since >= 0) {
                follow(result, file.get(), since);
            } else {
                tail(result, file.get(), limit);
            }
        } catch (IOException e) {
            log.warn("读取日志文件 {} 失败: {}", file.get(), e.getMessage());
            result.put("success", false);
            result.put("message", "读不了日志文件: " + e.getMessage());
        }

        return result;
    }

    private void tail(JSONObject result, Path file, int limit) throws IOException {
        EngineeringLogService.Tail tail = service.tail(file, limit);
        result.put("lines", tail.lines());
        result.put("more", tail.more());
        result.put("size", tail.size());
        result.put("offset", tail.offset());
    }

    /**
     * 定位到某一刻前后那一段
     */
    private void window(JSONObject result, Path file, LocalTime minute) throws IOException {
        EngineeringLogService.Window window = service.around(file, minute, EngineeringLogService.DEFAULT_SPAN);
        result.put("lines", window.lines());
        result.put("highlight", window.highlight());
        result.put("exact", window.exact());
        result.put("nearest", window.nearest());
        result.put("span", EngineeringLogService.DEFAULT_SPAN);
        // 没定到点名那一刻时说清楚：屏幕上高亮着一行，而它不是使用者点过来要看的那一行
        if (!window.exact() && window.nearest() != null) {
            result.put("message", "这一分钟没有记录，已定位到最近的一行（" + window.nearest() + "）。");
        }
    }

    /**
     * 上一次之后新写进去的那几行
     */
    private void follow(JSONObject result, Path file, long since) throws IOException {
        EngineeringLogService.Appended appended = service.since(file, since);
        result.put("lines", appended.lines());
        result.put("offset", appended.offset());
        // 文件换过了就说，界面据此重取整段尾巴——不说的话，屏幕上会从头再长出一整份日志
        result.put("reset", appended.reset());
    }
}
