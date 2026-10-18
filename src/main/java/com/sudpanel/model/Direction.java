package com.sudpanel.model;

/**
 * 上报方向。
 *
 * <p>平台测试分两个方向：
 * <ul>
 *   <li>APPTOMG：App → 游戏。App 内触发状态上报，验证游戏端收到的数据。</li>
 *   <li>MGTOAPP：游戏 → App。游戏内操作产生状态，验证 App 端收到的数据。</li>
 * </ul>
 * 两个方向的测试点、配置、运行记录相互独立。
 */
public enum Direction {
    /** App → 游戏（全自动执行方向）。 */
    APPTOMG,
    /** 游戏 → App（人工走查方向）。 */
    MGTOAPP
}
