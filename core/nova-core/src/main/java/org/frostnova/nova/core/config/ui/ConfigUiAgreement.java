package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
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
     * 判据只此一处：要过这道闸的地方不止一个（口令登录、启动令牌通道、未配口令时的令牌形态），
     * 各自抄一遍比较逻辑，迟早会有一处漏改而变成绕过协议的口子。
     * <p>
     * 两件事任一不成立就要重问一次：
     * <ul>
     *   <li><b>记着的版本不是当前这一版</b>——文案改过了，此前那次同意针对的是另一份文字</li>
     *   <li><b>记不出是谁点的</b>（{@code acceptedBy} 为空）——这种记录出自「登录之前就能点同意」
     *       的旧版本，那时任何能连上控制台端口的程序都写得下它，因此它<b>证明不了使用者本人看过</b>。
     *       重问一次的代价是一屏文字，而留着它的代价是这份记录从此不作数</li>
     * </ul>
     * 收整份记录而不是其中一项：拆成几个参数，就一定会有调用方只传版本号那一半，
     * 而漏掉的恰恰是新添的那一半。
     * @param agreement 配置里的同意记录
     * @return 需要请使用者确认协议时返回 true
     */
    public static boolean required(NovaCoreProperties.ConfigUi.Agreement agreement) {
        return agreement.getAcceptedVersion() < VERSION
                || agreement.getAcceptedBy() == null
                || agreement.getAcceptedBy().isBlank();
    }

    static final String ACTOR_RECORD = "novabot.core.config-ui.agreement.accepted-actor-record";

    static String actorRecord(String version, String at, String by) {
        return com.alibaba.fastjson2.JSON.toJSONString(java.util.Arrays.asList(version, at, by));
    }

    /** Old versions update the acceptance but leave unknown properties untouched. */
    static void hideStaleActor(java.util.Map<String, String> values) {
        String prefix = "novabot.core.config-ui.agreement.";
        String expected = actorRecord(values.get(prefix + "accepted-version"), values.get(prefix + "accepted-at"),
                values.get(prefix + "accepted-by"));
        if (!expected.equals(values.get(ACTOR_RECORD))) values.remove(prefix + "accepted-actor");
        values.remove(ACTOR_RECORD);
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
