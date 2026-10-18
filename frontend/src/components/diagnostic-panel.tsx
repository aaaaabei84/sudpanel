import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Copy, Download, Trash2 } from 'lucide-react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { clearDiagnostics, diagnosticsSnapshot } from '@/lib/diagnostics'

export function DiagnosticPanel() {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState(diagnosticsSnapshot)
  useEffect(() => {
    const update = () => setRows(diagnosticsSnapshot())
    window.addEventListener('sudpanel:diagnostic', update)
    return () => window.removeEventListener('sudpanel:diagnostic', update)
  }, [])
  const download = () => {
    const blob = new Blob([JSON.stringify({ schema: 1, generatedAt: new Date().toISOString(), diagnostics: rows }, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = 'sudpanel-diagnostics.json'; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <><Button variant="ghost" size="sm" onClick={() => setOpen(true)}>诊断{rows.length ? ` · ${rows.length}` : ''}</Button>
    <Dialog open={open} onOpenChange={setOpen}><DialogContent className="max-h-[85vh] overflow-auto sm:max-w-3xl"><DialogHeader><DialogTitle>本次会话诊断</DialogTitle></DialogHeader>
      <p className="text-xs text-muted-foreground">仅记录编号、时间、类型及HTTP状态。导出不附带请求正文、凭据、设备标识或本机路径。后端编号可用于查找本机服务日志。</p>
      <div className="flex gap-2"><Button size="sm" variant="outline" disabled={!rows.length} onClick={download}><Download className="size-3.5" />导出安全诊断</Button><Button size="sm" variant="outline" disabled={!rows.length} onClick={clearDiagnostics}><Trash2 className="size-3.5" />清空本次记录</Button></div>
      {!rows.length && <p className="py-6 text-center text-sm text-muted-foreground">本次会话暂无诊断记录</p>}
      <div className="space-y-2">{rows.map(row => <div key={row.id + row.at} className="flex flex-wrap items-center gap-3 rounded border p-3 text-xs"><time>{new Date(row.at).toLocaleTimeString()}</time><span>{row.kind} {row.method} {row.status ?? ''}</span><code className="min-w-0 break-all">{row.id}</code><Button size="sm" variant="ghost" className="ml-auto" onClick={() => void navigator.clipboard.writeText(row.id).then(() => toast.success('已复制诊断编号')).catch(() => toast.error('复制失败，可手动选择编号'))}><Copy className="size-3.5" />复制编号</Button></div>)}</div>
    </DialogContent></Dialog></>
}
