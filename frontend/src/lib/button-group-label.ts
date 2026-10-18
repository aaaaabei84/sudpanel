import type { PerfButtonGroup } from '@/types'

/**
 * 按钮坐标分组下拉选项的展示文案（perf 页与 apptomg 页共用，避免两处各写一份而漂移）。
 *
 * 【为什么要求"完整显示"】此前选项文案只取设备序列号的**末 4 位**（`…1234`），
 * 同一台机器换了 USB 口、或两台同型号设备时无法区分，用户也看不到完整型号。
 * 现在改为原样输出分组记录的整串设备标识；配合加宽的 SelectTrigger 即可完整显示。
 *
 * 说明：分组自己没绑设备时按「通用」展示（该分组可跨设备复用）。
 *
 * @param g 按钮坐标分组
 * @returns 形如 `客厅机（绑设备 R5CR30XXXXXX）` 或 `通用坐标（通用）`
 */
export function groupOptionLabel(g: PerfButtonGroup): string {
  return `${g.name}（${g.deviceSerial ? `绑设备 ${g.deviceSerial}` : '通用'}）`
}
