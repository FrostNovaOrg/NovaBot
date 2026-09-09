package org.frostnova.nova.report.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.report.painter.BilibiliLiveReportPreviewPainter;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Optional;

/**
 * 报告版式的数据面：有哪些开关，以及调成这样之后报告长什么样
 *
 * <h2>为什么要有这两支</h2>
 * 「报告长什么样」那一屏要做到所见即所得，需要两件东西：一份<b>版式项清单</b>
 * （界面不该把开关名字写死在前端——写死的那一份迟早与真正生效的对不上，
 * 而对不上时不会有任何报错，只会让人以为「配了没用」），
 * 以及一张<b>按当前勾选画出来的图</b>。
 *
 * <h2>路径为什么挂在控制台底下</h2>
 * 挂在 {@link ConfigUiController#BASE_PATH} 之下，与挑选推送目标那两支同理：
 * 来源 IP 白名单、令牌或口令、使用协议三道闸一道不少。
 * 另建一套鉴权的下场是两套规则迟早对不上，而对不上的那一侧通常是新写的这一套。
 *
 * <h2>路径里不写平台名</h2>
 * 写成 {@code /api/report/…} 而不是 {@code /api/bilibili/report/…}：
 * 「下播报告」是核心界面上的一件东西，核心界面不出现平台名。
 * ⚠️ <b>装第二个平台时这里要重判</b>：届时两个平台各有各的版式项，
 * 这条路径得能说清问的是谁的报告——留给那一笔，现在多加一层只是猜。
 */
@Slf4j
@RestController
@NovaComponent
public class BilibiliReportLayoutController {
    static final String LAYOUT_OPTIONS_PATH = ConfigUiController.BASE_PATH + "/api/report/layout-options";

    static final String PREVIEW_PATH = ConfigUiController.BASE_PATH + "/api/report/preview";

    private final BilibiliLiveReportPreviewPainter preview;

    private final RevenueVisibilityService revenueVisibility;

    public BilibiliReportLayoutController(BilibiliLiveReportPreviewPainter preview,
                                          RevenueVisibilityService revenueVisibility) {
        this.preview = preview;
        this.revenueVisibility = revenueVisibility;
    }

    /**
     * 列出报告的全部版式项
     * <p>
     * 现算，不缓存也不写死：这张表由 {@link BilibiliLiveReportOptions#layoutOptions()} 给，
     * 而那张表与真正生效的字段由判据两个方向钉着。
     * @return 版式项清单
     */
    @GetMapping(value = LAYOUT_OPTIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public JSONObject layoutOptions() {
        JSONArray items = new JSONArray();
        for (HandlerOption option : BilibiliLiveReportOptions.layoutOptions()) {
            JSONObject item = new JSONObject();
            item.put("key", option.key());
            item.put("label", option.label());
            item.put("description", option.description());
            // 类型名一律小写，与界面上那几种控件一一对应；直接吐枚举名会把
            // Java 的书写习惯泄进接口，换个实现语言就得跟着改
            item.put("type", option.type().name().toLowerCase(Locale.ROOT));
            item.put("default", option.defaultValue());
            item.put("min", option.min());
            item.put("max", option.max());
            items.add(item);
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("items", items);
        return result;
    }

    /**
     * 按给定版式画一张预览图
     * <p>
     * 入参就是推送配置里的那份 {@code params}，原样喂给 {@link BilibiliLiveReportOptions#of}——
     * <b>预览与真出报告读的是同一段解析码</b>。各解析各的话，预览会在「越界值怎么夹」
     * 「缺项取什么默认」这些地方悄悄给出与实际不同的图，而那正是所见即所得要防的事。
     * <p>
     * 金额按当前通道的「金额可见」画：预览与真发到这个群里的是同一张。
     * 没带通道（platform／num）时仍按可见画，与改之前同一条路。
     * 金额藏不藏只在本群设置改，这里不另给勾。
     * @param params 版式参数，可带当前通道的 platform、type、num；可为空
     * @return PNG 图片
     */
    @PostMapping(value = PREVIEW_PATH, produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(@RequestBody(required = false) JSONObject params) {
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, showRevenueFor(params));

        Optional<byte[]> image = preview.render(options);
        if (image.isEmpty()) {
            // 画不出来时不回一张空图：一张 0 字节的 PNG 在界面上就是一块白，
            // 而使用者会以为是这套版式什么都不显示
            log.warn("绘制报告版式预览失败");
            return ResponseEntity.status(500).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(image.get());
    }

    /**
     * 这份预览该不该带金额
     * <p>
     * 带了通道就问会话级设置，与真出报告同一条路；没带通道沿用旧行为（可见）。
     * type 缺省时按群聊处理——不确定就按更保守的那一边。
     * @param body 请求体
     * @return 是否画金额
     */
    private boolean showRevenueFor(JSONObject body) {
        if (body == null) {
            return true;
        }

        String platform = body.getString("platform");
        Long num = body.getLong("num");
        if (platform == null || platform.isBlank() || num == null) {
            return true;
        }

        Integer typeCode = body.getInteger("type");
        PushTargetType type = typeCode == null ? null : PushTargetType.of(typeCode);
        return revenueVisibility.isVisible(platform, type, num);
    }
}
