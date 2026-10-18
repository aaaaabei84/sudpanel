package com.sudpanel.backup;
import static org.assertj.core.api.Assertions.*;
import com.sudpanel.api.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.*;

class MaintenanceTest {
    @TempDir Path dir;Path db;JdbcTemplate jdbc;ApiRepository repo;MaintenanceService service;MaintenanceGate gate;AtomicBoolean owner;
    @BeforeEach void setup(){db=dir.resolve("test.db");jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+db));repo=new ApiRepository(jdbc);gate=new MaintenanceGate();owner=new AtomicBoolean();service=new MaintenanceService(jdbc,db,gate,owner::get);}
    ApiSpec spec(String name){return new ApiSpec(null,name,"GET","https://example.invalid",null,null,null,null,null,1000,false,true,null,null);}
    void planSchema(){new com.sudpanel.perf.PerfRepository(jdbc);new ApiWorkspaceRepository(jdbc);}
    static String seal(byte[] key,String ns,String id,String plain)throws Exception{byte[] nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);var c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));c.updateAAD(("sudpanel-plan-v1:"+ns+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));return "1."+Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    byte[] bytes(String text){return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    byte[] zip(Map<String,byte[]> entries)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new ZipOutputStream(bytes)){for(var e:entries.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}return bytes.toByteArray();}
    Map<String,byte[]> unzip(byte[] bytes)throws Exception{var out=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null)out.put(e.getName(),zip.readAllBytes());}return out;}
    @Test void backupZipPairsAllPlanNamespacesAndActualStartupRestoresBothDatabaseAndKey()throws Exception{
        planSchema();byte[] keyA=new byte[32],keyB=new byte[32];Arrays.fill(keyA,(byte)65);Arrays.fill(keyB,(byte)66);Files.write(BackupFiles.key(db),keyA);
        repo.createCase(spec("original"));jdbc.update("INSERT INTO api_batch_plan(batch_id,schema_version,sealed_plan,digest,created_at) VALUES(1,1,?,'fixture','2026-10-14T00:00:00Z')",seal(keyA,"api-batch","1","fixture api"));jdbc.update("INSERT INTO perf_run_plan(run_id,plan_json,plan_hash,captured_at) VALUES(2,?,'fixture','2026-10-14T00:00:00Z')",seal(keyA,"perf-run","2","fixture perf"));jdbc.update("INSERT INTO api_workspace(id,revision,payload,updated_at) VALUES('workspace-uuid',1,?,'2026-10-14T00:00:00Z')",seal(keyA,"api-workspace","workspace-uuid","fixture workspace"));
        var backup=service.backup();var entries=unzip(Files.readAllBytes(service.backupFile(backup.id())));assertThat(entries.keySet()).containsExactlyInAnyOrder("manifest.json","database.sqlite","vault.key");assertThat(entries.get("vault.key")).isEqualTo(keyA);
        Files.write(BackupFiles.key(db),keyB);jdbc.update("UPDATE api_batch_plan SET sealed_plan=?",seal(keyB,"api-batch","1","new api"));jdbc.update("UPDATE perf_run_plan SET plan_json=?",seal(keyB,"perf-run","2","new perf"));jdbc.update("UPDATE api_workspace SET payload=?",seal(keyB,"api-workspace","workspace-uuid","new workspace"));repo.createCase(spec("later"));
        var preview=service.preview(Files.readAllBytes(service.backupFile(backup.id())));assertThat(preview.differences()).anyMatch(x->x.table().equals("api_case")&&x.delta()==-1);service.stage(preview.id(),preview.sha256());assertThat(service.restoreStatus()).containsEntry("staged",true);
        try(var ds=OfflineRestore.open(db)){assertThatThrownBy(()->OfflineRestore.open(db)).hasMessageContaining("占用");var restored=new JdbcTemplate(ds);assertThat(restored.queryForList("SELECT name FROM api_case",String.class)).containsExactly("original");assertThat(Files.readAllBytes(BackupFiles.key(db))).isEqualTo(keyA);BackupFiles.validatePlans(db,BackupFiles.key(db));}
        assertThat(service.restoreStatus()).containsEntry("staged",false);assertThat(service.restoreStatus().get("lastResult").toString()).contains("RESTORED");
    }
    @Test void maintenanceReadsAndPrivateDownloadsRejectNonLocalClients()throws Exception{
        var filter=new MaintenanceWriteFilter(gate,service);
        for(String path:List.of("/api/maintenance/overview","/api/maintenance/backups/example/download")){
            var req=new MockHttpServletRequest("GET",path);req.setRemoteAddr("192.0.2.1");var response=new MockHttpServletResponse();
            filter.doFilter(req,response,(a,b)->fail("remote maintenance request must be blocked"));assertThat(response.getStatus()).isEqualTo(403);
        }
    }
    @Test void stageCanBeCancelledAndFilterNeverBlocksReadStatusOrCancellation()throws Exception{
        var b=service.backup();var p=service.preview(Files.readAllBytes(service.backupFile(b.id())));service.stage(p.id(),p.sha256());var filter=new MaintenanceWriteFilter(gate,service);
        var request=new MockHttpServletRequest("POST","/api/apitest/cases");var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,c)->fail("mutation must be blocked"));assertThat(response.getStatus()).isEqualTo(409);
        var reads=new AtomicBoolean();filter.doFilter(new MockHttpServletRequest("GET","/api/maintenance/restores/status"),new MockHttpServletResponse(),(a,c)->reads.set(true));assertThat(reads).isTrue();
        filter.doFilter(new MockHttpServletRequest("DELETE","/api/maintenance/restores/stage"),new MockHttpServletResponse(),(a,c)->service.unstage());assertThat(service.staged()).isFalse();
        var writes=new AtomicBoolean();filter.doFilter(request,new MockHttpServletResponse(),(a,c)->writes.set(true));assertThat(writes).isTrue();
    }
    @Test void uploadedArchiveRejectsPathTraversalHashesAndMissingPlanKeyWithoutTouchingCurrentDb()throws Exception{
        String before=BackupFiles.fingerprint(db);assertThatThrownBy(()->service.preview(zip(Map.of("../escape",new byte[200])))).hasMessageContaining("不允许");assertThat(Files.exists(dir.resolve("escape"))).isFalse();
        var b=service.backup();var entries=unzip(Files.readAllBytes(service.backupFile(b.id())));entries.put("database.sqlite",new byte[200]);assertThatThrownBy(()->service.preview(zip(entries))).hasMessageContaining("哈希");assertThat(BackupFiles.fingerprint(db)).isEqualTo(before);
        planSchema();byte[] key=new byte[32];Files.write(BackupFiles.key(db),key);jdbc.update("INSERT INTO api_workspace(id,revision,payload,updated_at) VALUES('uuid',1,?,'2026-10-14T00:00:00Z')",seal(key,"api-workspace","uuid","fixture"));var full=service.backup();var bare=unzip(Files.readAllBytes(service.backupFile(full.id()))).get("database.sqlite");Files.delete(BackupFiles.key(db));assertThatThrownBy(()->service.preview(bare)).hasMessageContaining("缺少配对密钥");
    }
    @Test void ownerStillFinishingAndActiveRowsRejectStageAndCleanup(){var preview=service.cleanupPreview(new MaintenanceService.CleanupRequest("API","2026-10-13T00:00:00Z",true));owner.set(true);assertThatThrownBy(()->service.cleanupCommit(preview.token())).hasMessageContaining("收尾");owner.set(false);var batch=repo.createBatch("running","ONCE",List.of(1L),0,1);assertThat(service.active()).isTrue();assertThatThrownBy(()->service.cleanupCommit(preview.token())).hasMessageContaining("活动");}
    @Test void stageWaitsForInflightMutationThenRefusesAnyOutOfBandDatabaseChange()throws Exception{
        var b=service.backup();var p=service.preview(Files.readAllBytes(service.backupFile(b.id())));var filter=new MaintenanceWriteFilter(gate,service);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var write=pool.submit(()->{try{filter.doFilter(new MockHttpServletRequest("POST","/api/apitest/cases"),new MockHttpServletResponse(),(a,c)->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}repo.createCase(spec("inflight"));});}catch(Exception e){throw new RuntimeException(e);}});assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();var stage=pool.submit(()->service.stage(p.id(),p.sha256()));Thread.sleep(30);assertThat(stage.isDone()).isFalse();release.countDown();write.get(3,TimeUnit.SECONDS);stage.get(3,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();}
        repo.createCase(spec("outside-stage-gate"));String changed=BackupFiles.fingerprint(db);try(var ds=OfflineRestore.open(db)){assertThat(ds).isNotNull();}assertThat(service.restoreStatus().get("lastResult").toString()).contains("REFUSED","已有变化");assertThat(BackupFiles.fingerprint(db)).isEqualTo(changed);assertThat(service.staged()).isFalse();
    }
    @Test void retentionPersistsAndCleanupArchivesBeforeTransactionalDeleteProtectsDrift()throws Exception{
        service.retention(new MaintenanceService.Retention(30,null,90));assertThat(new MaintenanceService(jdbc,db,gate,owner::get).retention().apiDays()).isEqualTo(30);
        var batch=repo.createBatch("old","ONCE",List.of(1L),0,1);jdbc.update("UPDATE api_batch SET status='DONE',started_at='2020-01-01T00:00:00Z' WHERE id=?",batch.id());repo.insertExec(new ApiExec(null,batch.id(),null,"old",1,1,"GET","https://example.invalid",200,"0",null,"PASS",null,1L,"{}",null,null,null,null,"2020-01-01T00:00:00Z","[]",null));
        var preview=service.cleanupPreview(new MaintenanceService.CleanupRequest("API","2026-10-13T00:00:00Z",true));assertThat(preview.counts().get("api_exec")).isEqualTo(1);jdbc.update("UPDATE api_batch SET name='changed' WHERE id=?",batch.id());assertThatThrownBy(()->service.cleanupCommit(preview.token())).hasMessageContaining("已有变化");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_exec",Integer.class)).isEqualTo(1);
        var fresh=service.cleanupPreview(new MaintenanceService.CleanupRequest("API","2026-10-13T00:00:00Z",true));var result=service.cleanupCommit(fresh.token());assertThat(result.deleted()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_exec",Integer.class)).isZero();assertThat(Files.readString(service.archiveFile(result.archiveId()))).contains("changed","credentialsIncluded");
    }
    @Test void cleanupFailureRollsBackEveryDeletedRow()throws Exception{
        var batch=repo.createBatch("old","ONCE",List.of(1L),0,1);jdbc.update("UPDATE api_batch SET status='DONE',started_at='2020-01-01T00:00:00Z'");jdbc.execute("CREATE TRIGGER reject_cleanup BEFORE DELETE ON api_batch BEGIN SELECT RAISE(ABORT,'fixture'); END");var preview=service.cleanupPreview(new MaintenanceService.CleanupRequest("API","2026-10-13T00:00:00Z",false));assertThatThrownBy(()->service.cleanupCommit(preview.token())).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_batch",Integer.class)).isEqualTo(1);
    }
    @Test void backupDuringConcurrentTransactionContainsOnlyCommittedPairs()throws Exception{
        jdbc.execute("CREATE TABLE pair_fixture(id INTEGER PRIMARY KEY, tx INTEGER, side INTEGER)");var pool=Executors.newSingleThreadExecutor();var begin=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=pool.submit(()->{try(var c=jdbc.getDataSource().getConnection()){c.setAutoCommit(false);try(var s=c.createStatement()){s.executeUpdate("INSERT INTO pair_fixture VALUES(1,1,1)");begin.countDown();release.await();s.executeUpdate("INSERT INTO pair_fixture VALUES(2,1,2)");}c.commit();}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(begin.await(2,TimeUnit.SECONDS)).isTrue();MaintenanceService.Backup b;try{b=service.backup();}finally{release.countDown();}writer.get(3,TimeUnit.SECONDS);pool.shutdownNow();var entries=unzip(Files.readAllBytes(service.backupFile(b.id())));Path restored=dir.resolve("snapshot.db");Files.write(restored,entries.get("database.sqlite"));try(var c=BackupFiles.connect(restored);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM pair_fixture")){r.next();assertThat(r.getInt(1)%2).isZero();}assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pair_fixture",Integer.class)).isEqualTo(2);
    }
    @Test void interruptedPairReplacementRollsBackBeforeOpeningDatabase()throws Exception{
        repo.createCase(spec("original"));byte[] oldKey=new byte[32];Files.write(BackupFiles.key(db),oldKey);var backup=service.backup();var p=service.preview(Files.readAllBytes(service.backupFile(backup.id())));service.stage(p.id(),p.sha256());Path restore=BackupFiles.root(db).resolve("restores").resolve(p.id());try(var c=jdbc.getDataSource().getConnection()){BackupFiles.snapshot(c,restore.resolve("rollback.sqlite"));}Files.copy(BackupFiles.key(db),restore.resolve("rollback.key"));
        repo.createCase(spec("half-replaced"));Files.write(BackupFiles.key(db),new byte[32]);BackupFiles.json(BackupFiles.root(db).resolve("restore-in-progress.json"),Map.of("folder",p.id(),"hadKey",true));try(var ds=OfflineRestore.open(db)){assertThat(ds).isNotNull();}assertThat(service.restoreStatus().get("lastResult").toString()).contains("ROLLED_BACK","已还原");assertThat(jdbc.queryForList("SELECT name FROM api_case",String.class)).containsExactly("original");assertThat(Files.readAllBytes(BackupFiles.key(db))).isEqualTo(oldKey);service.unstage();try(var ds=OfflineRestore.open(db)){assertThat(ds).isNotNull();}
    }
    @Test void previewBindingAndStageKeyDriftRefuseSilentReplacement()throws Exception{
        byte[] key=new byte[32];Files.write(BackupFiles.key(db),key);var b=service.backup();var p=service.preview(Files.readAllBytes(service.backupFile(b.id())));Path candidate=BackupFiles.root(db).resolve("restores").resolve(p.id()).resolve("database.sqlite");try(var c=DriverManager.getConnection("jdbc:sqlite:"+candidate);var st=c.createStatement()){st.executeUpdate("INSERT INTO api_case(name,method,url) VALUES('modified','GET','https://modified.invalid')");}
        assertThatThrownBy(()->service.stage(p.id(),p.sha256())).hasMessageContaining("备份文件已有变化");assertThat(service.staged()).isFalse();
        var fresh=service.preview(Files.readAllBytes(service.backupFile(b.id())));service.stage(fresh.id(),fresh.sha256());key[0]=1;Files.write(BackupFiles.key(db),key);try(var ds=OfflineRestore.open(db)){assertThat(ds).isNotNull();}assertThat(service.restoreStatus().get("lastResult").toString()).contains("REFUSED","密钥已有变化");assertThat(service.staged()).isFalse();
    }

}
