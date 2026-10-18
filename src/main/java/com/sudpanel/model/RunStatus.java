package com.sudpanel.model;

/**
 * 一次执行的整体状态（一次 run 包含多条测试点结果）。
 *
 * <ul>
 *   <li>RUNNING：执行中（前端据此持续轮询进度）。</li>
 *   <li>COMPLETED：正常跑完全部测试点。</li>
 *   <li>STOPPED：被人工停止（执行到一半中断）。</li>
 *   <li>ERROR：执行过程发生异常。</li>
 * </ul>
 */
public enum RunStatus {
    /** 执行中。 */
    RUNNING,
    /** 全部完成。 */
    COMPLETED,
    /** 人工停止。 */
    STOPPED,
    /** 出错。 */
    ERROR
}
