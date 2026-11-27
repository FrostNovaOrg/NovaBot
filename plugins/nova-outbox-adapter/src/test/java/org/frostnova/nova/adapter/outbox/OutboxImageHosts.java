package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.util.MessageImageAddressPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 测试里的图片主机不联网解析：名字里带 missing 的解析不出来，{@code *.lan} 当作局域网里的 192.168.1.30，
 * 别的名字一律当作公网地址 203.0.113.10
 */
final class OutboxImageHosts {
    private OutboxImageHosts() {
    }

    /**
     * @param allowedNetworks 另外放行的网段
     * @return 判图片地址时不联网的那一件
     */
    static MessageImageAddressPolicy policy(String... allowedNetworks) {
        return new MessageImageAddressPolicy(List.of(allowedNetworks), host -> {
            if (host.contains("missing")) {
                throw new UnknownHostException(host);
            }
            byte[] address = host.endsWith(".lan")
                    ? new byte[]{(byte) 192, (byte) 168, 1, 30}
                    : new byte[]{(byte) 203, 0, 113, 10};
            return new InetAddress[]{InetAddress.getByAddress(host, address)};
        });
    }
}
