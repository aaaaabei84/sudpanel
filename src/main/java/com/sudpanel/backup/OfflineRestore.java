package com.sudpanel.backup;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 数据源创建之前的真实停机恢复入口。进程全生命周期持锁，DB与vault成对回滚。 */
public final class OfflineRestore {
    private OfflineRestore(){}
    public static final class LockedDataSource extends DriverManagerDataSource implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;
        LockedDataSource(Path db,FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;setDriverClassName("org.sqlite.JDBC");setUrl("jdbc:sqlite:"+db);}
        @Override public void close()throws Exception{lock.release();channel.close();}
    }
    public static LockedDataSource open(Path database)throws Exception{
        Path db=database.toAbsolutePath().normalize();Files.createDirectories(db.getParent());Path root=BackupFiles.root(db);BackupFiles.privateDir(root);
        FileChannel channel=FileChannel.open(root.resolve("database.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){channel.close();throw new IllegalStateException("此数据库已由另一平台进程占用");}
        if(lock==null){channel.close();throw new IllegalStateException("此数据库已由另一平台进程占用");}
        try{apply(db);return new LockedDataSource(db,channel,lock);}catch(Exception e){lock.release();channel.close();throw e;}
    }
    private static void checkpoint(Path db)throws Exception{
        if(!Files.exists(db))return;
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var rs=s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")){if(rs.next()&&rs.getInt(1)!=0)throw new IllegalStateException("SQLite仍被其他写入者占用，拒绝恢复");}
        Files.deleteIfExists(Path.of(db+"-wal"));Files.deleteIfExists(Path.of(db+"-shm"));
    }
    private static void copyAtomic(Path from,Path to)throws Exception{Path tmp=to.resolveSibling(to.getFileName()+".restore-tmp");Files.copy(from,tmp,StandardCopyOption.REPLACE_EXISTING);BackupFiles.privateFile(tmp);BackupFiles.atomic(tmp,to);}
    private static void rollback(Path db,Path root,Map<String,Object> journal)throws Exception{
        String folder=journal.get("folder").toString();if(!folder.matches("[a-f0-9-]{36}"))throw new IllegalStateException("恢复日志路径无效");
        Path dir=root.resolve("restores").resolve(folder);Path old=dir.resolve("rollback.sqlite"),oldKey=dir.resolve("rollback.key");
        if(!Files.exists(old))throw new IllegalStateException("恢复回滚副本丢失，拒绝打开数据库");
        checkpoint(db);copyAtomic(old,db);
        if(Boolean.TRUE.equals(journal.get("hadKey")))copyAtomic(oldKey,BackupFiles.key(db));else Files.deleteIfExists(BackupFiles.key(db));
        if(Boolean.TRUE.equals(journal.get("transferAssets")))TransferAssets.replace(db,dir.resolve("rollback-assets"));
        BackupFiles.validate(db);BackupFiles.validatePlans(db,Files.exists(BackupFiles.key(db))?BackupFiles.key(db):null);
        Files.deleteIfExists(root.resolve("restore-in-progress.json"));
    }
    private static void result(Path root,Map<String,Object> result)throws Exception{
        // 每次迁移保留自己的终态；后一次恢复不能让旧UUID退回“未完成”。
        Object id=result.get("id");
        if(id instanceof String value&&value.matches("[a-f0-9-]{36}")){
            Path operation=root.resolve("transfers/operations").resolve(value+".json");
            if(Files.isRegularFile(operation)&&!Files.isSymbolicLink(operation)){
                var record=new LinkedHashMap<String,Object>(BackupFiles.map(operation));
                if(value.equals(record.get("id"))&&record.containsKey("uploadSha256")){
                    record.putAll(result);BackupFiles.json(operation,record);
                }
            }
        }
        BackupFiles.json(root.resolve("restore-result.json"),result);
    }
    static void apply(Path db)throws Exception{
        Path root=BackupFiles.root(db),marker=root.resolve("restore-stage.json"),journalFile=root.resolve("restore-in-progress.json");
        if(Files.exists(journalFile)){
            var journal=BackupFiles.map(journalFile);rollback(db,root,journal);
            result(root,Map.of("id",journal.get("folder"),"status","ROLLED_BACK","error","上次恢复被中断，已还原原库与密钥和附件"));Files.deleteIfExists(marker);return;
        }
        if(!Files.exists(marker))return;
        try{applyStaged(db,root,marker,journalFile);}catch(Exception failure){
            if(Files.exists(journalFile)){
                // 回滚失败必须抛出并保留journal，不能打开混配的数据库。
                var journal=BackupFiles.map(journalFile);rollback(db,root,journal);
                result(root,Map.of("id",journal.get("folder"),"status","ROLLED_BACK","error",Objects.toString(failure.getMessage(),"恢复失败")));
            }else{
                BackupFiles.validate(db);
                var refused=new LinkedHashMap<String,Object>();refused.put("status","REFUSED");refused.put("error",Objects.toString(failure.getMessage(),"恢复预检拒绝"));
                Object refusedId=null;try{refusedId=BackupFiles.map(marker).get("id");}catch(Exception ignored){}
                if(refusedId instanceof String value&&value.matches("[a-f0-9-]{36}"))refused.put("id",value);
                result(root,refused);
            }
            Files.deleteIfExists(marker);
        }
    }
    private static void applyStaged(Path db,Path root,Path marker,Path journalFile)throws Exception{
        var stage=BackupFiles.map(marker);String id=Objects.toString(stage.get("id"),"");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalStateException("恢复stage标记无效");
        Path dir=root.resolve("restores").resolve(id),candidate=dir.resolve("database.sqlite"),key=dir.resolve("vault.key");
        BackupFiles.validate(candidate);BackupFiles.validatePlans(candidate,Files.exists(key)?key:null);
        if(!BackupFiles.sha(candidate).equals(stage.get("databaseSha256"))||!Objects.equals(Files.exists(key)?BackupFiles.sha(key):null,stage.get("keySha256")))throw new IllegalStateException("待恢复文件已变化，拒绝恢复");
        if(!Objects.equals(Files.exists(BackupFiles.key(db))?BackupFiles.sha(BackupFiles.key(db)):null,stage.get("baseKeySha256")))throw new IllegalStateException("stage之后密钥已有变化，拒绝覆盖；请撤销stage并重新预检");
        if(!BackupFiles.fingerprint(db).equals(stage.get("baseFingerprint")))throw new IllegalStateException("stage之后数据库已有变化，拒绝覆盖；请撤销stage并重新预检");
        boolean transfer=Boolean.TRUE.equals(stage.get("transferAssets"));
        if(transfer){Path assets=dir.resolve("transfer-assets");TransferAssets.read(assets);if(!BackupFiles.sha(assets.resolve("inventory.json")).equals(stage.get("assetsSha256")))throw new IllegalStateException("待导入资产清单已变化，拒绝恢复");if(!TransferAssets.fingerprint(db).equals(stage.get("baseAssetsFingerprint")))throw new IllegalStateException("安排导入后配置或历史附件已有变化，已保留现有数据");}
        checkpoint(db);try(var c=BackupFiles.connect(db)){if(!BackupFiles.schema(c).equals(stage.get("schema")))throw new IllegalStateException("当前schema已变化，拒绝恢复");}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)){BackupFiles.snapshot(c,dir.resolve("rollback.sqlite"));}
        boolean hadKey=Files.exists(BackupFiles.key(db));if(hadKey){Files.copy(BackupFiles.key(db),dir.resolve("rollback.key"),StandardCopyOption.REPLACE_EXISTING);BackupFiles.privateFile(dir.resolve("rollback.key"));}
        if(transfer)TransferAssets.capture(db,dir.resolve("rollback-assets"));
        var journal=new LinkedHashMap<String,Object>();journal.put("folder",id);journal.put("hadKey",hadKey);journal.put("transferAssets",transfer);BackupFiles.json(journalFile,journal);
        copyAtomic(candidate,db);
        if(Files.exists(key))copyAtomic(key,BackupFiles.key(db));else Files.deleteIfExists(BackupFiles.key(db));
        if(transfer)TransferAssets.replace(db,dir.resolve("transfer-assets"));
        BackupFiles.validate(db);BackupFiles.validatePlans(db,Files.exists(key)?BackupFiles.key(db):null);
        result(root,Map.of("id",id,"status","RESTORED","restoredAt",Instant.now().toString(),"rollbackId",id));
        Files.delete(marker);Files.delete(journalFile);
    }
}
