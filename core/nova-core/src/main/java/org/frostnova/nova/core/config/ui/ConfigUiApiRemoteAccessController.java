package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.api.NovaApiFilter;
import org.frostnova.nova.core.api.NovaApiOpenGroups;
import org.frostnova.nova.core.api.NovaApiRegistry;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.IpMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 「访问令牌」页上「从别的机器访问」那一块：哪几组程序接口对任何来源地址开放，以及从本机、内网、公网各该怎么连
 * <p>
 * 两条都只给管理员（{@link ConfigUiPermissions}）。成员在这一页照旧只看得到、签得了自己的令牌。
 * <p>
 * 开关存的是设置项 {@value RuntimeConfigurationApplier#API_ANY_SOURCE_GROUPS}：写进配置文件，
 * 再经设置页保存同一条路落到运行中的程序上，保存即生效，时间线与「待重启」那本账也照常记。
 * <p>
 * 回包里没有令牌、凭据，也没有名单的原文；连法里用到的只有端口、监听地址与这台机器的内网地址。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/api-tokens/remote-access")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiApiRemoteAccessController {
    /** 组名的写法，与插件登记段名的规矩同一条 */
    private static final Pattern GROUP = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /** 内网地址最多列几个：一台机器挂着一串虚拟网卡时，页面不该被它们撑满 */
    private static final int MAX_LAN = 4;

    /** 读改写这一份清单时持的锁：两位管理员同时点两个开关，后写的不该把先写的冲掉 */
    private static final Object LOCK = new Object();

    private final NovaApiRegistry registry;

    private final NovaCoreProperties.ConfigUi configUi;

    private final ConfigurationFileService fileService;

    private final RuntimeConfigurationApplier applier;

    private final Environment environment;

    private final Supplier<List<String>> lanAddresses;

    @org.springframework.beans.factory.annotation.Autowired
    public ConfigUiApiRemoteAccessController(NovaApiRegistry registry, NovaCoreProperties properties,
                                             ConfigurationFileService fileService, RuntimeConfigurationApplier applier,
                                             Environment environment) {
        this(registry, properties, fileService, applier, environment, ConfigUiApiRemoteAccessController::siteLocal);
    }

    ConfigUiApiRemoteAccessController(NovaApiRegistry registry, NovaCoreProperties properties,
                                      ConfigurationFileService fileService, RuntimeConfigurationApplier applier,
                                      Environment environment, Supplier<List<String>> lanAddresses) {
        this.registry = registry;
        this.configUi = properties.getConfigUi();
        this.fileService = fileService;
        this.applier = applier;
        this.environment = environment;
        this.lanAddresses = lanAddresses;
    }

    @GetMapping
    public ResponseEntity<String> state() {
        JSONObject result = view();
        result.put("success", true);
        return json(HttpStatus.OK, result);
    }

    /**
     * 打开或关上一组
     * <p>
     * 请求体 {@code {"segment":"outbox","open":true}}。打开只收此刻登记了的组；关上什么名字都收，
     * 好让插件卸掉之后留在清单里的旧名字也去得掉。
     */
    @PostMapping
    public ResponseEntity<String> toggle(@RequestBody(required = false) JSONObject body) {
        JSONObject payload = body == null ? new JSONObject() : body;
        if (!(payload.get("segment") instanceof String segment) || !GROUP.matcher(segment).matches()) {
            return refuse(HttpStatus.BAD_REQUEST, "没有这组接口，请刷新页面后重试");
        }
        if (!(payload.get("open") instanceof Boolean open)) {
            return refuse(HttpStatus.BAD_REQUEST, "请说明是打开还是关上");
        }
        if (open && !registry.segments().contains(segment)) {
            return refuse(HttpStatus.BAD_REQUEST, "没有这组接口（插件可能已卸载），请刷新页面后重试");
        }

        String message;
        synchronized (LOCK) {
            List<String> current = configUi.getApiAnySourceGroups();
            List<String> next = new ArrayList<>(current == null ? List.of() : current);
            boolean changed = open ? !next.contains(segment) && next.add(segment) : next.removeIf(segment::equals);
            if (!changed) {
                message = open ? "这组接口本来就对任何地址开放" : "这组接口本来就没有对名单以外的地址开放";
            } else {
                Map<String, String> change = new HashMap<>();
                change.put(RuntimeConfigurationApplier.API_ANY_SOURCE_GROUPS, next.isEmpty() ? null : String.join("\n", next));
                try {
                    fileService.write(change);
                } catch (IOException e) {
                    log.error("保存「对任何地址开放的接口组」失败", e);
                    return refuse(HttpStatus.SERVICE_UNAVAILABLE, "保存失败: " + fileService.describeSaveFailure(e));
                }
                // 文件里本来就是这个值（有人手改过、还没重启）时写口不算改动，内存照样要换成它，开关才真的生效
                List<String> restart = applier.applyAndTrack(change);
                if (!restart.isEmpty()) {
                    return refuse(HttpStatus.INTERNAL_SERVER_ERROR, "已写进配置文件，但没能当场生效，重启后生效");
                }
                if (open) {
                    log.warn("{} 把程序接口组 {} 设为对任何来源地址开放（仍须有效的访问令牌）", ConfigUiAccess.actor(), segment);
                } else {
                    log.info("{} 关上了程序接口组 {} 对名单以外地址的开放", ConfigUiAccess.actor(), segment);
                }
                message = open ? "已打开：名单以外的地址从下一个请求起可以凭有效令牌调这组接口"
                        : "已关上：名单以外的地址从下一个请求起调这组接口会被拒绝（403）";
            }
        }

        JSONObject result = view();
        result.put("success", true);
        result.put("message", message);
        return json(HttpStatus.OK, result);
    }

    /**
     * 页面那一块要的全部值，都是此刻实际在用的
     */
    private JSONObject view() {
        List<String> configured = configUi.getApiAnySourceGroups() == null ? List.of() : configUi.getApiAnySourceGroups();
        Set<String> open = new NovaApiOpenGroups(() -> configured, registry).effective();

        JSONArray groups = new JSONArray();
        for (NovaApiRegistry.Group group : registry.groups()) {
            JSONObject item = new JSONObject();
            item.put("segment", group.segment());
            item.put("name", group.name());
            item.put("description", group.description());
            item.put("open", open.contains(group.segment()));
            groups.add(item);
        }
        JSONArray stale = new JSONArray();
        configured.stream().filter(name -> !registry.segments().contains(name)).distinct().forEach(stale::add);

        String address = environment.getProperty("server.address", "");
        String scope = listenScope(address);
        JSONObject listen = new JSONObject();
        listen.put("address", address);
        listen.put("scope", scope);
        listen.put("restartPending", applier.getPendingRestart().contains("server.address"));

        JSONObject result = new JSONObject();
        result.put("groups", groups);
        result.put("stale", stale);
        result.put("port", port());
        result.put("apiPath", NovaApiFilter.V1 + "/");
        result.put("listen", listen);
        result.put("lan", new JSONArray(new ArrayList<>("specific".equals(scope) ? List.of(address) : lanAddresses.get())));
        result.put("allowListBeyondLocal", beyondLoopback(configUi.getAllowIps()));
        result.put("consequence", RuntimeConfigurationApplier.OPENING_CONSEQUENCE);
        result.put("setting", RuntimeConfigurationApplier.API_ANY_SOURCE_GROUPS);
        return result;
    }

    /** 实际在听的端口：启动后框架写下的那个，取不到时按配置 */
    private int port() {
        Integer actual = environment.getProperty("local.server.port", Integer.class);
        if (actual != null && actual > 0) {
            return actual;
        }
        Integer configured = environment.getProperty("server.port", Integer.class);
        return configured == null ? 7827 : configured;
    }

    /**
     * 监听地址管到哪里
     * @param address {@code server.address} 此刻的值
     * @return {@code loopback} 只有本机连得上；{@code all} 所有网卡；{@code specific} 只听某一个地址
     */
    static String listenScope(String address) {
        String value = address == null ? "" : address.strip();
        if (value.isEmpty()) {
            return "all";
        }
        if (value.equalsIgnoreCase("localhost")) {
            return "loopback";
        }
        InetAddress parsed = literal(value);
        if (parsed == null) {
            return "specific";
        }
        if (parsed.isAnyLocalAddress()) {
            return "all";
        }
        return parsed.isLoopbackAddress() ? "loopback" : "specific";
    }

    /**
     * 来源名单里有没有本机以外的地址
     * <p>
     * 只为页面上「内网那一条现在通不通」那句话：名单里只有本机两条时，来访的机器只能靠开关进来。
     * @param entries 名单
     * @return 有一条管得到本机以外的地址时为 true
     */
    static boolean beyondLoopback(List<String> entries) {
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank() || IpMatcher.problemOf(entry) != null) {
                continue;
            }
            String rule = entry.strip();
            int slash = rule.indexOf('/');
            InetAddress address = literal(slash < 0 ? rule : rule.substring(0, slash).strip());
            if (address == null) {
                continue;
            }
            int full = address.getAddress().length * 8;
            int prefix = slash < 0 ? full : Integer.parseInt(rule.substring(slash + 1).strip());
            boolean onlyLoopback = address.isLoopbackAddress() && (address instanceof Inet4Address ? prefix >= 8 : prefix == full);
            if (!onlyLoopback) {
                return true;
            }
        }
        return false;
    }

    /** 严格的点分十进制 IPv4，每段 0-255：写成别的样子的串交给 getByName 会去查 DNS */
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}");

    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    /**
     * 只读 IP 字面量，不查 DNS
     */
    private static InetAddress literal(String text) {
        String value = text;
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.length() > 1 && value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!IPV4.matcher(value).matches() && !IPV6.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException malformed) {
            return null;
        }
    }

    /**
     * 这台机器的内网 IPv4 地址：开着的、不是回环的网卡上的私有网段地址
     */
    static List<String> siteLocal() {
        Set<String> found = new TreeSet<>();
        try {
            for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!face.isUp() || face.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(face.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        found.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException | RuntimeException unavailable) {
            return List.of();
        }
        return found.stream().limit(MAX_LAN).toList();
    }

    private static ResponseEntity<String> refuse(HttpStatus status, String message) {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("message", message);
        jakarta.servlet.http.HttpServletRequest request = ConfigUiAccess.request();
        if (request != null) {
            request.setAttribute(ConfigUiOperationFilter.REASON, message);
        }
        return json(status, result);
    }

    private static ResponseEntity<String> json(HttpStatus status, JSONObject body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(body.toJSONString(JSONWriter.Feature.WriteNulls));
    }
}
