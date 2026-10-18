import { useContext, useEffect, useMemo, useRef, useState } from 'react'
import { PageVisibilityContext } from '@/lib/page-visibility'
import { useDirtyGuard } from '@/lib/navigation-guard'
import { perfContinuation, settlementLabel, type PerfEvidenceLoop, type SettlementResult, type SettlementRules } from '@/lib/perf-continuation'
import type { ApiCase } from '@/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

const initial: SettlementRules = { recordPath: 'data.game_settle', roundIdPath: 'game_round_id', roomIdPath: 'room_id', gameIdPath: 'mg_id_str', playersPath: 'results', playerIdPath: 'uid', timestampPath: 'battle_end_at', timestampUnit: 'SECONDS', businessCodePath: 'ret_code', successCodes: ['0'], pendingCodes: [], noDataCodes: [], participantRule: 'EXACT', timeToleranceMs: 120000, participation: { startRecordPath: 'data.game_start', roundIdPath: 'game_round_id', roomIdPath: 'room_id', gameIdPath: 'mg_id_str', playersPath: 'players', playerIdPath: 'uid', completeRoster: false, capacityPath: '', capacitySource: '' } }
const initialText = JSON.stringify(initial, null, 2)
const selectStyle = 'h-8 w-full rounded-md border bg-background px-2 text-xs'
function parseRules(text: string): SettlementRules {
  const value: unknown = JSON.parse(text)
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('规则必须是 JSON 对象')
  const rule = value as Record<string, unknown>
  for (const key of ['recordPath', 'roundIdPath', 'businessCodePath']) if (typeof rule[key] !== 'string' || !rule[key].trim()) throw Error('记录路径、对局 ID 路径和业务码路径必须填写')
  for (const key of ['recordPath', 'roundIdPath', 'businessCodePath', 'roomIdPath', 'gameIdPath', 'playersPath', 'playerIdPath', 'timestampPath']) if (rule[key] !== undefined && (typeof rule[key] !== 'string' || rule[key].length > 512)) throw Error('响应路径必须为最多 512 字符的文本')
  for (const key of ['successCodes', 'pendingCodes', 'noDataCodes']) if (rule[key] !== undefined && (!Array.isArray(rule[key]) || rule[key].length > 20 || rule[key].some((item: unknown) => typeof item !== 'string' || item.length > 256))) throw Error('状态码必须是最多 20 项的字符串数组')
  if (!Array.isArray(rule.successCodes) || rule.successCodes.length === 0) throw Error('至少配置一个成功业务码')
  if (rule.participantRule && !['EXACT', 'SUBSET_ALLOWED'].includes(String(rule.participantRule))) throw Error('参局规则无效')
  if (rule.timestampUnit && !['SECONDS', 'MILLIS', 'ISO'].includes(String(rule.timestampUnit))) throw Error('时间单位无效')
  if (rule.timeToleranceMs !== undefined && (typeof rule.timeToleranceMs !== 'number' || !Number.isSafeInteger(rule.timeToleranceMs) || rule.timeToleranceMs < 0 || rule.timeToleranceMs > 86400000)) throw Error('时间容差须为 0～86400000 的整数毫秒')
  if (rule.participation !== undefined) {
    if (!rule.participation || typeof rule.participation !== 'object' || Array.isArray(rule.participation)) throw Error('实际参赛证据规则必须是对象')
    const source = rule.participation as Record<string, unknown>
    for (const key of ['startRecordPath', 'roundIdPath', 'roomIdPath', 'gameIdPath', 'playersPath', 'playerIdPath', 'capacityPath']) if (source[key] !== undefined && (typeof source[key] !== 'string' || source[key].length > 512)) throw Error('参赛证据路径必须为最多512字符的文本')
    if (source.completeRoster !== undefined && typeof source.completeRoster !== 'boolean') throw Error('完整名单确认必须为布尔值')
    if (source.capacitySource !== undefined && (typeof source.capacitySource !== 'string' || source.capacitySource.length > 256)) throw Error('容量来源说明最多256字符')
  }
  return value as SettlementRules
}
function display(value: unknown) { return value == null ? '未提供' : typeof value === 'string' ? value : JSON.stringify(value) }
function sourceLabel(value?: string) { return value?.replace('LOCAL_PUSH_REQUEST_CANDIDATES', '本机推送请求（候选）').replace('SAME_QUERY_START_REPORT:', '同次查询开局报告：').replace('QUERY_SETTLEMENT_RECORD:', '同次查询结算报告：').replace('NOT_RECORDED', '未记录') || '未记录' }
function Result({ result }: { result: SettlementResult }) {
  return <section className="space-y-3 rounded-md border p-3" data-testid="perf-settlement-result">
    <div className="flex flex-wrap items-center justify-between gap-2"><Badge variant={['ERROR', 'MISMATCH'].includes(result.status) ? 'destructive' : result.status === 'SUCCESS' ? 'secondary' : 'outline'}>{settlementLabel(result.status)}</Badge><span className="text-xs text-muted-foreground">{new Date(result.checkedAt).toLocaleString()}</span></div>
    <p className="text-xs text-muted-foreground" data-testid="perf-settlement-semantics">{result.semanticsVersion === 2 ? '本次使用实际参赛证据规则 V2；推送名单仅为候选。' : '历史规则 V1：可能按推送名单比较；原结论保持，需按实际参赛证据重新核验。'}</p>
    {result.participantEvidence && <section className="space-y-1 rounded bg-muted/50 p-2 text-xs" data-testid="perf-settlement-participation"><p>候选 {display(result.participantEvidence.candidateCount)} · 容量 {display(result.participantEvidence.capacity)} · 实际参赛 {display(result.participantEvidence.actualParticipantCount)} · 结算 {display(result.participantEvidence.settlementCount)}</p><p className="break-all text-xs text-muted-foreground">候选来源：{sourceLabel(result.participantEvidence.candidateSource)}；容量来源：{sourceLabel(result.participantEvidence.capacitySource)}；实际来源：{sourceLabel(result.participantEvidence.actualParticipantSource)}；结算来源：{sourceLabel(result.participantEvidence.settlementSource)}</p><p className="text-xs text-muted-foreground">{result.participantEvidence.sourceBoundary}</p></section>}
    {result.status === 'UNKNOWN' && <p className="text-xs text-amber-700 dark:text-amber-400">核验依据尚未齐全；没有把 HTTP 200 或部分匹配当作归属通过。</p>}
    {!!result.warnings?.length && <ul className="list-disc space-y-1 pl-4 text-xs text-amber-700 dark:text-amber-400">{result.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul>}
    {result.evidenceMissing && <p role="alert" className="text-xs text-destructive">本次证据写入失败，结果不能保证在重启后完整保留。</p>}
    {!!result.checks?.length && <div className="overflow-auto"><table className="w-full min-w-96 text-left text-xs"><thead><tr className="border-b"><th className="p-2">核验项</th><th className="p-2">结果</th><th className="p-2">本圈依据</th><th className="p-2">结算实际</th></tr></thead><tbody>{result.checks.map((check, index) => <tr key={`${check.field}-${index}`} className="border-b align-top"><td className="p-2">{check.field}<p className="mt-1 max-w-48 text-xs text-muted-foreground">{check.message}</p></td><td className={check.status === 'FAIL' ? 'p-2 text-destructive' : 'p-2'}>{check.status}</td><td className="max-w-64 break-all p-2 font-mono">{display(check.expected)}</td><td className="max-w-64 break-all p-2 font-mono">{display(check.actual)}{check.actualTruncated && <span className="block text-amber-700">实际值显示已截断</span>}</td></tr>)}</tbody></table></div>}
    <p className="break-all text-xs text-muted-foreground">来源快照 {result.sourcePlanHash || '未记录'}{result.stepId != null && ` · 本机步骤证据 #${result.stepId}`}</p>
  </section>
}
export function PerfSettlementCheck({ loop, cases, onClose }: { loop: PerfEvidenceLoop | null; cases: ApiCase[]; onClose: () => void }) {
  const visible = useContext(PageVisibilityContext)
  const [caseId, setCaseId] = useState(''), [rulesText, setRulesText] = useState(initialText), [baseline, setBaseline] = useState(initialText), [jsonMode, setJsonMode] = useState(false), [readOnlyConfirmed, setReadOnlyConfirmed] = useState(false)
  const [forbiddenCases, setForbiddenCases] = useState<number[]>([]), [planReady, setPlanReady] = useState(false), [planError, setPlanError] = useState(''), [planLoading, setPlanLoading] = useState(false)
  const historyLoop = useRef<number | null>(null)
  const [result, setResult] = useState<SettlementResult | null>(null), [history, setHistory] = useState<SettlementResult[]>([]), [historyLoading, setHistoryLoading] = useState(false), [historyError, setHistoryError] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [refresh, setRefresh] = useState(0)
  const loopRef = useRef(loop); loopRef.current = loop
  const mounted = useRef(true); useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const parsed = useMemo(() => { try { return { value: parseRules(rulesText), error: '' } } catch (cause) { return { value: null, error: cause instanceof Error ? cause.message : '规则格式无效' } } }, [rulesText])
  const rules = parsed.value ?? initial
  useDirtyGuard({ label: '结算核验规则', dirty: rulesText !== baseline, locked: busy, discard: () => setRulesText(baseline) })
  useEffect(() => {
    const controller = new AbortController();
    if (historyLoop.current !== (loop?.id ?? null)) { historyLoop.current = loop?.id ?? null; setResult(null); setHistory([]); setReadOnlyConfirmed(false); setForbiddenCases([]); setPlanReady(false) }
    setHistoryError(''); setError(''); setPlanError('')
    if (!loop || !visible) { setHistoryLoading(false); setPlanLoading(false); return () => controller.abort() }
    setHistoryLoading(true); setPlanLoading(true); setPlanReady(false)
    void perfContinuation.plan(loop.runId, controller.signal).then(plan => { if (controller.signal.aborted) return; const excluded = (plan.caseSources ?? []).map(source => source.caseId); setForbiddenCases(excluded); setPlanReady(plan.state === 'FROZEN' && plan.hash === loop.planHash) }).catch(cause => { if (!controller.signal.aborted) { setPlanReady(false); setPlanError(cause instanceof Error ? cause.message : '来源计划读取失败') } }).finally(() => { if (!controller.signal.aborted) setPlanLoading(false) })
    void perfContinuation.settlementHistory(loop.id, controller.signal).then(value => { if (!controller.signal.aborted) setHistory(value) }).catch(cause => { if (!controller.signal.aborted) setHistoryError(cause instanceof Error ? cause.message : '核验历史读取失败') }).finally(() => { if (!controller.signal.aborted) setHistoryLoading(false) })
    return () => controller.abort()
  }, [loop?.id, visible, refresh])
  const updateParticipation = (key: keyof NonNullable<SettlementRules['participation']>, value: unknown) => update('participation', { ...rules.participation, [key]: value })
  const update = (key: keyof SettlementRules, value: unknown) => { setRulesText(JSON.stringify({ ...rules, [key]: value }, null, 2)); setError('') }
  const execute = async () => {
    if (!loop || !caseId || !parsed.value || busy || !readOnlyConfirmed || !planReady || forbiddenCases.includes(Number(caseId)) || loop.roundIdEvidence?.state !== 'UNIQUE' || !loop.gameRoundId || !loop.planHash) return
    const target = loop.id; setBusy(true); setError(''); setResult(null)
    try {
      const value = await perfContinuation.settlement(target, Number(caseId), parsed.value)
      if (!mounted.current || loopRef.current?.id !== target) return
      setResult(value); setBaseline(rulesText)
      try { const past = await perfContinuation.settlementHistory(target); if (mounted.current && loopRef.current?.id === target) setHistory(past) }
      catch (cause) { if (mounted.current && loopRef.current?.id === target) setHistoryError(cause instanceof Error ? cause.message : '核验已完成，历史刷新失败') }
    } catch (cause) { if (mounted.current && loopRef.current?.id === target) setError(cause instanceof Error ? cause.message : '结算查询失败') }
    finally { if (mounted.current) setBusy(false) }
  }
  const sourceReady = loop?.roundIdEvidence?.state === 'UNIQUE' && !!loop.gameRoundId && !!loop.planHash && planReady
  const field = (key: keyof SettlementRules, label: string, hint?: string) => <label key={key} className="space-y-1 text-xs">{label}<Input value={String(rules[key] ?? '')} disabled={busy} onChange={event => update(key, event.target.value)} aria-label={label} />{hint && <span className="block text-xs text-muted-foreground">{hint}</span>}</label>
  return <Dialog open={loop != null} onOpenChange={open => { if (!open && !busy) onClose() }}><DialogContent className="flex max-h-[92vh] flex-col overflow-hidden sm:max-w-5xl" showCloseButton={!busy} data-testid="perf-settlement-dialog">
    <DialogHeader><DialogTitle>第 {loop?.loopNo} 圈 · 结算归属核验</DialogTitle></DialogHeader>
    <div className="min-h-0 space-y-4 overflow-auto pr-1">
      <section className="space-y-2 rounded-md border p-3 text-xs"><code className="block break-all font-mono">{loop?.gameRoundId || '本圈没有可靠唯一的对局 ID'}</code><p className="text-muted-foreground">使用本圈冻结的开局环境与独立会话发起一次真实查询。请核对查询用例与返回结构；未提供身份、参局或时间依据时会保留“未知”，不按人数规律猜测。</p>{planLoading && <p role="status" className="text-muted-foreground">正在核对本圈冻结来源…</p>}{!sourceReady && !planLoading && <p role="status" className="text-amber-700 dark:text-amber-400" data-testid="perf-settlement-source-missing">本圈 ID 来源不完整、冲突或没有冻结计划，自动核验已阻止。可查看来源证据后手工带入接口测试查询。</p>}</section>
      <label className="block space-y-1 text-xs">查询用例<select value={caseId} disabled={busy} onChange={event => { setCaseId(event.target.value); setReadOnlyConfirmed(false) }} className={selectStyle} aria-label="结算查询用例"><option value="">请选择只读结算查询用例</option>{cases.map(item => <option key={item.id} value={item.id} disabled={forbiddenCases.includes(item.id)}>{item.name || `用例 #${item.id}`} · {item.method}{forbiddenCases.includes(item.id) ? '（控制用例，禁止查询）' : ''}</option>)}</select></label>
      <div className="flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-medium">响应核验规则</h3><Button size="sm" variant="outline" disabled={busy} onClick={() => { if (jsonMode && !parsed.value) { setError(parsed.error); return } setJsonMode(value => !value) }} data-testid="perf-settlement-rule-mode">{jsonMode ? '返回结构化表单' : '编辑规则 JSON'}</Button></div>
      <p className="text-xs text-muted-foreground">记录路径相对整个响应；其余身份、玩家和时间路径相对该记录。默认路径是可编辑示例，请按真实返回核对。</p>
      {jsonMode ? <textarea className="min-h-64 w-full rounded-md border bg-background p-3 font-mono text-xs" value={rulesText} disabled={busy} onChange={event => setRulesText(event.target.value)} aria-label="结算核验规则 JSON" /> : <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {field('recordPath', '记录路径', '例如 data 或 data.records[0]')}{field('roundIdPath', '对局 ID 路径')}{field('businessCodePath', '业务码路径', '业务码路径相对整个响应')}
        {(['successCodes', 'pendingCodes', 'noDataCodes'] as const).map((key, index) => <label key={key} className="space-y-1 text-xs">{['成功业务码', '尚未结算业务码', '查无数据业务码'][index]}<Input value={(rules[key] ?? []).join(',')} disabled={busy} onChange={event => update(key, event.target.value.split(/[,，]/).map(value => value.trim()).filter(Boolean))} aria-label={['成功业务码', '尚未结算业务码', '查无数据业务码'][index]} /><span className="text-xs text-muted-foreground">逗号分隔；按字符串比较</span></label>)}
        {field('roomIdPath', '房间 ID 路径')}{field('gameIdPath', '游戏 ID 路径')}{field('playersPath', '参局玩家数组路径')}{field('playerIdPath', '每个玩家 ID 路径', '相对玩家；字符串数组可填 $')}
        <label className="space-y-1 text-xs">参局人数与名单规则<select value={rules.participantRule ?? ''} disabled={busy} onChange={event => update('participantRule', event.target.value)} className={selectStyle} aria-label="参局名单规则"><option value="">尚未配置</option><option value="EXACT">必须与完整实际参赛名单完全相等</option><option value="SUBSET_ALLOWED">旧 SUBSET_ALLOWED（新核验仍需实际完整名单）</option></select></label>
        {field('timestampPath', '结算时间路径')}<label className="space-y-1 text-xs">结算时间单位<select value={rules.timestampUnit ?? ''} disabled={busy} onChange={event => update('timestampUnit', event.target.value)} className={selectStyle} aria-label="结算时间单位"><option value="">尚未配置</option><option value="SECONDS">Unix 秒</option><option value="MILLIS">Unix 毫秒</option><option value="ISO">ISO 时间文本</option></select></label>
        <label className="space-y-1 text-xs">圈时间范围容差（毫秒）<Input type="number" min={0} max={86400000} value={rules.timeToleranceMs ?? ''} disabled={busy} onChange={event => update('timeToleranceMs', event.target.value === '' ? undefined : Number(event.target.value))} aria-label="结算时间容差" /></label>
      </div>}
      {!jsonMode && <section className="space-y-3 rounded-md border p-3" data-testid="perf-settlement-source-rules"><h4 className="text-xs font-medium">实际参赛依据 · 同次查询的开局报告</h4><p className="text-xs text-muted-foreground">上方结算路径默认对应 Sud 查询报告。这里读取同次查询中的另一份开局报告，并与本圈对局、房间和游戏身份绑定。它是服务端报告内的一致性证据，不是独立的客户端取证。没有完整名单时保持未知，不能把推送的前几人当作参赛者。</p><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{([['startRecordPath', '开局报告路径'], ['roundIdPath', '开局对局 ID 路径'], ['roomIdPath', '开局房间 ID 路径'], ['gameIdPath', '开局游戏 ID 路径'], ['playersPath', '开局实际玩家数组路径'], ['playerIdPath', '开局玩家 ID 路径'], ['capacityPath', '可靠容量字段路径（可选）'], ['capacitySource', '容量来源说明（可选）']] as const).map(([key, label]) => <label key={key} className="space-y-1 text-xs">{label}<Input value={String(rules.participation?.[key] ?? '')} disabled={busy} onChange={event => updateParticipation(key, event.target.value)} aria-label={label} /></label>)}</div><label className="flex items-start gap-2 text-xs"><Checkbox checked={rules.participation?.completeRoster === true} disabled={busy} onCheckedChange={value => updateParticipation('completeRoster', value === true)} data-testid="perf-settlement-complete-roster" /><span>已核对该开局报告契约：所选玩家数组是完整的实际参赛名单，而非候选、分页或截断名单。</span></label><p className="text-xs text-muted-foreground">容量必须来自已绑定开局报告的明确字段并填写来源说明；不从推送人数、游戏名称或历史样本推断。仅容量和候选身份通过，整体归属仍为未知。</p></section>}
      {parsed.error && <p role="alert" className="text-xs text-destructive">{parsed.error}</p>}
      <label className="flex items-start gap-2 rounded-md bg-muted/50 p-3 text-xs"><Checkbox checked={readOnlyConfirmed} disabled={busy} onCheckedChange={value => setReadOnlyConfirmed(value === true)} data-testid="perf-settlement-readonly-confirm" /><span>我已核对所选用例是只读结算查询。本操作会向真实服务发送一次查询请求，并在本机保存查询与核验证据。</span></label>
      <Button type="button" disabled={!sourceReady || !caseId || forbiddenCases.includes(Number(caseId)) || !readOnlyConfirmed || !parsed.value || busy} onClick={() => void execute()} data-testid="perf-settlement-execute">{busy ? '查询并核验中…' : '按以上规则查询并核验归属'}</Button>
      {planError && <p role="alert" className="text-xs text-destructive">冻结来源读取失败：{planError}；不会回落到当前环境。</p>}
      {error && <p role="alert" className="text-xs text-destructive" data-testid="perf-settlement-error">{error}</p>}
      {result && <Result result={result} />}
      <section className="space-y-2 border-t pt-3"><div className="flex items-center justify-between"><h3 className="text-sm font-medium">本圈最近 20 次核验</h3><Button size="sm" variant="ghost" disabled={busy || historyLoading} onClick={() => setRefresh(value => value + 1)}>刷新核验历史</Button></div>{historyLoading && <p role="status" className="text-xs">读取核验历史…</p>}{historyError && <p role="alert" className="text-xs text-destructive">{historyError}；当前查询结果与已加载历史保持。</p>}{!historyLoading && !historyError && history.length === 0 && <p className="text-xs text-muted-foreground">本圈尚无已保存的核验记录。</p>}{history.map((item, index) => <button key={item.id ?? `${item.checkedAt}-${index}`} type="button" onClick={() => setResult(item)} className="flex w-full flex-wrap justify-between gap-2 rounded-md border p-2 text-left text-xs hover:bg-muted/50" data-testid={`perf-settlement-history-${item.id ?? index}`}><span>{settlementLabel(item.status)}</span><span className="text-muted-foreground">{new Date(item.checkedAt).toLocaleString()}</span></button>)}</section>
    </div>
  </DialogContent></Dialog>
}
