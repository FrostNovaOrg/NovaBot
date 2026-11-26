package org.frostnova.nova.core.account;

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
     * 这个账号的标识，缺省就是平台名
     * <p>
     * 同一个平台上不止一个账号时（例如另有一个只用来发言的账号），靠它把它们分开：
     * 连接页上一张卡对一个标识，退出登录也按它找人。只按平台名找的话，
     * 点一张卡上的「退出登录」，退掉的可能是另一张卡上的那个号。
     * <p>
     * 与连接卡的页标识取同一个值，界面才配得上对；同一套字符规则：小写字母开头，只有小写字母、数字与连字符。
     * @return 账号标识
     */
    default String id() {
        return platform();
    }

    /**
     * 这个账号是不是可选的
     * <p>
     * 可选账号不登录也不妨碍本机采集与推送：初始设置里不把它当成必做的一步，
     * 首页链路上那一站也不按它的登录态算。
     * @return 可选时返回 true
     */
    default boolean optional() {
        return false;
    }

    /**
     * 是否要有人在界面上点一下才开始扫码
     * <p>
     * 缺省是 false：没登录时一直备着一张二维码，扫了就登上。返回 true 的账号平时不备码，
     * 也就不为它向平台发请求；有人点了「扫码登录」（见 {@link #startLogin()}）才去取码。
     * @return 按需扫码时返回 true
     */
    default boolean loginOnDemand() {
        return false;
    }

    /**
     * 开始一轮扫码登录，只对 {@link #loginOnDemand()} 为 true 的账号有意义
     * <p>
     * 立即返回，不等扫码结果：二维码随后出现在 {@link #pendingQrCodeContent()} 里。
     * @return 说给使用者的一句话
     */
    default String startLogin() {
        throw new UnsupportedOperationException("这个账号不支持在界面上发起扫码");
    }

    /**
     * 最近一次登录没成的原因，或此刻要人知道的一件事，没有时为空
     * <p>
     * 例如扫出来的号被拒、二维码过期了没人扫。文本原样显示给使用者，写清楚接下来该做什么。
     * @return 说明
     */
    default Optional<String> loginNotice() {
        return Optional.empty();
    }

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
