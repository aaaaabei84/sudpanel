package com.sudpanel.backup;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real private SQLite/ZIP/startup restore, independent AES-GCM envelopes; no app, devices or production data. */
class BackupEncryptedAssetsTest {
    enum Asset { SECRET_REFERENCE, HTTP_PROFILE, EXEC_MASK, BASELINE_MASK, PERF_STEP_MASK, PERF_CONDITIONS }
    @TempDir Path directory;
    Path db;JdbcTemplate jdbc;MaintenanceService service;
    final byte[] keyA=new byte[32],keyB=new byte[32];
    final ObjectMapper json=new ObjectMapper();
    static final String ID="00000000-0000-0000-0000-000000000001",MASK_ID="00000000-0000-0000-0000-000000000002";
    @BeforeEach void setup()throws Exception{
        Arrays.fill(keyA,(byte)0x41);Arrays.fill(keyB,(byte)0x42);db=directory.resolve("backup.db");
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+db));
        for(String sql:List.of("CREATE TABLE api_env(id INTEGER PRIMARY KEY)","CREATE TABLE api_case(id INTEGER PRIMARY KEY,name TEXT)","CREATE TABLE api_exec(id INTEGER PRIMARY KEY,metadata TEXT)",
                "CREATE TABLE api_secret_reference(id TEXT PRIMARY KEY,env_id INTEGER,payload_sealed TEXT)","CREATE TABLE api_http_profile(id TEXT PRIMARY KEY,env_id INTEGER,config_sealed TEXT)",
                "CREATE TABLE api_baseline(id INTEGER PRIMARY KEY,exec_json TEXT)","CREATE TABLE perf_step_exec(id INTEGER PRIMARY KEY,exec_json TEXT)","CREATE TABLE perf_run_conditions(run_id INTEGER PRIMARY KEY,conditions_json TEXT)"))jdbc.execute(sql);
        jdbc.update("INSERT INTO api_env VALUES(1)");jdbc.update("INSERT INTO api_case VALUES(1,'before')");
        service=new MaintenanceService(jdbc,db,new MaintenanceGate(),()->false);
    }
    static String seal(byte[] key,String namespace,String id)throws Exception{
        byte[] nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));
        cipher.updateAAD(("sudpanel-plan-v1:"+namespace+":"+id).getBytes(StandardCharsets.UTF_8));
        return "1."+Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal("fixture private asset".getBytes(StandardCharsets.UTF_8)));
    }
    void asset(Asset asset,byte[] key)throws Exception{
        String mask=json.writeValueAsString(Map.of("secretMask",Map.of("id",MASK_ID,"sealed",seal(key,"exec-mask",MASK_ID))));
        switch(asset){
            case SECRET_REFERENCE->jdbc.update("INSERT OR REPLACE INTO api_secret_reference VALUES(?,1,?)",ID,seal(key,"secret-reference",ID));
            case HTTP_PROFILE->jdbc.update("INSERT OR REPLACE INTO api_http_profile VALUES(?,1,?)",ID,seal(key,"http-advanced-profile","1."+ID));
            case EXEC_MASK->jdbc.update("INSERT OR REPLACE INTO api_exec VALUES(1,?)",mask);
            case BASELINE_MASK->jdbc.update("INSERT OR REPLACE INTO api_baseline VALUES(1,?)",json.writeValueAsString(Map.of("metadata",mask)));
            case PERF_STEP_MASK->jdbc.update("INSERT OR REPLACE INTO perf_step_exec VALUES(1,?)",json.writeValueAsString(Map.of("metadata",mask)));
            case PERF_CONDITIONS->jdbc.update("INSERT OR REPLACE INTO perf_run_conditions VALUES(1,?)",seal(key,"perf-conditions","1"));
        }
    }
    Map<String,byte[]> unzip(byte[] bytes)throws Exception{var out=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)out.put(entry.getName(),zip.readAllBytes());}return out;}
    byte[] zip(Map<String,byte[]> entries)throws Exception{var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return bytes.toByteArray();}
    void bindManifest(Map<String,byte[]> entries)throws Exception{
        var manifest=json.readValue(entries.get("manifest.json"),Map.class);byte[] key=entries.get("vault.key");manifest.put("keySha256",key==null?null:HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key)));entries.put("manifest.json",json.writeValueAsBytes(manifest));
    }
    @ParameterizedTest @EnumSource(Asset.class)
    void eachNewAssetPairsCorrectKeyThroughBackupPreviewAndOfflineRestore(Asset asset)throws Exception{
        Files.write(BackupFiles.key(db),keyA);asset(asset,keyA);assertThatCode(()->BackupFiles.validatePlans(db,BackupFiles.key(db))).doesNotThrowAnyException();
        var backup=service.backup();byte[] archive=Files.readAllBytes(service.backupFile(backup.id()));assertThat(unzip(archive).get("vault.key")).isEqualTo(keyA);
        Files.write(BackupFiles.key(db),keyB);asset(asset,keyB);jdbc.update("INSERT INTO api_case VALUES(2,'after')");
        var preview=service.preview(archive);service.stage(preview.id(),preview.sha256());
        try(var restored=OfflineRestore.open(db)){assertThat(restored).isNotNull();assertThat(Files.readAllBytes(BackupFiles.key(db))).isEqualTo(keyA);assertThat(jdbc.queryForList("SELECT name FROM api_case",String.class)).containsExactly("before");BackupFiles.validatePlans(db,BackupFiles.key(db));}
        assertThat(service.restoreStatus().get("lastResult").toString()).contains("RESTORED");
    }
    @ParameterizedTest @EnumSource(Asset.class)
    void wrongOrAbsentZipKeyIsRejectedAtPreviewBeforeAnyCurrentDatabaseChange(Asset asset)throws Exception{
        Files.write(BackupFiles.key(db),keyA);asset(asset,keyA);var backup=service.backup();var entries=unzip(Files.readAllBytes(service.backupFile(backup.id())));String before=BackupFiles.fingerprint(db);
        entries.put("vault.key",keyB);bindManifest(entries);byte[] wrong=zip(entries);assertThatThrownBy(()->service.preview(wrong)).hasMessageContaining("密钥不匹配");assertThat(BackupFiles.fingerprint(db)).isEqualTo(before);assertThat(Files.readAllBytes(BackupFiles.key(db))).isEqualTo(keyA);assertThat(service.staged()).isFalse();
        entries.remove("vault.key");bindManifest(entries);byte[] missing=zip(entries);assertThatThrownBy(()->service.preview(missing)).hasMessageContaining("缺少配对密钥");assertThat(BackupFiles.fingerprint(db)).isEqualTo(before);assertThat(service.staged()).isFalse();
        Files.write(BackupFiles.key(db),keyB);assertThatThrownBy(()->service.preview(entries.get("database.sqlite"))).hasMessageContaining("密钥不匹配");Files.delete(BackupFiles.key(db));assertThatThrownBy(()->service.preview(entries.get("database.sqlite"))).hasMessageContaining("缺少配对密钥");assertThat(BackupFiles.fingerprint(db)).isEqualTo(before);
    }
    @Test void oldBrokenMetadataWithoutSecretMaskDoesNotRequireOrInventAKey()throws Exception{
        int id=0;for(String metadata:Arrays.asList(null,"","{broken","not json","{\"note\":\"1.not-a-real-cipher\"}","{\"note\":\"secretMask\"}")){
            jdbc.update("INSERT INTO api_exec VALUES(?,?)",++id,metadata);String execution=metadata==null?"{broken":json.writeValueAsString(Map.of("metadata",metadata));jdbc.update("INSERT INTO api_baseline VALUES(?,?)",id,execution);jdbc.update("INSERT INTO perf_step_exec VALUES(?,?)",id,execution);
        }
        BackupFiles.validatePlans(db,null);var backup=service.backup();var entries=unzip(Files.readAllBytes(service.backupFile(backup.id())));assertThat(entries).doesNotContainKey("vault.key");assertThat(service.preview(zip(entries))).isNotNull();assertThat(BackupFiles.key(db)).doesNotExist();
    }
    @Test void explicitMalformedMaskAndWrongAadAreRejectedWithoutReclassifyingLegacyEvidence()throws Exception{
        Files.write(BackupFiles.key(db),keyA);
        for(String marker:List.of("null","{}","{\"id\":\""+MASK_ID+"\"}","{\"id\":\""+MASK_ID+"\",\"sealed\":\"broken\"}")){
            jdbc.update("INSERT OR REPLACE INTO api_exec VALUES(1,?)","{\"secretMask\":"+marker+"}");assertThatThrownBy(()->BackupFiles.validatePlans(db,BackupFiles.key(db))).isInstanceOf(IllegalArgumentException.class);
        }
        jdbc.update("DELETE FROM api_exec");asset(Asset.HTTP_PROFILE,keyA);jdbc.update("UPDATE api_http_profile SET env_id=2");assertThatThrownBy(()->BackupFiles.validatePlans(db,BackupFiles.key(db))).hasMessageContaining("密钥不匹配");
        jdbc.update("DELETE FROM api_http_profile");asset(Asset.PERF_CONDITIONS,keyA);jdbc.update("UPDATE perf_run_conditions SET run_id=2");assertThatThrownBy(()->BackupFiles.validatePlans(db,BackupFiles.key(db))).hasMessageContaining("密钥不匹配");
    }
}
