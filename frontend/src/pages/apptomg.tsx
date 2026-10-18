import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Play, Square, ListChecks, Search, ExternalLink, History, Plus, ArrowUp, ArrowDown, Trash2, ChevronDown, ChevronUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ConfigBar } from '@/components/config-bar'
import { ResultTable, ResultSummary } from '@/components/result-table'
import { api, perfApi, reportUrl, exportUrl } from '@/lib/api'
import { groupOptionLabel } from '@/lib/button-group-label'
import { useDevices } from '@/lib/device-context'
import type {
  ApptomgCoordSource,
  HistoryResultItem,
  PerfButtonGroup,
  RunListItem,
  RunResults,
  RunResult,
  TestPoint,
} from '@/types'

/**
 * localStorage 键：记住 apptomg 选中的按钮坐标分组。
 *
 * 【为什么放前端而不是落库】本工具是本地单机、无登录、单用户，
 * 这个选择纯粹是"我这个人在这个浏览器上的操作偏好"，不是业务数据；
 * 落库要额外加表 + 两个接口，收益为零。页面切换/刷新要能记住，localStorage 就够了。
 */
const COORD_GROUP_STORAGE_KEY = 'sudpanel.apptomg.buttonGroupId'

/** Radix Select 不允许空字符串值，用哨兵值表示「不使用分组」 */
const NO_GROUP = 'none'

export function ApptomgPage() {
  const { selected: device } = useDevices()
  const [points, setPoints] = useState<TestPoint[]>([])
  const [loading, setLoading] = useState(true)
  // 执行队列：number[]，同一测试点可重复添加，顺序即执行顺序
  const [queue, setQueue] = useState<number[]>([])
  const [search, setSearch] = useState('')
  const [runId, setRunId] = useState<number | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [results, setResults] = useState<RunResult[]>([])
  const [total, setTotal] = useState(0)
  const [busy, setBusy] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [runs, setRuns] = useState<RunListItem[]>([])
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [resultsData, setResultsData] = useState<Record<number, RunResults>>({})
  const [resultsLoading, setResultsLoading] = useState<Set<number>>(new Set())
  const timerRef = useRef<number | null>(null)
  const pollVersion = useRef(0)
  const pollMounted = useRef(true)
  const pollInFlight = useRef<number | null>(null)
  const pollRefreshAgain = useRef(false)
  const pollFailures = useRef(0)
  const [pollError, setPollError] = useState<string | null>(null)

  // ---------- 按钮坐标复用（v1.6 #26）----------
  // 复用性能测试里已标定的坐标，而不是在本模块再标一份：
  // 选中某个分组后，本方向的测试点**优先**按该组已标定的坐标点击（分组优先），
  // 分组里没有的按钮（振动/音效/重连等）仍走 uiautomator dump 定位。
  const [coordGroups, setCoordGroups] = useState<PerfButtonGroup[]>([])
  const [coordGroupId, setCoordGroupId] = useState<number | null>(() => {
    // 初值从 localStorage 恢复：刷新/切页回来不用重选。非法值一律当"未选"。
    const raw = window.localStorage.getItem(COORD_GROUP_STORAGE_KEY)
    const n = raw ? Number(raw) : NaN
    return Number.isFinite(n) && n > 0 ? n : null
  })
  // 每个 apptomg 测试点用哪个坐标：由服务端解析（含别名），前端只负责展示
  const [coordSources, setCoordSources] = useState<ApptomgCoordSource[]>([])

  const load = useCallback(async () => {
    try {
      setLoading(true)
      const list = await api.points('APPTOMG')
      setPoints(list)
      setQueue((prev) => (prev.length ? prev : list.map((p) => p.id)))
    } catch (e) {
      toast.error(`选点加载失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    pollMounted.current = true
    void load()
    return () => {
      pollMounted.current = false
      ++pollVersion.current
      if (timerRef.current) window.clearTimeout(timerRef.current)
    }
  }, [load])

  /** 坐标分组列表：直接拉性能测试模块的分组（同一份数据，不在本模块重复维护）。 */
  const loadCoordGroups = useCallback(async () => {
    try {
      const list = await perfApi.buttonGroups()
      setCoordGroups(list)
      // 选中的分组可能已在 perf 页被删除 → 自动回退为「不使用分组」，
      // 否则会带着一个失效 id 启动，后端查不到坐标、全量退回 dump，用户却看不出原因
      setCoordGroupId((cur) => (cur != null && !list.some((g) => g.id === cur) ? null : cur))
    } catch (e) {
      toast.error(`坐标分组加载失败：${(e as Error).message}`)
    }
  }, [])

  useEffect(() => {
    void loadCoordGroups()
  }, [loadCoordGroups])

  // 坐标来源映射：跟随选中分组变化。未选分组时也拉一次，
  // 用于渲染「全部走控件树定位」的说明，而不是留一片空白让用户猜。
  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const list = await api.apptomgCoordSources(coordGroupId ?? undefined)
        if (alive) setCoordSources(list)
      } catch {
        if (alive) setCoordSources([])
      }
    })()
    return () => {
      alive = false
    }
  }, [coordGroupId])

  // 记住选择：下次进页面自动带出
  useEffect(() => {
    if (coordGroupId == null) window.localStorage.removeItem(COORD_GROUP_STORAGE_KEY)
    else window.localStorage.setItem(COORD_GROUP_STORAGE_KEY, String(coordGroupId))
  }, [coordGroupId])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return points
    return points.filter(
      (p) =>
        (p.stateName || '').toLowerCase().includes(q) ||
        (p.description || '').toLowerCase().includes(q),
    )
  }, [points, search])

  // ---------- 坐标复用派生值 ----------
  // 只统计**本次队列里**的测试点：队列才是真正要跑的东西。
  // 用全量 11 个点统计会让用户误判覆盖情况（队列里可能只勾了 3 个）。
  const queueResourceKeys = useMemo(
    () => new Set(queue.map((id) => points.find((p) => p.id === id)?.resourceKey ?? '')),
    [queue, points],
  )
  const coordRelevant = useMemo(
    () => (queue.length === 0 ? coordSources : coordSources.filter((s) => queueResourceKeys.has(s.resourceKey))),
    [coordSources, queueResourceKeys, queue.length],
  )
  // 会按标定坐标点击的测试点
  const coordMatched = useMemo(() => coordRelevant.filter((s) => s.matched), [coordRelevant])
  // 仍要走控件树 dump 定位的测试点
  const coordFallback = useMemo(() => coordRelevant.filter((s) => !s.matched), [coordRelevant])
  const activeCoordGroup = useMemo(
    () => coordGroups.find((g) => g.id === coordGroupId) ?? null,
    [coordGroups, coordGroupId],
  )

  // ---------- 队列操作 ----------
  // 测试点池内已入队的 id 集合（用于池内按钮显示/全选去重）
  const inQueue = useMemo(() => new Set(queue), [queue])

  const addToQueue = (id: number) => setQueue((prev) => [...prev, id])

  const removeFromQueue = (idx: number) =>
    setQueue((prev) => prev.filter((_, i) => i !== idx))

  const moveUp = (idx: number) =>
    setQueue((prev) => {
      if (idx <= 0) return prev
      const next = [...prev]
      ;[next[idx - 1], next[idx]] = [next[idx], next[idx - 1]]
      return next
    })

  const moveDown = (idx: number) =>
    setQueue((prev) => {
      if (idx >= prev.length - 1) return prev
      const next = [...prev]
      ;[next[idx + 1], next[idx]] = [next[idx], next[idx + 1]]
      return next
    })

  const addAll = () =>
    setQueue((prev) => [...prev, ...points.filter((p) => !prev.includes(p.id)).map((p) => p.id)])

  const clearQueue = () => setQueue([])

  const pointName = (id: number) => {
    const p = points.find((x) => x.id === id)
    return p ? p.description || p.stateName || `#${id}` : `#${id}`
  }

  const poll = useCallback(async (id: number) => {
    if (!pollMounted.current) return
    const version = pollVersion.current
    if (pollInFlight.current === version) { pollRefreshAgain.current = true; return }
    pollInFlight.current = version
    pollRefreshAgain.current = false
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    let nextDelay: number | null = null
    try {
      const s = await api.runSummary(id)
      if (version !== pollVersion.current) return
      pollFailures.current = 0
      setPollError(null)
      setResults(s.results || [])
      setTotal(s.total)
      setStatus(s.status)
      if (s.status === 'RUNNING') {
        nextDelay = 1000
      } else {
        setBusy(false)
      }
    } catch (e) {
      if (version !== pollVersion.current) return
      const message = e instanceof Error ? e.message : String(e)
      setPollError(message)
      if (pollFailures.current++ === 0) toast.error(`进度查询失败，将自动重试：${message}`)
      nextDelay = Math.min(10000, 1000 * 2 ** Math.min(pollFailures.current, 4))
    } finally {
      if (version === pollVersion.current) {
        pollInFlight.current = null
        if (nextDelay != null) timerRef.current = window.setTimeout(() => void poll(id), pollRefreshAgain.current ? 0 : nextDelay)
      }
    }
  }, [])

  const start = async () => {
    if (!device) {
      toast.error('请先选择在线设备')
      return
    }
    if (queue.length === 0) {
      toast.error('请至少添加一个测试点')
      return
    }
    const version = ++pollVersion.current
    pollRefreshAgain.current = false
    pollFailures.current = 0
    setPollError(null)
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    setBusy(true)
    setStatus('RUNNING')
    setResults([])
    try {
      // 带上选中的坐标分组：后端会按「分组优先」用它已标定的坐标点击本方向的按钮
      const resp = await api.startRun('APPTOMG', device, queue, coordGroupId ?? undefined)
      if (!pollMounted.current || version !== pollVersion.current) return
      setRunId(resp.runId)
      await poll(resp.runId)
    } catch (e) {
      toast.error(`启动失败：${(e as Error).message}`)
      setBusy(false)
    }
  }

  const stop = async () => {
    if (!runId) return
    try {
      await api.stop(runId)
    } catch (e) {
      toast.error(`停止失败：${(e as Error).message}`)
    }
  }

  const loadHistory = async () => {
    try {
      const list = await api.runs()
      setRuns(list.filter((r) => r.direction === 'APPTOMG'))
      setResultsData({})
      setExpanded(new Set())
      setHistoryOpen(true)
    } catch (e) {
      toast.error(`加载历史失败：${(e as Error).message}`)
    }
  }

  const loadResults = async (runId: number) => {
    if (resultsData[runId] || resultsLoading.has(runId)) return
    setResultsLoading((prev) => new Set(prev).add(runId))
    try {
      const data = await api.runResults(runId)
      setResultsData((prev) => ({ ...prev, [runId]: data }))
    } catch (e) {
      toast.error(`结果加载失败：${(e as Error).message}`)
    } finally {
      setResultsLoading((prev) => {
        const next = new Set(prev)
        next.delete(runId)
        return next
      })
    }
  }

  const toggleExpand = (runId: number) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(runId)) next.delete(runId)
      else next.add(runId)
      return next
    })
    if (!resultsData[runId]) void loadResults(runId)
  }

  const running = status === 'RUNNING'
  const progressPct = total > 0 ? (results.length / total) * 100 : 0
  const currentName = running && results.length > 0 ? (results[results.length - 1].description || results[results.length - 1].stateName) : null

  return (
    <div className="space-y-4">
      {pollError && <div role="status" data-testid="functional-progress-error" className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-xs text-amber-700 dark:text-amber-400">进度查询失败，将自动重试：{pollError}。当前显示最后一次已知结果。</div>}
      <ConfigBar direction="APPTOMG" pointIds={queue} onLoad={setQueue} />

      {/* ---------- 按钮坐标（v1.6 #26）：复用性能测试里已标定的坐标 ---------- */}
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 space-y-0 pb-3">
          <CardTitle className="text-base">按钮坐标</CardTitle>
          <span className="text-xs text-muted-foreground">
            复用「性能测试」里已标定的坐标，同一批按钮不必两处各标一份
          </span>
        </CardHeader>
        <CardContent className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-muted-foreground">坐标来源</span>
            <Select
              value={coordGroupId != null ? String(coordGroupId) : NO_GROUP}
              onValueChange={(v) => setCoordGroupId(v === NO_GROUP ? null : Number(v))}
            >
              {/* 宽度放到 30rem：分组选项要完整显示「分组名（绑设备 <完整标识>）」，
                  原来的窄下拉会把机型截成省略号 */}
              <SelectTrigger className="h-8 w-[30rem] max-w-full">
                <SelectValue placeholder="不使用分组（全部走控件树定位）" />
              </SelectTrigger>
              <SelectContent className="w-auto max-w-[42rem]">
                <SelectItem value={NO_GROUP}>不使用分组（全部走控件树 dump 定位）</SelectItem>
                {coordGroups.map((g) => (
                  <SelectItem key={g.id} value={String(g.id)}>
                    {groupOptionLabel(g)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {coordGroups.length === 0 && (
              <span className="text-xs text-muted-foreground">
                还没有坐标分组，可到「性能测试」页新建并标定
              </span>
            )}
          </div>

          {/* 覆盖情况：把"哪几个点按标定坐标点、哪几个仍走 dump"写清楚，
              否则用户选完分组也不知道这步有没有生效 */}
          {coordGroupId != null && activeCoordGroup ? (
            <div className="space-y-1 text-xs">
              <div className="text-muted-foreground">
                分组「{activeCoordGroup.name}」已标定 {activeCoordGroup.calibrated ?? 0}/
                {activeCoordGroup.total ?? 0} 个按钮
                {activeCoordGroup.requiredTotal != null && (
                  <>（其中必标 {activeCoordGroup.requiredCalibrated ?? 0}/{activeCoordGroup.requiredTotal}）</>
                )}
                {queue.length > 0 ? `；当前队列 ${coordRelevant.length} 个测试点中` : '；本方向测试点中'}
                <span className="font-medium text-emerald-600 dark:text-emerald-400">
                  {' '}
                  {coordMatched.length} 个按标定坐标点击
                </span>
                {coordFallback.length > 0 && (
                  <>
                    ，<span className="font-medium text-amber-600 dark:text-amber-400">
                      {coordFallback.length} 个仍走控件树定位
                    </span>
                  </>
                )}
                。
              </div>
              {coordMatched.length > 0 && (
                <div className="text-muted-foreground">
                  按标定坐标：
                  {coordMatched
                    .map((s) =>
                      s.perfLabel && s.perfLabel !== s.label ? `${s.label}←${s.perfLabel}` : s.label,
                    )
                    .join('、')}
                </div>
              )}
              {coordFallback.length > 0 && (
                <div className="text-muted-foreground">
                  走控件树定位：{coordFallback.map((s) => s.label).join('、')}
                </div>
              )}
              {coordMatched.length === 0 && (
                <div className="text-destructive">
                  该分组没有覆盖当前队列里任何按钮，本次全部走控件树定位。
                  对局内的按钮（结束游戏、退出房间）无法 dump，请换一个已标定的分组，
                  或先到「性能测试」页把这些按钮标定好。
                </div>
              )}
            </div>
          ) : (
            <div className="text-xs text-muted-foreground">
              未使用分组：全部按钮走控件树（uiautomator dump）定位。对局内的按钮无法 dump，会定位失败。
            </div>
          )}
        </CardContent>
      </Card>

      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="outline" onClick={addAll}>
          <ListChecks className="mr-1 h-3.5 w-3.5" />
          全部添加
        </Button>
        <Button size="sm" variant="outline" onClick={clearQueue}>
          清空队列
        </Button>
        <Button size="sm" variant="outline" onClick={() => void loadHistory()}>
          <History className="mr-1 h-3.5 w-3.5" />
          历史
        </Button>
        <div className="relative ml-auto w-56">
          <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="搜索测试点名称 / state"
            className="pl-7"
          />
        </div>
        <span className="text-xs text-muted-foreground">
          队列 {queue.length} 个测试点
        </span>
      </div>

      <div className="max-h-80 overflow-auto rounded-xl border border-border/70 bg-card/60 shadow-sm">
        {loading ? (
          <div className="space-y-1 p-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 px-1 py-2">
                <Skeleton className="h-4 w-40" />
                <Skeleton className="ml-auto h-3 w-24" />
              </div>
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <div className="py-10 text-center text-sm text-muted-foreground">无匹配测试点</div>
        ) : (
          <ul className="divide-y divide-border/50">
            {filtered.map((p) => (
              <li key={p.id}>
                <div className="flex items-center gap-3 px-3.5 py-2.5 transition-colors hover:bg-muted/40">
                  <span className="min-w-0 flex-1 truncate text-sm font-medium">
                    {p.description || p.stateName}
                  </span>
                  <span className="font-mono text-xs text-muted-foreground">{p.stateName}</span>
                  {inQueue.has(p.id) && (
                    <Badge variant="secondary" className="font-mono shrink-0 text-xs font-semibold">
                      已添加 ×{queue.filter((x) => x === p.id).length}
                    </Badge>
                  )}
                  <Button size="sm" variant="outline" className="shrink-0 h-8 px-2.5 text-xs transition-all hover:border-primary/50" onClick={() => addToQueue(p.id)}>
                    <Plus className="mr-1 h-3.5 w-3.5" />
                    添加
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {queue.length > 0 && (
        <div className="rounded-xl border border-border/80 bg-card shadow-sm">
          <div className="flex items-center justify-between border-b border-border/60 px-3.5 py-2 text-xs font-medium text-muted-foreground">
            <span>执行队列（按顺序执行，可重复添加 / 调整顺序）</span>
            <span className="font-mono text-xs">{queue.length} 项</span>
          </div>
          <ul className="max-h-64 divide-y divide-border/50 overflow-auto">
            {queue.map((id, idx) => (
              <li key={`${id}-${idx}`} className="flex items-center gap-2 px-3.5 py-2 text-sm transition-colors hover:bg-muted/30">
                <span className="w-8 shrink-0 font-mono text-xs text-muted-foreground">#{idx + 1}</span>
                <span className="min-w-0 flex-1 truncate font-medium">{pointName(id)}</span>
                <div className="flex shrink-0 items-center gap-1">
                  <Button size="icon" variant="ghost" className="h-7 w-7 text-muted-foreground hover:text-foreground" disabled={idx === 0} onClick={() => moveUp(idx)}>
                    <ArrowUp className="h-3.5 w-3.5" />
                  </Button>
                  <Button size="icon" variant="ghost" className="h-7 w-7 text-muted-foreground hover:text-foreground" disabled={idx === queue.length - 1} onClick={() => moveDown(idx)}>
                    <ArrowDown className="h-3.5 w-3.5" />
                  </Button>
                  <Button size="icon" variant="ghost" className="h-7 w-7 text-destructive hover:bg-destructive/10 hover:text-destructive" onClick={() => removeFromQueue(idx)}>
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="flex items-center gap-3">
        <Button onClick={() => void start()} disabled={busy} className="shadow-sm">
          <Play className="mr-1 h-4 w-4" />
          开始全自动执行
        </Button>
        {running && (
          <Button variant="destructive" onClick={() => void stop()} className="shadow-sm">
            <Square className="mr-1 h-4 w-4" />
            停止
          </Button>
        )}
        {status && (
          <span className="text-sm text-muted-foreground">
            {running ? `执行中… 已完成 ${results.length}/${total}` : `结束（${status}）`}
          </span>
        )}
      </div>

      {running && total > 0 && (
        <div className="space-y-1.5 rounded-lg border bg-card p-3">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span className="truncate">
              {currentName ? (
              <>
                当前执行：<span className="font-medium text-foreground">{currentName}</span>
              </>
            ) : (
              '正在启动…'
            )}
            </span>
            <span className="ml-2 shrink-0 font-mono">
              {results.length}/{total}（{Math.round(progressPct)}%）
            </span>
          </div>
          <Progress value={progressPct} />
        </div>
      )}

      {results.length > 0 && !running && <ResultSummary results={results} />}

      {results.length > 0 && (
        <ResultTable results={results} currentIndex={running ? results.length - 1 : undefined} />
      )}

      {runId && status && status !== 'RUNNING' && (
        <div className="flex items-center gap-3 text-sm">
          <span className="text-muted-foreground">报告：</span>
          <a
            href={reportUrl(runId)}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
          >
            HTML <ExternalLink className="h-3 w-3" />
          </a>
          <a
            href={exportUrl(runId)}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
          >
            CSV <ExternalLink className="h-3 w-3" />
          </a>
        </div>
      )}

      {historyOpen && (
        <Dialog open={historyOpen} onOpenChange={setHistoryOpen}>
          <DialogContent className="sm:max-w-2xl">
            <DialogHeader>
              <DialogTitle>历史运行（APPTOMG）</DialogTitle>
            </DialogHeader>
            <div className="max-h-[65vh] space-y-2 overflow-auto pr-1">
              {runs.length === 0 ? (
                <div className="py-6 text-center text-sm text-muted-foreground">暂无运行记录</div>
              ) : (
                runs.map((r) => {
                  const isOpen = expanded.has(r.id)
                  const rd = resultsData[r.id]
                  const loading = resultsLoading.has(r.id)
                  return (
                    <div key={r.id} className="rounded-xl border p-3">
                      <div className="flex flex-wrap items-center gap-2 text-sm">
                        <span className="font-mono text-xs text-muted-foreground">#{r.id}</span>
                        <span className="text-xs text-muted-foreground">
                          {r.startTime ? r.startTime.replace('T', ' ').slice(0, 19) : ''}
                        </span>
                        <Badge variant="secondary" className="font-mono text-xs">{r.status}</Badge>
                        <span className="text-xs text-muted-foreground">
                          {r.selectedPointIds?.length ?? 0} 个测试点
                        </span>
                        <div className="ml-auto flex items-center gap-1">
                          <Button size="sm" variant="ghost" onClick={() => toggleExpand(r.id)}>
                            {isOpen ? (
                              <ChevronUp className="mr-1 h-3.5 w-3.5" />
                            ) : (
                              <ChevronDown className="mr-1 h-3.5 w-3.5" />
                            )}
                            {isOpen ? '收起' : '展开'}
                          </Button>
                          <a
                            href={reportUrl(r.id)}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline"
                          >
                            HTML <ExternalLink className="h-3 w-3" />
                          </a>
                          <a
                            href={exportUrl(r.id)}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline"
                          >
                            CSV <ExternalLink className="h-3 w-3" />
                          </a>
                        </div>
                      </div>
                      {isOpen && (
                        <div className="mt-2 space-y-2">
                          {loading && (
                            <div className="text-xs text-muted-foreground">结果加载中…</div>
                          )}
                          {rd && (
                            <>
                              <div className="flex items-center gap-2">
                                <Badge
                                  variant="outline"
                                  className="border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                                >
                                  PASS {rd.stats.PASS}
                                </Badge>
                                <Badge
                                  variant="outline"
                                  className="border-red-500/40 bg-red-500/10 text-red-600 dark:text-red-400"
                                >
                                  FAIL {rd.stats.FAIL}
                                </Badge>
                                <Badge
                                  variant="outline"
                                  className="border-slate-400/40 bg-slate-500/10 text-slate-500 dark:text-slate-400"
                                >
                                  SKIP {rd.stats.SKIP}
                                </Badge>
                                <span className="text-xs text-muted-foreground">
                                  共 {rd.stats.TOTAL} 项
                                </span>
                              </div>
                              {rd.results.length > 0 && (
                                <div className="max-h-56 overflow-auto rounded-lg border">
                                  <Table>
                                    <TableHeader>
                                      <TableRow>
                                        <TableHead>测试点</TableHead>
                                        <TableHead className="w-16">结果</TableHead>
                                        <TableHead className="w-20">耗时</TableHead>
                                      </TableRow>
                                    </TableHeader>
                                    <TableBody>
                                      {rd.results.map((item: HistoryResultItem) => (
                                        <TableRow key={item.id}>
                                          <TableCell className="max-w-[320px]">
                                            <span
                                              className="block truncate text-xs"
                                              title={item.pointName || item.stateName}
                                            >
                                              {item.pointName || item.stateName}
                                            </span>
                                            {item.note && (
                                              <span className="block truncate text-xs text-muted-foreground">
                                                {item.note}
                                              </span>
                                            )}
                                          </TableCell>
                                          <TableCell>
                                            <Badge
                                              variant="outline"
                                              className={
                                                item.result === 'PASS'
                                                  ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                                                  : item.result === 'FAIL'
                                                    ? 'border-red-500/40 bg-red-500/10 text-red-600 dark:text-red-400'
                                                    : 'border-slate-400/40 bg-slate-500/10 text-slate-500 dark:text-slate-400'
                                              }
                                            >
                                              {item.result}
                                            </Badge>
                                          </TableCell>
                                          <TableCell className="font-mono text-xs">
                                            {item.elapsedMs != null ? `${item.elapsedMs}ms` : '—'}
                                          </TableCell>
                                        </TableRow>
                                      ))}
                                    </TableBody>
                                  </Table>
                                </div>
                              )}
                            </>
                          )}
                        </div>
                      )}
                    </div>
                  )
                })
              )}
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  )
}
