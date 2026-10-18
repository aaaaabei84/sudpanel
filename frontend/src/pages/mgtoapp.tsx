import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import {
  Play,
  Square,
  ListChecks,
  Search,
  History,
  SkipForward,
  ArrowRight,
  Check,
  X,
  ExternalLink,
  ChevronDown,
  ChevronUp,
  Settings2,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
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
import { PointManageDialog } from '@/components/point-manage-dialog'
import { LogViewer, SchemaCompare, extractSchemaFields } from '@/components/log-highlight'
import { ResultSummary } from '@/components/result-table'
import { api, reportUrl, exportUrl } from '@/lib/api'
import { useDevices } from '@/lib/device-context'
import type { HistoryResultItem, RunListItem, RunResults, RunSummary, TestPoint, WalkCurrent } from '@/types'

const GROUPS: { key: string; label: string }[] = [
  { key: 'mgtoapp_game', label: '游戏态' },
  { key: 'mgtoapp_player', label: '玩家态' },
]

export function MgtoappPage() {
  const { selected: device } = useDevices()
  const [points, setPoints] = useState<TestPoint[]>([])
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [search, setSearch] = useState('')
  const [runId, setRunId] = useState<number | null>(null)
  const [current, setCurrent] = useState<WalkCurrent | null>(null)
  const [judgeOpen, setJudgeOpen] = useState(false)
  const [matched, setMatched] = useState('')
  const [note, setNote] = useState('')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [runs, setRuns] = useState<RunListItem[]>([])
  const [resultsData, setResultsData] = useState<Record<number, RunResults>>({})
  const [resultsLoading, setResultsLoading] = useState<Set<number>>(new Set())
  const [summary, setSummary] = useState<RunSummary | null>(null)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [busy, setBusy] = useState(false)
  /** 「测试点管理」弹窗（需求 #45）。原来它是侧边栏上一个独立页面。 */
  const [manageOpen, setManageOpen] = useState(false)
  const timerRef = useRef<number | null>(null)
  const pollVersion = useRef(0)
  const pollMounted = useRef(true)
  const pollInFlight = useRef<number | null>(null)
  const pollRefreshAgain = useRef(false)
  const pollFailures = useRef(0)
  const [pollError, setPollError] = useState<string | null>(null)
  // 上一次轮询看到的测试点 id。用来区分「轮询只是例行刷新」和「真的推进到了下一个点」——
  // 只有后者才该把判定面板收起（见 pollWalk 里的说明）。
  const shownPointIdRef = useRef<number | null>(null)

  const load = useCallback(async () => {
    try {
      const list = await api.points('MGTOAPP')
      setPoints(list)
      setSelectedIds((prev) => {
        if (prev.size) return prev
        return new Set(list.map((p) => p.id))
      })
    } catch (e) {
      toast.error(`选点加载失败：${(e as Error).message}`)
    }
  }, [])

  /**
   * 测试点管理弹窗落了改动后的回调（需求 #45）。
   *
   * 两件事都要做，少一件就出错：
   *
   * 1. **重新拉列表** —— 页面上这份 points 是弹窗打开前取的，不重载就是旧数据（改的名看不出来）；
   * 2. **清掉选中集合里的悬空 id** —— 删掉一个已勾选的测试点后，那个 id 会留在 selectedIds 里，
   *    于是「已选 N / M」对不上，start() 还会把一个已经不存在的 id 发给后端。
   *
   * 这里刻意不复用 load()：load() 在「一个都没选」时会自动全选，那是首次进入页面的行为；
   * 用户手动清空过之后再用它，会把清空动作悄悄撤销掉。
   */
  const reloadPointsAfterManage = useCallback(async () => {
    try {
      const list = await api.points('MGTOAPP')
      setPoints(list)
      const alive = new Set(list.map((p) => p.id))
      setSelectedIds((prev) => new Set([...prev].filter((id) => alive.has(id))))
    } catch (e) {
      toast.error(`选点刷新失败：${(e as Error).message}`)
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

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return points
    return points.filter(
      (p) =>
        (p.stateName || '').toLowerCase().includes(q) ||
        (p.description || '').toLowerCase().includes(q),
    )
  }, [points, search])

  const toggle = (id: number) =>
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const pollWalk = useCallback(async (id: number) => {
    if (!pollMounted.current) return
    const version = pollVersion.current
    if (pollInFlight.current === version) { pollRefreshAgain.current = true; return }
    pollInFlight.current = version
    pollRefreshAgain.current = false
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    let nextDelay: number | null = null
    try {
      const c = await api.runCurrent(id)
      if (version !== pollVersion.current) return
      pollFailures.current = 0
      setPollError(null)
      setCurrent(c)
      // 只在「确实换了一个测试点」或「走查结束」时才收起判定面板、清掉过滤结果。
      //
      // 原实现每次轮询都无条件 setJudgeOpen(false) + setMatched('')，而轮询每 2 秒一次 ——
      // 点「下一个」拿到人工判定用的过滤结果后，最多 2 秒就被下一次轮询擦掉，
      // 表现为「结果闪一下就不见了，根本来不及判定」（2026-10-03 用户反馈）。
      // 根因是「每 2 秒的例行刷新」和「刚取回结果、正等人工判定」这两件事共用了同一次状态重置。
      // 判定用的日志在后端一直存着（pendingLog），前端不该把它丢掉。
      const pointId = c.current?.id ?? null
      const switchedPoint = pointId !== shownPointIdRef.current
      shownPointIdRef.current = pointId
      if (c.status !== 'RUNNING' || switchedPoint) {
        setJudgeOpen(false)
        setMatched('')
      }
      if (c.status !== 'RUNNING') {
        setBusy(false)
        // 走查结束：停掉轮询定时器，并拉一次汇总（结果统计卡片）
        if (timerRef.current) {
          window.clearTimeout(timerRef.current)
          timerRef.current = null
        }
        try {
          const s = await api.runSummary(id)
          if (version === pollVersion.current) setSummary(s)
        } catch {
          if (version === pollVersion.current) setSummary(null)
        }
      } else {
        // 走查进行中：每 2 秒继续轮询一次（2026-09-28 v1.2 #5 修复）。
        // 原实现只在操作后调用一次 pollWalk、timerRef 从未赋值，cleanup 是死代码 ——
        // 结果后端的带外变化（掉线、自动判定、其它窗口操作）界面永远不刷新。
        nextDelay = 2000
      }
    } catch (e) {
      if (version !== pollVersion.current) return
      const message = e instanceof Error ? e.message : String(e)
      setPollError(message)
      if (pollFailures.current++ === 0) toast.error(`进度查询失败，将自动重试：${message}`)
      nextDelay = Math.min(10000, 2000 * 2 ** Math.min(pollFailures.current, 4))
    } finally {
      if (version === pollVersion.current) {
        pollInFlight.current = null
        if (nextDelay != null) timerRef.current = window.setTimeout(() => void pollWalk(id), pollRefreshAgain.current ? 0 : nextDelay)
      }
    }
  }, [])

  const start = async () => {
    if (!device) {
      toast.error('请先选择在线设备')
      return
    }
    if (selectedIds.size === 0) {
      toast.error('请至少勾选一个测试点')
      return
    }
    const version = ++pollVersion.current
    pollRefreshAgain.current = false
    pollFailures.current = 0
    setPollError(null)
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    setBusy(true)
    setJudgeOpen(false)
    setMatched('')
    setCurrent(null)
    // 新一轮走查：清掉上轮记住的点 id，保证第一次轮询一定被当成「换了点」、面板处于收起态
    shownPointIdRef.current = null
    try {
      const resp = await api.startRun('MGTOAPP', device, [...selectedIds])
      if (!pollMounted.current || version !== pollVersion.current) return
      setRunId(resp.runId)
      await pollWalk(resp.runId)
    } catch (e) {
      toast.error(`启动失败：${(e as Error).message}`)
      setBusy(false)
    }
  }

  const stop = async () => {
    if (!runId) return
    try {
      await api.stop(runId)
      await pollWalk(runId)
    } catch (e) {
      toast.error(`停止失败：${(e as Error).message}`)
    }
  }

  const trigger = async () => {
    if (!runId) return
    try {
      const resp = await api.trigger(runId)
      if (resp.autoJudged) {
        await pollWalk(runId)
      } else {
        setMatched(resp.matched || '（无命中日志）')
        setJudgeOpen(true)
      }
    } catch (e) {
      toast.error(`触发失败：${(e as Error).message}`)
    }
  }

  const skip = async () => {
    if (!runId) return
    try {
      await api.skip(runId)
      await pollWalk(runId)
    } catch (e) {
      toast.error(`跳过失败：${(e as Error).message}`)
    }
  }

  const judge = async (result: 'PASS' | 'FAIL') => {
    if (!runId) return
    try {
      await api.judge(runId, result, note)
      setNote('')
      await pollWalk(runId)
    } catch (e) {
      toast.error(`判定失败：${(e as Error).message}`)
    }
  }

  const loadHistory = async () => {
    try {
      const list = await api.runs()
      setRuns(list.filter((r) => r.direction === 'MGTOAPP'))
      setResultsData({})
      setHistoryOpen(true)
    } catch (e) {
      toast.error(`加载测试记录失败：${(e as Error).message}`)
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

  const applyHistory = (ids: number[]) => {
    setSelectedIds(ids.length ? new Set(ids) : new Set())
    setHistoryOpen(false)
  }

  const p = current?.current
  const walkDone = current && current.status !== 'RUNNING'

  return (
    <div className="space-y-4">
      {pollError && <div role="status" data-testid="functional-progress-error" className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-xs text-amber-700 dark:text-amber-400">进度查询失败，将自动重试：{pollError}。当前显示最后一次已知结果。</div>}
      <ConfigBar
        direction="MGTOAPP"
        pointIds={[...selectedIds]}
        onLoad={(ids) => setSelectedIds(new Set(ids))}
      />

      {/* ==================== 测试点管理入口（需求 #45） ==================== */}
      {/* 原来这是侧边栏上的独立页。走查时发现某个测试点的 state 或名字写错，得切页面再切回来，
          而改的往往就是手上这一个点 —— 收进这里就不出页面了。 */}
      <Card className="border-border/80 bg-card/80 shadow-sm">
        <CardContent className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
          <Button size="sm" variant="outline" onClick={() => setManageOpen(true)} className="h-8 transition-all hover:border-primary/50">
            <Settings2 className="mr-1.5 h-3.5 w-3.5 text-primary" />
            测试点管理
            <Badge variant="secondary" className="ml-2 font-mono text-xs font-semibold">
              共 {points.length}
            </Badge>
          </Button>
          <span className="text-xs text-muted-foreground">
            改 state、备注、测试点名称，或增删测试点 —— 不用离开这一页。
          </span>
        </CardContent>
      </Card>

      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="outline" onClick={() => setSelectedIds(new Set(points.map((x) => x.id)))}>
          <ListChecks className="mr-1 h-3.5 w-3.5" />
          全选
        </Button>
        <Button size="sm" variant="outline" onClick={() => setSelectedIds(new Set())}>
          清空
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
        <Button size="sm" variant="outline" onClick={() => void loadHistory()}>
          <History className="mr-1 h-3.5 w-3.5" />
          测试记录
        </Button>
        <span className="text-xs text-muted-foreground">
          已选 {selectedIds.size} / {points.length}
        </span>
      </div>

      <div className="max-h-72 space-y-4 overflow-auto rounded-xl border border-border/70 bg-card/60 p-3.5 shadow-sm">
        {GROUPS.map((g) => {
          const pts = filtered.filter((p) => p.group === g.key)
          if (pts.length === 0) return null
          return (
            <div key={g.key}>
              <div className="mb-1.5 text-xs font-semibold text-muted-foreground">{g.label}</div>
              <ul className="space-y-1">
                {pts.map((p) => (
                  <li key={p.id}>
                    <label className="flex cursor-pointer items-center gap-3 rounded-lg px-2.5 py-1.5 transition-colors hover:bg-muted/60">
                      <Checkbox
                        checked={selectedIds.has(p.id)}
                        onCheckedChange={() => toggle(p.id)}
                      />
                      <span className="min-w-0 flex-1 truncate text-sm font-medium">
                        {p.description || p.stateName}
                      </span>
                      <span className="font-mono text-xs text-muted-foreground">{p.stateName}</span>
                    </label>
                  </li>
                ))}
              </ul>
            </div>
          )
        })}
        {filtered.length === 0 && (
          <div className="py-8 text-center text-sm text-muted-foreground">无匹配测试点</div>
        )}
      </div>

      <div className="flex items-center gap-3">
        <Button onClick={() => void start()} disabled={busy}>
          <Play className="mr-1 h-4 w-4" />
          开始走查
        </Button>
        {busy && current?.status === 'RUNNING' && (
          <>
            <span className="text-sm text-muted-foreground">走查中…</span>
            <Button variant="destructive" size="sm" onClick={() => void stop()}>
              <Square className="mr-1 h-3.5 w-3.5" />
              停止
            </Button>
          </>
        )}
      </div>

      {p && current && current.status === 'RUNNING' && (
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-base">
              <span className="font-mono">{p.stateName}</span>
              {p.description && <span className="ml-2 font-normal text-muted-foreground">— {p.description}</span>}
            </CardTitle>
            <Badge variant="secondary" className="font-mono">
              {current.done + 1} / {current.total}
            </Badge>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center gap-3">
              <Progress
                value={current.total > 0 ? ((current.done + 1) / current.total) * 100 : 0}
                className="flex-1"
              />
              <span className="shrink-0 font-mono text-xs text-muted-foreground">
                {Math.round(((current.done + 1) / Math.max(current.total, 1)) * 100)}%
              </span>
            </div>
            <div className="text-sm text-muted-foreground">
              {p.autoPass ? '（该状态空 data，命中即自动通过）' : '（需人工判断）'}
            </div>
            {p.dataSchema && <SchemaCompare schema={p.dataSchema} text={matched} />}
            {p.prompt && (
              <div className="text-sm">
                <span className="font-medium text-muted-foreground">说明：</span>
                {p.prompt}
              </div>
            )}

            {!judgeOpen ? (
              <div className="flex items-center gap-2">
                <Button onClick={() => void trigger()}>
                  <ArrowRight className="mr-1 h-4 w-4" />
                  下一个
                </Button>
                <Button variant="outline" onClick={() => void skip()}>
                  <SkipForward className="mr-1 h-4 w-4" />
                  跳过
                </Button>
              </div>
            ) : (
              <div className="space-y-3 rounded-md border p-3">
                <LogViewer
                  text={matched}
                  stateName={p.stateName}
                  fields={extractSchemaFields(p.dataSchema)}
                />
                <div className="flex items-center gap-2">
                  <Button onClick={() => void judge('PASS')}>
                    <Check className="mr-1 h-4 w-4" />
                    通过
                  </Button>
                  <Button variant="destructive" onClick={() => void judge('FAIL')}>
                    <X className="mr-1 h-4 w-4" />
                    失败
                  </Button>
                  <Input
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    placeholder="备注（可选）"
                    className="ml-auto max-w-xs"
                  />
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {walkDone && current && (
        <Card>
          <CardContent className="space-y-3 py-4">
            <div className="flex items-center gap-3">
              <span className="text-sm text-muted-foreground">走查结束（{current.status}）</span>
              {runId && (
                <>
                  <a
                    href={reportUrl(runId)}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
                  >
                    HTML 报告 <ExternalLink className="h-3 w-3" />
                  </a>
                  <a
                    href={exportUrl(runId)}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
                  >
                    CSV <ExternalLink className="h-3 w-3" />
                  </a>
                </>
              )}
            </div>
            {summary && summary.results.length > 0 && <ResultSummary results={summary.results} />}
          </CardContent>
        </Card>
      )}

      {historyOpen && (
        <Dialog open={historyOpen} onOpenChange={setHistoryOpen}>
          <DialogContent className="sm:max-w-2xl">
            <DialogHeader>
              <DialogTitle>测试记录（MGTOAPP）</DialogTitle>
            </DialogHeader>
            <div className="max-h-[65vh] space-y-2 overflow-auto pr-1">
              {runs.length === 0 ? (
                <div className="py-6 text-center text-sm text-muted-foreground">暂无测试记录</div>
              ) : (
                runs.map((r) => {
                  const ids = r.selectedPointIds || []
                  const names = ids.map((id) => {
                    const pt = points.find((x) => x.id === id)
                    return pt ? pt.description || pt.stateName : `#${id}`
                  })
                  const isOpen = expanded.has(r.id)
                  const rd = resultsData[r.id]
                  const loading = resultsLoading.has(r.id)
                  const time = r.startTime ? r.startTime.replace('T', ' ').slice(0, 19) : ''
                  return (
                    <div key={r.id} className="rounded-xl border p-3">
                      <div className="flex items-center gap-2 text-sm">
                        <span className="font-mono text-xs text-muted-foreground">#{r.id}</span>
                        <span className="text-xs text-muted-foreground">{time}</span>
                        <Badge variant="secondary" className="font-mono text-xs">
                          {r.status}
                        </Badge>
                        <span className="text-xs text-muted-foreground">已选 {ids.length} 个</span>
                        <div className="ml-auto flex items-center gap-1">
                          <Button size="sm" variant="ghost" onClick={() => toggleExpand(r.id)}>
                            {isOpen ? (
                              <ChevronUp className="mr-1 h-3.5 w-3.5" />
                            ) : (
                              <ChevronDown className="mr-1 h-3.5 w-3.5" />
                            )}
                            {isOpen ? '收起' : '展开'}
                          </Button>
                          <Button size="sm" variant="outline" onClick={() => applyHistory(ids)}>
                            套用
                          </Button>
                        </div>
                      </div>
                      {isOpen && (
                        <div className="mt-2 space-y-2">
                          <div className="text-sm text-muted-foreground">
                            {names.join('、') || '（无）'}
                          </div>
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
                                        <TableHead className="w-20"></TableHead>
                                      </TableRow>
                                    </TableHeader>
                                    <TableBody>
                                      {rd.results.map((item: HistoryResultItem) => (
                                        <TableRow key={item.id}>
                                          <TableCell className="max-w-[280px]">
                                            <span className="block truncate text-xs" title={item.pointName || item.stateName}>
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
                                          <TableCell className="w-20 text-right">
                                            <a
                                              href={reportUrl(r.id)}
                                              target="_blank"
                                              rel="noreferrer"
                                              className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline"
                                            >
                                              HTML <ExternalLink className="h-3 w-3" />
                                            </a>
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

      {/* 测试点管理弹窗（需求 #45/#46）—— 与页面上其它弹窗同级，放在最后 */}
      <PointManageDialog
        open={manageOpen}
        onOpenChange={setManageOpen}
        onChanged={() => void reloadPointsAfterManage()}
      />
    </div>
  )
}
