package com.sudpanel.perf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** 分享导出仅有指标与步骤摘要，不导出本机实际报文、鉴权、变量、坐标或设备序列号。 */
@Service
public class PerfExportService {
    private final PerfRepository repo;
    private final PerfEvidenceRepository evidence;
    public PerfExportService(PerfRepository repo,PerfEvidenceRepository evidence) {this.repo=repo;this.evidence=evidence;}
    public void samples(long runId,Long gameId,Long loopId,OutputStream output) throws IOException {
        repo.validateExportScope(runId,gameId,loopId);
        Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),32768);
        writer.write("run_id,game_id,loop_id,loop_no,seq,ts,pss_kb,rss_kb,cpu_percent_one_core,cpu_cores,thread_count,ws_thread_count,net_conn,java_heap_kb,native_heap_kb,graphics_kb,code_kb,private_other_kb,system_kb,sample_duration_ms,sample_offset_ms,actual_interval_ms,source_statuses\n");
        repo.forEachSample(runId,gameId,loopId,row -> {
            try { for(int i=0;i<SAMPLE_KEYS.size();i++){if(i>0)writer.write(',');writer.write(cell(row.get(SAMPLE_KEYS.get(i))));}var q=PerfQualityView.sample((String)row.get("quality_json"));for(String key:List.of("durationMs","offsetMs","intervalMs","sourceStatuses")){writer.write(',');writer.write(cell(q.get(key)));}writer.write('\n'); }
            catch(IOException e){throw new UncheckedIOException(e);}
        });writer.flush();
    }
    private static final List<String> SAMPLE_KEYS=List.of("run_id","game_id","loop_id","loop_no","seq","ts","pss_kb","rss_kb","cpu_percent","cpu_cores","thread_count","ws_thread_count","net_conn","java_heap_kb","native_heap_kb","graphics_kb","code_kb","private_other_kb","system_kb");
    public void zip(long runId,Long gameId,OutputStream output) throws IOException {
        repo.validateExportScope(runId,gameId,null);
        // 同一读事务覆盖元信息及所有entry，运行期间新增的样本不会使清单与CSV相互漂移。
        try {repo.readExportSnapshot(() -> {
            try {
                var zip=new ZipOutputStream(output,StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry("summary.csv"));summary(runId,gameId,zip);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("samples.csv"));samples(runId,gameId,null,zip);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("steps.csv"));steps(runId,gameId,zip);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("sampling-quality.csv"));quality(runId,gameId,zip);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("metadata.json"));
                var metadata=new LinkedHashMap<String,Object>();metadata.put("schemaVersion",2);metadata.put("runId",runId);metadata.put("gameId",gameId);
                metadata.put("plan",repo.planSummary(runId));metadata.put("sampleCount",repo.sampleCount(runId,gameId,null));
                metadata.put("units",Map.of("memory","KiB","cpu_percent_one_core","100%表示一个CPU核心","cpu_cores","该采样点有效核心数","ts","ISO-8601 UTC"));
                metadata.put("missingValue","CSV空单元格表示未知或未采集，不能视为0");metadata.put("privacy","不包含报文、鉴权、环境变量、坐标、设备序列号；本机详情另行查看");
                zip.write(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));zip.closeEntry();zip.finish();zip.flush();
            }catch(IOException e){throw new UncheckedIOException(e);}
        });}catch(UncheckedIOException e){throw e.getCause();}
    }
    public void quality(long runId,Long gameId,OutputStream output)throws IOException{Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));writer.write("loop_id,loop_no,conditions\n");repo.forEachExportLoop(runId,gameId,row->{try{writer.write(cell(row.get("id"))+","+cell(row.get("loopNo"))+","+cell(PerfQualityView.summary(repo.loopQuality(((Number)row.get("id")).longValue())))+"\n");}catch(IOException e){throw new UncheckedIOException(e);}});writer.flush();}

    public void summary(long runId,Long gameId,OutputStream output) throws IOException {
        Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),32768);
        writer.write("run_id,game_id,loop_id,loop_no,status,round_id,round_id_state,cleanup_status,cleanup_description,evidence_missing,pss_avg_kb,pss_peak_kb,cpu_avg_percent_one_core,plan_hash,sampling_conditions\n");
        repo.forEachExportLoop(runId,gameId,row->{try{
            var round=(Map<?,?>)row.get("roundIdEvidence");
            Object[] values={runId,row.get("gameId"),row.get("id"),row.get("loopNo"),row.get("status"),row.get("gameRoundId"),round.get("state"),row.get("cleanupStatus"),cleanupDescription(row),row.get("evidenceMissing"),row.get("pssAvgKb"),row.get("pssPeakKb"),row.get("cpuAvg"),row.get("planHash"),PerfQualityView.summary(repo.loopQuality(((Number)row.get("id")).longValue()))};
            for(int i=0;i<values.length;i++){if(i>0)writer.write(',');writer.write(cell(values[i]));}
            writer.write('\n');
        }catch(IOException e){throw new UncheckedIOException(e);}});writer.flush();
    }
    public void steps(long runId,Long gameId,OutputStream output) throws IOException {
        Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),32768);
        writer.write("run_id,game_id,loop_id,loop_no,step,verdict,http_status,elapsed_ms,truncated,evidence_unreadable\n");
        evidence.forEachStep(runId,gameId,row->{try{
            String[] keys={"runId","gameId","loopId","loopNo","step","verdict","httpStatus","elapsedMs","truncated","evidenceUnreadable"};
            for(int i=0;i<keys.length;i++){if(i>0)writer.write(',');writer.write(cell(row.get(keys[i])));}writer.write('\n');
        }catch(IOException e){throw new UncheckedIOException(e);}});writer.flush();
    }
    public static String cleanupDescription(Map<String,Object> row) {
        String status=String.valueOf(row.get("cleanupStatus"));
        if("SUCCESS".equals(status))return "结束与清房均成功";
        if(!"FAILED".equals(status))return "未记录清理结果，可能是旧记录、未执行或取证写入失败";
        String reason=String.valueOf(row.get("cleanupReason"));var causes=new ArrayList<String>();
        if(reason.contains("结束游戏"))causes.add("结束步骤失败");if(reason.contains("清空房间"))causes.add("清房步骤失败");
        return (causes.isEmpty()?"清理未成功":String.join("；",causes))+"；详细错误保留在本机步骤详情";
    }
    public static String cell(Object value) {
        if(value==null)return "";String text=String.valueOf(value);
        if(value instanceof String && !text.isEmpty() && "=+-@".indexOf(text.charAt(0))>=0) text="'"+text;
        return text.indexOf(',')>=0||text.indexOf('"')>=0||text.indexOf('\n')>=0||text.indexOf('\r')>=0?'"'+text.replace("\"","\"\"")+'"':text;
    }
}
