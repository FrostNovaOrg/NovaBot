package org.frostnova.nova.core.config.ui;

import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监听地址在文件与界面上该怎么写
 * <p>
 * {@link InetAddress#toString()} 的形态是 {@code hostname/ip}，主机名解析不到时是
 * {@code /127.0.0.1}。第一次写出配置文件时若把运行中的 {@code ServerProperties.address}
 * 原样 {@code String.valueOf} 进去，设置页上「监听地址」就会显示成带头斜杠的一串，
 * 并被算成偏离了出厂的 {@code 127.0.0.1}。
 * <p>
 * 这里只动 IPv4 的这一种形态：CIDR（{@code 127.0.0.1/32}）与 URL 对不上这支正则，
 * 不会被改写成别的东西。
 */
final class InetAddressText {
    /**
     * {@code InetAddress.toString()} 的 IPv4 形态：可选主机名、一条斜杠、四个点分十进制
     */
    private static final Pattern INET4 = Pattern.compile("^[^/]*/(\\d{1,3}(?:\\.\\d{1,3}){3})$");

    private InetAddressText() {
    }

    /**
     * 写成配置值
     * @param value 任意对象，{@link InetAddress} 取主机地址，其余原样
     * @return 可写入 YAML 的标量；{@code null} 原样返回
     */
    static Object forYaml(Object value) {
        if (value instanceof InetAddress address) {
            return address.getHostAddress();
        }
        return value;
    }

    /**
     * 读出来若是 {@code InetAddress.toString()} 那一串，收成点分地址
     * @param text 文件里的值
     * @return 去掉斜杠形态后的文本；对不上这支正则则原样
     */
    static String fromFile(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = INET4.matcher(text);
        return matcher.matches() ? matcher.group(1) : text;
    }
}
