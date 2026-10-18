import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { req } from '@/lib/api'
import { readImportJson } from './import-json-source'

type Extracted = { schema: string; mediaType: string; status: string; warnings: string[] }
export function SchemaHelper({ onAppend }: { onAppend: (schema: string) => void }) {
  const [document, setDocument] = useState('')
  const [path, setPath] = useState('')
  const [method, setMethod] = useState('get')
  const [status, setStatus] = useState('200')
  const [result, setResult] = useState<Extracted | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const generation = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const invalidate = () => { generation.current++; controller.current?.abort(); setBusy(false); setResult(null); setError('') }
  useEffect(() => () => { generation.current++; controller.current?.abort() }, [])
  const load = async (file: File) => {
    invalidate(); const id = generation.current; setBusy(true)
    try { const text = await readImportJson(file); if (id === generation.current) setDocument(text) }
    catch (e) { if (id === generation.current) setError((e as Error).message) }
    finally { if (id === generation.current) setBusy(false) }
  }
  const extract = async () => {
    if (busy) return
    invalidate(); const id = generation.current; const abort = new AbortController(); controller.current = abort; setBusy(true)
    try {
      const value = await req<Extracted>('/api/apitest/schema/openapi', { method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: abort.signal, body: JSON.stringify({ document, path, method, status }) })
      if (id === generation.current) setResult(value)
    } catch (e) { if (id === generation.current) setError((e as Error).message) }
    finally { if (id === generation.current) setBusy(false) }
  }
  return <details className="space-y-2 rounded border p-3 text-xs">
    <summary className="cursor-pointer font-medium">从 OpenAPI 提取响应 Schema</summary>
    <p className="text-muted-foreground">读取本机 OpenAPI 3.0 / 3.1 JSON，指定响应后添加结构断言。此操作不发送业务请求。</p>
    <input type="file" accept=".json,application/json" aria-label="选择OpenAPI Schema来源文件" disabled={busy} onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void load(file) }} />
    <textarea aria-label="OpenAPI Schema来源内容" className="h-24 w-full rounded border bg-background p-2 font-mono" placeholder="选择文件或粘贴 OpenAPI JSON" value={document} onChange={event => { invalidate(); setDocument(event.target.value) }} />
    <div className="grid gap-2 sm:grid-cols-[90px_1fr_120px]">
      <select aria-label="Schema来源方法" value={method} className="h-9 rounded border bg-background px-2" onChange={event => { invalidate(); setMethod(event.target.value) }}>{['get', 'post', 'put', 'patch', 'delete', 'head', 'options'].map(value => <option key={value}>{value}</option>)}</select>
      <Input aria-label="Schema来源路径" value={path} placeholder="/items/{id}（按文档原路径）" onChange={event => { invalidate(); setPath(event.target.value) }} />
      <Input aria-label="Schema来源响应码" value={status} placeholder="200 / default" onChange={event => { invalidate(); setStatus(event.target.value) }} />
    </div>
    <div className="flex gap-2"><Button type="button" size="sm" variant="outline" disabled={busy || !document || !path || !status} onClick={() => void extract()}>{busy ? '读取中…' : '提取响应 Schema'}</Button>{busy && <Button type="button" size="sm" variant="ghost" onClick={invalidate}>取消</Button>}</div>
    {error && <p role="alert" className="text-red-600">{error}</p>}
    {result && <div className="space-y-2"><p>{result.status} · {result.mediaType}</p><textarea readOnly aria-label="提取的响应Schema" className="h-32 w-full rounded border bg-background p-2 font-mono" value={result.schema} />{result.warnings.map((warning, i) => <p key={i} className="text-muted-foreground">{warning}</p>)}<Button type="button" size="sm" onClick={() => { onAppend(result.schema); setResult(null) }}>添加到断言（仍需保存用例）</Button></div>}
  </details>
}
