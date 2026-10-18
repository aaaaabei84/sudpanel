import type { PanelLocation } from '@/lib/navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowRight, Gamepad2, Gauge, ListChecks, Plug, ScrollText, Smartphone } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { EntryCard } from '@/components/entry-card'
import { Skeleton } from '@/components/ui/skeleton'
import { api, perfApi, apitestApi, req } from '@/lib/api'
import { useDevices } from '@/lib/device-context'
import { deviceStatusHint, deviceStatusLabel, formatDeviceCheckedAt } from '@/lib/device-status'
import { cn } from '@/lib/utils'
import type { PerfRun, RunListItem, ApiBatch } from '@/types'

// 2026-10-04（需求 #45）：'manage' 已移除 —— 「测试点管理」不再是独立页面，
// 它收进了 mgtoapp 页的「测试点管理」弹窗。这里少一项，侧边栏（App.tsx 的 NAV）也要同步少一项。
export type NavPage = 'verify' | 'apptomg' | 'mgtoapp' | 'perf' | 'apitest' | 'logcat'

const ENTRIES: { page: NavPage; label: string; desc: string; icon: typeof Gamepad2 }[] = [
  // 2026-10-14（需求 #89）：apptomg / mgtoapp 折叠成一项「上报校验」——
  // 它们是同一个校验的两个方向，各占一个入口只会让侧边栏更长、还得每次先分辨方向。
  { page: 'verify', label: '上报校验', desc: 'apptomg / mgtoapp：选方向后进入各自的测试页', icon: ListChecks },
  { page: 'perf', label: '性能测试', desc: '压测 / 异常时序，采样与判定', icon: Gauge },
  { page: 'apitest', label: '接口测试', desc: '接口用例、返回码释义、定时与批量', icon: Plug },
  { page: 'logcat', label: '上报查询', desc: '实时查看手机上报日志，可按关键字与级别筛', icon: ScrollText },
]

function fmtTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function DashboardPage({ onNavigate }: { onNavigate: (page: NavPage, selection?: Omit<PanelLocation, 'page'>) => void }) {
  const { devices, selected, refresh, refreshing: devicesRefreshing, error: devicesError, lastUpdated } = useDevices()
  const [funcRuns, setFuncRuns] = useState<RunListItem[] | null>(null)
  const [perfRuns, setPerfRuns] = useState<PerfRun[] | null>(null)

  const [apiRuns, setApiRuns] = useState<ApiBatch[] | null>(null)
  const [apiActiveCount, setApiActiveCount] = useState(0)
  const [apiLoading, setApiLoading] = useState(false)
  const apiInFlight = useRef(false)
  const [apiError, setApiError] = useState<string | null>(null)
  const apiRequest = useRef(0)
  const loadApi = useCallback(async () => {
    if (apiInFlight.current) return
    apiInFlight.current = true; setApiLoading(true)
    const seq = ++apiRequest.current; setApiError(null)
    try {
      const [recent, allActive] = await Promise.all([apitestApi.batches(), req<ApiBatch[]>('/api/apitest/batches?active=true')])
      if (seq === apiRequest.current) {
        const active = allActive.filter(run => run.status === 'RUNNING')
        setApiRuns([...new Map([...active, ...recent].map(run => [run.id, run])).values()].slice(0, 6)); setApiActiveCount(active.length)
      }
    }
    catch (error) { if (seq === apiRequest.current) setApiError(error instanceof Error ? error.message : String(error)) }
    finally { if (seq === apiRequest.current) { apiInFlight.current = false; setApiLoading(false) } }
  }, [])

  const [funcError, setFuncError] = useState<string | null>(null)
  const [perfError, setPerfError] = useState<string | null>(null)
  const funcRequest = useRef(0)
  const perfRequest = useRef(0)
  const loadFunc = useCallback(async () => {
    const seq = ++funcRequest.current
    setFuncError(null)
    try {
      const rows = await api.runs()
      if (seq === funcRequest.current) setFuncRuns(rows.slice(0, 6))
    } catch (error) {
      if (seq === funcRequest.current) setFuncError(error instanceof Error ? error.message : String(error))
    }
  }, [])
  const loadPerf = useCallback(async () => {
    const seq = ++perfRequest.current
    setPerfError(null)
    try {
      const rows = await perfApi.runs()
      if (seq === perfRequest.current) setPerfRuns(rows.slice(0, 6))
    } catch (error) {
      if (seq === perfRequest.current) setPerfError(error instanceof Error ? error.message : String(error))
    }
  }, [])

  useEffect(() => {
    void Promise.allSettled([loadFunc(), loadPerf(), loadApi()])
    return () => { ++funcRequest.current; ++perfRequest.current; ++apiRequest.current; apiInFlight.current = false }
  }, [loadFunc, loadPerf, loadApi])

  const online = devices.filter((d) => d.status === 'device')

  return (
    <div className="space-y-6">
      {/* 设备状态 */}
      <Card className="border-border/80 bg-card/75 backdrop-blur-xl shadow-md">
        <CardHeader className="flex-row items-center justify-between space-y-0 pb-3">
          <CardTitle className="flex items-center gap-2.5 text-base font-semibold">
            <Smartphone className="h-4.5 w-4.5 text-primary" />
            设备状态
          </CardTitle>
          <Button size="sm" variant="outline" className="h-8 text-xs font-medium border-border/80 hover:border-primary/40 hover:bg-accent/40" aria-busy={devicesRefreshing} onClick={() => void refresh()}>
            刷新设备
          </Button>
        </CardHeader>
        {(devicesError || deviceStatusHint(devices) || devicesRefreshing || lastUpdated != null) && <CardContent className="space-y-1 pb-3 pt-0 text-xs">
          {devicesError ? <p role="status" data-testid="dashboard-device-error" className="text-amber-700 dark:text-amber-400">设备查询失败：{devicesError}。请检查 ADB 和 USB 连接后重试。</p> : <p data-testid="dashboard-device-hint" className="text-muted-foreground">{deviceStatusHint(devices) || (devicesRefreshing ? '正在检查设备…' : '设备连接正常')}</p>}
          {lastUpdated != null && <p className="text-muted-foreground">最近检查 {formatDeviceCheckedAt(lastUpdated)} · 页面可见时每 5 秒检查</p>}
        </CardContent>}
        <CardContent className="grid gap-4 md:grid-cols-3">
          <div className="flex items-center gap-3.5 rounded-xl border border-border/70 bg-card/50 backdrop-blur-md p-3.5 shadow-xs transition-all duration-200 hover:border-emerald-500/40 hover:bg-card/80 hover:shadow-sm">
            <div
              className={cn(
                'flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors',
                // >0 绿色（在线正常）；=0 红色（异常态：无设备可测，需查 USB 调试/授权）
                online.length > 0 ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/20' : 'bg-red-500/15 text-red-400 border border-red-500/20',
              )}
            >
              <span className="relative flex h-3 w-3">
                {online.length > 0 && (
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-500 opacity-60" />
                )}
                <span
                  className={cn(
                    'relative inline-flex h-3 w-3 rounded-full',
                    online.length > 0 ? 'bg-emerald-500' : 'bg-red-500',
                  )}
                />
              </span>
            </div>
            <div>
              <div className="text-3xl font-bold tracking-tight leading-none">{online.length}</div>
              <div className="mt-1.5 text-xs font-medium text-muted-foreground">
                {online.length > 0 ? '在线设备' : '无在线设备'}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3.5 rounded-xl border border-border/70 bg-card/60 p-3.5 shadow-xs transition-all duration-200 hover:border-primary/40 hover:bg-card/90 hover:shadow-sm">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-muted/60 text-muted-foreground border border-border/60">
              <Smartphone className="h-5 w-5" />
            </div>
            <div>
              <div className="text-3xl font-bold tracking-tight leading-none">{devices.length}</div>
              <div className="mt-1.5 text-xs font-medium text-muted-foreground">已发现设备</div>
            </div>
          </div>
          <div className="flex items-center gap-3.5 rounded-xl border border-border/70 bg-card/60 p-3.5 shadow-xs transition-all duration-200 hover:border-primary/40 hover:bg-card/90 hover:shadow-sm">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary border border-primary/20">
              <Gamepad2 className="h-5 w-5" />
            </div>
            <div className="min-w-0">
              <div className="truncate font-mono text-sm font-semibold leading-none">
                {selected ?? '—'}
              </div>
              <div className="mt-1.5 text-xs font-medium text-muted-foreground">当前选中设备</div>
            </div>
          </div>
        </CardContent>
        {devices.length > 0 && (
          <CardContent className="pt-0">
            <div className="flex flex-wrap gap-2">
              {devices.map((d) => (
                <Badge
                  key={d.serial}
                  variant="outline"
                  className={cn(
                    'font-mono text-xs px-2.5 py-1 rounded-lg transition-all',
                    d.status === 'device'
                      ? 'border-emerald-500/40 bg-emerald-500/5 text-emerald-600 dark:text-emerald-400'
                      : 'border-border/80 text-muted-foreground',
                  )}
                >
                  <span
                    className={cn(
                      'mr-2 inline-block h-2 w-2 rounded-full',
                      d.status === 'device' ? 'bg-emerald-500 shadow-xs shadow-emerald-500/50' : 'bg-muted-foreground/50',
                    )}
                  />
                  {d.model || d.serial}
                  {d.status !== 'device' && <span className="ml-1 text-xs opacity-70">({deviceStatusLabel(d.status)})</span>}
                </Badge>
              ))}
            </div>
          </CardContent>
        )}
        {devices.length === 0 && !devicesError && !devicesRefreshing && (
          <CardContent className="pt-0">
            <div className="rounded-xl border border-dashed border-border/80 p-5 text-sm text-muted-foreground text-center">
              未检测到设备：确认手机已开启 USB 调试并授权，然后点「刷新设备」。
            </div>
          </CardContent>
        )}
      </Card>

      {/* 快捷入口：5 个入口，宽屏一行放下，窄屏自动折行 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
        {ENTRIES.map((e) => (
          <EntryCard
            key={e.page}
            icon={e.icon}
            title={e.label}
            desc={e.desc}
            onClick={() => onNavigate(e.page)}
          />
        ))}
      </div>

      {/* 最近运行 */}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="lg:col-span-2 border-border/80 bg-card/75 backdrop-blur-xl shadow-md">
          <CardHeader className="flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-semibold">最近接口测试批次{apiActiveCount > 0 ? ` · ${apiActiveCount} 个运行中` : ''}</CardTitle>
            <div className="flex items-center gap-2">
              <Button size="sm" variant="outline" disabled={apiLoading} onClick={() => void loadApi()}>刷新接口记录</Button>
              <Button size="sm" variant="ghost" onClick={() => onNavigate('apitest')}>查看全部</Button>
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            {apiError && <div role="status" data-testid="dashboard-api-error" className="text-xs text-amber-700 dark:text-amber-400">接口批次加载失败：{apiError}{apiRuns ? '（保留上次结果）' : ''}<Button size="sm" variant="outline" onClick={() => void loadApi()}>重试接口记录</Button></div>}
            {apiRuns == null ? !apiError && <Skeleton className="h-12 w-full" /> : apiRuns.length === 0 ? <p className="text-sm text-muted-foreground">暂无接口批次</p> : apiRuns.map(run => (
              <button
                type="button"
                key={run.id}
                onClick={() => onNavigate('apitest', { batchId: run.id })}
                className="flex w-full flex-wrap items-center gap-2.5 rounded-xl border border-border/70 bg-card/40 backdrop-blur-md px-3.5 py-2.5 text-left text-sm hover:border-primary/40 hover:bg-card/70 transition-all"
              >
                <span className="font-mono text-xs font-medium text-muted-foreground">#{run.id}</span>
                <span className="min-w-0 flex-1 truncate font-medium">{run.name}</span>
                <span className="text-xs text-muted-foreground">{fmtTime(run.startedAt)} · 通过 {run.passCount} / 失败 {run.failCount} / 错误 {run.errorCount}</span>
                <Badge variant="outline" className="text-xs">{run.status}</Badge>
                <ArrowRight className="size-3.5 text-muted-foreground" />
              </button>
            ))}
          </CardContent>
        </Card>

        <Card className="border-border/80 bg-card/75 backdrop-blur-xl shadow-md">
          <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-semibold">最近功能测试运行</CardTitle>
            <Button
              size="sm"
              variant="ghost"
              className="text-xs text-muted-foreground"
              onClick={() => onNavigate(funcRuns?.some((r) => r.direction === 'MGTOAPP') ? 'mgtoapp' : 'apptomg')}
            >
              查看全部 <ArrowRight className="ml-1 h-3.5 w-3.5" />
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {funcError && <div role="status" data-testid="dashboard-functional-error" className="space-y-2 text-xs text-amber-700 dark:text-amber-400"><p>功能测试记录加载失败：{funcError}{funcRuns != null ? '（保留上次结果）' : ''}</p><Button size="sm" variant="outline" onClick={() => void loadFunc()}>重试功能记录</Button></div>}
            {funcRuns == null ? funcError ? null : (
              <div className="space-y-2">
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-full" />
              </div>
            ) : funcRuns.length === 0 ? (
              <div className="py-4 text-center text-sm text-muted-foreground">暂无运行记录</div>
            ) : (
              funcRuns.map((r) => (
                <div
                  key={r.id}
                  className="flex items-center gap-2 rounded-xl border border-border/70 bg-card/40 backdrop-blur-md px-3.5 py-2 text-sm hover:border-primary/30 transition-colors"
                >
                  <span className="font-mono text-xs font-medium text-muted-foreground">#{r.id}</span>
                  <Badge variant="outline" className="font-mono text-xs px-2 py-0.5">
                    {r.direction}
                  </Badge>
                  <span className="text-xs text-muted-foreground">{fmtTime(r.startTime)}</span>
                  <span className="text-xs text-muted-foreground">
                    {r.selectedPointIds?.length ?? 0} 点
                  </span>
                  <Badge
                    variant="secondary"
                    className={cn(
                      'ml-auto font-mono text-xs px-2 py-0.5',
                      r.status === 'RUNNING' && 'text-primary font-medium',
                    )}
                  >
                    {r.status}
                  </Badge>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card className="border-border/80 bg-card/75 backdrop-blur-xl shadow-md">
          <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-semibold">最近性能测试运行</CardTitle>
            <Button
              size="sm"
              variant="ghost"
              className="text-xs text-muted-foreground"
              onClick={() => onNavigate('perf')}
            >
              查看全部 <ArrowRight className="ml-1 h-3.5 w-3.5" />
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {perfError && <div role="status" data-testid="dashboard-performance-error" className="space-y-2 text-xs text-amber-700 dark:text-amber-400"><p>性能测试记录加载失败：{perfError}{perfRuns != null ? '（保留上次结果）' : ''}</p><Button size="sm" variant="outline" onClick={() => void loadPerf()}>重试性能记录</Button></div>}
            {perfRuns == null ? perfError ? null : (
              <div className="space-y-2">
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-full" />
              </div>
            ) : perfRuns.length === 0 ? (
              <div className="py-4 text-center text-sm text-muted-foreground">暂无运行记录</div>
            ) : (
              perfRuns.map((r) => {
                const names = r.gameNames ?? []
                const label =
                  names.length === 0
                    ? `#${r.id}`
                    : names.length <= 2
                      ? names.join('、')
                      : `${names.slice(0, 2).join('、')} 等${names.length}个`
                return (
                  <div
                    key={r.id}
                    className="flex items-center gap-2 rounded-lg border border-border/70 px-3 py-2 text-sm hover:border-primary/30 transition-colors"
                  >
                    <span className="font-mono text-xs font-medium text-muted-foreground">#{r.id}</span>
                    <span className="min-w-0 flex-1 truncate font-medium">{label}</span>
                    <span className="text-xs text-muted-foreground">{fmtTime(r.startTime)}</span>
                    <Badge variant="secondary" className="font-mono text-xs px-2 py-0.5">
                      {r.mode === 'EARLY_SETTLE' ? '时序' : '压测'} · {r.status}
                    </Badge>
                  </div>
                )
              })
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
