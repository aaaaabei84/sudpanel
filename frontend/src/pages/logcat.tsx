import { useLogFilter } from '@/hooks/use-log-filter'
import type { AdvancedLogFilter } from '@/lib/advanced-log-filter'
import { logExportText, downloadLogExport, downloadLogMetadata } from '@/lib/log-export'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type WheelEvent } from 'react'
import { ChevronDown, Eraser, Pause, Play, RefreshCw, Trash2, X } from 'lucide-react'
import { toast } from 'sonner'
import type { LogcatKeyword } from '@/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { LogViewer } from '@/components/log-highlight'
import { KEYWORD_HIT_CLASS, LOG_LEVELS, filterLogIndexes } from '@/lib/log-line'
import {
  EMPTY_LOG_BUFFER,
  browseStart,
  lineWindowStart,
  mergeBuffer,
  shouldTouchBuffer,
  windowLineOffset,
  type LogBuffer,
} from '@/lib/log-buffer'
import { REENGAGE_TOLERANCE_PX, isNearBottom } from '@/lib/sticky-scroll'
import { OVERLAY_MAX_PX, availableBelow, clippingBottoms, overlayMaxHeight } from '@/lib/overlay-space'
import {
  USER_SCROLL_CONFIRM_FRAMES,
  USER_SCROLL_MIN_EPS_PX,
  isNotScrollingUp,
  isUserScrollUp,
  isBaselineStale,
  isContentShrink,
  nextFollowTop,
  userScrollTolerance,
} from '@/lib/scroll-anim'
import { api } from '@/lib/api'
import { useDevices } from '@/lib/device-context'
import { cn } from '@/lib/utils'

/**
 * 上报查询（2026-10-06，需求 #60）—— 把 Android Studio 里「实时看手机上报日志」搬进面板。
 *
 * <h2>怎么拿日志</h2>
 * 高频轮询，把本地缓冲的**最后一行**作为锚点带回去，后端只返回它之后的新行
 * （机制见后端 {@code LogcatTail}）。之所以用轮询而不是长连接：不引入长进程/连接管理
 * 这套最重的基建；真不够再上 SSE 也是独立的一步。
 *
 * <h2>为什么关键字与级别都在前端过滤</h2>
 * 级别字符就在行首 meta 里，内容也已经在手上 —— 本地筛比让设备重跑一次快得多，
 * 改一下过滤条件是瞬间的事。后端只负责「把新行给我」这一件事。
 *
 * <h2>三处很容易做错、这里刻意避开的</h2>
 * <ol>
 *   <li><b>轮询不能重置用户在看的/在填的</b>：关键字、级别勾选、滚动位置都必须跨轮次保留。
 *       每一轮都把控件清零是最气人的写法。</li>
 *   <li><b>reset 必须替换而不是追加</b>：锚点失效时后端给的是整段，
 *       追加会让界面内容与设备实际内容错位，而且错得很隐蔽。</li>
 *   <li><b>缓冲区要有上限</b>：不设上限的话跑一晚上会把浏览器内存吃光。超出后只留尾部。</li>
 * </ol>
 */
/**
 * 两次轮询之间**至少**间隔多少毫秒（2026-10-06，需求 #63 用户指定 100ms）。
 *
 * <p>它只是「下限」，不是「实际周期」。真机实测一次取文（`adb logcat -d` + 走一遍 JS）
 * 约 330ms —— 那是**设备端把环形缓冲读出来**的固定开销，与取多少行几乎无关
 * （需求 #64 实测：整缓冲 312ms / 取 2000 行 342ms / 取 200 行 326ms）。
 * 所以实际刷新率是被设备限住的，大约每秒 3 次；界面显示的是**实测值**而不是这里的推导值，
 * 免得写死一个做不到的数（见 `rateText`）。
 *
 * <p>排期时会**扣掉本次往返耗时**（见轮询主循环），设备够快时才能真正贴住这个间隔；
 * 设备慢时表现为「一来一回立刻再发」，不会叠上无谓的等待。
 *
 * <p>前端靠 `busyRef` 保证**上一次没回来就不发下一次**：万一某次 adb 卡住，
 * 表现为刷新变慢，而不是请求堆积把浏览器压住 —— 也就是说**实际频率会自动退到设备能承受的水平**，
 * 这是刻意的。
 */
const POLL_MS = 100

/**
 * 本地最多保留多少行（2026-10-08 使用者要求：5000 → 200000）。
 *
 * <p>原值 5000 只覆盖约半分钟的刷屏量（使用者实测），回溯窗口太短。
 * 200000 行按每行约 150~300 字节估算占几十 MB 内存 —— 现代浏览器可承受；且
 * 「不跟随时只渲染一页」（见 {@link BROWSE_PAGE}）把**保留量**与**渲染量**解耦：
 * 数据都在（可搜索、可翻页回看），但任何时候都不往 DOM 里塞全量。
 */
const MAX_LINES = 200000

/**
 * 跟随时渲染窗口的**驻留行数**与**滞回步长**（2026-10-06，需求 #63）。
 *
 * <p>缓冲里留着 {@link MAX_LINES} 行是为了「往回翻能看到更早的」，但**渲染**不能全量：
 * 每秒刷新 10 次、每次都重建整个窗口的元素，5000 行足以把主线程吃满 ——
 * 那才是「日志一多就卡」的真正来源（不是网络，也不是滚动）。
 *
 * <p><b>为什么不是「永远只留最后 1200 行」</b>：那样窗口每来一行就整体前移一行，
 * 渲染高度恒定不变，而连续上滚动画的唯一依据就是「内容高度在长」——
 * 高度不变就没有位移可算，画面又退回每 100ms 整块跳一下。
 * 所以窗口是**滞回**的：从 1200 自然长到 1260，再一次性前移 60 行回到 1200 附近。
 * 两次前移之间内容在长（动画有东西可动），渲染行数又有界。规则见
 * `lib/log-buffer.ts` 的 `lineWindowStart`，那里连「为什么不能固定窗口」一起写了。
 */
const FOLLOW_WINDOW_LINES = 1200

/** 滞回步长：窗口长到这个步长之后一次性前移同样多。60 行约等于 1.2 秒的上报量。 */
const FOLLOW_WINDOW_CHUNK = 60

/**
 * 不跟随时「浏览窗口」一页多少行（2026-10-08，v1.9.2）。
 *
 * <p>缓冲提到 200000 行之后，往回翻不能再全量渲染 —— 一页 3000 行约为「一屏内
 * 容加上上下各若干屏」的量；滚到顶自动向前翻一页，翻到底部附近恢复跟随。
 */
const BROWSE_PAGE = 3000

/**
 * 默认勾选的级别（2026-10-14 使用者要求：默认**全部勾选**）。
 *
 * <p>原来是 D/I/W/E（照 AS Logcat 的习惯把 Verbose 排除在外）。改成全勾的理由：
 * 上报类日志的级别分布事先并不知道，缺了哪一级都表现为「怎么没有我要的那条」——
 * 这种静默漏掉比多几行噪音难查得多。级别按钮就在旁边，嫌吵随时可以点掉。
 */
const DEFAULT_LEVELS = new Set<string>(LOG_LEVELS)

export function LogcatPage({ active = true }: { active?: boolean }) {
  const { selected, devices } = useDevices()
  const [listening, setListening] = useState(false)
  const [buf, setBuf] = useState<LogBuffer>(EMPTY_LOG_BUFFER)
  const [keyword, setKeyword] = useState('')
  const [levels, setLevels] = useState<Set<string>>(new Set(DEFAULT_LEVELS))
  const [advanced, setAdvanced] = useState(false)
  const [includeTerms, setIncludeTerms] = useState(''), [excludeTerms, setExcludeTerms] = useState('')
  const [matchMode, setMatchMode] = useState<'AND' | 'OR'>('AND')
  const [filterRegex, setFilterRegex] = useState(''), [filterTag, setFilterTag] = useState(''), [filterPid, setFilterPid] = useState('')
  const capture = useRef({ firstReceivedAt: null as string | null, lastReceivedAt: null as string | null, anchorResets: 0 })
  const [hint, setHint] = useState('')
  const [follow, setFollow] = useState(true)
  /*
   * 「保留其它上报」（2026-10-14 需求 #89）。
   *
   * 现状是「设了关键字就把其它上报全屏蔽」—— 在现场盯着手机看的人常常需要**上下文**：
   * 命中行前后那几行往往正是判断「这条上报为什么没发出来」的关键，而它们恰好被筛掉了。
   *
   * 勾上之后做三件事：① 不过滤，行都留着（命中行照旧高亮）；② 停止自动滚动；
   * ③ 把视口停在命中处。不勾选时行为与改动前完全一致。
   */
  const [keepOthers, setKeepOthers] = useState(false)
  /** 跳到命中处时，在命中行上方留几行上下文（切窗口用）。 */
  const JUMP_CONTEXT_LINES = 3
  /** 实测的轮询周期（毫秒）。真机上取文耗时远大于目标间隔，界面要如实显示，不能写死。 */
  const [actualGapMs, setActualGapMs] = useState(0)
  // 隐藏时浏览器的滚动几何为零，不能用它覆盖用户离开前的位置或改变跟随状态。
  const visibleTopRef = useRef(0)
  const activeRef = useRef(active)
  const wasActiveRef = useRef(false)

  /*
   * 过滤关键字库（2026-10-06，需求 #66）。
   *   - saved：已保存的关键字（关键字本体 + 注释），来自后端；
   *   - pickerOpen：下拉是否展开；
   *   - noteDraft：「保存当前输入的关键字」时那句注释的草稿。
   */
  const [saved, setSaved] = useState<LogcatKeyword[]>([])
  const [pickerOpen, setPickerOpen] = useState(false)
  const [noteDraft, setNoteDraft] = useState('')
  /*
   * 面板的高度上限（像素），展开时按**真实可用空间**算出来（2026-10-07，需求 #67）。
   *
   * 为什么不写死一个 max-h：窗口一矮，写死的值照样把面板顶出视口 ——
   * 这条结论 v1.6.6（#52）已经吃过一次，见 `lib/overlay-space.ts` 的模块注释。
   * `null` 表示「还没量到」，此时不设内联高度，退回组件自身的自然高度。
   */
  const [menuMaxH, setMenuMaxH] = useState<number | null>(null)
  /*
   * 下拉的外层容器（把「输入框 + 下拉按钮 + 面板」整个包起来）。
   * 点击外部收起时要比对的是**它**而不是面板本身 —— 只比对面板的话，点那个按钮会先被
   * 判定成「点在外面」而收起，紧接着按钮自己的 onClick 又把它打开，看着像点不动。
   */
  const pickerRef = useRef<HTMLDivElement | null>(null)

  // 锚点与「是否正在取文」放 ref：它们只服务于轮询本身，变化不该触发重渲染
  const anchorRef = useRef<string | null>(null)
  const busyRef = useRef(false)
  /** 上一次轮询**完成**的时刻，用来算实测周期。 */
  const lastPollAtRef = useRef(0)
  // 设备在轮询过程中被换掉时，用这个标记让在途的响应作废，避免把 A 机的日志贴到 B 机名下
  const serialRef = useRef<string | null>(null)
  /** 本轮监听里「首次拿到内容时直接贴底」有没有做过（理由见 setBuf 后面的那段注释）。 */
  const jumpedRef = useRef(false)

  const serial = selected

  const clearBuffer = useCallback(() => {
    anchorRef.current = null
    setBuf(EMPTY_LOG_BUFFER)
    capture.current = { firstReceivedAt: null, lastReceivedAt: null, anchorResets: 0 }
    visibleTopRef.current = 0
    // 清空后把跟随打开：使用者刚把屏幕清干净，接下来要看的就是新出现的行。
    // 若此时还停在「翻上去就停住」的状态，清空之后新日志不跟，看着像坏了。
    setFollow(true)
    // 也让「首次贴底」重来一次：清空之后新来的那一批同样是一段「已经发生过的历史」。
    jumpedRef.current = false
  }, [])

  /** 取一次增量。返回 false 表示应当停止轮询（设备不在了）。 */
  const pollOnce = useCallback(async (): Promise<boolean> => {
    const s = serialRef.current
    if (!s || busyRef.current) {
      return true
    }
    busyRef.current = true
    try {
      const r = await api.logcatTail(s, anchorRef.current)
      // 在途期间设备被换掉：这一份结果已经不属于当前设备，直接丢掉
      if (serialRef.current !== s) {
        return true
      }
      if (r.deviceOffline) {
        setHint(r.message || '设备不在线')
        return false
      }
      setHint('')
      anchorRef.current = r.anchor
      /*
       * 只在**真的拿到行**时才动本地缓冲（2026-10-06，需求 #64 修复）。
       *
       * 这里原来还多一个分支：「reset 但一行都没有 → 把本地清空」。本意是「设备缓冲被清过了」，
       * 但它同时也是**取文失败**的形态 —— 一次什么都没取到时，界面会把手里的内容全丢掉，
       * 看起来就是「上报缓存隔几秒被自动清掉」。同一个信号不能同时表示「设备确实空了」
       * 和「这次没取到」，而后者的发生频率高得多，所以按「没取到 = 什么都不变」处理。
       *
       * 设备缓冲真被清空时不需要这个分支：下一轮拿到非空 dump、锚点找不到，自然走 reset 替换。
       * 使用者手动清设备缓冲时另有 {@code clearBuffer()} 显式清本地，也不依赖这里。
       */
      if (shouldTouchBuffer(r.lines.length)) {
        const now = new Date().toISOString()
        if (r.reset && capture.current.firstReceivedAt != null) capture.current.anchorResets++
        if (!capture.current.firstReceivedAt || r.reset) capture.current.firstReceivedAt = now
        capture.current.lastReceivedAt = now
        setBuf((prev) => mergeBuffer(prev, r.lines, r.reset, MAX_LINES))
      }
      return true
    } catch (e) {
      setHint(e instanceof Error ? e.message : String(e))
      return false
    } finally {
      busyRef.current = false
    }
  }, [])

  /**
   * 轮询主循环：用 setTimeout 链而不是 setInterval ——
   * 上一次请求还没回来时不该再发下一次（adb 调用可能超时），否则请求会堆积。
   *
   * <p><b>排期扣掉本次往返耗时</b>（2026-10-06，需求 #64 实测后改）：原来无条件
   * {@code setTimeout(POLL_MS)}，于是实际周期 = 往返耗时 + POLL_MS。
   * 真机实测一次取文约 330ms，也就是周期变成 430ms —— 白白多等 100ms。
   * 现在按「距离目标间隔还差多少」补，设备快时能真正贴住 {@link POLL_MS}，
   * 设备慢时就是「一来一回立刻再发」（{@code setTimeout(0)}），不会叠上无谓的等待。
   *
   * <p>顺带把**实测周期**记下来给界面显示：真机上取文耗时远大于 100ms，
   * 实际刷新率是被设备限住的，界面写死「每秒 10 次」就成了假话。
   */
  useEffect(() => {
    if (!listening || !serial) {
      return
    }
    let stopped = false
    let timer: number | undefined
    // 重新开始监听时先把上一次的计时清掉，免得把「隔了很久才点第二次开始」算进周期
    lastPollAtRef.current = 0
    setActualGapMs(0)
    const tick = async () => {
      const startedAt = performance.now()
      const keepGoing = await pollOnce()
      const now = performance.now()
      if (stopped) {
        return
      }
      if (lastPollAtRef.current > 0) {
        // 平滑一下：单次抖动不该在界面上跳来跳去
        const gap = now - lastPollAtRef.current
        setActualGapMs((prev) => (prev <= 0 ? gap : prev * 0.7 + gap * 0.3))
      }
      lastPollAtRef.current = now
      if (!keepGoing) {
        setListening(false)
        return
      }
      const elapsed = now - startedAt
      timer = window.setTimeout(() => void tick(), Math.max(0, POLL_MS - elapsed))
    }
    void tick()
    return () => {
      stopped = true
      if (timer !== undefined) {
        window.clearTimeout(timer)
      }
    }
  }, [listening, serial, pollOnce])

  // 切设备：清掉上一台的缓冲与锚点，别把两台机的日志混在一起
  useEffect(() => {
    serialRef.current = serial
    clearBuffer()
    setHint('')
  }, [serial, clearBuffer])

  /*
   * 拉一次保存过的关键字（2026-10-06，需求 #66）。
   *
   * 失败了**刻意不打扰使用者**：这是页面打开时的一次背景加载，后端抖动不该弹一条红提示；
   * 而且没加载到时下拉里就是「还没有保存过关键字」的空白态，功能仍然可用（能存、下次能选），
   * 只是看不到以前存过的。真正需要报错的是「保存 / 删除」这两个有明确意图的动作 ——
   * 那两个失败了不吭声才是问题。
   */
  useEffect(() => {
    let alive = true
    void api
      .logcatKeywords()
      .then((items) => {
        if (alive) {
          setSaved(items)
        }
      })
      .catch(() => {
        /* 静默：见上 */
      })
    return () => {
      alive = false
    }
  }, [])

  /** 下拉展开时，点面板之外或按 Esc 收起。 */
  useEffect(() => {
    if (!active || !pickerOpen) {
      return
    }
    const onDown = (e: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target as Node)) {
        setPickerOpen(false)
      }
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setPickerOpen(false)
      }
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [active, pickerOpen])

  /*
   * 面板展开时量一次「下面还剩多少地方」，把结果作为面板的高度上限（2026-10-07，需求 #67）。
   *
   * <p>量的时机必须是**布局阶段**（`useLayoutEffect`）：放到 `useEffect` 里会在浏览器
   * 已经画完一帧之后才补上高度，长列表那一下能看到面板先顶出去再收回来。
   *
   * <p>锚点是包裹「输入框 + 下拉按钮」的外层容器，不是输入框自己 ——
   * 面板贴的是这一层的底边（`top-full`），量错了会差出一行的高度。
   *
   * <p>收起时**不**把量到的值清掉：面板只在展开时渲染，而这里在**绘制前**就会写入新值，
   * 所以上一次的旧值永远没有机会被画出来（清它只会多一次渲染）。
   */
  useLayoutEffect(() => {
    if (!active || !pickerOpen) {
      return
    }
    const el = pickerRef.current
    if (!el) {
      return
    }
    const measure = () => {
      const avail = availableBelow(el.getBoundingClientRect().bottom, clippingBottoms(el))
      setMenuMaxH(Number.isFinite(avail) ? overlayMaxHeight(avail) : OVERLAY_MAX_PX)
    }
    measure()
    // 窗口变矮时可用空间跟着变，得重量；面板贴在控制卡上，页面本身不滚，所以不必监听滚动。
    window.addEventListener('resize', measure)
    return () => {
      window.removeEventListener('resize', measure)
    }
  }, [active, pickerOpen])

  // 日志区的滚动容器（不是页面）：跟随、「翻上去就停住」都作用在它身上。
  const scrollRef = useRef<HTMLDivElement | null>(null)
  /*
   * 跟随循环自己的几个记录。它们只服务于「这次滚动是不是用户干的」这个判断，
   * 变化不该触发重渲染，所以放 ref：
   *   - lastProgrammatic：我们最后一次写下的滚动位置；
   *   - lastStep：最近一帧推了多少像素（用来算判定容差）；
   *   - prevFrameHeight：上一帧读到的内容总高（用来识别「内容变矮了」）；
   *   - backwardStreak：连续判到「位置回退」的帧数（区分「事件迟了一帧」与「用户真在翻」）。
   */
  // 浏览窗口的期望起点（null = 最后一页）；仅 follow=false 时参与渲染
  const [browseWant, setBrowseWant] = useState<number | null>(null)
  /*
   * 翻页引起的程序性 scrollTop 调整要跳过 onScroll 的跟随恢复判断 ——
   * 翻页后视口贴到新页底部，不跳过会被误判「用户回到了最新」而立刻恢复跟随，翻页白做。
   */
  const pagingRef = useRef(false)
  const lastProgrammaticRef = useRef(0)
  const lastStepRef = useRef(0)
  const prevFrameHeightRef = useRef(0)
  const backwardStreakRef = useRef(0)
  /*
   * 不跟随期间的两个记录（需求 #65）。
   *   - lastUserTop：用户上一次停留的位置。恢复判定要拿它比「他还在往上翻吗」。
   *   - leftBottom：停住之后，用户有没有**明显离开过**底部。
   */
  const lastUserTopRef = useRef(0)
  /*
   * 为什么必须有「离开过底部」这个前提（2026-10-06 探针实测抓到的漏判）：
   *
   * 恢复的判据是「离底部够近 且 不是在往上翻」。**只看这两条的话，手动取消勾选会取消不掉** ——
   * 使用者点掉「自动滚到底」时，位置本来就在底部，于是他之后随便碰一下（滚一下、
   * 内容长一点引起的微调）都会满足那两条，勾选立刻被我们勾回来。
   * 他会觉得那个勾「黏住了、关不掉」。
   *
   * 加上这个前提就清楚了：**先离开过底部（说明他真的翻走过），回来时才恢复**；
   * 一直待在底部的，一律视为「他就是要关掉它」。
   * 探针里那条失败用例（距底 30px → 60px → 90px 连续往上翻却恢复了）走的就是这条路。
   */
  const leftBottomRef = useRef(false)

  // 返回页面时先恢复视口，再让跟随循环读取几何。采集循环不依赖 active，切页不会重启它。
  useLayoutEffect(() => {
    activeRef.current = active
    const returning = active && !wasActiveRef.current
    wasActiveRef.current = active
    if (!returning) return
    const el = scrollRef.current
    if (!el) return
    el.scrollTop = follow ? el.scrollHeight : visibleTopRef.current
    visibleTopRef.current = el.scrollTop
    lastUserTopRef.current = el.scrollTop
    lastProgrammaticRef.current = el.scrollTop
    lastStepRef.current = 0
    backwardStreakRef.current = 0
    prevFrameHeightRef.current = el.scrollHeight
  }, [active, follow])

  /**
   * 首次拿到内容时**一步贴底**（2026-10-06，需求 #65）。
   *
   * <p>「新行从底部滑出」是给**增量**的观感（需求 #63）。而开头的 2000 行是
   * 「已经发生过的历史」，让它们从顶部慢慢滑下来没有任何意义 —— 实测那一段要滑 1.9 秒，
   * 而且中途渲染窗口前移时视口内容会回退一次（探针抓到行号 570 → 13）。
   * 使用者看到的正是「**上报信息莫名其妙停留在中间部分**」。
   *
   * <p>所以第一次有内容时直接一步贴底，之后的新行才交给连续跟随动画。
   * 这样「一旦勾选就以最快速度到底」（他的原话）在**首屏**这一下是字面成立的。
   *
   * <p>用 `useLayoutEffect` 而不是 `useEffect`：要在浏览器绘制之前就把位置放好，
   * 否则会先画一帧「在顶部」、下一帧才跳到底，那反而是一下看得见的闪。
   * 同时把跟随循环的三个记录一起对齐 —— 位置是我们自己改的，不能让它在下一次判定里
   * 变成「用户往上翻」。
   */
  useLayoutEffect(() => {
    const el = scrollRef.current
    if (!active || !el || !follow || jumpedRef.current || buf.lines.length === 0) {
      return
    }
    el.scrollTop = el.scrollHeight // 浏览器会夹到合法上限，即真正的底部
    lastProgrammaticRef.current = el.scrollTop
    lastStepRef.current = 0
    backwardStreakRef.current = 0
    prevFrameHeightRef.current = el.scrollHeight
    visibleTopRef.current = el.scrollTop
    jumpedRef.current = true
  }, [active, buf.lines.length, follow])

  /**
   * 跟随循环（2026-10-06，需求 #63）—— 本需求的核心。
   *
   * <p>原来是每个轮询周期执行一句跳变赋值 `el.scrollTop = el.scrollHeight`：
   * 一批新行整块瞬移上来，观感就是使用者说的「画面太撕裂」。现在换成**常驻的动画循环**：
   * 只要处于跟随状态，就每帧读一次滚动几何、朝底部推一点点（位移算法在 `lib/scroll-anim.ts`）。
   * 于是新行是从视口底边**滑出来**的、整屏缓缓上滚，看不出「一批一批」。
   *
   * <p><b>为什么是常驻循环，而不是「内容变了就放一段动画」</b>：常驻循环读的是 DOM 的
   * 真实几何，任何来源的内容增长都会被自动跟上，不必依赖 React 的渲染时机，
   * 也不会出现「状态变了但动画没吃到那批内容」的错位。
   * 代价是跟随期间每帧多一次布局读取 —— 无内容变化时浏览器命中布局缓存，很便宜。
   *
   * <p><b>为什么依赖里没有内容</b>：动画是连续的，不能每 100ms 把循环重挂一次
   * （重挂会把「最近一帧位移」清零，容差判断跟着抖）。循环自己会去看 DOM，有事没事它自己知道。
   */
  useEffect(() => {
    const el = scrollRef.current
    if (!active || !el || !follow) {
      return
    }
    // 进入跟随的一刻先把基准位置对齐到当前位置。不这么做的话，上一次跟随留下的旧值
    // 会让「当前位置」看上去大幅回退，紧接着的一次滚动事件就会被误判成用户往上翻 ——
    // 表现为「刚点开始监听，跟随立刻自己关了」。
    lastProgrammaticRef.current = el.scrollTop
    lastStepRef.current = 0
    backwardStreakRef.current = 0
    prevFrameHeightRef.current = el.scrollHeight

    let raf = 0
    let lastTs = performance.now()
    const frame = (now: number) => {
      if (!activeRef.current) return
      const dt = (now - lastTs) / 1000
      lastTs = now
      const before = el.scrollTop
      visibleTopRef.current = before
      const height = el.scrollHeight

      /*
       * 「谁动了位置」分三步判，**顺序不能换**（2026-10-06，需求 #65 重排）。
       *
       * ① **内容变矮 → 那一下回退是我们自己造成的**。
       *    渲染窗口整体前移（跟随时只渲染尾部，滞回前移一次丢 60 行）、本地缓冲丢头、
       *    后端整段重推，都会让内容总高变小；浏览器随即把 scrollTop 夹回合法范围 ——
       *    实测那一下是 -826 ~ -1387px，**远比任何容差都大**。
       *    这一步必须排在「是不是用户往上翻」前面，否则每前移一次窗口，
       *    跟随就会在用户什么都没做的时候自己关掉（使用者报的「莫名其妙取消勾选」）。
       *
       * ② **位置回退超过容差，且连续 {@link USER_SCROLL_CONFIRM_FRAMES} 帧都如此 → 用户在往上翻**。
       *    要求连续两帧是为了滤掉一次性的滞后（事件派发、滚动锚定调整）；
       *    真人的翻动会持续，因为确认前的那一帧我们**刻意不推进**，回退差值不会被自己抹平。
       *
       * ③ **位置被往下推过 → 基准跟着抬高**。
       *    不做这一步，贴底之后循环不再写值、基准停在旧的小值上，之后用户真的往上翻时
       *    反而判不出来（这条让界面断言红过两轮）。注意只在「往下」时对齐，理由见
       *    `scroll-anim.ts` 的 `isBaselineStale`。
       */
      const shrank = isContentShrink(prevFrameHeightRef.current, height)
      prevFrameHeightRef.current = height
      if (shrank) {
        backwardStreakRef.current = 0
        lastProgrammaticRef.current = before
      } else if (
        isUserScrollUp(before, lastProgrammaticRef.current, userScrollTolerance(lastStepRef.current))
      ) {
        backwardStreakRef.current++
        if (backwardStreakRef.current >= USER_SCROLL_CONFIRM_FRAMES) {
          setFollow(false)
          return // 不再排下一帧；effect 的清理随后还会取消一次，重复取消无害
        }
        // 还没确认的这一帧**不推进**：推了就等于把用户刚拉走的位置又送回去，
        // 回退差值被我们抹平，下一帧就再也判不出来 —— 表现是「拽不动」。
        raf = requestAnimationFrame(frame)
        return
      } else {
        backwardStreakRef.current = 0
      }
      if (isBaselineStale(before, lastProgrammaticRef.current)) {
        lastProgrammaticRef.current = before
      }
      const next = nextFollowTop(before, height, el.clientHeight, dt)
      if (next > before) {
        el.scrollTop = next
        // 读回**真实值**：浏览器可能把小数取整、或夹到上限。记真实值才能让下一次
        // 「这次滚动是不是用户的」判断有准确的参照。
        const applied = el.scrollTop
        lastStepRef.current = Math.abs(applied - before)
        lastProgrammaticRef.current = applied
        visibleTopRef.current = applied
      }
      raf = requestAnimationFrame(frame)
    }
    raf = requestAnimationFrame(frame)
    return () => cancelAnimationFrame(raf)
  }, [active, follow])

  /**
   * 不跟随期间的两个记录初始化（需求 #65）。
   *
   * <p>恢复判定要拿「这次位置」和「上一次位置」比，看用户是不是还在往上翻；
   * 还要知道**他有没有翻走过**（理由见 `leftBottomRef` 的注释）。进入停住状态时
   * 一起对齐一次，避免拿上一次跟随留下的旧值去比 —— 那会让第一次滚动事件
   * 被误判成「在往上翻」，恢复不了。
   */
  useEffect(() => {
    if (!active || follow) {
      return
    }
    const el = scrollRef.current
    const top = el?.scrollTop ?? 0
    lastUserTopRef.current = top
    /*
     * 停住的那一刻如果位置**已经远离底部**，就直接认定「他翻走过」——
     * 不能指望后面的滚动事件来补这一刀（2026-10-06 探针实测踩到）。
     *
     * 因为让跟随停下来的那次翻动，是发生在 follow 还是 true 的时候：那时 onScroll 直接
     * return（跟随中一律不处理），等 follow 变成 false，那个事件早过去了；使用者翻到中间
     * 就停手的话，后面**再也不会有滚动事件**，`leftBottomRef` 就永远是 false ——
     * 于是他再划回底部时恢复不了。实测就是这个：翻到中间 → 划回距底 100px → 勾选没回来。
     */
    leftBottomRef.current = el
      ? !isNearBottom(top, el.scrollHeight, el.clientHeight, REENGAGE_TOLERANCE_PX)
      : false
  }, [active, follow])

  /**
   * 用户自己滚动时怎么处理。
   *
   * <p><b>跟随中这里什么都不做</b>（2026-10-06，需求 #65 改）：停住跟随的判定全部交给上面的
   * 动画循环 —— 它每帧都读得到实时位置，比滚动事件更及时，而且拿得到「内容高矮」这个
   * 事件里没有的信息（窗口前移那一下必须靠它区分）。两处各判一遍只会互相打架。
   *
   * <p>不跟随时只有一个职责：**用户把位置挪到接近底部了，就把「自动滚到底」重新勾上** ——
   * 这正是使用者这一轮明确要的行为（原话：「**手动划到最底下或者接近最底下时自动勾选
   * 自动滚动到底**」）。三个条件缺一不可：
   * <ol>
   *   <li>离底部足够近：用比「贴底」更宽松的 {@link REENGAGE_TOLERANCE_PX}（120px，
   *       手指在触屏上做惯性滚动、或用滚轮，根本停不进 24px 以内）；</li>
   *   <li>**不是在往上翻**：位置还在减小就绝不恢复。少了这一条会出现
   *       「停住 → 判贴底 → 恢复 → 又被拽回底部 → 用户再翻 → 停住」的来回跳，
   *       也就是使用者说的「滑到最底下也会莫名其妙跳来跳去」；</li>
   *   <li>**他确实翻走过**（{@link leftBottomRef}）：否则手动点掉勾选之后会被反复勾回来。</li>
   * </ol>
   */
  const onScroll = () => {
    const el = scrollRef.current
    if (!active || !el) return
    visibleTopRef.current = el.scrollTop
    if (follow) {
      return
    }
    // 翻页引发的程序性滚动：跳过本次的跟随恢复判断（见 pagingRef 注释），只更新基准
    if (pagingRef.current) {
      pagingRef.current = false
      lastUserTopRef.current = el.scrollTop
      return
    }
    const top = el.scrollTop
    const previous = lastUserTopRef.current
    lastUserTopRef.current = top
    /*
     * 滚到渲染窗口顶部且还有更早的行：自动向前翻一页（v1.9.2 浏览窗口）。
     * 翻页后把视口贴到新页底部（接缝处）——用户看到的是与刚才相邻的更早内容，
     * 继续上滚即可一路回溯；pagingRef 保证这次程序性滚动不触发「回到最新」的恢复。
     */
    if (top <= 0 && browseWindowStart > 0) {
      setBrowseWant(browseWindowStart - BROWSE_PAGE)
      pagingRef.current = true
      requestAnimationFrame(() => {
        const el2 = scrollRef.current
        if (el2 && activeRef.current) {
          el2.scrollTop = el2.scrollHeight - el2.clientHeight
          lastUserTopRef.current = el2.scrollTop
          visibleTopRef.current = el2.scrollTop
        }
      })
      return
    }
    /*
     * 「他确实翻走过」这一条**跟方向无关**，必须先判、且不能被下面的方向判据挡住
     * （2026-10-06 探针实测踩到）。
     *
     * 第一版把它排在「不是在往上翻」之后，结果是：使用者往上翻到中间这个动作
     * **本身**就被判成「在往上翻」，于是永远走不到置位那一行 ——
     * 他再划回底部时恢复不了，功能整个失效。远离底部就是远离底部，与方向无关。
     */
    if (!isNearBottom(top, el.scrollHeight, el.clientHeight, REENGAGE_TOLERANCE_PX)) {
      leftBottomRef.current = true
      return
    }
    // 回到「接近底部」这一段之后，才需要看方向：还在往上翻就绝不恢复
    if (!isNotScrollingUp(top, previous, USER_SCROLL_MIN_EPS_PX)) {
      return
    }
    if (leftBottomRef.current) {
      setFollow(true)
    }
  }

  /**
   * 滚轮（需求 #63 的「往上拨就停」+ 需求 #65 的「往下拨到底部就恢复」）。
   *
   * <p>为什么不只靠 onScroll 的兜底判断：兜底要等滚动事件回来、还要盖住一帧的派发延迟，
   * 用户会先看到「我往上拨了一下、它还在往下走」。滚轮的**方向**是明确信号，当场用掉，
   * 一帧的犹豫都没有。
   *
   * <p>但「已经没得往上滚」（内容不足一屏、或已经在顶部）时不停：那时往上拨什么都不会发生，
   * 停了反而没意义 —— 而且之后不会再有滚动事件能把跟随恢复回来，等于永久坏掉。
   */
  const onWheel = (e: WheelEvent<HTMLDivElement>) => {
    const el = scrollRef.current
    if (!active || !el) {
      return
    }
    lastUserTopRef.current = el.scrollTop
    if (e.deltaY < 0) {
      if (el.scrollTop > 0) {
        setFollow(false)
      }
      return
    }
    // 往下拨：已经在接近底部、而且他确实翻走过，就直接勾回来，不用等滚动事件
    if (
      !follow &&
      leftBottomRef.current &&
      isNearBottom(el.scrollTop, el.scrollHeight, el.clientHeight, REENGAGE_TOLERANCE_PX)
    ) {
      setFollow(true)
    }
  }

  /** 手指碰到日志区就交还控制权：手机上没有「滚轮方向」这种明确信号，先停住最不容易出错。 */
  const onTouchStart = () => {
    const el = scrollRef.current
    if (!active || !el || el.scrollTop <= 0) {
      return
    }
    lastUserTopRef.current = el.scrollTop
    setFollow(false)
  }

  const toggleLevel = (lv: string) => {
    setLevels((prev) => {
      const next = new Set(prev)
      if (next.has(lv)) {
        next.delete(lv)
      } else {
        next.add(lv)
      }
      return next
    })
  }

  /*
   * 过滤关键字库的三个动作（2026-10-06，需求 #66）。
   */
  /**
   * 保存当前输入框里的关键字。
   *
   * <p>关键字本体取**输入框里现在的内容**（而不是另开一个输入框重敲）——
   * 「一边看日志一边发现这个词有用，顺手存下来」是最常见的用法，那条路径要最短。
   *
   * <p>这里**不做去重判断**：同一个关键字再存一次在后端是「更新注释」（唯一索引 + upsert），
   * 前端要去重就得先有一份完整列表，而那份列表在多个窗口同时开着时随时可能是旧的。
   */
  const saveKeyword = useCallback(async () => {
    const kw = keyword.trim()
    if (!kw) {
      toast.error('先在上面填一个关键字')
      return
    }
    try {
      const r = await api.logcatKeywordSave(kw, noteDraft)
      if (!r.ok) {
        toast.error(r.message || '保存失败')
        return
      }
      setSaved(r.items)
      setNoteDraft('')
      toast.success(`已保存关键字「${kw}」`)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    }
  }, [keyword, noteDraft])

  /** 删掉一条保存过的关键字。 */
  const removeKeyword = useCallback(async (item: LogcatKeyword) => {
    try {
      const r = await api.logcatKeywordDelete(item.id)
      setSaved(r.items)
      if (!r.ok) {
        // 「删两次」（连点、或另一个窗口已经删过）不是错误，一句话就够，不该弹红
        toast(r.message || '这条已经不在了')
      } else {
        toast.success(`已删除「${item.keyword}」`)
      }
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    }
  }, [])

  // 回到跟随后重置浏览窗口：下次再往上翻从「最后一页」开始，而不是停在旧位置
  useEffect(() => {
    if (follow) {
      setBrowseWant(null)
    }
  }, [follow])

  const onStart = () => {
    if (!serial) {
      toast.error('先在左侧选一台设备')
      return
    }
    setHint('')
    setListening(true)
  }

  const onClearDevice = async () => {
    if (!serial) {
      toast.error('先在左侧选一台设备')
      return
    }
    try {
      const r = await api.logcatClear(serial)
      if (!r.ok) {
        toast.error(r.message || '清空失败')
        return
      }
      clearBuffer()
      toast.success('已清空设备日志缓冲')
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    }
  }

  const onlineCount = devices.filter((d) => d.status === 'device').length

  /**
   * 刷新率的显示文案（2026-10-06，需求 #64）。
   *
   * 监听时显示实际测得的刷新频率，未监听时显示目标上限。
   * 设备读取与传输都有开销，实际频率可能低于设置上限，因此不使用固定值代替实测值。
   */
  const rateText =
    listening && actualGapMs > 0
      ? `实测每秒约 ${(1000 / actualGapMs).toFixed(1)} 次`
      : `每秒最多 ${1000 / POLL_MS} 次`

  /*
   * 过滤与渲染窗口（2026-10-06，需求 #63）。
   *
   * 过滤走 lib 里的纯函数（可被断言脚本直接压）。这里用的是 `filterLogIndexes` 而不是
   * `filterLogLines`：渲染窗口每刷新一次就整体前移，若按窗口内的相对下标给 React 当 key，
   * 每次刷新都会让 1200 行的 key 全变、记忆化全废。所以要拿到**绝对下标**。
   *
   * `windowStart` 只在跟随时才切尾部；一旦使用者往上翻（follow=false）就回全量 ——
   * 他正在看历史，只渲染尾部等于把历史藏起来了。规则见 `lib/log-buffer.ts`。
   */
  const filterConfig = useMemo<AdvancedLogFilter>(() => ({ keyword, levels: [...levels], includes: includeTerms.split('\n'), excludes: excludeTerms.split('\n'), mode: matchMode, regex: filterRegex, tag: filterTag.trim(), pid: filterPid.trim() }), [keyword, levels, includeTerms, excludeTerms, matchMode, filterRegex, filterTag, filterPid])
  const filtering = useLogFilter(buf, advanced, filterConfig)
  // 勾了「保留其它上报」就不过滤：直接给出全部行的绝对下标。
  // 高级筛选（advanced）不走这条路 —— 那是另一套语义（正则/包含/排除），混在一起会互相打架。
  const allIdx = useMemo(
    () => Array.from({ length: buf.lines.length }, (_, i) => i),
    [buf.lines.length],
  )
  const keepAll = keepOthers && !advanced
  const shownIdx = keepAll ? allIdx : filtering.indexes


  // 跟随：滞回尾部窗口；不跟随：浏览分页窗口（一页 BROWSE_PAGE 行，滚到顶自动往前翻页）
  /*
   * 「停在命中关键字的地方」（需求 #89）。
   *
   * 两个坑决定了这段要这么写：
   *   1) **不能跟着 buf.lines 反复触发** —— 缓冲每来一行都重算的话，视口会被反复拽回命中处，
   *      那就不是「停住」而是「钉死」。所以用 parkedFor 记住「这个关键字已经停过了」，
   *      同一个关键字只停一次；buffer 只作为「算命中」的输入。
   *   2) **停在命中行的前一行** —— 命中行前面的上下文（请求参数、上一条状态）才是要看的东西，
   *      把命中行顶到视口第一行等于把上下文全切掉。
   */
  const parkedFor = useRef<string | null>(null)
  /** 待跳转的绝对行号（非 null 表示「这次渲染后把那一行滚进视口」）。 */
  const pendingJumpRef = useRef<number | null>(null)
  /**
   * 定位到最后一个命中行（停在它**前一行**，保住上下文）。
   *
   * <p>两处调用：关键字变化时自动停一次；以及**输入框里按回车**时手动再跳回去
   * （2026-10-14 使用者要求）—— 翻走看上下文之后，回车得能回到命中处。
   * 抽出来共用，避免「自动停」与「手动跳」两套实现各写一遍而漂移。
   *
   * <p>三个动作缺一不可：停跟随（否则跟随会立刻把视口推回底部）、
   * 切浏览窗口（渲染的是那一页）、**程序化滚到该页顶端**并把 `pagingRef` 置起来
   * （否则这次滚动会被 onScroll 当成用户在翻页、进而触发「回到最新」的恢复）。
   */
  const jumpToHits = () => {
    if (!keepAll || keyword.trim() === '') {
      return false
    }
    const hits = filterLogIndexes(buf.lines, keyword, [...levels])
    if (hits.length === 0) {
      return false
    }
    const last = hits[hits.length - 1]
    // 浏览窗口切到命中行附近（留 {JUMP_CONTEXT_LINES} 行上下文）。
    // 起点有上限（不能晚于「总行数 − 一页」），所以真正的定位交给下面那个 effect：
    // 它按绝对行号把那**一行**滚进视口 —— 只改窗口起点是不够的（命中行可能落在窗口深处）。
    setFollow(false)
    setBrowseWant(Math.max(0, last - JUMP_CONTEXT_LINES))
    pendingJumpRef.current = last
    return true
  }

  /*
   * 把待跳转的那一行滚进视口（2026-10-14，需求 #89）。
   *
   * 放在 effect 里而不是 requestAnimationFrame：状态更新后要**等 DOM 提交完**那一行才存在，
   * rAF 与 React 的提交顺序不保证；effect 每次渲染后都跑，条件不满足时只做一次 ref 判空。
   *
   * `pagingRef` 必须在滚动前置起来：否则这次滚动会被 onScroll 当成「用户在翻页」，
   * 进而触发「回到最新」的恢复 —— 那就是「按回车跳过去又被拉回底部」。
   */
  useEffect(() => {
    const target = pendingJumpRef.current
    if (target == null) {
      return
    }
    const row = scrollRef.current?.querySelector(`[data-line-index="${target}"]`)
    if (!(row instanceof HTMLElement)) {
      return
    }
    pendingJumpRef.current = null
    pagingRef.current = true
    row.scrollIntoView({ block: 'start' })
  })
  useEffect(() => {
    if (!keepAll) {
      parkedFor.current = null
      return
    }
    // 勾上就先停跟随 —— **不要求先有关键字、也不要求有命中行**（2026-10-14 使用者要求）。
    // 使用者勾这个开关的意思是「我要停下来读」，哪怕当前一个命中都没有，
    // 继续把视口往底部推也只会让他读不成。
    setFollow(false)
    if (keyword.trim() === '') {
      return
    }
    if (parkedFor.current === keyword) {
      return
    }
    if (jumpToHits()) {
      parkedFor.current = keyword
    }
    // jumpToHits 每轮渲染都是新的引用，进依赖会让这个效果每次都跑 —— 有意为之：
    // 真正的「只停一次」由 parkedFor 把关，这里只需要在关键字或勾选状态变化时重判。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keepAll, keyword, levels, buf.lines])

  const followWindowStart = lineWindowStart(
    shownIdx.length,
    follow,
    FOLLOW_WINDOW_LINES,
    FOLLOW_WINDOW_CHUNK,
  )
  const browseWindowStart = browseStart(shownIdx.length, browseWant, BROWSE_PAGE)
  const windowStart = follow ? followWindowStart : browseWindowStart
  const windowEnd = follow
    ? shownIdx.length
    : Math.min(shownIdx.length, browseWindowStart + BROWSE_PAGE)

  const text = useMemo(() => {
    const parts: string[] = []
    for (let k = windowStart; k < windowEnd; k++) {
      parts.push(buf.lines[shownIdx[k]])
    }
    return parts.join('\n')
  }, [shownIdx, windowStart, windowEnd, buf.lines])

  // 窗口首行在缓冲区里的下标（窗口为空时取 0，此时这个值不会被用到）
  const firstShownLinesIndex = windowStart < shownIdx.length ? shownIdx[windowStart] : 0
  const lineOffset = windowLineOffset(buf, firstShownLinesIndex)

  /*
   * 布局（2026-10-06，需求 #62）：**页面不滚，只有日志区滚**。
   *
   * 改之前是「外层 space-y-4 + 两个自然高度的卡片」，日志一多整个页面就变长 ——
   * 控制面板被顶出视口，得往下滚才能点「停止」。这不是日志多少的问题，是布局没约束。
   *
   * 现在：
   *   - 最外层 `flex-1 min-h-0` + `overflow-hidden`：吃掉 main 的剩余高度、自己绝不滚。
   *     （不用 `h-full`：百分比高度在这条父链上解析不出来，实测会被内容撑长。）
   *   - 控制卡 `shrink-0`：不参与压缩，永远在顶部；
   *   - 日志卡 `flex-1 min-h-0`：吃掉剩余高度。**`min-h-0` 不能省** ——
   *     flex 子项默认 `min-height:auto`，会被内容顶开、把控制卡挤出去，正是要修的那个毛病；
   *   - 日志卡内部的滚动容器 `flex-1 min-h-0 overflow-auto`：真正的滚动发生在这里。
   */
  return (
    <div
      data-testid="logcat-page"
      aria-hidden={!active}
      className={cn('min-h-0 flex-1 flex-col gap-4 overflow-hidden', active ? 'flex' : 'hidden')}
    >
      {/*
        控制卡这两处 class 不是装饰，是给下拉浮层让路的（2026-10-07，需求 #67）：
        - `overflow-visible`：共享 `Card` 默认带 `overflow-hidden`（为了圆角切边），
          而这张卡只有几十像素高 —— 绝对定位的面板超出去的部分会被它**整段切掉**。
          实测面板 256→358、卡片底边 308：切掉 50px，底部的注释框与保存按钮
          既不显示也点不到，看起来就是「点开只露一行」。
        - `relative z-30`（仅展开时）：卡片有 `hover:-translate-y-0.5`，悬停时会自己形成
          层叠上下文，把面板的 z-index 关在卡片里 —— 于是后画的日志卡会盖住面板。
          给卡片本身抬层，面板才真的画在日志卡之上。
          只在这一下拉展开时加，是为了不动平时悬停的观感。
      */}
      <Card className={cn('shrink-0 overflow-visible', pickerOpen && 'relative z-30')}>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">上报查询</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-end gap-2">
            <div className="flex items-center gap-2">
              <Button size="sm" onClick={onStart} disabled={listening || !serial}>
                <Play className="mr-1 h-3.5 w-3.5" />
                开始监听
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setListening(false)}
                disabled={!listening}
              >
                <Pause className="mr-1 h-3.5 w-3.5" />
                停止
              </Button>
              <Button size="sm" variant="outline" onClick={() => void onClearDevice()} disabled={!serial}>
                <Eraser className="mr-1 h-3.5 w-3.5" />
                清空设备缓冲
              </Button>
            </div>
            <div className="ml-auto flex items-center gap-2 text-xs text-muted-foreground">
              <span
                className={cn(
                  'inline-flex h-2 w-2 rounded-full',
                  listening ? 'bg-emerald-500' : 'bg-muted-foreground/40',
                )}
              />
              <span>{listening ? '监听中' : '已停止'}</span>
              <span className="opacity-60">·</span>
              {/* 频率常驻显示，而不是只在「监听中」才出现：没选设备时监听根本起不来，
                  那种写法下用户永远看不到刷新频率 —— 而这恰恰是他决定要不要点开始之前
                  最想知道的一件事。数值见上面的 rateText（监听中给实测值）。 */}
              <span>{rateText}</span>
              <span className="opacity-60">·</span>
              <span>
                {serial ? `设备 ${serial}` : onlineCount > 0 ? '未选择设备' : '无在线设备'}
              </span>
            </div>
          </div>

          <div className="space-y-2">
            <Button size="sm" variant="outline" aria-expanded={advanced} onClick={() => setAdvanced(value => !value)}>高级筛选{advanced ? ' · 已启用' : ''}</Button>
            {advanced && <div className="grid gap-2 rounded-md border p-3 text-xs sm:grid-cols-2 lg:grid-cols-3">
              <label className="space-y-1">包含词（每行一个）<textarea aria-label="包含词" rows={2} className="w-full rounded border bg-background p-2" value={includeTerms} onChange={e => setIncludeTerms(e.target.value)} /></label>
              <label className="space-y-1">匹配方式<select aria-label="包含词匹配方式" className="h-8 w-full rounded border bg-background px-2" value={matchMode} onChange={e => setMatchMode(e.target.value as 'AND' | 'OR')}><option value="AND">全部包含 AND</option><option value="OR">任一包含 OR</option></select></label>
              <label className="space-y-1">排除词（每行一个）<textarea aria-label="排除词" rows={2} className="w-full rounded border bg-background p-2" value={excludeTerms} onChange={e => setExcludeTerms(e.target.value)} /></label>
              <label>正则表达式<Input aria-label="日志正则表达式" className="h-8 font-mono" value={filterRegex} onChange={e => setFilterRegex(e.target.value)} /></label>
              <label>Tag（精确匹配）<Input aria-label="日志Tag" className="h-8" value={filterTag} onChange={e => setFilterTag(e.target.value)} /></label>
              <label>PID（逗号分隔）<Input aria-label="日志PID" className="h-8" value={filterPid} onChange={e => setFilterPid(e.target.value)} /></label>
              <p className="text-muted-foreground sm:col-span-2 lg:col-span-3">与下方关键字、级别同时生效。无法识别 Tag/PID 的续行保留，再按关键词和正则筛选。计算超过 2 秒自动终止。</p>
            </div>}
            {advanced && filtering.busy && <p role="status" className="text-xs text-muted-foreground">正在筛选，采集和停止仍可使用…</p>}
            {advanced && filtering.error && <p role="alert" className="text-xs text-amber-700 dark:text-amber-400">{filtering.error}</p>}
          </div>

          {hint && (
            <div className="rounded-md border border-amber-500/40 bg-amber-500/5 px-3 py-2 text-xs text-amber-700 dark:text-amber-400">
              {hint}
            </div>
          )}

          <div className="grid gap-3 sm:grid-cols-2">
            {/* 外层 `relative` 是下拉的定位上下文；也把它交给 pickerRef，见上面的注释 */}
            <div className="relative space-y-1.5" ref={pickerRef}>
              <Label htmlFor="logcat-keyword" className="text-xs">
                关键字（子串匹配，留空 = 不过滤）
              </Label>
              <div className="flex items-center gap-1">
                <div className="relative flex-1">
                  <Input
                    id="logcat-keyword"
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                    // 回车 = 手动定位到命中处（需求 #89）：只在勾了「保留其它上报」时有意义 ——
                    // 没勾时其它行本来就看不见，也就无所谓「定位」。
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        jumpToHits()
                      }
                    }}
                    placeholder="例如 SudReport 或 isIn=true（回车定位到命中处）"
                    className="h-8 pr-7 font-mono text-xs"
                  />
                  {/* 清除按钮只在有内容时出现 —— 空输入框上挂一个叉是纯噪音。
                      它同时也是界面断言要点的那个把手（冒烟点击比键盘清空更贴近真人）。 */}
                  {keyword !== '' && (
                    <button
                      type="button"
                      aria-label="清空关键字"
                      title="清空关键字"
                      data-testid="logcat-keyword-clear"
                      onClick={() => setKeyword('')}
                      className="absolute right-1 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-8 shrink-0 px-2 rounded-lg border-border/80 bg-background/70 backdrop-blur-md hover:bg-background/90"
                  aria-expanded={pickerOpen}
                  aria-haspopup="listbox"
                  title="选择已保存的关键字 / 保存当前关键字"
                  data-testid="logcat-keyword-picker"
                  onClick={() => setPickerOpen((v) => !v)}
                >
                  <ChevronDown className="h-3.5 w-3.5" />
                  {saved.length > 0 && (
                    <span className="ml-1 font-mono text-xs">{saved.length}</span>
                  )}
                </Button>
              </div>

              {pickerOpen && (
                <div
                  role="listbox"
                  data-testid="logcat-keyword-menu"
                  /* 高度上限是展开时按可用空间量出来的，见上面的 useLayoutEffect */
                  style={menuMaxH == null ? undefined : { maxHeight: menuMaxH }}
                  className="absolute left-0 right-0 top-full z-50 mt-1.5 flex flex-col rounded-xl border border-border/80 bg-popover/88 backdrop-blur-2xl p-1.5 shadow-2xl ring-1 ring-white/10 dark:ring-white/5"
                >
                  {/*
                    列表自己有滚动，而「注释 + 保存」那一行留在滚动区**外面**。
                    否则关键字一多，往下滚就找不到保存按钮了 —— 偏偏那是这一层唯一的出口。
                  */}
                  <div
                    data-testid="logcat-keyword-list"
                    className="min-h-0 flex-1 overflow-y-auto"
                  >
                    {saved.length === 0 ? (
                      <div className="px-2 py-1.5 text-xs text-muted-foreground">
                        还没有保存过关键字 —— 在下面写句注释就能存下来
                      </div>
                    ) : (
                      saved.map((item) => (
                        <div
                          key={item.id}
                          className={cn(
                            'group flex items-center gap-1 rounded px-1.5 py-1',
                            item.keyword === keyword ? 'bg-primary/10' : 'hover:bg-muted',
                          )}
                        >
                          <button
                            type="button"
                            className="min-w-0 flex-1 text-left"
                            data-testid={`logcat-keyword-item-${item.id}`}
                            onClick={() => {
                              setKeyword(item.keyword)
                              setPickerOpen(false)
                            }}
                          >
                            <span className="font-mono text-xs text-foreground">{item.keyword}</span>
                            {item.note !== '' && (
                              // 2026-10-12 使用者要求：注释放大一点、加粗蓝色、与关键字之间加冒号。
                              // 蓝色是「注释」这一身份的视觉标签 —— 与关键字（等宽深色）区分开，
                              // 扫列表时先看到关键字、需要时再看注释。
                              <span className="ml-1 text-[13px] font-semibold text-blue-600 dark:text-blue-400">
                                ：{item.note}
                              </span>
                            )}
                          </button>
                          <button
                            type="button"
                            aria-label={`删除 ${item.keyword}`}
                            title="删除"
                            data-testid={`logcat-keyword-del-${item.id}`}
                            onClick={() => void removeKeyword(item)}
                            className="shrink-0 rounded p-0.5 text-muted-foreground opacity-0 hover:bg-background hover:text-destructive group-hover:opacity-100"
                          >
                            <Trash2 className="h-3 w-3" />
                          </button>
                        </div>
                      ))
                    )}
                  </div>

                  <Separator className="my-1 shrink-0" />

                  {/* 保存「输入框里现在这个关键字」。刻意不给关键字本体单独一个输入框：
                      它已经在上面了，再放一个就会出现「两个地方都能改、以哪个为准」的问题。 */}
                  <div className="shrink-0 space-y-1 p-1">
                    <div className="flex items-center gap-1">
                      <Input
                        value={noteDraft}
                        onChange={(e) => setNoteDraft(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            void saveKeyword()
                          }
                        }}
                        placeholder="给这个关键字写句注释（可空）"
                        data-testid="logcat-keyword-note"
                        className="h-7 text-xs"
                      />
                      <Button
                        type="button"
                        size="sm"
                        className="h-7 shrink-0 px-2 text-xs"
                        disabled={keyword.trim() === ''}
                        data-testid="logcat-keyword-save"
                        onClick={() => void saveKeyword()}
                      >
                        保存
                      </Button>
                    </div>
                    <div className="px-0.5 text-xs text-muted-foreground">
                      {keyword.trim() === '' ? (
                        '输入框为空 —— 先在上面填一个关键字'
                      ) : (
                        <>
                          将保存：
                          <span className="font-mono text-foreground">{keyword.trim()}</span>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">级别</Label>
              <div className="flex flex-wrap items-center gap-1">
                {LOG_LEVELS.map((lv) => (
                  <button
                    key={lv}
                    type="button"
                    aria-pressed={levels.has(lv)}
                    onClick={() => toggleLevel(lv)}
                    className={cn(
                      'h-7 w-8 rounded border font-mono text-xs transition-colors',
                      levels.has(lv)
                        ? 'border-primary bg-primary/10 text-foreground'
                        : 'border-border text-muted-foreground hover:bg-muted',
                    )}
                  >
                    {lv}
                  </button>
                ))}
                <span className="ml-1 text-xs text-muted-foreground">
                  （不勾任何一级 = 全部显示）
                </span>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
            {/*
              统计行固定宽度（2026-10-14 需求 #90）。
              原来勾上「保留其它上报」会把「筛后 N 行」整段撤掉，右侧那排按钮跟着左移 ——
              点一下按钮就换位置，用起来很难受。现在：
                · 这一段**始终在**，勾了就置灰、数字显示成 `*`（不代表没筛，而是「筛了但全留着」）；
                · 数字放进固定 4 字符宽的格子（`w-[4ch]`），1 位 / 2 位 / 3 位数都不会推动后面的按钮。
            */}
            <span>
              累计 <span className="font-mono text-foreground">{buf.lines.length}</span> 行，筛后{' '}
              <span
                data-testid="logcat-shown-count"
                className={cn(
                  'inline-block w-[4ch] text-left font-mono',
                  keepAll ? 'text-muted-foreground' : 'text-foreground',
                )}
              >
                {keepAll ? '*' : shownIdx.length}
              </span>{' '}
              行
            </span>
            <label className="flex items-center gap-1">
              <input
                data-testid="logcat-follow"
                type="checkbox"
                checked={follow}
                onChange={(e) => setFollow(e.target.checked)}
                className="h-3 w-3"
              />
              自动滚到底
            </label>
            {/* 保留其它上报（需求 #89）：勾上后不再屏蔽其它行、停止滚动、停在命中处 */}
            <label
              className="flex items-center gap-1"
              title="勾选后：其它上报不再被屏蔽（命中行仍高亮），并停止自动滚动、停在命中处"
            >
              <input
                data-testid="logcat-keep-others"
                type="checkbox"
                checked={keepOthers}
                onChange={(e) => setKeepOthers(e.target.checked)}
                className="h-3 w-3"
              />
              保留其它上报
            </label>
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-xs"
              onClick={() => clearBuffer()}
              disabled={buf.lines.length === 0}
            >
              <RefreshCw className="mr-1 h-3 w-3" />
              清空本地缓冲
            </Button>
            <Button variant="outline" size="sm" className="h-6 px-2 text-xs" data-testid="logcat-export-filtered" disabled={shownIdx.length === 0 || (advanced && filtering.busy)} onClick={() => { try { downloadLogExport(logExportText(buf.lines, shownIdx), 'filtered'); toast.success(`已导出 ${shownIdx.length} 行筛选日志`) } catch { toast.error('日志导出失败，请重试') } }}>导出筛选</Button>
            <Button variant="outline" size="sm" className="h-6 px-2 text-xs" data-testid="logcat-export-all" disabled={buf.lines.length === 0} onClick={() => { try { downloadLogExport(logExportText(buf.lines), 'all'); toast.success(`已导出 ${buf.lines.length} 行本地日志`) } catch { toast.error('日志导出失败，请重试') } }}>导出全部</Button>
            <Button variant="outline" size="sm" className="h-6 px-2 text-xs" data-testid="logcat-export-metadata" disabled={buf.lines.length === 0 || (advanced && filtering.busy)} onClick={() => downloadLogMetadata({ schemaVersion: 1, exportedAt: new Date().toISOString(), device: { serial: selected, model: devices.find(d => d.serial === selected)?.model ?? null }, collection: capture.current, buffer: { retainedLines: buf.lines.length, receivedInCurrentSegment: buf.total, discardedInCurrentSegment: Math.max(0, buf.total - buf.lines.length), capacity: MAX_LINES, filteredLines: shownIdx.length }, filter: { advanced, effective: filtering.effective, error: filtering.error || null }, completeness: capture.current.anchorResets ? 'ANCHOR_RESET_DETECTED' : 'CURRENT_BUFFER_ONLY', warning: '仅描述浏览器保留范围；设备环形缓冲此前丢弃的行数未知。日志文件保持 UTF-8 原文。' })}>导出元信息</Button>
            {buf.lines.length >= MAX_LINES && (
              <span className="text-amber-600 dark:text-amber-400">
                本地只保留最近 {MAX_LINES} 行
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* 日志卡吃掉剩余高度，内部滚动；控制卡在它上面且不参与压缩 */}
      <Card className="flex min-h-0 flex-1 flex-col">
        <CardContent className="flex min-h-0 flex-1 flex-col pt-4">
          {/* 滚动容器就是它：跟随、「翻上去停住」都作用在这里，页面本身不滚。
              LogViewer 不带高度限制，于是被内容撑高、由这层产生滚动 —— 这样就不用给
              LogViewer 再加一个「填满父容器」的属性，它在另外三处的行为一个字没变。

              `data-rendered` / `data-line-offset` 是给界面断言看的：本机没设备造不出
              「超过窗口上限的真实日志」，窗口与稳定行号只能在纯函数层压；
              这两个属性让界面层至少能证明「传下去的确实是同一个绝对行号体系」，
              而不是断言靠猜。 */}
          <div
            ref={scrollRef}
            onScroll={onScroll}
            onWheel={onWheel}
            onTouchStart={onTouchStart}
            data-testid="logcat-scroll"
            data-rendered={windowEnd - windowStart}
            data-line-offset={lineOffset}
            className="min-h-0 flex-1 overflow-auto"
          >
            {/* collapsible={false}：这里的日志在滚动，折叠成 5 行 + 「展开全部」就没法用了。
                highlight + hitClass（需求 #66）：把过滤用的关键字在行内放大加色标出来 ——
                过滤之后「为什么这几行留下了」应该一眼看得出来，而不是让人自己去找。 */}
            <LogViewer
              text={text}
              collapsible={false}
              lineOffset={lineOffset}
              highlight={keyword}
              hitClass={KEYWORD_HIT_CLASS}
              emptyText={
                buf.lines.length === 0
                  ? '还没有内容 —— 点「开始监听」，然后在手机上操作。'
                  : '当前关键字 / 级别条件下没有匹配的行'
              }
            />
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
