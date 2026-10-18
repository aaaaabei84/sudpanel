package com.sudpanel.adb;

/** adb devices -l 解析出的一台设备。 */
// record 是 Java 16+ 的不可变数据类：编译器自动生成构造器、访问器（serial()/status()/model()）、
// equals/hashCode/toString，适合做纯数据载体。
// 组件字段说明：
//   serial：设备序列号（adb devices -l 输出第一列），一台设备的唯一标识，如 emulator-5554 / V2183A...
//   status：设备状态（device=正常在线 / offline=离线 / unauthorized=未授权）
//   model：机型名（来自 -l 输出的 model: 字段，可能为空串）
// 注：类型参数写成 <String> 会遮蔽 java.lang.String（历史写法），不影响功能，此处原样保留。
public record Device<String>(String serial, String status, String model) {
}
