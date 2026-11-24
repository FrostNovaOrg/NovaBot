package org.frostnova.nova.core.secret;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretLogMaskTest {
    @Test
    void registeredValueIsMaskedOnAnyThreadInEveryRendering() throws Exception {
        SecretLogMask.remember("test:rendering", "fixture-mask \"quoted\" value");
        String masked = CompletableFuture.supplyAsync(() -> SecretLogMask.mask(
                "raw fixture-mask \"quoted\" value json fixture-mask \\\"quoted\\\" value url fixture-mask+%22quoted%22+value"))
                .get();
        assertFalse(masked.contains("fixture-mask"), masked);
        assertTrue(masked.startsWith("raw *** json *** url ***"), masked);
    }

    @Test
    void slotKeepsCurrentAndPreviousValueOnly() {
        SecretLogMask.remember("test:slot", "fixture-slot-first");
        SecretLogMask.remember("test:slot", "fixture-slot-second");
        SecretLogMask.remember("test:slot", "fixture-slot-third");
        assertEquals("fixture-slot-first *** ***",
                SecretLogMask.mask("fixture-slot-first fixture-slot-second fixture-slot-third"));
        SecretLogMask.forget("test:slot");
        assertEquals("fixture-slot-third", SecretLogMask.mask("fixture-slot-third"));
    }

    @Test
    void shortValuesAreLeftToTheHeaderRule() {
        SecretLogMask.remember("test:short", "abc12");
        assertEquals("room abc12345", SecretLogMask.mask("room abc12345"));
        assertEquals("{Authorization=[Bearer ***], Host=[h]}",
                SecretLogMask.mask("{Authorization=[Bearer abc12], Host=[h]}"));
        assertEquals("\"token\":\"QQBot ***\"", SecretLogMask.mask("\"token\":\"QQBot abc12\""));
        assertEquals("Authorization: Bearer ********", SecretLogMask.mask("Authorization: Bearer ********"));
    }

    @Test
    void truncatedPrefixOfALongValueIsMasked() {
        String value = "fixture-truncated-" + "x".repeat(120);
        SecretLogMask.remember("test:truncated", value);
        String line = "Writing [{\"clientSecret\":\"" + value.substring(0, 80) + " (truncated)...]";
        assertEquals("Writing [{\"clientSecret\":\"*** (truncated)...]", SecretLogMask.mask(line));
    }

    @Test
    void throwableIsCopiedOnlyWhenItCarriesACredential() {
        SecretLogMask.remember("test:error", "fixture-error-secret");
        var plain = new IOException("connection refused");
        assertSame(plain, SecretLogMask.mask(plain));

        var carrying = new IllegalStateException("outer", new IOException("header fixture-error-secret rejected"));
        Throwable masked = SecretLogMask.mask(carrying);
        assertEquals("java.lang.IllegalStateException: outer", masked.toString());
        assertEquals("java.io.IOException: header *** rejected", masked.getCause().toString());
        assertEquals(carrying.getStackTrace().length, masked.getStackTrace().length);
    }

    @Test
    void textWithoutCredentialsIsReturnedAsIs() {
        String text = "已连接到 qq-onebot 的 OneBot Websocket 服务";
        assertSame(text, SecretLogMask.mask(text));
    }
}
