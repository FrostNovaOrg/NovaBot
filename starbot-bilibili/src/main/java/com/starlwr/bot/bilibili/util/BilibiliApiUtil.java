package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.DanmuType;
import com.starlwr.bot.bilibili.exception.NetworkException;
import com.starlwr.bot.bilibili.exception.RequestFailedException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.*;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 哔哩哔哩接口工具
 * <p>
 * 统一封装接口调用所需的请求头构造、签名附加、失败重试与错误代码处理。
 * 所有方法均只访问哔哩哔哩的公开接口。
 */
@Slf4j
@StarBotComponent
public class BilibiliApiUtil {
    private static final String MAIN_SITE = "https://www.bilibili.com";

    private static final String BUVID_API = "https://api.bilibili.com/x/web-frontend/getbuvid";

    private static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";

    private static final String MY_INFO_API = "https://api.bilibili.com/x/space/v2/myinfo";

    private static final String QR_CODE_GENERATE_API = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";

    private static final String QR_CODE_POLL_API = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=";

    private static final String MASTER_INFO_API = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=";

    private static final String ROOM_INFO_API = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=";

    private static final String ROOM_STATUS_API = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids";

    private static final String DANMU_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo";

    private static final String DANMU_HISTORY_API = "https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory?roomid=";

    private static final String GIFT_CONFIG_API = "https://api.live.bilibili.com/xlive/web-room/v1/giftPanel/roomGiftConfig?platform=pc";

    private static final String LIVE_HEARTBEAT_API = "https://live-trace.bilibili.com/xlive/rdata-interface/v1/heartbeat/webHeartBeat?pf=web&hb=";

    private static final String DYNAMIC_FEED_API = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,decorationCard,onlyfansAssetsV2,forwardListHidden,ugcDelete,onlyfansQaCard,commentsNewVersion";

    private static final String FOLLOWINGS_API = "https://api.bilibili.com/x/relation/followings?vmid=";

    private static final String RELATION_MODIFY_API = "https://api.bilibili.com/x/relation/modify";

    /**
     * 单页关注列表的最大条目数
     */
    private static final int FOLLOWING_PAGE_SIZE = 50;

    private final HttpUtil http;

    private final StarBotBilibiliProperties properties;

    /**
     * 当前使用的登录凭据
     */
    @Getter
    @Setter
    private Cookies cookies = new Cookies();

    /**
     * 设备标识
     */
    @Getter
    private String buvid3;

    /**
     * 接口签名凭据，按需刷新
     */
    private volatile WebSign webSign;

    @Autowired
    public BilibiliApiUtil(HttpUtil http, StarBotBilibiliProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    /**
     * 初始化设备标识与签名凭据
     */
    public void init() {
        try {
            this.buvid3 = fetchBuvid3();
            this.webSign = generateWebSign();
            log.info("哔哩哔哩接口凭据初始化完成");
        } catch (Exception e) {
            log.error("哔哩哔哩接口凭据初始化失败, 部分接口可能无法正常调用", e);
        }
    }

    /**
     * 构造调用哔哩哔哩接口所需的请求头
     * @return 请求头
     */
    public Map<String, String> getBilibiliHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", properties.getNetwork().getUserAgent());
        headers.put("Referer", MAIN_SITE);
        headers.put("Origin", MAIN_SITE);

        String cookie = buildCookieHeader();
        if (StringUtil.isNotBlank(cookie)) {
            headers.put("Cookie", cookie);
        }

        return headers;
    }

    /**
     * 拼接 Cookie 请求头
     * @return Cookie 请求头，无可用凭据时返回空字符串
     */
    private String buildCookieHeader() {
        StringJoiner joiner = new StringJoiner("; ");

        if (StringUtil.isNotBlank(cookies.getSessData())) {
            joiner.add("SESSDATA=" + cookies.getSessData());
        }
        if (StringUtil.isNotBlank(cookies.getBiliJct())) {
            joiner.add("bili_jct=" + cookies.getBiliJct());
        }

        String effectiveBuvid = StringUtil.isNotBlank(cookies.getBuvid3()) ? cookies.getBuvid3() : buvid3;
        if (StringUtil.isNotBlank(effectiveBuvid)) {
            joiner.add("buvid3=" + effectiveBuvid);
        }

        WebSign sign = webSign;
        if (sign != null && StringUtil.isNotBlank(sign.getTicket())) {
            joiner.add("bili_ticket=" + sign.getTicket());
            joiner.add("bili_ticket_expires=" + sign.getTicketExpires());
        }

        return joiner.toString();
    }

    /**
     * 请求哔哩哔哩接口
     * @param url 接口地址
     * @return 响应中的 data 字段
     */
    public JSONObject requestBilibiliApi(String url) {
        return requestBilibiliApi(url, null);
    }

    /**
     * 请求哔哩哔哩接口，附带 WBI 签名参数
     * @param url 接口地址
     * @param params 查询参数，非空时会附加 WBI 签名
     * @return 响应中的 data 字段
     */
    public JSONObject requestBilibiliApi(String url, Map<String, Object> params) {
        return requestBilibiliApi(url, "GET", getBilibiliHeaders(), params);
    }

    /**
     * 请求哔哩哔哩接口
     * @param url 接口地址
     * @param method 请求方法，支持 GET 与 POST
     * @param headers 请求头
     * @param params 参数，GET 时作为附加 WBI 签名的查询参数，POST 时作为表单参数
     * @return 响应中的 data 字段，响应无 data 时返回空 JSON 对象
     */
    public JSONObject requestBilibiliApi(String url, String method, Map<String, String> headers, Map<String, Object> params) {
        int maxTimes = Math.max(1, properties.getNetwork().getApiRetryMaxTimes());
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxTimes; attempt++) {
            try {
                JSONObject response = doRequest(url, method, headers, params);
                return extractData(response);
            } catch (ResponseCodeException e) {
                // 业务错误代码通常重试也不会变化，直接抛出交由调用方判断
                throw e;
            } catch (RuntimeException e) {
                last = e;
                log.debug("请求 {} 第 {} 次失败: {}", url, attempt, e.getMessage());

                if (attempt < maxTimes) {
                    sleep(properties.getNetwork().getApiRetryInterval());
                }
            }
        }

        throw new RequestFailedException("请求 " + url + " 失败, 已重试 " + maxTimes + " 次", last);
    }

    /**
     * 执行一次请求
     * @param url 接口地址
     * @param method 请求方法
     * @param headers 请求头
     * @param params 参数
     * @return 完整响应
     */
    private JSONObject doRequest(String url, String method, Map<String, String> headers, Map<String, Object> params) {
        try {
            if ("POST".equalsIgnoreCase(method)) {
                return http.postJsonAsForm(url, headers, params == null ? Map.of() : params);
            }

            String target = url;
            if (params != null && !params.isEmpty()) {
                WebSign sign = requireWebSign();
                target = url + BilibiliWbiUtil.sign(params, sign.getImgKey(), sign.getSubKey());
            }

            return http.getJson(target, headers);
        } catch (Exception e) {
            throw new NetworkException("请求 " + url + " 时发生网络异常", e);
        }
    }

    /**
     * 校验响应错误代码并取出 data 字段
     * @param response 完整响应
     * @return data 字段
     */
    private JSONObject extractData(JSONObject response) {
        if (response == null) {
            throw new NetworkException("接口未返回任何内容");
        }

        Integer code = response.getInteger("code");
        if (code != null && code != 0) {
            throw new ResponseCodeException(code, Optional.ofNullable(response.getString("message")).orElse("未知错误"));
        }

        JSONObject data = response.getJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    /**
     * 获取图片
     * @param url 图片地址
     * @return 图片，获取失败时返回空
     */
    public Optional<BufferedImage> getBilibiliImage(String url) {
        return getBilibiliImage(url, getBilibiliHeaders());
    }

    /**
     * 获取图片
     * @param url 图片地址
     * @param headers 请求头
     * @return 图片，获取失败时返回空
     */
    public Optional<BufferedImage> getBilibiliImage(String url, Map<String, String> headers) {
        if (StringUtil.isBlank(url)) {
            return Optional.empty();
        }

        try {
            return http.getBufferedImage(url, headers);
        } catch (Exception e) {
            log.debug("获取图片 {} 失败: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 异步获取图片
     * @param url 图片地址
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBilibiliImage(String url) {
        return asyncGetBilibiliImage(url, getBilibiliHeaders());
    }

    /**
     * 异步获取图片
     * @param url 图片地址
     * @param headers 请求头
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBilibiliImage(String url, Map<String, String> headers) {
        if (StringUtil.isBlank(url)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return http.asyncGetBufferedImage(url, headers)
                .exceptionally(e -> {
                    log.debug("异步获取图片 {} 失败: {}", url, e.getMessage());
                    return Optional.empty();
                });
    }

    /**
     * 批量异步获取图片，返回顺序与传入顺序一致
     * @param urls 图片地址列表
     * @return 图片列表
     */
    public CompletableFuture<List<Optional<BufferedImage>>> asyncGetBilibiliImages(List<String> urls) {
        return asyncGetBilibiliImages(urls, getBilibiliHeaders());
    }

    /**
     * 批量异步获取图片，返回顺序与传入顺序一致
     * @param urls 图片地址列表
     * @param headers 请求头
     * @return 图片列表
     */
    public CompletableFuture<List<Optional<BufferedImage>>> asyncGetBilibiliImages(List<String> urls, Map<String, String> headers) {
        if (urls == null || urls.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<Optional<BufferedImage>>> futures = urls.stream()
                .map(url -> asyncGetBilibiliImage(url, headers))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    /**
     * 获取当前可用的签名凭据，已失效时重新获取
     * @return 签名凭据
     */
    private WebSign requireWebSign() {
        WebSign current = webSign;
        if (current != null && current.isValid()) {
            return current;
        }

        synchronized (this) {
            if (webSign == null || !webSign.isValid()) {
                webSign = generateWebSign();
            }
            return webSign;
        }
    }

    /**
     * 获取接口签名所需的 bili_ticket 与 WBI 密钥
     * @return 签名凭据
     */
    public WebSign generateWebSign() {
        WebSign sign = new WebSign();

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", properties.getNetwork().getUserAgent());
        headers.put("Referer", MAIN_SITE);

        try {
            JSONObject ticket = extractData(http.postJsonWithHeaders(BilibiliTicketUtil.buildTicketUrl(cookies.getBiliJct()), headers));
            sign.setTicket(ticket.getString("ticket"));
            sign.setTicketExpires(ticket.getInteger("created_at") == null || ticket.getInteger("ttl") == null
                    ? (int) (Instant.now().getEpochSecond() + 259200)
                    : ticket.getInteger("created_at") + ticket.getInteger("ttl"));

            // WBI 密钥随 bili_ticket 接口一并返回，缺失时再单独请求导航接口
            JSONObject nav = ticket.getJSONObject("nav");
            if (nav != null) {
                sign.setImgKey(BilibiliWbiUtil.extractKey(nav.getString("img")));
                sign.setSubKey(BilibiliWbiUtil.extractKey(nav.getString("sub")));
            }
        } catch (Exception e) {
            log.warn("获取 bili_ticket 失败, 将仅使用 WBI 密钥: {}", e.getMessage());
        }

        if (StringUtil.isBlank(sign.getImgKey()) || StringUtil.isBlank(sign.getSubKey())) {
            try {
                JSONObject wbi = http.getJson(NAV_API, headers).getJSONObject("data").getJSONObject("wbi_img");
                sign.setImgKey(BilibiliWbiUtil.extractKey(wbi.getString("img_url")));
                sign.setSubKey(BilibiliWbiUtil.extractKey(wbi.getString("sub_url")));
            } catch (Exception e) {
                log.error("获取 WBI 密钥失败, 依赖 WBI 签名的接口将无法调用", e);
            }
        }

        return sign;
    }

    /**
     * 获取设备标识
     * @return 设备标识
     */
    private String fetchBuvid3() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", properties.getNetwork().getUserAgent());
        headers.put("Referer", MAIN_SITE);

        return extractData(http.getJson(BUVID_API, headers)).getString("buvid");
    }

    /**
     * 获取扫码登录所需的二维码内容与轮询令牌
     * @return 扫码登录信息
     */
    public QrCodeLogin getQrCodeLoginInfo() {
        JSONObject data = requestBilibiliApi(QR_CODE_GENERATE_API);
        return new QrCodeLogin(data.getString("url"), data.getString("qrcode_key"));
    }

    /**
     * 轮询扫码登录状态
     * <p>
     * 登录成功时会直接将取得的凭据写入当前实例。
     * @param key 轮询令牌
     * @return 是否已登录成功
     */
    public boolean getQrCodeLoginStatus(String key) {
        Map<String, String> headers = getBilibiliHeaders();

        JSONObject response;
        try {
            response = http.getJson(QR_CODE_POLL_API + URLEncoder.encode(key, StandardCharsets.UTF_8), headers);
        } catch (Exception e) {
            log.debug("轮询扫码登录状态失败: {}", e.getMessage());
            return false;
        }

        JSONObject data = response == null ? null : response.getJSONObject("data");
        if (data == null) {
            return false;
        }

        Integer code = data.getInteger("code");
        if (code == null || code != 0) {
            // 86101 未扫码、86090 已扫码待确认、86038 二维码失效
            return false;
        }

        Cookies logged = parseLoginUrl(data.getString("url"));
        if (logged == null) {
            log.error("扫码登录成功但未能解析出登录凭据");
            return false;
        }

        if (StringUtil.isBlank(logged.getBuvid3())) {
            logged.setBuvid3(buvid3);
        }
        this.cookies = logged;

        return true;
    }

    /**
     * 从登录成功后返回的跳转地址中解析登录凭据
     * @param url 跳转地址
     * @return 登录凭据，解析失败时返回 null
     */
    private Cookies parseLoginUrl(String url) {
        if (StringUtil.isBlank(url) || !url.contains("?")) {
            return null;
        }

        Map<String, String> query = new HashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                query.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }

        String sessData = query.get("SESSDATA");
        String biliJct = query.get("bili_jct");
        if (StringUtil.isBlank(sessData) || StringUtil.isBlank(biliJct)) {
            return null;
        }

        return new Cookies(sessData, biliJct, buvid3);
    }

    /**
     * 获取当前登录账号的 uid
     * @return uid，未登录时返回 null
     */
    public Long getLoginUid() {
        try {
            return requestBilibiliApi(MY_INFO_API).getJSONObject("profile").getLong("mid");
        } catch (Exception e) {
            log.debug("获取登录账号 uid 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据 uid 获取 UP 主信息
     * @param uid uid
     * @return UP 主信息
     */
    public Up getUpInfoByUid(@NonNull Long uid) {
        JSONObject data = requestBilibiliApi(MASTER_INFO_API + uid);

        JSONObject info = Optional.ofNullable(data.getJSONObject("info")).orElseGet(JSONObject::new);
        Long roomId = Optional.ofNullable(data.getLong("room_id")).filter(id -> id != 0L).orElse(null);

        return new Up(uid, info.getString("uname"), roomId, info.getString("face"));
    }

    /**
     * 根据直播间号获取 UP 主信息
     * @param roomId 直播间号
     * @return UP 主信息
     */
    public Up getUpInfoByRoomId(@NonNull Long roomId) {
        JSONObject data = requestBilibiliApi(ROOM_INFO_API + roomId);

        Long uid = data.getLong("uid");
        if (uid == null) {
            throw new RequestFailedException("直播间 " + roomId + " 未返回对应的 uid");
        }

        return getUpInfoByUid(uid);
    }

    /**
     * 根据 uid 获取昵称
     * @param uid uid
     * @return 昵称
     */
    public Optional<String> getUnameByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(getUpInfoByUid(uid).getUname());
        } catch (Exception e) {
            log.debug("获取 uid {} 的昵称失败: {}", uid, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 根据 uid 获取直播间号
     * @param uid uid
     * @return 直播间号
     */
    public Optional<Long> getRoomIdByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(getUpInfoByUid(uid).getRoomId());
        } catch (Exception e) {
            log.debug("获取 uid {} 的直播间号失败: {}", uid, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 根据 uid 获取头像地址
     * @param uid uid
     * @return 头像地址
     */
    public Optional<String> getFaceByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(getUpInfoByUid(uid).getFace());
        } catch (Exception e) {
            log.debug("获取 uid {} 的头像失败: {}", uid, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 获取直播间长连接信息
     * @param roomId 直播间号
     * @return 长连接信息
     */
    public ConnectInfo getLiveRoomConnectInfo(@NonNull Long roomId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", roomId);
        params.put("type", 0);

        JSONObject data = requestBilibiliApi(DANMU_INFO_API, params);

        List<ConnectAddress> addresses = new ArrayList<>();
        JSONArray hosts = data.getJSONArray("host_list");
        if (hosts != null) {
            for (int i = 0; i < hosts.size(); i++) {
                JSONObject host = hosts.getJSONObject(i);
                addresses.add(new ConnectAddress(
                        host.getString("host"),
                        host.getIntValue("port"),
                        host.getIntValue("wss_port"),
                        host.getIntValue("ws_port")
                ));
            }
        }

        return new ConnectInfo(data.getString("token"), addresses);
    }

    /**
     * 上报直播间心跳，用于维持观看状态
     * @param roomId 直播间号
     */
    public void liveRoomHeartbeat(@NonNull Long roomId) {
        try {
            http.getJson(LIVE_HEARTBEAT_API + roomId, getBilibiliHeaders());
        } catch (Exception e) {
            log.debug("上报直播间 {} 心跳失败: {}", roomId, e.getMessage());
        }
    }

    /**
     * 获取直播间最近的历史弹幕
     * @param roomId 直播间号
     * @return 弹幕列表
     */
    public List<Danmu> getLiveRoomLatestDanmus(@NonNull Long roomId) {
        JSONObject data = requestBilibiliApi(DANMU_HISTORY_API + roomId);

        JSONArray room = data.getJSONArray("room");
        if (room == null) {
            return List.of();
        }

        List<Danmu> danmus = new ArrayList<>(room.size());
        for (int i = 0; i < room.size(); i++) {
            JSONObject item = room.getJSONObject(i);

            Danmu danmu = new Danmu();
            danmu.setType(DanmuType.NORMAL);
            danmu.setContent(item.getString("text"));
            danmu.setContentText(item.getString("text"));
            danmu.setSender(new BilibiliUserInfo(item.getLong("uid"), item.getString("nickname")));
            danmu.setTimestamp(parseShanghaiTime(item.getString("timeline")).orElseGet(Instant::now));

            danmus.add(danmu);
        }

        return danmus;
    }

    /**
     * 获取全站礼物配置
     * @return 礼物列表
     */
    public List<Gift> getGiftInfos() {
        JSONObject data = requestBilibiliApi(GIFT_CONFIG_API);

        JSONArray list = Optional.ofNullable(data.getJSONObject("global_config"))
                .map(config -> config.getJSONArray("list"))
                .orElseGet(() -> data.getJSONArray("list"));
        if (list == null) {
            return List.of();
        }

        List<Gift> gifts = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            gifts.add(new Gift(
                    item.getLong("id"),
                    item.getString("name"),
                    // 接口返回的价格单位为电池的百分之一，1000 对应 1 元
                    item.getIntValue("price") / 1000.0,
                    item.getString("img_basic")
            ));
        }

        return gifts;
    }

    /**
     * 获取大航海名称与图标的对应关系
     * @return 大航海名称到图标地址的映射
     */
    public Map<String, String> getGuardInfos() {
        JSONObject data = requestBilibiliApi(GIFT_CONFIG_API);

        JSONArray list = Optional.ofNullable(data.getJSONObject("guard_resources"))
                .map(config -> config.getJSONArray("list"))
                .orElse(null);
        if (list == null) {
            return Map.of();
        }

        Map<String, String> guards = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            guards.put(item.getString("name"), item.getString("img"));
        }

        return guards;
    }

    /**
     * 批量获取指定 uid 的直播间状态
     * @param uids uid 集合
     * @return uid 到直播间信息的映射
     */
    public Map<Long, Room> getLiveInfoByUids(Set<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            return Map.of();
        }

        String query = uids.stream().map(uid -> "uids[]=" + uid).collect(Collectors.joining("&"));
        JSONObject data = requestBilibiliApi(ROOM_STATUS_API + "?" + query);

        Map<Long, Room> rooms = new HashMap<>();
        for (Long uid : uids) {
            JSONObject item = data.getJSONObject(String.valueOf(uid));
            if (item == null) {
                continue;
            }

            rooms.put(uid, new Room(
                    item.getInteger("live_status"),
                    item.getLong("live_time"),
                    item.getString("title"),
                    item.getString("cover_from_user")
            ));
        }

        return rooms;
    }

    /**
     * 获取指定直播间的状态
     * @param roomId 直播间号
     * @return 直播间信息
     */
    public Room getLiveInfoByRoomId(@NonNull Long roomId) {
        JSONObject data = requestBilibiliApi(ROOM_INFO_API + roomId);

        return new Room(
                data.getInteger("live_status"),
                parseShanghaiTime(data.getString("live_time")).map(Instant::getEpochSecond).orElse(null),
                data.getString("title"),
                data.getString("user_cover")
        );
    }

    /**
     * 解析接口返回的东八区时间文本
     * <p>
     * 接口以 yyyy-MM-dd HH:mm:ss 的形式返回时间且不带时区，未开播时会返回全零占位值。
     * @param text 时间文本
     * @return 对应时刻，无法解析时返回空
     */
    private Optional<Instant> parseShanghaiTime(String text) {
        if (StringUtil.isBlank(text) || text.startsWith("0000-00-00")) {
            return Optional.empty();
        }

        try {
            return Optional.of(LocalDateTime.parse(text.replace(' ', 'T'))
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .toInstant());
        } catch (Exception e) {
            log.debug("解析时间文本 {} 失败", text);
            return Optional.empty();
        }
    }

    /**
     * 获取当前账号关注的 UP 主动态更新列表
     * @return 动态列表，按接口返回顺序排列
     */
    public List<Dynamic> getDynamicUpdateList() {
        JSONObject data = requestBilibiliApi(DYNAMIC_FEED_API);

        JSONArray items = data.getJSONArray("items");
        if (items == null) {
            return List.of();
        }

        if (properties.getDebug().isDynamicRawMessageLog()) {
            log.info("动态接口原始响应: {}", data.toJSONString());
        }

        List<Dynamic> dynamics = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            dynamics.add(parseDynamic(items.getJSONObject(i)));
        }

        return dynamics;
    }

    /**
     * 解析单条动态
     * @param item 动态 JSON
     * @return 动态
     */
    private Dynamic parseDynamic(JSONObject item) {
        Dynamic dynamic = new Dynamic();
        dynamic.setId(item.getString("id_str"));
        dynamic.setType(item.getString("type"));
        dynamic.setVisible(item.getBoolean("visible"));
        dynamic.setBasic(item.getJSONObject("basic"));
        dynamic.setModules(item.getJSONObject("modules"));

        JSONObject origin = item.getJSONObject("orig");
        if (origin != null) {
            dynamic.setOrigin(parseDynamic(origin));
        }

        return dynamic;
    }

    /**
     * 获取指定账号的关注列表
     * @param selfUid 账号 uid
     * @return 关注的 UP 主列表
     */
    public List<Up> getFollowingUps(Long selfUid) {
        if (selfUid == null) {
            return List.of();
        }

        List<Up> ups = new ArrayList<>();
        for (int page = 1; ; page++) {
            JSONObject data;
            try {
                data = requestBilibiliApi(FOLLOWINGS_API + selfUid + "&ps=" + FOLLOWING_PAGE_SIZE + "&pn=" + page);
            } catch (Exception e) {
                log.error("获取关注列表第 {} 页失败: {}", page, e.getMessage());
                break;
            }

            JSONArray list = data.getJSONArray("list");
            if (list == null || list.isEmpty()) {
                break;
            }

            for (int i = 0; i < list.size(); i++) {
                JSONObject item = list.getJSONObject(i);
                ups.add(new Up(item.getLong("mid"), item.getString("uname"), null, item.getString("face")));
            }

            if (list.size() < FOLLOWING_PAGE_SIZE) {
                break;
            }
        }

        return ups;
    }

    /**
     * 关注指定 UP 主
     * @param uid UP 主 uid
     */
    public void followUp(Long uid) {
        if (uid == null) {
            return;
        }

        if (StringUtil.isBlank(cookies.getBiliJct())) {
            log.error("未登录, 无法关注 uid {}", uid);
            return;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fid", uid);
        // act=1 表示关注
        params.put("act", 1);
        params.put("re_src", 11);
        params.put("csrf", cookies.getBiliJct());

        try {
            requestBilibiliApi(RELATION_MODIFY_API, "POST", getBilibiliHeaders(), params);
            log.info("已关注 uid {}", uid);
        } catch (Exception e) {
            log.error("关注 uid {} 失败: {}", uid, e.getMessage());
        }
    }

    /**
     * 休眠指定毫秒数
     * @param millis 毫秒数
     */
    private void sleep(int millis) {
        if (millis <= 0) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 扫码登录信息
     *
     * @param url 二维码内容
     * @param key 轮询令牌
     */
    public record QrCodeLogin(String url, String key) {
    }
}
