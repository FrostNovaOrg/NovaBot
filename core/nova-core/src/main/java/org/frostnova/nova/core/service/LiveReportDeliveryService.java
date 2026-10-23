package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.ChannelSettings;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 使用者主动把最近一场已下播的报告发到当前通道。 */
@Service
public class LiveReportDeliveryService {
    public static final String NO_REPORT = "还没有可发的下播报告";
    private final LiveDetailArchive details;
    private final AbstractDataSource source;
    private final ObjectProvider<LiveReportRedrawer> redrawers;
    private final NovaMessageSender sender;

    public LiveReportDeliveryService(LiveDetailArchive details, AbstractDataSource source,
            ObjectProvider<LiveReportRedrawer> redrawers, NovaMessageSender sender) {
        this.details = details;
        this.source = source;
        this.redrawers = redrawers;
        this.sender = sender;
    }

    public Optional<LiveDetail> latest(String platform, Long uid) {
        return details.latest(platform, uid);
    }

    public JSONObject sendLatest(String platform, Long uid, PushTarget identity, long start) {
        var detail = latest(platform, uid).orElseThrow(() -> new IllegalArgumentException(NO_REPORT));
        if (detail.startTime() != start) throw new IllegalArgumentException("最新场次已更新，请重新确认");
        var channel = source.getAllUsers().stream()
                .filter(user -> platform.equals(user.getPlatform()) && uid.equals(user.getUid()))
                .map(user -> ChannelSettings.target(user, identity.getPlatform(), identity.getType(), identity.getNum()))
                .filter(java.util.Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这条通道"));
        String content = redrawers.orderedStream().filter(one -> platform.equals(one.platform())).findFirst()
                .flatMap(one -> one.channelContent(detail, channel))
                .filter(body -> !body.isBlank())
                .orElseThrow(() -> new IllegalStateException("下播报告生成失败"));
        var message = Message.create(channel.getPlatform(), channel.getType(), channel.getNum(), content).get(0);
        message.setChannel(channel);
        return sender.sendNow(message);
    }
}
