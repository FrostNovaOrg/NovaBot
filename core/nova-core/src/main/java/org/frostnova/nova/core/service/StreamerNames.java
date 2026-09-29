package org.frostnova.nova.core.service;

import org.frostnova.nova.core.model.PushUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 控制台上显示的主播昵称
 * <p>
 * 推送配置里通常只写 uid，昵称要等程序起动时去直播平台查回来。断网、被风控时查不回来，
 * 内存里的昵称就是空的——只显示空名或一串 uid 的话，使用者认不出那是谁。
 * 此时退回这位主播最近一场归档里的昵称，与主播页的取法一致。
 * <p>
 * <b>内存里有昵称就用内存的</b>：那是向平台查回来的现值，归档里的是当时的旧名。
 * 两样都没有时原样返回内存里那一份，各处照旧按自己的办法显示。
 */
@Service
public class StreamerNames {
    private final LiveSessionArchive archive;

    @Autowired
    public StreamerNames(LiveSessionArchive archive) {
        this.archive = archive;
    }

    /**
     * 主播的昵称
     * @param user 内存里加载着的推送用户
     * @return 内存里的昵称；为空时取最近一场归档里的；都没有时为内存里那一份（null 或空串）
     */
    public String uname(PushUser user) {
        String loaded = user.getUname();
        if (loaded != null && !loaded.isBlank()) {
            return loaded;
        }
        return archive.latestUname(user.getPlatform(), user.getUid()).orElse(loaded);
    }
}
