package com.sudpanel.api;

import java.net.URI;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** RFC7616的明确子集：MD5/SHA-256，qop=auth或旧无qop；不接受auth-int、sess或无限stale重试。 */
public final class ApiDigestAuth {
    private ApiDigestAuth() {}
    public static String authorization(String challenge,String method,URI uri,String username,String password,String expectedAlgorithm,String expectedQop) {
        if(challenge==null||!challenge.regionMatches(true,0,"Digest ",0,7))throw new IllegalArgumentException("服务器未返回受支持Digest challenge");
        var fields=new LinkedHashMap<String,String>();var matcher=Pattern.compile("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^,\\s]+))").matcher(challenge.substring(7));
        while(matcher.find())fields.put(matcher.group(1).toLowerCase(Locale.ROOT),matcher.group(2)!=null?matcher.group(2).replace("\\\"","\"").replace("\\\\","\\"):matcher.group(3));
        String realm=fields.get("realm"),nonce=fields.get("nonce"),algorithm=fields.getOrDefault("algorithm","MD5").toUpperCase(Locale.ROOT);
        if(realm==null||nonce==null||nonce.isBlank()||!Set.of("MD5","SHA-256").contains(algorithm)||!algorithm.equals(expectedAlgorithm))throw new IllegalArgumentException("Digest challenge算法或realm/nonce不匹配；仅MD5/SHA-256");
        String qop=fields.get("qop");boolean auth=qop!=null&&Arrays.stream(qop.split(",")).anyMatch(s->s.trim().equals("auth"));
        if("auth".equals(expectedQop)&&!auth||"none".equals(expectedQop)&&qop!=null)throw new IllegalArgumentException("Digest qop不匹配；仅auth或明确无qop，未执行其他握手");
        Charset charset=StandardCharsets.ISO_8859_1;
        if(fields.containsKey("charset")){if(!fields.get("charset").equalsIgnoreCase("UTF-8"))throw new IllegalArgumentException("Digest charset仅支持UTF-8或默认ISO-8859-1");charset=StandardCharsets.UTF_8;}
        if(!charset.newEncoder().canEncode(username+password+realm))throw new IllegalArgumentException("Digest字符超出服务器声明编码");
        String target=uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath();if(uri.getRawQuery()!=null)target+="?"+uri.getRawQuery();
        String cnonce=UUID.randomUUID().toString().replace("-",""),nc="00000001";
        String ha1=hash(algorithm,username+":"+realm+":"+password,charset),ha2=hash(algorithm,method+":"+target,charset);
        String response=hash(algorithm,ha1+":"+nonce+":"+(auth?nc+":"+cnonce+":auth:":"")+ha2,charset);
        String result="Digest username="+quote(username)+", realm="+quote(realm)+", nonce="+quote(nonce)+", uri="+quote(target)+", algorithm="+algorithm+", response="+quote(response);
        if(auth)result+=", qop=auth, nc="+nc+", cnonce="+quote(cnonce);if(fields.containsKey("opaque"))result+=", opaque="+quote(fields.get("opaque"));return result;
    }
    private static String hash(String algorithm,String value,Charset charset){try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(charset)));}catch(Exception e){throw new IllegalArgumentException("Digest算法不可用");}}
    private static String quote(String value){if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException("Digest challenge包含非法换行");return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
