package com.starlwr.bot.core.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「忘记口令」的启动令牌通道默认关闭
 * <p>
 * 这条通道<b>绕过口令与二次验证</b>，令牌又走地址栏、会进反向代理的访问日志。
 * 默认开着意味着每一台设了口令的实例都自带一个等同于口令的后门，而开着这件事没有任何现象——
 * 使用者要先读到配置文件里那一行注释才知道它存在。默认关掉之后它仍然是一条留着的路，
 * 只是改成<b>需要时临时打开</b>：改一行配置重启即可。
 * <p>
 * 默认值写在三处：属性字段的初始值、发出去的模板 {@code application.yml}、
 * 以及界面「配置项」页读的元数据（构建期由字段初始值生成）。三处分家的表现各不相同——
 * 字段与模板分家时，<b>装了模板的实例与没装模板的实例行为相反</b>；
 * 元数据与字段分家时，界面上那个开关<b>显示的是一个没人用的值</b>。
 * 所以这一格量的是三个数相等，不是某一个数为假。
 */
@DisplayName("启动令牌默认关")
class OperatorTokenDefaultTest {
    private static final String KEY = "starbot.core.config-ui.auth.operator-token";

    private static final String METADATA = "META-INF/spring-configuration-metadata.json";

    /**
     * 定位仓库根目录：模板不在本模块里，而测试的工作目录随跑法而变
     */
    private Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    /**
     * 匹配 `        operator-token: 值` 这样的一行，捕获冒号后、注释前的部分
     */
    private static String valueOf(String yaml, String key) {
        Matcher m = Pattern.compile("^\\s+" + key + ":([^#\\n]*)", Pattern.MULTILINE).matcher(yaml);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * 从元数据里取这一项的默认值
     * <p>
     * 类路径上不止一份同名元数据（各依赖都带一份），因此不取「第一份」而是<b>全部扫一遍</b>，
     * 并要求恰好命中一条：取第一份的写法在依赖顺序变动时会安静地读到别人的表，
     * 而那时它给出的是 {@code null}——一个会被读成「没配这一项」的值。
     */
    private Object metadataDefault(String name) throws IOException {
        List<Object> found = new ArrayList<>();
        Enumeration<URL> resources = getClass().getClassLoader().getResources(METADATA);
        while (resources.hasMoreElements()) {
            try (InputStream in = resources.nextElement().openStream()) {
                JSONArray properties = JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getJSONArray("properties");
                if (properties == null) {
                    continue;
                }
                for (int i = 0; i < properties.size(); i++) {
                    JSONObject property = properties.getJSONObject(i);
                    if (name.equals(property.getString("name"))) {
                        found.add(property.get("defaultValue"));
                    }
                }
            }
        }

        // 一条也没找到不算「默认值不是 true」，那是管道断了：多半是没编译过，
        // 元数据由注解处理器在编译期生成，没跑过编译时它根本不在类路径上
        assertEquals(1, found.size(), "类路径上应恰好有一份元数据声明了 " + name
                + "，实际 " + found.size() + " 份——0 份说明本格根本没量到东西，不许读成绿");
        return found.get(0);
    }

    @Test
    @DisplayName("判据自己先能认出写着 true 的那一行")
    void theRulerRecognisesAnEnabledLine() {
        String enabled = "      auth:\n        operator-token: true        # 说明\n        session-hours: 168\n";
        assertEquals("true", valueOf(enabled, "operator-token"),
                "认不出写着 true 的那一行，下面那条判据就是恒真绿");

        String disabled = "      auth:\n        operator-token: false       # 说明\n";
        assertEquals("false", valueOf(disabled, "operator-token"));
    }

    @Test
    @DisplayName("属性默认值必须是关")
    void propertyDefaultIsOff() {
        assertFalse(new StarBotCoreProperties().getConfigUi().getAuth().isOperatorToken(),
                "启动令牌通道绕过口令与二次验证，默认开着等于每台设了口令的实例自带一个后门");
    }

    @Test
    @DisplayName("属性默认值、模板、界面元数据三处一致")
    void allThreePlacesAgree() throws IOException {
        boolean field = new StarBotCoreProperties().getConfigUi().getAuth().isOperatorToken();

        String yaml = Files.readString(root().resolve("dist/templates/application.yml"), StandardCharsets.UTF_8);
        String template = valueOf(yaml, "operator-token");
        assertNotNull(template, "模板里找不到 operator-token 那一行");

        Object metadata = metadataDefault(KEY);

        assertEquals(List.of(false, "false", Boolean.FALSE), List.of(field, template, metadata),
                "三处默认值必须一致且为关（字段／模板／元数据）");
    }

    @Test
    @DisplayName("模板里那段说明要写明「怎么临时打开」")
    void templateTellsHowToTurnItBackOn() throws IOException {
        String yaml = Files.readString(root().resolve("dist/templates/application.yml"), StandardCharsets.UTF_8);
        int at = yaml.indexOf("operator-token:");
        assertTrue(at >= 0, "模板里找不到 operator-token 那一行");

        // 只看这一项的注释块：从值那一行的行尾起，到下一个配置项为止。
        // 从「operator-token:」起算的话，值本身就是 true/false，这一格会变成恒真
        int from = yaml.indexOf('\n', at);
        int end = yaml.indexOf("session-hours:", at);
        String comment = yaml.substring(from < 0 ? at : from, end < 0 ? yaml.length() : end);
        assertTrue(comment.contains("true"),
                "默认关掉之后，模板得告诉忘记口令的人怎么把它临时打开——"
                        + "一个只说「已关闭」的默认值，把人锁在门外的方式与默认开着留后门一样糟: " + comment);
    }
}
