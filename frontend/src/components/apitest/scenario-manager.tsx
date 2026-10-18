import { useDirtyGuard } from '@/lib/navigation-guard'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ArrowDown, ArrowUp, Copy, ListChecks, Pencil, Play, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { apitestApi } from '@/lib/api'
import { AssertionEditor } from './assertion-editor'
import { readAssertions } from '@/lib/apitest-workspace'
import { ExtractorEditor } from './request-options-editor'
import { UnsavedChangesNotice } from '@/components/unsaved-changes-notice'
import { draftFingerprint } from '@/lib/draft-state'
import type { ApiBatch, ApiCase, ApiCaseGroup, ApiEnv, ApiScenario, ApiScenarioStep } from '@/types'

const selectClass = 'h-9 rounded-md border bg-background px-2 text-sm'
const ordered = (steps: ApiScenarioStep[]) => [...steps.filter(s => !s.cleanup), ...steps.filter(s => s.cleanup)]

export function ScenarioManager({ cases, envs, groups, pickedIds, running, onRun }: {
  cases: ApiCase[]; envs: ApiEnv[]; groups: ApiCaseGroup[]; pickedIds: number[]
  running: boolean; onRun: (batch: ApiBatch) => void
}) {
  const [scenarios, setScenarios] = useState<ApiScenario[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [name, setName] = useState('')
  const [steps, setSteps] = useState<ApiScenarioStep[]>([])
  const [addCaseId, setAddCaseId] = useState('')
  const [busy, setBusy] = useState(false)
  const [repeat, setRepeat] = useState(1)
  const [interval, setInterval] = useState(0)
  const [forever, setForever] = useState(false)
  const editorBaseline = useRef('')
  const [discardPending, setDiscardPending] = useState(false)
  useDirtyGuard({ label: '请求链', dirty: open && draftFingerprint({ name, steps: ordered(steps) }) !== editorBaseline.current, locked: busy, discard: () => { setDiscardPending(false); setOpen(false); setName(''); setSteps([]) } })
  const closeEditor = () => {
    if (busy) return
    if (draftFingerprint({ name, steps: ordered(steps) }) !== editorBaseline.current) setDiscardPending(true)
    else setOpen(false)
  }
  const load = async () => {
    setLoading(true); setError('')
    try { setScenarios(await apitestApi.scenarios()) }
    catch (e) { setError((e as Error).message) }
    finally { setLoading(false) }
  }
  useEffect(() => { void load() }, [])
  const newStep = (caseId: number): ApiScenarioStep => ({ key: crypto.randomUUID(), name: cases.find(c => c.id === caseId)?.name ?? '', caseId, envId: null, onFailure: 'STOP', independent: false, cleanup: false, extractors: (cases.find(c => c.id === caseId)?.options?.extractors ?? []).map(r => ({ ...r })) })
  const edit = (scenario?: ApiScenario) => {
    const initialName = scenario?.name ?? ''
    const initialSteps = scenario ? scenario.steps.map(s => ({ ...s, extractors: (s.extractors ?? cases.find(c => c.id === s.caseId)?.options?.extractors ?? []).map(r => ({ ...r })) })) : pickedIds.map(newStep)
    editorBaseline.current = draftFingerprint({ name: initialName, steps: ordered(initialSteps) })
    setDiscardPending(false)
    setEditingId(scenario?.id ?? null); setName(initialName)
    setSteps(initialSteps)
    setAddCaseId(''); setOpen(true)
  }
  const patch = (key: string, p: Partial<ApiScenarioStep>) => setSteps(prev => ordered(prev.map(s => s.key === key ? { ...s, ...p } : s)))
  const move = (key: string, delta: number) => setSteps(prev => {
    const next = ordered(prev); const i = next.findIndex(s => s.key === key); const j = i + delta
    if (j < 0 || j >= next.length || next[i].cleanup !== next[j].cleanup) return prev
    ;[next[i], next[j]] = [next[j], next[i]]
    return next
  })
  const save = async () => {
    if (!name.trim() || steps.length === 0) { toast.error('请填写场景名称并添加步骤'); return }
    if (steps.some(s => !cases.some(c => c.id === s.caseId))) { toast.error('部分步骤的用例已不存在，请重新选择'); return }
    setBusy(true)
    try {
      const payload = { name: name.trim(), steps: ordered(steps) }
      if (editingId == null) await apitestApi.createScenario(payload)
      else await apitestApi.updateScenario(editingId, payload)
      setOpen(false); await load(); toast.success('场景已保存')
    } catch (e) { toast.error(`保存场景失败：${(e as Error).message}`) }
    finally { setBusy(false) }
  }
  const remove = async (scenario: ApiScenario) => {
    if (!window.confirm(`删除场景「${scenario.name}」？用例与历史结果会保留。`)) return
    setBusy(true)
    try { await apitestApi.deleteScenario(scenario.id); await load() }
    catch (e) { toast.error(`删除场景失败：${(e as Error).message}`) }
    finally { setBusy(false) }
  }
  const duplicate = async (scenario: ApiScenario) => {
    setBusy(true)
    try { await apitestApi.createScenario({ name: `${scenario.name} 副本`, steps: scenario.steps.map(s => ({ ...s, key: crypto.randomUUID() })) }); await load() }
    catch (e) { toast.error(`复制场景失败：${(e as Error).message}`) }
    finally { setBusy(false) }
  }
  const run = async (scenario: ApiScenario) => {
    setBusy(true)
    try { onRun(await apitestApi.runScenario(scenario.id, forever ? -1 : repeat, interval)) }
    catch (e) { toast.error(`启动场景失败：${(e as Error).message}`) }
    finally { setBusy(false) }
  }
  return <>
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-3"><CardTitle className="flex items-center gap-2 text-base"><ListChecks className="size-4" />请求链场景</CardTitle><Button type="button" size="sm" variant="outline" disabled={busy || running} data-testid="scenario-create" onClick={() => edit()}><Plus className="size-3.5" />{pickedIds.length ? '从已选顺序创建' : '新建场景'}</Button></CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">保存请求顺序、响应提取与清理步骤。变量只在本轮对应环境内流转，下一轮从空值重新开始。</p>
        <div className="flex flex-wrap items-center gap-2 text-xs"><Label>轮数</Label><Input aria-label="场景重复轮数" disabled={forever || busy || running} className="h-8 w-20" type="number" min={1} value={repeat} onChange={e => setRepeat(Math.max(1, Math.floor(Number(e.target.value) || 1)))} /><Label>轮间隔（秒）</Label><Input aria-label="场景轮间隔" disabled={busy || running} className="h-8 w-20" type="number" min={0} value={interval} onChange={e => setInterval(Math.max(0, Number(e.target.value) || 0))} /><label className="flex items-center gap-1"><Checkbox checked={forever} disabled={busy || running} onCheckedChange={v => setForever(v === true)} />持续运行直到停止</label></div>
        {loading && <p className="text-xs text-muted-foreground">加载场景…</p>}
        {error && <div className="flex items-center gap-2 text-xs text-red-600">{error}<Button type="button" size="sm" variant="outline" onClick={() => void load()}>重试</Button></div>}
        {!loading && !error && scenarios.length === 0 && <p className="text-xs text-muted-foreground">尚无场景。勾选用例后可沿当前顺序创建。</p>}
        {scenarios.map(s => <div key={s.id} className="flex flex-wrap items-center gap-2 rounded border p-2"><div className="min-w-40 flex-1"><p className="text-sm font-medium">{s.name}</p><p className="text-xs text-muted-foreground">{s.steps.filter(step => !step.cleanup).length} 个步骤 · {s.steps.filter(step => step.cleanup).length} 个清理步骤</p></div><Button aria-label={`编辑场景 ${s.name}`} type="button" size="icon" variant="ghost" disabled={busy || running} onClick={() => edit(s)}><Pencil className="size-3.5" /></Button><Button aria-label={`复制场景 ${s.name}`} type="button" size="icon" variant="ghost" disabled={busy || running} onClick={() => void duplicate(s)}><Copy className="size-3.5" /></Button><Button aria-label={`删除场景 ${s.name}`} type="button" size="icon" variant="ghost" disabled={busy || running} onClick={() => void remove(s)}><Trash2 className="size-3.5" /></Button><Button type="button" size="sm" disabled={busy || running} onClick={() => void run(s)}><Play className="size-3.5" />运行</Button></div>)}
      </CardContent>
    </Card>
    <Dialog open={open} onOpenChange={value => { if (value && !busy) setOpen(true); else if (!value) closeEditor() }}>
      <DialogContent className="flex h-[85vh] max-h-[92vh] flex-col overflow-hidden sm:max-w-6xl"><DialogHeader><DialogTitle>{editingId == null ? '新建请求链' : '编辑请求链'}</DialogTitle></DialogHeader>
        {discardPending && <UnsavedChangesNotice testId="scenario-unsaved-changes" onContinue={() => setDiscardPending(false)} onDiscard={() => { setDiscardPending(false); setOpen(false) }} />}
        <fieldset disabled={busy || discardPending} className="flex min-h-0 min-w-0 flex-1 flex-col gap-3"><Input aria-label="场景名称" placeholder="场景名称" value={name} onChange={e => setName(e.target.value)} />
        <p className="text-xs text-muted-foreground">普通步骤失败可停止后续普通步骤；标记为独立的步骤仍可执行，清理步骤始终在本轮末尾执行。提取值按环境隔离，跨环境不会自动带入鉴权或业务 ID。编辑保存会固定当前展示的提取规则。</p>
        <div className="flex gap-2"><select aria-label="添加场景用例" className={`${selectClass} min-w-0 flex-1`} value={addCaseId} onChange={e => setAddCaseId(e.target.value)}><option value="">选择要添加的用例</option>{cases.map(c => <option key={c.id} value={c.id}>{c.name}{c.enabled ? '' : '（未启用）'}</option>)}</select><Button type="button" variant="outline" disabled={!addCaseId} onClick={() => { setSteps(prev => ordered([...prev, newStep(Number(addCaseId))])); setAddCaseId('') }}><Plus className="size-3.5" />添加步骤</Button></div>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
          {ordered(steps).map((s, i, orderedSteps) => {
            const c = cases.find(c => c.id === s.caseId)
            const group = groups.find(g => g.id === c?.groupId)
            const inheritedEnv = envs.find(e => e.id === group?.envId) ?? envs.find(e => e.isDefault)
            return <div key={s.key} data-testid={`scenario-step-${s.key}`} className={`space-y-3 rounded-lg border p-3 ${s.cleanup ? 'border-amber-500/30 bg-amber-500/5' : ''}`}>
              {(i === 0 || orderedSteps[i - 1].cleanup !== s.cleanup) && <p className="text-xs font-medium text-muted-foreground">{s.cleanup ? '清理步骤 · 本轮末尾执行' : '普通步骤 · 按顺序执行'}</p>}
              <div className="flex items-center gap-2"><span className="text-xs text-muted-foreground">{i + 1}</span><Input aria-label={`步骤名称 ${i + 1}`} className="flex-1" value={s.name} onChange={e => patch(s.key, { name: e.target.value })} /><Button type="button" size="icon" variant="ghost" aria-label={`步骤 ${i + 1} 上移`} disabled={i === 0 || s.cleanup !== orderedSteps[i - 1]?.cleanup} onClick={() => move(s.key, -1)}><ArrowUp className="size-3.5" /></Button><Button type="button" size="icon" variant="ghost" aria-label={`步骤 ${i + 1} 下移`} disabled={i === orderedSteps.length - 1 || s.cleanup !== orderedSteps[i + 1]?.cleanup} onClick={() => move(s.key, 1)}><ArrowDown className="size-3.5" /></Button><Button type="button" size="icon" variant="ghost" aria-label={`删除步骤 ${i + 1}`} onClick={() => setSteps(prev => prev.filter(step => step.key !== s.key))}><Trash2 className="size-3.5" /></Button></div>
              <div className="grid gap-2 sm:grid-cols-3"><select aria-label={`步骤用例 ${i + 1}`} className={selectClass} value={s.caseId} onChange={e => { const nextCase = cases.find(c => c.id === Number(e.target.value)); patch(s.key, { caseId: Number(e.target.value), name: nextCase?.name ?? s.name, extractors: (nextCase?.options?.extractors ?? []).map(r => ({ ...r })) }) }}>{!c && <option value={s.caseId}>用例 #{s.caseId} 已不存在</option>}{cases.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select><select aria-label={`步骤环境 ${i + 1}`} className={selectClass} value={s.envId ?? ''} onChange={e => patch(s.key, { envId: e.target.value ? Number(e.target.value) : null })}><option value="">继承用例分组 → {inheritedEnv?.name ?? '默认环境'}</option>{envs.map(e => <option key={e.id} value={e.id}>{e.name}</option>)}</select><select aria-label={`步骤失败策略 ${i + 1}`} className={selectClass} value={s.onFailure} onChange={e => patch(s.key, { onFailure: e.target.value as 'STOP' | 'CONTINUE' })}><option value="STOP">失败：停止本轮普通步骤</option><option value="CONTINUE">失败：继续后续步骤</option></select></div>
              <label className="flex items-center gap-2 text-xs"><Checkbox checked={s.independent ?? false} onCheckedChange={v => patch(s.key, { independent: v === true })} />前置失败后仍执行（独立步骤）</label>
              <label className="flex items-center gap-2 text-xs"><Checkbox checked={s.cleanup} onCheckedChange={v => patch(s.key, { cleanup: v === true })} />这是清理步骤（即使前面失败仍执行）</label>
              <div className="grid gap-3 rounded border p-3 text-xs sm:grid-cols-2">
                <label className="space-y-1">发送前等待（毫秒）<Input aria-label={`步骤 ${i + 1} 等待毫秒`} type="number" min={0} max={3600000} value={s.delayMs ?? 0} onChange={e => patch(s.key, { delayMs: Number(e.target.value) })} /></label>
                <label className="flex items-center gap-2"><Checkbox checked={s.poll != null} onCheckedChange={value => patch(s.key, { poll: value === true ? { maxAttempts: 5, intervalMs: 1000, timeoutMs: 30000, untilAsserts: '[]' } : null })} />重复查询直到条件满足</label>
                {s.poll && <div className="space-y-3 sm:col-span-2"><p className="text-muted-foreground">每次都会真正发送并留痕。仅对适合重复执行的请求启用；达到次数或总时限后失败，清理步骤仍执行。</p><div className="grid gap-2 sm:grid-cols-3">
                  <label>最多尝试次数<Input aria-label={`步骤 ${i + 1} 轮询次数`} type="number" min={1} max={100} value={s.poll.maxAttempts} onChange={e => patch(s.key, { poll: { ...s.poll!, maxAttempts: Number(e.target.value) } })} /></label>
                  <label>间隔（毫秒）<Input aria-label={`步骤 ${i + 1} 轮询间隔`} type="number" min={0} max={60000} value={s.poll.intervalMs} onChange={e => patch(s.key, { poll: { ...s.poll!, intervalMs: Number(e.target.value) } })} /></label>
                  <label>总时限（毫秒）<Input aria-label={`步骤 ${i + 1} 轮询时限`} type="number" min={1} max={3600000} value={s.poll.timeoutMs} onChange={e => patch(s.key, { poll: { ...s.poll!, timeoutMs: Number(e.target.value) } })} /></label>
                </div><p className="font-medium">结束轮询条件</p><AssertionEditor rows={readAssertions(s.poll.untilAsserts)} onChange={rules => patch(s.key, { poll: { ...s.poll!, untilAsserts: JSON.stringify(rules) } })} /></div>}
              </div>
              <ExtractorEditor rows={s.extractors ?? []} onChange={extractors => patch(s.key, { extractors })} />
            </div>
          })}
          {steps.length === 0 && <p className="p-6 text-center text-sm text-muted-foreground">请添加至少一个步骤。</p>}
        </div>
        </fieldset>
        <DialogFooter><Button type="button" variant="outline" disabled={busy || discardPending} onClick={closeEditor}>取消</Button><Button type="button" disabled={busy || discardPending} onClick={() => void save()}>保存场景</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  </>
}
