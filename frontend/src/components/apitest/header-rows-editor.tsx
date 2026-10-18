import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { CodeArea } from '@/components/code-area'
import { VarInput } from '@/components/var-input'
import type { VarSuggestion } from '@/lib/var-suggest'
import type { ApiHeaderRow } from '@/types'

export function HeaderRowsEditor({ raw, rows, onRaw, onRows, rawId = "ordered-header-raw", suggestions }: { suggestions?: VarSuggestion[]; rawId?: string; raw: string; rows?: ApiHeaderRow[] | null; onRaw: (raw: string) => void; onRows: (rows: ApiHeaderRow[] | undefined) => void }) {
  const [error, setError] = useState(''), [showValues,setShowValues]=useState(false)
  const table = () => { try { const parsed: unknown = JSON.parse(raw || '{}'); if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some(v => typeof v !== 'string')) throw Error('切换前请将原始JSON头值写成字符串，避免数字或结构转换丢失'); onRows(Object.entries(parsed).map(([name,value]) => ({ name, value: String(value), enabled: true }))); setError('') } catch (e) { setError((e as Error).message) } }
  const original = () => { const seen = new Set<string>(); if (rows?.some(r => r.enabled === false || seen.has(r.name.toLowerCase()) || !seen.add(r.name.toLowerCase()))) { setError('原始JSON无法表示禁用行或同名多值；请先整理这些行，当前表格已保留'); return } onRaw(JSON.stringify(Object.fromEntries((rows ?? []).map(r => [r.name,r.value])),null,2)); onRows(undefined); setError('') }
  const patch = (i: number, p: Partial<ApiHeaderRow>) => onRows((rows ?? []).map((r,k) => k === i ? {...r,...p} : r))
  return <section className="space-y-2" data-testid="header-rows-editor"><div className="flex gap-2"><Button type="button" size="sm" variant="outline" onClick={rows == null ? table : original}>{rows == null ? '切换请求头表格' : '切换原始JSON'}</Button>{rows != null && <Button type="button" size="sm" variant="outline" onClick={() => onRows([...rows,{name:'',value:'',enabled:true}])}>添加请求头行</Button>}</div>
    <label className="flex gap-2 text-xs"><input type="checkbox" checked={showValues} onChange={e=>setShowValues(e.target.checked)}/>显示请求头值（可能含秘密）</label><p className="text-xs text-muted-foreground">表格按保存顺序发送同名多值；HTTP客户端决定不同名称的wire排列。Authorization/Cookie/Content-Type只允许一个启用值；Host、Content-Length等由客户端管理。签名与自动鉴权覆盖最终值。</p>
    {error && <p role="alert" className="text-xs text-red-600">{error}</p>}
    {rows == null ? <CodeArea id={rawId} suggestions={suggestions} formatJson={false} value={raw} onChange={onRaw} placeholder={'{"X-Trace":"value"}'} /> : rows.map((r,i) => <div key={i} className="grid grid-cols-[auto_1fr_2fr_auto] items-center gap-2"><input aria-label={`启用请求头行 ${i+1}`} type="checkbox" checked={r.enabled !== false} onChange={e => patch(i,{enabled:e.target.checked})} /><Input aria-label={`请求头名 ${i+1}`} value={r.name} onChange={e => patch(i,{name:e.target.value})} />{showValues ? <VarInput aria-label={`请求头值 ${i+1}`} value={r.value} suggestions={suggestions} onChange={value => patch(i,{value})} /> : <Input type="password" aria-label={`请求头值 ${i+1}`} value={r.value} onChange={e => patch(i,{value:e.target.value})} />}<Button size="sm" type="button" variant="ghost" onClick={() => onRows(rows.filter((_,k)=>k!==i))}>删除</Button></div>)}
  </section>
}
