package org.frostnova.nova.adapter.onebot.converter;

import org.frostnova.nova.core.util.MessageImageAddressPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 测试里的图片主机不联网解析：{@code example.com} 当作一个公网地址，别的名字一律解析不出来
 */
public final class PublicImageHosts {
    private PublicImageHosts() {
    }

    /**
     * @return 判图片地址时不联网的转换器
     */
    public static OneBotMessageConverter converter() {
        return new OneBotMessageConverter(new MessageImageAddressPolicy(List.of(), host -> {
            if (!host.equals("example.com")) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 203, 0, 113, 10})};
        }));
    }
}
