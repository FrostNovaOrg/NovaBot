package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 最近一场的昵称：读归档偶然失败一次之后
 * <p>
 * 读失败时这一次答不上来是对的，但那份空结果不能记下来——文件的修改时刻与大小没变，
 * 记下来的话，直到下一场归档写进来之前，各处主播名都只剩一串 uid。
 */
@DisplayName("最近一场的昵称：读归档失败一次，归档恢复可读后下一次照样读得到")
class LiveSessionArchiveLatestUnameReadFailureTest {
    private static final long UID = 4242L;

    @TempDir
    Path dir;

    @Test
    @DisplayName("读失败的那次为空；恢复可读后下一次读到最近一场的昵称")
    void readFailureIsNotCached() throws IOException {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        LiveSessionArchive archive = new LiveSessionArchive(properties);

        JSONObject line = new JSONObject();
        line.put("platform", "bilibili");
        line.put("uid", UID);
        line.put("uname", "最近的昵称");
        line.put("startTime", 1_700_000_000_000L);
        line.put("endTime", 1_700_003_600_000L);
        Path file = dir.resolve("sessions.jsonl");
        Files.writeString(file, line.toJSONString() + System.lineSeparator(), StandardCharsets.UTF_8);

        Set<PosixFilePermission> readable = Files.getPosixFilePermissions(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(file), "以超级用户身份运行时去掉读权限拦不住读取");
            assertEquals(Optional.empty(), archive.latestUname("bilibili", UID), "读不了的那一次答不上来");
        } finally {
            Files.setPosixFilePermissions(file, readable);
        }

        assertEquals(Optional.of("最近的昵称"), archive.latestUname("bilibili", UID),
                "读失败时的空结果被记住了，归档恢复可读后仍答不上来");
    }
}
