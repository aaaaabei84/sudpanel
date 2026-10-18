import { useEffect, useState } from 'react'
import { req } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'

type Event = { sequence: number; type: string; stage: string; gameOrdinal?: number; loopNo?: number; createdAt: string; reason: string; durationMs?: number }
type Timeline = { state: string; items: Event[]; total: number; truncated: boolean; limitReached: boolean }
type Conditions = { state: string; message: string; model?: string; appVersion?: string; romRelease?: string; sampleIntervalMs?: string; sampleSeconds?: string; mode?: string }
type Trend = { items: { id: number; pssAvgKb?: number; cpuAvg?: number; okLoops: number; comparable: boolean; differences: string[]; conditionsState: string }[]; excluded: number; message: string }
const TYPE: Record<string, string> = { START: '开始', RESUME: '手动续跑', STOP_REQUESTED: '请求停止', INTERRUPTED: '进程重启后中断', WAIT_STARTED: '进入等待', WAIT_PROBE: '只读探活', WAIT_FINISHED: '结束等待', RECOVERY_STARTED: '开始恢复', RECOVERY_ATTEMPT: '恢复尝试', RECOVERY_FINISHED: '恢复结束', FINALIZED: '收尾完成', CONDITIONS_CHANGED: '续跑条件变化' }
const REASON: Record<string, string> = { STARTED: '启动', USER_REQUEST: '用户操作', PROCESS_RESTART: '平台重启', DEVICE_DISCONNECTED: '设备断连', DEVICE_RECONNECTED: '设备重连', ENV_UNREACHABLE: '环境不可达', ENV_REACHABLE: '环境恢复', WAIT_TIMEOUT: '等待超限', STOPPED: '已停止', RECOVERED: '已恢复', RECOVERY_FAILED: '未能恢复', RECOVERY_REQUIRED: '需要恢复到列表', REQUEST_FAILED: '接口未通过', COMPLETED: '正常完成', ERROR: '异常收尾', CONDITIONS_DIFFER: '设备或应用条件变化', CONDITIONS_UNVERIFIED: '续跑条件未能核验' }
const FIELDS: Record<string, string> = { deviceIdentity: '设备身份', romIdentity: 'ROM构建', rosterIdentity: '游戏配置或名单来源', model: '型号', appVersion: '应用版本', appCode: '应用构建号', mode: '模式', sampleSeconds: '采样时长', sampleIntervalMs: '采样间隔', earlyTapDelayMs: '结束延迟', globalAiCount: '全局人机数', romRelease: '系统版本', romPatch: '安全补丁', loopCount: '计划圈数', trimBaseline: '回收基线', warmupLoops: '预热圈数' }
function TrendPlot({ items, onlyComparable }: { items: Trend['items']; onlyComparable: boolean }) {
  const points = [...items].filter(item => typeof item.pssAvgKb === 'number').sort((a, b) => a.id - b.id)
  if (!points.length) return null
  const values = points.map(item => item.pssAvgKb!), low = Math.min(...values), span = Math.max(1, Math.max(...values) - low)
  const xy = points.map((item, index) => ({ item, x: 24 + index * 552 / Math.max(1, points.length - 1), y: 136 - (item.pssAvgKb! - low) * 104 / span }))
  return <div><p className="text-muted-foreground">有效圈 PSS 均值，左旧右新。{onlyComparable ? '仅连同条件运行。' : '不同或未知条件只列点，不连接为同条件趋势。'}</p><svg viewBox="0 0 600 160" className="h-40 w-full" role="img" aria-label="跨运行PSS均值趋势"><path d="M24 24V140H580" fill="none" stroke="currentColor" opacity=".3" />{onlyComparable && <polyline points={xy.map(point => `${point.x},${point.y}`).join(' ')} fill="none" stroke="currentColor" strokeWidth="2" />}{xy.map(point => <circle key={point.item.id} cx={point.x} cy={point.y} r="4" fill={point.item.comparable ? 'currentColor' : '#d97706'}><title>运行 #{point.item.id} · {point.item.pssAvgKb} KiB · {point.item.comparable ? '可比' : '不可确认同条件'}</title></circle>)}</svg></div>
}
const metric = (value: unknown) => typeof value === 'number' ? Number(value.toFixed(2)) : '未知'

export function PerfRunContext({ runId, gameId }: { runId: number; gameId: number | null }) {
  const [open, setOpen] = useState(false), [onlyComparable, setOnlyComparable] = useState(true), [privateFiles, setPrivateFiles] = useState(false)
  const [data, setData] = useState<{ conditions: Conditions; timeline: Timeline; trend: Trend | null } | null>(null)
  const [error, setError] = useState(''), [retry, setRetry] = useState(0), [loading, setLoading] = useState(false)
  useEffect(() => {
    if (!open) return
    const controller = new AbortController(); let current = true
    setData(null); setError(''); setLoading(true); setPrivateFiles(false)
    void Promise.all([
      req<Conditions>(`/api/perf/runs/${runId}/conditions`, undefined, { signal: controller.signal }),
      req<Timeline>(`/api/perf/runs/${runId}/timeline`, undefined, { signal: controller.signal }),
      gameId == null ? Promise.resolve(null) : req<Trend>(`/api/perf/runs/${runId}/trend?gameId=${gameId}&comparableOnly=${onlyComparable}`, undefined, { signal: controller.signal }),
    ]).then(([conditions, timeline, trend]) => { if (current) setData({ conditions, timeline, trend }) })
      .catch(failure => { if (current) setError((failure as Error).message) }).finally(() => { if (current) setLoading(false) })
    return () => { current = false; controller.abort() }
  }, [open, runId, gameId, onlyComparable, retry])
  const zip = `/api/perf/runs/${runId}/evidence.zip${privateFiles ? '?includePrivateFiles=true&confirmation=PRIVATE_LOCAL_ONLY' : ''}`
  return <>
    <Button type="button" size="sm" variant="outline" data-testid="perf-context-open" onClick={() => setOpen(true)}>条件、恢复与证据</Button>
    <Dialog open={open} onOpenChange={setOpen}><DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-4xl" data-testid="perf-context-dialog">
      <DialogHeader><DialogTitle>运行条件、恢复时间线与证据包</DialogTitle></DialogHeader>
      <div className="space-y-4 overflow-y-auto text-xs">
        {loading && <p role="status">正在读取本运行的持久证据…</p>}
        {error && <p role="alert">{error}<Button size="sm" variant="ghost" onClick={() => setRetry(value => value + 1)}>重试读取</Button></p>}
        {data && <>
          <section data-testid="perf-conditions"><h3 className="font-medium">冻结可比条件 · {data.conditions.state}</h3><p>{data.conditions.message}</p>
            <p>型号 {data.conditions.model ?? '未知'} · 应用 {data.conditions.appVersion ?? '未知'} · ROM {data.conditions.romRelease ?? '未知'} · 模式 {data.conditions.mode ?? '未知'} · 时长 {data.conditions.sampleSeconds ?? '未知'} 秒 · 间隔 {data.conditions.sampleIntervalMs ?? '未知'} ms</p>
            <p className="text-muted-foreground">旧来源不按当前配置补造；设备、名单仅在本机作一致性比较，不显示真实身份。续跑条件混合不能当同条件趋势。</p>
          </section>
          <section data-testid="perf-timeline"><h3 className="font-medium">恢复时间线 · {data.timeline.total ?? 0} 条</h3>
            {data.timeline.state === 'NOT_RECORDED' && <p>旧运行没有时间线，不能据此认为未发生等待或恢复。</p>}
            {data.timeline.truncated && <p role="status">仅显示前 1000 条；完整备份仍保留其余事件。</p>}
            {data.timeline.limitReached && <p role="alert">事件达到 10000 条记录上限，后续恢复证据可能缺失。</p>}
            <p className="text-muted-foreground">停止不会自动复活；续跑必须手动确认。顺序以存储序号为准，时间为 UTC，耗时取单调时钟。</p>
            <div className="max-h-56 overflow-auto"><table className="w-full text-left"><thead><tr><th>时间</th><th>阶段/圈次</th><th>事件与原因</th><th>耗时</th></tr></thead><tbody>{(data.timeline.items ?? []).map(event => <tr key={event.sequence} className="border-t"><td>{event.createdAt}</td><td>{event.stage} · 游戏序号 {event.gameOrdinal ?? '—'} / 圈 {event.loopNo ?? '—'}</td><td>{TYPE[event.type] ?? event.type} · {REASON[event.reason] ?? event.reason}</td><td>{event.durationMs == null ? '未记录' : `${event.durationMs} ms`}</td></tr>)}</tbody></table></div>
          </section>
          <section data-testid="perf-comparable-trend"><h3 className="font-medium">同游戏跨运行趋势</h3><label className="flex items-center gap-2"><input type="checkbox" checked={onlyComparable} onChange={event => setOnlyComparable(event.target.checked)} aria-label="只看可比条件的运行" />只看可比条件的运行</label>
            {gameId == null ? <p>先选择游戏，再读取跨运行趋势。</p> : <><p>{data.trend?.message}</p><p>本页最多取最近 100 个有有效圈的运行；排除 {data.trend?.excluded ?? 0} 个未知或不同条件。</p>
              <TrendPlot items={data.trend?.items ?? []} onlyComparable={onlyComparable} />
              <table className="w-full text-left"><thead><tr><th>运行</th><th>有效圈</th><th>PSS 均值 KiB</th><th>CPU 单核 %</th><th>可比性/差异</th></tr></thead><tbody>{(data.trend?.items ?? []).map(item => <tr key={item.id} className="border-t"><td>#{item.id}</td><td>{item.okLoops}</td><td>{metric(item.pssAvgKb)}</td><td>{metric(item.cpuAvg)}</td><td>{item.comparable ? '条件一致' : `${item.conditionsState} · ${item.differences.map(key => FIELDS[key] ?? key).join('、') || '来源未知'}`}</td></tr>)}</tbody></table>
              {!data.trend?.items?.length && <p>没有可确认同条件的运行；不会把未知条件当一致。</p>}</>}
          </section>
        </>}
        <section className="space-y-2 rounded border p-3"><h3 className="font-medium">统一证据 ZIP</h3><p>默认包含清单、圈摘要、时间线、有限脱敏日志、报文摘要与 SHA256；截图仅记哈希和缺失/排除原因，不含原始报文或业务身份。</p>
          <label className="flex items-center gap-2"><input type="checkbox" checked={privateFiles} onChange={event => setPrivateFiles(event.target.checked)} aria-label="包含私密原始截图和日志" />包含原始截图与有限日志（私密，仅本机）</label>
          {privateFiles && <p role="alert">此包可能含账号、身份和凭据，不是脱敏分享包。仅在本机下载，转交前须人工检查。</p>}
          <a href={zip} className="text-primary underline" data-testid="perf-evidence-package">{privateFiles ? '下载私密本机证据包' : '下载默认脱敏证据包'}</a>
        </section>
      </div>
    </DialogContent></Dialog>
  </>
}

type Layout = { state: string; message: string; current?: { model?: string; width?: number; height?: number; rotation?: number } }
export function PerfCoordinateProfile({ device, groupId, mode, disabled }: { device: string; groupId: number | null; mode: string; disabled: boolean }) {
  const [open, setOpen] = useState(false), [data, setData] = useState<Layout | null>(null), [error, setError] = useState(''), [confirmed, setConfirmed] = useState(false), [busy, setBusy] = useState(false), [reload, setReload] = useState(0)
  useEffect(() => {
    if (!open) return
    let current = true; const controller = new AbortController(); setData(null); setConfirmed(false); setError('')
    void req<Layout>(`/api/perf/coordinate-profile?deviceSerial=${encodeURIComponent(device)}${groupId == null ? '' : `&groupId=${groupId}`}`, undefined, { signal: controller.signal }).then(value => { if (current) setData(value) }).catch(failure => { if (current) setError((failure as Error).message) })
    return () => { current = false; controller.abort() }
  }, [open, device, groupId, reload])
  const bind = async () => {
    if (!confirmed || busy) return
    setBusy(true); setError('')
    try { await req('/api/perf/coordinate-profile/fixed', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceSerial: device, confirmed: true }) }); setConfirmed(false); setReload(value => value + 1) }
    catch (failure) { setError((failure as Error).message) } finally { setBusy(false) }
  }
  return <><Button type="button" size="sm" variant="outline" disabled={disabled || !device} onClick={() => setOpen(true)} data-testid="perf-coordinate-profile-open">坐标布局来源</Button>
    <Dialog open={open} onOpenChange={value => { if (!busy) setOpen(value) }}><DialogContent className="sm:max-w-xl" data-testid="perf-coordinate-profile-dialog" showCloseButton={!busy}><DialogHeader><DialogTitle>坐标布局来源</DialogTitle></DialogHeader>
      <p className="text-xs">{mode === 'EARLY_SETTLE' ? '异常时序使用前会再次核对型号、有效尺寸与旋转；来源未知或失配禁止使用旧坐标。' : '当前模式由接口驱动，不增加坐标标定启动门槛；大厅优先使用当前控件树。'}</p>
      {error && <p role="alert">{error}</p>}{data && <p className="text-xs">{data.state} · {data.message}<br />当前：{data.current?.model ?? '未知型号'} / {data.current?.width ?? '?'} × {data.current?.height ?? '?'} / 旋转 {data.current?.rotation ?? '?'}</p>}
      <p className="text-xs">分组旧布局需重新标定，不能通过确认固定坐标代替。以下仅认可本机固定坐标配置。</p>
      <label className="flex items-center gap-2 text-xs"><input type="checkbox" disabled={busy || !data} checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />我已在当前设备、尺寸与方向现场核对固定坐标</label>
      <Button type="button" disabled={!confirmed || busy || !data} onClick={() => void bind()} data-testid="perf-coordinate-bind">{busy ? '保存中…' : '确认固定坐标来源'}</Button>
    </DialogContent></Dialog></>
}
