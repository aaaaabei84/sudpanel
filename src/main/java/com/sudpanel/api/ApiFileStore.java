package com.sudpanel.api;

import com.sudpanel.config.AppProperties;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 原字节以本机SQLite为正本；UUID目录只是可校验、可重建缓存，整库备份同时包含文件。 */
@Service
public class ApiFileStore {
    public static final int UPLOAD_LIMIT=16*1024*1024, BODY_LIMIT=64*1024*1024;
    public static final long STORE_LIMIT=128L*1024*1024;
    public record FileInfo(String fileId,String kind,String filename,String contentType,long bytes,String sha256,String createdAt) {}
    public record Overview(long bytes,long maxBytes,int uploadMaxBytes,int bodyMaxBytes,List<FileInfo> files,String retention) {}
    private final JdbcTemplate jdbc;
    private final Path root;
    private String startupCacheWarning;
    private final Map<String,Integer> leases=new HashMap<>();
    private java.util.function.BooleanSupplier activity=()->false;
    @Autowired public void activity(com.sudpanel.backup.MaintenanceActivity activity){this.activity=activity::busy;}
    public void activity(java.util.function.BooleanSupplier activity){this.activity=Objects.requireNonNull(activity);}
    private void idle(){if(activity.getAsBoolean())throw new IllegalArgumentException("仍有执行任务或结果正在收尾，请等待结束后清理文件");}
    @Autowired public ApiFileStore(JdbcTemplate jdbc,AppProperties props){this(jdbc,Path.of(props.dbPath()));}
    public ApiFileStore(JdbcTemplate jdbc,Path db){
        this.jdbc=jdbc;
        try {
            Path parent=db.toAbsolutePath().normalize().getParent();Files.createDirectories(parent);
            root=parent.toRealPath().resolve(db.getFileName()+".api-files");
        } catch(IOException e){throw new IllegalStateException("数据库父目录不可用：请检查权限后重启",e);}
        // 缓存故障仅使文件读写失败；不让附加功能阻断整个测试平台启动。
        try{privateDir();}catch(IOException e){startupCacheWarning="受管缓存目录不可用："+e.getMessage()+"；其他平台功能可继续使用。请修复权限/空间/符号链接后重试或重启，数据库原字节仍保留";}
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_file(id TEXT PRIMARY KEY,kind TEXT NOT NULL,filename TEXT NOT NULL,content_type TEXT NOT NULL,bytes INTEGER NOT NULL,sha256 TEXT NOT NULL,created_at TEXT NOT NULL,payload BLOB NOT NULL)");
    }
    public static String validId(String id){if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("fileId无效；只能选择本机受管文件，不能填写路径");return id;}
    public static String filename(String value){
        if(value==null||value.isBlank())return "file.bin";
        String v=value.replace('\\','/');v=v.substring(v.lastIndexOf('/')+1);
        if(v.isBlank()||v.equals(".")||v.equals("..")||v.length()>180||v.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("文件名为空、过长或含控制字符");
        return v;
    }
    public static String mime(String value){
        String v=value==null||value.isBlank()?"application/octet-stream":value.trim();
        if(v.length()>200||!v.matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+(?:[; ][^\\r\\n\\x00-\\x1f]*)?"))throw new IllegalArgumentException("文件Content-Type无效");
        return v;
    }
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public FileInfo upload(InputStream input,String name,String type)throws IOException {
        try(var out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if((long)out.size()+n>UPLOAD_LIMIT)throw new IllegalArgumentException("上传超过16MiB；未保存任何半文件");out.write(b,0,n);}
            return save("UPLOAD",name,type,out.toByteArray());
        }
    }
    public synchronized FileInfo save(String kind,String name,String type,byte[] bytes){
        if(!Set.of("UPLOAD","RESPONSE","PARTIAL").contains(kind))throw new IllegalArgumentException("文件类型无效");
        if(bytes==null||bytes.length>(kind.equals("UPLOAD")?UPLOAD_LIMIT:BODY_LIMIT))throw new IllegalArgumentException("受管文件超过字节上限");
        long used=Optional.ofNullable(jdbc.queryForObject("SELECT SUM(bytes) FROM api_file",Long.class)).orElse(0L);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM api_file",Integer.class);
        if(used+bytes.length>STORE_LIMIT||count!=null&&count>=2000)throw new IllegalStateException("受管文件容量已满（128MiB/2000个）；请先在「数据维护」生成完整本机备份并清理不再需要的历史，再在受管文件管理清理无引用文件后重试");
        FileInfo f=new FileInfo(UUID.randomUUID().toString(),kind,filename(name),mime(type),bytes.length,sha(bytes),Instant.now().toString());
        // 先完成目录写入再保存正本；失败不留下DB中无法下载的记录。
        cache(f,bytes);
        try{jdbc.update("INSERT INTO api_file(id,kind,filename,content_type,bytes,sha256,created_at,payload) VALUES(?,?,?,?,?,?,?,?)",f.fileId(),f.kind(),f.filename(),f.contentType(),f.bytes(),f.sha256(),f.createdAt(),bytes);}
        catch(RuntimeException e){try{Files.deleteIfExists(path(f.fileId()));}catch(IOException ignored){}throw e;}
        return f;
    }
    public synchronized FileInfo info(String id){return jdbc.query("SELECT id,kind,filename,content_type,bytes,sha256,created_at FROM api_file WHERE id=?",(r,i)->new FileInfo(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getString(7)),validId(id)).stream().findFirst().orElseThrow(()->new IllegalArgumentException("受管文件不存在或已被清理；请重新上传并替换引用，禁止空体重发"));}
    public synchronized Overview overview(){var files=jdbc.query("SELECT id,kind,filename,content_type,bytes,sha256,created_at FROM api_file ORDER BY created_at DESC",(r,i)->new FileInfo(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getString(7)));return new Overview(files.stream().mapToLong(FileInfo::bytes).sum(),STORE_LIMIT,UPLOAD_LIMIT,BODY_LIMIT,files,"文件跨重启保留；被用例、工作区、冻结计划或历史引用的文件不可删除。孤立文件需显式清理；完整本机备份包含原字节，分享导出不包含。"+(startupCacheWarning==null?"":" "+startupCacheWarning));}
    public final class Lease implements AutoCloseable {
        private final FileInfo info;private final byte[] bytes;private boolean closed;
        Lease(FileInfo info,byte[] bytes){this.info=info;this.bytes=bytes;}
        public FileInfo info(){return info;}public byte[] bytes(){return bytes;}
        public void close(){synchronized(ApiFileStore.this){if(!closed){closed=true;leases.compute(info.fileId(),(k,n)->n==null||n<=1?null:n-1);}}}
    }
    public synchronized Lease acquire(String id){
        FileInfo f=info(id);byte[] bytes=jdbc.queryForObject("SELECT payload FROM api_file WHERE id=?",byte[].class,id);
        if(bytes==null||bytes.length!=f.bytes()||!sha(bytes).equals(f.sha256()))throw new IllegalStateException("受管文件正本校验失败；请恢复完整本机备份，不会发送损坏内容");
        Path p=path(id);try{if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=bytes.length||!sha(Files.readAllBytes(p)).equals(f.sha256()))cache(f,bytes);}catch(IOException e){throw new IllegalStateException("受管文件缓存不可用；检查目录权限后重启，数据库中的原字节仍保留",e);}
        leases.merge(id,1,Integer::sum);return new Lease(f,bytes);
    }
    private void privateDir()throws IOException{
        if(Files.isSymbolicLink(root))throw new IOException("受管目录不能是符号链接");Files.createDirectories(root);
        try{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
    }
    private Path path(String id){try{privateDir();Path p=root.resolve(validId(id)+".bin");if(Files.isSymbolicLink(p))throw new IOException("受管文件不能是符号链接");return p;}catch(IOException e){throw new IllegalStateException("受管文件路径不可用，拒绝越界读写",e);}}
    private void cache(FileInfo f,byte[] bytes){Path temp=null;try{Path p=path(f.fileId());temp=Files.createTempFile(root,".upload-",".tmp");try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}Files.write(temp,bytes);try{Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}}catch(IOException e){throw new IllegalStateException("受管文件落盘失败；检查空间或权限后重试",e);}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}}
    private boolean table(String name){return !jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name=?",String.class,name).isEmpty();}
    /** 检查明文证据和加密冻结/工作区，损坏密文时拒绝清理而不是猜测无引用。 */
    public synchronized boolean referenced(String id){
        validId(id);
        for(String table:jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'api_file'",String.class)){
            String quoted="\""+table.replace("\"","\"\"")+"\"";
            var columns=jdbc.queryForList("PRAGMA table_info("+quoted+")");var tests=new ArrayList<String>();
            for(var c:columns)if(String.valueOf(c.get("type")).toUpperCase(Locale.ROOT).contains("TEXT"))tests.add("instr(CAST(\""+c.get("name").toString().replace("\"","\"\"")+"\" AS TEXT),?)>0");
            if(!tests.isEmpty()&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+quoted+" WHERE "+String.join(" OR ",tests)+" LIMIT 1)",Boolean.class,Collections.nCopies(tests.size(),id).toArray())))return true;
        }
        ApiPlanVault vault=new ApiPlanVault(jdbc);
        for(String t:List.of("api_batch_plan","perf_run_plan","api_workspace"))if(table(t)){
            String pk=t.equals("api_batch_plan")?"batch_id":t.equals("perf_run_plan")?"run_id":"id",body=t.equals("api_batch_plan")?"sealed_plan":t.equals("perf_run_plan")?"plan_json":"payload",ns=t.equals("api_batch_plan")?"api-batch":t.equals("perf_run_plan")?"perf-run":"api-workspace";
            for(var row:jdbc.queryForList("SELECT "+pk+","+body+" FROM "+t))if(vault.decrypt(ns,row.get(pk).toString(),row.get(body).toString()).contains(id))return true;
        }
        return false;
    }
    public synchronized void delete(String id){idle();info(id);if(leases.containsKey(id))throw new IllegalArgumentException("文件正在预览、发送或下载，请等待操作结束");if(referenced(id))throw new IllegalArgumentException("文件仍被用例、工作区、冻结计划或历史引用；先移除引用并保存，不能删除");jdbc.update("DELETE FROM api_file WHERE id=?",id);try{Files.deleteIfExists(path(id));}catch(Exception ignored){/* 正本已删；缓存无读取入口，下次显式清理回收。 */}}
    public synchronized int cleanupOrphans(){idle();int count=0;for(var f:overview().files())if(!leases.containsKey(f.fileId())&&!referenced(f.fileId())){delete(f.fileId());count++;}return count;}
    /** 历史清理事务内调用，仅回收本轮候选且无剩余引用的对象。 */
    public synchronized int cleanupCandidates(Collection<String> ids){int count=0;for(String id:ids)if(!leases.containsKey(id)&&!referenced(id)){count+=jdbc.update("DELETE FROM api_file WHERE id=?",validId(id));}return count;}
    /** DB回滚后重建缓存，不把目录内容当作文件正本或可用性的依据。 */
    public synchronized void cleanupCache(){try{privateDir();}catch(IOException e){throw new IllegalStateException("受管缓存目录不可用",e);}try(var paths=Files.list(root)){Set<String> ids=new HashSet<>(jdbc.queryForList("SELECT id FROM api_file",String.class));for(Path p:paths.toList()){String name=p.getFileName().toString();if(name.matches("[a-f0-9-]{36}\\.bin")&&!ids.contains(name.substring(0,36))&&!Files.isSymbolicLink(p))Files.deleteIfExists(p);}}catch(IOException e){throw new IllegalStateException("受管缓存清理失败，原字节仍在数据库",e);}}
}
