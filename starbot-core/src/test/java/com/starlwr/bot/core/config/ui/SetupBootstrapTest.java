package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.datasource.JsonDataSource;
import com.starlwr.bot.core.util.IpMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 免配置起步：没有 application.yml 的那台机器
 * <p>
 * 发行包不再带配置文件，于是「刚装好、什么都没配」成了每台实例都要经过的一档。
 * 这几格量的是那一档里三件事各自成不成立。
 *
 * <h2>先红读数（2026-09-04，改之前）</h2>
 * <ul>
 *   <li>① {@code /api/auth/state} <b>根本没有 setupDone 这一栏</b>——界面无从判断这台机器
 *       配过没有，只能摆一个「0 个主播、0 条推送」的空首页。</li>
 *   <li>③ {@code ConfigUiAuthService.enabled} 在构造时就定死了：设下第一把口令之后
 *       {@code isEnabled()} 仍为 false，<b>安全过滤器照旧走令牌形态、地址栏令牌照旧进得来</b>——
 *       🔴 界面说「已上锁」而门开着，而这件事从界面上看不出任何异常。</li>
 *   <li>另：那台机器上根本<b>没有</b>设第一把口令的入口——改口令要旧口令，重设要令牌会话，
 *       刚装好的实例两样都没有。</li>
 * </ul>
 */
@DisplayName("免配置起步")
class SetupBootstrapTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private static final String FIRST_PASSWORD = "correct horse battery staple";

    @TempDir
    Path dir;

    private Path config;
    private StarBotCoreProperties properties;
    private ConfigurationFileService fileService;
    private ConfigUiAuthService authService;
    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() {
        config = dir.resolve("application.yml");
        fileService = new ConfigurationFileService(config);

        properties = new StarBotCoreProperties();
        // 使用协议置为已同意：那道闸排在身份校验之后，不放行的话下面几趟量到的是协议闸，不是门
        properties.getConfigUi().getAgreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        properties.getConfigUi().getAgreement().setAcceptedBy("operator-token");

        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setTotp(false);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private ConfigUiSecurityFilter filter() {
        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement());
    }

    /**
     * 拿着地址栏令牌访问一个受保护的接口
     * @return 响应状态码
     */
    private int visitWithToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                ConfigUiController.BASE_PATH + "/api/status");
        request.setRemoteAddr("127.0.0.1");
        request.setParameter("token", TOKEN);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(request, response, chain);

        return response.getStatus();
    }

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                ConfigUiController.BASE_PATH + path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private JSONObject next(String password) {
        JSONObject body = new JSONObject();
        body.put("next", password);
        return body;
    }

    @Test
    @DisplayName("① 没有配置文件时，/auth/state 说这台机器还没配过")
    void stateReportsSetupNotDone() {
        assertFalse(Files.exists(config), "夹具起点：配置文件不存在");

        JSONObject state = controller.state(new MockHttpServletRequest());
        assertEquals(Boolean.FALSE, state.getBoolean("setupDone"),
                "界面靠这一栏决定把人领到初始设置页还是摆首页。"
                        + "缺了它，刚装好的机器上摆出来的是一个 0 主播 0 推送的空首页");
    }

    @Test
    @DisplayName("① 阴性 —— 文件一旦写出来，就不再说「还没配过」")
    void stateFlipsOnceTheFileExists() throws IOException {
        assertEquals(Boolean.FALSE, controller.state(new MockHttpServletRequest()).getBoolean("setupDone"));

        fileService.createIfAbsent();

        assertEquals(Boolean.TRUE, controller.state(new MockHttpServletRequest()).getBoolean("setupDone"),
                "判据是「配置文件在不在」。恒 false 的话，配好了的机器每次打开都被送回初始设置页");
    }

    @Test
    @DisplayName("① 配置文件那一侧拿不到时不作答，而不是随口答一个")
    void stateSaysNothingWhenTheFileIsUnreachable() {
        ConfigUiAuthController offline = new ConfigUiAuthController(authService, null, properties);

        JSONObject state = offline.state(new MockHttpServletRequest());

        assertTrue(state.getBooleanValue("success"), "这一问本身照常回答，只是少这一栏");
        assertFalse(state.containsKey("setupDone"),
                "「答不上来」不是这一位的取值之一。填 false 会把人钉死在初始设置页上"
                        + "——那页要做的事正是写配置文件；填 true 是拿没根据的值答一个本来查得到的问题。"
                        + "界面认得「没有这一栏」，那一档不跳转");
    }

    @Test
    @DisplayName("③ 阳性对照 —— 上锁之前，地址栏令牌进得来")
    void tokenWorksBeforeTheFirstLock() throws Exception {
        assertFalse(authService.isEnabled(), "夹具起点：这台机器还没上锁");
        assertEquals(HttpStatus.OK.value(), visitWithToken(),
                "阳性对照：这一条不成立，下面那条「上锁之后令牌不好使」说明不了任何事");
    }

    @Test
    @DisplayName("③ 上第一把锁当场生效：令牌进不来了，口令进得来")
    void firstLockTakesEffectAtOnce() throws Exception {
        assertEquals(HttpStatus.OK.value(), visitWithToken(), "上锁之前令牌是好使的");

        JSONObject result = controller.setPassword(next(FIRST_PASSWORD), post("/api/auth/password/set")).getBody();
        assertNotNull(result);
        assertTrue(result.getBooleanValue("success"), "上第一把锁失败了: " + result.getString("message"));

        assertTrue(authService.isEnabled(), "设了口令，门却还认为自己没上锁");
        assertEquals(HttpStatus.UNAUTHORIZED.value(), visitWithToken(),
                "🔴 上了锁，地址栏令牌却照旧进得来 —— 界面说已上锁而门开着，"
                        + "而这件事从界面上看不出任何异常");
        assertTrue(authService.login(FIRST_PASSWORD.toCharArray(), null, "127.0.0.1").success(),
                "新设的口令登不上，那这把锁把主人也锁在了门外");
    }

    @Test
    @DisplayName("③ 上锁这一趟把配置文件写了出来，口令以哈希落盘")
    void firstLockWritesTheConfigFile() throws Exception {
        assertFalse(Files.exists(config), "夹具起点：配置文件不存在");

        controller.setPassword(next(FIRST_PASSWORD), post("/api/auth/password/set"));

        assertTrue(Files.exists(config), "第一把口令没地方落 —— 重启之后这台机器又是没上锁的");
        String saved = fileService.read().get(ConfigUiAuthService.PASSWORD_PROPERTY);
        assertNotNull(saved, "配置文件里找不到口令那一项");
        assertFalse(saved.contains(FIRST_PASSWORD), "明文口令进了配置文件");
    }

    @Test
    @DisplayName("③ 上锁之后，「忘记口令」的启动令牌通道自动关掉，并写回配置文件")
    void firstLockClosesTheOperatorTokenChannel() throws Exception {
        properties.getConfigUi().getAuth().setOperatorToken(true);
        assertTrue(properties.getConfigUi().getAuth().isOperatorToken(), "夹具起点：这条通道是开着的");

        controller.setPassword(next(FIRST_PASSWORD), post("/api/auth/password/set"));

        assertFalse(properties.getConfigUi().getAuth().isOperatorToken(),
                "上了锁却还留着一扇不问口令的门，而这件事没有任何现象");
        assertEquals("false", fileService.read().get("starbot.core.config-ui.auth.operator-token"),
                "只关内存那一位的话，重启之后这扇门自己回来了");
    }

    @Test
    @DisplayName("③ 上锁那条路只走一次：已经上过锁之后它整个关掉")
    void firstLockIsNotASecondDoor() {
        controller.setPassword(next(FIRST_PASSWORD), post("/api/auth/password/set"));
        assertTrue(authService.isEnabled());

        JSONObject again = controller.setPassword(next("yet another passphrase"), post("/api/auth/password/set")).getBody();
        assertNotNull(again);
        assertFalse(again.getBooleanValue("success"),
                "🔴 上过锁之后这条路还开着，就等于给面板留了第二扇门，而那扇门不要旧口令");
        assertTrue(authService.login(FIRST_PASSWORD.toCharArray(), null, "127.0.0.2").success(),
                "被拒的那一趟不该动到口令");
    }

    @Test
    @DisplayName("从设置页保存口令与二次验证开关，当场生效")
    void savingAuthKeysFromSettingsTakesEffectAtOnce() {
        RuntimeConfigurationApplier applier = new RuntimeConfigurationApplier(properties, authService);

        assertFalse(authService.isEnabled(), "夹具起点：还没上锁");

        List<String> restart = applier.applyAndTrack(new java.util.LinkedHashMap<>(java.util.Map.of(
                ConfigUiAuthService.PASSWORD_PROPERTY, FIRST_PASSWORD)));

        assertTrue(restart.isEmpty(), "这一项标着即时生效，却被算进了「等重启」: " + restart);
        assertTrue(authService.isEnabled(), "从设置页保存的口令没有当场落到门上");
        assertTrue(authService.login(FIRST_PASSWORD.toCharArray(), null, "127.0.0.3").success());

        applier.applyAndTrack(new java.util.LinkedHashMap<>(java.util.Map.of("starbot.core.config-ui.auth.totp", "false")));
        assertFalse(authService.totpEnabled(), "二次验证开关没有当场落下");
    }

    @Test
    @DisplayName("配置界面被整个关掉时，那两项算「等重启」而不是静静跳过")
    void authKeysFallBackToRestartWhenTheConsoleIsOff() {
        RuntimeConfigurationApplier applier = new RuntimeConfigurationApplier(properties);

        List<String> restart = applier.applyAndTrack(new java.util.LinkedHashMap<>(java.util.Map.of(
                ConfigUiAuthService.PASSWORD_PROPERTY, FIRST_PASSWORD)));

        assertEquals(List.of(ConfigUiAuthService.PASSWORD_PROPERTY), restart,
                "落不下去却报「已生效」，是对着一个没发生的改动说它发生了");
    }

    @Test
    @DisplayName("上锁之前 /auth/state 说没上锁，上锁之后说上了锁")
    void stateFollowsTheLock() {
        assertEquals(Boolean.FALSE, controller.state(new MockHttpServletRequest()).getBoolean("enabled"));

        controller.setPassword(next(FIRST_PASSWORD), post("/api/auth/password/set"));

        assertEquals(Boolean.TRUE, controller.state(new MockHttpServletRequest()).getBoolean("enabled"),
                "界面照这一位决定摆不摆「退出登录」与改口令那一版，说错了就与门对不上");
    }

    @Test
    @DisplayName("口令太短时不上锁，也不把机器留在半上锁的状态")
    void aTooShortPasswordLocksNothing() throws IOException {
        JSONObject result = controller.setPassword(next("short"), post("/api/auth/password/set")).getBody();

        assertNotNull(result);
        assertFalse(result.getBooleanValue("success"));
        assertFalse(authService.isEnabled(), "被拒的那一趟不该动到门");
        assertNull(Files.exists(config) ? fileService.read().get(ConfigUiAuthService.PASSWORD_PROPERTY) : null,
                "被拒的那一趟不该往配置文件里写口令");
    }

    @Test
    @DisplayName("配置文件是 UTF-8 的：写出来的说明是中文，编码错了整份配置都读不回来")
    void generatedFileIsUtf8() throws IOException {
        fileService.createIfAbsent();

        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(text.contains("NovaBot 配置文件"), "写出来的件读不回中文说明");
    }

    /**
     * 打进 jar 里的那份默认配置得自己选定数据源实现
     * <p>
     * 免配置起步的实例第一次跑起来时，程序目录下那份 application.yml 还不存在，
     * 于是<b>选数据源这件事只剩 jar 里这一份说了算</b>。它不说的话选不到任何实现，
     * 🔴 <b>程序照常启动、控制台照常打开、主播也加得进去，只是一条推送都不会发</b>——
     * 而界面上看不出任何异常。
     * <p>
     * profile 名不写死在这一格里，两侧各取各的来路：一侧是打包的配置，
     * 一侧是数据源实现自己标的那个名。写死的话，改了实现上那个名而没改配置，这一格照旧绿。
     *
     * <h2>为什么读源码树而不读类路径</h2>
     * 🔴 判据自己跑的时候用的是 {@code install} 这一档，而这一档<b>刻意不把 application.yml
     * 复制进类路径</b>（那一档产出的是给插件模块依赖的库，不该夹带运行期配置）。
     * 于是从类路径上读到的要么没有，要么是上一次别的档次留下的<b>旧件</b>——
     * 而旧件读起来与刚出炉的一模一样。这一格因此读源码树里那一份，
     * 并另外钉住「发行那一档没有把它排除掉」，否则源码里写着、发行包里没有，两头都看着对。
     */
    @Test
    @DisplayName("① 打包的默认配置选定了数据源实现，否则免配置起步的实例一条推送都不发")
    void packagedDefaultsPickADataSource() throws IOException {
        Path source = repoRoot().resolve("starbot-core/src/main/resources/application.yml");
        assertTrue(Files.exists(source), "打进程序里的那份默认配置不见了: " + source);
        String packaged = Files.readString(source, StandardCharsets.UTF_8);

        Profile profile = JsonDataSource.class.getAnnotation(Profile.class);
        assertNotNull(profile, "数据源实现没标 profile —— 那么这一格下面比的是什么就说不清了");
        assertEquals(1, profile.value().length, "标了不止一个 profile，这一格的比法得跟着改");

        assertTrue(packaged.contains("active: " + profile.value()[0]),
                "打包的默认配置里没有 active: " + profile.value()[0]
                        + " —— 没有程序目录下那份 application.yml 的实例会回落到空数据源，"
                        + "此后加多少位主播都不会发出一条推送，而启动日志一切正常");

        // 发行那一档要真的把它装进去。这里只看那一段有没有把它排除掉：
        // install 档排除是有意的（库不该夹带运行期配置），package 档排除就是发行包里没有它
        String pom = Files.readString(repoRoot().resolve("starbot-core/pom.xml"), StandardCharsets.UTF_8);
        int packageProfile = pom.indexOf("<id>package</id>");
        int installProfile = pom.indexOf("<id>install</id>");
        assertTrue(packageProfile >= 0 && installProfile > packageProfile,
                "两档的位置认不出来了，下面那一句就量不到东西");
        assertFalse(pom.substring(packageProfile, installProfile).contains("<exclude>application.yml</exclude>"),
                "发行那一档把 application.yml 排除掉了 —— 源码里写着这一行，发行包里却没有，"
                        + "而两头单看都是对的");
    }

    /**
     * 仓库根目录
     */
    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalStateException("未能定位仓库根目录");
    }
}
