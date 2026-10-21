package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;

/** 审计日志中的对象只包含身份，不包含配置值与消息正文。 */
public final class ConfigUiAudit {
    private ConfigUiAudit() { }

    public static String streamer(AbstractDataSource source, String platform, String uid) {
        String name = source.getAllUsers().stream()
                .filter(user -> java.util.Objects.equals(platform, user.getPlatform())
                        && java.util.Objects.equals(uid, String.valueOf(user.getUid())))
                .map(PushUser::getUname).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        return platform + ":" + uid + (name == null ? "" : " (" + name + ")");
    }
}
