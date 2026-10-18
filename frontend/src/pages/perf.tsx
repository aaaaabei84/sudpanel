import { PerfRunContext, PerfCoordinateProfile } from '@/components/perf/perf-run-context'
import { PerfQuality, PerfSampleSources } from '@/components/perf/perf-quality'
import { PerfHealthPolicy } from '@/components/perf/perf-health-policy'
import type { QualitySample } from '@/lib/perf-quality'
import { replacePanelSelection } from '@/lib/navigation'
import { PerfPreflight } from '@/components/perf/perf-preflight'
import { PerfLoopEvidence } from '@/components/perf/perf-loop-evidence'
import { PerfHistoryBrowser } from '@/components/perf/perf-history-browser'
import { PerfFrozenPlan } from '@/components/perf/perf-frozen-plan'
import { PerfRoundSource } from '@/components/perf/perf-round-source'
import { PerfSettlementCheck } from '@/components/perf/perf-settlement-check'
import { cleanupLabel, perfContinuation, type PerfEvidenceLoop, type PerfStartResult } from '@/lib/perf-continuation'
import type { RoundQueryContext } from '@/lib/apitest-workspace'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import {
  Activity,
  ChevronDown,
  ChevronUp,
  ExternalLink,
  ListChecks,
  ListOrdered,
  Play,
  Plus,
  Radar,
  Square,
  Tag,
  Trash2,
} from 'lucide-react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { apitestApi, perfApi, perfReportPdfUrl,
  perfReportUrl, perfExportUrl } from '@/lib/api'
import { groupOptionLabel } from '@/lib/button-group-label'
import { useDevices } from '@/lib/device-context'
import { cn } from '@/lib/utils'
import type {
  AiRoomConfig,
  ApiCase,
  PerfButtonGroup,
  PerfOptions,
  PerfCheck,
  PerfGame,
  PerfMode,
  PerfProgress,
  PerfRun,
  PerfSample,
  PerfVerdict,
  PerfGrowthCurves,
} from '@/types'

// 等待重连或环境恢复时，后端仍持有执行权，控制和历史操作须共用活动态定义。
function isActiveRunStatus(status?: string): boolean {
  return status === 'RUNNING' || status === 'WAIT_DEVICE' || status === 'WAIT_ENV'
}

const VERDICT_STYLE: Record<string, string> = {
  PASS: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
  FAIL: 'border-red-500/40 bg-red-500/10 text-red-600 dark:text-red-400',
  SUSPECT: 'border-orange-500/40 bg-orange-500/10 text-orange-600 dark:text-orange-400',
  N_A: 'border-slate-400/40 bg-slate-500/10 text-slate-500 dark:text-slate-400',
}

function VerdictBadge({ verdict }: { verdict?: string | null }) {
  if (!verdict) return <span className="text-xs text-muted-foreground">—</span>
  return (
    <Badge variant="outline" className={VERDICT_STYLE[verdict] ?? ''}>
      {verdict}
    </Badge>
  )
}

function fmtTime(iso?: string | null): string {
  // startTime 存的是 UTC Instant（带 Z），转本地时间并精确到分钟
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 模式的中文名（列表里给使用者看的）。
 *
 * 常量表放在这里而不是散在 JSX 里：模式名在下拉、列表、详情三处都要显示，
 * 各写一遍的话迟早有一处漏掉新加的模式（就是这次新加的 AI_ROOM）。
 */
const MODE_LABEL: Record<PerfMode, string> = {
  NORMAL: '重复开关游戏',
  EARLY_SETTLE: '异常时序',
  AI_ROOM: '游戏内性能监测',
}

/**
 * 内存六项分项（v1.10.1）：显示名 / 数据键 / 颜色。
 *
 * 六项之和 ≈ PSS 总量，也就是「PSS 涨了 50MB」这句话拆开之后的六种可能。
 * Native 用红色 —— 实测它是这类客户端的常见主因，要在六条线里第一眼跳出来。
 * 颜色与报告保持一致，免得同一份数据在两处看起来像两回事。
 */
const MEM_DIMS = [
  { key: 'java', name: 'Java 堆', color: '#0ea5e9' },
  { key: 'native', name: 'Native 堆', color: '#dc2626' },
  { key: 'graphics', name: '图形', color: '#8b5cf6' },
  { key: 'code', name: '代码与资源', color: '#f59e0b' },
  { key: 'other', name: '私有其它', color: '#10b981' },
  { key: 'system', name: '系统', color: '#64748b' },
] as const

function runName(r: PerfRun): string {
  const names = r.gameNames ?? []
  const label =
    names.length === 0
      ? ''
      : names.length <= 2
        ? names.join('、')
        : `${names.slice(0, 2).join('、')} 等${names.length}个`
  return `${fmtTime(r.startTime)} ${label}`.trim()
}

function mb(kb?: number | null): number | null {
  return kb == null ? null : Math.round((kb / 1024) * 10) / 10
}

/** 内存增长曲线配色：蓝阶由浅到深 = 局序先后（v1.9.1）。 */
const GROWTH_COLORS = ['#93c5fd', '#60a5fa', '#3b82f6', '#2563eb', '#1d4ed8', '#1e40af', '#1e3a8a', '#172554']

export function PerfPage({ onQueryRound, initialRunId, initialGameId }: { onQueryRound?: (context: RoundQueryContext) => void; initialRunId?: number; initialGameId?: number }) {
  const { selected: device } = useDevices()

  // ---------- 游戏列表 ----------
  const [games, setGames] = useState<PerfGame[]>([])
  const [gamesLoading, setGamesLoading] = useState(true)
  const [checked, setChecked] = useState<Set<number>>(new Set())
  const [scanning, setScanning] = useState(false)
  const [sortOpen, setSortOpen] = useState(false)
  const [sortOrder, setSortOrder] = useState<number[]>([])

  // ---------- 按钮坐标分组（v1.2 #4）----------
  // 分组绑定设备序列号；异常时序模式使用分组时须完成必标按钮标定。
  // 坐标卡收起/展开（v1.9.3）：默认收起为一行摘要，点「展开标定」铺开清单
  const [calibExpanded, setCalibExpanded] = useState(false)
  const [buttonGroups, setButtonGroups] = useState<PerfButtonGroup[]>([])
  const [activeGroupId, setActiveGroupId] = useState<number | null>(null)
  const [calibratingKey, setCalibratingKey] = useState<string | null>(null)
  // 新建分组弹窗（v1.6 #26：输入名称从标题栏挪进弹窗）
  const [groupDialogOpen, setGroupDialogOpen] = useState(false)
  const [newGroupName, setNewGroupName] = useState('')
  const [creatingGroup, setCreatingGroup] = useState(false)

  // ---------- 运行控制 ----------
  const [mode, setMode] = useState<PerfMode>('NORMAL')
  const [loopCount, setLoopCount] = useState('')
  const [sampleSeconds, setSampleSeconds] = useState('')
  const [earlyDelay, setEarlyDelay] = useState('')
  const [starting, setStarting] = useState(false), [startError, setStartError] = useState('')
  const [runId, setRunId] = useState<number | null>(null)
  const [progress, setProgress] = useState<PerfProgress | null>(null)
  const [progressError, setProgressError] = useState<string | null>(null)
  const timerRef = useRef<number | null>(null)

  // ---------- 人机对局（需求 #68） ----------
  /*
   * 三条状态：人机对局的配置（四条用例绑定 + 人机数量）、可选的用例清单、
   * 以及「正在编辑哪个游戏的推送身份」的弹窗草稿。
   *
   * 配置是**存在后端**的（perf_setting 表），不是页面状态 —— 换了浏览器、重开面板都还在，
   * 因为它们描述的是「这套环境怎么跑」，不是「这次会话看了什么」。
   */
  const [aiCfg, setAiCfg] = useState<AiRoomConfig>({})
  // 性能测试通用选项（v1.10.0 #84）：目前只有「采样前触发内存回收」一个开关
  const [perfOpts, setPerfOpts] = useState<PerfOptions>({})
  // 人机对局配置是否展开（v1.10.4 方案 A）：默认收起，只露一行「当前绑了哪几条用例」的摘要
  const [aiCfgOpen, setAiCfgOpen] = useState(false)
  const [apiCases, setApiCases] = useState<ApiCase[]>([])
  const [idEditGame, setIdEditGame] = useState<PerfGame | null>(null)
  const [idMgId, setIdMgId] = useState('')
  const [idAppId, setIdAppId] = useState('')
  const [idRoomId, setIdRoomId] = useState('')
  const [idHumanCount, setIdHumanCount] = useState('')
  const [idAiCount, setIdAiCount] = useState('')
  const [idAiLevel, setIdAiLevel] = useState('')
  const [idGameSeconds, setIdGameSeconds] = useState('')
  const [idMachineUid, setIdMachineUid] = useState('')
  const [idBodyOverride, setIdBodyOverride] = useState('')

  /**
   * 名单预览（需求 #69/#70）：任一数量填了且合法时，实时给出将推送的名单一句话。
   * 与后端 AiPlayers 的规则同一套：前 humans 条真人（is_ai=0），其余人机（is_ai=1），
   * 真人数没配按 1 兜底；人机等级配了会写进每个人机条目（ai_level）。
   */
  const idRosterPreview = useMemo(() => {
    const h = idHumanCount.trim() === '' ? null : Number(idHumanCount.trim())
    const a = idAiCount.trim() === '' ? null : Number(idAiCount.trim())
    const lvl = idAiLevel.trim() === '' ? null : Number(idAiLevel.trim())
    if (h == null && a == null) return null
    const humans = h ?? 1
    const ais = a ?? 0
    if (!Number.isInteger(humans) || !Number.isInteger(ais) || humans < 0 || ais < 0
      || humans + ais < 1 || humans + ais > 64) return null
    const pad = (n: number) => String(n).padStart(2, '0')
    const parts: string[] = []
    if (humans > 0) {
      const first = idMachineUid.trim() !== '' ? idMachineUid.trim() : 'cy01'
      parts.push(humans === 1
        ? `${first}（真人 is_ai=0）`
        : `${first}+cy02～cy${pad(humans)}（真人 is_ai=0 × ${humans}）`)
    }
    if (ais > 0) {
      parts.push(`cy${pad(humans + 1)}～cy${pad(humans + ais)}（人机 is_ai=1 × ${ais}`
        + (lvl != null ? `，ai_level=${lvl}` : '') + '）')
    }
    return `将推送 ${humans + ais} 人：` + parts.join(' + ')
  }, [idHumanCount, idAiCount, idAiLevel, idMachineUid])

  // ---------- 结果分析 ----------
  const [runs, setRuns] = useState<PerfRun[]>([])
  const [selRun, setSelRun] = useState<number | null>(initialRunId ?? null)
  const [selGame, setSelGame] = useState<number | null>(initialGameId ?? null)
  const [loops, setLoops] = useState<PerfEvidenceLoop[]>([])
  const [checks, setChecks] = useState<PerfCheck[]>([])
  const [selLoop, setSelLoop] = useState<number | null>(null)
  const [qualityLoop, setQualityLoop] = useState<number | null>(null)
  const [evidenceLoop, setEvidenceLoop] = useState<number | null>(null)
  const [samples, setSamples] = useState<PerfSample[]>([])
  const [runsLoading, setRunsLoading] = useState(false), [runsError, setRunsError] = useState(''), [activeError, setActiveError] = useState('')
  const [gameDataLoading, setGameDataLoading] = useState(false), [gameDataError, setGameDataError] = useState('')
  const [cleanupFilter, setCleanupFilter] = useState(''), [filteredLoops, setFilteredLoops] = useState<PerfEvidenceLoop[] | null>(null), [cleanupLoading, setCleanupLoading] = useState(false), [cleanupError, setCleanupError] = useState('')
  const [planView, setPlanView] = useState<{ id: number; resume: boolean } | null>(null), [settlementLoop, setSettlementLoop] = useState<PerfEvidenceLoop | null>(null)
  const [lastStarted, setLastStarted] = useState<PerfStartResult | null>(null)
  const [selectedRunError, setSelectedRunError] = useState<{ id: number; message: string; missing: boolean } | null>(null), [selectedRunLoading, setSelectedRunLoading] = useState(false), [selectedRunRetry, setSelectedRunRetry] = useState(0)
  const selectedRunIdRef = useRef(selRun); selectedRunIdRef.current = selRun
  const runsRequest = useRef<AbortController | null>(null)

  // 内存增长曲线（v1.9.1）：各局局内 PSS 抽样叠加；随选中的 run/游戏加载
  const [growth, setGrowth] = useState<PerfGrowthCurves | null>(null)

  // ---------- 人工复核 ----------
  const [review, setReview] = useState<{ kind: 'check' | 'loop'; id: number } | null>(null)
  const [reviewVerdict, setReviewVerdict] = useState<PerfVerdict>('PASS')
  const [reviewNote, setReviewNote] = useState('')

  const loadGames = useCallback(async () => {
    try {
      setGamesLoading(true)
      setGames(await perfApi.games())
    } catch (e) {
      toast.error(`游戏列表加载失败：${(e as Error).message}`)
    } finally {
      setGamesLoading(false)
    }
  }, [])

  // ---------- 人机对局：配置与推送身份（需求 #68） ----------

  /** 拉配置与用例清单。失败都只提示、不阻塞页面 —— 不跑人机对局时这两样都不需要。 */
  /**
   * 把用例 id 换成用例名（v1.10.4 方案 A 的摘要行用）。
   *
   * 绑定为空 = 走「按名字自动找」，摘要里就如实说明，别让人以为已经绑死了。
   * 用例清单还没拉回来时退化成 #id，也不至于空着。
   */
  const caseName = (id?: string, fallback?: string) =>
    id ? (apiCases.find((c) => String(c.id) === id)?.name ?? `#${id}`) : `自动（按名找「${fallback ?? '默认名'}」）`

  const loadAiRoom = useCallback(async () => {
    try {
      setAiCfg(await perfApi.aiRoomConfig())
    } catch {
      /* 静默：配置读不到时页面仍可用，只是那几项显示为空 */
    }
    try {
      setApiCases(await apitestApi.cases())
    } catch {
      /* 同上：用例清单拉不到时绑定选择框为空，用户仍可手填 id */
    }
    try {
      setPerfOpts(await perfApi.perfOptions())
    } catch {
      /* 静默：通用选项读不到时按默认（全部关闭）处理 */
    }
  }, [])

  /**
   * 存性能测试通用选项（v1.10.0 #84）。改动即存，与配置弹窗同一约定。 */
  const savePerfOpts = useCallback(async (next: PerfOptions) => {
    setPerfOpts(next)
    try {
      await perfApi.savePerfOptions(next)
    } catch (e) {
      toast.error(`选项保存失败：${(e as Error).message}`)
    }
  }, [])

  /**
   * 存人机对局配置（改动即存，不留「改完忘了保存」这个坑）。 */
  const saveAiCfg = useCallback(async (next: AiRoomConfig) => {
    setAiCfg(next)
    try {
      await perfApi.saveAiRoomConfig(next)
    } catch (e) {
      toast.error(`配置保存失败：${(e as Error).message}`)
    }
  }, [])

  const openIdEdit = useCallback((g: PerfGame) => {
    setIdEditGame(g)
    setIdMgId(g.mgId ?? '')
    setIdAppId(g.appId ?? '')
    setIdRoomId(g.roomId ?? '')
    setIdHumanCount(g.humanCount != null ? String(g.humanCount) : '')
    setIdAiCount(g.aiCount != null ? String(g.aiCount) : '')
    setIdAiLevel(g.aiLevel != null ? String(g.aiLevel) : '')
    setIdGameSeconds(g.gameSeconds != null ? String(g.gameSeconds) : '')
    setIdMachineUid(g.machineUid ?? '')
    setIdBodyOverride(g.bodyOverride ?? '')
  }, [])

  const saveGameIds = useCallback(async () => {
    if (!idEditGame) return
    // 数值字段：空=没配（null）；填了必须落在各自合法区间。前端先拦一遍给好体验，
    // 后端还会再拦一次（绕过界面直接调接口的人归后端管）
    const num = (s: string, lo: number, hi: number): number | null | 'BAD' => {
      if (s.trim() === '') return null
      const v = Number(s.trim())
      if (!Number.isInteger(v) || v < lo || v > hi) return 'BAD'
      return v
    }
    const humans = num(idHumanCount, 0, 64)
    const ais = num(idAiCount, 0, 64)
    const level = num(idAiLevel, 0, 9999)
    const seconds = num(idGameSeconds, 15, 7200)
    if (humans === 'BAD' || ais === 'BAD' || level === 'BAD' || seconds === 'BAD') {
      toast.error('有数值不合法：真人/人机 0～64，等级 0～9999，时长 15～7200 秒')
      return
    }
    if (humans != null || ais != null) {
      const total = (humans ?? 1) + (ais ?? 0)
      if (total > 64) { toast.error('真人与人机合计不能超过 64 人'); return }
    }
    if (humans != null && ais != null && humans + ais === 0) {
      toast.error('真人数与人机数都是 0，没有可推的人；至少要推 1 个')
      return
    }
    try {
      await perfApi.updateGameIds(idEditGame.id, idMgId, idAppId, idRoomId,
        humans, ais, level, seconds, idMachineUid, idBodyOverride)
      toast.success(`已保存「${idEditGame.shortName || idEditGame.name}」的推送配置`)
      setIdEditGame(null)
      void loadGames()
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`)
    }
  }, [idEditGame, idMgId, idAppId, idRoomId, idHumanCount, idAiCount, idAiLevel,
    idGameSeconds, idMachineUid, idBodyOverride, loadGames])

  const loadRuns = useCallback(async () => {
    runsRequest.current?.abort(); const controller = new AbortController(); runsRequest.current = controller
    setRunsLoading(true)
    const results = await Promise.allSettled([perfContinuation.history({}, null, controller.signal), perfContinuation.active(controller.signal)])
    if (controller.signal.aborted) return
    const [recent, active] = results
    if (recent.status === 'fulfilled') {
      setRunsError('')
      setRuns(previous => {
        const selected = previous.find(run => run.id === selectedRunIdRef.current)
        return [...new Map([...(selected ? [selected] : []), ...recent.value.items, ...(active.status === 'fulfilled' ? active.value : [])].map(run => [run.id, run])).values()]
      })
    } else setRunsError(recent.reason instanceof Error ? recent.reason.message : '最近运行读取失败')
    if (active.status === 'fulfilled') {
      setActiveError('')
      setRuns(previous => [...new Map([...previous, ...active.value].map(run => [run.id, run])).values()])
      const task = active.value.find(run => isActiveRunStatus(run.status)); if (task) setRunId(current => current ?? task.id)
    } else setActiveError(active.reason instanceof Error ? active.reason.message : '活动任务读取失败')
    setRunsLoading(false)
  }, [])

  const selectedRunKnown = runs.some(run => run.id === selRun)
  useEffect(() => {
    const controller = new AbortController(); setSelectedRunError(null)
    if (selRun == null || selectedRunKnown) { setSelectedRunLoading(false); return () => controller.abort() }
    setSelectedRunLoading(true)
    void perfContinuation.run(selRun, controller.signal).then(run => {
      if (controller.signal.aborted) return
      if (!run) { setSelectedRunError({ id: selRun, message: '运行已删除或不存在', missing: true }); return }
      if (run.id !== selRun) throw new Error('所选运行返回内容无效')
      setRuns(previous => [...new Map([...previous, run].map(item => [item.id, item])).values()])
    }).catch(cause => {
      if (!controller.signal.aborted) {
        const message = cause instanceof Error ? cause.message : '所选运行读取失败'
        const missing = (cause as { status?: number })?.status === 404 || /不存在|已删除/.test(message)
        setSelectedRunError({ id: selRun, message: missing ? '运行已删除或不存在' : message, missing })
      }
    }).finally(() => { if (!controller.signal.aborted) setSelectedRunLoading(false) })
    return () => controller.abort()
  }, [selRun, selectedRunKnown, selectedRunRetry])

  useEffect(() => {
    void loadGames()
    void loadRuns()
    // 人机对局的配置与用例清单也在这里拉：它们是页面数据的一部分，
    // 单独再挂一个 effect 只会多一次渲染与一条同类的 lint 告警
    void loadAiRoom()
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current)
      runsRequest.current?.abort()
    }
  }, [loadGames, loadRuns, loadAiRoom])

  // 运行进度轮询（3s）：失败继续重试，慢请求不重叠，卸载后忽略迟到结果。
  useEffect(() => {
    setProgressError(null)
    if (runId == null) return
    setProgress(null)
    let disposed = false
    let querying = false
    let failed = false
    const tick = async () => {
      if (disposed || querying) return
      querying = true
      try {
        const p = await perfApi.progress(runId)
        if (disposed) return
        setProgress(p)
        setProgressError(null)
        failed = false
        // 等待设备或环境恢复仍是活动态；只有终态才停止轮询。
        if (!isActiveRunStatus(p.status)) {
          if (timerRef.current) window.clearInterval(timerRef.current)
          timerRef.current = null
          setRunId(null)
          void loadRuns()
        }
      } catch (e) {
        if (disposed) return
        const message = e instanceof Error ? e.message : String(e)
        setProgressError(message)
        // 同一失败周期只弹一次，页面提示持续保留到恢复。
        if (!failed) toast.error(`进度查询失败，将自动重试：${message}`)
        failed = true
      } finally {
        querying = false
      }
    }
    void tick()
    timerRef.current = window.setInterval(() => void tick(), 3000)
    return () => {
      disposed = true
      if (timerRef.current) window.clearInterval(timerRef.current)
      timerRef.current = null
    }
  }, [runId, loadRuns])

  // 分组相关操作：加载 / 新建 / 删除 / 组内逐按钮标定
  // 注：v1.5 #4 已删除旧的全屏一次性标定（perfApi.calibrate），统一走"分组内逐按钮标定"

  const loadButtonGroups = useCallback(async () => {
    try {
      const list = await perfApi.buttonGroups()
      setButtonGroups(list)
      // 自动选中第一个分组（通常是迁移生成的默认分组）；已选中且仍存在时保持不变
      setActiveGroupId((cur) =>
        cur != null && list.some((g) => g.id === cur) ? cur : (list[0]?.id ?? null),
      )
    } catch (e) {
      toast.error(`加载按钮分组失败：${(e as Error).message}`)
    }
  }, [])

  useEffect(() => {
    void loadButtonGroups()
  }, [loadButtonGroups])

  const activeGroup = useMemo(
    () => buttonGroups.find((g) => g.id === activeGroupId) ?? null,
    [buttonGroups, activeGroupId],
  )
  const groupItems = activeGroup?.items ?? activeGroup?.progress?.items ?? []
  // 必标按钮（能被自动定位的大厅按钮）：只有它们挡启动（v1.7 #31）。
  // 对局内按钮标不了也 dump 不到，硬性要求只会让用户白标一遍。
  const requiredItems = groupItems.filter((i) => i.required)
  const optionalItems = groupItems.filter((i) => !i.required)
  const requiredDone = requiredItems.filter((i) => i.calibrated).length
  const optionalDone = optionalItems.filter((i) => i.calibrated).length
  // 未标定必标按钮的中文名：既用于界面提示，也用于禁用「开始」的判定
  const groupMissing = requiredItems.filter((i) => !i.calibrated).map((i) => i.label)
  const groupBlocked = activeGroup != null && groupMissing.length > 0
  // 与后端 start 一致：NORMAL、AI_ROOM 走接口，只有异常时序需要坐标分组。
  const requiresCalibration = mode === 'EARLY_SETTLE'
  const calibrationBlocked = requiresCalibration && groupBlocked
  // 选标按钮未标定 → 不挡启动，但压测运行期会回落到本地固定坐标（不随设备走，换机可能点偏）
  const optionalMissing = optionalItems.filter((i) => !i.calibrated).map((i) => i.label)

  const createGroup = async () => {
    setCreatingGroup(true)
    try {
      // 名称留空时**不**在前端塞一个固定名，交给后端按「分组N」兜底——
      // 前端硬写「新分组」会造出多个同名分组，反而更难分辨。
      const g = await perfApi.createButtonGroup(newGroupName.trim(), device || undefined)
      setNewGroupName('')
      setGroupDialogOpen(false)
      await loadButtonGroups()
      setActiveGroupId(g.id)
      toast.success(`已新建分组「${g.name}」，异常时序使用前请标定必标按钮`)
    } catch (e) {
      toast.error(`新建分组失败：${(e as Error).message}`)
    } finally {
      setCreatingGroup(false)
    }
  }

  const removeGroup = async (id: number, name: string) => {
    if (!window.confirm(`确定删除分组「${name}」及其坐标？`)) return
    try {
      await perfApi.deleteButtonGroup(id)
      toast.success(`已删除分组「${name}」`)
      await loadButtonGroups()
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`)
    }
  }

  /** 只标定一个按钮。成功后刷新分组，该行变为「已标定」并打勾；找不到时给出中文提示。 */
  const calibrateOne = async (key: string, label: string) => {
    if (!device) {
      toast.error('请先选择在线设备')
      return
    }
    if (activeGroupId == null) return
    setCalibratingKey(key)
    try {
      const r = await perfApi.calibrateButton(activeGroupId, device, key)
      if (r.status === 'calibrated') {
        toast.success(`已标定：${label}`)
        await loadButtonGroups()
      } else if (r.status === 'assumed') {
        // 推定值不落库：它不是实测结果，说清楚它是什么、以及运行期实际用什么
        toast.warning(r.message ?? `「${label}」无法自动定位，已按同位关系推定（未保存）`)
      } else {
        toast.error(r.message ?? `当前界面未找到「${label}」，请让该按钮出现后再标定`)
      }
    } catch (e) {
      toast.error(`标定失败：${(e as Error).message}`)
    } finally {
      setCalibratingKey(null)
    }
  }

  const scan = async () => {
    if (!device) {
      toast.error('请先选择在线设备')
      return
    }
    setScanning(true)
    try {
      const list = await perfApi.scanGames(device)
      setGames(list)
      toast.success(`扫描完成，共 ${list.length} 个游戏`)
    } catch (e) {
      toast.error(`扫描失败：${(e as Error).message}`)
    } finally {
      setScanning(false)
    }
  }

  const toggleGame = (id: number) =>
    setChecked((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const removeChecked = async () => {
    if (checked.size === 0) return
    if (!window.confirm(`删除选中的 ${checked.size} 个游戏及其全部圈/采样/判定数据？`)) return
    try {
      await perfApi.deleteGames([...checked])
      toast.success(`已删除 ${checked.size} 个游戏`)
      setChecked(new Set())
      void loadGames()
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`)
    }
  }

  const toggleSort = (id: number) => {
    setSortOrder((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  const openSort = () => {
    setSortOrder([])
    setSortOpen(true)
  }

  const saveSort = async () => {
    try {
      await perfApi.reorderGames(sortOrder)
      toast.success('排序已保存')
      setSortOpen(false)
      void loadGames()
    } catch (e) {
      toast.error(`排序保存失败：${(e as Error).message}`)
    }
  }

  const sortPicked = useMemo(
    () =>
      sortOrder
        .map((id) => games.find((g) => g.id === id))
        .filter((g): g is PerfGame => !!g),
    [games, sortOrder],
  )
  const sortRest = useMemo(() => games.filter((g) => !sortOrder.includes(g.id)), [games, sortOrder])

  const moveGame = async (g: PerfGame, dir: 'up' | 'down') => {
    try {
      await perfApi.moveGame(g.id, dir)
      void loadGames()
    } catch (e) {
      toast.error(`排序失败：${(e as Error).message}`)
    }
  }

  const start = async () => {
    if (starting) return
    if (!device) {
      toast.error('请先选择在线设备')
      return
    }
    if (checked.size === 0) {
      toast.error('请至少勾选一个游戏')
      return
    }
    // 异常时序选了未就绪分组时提前提示；接口驱动模式不检查坐标。
    if (calibrationBlocked) {
      toast.error(`分组「${activeGroup?.name}」还有 ${groupMissing.length} 个按钮未标定：${groupMissing.join('、')}`)
      return
    }
    setStarting(true); setStartError('')
    try {
      const resp = await perfApi.start(
        device,
        mode,
        [...checked],
        loopCount ? Number(loopCount) : undefined,
        // 人机对局模式的对局时长只认游戏配置（#70/#71）：面板值不传，避免两个来源打架
        mode === 'AI_ROOM' ? undefined : (sampleSeconds ? Number(sampleSeconds) : undefined),
        earlyDelay ? Number(earlyDelay) : undefined,
        activeGroupId ?? undefined,
      )
      setLastStarted(resp as PerfStartResult)
      toast.success(`已启动 run #${resp.runId}（${resp.loopCount} 圈 × ${resp.games} 个游戏）`)
      setRunId(resp.runId)
      chooseHistory(resp.runId, null)
      void loadRuns()
    } catch (e) {
      setStartError(e instanceof Error ? e.message : '启动返回失败'); toast.error(`启动失败：${(e as Error).message}`); void loadRuns()
    } finally { setStarting(false) }
  }

  const stop = async () => {
    if (runId == null) return
    try {
      await perfApi.stop(runId)
      toast.success('已请求停止')
    } catch (e) {
      toast.error(`停止失败：${(e as Error).message}`)
    }
  }

  const RESUMABLE = ['INTERRUPTED', 'STOPPED', 'ERROR', 'ABORTED']

  const rejudgeRun = async () => {
    if (selRun == null) return
    try {
      const r = await perfApi.rejudge(selRun)
      toast.success(`重算完成：${r.games} 个游戏 ${r.checks} 条判定`)
      if (selRun != null && selGame != null) void loadGameData(selRun, selGame)
    } catch (e) {
      toast.error(`重算失败：${(e as Error).message}`)
    }
  }

  const resumed = (resp: PerfStartResult) => {
    toast.success(`已续跑 run #${resp.runId}（清理中断圈 ${resp.removedInterrupted ?? 0} 个）`)
    setRunId(resp.runId); chooseHistory(resp.runId, null); void loadRuns()
  }

  useEffect(() => {
    setSelRun(initialRunId ?? null); setSelGame(initialGameId ?? null)
  }, [initialRunId, initialGameId])
  const chooseHistory = (rid: number | null, gid: number | null) => {
    setSelRun(rid); setSelGame(gid)
    replacePanelSelection('perf', { runId: rid ?? undefined, gameId: gid ?? undefined })
  }

  // 选中 run 的游戏队列
  const currentRun = runs.find((r) => r.id === selRun) ?? null
  const validHistoryGame = currentRun != null && selGame != null && currentRun.gameQueue?.split(',').some(id => Number(id.trim()) === selGame)
  const runGames = useMemo(() => {
    if (!currentRun?.gameQueue) return []
    return currentRun.gameQueue
      .split(',')
      .map((s) => Number(s.trim()))
      .filter((n) => Number.isFinite(n))
      .map((id) => games.find((g) => g.id === id) ?? { id, name: `#${id}` })
  }, [currentRun, games])

  // 请求竞态防护（2026-09-28，v1.2 #7）：
  // 快速切换圈/游戏时，先发出的请求可能后返回并覆盖新选中的数据。
  // 用递增序号标记每次请求，回调里比对「是否仍是最新一次请求」，不是则丢弃结果。
  const gameDataSeq = useRef(0)
  const samplesSeq = useRef(0)
  const selectedGameRef = useRef<{ run: number; game: number } | null>(null)

  const loadGameData = useCallback(async (rid: number, gid: number) => {
    if (selectedGameRef.current?.run !== rid || selectedGameRef.current.game !== gid) return
    const seq = ++gameDataSeq.current
    setGameDataLoading(true); setGameDataError('')
    try {
      const [l, c] = await Promise.all([perfContinuation.loops(rid, gid), perfApi.checks(rid, gid)])
      if (seq !== gameDataSeq.current) return
      setLoops(l); setChecks(c)
    } catch (cause) {
      if (seq === gameDataSeq.current) setGameDataError(cause instanceof Error ? cause.message : '圈数据加载失败')
    } finally { if (seq === gameDataSeq.current) setGameDataLoading(false) }
  }, [])

  useEffect(() => {
    selectedGameRef.current = selRun != null && selGame != null && validHistoryGame ? { run: selRun, game: selGame } : null
    setLoops([])
    setChecks([])
    setCleanupFilter(''); setFilteredLoops(null); setCleanupError(''); setGameDataError('')
    setSelLoop(null)
    setSamples([])
    if (selRun != null && selGame != null && validHistoryGame) void loadGameData(selRun, selGame)
    return () => {
      selectedGameRef.current = null
      ++gameDataSeq.current
      ++samplesSeq.current
    }
  }, [selRun, selGame, loadGameData, validHistoryGame])

  useEffect(() => {
    const controller = new AbortController()
    setCleanupError('')
    if (!cleanupFilter || selRun == null || selGame == null || !validHistoryGame) { setFilteredLoops(null); setCleanupLoading(false); return () => controller.abort() }
    setCleanupLoading(true)
    void perfContinuation.loops(selRun, selGame, cleanupFilter, controller.signal).then(value => { if (!controller.signal.aborted) setFilteredLoops(value) }).catch(cause => { if (!controller.signal.aborted) setCleanupError(cause instanceof Error ? cause.message : '清理筛选失败') }).finally(() => { if (!controller.signal.aborted) setCleanupLoading(false) })
    return () => controller.abort()
  }, [selRun, selGame, cleanupFilter, validHistoryGame])
  const visibleLoops = cleanupFilter && filteredLoops != null ? filteredLoops : loops

  // 选中 run/游戏变化时加载内存增长曲线（浅→深 = 局序的叠加对比数据）
  useEffect(() => {
    setGrowth(null)
    if (selRun == null || selGame == null || !validHistoryGame) {
      return
    }
    let alive = true
    perfApi
      .growthCurves(selRun, selGame)
      .then((g) => {
        if (alive) setGrowth(g)
      })
      .catch(() => {
        if (alive) setGrowth(null)
      })
    return () => {
      alive = false
    }
  }, [selRun, selGame, validHistoryGame])

  const loadSamples = async (loopId: number) => {
    setSelLoop(loopId)
    setSamples([])
    // 圈切换同样防竞态（v1.2 #7）：只接受最后一次点击对应的响应
    const seq = ++samplesSeq.current
    try {
      const s = await perfApi.samples(loopId)
      if (seq !== samplesSeq.current) return
      setSamples(s)
    } catch (e) {
      if (seq !== samplesSeq.current) return
      toast.error(`采样加载失败：${(e as Error).message}`)
    }
  }

  const submitReview = async () => {
    if (!review) return
    try {
      if (review.kind === 'check') {
        await perfApi.reviewCheck(review.id, reviewVerdict, reviewNote)
      } else {
        await perfApi.reviewLoop(review.id, reviewVerdict, reviewNote)
      }
      toast.success('复核已保存')
      setReview(null)
      if (selRun != null && selGame != null) void loadGameData(selRun, selGame)
    } catch (e) {
      toast.error(`复核失败：${(e as Error).message}`)
    }
  }

  // ---------- 图表数据 ----------
  const okLoops = loops.filter((l) => l.status === 'OK')
  const trendData = okLoops.map((l) => {
    // CPU 用整机口径优先（v1.10.1 修正）：判定项 4 一直在用 cpuAvgSys ÷ 阈值 15%，
    // 而这张图原先画的是**单核值**、参考线还写死 60% —— 图与判定实际上是两套口径，
    // 表现就是「图上一片 100%+、判定却说 PASS」。老 run 无核数记录时才回退单核值。
    const cpuAvg = l.cpuAvgSys ?? l.cpuAvg
    const cpuPeak = l.cpuPeakSys ?? l.cpuPeak
    return {
      loop: l.loopNo,
      pssBaseline: mb(l.baselinePssKb),
      pssAvg: mb(l.pssAvgKb),
      pssPeak: mb(l.pssPeakKb),
      cpuAvg: cpuAvg != null ? Math.round(cpuAvg * 10) / 10 : null,
      cpuPeak: cpuPeak != null ? Math.round(cpuPeak * 10) / 10 : null,
      wsBaseline: l.baselineWsThreads ?? null,
      wsInGame: l.inGameWsThreads ?? null,
    }
  })

  const qualitySamples = samples as QualitySample[]
  const sampleTimeKnown = samples.length > 0 && qualitySamples.every(sample => typeof sample.quality?.offsetMs === 'number')
  const sampleData = qualitySamples.map((s) => ({
    seq: sampleTimeKnown ? Number((s.quality!.offsetMs! / 1000).toFixed(3)) : s.seq,
    pss: mb(s.pssKb),
    cpu: s.cpuPercent != null ? Math.round(s.cpuPercent * 10) / 10 : null,
    ws: s.wsThreadCount ?? null,
    netConn: s.netConn ?? null,
  }))

  // 增长曲线 pivot：行 = 秒，列 = 各局（recharts 多线需要同一个 data 数组）
  const growthData = useMemo(() => {
    if (!growth || growth.curves.length === 0) return []
    const maxT = Math.max(0, ...growth.curves.map((c) => c.points.length))
    const rows: Record<string, number | null>[] = []
    for (let i = 0; i < maxT; i++) {
      const row: Record<string, number | null> = { t: i + 1 }
      for (const c of growth.curves) {
        row[`第${c.loopNo}局`] = c.points[i]?.pss ?? null
      }
      rows.push(row)
    }
    return rows
  }, [growth])
  const growthNames = growth?.curves.map((c) => `第${c.loopNo}局`) ?? []

  /**
   * 内存六项 + 局内 CPU 的逐圈序列（v1.10.1）。
   *
   * 口径与判定、报告三处强制一致：**有回收后基线就用回收后序列**。
   * 三处口径不一致是最难查的一类问题 —— 图上看着没涨、判定说涨了。
   */
  const memTrendData = useMemo(() => {
    const ok = loops.filter((l) => l.status === 'OK')
    if (ok.length < 3) return []
    const useTrimmed = ok.every((l) => l.trimmedPssKb != null)
    // 这里**不做任何取整**：差值必须由原始值相减后再取整。
    // 前两版踩的坑：先把每个点取整再相减，面板得到「持续 +37」而报告是「+38」；
    // 改成保留一位小数后，显示的首值又出现 50 与 49 的差（49.46 先变 49.5 再进位）。
    // 全精度传递 + 展示时才取整，才能和报告逐字一致。图表 tooltip 里另做取整。
    const mb = (raw?: number | null, trimmed?: number | null) =>
      ((useTrimmed ? trimmed : raw) ?? 0) / 1024
    return ok.map((l) => ({
      loop: l.loopNo,
      java: mb(l.baselineJavaKb, l.trimmedJavaKb),
      native: mb(l.baselineNativeKb, l.trimmedNativeKb),
      graphics: mb(l.baselineGraphicsKb, l.trimmedGraphicsKb),
      code: mb(l.baselineCodeKb, l.trimmedCodeKb),
      other: mb(l.baselinePrivateOtherKb, l.trimmedPrivateOtherKb),
      system: mb(l.baselineSystemKb, l.trimmedSystemKb),
      // 整机口径优先（v1.5 #21）：老 run 没有 cpu_avg_sys，回退单核值
      cpuAvg: l.cpuAvgSys ?? l.cpuAvg ?? null,
      cpuPeak: l.cpuPeakSys ?? l.cpuPeak ?? null,
    }))
  }, [loops])

  /**
   * 六项各自的「持续增量」（v1.10.2）。
   *
   * 首圈可能包含首次加载引擎、配置和纹理等开销。
   * 因此以第二圈为基准展示后续变化，供使用者与完整曲线对照；这不直接等同于泄漏量。
   */
  const memDimSeries = useMemo(
    () =>
      MEM_DIMS.map((d) => {
        const vals = memTrendData.map((r) => r[d.key] as number)
        const baseIdx = vals.length > 1 ? 1 : 0
        const base = vals[baseIdx] ?? 0
        const last = vals[vals.length - 1] ?? 0
        return { ...d, base, last, delta: last - base }
      }),
    [memTrendData],
  )

  /** 冷启动圈号（就是第 1 圈）：图上画一条竖虚线标出来，免得看图的人把那一跳算成增长 */
  const coldStartLoop = memTrendData[0]?.loop

  // 已接管活动运行即保留停止入口，不依赖首次进度查询成功。
  const running = runId != null
  const lastSample = progress?.lastSample
  const isEarly = currentRun?.mode === 'EARLY_SETTLE'

  return (
    <div className="space-y-4">
      {/* ---------- 游戏列表 ---------- */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-base">游戏列表</CardTitle>
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">
              已选 {checked.size} / {games.length}
            </span>
            <Button
              size="sm"
              variant="outline"
              onClick={() => setChecked(new Set(games.map((g) => g.id)))}
            >
              <ListChecks className="mr-1 h-3.5 w-3.5" />
              全选
            </Button>
            <Button size="sm" variant="outline" onClick={() => setChecked(new Set())}>
              清空
            </Button>
            <Button
              size="sm"
              variant="destructive"
              disabled={checked.size === 0}
              onClick={() => void removeChecked()}
            >
              <Trash2 className="mr-1 h-3.5 w-3.5" />
              删除选中 ({checked.size})
            </Button>
            <Button size="sm" variant="outline" onClick={openSort}>
              <ListOrdered className="mr-1 h-3.5 w-3.5" />
              排序
            </Button>
            <Button size="sm" onClick={() => void scan()} disabled={scanning}>
              <Radar className="mr-1 h-3.5 w-3.5" />
              {scanning ? '扫描中…' : '扫描游戏'}
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {gamesLoading ? (
            <div className="grid max-h-72 grid-cols-1 gap-2 overflow-auto sm:grid-cols-2 lg:grid-cols-3">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-8 rounded-md" />
              ))}
            </div>
          ) : games.length === 0 ? (
            <div className="py-6 text-center text-sm text-muted-foreground">
              暂无游戏，先连接设备点「扫描游戏」
            </div>
          ) : (
            <div className="grid max-h-72 grid-cols-1 gap-1 overflow-auto sm:grid-cols-2 lg:grid-cols-3">
              {games.map((g, idx) => (
                <div
                  key={g.id}
                  className="group flex items-center gap-1 rounded-md px-2 py-1.5 hover:bg-muted/50"
                >
                  <Checkbox
                    className="shrink-0"
                    checked={checked.has(g.id)}
                    onCheckedChange={() => toggleGame(g.id)}
                  />
                  <span
                    className="min-w-0 flex-1 cursor-pointer truncate text-sm"
                    onClick={() => toggleGame(g.id)}
                    title={g.name}
                  >
                    {g.shortName || g.name}
                  </span>
                  <span className="font-mono text-xs text-muted-foreground">#{g.id}</span>
                  {/*
                    推送配置入口（需求 #68；#69/#70 扩成八项）。已配过的用主色高亮 ——
                    一眼能看出哪些游戏还缺配置，而不必逐个点开检查；没配的保持淡色，不抢眼。
                  */}
                  <Button
                    size="icon"
                    variant="ghost"
                    className={cn(
                      'h-6 w-6 shrink-0',
                      g.mgId || g.appId || g.roomId || g.humanCount != null
                        || g.aiCount != null || g.aiLevel != null || g.gameSeconds != null
                        || g.machineUid || g.bodyOverride
                        ? 'text-primary opacity-100'
                        : 'opacity-0 group-hover:opacity-100',
                    )}
                    onClick={() => openIdEdit(g)}
                    title={
                      g.mgId || g.appId || g.roomId || g.humanCount != null
                        || g.aiCount != null || g.aiLevel != null || g.gameSeconds != null
                        || g.machineUid || g.bodyOverride
                        ? `推送配置：mg=${g.mgId || '（全局）'} app=${g.appId || '（全局）'} 房=${g.roomId || '（全局）'}`
                          + ` 真人=${g.humanCount ?? '（默认1）'} 人机=${g.aiCount ?? '（退全局）'}`
                          + ` 等级=${g.aiLevel ?? '（用例值）'} 时长=${g.gameSeconds ? g.gameSeconds + 's' : '（启动值）'}`
                          + `${g.bodyOverride ? ' 模板=已配' : ''}`
                        : '设置推送配置（身份/房间号/真人数/人机数/等级/时长/模板）'
                    }
                    data-testid={`perf-game-ids-${g.id}`}
                  >
                    <Tag className="h-3.5 w-3.5" />
                  </Button>
                  <span className="flex shrink-0 items-center opacity-0 transition-opacity group-hover:opacity-100">
                    <Button
                      size="icon"
                      variant="ghost"
                      className="h-6 w-6"
                      disabled={idx === 0}
                      onClick={() => void moveGame(g, 'up')}
                      title="上移"
                    >
                      <ChevronUp className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      size="icon"
                      variant="ghost"
                      className="h-6 w-6"
                      disabled={idx === games.length - 1}
                      onClick={() => void moveGame(g, 'down')}
                      title="下移"
                    >
                      <ChevronDown className="h-3.5 w-3.5" />
                    </Button>
                  </span>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ---------- 按钮坐标（v1.2 #4；v1.9.3 收成紧凑卡片）----------
          默认只占一行：分组选择 + 就绪摘要 + 展开按钮；完整标定清单点「展开」才铺开。
          收起用 hidden（不是条件渲染）——DOM 保留，界面断言照常能查到表格与进度。 */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2 space-y-0 pb-2">
          <CardTitle className="text-base">按钮坐标</CardTitle>
          {/* 新建分组走弹窗（v1.6 #26）：点按钮后弹窗输入名称 */}
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              // 每次打开都清空上次残留的名称，避免「上次输了一半」被当成新分组的名字建出来
              setNewGroupName('')
              setGroupDialogOpen(true)
            }}
          >
            <Plus className="mr-1 h-3.5 w-3.5" />
            新建分组
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {buttonGroups.length === 0 ? (
            <div className="py-2 text-center text-sm text-muted-foreground">
              重复开关游戏、游戏内性能监测无需标定。异常时序可新建分组并标定必标按钮；换手机时新建对应分组即可复用坐标方案。
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm text-muted-foreground">当前分组</span>
                <Select
                  value={activeGroupId != null ? String(activeGroupId) : undefined}
                  onValueChange={(v) => setActiveGroupId(Number(v))}
                >
                  <SelectTrigger className="h-8 w-[22rem] max-w-full">
                    <SelectValue placeholder="选择分组" />
                  </SelectTrigger>
                  <SelectContent className="w-auto max-w-[42rem]">
                    {buttonGroups.map((g) => (
                      <SelectItem key={g.id} value={String(g.id)}>
                        {groupOptionLabel(g)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {/* 就绪摘要：不展开也能一眼看出「能不能启动」 */}
                {activeGroup && (
                  <span
                    className={cn(
                      'text-xs',
                      groupBlocked
                        ? 'text-destructive'
                        : 'text-emerald-600 dark:text-emerald-400',
                    )}
                  >
                    {groupBlocked
                      ? `未标定：${groupMissing.join('、')}`
                      : `已就绪 · 必标 ${requiredDone}/${requiredItems.length}${
                          optionalItems.length > 0
                            ? ` · 选标 ${optionalDone}/${optionalItems.length}`
                            : ''
                        }`}
                  </span>
                )}
                <div className="ml-auto flex items-center gap-1">
                  {activeGroup && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void removeGroup(activeGroup.id, activeGroup.name)}
                    >
                      <Trash2 className="mr-1 h-3.5 w-3.5" />
                      删除该分组
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => setCalibExpanded((v) => !v)}>
                    {calibExpanded ? '收起标定' : '展开标定'}
                    <ChevronDown
                      className={cn(
                        'ml-1 h-3.5 w-3.5 transition-transform',
                        calibExpanded && 'rotate-180',
                      )}
                    />
                  </Button>
                </div>
              </div>

              <div className={calibExpanded ? 'space-y-3' : 'hidden'}>
                {/* 需求指定的提示语 */}
                <div className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-400">
                  当需要的按钮出现时请点击标定按钮
                </div>

                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>按钮名称</TableHead>
                      <TableHead className="w-24">类型</TableHead>
                      <TableHead className="w-28">状态</TableHead>
                      <TableHead className="w-28">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {groupItems.map((it) => (
                      <TableRow key={it.key}>
                        <TableCell className="text-sm">{it.label}</TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {it.required ? '必标' : '选标'}
                        </TableCell>
                        <TableCell>
                          {it.calibrated ? (
                            <span className="text-sm font-medium text-emerald-600 dark:text-emerald-400">
                              ✅ 已标定
                            </span>
                          ) : (
                            <span className="text-sm text-muted-foreground">待标定</span>
                          )}
                        </TableCell>
                        <TableCell>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={calibratingKey === it.key}
                            onClick={() => void calibrateOne(it.key, it.label)}
                          >
                            {calibratingKey === it.key ? '标定中…' : '标定'}
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {/* 进度条只算必标按钮：那才是「能不能启动」的口径。
                    选标按钮是加分项，混进来会让进度条永远差一点、看起来像没做完。 */}
                <div className="flex items-center gap-3">
                  <Progress
                    value={requiredItems.length > 0 ? (requiredDone / requiredItems.length) * 100 : 0}
                    className="h-2 flex-1"
                  />
                  <span className="whitespace-nowrap text-xs text-muted-foreground">
                    必标 {requiredDone}/{requiredItems.length}
                    {optionalItems.length > 0 && ` · 选标 ${optionalDone}/${optionalItems.length}`}
                  </span>
                </div>
                {groupBlocked && (
                  <div className="text-xs text-destructive">
                    还有未标定按钮：{groupMissing.join('、')}（仅异常时序使用此分组前需要完成标定）
                  </div>
                )}
                {requiresCalibration && !groupBlocked && optionalMissing.length > 0 && (
                  <div className="text-xs text-amber-700 dark:text-amber-400">
                    未标定选标按钮：{optionalMissing.join('、')}。不影响启动；
                    压测运行期对这几个会改用本地配置文件里的固定坐标（那份坐标不随设备变化，
                    换设备跑之前建议在这里标定）。
                  </div>
                )}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      {/* ---------- 排序对话框 ---------- */}
      <Dialog open={sortOpen} onOpenChange={setSortOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>调整游戏顺序</DialogTitle>
          </DialogHeader>
          <div className="text-xs text-muted-foreground">
            按勾选顺序排序：先勾选的排最前（行首数字为排序位置）；未勾选的保持原有顺序排在后面。
          </div>
          <ScrollArea className="max-h-80">
            <div className="space-y-1 pr-3">
              {[...sortPicked, ...sortRest].map((g) => {
                const idx = sortOrder.indexOf(g.id)
                const isPicked = idx >= 0
                return (
                  <div
                    key={g.id}
                    className={`flex items-center gap-2 rounded-md border px-2 py-1.5 ${
                      isPicked ? 'border-primary/30 bg-primary/5' : 'opacity-70'
                    }`}
                  >
                    <Checkbox
                      checked={isPicked}
                      onCheckedChange={() => toggleSort(g.id)}
                    />
                    <span className="min-w-0 flex-1 truncate text-sm">
                      {g.shortName || g.name}
                    </span>
                    {isPicked ? (
                      <Badge className="font-mono">{idx + 1}</Badge>
                    ) : (
                      <span className="text-xs text-muted-foreground">未勾选</span>
                    )}
                  </div>
                )
              })}
            </div>
          </ScrollArea>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSortOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void saveSort()}>确认排序</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ---------- 推送身份对话框（需求 #68） ---------- */}
      {/*
        每个游戏一份 mg_id / app_id：人机对局模式推指令时把它们注入用例里的
        {{mg_id}} / {{app_id}}。留空则用接口测试变量表里的全局值（与从前一致）。
        刻意做成「游戏列表里点一下才打开」而不是在列表里铺两个输入框：
        三列网格里塞不下两个长数字输入框，而 75 个游戏都铺出来也没法看。
      */}
      <Dialog open={idEditGame != null} onOpenChange={(o) => !o && setIdEditGame(null)}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>推送配置 · {idEditGame?.shortName || idEditGame?.name}</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div className="text-xs text-muted-foreground">
              人机对局模式推指令时，身份三项替换用例里的
              <span className="font-mono text-foreground"> {'{{mg_id}}'} </span>/
              <span className="font-mono text-foreground"> {'{{app_id}}'} </span>/
              <span className="font-mono text-foreground"> {'{{room_id}}'} </span>。
              留空表示用接口测试里配的全局值。
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="game-mg-id">mg_id</Label>
                <Input
                  id="game-mg-id"
                  className="font-mono text-xs"
                  value={idMgId}
                  placeholder="留空 = 用全局 mg_id"
                  onChange={(e) => setIdMgId(e.target.value)}
                  data-testid="perf-game-mg-id"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="game-app-id">app_id</Label>
                <Input
                  id="game-app-id"
                  className="font-mono text-xs"
                  value={idAppId}
                  placeholder="留空 = 用全局 app_id"
                  onChange={(e) => setIdAppId(e.target.value)}
                  data-testid="perf-game-app-id"
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="game-room-id">房间号 room_id</Label>
              <Input
                id="game-room-id"
                className="font-mono text-xs"
                value={idRoomId}
                placeholder="留空 = 用全局 room_id；填真机房号，人机才会进对的那间"
                onChange={(e) => setIdRoomId(e.target.value)}
                data-testid="perf-game-room-id"
              />
              <div className="text-xs text-muted-foreground">
                人机只进这个房号 —— 变量表里写死的联调房号（如 3333）会让人机
                全进错房、开局报「房间或者玩家不存在」。
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="game-human-count">真人数量</Label>
                <Input
                  id="game-human-count"
                  className="font-mono text-xs"
                  value={idHumanCount}
                  inputMode="numeric"
                  placeholder="留空 = 1 个真人"
                  onChange={(e) => setIdHumanCount(e.target.value)}
                  data-testid="perf-game-human-count"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="game-machine-uid">本机账号 uid</Label>
                <Input
                  id="game-machine-uid"
                  className="font-mono text-xs"
                  value={idMachineUid}
                  placeholder="留空 = 真人位用 cy01；填了则真人首位用此身份"
                  onChange={(e) => setIdMachineUid(e.target.value)}
                  data-testid="perf-game-machine-uid"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="game-ai-count">人机数量</Label>
                <Input
                  id="game-ai-count"
                  className="font-mono text-xs"
                  value={idAiCount}
                  inputMode="numeric"
                  placeholder="留空 = 退全局，再退用例原文"
                  onChange={(e) => setIdAiCount(e.target.value)}
                  data-testid="perf-game-ai-count"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="game-ai-level">人机等级 ai_level</Label>
                <Input
                  id="game-ai-level"
                  className="font-mono text-xs"
                  value={idAiLevel}
                  inputMode="numeric"
                  placeholder="留空 = 沿用用例里的值"
                  onChange={(e) => setIdAiLevel(e.target.value)}
                  data-testid="perf-game-ai-level"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="game-seconds">对局时长（秒）</Label>
                <Input
                  id="game-seconds"
                  className="font-mono text-xs"
                  value={idGameSeconds}
                  inputMode="numeric"
                  placeholder="留空 = 用启动时配的值（如 180）"
                  onChange={(e) => setIdGameSeconds(e.target.value)}
                  data-testid="perf-game-seconds"
                />
              </div>
            </div>
            {idRosterPreview && (
              <div className="text-xs text-muted-foreground" data-testid="perf-game-roster-preview">
                {idRosterPreview}
              </div>
            )}
            <div className="space-y-1.5">
              <Label htmlFor="game-body-override">推送体模板（高级）</Label>
              <textarea
                id="game-body-override"
                className="flex min-h-[120px] w-full rounded-md border border-input bg-transparent px-3 py-2 font-mono text-xs shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                value={idBodyOverride}
                placeholder={'留空 = 用用例原文。\n写法与接口用例相同，支持 {{mg_id}} / {{app_id}} / {{room_id}} 等占位符；配了就整段替换推送用例的请求体。'}
                onChange={(e) => setIdBodyOverride(e.target.value)}
                data-testid="perf-game-body-override"
              />
              <div className="text-xs text-muted-foreground">
                配了模板后：填了人机数量就按数量重排模板里的名单；没填数量则只保证
                第 1 条是真人（is_ai=0），其余照你写的推。
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setIdEditGame(null)}>
              取消
            </Button>
            <Button onClick={() => void saveGameIds()} data-testid="perf-game-ids-save">
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ---------- 新建分组对话框（v1.6 #26） ---------- */}
      {/* 交互：点「新建分组」→ 弹窗输入名称 → 创建。名称留空时后端按「分组N」兜底，
         所以这里不把空名当错误，只提示会有默认名。 */}
      <Dialog open={groupDialogOpen} onOpenChange={setGroupDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>新建坐标分组</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="new-group-name">分组名称</Label>
              <Input
                id="new-group-name"
                value={newGroupName}
                autoFocus
                placeholder="例如：小米 15 / 测试机 A"
                onChange={(e) => setNewGroupName(e.target.value)}
                onKeyDown={(e) => {
                  // 回车即提交：弹窗只有一个输入项，符合直觉
                  if (e.key === 'Enter' && !creatingGroup) void createGroup()
                }}
              />
            </div>
            <div className="text-xs text-muted-foreground">
              留空则自动命名为「分组N」。新分组会绑定当前在线设备
              {device ? `（${device}）` : '（当前无在线设备，将建为通用分组）'}，
              异常时序使用此分组前需完成必标按钮标定；重复开关游戏、游戏内性能监测无需标定。
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setGroupDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void createGroup()} disabled={creatingGroup}>
              {creatingGroup ? '创建中…' : '创建'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ---------- 运行控制 ---------- */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">运行控制</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <Label className="text-xs">模式</Label>
              <Select value={mode} onValueChange={(v) => setMode(v as PerfMode)}>
                <SelectTrigger className="w-44" data-testid="perf-mode">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="NORMAL">重复开关游戏</SelectItem>
                  <SelectItem value="EARLY_SETTLE">异常时序（提前结算）</SelectItem>
                  <SelectItem value="AI_ROOM">游戏内性能监测</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label className="text-xs">圈数（默认配置值）</Label>
              <Input
                className="w-28"
                value={loopCount}
                onChange={(e) => setLoopCount(e.target.value)}
                placeholder="50"
                inputMode="numeric"
              />
            </div>
            {/* 人机对局模式下不显示：对局时长已跟随游戏配置（#70，游戏弹窗里的「对局时长」），
                面板上再放一个就是两个来源打架 —— 使用者点名删掉（#71）。
                其它模式它仍是「单圈采样秒数」，照旧。 */}
            {mode !== 'AI_ROOM' && (
              <div className="space-y-1">
                <Label className="text-xs">单圈采样秒数</Label>
                <Input
                  className="w-28"
                  value={sampleSeconds}
                  onChange={(e) => setSampleSeconds(e.target.value)}
                  placeholder="10"
                  inputMode="numeric"
                  data-testid="perf-sample-seconds"
                />
              </div>
            )}
            {mode === 'EARLY_SETTLE' && (
              <div className="space-y-1">
                <Label className="text-xs">提前点结算延迟 ms</Label>
                <Input
                  className="w-32"
                  value={earlyDelay}
                  onChange={(e) => setEarlyDelay(e.target.value)}
                  placeholder="700"
                  inputMode="numeric"
                />
              </div>
            )}
            {/* 回收后基线开关（v1.10.0 #84）：三个模式都适用，默认勾选。
                打开后每圈多花约 3 秒（发一次回收信号 + 再采一个点），
                换来的是判定能把「可回收缓存」与「真泄漏」分开报。 */}
            <label className="flex items-center gap-2 pb-1 text-xs text-muted-foreground">
              <input
                type="checkbox"
                className="h-4 w-4"
                checked={perfOpts.trimBaseline !== false}
                onChange={(e) => void savePerfOpts({ ...perfOpts, trimBaseline: e.target.checked })}
                data-testid="perf-trim-baseline"
              />
              采样前触发内存回收（区分缓存与真泄漏）
            </label>
            <PerfHealthPolicy />
            <PerfCoordinateProfile device={device ?? ''} groupId={activeGroupId ?? null} mode={mode} disabled={running || starting} />
            <PerfPreflight deviceSerial={device ?? ''} mode={mode} gameIds={Array.from(checked)} buttonGroupId={activeGroupId ?? undefined} disabled={running || starting} />
            {/* 仅异常时序检查分组标定，按钮和 start 共用同一启动条件。 */}
            <Button
              onClick={() => void start()}
              disabled={running || starting || calibrationBlocked}
              title={
                calibrationBlocked
                  ? `还有未标定按钮：${groupMissing.join('、')}`
                  : undefined
              }
            >
              <Play className="mr-1 h-4 w-4" />
              {starting ? '预检并启动中…' : '开始'}
            </Button>
            {running && (
              <Button variant="destructive" onClick={() => void stop()}>
                <Square className="mr-1 h-4 w-4" />
                停止
              </Button>
            )}
          </div>

          {startError && <p role="alert" className="text-xs text-destructive" data-testid="perf-start-error">启动失败：{startError}</p>}
          {lastStarted?.planState === 'FROZEN' && <p className="text-xs text-muted-foreground" data-testid="perf-start-frozen">运行 #{lastStarted.runId} 启动时已复用只读预检并冻结有效配置。<Button size="sm" variant="link" onClick={() => setPlanView({ id: lastStarted.runId, resume: false })}>查看本次配置</Button></p>}
          {activeError && <p role="alert" className="text-xs text-destructive" data-testid="perf-active-error">活动任务读取失败：{activeError}；已接管任务与停止入口保持。<Button size="sm" variant="ghost" onClick={() => void loadRuns()}>重试</Button></p>}
          {runId != null && progressError && (
            <p role="status" data-testid="perf-progress-error" className="text-xs text-destructive">
              进度查询失败，将自动重试：{progressError}
            </p>
          )}

          {/* ---------- 人机对局配置（需求 #68）---------- */}
          {mode === 'AI_ROOM' && (
            <div className="space-y-2 rounded-md border border-dashed px-3 py-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="text-xs font-medium">游戏内性能监测配置</div>
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-6 px-2 text-xs"
                  onClick={() => setAiCfgOpen((v) => !v)}
                  data-testid="perf-ai-cfg-toggle"
                >
                  {aiCfgOpen ? '收起' : '修改'}
                </Button>
              </div>
              {/* 收起态只露一行摘要（v1.10.4 方案 A）：这四条绑定配好之后长期不变，
                  为一次性决策付长期版面成本不值得；但摘要里要直接写出**当前绑的是哪条用例**，
                  不能只写「已绑定」—— 那样等于把「选了也不知道命中什么」的问题留在原地。 */}
              <div className="text-xs text-muted-foreground" data-testid="perf-ai-cfg-summary">
                指令绑定：推人机 {caseName(aiCfg.caseBatch, aiCfg.defaults?.batch)} ｜ 开局{' '}
                {caseName(aiCfg.caseStart, aiCfg.defaults?.start)} ｜ 结束{' '}
                {caseName(aiCfg.caseEnd, aiCfg.defaults?.end)} ｜ 清空{' '}
                {caseName(aiCfg.caseClear, aiCfg.defaults?.clear)}
              </div>
              {aiCfgOpen && (
                <>
              <div className="text-xs text-muted-foreground">
                这一模式由接口推送驱动：真机自己点进游戏、加入房间并准备，随后平台按顺序
                <span className="text-foreground">推人机 → 开局 →（对局时长到了）→ 结束游戏 → 清空房间</span>。
                四个环节各选一条接口测试用例；不选则按名字自动找。
              </div>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                <div className="space-y-1">
                  <Label className="text-xs">① 推人机</Label>
                  <Select
                    value={aiCfg.caseBatch ?? '__auto__'}
                    onValueChange={(v) =>
                      void saveAiCfg({ ...aiCfg, caseBatch: v === '__auto__' ? '' : v })
                    }
                  >
                    <SelectTrigger className="h-8" data-testid="perf-ai-case-batch">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__auto__">
                        自动（找「{aiCfg.defaults?.batch ?? '批量加入'}」）
                      </SelectItem>
                      {apiCases.map((c) => (
                        <SelectItem key={c.id} value={String(c.id)}>
                          {c.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <Label className="text-xs">② 开局</Label>
                  <Select
                    value={aiCfg.caseStart ?? '__auto__'}
                    onValueChange={(v) =>
                      void saveAiCfg({ ...aiCfg, caseStart: v === '__auto__' ? '' : v })
                    }
                  >
                    <SelectTrigger className="h-8" data-testid="perf-ai-case-start">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__auto__">
                        自动（找「{aiCfg.defaults?.start ?? '游戏开始'}」）
                      </SelectItem>
                      {apiCases.map((c) => (
                        <SelectItem key={c.id} value={String(c.id)}>
                          {c.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <Label className="text-xs">③ 结束游戏</Label>
                  <Select
                    value={aiCfg.caseEnd ?? '__auto__'}
                    onValueChange={(v) =>
                      void saveAiCfg({ ...aiCfg, caseEnd: v === '__auto__' ? '' : v })
                    }
                  >
                    <SelectTrigger className="h-8" data-testid="perf-ai-case-end">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__auto__">
                        自动（找「{aiCfg.defaults?.end ?? '游戏结束'}」）
                      </SelectItem>
                      {apiCases.map((c) => (
                        <SelectItem key={c.id} value={String(c.id)}>
                          {c.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <Label className="text-xs">④ 清空房间</Label>
                  <Select
                    value={aiCfg.caseClear ?? '__auto__'}
                    onValueChange={(v) =>
                      void saveAiCfg({ ...aiCfg, caseClear: v === '__auto__' ? '' : v })
                    }
                  >
                    <SelectTrigger className="h-8" data-testid="perf-ai-case-clear">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__auto__">
                        自动（找「{aiCfg.defaults?.clear ?? '房间清理'}」）
                      </SelectItem>
                      {apiCases.map((c) => (
                        <SelectItem key={c.id} value={String(c.id)}>
                          {c.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <div className="flex flex-wrap items-end gap-3">
                <div className="space-y-1">
                  <Label className="text-xs">人机数量</Label>
                  <Input
                    className="h-8 w-24"
                    value={aiCfg.aiCount ?? ''}
                    onChange={(e) => void saveAiCfg({ ...aiCfg, aiCount: e.target.value })}
                    placeholder="8"
                    inputMode="numeric"
                    data-testid="perf-ai-count"
                  />
                </div>
                <div className="pb-1 text-xs text-muted-foreground">
                  留空 = 用①那条用例里写的名单；填了则按这个数量重排（is_ai 一律置 1）
                </div>
              </div>
              <div className="flex flex-wrap items-end gap-3">
                <div className="space-y-1">
                  <Label className="text-xs">对局结束关键字</Label>
                  <Input
                    className="h-8 w-72"
                    value={aiCfg.endKeyword ?? ''}
                    onChange={(e) => void saveAiCfg({ ...aiCfg, endKeyword: e.target.value })}
                    placeholder="留空 = 不做提前结束检测"
                    data-testid="perf-ai-end-keyword"
                  />
                </div>
                <div className="pb-1 text-xs text-muted-foreground">
                  采样期间按它扫上报日志，命中即视为本局已结束 → 提前收尾，并在圈记录上标「提前结束」
                </div>
              </div>
              <div className="text-xs text-muted-foreground">
                每个游戏的 <span className="font-mono text-foreground">mg_id</span> /
                <span className="font-mono text-foreground"> app_id</span> 在下方游戏列表里逐条设置
                （点那一行的 <Tag className="inline h-3 w-3" /> 图标）。
              </div>
                </>
              )}
            </div>
          )}

          {progress && runId != null && (
            <div className="space-y-2 rounded-md border px-3 py-2">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
                <Badge variant={running ? 'default' : 'secondary'} className="font-mono">
                  {progress.status}
                </Badge>
                {running && (
                  <>
                    <span className="text-muted-foreground">
                      游戏 {Math.min((progress.currentGameIdx ?? 0) + 1, progress.totalGames ?? 0)}/
                      {progress.totalGames}
                      {progress.currentGame ? `（${progress.currentGame}）` : ''}
                    </span>
                    <span className="text-muted-foreground">第 {progress.currentLoop} 圈</span>
                    {lastSample && (
                      <span className="font-mono text-xs text-muted-foreground">
                        PSS {mb(lastSample.pssKb) ?? '—'}MB · CPU {lastSample.cpuPercent ?? '—'}% ·
                        ws {lastSample.wsThreadCount ?? '—'}
                      </span>
                    )}
                  </>
                )}
                {progress.message && (
                  <span className="truncate text-xs text-muted-foreground">{progress.message}</span>
                )}
              </div>
              {running && (progress.totalGames ?? 0) > 0 && (
                <Progress
                  value={((progress.currentGameIdx ?? 0) + 1) / (progress.totalGames ?? 1) * 100}
                />
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ---------- 结果分析 ---------- */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-base">结果分析</CardTitle>
          <div className="flex flex-wrap items-center gap-2">
            <Select
              value={selRun != null ? String(selRun) : ''}
              onValueChange={(v) => {
                chooseHistory(Number(v), null)
              }}
            >
              <SelectTrigger className="w-56" data-testid="perf-run-select">
                <SelectValue placeholder="选择运行" />
              </SelectTrigger>
              {/* 长列表使用固定锚点和有界滚动，避免设备/进度刷新重置逐项对齐的高度与位置。 */}
              <SelectContent position="popper" align="start" className="max-h-[min(24rem,var(--radix-select-content-available-height))] max-w-[calc(100vw-1rem)]">
                {runs.map((r) => (
                  <SelectItem key={r.id} value={String(r.id)} className="min-w-0 whitespace-normal break-words">
                    {runName(r)} · {MODE_LABEL[r.mode] ?? r.mode} · {r.status}
                    {/* 人机对局额外标出这一轮推了几个人机：事后复核时
                        「用例里写了 8 个」与「这轮实际推了 4 个」是两件事 */}
                    {r.mode === 'AI_ROOM' && r.aiCount != null && ` · ${r.aiCount} 人机`}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button size="sm" variant="outline" disabled={runsLoading} onClick={() => void loadRuns()}>
              {runsLoading ? '读取中…' : '刷新'}
            </Button>
            <PerfHistoryBrowser games={games} onSelect={run => { setRuns(previous => [...new Map([...previous, run].map(item => [item.id, item])).values()]); chooseHistory(run.id, null) }} />
            {selRun != null && currentRun != null && <PerfRunContext key={selRun} runId={selRun} gameId={selGame} />}
            {selRun != null && currentRun != null && <Button size="sm" variant="outline" onClick={() => setPlanView({ id: selRun, resume: false })} data-testid="perf-plan-open">冻结配置</Button>}
            {selRun != null && currentRun != null && RESUMABLE.includes(currentRun.status) && runId == null && (
              <Button size="sm" variant="outline" onClick={() => selRun != null && setPlanView({ id: selRun, resume: true })}>
                <Play className="mr-1 h-3.5 w-3.5" />
                继续跑
              </Button>
            )}
            {selRun != null && currentRun != null && !isActiveRunStatus(currentRun.status) && (
              <Button size="sm" variant="outline" onClick={() => void rejudgeRun()} title="对历史 run 重新跑机器初判（ABORTED/STOPPED 也能出结论）">
                <Activity className="mr-1 h-3.5 w-3.5" />
                重算判定
              </Button>
            )}
            {selRun != null && currentRun != null && !isActiveRunStatus(currentRun.status) && (
              <Button
                size="sm"
                variant="outline"
                className="text-destructive hover:text-destructive"
                onClick={() => {
                  if (!window.confirm(`删除运行「${runName(currentRun)}」及其全部数据？`)) return
                  void perfApi
                    .deleteRun(selRun)
                    .then(() => {
                      toast.success('已删除')
                      setSelRun(null)
                      setSelGame(null)
                      void loadRuns()
                    })
                    .catch((e: Error) => toast.error(e.message))
                }}
              >
                <Trash2 className="mr-1 h-3.5 w-3.5" />
                删除
              </Button>
            )}
            {selRun != null && selGame != null && (
              <>
                <a
                  href={perfReportUrl(selRun, selGame)}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
                >
                  HTML 报告 <ExternalLink className="h-3 w-3" />
                </a>
                <a
                  href={perfReportPdfUrl(selRun, selGame)}
                  className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
                  title="导出 PDF（转发给别人最稳妥的格式）"
                  data-testid="perf-report-pdf"
                >
                  导出 PDF <ExternalLink className="h-3 w-3" />
                </a>
                <a
                  href={perfExportUrl(selRun, selGame)}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
                >
                  CSV <ExternalLink className="h-3 w-3" />
                </a>
              </>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          {runsError && <p role="alert" className="text-xs text-destructive" data-testid="perf-runs-error">运行记录读取失败：{runsError}；已选择与已加载的结果保持。<Button size="sm" variant="ghost" onClick={() => void loadRuns()}>重试</Button></p>}
          {currentRun == null ? (
            <div className="py-6 text-center text-sm text-muted-foreground">
              {selectedRunLoading ? `正在读取运行 #${selRun}…` : selectedRunError?.id === selRun ? <span role="alert" data-testid="perf-selected-run-error">运行 #{selRun}：{selectedRunError.message}。{selectedRunError.missing ? '已停止自动重试，请选择其他运行。' : <Button size="sm" variant="ghost" onClick={() => setSelectedRunRetry(value => value + 1)}>重试读取所选运行</Button>}</span> : '选择一个运行查看结果'}
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2">
                {runGames.map((g) => (
                  <Button
                    key={g.id}
                    size="sm"
                    variant={selGame === g.id ? 'default' : 'outline'}
                    onClick={() => chooseHistory(selRun, g.id)}
                  >
                    {g.shortName || g.name}
                  </Button>
                ))}
                {runGames.length === 0 && (
                  <span className="text-sm text-muted-foreground">该运行无游戏队列</span>
                )}
              </div>

              {selRun != null && <div className="flex flex-wrap items-center gap-3 rounded-md border bg-muted/20 p-2 text-xs"><a href={perfContinuation.samplesUrl(selRun, selGame)} className="text-primary underline underline-offset-4" data-testid="perf-samples-download">{selGame == null ? '全运行原始样本 CSV' : '所选游戏原始样本 CSV'}</a><a href={perfContinuation.zipUrl(selRun, selGame)} className="text-primary underline underline-offset-4" data-testid="perf-zip-download">汇总、样本与元信息 ZIP</a><span className="text-muted-foreground">样本缺值留空；分享包不含实际报文、鉴权、坐标或设备标识。</span></div>}
              {selGame != null && !validHistoryGame && <p role="alert" className="text-xs text-destructive">游戏 #{selGame} 不在本次运行保存的队列中，请选择本次运行的游戏。</p>}
              {selGame != null && validHistoryGame && (
                <>
                  {gameDataLoading && <p role="status" className="text-xs text-muted-foreground">读取圈数据，保留当前已加载结果…</p>}
                  {gameDataError && <p role="alert" className="text-xs text-destructive" data-testid="perf-game-data-error">圈数据读取失败：{gameDataError}<Button size="sm" variant="ghost" onClick={() => selRun != null && void loadGameData(selRun, selGame)}>重试</Button></p>}
                  {/* 跨圈趋势（曲线区：三图并排） */}
                  {!isEarly && trendData.length > 0 && (
                    <div className="grid gap-3 lg:grid-cols-3">
                      <div className="h-44 rounded-2xl border bg-card p-3 shadow-sm">
                        <div className="mb-1 text-xs font-semibold text-muted-foreground">
                          PSS 跨圈趋势（MB）
                        </div>
                        <ResponsiveContainer width="100%" height="82%">
                          <AreaChart data={trendData}>
                            <defs>
                              <linearGradient id="gPssB" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.3} />
                                <stop offset="100%" stopColor="#3b82f6" stopOpacity={0} />
                              </linearGradient>
                              <linearGradient id="gPssP" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="0%" stopColor="#8b5cf6" stopOpacity={0.3} />
                                <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0} />
                              </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" opacity={0.25} vertical={false} />
                            <XAxis dataKey="loop" fontSize={11} tickLine={false} axisLine={false} />
                            <YAxis fontSize={11} width={42} tickLine={false} axisLine={false} />
                            <Tooltip />
                            <Area
                              type="monotone"
                              dataKey="pssBaseline"
                              name="基线"
                              stroke="#3b82f6"
                              strokeWidth={2}
                              fill="url(#gPssB)"
                              dot={{ r: 1.5 }}
                              connectNulls={false}
                            />
                            <Area
                              type="monotone"
                              dataKey="pssPeak"
                              name="峰值"
                              stroke="#8b5cf6"
                              strokeWidth={2}
                              fill="url(#gPssP)"
                              dot={{ r: 1.5 }}
                              connectNulls={false}
                            />
                          </AreaChart>
                        </ResponsiveContainer>
                      </div>
                      <div className="h-44 rounded-2xl border bg-card p-3 shadow-sm">
                        <div className="mb-1 text-xs font-semibold text-muted-foreground">
                          CPU 跨圈趋势（%）
                        </div>
                        <ResponsiveContainer width="100%" height="82%">
                          <AreaChart data={trendData}>
                            <defs>
                              <linearGradient id="gCpu" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.3} />
                                <stop offset="100%" stopColor="#3b82f6" stopOpacity={0} />
                              </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" opacity={0.25} vertical={false} />
                            <XAxis dataKey="loop" fontSize={11} tickLine={false} axisLine={false} />
                            <YAxis fontSize={11} width={36} tickLine={false} axisLine={false} />
                            <Tooltip />
                            {/* 阈值取自后端配置（v1.10.1 修正）：原先写死 60%（单核口径的旧阈值），
                                与判定项 4 实际使用的整机阈值 15% 差了三倍 */}
                            <ReferenceLine
                              y={perfOpts.cpuThreshold ?? 15}
                              stroke="#ef4444"
                              strokeDasharray="4 3"
                              strokeWidth={1.5}
                              label={{
                                value: `阈值${perfOpts.cpuThreshold ?? 15}%`,
                                fontSize: 10,
                                fill: '#ef4444',
                                position: 'insideTopRight',
                              }}
                            />
                            <Area
                              type="monotone"
                              dataKey="cpuAvg"
                              name="均值"
                              stroke="#3b82f6"
                              strokeWidth={2}
                              fill="url(#gCpu)"
                              dot={{ r: 1.5 }}
                              connectNulls={false}
                            />
                            <Area
                              type="monotone"
                              dataKey="cpuPeak"
                              name="峰值"
                              stroke="#ef4444"
                              strokeWidth={1.5}
                              fill="transparent"
                              dot={{ r: 1.5 }}
                              connectNulls={false}
                            />
                          </AreaChart>
                        </ResponsiveContainer>
                      </div>
                      <div className="h-44 rounded-2xl border bg-card p-3 shadow-sm">
                        <div className="mb-1 text-xs font-semibold text-muted-foreground">
                          ws 线程跨圈趋势
                        </div>
                        <ResponsiveContainer width="100%" height="82%">
                          <AreaChart data={trendData}>
                            <defs>
                              <linearGradient id="gWsG" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="0%" stopColor="#8b5cf6" stopOpacity={0.3} />
                                <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0} />
                              </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" opacity={0.25} vertical={false} />
                            <XAxis dataKey="loop" fontSize={11} tickLine={false} axisLine={false} />
                            <YAxis fontSize={11} width={30} allowDecimals={false} tickLine={false} axisLine={false} />
                            <Tooltip />
                            <Legend wrapperStyle={{ fontSize: 11 }} />
                            <Area
                              type="monotone"
                              dataKey="wsInGame"
                              name="局内"
                              stroke="#8b5cf6"
                              strokeWidth={2}
                              fill="url(#gWsG)"
                              dot={{ r: 1.5 }}
                              connectNulls={false}
                            />
                            <Area
                              type="monotone"
                              dataKey="wsBaseline"
                              name="关后基线"
                              stroke="#3b82f6"
                              strokeWidth={1.5}
                              fill="transparent"
                              dot={{ r: 1.5 }}
                              connectNulls={false}
                            />
                          </AreaChart>
                        </ResponsiveContainer>
                      </div>
                    </div>
                  )}

                  {/* 统计卡 + 判定摘要（信息区） */}
                  {loops.length > 0 && (
                    <>
                      {(() => {
                        const ok = loops.filter((l) => l.status === 'OK')
                        const f = ok[0]
                        const last = ok[ok.length - 1]
                        const memGrowth =
                          f?.baselinePssKb != null && last?.baselinePssKb != null
                            ? (((last.baselinePssKb - f.baselinePssKb) / f.baselinePssKb) * 100).toFixed(1)
                            : '—'
                        const cpuArr = ok.map((l) => l.cpuAvg).filter((v): v is number => v != null)
                        const cpuAvgAll = cpuArr.length
                          ? (cpuArr.reduce((a, b) => a + b, 0) / cpuArr.length).toFixed(1)
                          : '—'
                        const wsArr = ok
                          .map((l) => l.inGameWsThreads)
                          .filter((v): v is number => v != null)
                        const wsMin = wsArr.length ? Math.min(...wsArr) : null
                        const wsMax = wsArr.length ? Math.max(...wsArr) : null
                        const v1 = checks.find((c) => c.checkNo === 1)?.verdict
                        const v3 = checks.find((c) => c.checkNo === 3)?.verdict
                        const v4 = checks.find((c) => c.checkNo === 4)?.verdict
                        const statCard = (
                          title: string,
                          value: string,
                          sub: string,
                          verdict?: string,
                        ) => (
                          <div className="rounded-2xl border bg-card p-4 shadow-sm transition-shadow hover:shadow-md">
                            <div className="text-xs text-muted-foreground">{title}</div>
                            <div className="mt-1 text-2xl font-bold tracking-tight">{value}</div>
                            <div className="mt-2 flex items-center gap-2">
                              <VerdictBadge verdict={verdict} />
                              <span className="text-xs text-muted-foreground">{sub}</span>
                            </div>
                          </div>
                        )
                        return (
                          <>
                            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                              {statCard('内存增幅', `${memGrowth}%`, '首末圈基线 PSS', v1)}
                              {statCard('CPU 均值', `${cpuAvgAll}%`, `阈值 ${perfOpts.cpuThreshold ?? 15}%`, v4)}
                              {statCard(
                                'ws 线程',
                                wsMin != null ? `${wsMin}–${wsMax}` : '—',
                                '局内区间',
                                v3,
                              )}
                              {statCard(
                                '有效圈',
                                `${ok.length}/${loops.length}`,
                                `${loops.length - ok.length} 圈无效`,
                                undefined,
                              )}
                            </div>
                            {checks.length > 0 && (
                              <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-2xl border bg-card px-4 py-3 shadow-sm">
                                <span className="text-xs font-semibold text-muted-foreground">机器初判</span>
                                {checks.map((c) => (
                                  <span key={c.id} className="flex items-center gap-1.5 text-xs">
                                    <span className="text-muted-foreground">
                                      项{c.checkNo} {c.checkName}
                                    </span>
                                    <VerdictBadge verdict={c.verdict} />
                                  </span>
                                ))}
                                <span className="text-xs text-muted-foreground">人工复核后生效</span>
                              </div>
                            )}
                          </>
                        )
                      })()}
                    </>
                  )}

                  {/* 每圈统计摘要 */}
                  {loops.length > 0 && (
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-xs font-semibold text-muted-foreground">
                        圈统计：
                      </span>
                      {isEarly ? (
                        <>
                          <Badge variant="outline" className="border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                            PASS {loops.filter((l) => l.verdict === 'PASS').length}
                          </Badge>
                          <Badge variant="outline" className="border-orange-500/40 bg-orange-500/10 text-orange-600 dark:text-orange-400">
                            SUSPECT {loops.filter((l) => l.verdict === 'SUSPECT').length}
                          </Badge>
                          <Badge variant="outline" className="border-red-500/40 bg-red-500/10 text-red-600 dark:text-red-400">
                            FAIL {loops.filter((l) => l.verdict === 'FAIL').length}
                          </Badge>
                          <span className="text-xs text-muted-foreground">
                            共 {loops.length} 圈 · 人工待复核 {loops.filter((l) => !l.humanVerdict).length}
                          </span>
                        </>
                      ) : (
                        <>
                          <Badge variant="secondary">OK {loops.filter((l) => l.status === 'OK').length}</Badge>
                          <Badge variant="outline" className="border-red-500/40 bg-red-500/10 text-red-600 dark:text-red-400">
                            INVALID {loops.filter((l) => l.status === 'INVALID').length}
                          </Badge>
                          <span className="text-xs text-muted-foreground">共 {loops.length} 圈</span>
                        </>
                      )}
                    </div>
                  )}

                  {/* 聚合判定（机器初判 / 人工复核） */}
                  {checks.length > 0 && (
                    <div>
                      <div className="mb-1 flex items-center gap-2 text-xs font-semibold text-muted-foreground">
                        <Activity className="h-3.5 w-3.5" />
                        聚合判定（机器初判 / 人工复核）
                      </div>
                      <div className="overflow-auto rounded-xl border">
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead className="w-14">项</TableHead>
                              <TableHead>检测项</TableHead>
                              <TableHead className="w-24">初判</TableHead>
                              <TableHead>依据</TableHead>
                              <TableHead className="w-24">人工</TableHead>
                              <TableHead className="w-20"></TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {checks.map((c) => (
                              <TableRow key={c.id}>
                                <TableCell className="font-mono">{c.checkNo}</TableCell>
                                <TableCell>{c.checkName}</TableCell>
                                <TableCell>
                                  <VerdictBadge verdict={c.verdict} />
                                </TableCell>
                                <TableCell className="max-w-md">
                                  <span className="line-clamp-2 text-xs text-muted-foreground">
                                    {c.evidence}
                                  </span>
                                </TableCell>
                                <TableCell>
                                  <VerdictBadge verdict={c.humanVerdict} />
                                </TableCell>
                                <TableCell>
                                  <Button
                                    size="sm"
                                    variant="ghost"
                                    onClick={() => {
                                      setReview({ kind: 'check', id: c.id })
                                      setReviewVerdict((c.humanVerdict as PerfVerdict) || 'PASS')
                                      setReviewNote(c.humanNote || '')
                                    }}
                                  >
                                    复核
                                  </Button>
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    </div>
                  )}

                  {/* 每圈表 */}
                  <div>
                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs"><span className="font-semibold">每圈数据（点击行看单圈曲线）</span><label className="flex items-center gap-2">清理结果<select value={cleanupFilter} onChange={event => { setCleanupFilter(event.target.value); setSelLoop(null); setSamples([]) }} className="h-8 rounded-lg border border-border/80 bg-background/70 backdrop-blur-md px-2.5 text-xs text-foreground shadow-xs transition-all hover:border-primary/50 hover:bg-background/90 focus:border-primary/60 focus:bg-background/95 focus:ring-1 focus:ring-primary/30 outline-none" aria-label="圈清理筛选"><option value="">全部圈</option><option value="SUCCESS">结束与清房通过</option><option value="FAILED">清房未全部成功</option><option value="NOT_RECORDED">清房未记录</option></select></label></div>
                    {cleanupFilter && <p className="mb-2 text-xs text-muted-foreground">清理筛选只影响下方每圈表格；图表与聚合判定保留完整运行口径。</p>}
                    {cleanupLoading && <p role="status" className="mb-1 text-xs">查询清理结果，保留上次圈列表…</p>}
                    {cleanupError && <p role="alert" className="mb-1 text-xs text-destructive">{cleanupError}；保留上次成功的圈列表。<Button size="sm" variant="ghost" onClick={() => setCleanupFilter('')}>清除筛选</Button></p>}
                    <div className="max-h-72 overflow-auto rounded-md border">
                      <Table>
                        <TableHeader>
                          <TableRow>
                            <TableHead className="w-14">圈</TableHead>
                            <TableHead className="w-20">状态</TableHead>
                            {isEarly ? (
                              <>
                                <TableHead className="w-20">判定</TableHead>
                                <TableHead>无效原因 / 摘要</TableHead>
                              </>
                            ) : (
                              <>
                                <TableHead className="w-28" data-testid="perf-round-id-col">
                              对局 ID
                            </TableHead>
                            <TableHead className="w-24">基线 PSS</TableHead>
                                <TableHead className="w-24">PSS 均值</TableHead>
                                <TableHead className="w-24">PSS 峰值</TableHead>
                                <TableHead className="w-20">CPU 均值</TableHead>
                                <TableHead className="w-20">CPU 峰值</TableHead>
                                <TableHead className="w-24">ws 基线/局内</TableHead>
                                  </>
                            )}
                            <TableHead className="w-36">清房／证据</TableHead>
                            <TableHead className="w-20">人工</TableHead>
                            <TableHead className="w-36">操作</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {visibleLoops.map((l) => (
                            <TableRow
                              key={l.id}
                              className={selLoop === l.id ? 'bg-muted/60' : 'cursor-pointer'}
                              onClick={() => void loadSamples(l.id)}
                            >
                              <TableCell className="font-mono">{l.loopNo}</TableCell>
                              <TableCell>
                                <div className="flex items-center gap-1">
                                  <Badge
                                    variant={l.status === 'OK' ? 'secondary' : 'outline'}
                                    className={
                                      l.status === 'INVALID'
                                        ? 'border-red-500/40 text-red-600 dark:text-red-400'
                                        : ''
                                    }
                                  >
                                    {l.status}
                                  </Badge>
                                  {/* 对局提前结束标记（v1.9.4 #78）：这一圈没跑满时长，
                                      均值天然偏低。不标出来会和满时长圈混在一条趋势里，把人带偏。 */}
                                  {l.earlyEnd ? (
                                    <Badge
                                      variant="outline"
                                      className="border-amber-500/40 text-amber-600 dark:text-amber-400"
                                      title="采样期间检测到对局已结束，本圈提前收尾（未跑满设定时长）"
                                    >
                                      提前结束
                                    </Badge>
                                  ) : null}
                                  {/* 可回收缓存（v1.10.0 #84）：只在采了第二个基线且差值超过 10MB 时显示，
                                      少于这个量级不值得占版面。这块内存系统一施压就归还，不算泄漏。 */}
                                  {l.trimmedPssKb != null && l.baselinePssKb != null
                                    && l.baselinePssKb - l.trimmedPssKb > 10240 ? (
                                    <Badge
                                      variant="outline"
                                      className="border-sky-500/40 text-sky-600 dark:text-sky-400"
                                      title="未回收基线 − 回收后基线：系统一施压就归还，属缓存不属泄漏"
                                    >
                                      缓存 {Math.round((l.baselinePssKb - l.trimmedPssKb) / 1024)}M
                                    </Badge>
                                  ) : null}
                                </div>
                              </TableCell>
                              {isEarly ? (
                                <>
                                  <TableCell>
                                    <VerdictBadge verdict={l.verdict} />
                                  </TableCell>
                                  <TableCell className="max-w-md">
                                    <span className="line-clamp-2 text-xs text-muted-foreground">
                                      {l.invalidReason || ''}
                                    </span>
                                  </TableCell>
                                </>
                              ) : (
                                <>
                                  <TableCell><PerfRoundSource loop={l} gameName={games.find(game => game.id === l.gameId)?.name ?? `游戏 #${l.gameId}`} onQueryRound={onQueryRound} /></TableCell>
                                  <TableCell className="font-mono text-xs">
                                    {mb(l.baselinePssKb) ?? '—'}
                                  </TableCell>
                                  <TableCell className="font-mono text-xs">
                                    {mb(l.pssAvgKb) ?? '—'}
                                  </TableCell>
                                  <TableCell className="font-mono text-xs">
                                    {mb(l.pssPeakKb) ?? '—'}
                                  </TableCell>
                                  <TableCell className="font-mono text-xs">
                                    {l.cpuAvg != null ? l.cpuAvg.toFixed(1) : '—'}
                                  </TableCell>
                                  <TableCell className="font-mono text-xs">
                                    {l.cpuPeak != null ? l.cpuPeak.toFixed(1) : '—'}
                                  </TableCell>
                                  <TableCell className="font-mono text-xs">
                                    {l.baselineWsThreads ?? '—'} / {l.inGameWsThreads ?? '—'}
                                  </TableCell>
                                </>
                              )}
                              <TableCell className="text-xs"><Badge variant={l.cleanupStatus === 'FAILED' ? 'destructive' : 'outline'}>{cleanupLabel(l.cleanupStatus)}</Badge>{l.evidenceMissing && <p className="mt-1 text-xs text-amber-700">步骤证据可能缺失</p>}</TableCell>
                              <TableCell>
                                <VerdictBadge verdict={l.humanVerdict} />
                              </TableCell>
                              <TableCell>
                                {<Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs"
                                  data-testid={`perf-evidence-${l.id}`} aria-label={`查看第 ${l.loopNo} 圈接口步骤`}
                                  onClick={event => { event.stopPropagation(); setEvidenceLoop(l.id) }}>接口步骤</Button>}
                                <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs" data-testid={`perf-quality-${l.id}`} onClick={event => { event.stopPropagation(); setQualityLoop(l.id) }}>采样质量</Button>
                                <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs" data-testid={`perf-settlement-${l.id}`} onClick={event => { event.stopPropagation(); setSettlementLoop(l) }}>结算归属核验</Button>
                                <a href={perfContinuation.samplesUrl(l.runId,l.gameId,l.id)} className="block px-2 py-1 text-xs text-primary underline-offset-4 hover:underline" onClick={event => event.stopPropagation()}>本圈样本 CSV</a>
                                {isEarly && (
                                  <Button
                                    size="sm"
                                    variant="ghost"
                                    onClick={(e) => {
                                      e.stopPropagation()
                                      setReview({ kind: 'loop', id: l.id })
                                      setReviewVerdict((l.humanVerdict as PerfVerdict) || 'PASS')
                                      setReviewNote(l.humanNote || '')
                                    }}
                                  >
                                    复核
                                  </Button>
                                )}
                              </TableCell>
                            </TableRow>
                          ))}
                          {visibleLoops.length === 0 && !gameDataLoading && !gameDataError && !cleanupLoading && !cleanupError && (
                            <TableRow>
                              <TableCell
                                colSpan={9}
                                className="py-6 text-center text-sm text-muted-foreground"
                              >
                                {cleanupFilter ? '没有符合清理条件的圈' : '暂无圈数据'}
                              </TableCell>
                            </TableRow>
                          )}
                        </TableBody>
                      </Table>
                    </div>
                  </div>

                  {/* 内存分项趋势（v1.10.1）：把「PSS 涨了多少」拆成「哪一类内存在涨」。
                      与判定项 6、报告同名小节三处同口径。 */}
                  {memTrendData.length > 2 && (
                    <>
                      <div className="h-72" data-testid="perf-mem-dim-trend">
                        <div className="mb-1 text-xs font-semibold text-muted-foreground">
                          内存分项趋势（每圈关后基线，MB）
                          <span className="font-normal">
                            ｜图例的「持续」= 第 2 圈 → 末圈；第 1 圈是冷启动（首次加载资源，
                            一次性），不计入增长
                          </span>
                        </div>
                        <ResponsiveContainer width="100%" height="88%">
                          <LineChart data={memTrendData}>
                            <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
                            <XAxis dataKey="loop" fontSize={11} />
                            <YAxis fontSize={11} width={54} unit=" MB" />
                            <Tooltip formatter={(v) => `${Math.round(Number(v))} MB`} />
                            <Legend wrapperStyle={{ fontSize: 11 }} />
                            {coldStartLoop != null && (
                              <ReferenceLine
                                x={coldStartLoop}
                                stroke="#94a3b8"
                                strokeDasharray="3 3"
                                label={{
                                  value: '冷启动',
                                  fontSize: 10,
                                  fill: '#94a3b8',
                                  position: 'insideTopLeft',
                                }}
                              />
                            )}
                            {MEM_DIMS.map((d) => (
                              <Line
                                key={d.key}
                                type="monotone"
                                dataKey={d.key}
                                name={d.name}
                                stroke={d.color}
                                strokeWidth={d.key === 'native' ? 2 : 1.4}
                                dot={false}
                              />
                            ))}
                          </LineChart>
                        </ResponsiveContainer>
                      </div>

                      {/* 六项各自量程：共用纵轴时小量级的项会被压扁，分开画才看得出它自己有没有动 */}
                      <div
                        className="grid grid-cols-1 gap-3 md:grid-cols-3"
                        data-testid="perf-mem-dim-small"
                      >
                        {memDimSeries.map((d) => (
                          <div key={d.key} className="h-40 rounded-md border px-2 py-1">
                            <div className="flex items-center justify-between text-xs">
                              <span className="font-medium" style={{ color: d.color }}>
                                {d.name}
                              </span>
                              <span className="text-muted-foreground">
                                {Math.round(d.base)} → {Math.round(d.last)} MB（持续{' '}
                                {d.delta >= 0 ? '+' : ''}
                                {Math.round(d.delta)}）
                              </span>
                            </div>
                            <ResponsiveContainer width="100%" height="82%">
                              <LineChart data={memTrendData}>
                                <XAxis dataKey="loop" hide />
                                <YAxis fontSize={10} width={40} domain={['auto', 'auto']} />
                                <Tooltip formatter={(v) => `${Math.round(Number(v))} MB`} />
                                <Line
                                  type="monotone"
                                  dataKey={d.key}
                                  stroke={d.color}
                                  strokeWidth={1.8}
                                  dot={false}
                                />
                              </LineChart>
                            </ResponsiveContainer>
                          </div>
                        ))}
                      </div>
                    </>
                  )}

                  {/* 内存增长曲线（v1.9.1）：各局局内 PSS 叠加，浅→深 = 局序先后 */}
                  {growthData.length > 0 && (
                    <div className="h-72">
                      <div className="mb-1 text-xs font-semibold text-muted-foreground">
                        内存增长曲线（各局局内 PSS 叠加 · 浅→深 = 局序先后；后段曲线整体抬高 =
                        存在增长）
                        {growth != null && growth.totalOk > growthNames.length
                          ? `（共 ${growth.totalOk} 个有效局，等距抽样 ${growthNames.length} 条）`
                          : ''}
                      </div>
                      <ResponsiveContainer width="100%" height="88%">
                        <LineChart data={growthData}>
                          <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
                          <XAxis
                            dataKey="t"
                            fontSize={11}
                            label={{
                              value: '局内第几秒',
                              position: 'insideBottomRight',
                              fontSize: 10,
                              offset: -4,
                            }}
                          />
                          <YAxis fontSize={11} width={48} />
                          <Tooltip />
                          <Legend />
                          {growthNames.map((name, i) => (
                            <Line
                              key={name}
                              type="monotone"
                              dataKey={name}
                              stroke={GROWTH_COLORS[i % GROWTH_COLORS.length]}
                              dot={false}
                              connectNulls={false}
                            />
                          ))}
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  )}

                  {selLoop != null && samples.length > 0 && <PerfSampleSources samples={qualitySamples} />}
                  {/* 单圈曲线 */}
                  {selLoop != null && sampleData.length > 0 && (
                    <div className="h-64">
                      <div className="mb-1 text-xs font-semibold text-muted-foreground">
                        单圈逐秒曲线（圈 #{loops.find((l) => l.id === selLoop)?.loopNo}，PSS MB /
                        CPU % / 连接数） · 横轴：{sampleTimeKnown ? '单调偏移秒' : '采样序号，实际时间未完整记录'}
                      </div>
                      <ResponsiveContainer width="100%" height="88%">
                        <LineChart data={sampleData}>
                          <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
                          <XAxis dataKey="seq" fontSize={11} />
                          <YAxis yAxisId="left" fontSize={11} width={44} />
                          <YAxis
                            yAxisId="right"
                            orientation="right"
                            fontSize={11}
                            width={36}
                            domain={[0, 100]}
                          />
                          <Tooltip />
                          <Legend />
                          <Line
                            yAxisId="left"
                            type="monotone"
                            dataKey="pss"
                            name="PSS (MB)"
                            stroke="#3b82f6"
                            dot={false}
                            connectNulls={false}
                          />
                          <Line
                            yAxisId="right"
                            type="monotone"
                            dataKey="cpu"
                            name="CPU (%)"
                            stroke="#ef4444"
                            dot={false}
                            connectNulls={false}
                          />
                          <Line
                            yAxisId="right"
                            type="monotone"
                            dataKey="netConn"
                            name="连接数"
                            stroke="#10b981"
                            dot={false}
                            connectNulls={false}
                          />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <PerfFrozenPlan runId={planView?.id ?? null} resume={planView?.resume} onClose={() => setPlanView(null)} onResumed={resumed} />
      <PerfQuality loopId={qualityLoop} onClose={() => setQualityLoop(null)} />
      <PerfSettlementCheck loop={settlementLoop} cases={apiCases} onClose={() => setSettlementLoop(null)} />
      <PerfLoopEvidence loopId={evidenceLoop} onClose={() => setEvidenceLoop(null)} />
      {/* ---------- 人工复核弹窗 ---------- */}
      <Dialog open={review != null} onOpenChange={(open) => !open && setReview(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>人工复核</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <Label className="text-xs">结论</Label>
              <Select value={reviewVerdict} onValueChange={(v) => setReviewVerdict(v as PerfVerdict)}>
                <SelectTrigger className="w-40">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="PASS">PASS</SelectItem>
                  <SelectItem value="SUSPECT">SUSPECT</SelectItem>
                  <SelectItem value="FAIL">FAIL</SelectItem>
                  <SelectItem value="N_A">N/A（不适用）</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label className="text-xs">备注</Label>
              <Input
                value={reviewNote}
                onChange={(e) => setReviewNote(e.target.value)}
                placeholder="复核说明（可选）"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setReview(null)}>
              取消
            </Button>
            <Button onClick={() => void submitReview()}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
