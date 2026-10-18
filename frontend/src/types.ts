// 与后端 REST API 对应的类型定义

export type Direction = 'APPTOMG' | 'MGTOAPP'

export interface Device {
  serial: string
  model?: string
  status: string
}

export interface TestPoint {
  id: number
  direction: Direction
  group: string
  seq: number
  stateName: string
  resourceKey?: string
  description?: string
  dataSchema?: string
  triggerType: string
  keyword?: string
  prompt?: string
  judgeMode: string
  autoPass: boolean
  enabled: boolean
  tapCount: number
}

export interface TestConfig {
  id: number
  direction: Direction
  name: string
  pointIds: number[]
  createdAt?: string
}

export type ResultStatus = 'PASS' | 'FAIL' | 'SKIP'

export interface RunResult {
  id: number
  runId: number
  pointId?: number
  stateName: string
  /** 测试点名称（后端快照自 TestPoint.description，序列化字段名即 description） */
  description?: string
  direction: string
  result: ResultStatus
  logSnippet?: string
  note?: string
  elapsedMs?: number
  createdAt?: string
}

export interface RunSummary {
  runId: number
  direction: string
  device?: string
  status: 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'ERROR'
  total: number
  results: RunResult[]
}

export interface RunListItem {
  id: number
  direction: string
  device?: string
  status: string
  startTime?: string
  endTime?: string
  selectedPointIds?: number[]
}

/** 历史 run 完整结果（GET /api/runs/{id}/results） */
export interface HistoryResultItem {
  id: number
  pointId: number
  stateName: string
  pointName?: string
  result: ResultStatus
  logSnippet?: string
  note?: string
  elapsedMs?: number
  createdAt?: string
}

export interface RunResults {
  run: {
    id: number
    direction: string
    device?: string
    status: string
    startTime?: string
    endTime?: string
    selectedPointIds?: number[]
  }
  stats: { PASS: number; FAIL: number; SKIP: number; TOTAL: number }
  results: HistoryResultItem[]
}

export interface WalkCurrent {
  runId: number
  direction: string
  status: string
  total: number
  done: number
  current?: {
    id: number
    stateName: string
    description?: string
    dataSchema?: string
    keyword?: string
    prompt?: string
    triggerType: string
    judgeMode: string
    autoPass: boolean
  } | null
  pendingLog?: string
}

export interface StartRunResponse {
  runId: number
  mode: string
  total: number
}

export interface TriggerResponse {
  autoJudged: boolean
  result?: string
  matched?: string
}

// ---------- M6 性能测试 ----------

/**
 * 性能测试的运行模式。
 *
 * - `NORMAL` 正常压测：点 UI 按钮把对局跑起来，对局内采 N 秒
 * - `EARLY_SETTLE` 异常时序：点开始后很快点结束，看有没有崩溃
 * - `AI_ROOM` 人机对局（需求 #68）：真机只负责进房，人机与开局、收尾都走接口推送
 */
export type PerfMode = 'NORMAL' | 'EARLY_SETTLE' | 'AI_ROOM'
export type PerfVerdict = 'PASS' | 'SUSPECT' | 'FAIL' | 'N_A'

/** 内存增长曲线（v1.9.1）：各局局内 PSS 叠加对比用的抽样曲线。 */
export interface PerfGrowthCurve {
  loopNo: number
  /** 局内逐秒 PSS（MB），t 从 1 起 */
  points: { t: number; pss: number | null }[]
}

export interface PerfGrowthCurves {
  curves: PerfGrowthCurve[]
  totalOk: number
}

export interface PerfGame {
  id: number
  name: string
  shortName?: string
  createdAt?: string
  /**
   * 推送这个游戏时用的 mg_id / app_id（需求 #68）。
   *
   * 人机对局模式把它们注入用例里的 `{{mg_id}}` / `{{app_id}}`；留空则该用变量表里的全局值。
   * 不同游戏不一样，所以挂在游戏上，而不是全局配一份。
   */
  mgId?: string
  appId?: string
  /** 推送请求里的房间号（需求 #70）。空 = 用变量表里的全局 room_id。 */
  roomId?: string
  /**
   * 名单里真人（is_ai=0）的条数（需求 #70）。null = 没配，按 1 兜底；
   * 人机数没配而它配了时 = 纯真人名单。
   */
  humanCount?: number | null
  /**
   * 这个游戏推几个人机（is_ai=1）。null = 没配，退回全局「人机数量」，
   * 全局也没配则用用例原文的名单（首条强制真人）。
   */
  aiCount?: number | null
  /** 人机的对局强度（ai_level，需求 #70）。null = 沿用用例样板里的原值。 */
  aiLevel?: number | null
  /** 这个游戏「开局后多久收尾」秒数（需求 #70）。null = 用 run 启动时配的值。 */
  gameSeconds?: number | null
  /**
   * 本机账号的 uid（需求 #71）。名单真人首位直接用这个身份推送（is_ai=0），
   * 真机客户端随后的加入被服务端幂等合并 —— 房间账本上全部成员都来自接口名单。
   * null = 真人位沿用 cy01。
   */
  machineUid?: string | null
  /**
   * 这个游戏的推送体模板（需求 #69，高级）。写法与接口用例同一种带占位符的模板，
   * 配了就整段替换该游戏推送用例的请求体。null/undefined = 用用例原文。
   */
  bodyOverride?: string | null
}

/**
 * 人机对局模式的配置（需求 #68）。
 *
 * 四个环节各绑一条接口用例 id（文本形式，空串=按名字自动找）；`aiCount` 留空表示
 * 用用例原文的人机名单，填了则按数量重排。
 */
export interface AiRoomConfig {
  caseBatch?: string
  caseStart?: string
  caseEnd?: string
  caseClear?: string
  aiCount?: string
  /**
   * 对局提前结束的关键字（v1.9.4 #78）。
   *
   * 采样期间按这个关键字扫 logcat，命中即认定为「对局已经结束」→ 提前收尾并给本圈打标记。
   * 留空 = 不做检测（与这一版之前的行为一致）。它是环境相关的上报事件名，所以只能由使用者填。
   */
  endKeyword?: string
  /** 各环节的默认用例名（后端给，用于在页面上提示「不指定时会去找哪一条」） */
  defaults?: { batch?: string; start?: string; end?: string; clear?: string }
}

/**
 * 性能测试的通用运行选项（v1.10.0 #84）。
 *
 * 三个模式都适用，所以与 `AiRoomConfig` 分开 —— 那个是人机对局专属的四条用例绑定。
 */
export interface PerfOptions {
  /**
   * 每圈采「关后基线」时，是否先触发一次系统内存回收再采第二个点。
   *
   * 打开后每圈多花约 3 秒，换来的是把「可回收缓存」与「真泄漏」分开报。
   * 默认关闭。
   */
  trimBaseline?: boolean
  /** 判定用的 CPU 阈值（整机口径 %）：面板上画参考线要与判定用同一个值，否则图与结论打架 */
  cpuThreshold?: number
}

export interface PerfRun {
  id: number
  device?: string
  mode: PerfMode
  status: string
  /** 逗号分隔的游戏 id 队列 */
  gameQueue?: string
  /** 队列对应的游戏短名列表（后端附带） */
  gameNames?: string[]
  loopCount: number
  sampleSeconds: number
  earlyTapDelayMs: number
  /**
   * 人机对局模式这一轮推了几个人机（需求 #68）。
   *
   * `null` 表示**没有这一项**（老 run，或不是人机对局模式）—— 与「推了 0 个」是两回事，
   * 界面上要分开显示，否则会把历史记录说成「一个人机都没推」。
   */
  aiCount?: number | null
  startTime?: string
  endTime?: string
}

export interface PerfStartResponse {
  runId: number
  mode: PerfMode
  games: number
  loopCount: number
  /** 续跑时清理的中断圈数 */
  removedInterrupted?: number
}

export interface PerfSample {
  seq: number
  pssKb?: number
  rssKb?: number
  cpuPercent?: number
  threadCount?: number
  wsThreadCount?: number
  netConn?: number
  ts?: string
}

/** 进度里的 lastSample 是 PerfCollector.Sample（tsMillis 字段名不同） */
export interface PerfLiveSample {
  tsMillis: number
  pssKb?: number
  rssKb?: number
  cpuPercent?: number
  threadCount?: number
  wsThreadCount?: number
  netConn?: number
}

export interface PerfProgress {
  runId?: number
  status: string
  mode?: PerfMode
  totalGames?: number
  currentGameIdx?: number
  currentLoop?: number
  currentGame?: string
  message?: string
  lastSample?: PerfLiveSample | null
}

export interface PerfLoop {
  id: number
  runId: number
  gameId: number
  loopNo: number
  status: string
  invalidReason?: string
  baselinePssKb?: number
  baselineWsThreads?: number
  inGameWsThreads?: number
  pssAvgKb?: number
  pssPeakKb?: number
  cpuAvg?: number
  cpuPeak?: number
  /**
   * 关游戏后基线的六个内存分项（v1.10.1 面板绘图需要）。
   *
   * 六项之和 ≈ PSS 总量。「是哪一类内存在涨」这个问题只能靠它们回答 ——
   * 实测某次 50 圈的增长有 93% 集中在 Native，Java 与图形完全持平。
   */
  baselineJavaKb?: number
  baselineNativeKb?: number
  baselineGraphicsKb?: number
  baselineCodeKb?: number
  baselinePrivateOtherKb?: number
  baselineSystemKb?: number
  /** CPU 整机口径（单核值 ÷ 在线核数，v1.5 #21）；老 run 为 null，回退 cpuAvg/cpuPeak 单核口径 */
  cpuAvgSys?: number | null
  cpuPeakSys?: number | null
  cpuCores?: number | null
  verdict?: PerfVerdict
  judgeJson?: string
  humanVerdict?: string
  humanNote?: string
  batteryLevel?: number
  /**
   * 本圈是不是「对局提前结束」圈（v1.9.4 #78）。
   *
   * `1` = 采样期间检测到对局销毁事件、提前收尾；`null` = 这一列还没有数据（老 run）。
   * 提前结束圈的 PSS 均值天然偏低，混进趋势里会把人带偏，所以要在界面上能一眼区分。
   */
  earlyEnd?: number | null
  /**
   * 触发系统内存回收之后的关后基线（v1.10.0 #84）。
   *
   * `null` = 没采（开关关闭或老 run）。与 `baselinePssKb` 之差就是**可回收缓存量**：
   * 系统一施压就归还的那部分，不算泄漏。
   */
  trimmedPssKb?: number | null
  trimmedNativeKb?: number | null
  /**
   * 本圈真实对局的 roundId（v1.10.4 #86）。
   *
   * 圈号是我们自己数的，只在报告内部有意义；roundId 才是跟服务端对局记录对得上的标识。
   * `null` = 没采到（其余模式不写这一列，或本圈日志里没有这个关键字）。
   */
  gameRoundId?: string | null
  apiEnvId?: number | null
  apiEnvName?: string | null
  apiConfigCapturedAt?: string | null
  /** 回收后基线的其余分项（与 baseline*Kb 一一对应） */
  trimmedJavaKb?: number | null
  trimmedGraphicsKb?: number | null
  trimmedCodeKb?: number | null
  trimmedPrivateOtherKb?: number | null
  trimmedSystemKb?: number | null
  startTime?: string
  endTime?: string
}

export interface PerfCheck {
  id: number
  checkNo: number
  checkName?: string
  verdict?: PerfVerdict
  evidence?: string
  humanVerdict?: string
  humanNote?: string
}

// ---- 按钮坐标分组（v1.2 #4）----

/** 分组内的单个按钮条目；label 是中文按钮名，界面只用它展示，不用英文 key */
export interface PerfButtonItem {
  key: string
  label: string
  /** 是否必标：只有能被自动定位的大厅按钮才是必标（v1.7 #31） */
  required: boolean
  calibrated: boolean
}

/** 标定进度：已标定 N / 共 M + 逐按钮明细 */
export interface PerfButtonProgress {
  calibrated: number
  total: number
  /** 必标按钮的完成情况 —— 启动校验只看这一对（选标缺了不阻塞） */
  requiredCalibrated: number
  requiredTotal: number
  items: PerfButtonItem[]
}

/** 按钮坐标分组：换机即换分组（绑设备序列号），也可建不绑定设备的通用分组 */
export interface PerfButtonGroup {
  id: number
  name: string
  deviceSerial?: string | null
  createdAt?: string
  calibratedAt?: string | null
  calibrated?: number
  total?: number
  /** 必标按钮完成数（启动校验只看这个口径） */
  requiredCalibrated?: number
  /** 必标按钮总数 */
  requiredTotal?: number
  items?: PerfButtonItem[]
  progress?: PerfButtonProgress
}

/** 标定结果：status=calibrated 表示已实测落库，assumed 表示由同位按钮推定（未落库），not_found 表示当前界面未出现该按钮 */
export interface PerfCalibrateResult {
  key: string
  label: string
  x?: number
  y?: number
  status: 'calibrated' | 'assumed' | 'not_found'
  message?: string
  progress?: PerfButtonProgress
  _found?: number
}

/**
 * apptomg 测试点与「性能测试按钮坐标分组」的对应关系（v1.6 #26）。
 * 由服务端解析，前端不自己算映射，避免两边漂移。
 */
export interface ApptomgCoordSource {
  /** apptomg 测试点的 resourceKey */
  resourceKey: string
  /** 测试点名称（中文，直接展示） */
  label: string
  /** 对应到 perf 侧的可标定按钮 key；为 null 表示 perf 侧没有该按钮 */
  perfKey: string | null
  /** perf 侧按钮的中文名（用于显示「别名映射到哪个按钮」） */
  perfLabel: string | null
  /** 当前选中的分组是否已覆盖该按钮 */
  matched: boolean
}

// ---------- M7 接口测试（v1.10）----------

/**
 * 一条接口用例。地址/请求头/请求体由使用者填写，只存本地库。
 * expectStatus / expectCode 留空表示该项不做断言。
 */
export interface ApiCase {
  id: number
  name: string
  method: string
  url: string
  headers: string | null
  body: string | null
  note: string | null
  expectStatus: string | null
  expectCode: string | null
  timeoutMs: number | null
  /** 是否跳过 HTTPS 证书校验（内部自签证书环境用） */
  insecure: boolean
  /** 未启用的用例不参与批量执行 */
  enabled: boolean
  /** 所属分组 id；null 表示未分组（2026-10-04 新增，需求 #48） */
  groupId: number | null
  /**
   * 响应体断言：规则数组的 JSON 文本，如
   * `[{"path":"data.roomId","op":"type","expect":"integer"}]`；
   * null 表示不校验响应体（2026-10-05 新增，需求 #51）。
   */
  asserts: string | null
  options?: ApiRequestOptions | null
}

/**
 * 用例分组（2026-10-04 新增，需求 #48）。
 *
 * 分组是独立的一张表，不是用例上的一个文本字段：改名要能一次改全组，
 * 而且要能存在「还没有用例的空分组」。
 */
export interface ApiCaseGroup {
  id: number
  name: string
  /** 绑定的环境；null = 未绑定，执行时用默认环境（v1.10.7 #87） */
  envId: number | null
  sortOrder: number | null
  /** 该组的用例数，列表接口会带上（分组卡片上显示计数用） */
  caseCount: number
}

/** 返回码字典里的一条。source=SEED 表示随程序内置的官方公开码，不可删除。 */
export interface ApiEnv {
  strictVariables?: boolean | null
  id: number
  name: string
  /** 是否默认环境（全库只有一个，是未绑定用例的兜底）；默认环境不可删除 */
  isDefault: boolean
  sortOrder: number | null
}

export interface ApiCode {
  id: number
  scope: string
  scopeLabel: string
  code: number
  message: string
  source: 'SEED' | 'CUSTOM'
}

/**
 * 签名设置（环境级，不是某一条用例的属性）。
 *
 * 注意这里 **没有密钥字段** —— 密钥保存后就不再回传，界面只知道「存没存过」。
 * 这样它不会出现在页面刷新、截图或浏览器调试面板里。
 */
export interface ApiSignSetting {
  enabled: boolean
  appId: string | null
  nonce: string | null
  mgId: string | null
  secretSaved: boolean
  /** 开着开关且凭证齐了；为 false 时执行会直接报错。 */
  ready: boolean
}

/**
 * 变量表里的一行。名字为空的行后端会丢掉；值允许是空串（表示「就让它空着」）。
 */
export interface ApiVarItem {
  name: string
  secret?: boolean
  value: string
}

/**
 * 三组自定义变量：环境基址一组、请求头一组、请求体一组。
 *
 * 三组互不干涉 —— 请求头那个输入框只认 header 里定义过的名字，请求体那个框只认 body 里的，
 * 因此两边各有一个同名的 `id` 也不会互相盖掉。
 *
 * 地址是唯一「看得见两组」的地方：基址与请求头都作用于它，**同名时基址优先**；
 * 反过来不成立，基址的名字不会出现在请求头里（这是把基址单列一组的全部意义）。
 *
 * 与签名设置不同，这里**没有保密字段**：变量本来就是填进请求里、界面上要能看见能改的东西。
 */
export interface ApiVarSet {
  /** 环境基址：换环境时只改这一组。只在地址里生效。 */
  base: ApiVarItem[]
  header: ApiVarItem[]
  body: ApiVarItem[]
}

/**
 * 按需取回的密钥明文（2026-10-04 新增，需求 #39）。
 *
 * 这是全平台唯一一处密钥跨过 HTTP 边界的地方：只在用户点「显示」时请求，
 * 默认永远是遮住的。没存过时 saved=false 且 appSecret 为 null。
 */
export interface ApiSignSecret {
  saved: boolean
  appSecret: string | null
}

/** 一次接口调用的结果。verdict 三态：通过 / 断言未过 / 请求本身出错。 */
export interface ApiExec {
  id: number
  batchId: number | null
  caseId: number | null
  caseName: string
  roundNo: number
  seq: number
  method: string
  url: string
  httpStatus: number | null
  respCode: string | null
  codeMeaning: string | null
  verdict: 'PASS' | 'FAIL' | 'ERROR' | 'SKIP'
  reason: string | null
  elapsedMs: number
  respBody: string | null
  respHeaders: string | null
  /** 本次实际发出的请求头快照（含自动补的 Content-Type） */
  reqHeaders: string | null
  /** 本次实际发出的请求体，与发出的字节一致 */
  reqBody: string | null
  error: string | null
  ts: string
  /**
   * 本次执行的响应体断言逐条判定结果（JSON 文本，需求 #58）。
   * 内容是 `[{path,op,expect,ok,why}]`，通过的也在里面。
   *
   * 三种取值要分开解释：
   * - `null` —— 没有这一项（本版本之前的历史记录），界面显示「旧记录没存这一项」；
   * - `'[]'` —— 这次确实判定了，只是该用例一条断言都没配；
   * - 有内容 —— 逐条列出来。
   */
  assertsResult: string | null
  metadata?: string | null
}

/** 一个批量/定时执行批次。repeatCount = -1 表示一直循环到手动停止。 */
export interface ApiBatch {  id: number
  name: string
  mode: 'ONCE' | 'REPEAT'
  caseIds: number[]
  intervalSeconds: number
  repeatCount: number
  status: 'RUNNING' | 'DONE' | 'STOPPED' | 'FAILED' | 'INTERRUPTED'
  totalExecs: number
  passCount: number
  failCount: number
  errorCount: number
  skippedExecs?: number
  startedAt: string
  finishedAt: string | null
}

/** 批次详情：批次本身 + 全部执行明细。 */
export interface ApiBatchDetail {
  batch: ApiBatch
  execs: ApiExec[]
  running: boolean
}

/**
 * 上报查询：一次增量取文的返回（2026-10-06，需求 #60）。
 *
 * - `reset=true` 表示锚点失效（那行被 logcat 环形缓冲挤掉了），`lines` 是**整段**，
 *   必须**替换**本地缓冲而不是追加 —— 追加会让界面显示的内容与设备上的实际内容错位。
 * - `deviceOffline=true` 不是错误：实时查看时拔线/掉线是常态，界面据此提示并停止轮询。
 */
export interface LogcatTail {
  lines: string[]
  anchor: string | null
  reset: boolean
  total: number
  deviceOffline: boolean
  message?: string
}

/**
 * 一条保存下来的过滤关键字（2026-10-06，需求 #66）。
 *
 * <p>刻意分成两部分：**关键字本体**（要滤出什么）与**注释**（当初为什么加它）。
 * 过一段时间回来看，「roomId」这种关键字还认得出，而「SudReport」这种就得靠注释了。
 * 注释可空 —— 它是后补的辅助信息，不该拦住保存这个动作。
 */
export interface LogcatKeyword {
  id: number
  keyword: string
  note: string
}


// 接口工作区：兼容旧用例 options=null，所有请求组装由后端完成。
export interface ApiQueryParam { name: string; value: string; enabled: boolean }
export interface ApiAuth {
  type: 'INHERIT' | 'NONE' | 'SUD' | 'BEARER' | 'BASIC' | 'API_KEY' | 'OAUTH2' | 'DIGEST' | 'SCRIPT' | 'TEMPLATE'
  script?: string
  authorizationTemplate?: string
  token?: string; username?: string; password?: string
  keyName?: string; keyValue?: string; in?: 'HEADER' | 'QUERY'; profileId?: string; digestAlgorithm?: 'MD5' | 'SHA-256'; digestQop?: 'auth' | 'none'
}
export interface ApiExtractor { name: string; source: 'JSON' | 'HEADER'; path: string; required: boolean; secret?: boolean }
export interface ApiManagedFile { fileId: string; kind: 'UPLOAD' | 'RESPONSE' | 'PARTIAL'; filename: string; contentType: string; bytes: number; sha256: string; createdAt: string }
export interface ApiFileBody { fileId: string; filename?: string; contentType?: string }
export interface ApiFilePart { name: string; type: 'TEXT' | 'FILE'; value?: string; fileId?: string; filename?: string; contentType?: string; enabled?: boolean }
export interface ApiHeaderRow { name: string; value: string; enabled: boolean }
export interface ApiRequestOptions {
  headerRows?: ApiHeaderRow[] | null
  bodyType?: 'JSON' | 'RAW' | 'NONE' | 'URLENCODED' | 'MULTIPART' | 'BINARY' | null
  params?: ApiQueryParam[]
  auth?: ApiAuth | null
  extractors?: ApiExtractor[]
  fields?: ApiQueryParam[]
  fileBody?: ApiFileBody | null
  parts?: ApiFilePart[]
  strictVariables?: boolean
  transport?: { redirect?: 'NORMAL' | 'NEVER' | 'ALWAYS'; maxRedirects?: number; httpVersion?: 'INHERIT' | 'HTTP_1_1' | 'HTTP_2'; certificateProfileId?: string; cookieSession?: string; cookieDataRows?: 'ISOLATED' | 'SHARED'; proxyMode?: 'INHERIT' | 'DIRECT' | 'HTTP'; proxyHost?: string; proxyPort?: number; cookieMode?: 'OFF' | 'ENV' | 'CHAIN'; maxResponseBytes?: number }
}
export interface ApiPrepared {
  headerRows?: ApiHeaderRow[]
  bodyMetadata?: Record<string, unknown>
  valid: boolean; method: string; url: string; headers: Record<string, string>; body: string | null
  envId: number; envName: string; capturedAt: string
  warnings: string[]; unresolved: string[]
  variables: unknown
  error?: string | null
}
export interface ApiCapabilities {
  methods: string[]; bodyTypes: string[]; authTypes: string[]
  systemVariables: unknown[]; assertionOps: string[]; extractorSources: string[]
}
export interface ApiResponseField { path: string; type: string; valueJson: string | null; preview: string; selectable?: boolean; truncated?: boolean }
export interface ApiResponseInspection { validJson: boolean; fields: ApiResponseField[]; error?: string | null; truncated?: boolean }
export interface ApiScenarioStep {
  key: string; name: string; caseId: number; envId: number | null
  onFailure: 'STOP' | 'CONTINUE'; independent?: boolean; cleanup: boolean; extractors: ApiExtractor[]
  delayMs?: number | null; poll?: { maxAttempts: number; intervalMs: number; timeoutMs: number; untilAsserts: string } | null
}
export interface ApiScenario { id: number; name: string; steps: ApiScenarioStep[] }
