package org.frostnova.nova.bilibili;

import org.frostnova.nova.core.enums.LivePlatform;

/**
 * 本插件的直播平台标识
 * <p>
 * 平台标识由插件自己登记，核心不认识任何一个具体平台。全插件<b>只在这里</b>登记一次，
 * 别处一律引用这个常量：标识串多写一遍，就多一处能跟这里写得不一样的地方，
 * 而写岔了的那一处存出去的数据，读回来时就归到了另一个平台名下。
 */
public final class BilibiliPlatform {
    /**
     * 平台标识
     * <p>
     * <b>标识串 {@code bilibili} 沿用既有取值，不可改动</b>：既有部署的配置文件、数据源里的
     * 推送用户、数据库里按平台分键的直播数据与绑定关系，存的都是这个串。改一次，
     * 那些数据就全都认不回来了。
     */
    public static final LivePlatform BILIBILI = LivePlatform.of("bilibili", "哔哩哔哩");

    private BilibiliPlatform() {
    }
}
