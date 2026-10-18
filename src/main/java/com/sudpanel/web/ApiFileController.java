package com.sudpanel.web;

import com.sudpanel.api.ApiFileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 只接受opaque ID。响应HTML/SVG永不作为活动文档打开；默认下载采用attachment。 */
@RestController
@RequestMapping("/api/apitest/files")
public class ApiFileController {
    private final ApiFileStore store;
    public ApiFileController(ApiFileStore store){this.store=store;}
    @PostMapping public ApiFileStore.FileInfo upload(HttpServletRequest request,@RequestParam(defaultValue="file.bin") String filename,@RequestParam(defaultValue="application/octet-stream") String contentType)throws IOException{
        if(request.getContentLengthLong()>ApiFileStore.UPLOAD_LIMIT)throw new IllegalArgumentException("上传超过16MiB");
        return store.upload(request.getInputStream(),filename,contentType);
    }
    @GetMapping public ApiFileStore.Overview list(){return store.overview();}
    @GetMapping("/{id}") public ApiFileStore.FileInfo info(@PathVariable String id){return store.info(id);}
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable String id){store.delete(id);store.cleanupCache();return Map.of("ok",true);}
    @PostMapping("/cleanup-orphans") public Map<String,Object> cleanup(){int n=store.cleanupOrphans();store.cleanupCache();return Map.of("ok",true,"deleted",n);}
    @GetMapping("/{id}/download") public ResponseEntity<StreamingResponseBody> download(@PathVariable String id){return content(id,false);}
    @GetMapping("/{id}/image") public ResponseEntity<StreamingResponseBody> image(@PathVariable String id){return content(id,true);}
    /** HEAD不取得lease，防止容器跳过正文回调后留下活跃引用。 */
    @RequestMapping(value={"/{id}/download","/{id}/image"},method=RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String id,HttpServletRequest request){var f=store.info(id);String type=f.contentType().split(";",2)[0].toLowerCase(Locale.ROOT);boolean image=request.getRequestURI().endsWith("/image");if(image&&!Set.of("image/png","image/jpeg","image/gif","image/webp").contains(type))throw new IllegalArgumentException("此MIME不支持图像预览");return ResponseEntity.ok().contentType(MediaType.parseMediaType(image?type:"application/octet-stream")).contentLength(f.bytes()).header("X-SudPanel-SHA256",f.sha256()).header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").build();}
    private ResponseEntity<StreamingResponseBody> content(String id,boolean image){
        var lease=store.acquire(id);var f=lease.info();String type=f.contentType().split(";",2)[0].toLowerCase(Locale.ROOT);
        if(image&&!Set.of("image/png","image/jpeg","image/gif","image/webp").contains(type)){lease.close();throw new IllegalArgumentException("仅支持PNG/JPEG/GIF/WebP预览；HTML、SVG和其他文件请下载后自行检查");}
        var headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(image?type:"application/octet-stream"));headers.setContentLength(f.bytes());headers.set("X-Content-Type-Options","nosniff");headers.set("Content-Security-Policy","sandbox; default-src 'none'");headers.set("X-Frame-Options","DENY");headers.setCacheControl("no-store");headers.set("X-SudPanel-SHA256",f.sha256());headers.setContentDisposition((image?ContentDisposition.inline():ContentDisposition.attachment()).filename(f.filename(),StandardCharsets.UTF_8).build());
        StreamingResponseBody stream=out->{try(lease){out.write(lease.bytes());out.flush();}};
        return new ResponseEntity<>(stream,headers,HttpStatus.OK);
    }
}
