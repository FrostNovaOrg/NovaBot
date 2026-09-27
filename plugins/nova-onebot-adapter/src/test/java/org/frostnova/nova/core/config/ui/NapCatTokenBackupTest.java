package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.napcat.NapCatCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * NapCat 的明文 token 换哈希写回那一次，同目录不留下含明文的备份
 * <p>
 * 抓的用户故障：配置里手写明文 token 启动一次以后，配置目录里的备份文件还留着明文 token。
 * 那串 token 是 QQ 机器人的代登录凭据，谁拿到配置目录就拿到了它。
 * <p>
 * 本件摆在核心的配置包下：写回那条路的文件服务，构造器只在这个包里可见；
 * 台架因此走的是真文件服务，量的是盘上的备份文件，不是桩记下的调用。
 */
@DisplayName("NapCat 的 token 换哈希写回不留明文备份")
class NapCatTokenBackupTest {

    private static final String PLAIN = "plain-token-865";

    private static final String TEMPLATE = """
            novabot:
              adapter:
                onebot:
                  napcat:
                    address: http://127.0.0.1:6099
                    token: plain-token-865
                    token-hash: ""
            """;

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);
    }

    /**
     * 配置目录里还留着这段明文的文件名，一份没有时为空表
     */
    private List<String> filesHolding(String plaintext) throws IOException {
        List<String> left = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isRegularFile(file)
                        && Files.readString(file, StandardCharsets.UTF_8).contains(plaintext)) {
                    left.add(file.getFileName().toString());
                }
            }
        }
        return left;
    }

    @Test
    @DisplayName("写回哈希那一次，同目录不留下含明文的备份")
    void hashWriteBackLeavesNoPlaintextBehind() throws IOException {
        List<String> red = new ArrayList<>();

        List<String> before = filesHolding(PLAIN);
        try {
            // 对照：还没写回时这把扫描要看得见手写那一份明文。看不见就等于它只会报空表，
            // 后面那句「一份都没有」在它瞎掉的时候同样是绿的
            assertFalse(before.isEmpty(), "对照：写回前要在配置目录里找得到明文，得到：" + before);
        } catch (AssertionError e) {
            red.add("①" + e.getMessage());
        }

        OneBotAdapterPluginProperties.NapCat properties = new OneBotAdapterPluginProperties.NapCat();
        properties.setToken(PLAIN);
        properties.setTokenHash("");
        properties.setTotpSecret("");
        properties.setAddress("http://127.0.0.1:6099");
        new NapCatCredentialService(properties, fileService, mock(RestTemplate.class));

        try {
            List<String> left = filesHolding(PLAIN);
            assertTrue(left.isEmpty(),
                    "手写明文 token 启动一次以后，配置目录里的备份文件还留着明文 token，留下的是：" + left);
        } catch (AssertionError e) {
            red.add("②" + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("写回不留明文备份两问中 " + red.size() + " 问未销：" + String.join("；", red));
        }
    }
}
