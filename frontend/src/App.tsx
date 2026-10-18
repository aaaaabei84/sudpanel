import { NavigationGuardProvider, useGuardedNavigation } from '@/lib/navigation-guard'
import { PageVisibilityContext } from '@/lib/page-visibility'
import { parsePanelLocation, updatePanelLocation, ensurePanelHistory, panelHistoryIndex, panelHash, type PanelLocation } from '@/lib/navigation'
import { DiagnosticPanel } from '@/components/diagnostic-panel'
import { MaintenancePanel } from '@/components/maintenance/maintenance-panel'
import { lazy, Suspense, useEffect, useState, useRef, type ReactNode } from 'react'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
} from '@/components/ui/sidebar'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Toaster } from '@/components/ui/sonner'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { api } from '@/lib/api'
import { useDevices } from '@/lib/device-context'
import { deviceStatusHint, formatDeviceCheckedAt } from '@/lib/device-status'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/utils'
import type { NavPage } from '@/pages/dashboard'
import type { RoundQueryContext } from '@/lib/apitest-workspace'
import { PageErrorBoundary } from '@/components/page-error-boundary'
import { Skeleton } from '@/components/ui/skeleton'

const DashboardPage = lazy(() => import('@/pages/dashboard').then(m => ({ default: m.DashboardPage })))
const VerifyPage = lazy(() => import('@/pages/verify').then(m => ({ default: m.VerifyPage })))
const ApptomgPage = lazy(() => import('@/pages/apptomg').then(m => ({ default: m.ApptomgPage })))
const MgtoappPage = lazy(() => import('@/pages/mgtoapp').then(m => ({ default: m.MgtoappPage })))
const PerfPage = lazy(() => import('@/pages/perf').then(m => ({ default: m.PerfPage })))
const ApitestPage = lazy(() => import('@/pages/apitest').then(m => ({ default: m.ApitestPage })))
const LogcatPage = lazy(() => import('@/pages/logcat').then(m => ({ default: m.LogcatPage })))

function PageSlot({ label, children, hidden = false }: { label: string; children: ReactNode; hidden?: boolean }) {
  return <PageErrorBoundary label={label} hidden={hidden}><Suspense fallback={hidden ? null : <div role="status" aria-label={`${label}加载中`} className="space-y-4"><Skeleton className="h-16 w-full" /><Skeleton className="h-52 w-full" /></div>}>{children}</Suspense></PageErrorBoundary>
}
import {
  Gamepad2,
  Gauge,
  LayoutDashboard,
  ListChecks,
  Moon,
  Plug,
  RefreshCw,
  ScanLine,
  ScrollText,
  Sun,
} from 'lucide-react'

const NAV: { key: NavPage | 'dashboard'; label: string; desc: string; icon: typeof Gamepad2 }[] = [
  { key: 'dashboard', label: '首页', desc: '设备 / 快捷入口 / 最近运行', icon: LayoutDashboard },
  // 2026-10-14（需求 #89）：两个方向折叠成「上报校验」一项，点开是两张卡片。
  { key: 'verify', label: '上报校验', desc: 'apptomg / mgtoapp 两个方向', icon: ListChecks },
  { key: 'perf', label: '性能测试', desc: 'M6 压测 / 异常时序', icon: Gauge },
  { key: 'apitest', label: '接口测试', desc: 'M7 接口用例 / 定时 / 批量', icon: Plug },
  { key: 'logcat', label: '上报查询', desc: '实时查看手机上报日志', icon: ScrollText },
  // 2026-10-04（需求 #45）：「测试点管理」不再是这里的一项 —— 它收进了 mgtoapp 页的弹窗。
]

export default function App() { return <NavigationGuardProvider><Application /></NavigationGuardProvider> }

function Application() {
  const guardNavigation = useGuardedNavigation()
  const internalNavigation = useRef(false)
  const currentHistoryIndex = useRef<number | undefined>(undefined)
  const pendingNavigation = useRef<string | null>(null)
  const [page, setPage] = useState<NavPage | 'dashboard'>(() => parsePanelLocation(window.location.hash).page)
  const [queryContext, setQueryContext] = useState<RoundQueryContext | null>(null)
  // 上报监听属于本次应用会话：首次打开后保留挂载，切页仅切换可见性。
  const [logcatOpened, setLogcatOpened] = useState(() => parsePanelLocation(window.location.hash).page === 'logcat')
  const [apitestOpened, setApitestOpened] = useState(() => parsePanelLocation(window.location.hash).page === 'apitest')
  const [location, setLocation] = useState(() => parsePanelLocation(window.location.hash))
  // 平台版本号：从后端 /api/meta 拉取（事实来源 pom.xml），拉不到就不显示，不写死默认值
  const [version, setVersion] = useState('')
  const [maintenanceOpen, setMaintenanceOpen] = useState(false)
  const { devices, selected, setSelected, refresh, refreshing: devicesRefreshing, error: devicesError, lastUpdated } = useDevices()
  const { theme, toggle } = useTheme()
  const currentLocation = useRef(location); currentLocation.current = location

  useEffect(() => {
    api
      .meta()
      .then((m) => setVersion(m.version))
      .catch(() => setVersion(''))
  }, [])

  useEffect(() => {
    currentHistoryIndex.current = ensurePanelHistory()
    const sync = () => {
      if (internalNavigation.current) return
      const next = parsePanelLocation(window.location.hash)
      const targetHash = panelHash(next)
      if (pendingNavigation.current === targetHash) return
      const destinationIndex = panelHistoryIndex()
      const prior = currentLocation.current, priorIndex = currentHistoryIndex.current
      const apply = () => {
        pendingNavigation.current = null
        currentLocation.current = next; currentHistoryIndex.current = destinationIndex
        setLocation(next); setPage(next.page)
        if (next.page === 'logcat') setLogcatOpened(true)
        if (next.page === 'apitest') setApitestOpened(true)
        if (next.page !== 'apitest') setQueryContext(null)
        internalNavigation.current = true; updatePanelLocation(next, true); internalNavigation.current = false
      }
      if (next.page === currentLocation.current.page) { apply(); return }
      pendingNavigation.current = targetHash
      guardNavigation(apply, () => {
        pendingNavigation.current = null
        if (priorIndex != null && destinationIndex != null && priorIndex !== destinationIndex) {
          window.history.go(priorIndex - destinationIndex)
        } else {
          internalNavigation.current = true; updatePanelLocation(prior, true); internalNavigation.current = false
        }
      })
    }
    window.addEventListener('hashchange', sync); window.addEventListener('popstate', sync); window.addEventListener('sudpanel:navigate', sync)
    return () => { window.removeEventListener('hashchange', sync); window.removeEventListener('popstate', sync); window.removeEventListener('sudpanel:navigate', sync) }
  }, [guardNavigation])

  // 落在子页（apptomg / mgtoapp）时回退到「上报校验」那一项：
  // 它是从那里进去的，标题栏跟着显示分组名比显示 undefined 更清楚，也避免 `!` 断言被击穿。
  const current = NAV.find((n) => n.key === page)
    ?? NAV.find((n) => n.key === 'verify')!
  const online = devices.filter((d) => d.status === 'device')
  const onlineCount = online.length

  const navigate = (p: NavPage | 'dashboard', selection: Omit<PanelLocation, 'page'> = {}) => {
    const apply = () => {
      const next = { page: p, ...selection }
      internalNavigation.current = true; updatePanelLocation(next); internalNavigation.current = false
      currentLocation.current = next; currentHistoryIndex.current = panelHistoryIndex()
      setLocation(next)
      if (p !== 'apitest') setQueryContext(null)
      if (p === 'logcat') setLogcatOpened(true)
      if (p === 'apitest') setApitestOpened(true)
      setPage(p)
    }
    if (p === page) apply(); else guardNavigation(apply)
  }


  return (
    <TooltipProvider>
      {/*
        外壳固定为视口高（2026-10-06，需求 #62）。
        组件默认是 `min-h-svh`（**最小**高度）—— 高度仍由内容决定，所以内容一多整条链就被撑长：
        实测日志区塞 400 行后 main 从 844px 涨到 6750px，日志区自己的滚动容器也一起被撑到 6416px，
        于是滚动发生在页面上、控制面板被顶出视口。
        这里在调用点补 `h-svh` 而不是改 components/ui/sidebar.tsx（那是模板文件，改了容易被重新生成覆盖），
        两行同时存在不冲突（height 与 min-height 是不同属性，值相同）。
        对其它页面无影响：main 的 overflow-auto 照旧兜住超长内容，只是现在它有了确定高度，反而更可靠。
      */}
      <SidebarProvider className="h-svh">
        <Sidebar collapsible="icon">
          <SidebarHeader className="gap-2 px-3 py-4">
            <div className="flex items-center gap-2.5 px-1">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary to-primary/80 text-primary-foreground shadow-md shadow-primary/20">
                <ScanLine className="h-4.5 w-4.5" />
              </div>
              <div className="min-w-0">
                <div className="truncate text-base font-bold leading-tight tracking-tight">SudPanel</div>
                <div className="truncate text-xs text-muted-foreground">状态上报测试平台</div>
                {version && (
                  <div className="mt-0.5 inline-flex items-center rounded-full bg-primary/10 border border-primary/20 px-1.5 py-0.5 font-mono text-xs leading-none text-primary font-medium">
                    v{version}
                  </div>
                )}
              </div>
            </div>
          </SidebarHeader>

          <SidebarContent>
            <SidebarGroup>
              <SidebarGroupLabel className="text-xs font-semibold tracking-wider text-muted-foreground/80">导航</SidebarGroupLabel>
              <SidebarGroupContent>
                <SidebarMenu>
                  {NAV.map((item) => (
                    <SidebarMenuItem key={item.key}>
                      <SidebarMenuButton
                        isActive={page === item.key
                          // 子页也算「上报校验」这一项在活动 —— 否则从卡片进去之后侧边栏会一个都不亮
                          || (item.key === 'verify' && (page === 'apptomg' || page === 'mgtoapp'))}
                        onClick={() => navigate(item.key)}
                        tooltip={item.label}
                        className="group/nav relative h-9 px-3 rounded-lg text-sm transition-all duration-150 hover:bg-accent/60 data-[active=true]:bg-primary/15 data-[active=true]:text-primary data-[active=true]:font-semibold data-[active=true]:shadow-xs"
                      >
                        <item.icon className="h-4 w-4 transition-transform duration-150 group-hover/nav:scale-110" />
                        <span>{item.label}</span>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>

            <SidebarGroup>
              <SidebarGroupLabel className="text-xs font-semibold tracking-wider text-muted-foreground/80">设备</SidebarGroupLabel>
              <SidebarGroupContent className="px-2">
                <Select value={selected ?? ''} onValueChange={(v) => setSelected(v || null)}>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="无在线设备" />
                  </SelectTrigger>
                  <SelectContent>
                    {online.length === 0 ? (
                      <div className="px-2 py-4 text-center text-xs text-muted-foreground">
                        无在线设备
                      </div>
                    ) : (
                      online.map((d) => (
                        <SelectItem key={d.serial} value={d.serial}>
                          {d.model || d.serial}
                        </SelectItem>
                      ))
                    )}
                  </SelectContent>
                </Select>
                <Button
                  variant="ghost"
                  size="sm"
                  className="mt-1 w-full justify-start gap-2 text-muted-foreground hover:text-foreground hover:bg-accent/50"
                  onClick={() => void refresh()}
                  aria-busy={devicesRefreshing}
                  title={devicesRefreshing ? '正在检查设备；再次点击将重新检查' : '立即检查设备'}
                >
                  <RefreshCw className={cn('h-3.5 w-3.5', devicesRefreshing && 'animate-spin')} />
                  刷新设备
                </Button>
                {devicesError && <p role="status" data-testid="sidebar-device-error" className="mt-1 break-words px-1 text-xs text-amber-700 dark:text-amber-400">设备查询失败：{devicesError}</p>}
                {!devicesError && deviceStatusHint(devices) && <p className="mt-1 px-1 text-xs text-muted-foreground">{deviceStatusHint(devices)}</p>}
                {lastUpdated != null && <p className="mt-1 px-1 text-xs text-muted-foreground">最近检查 {formatDeviceCheckedAt(lastUpdated)}</p>}
              </SidebarGroupContent>
            </SidebarGroup>
          </SidebarContent>

          <SidebarFooter className="px-3 py-3">
            <div className="flex items-center gap-2 rounded-xl border border-border/80 px-3 py-2 text-xs font-medium bg-card/60 backdrop-blur-md shadow-xs">
              <span
                className={cn(
                  'relative flex h-2 w-2',
                  // 有设备在线=绿色；0 台在线=红色（明确的异常态，比灰色更容易被注意到）
                  onlineCount > 0 ? 'text-emerald-500' : 'text-red-500',
                )}
              >
                {/* 呼吸动画只在"有设备在线"时才有意义（表示活跃）；离线态用静止红点 */}
                {onlineCount > 0 && (
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-60" />
                )}
                <span className="relative inline-flex h-2 w-2 rounded-full bg-current" />
              </span>
              <span className="text-muted-foreground">
                {devicesError ? '设备查询失败' : devicesRefreshing && lastUpdated == null ? '检查设备中' : onlineCount > 0 ? `${onlineCount} 台在线` : '无在线设备'}
              </span>
            </div>
          </SidebarFooter>
        </Sidebar>

        <SidebarInset>
          <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border/80 bg-background/80 px-4 backdrop-blur-md">
            <SidebarTrigger className="-ml-1" />
            <div className="flex min-w-0 items-baseline gap-2">
              <h1 className="truncate text-base font-bold tracking-tight">{current.label}</h1>
              <span className="hidden truncate text-xs text-muted-foreground/80 sm:inline">
                {current.desc}
              </span>
            </div>
            <div className="ml-auto flex items-center gap-2">
              <Button size="sm" variant="outline" className="border-border/80 hover:border-primary/40 hover:bg-accent/40" onClick={() => setMaintenanceOpen(true)}>数据维护</Button>
              <MaintenancePanel open={maintenanceOpen} onOpenChange={setMaintenanceOpen} />
              <DiagnosticPanel />
              {/* 在线状态徽标：常驻显示。>0 绿色（活跃），=0 红色（异常，需排查 USB/授权） */}
              <div
                className={cn(
                  'flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium shadow-xs transition-colors',
                  onlineCount > 0
                    ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                    : 'border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400',
                )}
              >
                <span
                  className={cn(
                    'relative flex h-2 w-2',
                    onlineCount > 0 ? 'text-emerald-500' : 'text-red-500',
                  )}
                >
                  {onlineCount > 0 && (
                    <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-60" />
                  )}
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-current" />
                </span>
                {devicesError ? '设备查询失败' : devicesRefreshing && lastUpdated == null ? '检查设备中' : onlineCount > 0 ? `${onlineCount} 台在线` : '无在线设备'}
              </div>
              {selected && (
                <div className="hidden items-center gap-1.5 rounded-full border border-border/80 bg-muted/30 px-3 py-1 text-xs font-medium text-foreground/90 md:flex">
                  <span className="font-mono text-xs">{selected}</span>
                </div>
              )}
              <Button variant="ghost" size="icon" onClick={toggle} title={theme === 'dark' ? '切换到浅色' : '切换到深色'}>
                {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
              </Button>
            </div>
          </header>
          {/*
            main 做 flex 容器（2026-10-06，需求 #62）：让页面能「吃掉剩余高度、自己内部滚动」。
            改之前是 block，页面里想拿剩余高度只能写 `h-full`（height:100%）——
            实测在这条父链上解析不出来（父高由 flex 拉伸而来），于是日志区一涨就把页面撑长、
            把控制面板顶出视口。
            `min-h-0` 不能省：flex 子项默认 `min-height:auto`，会被内容顶开，
            那就等于把要修的毛病换个地方重现。
            对其它页面无影响：它们的根是 flex item，宽度照旧撑满、高度照内容，`space-y-*` 照常生效。
          */}
          <main className="flex min-h-0 flex-1 flex-col overflow-auto p-4 md:p-6">
            {page === 'dashboard' && <PageSlot label="首页"><DashboardPage onNavigate={navigate} /></PageSlot>}
            {page === 'verify' && <PageSlot label="上报校验"><VerifyPage onNavigate={navigate} /></PageSlot>}
            {page === 'apptomg' && <PageSlot label="apptomg"><ApptomgPage /></PageSlot>}
            {page === 'mgtoapp' && <PageSlot label="mgtoapp"><MgtoappPage /></PageSlot>}
            {page === 'perf' && <PageSlot label="性能测试"><PerfPage initialRunId={location.runId} initialGameId={location.gameId} onQueryRound={context => { setQueryContext(context); navigate('apitest') }} /></PageSlot>}
            {apitestOpened && <PageVisibilityContext.Provider value={page === 'apitest'}><div hidden={page !== 'apitest'}><PageSlot label="接口测试" hidden={page !== 'apitest'}><ApitestPage queryContext={queryContext} initialBatchId={location.page === 'apitest' ? location.batchId : undefined} /></PageSlot></div></PageVisibilityContext.Provider>}
            {logcatOpened && <PageSlot label="上报查询" hidden={page !== 'logcat'}><LogcatPage active={page === 'logcat'} /></PageSlot>}
          </main>
        </SidebarInset>
      </SidebarProvider>
      <Toaster position="top-center" richColors />
    </TooltipProvider>
  )
}
