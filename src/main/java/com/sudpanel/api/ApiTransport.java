package com.sudpanel.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** 请求级传输参数及有界响应解码；不把不完整正文交给断言形成假通过。 */
public final class ApiTransport {
    public static final int DEFAULT_LIMIT = 8 * 1024 * 1024;
    public static final int MAX_LIMIT = 64 * 1024 * 1024;
    private ApiTransport() {}
    public record Key(boolean insecure, String redirect, String proxyMode, String host, int port, Long cookieEnv, String cookieScope,String httpVersion,String certificateProfileId,long envId) {
        public Key(boolean insecure,String redirect,String proxyMode,String host,int port,Long cookieEnv,String cookieScope){this(insecure,redirect,proxyMode,host,port,cookieEnv,cookieScope,"HTTP_1_1",null,cookieEnv==null?0:cookieEnv);}
        public Key(boolean insecure, String redirect, String proxyMode, String host, int port, Long cookieEnv) { this(insecure,redirect,proxyMode,host,port,cookieEnv,null); }
        public Key withCookieScope(String scope) { return new Key(insecure,redirect,proxyMode,host,port,cookieEnv,cookieEnv==null?null:scope==null?cookieScope:scope,httpVersion,certificateProfileId,envId); }
        public Key withoutCookies(){return new Key(insecure,redirect,proxyMode,host,port,null,null,httpVersion,certificateProfileId,envId);}
        public Key neverRedirect(){return new Key(insecure,"NEVER",proxyMode,host,port,cookieEnv,cookieScope,httpVersion,certificateProfileId,envId);}
    }
    public record Decoded(String text, int wireBytes, int decodedBytes, String charset, String encoding) {}

    public static void validate(ApiRequestOptions.Transport t) {
        if (t == null) return;
        if (t.redirect() != null && !Set.of("NORMAL", "NEVER", "ALWAYS").contains(t.redirect()))
            throw new IllegalArgumentException("重定向仅支持 NORMAL / NEVER / ALWAYS");
        if(t.maxRedirects()!=null&&(t.maxRedirects()<0||t.maxRedirects()>20))throw new IllegalArgumentException("最大跳数须为0～20");
        if(t.httpVersion()!=null&&!Set.of("INHERIT","HTTP_1_1","HTTP_2").contains(t.httpVersion()))throw new IllegalArgumentException("HTTP版本仅INHERIT/HTTP_1_1/HTTP_2");
        if(t.certificateProfileId()!=null&&!t.certificateProfileId().isBlank())ApiHttpProfiles.id(t.certificateProfileId());
        if (t.proxyMode() != null && !Set.of("INHERIT", "DIRECT", "HTTP").contains(t.proxyMode()))
            throw new IllegalArgumentException("代理模式仅支持 INHERIT / DIRECT / HTTP");
        if ("HTTP".equals(t.proxyMode())) {
            if (t.proxyHost() == null || t.proxyHost().isBlank() || t.proxyHost().matches(".*[\\s/@?#].*"))
                throw new IllegalArgumentException("请填写代理主机，不含协议、路径或凭据");
            if (t.proxyPort() == null || t.proxyPort() < 1 || t.proxyPort() > 65535)
                throw new IllegalArgumentException("代理端口须为 1～65535");
        }
        if (t.cookieMode() != null && !Set.of("OFF", "ENV", "CHAIN").contains(t.cookieMode()))
            throw new IllegalArgumentException("Cookie 模式仅支持 OFF / ENV / CHAIN");
        if(t.cookieSession()!=null&&!t.cookieSession().isBlank())ApiHttpProfiles.id(t.cookieSession());
        if(t.cookieDataRows()!=null&&!Set.of("ISOLATED","SHARED").contains(t.cookieDataRows()))throw new IllegalArgumentException("数据行会话仅ISOLATED/SHARED");
        if (t.maxResponseBytes() != null && (t.maxResponseBytes() < 1024 || t.maxResponseBytes() > MAX_LIMIT))
            throw new IllegalArgumentException("响应上限须为 1024～67108864 字节");
    }

    public static int limit(ApiSpec spec) {
        var t = spec.options() == null ? null : spec.options().transport();
        return t == null || t.maxResponseBytes() == null ? DEFAULT_LIMIT : t.maxResponseBytes();
    }

    public static Key key(ApiSpec spec, long envId, String inheritedProxy, boolean explicitCookie) {
        var t = spec.options() == null ? null : spec.options().transport();
        validate(t);
        String redirect = t == null || t.redirect() == null ? "NORMAL" : t.redirect();
        String mode = t == null || t.proxyMode() == null ? "INHERIT" : t.proxyMode();
        String host = null; int port = 0;
        if ("HTTP".equals(mode)) { host = t.proxyHost(); port = t.proxyPort(); }
        else if ("INHERIT".equals(mode) && inheritedProxy != null && !inheritedProxy.isBlank()) {
            try {
                URI uri = URI.create("http://" + inheritedProxy);
                host = uri.getHost(); port = uri.getPort();
                if (host == null || port < 1 || port > 65535 || uri.getRawUserInfo() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) throw new IllegalArgumentException();
            } catch (Exception e) { throw new IllegalArgumentException("继承代理配置无效，应为 host:port 或 [IPv6]:port"); }
        }
        Long cookieEnv = t != null && Set.of("ENV","CHAIN").contains(t.cookieMode()==null?"OFF":t.cookieMode()) && !explicitCookie ? envId : null;
        String scope=t!=null&&"CHAIN".equals(t.cookieMode())?t.cookieSession():null;
        return new Key(spec.insecure(), redirect, mode, host, port, cookieEnv,scope,t==null||t.httpVersion()==null?"HTTP_1_1":t.httpVersion(),t==null?null:t.certificateProfileId(),envId);
    }

    public static void configure(HttpClient.Builder builder, Key key, ProxySelector direct, CookieManager manager) {
        builder.followRedirects(HttpClient.Redirect.NEVER).version("HTTP_2".equals(key.httpVersion())?HttpClient.Version.HTTP_2:HttpClient.Version.HTTP_1_1);
        builder.proxy(key.host() == null ? direct : ProxySelector.of(new InetSocketAddress(key.host(), key.port())));
        if (key.cookieEnv() != null) builder.cookieHandler(manager);
    }

    public static CookieManager newCookieManager() { return new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER); }

    public static HttpResponse.BodyHandler<byte[]> bounded(int limit) {
        return bounded(limit, false);
    }
    public static HttpResponse.BodyHandler<byte[]> bounded(int limit, boolean head) {
        return ApiResponseBody.bounded(limit,head);
    }

    public static Decoded decode(byte[] wire, Map<String, List<String>> headers, int limit) throws IOException {
        if (wire.length > limit) throw new IOException("响应传输超过上限 " + limit + " 字节");
        String encoding = header(headers, "Content-Encoding").trim().toLowerCase(Locale.ROOT);
        InputStream stream = new ByteArrayInputStream(wire);
        if (wire.length > 0) stream = switch (encoding) {
            case "", "identity" -> stream;
            case "gzip", "x-gzip" -> new GZIPInputStream(stream);
            case "deflate" -> new InflaterInputStream(stream);
            default -> throw new IOException("暂不支持响应压缩方式：" + encoding);
        };
        byte[] bytes;
        try (InputStream in = stream; var out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) {
                if ((long) out.size() + read > limit) throw new IOException("响应解压超过上限 " + limit + " 字节；未执行断言");
                out.write(buffer, 0, read);
            }
            bytes = out.toByteArray();
        }
        Charset charset = StandardCharsets.UTF_8;
        var match = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*[\"']?([^;\\s\"']+)").matcher(header(headers, "Content-Type"));
        if (match.find() && bytes.length > 0) {
            try { charset = Charset.forName(match.group(1)); }
            catch (Exception e) { throw new IOException("不支持响应字符集：" + match.group(1)); }
        }
        return new Decoded(new String(bytes, charset), wire.length, bytes.length, charset.name(), encoding);
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> String.join(",", e.getValue())).findFirst().orElse("");
    }
}
