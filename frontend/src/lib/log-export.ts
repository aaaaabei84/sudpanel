/** 导出原始日志行；过滤索引覆盖整个缓冲，而非界面的虚拟渲染窗口。 */
export function logExportText(lines: readonly string[], indexes?: readonly number[]): string {
  const selected = indexes == null ? lines : indexes.filter(i => Number.isInteger(i) && i >= 0 && i < lines.length).map(i => lines[i])
  return selected.length ? selected.join('\n') + '\n' : ''
}

/** 原文保留为 UTF-8 .log，不添加会干扰机器检索的头部或BOM。 */
export function downloadLogExport(text: string, scope: 'filtered' | 'all'): void {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `sudpanel-logcat-${scope}-${stamp}.log`
  document.body.appendChild(link)
  try { link.click() }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
}

export function downloadLogMetadata(value: unknown): void {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = `sudpanel-logcat-metadata-${new Date().toISOString().replace(/[:.]/g, '-')}.json`
  document.body.appendChild(link)
  try { link.click() } finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
}
