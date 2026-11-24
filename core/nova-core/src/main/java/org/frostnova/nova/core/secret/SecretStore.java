package org.frostnova.nova.core.secret;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.util.DurableFiles;

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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.function.Function;

/** 插件密钥的本地 AES-256-GCM 存储。 */
public final class SecretStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    // 同一进程里的不同实例也可能指向同一对文件。
    private static final Object FILE_LOCK = new Object();

    private final Path encryptedPath;
    private final Path masterPath;

    /** @param name 文件基础名，使用单个路径组件 */
    public SecretStore(Path directory, String name) {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("密钥存储名称须为单个文件名");
        }
        Path absolute = directory.toAbsolutePath();
        encryptedPath = absolute.resolve(name + ".enc");
        masterPath = absolute.resolve(name + "-master.key");
    }

    public boolean isSet() {
        synchronized (FILE_LOCK) {
            return Files.exists(encryptedPath) && Files.exists(masterPath);
        }
    }

    public String masked() {
        synchronized (FILE_LOCK) {
            try {
                if (!isSet()) {
                    return "";
                }
                return readEncrypted().getString("masked");
            } catch (Exception e) {
                throw storageError();
            }
        }
    }

    public void save(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192
                || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("密钥须为 1 至 8192 个可见 ASCII 字符");
        }
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        try {
            synchronized (FILE_LOCK) {
                createDirectory(encryptedPath.getParent());
                SecretKey master = masterForSave();
                byte[] iv = new byte[IV_BYTES];
                RANDOM.nextBytes(iv);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, iv));
                write(encryptedPath, JSONObject.of("version", VERSION,
                        "iv", Base64.getEncoder().encodeToString(iv),
                        "payload", Base64.getEncoder().encodeToString(cipher.doFinal(plain)),
                        "masked", mask(value)).toJSONString());
            }
        } catch (Exception e) {
            throw storageError();
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** 在需要使用密钥时解密。回调应就地使用明文，避免留存、返回或记录明文。 */
    public <T> T use(Function<String, T> action) {
        try {
            String value;
            synchronized (FILE_LOCK) {
                JSONObject json = readEncrypted();
                if (json.getIntValue("version") != VERSION) {
                    throw storageError();
                }
                byte[] iv = Base64.getDecoder().decode(json.getString("iv"));
                if (iv.length != IV_BYTES) {
                    throw storageError();
                }
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, readMaster(), new GCMParameterSpec(TAG_BITS, iv));
                byte[] plain = cipher.doFinal(Base64.getDecoder().decode(json.getString("payload")));
                try {
                    value = new String(plain, StandardCharsets.UTF_8);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                }
            }
            return action.apply(value);
        } catch (Exception e) {
            throw storageError();
        }
    }

    /** 清除已存的密文，主密钥可用于下次保存。 */
    public void clear() {
        synchronized (FILE_LOCK) {
            try {
                Files.deleteIfExists(encryptedPath);
            } catch (Exception e) {
                throw storageError();
            }
        }
    }

    private JSONObject readEncrypted() throws IOException {
        restrict(encryptedPath, DurableFiles.OWNER_ONLY);
        return JSON.parseObject(Files.readString(encryptedPath));
    }

    private SecretKey readMaster() throws IOException {
        restrict(masterPath, DurableFiles.OWNER_ONLY);
        byte[] bytes = Base64.getDecoder().decode(Files.readString(masterPath).strip());
        try {
            if (bytes.length != KEY_BITS / Byte.SIZE) {
                throw storageError();
            }
            return new SecretKeySpec(bytes, "AES");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private SecretKey masterForSave() throws IOException, GeneralSecurityException {
        if (!Files.notExists(masterPath)) {
            return readMaster();
        }
        Files.deleteIfExists(encryptedPath);
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(KEY_BITS, RANDOM);
        SecretKey master = generator.generateKey();
        byte[] bytes = master.getEncoded();
        try {
            write(masterPath, Base64.getEncoder().encodeToString(bytes));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        return master;
    }

    private static String mask(String value) {
        return value.length() > 8 ? value.substring(0, 6) + "…" + value.substring(value.length() - 2) : "…";
    }

    private static void createDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY));
        } catch (UnsupportedOperationException e) {
            createDirectory(directory.getParent());
            Files.createDirectory(directory);
            restrict(directory, OWNER_DIRECTORY);
        }
    }

    private static void write(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            restrict(path, DurableFiles.OWNER_ONLY);
        }
        DurableFiles.replace(path, content, DurableFiles.OWNER_ONLY);
        restrict(path, DurableFiles.OWNER_ONLY);
    }

    private static void restrict(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            var file = path.toFile();
            if (!file.setReadable(false, false) || !file.setWritable(false, false)
                    || !file.setExecutable(false, false) || !file.setReadable(true, true)
                    || !file.setWritable(true, true)
                    || (permissions.contains(PosixFilePermission.OWNER_EXECUTE) && !file.setExecutable(true, true))) {
                throw storageError();
            }
        }
    }

    private static IllegalStateException storageError() {
        return new IllegalStateException("密钥文件无法读写或解密，请重新填写");
    }
}
