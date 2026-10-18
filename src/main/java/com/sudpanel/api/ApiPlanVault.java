package com.sudpanel.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;

/** AES-GCM本机计划库。备份SQLite必须同时保存KEY_FILE_NAME；共享导出不得携带该秘密文件。 */
public final class ApiPlanVault {
    public static final String KEY_FILE_NAME = ".sudpanel-plan-v1.key";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<Object, byte[]> MEMORY_KEYS = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    public ApiPlanVault(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc); }
    public String encrypt(String namespace, long id, String plain) { return encrypt(namespace, Long.toString(id), plain); }
    public String decrypt(String namespace, long id, String sealed) { return decrypt(namespace, Long.toString(id), sealed); }
    public String encrypt(String namespace, String id, String plain) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad(namespace, id));
            return "1." + Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("冻结计划加密保存失败", e); }
    }
    public String decrypt(String namespace, String id, String sealed) {
        try {
            String[] p = sealed.split("\\."); if (p.length != 3 || !p[0].equals("1")) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(p[1]); if (nonce.length != 12) throw new IllegalArgumentException();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad(namespace, id));
            return new String(c.doFinal(Base64.getDecoder().decode(p[2])), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("冻结计划不可读取：本机计划密钥缺失、密文损坏或版本不支持；不会自动使用当前配置", e); }
    }
    String seal(long batchId, String plain) { return encrypt("api-batch", batchId, plain); }
    String open(long batchId, String sealed) { return decrypt("api-batch", batchId, sealed); }
    /** null仅用于内存数据库；文件SQLite返回受管密钥的绝对路径，不创建文件。 */
    public Path keyFile() {
        try {
            String url;
            try (var connection = jdbc.getDataSource().getConnection()) { url = connection.getMetaData().getURL(); }
            if (url == null || !url.startsWith("jdbc:sqlite:") || url.contains(":memory:") || url.contains("mode=memory")) return null;
            String file = url.substring("jdbc:sqlite:".length());
            if (file.startsWith("file:")) file = java.net.URI.create(file).getPath();
            return Path.of(file).toAbsolutePath().normalize().getParent().resolve(KEY_FILE_NAME);
        } catch (Exception e) { throw new IllegalStateException("无法确定计划密钥保存位置", e); }
    }
    private byte[] key(boolean create) throws Exception {
        Path path = keyFile();
        synchronized (ApiPlanVault.class) {
            if (path == null) {
                Object source = jdbc.getDataSource();
                if (!create && !MEMORY_KEYS.containsKey(source)) throw new IllegalStateException("计划密钥不存在");
                return MEMORY_KEYS.computeIfAbsent(source, ignored -> randomKey());
            }
            if (Files.isSymbolicLink(path)) throw new IllegalStateException("计划密钥不能是符号链接");
            if (!Files.exists(path)) {
                if (!create) throw new IllegalStateException("本机计划密钥不存在");
                // 丢失原密钥时，新建密钥会把同库历史分成两套不可兼容密文。
                // 只有尚无加密资产的数据库允许初始化；恢复必须使用配对备份。
                for (String table : java.util.List.of("api_batch_plan", "perf_run_plan", "api_workspace", "api_secret_reference", "api_http_profile", "perf_run_conditions")) {
                    if (!jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name=?", String.class, table).isEmpty()
                            && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " LIMIT 1)", Boolean.class))) {
                        throw new IllegalStateException("数据库已有加密资产但本机密钥丢失，请恢复配对密钥；不能初始化新密钥");
                    }
                }
                if(!jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name='api_exec'",String.class).isEmpty()
                        && jdbc.queryForList("PRAGMA table_info(api_exec)").stream().anyMatch(c->"metadata".equals(c.get("name")))
                        && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM api_exec WHERE CASE WHEN json_valid(metadata) THEN json_type(metadata,'$.secretMask')='object' ELSE 0 END LIMIT 1)",Boolean.class)))
                    throw new IllegalStateException("数据库已有加密敏感标记但配对密钥丢失，不能初始化新密钥");
                for(String table:java.util.List.of("api_baseline","perf_step_exec")) {
                    if(!jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name=?",String.class,table).isEmpty()
                        && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE CASE WHEN json_valid(exec_json) THEN CASE WHEN json_valid(json_extract(exec_json,'$.metadata')) THEN json_type(json_extract(exec_json,'$.metadata'),'$.secretMask')='object' ELSE 0 END ELSE 0 END LIMIT 1)",Boolean.class)))
                        throw new IllegalStateException("独立基线或性能证据已有加密敏感标记但配对密钥丢失，不能初始化新密钥");
                }
                byte[] value = randomKey();
                try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (UnsupportedOperationException e) { Files.createFile(path); }
                Files.write(path, value, StandardOpenOption.WRITE);
            }
            byte[] value = Files.readAllBytes(path); if (value.length != 32) throw new IllegalStateException("计划密钥格式无效"); return value;
        }
    }
    private static byte[] aad(String namespace, String id) {
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,40}") || id == null || !id.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("计划身份无效");
        return ("sudpanel-plan-v1:" + namespace + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static byte[] randomKey() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return b; }
}
