import { useContext, useEffect, useState } from 'react'
import { useDirtyGuard } from '@/lib/navigation-guard'
import { PageVisibilityContext } from '@/lib/page-visibility'
import { perfContinuation, type PerfPlan, type PerfStartResult } from '@/lib/perf-continuation'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'

export function PerfFrozenPlan({ runId, resume, onClose, onResumed }: { runId: number | null; resume?: boolean; onClose: () => void; onResumed: (result: PerfStartResult) => void }) {
  const visible = useContext(PageVisibilityContext)
  const [plan, setPlan] = useState<PerfPlan | null>(null), [loading, setLoading] = useState(false), [error, setError] = useState(''), [busy, setBusy] = useState(false), [confirmed, setConfirmed] = useState(false), [retry, setRetry] = useState(0)
  useDirtyGuard({ label: '性能续跑', dirty: false, locked: busy, discard: () => {} })
  useEffect(() => {
    const controller = new AbortController(); setPlan(null); setError(''); setConfirmed(false)
    if (runId == null || !visible) { setLoading(false); return () => controller.abort() }
    setLoading(true)
    void perfContinuation.plan(runId, controller.signal).then(value => { if (!controller.signal.aborted) { if (!value || !['FROZEN', 'LEGACY_UNKNOWN'].includes(value.state) || value.state === 'FROZEN' && !value.hash) throw Error('冻结配置状态或指纹无效，已阻止续跑'); setPlan(value) } }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '冻结配置读取失败') }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [runId, visible, retry])
  const execute = async () => {
    if (!plan || runId == null || busy || (plan.state === 'LEGACY_UNKNOWN' && !confirmed)) return
    setBusy(true); setError('')
    try { const result = await perfContinuation.resume(runId, plan.state === 'FROZEN' ? 'FROZEN' : 'CAPTURE_CURRENT'); onResumed(result); onClose() }
    catch (cause) { setError(cause instanceof Error ? cause.message : '续跑失败') }
    finally { setBusy(false) }
  }
  return <Dialog open={runId != null} onOpenChange={open => { if (!open && !busy) onClose() }}><DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-3xl" showCloseButton={!busy} data-testid="perf-plan-dialog">
    <DialogHeader><DialogTitle>运行 #{runId} · {resume ? '确认续跑配置' : '冻结配置与来源'}</DialogTitle></DialogHeader>
    {loading && <p role="status">读取本次冻结配置…</p>}
    {error && <p role="alert" className="text-xs text-destructive" data-testid="perf-plan-error">{error}{!plan && <Button size="sm" variant="ghost" onClick={() => setRetry(value => value + 1)}>重试读取</Button>}</p>}
    {plan && <div className="min-h-0 space-y-4 overflow-auto pr-1">
      {plan.state === 'LEGACY_UNKNOWN' ? <section className="space-y-3 rounded-md border border-amber-500/40 bg-amber-500/5 p-3" data-testid="perf-plan-legacy">
        <Badge variant="outline">旧运行 · 原配置未知</Badge><p className="text-sm">此运行未保存完整冻结计划，不能从现在的配置反推当时的环境、变量、签名或推送名单。</p>
        {resume ? <><p className="text-xs text-muted-foreground">按当前配置续跑会先预检并生成一个新的快照，来源记为“按当前配置续跑”，时间为现在。既有圈的未知来源会保留；继续的圈使用新快照。</p><label className="flex items-start gap-2 text-xs"><Checkbox checked={confirmed} onCheckedChange={value => setConfirmed(value === true)} data-testid="perf-resume-current-confirm" /><span>我确认按当前配置创建新的续跑快照，并保留旧圈来源未知。</span></label></> : <p className="text-xs text-muted-foreground">需要继续执行时，可在“继续跑”中明确选择按当前配置捕获新快照。</p>}
      </section> : <>
        <section className="space-y-2 rounded-md border p-3" data-testid="perf-plan-frozen"><div className="flex flex-wrap items-center gap-2"><Badge variant="secondary">已持久冻结</Badge><span className="text-xs">{plan.origin === 'RESUME_CURRENT' ? '按当前配置续跑时捕获' : '启动时捕获'}</span></div><p className="text-xs text-muted-foreground">捕获时间：{plan.capturedAt ? new Date(plan.capturedAt).toLocaleString() : '未记录'} · 模式 {plan.mode}</p><p className="break-all font-mono text-xs">指纹 {plan.hash}</p><p className="text-xs">续跑沿用这些用例、环境、变量、签名、游戏计划、关键开关与坐标选择。之后编辑当前配置不会改动本运行。</p></section>
        {!!plan.caseSources?.length && <section className="space-y-2"><h3 className="text-sm font-medium">接口及环境来源</h3>{plan.caseSources.map(source => <div key={source.role} className="flex flex-wrap justify-between gap-2 rounded-md border p-2 text-xs"><strong>{source.role}</strong><span>{source.name} · #{source.caseId}</span><span className="text-muted-foreground">{source.envName} · 环境 #{source.envId}</span></div>)}</section>}
        {!!plan.effectiveGames?.length && <section className="space-y-2"><h3 className="text-sm font-medium">本次有效游戏计划</h3>{plan.effectiveGames.map(game => <div key={game.gameId} className="rounded-md border p-2 text-xs"><strong>{game.name || `游戏 #${game.gameId}`}</strong><p className="mt-1 text-muted-foreground">{game.rosterSource === 'CUSTOM_BODY' ? '使用自定义推送体，数量配置不覆盖它' : game.rosterSource === 'CASE_BODY' ? '使用冻结用例原名单' : `真人 ${game.humanCount ?? '未知'} + 人机 ${game.aiCount ?? '未知'}`} · 局内 {game.gameSeconds ?? '未知'} 秒</p></div>)}</section>}
        <p className="text-xs text-muted-foreground">坐标选择：{plan.coordinateGroupId == null ? '本机固定／缓存配置快照' : `分组 #${plan.coordinateGroupId}`}；具体坐标与凭据仅保存在本机。</p>
        {plan.judgeLimits && <details className="rounded-md border p-2 text-xs"><summary className="cursor-pointer">查看冻结判定阈值</summary><dl className="mt-2 grid grid-cols-2 gap-2">{Object.entries(plan.judgeLimits).map(([key, value]) => <div key={key}><dt className="text-muted-foreground">{key}</dt><dd className="font-mono">{value}</dd></div>)}</dl></details>}
      </>}
      <p className="rounded-md bg-muted/50 p-3 text-xs text-muted-foreground">{plan.cookiePolicy || '每个运行使用独立 Cookie 会话；续跑从空会话开始。需要登录的接口应先完成相应登录步骤。'}</p>
      {resume && <Button type="button" disabled={busy || (plan.state === 'LEGACY_UNKNOWN' && !confirmed)} onClick={() => void execute()} data-testid="perf-resume-confirm">{busy ? '正在校验并续跑…' : plan.state === 'FROZEN' ? '沿原快照续跑' : '按当前配置捕获快照并续跑'}</Button>}
    </div>}
  </DialogContent></Dialog>
}
