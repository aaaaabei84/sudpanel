package com.sudpanel.perf;

import java.util.*;

/** 一圈质量摘要，无报文、包名、序列号或业务身份。 */
public final class PerfSamplingDiagnostics {
    private final PerfDeviceHealth.Snapshot start;private final PerfDeviceHealth.Policy policy;
    private final PerfProcessContinuity continuity=new PerfProcessContinuity();private final List<PerfCollector.Sample> samples=new ArrayList<>();
    private final List<Map<String,Object>> events=new ArrayList<>();private long skipped;private Long durationMs,nominalIntervalMs;
    public PerfSamplingDiagnostics(PerfDeviceHealth.Snapshot start,PerfDeviceHealth.Policy policy){this.start=start;this.policy=policy;}
    public PerfProcessContinuity continuity(){return continuity;}
    public void event(String type,long offsetMs){if(events.size()<100)events.add(Map.of("type",type,"offsetMs",offsetMs));}
    public void sample(PerfCollector.Sample sample){samples.add(sample);}
    public void completed(long elapsedMs,long nominalIntervalMs,long skipped){durationMs=elapsedMs;this.nominalIntervalMs=nominalIntervalMs;this.skipped=skipped;}
    public Map<String,Object> summary(PerfDeviceHealth.Snapshot end){
        var result=new LinkedHashMap<String,Object>();result.put("state","RECORDED");result.put("version",1);result.put("startHealth",start);result.put("endHealth",end);result.put("policy",policy);
        var warnings=new ArrayList<String>(PerfDeviceHealth.warnings(start,policy));for(String message:PerfDeviceHealth.warnings(end,policy))if(!warnings.contains(message))warnings.add(message);
        result.put("warnings",warnings);result.put("continuity",continuity.status());result.put("processUnknownReads",continuity.unknownReads());result.put("processAbsentReads",continuity.absentReads());result.put("events",events);
        result.put("sampleCount",samples.size());result.put("samplingElapsedMs",durationMs);result.put("nominalIntervalMs",nominalIntervalMs);result.put("skippedSlots",skipped);
        var missing=new LinkedHashMap<String,Object>();missing.put("pss",missing(s->s.pssKb()));missing.put("cpu",missing(s->s.cpuPercent()));missing.put("threads",missing(s->s.threadCount()));missing.put("ws",missing(s->s.wsThreadCount()));missing.put("tcp",missing(s->s.netConnections()));result.put("missing",missing);
        var intervals=samples.stream().filter(s->s.quality()!=null&&s.quality().intervalMs()!=null).mapToLong(s->s.quality().intervalMs()).toArray();result.put("actualIntervalAvgMs",intervals.length==0?null:Arrays.stream(intervals).average().orElseThrow());
        result.put("effectiveHz",intervals.length==0||Arrays.stream(intervals).sum()==0?null:1000.0*intervals.length/Arrays.stream(intervals).sum());
        var durations=samples.stream().filter(s->s.quality()!=null&&s.quality().durationMs()!=null).mapToLong(s->s.quality().durationMs()).toArray();result.put("acquisitionAvgMs",durations.length==0?null:Arrays.stream(durations).average().orElseThrow());result.put("acquisitionMaxMs",durations.length==0?null:Arrays.stream(durations).max().orElseThrow());
        var sourceCounts=new TreeMap<String,Integer>();for(var sample:samples)if(sample.quality()!=null)sample.quality().sources().forEach((key,value)->sourceCounts.merge(key+":"+value.status(),1,Integer::sum));result.put("sourceCounts",sourceCounts);return result;
    }
    private Map<String,Object> missing(java.util.function.Function<PerfCollector.Sample,Object> read){long count=samples.stream().filter(s->read.apply(s)==null).count();var m=new LinkedHashMap<String,Object>();m.put("count",count);m.put("percent",samples.isEmpty()?null:100.0*count/samples.size());return m;}
}
