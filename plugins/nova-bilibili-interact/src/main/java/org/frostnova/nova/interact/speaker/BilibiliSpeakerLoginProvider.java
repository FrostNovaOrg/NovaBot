package org.frostnova.nova.interact.speaker;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.core.account.AccountLoginProvider;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 发言账号的登录能力，接到控制台连接页上那张「哔哩哔哩发言账号」卡
 * <p>
 * 与监听账号同一个平台，标识不同：卡按标识配对，退出登录也按标识找人，两张卡互不误伤。
 * 它是可选的（初始设置不等它），也是按需扫码的（平时不备二维码，有人点了才取）。
 */
@NovaComponent
public class BilibiliSpeakerLoginProvider implements AccountLoginProvider {
    private final BilibiliSpeakerAccount speaker;

    /**
     * 昵称只问一次，口径与监听账号那张卡相同：普通失败也算问过，被风控拦下的下次刷新再问
     */
    private volatile boolean nameTried;

    private final AtomicBoolean nameInFlight = new AtomicBoolean();

    private volatile String name;

    private volatile Long nameUid;

    @Autowired
    public BilibiliSpeakerLoginProvider(BilibiliSpeakerAccount speaker) {
        this.speaker = speaker;
    }

    @Override
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public String id() {
        return BilibiliSpeakerAccount.ID;
    }

    @Override
    public String displayName() {
        return BilibiliSpeakerAccount.DISPLAY_NAME;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public boolean loginOnDemand() {
        return true;
    }

    @Override
    public String startLogin() {
        return speaker.startLogin();
    }

    @Override
    public boolean isLoggedIn() {
        return speaker.isLoggedIn();
    }

    @Override
    public Optional<String> accountId() {
        return Optional.ofNullable(speaker.uid()).map(String::valueOf);
    }

    @Override
    public Optional<String> accountName() {
        Long uid = speaker.uid();
        if (uid == null || !speaker.isLoggedIn()) {
            return Optional.empty();
        }
        if (!uid.equals(nameUid)) {
            // 换了号：上一个号的昵称作废
            nameUid = uid;
            name = null;
            nameTried = false;
        }
        if (!nameTried && nameInFlight.compareAndSet(false, true)) {
            try {
                Up up = speaker.api().getUpInfoByUid(uid);
                nameTried = true;
                if (up != null && StringUtil.isNotBlank(up.getUname())) {
                    name = up.getUname();
                }
            } catch (RiskCooldownException e) {
                // 冷却中不算问过，下次刷新再问；不登记补发，免得为一个昵称另起线程
            } catch (Exception ignored) {
                // 失败静默：卡上只显 uid
                nameTried = true;
            } finally {
                nameInFlight.set(false);
            }
        }
        return Optional.ofNullable(name);
    }

    @Override
    public Optional<String> pendingQrCodeContent() {
        return speaker.pendingQrCodeContent();
    }

    @Override
    public Optional<String> loginNotice() {
        return speaker.notice();
    }

    @Override
    public Optional<Instant> credentialExpiresAt() {
        return speaker.isLoggedIn() ? speaker.credentialExpiresAt() : Optional.empty();
    }

    @Override
    public Optional<String> credentialNote() {
        return speaker.isLoggedIn() ? Optional.of(speaker.credentialNote()) : Optional.empty();
    }

    @Override
    public void logout() {
        speaker.logout();
        name = null;
        nameUid = null;
        nameTried = false;
    }
}
