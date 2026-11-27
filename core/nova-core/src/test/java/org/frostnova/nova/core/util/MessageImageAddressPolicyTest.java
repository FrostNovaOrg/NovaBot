package org.frostnova.nova.core.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.MessageImageAddressPolicy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("消息图片地址能不能取")
class MessageImageAddressPolicyTest {
    /** 测试里的域名表：不联网，查不到的当作解析不出来 */
    private static final Map<String, List<String>> NAMES = Map.of(
            "cdn.example", List.of("203.0.113.5"),
            "v6only.example", List.of("2001:db8::5"),
            "fakeip.example", List.of("198.18.3.4"),
            "rebind.example", List.of("203.0.113.5", "127.0.0.1"),
            "metadata.example", List.of("169.254.169.254"),
            "nat64.example", List.of("64:ff9b::a9fe:a9fe"),
            "mapped.example", List.of("mapped:10.0.0.1"),
            "nas.lan", List.of("192.168.1.30"),
            "router.lan", List.of("192.168.2.1"));

    private final List<String> resolved = new ArrayList<>();

    private InetAddress[] resolve(String host) throws UnknownHostException {
        resolved.add(host);
        List<String> addresses = NAMES.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        List<InetAddress> out = new ArrayList<>();
        for (String address : addresses) {
            if (address.startsWith("mapped:")) {
                // 解析回来的就是 IPv6 形式的映射地址，不让系统先把它换成 IPv4
                byte[] v4 = InetAddress.getByName(address.substring("mapped:".length())).getAddress();
                byte[] v6 = new byte[16];
                v6[10] = (byte) 0xff;
                v6[11] = (byte) 0xff;
                System.arraycopy(v4, 0, v6, 12, 4);
                out.add(Inet6Address.getByAddress(host, v6, -1));
            } else {
                out.add(InetAddress.getByAddress(host, InetAddress.getByName(address).getAddress()));
            }
        }
        return out.toArray(InetAddress[]::new);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://cdn.example/a.png                    | ALLOWED
            https://cdn.example:8443/a.png?w=1&h=2       | ALLOWED
            https://v6only.example/a.png                 | ALLOWED
            https://[2001:db8::7]/a.png                  | ALLOWED
            http://203.0.113.9:65535/a.png               | ALLOWED
            https://fakeip.example/a.png                 | ALLOWED
            http://198.18.0.1/a.png                      | ALLOWED
            http://198.19.255.255/a.png                  | ALLOWED
            http://100.128.0.1/a.png                     | ALLOWED
            http://172.32.0.1/a.png                      | ALLOWED
            https://rebind.example/a.png                 | BLOCKED
            http://metadata.example/latest/meta-data/    | BLOCKED
            http://nat64.example/a.png                   | BLOCKED
            http://mapped.example/a.png                  | BLOCKED
            http://router.lan/a.png                      | BLOCKED
            http://localhost/a.png                       | BLOCKED
            http://img.localhost./a.png                  | BLOCKED
            http://2130706433/a.png                      | BLOCKED
            http://3232235777/a.png                      | BLOCKED
            http://0/a.png                               | BLOCKED
            http://172.31.255.255/a.png                  | BLOCKED
            http://100.127.255.255/a.png                 | BLOCKED
            http://224.0.0.251/a.png                     | BLOCKED
            http://255.255.255.255/a.png                 | BLOCKED
            http://[::]/a.png                            | BLOCKED
            http://[fec0::1]/a.png                       | BLOCKED
            http://[ff02::1]/a.png                       | BLOCKED
            http://[::127.0.0.1]/a.png                   | BLOCKED
            http://[::ffff:0:a9fe:a9fe]/a.png            | BLOCKED
            http://[2002:7f00:1::]/a.png                 | BLOCKED
            http://[64:ff9b:1::1]/a.png                  | BLOCKED
            https://missing.example/a.png                | UNRESOLVED
            http://user:secret@cdn.example/a.png         | INVALID
            ftp://cdn.example/a.png                      | INVALID
            file:///etc/hosts                            | INVALID
            http:///a.png                                | INVALID
            http://cdn.example:0/a.png                   | INVALID
            http://cdn.example:65536/a.png               | INVALID
            http://0177.0.0.1/a.png                      | INVALID
            http://127.1/a.png                           | INVALID
            http://[fe80::1%25en0]/a.png                 | INVALID
            """)
    @DisplayName("按规则表判：解析出的任意一个地址落在不许取的网段就不收，内嵌的 IPv4 一并判，代理的假地址段不拦")
    void refusesHostsThatResolveOrUnwrapIntoBlockedNetworks(String url, Verdict expected) {
        var policy = new MessageImageAddressPolicy(List.of(), this::resolve);
        assertEquals(expected, policy.check(url), url);
        if (url.contains("localhost") || !url.contains(".example") && !url.contains(".lan")) {
            assertEquals(List.of(), resolved, "IP 字面量与 localhost 不该拿去解析");
        }
    }

    @Test
    @DisplayName("写进「消息图片 · 允许的内网网段」的网段可以取，写错的那条忽略并告警")
    @ExtendWith(OutputCaptureExtension.class)
    void allowsAddressesInsideTheConfiguredNetworks(CapturedOutput output) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setImageAllowedNetworks(List.of("192.168.1.0/24", "fd00:1::/32", "not-a-network",
                "10.0.0.0/33"));
        var configured = new MessageImageAddressPolicy(properties);
        assertTrue(configured.permits("http://192.168.1.20/a.png"));
        assertTrue(configured.permits("https://[fd00:1::8]:8443/a.png"));
        assertTrue(configured.permits("http://[::ffff:192.168.1.5]/a.png"));
        assertEquals(Verdict.BLOCKED, configured.check("http://192.168.2.1/a.png"));
        assertEquals(Verdict.BLOCKED, configured.check("http://10.0.0.1/a.png"));
        assertEquals(Verdict.BLOCKED, configured.check("http://127.0.0.1/a.png"));
        assertTrue(output.getOut().contains("novabot.core.push.image-allowed-networks"), output.getOut());
        assertTrue(output.getOut().contains("not-a-network"), output.getOut());
        assertTrue(output.getOut().contains("10.0.0.0/33"), output.getOut());

        var withNames = new MessageImageAddressPolicy(properties.getPush().getImageAllowedNetworks(), this::resolve);
        assertEquals(Verdict.ALLOWED, withNames.check("http://nas.lan/a.png"));
        assertEquals(Verdict.BLOCKED, withNames.check("http://router.lan/a.png"));
        assertEquals(Verdict.BLOCKED, new MessageImageAddressPolicy(List.of(), this::resolve)
                .check("http://nas.lan/a.png"));
    }
}
