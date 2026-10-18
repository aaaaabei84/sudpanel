package com.sudpanel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 环境级不可变profile。所有证书/口令/令牌源仅在配对本机vault中；公共列表不返回秘密。 */
@Service
public class ApiHttpProfiles {
    public record Input(String kind,String name,String host,Integer port,String caPem,String pkcs12Base64,String password,
                        String tokenUrl,String grant,String clientId,String clientSecret,String refreshToken,String scope,String audience,
                        String username,String algorithm,String qop,String tokenAuthMethod) {
        public Input(String kind,String name,String host,Integer port,String caPem,String pkcs12Base64,String password,String tokenUrl,String grant,String clientId,String clientSecret,String refreshToken,String scope,String audience,String username,String algorithm,String qop){this(kind,name,host,port,caPem,pkcs12Base64,password,tokenUrl,grant,clientId,clientSecret,refreshToken,scope,audience,username,algorithm,qop,null);}
    }
    private record Profile(String id,long envId,Input config,String createdAt) {}
    private record Token(String value,Instant expires,String refreshToken) {}
    private final JdbcTemplate jdbc;private final ApiRepository repo;private final ApiPlanVault vault;
    private final ObjectMapper json=new ObjectMapper();private final Map<String,Token> tokens=new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock[] tokenLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new java.util.concurrent.locks.ReentrantLock()).toArray(java.util.concurrent.locks.ReentrantLock[]::new);
    public ApiHttpProfiles(JdbcTemplate jdbc,ApiRepository repo){this.jdbc=jdbc;this.repo=repo;this.vault=new ApiPlanVault(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_http_profile(id TEXT PRIMARY KEY,env_id INTEGER NOT NULL,kind TEXT NOT NULL,name TEXT NOT NULL,config_sealed TEXT NOT NULL,updated_at TEXT NOT NULL)");}
    public static String id(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}catch(Exception e){throw new IllegalArgumentException("Profile引用必须是标准小写UUID");}}
    public synchronized Map<String,Object> create(long env,Input input){
        repo.getEnv(env);validate(input);if(jdbc.queryForObject("SELECT COUNT(*) FROM api_http_profile WHERE env_id=?",Long.class,env)>=64)throw new IllegalArgumentException("每环境最多64个HTTP profile，请先删除不用的条目");
        String key=UUID.randomUUID().toString(),now=Instant.now().toString();
        try{String plain=json.writeValueAsString(input);String sealed=vault.encrypt("http-advanced-profile",env+"."+key,plain);
            jdbc.update("INSERT INTO api_http_profile VALUES(?,?,?,?,?,?)",key,env,input.kind(),input.name().trim(),sealed,now);
            return summary(new Profile(key,env,input,now));
        }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("HTTP profile保存失败");}
    }
    public List<Map<String,Object>> list(long env){repo.getEnv(env);return jdbc.query("SELECT id,kind,name,updated_at FROM api_http_profile WHERE env_id=? ORDER BY updated_at,id",(rs,n)->{
        String key=rs.getString("id");try{var out=summary(load(env,key));var token=tokens.get(env+"."+key);if("OAUTH2".equals(out.get("kind"))){out.put("tokenCached",token!=null&&Instant.now().isBefore(token.expires));if(token!=null)out.put("tokenExpiresAt",token.expires.toString());}return out;}catch(RuntimeException failure){return new LinkedHashMap<String,Object>(Map.of("id",key,"envId",env,"kind",rs.getString("kind"),"name",rs.getString("name"),"unavailable",true,"diagnostic","本机密钥或profile不可读取，请恢复配对备份"));}},env);}
    public void delete(long env,String key){repo.getEnv(env);jdbc.update("DELETE FROM api_http_profile WHERE env_id=? AND id=?",env,id(key));tokens.remove(env+"."+key);}
    public void clearTokens(long env){tokens.keySet().removeIf(k->k.startsWith(env+"."));}
    private Profile load(long env,String key){id(key);var rows=jdbc.query("SELECT config_sealed,updated_at FROM api_http_profile WHERE id=? AND env_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},key,env);
        if(rows.isEmpty())throw new IllegalArgumentException("HTTP profile不存在或属于其他环境，请重新选择");
        try{return new Profile(key,env,json.readValue(vault.decrypt("http-advanced-profile",env+"."+key,rows.getFirst()[0]),Input.class),rows.getFirst()[1]);}
        catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("HTTP profile不可读取，请恢复数据库与配对密钥；不会使用其他凭据");}}
    public void validateTarget(long env,String key,String kind,URI target){if(key==null||key.isBlank())throw new IllegalArgumentException(kind+"须选择当前环境profile");Profile p=load(env,key);
        if(!kind.equals(p.config.kind()))throw new IllegalArgumentException("Profile类型不匹配，需要"+kind);
        int port=target.getPort()<0?("https".equalsIgnoreCase(target.getScheme())?443:80):target.getPort();
        if(target.getHost()==null||!p.config.host().equalsIgnoreCase(target.getHost())||p.config.port()!=port)throw new IllegalArgumentException("Profile域名/端口不匹配：仅允许"+p.config.host()+":"+p.config.port());
        if("TLS".equals(kind)&&!"https".equalsIgnoreCase(target.getScheme()))throw new IllegalArgumentException("证书profile仅用于HTTPS，未降级为HTTP");}
    public SSLContext tls(long env,String key,URI target){validateTarget(env,key,"TLS",target);try{return context(load(env,key).config);}catch(Exception e){throw new IllegalArgumentException("证书profile验证失败：证书过期、口令或密钥不匹配；请查看指纹/到期摘要");}}
    public Input auth(long env,String key,String kind,URI target){validateTarget(env,key,kind,target);return load(env,key).config;}
    public String oauth(long env,String key,URI target,long deadline)throws Exception{return oauth(env,key,target,deadline,f->{},()->false);}
    public String oauth(long env,String key,URI target,long deadline,java.util.function.Consumer<java.util.concurrent.CompletableFuture<?>> future,java.util.function.BooleanSupplier cancelled)throws Exception{
        var lock=tokenLocks[Math.floorMod((env+"."+key).hashCode(),tokenLocks.length)];boolean acquired=false;
        try{while(!acquired){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("已取消OAuth令牌等待，未发业务请求");long left=deadline-System.nanoTime();if(left<=0)throw new java.net.http.HttpTimeoutException("OAuth令牌等待超过总时限");acquired=lock.tryLock(Math.min(left,java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25)),java.util.concurrent.TimeUnit.NANOSECONDS);}return oauthLocked(env,key,target,deadline,future,cancelled);}
        finally{if(acquired)lock.unlock();}
    }
    private String oauthLocked(long env,String key,URI target,long deadline,java.util.function.Consumer<java.util.concurrent.CompletableFuture<?>> observer,java.util.function.BooleanSupplier cancelled)throws Exception{
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("已取消OAuth令牌等待，未发业务请求");
        Input p=auth(env,key,"OAUTH2",target);String cacheKey=env+"."+key;Token previous=tokens.get(cacheKey);if(previous!=null&&Instant.now().isBefore(previous.expires))return previous.value;
        boolean basic="BASIC".equals(p.tokenAuthMethod());var fields=new LinkedHashMap<String,String>();fields.put("grant_type",p.grant());if(!basic){fields.put("client_id",p.clientId());if(nonblank(p.clientSecret()))fields.put("client_secret",p.clientSecret());}
        if("refresh_token".equals(p.grant()))fields.put("refresh_token",previous!=null&&nonblank(previous.refreshToken)?previous.refreshToken:p.refreshToken());
        if(nonblank(p.scope()))fields.put("scope",p.scope());if(nonblank(p.audience()))fields.put("audience",p.audience());
        String body=String.join("&",fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).toList());
        long left=Math.min(10000,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));if(left<=0)throw new java.net.http.HttpTimeoutException("OAuth令牌请求超过总时限");
        var builder=HttpRequest.newBuilder(URI.create(p.tokenUrl())).timeout(Duration.ofMillis(left)).header("Content-Type","application/x-www-form-urlencoded");if(basic)builder.header("Authorization","Basic "+Base64.getEncoder().encodeToString((encode(p.clientId())+":"+encode(p.clientSecret())).getBytes(StandardCharsets.UTF_8)));HttpRequest request=builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(left)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}}).build();
        var pending=client.sendAsync(request,ApiTransport.bounded(256*1024));try{observer.accept(pending);}catch(RuntimeException rejected){pending.cancel(true);throw rejected;}
        HttpResponse<byte[]> response;
        try { response=pending.get(left,java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) { pending.cancel(true);throw new java.net.http.HttpTimeoutException("OAuth令牌响应超过总时限"); }
        catch (InterruptedException interrupted) { pending.cancel(true);Thread.currentThread().interrupt();throw interrupted; }
        catch (java.util.concurrent.ExecutionException failed) { throw new java.io.IOException("OAuth令牌请求失败，未重发业务请求",failed.getCause()); }
        if(response.statusCode()!=200)throw new IllegalArgumentException("OAuth令牌服务拒绝授权（HTTP "+response.statusCode()+"），未重发业务请求");
        var result=json.readTree(response.body());if(!result.path("access_token").isTextual())throw new IllegalArgumentException("OAuth access_token须为文本");String access=result.path("access_token").asText();
        if(access.isBlank()||access.indexOf('\r')>=0||access.indexOf('\n')>=0||access.length()>65536||!result.path("token_type").asText("Bearer").equalsIgnoreCase("Bearer"))throw new IllegalArgumentException("OAuth返回缺少有效Bearer令牌");
        if(result.has("expires_in")&&(!result.path("expires_in").isIntegralNumber()||!result.path("expires_in").canConvertToLong()))throw new IllegalArgumentException("OAuth expires_in须为整数秒");
        long ttl=result.path("expires_in").asLong(300);if(ttl<1||ttl>86400)throw new IllegalArgumentException("OAuth expires_in须为1～86400秒");
        String refresh=previous!=null?previous.refreshToken:p.refreshToken();
        if(result.hasNonNull("refresh_token")){
            if(!result.path("refresh_token").isTextual()||result.path("refresh_token").asText().isBlank()||result.path("refresh_token").asText().length()>65536)throw new IllegalArgumentException("OAuth refresh_token须为非空文本且最多65536字符");
            refresh=result.path("refresh_token").asText();
            if(!Objects.equals(refresh,p.refreshToken())){
                Input rotated=new Input(p.kind(),p.name(),p.host(),p.port(),p.caPem(),p.pkcs12Base64(),p.password(),p.tokenUrl(),p.grant(),p.clientId(),p.clientSecret(),refresh,p.scope(),p.audience(),p.username(),p.algorithm(),p.qop(),p.tokenAuthMethod());
                String sealed=vault.encrypt("http-advanced-profile",cacheKey,json.writeValueAsString(rotated));
                if(jdbc.update("UPDATE api_http_profile SET config_sealed=? WHERE id=? AND env_id=?",sealed,key,env)!=1)throw new IllegalStateException("OAuth profile在获取令牌时已删除，未发送业务请求");
            }
        }
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("已取消OAuth令牌等待，未发业务请求");synchronized(tokens){if(tokens.size()>=128&&!tokens.containsKey(cacheKey))tokens.remove(tokens.keySet().iterator().next());tokens.put(cacheKey,new Token(access,Instant.now().plusSeconds(Math.max(1,ttl-Math.min(30,ttl/10))),refresh));}return access;
    }
    public void invalidateToken(long env,String key){tokens.computeIfPresent(env+"."+key,(k,t)->new Token("",Instant.EPOCH,t.refreshToken));}
    private static String encode(String s){return URLEncoder.encode(s==null?"":s,StandardCharsets.UTF_8);}
    private static boolean nonblank(String s){return s!=null&&!s.isBlank();}
    private static void validate(Input p){if(p==null||p.kind()==null||!Set.of("TLS","OAUTH2","DIGEST").contains(p.kind()))throw new IllegalArgumentException("Profile类型仅TLS/OAUTH2/DIGEST");
        for(String value:new String[]{p.password(),p.clientId(),p.clientSecret(),p.refreshToken(),p.scope(),p.audience(),p.username(),p.tokenUrl()})if(value!=null&&value.length()>65536)throw new IllegalArgumentException("单凭据/URL字段最多65536字符");
        if(!nonblank(p.name())||p.name().length()>80||!nonblank(p.host())||p.host().matches(".*[\\s/@?#].*")||p.port()==null||p.port()<1||p.port()>65535)throw new IllegalArgumentException("请填写名称、精确域名和1～65535端口");
        if("TLS".equals(p.kind())){if(!nonblank(p.caPem())&&!nonblank(p.pkcs12Base64()))throw new IllegalArgumentException("至少提供CA或PKCS12证书");try{context(p);}catch(Exception e){throw new IllegalArgumentException("证书解析失败、已过期或PKCS12口令不匹配");}}
        if("OAUTH2".equals(p.kind())){if(p.tokenAuthMethod()!=null&&!Set.of("BODY","BASIC").contains(p.tokenAuthMethod()))throw new IllegalArgumentException("OAuth客户端认证仅BODY或BASIC");if(p.grant()==null||!Set.of("client_credentials","refresh_token").contains(p.grant()))throw new IllegalArgumentException("只支持client_credentials/refresh_token；Authorization Code/PKCE尚不支持");
            URI u;try{u=URI.create(p.tokenUrl());}catch(Exception e){throw new IllegalArgumentException("OAuth token URL无效");}
            if(u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||!("https".equals(u.getScheme())||"http".equals(u.getScheme())&&Set.of("127.0.0.1","localhost","::1","[::1]").contains(u.getHost())))throw new IllegalArgumentException("OAuth token URL须HTTPS；HTTP仅允许本机回环mock");
            if(!nonblank(p.clientId())||"client_credentials".equals(p.grant())&&!nonblank(p.clientSecret())||"refresh_token".equals(p.grant())&&!nonblank(p.refreshToken()))throw new IllegalArgumentException("OAuth凭据或refresh token未填写");}
        if("DIGEST".equals(p.kind())&&(!nonblank(p.username())||!nonblank(p.password())||p.algorithm()==null||!Set.of("MD5","SHA-256").contains(p.algorithm())||p.qop()==null||!Set.of("auth","none").contains(p.qop())))throw new IllegalArgumentException("Digest须用户名/口令；算法MD5或SHA-256，qop仅auth或none");
    }
    private static List<X509Certificate> cas(Input p)throws Exception{if(!nonblank(p.caPem()))return List.of();if(p.caPem().length()>2_000_000)throw new IllegalArgumentException();var certs=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(p.caPem().getBytes(StandardCharsets.US_ASCII)));var out=new ArrayList<X509Certificate>();for(var c:certs){var x=(X509Certificate)c;x.checkValidity();out.add(x);}if(out.isEmpty())throw new IllegalArgumentException();return out;}
    private static KeyStore keys(Input p)throws Exception{if(!nonblank(p.pkcs12Base64()))return null;if(p.pkcs12Base64().length()>2_796_204)throw new IllegalArgumentException();byte[] bytes=Base64.getDecoder().decode(p.pkcs12Base64());if(bytes.length>2*1024*1024)throw new IllegalArgumentException();KeyStore store=KeyStore.getInstance("PKCS12");store.load(new ByteArrayInputStream(bytes),p.password()==null?new char[0]:p.password().toCharArray());boolean found=false;for(var a=store.aliases();a.hasMoreElements();){String alias=a.nextElement();if(store.isKeyEntry(alias)){found=true;((X509Certificate)store.getCertificate(alias)).checkValidity();store.getKey(alias,p.password()==null?new char[0]:p.password().toCharArray());}}if(!found)throw new IllegalArgumentException();return store;}
    private static SSLContext context(Input p)throws Exception{var certificates=cas(p);TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());KeyStore trust=null;if(!certificates.isEmpty()){trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);for(int i=0;i<certificates.size();i++)trust.setCertificateEntry("ca-"+i,certificates.get(i));}tm.init(trust);
        KeyManager[] km=null;var keys=keys(p);if(keys!=null){var factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(keys,p.password()==null?new char[0]:p.password().toCharArray());km=factory.getKeyManagers();}
        SSLContext context=SSLContext.getInstance("TLS");context.init(km,tm.getTrustManagers(),null);return context;}
    private static Map<String,Object> summary(Profile p){var out=new LinkedHashMap<String,Object>();Input c=p.config;out.put("id",p.id);out.put("envId",p.envId);out.put("kind",c.kind());out.put("name",c.name());out.put("host",c.host());out.put("port",c.port());out.put("createdAt",p.createdAt);out.put("persistent",true);out.put("hasSecret",nonblank(c.password())||nonblank(c.clientSecret())||nonblank(c.refreshToken()));
        if("TLS".equals(c.kind())){var list=new ArrayList<Map<String,String>>();try{var certs=new ArrayList<>(cas(c));var ks=keys(c);if(ks!=null)for(var a=ks.aliases();a.hasMoreElements();){var cert=ks.getCertificate(a.nextElement());if(cert instanceof X509Certificate x)certs.add(x);}for(var cert:certs)list.add(Map.of("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())),"expiresAt",cert.getNotAfter().toInstant().toString(),"subject",cert.getSubjectX500Principal().getName()));out.put("certificates",list);}catch(Exception e){out.put("diagnostic","证书已过期或不可读取，请重新导入");}}
        if("OAUTH2".equals(c.kind())){out.put("grant",c.grant());out.put("tokenAuthMethod",c.tokenAuthMethod()==null?"BODY":c.tokenAuthMethod());out.put("cache","进程内最多128个，重启清空；授权失败不重发业务写请求");}
        if("DIGEST".equals(c.kind())){out.put("algorithm",c.algorithm());out.put("qop",c.qop());}return out;}
}
