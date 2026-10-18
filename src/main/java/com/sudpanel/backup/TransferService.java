package com.sudpanel.backup;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 两步完整迁移：一次生成下载，一次上传后自动备份、安排恢复及重启。 */
@Service
public class TransferService {
    public record Archive(String id,String createdAt,long bytes,String sha256) {}
    public record Imported(String id,String status,boolean restartRequired,String backupId) {}
    record FileEntry(String path,long bytes,String sha256) {}
    record Manifest(int schema,String format,String version,String createdAt,boolean credentialsIncluded,List<FileEntry> files) {}
    private final MaintenanceService maintenance;
    private final MaintenanceGate gate;
    private final TransferRestart restart;
    private final String version;
    private final Path db,root;

    public TransferService(MaintenanceService maintenance,MaintenanceGate gate,TransferRestart restart,
                           @Value("${sudpanel.version:dev}") String version) {
        this.maintenance=maintenance;this.gate=gate;this.restart=restart;this.version=version;
        db=maintenance.database();root=BackupFiles.root(db).resolve("transfers");
        try{BackupFiles.privateDir(root);BackupFiles.privateDir(root.resolve("operations"));}
        catch(Exception e){throw new IllegalStateException("数据迁移目录不可用",e);}
    }
    private static String id(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(Exception e){throw new IllegalArgumentException("数据迁移编号无效");}}
    private void idle(){if(maintenance.active())throw new IllegalArgumentException("存在活动任务或后台收尾，请停止任务后再迁移数据");if(maintenance.staged())throw new ResponseStatusException(HttpStatus.CONFLICT,"已有数据导入等待重启，请先检查处理状态");}
    private interface Action<T>{T run()throws Exception;}
    private <T>T locked(Action<T> action){gate.lock.writeLock().lock();try{return action.run();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("完整数据迁移失败，请重试或检查本机日志",e);}finally{gate.lock.writeLock().unlock();}}

    public Archive exportArchive(){return locked(()->{idle();return exportLocked();});}
    private Archive exportLocked()throws Exception{
        String id=UUID.randomUUID().toString();Path work=root.resolve(id+".work"),archive=root.resolve(id+".zip");
        BackupFiles.privateDir(work);
        try{
            var nativeBackup=maintenance.backup();
            Files.copy(maintenance.backupFile(nativeBackup.id()),work.resolve("database.zip"));BackupFiles.privateFile(work.resolve("database.zip"));
            TransferAssets.Inventory inventory=TransferAssets.capture(db,work.resolve("assets"));
            var files=new ArrayList<FileEntry>();
            files.add(new FileEntry("database.zip",Files.size(work.resolve("database.zip")),BackupFiles.sha(work.resolve("database.zip"))));
            files.add(new FileEntry("assets/inventory.json",Files.size(work.resolve("assets/inventory.json")),BackupFiles.sha(work.resolve("assets/inventory.json"))));
            for(var entry:inventory.files())files.add(new FileEntry("assets/"+entry.path(),entry.bytes(),entry.sha256()));
            long total=files.stream().mapToLong(FileEntry::bytes).sum();
            Manifest manifest=new Manifest(1,"sudpanel-complete-data",version,Instant.now().toString(),true,List.copyOf(files));
            byte[] manifestBytes=BackupFiles.JSON.writeValueAsBytes(manifest);
            if(manifestBytes.length>8L*1024*1024||Files.size(work.resolve("assets/inventory.json"))>8L*1024*1024)
                throw new IllegalArgumentException("附件路径清单超过8MB，请缩短路径或减少历史附件后重新打包");
            // 与导入使用同一预算，不能生成展开时必然被拒绝的数据包。
            total=expandedDatabaseBudget(work.resolve("database.zip"),total+manifestBytes.length);
            if(total>TransferAssets.LIMIT)throw new IllegalArgumentException("完整数据包超过256MB，请先清理不需要的历史附件");
            try(var zip=new ZipOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW))){
                for(var file:files){zip.putNextEntry(new ZipEntry(file.path()));Files.copy(work.resolve(file.path()),zip);zip.closeEntry();}
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifestBytes);zip.closeEntry();
            }
            BackupFiles.privateFile(archive);
            if(Files.size(archive)>TransferAssets.LIMIT)throw new IllegalArgumentException("数据包压缩后仍超过256MB");
            Archive result=new Archive(id,manifest.createdAt(),Files.size(archive),BackupFiles.sha(archive));BackupFiles.json(root.resolve(id+".json"),result);return result;
        }catch(Exception e){Files.deleteIfExists(archive);throw e;}finally{TransferAssets.deleteTree(work);}
    }
    public Path archiveFile(String archiveId){Path path=root.resolve(id(archiveId)+".zip");TransferAssets.noLinks(path);if(!Files.isRegularFile(path))throw new IllegalArgumentException("完整数据包不存在");return path;}

    private static boolean allowed(String name){
        if(name.equals("manifest.json")||name.equals("database.zip")||name.equals("assets/inventory.json"))return true;
        if(!name.startsWith("assets/"))return false;
        try{TransferAssets.safeName(name.substring(7));return true;}catch(Exception ignored){return false;}
    }
    private Manifest unpack(byte[] content,Path destination)throws Exception{
        BackupFiles.privateDir(destination);var seen=new HashSet<String>();long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(content))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(entry.isDirectory()||!allowed(name)||!seen.add(name)||seen.size()>TransferAssets.FILE_LIMIT+3)throw new IllegalArgumentException("这不是平台导出的完整数据包，或包内有重复、越界文件");
                Path target=destination.resolve(name);BackupFiles.privateDir(target.getParent());long size=0;
                try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[65536];int n;
                    while((n=zip.read(buffer))!=-1){size+=n;total+=n;if(total>TransferAssets.LIMIT||name.endsWith("manifest.json")&&size>8L*1024*1024||name.equals("assets/inventory.json")&&size>8L*1024*1024)throw new IllegalArgumentException("数据包展开超过容量限制");output.write(buffer,0,n);}
                }
                BackupFiles.privateFile(target);
            }
        }
        if(!seen.containsAll(Set.of("manifest.json","database.zip","assets/inventory.json")))throw new IllegalArgumentException("请选择“打包并下载全部数据”生成的数据包；旧离线目录ZIP不能在此上传");
        Manifest manifest=BackupFiles.JSON.readValue(destination.resolve("manifest.json").toFile(),Manifest.class);
        if(manifest.schema()!=1||!"sudpanel-complete-data".equals(manifest.format())||!manifest.credentialsIncluded()||!version.equals(manifest.version())||manifest.files()==null||manifest.files().size()>TransferAssets.FILE_LIMIT+2)
            throw new IllegalArgumentException("数据包版本或格式不兼容，请在相同版本平台重新打包");
        var expected=new HashSet<String>();
        for(var file:manifest.files()){
            if(file.path()==null||file.path().equals("manifest.json")||!allowed(file.path())||!expected.add(file.path())||file.bytes()<0||file.bytes()>TransferAssets.LIMIT||file.sha256()==null||!file.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("数据包文件清单无效");
            Path path=destination.resolve(file.path());
            if(!Files.isRegularFile(path)||Files.size(path)!=file.bytes()||!BackupFiles.sha(path).equals(file.sha256()))throw new IllegalArgumentException("数据包文件缺失、长度或SHA256不匹配");
        }
        expected.add("manifest.json");if(!expected.equals(seen))throw new IllegalArgumentException("数据包包含未登记文件");
        TransferAssets.read(destination.resolve("assets"));
        // 总预算同时包含内层SQLite实际展开字节，不能信ZIP声明长度。
        expandedDatabaseBudget(destination.resolve("database.zip"),total);
        return manifest;
    }
    private static long expandedDatabaseBudget(Path archive,long total)throws Exception{
        try(var inner=new ZipInputStream(Files.newInputStream(archive))){byte[] buffer=new byte[65536];while(inner.getNextEntry()!=null){int n;while((n=inner.read(buffer))!=-1){total+=n;if(total>TransferAssets.LIMIT)throw new IllegalArgumentException("完整数据及附件展开超过256MB，请先清理不需要的历史附件");}}}
        return total;
    }
    public Imported importArchive(byte[] content,String operationId){
        String operation=id(operationId);
        if(content==null||content.length<100||content.length>TransferAssets.LIMIT)throw new IllegalArgumentException("请选择256MB以内的完整数据ZIP");
        return locked(()->{
            String uploadSha=TransferAssets.hash(content);Path record=root.resolve("operations").resolve(operation+".json");
            if(Files.exists(record)){
                var previous=BackupFiles.map(record);if(!uploadSha.equals(previous.get("uploadSha256")))throw new ResponseStatusException(HttpStatus.CONFLICT,"同一导入编号的内容不同，已保留原操作");
                Map<String,Object> status=status(operation);var result=status.get("lastResult");
                if(result instanceof Map<?,?> r&&operation.equals(r.get("id")))return new Imported(operation,Objects.toString(r.get("status")),false,Objects.toString(previous.get("backupId"),null));
                if(Boolean.TRUE.equals(status.get("staged"))&&operation.equals(status.get("id")))return new Imported(operation,"RESTARTING",true,Objects.toString(previous.get("backupId"),null));
                throw new ResponseStatusException(HttpStatus.CONFLICT,"该导入操作已受理，请检查状态，不要重复上传");
            }
            idle();restart.requireSupported();Path work=root.resolve("import-"+operation);boolean staged=false;
            try{
                unpack(content,work);
                var preview=maintenance.previewTransfer(Files.readAllBytes(work.resolve("database.zip")),operation);
                Path candidate=BackupFiles.root(db).resolve("restores").resolve(operation);
                Files.move(work.resolve("assets"),candidate.resolve("transfer-assets"),StandardCopyOption.ATOMIC_MOVE);
                Archive automatic=exportLocked();
                var state=new LinkedHashMap<String,Object>();state.put("id",operation);state.put("uploadSha256",uploadSha);state.put("backupId",automatic.id());state.put("status","STAGED");BackupFiles.json(record,state);
                maintenance.stageTransfer(operation,preview.sha256());staged=true;
                restart.restartAfterResponse();
                return new Imported(operation,"RESTARTING",true,automatic.id());
            }catch(Exception error){
                if(staged){var current=maintenance.restoreStatus();if(operation.equals(current.get("id")))maintenance.unstage();}
                BackupFiles.json(record,Map.of("id",operation,"uploadSha256",uploadSha,"status","REFUSED","error","本次导入未完成，原数据保持；可重新选择数据包"));
                throw error;
            }finally{TransferAssets.deleteTree(work);}
        });
    }
    public Map<String,Object> status(String operationId){
        var status=maintenance.restoreStatus();if(operationId==null||operationId.isBlank())return status;
        String operation=id(operationId);
        if(operation.equals(status.get("id"))||status.get("lastResult") instanceof Map<?,?> r&&operation.equals(r.get("id")))return status;
        Path record=root.resolve("operations").resolve(operation+".json");
        if(Files.exists(record))try{
            var value=BackupFiles.map(record);
            if(Set.of("RESTORED","REFUSED","ROLLED_BACK").contains(Objects.toString(value.get("status"),""))){
                var result=new LinkedHashMap<String,Object>();
                for(String field:List.of("id","status","error","restoredAt","rollbackId"))if(value.get(field)!=null)result.put(field,value.get(field));
                return Map.of("staged",false,"lastResult",result);
            }
            String error=Objects.toString(value.get("error"),"上次导入未完成，原数据已保留；请重新选择数据包");
            return Map.of("staged",false,"lastResult",Map.of("id",operation,"status","REFUSED","error",error));
        }catch(Exception e){throw new IllegalStateException("导入状态暂不可读，请重试",e);}
        return Map.of("staged",false,"operationPending",true);
    }
}
