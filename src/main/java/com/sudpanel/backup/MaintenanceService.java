package com.sudpanel.backup;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import java.util.function.Supplier;
import com.sudpanel.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/** 私密整库备份和显式历史维护。下载归档含原始证据/凭据，不作为分享包。 */
@Service
public class MaintenanceService {
    private final JdbcTemplate jdbc;private final Path db,root;private final MaintenanceGate gate;private final Supplier<Boolean> owner;
    private final TransactionTemplate tx;
    private com.sudpanel.api.ApiFileStore fileStore;
    @org.springframework.beans.factory.annotation.Autowired public void fileStore(com.sudpanel.api.ApiFileStore store){fileStore=store;}
    public record Table(String name,long rows){}
    public record Retention(Integer apiDays,Integer perfDays,Integer functionDays){}
    public record Overview(List<Table> tables,long databaseBytes,boolean active,Retention retention,Map<String,Object> pendingRestore,List<String> warnings){}
    public record Backup(String id,String createdAt,long bytes,String sha256,boolean credentialsIncluded,Map<String,Long> counts){}
    public record Difference(String table,long currentRows,long restoredRows,long delta){}
    public record RestorePreview(String id,String sha256,Map<String,Long> counts,List<Difference> differences,List<String> warnings,boolean restartRequired){}
    public record CleanupRequest(String domain,String before,boolean archive){}
    public record CleanupPreview(String token,String domain,String before,List<Long> ids,Map<String,Long> counts,List<String> warnings){}
    public record Cleaned(String domain,long deleted,String archiveId,Map<String,Long> counts){}
    @org.springframework.beans.factory.annotation.Autowired
    public MaintenanceService(JdbcTemplate jdbc,AppProperties props,MaintenanceGate gate,MaintenanceActivity activity){this(jdbc,Path.of(props.dbPath()),gate,activity::busy);}
    public MaintenanceService(JdbcTemplate jdbc,Path db,MaintenanceGate gate,Supplier<Boolean> owner){
        this.jdbc=jdbc;this.db=db.toAbsolutePath().normalize();this.root=BackupFiles.root(this.db);this.gate=gate;this.owner=owner;
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(s->jdbc.execute("CREATE TABLE IF NOT EXISTS maintenance_retention(id INTEGER PRIMARY KEY CHECK(id=1),api_days INTEGER,perf_days INTEGER,function_days INTEGER)"));
        try{BackupFiles.privateDir(root);for(String name:List.of("backups","restores","cleanup","archives"))BackupFiles.privateDir(root.resolve(name));}catch(Exception e){throw new IllegalStateException("维护目录不可用",e);}
    }
    public Path database(){return db;}
    public boolean staged(){return Files.exists(root.resolve("restore-stage.json"));}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
    private <T>T io(Checked<T> action){try{return action.get();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("维护操作失败："+e.getMessage(),e);}}
    private interface Checked<T>{T get()throws Exception;}
    private <T>T readLock(Supplier<T> action){gate.lock.readLock().lock();try{if(staged())throw bad("已安排恢复，修改前请撤销stage");return action.get();}finally{gate.lock.readLock().unlock();}}
    private boolean table(String table){return !jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name=?",String.class,table).isEmpty();}
    public boolean active(){if(owner.get())return true;for(String table:List.of("api_batch","perf_run","test_run"))if(table(table)){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE status IN ('RUNNING','WAIT_DEVICE','WAIT_ENV')",Integer.class);if(n!=null&&n>0)return true;}return false;}
    private void idle(){if(active())throw bad("存在活动任务或仍在收尾的执行者，请先停止并等待结束");}
    public Retention retention(){var rows=jdbc.query("SELECT api_days,perf_days,function_days FROM maintenance_retention WHERE id=1",(r,i)->new Retention((Integer)r.getObject(1),(Integer)r.getObject(2),(Integer)r.getObject(3)));return rows.isEmpty()?new Retention(null,null,null):rows.getFirst();}
    public Retention retention(Retention r){for(Integer n:new Integer[]{r.apiDays(),r.perfDays(),r.functionDays()})if(n!=null&&(n<1||n>36500))throw bad("保留天数为1至36500，空表示沿用旧行为");return readLock(()->{jdbc.update("INSERT INTO maintenance_retention(id,api_days,perf_days,function_days) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET api_days=excluded.api_days,perf_days=excluded.perf_days,function_days=excluded.function_days",r.apiDays(),r.perfDays(),r.functionDays());return r;});}
    public Overview overview(){return io(()->{Map<String,Long> counts;try(var c=jdbc.getDataSource().getConnection()){counts=BackupFiles.counts(c);}long bytes=Files.exists(db)?Files.size(db):0;for(String suffix:List.of("-wal","-shm"))if(Files.exists(Path.of(db+suffix)))bytes+=Files.size(Path.of(db+suffix));return new Overview(counts.entrySet().stream().map(e->new Table(e.getKey(),e.getValue())).toList(),bytes,active(),retention(),restoreStatus(),List.of("现有自动保留行为保持：MGTOAPP最多50条自动保留，APPTOMG/性能/API全量保留；设置天数仅提供显式清理的默认范围，不自动删除","完整备份与历史归档包含本机凭据，仅供私密恢复"));});}
    public List<Backup> backups(){return io(()->{var out=new ArrayList<Backup>();try(var paths=Files.list(root.resolve("backups"))){for(Path p:paths.filter(x->x.toString().endsWith(".json")).toList())out.add(BackupFiles.JSON.readValue(p.toFile(),Backup.class));}out.sort(Comparator.comparing(Backup::createdAt).reversed());return List.copyOf(out);});}
    public Backup backup(){return io(()->{
        String id=UUID.randomUUID().toString();Path temp=root.resolve("backups").resolve(id+".sqlite"),zip=root.resolve("backups").resolve(id+".zip");
        try{
            try(var c=jdbc.getDataSource().getConnection()){BackupFiles.snapshot(c,temp);}Path k=Files.exists(BackupFiles.key(db))?BackupFiles.key(db):null;BackupFiles.validatePlans(temp,k);
            byte[] keyBytes=k==null?null:Files.readAllBytes(k);Map<String,Long> counts;try(var c=BackupFiles.connect(temp)){counts=BackupFiles.counts(c);}
            var manifest=new LinkedHashMap<String,Object>();manifest.put("schema",1);manifest.put("format","sudpanel-private-backup");manifest.put("credentialsIncluded",true);manifest.put("databaseSha256",BackupFiles.sha(temp));manifest.put("keySha256",keyBytes==null?null:hex(keyBytes));manifest.put("createdAt",Instant.now().toString());manifest.put("counts",counts);
            try(var output=new ZipOutputStream(Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW))){entry(output,"manifest.json",BackupFiles.JSON.writeValueAsBytes(manifest));output.putNextEntry(new ZipEntry("database.sqlite"));Files.copy(temp,output);output.closeEntry();if(keyBytes!=null)entry(output,"vault.key",keyBytes);}
            BackupFiles.privateFile(zip);var result=new Backup(id,manifest.get("createdAt").toString(),Files.size(zip),BackupFiles.sha(zip),true,counts);BackupFiles.json(root.resolve("backups").resolve(id+".json"),result);return result;
        }catch(Exception e){Files.deleteIfExists(zip);throw e;}finally{Files.deleteIfExists(temp);}
    });}
    private static void entry(ZipOutputStream output,String name,byte[] bytes)throws Exception{output.putNextEntry(new ZipEntry(name));output.write(bytes);output.closeEntry();}
    private static String hex(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String id(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw bad("受管文件ID无效");return value;}
    public Path backupFile(String value){Path p=root.resolve("backups").resolve(id(value)+".zip");if(!Files.isRegularFile(p)||Files.isSymbolicLink(p))throw bad("备份不存在");return p;}
    public RestorePreview preview(byte[] upload){return preview(upload,false,UUID.randomUUID().toString());}
    RestorePreview previewTransfer(byte[] upload,String operationId){return preview(upload,true,id(operationId));}
    private RestorePreview preview(byte[] upload,boolean transfer,String operationId){return io(()->{
        if(upload==null||upload.length<100||upload.length>BackupFiles.MAX_BYTES)throw bad("上传备份为空或超过256MB");String id=operationId;Path dir=root.resolve("restores").resolve(id);
        if(Files.exists(dir))throw bad("该导入编号已使用，请检查处理状态");Files.createDirectory(dir);BackupFiles.privateDir(dir);Path candidate=dir.resolve("database.sqlite"),key=dir.resolve("vault.key");
        try{
            boolean zipped=upload[0]=='P'&&upload[1]=='K';
            if(zipped){var seen=new HashSet<String>();long total=0;try(var zip=new ZipInputStream(new ByteArrayInputStream(upload))){ZipEntry e;while((e=zip.getNextEntry())!=null){String name=e.getName();if(e.isDirectory()||!Set.of("manifest.json","database.sqlite","vault.key").contains(name)||!seen.add(name))throw bad("ZIP包含不允许、重复或路径穿越条目");Path target=dir.resolve(name);try(var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[8192];int n;long bytes=0;while((n=zip.read(buffer))!=-1){bytes+=n;total+=n;if(total>BackupFiles.MAX_BYTES||name.equals("vault.key")&&bytes>32||name.equals("manifest.json")&&bytes>65536)throw bad("ZIP展开大小超限");out.write(buffer,0,n);}}BackupFiles.privateFile(target);}}
                if(!seen.containsAll(Set.of("manifest.json","database.sqlite")))throw bad("ZIP缺少manifest或数据库");var manifest=BackupFiles.map(dir.resolve("manifest.json"));if(!Objects.equals(manifest.get("schema"),1)||!Objects.equals(manifest.get("format"),"sudpanel-private-backup"))throw bad("不支持的备份格式版本");if(!BackupFiles.sha(candidate).equals(manifest.get("databaseSha256"))||!Objects.equals(Files.exists(key)?BackupFiles.sha(key):null,manifest.get("keySha256")))throw bad("备份文件哈希不匹配");
            }else{if(transfer)throw bad("完整数据包必须携带配对ZIP备份");Files.write(candidate,upload,StandardOpenOption.CREATE_NEW);BackupFiles.privateFile(candidate);if(Files.isRegularFile(BackupFiles.key(db))&&!Files.isSymbolicLink(BackupFiles.key(db))){Files.copy(BackupFiles.key(db),key);BackupFiles.privateFile(key);}}
            BackupFiles.validate(candidate);BackupFiles.validatePlans(candidate,Files.exists(key)?key:null);
            Map<String,Long> current,restored;try(var c=jdbc.getDataSource().getConnection();var r=BackupFiles.connect(candidate)){
                if(transfer)compatible(c,r);else if(!BackupFiles.schema(c).equals(BackupFiles.schema(r)))throw bad("备份schema与当前平台不兼容；请使用同版本完整备份");current=BackupFiles.counts(c);restored=BackupFiles.counts(r);
            }
            var diffs=new ArrayList<Difference>();for(String table:current.keySet())diffs.add(new Difference(table,current.get(table),restored.getOrDefault(table,0L),restored.getOrDefault(table,0L)-current.get(table)));
            var preview=new RestorePreview(id,hex(upload),restored,List.copyOf(diffs),List.of("恢复将在下次启动且数据源打开前执行，现库尚未修改","恢复将同时替换数据库与冻结计划密钥；保留原库/密钥回滚副本",zipped?"完整备份包含本机凭据":"旧裸SQLite未携带vault key，使用当前本机密钥校验；跨电脑请上传完整ZIP"),true);
            BackupFiles.json(dir.resolve("preview.json"),preview);var binding=new LinkedHashMap<String,Object>();binding.put("databaseSha256",BackupFiles.sha(candidate));binding.put("keySha256",Files.exists(key)?BackupFiles.sha(key):null);BackupFiles.json(dir.resolve("validation.json"),binding);return preview;
        }catch(Exception e){try(var files=Files.list(dir)){for(Path p:files.toList())Files.deleteIfExists(p);}Files.deleteIfExists(dir);throw e;}
    });}
    /** 仅同版本完整迁移包使用结构兼容检查；列顺序、DDL排版及旧辅助字段不影响SQL按列读写。 */
    static void compatible(Connection current,Connection restored)throws Exception{
        var incoming=BackupFiles.counts(restored);
        for(String table:BackupFiles.counts(current).keySet()){
            if(!(table.startsWith("api_")||table.startsWith("perf_")||table.startsWith("test_")||Set.of("logcat_keyword","maintenance_retention").contains(table)))continue;
            if(!incoming.containsKey(table))throw bad("数据包缺少平台必需表，请使用相同版本重新打包");
            var columns=new HashMap<String,List<Object>>();
            try(var st=restored.createStatement();var rs=st.executeQuery("PRAGMA table_info("+BackupFiles.quote(table)+")")){
                while(rs.next())columns.put(rs.getString("name"),List.of(Objects.toString(rs.getString("type"),"").toUpperCase(Locale.ROOT),rs.getInt("pk")));
            }
            try(var st=current.createStatement();var rs=st.executeQuery("PRAGMA table_info("+BackupFiles.quote(table)+")")){
                while(rs.next()){
                    String name=rs.getString("name");if(table.equals("test_point")&&name.equals("steps"))continue;
                    if(!Objects.equals(columns.get(name),List.of(Objects.toString(rs.getString("type"),"").toUpperCase(Locale.ROOT),rs.getInt("pk"))))throw bad("数据包必需字段结构不兼容，请使用相同版本重新打包");
                }
            }
        }
    }
    public Map<String,Object> stage(String value,String confirmed){return stage(value,confirmed,false);}
    Map<String,Object> stageTransfer(String value,String confirmed){return stage(value,confirmed,true);}
    private Map<String,Object> stage(String value,String confirmed,boolean transfer){gate.lock.writeLock().lock();try{return io(()->{
        if(staged())throw bad("已有恢复stage，请先撤销");idle();BackupFiles.validatePlans(db,Files.exists(BackupFiles.key(db))?BackupFiles.key(db):null);String id=id(value);Path dir=root.resolve("restores").resolve(id);RestorePreview p=BackupFiles.JSON.readValue(dir.resolve("preview.json").toFile(),RestorePreview.class);if(!Objects.equals(p.sha256(),confirmed))throw bad("确认哈希与预检不一致");Path candidate=dir.resolve("database.sqlite"),key=dir.resolve("vault.key");BackupFiles.validate(candidate);BackupFiles.validatePlans(candidate,Files.exists(key)?key:null);var binding=BackupFiles.map(dir.resolve("validation.json"));if(!BackupFiles.sha(candidate).equals(binding.get("databaseSha256"))||!Objects.equals(Files.exists(key)?BackupFiles.sha(key):null,binding.get("keySha256")))throw bad("预检后备份文件已有变化，请重新上传");
        try(var c=jdbc.getDataSource().getConnection();var r=BackupFiles.connect(candidate)){if(transfer)compatible(c,r);else if(!BackupFiles.schema(c).equals(BackupFiles.schema(r)))throw bad("预检之后schema已变化，请重新上传预检");}
        var marker=new LinkedHashMap<String,Object>();marker.put("id",id);marker.put("sha256",p.sha256());marker.put("databaseSha256",BackupFiles.sha(candidate));marker.put("keySha256",Files.exists(key)?BackupFiles.sha(key):null);marker.put("baseFingerprint",BackupFiles.fingerprint(db));marker.put("baseKeySha256",Files.exists(BackupFiles.key(db))?BackupFiles.sha(BackupFiles.key(db)):null);marker.put("stagedAt",Instant.now().toString());try(var c=BackupFiles.connect(candidate)){marker.put("schema",BackupFiles.schema(c));}
        if(transfer){Path assets=dir.resolve("transfer-assets");TransferAssets.read(assets);marker.put("assetsSha256",BackupFiles.sha(assets.resolve("inventory.json")));marker.put("baseAssetsFingerprint",TransferAssets.fingerprint(db));marker.put("transferAssets",true);try(var c=jdbc.getDataSource().getConnection()){marker.put("schema",BackupFiles.schema(c));}}
        BackupFiles.json(root.resolve("restore-stage.json"),marker);return restoreStatus();
    });}finally{gate.lock.writeLock().unlock();}}
    public Map<String,Object> unstage(){gate.lock.writeLock().lock();try{return io(()->{Files.deleteIfExists(root.resolve("restore-stage.json"));return restoreStatus();});}finally{gate.lock.writeLock().unlock();}}
    public Map<String,Object> restoreStatus(){return io(()->{var status=new LinkedHashMap<String,Object>();status.put("staged",staged());status.put("restartRequired",staged());if(staged()){var marker=BackupFiles.map(root.resolve("restore-stage.json"));for(String k:List.of("id","sha256","stagedAt"))status.put(k,marker.get(k));}if(Files.exists(root.resolve("restore-result.json")))status.put("lastResult",BackupFiles.map(root.resolve("restore-result.json")));return status;});}
    private String domainTable(String domain){return switch(domain==null?"":domain){case "API"->"api_batch";case "PERF"->"perf_run";case "FUNCTION"->"test_run";default->throw bad("清理domain需为API/PERF/FUNCTION");};}
    public CleanupPreview cleanupPreview(CleanupRequest request){return readLock(()->io(()->{
        String table=domainTable(request.domain());Instant before;try{String cutoff=request.before();if(cutoff==null||cutoff.isBlank()){Retention r=retention();Integer days=request.domain().equals("API")?r.apiDays():request.domain().equals("PERF")?r.perfDays():r.functionDays();if(days==null)throw bad("未配置此模块保留天数，请填写清理截止时间");cutoff=Instant.now().minus(java.time.Duration.ofDays(days)).toString();}before=Instant.parse(cutoff);}catch(Exception e){throw bad("清理截止时间需带时区的ISO时间");}if(before.isAfter(Instant.now()))throw bad("清理截止时间不能晚于当前时间");
        String time=request.domain().equals("API")?"started_at":"start_time";var ids=table(table)?jdbc.queryForList("SELECT id FROM "+table+" WHERE julianday("+time+")<julianday(?) AND status NOT IN ('RUNNING','WAIT_DEVICE','WAIT_ENV') ORDER BY id",Long.class,before.toString()):List.<Long>of();
        if(ids.size()>10000)throw bad("一次清理最多10000项，请缩小时间范围");
        var payload=payload(request.domain(),ids,before.toString());var counts=new LinkedHashMap<String,Long>();payload.forEach((k,v)->counts.put(k,(long)v.size()));String token=UUID.randomUUID().toString();
        var marker=new LinkedHashMap<String,Object>();marker.put("domain",request.domain());marker.put("before",before.toString());marker.put("ids",ids);marker.put("archive",request.archive());marker.put("digest",hex(BackupFiles.JSON.writeValueAsBytes(payload)));marker.put("createdAt",Instant.now().toString());BackupFiles.json(root.resolve("cleanup").resolve(token+".json"),marker);
        return new CleanupPreview(token,request.domain(),before.toString(),ids,counts,List.of("提交时重新核对活动任务与记录内容；有变化即拒绝，需重新预览",request.archive()?"归档包含原始凭据，生成成功后才执行事务删除":"未选择归档，删除后仅可从先前整库备份恢复"));
    }));}
    private Map<String,List<Map<String,Object>>> payload(String domain,List<Long> ids,String before){
        var out=new LinkedHashMap<String,List<Map<String,Object>>>();var budget=new long[2];String table=domainTable(domain);String in=ids.isEmpty()?"NULL":String.join(",",ids.stream().map(Object::toString).toList());out.put(table,table(table)?rows(budget,"SELECT * FROM "+table+" WHERE id IN ("+in+") ORDER BY id"):List.of());
        if(domain.equals("API")){
            out.put("api_exec",rows(budget,"SELECT * FROM api_exec WHERE batch_id IN ("+in+") OR (batch_id IS NULL AND julianday(ts)<julianday(?)) ORDER BY id",before));
            if(table("api_batch_plan"))out.put("api_batch_plan",rows(budget,"SELECT * FROM api_batch_plan WHERE batch_id IN ("+in+") ORDER BY batch_id"));
        }else if(domain.equals("FUNCTION")){if(table("test_result"))out.put("test_result",rows(budget,"SELECT * FROM test_result WHERE run_id IN ("+in+") ORDER BY id"));}
        else{
            for(String t:List.of("perf_loop","perf_check_result","perf_step_exec","perf_loop_cleanup","perf_run_plan","perf_settlement_check","perf_run_event","perf_run_conditions"))if(table(t))out.put(t,rows(budget,"SELECT * FROM "+t+" WHERE run_id IN ("+in+")"));
            if(table("perf_sample")&&table("perf_loop"))out.put("perf_sample",rows(budget,"SELECT * FROM perf_sample WHERE loop_id IN (SELECT id FROM perf_loop WHERE run_id IN ("+in+")) ORDER BY id"));
            if(table("perf_loop_quality")&&table("perf_loop"))out.put("perf_loop_quality",rows(budget,"SELECT * FROM perf_loop_quality WHERE loop_id IN (SELECT id FROM perf_loop WHERE run_id IN ("+in+")) ORDER BY loop_id"));
        }
        if(table("api_file")){
            var referenced=new java.util.LinkedHashSet<String>();var matcher=java.util.regex.Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
            var vault=new com.sudpanel.api.ApiPlanVault(jdbc);
            for(var entry:out.entrySet())for(var row:entry.getValue()){
                String text=row.values().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("\n"));
                if(entry.getKey().equals("api_batch_plan"))text+=vault.decrypt("api-batch",row.get("batch_id").toString(),row.get("sealed_plan").toString());
                if(entry.getKey().equals("perf_run_plan"))text+=vault.decrypt("perf-run",row.get("run_id").toString(),row.get("plan_json").toString());
                var m=matcher.matcher(text);while(m.find())referenced.add(m.group());
            }
            var files=new ArrayList<Map<String,Object>>();for(String id:referenced)files.addAll(rows(budget,"SELECT * FROM api_file WHERE id=?",id));
            if(!files.isEmpty())out.put("api_file",files);
        }
        return out;
    }
    private List<Map<String,Object>> rows(long[] budget,String sql,Object... args){
        return jdbc.query(sql,rs->{var out=new ArrayList<Map<String,Object>>();var mapper=new org.springframework.jdbc.core.ColumnMapRowMapper();int index=0;while(rs.next()){
            var row=mapper.mapRow(rs,index++);budget[0]++;for(Object value:row.values())budget[1]+=value instanceof String text?text.length()*2L:value instanceof byte[] bytes?bytes.length:16;
            if(budget[0]>100000||budget[1]>64L*1024*1024)throw bad("清理范围超过100000行或64MB证据，请缩小截止范围分批清理");out.add(row);
        }return out;},args);
    }
    public Cleaned cleanupCommit(String token){gate.lock.writeLock().lock();try{if(staged())throw bad("已安排恢复，请先撤销stage");return io(()->{
        Path marker=root.resolve("cleanup").resolve(id(token)+".json");var m=BackupFiles.map(marker);if(Instant.parse(m.get("createdAt").toString()).plusSeconds(900).isBefore(Instant.now()))throw bad("清理预览已过期，请重新预览");String domain=m.get("domain").toString();var ids=((List<?>)m.get("ids")).stream().map(x->((Number)x).longValue()).toList();
        return tx.execute(status->{idle();return io(()->{
            var payload=payload(domain,ids,m.get("before").toString());if(!hex(BackupFiles.JSON.writeValueAsBytes(payload)).equals(m.get("digest")))throw bad("预览后历史已有变化，请重新预览");
            String archiveId=null;var counts=new LinkedHashMap<String,Long>();payload.forEach((k,v)->counts.put(k,(long)v.size()));
            if(Boolean.TRUE.equals(m.get("archive"))){archiveId=UUID.randomUUID().toString();BackupFiles.json(root.resolve("archives").resolve(archiveId+".json"),Map.of("schema",1,"credentialsIncluded",true,"domain",domain,"createdAt",Instant.now().toString(),"tables",payload));}
            // 子表先删，兼容旧库未启用外键的情况；perf的触发器再提供兜底。
            var names=new ArrayList<>(payload.keySet());Collections.reverse(names);for(String table:names){if(table.equals("api_file"))continue;String pk=table.equals("api_batch_plan")?"batch_id":(table.equals("perf_run_plan")||table.equals("perf_run_conditions"))?"run_id":(table.equals("perf_loop_cleanup")||table.equals("perf_loop_quality"))?"loop_id":"id";for(var row:payload.get(table))jdbc.update("DELETE FROM "+table+" WHERE "+pk+"=?",row.get(pk));}
            if(payload.containsKey("api_file")){
                if(fileStore==null)throw bad("文件存储未初始化，请重启后重新预览清理");
                var candidates=payload.get("api_file").stream().map(row->row.get("id").toString()).toList();
                counts.put("api_file",(long)fileStore.cleanupCandidates(candidates));
            }
            Files.deleteIfExists(marker);return new Cleaned(domain,ids.size(),archiveId,counts);
        });});
    });}finally{gate.lock.writeLock().unlock();}}
    public Path archiveFile(String value){Path p=root.resolve("archives").resolve(id(value)+".json");if(!Files.isRegularFile(p)||Files.isSymbolicLink(p))throw bad("归档不存在");return p;}
}
