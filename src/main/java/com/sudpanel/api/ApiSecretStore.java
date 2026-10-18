package com.sudpanel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Local encrypted secret references. Keychain references are read only, never create or change OS credentials. */
public final class ApiSecretStore {
    public record Summary(String id,long envId,String name,String kind,String reference,boolean enabled,String updatedAt) {}
    public record Input(String name,String kind,String value,String service,String account) {}
    @FunctionalInterface public interface KeychainReader { String read(String service,String account) throws Exception; }
    private final JdbcTemplate jdbc;
    private final ApiPlanVault vault;
    private final KeychainReader keychain;
    private final ObjectMapper json=new ObjectMapper();
    public ApiSecretStore(JdbcTemplate jdbc){this(jdbc,ApiSecretStore::readKeychain);}
    public ApiSecretStore(JdbcTemplate jdbc,KeychainReader keychain) {
        this.jdbc=jdbc;this.vault=new ApiPlanVault(jdbc);this.keychain=keychain;
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_secret_reference(id TEXT PRIMARY KEY,env_id INTEGER NOT NULL,name TEXT NOT NULL,kind TEXT NOT NULL,payload_sealed TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,updated_at TEXT NOT NULL)");
    }
    public List<Summary> list(long env) {
        return jdbc.query("SELECT id,env_id,name,kind,enabled,updated_at FROM api_secret_reference WHERE env_id=? ORDER BY updated_at DESC,id",(rs,i)->new Summary(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),"secret://"+rs.getString(1),rs.getInt(5)!=0,rs.getString(6)),env);
    }
    public Summary create(long env,Input input) {
        if(env<=0 || input==null || input.name()==null || input.name().isBlank() || input.name().length()>100)throw new IllegalArgumentException("请填写最多100字符的秘密名称");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM api_env WHERE id=?",Integer.class,env)!=1)throw new IllegalArgumentException("环境不存在");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM api_secret_reference",Integer.class)>=500)throw new IllegalArgumentException("本机秘密引用已达500条；已有引用仍可使用，请勿继续批量新建");
        String kind=input.kind()==null?"VAULT":input.kind();
        if(!List.of("VAULT","KEYCHAIN").contains(kind))throw new IllegalArgumentException("秘密来源仅支持本机加密库或已有Keychain项");
        if(kind.equals("VAULT") && (input.value()==null || input.value().isEmpty() || input.value().length()>65536))throw new IllegalArgumentException("秘密值须为1至65536字符");
        if(kind.equals("KEYCHAIN") && (invalidIdentity(input.service()) || invalidIdentity(input.account())))throw new IllegalArgumentException("请填写已有Keychain项的服务与账号（最多200字符，无控制字符）");
        String id=UUID.randomUUID().toString(),time=Instant.now().toString();
        String sealed;
        try{sealed=vault.encrypt("secret-reference",id,json.writeValueAsString(new Input(input.name(),kind,kind.equals("VAULT")?input.value():null,kind.equals("KEYCHAIN")?input.service():null,kind.equals("KEYCHAIN")?input.account():null)));}
        catch(Exception error){throw new IllegalStateException("秘密加密保存失败，请检查配对密钥",error);}
        jdbc.update("INSERT INTO api_secret_reference(id,env_id,name,kind,payload_sealed,enabled,updated_at) VALUES(?,?,?,?,?,1,?)",id,env,input.name(),kind,sealed,time);
        return new Summary(id,env,input.name(),kind,"secret://"+id,true,time);
    }
    public void enabled(long env,String id,boolean enabled) {
        uuid(id);if(jdbc.update("UPDATE api_secret_reference SET enabled=?,updated_at=? WHERE env_id=? AND id=?",enabled?1:0,Instant.now().toString(),env,id)!=1)throw new java.util.NoSuchElementException("秘密引用不存在");
    }
    public String resolve(long env,String reference) {
        if(reference==null || !reference.startsWith("secret://"))return reference;
        String id=reference.substring(9);uuid(id);
        var rows=jdbc.queryForList("SELECT env_id,payload_sealed,enabled FROM api_secret_reference WHERE id=?",id);
        if(rows.isEmpty() || ((Number)rows.getFirst().get("env_id")).longValue()!=env)throw new IllegalArgumentException("秘密引用不存在或不属于当前环境，请在当前环境配置");
        if(((Number)rows.getFirst().get("enabled")).intValue()==0)throw new IllegalArgumentException("秘密引用已停用，未发送请求");
        try{
            var input=json.readValue(vault.decrypt("secret-reference",id,(String)rows.getFirst().get("payload_sealed")),Input.class);
            String value=input.kind().equals("KEYCHAIN")?keychain.read(input.service(),input.account()):input.value();
            if(value==null || value.isEmpty() || value.length()>65536)throw new IllegalArgumentException();return value;
        }catch(Exception failure){throw new IllegalStateException("秘密引用不可读取：请检查配对密钥或在本机解锁Keychain并核对引用；不会使用空值或其它环境凭据",failure);}
    }
    public ApiVariables.VarSet resolve(long env,ApiVariables.VarSet vars) {
        return new ApiVariables.VarSet(resolveGroup(env,vars.base()),resolveGroup(env,vars.header()),resolveGroup(env,vars.body()));
    }
    public ApiVariables.VarSet resolveForRequest(long env,ApiVariables.VarSet vars,ApiSpec spec) {
        var used=ApiSecretReferences.used(spec,vars);
        java.util.function.BiFunction<List<ApiVariables.Var>,String,List<ApiVariables.Var>> selected=(group,scope)->group.stream().map(v->Boolean.TRUE.equals(v.secret())&&used.get(scope).contains(v.name())?new ApiVariables.Var(v.name(),resolve(env,v.value()),true):v).toList();
        return new ApiVariables.VarSet(selected.apply(vars.base(),"base"),selected.apply(vars.header(),"header"),selected.apply(vars.body(),"body"));
    }
    private List<ApiVariables.Var> resolveGroup(long env,List<ApiVariables.Var> vars) {
        return vars.stream().map(v->Boolean.TRUE.equals(v.secret())?new ApiVariables.Var(v.name(),resolve(env,v.value()),true):v).toList();
    }
    public java.util.Map<String,String> copy(long source,long destination) {
        var map=new java.util.LinkedHashMap<String,String>();
        for(var row:list(source)) {
            String sealed=jdbc.queryForObject("SELECT payload_sealed FROM api_secret_reference WHERE id=?",String.class,row.id());
            try{var input=json.readValue(vault.decrypt("secret-reference",row.id(),sealed),Input.class);var copy=create(destination,input);if(!row.enabled())enabled(destination,copy.id(),false);map.put(row.reference(),copy.reference());}
            catch(Exception e){throw new IllegalStateException("复制环境秘密引用失败，已中止复制",e);}
        }
        return map;
    }
    private static boolean invalidIdentity(String value){return value==null||value.isBlank()||value.length()>200||value.chars().anyMatch(Character::isISOControl);}
    private static void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("秘密引用须为标准UUID");}}
    private static String readKeychain(String service,String account) throws Exception {
        if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("mac"))throw new IllegalStateException("当前系统没有macOS Keychain");
        Process process=new ProcessBuilder("/usr/bin/security","find-generic-password","-s",service,"-a",account,"-w").start();
        var out=new java.util.concurrent.CompletableFuture<byte[]>();var err=new java.util.concurrent.CompletableFuture<byte[]>();
        Thread.ofVirtual().start(()->{try{out.complete(process.getInputStream().readNBytes(262145));}catch(Exception e){out.completeExceptionally(e);}});
        Thread.ofVirtual().start(()->{try{err.complete(process.getErrorStream().readNBytes(4096));}catch(Exception e){err.completeExceptionally(e);}});
        try{
            if(!process.waitFor(3,TimeUnit.SECONDS))throw new IllegalStateException("Keychain读取超时");
            byte[] bytes=out.get(1,TimeUnit.SECONDS);if(process.exitValue()!=0||bytes.length>262144)throw new IllegalStateException("Keychain项不可用");
            String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();return value.endsWith("\n")?value.substring(0,value.length()-1):value;
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
}
