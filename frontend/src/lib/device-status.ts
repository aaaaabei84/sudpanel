import type { Device } from '@/types'

export function deviceStatusLabel(status: string): string {
  const labels: Record<string, string> = { device: '在线', unauthorized: '未授权', offline: '离线', absent: '未连接', unreachable: '无法访问' }
  return labels[status] ?? status
}

/** 根据实际ADB状态给出下一步；查询失败在调用方单独展示，不能当成成功查到0台。 */
export function deviceStatusHint(devices: readonly Device[]): string {
  if (devices.some(d => d.status === 'unauthorized')) return '有手机未授权：解锁手机，接受“允许 USB 调试”，再刷新设备。'
  if (devices.some(d => d.status === 'offline')) return '有手机离线：检查 USB 连接、保持手机解锁，必要时重新连接数据线。'
  if (devices.length === 0) return '未发现设备：检查数据线、USB 调试和手机上的授权提示。'
  if (!devices.some(d => d.status === 'device')) return '设备暂不可用：检查连接状态后刷新设备。'
  return ''
}

export function formatDeviceCheckedAt(timestamp: number): string {
  const date = new Date(timestamp)
  if (!Number.isFinite(date.getTime())) return '未知'
  return [date.getHours(), date.getMinutes(), date.getSeconds()].map(n => String(n).padStart(2, '0')).join(':')
}
