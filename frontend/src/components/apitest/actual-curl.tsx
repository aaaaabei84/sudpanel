import { useEffect, useState } from 'react'
import { Copy, Loader2, Terminal } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { httpContinuation } from '@/lib/http-continuation'
import type { ActualCurl } from '@/lib/http-continuation'
import type { ApiCaseInput } from '@/lib/api'

type Result = { spec: ApiCaseInput | null | undefined; envId: number | null | undefined; execId: number | null | undefined; secrets: boolean; value: ActualCurl | null; error: string }

/** 仅请求后端纯导出接口，不执行curl；敏感值只在这次打开的弹窗内保留。 */
export function ActualCurlExport({ spec, envId, execId, label = '导出实际 cURL', className = '' }: {
  spec?: ApiCaseInput | null; envId?: number | null; execId?: number | null; label?: string; className?: string
}) {
  const [open, setOpen] = useState(false)
  const [secrets, setSecrets] = useState(false)
  const [result, setResult] = useState<Result | null>(null)
  const current = result?.spec === spec && result?.envId === envId && result?.execId === execId && result?.secrets === secrets ? result : null
  const loading = open && current == null
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    let active = true
    const input = { spec, envId, execId, secrets }
    const request = execId != null ? httpContinuation.historyCurl(execId, secrets, controller.signal)
      : spec ? httpContinuation.actualCurl(spec, envId, secrets, controller.signal) : Promise.reject(new Error('没有可导出的请求'))
    request.then(value => { if (active) setResult({ ...input, value, error: '' }) })
      .catch(error => { if (active && !controller.signal.aborted) setResult({ ...input, value: null, error: (error as Error).message }) })
    return () => { active = false; controller.abort() }
  }, [open, spec, envId, execId, secrets])
  const changeOpen = (value: boolean) => { setResult(null); setSecrets(false); setOpen(value) }
  return <>
    <Button type="button" size="sm" variant="outline" className={className} disabled={execId == null && !spec} onClick={() => changeOpen(true)}><Terminal className="size-3.5" />{label}</Button>
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent className="flex max-h-[88vh] flex-col overflow-hidden sm:max-w-4xl">
        <DialogHeader><DialogTitle>{execId != null ? '历史应用请求 · cURL' : '本次组装应用请求 · cURL'}</DialogTitle></DialogHeader>
        <p className="text-xs text-muted-foreground">导出不会发送接口请求。默认隐藏请求头值、查询值和正文；需要复现时，显式包含敏感值后再复制。路径和主机名仍需分享前检查。</p>
        <label className="flex items-center gap-2 text-sm"><Checkbox aria-label="cURL 包含敏感值" checked={secrets} onCheckedChange={value => { setResult(null); setSecrets(value === true) }} />包含敏感值（仅本次弹窗）</label>
        {loading && <p role="status" className="flex items-center gap-2 text-sm"><Loader2 className="size-4 animate-spin" />正在组装导出…</p>}
        {current?.error && <p role="alert" className="text-sm text-amber-700 dark:text-amber-400">{current.error}</p>}
        {current?.value && <>
          <p className="text-xs text-muted-foreground">{current.value.source === 'HISTORY' ? '历史快照' : '导出时重新组装'} · {current.value.complete ? '应用正文完整' : '旧记录完整性未知'} · {current.value.generatedAt}</p>
          <div className="max-h-32 shrink-0 overflow-auto space-y-1 rounded border bg-muted/20 p-2 text-xs text-amber-700 dark:text-amber-400">{current.value.warnings.map((warning, index) => <p key={index}>{warning}</p>)}</div>
          <textarea aria-label="实际请求 cURL" readOnly value={current.value.curl} className="min-h-56 flex-1 resize-none overflow-auto rounded-md border bg-background p-3 font-mono text-xs" />
          <Button type="button" className="self-end" onClick={() => void navigator.clipboard.writeText(current.value!.curl).then(() => toast.success(secrets ? '已复制含敏感值的 cURL' : '已复制脱敏 cURL')).catch(() => toast.error('复制失败'))}><Copy className="size-4" />复制{secrets ? '完整' : '脱敏'} cURL</Button>
        </>}
      </DialogContent>
    </Dialog>
  </>
}
