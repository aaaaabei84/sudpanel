package com.sudpanel.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.sqlite.SQLiteConnection;

/** 备份格式和停机恢复共用的小型文件层；不会复制活动SQLite主文件。 */
public final class BackupFiles {
    public static final String KEY_NAME=".sudpanel-plan-v1.key";
    static final ObjectMapper JSON=new ObjectMapper();
    static final long MAX_BYTES=256L*1024*1024;
    private BackupFiles(){}
    public static Path root(Path db){return db.toAbsolutePath().normalize().getParent().resolve(".sudpanel-maintenance");}
    public static Path key(Path db){return db.toAbsolutePath().normalize().getParent().resolve(KEY_NAME);}
    public static String sha(Path path)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)d.update(buffer,0,n);}return HexFormat.of().formatHex(d.digest());}
    static void privateFile(Path p)throws Exception{try{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
    static void privateDir(Path p)throws Exception{Files.createDirectories(p);try{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}}
    static void atomic(Path from,Path to)throws Exception{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    static void json(Path p,Object value)throws Exception{Path tmp=p.resolveSibling(p.getFileName()+".tmp");JSON.writeValue(tmp.toFile(),value);privateFile(tmp);try(var c=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){c.force(true);}atomic(tmp,p);}
    static Map<String,Object> map(Path p)throws Exception{return JSON.readValue(p.toFile(),JSON.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));}
    public static void snapshot(Connection connection,Path dest)throws Exception{
        Files.deleteIfExists(dest);
        SQLiteConnection sqlite=connection.unwrap(SQLiteConnection.class);
        int code=sqlite.getDatabase().backup("main",dest.toString(),null,20,100,128);
        if(code!=0)throw new IllegalStateException("SQLite备份失败，状态="+code);
        privateFile(dest);validate(dest);
    }
    public static void validate(Path db)throws Exception{
        if(Files.isSymbolicLink(db)||Files.size(db)<100||Files.size(db)>MAX_BYTES)throw new IllegalArgumentException("SQLite文件大小或路径无效");
        try(Connection c=connect(db);Statement s=c.createStatement()){
            try(ResultSet r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!"ok".equals(r.getString(1))||r.next())throw new IllegalArgumentException("SQLite完整性校验失败");}
            var names=counts(c);for(String required:List.of("api_env","api_case","api_exec"))if(!names.containsKey(required))throw new IllegalArgumentException("不是可识别的平台数据库，缺少"+required);
            if(names.containsKey("api_file"))try(var files=c.createStatement();var r=files.executeQuery("SELECT id,kind,filename,content_type,bytes,sha256,payload FROM api_file")){
                long total=0;int count=0;while(r.next()){
                    com.sudpanel.api.ApiFileStore.validId(r.getString(1));com.sudpanel.api.ApiFileStore.filename(r.getString(3));com.sudpanel.api.ApiFileStore.mime(r.getString(4));
                    String kind=r.getString(2);if(!Set.of("UPLOAD","RESPONSE","PARTIAL").contains(kind))throw new IllegalArgumentException("备份受管文件类型无效");
                    long size=r.getLong(5);if(kind.equals("UPLOAD")&&size>com.sudpanel.api.ApiFileStore.UPLOAD_LIMIT)throw new IllegalArgumentException("备份上传文件超限");total+=size;if(size<0||size>com.sudpanel.api.ApiFileStore.BODY_LIMIT||total>com.sudpanel.api.ApiFileStore.STORE_LIMIT||++count>2000)throw new IllegalArgumentException("备份受管文件容量或长度无效");
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");long read=0;try(var in=r.getBinaryStream(7)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){read+=n;if(read>size)throw new IllegalArgumentException("备份受管文件长度不匹配");digest.update(b,0,n);}}
                    if(read!=size||!HexFormat.of().formatHex(digest.digest()).equals(r.getString(6)))throw new IllegalArgumentException("备份受管文件SHA256不匹配");
                }
            }
        }
    }
    static Connection connect(Path p)throws Exception{Class.forName("org.sqlite.JDBC");return DriverManager.getConnection("jdbc:sqlite:file:"+p.toAbsolutePath().toUri().getRawPath()+"?mode=ro");}
    public static Map<String,Long> counts(Connection c)throws Exception{
        var counts=new LinkedHashMap<String,Long>();var names=new ArrayList<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")){while(r.next())names.add(r.getString(1));}
        for(String n:names)try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+quote(n))){r.next();counts.put(n,r.getLong(1));}return counts;
    }
    static String quote(String name){return "\""+name.replace("\"","\"\"")+"\"";}
    static String schema(Connection c)throws Exception{
        var s=new StringBuilder();try(var st=c.createStatement();var rs=st.executeQuery("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name")){while(rs.next())for(int i=1;i<=4;i++)s.append(rs.getString(i)).append('\n');}return s.toString();
    }
    public static String fingerprint(Path p)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(Connection c=connect(p)){
            // 所有表从同一读事务取值；按数据库排序流式哈希，避免把整库证据装入Java堆。
            try(var st=c.createStatement()){st.execute("PRAGMA temp_store=FILE");}c.setAutoCommit(false);
            digest.update(schema(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var names=new ArrayList<>(counts(c).keySet());if(table(c,"sqlite_sequence"))names.add("sqlite_sequence");
            for(String table:names){
                digest.update(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var keys=new TreeMap<Integer,String>();var columns=new ArrayList<String>();
                try(var st=c.createStatement();var info=st.executeQuery("PRAGMA table_info("+quote(table)+")")){
                    while(info.next()){String column=info.getString("name");columns.add(quote(column));int pk=info.getInt("pk");if(pk>0)keys.put(pk,quote(column));}
                }
                String order=String.join(",",keys.isEmpty()?columns:new ArrayList<>(keys.values()));
                try(var st=c.createStatement();var r=st.executeQuery("SELECT * FROM "+quote(table)+" ORDER BY "+order)){
                    int n=r.getMetaData().getColumnCount();while(r.next()){
                        var values=new ArrayList<Object>();for(int i=1;i<=n;i++){Object v=r.getObject(i);values.add(v instanceof byte[] bytes?Map.of("blob",Base64.getEncoder().encodeToString(bytes)):v);}
                        digest.update(JSON.writeValueAsBytes(values));digest.update((byte)0);
                    }
                }
            }
        }return HexFormat.of().formatHex(digest.digest());
    }
    static boolean table(Connection c,String name)throws Exception{try(var s=c.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")){s.setString(1,name);try(var r=s.executeQuery()){r.next();return r.getInt(1)>0;}}}
    static void validatePlans(Path db,Path key)throws Exception{
        if(key!=null&&Files.isSymbolicLink(key))throw new IllegalArgumentException("冻结计划密钥不能是符号链接");
        if(key!=null&&Files.exists(key)&&(!Files.isRegularFile(key)||Files.size(key)!=32))throw new IllegalArgumentException("冻结计划密钥长度无效");
        byte[] keyBytes=key!=null&&Files.isRegularFile(key)?Files.readAllBytes(key):null;
        try(Connection c=connect(db)){
            // Each row authenticates its real vault namespace and AAD, including assets outside plan tables.
            for(String[] asset:List.of(
                    new String[]{"api_batch_plan","batch_id","sealed_plan","api-batch"},
                    new String[]{"perf_run_plan","run_id","plan_json","perf-run"},
                    new String[]{"api_workspace","id","payload","api-workspace"},
                    new String[]{"api_secret_reference","id","payload_sealed","secret-reference"},
                    new String[]{"api_http_profile","env_id || '.' || id","config_sealed","http-advanced-profile"},
                    new String[]{"perf_run_conditions","run_id","conditions_json","perf-conditions"}))if(table(c,asset[0])){
                try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT "+asset[1]+","+asset[2]+" FROM "+asset[0])){
                    while(rows.next())validateSealed(keyBytes,asset[3],rows.getString(1),rows.getString(2));
                }
            }
            if(table(c,"api_exec")&&column(c,"api_exec","metadata")){
                try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT metadata FROM api_exec WHERE metadata IS NOT NULL")){
                    while(rows.next())validateMask(jsonOrNull(rows.getString(1)),keyBytes);
                }
            }
            for(String source:List.of("api_baseline","perf_step_exec"))if(table(c,source)&&column(c,source,"exec_json")){
                try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT exec_json FROM "+source+" WHERE exec_json IS NOT NULL")){
                    while(rows.next()){
                        var execution=jsonOrNull(rows.getString(1));
                        if(execution!=null&&execution.isObject()){
                            var metadata=execution.get("metadata");
                            validateMask(metadata!=null&&metadata.isTextual()?jsonOrNull(metadata.asText()):metadata,keyBytes);
                        }
                    }
                }
            }
        }
    }
    private static boolean column(Connection connection,String table,String column)throws Exception{
        try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA table_info("+quote(table)+")")){
            while(rows.next())if(column.equals(rows.getString("name")))return true;
        }return false;
    }
    private static com.fasterxml.jackson.databind.JsonNode jsonOrNull(String text){
        if(text==null||text.isBlank())return null;
        try{return JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);}
        catch(Exception legacy){return null;} // Broken legacy evidence without a parsed secretMask is not ciphertext.
    }
    private static void validateMask(com.fasterxml.jackson.databind.JsonNode metadata,byte[] key)throws Exception{
        if(metadata==null||!metadata.isObject()||!metadata.has("secretMask"))return;
        var mask=metadata.get("secretMask");
        if(!mask.isObject()||!mask.path("id").isTextual()||!mask.path("sealed").isTextual())throw new IllegalArgumentException("历史敏感标记密文无效，不能恢复");
        validateSealed(key,"exec-mask",mask.path("id").asText(),mask.path("sealed").asText());
    }
    private static void validateSealed(byte[] key,String namespace,String id,String sealed)throws Exception{
        if(key==null)throw new IllegalArgumentException("含冻结计划或加密资产的备份缺少配对密钥，不能恢复");
        try{
            if(id==null||!id.matches("[A-Za-z0-9_.-]{1,100}")||sealed==null)throw new IllegalArgumentException();
            String[] value=sealed.split("\\.");if(value.length!=3||!value[0].equals("1"))throw new IllegalArgumentException();
            byte[] nonce=Base64.getDecoder().decode(value[1]);if(nonce.length!=12)throw new IllegalArgumentException();
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));
            cipher.updateAAD(("sudpanel-plan-v1:"+namespace+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cipher.doFinal(Base64.getDecoder().decode(value[2]));
        }catch(Exception corrupt){throw new IllegalArgumentException("冻结计划或加密资产与密钥不匹配或密文损坏");}
    }
}
