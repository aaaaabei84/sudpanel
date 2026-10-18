package com.sudpanel.model;

/**
 * 判定方式：单条测试点跑完后，由谁来决定结果。
 *
 * <ul>
 *   <li>AUTO：工具自动判。日志里命中关键字即记 PASS，无需人工介入（apptomg 全自动用）。</li>
 *   <li>MANUAL：人工判。工具展示命中日志与数据字段，由测试人员点「通过 / 失败」（mgtoapp 走查用）。</li>
 * </ul>
 */
public enum JudgeMode {
    /** 自动判定：关键字命中即通过。 */
    AUTO,
    /** 人工判定：需测试人员确认。 */
    MANUAL
}
