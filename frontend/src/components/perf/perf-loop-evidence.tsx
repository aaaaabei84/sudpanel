import { executionDisplay } from '@/lib/execution-privacy'
import { useEffect, useState } from 'react'
import { req } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { JsonBlock } from '@/components/json-block'
import { ExecutionMetadata } from '@/components/apitest/execution-metadata'
import type { ApiExec } from '@/types'

type Step = { id: number; step: string; verdict?: string; reason?: string; httpStatus?: number; elapsedMs?: number; createdAt: string; truncated?: boolean; evidenceUnreadable?: boolean }
type Evidence = { recorded: boolean; steps: Step[]; cleanup: { status: 'SUCCESS' | 'FAILED' | 'NOT_RECORDED'; reason?: string; evidenceMissing?: boolean | null; updatedAt?: string } }
type Detail = { id: number; step: string; exec: ApiExec; truncated: boolean }

function read<T>(url: string, signal: AbortSignal): Promise<T> {
  return req<T>(url, undefined, { signal })
}

export function PerfLoopEvidence({ loopId, onClose }: { loopId: number | null; onClose: () => void }) {
  const [evidence, setEvidence] = useState<Evidence | null>(null)
  const [selected, setSelected] = useState<number | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [reveal, setReveal] = useState(false)
  const displayExec = detail ? executionDisplay(detail.exec,reveal) : null
  useEffect(() => { setReveal(false) },[detail])
  const [loading, setLoading] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setEvidence(null); setSelected(null); setDetail(null); setError('')
    if (loopId == null) { setLoading(false); return () => controller.abort() }
    setLoading(true)
    void read<Evidence>(`/api/perf/loops/${loopId}/evidence`, controller.signal).then(value => {
      if (controller.signal.aborted) return
      setEvidence(value); setSelected(value.steps[0]?.id ?? null)
    }).catch(reason => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '取证记录读取失败')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [loopId, refresh])

  useEffect(() => {
    const controller = new AbortController()
    setDetail(null); setDetailError('')
    if (selected == null) { setDetailLoading(false); return () => controller.abort() }
    setDetailLoading(true)
    void read<Detail>(`/api/perf/steps/${selected}`, controller.signal).then(value => {
      if (!controller.signal.aborted) setDetail(value)
    }).catch(reason => {
      if (!controller.signal.aborted) setDetailError(reason instanceof Error ? reason.message : '步骤详情读取失败')
    }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false) })
    return () => controller.abort()
  }, [selected, refresh])

  return <Dialog open={loopId != null} onOpenChange={open => { if (!open) onClose() }}>
    <DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-5xl" data-testid="perf-evidence-dialog">
      <DialogHeader><DialogTitle>本圈接口步骤与清房结果</DialogTitle></DialogHeader>
      <p className="text-xs text-muted-foreground">本机取证保留实际请求、鉴权和业务数据，请核对后分享。本窗为本机原始报文；分享请使用页面上的脱敏报告或 ZIP。</p>
      <div className="flex items-center gap-2 text-xs"><Button type="button" variant="outline" size="sm" onClick={() => setRefresh(value => value + 1)} disabled={loading}>刷新记录</Button><span>按真实执行顺序展示，重试会保留多次记录。</span></div>
      {loading && <p role="status" className="text-sm">读取接口步骤…</p>}
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {evidence && <div className="min-h-0 space-y-3 overflow-auto pr-1">
        <div className="rounded-md border p-3 text-sm" data-testid="perf-cleanup-state">
          <div className="flex flex-wrap items-center gap-2"><span className="font-medium">结束游戏／清房</span><Badge variant={evidence.cleanup.status === 'FAILED' ? 'destructive' : 'outline'}>{evidence.cleanup.status === 'SUCCESS' ? '均已通过' : evidence.cleanup.status === 'FAILED' ? '未全部成功' : '未记录'}</Badge></div>
          {evidence.cleanup.reason && <p className="mt-1 break-words text-xs text-muted-foreground">{evidence.cleanup.reason}</p>}
          {evidence.cleanup.evidenceMissing === true && <p role="status" className="mt-1 text-xs text-amber-700 dark:text-amber-400">本圈曾发生证据写入失败，以下记录可能缺步骤；业务执行结果未因此改判。</p>}
          <p className="mt-1 text-xs text-muted-foreground">清理结论独立于采样是否有效。未记录时无法证明已清房；进程中断且未写入的取证失败也无法在重启后完整还原。</p>
        </div>
        {!evidence.recorded && <p className="text-sm text-muted-foreground">本圈没有接口取证记录，可能是旧记录、未执行对应步骤或存证失败。</p>}
        {evidence.steps.length > 0 && <div className="grid min-w-0 gap-3 md:grid-cols-[220px_minmax(0,1fr)]">
          <div className="max-h-72 space-y-1 overflow-auto rounded-md border p-2 md:max-h-[55vh]" aria-label="接口步骤列表">
            {evidence.steps.map((step, index) => <button key={step.id} type="button" data-testid={`perf-step-${step.id}`} aria-pressed={selected === step.id} onClick={() => setSelected(step.id)} className={`w-full rounded-md p-2 text-left text-xs ${selected === step.id ? 'bg-muted' : 'hover:bg-muted/50'}`}>
              <div className="flex items-start justify-between gap-2"><span>{index + 1}. {step.step}</span><span className={step.verdict === 'PASS' ? 'text-emerald-700 dark:text-emerald-400' : 'text-destructive'}>{step.verdict ?? '不可读'}</span></div>
              <p className="mt-1 text-xs text-muted-foreground">{step.httpStatus ?? '无 HTTP 响应'} · {step.elapsedMs ?? '—'}ms</p>
              {(step.truncated || step.evidenceUnreadable) && <p className="text-xs text-amber-700">{step.evidenceUnreadable ? '证据无法解析' : '报文有截断'}</p>}
            </button>)}
          </div>
          <div className="min-w-0 space-y-3 rounded-md border p-3">
            {detailLoading && <p role="status" className="text-sm">读取实际报文…</p>}
            {detailError && <p role="alert" className="text-sm text-destructive">{detailError}</p>}
            {detail && <>
              <label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={reveal} onChange={e => setReveal(e.target.checked)} />显示本机敏感原文</label>
              <div className="space-y-1 text-xs"><p className="font-medium">{detail.step} · {displayExec!.verdict}</p><p className="break-all font-mono">{displayExec!.method} {displayExec!.url}</p><p className="break-words text-muted-foreground">{displayExec!.reason || displayExec!.error || '请求与校验均已通过'}</p></div>
              {detail.truncated && <p role="status" className="text-xs text-amber-700 dark:text-amber-400">保存内容有截断，不能作为完整报文取证。</p>}
              <ExecutionMetadata text={displayExec!.metadata} />
              <section className="space-y-1"><h3 className="text-xs font-medium">实际请求头</h3><JsonBlock text={displayExec!.reqHeaders} title="实际请求头" empty="未记录请求头" /></section>
              <section className="space-y-1"><h3 className="text-xs font-medium">实际请求体</h3><JsonBlock text={displayExec!.reqBody} title="实际请求体" empty="无请求体或未发送" /></section>
              <section className="space-y-1"><h3 className="text-xs font-medium">响应头</h3><JsonBlock text={displayExec!.respHeaders} title="响应头" empty="无响应头" /></section>
              <section className="space-y-1"><h3 className="text-xs font-medium">响应体</h3><JsonBlock text={displayExec!.respBody} title="响应体" empty="无响应体" /></section>
              <section className="space-y-1"><h3 className="text-xs font-medium">逐条校验结果</h3><JsonBlock text={displayExec!.assertsResult} title="逐条校验结果" empty="未记录断言结果" /></section>
            </>}
          </div>
        </div>}
      </div>}
    </DialogContent>
  </Dialog>
}
