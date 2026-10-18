import { executionDisplay } from '@/lib/execution-privacy'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { req } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { JsonBlock } from '@/components/json-block'
import { CodeArea } from '@/components/code-area'
import { BatchStats, type Stats, type StatisticsDrilldown } from './batch-stats'
import { BaselineManager } from './baseline-manager'
import { ReportPolicyEditor } from './report-policy-editor'
import type { Comparison } from '@/lib/api-governance'
import { ExecutionMetadata } from './execution-metadata'
import { ActualCurlExport } from './actual-curl'
import type { ApiCase, ApiEnv, ApiExec } from '@/types'

export type HistoricalDraft = { spec: Omit<ApiCase, 'id'>; envId: number | null; warnings: string[]; key: number }
type Page = { items: ApiExec[]; nextBeforeId: number | null }
const failedCase = (row: ApiExec) => {
  if (row.caseId == null || !['FAIL', 'ERROR'].includes(row.verdict)) return false
  try { return !JSON.parse(row.metadata ?? '{}').scenarioId } catch { return false }
}

export function HistoryExplorer({ envs, batchId, onLoad, onFailed }: { envs: ApiEnv[]; batchId: number | null; onLoad: (draft: HistoricalDraft) => void; onFailed: (ids: number[]) => void }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<ApiExec[]>([])
  const [next, setNext] = useState<number | null>(null)
  const [q, setQ] = useState('')
  const [verdict, setVerdict] = useState('')
  const [env, setEnv] = useState('')
  const [scope, setScope] = useState('all')
  const [method, setMethod] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [busy, setBusy] = useState(false)
  const [baselineBusy, setBaselineBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<ApiExec | null>(null)
  const [revealDetail, setRevealDetail] = useState(false)
  const displayDetail = detail ? executionDisplay(detail,revealDetail) : null
  useEffect(() => { setRevealDetail(false) },[detail])
  const [selected, setSelected] = useState<number[]>([])
  const [ignore, setIgnore] = useState('')
  const [comparison, setComparison] = useState<Comparison | null>(null)
  const [stats, setStats] = useState<Stats | null>(null)
  const [drilldown, setDrilldown] = useState<StatisticsDrilldown | null>(null)
  const [includeSecrets, setIncludeSecrets] = useState(false)
  const appliedQuery = useRef('limit=50')
  const sequence = useRef(0)
  const detailSequence = useRef(0)
  const compareSequence = useRef(0)
  const restoreSequence = useRef(0)
  const statsSequence = useRef(0)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; ++sequence.current; ++detailSequence.current; ++compareSequence.current; ++restoreSequence.current; ++statsSequence.current } }, [])
  useEffect(() => { ++statsSequence.current; setStats(null); setDrilldown(null) }, [batchId])
  const load = async (before?: number, filter = drilldown, reset = false) => {
    const generation = ++sequence.current
    ++compareSequence.current; ++detailSequence.current; ++restoreSequence.current
    setBusy(true); setError(null)
    const query = new URLSearchParams(before ? appliedQuery.current : 'limit=50')
    if (!before && !reset) {
      if (q) query.set('q', q)
      if (verdict) query.set('verdict', verdict)
      if (env) query.set('envId', env)
      if (method) query.set('method', method)
      if (from) query.set('from', new Date(from).toISOString())
      if (to) query.set('to', new Date(to).toISOString())
    }
    if (!before && scope === 'adhoc') query.set('adhoc', 'true')
    if (!before && scope === 'batch' && batchId != null) query.set('batchId', String(batchId))
    if (!before && filter && batchId != null) { query.delete('adhoc'); query.set('batchId', String(batchId)); query.set('statsDimension', filter.dimension); query.set('statsKey', filter.key) }
    if (before) query.set('beforeId', String(before))
    try {
      const page = await req<Page>(`/api/apitest/history?${query}`)
      if (generation !== sequence.current) return
      if (!before) { appliedQuery.current = query.toString(); setDrilldown(filter) }
      setRows(previous => before ? [...previous, ...page.items] : page.items); setNext(page.nextBeforeId)
      if (!before) { setSelected([]); setDetail(null); setComparison(null) }
    } catch (e) { if (generation === sequence.current) setError((e as Error).message) }
    finally { if (generation === sequence.current) setBusy(false) }
  }
  const showDetail = async (id: number) => {
    const generation = ++detailSequence.current; setDetail(null)
    try { const value = await req<ApiExec>(`/api/apitest/history/${id}`); if (generation === detailSequence.current) setDetail(value) }
    catch (e) { if (generation === detailSequence.current) toast.error((e as Error).message) }
  }
  const restore = async (id: number) => {
    const generation = ++restoreSequence.current
    try {
      const value = await req<Omit<HistoricalDraft, 'key'>>(`/api/apitest/history/${id}/draft`)
      if (!mounted.current || generation !== restoreSequence.current) return
      ++restoreSequence.current
      onLoad({ ...value, key: Date.now() }); setOpen(false)
    } catch (e) { if (generation === restoreSequence.current) toast.error((e as Error).message) }
  }
  const showStats = async () => {
    if (batchId == null) return
    const generation = ++statsSequence.current
    setStats(null)
    try { const value = await req<Stats>(`/api/apitest/batches/${batchId}/stats`); if (generation === statsSequence.current) setStats(value) }
    catch (e) { if (generation === statsSequence.current) toast.error((e as Error).message) }
  }
  const compare = async () => {
    if (selected.length !== 2) return
    setBusy(true)
    const generation = ++compareSequence.current
    try { const value = await req<Comparison>('/api/apitest/history/compare', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ leftId: selected[0], rightId: selected[1], ignorePaths: ignore.split('\n').map(x => x.trim()).filter(Boolean) }) }); if (generation === compareSequence.current) setComparison(value) }
    catch (e) { if (generation === compareSequence.current) toast.error((e as Error).message) }
    finally { if (generation === compareSequence.current) setBusy(false) }
  }
  return <>
    <Button type="button" size="sm" variant="outline" onClick={() => { setOpen(true); void load() }}>历史检索与对比</Button>
    <Dialog open={open} onOpenChange={v => { if (baselineBusy) return; setOpen(v); if (!v) { ++sequence.current; ++detailSequence.current; ++compareSequence.current; ++restoreSequence.current; ++statsSequence.current; setBusy(false) } }}><DialogContent className="flex h-[90vh] max-h-[95vh] flex-col overflow-hidden sm:max-w-7xl"><DialogHeader><DialogTitle>接口历史检索与证据</DialogTitle></DialogHeader>
      <div className="min-h-0 space-y-3 overflow-y-auto pr-1">
        <div className="flex flex-wrap gap-2"><Input aria-label="历史关键字" className="min-w-40 flex-1" value={q} placeholder="用例名或 URL 关键字" onChange={e => setQ(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') void load() }} /><select aria-label="历史判定" className="h-9 rounded border bg-background px-2 text-sm" value={verdict} onChange={e => setVerdict(e.target.value)}><option value="">全部结果</option>{['PASS', 'FAIL', 'ERROR', 'SKIP'].map(v => <option key={v}>{v}</option>)}</select><select aria-label="历史环境" className="h-9 rounded border bg-background px-2 text-sm" value={env} onChange={e => setEnv(e.target.value)}><option value="">全部环境</option>{envs.map(e => <option key={e.id} value={e.id}>{e.name}</option>)}</select><select aria-label="历史范围" className="h-9 rounded border bg-background px-2 text-sm" value={scope} onChange={e => { setScope(e.target.value); setDrilldown(null) }}><option value="all">全部执行</option><option value="adhoc">单条调试</option>{batchId != null && <option value="batch">当前批次 #{batchId}</option>}</select><Button type="button" disabled={busy} onClick={() => void load()}>查询历史</Button></div>
        <div className="flex flex-wrap items-center gap-2 text-xs"><select aria-label="历史方法" className="h-9 rounded border bg-background px-2 text-sm" value={method} onChange={e => setMethod(e.target.value)}><option value="">全部方法</option>{['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map(v => <option key={v}>{v}</option>)}</select><label className="flex items-center gap-2">开始时间<Input aria-label="历史开始时间" type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} /></label><label className="flex items-center gap-2">结束时间<Input aria-label="历史结束时间" type="datetime-local" value={to} onChange={e => setTo(e.target.value)} /></label><span className="text-muted-foreground">按本机时区选择；点击查询应用筛选。</span></div>
        {error && <p role="alert" className="text-xs text-red-600">历史加载失败：{error}</p>}
        {batchId != null && <div className="flex flex-wrap items-center gap-2 rounded border p-3 text-xs"><span>当前批次 #{batchId}</span><Button type="button" size="sm" variant="outline" onClick={() => void showStats()}>查看耗时与失败分布</Button><a className="underline" href={`/api/apitest/batches/${batchId}/export.json?includeSecrets=${includeSecrets}`} download>JSON 证据</a><a className="underline" href={`/api/apitest/batches/${batchId}/export.junit?includeSecrets=${includeSecrets}`} download>JUnit 报告</a><label className="flex items-center gap-1"><input type="checkbox" checked={includeSecrets} onChange={e => setIncludeSecrets(e.target.checked)} />包含本机实际报文</label><span className="text-muted-foreground">默认遮罩常见凭据；分享前核对自定义敏感字段。</span></div>}
        {drilldown && <div role="status" className="flex flex-wrap items-center gap-2 rounded border p-2 text-xs"><span className="break-all">当前统计筛选 · 批次 #{batchId} · {drilldown.label}</span><Button type="button" size="sm" variant="outline" disabled={busy || baselineBusy} onClick={() => { void load(undefined, null) }}>清除统计筛选</Button></div>}
        {stats && <BatchStats value={stats} disabled={busy || baselineBusy} onFilter={filter => { if (busy || baselineBusy) return; setQ(''); setVerdict(''); setEnv(''); setMethod(''); setFrom(''); setTo(''); setScope('batch'); void load(undefined, filter, true) }} />}
        <ReportPolicyEditor />
        <BaselineManager onBusyChange={setBaselineBusy} sourceExecId={detail?.id ?? selected[0] ?? null} onGenerate={(value, baseline) => {
          ++restoreSequence.current
          onLoad({ spec: { name: `基线：${baseline.name}`, method: 'GET', url: '', headers: '', body: '', note: `由基线 #${baseline.id} 生成的规则，请选择请求并编辑`, expectStatus: value.expectStatus, expectCode: value.expectCode, timeoutMs: 10000, insecure: false, enabled: true, groupId: null, asserts: JSON.stringify(value.rules), options: null }, envId: null, warnings: [...value.warnings, '规则已载入新草稿；请求地址为空，请先选择请求，未修改任何已有用例。'], key: Date.now() })
          setOpen(false)
        }} />
        <div className="flex flex-wrap gap-2 text-xs"><Button type="button" size="sm" variant="outline" disabled={busy || baselineBusy || selected.length !== 2} onClick={() => void compare()}>比较已选两条</Button><Button type="button" size="sm" variant="outline" disabled={baselineBusy || !rows.some(failedCase)} onClick={() => { ++restoreSequence.current; onFailed([...new Set(rows.filter(failedCase).map(r => r.caseId!))]); setOpen(false) }}>加入已显示失败项到执行计划</Button><span className="self-center text-muted-foreground">复跑使用当前用例配置；请求链应整链复跑，不会自动发送。</span></div>
        <div className="overflow-x-auto rounded border"><table className="w-full text-left text-xs"><thead className="bg-muted/50"><tr>{['比较', '记录', '时间', '用例', '方法', '结果', '状态', '耗时', '操作'].map(h => <th key={h} className="whitespace-nowrap p-2">{h}</th>)}</tr></thead><tbody>{rows.map(row => <tr key={row.id} className="border-t"><td className="p-2"><input aria-label={`选择比较记录 ${row.id}`} type="checkbox" checked={selected.includes(row.id)} disabled={busy || baselineBusy || (!selected.includes(row.id) && selected.length >= 2)} onChange={e => { ++compareSequence.current; setComparison(null); setSelected(v => e.target.checked ? [...v, row.id] : v.filter(id => id !== row.id)) }} /></td><td className="p-2">#{row.id}</td><td className="whitespace-nowrap p-2">{row.ts}</td><td className="max-w-56 break-words p-2">{row.caseName}</td><td className="p-2">{row.method}</td><td className="p-2">{row.verdict}</td><td className="p-2">{row.httpStatus ?? '—'}</td><td className="p-2">{row.elapsedMs} ms</td><td className="whitespace-nowrap p-2"><Button type="button" size="sm" variant="ghost" disabled={baselineBusy} onClick={() => void showDetail(row.id)}>详情</Button><Button type="button" size="sm" variant="ghost" disabled={baselineBusy} onClick={() => void restore(row.id)}>载入草稿</Button></td></tr>)}</tbody></table></div>
        {!busy && !error && rows.length === 0 && <p className="text-xs text-muted-foreground">查询成功，没有符合条件的记录。</p>}
        {next != null && <Button type="button" variant="outline" disabled={busy} onClick={() => void load(next)}>加载更早记录</Button>}
        {displayDetail && <section className="space-y-2 rounded border p-3"><p className="text-sm">记录 #{displayDetail!.id} · {displayDetail!.caseName} · {displayDetail!.verdict}</p><label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={revealDetail} onChange={e => setRevealDetail(e.target.checked)} />显示本机敏感原文</label><ActualCurlExport execId={displayDetail!.id} /><ExecutionMetadata text={displayDetail!.metadata} /><div className="grid gap-2 sm:grid-cols-2"><JsonBlock text={displayDetail!.reqHeaders} empty="无" title="实际请求头" /><JsonBlock text={displayDetail!.reqBody} empty="无" title="实际请求体" /><JsonBlock text={displayDetail!.respHeaders} empty="无" title="响应头" /><JsonBlock text={displayDetail!.respBody} empty="无" title="响应体" /></div><JsonBlock text={displayDetail!.assertsResult} empty="未执行断言" title="逐条断言" /></section>}
        <details><summary className="cursor-pointer text-xs">比较时忽略的 JSON Pointer（每行一项）</summary><fieldset disabled={busy}><CodeArea id="history-ignore" formatJson={false} value={ignore} onChange={v => { if (busy) return; ++compareSequence.current; setComparison(null); setIgnore(v) }} placeholder={'/data/time\n/data/nonce'} /></fieldset></details>
        {comparison && <section className="space-y-2 rounded border p-3 text-xs"><p>{comparison.complete ? `发现 ${comparison.differences.length} 处差异` : '快照不完整或旧记录完整性未知；不能据此认定完全一致'}</p>{comparison.warnings.map((w, i) => <p key={i} className="text-amber-700 dark:text-amber-400">{w}</p>)}{comparison.differences.map((d, i) => <div key={i} className="space-y-1 border-t pt-2"><p className="font-mono">{d.section} · {d.path} · {d.kind}</p><div className="grid gap-2 sm:grid-cols-2"><JsonBlock text={d.left} empty="缺失" title="左侧" /><JsonBlock text={d.right} empty="缺失" title="右侧" /></div></div>)}</section>}
      </div>
    </DialogContent></Dialog>
  </>
}
