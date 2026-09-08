package com.starlwr.bot.adapter.onebot.extension.napcat.util;

import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哪些推送平台是 NapCat
 * <p>
 * 这份名单是<b>运行期攒出来的</b>：连上哪台、问过版本、认出是 NapCat，才有这一条。
 * 因此「没登记过」是常态而不是异常情形——扩展功能要能安静地让路，
 * 而不是在一台跑着别家 OneBot 实现的机器上到处抛异常。
 */
@DisplayName("NapCat 服务容器")
class NapcatServiceHolderTest {
    private final NapcatServiceHolder holder = new NapcatServiceHolder();

    private static OneBotSender sender(String name) {
        OneBotSender sender = new OneBotSender();
        sender.setName(name);
        return sender;
    }

    @Test
    @DisplayName("没登记过的平台不算 NapCat")
    void unknownSenderIsNotNapcat() {
        assertFalse(holder.isNapcat("qq-onebot"));
    }

    @Test
    @DisplayName("登记之后按名字认得出, 取回的就是登记进去的那一个对象")
    void registeredSenderIsFoundByName() {
        OneBotSender sender = sender("napcat-qq");

        holder.registerNapcat(sender);

        assertTrue(holder.isNapcat("napcat-qq"));
        assertSame(sender, holder.getNapcat("napcat-qq"));
    }

    @Test
    @DisplayName("名字是精确匹配, 不认大小写变体")
    void nameIsMatchedExactly() {
        holder.registerNapcat(sender("napcat-qq"));

        assertFalse(holder.isNapcat("NapCat-QQ"));
    }

    @Test
    @DisplayName("同名再登记一次覆盖旧的, 重连换了对象也取得到新的")
    void reRegisterReplaces() {
        holder.registerNapcat(sender("napcat-qq"));
        OneBotSender reconnected = sender("napcat-qq");

        holder.registerNapcat(reconnected);

        assertSame(reconnected, holder.getNapcat("napcat-qq"));
    }

    @Test
    @DisplayName("多个平台各存各的, 互不覆盖")
    void keepsSendersApart() {
        OneBotSender first = sender("napcat-a");
        OneBotSender second = sender("napcat-b");

        holder.registerNapcat(first);
        holder.registerNapcat(second);

        assertSame(first, holder.getNapcat("napcat-a"));
        assertSame(second, holder.getNapcat("napcat-b"));
    }

    @Test
    @DisplayName("取一个没登记过的平台会抛异常, 异常里带得出是哪个平台")
    void missingSenderThrows() {
        NoSuchElementException thrown = assertThrows(NoSuchElementException.class, () -> holder.getNapcat("qq-onebot"));

        assertEquals("qq-onebot 不是一个 Napcat 服务", thrown.getMessage());
    }
}
