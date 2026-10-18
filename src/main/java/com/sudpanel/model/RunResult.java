package com.sudpanel.model;

import java.time.Instant;

/**
 * 一条测试点执行结果（一次 run 里每个测试点跑完产生一行，存 run_result 表）。
 *
 * <p>执行过程中前端轮询 /api/runs/{id} 拿到的 results 列表就是本记录列表。
 *
 * @param id          结果主键
 * @param runId       所属 run（一次执行）的 id
 * @param pointId     对应的测试点 id（可能为空：某些结果不由测试点产生）
 * @param stateName   状态名（state，快照自 TestPoint，避免测试点被改后历史结果错乱）
 * @param description 测试点名称（快照自 TestPoint.description）
 * @param direction   所属方向
 * @param result      结果：PASS / FAIL / SKIP
 * @param logSnippet  命中的日志片段（展示用，可滚动查看）
 * @param note        备注（人工判定时的说明，或自动判定的附加信息）
 * @param elapsedMs   该测试点执行耗时（毫秒）
 * @param createdAt   完成时间
 */
public record RunResult(
        Long id,
        Long runId,
        Long pointId,
        String stateName,
        String description,
        Direction direction,
        ResultStatus result,
        String logSnippet,
        String note,
        long elapsedMs,
        Instant createdAt
) {
}
