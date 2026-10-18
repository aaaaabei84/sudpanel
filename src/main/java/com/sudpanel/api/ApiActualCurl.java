package com.sudpanel.api;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 只生成POSIX字符串，绝不执行shell；以已组装应用报文导出，不猜测HTTP客户端追加的wire头。 */
public final class ApiActualCurl {
    private ApiActualCurl() {}
    public record Export(String curl,List<String> warnings,String source,boolean complete,String generatedAt) {}
    public static Export prepared(ApiRequestEngine.Built built,boolean insecure,boolean includeSecrets) {
        if(built.wireBody()!=null)throw new IllegalArgumentException("文件正文不能完整表示为文本cURL；请在文件管理中下载原字节后使用--data-binary/@file自行构造，平台不会生成失真的命令");
        var warnings=new ArrayList<String>();warnings.add("这是导出时新组装的应用请求，动态值已固定；签名可能过期，导出不会发送请求");
        warnings.add("底层Host/Content-Length等自动头与ENV Cookie不在应用快照中；cURL不会复用平台Cookie会话、代理或重定向策略");
        return render(built.method(),built.url(),built.headerRows(),built.body(),insecure,includeSecrets,"PREPARED",true,warnings);
    }
    public static Export history(ApiExec exec,boolean includeSecrets) {
        if(exec==null||exec.reqHeaders()==null)throw new IllegalArgumentException("此记录没有实际请求头快照，不能生成实际请求cURL");
        var warnings=new ArrayList<String>();boolean known=false;
        try {var metadata=exec.metadata()==null?null:new ObjectMapper().readTree(exec.metadata());
            if(metadata!=null&&java.util.Set.of("MULTIPART","BINARY").contains(metadata.path("requestBodyMode").asText()))throw new IllegalArgumentException("此记录为文件正文，不能导出完整文本cURL；原字节请从受管文件下载");
            if(metadata!=null&&metadata.path("requestBodyTruncated").asBoolean())throw new IllegalArgumentException("历史请求体已截断，不能导出完整请求");
            known=metadata!=null&&metadata.has("requestBodyTruncated")&&metadata.get("requestBodyTruncated").isBoolean();
        }catch(IllegalArgumentException e){throw e;}catch(Exception ignored){}
        if(!known&&exec.reqBody()!=null&&exec.reqBody().contains("…（已截断，原文 "))throw new IllegalArgumentException("历史请求体已截断，不能导出完整请求");
        var headers=new ArrayList<ApiRequestOptions.HeaderRow>();String[] lines=exec.reqHeaders().split("\\r?\\n",-1);
        for(int i=1;i<lines.length;i++){String line=lines[i];if(line.isBlank()||line.startsWith("（")||line.startsWith("请求体（"))break;int colon=line.indexOf(':');if(colon<=0)throw new IllegalArgumentException("历史请求头快照格式无法识别");headers.add(new ApiRequestOptions.HeaderRow(line.substring(0,colon).trim(),line.substring(colon+1).stripLeading(),true));}
        if(!known)warnings.add("旧记录未标记请求体完整性，无法确认是否完整；导出不补猜遗漏内容");
        if(exec.httpStatus()==null)warnings.add("此记录没有HTTP响应，不能据此确认请求是否到达业务服务器");
        warnings.add("历史签名/令牌可能已过期；这是应用请求快照，底层自动头、ENV Cookie及重定向过程不属于完整wire报文");
        warnings.add("历史未保存完整传输策略，cURL采用自身默认TLS、代理和重定向设置；执行前请核对");
        return render(exec.method(),exec.url(),headers,exec.reqBody(),false,includeSecrets,"HISTORY",known,warnings);
    }
    private static Export render(String method,String url,List<ApiRequestOptions.HeaderRow> headers,String body,boolean insecure,boolean secrets,String source,boolean complete,List<String>warnings) {
        if((url!=null&&url.indexOf(0)>=0)||(body!=null&&body.indexOf(0)>=0)||headers.stream().anyMatch(e->e.name().indexOf(0)>=0||e.value().indexOf(0)>=0))throw new IllegalArgumentException("请求含NUL字节，不能以POSIX命令参数完整表示");
        if(method==null||!ApiRequestEngine.METHODS.contains(method)||url==null)throw new IllegalArgumentException("应用请求的方法或URL不完整");
        if(!secrets){url=redact(url);warnings.add("默认替换全部请求头值、查询参数值与整个正文，不能直接复放；URL路径和主机名仍需分享前检查");}
        StringBuilder command=new StringBuilder("curl --globoff --http1.1 --request ").append(quote(method)).append(" --url ").append(quote(url));
        if(insecure)command.append(" \\\n  --insecure");
        headers.forEach(row->{String name=row.name(),value=row.value();command.append(" \\\n  --header ").append(quote(secrets&&value.isEmpty()?name+";":name+": "+(secrets?value:"<REDACTED>")));});
        if(body!=null)command.append(" \\\n  --data-raw ").append(quote(secrets?body:"<REDACTED_REQUEST_BODY>"));
        return new Export(command.toString(),List.copyOf(warnings),source,complete,Instant.now().toString());
    }
    private static String redact(String url) {
        try {URI u=URI.create(url);String origin=u.getScheme()+"://"+u.getRawAuthority();if(u.getRawUserInfo()!=null)origin=origin.replace(u.getRawUserInfo()+"@","");
            String result=origin+(u.getRawPath()==null?"":u.getRawPath());String query=u.getRawQuery();
            if(query!=null){var parts=new ArrayList<String>();for(String part:query.split("&",-1)){int at=part.indexOf('=');parts.add((at<0?part:part.substring(0,at))+"=%3CREDACTED%3E");}result+="?"+String.join("&",parts);}return result;
        }catch(Exception e){throw new IllegalArgumentException("请求URL无效，无法脱敏导出");}
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
}
