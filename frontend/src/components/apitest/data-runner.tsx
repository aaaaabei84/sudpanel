import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { req, apitestApi } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { CodeArea } from '@/components/code-area'
import { JsonBlock } from '@/components/json-block'
import type { ApiBatch, ApiScenario } from '@/types'

export function DataRunner({ ids, running, onRun }: { ids: number[]; running: boolean; onRun: (batch: ApiBatch) => void }) {
  const [open, setOpen] = useState(false)
  const [format, setFormat] = useState('CSV')
  const [content, setContent] = useState('')
  const [name, setName] = useState('数据驱动回归')
  const [scenarios, setScenarios] = useState<ApiScenario[]>([])
  const [scenarioId, setScenarioId] = useState('')
  const [preview, setPreview] = useState<{ columns: string[]; rowCount: number; sample: unknown } | null>(null)
  const [repeat, setRepeat] = useState(1)
  const [interval, setInterval] = useState(0)
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (open) void apitestApi.scenarios().then(setScenarios).catch(e => toast.error((e as Error).message)) }, [open])
  const inspect = async () => {
    setBusy(true); setPreview(null)
    try { setPreview(await req('/api/apitest/data/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ format, content }) })) }
    catch (e) { toast.error((e as Error).message) }
    finally { setBusy(false) }
  }
  const start = async () => {
    if (!preview || (!scenarioId && !ids.length)) return
    setBusy(true)
    try {
      const batch = await req<ApiBatch>('/api/apitest/data/run', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, caseIds: ids, scenarioId: scenarioId ? Number(scenarioId) : null, data: { format, content }, intervalSeconds: interval, repeatCount: repeat }) })
      onRun(batch); setOpen(false)
    } catch (e) { toast.error((e as Error).message) }
    finally { setBusy(false) }
  }
  return <>
    <Button type="button" size="sm" variant="outline" disabled={running} onClick={() => setOpen(true)}>数据驱动</Button>
    <Dialog open={open} onOpenChange={v => { if (!busy) setOpen(v) }}><DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-4xl"><DialogHeader><DialogTitle>CSV / JSON 数据驱动</DialogTitle></DialogHeader>
      <fieldset disabled={busy} className="min-h-0 space-y-3 overflow-y-auto">
        <p className="text-xs text-muted-foreground">请求和断言期望可引用 {'{{data.列名}}'}。每行有独立的本轮变量，清理步骤逐行执行；不会写回环境。CSV 为字符串，JSON 保留类型和长整数。最多 1000 行、200 列。</p>
        <div className="grid gap-2 sm:grid-cols-2"><Input aria-label="数据批次名称" value={name} onChange={e => setName(e.target.value)} /><select aria-label="数据执行计划" className="h-9 rounded border bg-background px-2 text-sm" value={scenarioId} onChange={e => setScenarioId(e.target.value)}><option value="">当前执行计划（{ids.length} 条）</option>{scenarios.map(s => <option key={s.id} value={s.id}>请求链：{s.name}</option>)}</select></div>
        <select aria-label="数据格式" className="h-9 rounded border bg-background px-2 text-sm" value={format} onChange={e => { setFormat(e.target.value); setPreview(null) }}><option value="CSV">CSV（首行列名）</option><option value="JSON">JSON 对象数组</option></select>
        <CodeArea id="data-content" value={content} formatJson={false} onChange={v => { setContent(v); setPreview(null) }} placeholder={format === 'CSV' ? 'id,name\n001,示例' : '[{"id":"001","name":"示例"}]'} />
        <div className="flex flex-wrap items-center gap-2 text-xs"><label>重复次数<Input aria-label="数据重复次数" className="w-24" type="number" min={1} max={1000} value={repeat} onChange={e => setRepeat(Number(e.target.value))} /></label><label>行间隔（秒）<Input aria-label="数据行间隔" className="w-24" type="number" min={0} max={3600} value={interval} onChange={e => setInterval(Number(e.target.value))} /></label><Button type="button" variant="outline" disabled={!content.trim()} onClick={() => void inspect()}>预览数据</Button><Button type="button" disabled={!preview || (!scenarioId && !ids.length) || running} onClick={() => void start()}>开始数据回归</Button></div>
        {preview && <div className="space-y-2 rounded border p-3 text-xs"><p>{preview.rowCount} 行 · {preview.columns.length} 列 · 共 {preview.rowCount * repeat} 轮</p><p className="font-mono">{preview.columns.map(c => `{{data.${c}}}`).join('、')}</p><JsonBlock text={JSON.stringify(preview.sample, null, 2)} empty="空" title="前五行（值以 JSON 文本保存）" /></div>}
      </fieldset>
    </DialogContent></Dialog>
  </>
}
