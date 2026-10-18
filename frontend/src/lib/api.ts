import { RequestError, recordDiagnostic } from '@/lib/diagnostics'
import { responseHeaderMap } from '@/lib/apitest-workspace'
// 后端 REST API 客户端
import type { RoundQueryContext } from '@/lib/apitest-workspace'
import type {
  ApiEnv,
  ApiPrepared,
  ApiCapabilities,
  ApiResponseInspection,
  ApiScenario,
  AiRoomConfig,
  ApiBatch,
  ApiBatchDetail,
  ApiCase,
  ApiCaseGroup,
  ApiCode,
  ApiExec,
  ApiSignSetting,
  ApiSignSecret,
  ApiVarItem,
  ApiVarSet,
  ApptomgCoordSource,
  Device,
  Direction,
  LogcatKeyword,
  LogcatTail,
  PerfButtonGroup,
  PerfCalibrateResult,
  PerfOptions,
  PerfCheck,
  PerfGame,
  PerfLoop,
  PerfMode,
  PerfProgress,
  PerfRun,
  PerfSample,
  PerfStartResponse,
  RunListItem,
  RunResults,
  RunSummary,
  StartRunResponse,
  TestConfig,
  TestPoint,
  TriggerResponse,
  WalkCurrent,
} from '@/types'

export interface ApiRequestControl {
  signal?: AbortSignal
  timeoutMs?: number
}

/** 读取可安全重试；控制请求只限制等待时间，绝不自动重发。 */
export async function req<T>(url: string, init?: RequestInit, control: ApiRequestControl = {}): Promise<T> {
  const controller = new AbortController()
  const external = control.signal ?? init?.signal
  const timeoutMs = control.timeoutMs ?? ((init?.method ?? 'GET') === 'GET' ? 30000 : 60000)
  let timedOut = false
  const abort = () => controller.abort(external?.reason)
  if (external?.aborted) abort()
  else external?.addEventListener('abort', abort, { once: true })
  const timer = timeoutMs > 0 ? window.setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs) : null
  try {
    const res = await fetch(url, { ...init, signal: controller.signal })
    if (!res.ok) {
      let msg = `HTTP ${res.status}`
      let requestId: string | null = res.headers.get('X-Request-Id')
      try {
        const body = await res.json()
        if (body?.message) msg = body.message
        if (typeof body?.requestId === 'string') requestId = body.requestId
      } catch {
        // 代理或网络错误可能返回非JSON正文。
      }
      if (!requestId || !/^[A-Za-z0-9-]{8,100}$/.test(requestId)) requestId = null
      if (res.status >= 500 || requestId) recordDiagnostic({ id: requestId ?? crypto.randomUUID(), kind: 'HTTP', status: res.status, method: init?.method ?? 'GET' })
      throw new RequestError(msg, requestId, res.status)
    }
    if (res.status === 204 || res.headers.get('content-length') === '0') return undefined as T
    const text = await res.text()
    return text ? JSON.parse(text) as T : undefined as T
  } catch (error) {
    if (timedOut) throw new Error('等待响应超时；后台操作可能仍在进行，请先查看任务状态再决定是否重试')
    if (!(error instanceof RequestError) && !timedOut && !(error instanceof DOMException && error.name === 'AbortError')) recordDiagnostic({ id: crypto.randomUUID(), kind: 'NETWORK', method: init?.method ?? 'GET' })
    throw error
  } finally {
    if (timer != null) window.clearTimeout(timer)
    external?.removeEventListener('abort', abort)
  }
}

function jsonInit(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

export const api = {
  /** 平台元信息（名称 + 版本号）。版本号由后端从 pom.xml 注入，前端不写死。 */
  meta: () => req<{ name: string; version: string }>('/api/meta'),

  devices: (signal?: AbortSignal) => req<Device[]>('/api/devices', undefined, { signal }),

  /**
   * 上报查询：取当前设备 logcat 的增量（需求 #60）。
   *
   * <p>把本地缓冲的最后一行原样带回去当锚点，后端只返回它之后的新行。
   * 锚点失效时后端会回 `reset=true` 并给出整段，调用方必须**替换**缓冲。
   */
  logcatTail: (serial: string, anchor: string | null) =>
    req<LogcatTail>('/api/logcat/tail', jsonInit('POST', { serial, anchor })),

  /** 上报查询：清空设备的 logcat 缓冲。 */
  logcatClear: (serial: string) =>
    req<{ ok: boolean; message?: string }>('/api/logcat/clear', jsonInit('POST', { serial })),

  /** 上报查询：列出保存过的过滤关键字（需求 #66）。 */
  logcatKeywords: () => req<LogcatKeyword[]>('/api/logcat/keywords'),

  /**
   * 上报查询：保存一个过滤关键字（需求 #66）。
   *
   * <p>同一个关键字再存一次是**更新它的注释**，不是新增一条 —— 后端用唯一索引 + upsert 保证，
   * 前端不用自己去重（自己去重就得先有一份完整列表，而那份列表随时可能是旧的）。
   *
   * <p>成功时后端把**整份列表**一起回传，所以这里直接拿 `items` 刷新下拉，不必再发一次 GET。
   */
  logcatKeywordSave: (keyword: string, note: string) =>
    req<{ ok: boolean; message?: string; item?: LogcatKeyword; items: LogcatKeyword[] }>(
      '/api/logcat/keywords',
      jsonInit('POST', { keyword, note }),
    ),

  /** 上报查询：删除一个保存过的关键字。后端同样把刷新后的整份列表带回来。 */
  logcatKeywordDelete: (id: number) =>
    req<{ ok: boolean; message?: string; items: LogcatKeyword[] }>(
      `/api/logcat/keywords/${id}`,
      { method: 'DELETE' },
    ),

  points: (direction?: Direction, all = false) => {
    const params = new URLSearchParams()
    if (direction) params.set('direction', direction.toLowerCase())
    if (all) params.set('all', 'true')
    const qs = params.toString()
    return req<TestPoint[]>(`/api/points${qs ? `?${qs}` : ''}`)
  },

  /** mgtoapp 新增测试点：group + state + 备注 + 测试点名称（其余字段服务端固定）。 */
  createMgPoint: (group: string, state: string, note: string, shortName: string) =>
    req<TestPoint>('/api/points/mgtoapp', jsonInit('POST', { group, state, note, shortName })),
  updateMgPoint: (id: number, group: string, state: string, note: string, shortName: string) =>
    req<void>(`/api/points/mgtoapp/${id}`, jsonInit('PUT', { group, state, note, shortName })),
  deletePoint: (id: number) => req<void>(`/api/points/${id}`, { method: 'DELETE' }),

  configs: (direction: Direction) => req<TestConfig[]>(`/api/configs?direction=${direction.toLowerCase()}`),
  createConfig: (direction: Direction, name: string, pointIds: number[]) =>
    req<TestConfig>('/api/configs', jsonInit('POST', { direction: direction.toLowerCase(), name, pointIds })),
  updateConfig: (id: number, name: string, pointIds: number[]) =>
    req<void>(`/api/configs/${id}`, jsonInit('PUT', { name, pointIds })),
  deleteConfig: (id: number) => req<void>(`/api/configs/${id}`, { method: 'DELETE' }),

  /** apptomg 坐标来源映射（含别名），用于「坐标选择」区展示分组能覆盖哪几个测试点 */
  apptomgCoordSources: (groupId?: number) =>
    req<ApptomgCoordSource[]>(
      `/api/points/apptomg-coord-sources${groupId != null ? `?groupId=${groupId}` : ''}`,
    ),

  startRun: (direction: Direction, deviceSerial: string, pointIds?: number[], buttonGroupId?: number) =>
    req<StartRunResponse>(
      '/api/runs',
      jsonInit('POST', { direction: direction.toLowerCase(), deviceSerial, pointIds, buttonGroupId }),
    ),
  runs: () => req<RunListItem[]>('/api/runs'),
  runSummary: (id: number) => req<RunSummary>(`/api/runs/${id}`),
  runResults: (id: number) => req<RunResults>(`/api/runs/${id}/results`),
  runCurrent: (id: number) => req<WalkCurrent>(`/api/runs/${id}/current`),
  trigger: (id: number) => req<TriggerResponse>(`/api/runs/${id}/current/trigger`, { method: 'POST' }),
  skip: (id: number) => req<void>(`/api/runs/${id}/current/skip`, { method: 'POST' }),
  judge: (id: number, result: string, note: string) =>
    req<void>(`/api/runs/${id}/current/judge`, jsonInit('POST', { result, note })),
  stop: (id: number) => req<void>(`/api/runs/${id}/stop`, { method: 'POST' }),
}

export function reportUrl(runId: number): string {
  return `/api/runs/${runId}/report`
}

export function exportUrl(runId: number): string {
  return `/api/runs/${runId}/export`
}

// ---------- M6 性能测试 ----------

export const perfApi = {
  scanGames: (deviceSerial: string) =>
    req<PerfGame[]>('/api/perf/games/scan', jsonInit('POST', { deviceSerial }), { timeoutMs: 180000 }),
  games: () => req<PerfGame[]>('/api/perf/games'),
  deleteGame: (id: number) => req<void>(`/api/perf/games/${id}`, { method: 'DELETE' }),
  deleteGames: (ids: number[]) => req<void>('/api/perf/games', jsonInit('DELETE', { ids })),
  reorderGames: (ids: number[]) => req<void>('/api/perf/games/order', jsonInit('PUT', { ids })),
  moveGame: (id: number, dir: 'up' | 'down') =>
    req<void>(`/api/perf/games/${id}/move`, jsonInit('POST', { dir })),
  // ---- 人机对局（需求 #68；#69/#70 扩推送配置）----
  /** 设置某游戏的推送配置（八项）。null = 没配退全局；空串 = 清掉。 */
  updateGameIds: (
    id: number,
    mgId: string,
    appId: string,
    roomId: string,
    humanCount: number | null,
    aiCount: number | null,
    aiLevel: number | null,
    gameSeconds: number | null,
    machineUid: string,
    bodyOverride: string,
  ) =>
    req<{ ok: boolean }>(`/api/perf/games/${id}/ids`,
      jsonInit('PUT', { mgId, appId, roomId, humanCount, aiCount, aiLevel, gameSeconds, machineUid, bodyOverride })),
  /** 读人机对局配置（四条用例绑定 + 人机数量）。 */
  aiRoomConfig: () => req<AiRoomConfig>('/api/perf/ai-room'),
  /** 写人机对局配置。 */
  saveAiRoomConfig: (cfg: AiRoomConfig) =>
    req<{ ok: boolean }>('/api/perf/ai-room', jsonInit('PUT', cfg)),
  /** 读性能测试通用选项（v1.10.0 #84；三个模式都适用）。 */
  perfOptions: () => req<PerfOptions>('/api/perf/options'),
  /** 写性能测试通用选项。 */
  savePerfOptions: (o: PerfOptions) =>
    req<{ ok: boolean }>('/api/perf/options', jsonInit('PUT', o)),
  // ---- 按钮坐标分组（v1.2 #4）----
  buttonGroups: () => req<PerfButtonGroup[]>('/api/perf/button-groups'),
  createButtonGroup: (name: string, deviceSerial?: string) =>
    req<PerfButtonGroup>('/api/perf/button-groups', jsonInit('POST', { name, deviceSerial })),
  deleteButtonGroup: (id: number) =>
    req<void>(`/api/perf/button-groups/${id}`, { method: 'DELETE' }),
  // 传 key 只标该按钮；不传则扫全屏标定全部
  calibrateButton: (groupId: number, deviceSerial: string, key?: string) =>
    req<PerfCalibrateResult>(
      `/api/perf/button-groups/${groupId}/calibrate`,
      jsonInit('POST', { deviceSerial, key }),
      { timeoutMs: 180000 },
    ),
  start: (
    deviceSerial: string,
    mode: PerfMode,
    gameIds: number[],
    loopCount?: number,
    sampleSeconds?: number,
    earlyTapDelayMs?: number,
    buttonGroupId?: number,
  ) =>
    req<PerfStartResponse>(
      '/api/perf/runs',
      jsonInit('POST', { deviceSerial, mode, gameIds, loopCount, sampleSeconds, earlyTapDelayMs, buttonGroupId }),
    ),
  stop: (id: number) => req<void>(`/api/perf/runs/${id}/stop`, { method: 'POST' }),
  resume: (id: number) => req<PerfStartResponse>(`/api/perf/runs/${id}/resume`, { method: 'POST' }),
  rejudge: (id: number) => req<{ ok: boolean; games: number; checks: number }>(`/api/perf/runs/${id}/rejudge`, { method: 'POST' }),
  deleteRun: (id: number) => req<void>(`/api/perf/runs/${id}`, { method: 'DELETE' }),
  progress: (id: number) => req<PerfProgress>(`/api/perf/runs/${id}/progress`),
  runs: () => req<PerfRun[]>('/api/perf/runs'),
  run: (id: number) => req<PerfRun>(`/api/perf/runs/${id}`),
  loops: (runId: number, gameId: number) =>
    req<PerfLoop[]>(`/api/perf/runs/${runId}/games/${gameId}/loops`),
  samples: (loopId: number) => req<PerfSample[]>(`/api/perf/loops/${loopId}/samples`),
  growthCurves: (runId: number, gameId: number) =>
    req<import('@/types').PerfGrowthCurves>(`/api/perf/runs/${runId}/games/${gameId}/growth-curves`),
  checks: (runId: number, gameId: number) =>
    req<PerfCheck[]>(`/api/perf/runs/${runId}/games/${gameId}/checks`),
  reviewCheck: (id: number, humanVerdict: string, humanNote: string) =>
    req<void>(`/api/perf/checks/${id}/review`, jsonInit('POST', { humanVerdict, humanNote })),
  reviewLoop: (id: number, humanVerdict: string, humanNote: string) =>
    req<void>(`/api/perf/loops/${id}/review`, jsonInit('POST', { humanVerdict, humanNote })),
}

export function perfReportUrl(runId: number, gameId: number): string {
  return `/api/perf/runs/${runId}/games/${gameId}/report`
}

/** 内存增长曲线数据（各局局内 PSS 抽样叠加，≤8 条）。 */
export function perfGrowthUrl(runId: number, gameId: number): string {
  return `/api/perf/runs/${runId}/games/${gameId}/growth-curves`
}

/** 报告 PDF 导出地址（服务端 Chrome 打印生成；PDF 转发给他人最稳）。 */
export function perfReportPdfUrl(runId: number, gameId: number): string {
  return `/api/perf/runs/${runId}/games/${gameId}/report.pdf`
}

export function perfExportUrl(runId: number, gameId: number): string {
  return `/api/perf/runs/${runId}/games/${gameId}/export`
}

// ---------- M7 接口测试 ----------

/** 用例的写入体（新建时 id 为 null）。 */
export type ApiCaseInput = Omit<ApiCase, 'id'> & { id?: number | null }

export const apitestApi = {
  capabilities: () => req<ApiCapabilities>('/api/apitest/capabilities'),
  prepare: (spec: ApiCaseInput, envId: number) =>
    req<ApiPrepared>('/api/apitest/prepare', jsonInit('POST', { spec, envId })),
  send: (spec: ApiCaseInput, envId: number, sourceContext?: RoundQueryContext | null) =>
    req<ApiExec>('/api/apitest/send', jsonInit('POST', { spec, envId, sourceContext }), { timeoutMs: Math.max(30000, (spec.timeoutMs ?? 10000) + 15000) }),
  inspectResponse: (body: string, headers?: string | null) =>
    req<ApiResponseInspection>('/api/apitest/inspect-response', jsonInit('POST', { body, headers: responseHeaderMap(headers) })),
  scenarios: () => req<ApiScenario[]>('/api/apitest/scenarios'),
  createScenario: (scenario: Omit<ApiScenario, 'id'>) =>
    req<ApiScenario>('/api/apitest/scenarios', jsonInit('POST', scenario)),
  updateScenario: (id: number, scenario: Omit<ApiScenario, 'id'>) =>
    req<ApiScenario>(`/api/apitest/scenarios/${id}`, jsonInit('PUT', scenario)),
  deleteScenario: (id: number) => req<void>(`/api/apitest/scenarios/${id}`, { method: 'DELETE' }),
  runScenario: (id: number, repeatCount = 1, intervalSeconds = 0) =>
    req<ApiBatch>(`/api/apitest/scenarios/${id}/run`, jsonInit('POST', { repeatCount, intervalSeconds })),
  cases: () => req<ApiCase[]>('/api/apitest/cases'),
  createCase: (c: ApiCaseInput) => req<ApiCase>('/api/apitest/cases', jsonInit('POST', c)),
  updateCase: (id: number, c: ApiCaseInput) =>
    req<ApiCase>(`/api/apitest/cases/${id}`, jsonInit('PUT', c)),
  deleteCase: (id: number) => req<void>(`/api/apitest/cases/${id}`, { method: 'DELETE' }),
  duplicateCase: (id: number) =>
    req<ApiCase>(`/api/apitest/cases/${id}/duplicate`, { method: 'POST' }),

  /** 全部分组（带每组的用例数）。 */
  /** 批量删除用例（v1.10.6）：返回实际删掉的条数，与入参个数可能不同。 */
  batchDeleteCases: (ids: number[]) =>
    req<{ ok: boolean; deleted: number }>(
      '/api/apitest/cases/batch-delete',
      jsonInit('POST', { ids }),
    ),
  /**
   * 批量迁移用例（v1.10.6）。
   *
   * `copy=true` = 复制过去（原分组保留）；`false` = 直接移过去。
   * `groupId=null` = 搬到「未分组」。
   */
  batchMoveCases: (ids: number[], groupId: number | null, copy: boolean) =>
    req<{ ok: boolean; affected: number; copy: boolean }>(
      '/api/apitest/cases/batch-move',
      jsonInit('POST', { ids, groupId, copy }),
    ),
  /** 复制分组（含组内全部用例）；新名字由后端取，保证连续复制得到确定结果。 */
  duplicateCaseGroup: (id: number) =>
    req<ApiCaseGroup>(`/api/apitest/case-groups/${id}/duplicate`, { method: 'POST' }),
  /** 全部环境（v1.10.7 #87）：一个环境 = 一套变量 + 一套签名。 */
  envs: () => req<ApiEnv[]>('/api/apitest/envs'),
  createEnv: (name: string) =>
    req<ApiEnv>('/api/apitest/envs', jsonInit('POST', { name })),
  renameEnv: (id: number, name: string) =>
    req<ApiEnv>(`/api/apitest/envs/${id}`, jsonInit('PUT', { name })),
  deleteEnv: (id: number) => req<void>(`/api/apitest/envs/${id}`, { method: 'DELETE' }),
  /** 复制环境（变量与签名一起复制）；新名字由后端取序号。 */
  duplicateEnv: (id: number) =>
    req<ApiEnv>(`/api/apitest/envs/${id}/duplicate`, { method: 'POST' }),
  caseGroups: () => req<ApiCaseGroup[]>('/api/apitest/case-groups'),
  /** 新建分组。名字为空或重名会被后端拒掉并给出原因。 */
  createCaseGroup: (name: string, envId?: number) =>
    req<ApiCaseGroup>('/api/apitest/case-groups', jsonInit('POST', { name, envId })),
  /** 重命名分组。只改一行，组内用例跟着换名。 */
  /** 改名，同时可更新环境绑定（envId 传 undefined = 不改动）。 */
  renameCaseGroup: (id: number, name: string, envId?: number) =>
    req<ApiCaseGroup>(`/api/apitest/case-groups/${id}`, jsonInit('PUT', { name, envId })),
  /** 删除分组。组内用例不删，只变回未分组。 */
  deleteCaseGroup: (id: number) =>
    req<void>(`/api/apitest/case-groups/${id}`, { method: 'DELETE' }),

  /** 同步跑一组用例，跑完才返回（执行选中、不需要重复时用）。 */
  run: (caseIds: number[]) => req<ApiExec[]>('/api/apitest/run', jsonInit('POST', { caseIds }), { timeoutMs: 600000 }),

  /** 读签名设置。返回里不含密钥原文。 */
  sign: (env: number) => req<ApiSignSetting>(`/api/apitest/sign?env=${env}`),

  /**
   * 按需取回密钥明文（需求 #39）。
   *
   * 只在用户点「显示」时调用 —— 这是唯一一处让密钥过 HTTP 的口子，
   * 不调它，密钥就不会出现在响应体、浏览器网络面板或前端内存里。
   */
  signSecret: (env: number) => req<ApiSignSecret>(`/api/apitest/sign/secret?env=${env}`),

  /** 存签名设置。appSecret 留空表示不改动已保存的密钥。 */
  saveSign: (s: {
    enabled: boolean
    appId: string
    appSecret: string
    nonce: string
    mgId: string
    env: number
  }) => req<ApiSignSetting>('/api/apitest/sign', jsonInit('PUT', s)),

  /** 读三组自定义变量（环境基址一组、请求头一组、请求体一组）。值不保密，原样返回。 */
  vars: (env: number) => req<ApiVarSet>(`/api/apitest/vars?env=${env}`),

  /** 覆盖保存三组变量。整个替换，后端不做逐条比对。 */
  saveVars: (v: {
    base: ApiVarItem[]
    header: ApiVarItem[]
    body: ApiVarItem[]
    env: number
  }) => req<ApiVarSet>('/api/apitest/vars', jsonInit('PUT', v)),

  saveEnvironment: (env: number, sign: {
    enabled: boolean
    appId: string
    appSecret: string
    nonce: string
    mgId: string
  }, vars: ApiVarSet) =>
    req<{ sign: ApiSignSetting; vars: ApiVarSet }>(
      `/api/apitest/envs/${env}/settings`, jsonInit('PUT', { sign, vars })),

  /** 启动批次（按间隔重复）。repeatCount = -1 表示一直循环到手动停止。 */
  startBatch: (name: string, caseIds: number[], intervalSeconds: number, repeatCount: number) =>
    req<ApiBatch>(
      '/api/apitest/batches',
      jsonInit('POST', { name, caseIds, intervalSeconds, repeatCount }),
    ),
  batches: () => req<ApiBatch[]>('/api/apitest/batches'),
  batchDetail: (id: number) => req<ApiBatchDetail>(`/api/apitest/batches/${id}`),
  stopBatch: (id: number) => req<void>(`/api/apitest/batches/${id}/stop`, { method: 'POST' }),
  deleteBatch: (id: number) => req<void>(`/api/apitest/batches/${id}`, { method: 'DELETE' }),
  recentExecs: (limit = 20) => req<ApiExec[]>(`/api/apitest/execs/recent?limit=${limit}`),

  codes: (q?: string, scope?: string) => {
    const p = new URLSearchParams()
    if (q) p.set('q', q)
    if (scope) p.set('scope', scope)
    const qs = p.toString()
    return req<ApiCode[]>(`/api/apitest/codes${qs ? `?${qs}` : ''}`)
  },
  codeScopes: () => req<{ scope: string; label: string }[]>('/api/apitest/codes/scopes'),
  addCode: (scope: string, code: number, message: string) =>
    req<ApiCode>('/api/apitest/codes', jsonInit('POST', { scope, code, message })),
  deleteCode: (id: number) => req<void>(`/api/apitest/codes/${id}`, { method: 'DELETE' }),
}

/** 批次明细 CSV 导出地址。 */
export function apiBatchExportUrl(batchId: number): string {
  return `/api/apitest/batches/${batchId}/export`
}
