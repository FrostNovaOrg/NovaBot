package com.starlwr.bot.core.config.ui;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 控制台使用协议：文案改版时把 {@link #VERSION} 加一，此前记下的同意随即失效，使用者会被要求重新确认一次。
 */
public final class ConfigUiAgreement {
    /**
     * 当前协议版本
     */
    public static final int VERSION = 1;

    /**
     * 协议文案所在的类路径资源
     * <p>
     * 文案只此一份：界面显示的与这里读出来的是同一个文件，不存在「页面上改了、别处那份没改」。
     */
    private static final String RESOURCE = "config-ui/agreement.txt";

    private ConfigUiAgreement() {
    }

    /**
     * 是否还需要使用者确认协议
     * <p>
     * 判据只此一处：签发会话的地方不止一个（口令登录与启动令牌通道），
     * 各自抄一遍比较逻辑，迟早会有一处漏改而变成绕过协议的口子。
     * @param acceptedVersion 已记录的同意版本，从未同意过时为 0
     * @return 需要先确认协议时返回 true
     */
    public static boolean required(int acceptedVersion) {
        return acceptedVersion < VERSION;
    }

    /**
     * 读取协议全文
     * @return 协议文案
     * @throws IOException 读取失败时抛出
     */
    public static String text() throws IOException {
        try (var stream = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
