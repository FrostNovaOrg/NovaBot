package com.starlwr.bot.core.account;

import java.util.Optional;

/**
 * 账号登录能力
 * <p>
 * 各数据源插件实现本接口并注册为 Bean，即可把自身的登录状态与扫码流程接入配置界面，
 * 核心无需反向依赖各插件。
 * <p>
 * 存在的意义是把登录从终端里搬到界面上：二维码原本只打印在启动日志里，systemd 部署时得靠
 * journalctl 翻日志才能看到，而终端里的字符画二维码在字体或宽度不合适时根本扫不出来。
 */
public interface AccountLoginProvider {
    /**
     * 平台名称，例如 bilibili
     * @return 平台名称
     */
    String platform();

    /**
     * 展示名称，例如「哔哩哔哩」
     * @return 展示名称
     */
    String displayName();

    /**
     * 是否已登录
     * @return 是否已登录
     */
    boolean isLoggedIn();

    /**
     * 当前登录账号的标识，未登录时为空
     * @return 账号标识
     */
    Optional<String> accountId();

    /**
     * 当前待扫描的二维码内容，无待扫码时为空
     * @return 二维码内容
     */
    Optional<String> pendingQrCodeContent();

    /**
     * 退出登录并清除本地凭据
     */
    void logout();
}
