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
     * 登录能力被配置关掉的原因，未被关掉时为空
     * <p>
     * 有了它，界面才能把「还没扫码」和「这台机器压根不打算登录」区分开。
     * 两者在界面上原本长得一模一样：都是「未登录」加一个永远等不来的二维码。
     * <p>
     * 返回的文本会直接显示给使用者，因此要写清楚<b>关掉之后代价是什么</b>，
     * 而不只是说一句「已禁用」。
     * @return 被关掉的原因
     */
    default Optional<String> disabledReason() {
        return Optional.empty();
    }

    /**
     * 退出登录并清除本地凭据
     */
    void logout();
}
