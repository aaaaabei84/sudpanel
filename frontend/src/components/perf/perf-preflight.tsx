import { useContext, useEffect, useRef, useState } from 'react'
import { req } from '@/lib/api'
import { PageVisibilityContext } from '@/lib/page-visibility'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'

type Result = { ready: boolean; checkedAt: string; checks: { key: string; name: string; status: 'PASS' | 'WARN' | 'BLOCK' | 'INFO'; message: string }[] }
export function PerfPreflight({ deviceSerial, mode, gameIds, buttonGroupId, disabled }: { deviceSerial: string; mode: string; gameIds: number[]; buttonGroupId?: number | null; disabled?: boolean }) {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  const visible = useContext(PageVisibilityContext)
  const signature = JSON.stringify({ deviceSerial, mode, gameIds, buttonGroupId })
  const previous = useRef(signature)
  useEffect(() => { if (previous.current !== signature) { previous.current = signature; request.current?.abort(); setLoading(false); setResult(null); if (open) setError('设备或配置已改变，请重新检查。') } }, [signature, open])
  useEffect(() => { if (!visible) { request.current?.abort(); setLoading(false); setOpen(false) } }, [visible])
  useEffect(() => () => request.current?.abort(), [])
  const check = () => {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setOpen(true); setLoading(true); setResult(null); setError('')
    void req<Result>('/api/perf/preflight', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceSerial, mode, gameIds, buttonGroupId }) }, { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setResult(value)
    }).catch(reason => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '检查失败')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
  }
  return <>
    <Button type="button" variant="outline" onClick={check} disabled={disabled || loading} data-testid="perf-preflight-open">启动前检查</Button>
    <Dialog open={open} onOpenChange={value => { setOpen(value); if (!value) { request.current?.abort(); setLoading(false) } }}>
      <DialogContent className="flex max-h-[85vh] flex-col overflow-hidden sm:max-w-2xl" data-testid="perf-preflight-dialog">
        <DialogHeader><DialogTitle>启动前只读检查</DialogTitle></DialogHeader>
        <p className="text-xs text-muted-foreground">读取当前设备与配置；不点手机、不唤醒、不清日志、不发业务请求，也不创建测试任务。</p>
        {loading && <p role="status" className="text-sm">正在检查设备、用例绑定和有效名单…</p>}
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        {result && <div className="min-h-0 space-y-3 overflow-auto pr-1">
          <p className={`text-sm font-medium ${result.ready ? 'text-emerald-700 dark:text-emerald-400' : 'text-destructive'}`}>{result.ready ? '当前检查没有阻断项；开始时会再次检查并冻结本次有效配置。' : '以下项目需要修正后再开始。'}</p>
          <p className="text-xs text-muted-foreground">检查时刻：{new Date(result.checkedAt).toLocaleString()}</p>
          {result.checks.map(item => <div key={item.key} className="rounded-md border p-2 text-xs"><div className="flex items-center gap-2"><Badge variant={item.status === 'BLOCK' ? 'destructive' : 'outline'}>{item.status === 'PASS' ? '通过' : item.status === 'BLOCK' ? '阻断' : item.status === 'WARN' ? '待核对' : '说明'}</Badge><span className="font-medium">{item.name}</span></div><p className="mt-1 break-words text-muted-foreground">{item.message}</p></div>)}
        </div>}
        <Button type="button" variant="outline" onClick={check} disabled={loading}>重新检查当前配置</Button>
      </DialogContent>
    </Dialog>
  </>
}
