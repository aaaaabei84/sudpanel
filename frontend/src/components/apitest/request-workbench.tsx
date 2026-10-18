import { executionDisplay, hasSecretInventory } from '@/lib/execution-privacy'
import { useRequestTask, taskEnded } from '@/hooks/use-request-task'
import type { RequestTab } from '@/lib/request-workspace'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Play, Save, Search, Loader2, Copy } from 'lucide-react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { CodeArea } from '@/components/code-area'
import { VarInput } from '@/components/var-input'
import { JsonBlock } from '@/components/json-block'
import { apitestApi, req } from '@/lib/api'
import { assertionsBroken, capabilitySuggestions, fillGameRoundId, readAssertions, scopedSystemSuggestions, SYSTEM_SUGGESTIONS, type RoundQueryContext } from '@/lib/apitest-workspace'
import { HeaderRowsEditor } from './header-rows-editor'
import { RequestOptionsEditor } from './request-options-editor'
import { AssertionEditor } from './assertion-editor'
import { ResponseInspector } from './response-inspector'
import { ExecutionMetadata } from './execution-metadata'
import { VariableHelp } from './variable-help'
import { VariableDiagnostics } from './variable-diagnostics'
import { ActualCurlExport } from './actual-curl'
import type { PreparedWithDiagnostics } from '@/lib/http-continuation'
import { UnsavedChangesNotice } from '@/components/unsaved-changes-notice'
import { TransferDialog } from './transfer-dialog'
import type { HistoricalDraft } from './history-explorer'
import type { ApiCase, ApiEnv, ApiCaseGroup, ApiExec, ApiVarSet } from '@/types'

const TABS = [['params', '查询参数'], ['headers', '请求头'], ['body', '请求体'], ['auth', '鉴权'], ['checks', '校验与提取'], ['transport', '会话与传输']] as const
type RequestDraft = Omit<ApiCase, 'id'>
export interface WorkbenchProps {
  open: boolean; onOpenChange: (open: boolean) => void
  envs: ApiEnv[]; cases: ApiCase[]; groups: ApiCaseGroup[]
  onSaved: () => void; context?: RoundQueryContext | null; historyDraft?: HistoricalDraft | null
}
export function RequestEditor({ open, onOpenChange, envs, cases, groups, onSaved, initial, onSnapshot, tabHeader, onNew, onBusy }: WorkbenchProps & {
  initial: RequestTab; onSnapshot: (patch: Pick<RequestTab, 'draft' | 'envId' | 'sourceContext' | 'dirty'>) => void; tabHeader: ReactNode; onNew: () => void; onBusy: (busy: boolean) => void
}) {
  const [draft, setDraft] = useState<RequestDraft>(initial.draft)
  const [sourceContext, setSourceContext] = useState<RoundQueryContext | null>(initial.sourceContext)
  const [envId, setEnvId] = useState(initial.envId)
  const [dirty, setDirty] = useState(initial.dirty ?? false)
  const snapshot = useRef(onSnapshot); snapshot.current = onSnapshot
  const [tab, setTab] = useState<typeof TABS[number][0]>('body')
  const [busy, setBusy] = useState<'prepare' | 'send' | 'save' | 'export' | null>(null)
  const requestTask = useRequestTask()
  const busyCallback = useRef(onBusy); busyCallback.current = onBusy
  useEffect(() => { busyCallback.current(!!busy); return () => busyCallback.current(false) }, [busy])
  const [prepared, setPrepared] = useState<PreparedWithDiagnostics | null>(null)
  const [result, setResult] = useState<ApiExec | null>(null)
  const [revealResult, setRevealResult] = useState(false)
  const displayResult = result ? executionDisplay(result, revealResult) : null
  useEffect(() => { setRevealResult(false) }, [result])
  const [vars, setVars] = useState<ApiVarSet>({ base: [], header: [], body: [] })
  const [methods, setMethods] = useState(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'])
  const [loadedCase, setLoadedCase] = useState('')
  const [replacePending, setReplacePending] = useState<(() => void) | null>(null)
  const replaceDraft = (action: () => void) => { if (dirty) setReplacePending(() => action); else action() }
  const [systemVars, setSystemVars] = useState(SYSTEM_SUGGESTIONS)
  const [curlExport, setCurlExport] = useState<{ curl: string; warnings: string[] } | null>(null)
  const [includeSecrets, setIncludeSecrets] = useState(false)
  const seq = useRef(0)
  const exportSeq = useRef(0)
  const patch = (p: Partial<RequestDraft>) => { setDirty(true); setDraft(d => ({ ...d, ...p })); setPrepared(null) }
  useEffect(() => { snapshot.current({ draft, envId, sourceContext, dirty }) }, [draft, envId, sourceContext, dirty])
  useEffect(() => { ++exportSeq.current; setCurlExport(null) }, [draft, envId, includeSecrets])
  const exportDraft = async () => {
    const token = ++exportSeq.current; setBusy('export')
    try { const value = await req<{ curl: string; warnings: string[] }>('/api/apitest/export/curl', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ spec: draft, includeSecrets }) }); if (token === exportSeq.current) setCurlExport(value) }
    catch (e) { if (token === exportSeq.current) toast.error((e as Error).message) }
    finally { if (token === exportSeq.current) setBusy(null) }
  }
  useEffect(() => {
    if (!open) return
    let current = true
    apitestApi.capabilities().then(c => { if (current) { if (c.methods?.length) setMethods(c.methods); setSystemVars(capabilitySuggestions(c.systemVariables ?? [])) } }).catch(() => { /* 本地默认清单兼容服务端暂时不可用。 */ })
    return () => { current = false }
  }, [open])
  useEffect(() => {
    let current = true
    setVars({ base: [], header: [], body: [] })
    if (!envId) return
    apitestApi.vars(Number(envId)).then(v => { if (current) setVars(v) }).catch(() => { if (current) toast.error('当前环境变量加载失败') })
    return () => { current = false }
  }, [envId])
  const suggestions = useMemo(() => ({
    url: [...vars.base, ...vars.header].map(v => ({ name: v.name, group: '当前环境' })).concat(scopedSystemSuggestions(systemVars, 'URL')),
    header: vars.header.map(v => ({ name: v.name, group: '当前环境请求头' })).concat(scopedSystemSuggestions(systemVars, 'HEADER')),
    body: vars.body.map(v => ({ name: v.name, group: '当前环境请求体' })).concat(scopedSystemSuggestions(systemVars, 'BODY')),
  }), [vars, systemVars])
  useEffect(() => {
    const task = requestTask.task
    if (!taskEnded(task)) return
    if (task?.exec) { setResult(task.exec); setPrepared(null) }
    setBusy(null)
  }, [requestTask.task])
  const run = async (mode: 'prepare' | 'send') => {
    if (!envId || !envs.some(e => e.id === Number(envId))) { toast.error('请明确选择仍存在的本次请求环境'); return }
    if (!draft.url.trim()) { toast.error('请填写请求地址'); return }
    const token = ++seq.current
    setBusy(mode)
    try {
      if (mode === 'prepare') { const value = await apitestApi.prepare(draft, Number(envId)); if (token === seq.current) setPrepared(value) }
      else { setResult(null); setPrepared(null); await requestTask.send(draft, Number(envId), sourceContext) }
    } catch (e) { if (token === seq.current) { setBusy(null); toast.error(`${mode === 'prepare' ? '预览' : '发送'}失败：${(e as Error).message}`) } }
    finally { if (token === seq.current && mode !== 'send') setBusy(null) }
  }
  const saveGroup = groups.find(g => g.id === draft.groupId)
  const savedEnv = envs.find(e => e.id === (saveGroup?.envId ?? envs.find(item => item.isDefault)?.id))
  const saveEnvironmentMatches = !!envId && savedEnv?.id === Number(envId)
  const save = async () => {
    if (!saveEnvironmentMatches) { toast.error('保存后的执行环境与当前调试环境不同，请选择绑定当前环境的保存分组'); return }
    if (assertionsBroken(draft.asserts)) { toast.error('原断言配置无法解析，请先明确修正后保存'); setTab('checks'); return }
    if (!draft.url.trim() || !draft.name.trim()) { toast.error('保存用例需要填写名称和地址'); return }
    setBusy('save')
    try { await apitestApi.createCase(draft); setDirty(false); onSaved(); toast.success('已保存为新用例') }
    catch (e) { toast.error(`保存失败：${(e as Error).message}`) }
    finally { setBusy(null) }
  }
  const loadCase = (id: string) => {
    setLoadedCase(id)
    setDirty(false)
    const c = cases.find(c => String(c.id) === id)
    if (!c) return
    const { id: _id, ...spec } = c
    // 只替换已明确命名的对局占位符；不猜测任意 id 字段。
    const fillRound = (text: string | null) => sourceContext && text ? text.replace(/\{\{(?:round_id|game_round_id|gameRoundId|run\.round_id)\}\}/g, sourceContext.roundId) : text
    const filled = sourceContext ? fillGameRoundId(fillRound(spec.body), sourceContext.roundId) : { body: spec.body, filled: false }
    const hadPlaceholder = sourceContext && spec.body && /\{\{(?:round_id|game_round_id|gameRoundId|run\.round_id)\}\}/.test(spec.body)
    setDraft({ ...spec, url: fillRound(spec.url) ?? '', body: filled.body, headers: fillRound(spec.headers), note: sourceContext ? `${spec.note ?? ''}\n来自性能运行 #${sourceContext.runId} 第 ${sourceContext.loopNo} 圈，对局 ID ${sourceContext.roundId}` : spec.note })
    if (sourceContext && !filled.filled && !hadPlaceholder) toast.info('已载入用例，但没有可识别的 game_round_id 字段。请把上方对局 ID 填入正确位置后发送。')
    const group = groups.find(g => g.id === c.groupId)
    if (!sourceContext) {
      const targetEnv = group?.envId ?? (c.groupId == null || group ? envs.find(e => e.isDefault)?.id : null)
      if (targetEnv != null && envs.some(e => e.id === targetEnv)) setEnvId(String(targetEnv))
      else { setEnvId(''); toast.info('用例分组或环境尚未加载，请明确选择本次环境后发送') }
    }
    setPrepared(null); setResult(null)
  }
  return <Dialog open={open} onOpenChange={onOpenChange}>
    {open && <DialogContent className="flex h-[90vh] max-h-[95vh] flex-col gap-3 overflow-hidden sm:max-w-7xl" onKeyDown={event => {
      // 只响应本工作台，嵌套的导入/响应查看弹窗不触发发送。
      if (event.defaultPrevented || event.nativeEvent.isComposing || event.repeat || event.altKey || event.key !== 'Enter' || (!event.metaKey && !event.ctrlKey)) return
      if (!(event.target instanceof Element) || event.target.closest('[role="dialog"]') !== event.currentTarget) return
      event.preventDefault()
      if (!busy && !replacePending) void run('send')
    }}>
      <DialogHeader><DialogTitle>快速调试</DialogTitle></DialogHeader>
      {tabHeader}
      {replacePending && <UnsavedChangesNotice testId="request-replace-changes" onContinue={() => setReplacePending(null)} onDiscard={() => { replacePending(); setReplacePending(null) }} />}
      {requestTask.task && <div className="flex flex-wrap items-center gap-2 rounded border p-2 text-xs"><span>请求 {requestTask.task.state}</span><code className="break-all">{requestTask.task.requestId}</code>{!taskEnded(requestTask.task) && <Button type="button" size="sm" variant="destructive" disabled={requestTask.cancelling || requestTask.task.cancelRequested} onClick={() => void requestTask.cancel()}>{requestTask.cancelling || requestTask.task.cancelRequested ? '正在取消…' : '取消当前请求'}</Button>}{requestTask.task.serverOutcomeUnknown && <span className="text-amber-700 dark:text-amber-400">已终止本地等待；服务器可能已处理请求，请核对业务状态。</span>}{requestTask.task.error && <span role="alert">{requestTask.task.error}</span>}</div>}
      {requestTask.error && <div role="status" className="flex flex-wrap items-center gap-2 text-xs text-amber-700 dark:text-amber-400"><span>{requestTask.error}</span>{!taskEnded(requestTask.task) && <Button type="button" size="sm" variant="outline" onClick={requestTask.stopTracking}>结束本机状态查询，保留结果未知</Button>}</div>}
      <fieldset disabled={!!busy || replacePending != null} className="min-h-0 min-w-0 flex-1 space-y-3 overflow-y-auto pr-1">
        {sourceContext && <div className="space-y-1 rounded-xl border border-sky-500/30 bg-sky-500/10 backdrop-blur-md p-3 text-xs shadow-xs"><p>性能运行 #{sourceContext.runId} · {sourceContext.gameName} · 第 {sourceContext.loopNo} 圈</p><div className="flex items-center gap-2"><code className="break-all font-mono">对局 ID：{sourceContext.roundId}</code><Button type="button" size="sm" variant="ghost" onClick={() => void navigator.clipboard.writeText(sourceContext.roundId).then(() => toast.success('已复制对局 ID')).catch(() => toast.error('复制失败'))}><Copy className="size-3.5" /></Button></div><p>环境来源：{sourceContext.envSource ?? '未知，请确认本次查询使用哪个环境。'} 已预填新请求体；载入用例时只替换对局占位符，请核对实际请求。</p></div>}
        <div className="grid gap-2 sm:grid-cols-3">
          <div className="space-y-1"><Label className="text-xs font-medium">本次执行环境</Label><select data-testid="quick-env" aria-label="本次执行环境" disabled={!!busy} className="h-9 w-full rounded-lg border border-border/80 bg-background/70 backdrop-blur-md px-3 text-sm text-foreground shadow-xs transition-all hover:border-primary/50 hover:bg-background/90 focus:border-primary/60 focus:bg-background/95 focus:ring-1 focus:ring-primary/30 outline-none" value={envId} onChange={e => { setEnvId(e.target.value); setDirty(true); setPrepared(null) }}><option value="">请选择环境</option>{envs.map(e => <option key={e.id} value={e.id}>{e.name}{e.isDefault ? '（默认）' : ''}</option>)}</select></div>
          <div className="space-y-1"><Label className="text-xs font-medium">从已有用例载入</Label><select aria-label="从已有用例载入" disabled={!!busy} className="h-9 w-full rounded-lg border border-border/80 bg-background/70 backdrop-blur-md px-3 text-sm text-foreground shadow-xs transition-all hover:border-primary/50 hover:bg-background/90 focus:border-primary/60 focus:bg-background/95 focus:ring-1 focus:ring-primary/30 outline-none" value={loadedCase} onChange={e => { const id = e.target.value; replaceDraft(() => loadCase(id)) }}><option value="">新请求草稿</option>{cases.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></div>
          <div className="space-y-1"><Label className="text-xs font-medium">保存到分组</Label><select aria-label="保存到分组" disabled={!!busy} className="h-9 w-full rounded-lg border border-border/80 bg-background/70 backdrop-blur-md px-3 text-sm text-foreground shadow-xs transition-all hover:border-primary/50 hover:bg-background/90 focus:border-primary/60 focus:bg-background/95 focus:ring-1 focus:ring-primary/30 outline-none" value={draft.groupId ?? ''} onChange={e => patch({ groupId: e.target.value ? Number(e.target.value) : null })}><option value="">未分组</option>{groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select><p data-testid="quick-saved-env" className={`text-xs ${saveEnvironmentMatches ? 'text-muted-foreground' : 'text-amber-700 dark:text-amber-400'}`}>保存后执行环境：{savedEnv?.name ?? '未加载'}{saveEnvironmentMatches ? '' : ' · 请选与本次环境一致的分组'}</p></div>
        </div>
        <div className="flex gap-2"><Input aria-label="请求名称" placeholder="请求名称（保存用例时必填）" className="h-9 rounded-lg border-border/80 bg-background/70 backdrop-blur-md focus:bg-background/90 transition-all" value={draft.name} onChange={e => patch({ name: e.target.value })} /><Button type="button" variant="outline" disabled={!!busy} onClick={onNew} className="h-9">新请求</Button></div>
        <div className="flex flex-wrap gap-2"><select aria-label="请求方法" className="h-9 rounded-lg border border-border/80 bg-background/70 backdrop-blur-md px-3 text-sm font-semibold transition-all hover:border-primary/50 focus:border-primary/60 focus:ring-1 focus:ring-primary/30 outline-none" disabled={!!busy} value={draft.method} onChange={e => patch({ method: e.target.value })}>{methods.map(m => <option key={m}>{m}</option>)}</select><div className="min-w-40 flex-1"><VarInput aria-label="请求地址" className="font-mono h-9 rounded-lg border-border/80 bg-background/70 backdrop-blur-md focus:bg-background/90 transition-all" value={draft.url} suggestions={suggestions.url} placeholder="https://… 或 {{base}}/api/…" onChange={url => patch({ url })} /></div><Button type="button" variant="outline" className="h-9" disabled={!!busy || !envId} onClick={() => void run('prepare')}><Search className="size-4" />预览</Button><Button data-testid="quick-send" title="发送（⌘/Ctrl + Enter）" type="button" className="h-9 shadow-sm" disabled={!!busy || !envId} onClick={() => void run('send')}>{busy === 'send' ? <Loader2 className="size-4 animate-spin" /> : <Play className="size-4" />}发送</Button></div>
        <VariableHelp vars={vars} systemVariables={systemVars} />
        <div className="flex flex-wrap items-center gap-2">
          <TransferDialog kind="CURL" onLoaded={spec => replaceDraft(() => { setDirty(true); setDraft(spec); setSourceContext(null); setLoadedCase(''); setPrepared(null); setResult(null); setCurlExport(null) })} />
          <Button type="button" size="sm" variant="outline" onClick={() => void exportDraft()}>导出 cURL 模板</Button>
          <ActualCurlExport spec={draft} envId={envId ? Number(envId) : null} label="导出本次组装 cURL" />
          <label className="flex items-center gap-1 text-xs"><Checkbox checked={includeSecrets} onCheckedChange={v => setIncludeSecrets(v === true)} />包含实际凭据和正文</label>
          <span className="text-xs text-muted-foreground">草稿自动保存到本机加密库；响应仅在当前标签会话内保留。</span>
        </div>
        {curlExport && <section className="space-y-2 rounded-xl border border-border/70 bg-card/60 backdrop-blur-md p-3 text-xs shadow-xs">{curlExport.warnings.map((w, i) => <p key={i} className="text-amber-700 dark:text-amber-400">{w}</p>)}<JsonBlock text={curlExport.curl} empty="空" title="cURL 模板" /><Button type="button" size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(curlExport.curl).then(() => toast.success('已复制 cURL 模板')).catch(() => toast.error('复制失败'))}>复制 cURL</Button></section>}
        <div className="flex flex-wrap gap-1 border-b border-border/60 pb-2">{TABS.map(([key, label]) => <Button type="button" size="sm" key={key} variant={tab === key ? 'secondary' : 'ghost'} className="h-8 transition-colors" onClick={() => setTab(key)}>{label}</Button>)}</div>
        <div className="min-h-40 rounded-xl border border-border/70 bg-card/60 backdrop-blur-md p-3.5 shadow-xs">
          {tab === 'params' && <RequestOptionsEditor section="params" value={draft.options} suggestions={suggestions.url} onChange={options => patch({ options })} />}
          {tab === 'headers' && <HeaderRowsEditor suggestions={suggestions.header} rawId="quick-headers" raw={draft.headers ?? ''} rows={draft.options?.headerRows} onRaw={headers => patch({headers})} onRows={headerRows=>patch({options:{...draft.options,headerRows}})}/>}
          {tab === 'body' && <div className="space-y-3"><RequestOptionsEditor section="body" headers={draft.headers} value={draft.options} suggestions={suggestions.body} onChange={options => patch({ options })} />{draft.options?.bodyType !== 'NONE' && draft.options?.bodyType !== 'URLENCODED' && draft.options?.bodyType !== 'MULTIPART' && draft.options?.bodyType !== 'BINARY' && <CodeArea id="quick-body" formatJson={draft.options?.bodyType !== 'RAW'} value={draft.body ?? ''} suggestions={suggestions.body} onChange={body => patch({ body })} placeholder={'{\n  "id": "{{run.id}}"\n}'} />}</div>}
          {tab === 'transport' && <div className="space-y-3"><RequestOptionsEditor envId={envId ? Number(envId) : undefined} section="transport" value={draft.options} onChange={options => patch({ options })} /><Button type="button" size="sm" variant="outline" disabled={!envId} onClick={() => void req(`/api/apitest/envs/${envId}/cookies`, { method: 'DELETE' }).then(() => toast.success('已清空当前环境的自动 Cookie 会话')).catch(e => toast.error((e as Error).message))}>清空当前环境 Cookie</Button></div>}
          {tab === 'auth' && <RequestOptionsEditor key={loadedCase} envId={envId ? Number(envId) : undefined} section="auth" value={draft.options} suggestions={suggestions.header} paramSuggestions={suggestions.url} onChange={options => patch({ options })} />}
          {tab === 'checks' && <div className="space-y-4"><div className="grid gap-2 sm:grid-cols-2"><Input aria-label="期望 HTTP 状态码" placeholder="期望 HTTP 状态码；空=不校验" value={draft.expectStatus ?? ''} onChange={e => patch({ expectStatus: e.target.value })} /><Input aria-label="期望业务码" placeholder="期望业务返回码；空=不校验" value={draft.expectCode ?? ''} onChange={e => patch({ expectCode: e.target.value })} /></div>{assertionsBroken(draft.asserts) ? <div role="alert" className="space-y-2 rounded border border-red-500/30 p-3 text-xs text-red-600"><p>原断言配置无法解析。原文仍保留，修正前不能另存用例。</p><JsonBlock text={draft.asserts} empty="空" title="损坏的断言配置" /><Button type="button" variant="outline" onClick={() => patch({ asserts: null })}>清空损坏配置并重新编辑</Button></div> : <AssertionEditor rows={readAssertions(draft.asserts)} onChange={rows => patch({ asserts: rows.length ? JSON.stringify(rows) : null })} />}<RequestOptionsEditor section="extractors" value={draft.options} onChange={options => patch({ options })} /></div>}
        </div>
        <div className="flex flex-wrap items-center gap-3"><Label className="text-xs">超时（毫秒）</Label><Input aria-label="快速请求超时" className="w-28 h-8 rounded-lg border-border/80 bg-background/70 backdrop-blur-md" type="number" min={1} value={draft.timeoutMs ?? 10000} onChange={e => patch({ timeoutMs: Number(e.target.value) || 10000 })} /><label className="flex items-center gap-2 text-xs"><Checkbox checked={draft.insecure} onCheckedChange={v => patch({ insecure: v === true })} />跳过证书校验</label><Button type="button" variant="outline" className="ml-auto h-8 shadow-xs" disabled={!!busy} onClick={() => void save()}><Save className="size-4 mr-1" />保存为新用例</Button></div>
        {prepared && <section className="space-y-2 rounded-xl border border-border/70 bg-card/60 backdrop-blur-md p-3.5 text-xs shadow-xs"><p className={prepared.valid ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-600'}>{prepared.valid ? '预览校验通过' : '请求配置不完整'} · {prepared.envName} · {prepared.capturedAt}</p>{(prepared.warnings ?? []).map((w, i) => <p key={i} className="text-amber-700 dark:text-amber-400">{w}</p>)}{prepared.error && <p role="alert" className="text-red-600">{prepared.error}</p>}{(prepared.unresolved ?? []).length > 0 && <p className="text-red-600">未解析变量：{prepared.unresolved.join('、')}</p>}<VariableDiagnostics diagnostics={prepared.diagnostics} /><p className="break-all font-mono">{prepared.method} {prepared.url}</p><div className="grid gap-2 sm:grid-cols-2"><JsonBlock text={JSON.stringify(prepared.headerRows ?? prepared.headers, null, 2)} empty="无请求头" title="预览请求头" /><JsonBlock text={prepared.bodyMetadata && Object.keys(prepared.bodyMetadata).length ? JSON.stringify(prepared.bodyMetadata, null, 2) : prepared.body} empty="无请求体" title="预览请求体（文件仅元数据）" /></div><details><summary className="cursor-pointer">变量来源</summary><JsonBlock text={JSON.stringify(prepared.variables, null, 2)} empty="无变量" title="变量来源" /></details></section>}
        {displayResult && <section className="space-y-2 rounded-xl border border-border/70 bg-card/60 backdrop-blur-md p-3.5 shadow-xs"><div className="flex flex-wrap items-center gap-3 text-sm"><ActualCurlExport execId={displayResult!.id} label="导出历史 cURL" /><label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={revealResult} onChange={e => setRevealResult(e.target.checked)} />显示本机敏感原文</label><strong>响应 · {displayResult!.httpStatus ?? '未收到'}</strong><span>{displayResult!.verdict} · {displayResult!.elapsedMs} ms</span>{(!hasSecretInventory(result!) || revealResult) && <ResponseInspector body={result!.respBody} headers={result!.respHeaders} onAssert={row => { if (assertionsBroken(draft.asserts)) { toast.error('请先修复原断言配置'); setTab('checks'); return }; patch({ asserts: JSON.stringify([...readAssertions(draft.asserts), row]) }); setTab('checks'); toast.success('已加入草稿断言') }} onExtract={row => { patch({ options: { ...draft.options, extractors: [...(draft.options?.extractors ?? []), row] } }); setTab('checks'); toast.success('已加入草稿提取') }} />}</div>{(displayResult!.reason || displayResult!.error) && <p className="whitespace-pre-wrap text-xs text-red-600">{displayResult!.reason || displayResult!.error}</p>}<ExecutionMetadata text={displayResult!.metadata} /><div className="grid gap-2 sm:grid-cols-2"><JsonBlock text={displayResult!.respBody} empty="空响应体" title="响应体" /><JsonBlock text={displayResult!.respHeaders} empty="无响应头" title="响应头" /></div><details className="text-xs"><summary className="cursor-pointer">实际发出的请求与断言结果</summary><JsonBlock text={displayResult!.reqHeaders} empty="无请求头" title="实际请求" /><JsonBlock text={displayResult!.reqBody} empty="无请求体" title="实际请求体" /><JsonBlock text={displayResult!.assertsResult} empty="未执行断言" title="断言结果" /></details></section>}
      </fieldset>
    </DialogContent>}
  </Dialog>
}
