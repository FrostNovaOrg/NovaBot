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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 候命期间在旧版里改过的 token，过门后的写回不把它盖掉
 * <p>
 * 热升级候命那一会儿旧版还开着，设置页若换了 token，旧版会把新值存进文件；
 * 新版过门后的写回照写的话，新 token 被盖回起动时那份明文的哈希，重启后新 token 不认。
 * 写回前核文件里还是不是起动时那份明文，不是就不写。
 * <p>
 * 与 {@link NapCatTokenBackupTest} 同包：文件服务的构造器只在这个包里可见，
 * 台架因此走真文件服务，量的是盘上的文件，不是桩记下的调用。
 */
@DisplayName("候命期间改过的 token 不被写回盖掉")
class NapCatTokenChangedDuringStandbyTest {

    private static final String STARTUP_TOKEN = "startup-plain-token-483";

    private static final String CHANGED_TOKEN = "standby-changed-token-917";

    private static final String TEMPLATE = """
            novabot:
              adapter:
                onebot:
                  napcat:
                    address: http://127.0.0.1:6099
                    token: startup-plain-token-483
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

    @Test
    @DisplayName("候命期间在旧版里改过的 token 不被写回盖掉")
    void tokenChangedDuringStandbyIsNotOverwritten() throws IOException {
        OneBotAdapterPluginProperties.NapCat properties = new OneBotAdapterPluginProperties.NapCat();
        properties.setToken(STARTUP_TOKEN);
        properties.setTokenHash("");
        properties.setTotpSecret("");
        properties.setAddress("http://127.0.0.1:6099");
        NapCatCredentialService service = new NapCatCredentialService(
                properties, fileService, mock(RestTemplate.class));

        // 构造之后、写回之前，把文件里那一项换成新值（模拟旧版在候命期里的保存）
        fileService.writeWithoutBackup(Map.of(NapCatCredentialService.TOKEN_PROPERTY, CHANGED_TOKEN));

        service.start();

        assertEquals(CHANGED_TOKEN, fileService.read().get(NapCatCredentialService.TOKEN_PROPERTY),
                "候命期间被改过的那一项要原样留着，等下次启动读到新值再换哈希");
    }
}
