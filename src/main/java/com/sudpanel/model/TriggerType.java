package com.sudpanel.model;

/**
 * 触发方式：执行一个测试点时，怎么触发状态上报。
 *
 * <ul>
 *   <li>AUTO：工具自动点 mock 按钮（通过 UiLocator 定位坐标后 adb 点击），全自动方向使用。</li>
 *   <li>MANUAL：人工在游戏/App 里点按钮，工具只负责读取日志判定，走查方向使用。</li>
 * </ul>
 */
public enum TriggerType {
    /** 工具自动点击 mock 按钮触发。 */
    AUTO,
    /** 人工操作触发，工具只读日志。 */
    MANUAL
}
