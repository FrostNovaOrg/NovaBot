package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.LiveProperties;
import com.starlwr.bot.core.model.EventStreamToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 事件流口令落盘失败时，对外不得报成功
 * <p>
 * 签发若在写入失败后仍交出明文，调用方会以为口令可用，实际校验读不到表。
 * 吊销若在写入失败后仍回报已撤，运维以为旧口令作废，文件里那一行还活着。
 */
@DisplayName("事件流口令落盘失败不得报成功")
class EventStreamTokenPersistFailureMustNotReportSuccessTest {
    @TempDir
    Path dir;

    private EventStreamTokenService service(Path dataFile) {
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(dataFile.toString());
        return new EventStreamTokenService(live);
    }

    @Test
    @DisplayName("对照：正常目录下签发的口令能校验通过，吊销后新连接拒")
    void happyPathIssueAndRevoke() {
        EventStreamTokenService tokens = service(dir.resolve("data.json"));
        String token = tokens.issue("面板-甲");

        assertTrue(tokens.verify(token), "阳性对照：正常签发必须能校验");
        EventStreamToken record = tokens.list().get(0);
        assertTrue(tokens.revoke(tokens.fingerprintOf(record)));
        assertFalse(tokens.verify(token), "正常吊销后应拒");
    }

    @Test
    @DisplayName("父路径是普通文件、写入必然失败时，签发不得交出明文口令")
    void issueMustNotReturnATokenWhenAppendFails() throws IOException {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "occupied");
        EventStreamTokenService tokens = service(blocker.resolve("data.json"));

        String token = null;
        try {
            token = tokens.issue("面板-乙");
        } catch (RuntimeException expected) {
            assertNull(token);
            return;
        }

        fail("落盘失败时不该交出明文口令, 实际交出了长度 " + (token == null ? 0 : token.length())
                + " 且 verify=" + tokens.verify(token));
    }

    @Test
    @DisplayName("口令表只读、重写必然失败时，吊销不得回报成功")
    void revokeMustNotReportSuccessWhenRewriteFails() throws Exception {
        EventStreamTokenService tokens = service(dir.resolve("ok/data.json"));
        tokens.issue("面板-丙");
        EventStreamToken record = tokens.list().get(0);
        String fingerprint = tokens.fingerprintOf(record);

        Path ledger = Path.of(dir.resolve("ok/data.json").toAbsolutePath().toString())
                .getParent()
                .resolve("event-stream-tokens.jsonl");
        var original = Files.getPosixFilePermissions(ledger);
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("r--r--r--"));
        try {
            assertThrows(UncheckedIOException.class, () -> tokens.revoke(fingerprint),
                    "写不进盘时应当让调用方看见失败，而不是回报没找到");
            List<EventStreamToken> after = tokens.list();
            boolean stillActive = after.stream().anyMatch(EventStreamToken::active);
            assertTrue(stillActive, "写失败之后盘上那一行还应当活着");
        } finally {
            Files.setPosixFilePermissions(ledger, original);
        }
    }
}
