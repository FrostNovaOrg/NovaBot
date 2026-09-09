package org.frostnova.nova.core.datasource;

import org.frostnova.nova.core.properties.DatasourceProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.exception.DataSourceException;
import org.frostnova.nova.core.handler.StarBotEventHandlerPushMessageInitializer;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 还没有 datasource.json 的那台机器
 * <p>
 * 发行包不再带这个文件——它由控制台在加第一位主播时生成。于是「刚装好、还没加过主播」
 * 成了每台实例都要经过的一档，而数据源在那一档里<b>必须起得来</b>。
 * <p>
 * 先红读数（2026-09-04，改之前）：{@code load()} 对着不存在的文件抛
 * {@link DataSourceException}，而它是在 {@code ApplicationReadyEvent} 里抛出来的——
 * 🔴 <b>进程当场死掉，使用者看到的是「装好了起不来」，而他还没有任何机会去配置。</b>
 *
 * <h2>放行的只有「文件不在」这一形</h2>
 * 「还没配」与「路径配错了」在同一句 {@code NoSuchFileException} 上分不开，两者都只能放行
 * （在这一步抛异常就是进程死掉），日志里把两种读法一起说出来。<b>但「文件在、内容坏掉」不在此列</b>：
 * 把它也咽下去，表现是一台配了 20 位主播的机器安安静静地一条推送都不发，
 * 而启动日志一切正常。阴性那一格量的正是这条边。
 */
@DisplayName("缺 datasource.json")
class MissingDatasourceFileTest {
    private static JsonDataSource dataSourceAt(String path) {
        DatasourceProperties properties = new DatasourceProperties();
        properties.setJsonPath(path);
        // 监听线程与本组用例无关，开着只会在临时目录被删掉之后继续刷日志
        properties.setJsonAutoReload(false);

        return new JsonDataSource(
                mock(ApplicationEventPublisher.class),
                new DataSourceServiceRegistry(List.of()),
                new StarBotEventHandlerPushMessageInitializer(mock(StarBotEventHandlerService.class),
                        new PushTemplateDefaults(new NovaCoreProperties())),
                properties);
    }

    @Test
    @DisplayName("没有这个文件时，按空的推送配置起得来")
    void startsEmptyWhenTheFileIsAbsent(@TempDir Path directory) {
        Path missing = directory.resolve(new DatasourceProperties().getJsonPath());
        assertFalse(Files.exists(missing), "夹具起点：文件不存在");

        JsonDataSource dataSource = dataSourceAt(missing.toString());

        assertDoesNotThrow(dataSource::load,
                "🔴 加载在 ApplicationReadyEvent 里跑，这里抛出去进程就死了 —— "
                        + "而使用者看到的是「装好了起不来」，他还没有任何机会去配置");
        assertTrue(dataSource.getAllUsers().isEmpty(), "空表起，不该凭空多出主播");
    }

    @Test
    @DisplayName("阳性对照 —— 文件在的时候照常读得出来")
    void readsTheFileWhenItIsThere(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("datasource.json");
        Files.writeString(file, "[]");

        JsonDataSource dataSource = dataSourceAt(file.toString());

        assertDoesNotThrow(dataSource::load);
        assertTrue(dataSource.getAllUsers().isEmpty());
    }

    @Test
    @DisplayName("路径是使用者自己配的，缺文件也照样起得来 —— 不按「改没改过路径」分支")
    void aCustomPathThatIsMissingIsToleratedTheSameWay(@TempDir Path directory) {
        String custom = directory.resolve("nowhere/datasource.json").toString();
        assertFalse(custom.equals(new DatasourceProperties().getJsonPath()), "夹具起点：路径与默认值不同");

        JsonDataSource dataSource = dataSourceAt(custom);

        // 这一格钉的是一个「不做什么」的决定：不去按路径改没改过分成两支。
        // 分了也只分得动日志的级别，而分完之后没有任何判据量得到走的是哪一支；
        // 更要紧的是分错的代价不对称 —— 判成「配错了」就抛，等于把一台还没配过、
        // 只是顺手改了路径的机器拦在启动之外。
        assertDoesNotThrow(dataSource::load);
        assertTrue(dataSource.getAllUsers().isEmpty());
    }

    @Test
    @DisplayName("阴性 —— 文件在但内容坏掉，照旧当错误抛出来")
    void brokenContentIsStillAnError(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("datasource.json");
        Files.writeString(file, "{ 这不是一个数组");

        JsonDataSource dataSource = dataSourceAt(file.toString());

        assertThrows(DataSourceException.class, dataSource::load,
                "「文件不在」放行了，不等于「文件坏了」也放行 —— 后者放行的表现是"
                        + "一台配好了的机器安安静静地一条推送都不发");
    }
}
