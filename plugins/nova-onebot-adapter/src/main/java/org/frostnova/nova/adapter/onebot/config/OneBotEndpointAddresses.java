package org.frostnova.nova.adapter.onebot.config;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把配置里写的 one-bot-address 拼成 HTTP 与 Websocket 实际连接用的地址
 * <p>
 * NapCat 放在另一台机器上、中间走的是不可信网络时，访问令牌与消息都要靠 TLS 挡着。
 * 因此地址除了只写主机（照旧明文），前面还可以带协议头：
 * <ul>
 *     <li>{@code https://主机} 或 {@code wss://主机}——HTTP 走 https、Websocket 走 wss，
 *         两者一起加密；协议头不分大小写</li>
 *     <li>{@code http://主机}、{@code ws://主机}——明说不要加密，按明文拼</li>
 * </ul>
 * 端口不写在地址里，照旧填在 one-bot-http-port 与 one-bot-websocket-port 两个端口项。
 * <p>
 * IPv6 字面量照浏览器的写法整个用方括号括起来（{@code [::1]}）：方括号里的冒号是地址
 * 自己的，不算端口；方括号后面紧跟的 {@code :端口} 才照旧按带端口处理。
 * <p>
 * 地址里带了路径、端口或别的协议头时<b>不去猜</b>：说一声该怎么写，然后按老写法照原样拼。
 * 拼出来的地址连不上时，会走原有的连不上提示——那才是排障的熟路。
 * <p>
 * 加密连接的证书按 JVM 默认信任链校验，没有也不加跳过校验的开关：跳过校验的加密
 * 只防得了抓包工具，防不了站在中间的人。
 * <p>
 * HTTP 代理、NapCat 扩展代理、Websocket 连接、两条「连接地址」日志与连接测试拼的都是
 * 这一处，谁也不许自己再拼一份——各拼各的迟早拼出「日志里 https、实际连的 http」。
 */
@Slf4j
public final class OneBotEndpointAddresses {

    /**
     * 地址前面可以带的协议头
     */
    private static final Set<String> KNOWN_SCHEMES = Set.of("http", "https", "ws", "wss");

    /**
     * 已经说过「这个写法不认识」的（平台名＋地址）
     * <p>
     * 拼地址发生在每一次接口调用上，没有这一份的话，一个怪写法会把同一句话按调用次数
     * 刷进日志。改成另一个怪写法要再说一声，因此键里有地址本身。
     */
    private static final Set<String> UNRECOGNIZED_WARNED = ConcurrentHashMap.newKeySet();

    private OneBotEndpointAddresses() {
    }

    /**
     * HTTP 接口的基地址，如 {@code https://napcat.internal:3000}
     * @param senderName 推送平台名，写进「该怎么写」那句提醒
     * @param address 配置里写的 one-bot-address 原值
     * @param port one-bot-http-port
     * @return 基地址，接口路径由调用方接在后面
     */
    public static String httpBaseUrl(String senderName, String address, int port) {
        return assemble(senderName, address, port, "http", "https");
    }

    /**
     * Websocket 的连接地址，如 {@code wss://napcat.internal:3001}
     * @param senderName 推送平台名，写进「该怎么写」那句提醒
     * @param address 配置里写的 one-bot-address 原值
     * @param port one-bot-websocket-port
     * @return 连接地址
     */
    public static String websocketUrl(String senderName, String address, int port) {
        return assemble(senderName, address, port, "ws", "wss");
    }

    private static String assemble(String senderName, String rawAddress, int port, String plainScheme, String secureScheme) {
        // null 落到 "null" 上，与原来字符串拼接的结果一致，不算要提醒的怪写法
        String address = String.valueOf(rawAddress);
        boolean secure = false;
        String problem = null;
        String host = address;

        int schemeEnd = address.indexOf("://");
        if (schemeEnd >= 0) {
            String scheme = address.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (KNOWN_SCHEMES.contains(scheme)) {
                secure = "https".equals(scheme) || "wss".equals(scheme);
                host = address.substring(schemeEnd + 3);
            } else {
                problem = "协议头不是 http/https/ws/wss";
            }
        }
        if (problem == null && host.contains("/")) {
            problem = "地址里带了路径";
        }
        if (problem == null && hostWithoutIpv6Literal(host).contains(":")) {
            problem = "地址里带了端口";
        }
        if (problem != null) {
            warnUnrecognized(senderName, address, problem);
            // 按老写法照原样拼：连不上会走原有的连不上提示
            return plainScheme + "://" + address + ":" + port;
        }
        return (secure ? secureScheme : plainScheme) + "://" + host + ":" + port;
    }

    /**
     * 把开头的 IPv6 字面量（如 {@code [::1]}）那段拿掉，剩下的部分里的冒号才是端口：
     * 方括号里那些是地址自己的。没写方括号的 IPv6（{@code ::1}）不在此列——
     * 那样拼不出能用的地址，仍按带端口的老路提醒一句，提醒里教了怎么写
     */
    private static String hostWithoutIpv6Literal(String host) {
        if (!host.startsWith("[")) {
            return host;
        }
        int closeBracket = host.indexOf(']');
        return closeBracket < 0 ? host : host.substring(closeBracket + 1);
    }

    /**
     * 说一声这个写法不认识、该怎么写。同一个平台名＋写法只说一次
     */
    private static void warnUnrecognized(String senderName, String address, String problem) {
        if (!UNRECOGNIZED_WARNED.add(senderName + "\n" + address)) {
            return;
        }
        log.warn("推送平台 {} 的 one-bot-address 写的是「{}」: {}。已按老写法照原样拼成明文地址, 连不上时会走原有的连不上提示。"
                        + "地址里只写主机名; IPv6 地址要加方括号, 如 [::1]; 要加密连接就写成 https://主机 或 wss://主机（HTTP 与 Websocket 一起走加密）; "
                        + "端口照旧填在 one-bot-http-port 与 one-bot-websocket-port 两个端口项里",
                senderName, address, problem);
    }
}
