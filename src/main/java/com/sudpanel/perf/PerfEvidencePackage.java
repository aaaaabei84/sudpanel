package com.sudpanel.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.config.AppProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;

/** 分享包只导出白名单证据；原始附件只在明确请求私密包时提供。 */
@Service
public class PerfEvidencePackage {
    private final PerfRepository repo;private final PerfEvidenceRepository evidence;private final Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    public PerfEvidencePackage(PerfRepository repo,PerfEvidenceRepository evidence,AppProperties props){this(repo,evidence,Path.of(props.dbPath()).toAbsolutePath().getParent());}
    public PerfEvidencePackage(PerfRepository repo,PerfEvidenceRepository evidence,Path root){this.repo=repo;this.evidence=evidence;this.root=root.toAbsolutePath().normalize();}
    public byte[] create(long runId,boolean privateFiles) {
        repo.validateExportScope(runId,null,null);
        byte[][] result={null};repo.readExportSnapshot(()->result[0]=build(runId,privateFiles));return result[0];
    }
    private byte[] build(long runId,boolean privateFiles) {
        try {
            var files=new LinkedHashMap<String,byte[]>();var attachments=new ArrayList<Map<String,Object>>();var loops=new ArrayList<Map<String,Object>>();
            int[] ordinal={0};long[] budget={0};
            repo.forEachExportLoop(runId,null,row->{
                if(++ordinal[0]>1000)throw new IllegalArgumentException("统一证据包最多1000圈，请使用分圈原始样本导出");
                var safe=new LinkedHashMap<String,Object>();safe.put("ordinal",ordinal[0]);
                for(String key:List.of("loopNo","status","cleanupStatus","evidenceMissing","pssAvgKb","pssPeakKb","cpuAvg")){Object value=row.get(key);safe.put(key,value instanceof Number||value instanceof Boolean||value==null?value:allowedState(value));}
                var quality=new LinkedHashMap<String,Object>(repo.loopQuality(((Number)row.get("id")).longValue()));if(!Set.of("CONTINUOUS","CHANGED","UNVERIFIED","UNKNOWN").contains(String.valueOf(quality.get("continuity"))))quality.put("continuity","UNKNOWN");safe.put("sampling",PerfQualityView.summary(quality));loops.add(safe);
                long game=((Number)row.get("gameId")).longValue();int loop=((Number)row.get("loopNo")).intValue();
                for(String extension:List.of("png","log")) {
                    String name="run"+runId+"-game"+game+"-loop"+loop+"."+extension;
                    var file=attachment(root.resolve("screenshots").resolve(name));var item=new LinkedHashMap<String,Object>();
                    item.put("loopOrdinal",ordinal[0]);item.put("kind",extension.equals("png")?"SCREENSHOT":"BOUNDED_LOG");item.put("state",file.state);item.put("sha256",file.hash);
                    item.put("included",privateFiles&&file.bytes!=null);item.put("reason",file.bytes==null?file.state:privateFiles?"私密本机原始附件":"原始内容可能含身份或凭据，默认只记录哈希");attachments.add(item);
                    if(file.bytes!=null){budget[0]+=file.bytes.length;if(budget[0]>32L*1024*1024)throw new IllegalArgumentException("证据文件读取超过32MiB，请缩小范围");}
                    if(privateFiles&&file.bytes!=null){files.put("private/loop-"+ordinal[0]+"."+extension,file.bytes);}
                }
            });
            var steps=new ArrayList<Map<String,Object>>();
            evidence.forEachStep(runId,null,row->{if(steps.size()>=5000)throw new IllegalArgumentException("统一证据包最多5000条步骤，请缩小运行范围");var safe=new LinkedHashMap<String,Object>();safe.put("ordinal",steps.size()+1);for(String key:List.of("loopNo","httpStatus","elapsedMs","truncated","evidenceUnreadable"))safe.put(key,row.get(key));safe.put("step",Set.of("推人机","开局","结束游戏","清空房间","结算查询").contains(row.get("step"))?row.get("step"):"OTHER");safe.put("verdict",allowedState(row.get("verdict")));steps.add(safe);});
            var timeline=repo.facts().timeline(runId);
            files.put("loops.json",JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(loops));
            files.put("timeline.json",JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(timeline));
            files.put("request-summaries.json",JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(steps));
            files.put("logs/redacted.txt",("此日志仅由类型化恢复事件生成，不包含原始设备日志；原日志缺失会在附件清单标明。\n"+JSON.writeValueAsString(timeline)).getBytes(StandardCharsets.UTF_8));
            var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("private",privateFiles);manifest.put("privacy",privateFiles?"包含原始截图/有限日志，仅限本机私密取证，不是脱敏分享包":"默认脱敏：无原始报文、设备序列号、对局/房间/玩家/应用业务标识、名单或凭据；原始附件仅留哈希");manifest.put("loopCount",loops.size());manifest.put("attachments",attachments);manifest.put("timelineTruncated",timeline.get("truncated"));manifest.put("conditionsState",repo.facts().summary(runId).get("state"));
            files.put("README.txt",("先读manifest.json核对附件缺失与私密标记；SHA256针对包内精确字节，manifest不对自身哈希。\n圈ordinal为本包序号，不是业务对局ID；UNKNOWN/NOT_RECORDED不能视为通过。\n日志为有限结构化时间线，截图默认排除；原始报文只在本机步骤详情查看。\n").getBytes(StandardCharsets.UTF_8));
            var hashes=new LinkedHashMap<String,Object>();files.forEach((name,bytes)->hashes.put(name,Map.of("sha256",hash(bytes),"bytes",bytes.length)));manifest.put("files",hashes);
            files.put("manifest.json",JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}}return output.toByteArray();
        }catch(UncheckedIOException failure){throw failure;}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("统一证据包生成失败",failure);}
    }
    private static String allowedState(Object value){String text=String.valueOf(value);return Set.of("OK","INVALID","RUNNING","INTERRUPTED","PASS","FAIL","ERROR","SKIP","SUCCESS","FAILED","NOT_RECORDED","UNKNOWN").contains(text)?text:"UNKNOWN";}
    record Attachment(String state,String hash,byte[] bytes){}
    Attachment attachment(Path file) {
        try {
            Path normalized=file.toAbsolutePath().normalize();Path dir=root.resolve("screenshots");
            if(!normalized.startsWith(dir)||Files.isSymbolicLink(root)||Files.isSymbolicLink(dir)||Files.isSymbolicLink(normalized))return new Attachment("UNSAFE_PATH",null,null);
            if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS))return new Attachment("MISSING",null,null);
            if(!Files.isRegularFile(normalized,LinkOption.NOFOLLOW_LINKS)||!normalized.toRealPath().startsWith(root.toRealPath().resolve("screenshots")))return new Attachment("UNSAFE_PATH",null,null);
            if(Files.size(normalized)>8L*1024*1024)return new Attachment("OVERSIZE",null,null);
            try(var input=Files.newInputStream(normalized,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)return new Attachment("OVERSIZE",null,null);return new Attachment("RECORDED",hash(bytes),bytes);}
        }catch(Exception failure){return new Attachment("UNREADABLE",null,null);}
    }
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
