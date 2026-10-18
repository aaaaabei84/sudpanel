package com.sudpanel.model;

/**
 * 单条测试点的执行结果状态。
 *
 * <ul>
 *   <li>PASS：通过（自动命中关键字或人工判通过）。</li>
 *   <li>FAIL：失败（人工判失败，或自动判定未命中）。</li>
 *   <li>SKIP：跳过（测试点被跳过，未实际执行）。</li>
 * </ul>
 */
public enum ResultStatus {
    /** 通过。 */
    PASS,
    /** 失败。 */
    FAIL,
    /** 跳过。 */
    SKIP
}
