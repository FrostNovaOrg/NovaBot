package com.starlwr.bot.core.account;

import java.time.Instant;
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
     * 当前凭据的到期时刻，答不上时为空
     * <p>
     * 「这把登录还能用多久」是连接页上使用者最想知道的一件事，而它<b>只有平台自己答得出来</b>：
     * 有的平台把到期时间写在凭据里，有的压根没有一个确定的到期时刻。
     * <p>
     * 答不上就留空，界面那一侧就不写「还剩几天」——编一个日期出来的话，那个数字从第一天起就是错的，
     * 而使用者恰恰会照着它决定什么时候去重新扫码。
     * @return 到期时刻
     */
    default Optional<Instant> credentialExpiresAt() {
        return Optional.empty();
    }

    /**
     * 凭据续期状况的一句话，没有可说的时为空
     * <p>
     * 与 {@link #credentialExpiresAt()} 分开：到期时刻答的是「什么时候失效」，
     * 这一句答的是「失效之后会不会自己续上」。后者常常比前者要紧——
     * 能自动续期的凭据到期不用管人，不能续的那种到期就是一次掉登录。
     * <p>
     * 文本会原样显示给使用者，因此要写清楚<b>接下来会发生什么</b>。
     * @return 续期状况
     */
    default Optional<String> credentialNote() {
        return Optional.empty();
    }

    /**
     * 当前登录账号的昵称，未登录或取不到时为空
     * <p>
     * 标识（{@link #accountId()}）是稳定的，昵称会改。界面要两个一起写：有昵称时「名 · uid」，
     * 取不到时只写 uid。取的那一下走该平台已经在用的那条查询口，失败就空着，不重试——
     * 昵称不是进门的凭据，缺了它卡上照样认得人。
     * @return 昵称
     */
    default Optional<String> accountName() {
        return Optional.empty();
    }

    /**
     * 退出登录并清除本地凭据
     */
    void logout();
}
