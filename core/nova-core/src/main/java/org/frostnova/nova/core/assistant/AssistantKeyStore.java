package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.HttpHeaders;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** AES-256-GCM 密钥存储；解密入口只负责填写请求头。 */
public final class AssistantKeyStore {
    private final Path encryptedPath;
    private final Path masterPath;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AssistantKeyStore(Path directory) {
        encryptedPath = directory.resolve("assistant-key.enc");
        masterPath = directory.resolve("assistant-master.key");
    }

    public synchronized boolean isSet() { return Files.exists(encryptedPath) && Files.exists(masterPath); }

    public synchronized String masked() {
        if (!isSet()) return "";
        try {
            AssistantFiles.restrict(encryptedPath);
            return JSON.parseObject(Files.readString(encryptedPath)).getString("masked");
        } catch (Exception error) { throw storageError(); }
    }

    synchronized void save(String value) {
        validate(value);
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, master(true), new GCMParameterSpec(128, iv));
            AssistantFiles.write(encryptedPath, JSONObject.of("version", 1,
                    "iv", Base64.getEncoder().encodeToString(iv),
                    "payload", Base64.getEncoder().encodeToString(cipher.doFinal(plain)),
                    "masked", mask(value)).toJSONString());
        } catch (Exception error) { throw storageError(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    synchronized void authorize(HttpHeaders headers) {
        if (!isSet()) throw new AssistantException("尚未设置智能助手模型密钥");
        byte[] plain = null;
        try {
            AssistantFiles.restrict(encryptedPath);
            JSONObject json = JSON.parseObject(Files.readString(encryptedPath));
            if (json.getIntValue("version") != 1) throw storageError();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, master(false), new GCMParameterSpec(128, Base64.getDecoder().decode(json.getString("iv"))));
            plain = cipher.doFinal(Base64.getDecoder().decode(json.getString("payload")));
            headers.setBearerAuth(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception error) { throw storageError(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    public synchronized void clear() {
        try { Files.deleteIfExists(encryptedPath); }
        catch (Exception error) { throw storageError(); }
    }

    static void validate(String value) {
        if (value == null || value.isBlank() || value.length() > 8192
                || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new AssistantException("模型密钥不能为空，也不能包含空白或控制字符");
        }
    }

    private static String mask(String value) {
        return value.length() > 8 ? value.substring(0, 6) + "…" + value.substring(value.length() - 2) : "…";
    }

    private SecretKey master(boolean create) throws Exception {
        if (Files.exists(masterPath)) {
            AssistantFiles.restrict(masterPath);
            byte[] bytes = Base64.getDecoder().decode(Files.readString(masterPath).strip());
            try { return new SecretKeySpec(bytes, "AES"); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }
        if (!create || Files.exists(encryptedPath)) throw storageError();
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256, RANDOM);
        SecretKey key = generator.generateKey();
        AssistantFiles.write(masterPath, Base64.getEncoder().encodeToString(key.getEncoded()));
        return key;
    }

    private static AssistantException storageError() { return new AssistantException("智能助手密钥文件无法读写或解密，请重新设置密钥"); }
}
