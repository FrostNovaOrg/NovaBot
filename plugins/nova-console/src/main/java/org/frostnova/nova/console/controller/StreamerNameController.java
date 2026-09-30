package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.StreamerNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 程序手上已有的主播昵称，不去直播平台现查
 * <p>
 * 推送页上的主播名原本只靠现场去平台查（{@link StreamerLookupController}）。断网、被风控时查不回来，
 * 一排主播都只剩一串 uid，而运行状态页与主播页此刻照样显示得出名字。
 * 这里补的就是那条退路：先取内存里加载着的昵称，再取最近一场归档里的，取法走 {@link StreamerNames}。
 * <p>
 * <b>它不替添加主播那一步核对身份。</b>这里给的可能是一个旧名，只配拿来认出列表里已经配好的那一位；
 * 添加时仍只认现场查回来的结果。
 */
@NovaComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH)
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class StreamerNameController {
    private final AbstractDataSource dataSource;

    private final StreamerNames streamerNames;

    @Autowired
    public StreamerNameController(AbstractDataSource dataSource, StreamerNames streamerNames) {
        this.dataSource = dataSource;
        this.streamerNames = streamerNames;
    }

    /**
     * 主播昵称：内存里的，没有时取最近一场归档里的
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 取得到时 success 为真并带 uname；两处都没有时 success 为假
     */
    @GetMapping("/api/streamer/name")
    public JSONObject streamerName(@RequestParam String platform, @RequestParam Long uid) {
        String loaded = dataSource.getAllUsers().stream()
                .filter(user -> platform.equals(user.getPlatform()) && uid.equals(user.getUid()))
                .map(PushUser::getUname)
                .findFirst()
                .orElse(null);
        String uname = streamerNames.uname(platform, uid, loaded);

        JSONObject result = new JSONObject();
        if (uname == null || uname.isBlank()) {
            result.put("success", false);
            result.put("message", "程序手上没有这位主播的昵称");
            return result;
        }
        result.put("success", true);
        result.put("uid", uid);
        result.put("uname", uname);
        return result;
    }
}
