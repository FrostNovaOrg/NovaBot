package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自带手册的只读出口
 * <p>
 * 抓的用户故障有两类。其一：「使用说明」打开了手册以外的件——这个出口把请求路径直接
 * 映到类路径资源，件名不设白名单的话，{@code ../} 一类能把整个 jar 里的东西读出去，
 * 而表现只是「多开出来一页」。其二：只跑 mvn 的开发构建不带手册产物，点「使用说明」
 * 得到一个 404——使用者分不清「这版没带手册」与「手册坏了」，所以查不到时要退到
 * 站上那份手册的同一章。
 * <p>
 * 手册产物从注入的类加载器取：本机恰好跑过 {@code manual/build.py} 时主类路径上会躺着
 * 真手册（package 形态把 manual/dist 也拷进来），直接用默认加载器的话，
 * 「包里没带手册」那一格的红绿就跟着环境走。
 */
@DisplayName("自带手册的只读出口")
class ConfigUiManualControllerTest {
    /**
     * 只认测试资源里那两件假手册的加载器
     */
    private static final ClassLoader FIXTURE = fixtureLoader();

    /**
     * 什么都取不到的加载器：扮「包里没带手册」的那一形态
     */
    private static final ClassLoader EMPTY = new ClassLoader(null) {
    };

    private static ClassLoader fixtureLoader() {
        URL marker = ConfigUiManualControllerTest.class.getClassLoader()
                .getResource(ConfigUiManualController.RESOURCE_ROOT + "index.html");
        assertNotNull(marker, "前提：测试资源里有假手册那两件（config-ui-manual/）");
        try {
            URI base = marker.toURI().resolve("..");
            return new URLClassLoader(new URL[]{base.toURL()}, null);
        } catch (Exception e) {
            throw new IllegalStateException("够不到假手册所在的目录", e);
        }
    }

    private static MockMvc manualOver(ClassLoader loader) {
        return MockMvcBuilders.standaloneSetup(new ConfigUiManualController(loader)).build();
    }

    @Test
    @DisplayName("带了手册时，发的是包里那一件，类型也对")
    void servesThePackagedManual() throws Exception {
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("测试用的假手册目录页")))
                .andExpect(header().string("Cache-Control", "no-cache"));

        byte[] expected = Files.readAllBytes(fixtureFile("images/demo-screen.png"));
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/images/demo-screen.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(result -> assertArrayEquals(expected, result.getResponse().getContentAsByteArray(),
                        "图片要逐字节是包里那一件"));
    }

    @Test
    @DisplayName("「从手册出口读到了手册以外的件」：../、编码过的 ../、反斜杠、别的子目录、白名单外的类型，一律 404 且不吐内容")
    void refusesAnythingOutsideTheManual() throws Exception {
        String[] rejected = {
                "../index.html",
                "%2E%2E%2Findex.html",
                "..%5Cindex.html",
                "assets/manual.css",
                "application.yml",
                "images/../index.html",
        };
        for (String name : rejected) {
            manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/" + name))
                    .andExpect(status().isNotFound())
                    .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length == 0,
                            "404 那一趟不许带出任何件的内容: " + name));
        }

        // images/ 下只认图：把页当图要、把图外的类型要，都不给
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/images/shot.html"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/images/..%2Findex.html"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));

        // 不列目录：手册根与 images/ 本身都不是一页
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/"))
                .andExpect(status().isNotFound());
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/images/"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("「包里没带手册时，点使用说明是 404」：查不到手册产物时 302 到站上那份的同一件")
    void fallsBackToThePublishedSiteWhenManualAbsent() throws Exception {
        manualOver(EMPTY).perform(get(ConfigUiManualController.PATH + "/05-console-tour.html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        ConfigUiManualController.PAGES_SITE + "05-console-tour.html"))
                .andExpect(header().string("Cache-Control", "no-cache"));

        // 退路只到固定的站址加白名单件名：图片那一件退到站上同一个子目录
        manualOver(EMPTY).perform(get(ConfigUiManualController.PATH + "/images/01-home.png"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        ConfigUiManualController.PAGES_SITE + "images/01-home.png"))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length == 0,
                        "重定向那一趟不带内容"));

        // 夹具那份里没有的章同样退：出口只认类路径上实有件，不猜「大概有」
        manualOver(FIXTURE).perform(get(ConfigUiManualController.PATH + "/14-troubleshooting.html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        ConfigUiManualController.PAGES_SITE + "14-troubleshooting.html"));
    }

    private static Path fixtureFile(String name) throws IOException {
        URL url = ConfigUiManualControllerTest.class.getClassLoader()
                .getResource(ConfigUiManualController.RESOURCE_ROOT + name);
        assertNotNull(url, "前提：假手册里要有 " + name);
        try {
            return Path.of(url.toURI());
        } catch (Exception e) {
            throw new IOException("够不到假手册件 " + name, e);
        }
    }
}
