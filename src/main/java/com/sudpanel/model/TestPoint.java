package com.sudpanel.model;

/**
 * 一个测试点：对应一次状态上报/下发的验证项，是平台最小的测试单元。
 *
 * <p>apptomg 的测试点由服务端预置（固定），mgtoapp 的测试点可在「测试点管理」页维护。
 * 持久化在 test_point 表。
 *
 * @param id          测试点主键
 * @param direction   所属方向（APPTOMG / MGTOAPP）
 * @param group       mgtoapp 分组：mgtoapp_game（游戏态）/ mgtoapp_player（玩家态）
 * @param seq         组内排序序号
 * @param stateName   状态名（state）：日志过滤关键字的核心，如 onGameStart、onGetGameCfg
 * @param resourceKey 对应的 mock 按钮 resource-id（apptomg 自动点击定位用；mgtoapp 与状态名同值）
 * @param description 测试点名称（前端列表展示用）
 * @param dataSchema  期望的数据字段结构（JSON Schema 描述，mgtoapp 走查时做字段对照）
 * @param triggerType 触发方式：AUTO 工具自动点 / MANUAL 人工点
 * @param keyword     自动判定关键字（日志命中即 PASS，JudgeMode=AUTO 时用）
 * @param prompt      备注/说明（走查时展示给测试人员）
 * @param judgeMode   判定方式：AUTO 自动 / MANUAL 人工
 * @param autoPass    是否空 data 命中即自动通过（部分状态无需比对数据）
 * @param enabled     是否启用（禁用的不参与执行）
 * @param tapCount    apptomg 自动执行时该按钮需要点击的次数（某些状态要连点）
 */
public record TestPoint(
        Long id,
        Direction direction,
        String group,
        int seq,
        String stateName,
        String resourceKey,
        String description,
        String dataSchema,
        TriggerType triggerType,
        String keyword,
        String prompt,
        JudgeMode judgeMode,
        boolean autoPass,
        boolean enabled,
        int tapCount
) {
}
