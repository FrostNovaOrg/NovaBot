package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Cookies;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * 哔哩哔哩登录凭据存储
 * <p>
 * 默认以 AES-256-GCM 加密后落盘，密钥保存在独立的密钥文件中，两者均以仅属主可读写（0600）的权限创建。
 * 若检测到既有的明文凭据文件，会自动迁移为加密存储并将原文件改名备份，避免升级后凭据仍以明文留在磁盘上。
 */
@Slf4j
public class BilibiliCredentialStore {
    /**
     * 加密文件的格式版本，便于后续更换算法时平滑升级
     */
    private static final int FORMAT_VERSION = 1;

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    private static final int KEY_BITS = 256;

    private static final int GCM_IV_BYTES = 12;

    private static final int GCM_TAG_BITS = 128;

    /**
     * 仅属主可读写
     */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StarBotBilibiliProperties.Account properties;

    private final Path cookiePath;

    private final Path keyPath;

    public BilibiliCredentialStore(@NonNull StarBotBilibiliProperties.Account properties) {
        this.properties = properties;
        this.cookiePath = Path.of(properties.getCookiePath());
        this.keyPath = Path.of(properties.getKeyPath());
    }

    /**
     * 读取已保存的登录凭据
     * @return 登录凭据，不存在或无法解密时返回空
     */
    public Optional<Cookies> load() {
        if (!Files.exists(cookiePath)) {
            return Optional.empty();
        }

        String content;
        try {
            content = Files.readString(cookiePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取登录凭据文件 {} 失败", cookiePath, e);
            return Optional.empty();
        }

        if (content.isBlank()) {
            return Optional.empty();
        }

        JSONObject json;
        try {
            json = JSON.parseObject(content);
        } catch (Exception e) {
            log.error("登录凭据文件 {} 格式异常, 请删除后重新扫码登录", cookiePath);
            return Optional.empty();
        }

        // 明文格式：直接是凭据字段本身；加密格式：带有 version 与 payload 字段
        if (!json.containsKey("payload")) {
            Cookies cookies = toCookies(json);
            log.warn("检测到明文存储的登录凭据文件 {}", cookiePath);
            if (properties.isEncrypt()) {
                migrateToEncrypted(cookies);
            }
            return Optional.of(cookies);
        }

        return decrypt(json);
    }

    /**
     * 保存登录凭据
     * @param cookies 登录凭据
     */
    public void save(@NonNull Cookies cookies) {
        try {
            if (properties.isEncrypt()) {
                writeSecurely(cookiePath, encrypt(cookies));
            } else {
                log.warn("登录凭据加密存储已关闭, {} 将以明文保存; 该文件等同于账号密码, 请自行确保其访问权限", cookiePath);
                writeSecurely(cookiePath, toJson(cookies).toJSONString());
            }
        } catch (Exception e) {
            log.error("保存登录凭据至 {} 失败", cookiePath, e);
        }
    }

    /**
     * 删除已保存的登录凭据
     */
    public void clear() {
        try {
            Files.deleteIfExists(cookiePath);
        } catch (IOException e) {
            log.error("删除登录凭据文件 {} 失败", cookiePath, e);
        }
    }

    /**
     * 将明文凭据迁移为加密存储，并把原文件改名备份
     * @param cookies 凭据
     */
    private void migrateToEncrypted(Cookies cookies) {
        try {
            Path backup = cookiePath.resolveSibling(cookiePath.getFileName() + ".plain.bak");
            Files.move(cookiePath, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            restrictPermissions(backup);

            writeSecurely(cookiePath, encrypt(cookies));
            log.info("已将登录凭据迁移为加密存储, 原明文文件备份为 {}; 确认运行正常后请手动删除该备份", backup);
        } catch (Exception e) {
            log.error("迁移登录凭据为加密存储失败, 凭据仍以明文保存", e);
        }
    }

    /**
     * 加密凭据
     * @param cookies 凭据
     * @return 加密后的 JSON 文本
     * @throws Exception 加密失败时抛出
     */
    private String encrypt(Cookies cookies) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(toJson(cookies).toJSONString().getBytes(StandardCharsets.UTF_8));

        JSONObject json = new JSONObject();
        json.put("version", FORMAT_VERSION);
        json.put("iv", Base64.getEncoder().encodeToString(iv));
        json.put("payload", Base64.getEncoder().encodeToString(encrypted));
        return json.toJSONString();
    }

    /**
     * 解密凭据
     * @param json 加密后的 JSON
     * @return 凭据，解密失败时返回空
     */
    private Optional<Cookies> decrypt(JSONObject json) {
        int version = json.getIntValue("version", FORMAT_VERSION);
        if (version != FORMAT_VERSION) {
            log.error("登录凭据文件 {} 的格式版本 {} 不受支持, 请删除后重新扫码登录", cookiePath, version);
            return Optional.empty();
        }

        if (!Files.exists(keyPath)) {
            log.error("登录凭据已加密, 但密钥文件 {} 不存在, 无法解密, 请删除 {} 后重新扫码登录", keyPath, cookiePath);
            return Optional.empty();
        }

        try {
            byte[] iv = Base64.getDecoder().decode(json.getString("iv"));
            byte[] payload = Base64.getDecoder().decode(json.getString("payload"));

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(payload);

            try {
                return Optional.of(toCookies(JSON.parseObject(new String(decrypted, StandardCharsets.UTF_8))));
            } finally {
                // 及时抹除堆上的明文副本
                Arrays.fill(decrypted, (byte) 0);
            }
        } catch (Exception e) {
            log.error("解密登录凭据失败, 密钥文件 {} 可能已被替换或凭据文件已损坏, 请删除后重新扫码登录", keyPath);
            return Optional.empty();
        }
    }

    /**
     * 读取密钥，不存在时生成一个新密钥并落盘
     * @return 密钥
     * @throws Exception 读取或生成失败时抛出
     */
    private SecretKey loadOrCreateKey() throws Exception {
        if (Files.exists(keyPath)) {
            byte[] key = Base64.getDecoder().decode(Files.readString(keyPath, StandardCharsets.UTF_8).strip());
            return new SecretKeySpec(key, "AES");
        }

        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(KEY_BITS, RANDOM);
        SecretKey key = generator.generateKey();

        writeSecurely(keyPath, Base64.getEncoder().encodeToString(key.getEncoded()));
        log.info("已生成登录凭据加密密钥 {}, 请连同凭据文件一并妥善备份, 丢失后需重新扫码登录", keyPath);

        return key;
    }

    /**
     * 以仅属主可读写的权限写入文件
     * <p>
     * 先按目标权限创建文件再写入，避免文件在创建与改权限之间存在一个可被其他用户读取的时间窗口。
     * @param path 文件路径
     * @param content 内容
     * @throws IOException 写入失败时抛出
     */
    private void writeSecurely(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (UnsupportedOperationException e) {
                // 非 POSIX 文件系统（如 Windows）不支持此属性，退化为普通创建
                Files.createFile(path);
            }
        }

        Files.writeString(path, content, StandardCharsets.UTF_8);
        restrictPermissions(path);
    }

    /**
     * 将文件权限收紧为仅属主可读写
     * @param path 文件路径
     */
    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("当前文件系统不支持设置 POSIX 权限, 已跳过对 {} 的权限收紧", path);
        }
    }

    /**
     * 凭据转 JSON
     * @param cookies 凭据
     * @return JSON
     */
    private JSONObject toJson(Cookies cookies) {
        JSONObject json = new JSONObject();
        json.put("sessData", cookies.getSessData());
        json.put("biliJct", cookies.getBiliJct());
        json.put("buvid3", cookies.getBuvid3());
        json.put("refreshToken", cookies.getRefreshToken());
        // TV 端登录取得的续期令牌。本方法是逐字段显式映射，Cookies 新增字段时必须同步补在这里，
        // 否则字段会在落盘时被静默丢弃——表现为重启后自动续期能力莫名消失
        json.put("accessToken", cookies.getAccessToken());
        json.put("accessTokenExpiresAt", cookies.getAccessTokenExpiresAt());
        return json;
    }

    /**
     * JSON 转凭据，兼容驼峰与下划线两种字段命名
     * <p>
     * refreshToken 还额外兼容 ac_time_value：那是官方 Web 端在 localStorage 里用的键名，
     * 从浏览器手工导出凭据时照抄下来的就是这个名字。
     * @param json JSON
     * @return 凭据
     */
    private Cookies toCookies(JSONObject json) {
        Cookies cookies = new Cookies(
                firstNonNull(json, "sessData", "SESSDATA"),
                firstNonNull(json, "biliJct", "bili_jct"),
                firstNonNull(json, "buvid3", "BUVID3"),
                firstNonNull(json, "refreshToken", "refresh_token", "ac_time_value")
        );

        cookies.setAccessToken(firstNonNull(json, "accessToken", "access_token", "access_key"));
        cookies.setAccessTokenExpiresAt(json.getLong("accessTokenExpiresAt"));
        return cookies;
    }

    private String firstNonNull(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
