package org.frostnova.nova.core.config.ui;

import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监听地址在文件与界面上该怎么写
 * <p>
 * {@link InetAddress#toString()} 的形态是 {@code hostname/ip}，主机名解析不到时是
 * {@code /127.0.0.1} 或 {@code /0:0:0:0:0:0:0:1}。第一次写出配置文件时若把运行中的
 * {@code ServerProperties.address} 原样 {@code String.valueOf} 进去，设置页上「监听地址」
 * 就会显示成带头斜杠的一串，并被算成偏离了出厂的 {@code 127.0.0.1}；Spring 绑定
 * {@code server.address} 时也认不出这串，进程起不来。
 * <p>
 * CIDR（{@code 127.0.0.1/32}、{@code ::1/128}）对不上下面两支正则。掩码写法
 * {@code 10.0.0.0/255.255.255.0} 对得上 IPv4 那支，所以读口必须按键设门，
 * 只有监听地址这类键才走 {@link #fromFile(String)}。
 */
final class InetAddressText {
    /**
     * {@code InetAddress.toString()} 的 IPv4 形态：可选主机名、一条斜杠、四个点分十进制
     */
    private static final Pattern INET4 = Pattern.compile("^[^/]*/(\\d{1,3}(?:\\.\\d{1,3}){3})$");

    /**
     * {@code InetAddress.toString()} 的 IPv6 形态：可选主机名、一条斜杠、至少含一个冒号的地址。
     * 地址段必须带冒号，以免把 {@code ::1/128} 的 {@code 128} 收成「地址」。
     */
    private static final Pattern INET6 = Pattern.compile("^[^/]*/([0-9a-fA-F]*:[0-9a-fA-F:]+)$");

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
     * 读出来若是 {@code InetAddress.toString()} 那一串，收成裸地址
     * @param text 文件里的值
     * @return 去掉斜杠形态后的文本；对不上这两支正则则原样
     */
    static String fromFile(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher v4 = INET4.matcher(text);
        if (v4.matches()) {
            return v4.group(1);
        }
        Matcher v6 = INET6.matcher(text);
        return v6.matches() ? v6.group(1) : text;
    }
}
