package com.sudpanel.api;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.UnaryOperator;

/** multipart与binary都先确定唯一最终字节，再签名和发布；摘要不回显文件内容。 */
public final class ApiFileBody {
    private ApiFileBody(){}
    public record Encoded(byte[] bytes,String contentType,Map<String,Object> metadata,List<ApiFileStore.Lease> leases) implements AutoCloseable {
        public void close(){leases.forEach(ApiFileStore.Lease::close);}
    }
    public static void validate(ApiRequestOptions o){
        if(o.fileBody()!=null){ApiFileStore.validId(o.fileBody().fileId());if(o.fileBody().filename()!=null)ApiFileStore.filename(o.fileBody().filename());if(o.fileBody().contentType()!=null)ApiFileStore.mime(o.fileBody().contentType());}
        if(o.parts()!=null){if(o.parts().size()>200||o.parts().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("multipart最多200项且不能为null");for(var p:o.parts()){if(p.type()!=null&&!Set.of("TEXT","FILE").contains(p.type()))throw new IllegalArgumentException("multipart类型仅支持TEXT/FILE");if(Boolean.FALSE.equals(p.enabled()))continue;if("FILE".equals(p.type()))ApiFileStore.validId(p.fileId());if(p.filename()!=null)ApiFileStore.filename(p.filename());if(p.contentType()!=null)ApiFileStore.mime(p.contentType());}}
    }
    public static Encoded encode(ApiRequestOptions o,ApiFileStore store,UnaryOperator<String> resolve){
        if(store==null)throw new IllegalArgumentException("文件存储未初始化；请重新启动平台后重试");
        List<ApiFileStore.Lease> leases=new ArrayList<>();
        try{
            if("BINARY".equals(o.bodyType())){
                if(o.fileBody()==null)throw new IllegalArgumentException("请先上传或选择binary文件");var b=o.fileBody();var lease=store.acquire(b.fileId());leases.add(lease);if(!lease.info().kind().equals("UPLOAD"))throw new IllegalArgumentException("只能发送明确上传的文件；请下载响应后自行重新上传");
                var m=new LinkedHashMap<String,Object>();m.put("type","BINARY");m.put("bytes",lease.bytes().length);m.put("sha256",lease.info().sha256());m.put("file",lease.info());
                return new Encoded(lease.bytes(),b.contentType()==null?lease.info().contentType():ApiFileStore.mime(b.contentType()),m,leases);
            }
            String boundary="----SudPanel"+UUID.randomUUID().toString().replace("-","");var out=new ByteArrayOutputStream();var parts=new ArrayList<Map<String,Object>>();
            for(var p:o.parts()==null?List.<ApiRequestOptions.FilePart>of():o.parts()){
                if(Boolean.FALSE.equals(p.enabled()))continue;String name=resolve.apply(p.name());if(name==null||name.isBlank())throw new IllegalArgumentException("启用的multipart字段名不能为空");safeDisposition(name);
                put(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\""+quoted(name)+"\"");var meta=new LinkedHashMap<String,Object>();meta.put("name",name);
                byte[] bytes;
                if("FILE".equals(p.type())){
                    var lease=store.acquire(p.fileId());leases.add(lease);if(!lease.info().kind().equals("UPLOAD"))throw new IllegalArgumentException("multipart文件项只能引用上传文件");
                    String filename=ApiFileStore.filename(p.filename()==null?lease.info().filename():resolve.apply(p.filename()));String type=p.contentType()==null?lease.info().contentType():ApiFileStore.mime(resolve.apply(p.contentType()));
                    put(out,"; filename=\""+quoted(filename)+"\"\r\nContent-Type: "+type+"\r\n\r\n");bytes=lease.bytes();meta.put("type","FILE");meta.put("file",lease.info());meta.put("filename",filename);meta.put("contentType",type);
                }else{
                    String value=resolve.apply(p.value());bytes=(value==null?"":value).getBytes(StandardCharsets.UTF_8);put(out,"\r\nContent-Type: "+(p.contentType()==null?"text/plain; charset=UTF-8":ApiFileStore.mime(resolve.apply(p.contentType())))+"\r\n\r\n");meta.put("type","TEXT");
                }
                if((long)out.size()+bytes.length+boundary.length()+10>ApiFileStore.BODY_LIMIT)throw new IllegalArgumentException("multipart最终正文超过64MiB");out.writeBytes(bytes);put(out,"\r\n");meta.put("bytes",bytes.length);meta.put("sha256",ApiFileStore.sha(bytes));parts.add(meta);
            }
            put(out,"--"+boundary+"--\r\n");byte[] bytes=out.toByteArray();return new Encoded(bytes,"multipart/form-data; boundary="+boundary,Map.of("type","MULTIPART","bytes",bytes.length,"sha256",ApiFileStore.sha(bytes),"boundary",boundary,"parts",parts),leases);
        }catch(RuntimeException e){leases.forEach(ApiFileStore.Lease::close);throw e;}
    }
    private static void safeDisposition(String value){if(value.length()>1000||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("multipart字段名/文件名含控制字符或过长");}
    private static String quoted(String value){safeDisposition(value);return value.replace("\\","\\\\").replace("\"","\\\"");}
    private static void put(ByteArrayOutputStream out,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if((long)out.size()+bytes.length>ApiFileStore.BODY_LIMIT)throw new IllegalArgumentException("请求正文超过64MiB");out.writeBytes(bytes);}
}
