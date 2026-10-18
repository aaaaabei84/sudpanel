package com.sudpanel.api;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import java.util.zip.*;

/** 有界原字节接收/解压，超限保留明确计数与有限前缀；不把半正文交给断言。 */
public final class ApiResponseBody {
    private ApiResponseBody(){}
    public static final class TooLarge extends IOException {
        public final String stage;public final int limit,status;public final long readBytes,wireBytes;public final byte[] retained;public final Map<String,List<String>> headers;
        TooLarge(String stage,int limit,long readBytes,long wireBytes,byte[] retained,int status,Map<String,List<String>> headers){super("响应"+(stage.equals("WIRE")?"传输":"解压")+"超过上限 "+limit+" 字节；正文不完整，未执行断言");this.stage=stage;this.limit=limit;this.readBytes=readBytes;this.wireBytes=wireBytes;this.retained=retained;this.status=status;this.headers=headers;}
        public Map<String,Object> metadata(){return Map.of("tooLarge",true,"complete",false,"stage",stage,"limit",limit,"readBytes",readBytes,"wireBytes",wireBytes,"retainedBytes",retained.length,"truncated",true);}
        public static TooLarge find(Throwable e){for(int n=0;e!=null&&n<20;n++,e=e.getCause())if(e instanceof TooLarge t)return t;return null;}
    }
    public record Decoded(byte[] bytes,String text,String classification,String contentType,String charset,String encoding,int wireBytes,String sha256) {}
    public static HttpResponse.BodyHandler<byte[]> bounded(int limit,boolean head){return info->new HttpResponse.BodySubscriber<>(){
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream out=new ByteArrayOutputStream();Flow.Subscription subscription;long read;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;long declared=info.headers().firstValueAsLong("Content-Length").orElse(0);if(!head&&info.statusCode()!=204&&info.statusCode()!=304&&declared>limit)fail(0);else s.request(1);}
        private void fail(long observed){subscription.cancel();result.completeExceptionally(new TooLarge("WIRE",limit,observed,observed,out.toByteArray(),info.statusCode(),info.headers().map()));}
        public void onNext(List<ByteBuffer> buffers){if(result.isDone())return;for(ByteBuffer b:buffers){int size=b.remaining();read+=size;int keep=Math.min(size,limit-out.size());if(keep>0){byte[] data=new byte[keep];b.get(data);out.writeBytes(data);}if(size>keep){fail(read);return;}}subscription.request(1);}
        public void onError(Throwable e){result.completeExceptionally(e);}public void onComplete(){result.complete(out.toByteArray());}
    };}
    public static Decoded decode(byte[] wire,Map<String,List<String>> headers,int limit,int status)throws IOException{
        String encoding=header(headers,"Content-Encoding").trim().toLowerCase(Locale.ROOT),type=header(headers,"Content-Type");
        if(wire.length>limit)throw new TooLarge("WIRE",limit,wire.length,wire.length,Arrays.copyOf(wire,limit),status,headers);
        InputStream stream=new ByteArrayInputStream(wire);
        if(wire.length>0)stream=switch(encoding){case "","identity"->stream;case "gzip","x-gzip"->new GZIPInputStream(stream);case "deflate"->new InflaterInputStream(stream);default->throw new IOException("暂不支持响应压缩方式："+encoding);};
        byte[] bytes;try(InputStream in=stream;var out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;long read=0;while((n=in.read(b))!=-1){read+=n;int keep=Math.min(n,limit-out.size());out.write(b,0,keep);if(n>keep)throw new TooLarge("DECODED",limit,read,wire.length,out.toByteArray(),status,headers);}bytes=out.toByteArray();}
        String bare=type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);boolean text=bare.startsWith("text/")||bare.equals("application/json")||bare.endsWith("+json")||bare.equals("application/xml")||bare.endsWith("+xml")||bare.equals("application/javascript")||bare.equals("application/x-www-form-urlencoded")||bare.isEmpty();
        String decoded=null,charset=null,kind="BINARY";
        if(text){Charset cs=StandardCharsets.UTF_8;var m=Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*[\"']?([^;\\s\"']+)").matcher(type);if(m.find())try{cs=Charset.forName(m.group(1));}catch(Exception e){throw new IOException("不支持响应字符集："+m.group(1));}
            try{decoded=cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(bare.isEmpty()&&decoded.indexOf(0)>=0)decoded=null;}catch(CharacterCodingException e){decoded=null;}
            if(decoded!=null){charset=cs.name();kind=bare.equals("application/json")||bare.endsWith("+json")?"JSON":"TEXT";}
        }
        return new Decoded(bytes,decoded,kind,type,charset,encoding,wire.length,ApiFileStore.sha(bytes));
    }
    public static String header(Map<String,List<String>> headers,String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(e->String.join(",",e.getValue())).findFirst().orElse("");}
}
