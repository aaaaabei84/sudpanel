import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Plus, RotateCcw, X } from 'lucide-react'
import { RequestEditor, type WorkbenchProps } from './request-workbench'
import { useRequestWorkspace } from '@/hooks/use-request-workspace'
import { requestTab, type RequestTab } from '@/lib/request-workspace'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'

export function RequestWorkbench(props: WorkbenchProps) {
  const workspace = useRequestWorkspace(props.open), { value, setValue } = workspace
  const [busyTabs, setBusyTabs] = useState<Set<string>>(new Set())
  const [closing, setClosing] = useState<string | null>(null), [incoming, setIncoming] = useState<RequestTab | null>(null)
  const seenContext = useRef(props.context), seenHistory = useRef(props.historyDraft)
  const initialIncoming = useRef(false)
  const add = (tab: RequestTab = requestTab()) => {
    if (value.tabs.length >= 12) { setIncoming(tab); toast.info('最多同时打开 12 个请求；关闭一个标签后可打开待处理请求。'); return }
    setValue(old => ({ ...old, tabs: [...old.tabs, tab], activeId: tab.id })); setIncoming(null)
  }
  useEffect(() => {
    if (!workspace.ready) return
    const first = !initialIncoming.current; initialIncoming.current = true
    if (props.historyDraft && (first || seenHistory.current !== props.historyDraft)) {
      seenHistory.current = props.historyDraft
      const source = props.historyDraft
      add({ ...requestTab(), title: '历史请求', draft: source.spec, envId: source.envId == null ? '' : String(source.envId), dirty: true })
      source.warnings.forEach(warning => toast.info(warning))
    } else if (props.context && (first || seenContext.current !== props.context)) {
      seenContext.current = props.context
      const context = props.context, tab = requestTab()
      tab.title = `第 ${context.loopNo} 圈结算查询`; tab.envId = context.envId == null ? '' : String(context.envId); tab.sourceContext = context; tab.dirty = true
      tab.draft = { ...tab.draft, name: `${context.gameName} · 第 ${context.loopNo} 圈结算查询`, method: 'POST', body: JSON.stringify({ game_round_id: context.roundId }, null, 2), options: { bodyType: 'JSON', auth: { type: 'INHERIT' }, params: [], extractors: [] }, note: `来自性能运行 #${context.runId}，游戏 #${context.gameId}，圈次 ${context.loopNo}；环境来源：${context.envSource ?? '未知，发送前明确选择'}` }
      add(tab)
    }
  }, [workspace.ready, props.context, props.historyDraft])
  const close = (id: string) => {
    setValue(old => {
      const target = old.tabs.find(tab => tab.id === id)
      if (!target) return old
      const remaining = old.tabs.filter(tab => tab.id !== id)
      if (!remaining.length) remaining.push(requestTab())
      return { ...old, tabs: remaining, closed: [target, ...old.closed].slice(0, 12), activeId: old.activeId === id ? remaining[0].id : old.activeId }
    }); setClosing(null)
  }
  const patch = (id: string, changes: Partial<RequestTab>) => setValue(old => ({ ...old, tabs: old.tabs.map(tab => tab.id === id ? { ...tab, ...changes } : tab) }))
  const header = <div className="shrink-0 space-y-2">
    <div className="flex flex-wrap items-center gap-1" role="tablist" aria-label="请求标签">{value.tabs.map((tab, index) => <div key={tab.id} className="flex items-center rounded border"><Button type="button" size="sm" role="tab" aria-selected={value.activeId === tab.id} variant={value.activeId === tab.id ? 'secondary' : 'ghost'} onClick={() => { setValue(old => ({ ...old, activeId: tab.id })); setClosing(null) }}>{tab.title || tab.draft.name || `请求 ${index + 1}`}{tab.dirty ? ' · 已修改' : ''}</Button><Button type="button" size="icon-sm" variant="ghost" aria-label={`关闭请求标签 ${index + 1}`} disabled={busyTabs.has(tab.id)} onClick={() => tab.dirty ? setClosing(tab.id) : close(tab.id)}><X className="size-3" /></Button></div>)}<Button size="sm" variant="outline" onClick={() => add()}><Plus className="size-3" />新标签</Button><Button size="sm" variant="outline" disabled={!value.closed.length || value.tabs.length >= 12} onClick={() => setValue(old => { const tab = old.closed[0]; return { ...old, tabs: [...old.tabs, tab], closed: old.closed.slice(1), activeId: tab.id } })}><RotateCcw className="size-3" />恢复关闭</Button></div>
    <div className="flex flex-wrap items-center gap-2 text-xs"><Input aria-label="当前标签名称" className="h-7 w-52 text-xs" value={value.tabs.find(tab => tab.id === value.activeId)?.title ?? ''} placeholder="给当前标签命名" onChange={e => patch(value.activeId, { title: e.target.value })} /><span role="status" className="text-muted-foreground">{workspace.saving ? '正在保存草稿…' : workspace.pending ? '草稿待保存' : workspace.savedAt ? '草稿已加密保存' : '空白草稿'}</span><Button size="sm" variant="ghost" onClick={() => void workspace.flush()}>立即保存草稿</Button></div>
    {workspace.error && <p role="alert" className="text-xs text-amber-700 dark:text-amber-400">{workspace.error}。当前内容仍保留，请重试保存。</p>}
    {closing && <div role="alert" className="flex flex-wrap items-center gap-2 rounded border p-2 text-xs"><span>标签有未另存为用例的编辑。关闭后保留在最近 12 个关闭标签中。</span><Button size="sm" variant="outline" onClick={() => setClosing(null)}>继续编辑</Button><Button size="sm" onClick={() => close(closing)}>保留草稿并关闭标签</Button></div>}
    {incoming && <Button size="sm" variant="outline" disabled={value.tabs.length >= 12} onClick={() => add(incoming)}>打开待处理请求</Button>}
  </div>
  if (!workspace.ready) return <Dialog open={props.open} onOpenChange={props.onOpenChange}><DialogContent className="sm:max-w-lg"><DialogHeader><DialogTitle>快速调试</DialogTitle></DialogHeader>{workspace.error ? <div role="alert" className="space-y-2"><p className="text-sm">工作区加载失败：{workspace.error}</p><Button onClick={() => void workspace.load()}>重试读取草稿</Button></div> : <p role="status">正在读取本机草稿…</p>}</DialogContent></Dialog>
  return <>{value.tabs.map(tab => <RequestEditor {...props} context={undefined} historyDraft={undefined} key={tab.id} initial={tab} open={props.open && value.activeId === tab.id} onOpenChange={open => { if (!open) void workspace.flush(); props.onOpenChange(open) }} onBusy={busy => setBusyTabs(old => { if (old.has(tab.id) === busy) return old; const next = new Set(old); if (busy) next.add(tab.id); else next.delete(tab.id); return next })} onSnapshot={changes => patch(tab.id, changes)} tabHeader={header} onNew={() => add()} />)}</>
}
