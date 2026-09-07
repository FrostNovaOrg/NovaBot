package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ui.ConfigUiController;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.StreamerReference;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.DataSourceService.StreamerWithFans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查主播：添加主播时先把昵称与直播间号显示出来让人确认
 * <p>
 * 从核心配置界面拆出，随控制台插件走。路径不变。
 */
@Slf4j
@StarBotComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH)
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class StreamerLookupController {
    /**
     * 纯数字：可能是 uid，也可能是直播间号
     * <p>
     * 链接由各平台自己认（见 {@link DataSourceService#parseStreamerLink}），只有这一种留在这里：
     * 一串纯数字不是谁家的链接，各平台一样对待。
     */
    private static final Pattern PLAIN_UID = Pattern.compile("^(\\d{1,19})$");

    private final DataSourceServiceRegistry dataSourceServiceRegistry;

    public StreamerLookupController(DataSourceServiceRegistry dataSourceServiceRegistry) {
        this.dataSourceServiceRegistry = dataSourceServiceRegistry;
    }

    /**
     * 查询主播信息
     * <p>
     * 添加主播时先把昵称与直播间号显示出来让人确认，避免 uid 打错一位却配了个陌生人——
     * 这类错误在推送真正发生前完全无法察觉。
     * <p>
     * 输入除 uid 与个人空间链接外，也收直播间号：纯数字短号，或直播间链接。
     * 纯数字既像 uid 又像房间号时<b>先按 uid 查，查不到再按房间号查一次</b>；
     * 链接已经标明是哪一种时只走对应那一趟，不加重试。
     * <p>
     * 链接长什么样由各平台自己认，本处不认识任何一家的域名——所以平台要先定下来，
     * 才知道该拿谁的数据源服务去认这段文本。
     * @param body 请求体，含 platform 与 uid（uid 亦可为空间链接、直播间号或直播间链接）
     * @return 主播信息
     */
    @PostMapping("/api/streamer/lookup")
    public JSONObject lookupStreamer(@RequestBody JSONObject body) {
        JSONObject result = new JSONObject();

        String platform = body.getString("platform");
        String input = body.getString("uid");

        if (platform == null || platform.isBlank() || input == null || input.isBlank()) {
            result.put("success", false);
            result.put("message", "请填写平台与 uid，也可直接粘贴个人空间链接、直播间号或直播间链接");
            return result;
        }

        Optional<DataSourceService> service = dataSourceServiceRegistry.getDataSourceService(platform);
        if (service.isEmpty()) {
            result.put("success", false);
            result.put("message", "平台 " + platform + " 没有可用的数据源服务，请确认对应插件已加载");
            return result;
        }

        DataSourceService data = service.get();
        StreamerQuery query = parseStreamerQuery(input, data);
        if (query == null) {
            result.put("success", false);
            result.put("message", recognizedByOtherPlatform(input, platform)
                    ? "这条链接不属于当前选择的平台，请先把平台选对再粘贴"
                    : "不认识这条链接，可以粘贴主播的个人空间链接或直播间链接，也可以直接填 uid 或直播间号");
            return result;
        }

        StreamerWithFans found;
        try {
            if (query.kind() == StreamerIdKind.ROOM) {
                found = data.lookupByRoomIdWithFans(query.id());
            } else {
                found = data.completeStreamerWithFans(incompleteUser(platform, query.id()));
                if (missingName(found.user()) && query.kind() == StreamerIdKind.DIGITS) {
                    found = data.lookupByRoomIdWithFans(query.id());
                }
            }
        } catch (Exception e) {
            log.error("查询主播 {} 信息失败", query.id(), e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
            return result;
        }

        PushUser user = found.user();

        if (missingName(user)) {
            result.put("success", false);
            result.put("message", query.kind() == StreamerIdKind.ROOM
                    ? "未查到直播间号 " + query.id() + " 对应的主播，请确认直播间号是否正确"
                    : "未查到 uid " + query.id() + " 对应的主播，请确认 uid 是否正确");
            return result;
        }

        result.put("success", true);
        result.put("uid", user.getUid());
        result.put("uname", user.getUname());
        result.put("roomId", user.getRoomId());
        result.put("face", user.getFace());
        // 粉丝数是这张确认小卡上最容易发现「认错人」的一项：uid 打错一位仍可能查到
        // 一位真实存在的人，昵称与头像未必看得出不对，粉丝数往往差着数量级。
        // 取不到时给 null 而不是 0——后者会显示成「这位主播一个粉丝都没有」。
        // 它与昵称、房间号由数据源同一趟带回（见 DataSourceService#completeStreamerWithFans），
        // 取不到已在那一趟里收住，不会连累整次查询
        result.put("fans", found.fans());
        return result;
    }

    /**
     * 只填了 uid 与平台的半成品推送用户，其余字段待数据源补全
     */
    private PushUser incompleteUser(String platform, long uid) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform(platform);
        return user;
    }

    private static boolean missingName(PushUser user) {
        return user == null || user.getUname() == null || user.getUname().isBlank();
    }

    /**
     * 从输入中提取 uid 或直播间号
     * <p>
     * 让使用者自己去链接里抠出那串数字是没必要的一道门槛，而这段文本是不是一条链接、
     * 里头那串数字是 uid 还是直播间号，只有平台自己认得——本处只收它认出来的结果。
     * 纯数字不问平台：它不是谁家的链接，两种编号都像，先按 uid 查、落空再按房间号查一次。
     * @param input 输入内容，非空且已判过非空白
     * @param data 所选平台的数据源服务
     * @return 解析结果，无法识别时返回 null
     */
    private StreamerQuery parseStreamerQuery(String input, DataSourceService data) {
        String trimmed = input.trim();

        Optional<StreamerReference> link = parseStreamerLink(data, trimmed);
        if (link.isPresent()) {
            StreamerReference reference = link.get();
            StreamerIdKind kind = reference.kind() == StreamerReference.Kind.ROOM_ID
                    ? StreamerIdKind.ROOM
                    : StreamerIdKind.UID;
            return new StreamerQuery(kind, reference.id());
        }

        Matcher plain = PLAIN_UID.matcher(trimmed);
        return plain.matches() ? new StreamerQuery(StreamerIdKind.DIGITS, Long.parseLong(plain.group(1))) : null;
    }

    /**
     * 问一个平台认不认得这段文本
     * <p>
     * 认链接的是插件那一侧的代码，它抛异常不该让整次查询变成 500：认不出与认的时候炸了，
     * 对使用者是同一件事——这条链接在这里用不了。
     * @param data 数据源服务
     * @param text 已去过首尾空白的输入
     * @return 认出的 uid 或直播间号，认不出时为空
     */
    private Optional<StreamerReference> parseStreamerLink(DataSourceService data, String text) {
        try {
            Optional<StreamerReference> parsed = data.parseStreamerLink(text);
            return parsed == null ? Optional.empty() : parsed;
        } catch (Exception e) {
            log.debug("解析主播链接失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 别的平台认不认得这条链接
     * <p>
     * 分开说这两种情形：一条谁都不认得的链接，与一条平台选错了的链接。后者若也回「不认识」，
     * 使用者会对着一条完全正常的链接反复检查那串数字。
     * <p>
     * 只在已经认不出的时候才逐平台问一遍，问的是「该不该换一句话说」，不据此改查谁——
     * 平台是使用者在界面上选的，配置也按那个平台写，替他改掉是另一回事。
     * @param input 输入内容
     * @param platform 当前所选平台，已经问过了不再重问
     * @return 有别的平台认得时为真
     */
    private boolean recognizedByOtherPlatform(String input, String platform) {
        String trimmed = input.trim();
        for (String other : dataSourceServiceRegistry.platforms()) {
            if (other.equals(platform)) {
                continue;
            }
            boolean known = dataSourceServiceRegistry.getDataSourceService(other)
                    .flatMap(service -> parseStreamerLink(service, trimmed))
                    .isPresent();
            if (known) {
                return true;
            }
        }
        return false;
    }

    private enum StreamerIdKind {
        UID, ROOM, DIGITS
    }

    private record StreamerQuery(StreamerIdKind kind, long id) {
    }
}
