import { useContext, useEffect, useRef, useState } from 'react'
import { PageVisibilityContext } from '@/lib/page-visibility'
import { perfContinuation, type HistoryFilter } from '@/lib/perf-continuation'
import type { PerfGame, PerfRun } from '@/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'

const selectStyle = 'h-8 min-w-0 rounded-md border bg-background px-2 text-xs'
export function PerfHistoryBrowser({ games, onSelect }: { games: PerfGame[]; onSelect: (run: PerfRun) => void }) {
  const visible = useContext(PageVisibilityContext)
  const [open, setOpen] = useState(false), [items, setItems] = useState<PerfRun[]>([]), [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false), [error, setError] = useState(''), [loaded, setLoaded] = useState(false), [hasMore, setHasMore] = useState(false)
  const [gameId, setGameId] = useState(''), [mode, setMode] = useState(''), [status, setStatus] = useState(''), [cleanup, setCleanup] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('')
  const [resultFilter, setResultFilter] = useState<HistoryFilter>({})
  const lastAttempt = useRef<{ append: boolean; filter: HistoryFilter; cursor: string | null }>({ append: false, filter: {}, cursor: null })
  const controller = useRef<AbortController | null>(null), sequence = useRef(0), applied = useRef<HistoryFilter>({})
  const load = async (append: boolean, filter: HistoryFilter, pageCursor = append ? cursor : null) => {
    lastAttempt.current = { append, filter, cursor: pageCursor }
    controller.current?.abort(); const abort = new AbortController(); controller.current = abort; const seq = ++sequence.current
    setLoading(true); setError('')
    try {
      const page = await perfContinuation.history(filter, pageCursor, abort.signal)
      if (abort.signal.aborted || seq !== sequence.current) return
      setItems(previous => append ? [...new Map([...previous, ...page.items].map(run => [run.id, run])).values()] : page.items)
      setCursor(page.nextCursor ?? null); setHasMore(page.hasMore); setLoaded(true); applied.current = filter; setResultFilter(filter)
    } catch (cause) { if (!abort.signal.aborted && seq === sequence.current) setError(cause instanceof Error ? cause.message : '历史查询失败') }
    finally { if (!abort.signal.aborted && seq === sequence.current) setLoading(false) }
  }
  useEffect(() => {
    if (open && visible) void load(false, applied.current)
    return () => { controller.current?.abort(); ++sequence.current }
    // 筛选仅由“查询”提交，不在输入中发请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, visible])
  const apply = () => {
    try {
      const filter: HistoryFilter = { gameId: gameId ? Number(gameId) : undefined, mode, status, cleanupStatus: cleanup, from: from ? new Date(from).toISOString() : undefined, to: to ? new Date(to).toISOString() : undefined }
      void load(false, filter)
    } catch { setError('请填写有效的开始与结束时间') }
  }
  return <>
    <Button size="sm" variant="outline" onClick={() => setOpen(true)} data-testid="perf-history-open">检索历史</Button>
    <Dialog open={open} onOpenChange={setOpen}><DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-4xl" data-testid="perf-history-dialog">
      <DialogHeader><DialogTitle>性能运行历史</DialogTitle></DialogHeader>
      <form className="grid grid-cols-2 gap-2 md:grid-cols-4" onSubmit={event => { event.preventDefault(); apply() }}>
        <label className="space-y-1 text-xs">游戏<select className={`${selectStyle} w-full`} value={gameId} onChange={event => setGameId(event.target.value)} aria-label="历史游戏筛选"><option value="">全部游戏</option>{games.map(game => <option key={game.id} value={game.id}>{game.shortName || game.name}</option>)}</select></label>
        <label className="space-y-1 text-xs">模式<select className={`${selectStyle} w-full`} value={mode} onChange={event => setMode(event.target.value)} aria-label="历史模式筛选"><option value="">全部模式</option><option value="NORMAL">重复开关游戏</option><option value="AI_ROOM">游戏内性能监测</option><option value="EARLY_SETTLE">异常时序</option></select></label>
        <label className="space-y-1 text-xs">运行状态<select className={`${selectStyle} w-full`} value={status} onChange={event => setStatus(event.target.value)} aria-label="历史运行状态"><option value="">全部状态</option><option value="RUNNING,WAIT_DEVICE,WAIT_ENV">所有活动任务</option>{['COMPLETED', 'STOPPED', 'ABORTED', 'ERROR', 'INTERRUPTED'].map(value => <option key={value} value={value}>{value}</option>)}</select></label>
        <label className="space-y-1 text-xs">清理结果<select className={`${selectStyle} w-full`} value={cleanup} onChange={event => setCleanup(event.target.value)} aria-label="历史清理筛选"><option value="">全部清理结果</option><option value="FAILED">有清理失败圈</option><option value="SUCCESS">有清理成功圈</option><option value="NOT_RECORDED">有清理未记录圈</option></select></label>
        <label className="space-y-1 text-xs md:col-span-2">开始时间（本地）<Input type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} aria-label="历史开始时间" /></label>
        <label className="space-y-1 text-xs md:col-span-2">结束时间（本地）<Input type="datetime-local" value={to} onChange={event => setTo(event.target.value)} aria-label="历史结束时间" /></label>
        <Button type="submit" size="sm" disabled={loading} data-testid="perf-history-search">查询</Button><Button type="button" size="sm" variant="outline" disabled={loading} onClick={() => { setGameId(''); setMode(''); setStatus(''); setCleanup(''); setFrom(''); setTo(''); void load(false, {}) }}>清空筛选</Button>
      </form>
      {loaded && <p className="text-xs text-muted-foreground" data-testid="perf-history-applied">已加载条件：{resultFilter.gameId ? games.find(game => game.id === resultFilter.gameId)?.name || `游戏 #${resultFilter.gameId}` : '全部游戏'} · {resultFilter.mode || '全部模式'} · {resultFilter.status || '全部状态'} · 清理 {resultFilter.cleanupStatus || '全部'}{resultFilter.from && ` · 自 ${new Date(resultFilter.from).toLocaleString()}`}{resultFilter.to && ` · 至 ${new Date(resultFilter.to).toLocaleString()}`}。修改上方条件后点击“查询”才会生效。</p>}
      {loading && <p role="status" className="text-xs text-muted-foreground">{items.length ? '正在查询，保留上次结果…' : '正在查询历史…'}</p>}
      {error && <p role="alert" className="text-xs text-destructive" data-testid="perf-history-error">{error}{items.length > 0 && '；下方保留上次成功结果。'}<Button type="button" size="sm" variant="ghost" onClick={() => { const attempt = lastAttempt.current; void load(attempt.append, attempt.filter, attempt.cursor) }} disabled={loading}>重试本次查询</Button></p>}
      <div className="min-h-0 space-y-2 overflow-auto" data-testid="perf-history-results">
        {!loading && !error && loaded && items.length === 0 && <p className="py-6 text-center text-sm text-muted-foreground">当前条件没有匹配运行。</p>}
        {items.map(run => <button key={run.id} type="button" onClick={() => { onSelect(run); setOpen(false) }} className="flex w-full flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-left text-xs hover:bg-muted/50 focus-visible:outline-2 focus-visible:outline-ring" data-testid={`perf-history-run-${run.id}`}>
          <span className="min-w-0"><strong>运行 #{run.id}</strong> · {run.gameNames?.join('、') || '游戏名称未记录'}<span className="mt-1 block text-muted-foreground">{run.startTime ? new Date(run.startTime).toLocaleString() : '时间未记录'} · {run.mode} · {run.loopCount} 圈</span></span><Badge variant="outline">{run.status}</Badge>
        </button>)}
      </div>
      {hasMore && <Button type="button" variant="outline" size="sm" disabled={loading} onClick={() => void load(true, applied.current)} data-testid="perf-history-more">{loading ? '加载中…' : '加载更多'}</Button>}
    </DialogContent></Dialog>
  </>
}
