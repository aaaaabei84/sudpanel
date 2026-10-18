import { useEffect, useRef, useState } from 'react'
import { Plus, Trash2, Upload } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { req } from '@/lib/api'
import type { ApiManagedFile, ApiRequestOptions, ApiFilePart } from '@/types'

interface Files { bytes: number; maxBytes: number; files: ApiManagedFile[]; retention: string }
const cls = 'h-9 rounded border bg-background px-2 text-xs'

export function FileBodyEditor({ value, onChange }: { value: ApiRequestOptions; onChange: (v: ApiRequestOptions) => void }) {
  const [files, setFiles] = useState<Files | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState<number | null>(null)
  const [manager, setManager] = useState(false)
  const latest = useRef(value); latest.current = value
  const sequence = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const refresh = async () => { try { setFiles(await req<Files>('/api/apitest/files')); setError('') } catch (e) { setError((e as Error).message) } }
  useEffect(() => { void refresh(); return () => { sequence.current++; controller.current?.abort() } }, [])
  const parts = value.parts ?? []
  const patchPart = (i: number, p: Partial<ApiFilePart>) => onChange({ ...value, parts: parts.map((v, k) => k === i ? { ...v, ...p } : v) })
  const upload = async (file: File, i: number) => {
    if (file.size > 16 * 1024 * 1024) { toast.error('单文件最多16MiB；请选择更小文件'); return }
    const generation = ++sequence.current, source = latest.current
    controller.current?.abort(); const abort = new AbortController(); controller.current = abort; setBusy(i); setError('')
    try {
      const f = await req<ApiManagedFile>(`/api/apitest/files?filename=${encodeURIComponent(file.name)}&contentType=${encodeURIComponent(file.type || 'application/octet-stream')}`, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file, signal: abort.signal })
      if (generation !== sequence.current || latest.current !== source) return
      if (i < 0) onChange({ ...source, fileBody: { fileId: f.fileId, filename: f.filename, contentType: f.contentType } })
      else onChange({ ...source, parts: (source.parts ?? []).map((p, k) => k === i ? { ...p, fileId: f.fileId, filename: f.filename, contentType: f.contentType } : p) })
      toast.success(`已保存原字节：${f.bytes} 字节`); void refresh()
    } catch (e) { if (generation === sequence.current && !abort.signal.aborted) setError((e as Error).message) }
    finally { if (generation === sequence.current) { setBusy(null); controller.current = null } }
  }
  const picker = (id: string | undefined, i: number) => <div className="flex flex-wrap items-center gap-2">
    <select className={`${cls} min-w-48 flex-1`} aria-label={i < 0 ? 'binary受管文件' : `multipart受管文件 ${i + 1}`} value={id ?? ''} onChange={e => {
      const f = files?.files.find(f => f.fileId === e.target.value)
      if (i < 0) onChange({ ...value, fileBody: f ? { fileId: f.fileId, filename: f.filename, contentType: f.contentType } : null })
      else patchPart(i, { fileId: f?.fileId, filename: f?.filename, contentType: f?.contentType })
    }}><option value="">选择已上传文件</option>{id && !files?.files.some(f => f.fileId === id) && <option value={id}>引用缺失：请补传并替换</option>}{files?.files.filter(f => f.kind === 'UPLOAD').map(f => <option key={f.fileId} value={f.fileId}>{f.filename} · {f.bytes} 字节 · {f.fileId.slice(0, 8)}</option>)}</select>
    <label className="relative inline-flex h-9 cursor-pointer items-center gap-1 rounded border px-3 text-xs"><Upload className="size-3.5" />{busy === i ? '上传中…' : '选择文件上传'}<input type="file" aria-label={i < 0 ? '上传binary文件' : `上传multipart文件 ${i + 1}`} className="absolute inset-0 w-full cursor-pointer opacity-0" onChange={e => { const f = e.currentTarget.files?.[0]; e.currentTarget.value = ''; if (f) void upload(f, i) }} /></label>
    {id && <span className="w-full break-all font-mono text-xs text-muted-foreground">fileId：{id}{files?.files.find(f => f.fileId === id)?.sha256 ? ` · SHA256：${files.files.find(f => f.fileId === id)!.sha256}` : ''}</span>}
  </div>
  return <div className="space-y-3 rounded border p-3" data-testid="file-body-editor">
    <p className="text-xs text-muted-foreground">单文件最多16MiB，multipart合计最多64MiB。原字节在本机跨重启保留；完整本机备份包含文件，配置分享包只有引用。若恢复后提示文件缺失，请补传并重新选择，平台不会空体重发。</p>
    {error && <p role="alert" className="text-xs text-red-600">{error} <button type="button" className="underline" onClick={() => void refresh()}>重新加载</button>；目录或磁盘故障请修复权限/空间后重启，原字节正本在数据库中。</p>}
    <fieldset disabled={busy !== null} className="space-y-3">
      {value.bodyType === 'BINARY' ? <>{picker(value.fileBody?.fileId, -1)}<Input aria-label="binary Content-Type" value={value.fileBody?.contentType ?? ''} placeholder="application/octet-stream" disabled={!value.fileBody} onChange={e => onChange({ ...value, fileBody: value.fileBody ? { ...value.fileBody, contentType: e.target.value } : null })} /></> : <>
        <div className="flex items-center justify-between"><strong className="text-xs">multipart字段（按顺序发送，支持重复名称）</strong><Button type="button" size="sm" variant="outline" onClick={() => onChange({ ...value, parts: [...parts, { name: '', type: 'TEXT', value: '', enabled: true }] })}><Plus className="size-3.5" />添加part</Button></div>
        {parts.map((p, i) => <div key={i} className="space-y-2 rounded border p-2">
          <div className="flex items-center gap-2"><Checkbox aria-label={`启用multipart ${i + 1}`} checked={p.enabled !== false} onCheckedChange={v => patchPart(i, { enabled: v === true })} /><Input aria-label={`multipart字段名 ${i + 1}`} placeholder="字段名，允许重复" value={p.name} onChange={e => patchPart(i, { name: e.target.value })} /><select aria-label={`multipart类型 ${i + 1}`} className={cls} value={p.type} onChange={e => patchPart(i, { type: e.target.value as 'TEXT' | 'FILE' })}><option value="TEXT">文本</option><option value="FILE">文件</option></select><Button type="button" size="icon" variant="ghost" aria-label={`删除multipart ${i + 1}`} onClick={() => onChange({ ...value, parts: parts.filter((_, k) => k !== i) })}><Trash2 className="size-3.5" /></Button></div>
          {p.type === 'FILE' ? <>{picker(p.fileId, i)}<div className="grid gap-2 sm:grid-cols-2"><Input aria-label={`multipart文件名 ${i + 1}`} placeholder="发送文件名" value={p.filename ?? ''} onChange={e => patchPart(i, { filename: e.target.value || undefined })} /><Input aria-label={`multipart Content-Type ${i + 1}`} placeholder="文件 MIME，空为上传时类型" value={p.contentType ?? ''} onChange={e => patchPart(i, { contentType: e.target.value || undefined })} /></div></> : <Input aria-label={`multipart文本 ${i + 1}`} placeholder="文本；支持请求体变量" value={p.value ?? ''} onChange={e => patchPart(i, { value: e.target.value })} />}
        </div>)}
        <p className="text-xs text-muted-foreground">boundary与Content-Type自动生成；预览只显示字段/文件摘要。先确定编码后的完整字节再签名，不改文件内容。</p>
      </>}
      <Button type="button" size="sm" variant="outline" onClick={() => { setManager(true); void refresh() }}>受管文件管理</Button>
    </fieldset>
    <Dialog open={manager} onOpenChange={setManager}><DialogContent className="max-h-[80vh] overflow-auto sm:max-w-3xl"><DialogHeader><DialogTitle>受管文件管理</DialogTitle></DialogHeader>{error && <p role="alert" className="text-xs text-red-600">{error}</p>}<p className="text-xs text-muted-foreground">{files?.retention}</p><p className="text-xs text-muted-foreground">容量不足或文件仍被历史引用时，先关闭此窗口，在页面「数据维护」生成完整本机备份，再预览清理不再需要的历史；最后回到这里清理无引用文件。被用例、工作区或冻结计划引用的文件会保留。</p><p className="text-xs">已用 {files?.bytes ?? 0} / {files?.maxBytes ?? 134217728} 字节 · 上限2000个</p><Button type="button" size="sm" variant="outline" onClick={() => { if (window.confirm('清理未被保存的用例、工作区、计划和历史引用的文件。请先保存当前草稿；未保存的选择可能失效。继续？')) void req('/api/apitest/files/cleanup-orphans', { method: 'POST' }).then(() => refresh()).catch(e => setError((e as Error).message)) }}>清理无引用文件</Button>{files?.files.map(f => <div key={f.fileId} className="space-y-1 rounded border p-2 text-xs"><div className="flex items-center justify-between gap-2"><strong>{f.filename}</strong><span>{f.kind} · {f.bytes} 字节</span><a className="underline" href={`/api/apitest/files/${f.fileId}/download`} download>下载原字节</a><Button type="button" size="sm" variant="ghost" onClick={() => { if (window.confirm(`删除 ${f.filename}？已保存引用或正在使用的文件会被拒绝。`)) void req(`/api/apitest/files/${f.fileId}`, { method: 'DELETE' }).then(() => refresh()).catch(e => setError((e as Error).message)) }}>删除</Button></div><p className="break-all font-mono">{f.contentType} · SHA256 {f.sha256}</p><p className="break-all text-muted-foreground">{f.fileId}</p></div>)}</DialogContent></Dialog>
  </div>
}
