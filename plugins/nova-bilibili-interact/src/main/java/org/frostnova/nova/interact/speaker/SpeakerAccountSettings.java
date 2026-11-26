package org.frostnova.nova.interact.speaker;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.interact.config.InteractProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 发言账号的账号配置
 * <p>
 * 凭据放在哪两项读本插件自己的配置，别的项一律读监听账号那一份的<b>此刻值</b>：
 * 复检间隔、退避上限、自动续期、扫码方式、加密与否，两个账号同一个节奏、同一种存法。
 * 读此刻值而不是启动时抄一份：自动续期、扫码方式是改了当场生效的项，抄下来的那份不会跟着变。
 * <p>
 * 匿名模式恒为关：那是监听账号的选择，监听账号免登录时发言账号照样可以登录。
 */
final class SpeakerAccountSettings extends NovaBilibiliProperties.Account {
    private final NovaBilibiliProperties.Account listener;

    private final InteractProperties.Account own;

    SpeakerAccountSettings(NovaBilibiliProperties.Account listener, InteractProperties.Account own) {
        this.listener = listener;
        this.own = own;
    }

    /**
     * 发言账号的凭据文件或密钥文件是不是与监听账号的某一个落在同一处
     * <p>
     * 落在同一处的话，发言账号会把监听账号的凭据读进来，扫码登录会写坏它，退出登录会把它删掉——
     * 监听账号跟着掉登录。四对路径两两比：各自解析成绝对路径、去掉 {@code .} 与 {@code ..} 之后比；
     * 两头的文件都已存在时，再问一次文件系统是不是同一个文件（认得出链接）。只看路径与文件身份，不读内容。
     * @return 冲突时的说明，没有冲突时为空
     */
    Optional<String> sharedFileWithListener() {
        List<String> mine = List.of(own.getCookiePath(), own.getKeyPath());
        List<String> theirs = List.of(listener.getCookiePath(), listener.getKeyPath());
        for (String a : mine) {
            for (String b : theirs) {
                if (samePlace(a, b)) {
                    return Optional.of(SHARED_FILE);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 路径冲突时说给使用者的话
     */
    static final String SHARED_FILE = "发言账号的凭据文件或密钥文件与监听账号的是同一个，发言账号已停用。"
            + "两个账号不能共用同一个凭据文件，请把 novabot.bilibili.interact.account.cookie-path 与 key-path "
            + "改回各自的路径后重启";

    private static boolean samePlace(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        Path left;
        Path right;
        try {
            left = Path.of(a).toAbsolutePath().normalize();
            right = Path.of(b).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        if (Files.exists(left) && Files.exists(right)) {
            try {
                return Files.isSameFile(left, right);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    @Override
    public String getCookiePath() {
        return own.getCookiePath();
    }

    @Override
    public String getKeyPath() {
        return own.getKeyPath();
    }

    @Override
    public boolean isEncrypt() {
        return listener.isEncrypt();
    }

    @Override
    public int getVerifyInterval() {
        return listener.getVerifyInterval();
    }

    @Override
    public int getMaintenanceBackoffCap() {
        return listener.getMaintenanceBackoffCap();
    }

    @Override
    public boolean isAutoRefreshCookie() {
        return listener.isAutoRefreshCookie();
    }

    @Override
    public String getQrCodeLoginMode() {
        return listener.getQrCodeLoginMode();
    }
}
