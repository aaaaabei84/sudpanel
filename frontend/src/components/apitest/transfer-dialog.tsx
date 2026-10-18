import { useCallback, useRef, useState } from 'react'
import { toast } from 'sonner'
import { req } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { CodeArea } from '@/components/code-area'
import { JsonBlock } from '@/components/json-block'
import { ImportDisclosure } from './import-disclosure'
import { ImportVariableMappingTable } from './import-variable-mapping'
import { ImportJsonSource } from './import-json-source'
import type { ImportVariableMapping } from './import-variable-mapping'
import type { ImportedFolder, ImportLoss, SkippedImportRequest } from './import-disclosure'
import type { ApiCase, ApiEnv } from '@/types'

type Preview = { name: string; requests: { folder: string; spec: Omit<ApiCase, 'id'> }[]; environmentName?: string; variables: { base: unknown[]; header: unknown[]; body: unknown[] }; warnings: string[]; errors: string[]; folders?: ImportedFolder[]; losses?: ImportLoss[]; variableMappings?: ImportVariableMapping[]; skippedRequests?: SkippedImportRequest[]; skippedCount?: number }

export function TransferDialog({ kind = 'COLLECTION', envs = [], onLoaded, onCommitted }: {
  kind?: 'CURL' | 'COLLECTION'; envs?: ApiEnv[]; onLoaded?: (spec: Omit<ApiCase, 'id'>) => void; onCommitted?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [format, setFormat] = useState(kind === 'CURL' ? 'CURL' : 'POSTMAN')
  const [content, setContent] = useState('')
  const [environmentContent, setEnvironmentContent] = useState('')
  const [name, setName] = useState('')
  const [envId, setEnvId] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState(false)
  const [variableScopes, setVariableScopes] = useState<Record<string, string[]>>({})
  const [includeSecrets, setIncludeSecrets] = useState(false)
  const [skipInvalidRequests, setSkipInvalidRequests] = useState(false)
  const [enableSignatureScripts, setEnableSignatureScripts] = useState(false)
  const [previewScriptsEnabled, setPreviewScriptsEnabled] = useState(false)
  const [needsPreview, setNeedsPreview] = useState(false)
  const [readingContent, setReadingContent] = useState(false)
  const [readingEnvironment, setReadingEnvironment] = useState(false)
  const readSession = useRef(0)
  const inFlight = useRef(false)
  const [expandedRequests, setExpandedRequests] = useState<Set<number>>(new Set())
  const reading = readingContent || readingEnvironment
  const skipInvalid = format === 'POSTMAN' && skipInvalidRequests
  const signatureScriptsEnabled = format === 'POSTMAN' && enableSignatureScripts
  const skippedRequests = preview?.skippedRequests ?? []
  const skippedCount = preview?.skippedCount ?? skippedRequests.length
  const blockingCount = preview?.errors.length ?? 0
  const noRequestsAfterSkipping = skipInvalid && !!preview && preview.requests.length === 0
  const canCommit = !busy && !reading && !!preview && !needsPreview && blockingCount === 0 && !noRequestsAfterSkipping && (kind !== 'CURL' || preview.requests.length > 0)
  const resetSource = useCallback(() => { setPreview(null); setPreviewScriptsEnabled(false); setVariableScopes({}); setIncludeSecrets(false); setNeedsPreview(false); setExpandedRequests(new Set()) }, [])
  const readContent = useCallback((reading: boolean) => { setReadingContent(reading); if (reading) resetSource() }, [resetSource])
  const readEnvironment = useCallback((reading: boolean) => { setReadingEnvironment(reading); if (reading) resetSource() }, [resetSource])
  const inspect = async () => {
    if (reading || inFlight.current) return
    inFlight.current = true
    setBusy(true); setPreview(null); setNeedsPreview(true); setExpandedRequests(new Set())
    try { setPreview(await req<Preview>('/api/apitest/import/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ format, content, environmentContent, variableScopes: format === 'POSTMAN' ? variableScopes : null, includeSecrets, skipInvalidRequests: skipInvalid, enableSignatureScripts: signatureScriptsEnabled }) })); setPreviewScriptsEnabled(signatureScriptsEnabled); setNeedsPreview(false) }
    catch (error) { toast.error((error as Error).message) }
    finally { inFlight.current = false; setBusy(false) }
  }
  const commit = async () => {
    if (!canCommit || !preview || inFlight.current) return
    if (kind === 'CURL') { if (preview.requests[0]) { onLoaded?.(preview.requests[0].spec); setOpen(false); toast.success('已载入草稿，请核对环境和实际请求') }; return }
    inFlight.current = true; setBusy(true)
    try {
      const result = await req<{ environmentName: string; caseIds: number[]; skippedCount?: number }>('/api/apitest/import/commit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ format, content, environmentContent: format === 'POSTMAN' ? environmentContent : null, name, envId: envId ? Number(envId) : null, variableScopes: format === 'POSTMAN' ? variableScopes : null, skipInvalidRequests: skipInvalid, enableSignatureScripts: signatureScriptsEnabled }) })
      toast.success(`已追加 ${result.caseIds.length} 条用例${result.skippedCount ? `，跳过 ${result.skippedCount} 条错误请求` : ''}；环境：${result.environmentName}。请在用例管理中勾选后执行。`)
      onCommitted?.(); setOpen(false)
    } catch (error) { toast.error((error as Error).message) }
    finally { inFlight.current = false; setBusy(false) }
  }
  return <>
    <Button type="button" variant="outline" size="sm" onClick={() => setOpen(true)}>{kind === 'CURL' ? '导入 cURL' : '导入 Postman / OpenAPI'}</Button>
    <Dialog open={open} onOpenChange={value => { if (!busy) { readSession.current++; setReadingContent(false); setReadingEnvironment(false); setOpen(value) } }}>
      <DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-4xl">
        <DialogHeader><DialogTitle>{kind === 'CURL' ? 'cURL 载入草稿' : '导入请求集合'}</DialogTitle></DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto" data-testid="import-scroll-content"><fieldset disabled={busy} className="min-w-0 space-y-3">
          <p className="text-xs text-muted-foreground">先预览再追加；已有配置不会被覆盖。导入不发送业务请求，也不运行脚本。勾选下方开关后，前置脚本会保存为请求级签名脚本，未来执行该请求时才运行。</p>
          {kind !== 'CURL' && <select aria-label="导入格式" className="h-9 rounded border bg-background px-2 text-sm" value={format} onChange={e => { readSession.current++; setReadingContent(false); setReadingEnvironment(false); setFormat(e.target.value); setSkipInvalidRequests(false); setEnableSignatureScripts(false); setEnvironmentContent(''); resetSource() }}><option value="POSTMAN">Postman Collection v2.1 / 环境 JSON</option><option value="OPENAPI">OpenAPI 3.0 / 3.1 JSON</option></select>}
          <Label>待导入内容</Label>{kind === 'CURL'
            ? <CodeArea id={`import-${kind}`} value={content} formatJson={false} onChange={v => { setContent(v); resetSource() }} placeholder="curl 'https://example.test/api'" />
            : open && <ImportJsonSource key={format} id={`import-${kind}`} value={content} label="集合" session={readSession} onReadingChange={readContent} onChange={v => { setContent(v); resetSource() }} />}
          {format === 'POSTMAN' && open && <details><summary className="cursor-pointer text-xs">同时导入 Postman 环境（可选）</summary><ImportJsonSource id="import-environment" value={environmentContent} label="环境" session={readSession} onReadingChange={readEnvironment} onChange={v => { setEnvironmentContent(v); resetSource() }} /></details>}
          {kind !== 'CURL' && <div className="grid gap-2 sm:grid-cols-2"><Input aria-label="导入名称" placeholder="追加分组/新环境名称（可选）" value={name} onChange={e => setName(e.target.value)} /><select aria-label="导入目标环境" className="h-9 rounded border bg-background px-2 text-sm" value={envId} onChange={e => setEnvId(e.target.value)}><option value="">不含变量时使用默认环境</option>{envs.map(env => <option key={env.id} value={env.id}>{env.name}</option>)}</select></div>}
          {format === 'POSTMAN' && <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={includeSecrets} onChange={e => { setIncludeSecrets(e.target.checked); setNeedsPreview(true) }} />在本机显示密钥变量值（重新预览后生效）</label>}
          {format === 'POSTMAN' && <div className="space-y-1 rounded border p-3 text-xs"><label className="flex items-start gap-2"><input type="checkbox" data-testid="import-enable-signature-scripts" checked={enableSignatureScripts} onChange={event => { setEnableSignatureScripts(event.target.checked); setNeedsPreview(true) }} /><span>启用前置签名脚本导入（请求级，默认关闭）</span></label><p className="text-muted-foreground">仅将已启用的 prerequest 按集合→目录→请求顺序合并为 SCRIPT 鉴权；测试和禁用脚本仅保存在备注。已有其他自动鉴权会报冲突，每请求源码最多 64KiB UTF-8，超限阻断整批。不是完整 Postman 脚本兼容。</p></div>}
          {format === 'POSTMAN' && <div className="space-y-1 rounded border p-3 text-xs"><label className="flex items-start gap-2"><input type="checkbox" data-testid="import-skip-invalid" checked={skipInvalidRequests} onChange={e => { setSkipInvalidRequests(e.target.checked); setNeedsPreview(true) }} /><span>跳过错误请求，仅导入其余可转换请求</span></label><p className="text-muted-foreground">默认遇到错误阻止整批导入。勾选后须重新预览并核对跳过清单；格式、目录、变量和容量等全局错误仍会阻止导入。</p></div>}
          {format === 'POSTMAN' && preview && <ImportVariableMappingTable rows={preview.variableMappings ?? []} choices={variableScopes} showSecrets={includeSecrets && !needsPreview} onChange={(key, scopes) => { setVariableScopes(current => ({ ...current, [key]: scopes })); setNeedsPreview(true) }} />}
          {preview && <section className="space-y-2 rounded border p-3 text-xs" data-testid="import-preview"><p>{preview.name} · {preview.requests.length} 个请求 · 三组变量 {preview.variables.base.length}/{preview.variables.header.length}/{preview.variables.body.length}</p><p className="text-muted-foreground">{needsPreview ? '上次预览已过期；以下为上次解析结果。' : '以下仅为解析预览，尚未导入或执行。'}黄色警告不计入阻断错误数。</p><ImportDisclosure folders={preview.folders} losses={preview.losses} skippedRequests={skippedRequests} stale={needsPreview} signatureScriptsEnabled={previewScriptsEnabled} />{preview.errors.map((e, i) => <p role="alert" key={i} className="text-red-600">{e}</p>)}{preview.warnings.map((w, i) => <p key={i} className="text-amber-700 dark:text-amber-400">{w}</p>)}{preview.requests.map((request, i) => <details key={i} onToggle={event => { const open = event.currentTarget.open; setExpandedRequests(current => { const next = new Set(current); if (open) next.add(i); else next.delete(i); return next }) }}><summary className="cursor-pointer">{request.folder} · {request.spec.name} · {request.spec.method}</summary>{expandedRequests.has(i) && <JsonBlock text={JSON.stringify(request.spec, null, 2)} empty="空" title="转换后的请求" />}</details>)}</section>}
        </fieldset></div>
        <div className="shrink-0 space-y-2 border-t pt-3" data-testid="import-actions">
          <div role="status" aria-live="polite" className="text-xs" data-testid="import-state">
            {reading ? '正在读取文件，尚未导入。' : busy ? '正在处理，请稍候。' : !preview ? '尚未导入，请先预览。' : needsPreview ? '选项或映射已改变，旧预览已失效；尚未导入，请重新预览。' : blockingCount > 0 ? `尚未导入：还有 ${blockingCount} 项阻断错误，请修正后重新预览。` : noRequestsAfterSkipping ? '尚未导入：跳过后没有可追加的请求。' : kind === 'CURL' ? '仅预览，尚未发送；载入草稿后再核对并发送。' : '仅预览，尚未导入或执行；确认追加后请在用例管理中勾选执行。'}
          </div>
          {preview && <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs" data-testid="import-counts"><span>{needsPreview ? '上次解析' : '可导入'} <b>{preview.requests.length}</b> 条请求</span><span>{needsPreview ? '上次跳过' : '将跳过'} <b>{skippedCount}</b> 条请求</span><span className={blockingCount ? 'text-red-600' : ''}>阻断 <b>{blockingCount}</b> 项</span></p>}
          <div className="flex flex-wrap justify-end gap-2"><Button type="button" variant="outline" data-testid="import-preview-action" disabled={busy || reading || !content.trim()} onClick={() => void inspect()}>预览导入</Button><Button type="button" data-testid="import-commit-action" disabled={!canCommit} onClick={() => void commit()}>{kind === 'CURL' ? '载入草稿' : '确认追加导入'}</Button></div>
        </div>
      </DialogContent>
    </Dialog>
  </>
}
