import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Search, Copy, Plus, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { httpContinuation, inspectionReason } from '@/lib/http-continuation'
import type { ResponseField, ResponseInspection, PathInspection } from '@/lib/http-continuation'
import type { AssertionDraft } from '@/lib/apitest-workspace'
import type { ApiExtractor } from '@/types'

/** 长整数始终保留后端valueJson文本；仅JSON字符串需要在生成字符串期望时解码。 */
export function ResponseInspector({ body, headers, onAssert, onExtract }: {
  body?: string | null; headers?: string | null
  onAssert?: (row: AssertionDraft) => void; onExtract?: (row: ApiExtractor) => void
}) {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<{ body: typeof body; headers: typeof headers; value: ResponseInspection | null; error: string } | null>(null)
  const [query, setQuery] = useState('')
  const [path, setPath] = useState('')
  const [pathLoading, setPathLoading] = useState(false)
  const [pathTarget, setPathTarget] = useState<{ body: typeof body; headers: typeof headers } | null>(null)
  const [pathData, setPathData] = useState<{ body: typeof body; headers: typeof headers; value: PathInspection } | null>(null)
  const [selection, setSelection] = useState<{ body: typeof body; headers: typeof headers; field: ResponseField } | null>(null)
  const selected = selection && selection.body === body && selection.headers === headers ? selection.field : null
  const setSelected = (field: ResponseField | null) => setSelection(field ? { body, headers, field } : null)
  const [variable, setVariable] = useState('')
  const pathRequest = useRef<AbortController | null>(null)
  const currentData = data && data.body === body && data.headers === headers ? data : null
  const inspection = currentData?.value ?? null
  const error = currentData?.error ?? ''
  const loading = open && !currentData
  const pathBusy = pathLoading && pathTarget?.body === body && pathTarget?.headers === headers
  const direct = pathData && pathData.body === body && pathData.headers === headers ? pathData.value : null
  useEffect(() => {
    if (!open) return
    let active = true
    const controller = new AbortController()
    pathRequest.current?.abort()
    httpContinuation.inspect(body ?? '', headers, controller.signal).then(value => { if (active) setData({ body, headers, value, error: '' }) })
      .catch(e => { if (active && !controller.signal.aborted) setData({ body, headers, value: null, error: (e as Error).message }) })
    return () => { active = false; controller.abort(); pathRequest.current?.abort() }
  }, [open, body, headers])
  const select = (field: ResponseField) => { setSelected(field); setVariable(field.path.split('.').at(-1)?.replace(/[^A-Za-z0-9_]/g, '') || 'value') }
  const readPath = async () => {
    if (!path.trim()) return
    pathRequest.current?.abort()
    const controller = new AbortController(); pathRequest.current = controller
    setPathTarget({ body, headers }); setPathLoading(true); setPathData(null); setSelected(null)
    try { const value = await httpContinuation.inspectPath(body ?? '', headers, path.trim(), controller.signal); if (!controller.signal.aborted) setPathData({ body, headers, value }) }
    catch (e) { if (!controller.signal.aborted) setPathData({ body, headers, value: { found: false, fields: [], error: (e as Error).message, truncationReasons: [], limits: { maxFields: 500, maxDepth: 32, maxValueChars: 4096, previewChars: 160 } } }) }
    finally { if (!controller.signal.aborted) setPathLoading(false) }
  }
  const expect = (field: ResponseField) => {
    if (field.type === 'string') { try { return String(JSON.parse(field.valueJson ?? '')) } catch { return field.valueJson ?? '' } }
    return field.valueJson ?? ''
  }
  const typeOnly = selected != null && (selected.valueJson == null || selected.selectable === false || ['object', 'array', 'null'].includes(selected.type))
  const reasons = [...new Set([...(inspection?.truncationReasons ?? []), ...(direct?.truncationReasons ?? [])])]
  const fields = direct?.found ? direct.fields : (inspection?.fields ?? []).filter(f => f.path.toLowerCase().includes(query.toLowerCase()))
  const changeOpen = (value: boolean) => { if (!value) { pathRequest.current?.abort(); setSelected(null); setData(null); setPathData(null); setPath(''); setQuery('') }; setOpen(value) }
  return <>
    <Button type="button" size="sm" variant="outline" className="h-7 text-xs" disabled={!body && !headers} onClick={() => changeOpen(true)}><Search className="size-3.5" />选择响应字段</Button>
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent className="flex h-[80vh] max-h-[92vh] flex-col overflow-hidden sm:max-w-5xl">
        <DialogHeader><DialogTitle>响应字段 · 生成路径与规则</DialogTitle></DialogHeader>
        <p className="text-xs text-muted-foreground">路径从响应根开始。长 ID 保留原始精度。自动列表有限额，更深字段可直接输入路径读取；历史正文已截断时，未保存的内容无法恢复。</p>
        <Input aria-label="搜索响应路径" placeholder="搜索已列出的字段路径" value={query} onChange={e => { setQuery(e.target.value); setPathData(null); setSelected(null) }} />
        <div className="flex gap-2"><Input aria-label="直接读取响应路径" className="font-mono text-xs" placeholder="完整路径，如 data.players[0].id、/a.b 或 header:X-Id" value={path} onChange={e => { pathRequest.current?.abort(); setPathLoading(false); setPath(e.target.value); setPathData(null); setSelected(null) }} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void readPath() } }} /><Button type="button" variant="outline" size="sm" disabled={pathBusy || !path.trim()} onClick={() => void readPath()}>{pathBusy ? <Loader2 className="size-3.5 animate-spin" /> : <Search className="size-3.5" />}读取路径</Button>{direct && <Button type="button" variant="ghost" size="sm" onClick={() => { setPathData(null); setSelected(null) }}>返回列表</Button>}</div>
        {loading && <p className="flex gap-2 text-sm"><Loader2 className="size-4 animate-spin" />解析中…</p>}
        {!!reasons.length && <div className="space-y-1 text-xs text-amber-700 dark:text-amber-400">{reasons.map(reason => <p key={reason}>{inspectionReason[reason] ?? reason}。</p>)}{inspection?.limits && <p className="text-muted-foreground">自动列表最多 {inspection.limits.maxFields} 个字段、{inspection.limits.maxDepth} 层；单个值全文最多 {inspection.limits.maxValueChars} 字符。</p>}</div>}
        {!reasons.length && inspection?.truncated && <p className="text-xs text-amber-700 dark:text-amber-400">字段列表有省略，可手输路径读取。</p>}
        {(error || inspection?.error || direct?.error) && <p role="alert" className="text-sm text-amber-700 dark:text-amber-400">{direct?.error || error || inspection?.error}</p>}
        <div className="min-h-0 flex-1 overflow-auto rounded border">
          {fields.map((field, i) => <button type="button" key={`${field.path}-${i}`} data-testid={`response-field-${field.path}`} disabled={field.selectable === false && field.typeSelectable === false} onClick={() => select(field)} className={`grid w-full grid-cols-[1fr_80px_1fr] gap-3 border-b px-3 py-2 text-left text-xs hover:bg-muted/50 ${selected === field ? 'bg-primary/5' : ''}`}>
            <code className="break-all">{field.path || '（响应根）'}</code><span className="text-muted-foreground">{field.type}</span><code className="truncate" title={field.valueJson ?? undefined}>{field.preview}</code>
          </button>)}
          {!loading && inspection && fields.length === 0 && <p className="p-3 text-xs text-muted-foreground">没有匹配字段；可输入完整路径读取。</p>}
        </div>
        {selected && (inspection || direct) && <div className="shrink-0 space-y-2 rounded border bg-muted/20 p-3">
          <code className="block max-h-24 overflow-auto break-all text-xs">{selected.path || '（响应根）'} = {selected.valueJson ?? selected.preview}</code>
          {selected.valueJson == null && <p className="text-xs text-muted-foreground">值全文因大小限制未返回，仍可添加类型断言或按路径提取。</p>}
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(selected.path).then(() => toast.success('已复制路径')).catch(() => toast.error('复制失败'))}><Copy className="size-3.5" />复制路径</Button>
            {onAssert && <Button data-testid="inspect-add-assert" type="button" size="sm" variant="outline" onClick={() => { onAssert({ path: selected.path, op: typeOnly ? 'type' : 'equals', expect: typeOnly ? selected.type : expect(selected) }); changeOpen(false) }}><Plus className="size-3.5" />{typeOnly ? '添加类型断言' : '添加相等断言'}</Button>}
            {onExtract && <><Input aria-label="提取变量名称" className="w-40" placeholder="变量名" value={variable} onChange={e => setVariable(e.target.value)} /><Button data-testid="inspect-add-extract" type="button" size="sm" disabled={!/^[A-Za-z_][A-Za-z0-9_]*$/.test(variable)} onClick={() => { const header = selected.path.startsWith('header:'); onExtract({ name: variable, source: header ? 'HEADER' : 'JSON', path: header ? selected.path.slice(7) : selected.path, required: true }); changeOpen(false) }}>添加提取</Button></>}
          </div>
        </div>}
      </DialogContent>
    </Dialog>
  </>
}
