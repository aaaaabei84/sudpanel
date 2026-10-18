import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { req } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { ApiBatch } from '@/types'
interface Difference { changes: { seq: number | null; caseId: number | null; field: string; status: string }[]; changed: boolean; currentAvailable: boolean; warnings: string[] }
interface Plan { kind: string; capturedAt: string; stepCount: number; rowCount: number; repeatCount: number; dataColumns: string[]; steps: { seq: number; name: string; envName: string; method: string; cleanup: boolean; polling: boolean }[] }
interface Retry { available: boolean; failedSteps: number; rows: number; wholeScenario: boolean; originalRounds: number[]; warnings: string[] }
export function BatchReplay({ batchId, running, onRun }: { batchId: number; running: boolean; onRun: (batch: ApiBatch) => void }) {
  const [open, setOpen] = useState(false), [loading, setLoading] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [plan, setPlan] = useState<Plan | null>(null), [difference, setDifference] = useState<Difference | null>(null), [retry, setRetry] = useState<Retry | null>(null)
  const [source, setSource] = useState<'ORIGINAL' | 'CURRENT'>('ORIGINAL'), [confirmed, setConfirmed] = useState(false)
  const sequence = useRef(0)
  useEffect(() => {
    setPlan(null); setDifference(null); setRetry(null); setConfirmed(false); setSource('ORIGINAL')
    if (!open) return
    const seq = ++sequence.current; setLoading(true); setError('')
    void Promise.all([req<Plan>(`/api/apitest/batches/${batchId}/plan`), req<Difference>(`/api/apitest/batches/${batchId}/plan/diff`), req<Retry>(`/api/apitest/batches/${batchId}/retry-failed/preview`)]).then(([p, d, r]) => { if (seq === sequence.current) { setPlan(p); setDifference(d); setRetry(r) } }).catch(e => { if (seq === sequence.current) setError(e.message) }).finally(() => { if (seq === sequence.current) setLoading(false) })
    return () => { ++sequence.current }
  }, [batchId, open])
  const start = async (failedOnly: boolean) => {
    setBusy(true)
    try {
      const result = await req<ApiBatch>(`/api/apitest/batches/${batchId}/${failedOnly ? 'retry-failed' : 'replay'}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source, confirmConfigChanges: confirmed }) })
      setOpen(false); onRun(result); toast.success(`已创建新批次 #${result.id}`)
    } catch (problem) { setError(problem instanceof Error ? problem.message : String(problem)) }
    finally { setBusy(false) }
  }
  const disabled = loading || busy || running || !plan || (source === 'CURRENT' && (!difference?.currentAvailable || (difference.changed && !confirmed)))
  return <><Button size="sm" variant="outline" onClick={() => setOpen(true)}>执行计划与重跑</Button><Dialog open={open} onOpenChange={value => { if (!busy) setOpen(value) }}><DialogContent className="flex max-h-[90vh] flex-col sm:max-w-4xl"><DialogHeader><DialogTitle>批次 #{batchId} · 执行计划</DialogTitle><DialogDescription>查看原计划与当前配置差异，明确选择新批次使用哪套配置。</DialogDescription></DialogHeader>
    <div className="min-h-0 space-y-3 overflow-y-auto text-sm">{loading && <p role="status">正在读取冻结计划…</p>}{error && <p role="alert" className="text-amber-700 dark:text-amber-400">{error}</p>}
      {plan && <><p>{plan.kind} · 冻结于 {plan.capturedAt} · {plan.stepCount} 步 · {plan.rowCount} 个数据行 · {plan.repeatCount < 0 ? '持续' : plan.repeatCount} 轮</p><div className="rounded border">{plan.steps.map(step => <p key={step.seq} className="border-b p-2 text-xs">{step.seq}. {step.method} {step.name} · {step.envName}{step.cleanup ? ' · 清理' : ''}{step.polling ? ' · 条件轮询' : ''}</p>)}</div>{difference?.changes.length ? <div className="rounded border p-3 text-xs"><p className="mb-2 font-medium">当前配置已有变化</p>{difference.changes.map((change, i) => <p key={i}>{change.seq == null ? '场景' : `步骤 ${change.seq}`} · {change.field} · {change.status}</p>)}</div> : <p className="text-xs text-muted-foreground">当前配置与原计划未发现差异。</p>}
        <div className="space-y-2 rounded border p-3"><label className="flex items-center gap-2"><input type="radio" checked={source === 'ORIGINAL'} onChange={() => { setSource('ORIGINAL'); setConfirmed(false) }} />原计划：原用例、环境、数据行与原迭代值</label><label className="flex items-center gap-2"><input type="radio" checked={source === 'CURRENT'} disabled={!difference?.currentAvailable} onChange={() => { setSource('CURRENT'); setConfirmed(false) }} />当前配置：重新读取用例和环境，保留原数据行</label>{source === 'CURRENT' && difference?.changed && <label className="flex items-center gap-2 text-xs"><Checkbox checked={confirmed} onCheckedChange={value => setConfirmed(value === true)} />已核对上方差异，新批次使用当前配置</label>}</div>
        {retry && <p className="text-xs">服务端在整个批次内找到 {retry.failedSteps} 个最终失败步骤、{retry.rows} 个受影响数据行。{retry.wholeScenario ? '请求链将对这些数据行重跑整条链，并执行清理步骤。' : '普通批次仅重跑最终失败的用例。'}中间轮询尝试不会单独算成待重跑失败。</p>}
        {[...(difference?.warnings ?? []), ...(retry?.warnings ?? [])].map((warning, index) => <p key={index} className="text-xs text-muted-foreground">{warning}</p>)}
      </>}
    </div><div className="flex shrink-0 flex-wrap justify-end gap-2 border-t pt-3"><Button variant="outline" disabled={disabled} onClick={() => void start(false)}>新建完整重跑批次</Button><Button disabled={disabled || !retry?.available} onClick={() => void start(true)}>仅重跑最终失败项</Button></div>
  </DialogContent></Dialog></>
}
