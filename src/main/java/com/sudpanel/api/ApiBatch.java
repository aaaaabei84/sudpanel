package com.sudpanel.api;

import java.util.List;

/**
 * 一个批量/定时执行批次。
 *
 * @param id              自增 id
 * @param name            批次名称
 * @param mode            ONCE（跑一轮）/ REPEAT（按间隔重复）
 * @param caseIds         本批次包含的用例 id，顺序即执行顺序
 * @param intervalSeconds 每轮之间的间隔秒数；ONCE 时为 0
 * @param repeatCount     重复轮数；-1 表示一直循环直到手动停止
 * @param status          RUNNING / DONE / STOPPED / FAILED / INTERRUPTED
 * @param totalExecs      累计执行次数
 * @param passCount       通过数
 * @param failCount       断言未通过数
 * @param errorCount      请求本身出错数
 * @param startedAt       开始时间
 * @param finishedAt      结束时间
 */
public record ApiBatch(
        Long id,
        String name,
        String mode,
        List<Long> caseIds,
        Integer intervalSeconds,
        Integer repeatCount,
        String status,
        Integer totalExecs,
        Integer passCount,
        Integer failCount,
        Integer errorCount,
        String startedAt,
        String finishedAt,
        Integer skippedExecs
) {
    public ApiBatch(Long id, String name, String mode, List<Long> caseIds, Integer intervalSeconds,
                    Integer repeatCount, String status, Integer totalExecs, Integer passCount,
                    Integer failCount, Integer errorCount, String startedAt, String finishedAt) {
        this(id, name, mode, caseIds, intervalSeconds, repeatCount, status, totalExecs,
                passCount, failCount, errorCount, startedAt, finishedAt, 0);
    }
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String STOPPED = "STOPPED";
    public static final String FAILED = "FAILED";
    /** 程序退出时批次还在 RUNNING —— 下次启动标记成这个状态，提示用户结果不完整。 */
    public static final String INTERRUPTED = "INTERRUPTED";

    /** 一直循环的约定值。 */
    public static final int REPEAT_FOREVER = -1;
}
