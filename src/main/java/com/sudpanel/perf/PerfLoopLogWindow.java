package com.sudpanel.perf;

import com.sudpanel.logcat.LogcatTail;

/** 单圈日志边界。锚点丢失时只重建边界，绝不把剩余整段旧日志当成本圈证据。 */
final class PerfLoopLogWindow {
    private String anchor;
    private boolean established;
    private boolean incomplete;
    private final PerfRoundIdEvidence roundIds=new PerfRoundIdEvidence();

    PerfLoopLogWindow(String initialDump) {
        established = initialDump != null;
        if (established) anchor = LogcatTail.diff(initialDump, null).anchor();
        else incomplete = true;
    }

    String accept(String dump) {
        roundIds.readObserved();
        if (dump == null || dump.isBlank()) {
            if (anchor != null || dump == null) incomplete = true;
            return "";
        }
        LogcatTail.Result diff = LogcatTail.diff(dump, anchor);
        if (!established) {
            established = true;
            anchor = diff.anchor();
            return "";
        }
        boolean lost = anchor != null && diff.reset();
        anchor = diff.anchor();
        if (lost) {
            incomplete = true;
            return "";
        }
        String fresh=String.join("\n", diff.lines());
        roundIds.observe(fresh);
        return fresh;
    }

    String reliableRoundId() { return roundIds.reliableId(incomplete); }
    java.util.Map<String,Object> roundEvidence() { return roundIds.summary(incomplete); }

    void failedRead() { incomplete = true; }

    boolean incomplete() { return incomplete; }

    /** 短于十个点的 NORMAL 圈也必须读一次；结束时调用方还需补读。 */
    static boolean shouldReadAt(int sampleIndex, int periodicEvery) {
        return sampleIndex == 0 || sampleIndex % periodicEvery == 0;
    }
}
