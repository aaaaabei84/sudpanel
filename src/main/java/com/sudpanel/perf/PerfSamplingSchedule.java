package com.sudpanel.perf;

import java.util.function.LongSupplier;

/** 单调固定槽位调度；慢采样跳过过期槽位，不补发积压请求。 */
public final class PerfSamplingSchedule {
    private final LongSupplier clock;private final long start,deadline,interval;private long next;private long skipped;
    public PerfSamplingSchedule(long durationMs,long intervalMs){this(durationMs,intervalMs,System::nanoTime);}
    PerfSamplingSchedule(long durationMs,long intervalMs,LongSupplier clock){if(durationMs<1||durationMs>86400000||intervalMs<1||intervalMs>86400000)throw new IllegalArgumentException("采样时长或间隔超出范围");this.clock=clock;start=clock.getAsLong();deadline=start+durationMs*1000000;interval=intervalMs*1000000;next=start;}
    public long delayMillis(){long now=clock.getAsLong();if(now>=deadline||next>=deadline)return -1;return Math.max(0,(next-now+999999)/1000000);}
    public boolean hasTime(){return clock.getAsLong()<deadline;}
    public long remainingMillis(){return Math.max(0,(deadline-clock.getAsLong()+999999)/1000000);}
    public long offsetMillis(){return Math.max(0,(clock.getAsLong()-start)/1000000);}
    public void complete(){next+=interval;long now=clock.getAsLong();if(next<now){long aligned=start+((now-start)/interval+1)*interval;skipped+=(aligned-next)/interval;next=aligned;}}
    public long skippedSlots(){return skipped;}
}
