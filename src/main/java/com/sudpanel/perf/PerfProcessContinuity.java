package com.sudpanel.perf;

/** 读取失败/暂时消失与确证换进程分开；确认变化后永不恢复成连续。 */
public final class PerfProcessContinuity {
    private Long startTicks;private boolean changed;private int unknown,absent;
    public boolean observe(PerfCollector.ProcessProbe probe,PerfCollector.Sample sample){
        if(changed)return false;
        if(probe==null||"UNKNOWN".equals(probe.state()))unknown++;
        else if("ABSENT".equals(probe.state()))absent++;
        else if("DIFFERENT".equals(probe.state()))changed=true;
        if(sample!=null&&sample.quality()!=null&&sample.quality().sources().values().stream().anyMatch(source->"CHANGED".equals(source.status())))changed=true;
        if(sample!=null&&sample.quality()!=null&&sample.quality().sources().get("process")!=null&&!"OK".equals(sample.quality().sources().get("process").status()))unknown++;
        Long ticks=sample==null||sample.quality()==null?null:sample.quality().processStartTicks();
        if(ticks!=null){if(startTicks!=null&&!startTicks.equals(ticks))changed=true;else startTicks=ticks;}
        return !changed;
    }
    public boolean changed(){return changed;}public int unknownReads(){return unknown;}public int absentReads(){return absent;}
    public String status(){return changed?"CHANGED":unknown>0||absent>0||startTicks==null?"UNVERIFIED":"CONTINUOUS";}
}
