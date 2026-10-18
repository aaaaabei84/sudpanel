import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { assertionPath, assertionSource, type AssertionDraft } from '@/lib/apitest-workspace'
import { SchemaHelper } from './schema-helper'

const OP_GROUPS = [
  ['存在性', [['exists', '存在'], ['notExists', '不存在']]],
  ['类型', [['type', '是某类型']]],
  ['相等', [['equals', '等于'], ['notEquals', '不等于']]],
  ['文本', [['contains', '包含'], ['regex', '正则匹配']]],
  ['数值', [['gt', '大于'], ['gte', '大于等于'], ['lt', '小于'], ['lte', '小于等于']]],
  ['长度', [['length', '长度等于']]],
  ['集合', [['in', '属于集合']]],
  ['结构', [['schema', '符合 Schema']]],
] as const
export function AssertionEditor({ rows, onChange }: { rows: AssertionDraft[]; onChange: (rows: AssertionDraft[]) => void }) {
  const patch = (i: number, p: Partial<AssertionDraft>) => onChange(rows.map((r, k) => k === i ? { ...r, ...p } : r))
  return <div className="space-y-2">
    <div className="flex items-center justify-between"><Label>断言</Label><Button size="sm" variant="outline" type="button" onClick={() => onChange([...rows, { path: '', op: 'equals', expect: '' }])}><Plus className="size-3.5" />添加断言</Button></div>
    <p className="text-xs text-muted-foreground">JSON 路径以响应根为起点，如 data.players[0].id；响应头名称不区分大小写；耗时单位为毫秒。文件属性路径可填 $response.bytes（字节数）、$response.sha256、$response.contentType；二进制不支持普通正文断言。</p>
    {rows.length === 0 && <p className="rounded-md border border-dashed p-3 text-xs text-muted-foreground">还没有断言，可点击「添加断言」配置校验。</p>}
    {rows.map((row, i) => {
      const source = assertionSource(row.path)
      return <div key={i} className="grid gap-2 sm:grid-cols-[110px_1fr_120px_1fr_auto]">
        <select aria-label={`断言来源 ${i + 1}`} className="h-9 rounded-md border bg-background px-2 text-xs" value={source} onChange={e => patch(i, { path: assertionPath(e.target.value, row.path), op: e.target.value === 'ELAPSED' ? 'lte' : e.target.value !== 'BODY' && row.op === 'schema' ? 'equals' : row.op })}><option value="BODY">响应 JSON</option><option value="HEADER">响应头</option><option value="ELAPSED">响应耗时</option></select>
        <Input aria-label={`断言路径 ${i + 1}`} disabled={source === 'ELAPSED'} placeholder={source === 'HEADER' ? 'Content-Type' : 'data.token；留空=响应根'} value={source === 'HEADER' ? row.path.slice(7) : source === 'ELAPSED' ? '耗时（毫秒）' : row.path} onChange={e => patch(i, { path: assertionPath(source, e.target.value) })} />
        <select aria-label={`断言条件 ${i + 1}`} className="h-9 rounded-md border bg-background px-2 text-xs" value={row.op} onChange={e => patch(i, { op: e.target.value })}>{OP_GROUPS.map(([group, ops]) => {
          const available = ops.filter(([op]) => (source !== 'ELAPSED' || ['gt', 'gte', 'lt', 'lte', 'equals'].includes(op)) && (op !== 'schema' || source === 'BODY'))
          return available.length > 0 ? <optgroup key={group} label={group}>{available.map(([op, label]) => <option key={op} value={op}>{label}</option>)}</optgroup> : null
        })}</select>
        {row.op === 'schema' ? <textarea aria-label={`断言Schema ${i + 1}`} className="h-32 w-full rounded border bg-background p-2 font-mono text-xs" value={row.expect} placeholder='{"type":"object"}' onChange={event => patch(i, { expect: event.target.value })} /> : row.op === 'type' ? <select aria-label={`期望类型 ${i + 1}`} className="h-9 rounded-md border bg-background px-2 text-xs" value={row.expect} onChange={e => patch(i, { expect: e.target.value })}><option value="">选择类型</option>{['string', 'number', 'integer', 'boolean', 'object', 'array', 'null'].map(t => <option key={t}>{t}</option>)}</select> : <Input aria-label={`断言期望 ${i + 1}`} disabled={['exists', 'notExists'].includes(row.op)} value={row.expect} placeholder={row.op === 'regex' ? '^pattern$' : '期望值'} onChange={e => patch(i, { expect: e.target.value })} />}
        <Button type="button" size="icon" variant="ghost" aria-label={`删除断言 ${i + 1}`} onClick={() => onChange(rows.filter((_, k) => k !== i))}><Trash2 className="size-3.5" /></Button>
      </div>
    })}
    <p className="text-xs text-muted-foreground">Schema 支持 draft-07 / 2020-12，仅本文件有限引用；format 作为注释。Schema 原文不替换变量。</p>
    <SchemaHelper onAppend={schema => onChange([...rows, { path: '', op: 'schema', expect: schema }])} />
  </div>
}
