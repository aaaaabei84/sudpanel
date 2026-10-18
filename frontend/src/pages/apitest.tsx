import { executionDisplay, hasSecretInventory } from '@/lib/execution-privacy'
import { SecretValueInput } from '@/components/apitest/secret-value-input'
import { SecretManager } from '@/components/apitest/secret-manager'
import { BatchReplay } from '@/components/apitest/batch-replay'
import { useDirtyGuard } from '@/lib/navigation-guard'
import { replacePanelSelection } from '@/lib/navigation'
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import {
  BookOpen,
  Check,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  Eye,
  EyeOff,
  Folder,
  Globe,
  ListChecks,
  Loader2,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Search,
  Send,
  SlidersHorizontal,
  Square,
  Timer,
  FolderInput,
  Trash2,
  X,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { CodeArea } from '@/components/code-area'
import { EntryCard } from '@/components/entry-card'
import { UnsavedChangesNotice } from '@/components/unsaved-changes-notice'
import { draftFingerprint } from '@/lib/draft-state'
import { JsonBlock } from '@/components/json-block'
import { VarInput } from '@/components/var-input'
import { apiBatchExportUrl, apitestApi, req } from '@/lib/api'
import { cn } from '@/lib/utils'
import { AssertionEditor } from '@/components/apitest/assertion-editor'
import { ExecutionMetadata } from '@/components/apitest/execution-metadata'
import { ActualCurlExport } from '@/components/apitest/actual-curl'
import { ConfigArchiveDialog } from '@/components/apitest/config-archive-dialog'
import { RequestError } from '@/lib/diagnostics'
import { HeaderRowsEditor } from '@/components/apitest/header-rows-editor'
import { RequestOptionsEditor } from '@/components/apitest/request-options-editor'
import { RequestWorkbench } from '@/components/apitest/request-workspace'
import { ResponseInspector } from '@/components/apitest/response-inspector'
import { RunPlan } from '@/components/apitest/run-plan'
import { ScenarioManager } from '@/components/apitest/scenario-manager'
import { TransferDialog } from '@/components/apitest/transfer-dialog'
import { EnvironmentStrict } from '@/components/apitest/environment-strict'
import { DataRunner } from '@/components/apitest/data-runner'
import { HistoryExplorer, type HistoricalDraft } from '@/components/apitest/history-explorer'
import { VariableHelp } from '@/components/apitest/variable-help'
import { assertionsBroken, capabilitySuggestions, emptyOptions, scopedSystemSuggestions, SYSTEM_SUGGESTIONS, type RoundQueryContext } from '@/lib/apitest-workspace'
import type { VarSuggestion } from '@/lib/var-suggest'
import type {
  ApiBatch,
  ApiEnv,
  ApiBatchDetail,
  ApiCase,
  ApiCaseGroup,
  ApiCode,
  ApiExec,
  ApiSignSetting,
  ApiVarItem,
  ApiVarSet,
} from '@/types'

function getMethodBadgeStyle(method: string) {
  const m = (method || '').toUpperCase()
  switch (m) {
    case 'GET':
      return 'bg-emerald-500/15 text-emerald-400 border-emerald-500/35 hover:bg-emerald-500/25'
    case 'POST':
      return 'bg-amber-500/15 text-amber-400 border-amber-500/35 hover:bg-amber-500/25'
    case 'PUT':
      return 'bg-sky-500/15 text-sky-400 border-sky-500/35 hover:bg-sky-500/25'
    case 'DELETE':
      return 'bg-rose-500/15 text-rose-400 border-rose-500/35 hover:bg-rose-500/25'
    case 'PATCH':
      return 'bg-purple-500/15 text-purple-400 border-purple-500/35 hover:bg-purple-500/25'
    case 'HEAD':
    case 'OPTIONS':
      return 'bg-indigo-500/15 text-indigo-400 border-indigo-500/35 hover:bg-indigo-500/25'
    default:
      return 'bg-zinc-500/15 text-zinc-400 border-zinc-500/35'
  }
}

function FormattedUrl({ url }: { url: string }) {
  if (!url) return null
  const parts: { type: 'var' | 'text'; content: string }[] = []
  const regex = /(\{\{[^{}]+\}\})/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = regex.exec(url)) !== null) {
    if (match.index > lastIndex) {
      parts.push({ type: 'text', content: url.slice(lastIndex, match.index) })
    }
    parts.push({ type: 'var', content: match[1] })
    lastIndex = regex.lastIndex
  }
  if (lastIndex < url.length) {
    parts.push({ type: 'text', content: url.slice(lastIndex) })
  }
  return (
    <span className="font-mono text-[13px] leading-relaxed select-text inline-flex flex-wrap items-center gap-1.5">
      {parts.map((p, idx) =>
        p.type === 'var' ? (
          <span
            key={idx}
            className="inline-flex items-center px-1.5 py-0.5 rounded font-semibold text-cyan-300 bg-cyan-950/80 border border-cyan-500/40 text-xs shadow-xs tracking-tight"
          >
            {p.content}
          </span>
        ) : (
          <span key={idx} className="text-zinc-200/90 font-mono text-[13px]">
            {p.content}
          </span>
        ),
      )}
    </span>
  )
}

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

/**
 * 平台内置的占位符名字（与后端 ApiSigner 的常量一一对应）。
 *
 * 这里只是把名字列出来给补全用，值由后端在执行时替换。之所以不在界面上重新定义一遍占位符语法，
 * 是因为「有哪些内置名字」是后端的知识 —— 多写一个前端不认识的名字不会报错，只会静默不替换。
 */
const BUILTIN_VARS = ['times', 'app_id', 'mg_id', 'nonce', 'sign']

/**
 * 内置变量的「从哪来 / 在哪生效」说明（需求 #40）。
 *
 * 与 BUILTIN_VARS 分开写：上面那份是补全用的纯名字列表，这份是给人看的。
 *
 * `where` 那一列按**后端实际替换范围**填 —— `{{sign}}` 只在地址与请求头里替换，
 * 写进请求体不会被替换（签名要等请求体替换完才算得出来）。这一条必须摆在界面上：
 * 光看名字看不出差别，写进请求体也不会报错，只会原样发出去。
 */
const BUILTIN_VAR_DOCS: { name: string; from: string; where: string }[] = [
  { name: 'times', from: '当前秒（发送时现取）', where: '地址、请求头、请求体' },
  { name: 'app_id', from: '签名设置里的应用 id', where: '地址、请求头、请求体' },
  { name: 'mg_id', from: '签名设置里的 mg_id', where: '地址、请求头、请求体' },
  { name: 'nonce', from: '签名设置里的随机串（留空则每次现生成）', where: '地址、请求头、请求体' },
  { name: 'sign', from: '算好的签名（覆盖替换完变量的那一份请求体）', where: '仅地址与请求头' },
]

/**
 * 响应体断言的判断条件（2026-10-05，需求 #51）。
 *
 * 界面上显示中文、库里存左边的英文枚举 —— 以后想把文案改成「类型为」之类，
 * 已经存好的用例不受影响。
 *
 * kind 决定「期望值」那一栏是什么形态：条件本身永远是用选的，
 * 只有「值」需要打字，而「是某类型」连值都是选的。
 */
type AssertKind = 'none' | 'type' | 'number' | 'text'

interface AssertRow {
  path: string
  op: string
  expect: string
}

const ASSERT_OPS: { group: string; items: { value: string; label: string; kind: AssertKind }[] }[] = [
  {
    group: '存在性',
    items: [
      { value: 'exists', label: '存在', kind: 'none' },
      { value: 'notExists', label: '不存在', kind: 'none' },
    ],
  },
  { group: '类型', items: [{ value: 'type', label: '是某类型', kind: 'type' }] },
  {
    group: '相等',
    items: [
      { value: 'equals', label: '等于', kind: 'text' },
      { value: 'notEquals', label: '不等于', kind: 'text' },
    ],
  },
  { group: '文本', items: [{ value: 'contains', label: '包含', kind: 'text' }, { value: 'regex', label: '正则匹配', kind: 'text' }] },
  {
    group: '数值',
    items: [
      { value: 'gt', label: '大于', kind: 'number' },
      { value: 'gte', label: '大于等于', kind: 'number' },
      { value: 'lt', label: '小于', kind: 'number' },
      { value: 'lte', label: '小于等于', kind: 'number' },
    ],
  },
  { group: '长度', items: [{ value: 'length', label: '长度等于', kind: 'number' }] },
  { group: '集合', items: [{ value: 'in', label: '属于其中之一', kind: 'text' }] },
  { group: '结构', items: [{ value: 'schema', label: '符合 Schema', kind: 'text' }] },
]

/** type 条件能选的目标类型。integer 是 number 的子集（要求原文不带小数点）。 */


/**
 * 判断条件的英文值 → 中文标签（含一个归一化查找表）。
 *
 * 判定结果那一栏要显示中文，而库里存的是英文 —— 这里与编辑区的下拉**共用同一份
 * {@link ASSERT_OPS}**，不另抄一份，否则以后加条件时必然只改一处。
 *
 * 归一化后的表是按后端的口径建的：后端认条件是「小写 + 去掉下划线/短横/点」，
 * 所以库里可能躺着 `not_equals` 这种老写法，直接按原串查会查不到、显示成英文。
 */
const OP_LABEL: Record<string, string> = Object.fromEntries(
  ASSERT_OPS.flatMap((g) => g.items.map((it) => [it.value, it.label])),
)
const OP_LABEL_NORM: Record<string, string> = Object.fromEntries(
  Object.entries(OP_LABEL).map(([k, v]) => [k.toLowerCase().replace(/[_\-.]/g, ''), v]),
)

function opLabel(op: string): string {
  return OP_LABEL[op] ?? OP_LABEL_NORM[op.toLowerCase().replace(/[_\-.]/g, '')] ?? op
}

/**
 * 把库里存的 JSON 文本读成界面上的断言行。
 *
 * 读不出来就返回空 —— 不在这里报错，因为后端在判定时会明确报「断言配置无法解析」，
 * 那才是该看到这句话的地方（执行结果里），而不是打开编辑框时弹一个说不清的错。
 */
function parseAsserts(text: string | null): AssertRow[] {
  if (!text) return []
  try {
    const arr: unknown = JSON.parse(text)
    if (!Array.isArray(arr)) return []
    return arr.map((r) => {
      const o = (r ?? {}) as Record<string, unknown>
      return {
        path: String(o.path ?? ''),
        op: String(o.op ?? ''),
        expect: String(o.expect ?? ''),
      }
    })
  } catch {
    return []
  }
}

/**
 * 界面上的断言行存回 JSON 文本。
 *
 * 整行都空的丢掉：那是点了「添加断言」还没填的草稿行，不该变成一条约束
 * （后端也按同一规则忽略，两边一致，免得「界面上没填却影响判定」）。
 */
function serializeAsserts(rows: AssertRow[]): string | null {
  const kept = rows.filter((r) => r.path.trim() || r.op || r.expect.trim())
  if (kept.length === 0) return null
  return JSON.stringify(
    kept.map((r) => ({ path: r.path.trim(), op: r.op, expect: r.expect.trim() })),
  )
}

/** 用例带了几条响应体断言（给列表上的小标记用）。 */
function assertCount(text: string | null): number {
  return parseAsserts(text).length
}

/** 空表单的默认值：GET、10 秒超时、启用、未分组。 */
const EMPTY_FORM = {
  name: '',
  method: 'GET',
  url: '',
  headers: '',
  body: '',
  note: '',
  expectStatus: '',
  expectCode: '',
  timeoutMs: 10000,
  insecure: false,
  enabled: true,
  /**
   * 分组。刻意用字符串存：下拉组件的值必须是字符串，
   * 而「未分组」在后端是 null —— 中间要转换，转换点只放一处（saveCase 与 openEdit）。
   */
  groupId: '',
  /**
   * 响应体断言（需求 #51）。界面上按行编辑，保存时才拼成 JSON 文本。
   * 数组本身不可变 —— 每次改动都换一个新数组，别改这个常量里的那一份。
   */
  asserts: [] as AssertRow[],
  options: emptyOptions(),
  assertsOriginal: null as string | null,
  assertsInvalid: false,
}

type CaseForm = typeof EMPTY_FORM

function fmtTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 默认收起的正文块（2026-10-05，需求 #53）。
 *
 * 执行详情的几块里，「本次发出的请求」和「本次发出的请求体」日常最不需要看 ——
 * 但它们往往最长（一整份请求头加整段请求体），摊开来会把下面所有记录一起顶下去。
 * 所以这两块默认只留一行标题，点了才展开。（需求 #55 把响应侧挪到上面之后这条理由没变：
 * 要看的响应本来就在最上面，那两块更没有必要一直摊着。）
 *
 * 收起状态不往外提：这一行随外层「展开执行记录」一起挂载/卸载，
 * 外层一收起来这里就整体卸载，再展开自然回到收起态 —— 不需要额外做重置。
 */
function CollapsibleBlock({
  title,
  text,
  empty,
  hint,
}: {
  title: string
  text: string | null
  empty: string
  hint?: string
}) {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="mb-1 flex w-full items-center gap-1 text-left text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
      >
        {open ? (
          <ChevronDown className="size-3 shrink-0" />
        ) : (
          <ChevronRight className="size-3 shrink-0" />
        )}
        <span>{title}</span>
        {hint && <span className="truncate font-normal opacity-70">{hint}</span>}
        <span className="ml-auto shrink-0 font-normal opacity-70">
          {open ? '点击收起' : '点击展开'}
        </span>
      </button>
      {open && <JsonBlock text={text} empty={empty} title={title} />}
    </div>
  )
}

/** 断言判定结果里的一条 —— 字段与后端 {@code ApiBodyAssert.toJson} 的输出一一对应。 */
interface JudgedRow {
  path: string
  op: string
  expect: string
  ok: boolean
  why: string | null
  actual: string | null
  source: string
  actualTruncated: boolean
}

/**
 * 读取「本次执行的断言判定结果」（2026-10-05，需求 #58）。
 *
 * <p>「没有内容」有三种，必须分开：**没这一列**（旧记录）／**有但读不出来**（数据坏了）／
 * **读出来是空数组**（该用例确实没配断言）。混成一句话就会出现「我明明配了断言，
 * 界面却说记录丢了」这种误导 —— 而这三种情况下使用者要做的事完全不同。
 */
function readJudged(text: string | null): { rows: JudgedRow[] | null; broken: boolean } {
  if (text == null) return { rows: null, broken: false }
  try {
    const v: unknown = JSON.parse(text)
    if (!Array.isArray(v)) return { rows: null, broken: true }
    return {
      rows: v
        .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
        .map((r) => ({
          path: String(r.path ?? ''),
          op: String(r.op ?? ''),
          expect: String(r.expect ?? ''),
          ok: r.ok === true,
          why: r.why == null ? null : String(r.why),
          actual: r.actual == null ? null : typeof r.actual === 'string' ? r.actual : JSON.stringify(r.actual),
          source: String(r.source ?? (String(r.path ?? '').startsWith('header:') ? 'HEADER' : r.path === '$elapsedMs' ? 'ELAPSED' : 'BODY')),
          actualTruncated: r.actualTruncated === true,
        })),
      broken: false,
    }
  } catch {
    return { rows: null, broken: true }
  }
}

/**
 * 结果详情里的「响应体断言」块（2026-10-05，需求 #58）。
 *
 * <p>这一块存在的理由是「绿得不明不白」：在此之前只有**没通过**的断言会落库（进失败原因），
 * 于是断言全过的时候界面上一个字都没有 —— 一个绿灯到底是因为断言都过了，
 * 还是因为这条用例压根没配断言，从记录上分不出来。所以收起态**必须带上条数**，
 * 说「4 条全部通过」，而不是只说「通过」。
 *
 * <p>默认收起，理由与请求侧两块相同：一条用例可能配十几条断言，天天摊着会把
 * 下面所有执行记录顶下去。
 */
function AssertsBlock({ text, verdict }: { text: string | null; verdict: ApiExec['verdict'] }) {
  const [open, setOpen] = useState(false)
  const { rows, broken } = readJudged(text)

  const total = rows?.length ?? 0
  const failed = rows?.filter((r) => !r.ok).length ?? 0
  /**
   * 「没内容」有四种，**必须分开说**，因为使用者要做的事完全不同：
   * <ul>
   *   <li>记录坏了 —— 去查数据；</li>
   *   <li>请求根本没通（ERROR）—— 没有响应体就无从判定，去查网络/地址；</li>
   *   <li>老记录 —— 这一列是本版本才加的，历史记录本来就没有；</li>
   *   <li>确实没配断言 —— 去给用例加断言。</li>
   * </ul>
   * 把「请求没通」说成「旧记录」尤其误导：那是一条刚刚跑出来的记录，
   * 用户会以为记录被吞了，而真正该看的是上面那条红色的报错。
   */
  const summary = broken
    ? '（这一项的记录读不出来）'
    : rows === null
      ? verdict === 'SKIP'
        ? '（步骤被跳过，未做断言）'
        : verdict === 'ERROR'
        ? '（请求没通，未做断言）'
        : '（旧记录没存这一项）'
      : total === 0
        ? '（该用例没配响应断言）'
        : failed === 0
          ? `${total} 条全部通过`
          : `${total} 条，${failed} 条未通过`

  // 没有可展开的内容时就是个静态标题 —— 给一个点了没反应的按钮比不给还糟。
  // 标题同样包在 <span> 里：与可展开分支**结构保持一致**，否则两处的标题一个是元素、
  // 一个是纯文本节点，「找那一块的标题」这种定位方式就会在其中一个分支上失灵。
  if (!rows || total === 0) {
    return (
      <div>
        <div className="mb-1 text-xs font-medium text-muted-foreground">
          <span>响应断言</span>
          <span className="ml-1 font-normal opacity-70">{summary}</span>
        </div>
      </div>
    )
  }

  return (
    <div>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="mb-1 flex w-full items-center gap-1 text-left text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
      >
        {open ? (
          <ChevronDown className="size-3 shrink-0" />
        ) : (
          <ChevronRight className="size-3 shrink-0" />
        )}
        <span>响应断言</span>
        <span
          className={cn(
            'truncate font-normal',
            failed === 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400',
          )}
        >
          {summary}
        </span>
        <span className="ml-auto shrink-0 font-normal opacity-70">
          {open ? '点击收起' : '点击展开'}
        </span>
      </button>
      {open && (
        <div className="space-y-0.5 rounded-md border border-border/60 p-1.5">
          {rows.map((r, i) => (
            <div key={i} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-xs">
              <span
                className={cn(
                  'shrink-0 font-medium',
                  r.ok ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400',
                )}
              >
                {r.ok ? '✓' : '✗'}
              </span>
              <span className="shrink-0 text-muted-foreground">{r.source === 'HEADER' ? '响应头' : r.source === 'ELAPSED' ? '耗时' : '响应体'}</span>
              <span className="break-all font-mono">{r.path === '$elapsedMs' ? '响应耗时（毫秒）' : r.path.startsWith('header:') ? `响应头 ${r.path.slice(7)}` : r.path || '（整个响应体）'}</span>
              <span className="shrink-0 text-muted-foreground">{opLabel(r.op)}</span>
              {/* 期望值：条件标签本身已经说清在比什么（「等于」「长度等于」「属于其中之一」），
                  再补一个「期望」前缀就成了「等于 期望 3333」这种夹生话。
                  这里只把值本身摆出来；类型类的值是一组固定名字（integer 等），不加引号更利落。 */}
              <span className="break-all font-mono text-muted-foreground">
                {r.expect === '' ? '' : (r.op === 'type' ? r.expect : `「${r.expect}」`)}
              </span>
              {r.actual != null && <span className="break-all font-mono text-muted-foreground">实际：{r.actual}{r.actualTruncated ? '（已截断）' : ''}</span>}
              {r.why && (
                <span className="w-full break-words text-red-600 dark:text-red-400">{r.why}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** 判定徽标：通过=绿，断言未过=红，请求报错=琥珀（区分「接口返回不对」和「根本没通」）。 */
function VerdictBadge({ verdict }: { verdict: ApiExec['verdict'] }) {
  const map = {
    PASS: { text: '通过', cls: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400' },
    FAIL: { text: '未通过', cls: 'bg-red-500/10 text-red-600 dark:text-red-400' },
    SKIP: { text: '已跳过', cls: 'bg-muted text-muted-foreground' },
    ERROR: { text: '报错', cls: 'bg-amber-500/10 text-amber-600 dark:text-amber-400' },
  } as const
  const m = map[verdict] ?? map.ERROR
  return (
    <span className={cn('inline-flex rounded px-1.5 py-0.5 text-xs font-medium', m.cls)}>
      {m.text}
    </span>
  )
}

/** HTTP 状态码：2xx 绿、其余红、没收到响应灰。 */
function statusColor(code: number | null): string {
  if (code == null) return 'text-muted-foreground'
  if (code >= 200 && code < 300) return 'text-emerald-600 dark:text-emerald-400'
  return 'text-red-600 dark:text-red-400'
}

/**
 * 一张变量表：名称 + 值，一行一条。
 *
 * 两组变量共用这一个组件，靠调用方传进来的回调决定写哪一组 —— 复制一份出来会让两边
 * 的「添加一行、删一行」慢慢长歪，而这两个动作的交互本来就该一样。
 *
 * 行的顺序就是用户看到的顺序，用下标当 key 在这里是安全的：每个输入框显示的值都来自数组，
 * 不依赖 DOM 自身的状态，重排或删除后不会串行。
 */
function VarTable({
  title,
  hint,
  rows,
  onAdd,
  onChange,
  onRemove,
}: {
  title: string
  hint: string
  rows: ApiVarItem[]
  onAdd: () => void
  onChange: (i: number, patch: Partial<ApiVarItem>) => void
  onRemove: (i: number) => void
}) {
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <Label className="text-xs">{title}</Label>
        <Button size="sm" variant="ghost" className="h-6 px-1.5 text-xs" onClick={onAdd}>
          <Plus className="mr-1 h-3 w-3" />
          添加
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">{hint}</p>
      {rows.length === 0 ? (
        <div className="rounded-md border border-dashed px-2 py-3 text-center text-xs text-muted-foreground">
          还没有变量
        </div>
      ) : (
        <div className="space-y-1.5">
          {rows.map((v, i) => (
            <div key={i} className="flex items-center gap-1.5">
              <Input
                className="h-8 flex-1 font-mono text-xs"
                placeholder="名称"
                value={v.name}
                onChange={(e) => onChange(i, { name: e.target.value })}
              />
              <SecretValueInput className="h-8 min-w-0 font-mono text-xs" placeholder="值或 secret:// 引用" secret={v.secret === true} value={v.value} onChange={value => onChange(i,{value})} />
              <label className="flex shrink-0 items-center gap-1 text-xs"><Checkbox aria-label={`敏感变量 ${i + 1}`} checked={v.secret === true} onCheckedChange={secret => onChange(i,{secret:secret === true})} />敏感</label>
              <Button
                size="icon"
                variant="ghost"
                className="h-8 w-8 shrink-0 text-muted-foreground hover:text-red-500"
                title="删掉这一行"
                onClick={() => onRemove(i)}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * 内置变量清单（只读，需求 #40）。
 *
 * 为什么要有这块：平台的自动签名是「发送前现算」，这五个名字由后端在发送那一刻填值，
 * 界面上不写出来，使用者根本不知道有它们可用 —— 也就只能停留在「手工抄一个签名」，
 * 而抄来的签名从抄的那一刻就开始过期。
 *
 * 它与自定义变量放在同一个分段里，是因为它们本来就是同一层的东西：
 * 都是「在框里写 {{名字}}」。区别只有一个 —— 一个自己填，一个平台算。
 */
function BuiltinVarsPanel() {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">内置变量（平台自动填值，只读）</Label>
      <p className="text-xs text-muted-foreground">
        开启「发送前自动签名」后生效。地址与请求头里可用全部五个；请求体里不能用{' '}
        <span className="font-mono">{'{{sign}}'}</span>。
      </p>
      <div className="overflow-hidden rounded-md border">
        <Table>
          <TableHeader>
            <TableRow className="bg-muted/50">
              <TableHead className="w-[26%] text-xs">写法</TableHead>
              <TableHead className="text-xs">值从哪来</TableHead>
              <TableHead className="w-[34%] text-xs">在哪生效</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {BUILTIN_VAR_DOCS.map((b) => (
              <TableRow key={b.name}>
                <TableCell className="py-1.5 font-mono text-xs">{`{{${b.name}}}`}</TableCell>
                <TableCell className="py-1.5 text-xs text-muted-foreground">{b.from}</TableCell>
                <TableCell className="py-1.5 text-xs text-muted-foreground">{b.where}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <p className="text-xs text-muted-foreground">
        换环境要改的地址前缀放上面那组「环境基址」，别混进请求头变量里。
      </p>
    </div>
  )
}

export function ApitestPage({ queryContext, initialBatchId }: { queryContext?: RoundQueryContext | null; initialBatchId?: number }) {
  // ---- 用例 ----
  const [cases, setCases] = useState<ApiCase[] | null>(null)
  const [orderedPicked, setOrderedPicked] = useState<number[]>([])
  const picked = useMemo(() => new Set(orderedPicked), [orderedPicked])
  const [quickOpen, setQuickOpen] = useState(false)
  const [historyDraft, setHistoryDraft] = useState<HistoricalDraft | null>(null)
  const [systemVars, setSystemVars] = useState(SYSTEM_SUGGESTIONS)
  useEffect(() => { if (queryContext) setQuickOpen(true) }, [queryContext])
  useEffect(() => { void apitestApi.capabilities().then(c => setSystemVars(capabilitySuggestions(c.systemVariables ?? []))).catch(() => {}) }, [])

  // 批量迁移面板（v1.10.6）：点「迁移选中」后，操作条整体换成这个表单。
  // 用内联面板而不是再套一层弹窗 —— 弹窗里再开弹窗，焦点与 Esc 的归属很容易出问题。
  const [moveOpen, setMoveOpen] = useState(false)
  // true = 复制过去（原分组保留），false = 直接移过去。
  // 默认「直接移过去」：与「迁移」的字面语义一致，把复制做成默认会让人以为搬走了、其实多出一份。
  const [moveCopy, setMoveCopy] = useState(false)
  const [moveTarget, setMoveTarget] = useState('__none__')
  const moveOpenCount = picked.size
  const [search, setSearch] = useState('')
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<CaseForm>(EMPTY_FORM)
  const [saving, setSaving] = useState(false)
  const caseEditorBaseline = useRef('')
  const [caseDiscardPending, setCaseDiscardPending] = useState(false)
  const closeCaseEditor = () => {
    if (saving) return
    if (draftFingerprint(form) !== caseEditorBaseline.current) setCaseDiscardPending(true)
    else setEditorOpen(false)
  }
  useDirtyGuard({ label: '接口用例', dirty: editorOpen && draftFingerprint(form) !== caseEditorBaseline.current, locked: saving, discard: () => { setCaseDiscardPending(false); setEditorOpen(false); setForm(EMPTY_FORM) } })
  /**
   * 用例管理弹窗（需求 #41/#42）。
   *
   * 用例区整块收进弹窗，主页面只留一个入口按钮。`picked` 仍是页面级 state，
   * 所以弹窗里怎么勾选，主页面按钮上的「已选 N 条」都是实时的 —— 不需要额外同步，
   * 也就不存在「弹窗关了计数还是旧的」这种错位。
   */
  const [casesOpen, setCasesOpen] = useState(false)

  /**
   * 用例分组（需求 #48）。
   *
   * `activeGroup` 用一个字符串而不是 id，因为列表里有两个「伪分组」——
   * 「全部」与「未分组」：前者不是一个组，后者对应 groupId = null。
   * 用 `'all' | 'none' | String(id)` 表示，比再加两个布尔状态更好推理。
   */
  const [groups, setGroups] = useState<ApiCaseGroup[] | null>(null)
  const [activeGroup, setActiveGroup] = useState<string>('all')
  const [groupEditing, setGroupEditing] = useState<number | null>(null)
  const [groupDraft, setGroupDraft] = useState('')
  const [newGroupOpen, setNewGroupOpen] = useState(false)
  const [newGroupName, setNewGroupName] = useState('')
  const [savingGroup, setSavingGroup] = useState(false)

  // ---- 执行 ----
  const [syncResults, setSyncResults] = useState<ApiExec[]>([])
  const [running, setRunning] = useState(false)
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [intervalSeconds, setIntervalSeconds] = useState(5)
  const [repeatCount, setRepeatCount] = useState(3)
  const [forever, setForever] = useState(false)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [revealedExecs, setRevealedExecs] = useState<Set<number>>(new Set())

  // ---- 批次 ----
  const [configArchiveOpen, setConfigArchiveOpen] = useState(false)
  const [batchMissing, setBatchMissing] = useState(false)
  const [batches, setBatches] = useState<ApiBatch[]>([])
  const [listErrors, setListErrors] = useState<Record<string, string>>({})
  const listSeq = useRef<Record<string, number>>({})
  const [batchesLoaded, setBatchesLoaded] = useState(false)
  const [batchesLoading, setBatchesLoading] = useState(false)
  useEffect(() => () => { for (const key of Object.keys(listSeq.current)) ++listSeq.current[key] }, [])
  const [activeBatchId, setActiveBatchId] = useState<number | null>(null)
  const [detail, setDetail] = useState<ApiBatchDetail | null>(null)
  const timerRef = useRef<number | null>(null)
  const batchSeq = useRef(0)
  const activeBatchRef = useRef<number | null>(null)
  const batchMounted = useRef(true)
  const [batchProgressError, setBatchProgressError] = useState<string | null>(null)
  const batchFailures = useRef(0)
  const batchInFlight = useRef<number | null>(null)
  const batchRefreshAgain = useRef(false)
  const batchCursor = useRef(0)
  const batchRows = useRef<ApiExec[]>([])
  const fullExecutions = useRef(new Set<number>())
  const [executionErrors, setExecutionErrors] = useState<Record<number, string>>({})
  const [batchCurrent, setBatchCurrent] = useState<{ requestId: string; phase: string; attempt: number } | null>(null)

  // 换结果来源时，定时器和已发出的请求都必须失效。
  const selectBatch = useCallback((id: number | null) => {
    const seq = ++batchSeq.current
    setRevealedExecs(new Set())
    batchRefreshAgain.current = false
    batchFailures.current = 0
    setBatchProgressError(null)
    setBatchMissing(false)
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    activeBatchRef.current = id
    setActiveBatchId(id)
    replacePanelSelection('apitest', { batchId: id ?? undefined })
    setDetail(null)
    batchCursor.current = 0; batchRows.current = []; fullExecutions.current.clear(); setExpanded(new Set()); setExecutionErrors({}); setBatchCurrent(null)
    setSyncResults([])
    setRunning(false)
    return seq
  }, [])

  // ---- 返回码字典 ----
  const [codeQuery, setCodeQuery] = useState('')
  const [codeScope, setCodeScope] = useState('')
  const [codes, setCodes] = useState<ApiCode[] | null>(null)
  const [newCode, setNewCode] = useState({ scope: 'CUSTOM', code: '', message: '' })

  // ---- 环境设置：三组变量 + 签名 ----
  // 环境（v1.10.7 #87）：一个环境 = 一套变量 + 一套签名；activeEnv 是「正在编辑」的那个
  const [envs, setEnvs] = useState<ApiEnv[] | null>(null)
  const [activeEnv, setActiveEnv] = useState(1)
  const [envNewOpen, setEnvNewOpen] = useState(false)
  const [envDraft, setEnvDraft] = useState('')
  const [sign, setSign] = useState<ApiSignSetting | null>(null)
  const [vars, setVars] = useState<ApiVarSet>({ base: [], header: [], body: [] })
  const [envOpen, setEnvOpen] = useState(false)
  const [envTab, setEnvTab] = useState<'vars' | 'sign'>('vars')
  const [savingEnv, setSavingEnv] = useState(false)
  const [varForm, setVarForm] = useState<ApiVarSet>({ base: [], header: [], body: [] })
  // 密钥永远从空开始：后端不回传它，界面也就不该假装手里有
  const [signForm, setSignForm] = useState({
    enabled: false,
    appId: '',
    appSecret: '',
    nonce: '',
    mgId: '',
  })
  /**
   * 密钥是否处于「已显示」状态（需求 #39）。
   *
   * 默认 false = 密码框。点「显示」才会去后端要明文填进 signForm.appSecret，
   * 关弹窗复位。为什么不直接沿用 signForm.appSecret 是否为空来判断：
   * 用户自己重新输入密钥时那一格也是非空的，但那时不该切成明文显示。
   */
  const [secretShown, setSecretShown] = useState(false)
  const [secretLoading, setSecretLoading] = useState(false)
  const [envLoading, setEnvLoading] = useState(false)
  const [loadedEnv, setLoadedEnv] = useState<number | null>(null)
  const envSeq = useRef(0)
  const secretSeq = useRef(0)
  const envReady = !envLoading && loadedEnv === activeEnv

  const envBaseline = useRef('')
  const [secretEdited, setSecretEdited] = useState(false)
  const [envPending, setEnvPending] = useState<{ nextId: number | null } | null>(null)
  const envDrafts = useRef(new Map<number, { vars: typeof varForm; sign: typeof signForm; baseline: string; needsSecret: boolean }>())
  const envFingerprint = draftFingerprint({ vars: varForm, sign: { ...signForm, appSecret: '' } })
  const envDirty = envOpen && envReady && (envFingerprint !== envBaseline.current || secretEdited)
  const finishEnvNavigation = (nextId: number | null, keep: boolean) => {
    if (keep && envDirty) envDrafts.current.set(activeEnv, { vars: structuredClone(varForm), sign: { ...signForm, appSecret: '' }, baseline: envBaseline.current, needsSecret: secretEdited })
    if (!keep) envDrafts.current.delete(activeEnv)
    ++secretSeq.current; setSecretShown(false); setSecretLoading(false); setSecretEdited(false)
    setSignForm(value => ({ ...value, appSecret: '' })); setEnvPending(null)
    if (nextId == null) setEnvOpen(false); else setActiveEnv(nextId)
  }
  const changeEnv = (nextId: number | null) => {
    if (savingEnv || nextId === activeEnv) return
    if (envDirty) setEnvPending({ nextId }); else finishEnvNavigation(nextId, false)
  }
  useDirtyGuard({ label: '环境设置', dirty: envDirty, locked: savingEnv, discard: () => finishEnvNavigation(null, false) })

  const loadEnvs = useCallback(async () => {
    try {
      setEnvs(await apitestApi.envs())
    } catch {
      setEnvs(null)
    }
  }, [])

  const loadEnv = useCallback(async (id: number) => {
    const seq = ++envSeq.current
    ++secretSeq.current
    setEnvLoading(true)
    setLoadedEnv(null)
    setSecretLoading(false)
    setSecretShown(false)
    setSign(null)
    setVars({ base: [], header: [], body: [] })
    setVarForm({ base: [], header: [], body: [] })
    setSignForm({ enabled: false, appId: '', appSecret: '', nonce: '', mgId: '' })
    const [s, v] = await Promise.allSettled([apitestApi.sign(id), apitestApi.vars(id)])
    if (seq !== envSeq.current) return
    setEnvLoading(false)
    if (s.status !== 'fulfilled' || v.status !== 'fulfilled') {
      // 读失败时不允许把空白表单保存回去；重开弹窗可重试。
      toast.error('环境设置加载失败，请重新打开后重试')
      return
    }
    setSign(s.value)
    setVars(v.value)
    setVarForm({
      base: v.value.base.map(x => ({ ...x })),
      header: v.value.header.map(x => ({ ...x })),
      body: v.value.body.map(x => ({ ...x })),
    })
    setSignForm({ enabled: s.value.enabled, appId: s.value.appId ?? '', appSecret: '',
      nonce: s.value.nonce ?? '', mgId: s.value.mgId ?? '' })
    const savedFingerprint = draftFingerprint({ vars: v.value, sign: { enabled: s.value.enabled, appId: s.value.appId ?? '', appSecret: '', nonce: s.value.nonce ?? '', mgId: s.value.mgId ?? '' } })
    const cached = envDrafts.current.get(id)
    envBaseline.current = cached?.baseline ?? savedFingerprint
    setSecretEdited(false)
    if (cached) {
      setVarForm(cached.vars); setSignForm(cached.sign)
      if (cached.needsSecret) toast.info('环境草稿已恢复；之前编辑的密钥需重新填写。')
      if (cached.baseline !== savedFingerprint) toast.info('环境配置已有更新，请核对草稿后再保存。')
    }
    setLoadedEnv(id)
  }, [])

  /**
   * 建用例时按框给的变量名候选。
   *
   * 三份候选对应三个框，因为后端替换时作用域是分开的：请求头框只认 header 里定义过的名字，
   * 请求体框只认 body 里的，地址框认 base + header —— 候选要是混着给，
   * 用户就会选中一个执行时根本不会被替换的名字，而且不报错。
   *
   * 内置那五个名字三个框基本都能引用，唯一差别是 `{{sign}}`：签名的值要等请求体替换完
   * 才算得出来，所以它只在地址与请求头里替换，写进请求体不会被替换。请求体那份候选因此不列它。
   */
  const urlSuggestions = useMemo<VarSuggestion[]>(
    () => [
      ...vars.base.filter((v) => v.name.trim()).map((v) => ({ name: v.name.trim(), group: '环境基址' })),
      ...vars.header
        .filter((v) => v.name.trim())
        .map((v) => ({ name: v.name.trim(), group: '地址与请求头变量' })),
      ...BUILTIN_VARS.map((n) => ({ name: n, group: '旧写法 · 兼容' })),
      ...scopedSystemSuggestions(systemVars, 'URL'),
    ],
    [vars, systemVars],
  )

  const headerSuggestions = useMemo<VarSuggestion[]>(
    () => [
      ...vars.header
        .filter((v) => v.name.trim())
        .map((v) => ({ name: v.name.trim(), group: '地址与请求头变量' })),
      ...BUILTIN_VARS.map((n) => ({ name: n, group: '旧写法 · 兼容' })),
      ...scopedSystemSuggestions(systemVars, 'HEADER'),
    ],
    [vars, systemVars],
  )

  const bodySuggestions = useMemo<VarSuggestion[]>(
    () => [
      ...vars.body.filter((v) => v.name.trim()).map((v) => ({ name: v.name.trim(), group: '请求体变量' })),
      ...BUILTIN_VARS.filter((n) => n !== 'sign').map((n) => ({ name: n, group: '旧写法 · 兼容' })),
      ...scopedSystemSuggestions(systemVars, 'BODY'),
    ],
    [vars, systemVars],
  )

  const readList = useCallback(async <T,>(key: string, read: () => Promise<T>, apply: (rows: T) => void) => {
    const seq = (listSeq.current[key] ?? 0) + 1
    listSeq.current[key] = seq
    setListErrors(old => ({ ...old, [key]: '' }))
    if (key === '批次') setBatchesLoading(true)
    try {
      const rows = await read()
      if (listSeq.current[key] !== seq) return
      apply(rows)
      if (key === '批次') setBatchesLoaded(true)
    } catch (error) {
      if (listSeq.current[key] === seq) setListErrors(old => ({ ...old, [key]: error instanceof Error ? error.message : String(error) }))
    } finally {
      if (key === '批次' && listSeq.current[key] === seq) setBatchesLoading(false)
    }
  }, [])
  const loadCases = useCallback(() => readList('用例', () => apitestApi.cases(), setCases), [readList])
  const loadGroups = useCallback(() => readList('分组', () => apitestApi.caseGroups(), setGroups), [readList])
  const loadBatches = useCallback(() => readList('批次', () => apitestApi.batches(), setBatches), [readList])
  const loadCodes = useCallback(() => readList('返回码字典', () => apitestApi.codes(codeQuery || undefined, codeScope || undefined), setCodes), [readList, codeQuery, codeScope])

  useEffect(() => {
    void loadCases()
    void loadGroups()
    void loadBatches()
    void loadEnvs()
  }, [loadCases, loadGroups, loadBatches, loadEnvs])

  useEffect(() => {
    void loadEnv(activeEnv)
    return () => {
      ++envSeq.current
      ++secretSeq.current
    }
  }, [activeEnv, loadEnv])

  useEffect(() => {
    // 输入关键字后延迟 250ms 再查，避免每敲一个字打一次接口
    const t = window.setTimeout(() => void loadCodes(), 250)
    return () => window.clearTimeout(t)
  }, [loadCodes])

  /**
   * 轮询批次进度。
   *
   * 这里和 mgtoapp 那次的教训相同：轮询只允许**刷新数据**，不允许**重置用户正在看的状态**。
   * 所以只更新 detail / batches，不碰 activeBatchId、不碰 expanded —— 用户展开了哪几条
   * 响应体、正在看哪个批次，都不能被例行刷新改掉。
   *
   * 跑完不弹提示：状态徽标和结果表已经说明了一切，再弹一条只是多一次要点掉的干扰。
   */
  const pollBatch = useCallback(
    async (id: number, seq: number) => {
      if (seq !== batchSeq.current) return
      // 手动刷新/停止后的刷新与定时读取共用一个在途查询。
      if (batchInFlight.current === seq) { batchRefreshAgain.current = true; return }
      batchInFlight.current = seq
      batchRefreshAgain.current = false
      if (timerRef.current != null) window.clearTimeout(timerRef.current)
      timerRef.current = null
      let nextDelay: number | null = null
      try {
        const [progress, page] = await Promise.all([
          req<{ batch: ApiBatch; running: boolean; current: { requestId: string; phase: string; attempt: number } | null }>(`/api/apitest/batches/${id}/progress`),
          req<{ items: ApiExec[]; nextAfterId: number; hasMore: boolean }>(`/api/apitest/batches/${id}/executions?afterId=${batchCursor.current}&limit=100`),
        ])
        if (seq !== batchSeq.current) return
        if (page.hasMore && page.nextAfterId <= batchCursor.current) throw new Error('执行摘要游标没有前进')
        batchCursor.current = page.nextAfterId
        const rows = new Map(batchRows.current.map(row => [row.id, row]))
        page.items.forEach(row => { if (!rows.has(row.id)) rows.set(row.id, row) })
        batchRows.current = [...rows.values()].slice(-1000)
        const retained = new Set(batchRows.current.map(row => row.id))
        fullExecutions.current = new Set([...fullExecutions.current].filter(id => retained.has(id)))
        batchFailures.current = 0
        setBatchProgressError(null); setBatchCurrent(progress.current)
        setDetail({ batch: progress.batch, execs: batchRows.current, running: progress.running })
        setRunning(progress.running || progress.batch.status === 'RUNNING')
        if (page.hasMore) nextDelay = 10
        else if (progress.running || progress.batch.status === 'RUNNING') nextDelay = 1500
        else void loadBatches()

      } catch (e) {
        if (seq !== batchSeq.current) return
        const message = e instanceof Error ? e.message : String(e)
        setBatchProgressError(message)
        if (e instanceof RequestError && e.status === 404) { setBatchMissing(true); setRunning(false); setBatchProgressError('批次不存在或已删除。请从历史列表选择其他批次。'); return }
        if (batchFailures.current++ === 0) toast.error(`批次进度获取失败，将自动重试：${message}`)
        // 查询失败不能证明后台已停止，保留最后一次已知状态。
        nextDelay = Math.min(10000, 1500 * 2 ** Math.min(batchFailures.current, 3))
      } finally {
        if (seq === batchSeq.current) {
          batchInFlight.current = null
          if (nextDelay != null) timerRef.current = window.setTimeout(() => void pollBatch(id, seq), batchRefreshAgain.current ? 0 : nextDelay)
        }
      }
    },
    [loadBatches],
  )

  useEffect(
    () => {
      batchMounted.current = true
      return () => {
      batchMounted.current = false
      ++batchSeq.current
      if (timerRef.current) window.clearTimeout(timerRef.current)
      }
    },
    [],
  )

  // ---- 环境设置 ----

  const openEnv = () => {
    setVarForm({
      base: vars.base.map((v) => ({ ...v })),
      header: vars.header.map((v) => ({ ...v })),
      body: vars.body.map((v) => ({ ...v })),
    })
    setSignForm({
      enabled: sign?.enabled ?? false,
      appId: sign?.appId ?? '',
      appSecret: '',
      nonce: sign?.nonce ?? '',
      mgId: sign?.mgId ?? '',
    })
    // 每次打开都从「遮住」开始（需求 #39）：上一次点过「显示」不该让这次一打开就明文
    setSecretShown(false)
    setEnvTab('vars')
    setEnvOpen(true)
    void loadEnv(activeEnv)
  }

  /** 按需取回密钥明文并显示；再点一次收起。 */
  const toggleSecret = async () => {
    if (!envReady) return
    if (secretEdited) { setSecretShown(value => !value); return }
    const seq = ++secretSeq.current
    if (secretShown) {
      setSecretShown(false)
      setSignForm((f) => ({ ...f, appSecret: '' }))
      return
    }
    setSecretLoading(true)
    try {
      const r = await apitestApi.signSecret(activeEnv)
      if (seq !== secretSeq.current) return
      if (!r.saved || r.appSecret == null) {
        toast.error('还没存过密钥，先填一个再保存')
        return
      }
      setSignForm((f) => ({ ...f, appSecret: r.appSecret as string }))
      setSecretShown(true)
    } catch (e) {
      if (seq !== secretSeq.current) return
      toast.error(`读取密钥失败：${(e as Error).message}`)
    } finally {
      if (seq === secretSeq.current) setSecretLoading(false)
    }
  }

  /** 新建环境（v1.10.7 #87）。建完自动切过去，编辑区跟着换一套。 */
  const createEnv = async () => {
    if (!envDraft.trim()) return
    try {
      const e = await apitestApi.createEnv(envDraft.trim())
      await loadEnvs()
      changeEnv(e.id)
      setEnvNewOpen(false)
      toast.success(`已创建环境「${e.name}」`)
    } catch (e) {
      toast.error(`创建环境失败：${(e as Error).message}`)
    }
  }

  /** 复制环境：变量与签名一起复制。换环境最常用的动作就是「拿一套改几个值」。 */
  const duplicateEnv = async () => {
    try {
      const e = await apitestApi.duplicateEnv(activeEnv)
      await loadEnvs()
      changeEnv(e.id)
      toast.success(`已复制环境「${e.name}」（含变量与签名）`)
    } catch (e) {
      toast.error(`复制环境失败：${(e as Error).message}`)
    }
  }

  /** 删除环境：变量与签名一并删；引用它的用例分组回到默认环境。默认环境不可删。 */
  const removeEnv = async (id: number, name: string) => {
    if (!window.confirm(
      `删除环境「${name}」？其中的变量与签名会一并删除；绑定它的用例分组将回到默认环境。`)) return
    try {
      await apitestApi.deleteEnv(id)
      envDrafts.current.delete(id)
      await loadEnvs()
      if (activeEnv === id) setActiveEnv(1)
      await loadGroups()
    } catch (e) {
      toast.error(`删除环境失败：${(e as Error).message}`)
    }
  }

  /** 把当前查看的用例分组绑定到某个环境（v1.10.7 #87）。 */
  const bindGroupEnv = async (groupId: number, groupName: string, envId: number) => {
    try {
      await apitestApi.renameCaseGroup(groupId, groupName, envId)
      await loadGroups()
    } catch (e) {
      toast.error(`绑定环境失败：${(e as Error).message}`)
    }
  }

  /**
   * 保存环境设置。
   *
   * 两块一起提交：变量与签名是同一个环境的两面，分开存会出现「刚填的变量没生效、以为变量不好使」
   * 这种查不出原因的错位。密钥留空 = 不改动原来那个（后端按这个语义处理）。
   */
  const saveEnv = async () => {
    if (!envReady || savingEnv) return
    setSavingEnv(true)
    try {
      const { sign: savedSign, vars: savedVars } = await apitestApi.saveEnvironment(
        activeEnv, signForm, {
          // 名字空着的行不进库：存了也匹配不到任何占位符，只会让下次打开时多一行空白
          base: varForm.base.filter((v) => v.name.trim()),
          header: varForm.header.filter((v) => v.name.trim()),
          body: varForm.body.filter((v) => v.name.trim()),
        })
      setSign(savedSign)
      setVars(savedVars)
      envDrafts.current.delete(activeEnv); setSecretEdited(false)
      // 关弹窗即恢复遮住（需求 #39）：明文不该在界面状态里留着
      setSecretShown(false)
      setSignForm((f) => ({ ...f, appSecret: '' }))
      setEnvOpen(false)
      if (savedSign.enabled && !savedSign.ready) {
        toast.error('已保存，但还缺 app_id 或密钥，现在执行会直接报错')
      }
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSavingEnv(false)
    }
  }

  /** 变量表的行操作。三组用同一套函数，靠 scope 区分写哪一组。 */
  const addVar = (scope: 'base' | 'header' | 'body') =>
    setVarForm((f) => ({ ...f, [scope]: [...f[scope], { name: '', value: '' }] }))

  const setVar = (scope: 'base' | 'header' | 'body', i: number, patch: Partial<ApiVarItem>) =>
    setVarForm((f) => ({
      ...f,
      [scope]: f[scope].map((v, k) => (k === i ? { ...v, ...patch } : v)),
    }))

  const removeVar = (scope: 'base' | 'header' | 'body', i: number) =>
    setVarForm((f) => ({ ...f, [scope]: f[scope].filter((_, k) => k !== i) }))

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!cases) return []
    if (!q) return cases
    return cases.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.url.toLowerCase().includes(q) ||
        c.method.toLowerCase().includes(q),
    )
  }, [cases, search])

  // ---- 分组（需求 #48） ----

  /** 当前分组下的用例（在搜索过滤之后再按分组筛，两个条件是「与」的关系）。 */
  const visibleCases = useMemo(() => {
    if (activeGroup === 'all') return filtered
    if (activeGroup === 'none') return filtered.filter((c) => c.groupId == null)
    const gid = Number(activeGroup)
    return filtered.filter((c) => c.groupId === gid)
  }, [filtered, activeGroup])

  /** 未分组的用例数。它不是真实分组，所以要自己数。 */
  const ungroupedCount = useMemo(
    () => (cases ?? []).filter((c) => c.groupId == null).length,
    [cases],
  )

  const groupCount = groups?.length ?? 0

  /**
   * 分组列表。前两项是「伪分组」：全部 / 未分组 —— 它们没有 id，靠 key 区分。
   * 顺带在这里算好每个组的计数，渲染时不必再遍历用例。
   */
  const groupCards = useMemo(() => {
    const list: { key: string; label: string; count: number; id: number | null }[] = [
      { key: 'all', label: '全部', count: cases?.length ?? 0, id: null },
      { key: 'none', label: '未分组', count: ungroupedCount, id: null },
    ]
    for (const g of groups ?? []) {
      list.push({ key: String(g.id), label: g.name, count: g.caseCount, id: g.id })
    }
    return list
  }, [groups, cases, ungroupedCount])

  const createGroup = async () => {
    if (!newGroupName.trim()) {
      toast.error('分组名不能为空')
      return
    }
    setSavingGroup(true)
    try {
      const g = await apitestApi.createCaseGroup(newGroupName.trim())
      await loadGroups()
      setNewGroupName('')
      setNewGroupOpen(false)
      // 建完立刻切到新组：建一个空组却停在原地，看起来像「没建成」
      setActiveGroup(String(g.id))
    } catch (e) {
      toast.error(`新建分组失败：${(e as Error).message}`)
    } finally {
      setSavingGroup(false)
    }
  }

  const renameGroup = async (id: number) => {
    const name = groupDraft.trim()
    if (!name) {
      toast.error('分组名不能为空')
      return
    }
    setSavingGroup(true)
    try {
      await apitestApi.renameCaseGroup(id, name)
      await loadGroups()
      setGroupEditing(null)
      setGroupDraft('')
    } catch (e) {
      toast.error(`重命名失败：${(e as Error).message}`)
    } finally {
      setSavingGroup(false)
    }
  }

  /**
   * 删除分组。后端只把组内用例变回未分组，不删用例 —— 
   * 但界面上仍要说清楚，否则「删分组」这四个字会被理解成「连用例一起删」。
   */
  const removeGroup = async (id: number, label: string, count: number) => {
    const extra = count > 0 ? `，组内 ${count} 条用例会变成「未分组」（不会删除）` : ''
    if (!window.confirm(`删除分组「${label}」${extra}？`)) return
    try {
      await apitestApi.deleteCaseGroup(id)
      await Promise.all([loadGroups(), loadCases()])
      // 删掉的正是当前查看的组时，退回「全部」—— 否则会停在一个已不存在的分组上，列表空白
      if (activeGroup === String(id)) setActiveGroup('all')
    } catch (e) {
      toast.error(`删除分组失败：${(e as Error).message}`)
    }
  }

  // ---- 用例操作 ----

  const togglePick = (id: number) => {
    if (running) return
    setOrderedPicked(prev => prev.includes(id) ? prev.filter(value => value !== id) : [...prev, id])
  }

  const isAllVisiblePicked = useMemo(
    () => visibleCases.length > 0 && visibleCases.every((c) => picked.has(c.id)),
    [visibleCases, picked],
  )
  const isSomeVisiblePicked = useMemo(
    () => visibleCases.some((c) => picked.has(c.id)) && !isAllVisiblePicked,
    [visibleCases, picked, isAllVisiblePicked],
  )

  const togglePickAllVisible = useCallback(() => {
    if (running || visibleCases.length === 0) return
    if (isAllVisiblePicked) {
      const visibleIds = new Set(visibleCases.map((c) => c.id))
      setOrderedPicked((prev) => prev.filter((id) => !visibleIds.has(id)))
    } else {
      const visibleIds = visibleCases.map((c) => c.id)
      setOrderedPicked((prev) => {
        const set = new Set(prev)
        const next = [...prev]
        for (const id of visibleIds) {
          if (!set.has(id)) {
            set.add(id)
            next.push(id)
          }
        }
        return next
      })
    }
  }, [running, visibleCases, isAllVisiblePicked])

  const openNew = () => {
    setEditingId(null)
    // 正在看某个分组时新建，默认就归到那个组 —— 否则每次都要在建完之后再改一次分组
    const initialForm: CaseForm = {
      ...EMPTY_FORM,
      groupId: activeGroup === 'all' || activeGroup === 'none' ? '' : activeGroup,
      asserts: [],
      options: emptyOptions(),
    }
    caseEditorBaseline.current = draftFingerprint(initialForm)
    setCaseDiscardPending(false)
    setForm(initialForm)
    setEditorOpen(true)
  }

  const openEdit = (c: ApiCase) => {
    setEditingId(c.id)
    const initialForm: CaseForm = {
      name: c.name,
      method: c.method,
      url: c.url,
      headers: c.headers ?? '',
      body: c.body ?? '',
      note: c.note ?? '',
      expectStatus: c.expectStatus ?? '',
      expectCode: c.expectCode ?? '',
      timeoutMs: c.timeoutMs ?? 10000,
      insecure: c.insecure,
      enabled: c.enabled,
      groupId: c.groupId == null ? '' : String(c.groupId),
      asserts: parseAsserts(c.asserts),
      options: c.options ?? emptyOptions(),
      assertsOriginal: c.asserts,
      assertsInvalid: assertionsBroken(c.asserts),
    }
    caseEditorBaseline.current = draftFingerprint(initialForm)
    setCaseDiscardPending(false)
    setForm(initialForm)
    setEditorOpen(true)
  }

  const saveCase = async () => {
    if (form.assertsInvalid) { toast.error('原断言配置无法解析，请先明确修正后保存'); return }
    if (!form.url.trim()) {
      toast.error('地址不能为空')
      return
    }
    setSaving(true)
    try {
      // 表单里的 groupId 是字符串（下拉要求），这里统一转成后端的语义：
      // 空串 = 未分组 = null
      // 断言同理：界面上是按行编辑的，后端收的是 JSON 文本 —— 转换只放在这一处。
      const { groupId, asserts, assertsOriginal: _assertsOriginal, assertsInvalid: _assertsInvalid, ...rest } = form
      const payload = {
        ...rest,
        groupId: groupId ? Number(groupId) : null,
        asserts: serializeAsserts(asserts),
        id: editingId,
      }
      if (editingId == null) {
        await apitestApi.createCase(payload)
      } else {
        await apitestApi.updateCase(editingId, payload)
      }
      setEditorOpen(false)
      // 用例的分组可能变了，两个列表都要刷：用例列表与分组计数
      await Promise.all([loadCases(), loadGroups()])
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  // removeCase 已删除（v1.10.6）：行尾的单条删除按钮取消，删除统一走操作条的「删除选中」

  const duplicateCase = async (c: ApiCase) => {
    try {
      await apitestApi.duplicateCase(c.id)
      await loadCases()
    } catch (e) {
      toast.error(`复制失败：${(e as Error).message}`)
    }
  }

  /**
   * 批量删除勾选的用例（v1.10.6）。
   *
   * <p>二次确认必给，且把条数写进文案 —— 用例是填了半天的资产，误删不可撤销。
   */
  const deletePicked = async () => {
    const ids = [...picked]
    if (ids.length === 0) return
    if (!window.confirm(`确定删除勾选的 ${ids.length} 条用例？（历史执行记录会保留）`)) return
    try {
      const r = await apitestApi.batchDeleteCases(ids)
      setOrderedPicked([])
      await Promise.all([loadCases(), loadGroups()])
      toast.success(`已删除 ${r.deleted} 条`)
    } catch (e) {
      toast.error(`批量删除失败：${(e as Error).message}`)
    }
  }

  /**
   * 执行批量迁移（v1.10.6）。
   *
   * <p>两种方式都做二次确认，文案不同：「直接移过去」会丢原位置，必须让人看到这句话再确认。
   */
  const applyMove = async () => {
    const ids = [...picked]
    if (ids.length === 0) return
    const target = moveTarget === '__none__' ? null : Number(moveTarget)
    const targetLabel = moveTarget === '__none'
      ? '未分组'
      : (groups?.find((g) => String(g.id) === moveTarget)?.name ?? `#${moveTarget}`)
    const action = moveCopy ? '复制' : '移动'
    if (!window.confirm(
      `确定把勾选的 ${ids.length} 条用例${action}到「${targetLabel}」？`
      + (moveCopy ? '（原分组保留一份）' : '（原分组将不再保留）'))) {
      return
    }
    try {
      const r = await apitestApi.batchMoveCases(ids, target, moveCopy)
      setOrderedPicked([])
      setMoveOpen(false)
      await Promise.all([loadCases(), loadGroups()])
      toast.success(`已${action} ${r.affected} 条到「${targetLabel}」`)
    } catch (e) {
      toast.error(`迁移失败：${(e as Error).message}`)
    }
  }

  /** 复制分组（v1.10.6）：连组内用例一起复制，新名字由后端取序号。 */
  const duplicateGroup = async (id: number, label: string) => {
    try {
      const g = await apitestApi.duplicateCaseGroup(id)
      await Promise.all([loadGroups(), loadCases()])
      toast.success(`已复制「${label}」→「${g.name}」（含组内用例）`)
    } catch (e) {
      toast.error(`复制分组失败：${(e as Error).message}`)
    }
  }

  // ---- 执行 ----

  const runPicked = async () => {
    if (picked.size === 0) {
      toast.error('请先勾选要执行的用例')
      return
    }
    const seq = selectBatch(null)
    setRunning(true)
    try {
      const res = await apitestApi.run(orderedPicked)
      if (seq !== batchSeq.current) return
      setSyncResults(res)
    } catch (e) {
      if (seq !== batchSeq.current) return
      toast.error(`执行失败：${(e as Error).message}`)
    } finally {
      if (seq === batchSeq.current) setRunning(false)
    }
  }

  const startBatch = async () => {
    if (picked.size === 0) {
      toast.error('请先勾选要执行的用例')
      return
    }
    const repeat = forever ? -1 : repeatCount
    setScheduleOpen(false)
    const seq = selectBatch(null)
    setRunning(true)
    try {
      const b = await apitestApi.startBatch('', orderedPicked, intervalSeconds, repeat)
      await loadBatches()
      if (seq !== batchSeq.current) return
      activeBatchRef.current = b.id
      setActiveBatchId(b.id)
      void pollBatch(b.id, seq)
    } catch (e) {
      if (seq !== batchSeq.current) return
      setRunning(false)
      toast.error(`启动失败：${(e as Error).message}`)
    }
  }

  const stopBatch = async (immediate = false) => {
    if (activeBatchId == null) return
    try {
      await req(`/api/apitest/batches/${activeBatchId}/stop`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: immediate ? 'IMMEDIATE' : 'AFTER_CURRENT' }) })
      // 这一条是「请求已收到、但还没停」，用户看不到任何变化，必须说一声；
      // 它不是成功提示，所以留着
      toast.info(immediate ? '已请求取消当前请求，随后执行清理步骤；请核对服务器是否已经处理请求。' : '已请求停止：当前请求完成后停止普通步骤，并执行已配置的清理步骤')
    } catch (e) {
      toast.error(`停止失败：${(e as Error).message}`)
    }
  }

  const openBatch = async (id: number) => {
    const seq = selectBatch(id)
    setRunning(batches.some(batch => batch.id === id && batch.status === 'RUNNING'))
    await pollBatch(id, seq)
  }

  const routeBatch = useRef<number | undefined>(undefined)
  useEffect(() => {
    if (initialBatchId == null && routeBatch.current != null) { routeBatch.current = undefined; selectBatch(null); return }
    if (initialBatchId != null && routeBatch.current !== initialBatchId) {
      routeBatch.current = initialBatchId
      if (activeBatchRef.current !== initialBatchId) void openBatch(initialBatchId)
    }
  }, [initialBatchId])

  const exportBatch = (id: number) => {
    // 导出前明确提醒一次：CSV 里含接口地址与响应内容，属于内部资料
    if (
      !window.confirm(
        `导出的 CSV 含接口地址、请求结果等内容，属于内部资料，请勿外发。\n\n确定导出批次 #${id}？`,
      )
    ) {
      return
    }
    window.open(apiBatchExportUrl(id), '_blank')
  }

  const deleteBatch = async (id: number) => {
    if (!window.confirm(`删除批次 #${id} 及其全部执行记录？此操作不可恢复。`)) return
    try {
      await apitestApi.deleteBatch(id)
      if (activeBatchRef.current === id) selectBatch(null)
      await loadBatches()
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`)
    }
  }

  // ---- 字典操作 ----

  const addCode = async () => {
    const code = Number(newCode.code)
    if (!newCode.code.trim() || Number.isNaN(code)) {
      toast.error('返回码要填数字')
      return
    }
    if (!newCode.message.trim()) {
      toast.error('说明不能为空')
      return
    }
    try {
      await apitestApi.addCode(newCode.scope, code, newCode.message.trim())
      setNewCode({ scope: newCode.scope, code: '', message: '' })
      await loadCodes()
    } catch (e) {
      toast.error(`新增失败：${(e as Error).message}`)
    }
  }

  const removeCode = async (c: ApiCode) => {
    if (!window.confirm(`删除自定义返回码 ${c.code}「${c.message}」？`)) return
    try {
      await apitestApi.deleteCode(c.id)
      await loadCodes()
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`)
    }
  }

  // ---- 结果表数据源：批次明细优先，否则同步执行结果 ----
  const execs: ApiExec[] = detail ? detail.execs : syncResults
  const stat = useMemo(() => {
    const s = { pass: 0, fail: 0, error: 0, skip: 0 }
    for (const e of execs) {
      if (e.verdict === 'PASS') s.pass++
      else if (e.verdict === 'FAIL') s.fail++
      else if (e.verdict === 'SKIP') s.skip++
      else s.error++
    }
    return s
  }, [execs])

  const toggleExpanded = async (id: number) => {
    const opening = !expanded.has(id)
    if(!opening)setRevealedExecs(current => { const next=new Set(current); next.delete(id); return next })
    setExpanded(prev => { const next = new Set(prev); if (next.has(id)) next.delete(id); else next.add(id); return next })
    if (!opening || activeBatchRef.current == null || fullExecutions.current.has(id)) return
    const seq = batchSeq.current
    setExecutionErrors(previous => ({ ...previous, [id]: '' }))
    try {
      const item = await req<ApiExec>(`/api/apitest/history/${id}`)
      if (seq !== batchSeq.current) return
      fullExecutions.current.add(id)
      batchRows.current = batchRows.current.map(row => row.id === id ? item : row)
      setDetail(previous => previous ? { ...previous, execs: batchRows.current } : previous)
    } catch (problem) { if (seq === batchSeq.current) setExecutionErrors(previous => ({ ...previous, [id]: problem instanceof Error ? problem.message : String(problem) })) }
  }


  return (
    <div className="space-y-4">
      {Object.entries(listErrors).filter(([, error]) => error).map(([key, error]) => <div key={key} role="status" data-testid={`api-list-error-${key}`} className="flex flex-wrap items-center gap-2 rounded-md border border-amber-500/40 p-3 text-sm"><span>{key}加载失败：{error}。已有结果保留。</span><Button size="sm" variant="outline" onClick={() => void (({ '用例': loadCases, '分组': loadGroups, '批次': loadBatches, '返回码字典': loadCodes } as Record<string, () => Promise<void>>)[key]?.())}>重试{key}</Button></div>)}
      <div data-testid="quick-open"><EntryCard icon={Send} title="快速调试" desc="选择环境直接发送 HTTP 请求，调通后保存为用例" onClick={() => setQuickOpen(true)} /></div>
      <RequestWorkbench open={quickOpen} onOpenChange={setQuickOpen} envs={envs ?? []} cases={cases ?? []} groups={groups ?? []} context={queryContext} historyDraft={historyDraft} onSaved={() => { void loadCases(); void loadGroups() }} />
      {/* ==================== 用例管理入口（需求 #48 / #50） ==================== */}
      {/* 用例整块（含分组）收进弹窗，这里只留一张入口卡片。
          样式与首页的功能跳转卡片同一套（共用 components/entry-card）：
          方形图标块 + 标题 + 一行说明，悬停浮出箭头。
          用卡片而不是裸按钮，是因为「按钮」读起来像一次性动作，
          而这里点开是进入一个工作区 —— 卡片的语义更准。 */}
      <EntryCard
        icon={ListChecks}
        title="用例管理"
        desc={
          cases == null
            ? '加载中…'
            : `${groupCount > 0 ? `${groupCount} 个分组 · ` : ''}共 ${cases.length} 条用例`
        }
        right={
          // 选中数必须一眼看到：它决定「执行选中」会跑几条，
          // 而弹窗关掉之后页面上没有别的地方显示它
          <span className="shrink-0 rounded bg-secondary px-2 py-0.5 font-mono text-xs font-medium text-secondary-foreground">
            已选 {picked.size}
          </span>
        }
        footer={
          // 环境设置搬进弹窗后，这里是主页面唯一还看得见的签名信号 ——
          // 「开着但缺凭证」会让每一次执行都认证失败，不该等点进去才发现。
          sign ? (
            <div
              className={cn(
                'mt-1.5 flex items-center gap-1.5 text-xs',
                sign.enabled && !sign.ready
                  ? 'text-amber-600 dark:text-amber-400'
                  : 'text-muted-foreground',
              )}
            >
              <span
                className={cn(
                  'h-1.5 w-1.5 rounded-full',
                  sign.enabled
                    ? sign.ready
                      ? 'bg-emerald-500'
                      : 'bg-amber-500'
                    : 'bg-muted-foreground/40',
                )}
              />
              自动签名{sign.enabled ? (sign.ready ? '已开启' : '已开启 · 缺凭证') : '未开启'}
            </div>
          ) : null
        }
        onClick={() => setCasesOpen(true)}
        className="max-w-xs"
      />

      {/* ==================== 用例管理弹窗（需求 #41/#42/#43/#44） ==================== */}
      <Dialog open={casesOpen} onOpenChange={setCasesOpen}>
        {/* 宽度必须写成 sm:max-w-* —— 见用例编辑弹窗那条注释。
            它比编辑弹窗宽一档：装的是列表，一行要放得下方法、名称、期望值和整条地址。 */}
        {/* 固定外壳（v1.10.6）：原来是「最大高度 90vh」，切到用例少的分组时内容撑不满、
            外壳跟着缩 —— 一眼看过去像弹窗闪了一下。改成固定高度 + 内部滚动，
            与环境设置弹窗同一套做法（那一个在 v1.9.3 已经这样改过）。 */}
        <DialogContent className="flex h-[85vh] max-h-[92vh] flex-col gap-4 overflow-hidden border border-border/80 bg-background/95 backdrop-blur-xl shadow-2xl sm:max-w-6xl">
          <DialogHeader className="flex-row flex-wrap items-center gap-2 space-y-0 pr-8">
            <DialogTitle className="mr-auto flex items-center gap-2">
              用例管理
              {cases != null && (
                <span className="text-sm font-normal text-muted-foreground">
                  共 {cases.length} 条，已选 {picked.size} 条
                </span>
              )}
            </DialogTitle>
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground transition-colors group-focus-within:text-primary" />
              <Input
                className="h-8 w-56 rounded-md border-white/10 bg-background/40 backdrop-blur-md pl-8 pr-7 text-sm transition-all focus:border-primary/60 focus:bg-background/70 focus:ring-1 focus:ring-primary/30 shadow-xs"
                placeholder="搜名称 / 地址 / 方法"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
              {search && (
                <button
                  type="button"
                  className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                  onClick={() => setSearch('')}
                  title="清空搜索"
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
            <Button
              size="icon"
              variant="outline"
              className="h-8 w-8 rounded-lg border-white/10 bg-background/40 backdrop-blur-md hover:border-primary/50 hover:bg-primary/10 transition-all shadow-xs"
              title="重新拉取用例与分组"
              onClick={() => {
                void loadCases()
                void loadGroups()
              }}
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </Button>
          </DialogHeader>

          {/* 当前分组的环境绑定 */}
          {activeGroup !== 'all' && activeGroup !== 'none' && (
            <div className="flex shrink-0 items-center justify-between rounded-xl border border-white/10 bg-card/40 backdrop-blur-xl px-3.5 py-2 shadow-xs transition-all">
              <div className="flex items-center gap-2">
                <Globe className="h-4 w-4 text-primary/80" />
                <span className="text-sm font-medium text-foreground/80">当前分组绑定的环境：</span>
              </div>
              <select
                className="h-8 rounded-lg border border-white/10 bg-background/50 backdrop-blur-md px-3 text-sm text-foreground shadow-xs transition-all cursor-pointer hover:border-primary/50 hover:bg-background/80 focus:border-primary/60 focus:bg-background/90 focus:ring-1 focus:ring-primary/30 outline-none"
                value={String(
                  (groups ?? []).find((g) => String(g.id) === activeGroup)?.envId ?? '',
                )}
                onChange={(e) => {
                  const g = (groups ?? []).find((x) => String(x.id) === activeGroup)
                  if (!g || g.id == null) return
                  const v = e.target.value === '' ? undefined : Number(e.target.value)
                  void bindGroupEnv(g.id, g.name, v as number)
                }}
                data-testid="case-group-env"
              >
                <option value="">未绑定（用默认环境）</option>
                {(envs ?? []).map((e) => (
                  <option key={e.id} value={String(e.id)}>
                    {e.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* 功能入口卡片行 */}
          <div className="grid shrink-0 gap-2 sm:grid-cols-2">
            <EntryCard
              compact
              icon={SlidersHorizontal}
              title="环境设置"
              desc={
                sign
                  ? `变量与签名 · 自动签名${
                      sign.enabled ? (sign.ready ? '已开启' : '已开启但缺凭证') : '未开启'
                    }`
                  : '变量与签名'
              }
              onClick={openEnv}
              className={cn(
                'rounded-xl border-white/10 bg-card/40 backdrop-blur-xl shadow-xs transition-all duration-200 hover:border-primary/50 hover:bg-card/70 hover:shadow-md',
                sign?.enabled && !sign.ready && 'border-amber-500/50 bg-amber-500/10',
              )}
            />
            <EntryCard
              compact
              icon={Plus}
              title="新建用例"
              desc={
                activeGroup === 'all' || activeGroup === 'none'
                  ? '从零建一条；地址、请求头、请求体都在里面填'
                  : `新建后自动归到「${
                      (groups ?? []).find((g) => String(g.id) === activeGroup)?.name ?? '当前分组'
                    }」`
              }
              onClick={openNew}
              className="rounded-xl border-white/10 bg-card/40 backdrop-blur-xl shadow-xs transition-all duration-200 hover:border-primary/50 hover:bg-card/70 hover:shadow-md"
            />
          </div>

          {/* 两栏主体：类名保留 sm:grid-cols-[220px_1fr]，左侧通过 sm:w-[260px] 拓宽，确保单行显示 7 个汉字以上 */}
          <div className="grid min-h-0 flex-1 gap-3 sm:grid-cols-[220px_1fr] sm:grid-cols-[260px_1fr]">
            {/* ---------- 左栏：分组导航栏 ---------- */}
            <div className="custom-dark-scrollbar flex min-h-0 flex-col gap-1.5 overflow-y-auto pr-0.5">
              <div className="flex items-center justify-between px-0.5">
                <span className="text-sm font-semibold text-foreground/80">分组</span>
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-7 px-2 text-xs font-medium hover:bg-primary/10 hover:text-primary transition-colors"
                  onClick={() => {
                    setNewGroupOpen(true)
                    setNewGroupName('')
                    setGroupEditing(null)
                  }}
                >
                  <Plus className="mr-0.5 h-3 w-3" />
                  新建
                </Button>
              </div>

              {/* 新建分组输入行 */}
              {newGroupOpen && (
                <div className="flex items-center gap-1 rounded-md border border-primary/40 bg-primary/5 p-1">
                  <Input
                    autoFocus
                    className="h-7 text-xs"
                    placeholder="分组名"
                    value={newGroupName}
                    onChange={(e) => setNewGroupName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void createGroup()
                      if (e.key === 'Escape') setNewGroupOpen(false)
                    }}
                  />
                  <Button
                    size="icon"
                    variant="ghost"
                    className="h-7 w-7 shrink-0"
                    disabled={savingGroup}
                    title="确定新建"
                    onClick={() => void createGroup()}
                  >
                    <Check className="h-3.5 w-3.5" />
                  </Button>
                  <Button
                    size="icon"
                    variant="ghost"
                    className="h-7 w-7 shrink-0"
                    title="取消"
                    onClick={() => setNewGroupOpen(false)}
                  >
                    <X className="h-3.5 w-3.5" />
                  </Button>
                </div>
              )}

              {groups == null ? (
                <Skeleton className="h-8 w-full" />
              ) : (
                groupCards.map((g) => {
                  const active = activeGroup === g.key
                  if (groupEditing != null && g.id === groupEditing) {
                    return (
                      <div
                        key={g.key}
                        className="flex items-center gap-1 rounded-md border border-primary/40 bg-primary/5 p-1"
                      >
                        <Input
                          autoFocus
                          className="h-7 text-xs"
                          value={groupDraft}
                          onChange={(e) => setGroupDraft(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') void renameGroup(g.id!)
                            if (e.key === 'Escape') setGroupEditing(null)
                          }}
                        />
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-7 w-7 shrink-0"
                          disabled={savingGroup}
                          title="确定改名"
                          onClick={() => void renameGroup(g.id!)}
                        >
                          <Check className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-7 w-7 shrink-0"
                          title="取消"
                          onClick={() => setGroupEditing(null)}
                        >
                          <X className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    )
                  }
                  return (
                    <div
                      key={g.key}
                      className={cn(
                        'group/g relative flex cursor-pointer items-center gap-2.5 rounded-xl border px-3 py-2 text-sm transition-all duration-150 transform-gpu shadow-xs',
                        active
                          ? 'border-primary/60 bg-primary/10 font-semibold text-foreground shadow-sm backdrop-blur-xl ring-1 ring-primary/20'
                          : 'border-white/5 bg-white/[0.025] hover:border-white/15 hover:bg-white/[0.07] backdrop-blur-md text-muted-foreground hover:text-foreground',
                      )}
                      onClick={() => setActiveGroup(g.key)}
                    >
                      <div
                        className={cn(
                          'flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary transition-colors',
                          active && 'bg-primary/25 font-semibold',
                        )}
                      >
                        <Folder className="h-4 w-4" />
                      </div>
                      <span className="min-w-0 flex-1 truncate text-sm font-medium" title={g.label}>
                        {g.label}
                      </span>
                      <span className="shrink-0 font-mono text-xs text-muted-foreground bg-white/[0.06] px-1.5 py-0.5 rounded-full">
                        {g.count}
                      </span>
                      {g.id != null && !(groupEditing != null && g.id === groupEditing) && (
                        <span className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover/g:opacity-100">
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-6 w-6"
                            title="改名"
                            onClick={(e) => {
                              e.stopPropagation()
                              setGroupEditing(g.id!)
                              setGroupDraft(g.label)
                              setNewGroupOpen(false)
                            }}
                          >
                            <Pencil className="h-3 w-3" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-6 w-6"
                            title="复制分组（连组内用例一起复制）"
                            data-testid="case-group-duplicate"
                            onClick={(e) => {
                              e.stopPropagation()
                              void duplicateGroup(g.id!, g.label)
                            }}
                          >
                            <Copy className="h-3 w-3" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-6 w-6 text-muted-foreground hover:text-red-500"
                            title="删除分组（组内用例保留，变成未分组）"
                            onClick={(e) => {
                              e.stopPropagation()
                              void removeGroup(g.id!, g.label, g.count)
                            }}
                          >
                            <Trash2 className="h-3 w-3" />
                          </Button>
                        </span>
                      )}
                    </div>
                  )
                })
              )}
            </div>

            {/* ---------- 右栏：该分组的用例 ---------- */}
            <div className="flex min-h-0 flex-col gap-2">
              {/* 批量操作条 */}
              <div
                className={cn(
                  'flex shrink-0 flex-wrap items-center gap-2 rounded-xl border px-3.5 py-2 transition-all duration-150 backdrop-blur-xl shadow-xs',
                  picked.size > 0
                    ? 'border-primary/50 bg-primary/15 shadow-sm'
                    : 'border-white/10 bg-card/40',
                )}
              >
                {moveOpen ? (
                  <>
                    <span className="text-sm font-medium">
                      把选中的 <b>{moveOpenCount}</b> 条用例
                    </span>
                    <Select value={moveTarget} onValueChange={setMoveTarget}>
                      <SelectTrigger className="h-8 w-44 text-sm bg-background/80 backdrop-blur-md border-border/80" data-testid="case-move-target">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__none__">未分组</SelectItem>
                        {(groups ?? []).filter((g) => g.id != null).map((g) => (
                          <SelectItem key={g.id} value={String(g.id)}>
                            {g.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <span className="flex items-center gap-1">
                      <Button
                        size="sm"
                        variant={moveCopy ? 'default' : 'outline'}
                        className="h-8 text-sm"
                        onClick={() => setMoveCopy(true)}
                      >
                        复制过去
                      </Button>
                      <Button
                        size="sm"
                        variant={moveCopy ? 'outline' : 'default'}
                        className="h-8 text-sm"
                        onClick={() => setMoveCopy(false)}
                      >
                        直接移过去
                      </Button>
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {moveCopy ? '原分组保留一份' : '原分组不再保留'}
                    </span>
                    <Button
                      size="sm"
                      className="h-8 text-sm"
                      onClick={() => void applyMove()}
                      data-testid="case-move-confirm"
                    >
                      确认迁移
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-8 text-sm"
                      onClick={() => setMoveOpen(false)}
                    >
                      取消
                    </Button>
                  </>
                ) : (
                  <>
                    {/* 全选复选框 */}
                    <div
                      className="flex items-center gap-1.5 pr-1 cursor-pointer select-none"
                      onClick={togglePickAllVisible}
                      title={isAllVisiblePicked ? '取消全选当前用例' : '全选当前视图所有用例'}
                    >
                      <Checkbox
                        checked={isAllVisiblePicked ? true : isSomeVisiblePicked ? 'indeterminate' : false}
                        disabled={running || visibleCases.length === 0}
                        className="transition-transform duration-150 active:scale-90"
                      />
                      <span className="text-sm font-medium text-foreground/80">全选</span>
                    </div>

                    <span className="text-sm text-muted-foreground" data-testid="case-batch-hint">
                      {picked.size > 0
                        ? `已选 ${picked.size} 条`
                        : '勾选用例后可批量删除或迁移'}
                    </span>
                    {picked.size > 0 && (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 px-2.5 text-sm font-medium text-red-600 hover:text-red-700 border-red-500/30 bg-red-500/10 hover:bg-red-500/20"
                          onClick={() => void deletePicked()}
                          data-testid="case-batch-delete"
                        >
                          <Trash2 className="mr-1.5 h-3.5 w-3.5" />
                          删除选中
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 px-2.5 text-sm font-medium text-primary border-primary/30 bg-primary/10 hover:bg-primary/20"
                          onClick={() => {
                            setMoveTarget('__none__')
                            setMoveCopy(false)
                            setMoveOpen(true)
                          }}
                          data-testid="case-batch-move"
                        >
                          <FolderInput className="mr-1.5 h-3.5 w-3.5" />
                          迁移选中
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-8 px-2.5 text-sm text-muted-foreground hover:text-foreground"
                          onClick={() => setOrderedPicked([])}
                        >
                          取消勾选
                        </Button>
                      </>
                    )}
                  </>
                )}
              </div>
              <div className="custom-dark-scrollbar min-h-0 overflow-x-hidden overflow-y-auto px-0.5">
                <div key={activeGroup} className="space-y-1.5 py-0.5 pr-9 animate-spring-pop">
                  {cases == null ? (
                    <>
                      <Skeleton className="h-14 w-full" />
                      <Skeleton className="h-14 w-full" />
                      <Skeleton className="h-14 w-full" />
                    </>
                  ) : visibleCases.length === 0 ? (
                    <div className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">
                      {cases.length === 0
                        ? '还没有用例'
                        : search.trim()
                          ? '这个分组里没有匹配的用例'
                          : activeGroup === 'all'
                            ? '还没有用例'
                            : '这个分组还是空的 —— 新建用例时选它，或编辑已有用例改分组'}
                    </div>
                  ) : (
                    visibleCases.map((c) => (
                      <div
                        key={c.id}
                        onClick={() => togglePick(c.id)}
                        className={cn(
                          'group relative flex cursor-pointer items-start gap-3 rounded-xl border px-3.5 py-2.5 transition-all duration-150 transform-gpu will-change-transform',
                          'origin-left hover:z-10 hover:scale-[1.03] hover:shadow-md select-none',
                          picked.has(c.id)
                            ? 'border-primary/60 bg-primary/10 shadow-xs backdrop-blur-xl ring-1 ring-primary/20'
                            : 'border-white/8 bg-white/[0.03] hover:border-white/20 hover:bg-white/[0.07] backdrop-blur-xl shadow-xs',
                          !c.enabled && 'opacity-60',
                        )}
                      >
                        {/* 复选框 */}
                        <span className="shrink-0" onClick={(e) => e.stopPropagation()}>
                          <Checkbox
                            className="mt-0.5 transition-transform duration-150 active:scale-90"
                            checked={picked.has(c.id)}
                            disabled={running}
                            data-testid={`case-pick-${c.id}`}
                            onCheckedChange={() => togglePick(c.id)}
                          />
                        </span>
                        {/* 一条两行：第一行方法 + 名称 + 期望值，第二行地址 */}
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <Badge
                              variant="outline"
                              className={cn(
                                'shrink-0 font-mono text-xs font-semibold tracking-wider px-2 py-0.5 rounded shadow-xs',
                                getMethodBadgeStyle(c.method),
                              )}
                            >
                              {c.method}
                            </Badge>
                            <span className="min-w-0 truncate text-sm font-medium text-foreground" title={c.name}>
                              {c.name}
                              {!c.enabled && (
                                <span className="ml-2 text-xs text-muted-foreground">（未启用）</span>
                              )}
                            </span>
                            <div className="ml-auto flex shrink-0 items-center gap-1.5">
                              {(c.expectStatus || c.expectCode) && (
                                <Badge
                                  variant="secondary"
                                  className="shrink-0 font-mono text-xs px-2 py-0.5"
                                >
                                  期望 {c.expectStatus || '—'}/{c.expectCode || '—'}
                                </Badge>
                              )}
                              {assertCount(c.asserts) > 0 && (
                                <Badge variant="outline" className="shrink-0 font-mono text-xs px-2 py-0.5">
                                  响应体 {assertCount(c.asserts)}
                                </Badge>
                              )}
                            </div>
                          </div>
                          {/* 第二行：高对比 URL 显示（环境变量与接口路径清晰区分） */}
                          <div className="mt-1 truncate" title={c.url}>
                            <FormattedUrl url={c.url} />
                          </div>
                        </div>
                        {/* 操作按钮区 */}
                        <div
                          className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity duration-150 group-hover:opacity-100"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-7 w-7 hover:text-emerald-400"
                            title="单条执行"
                            disabled={running}
                            onClick={() => {
                              setOrderedPicked([c.id])
                              const seq = selectBatch(null)
                              setRunning(true)
                              void apitestApi.run([c.id]).then(r => {
                                if (seq === batchSeq.current) setSyncResults(r)
                              }).catch(error => {
                                if (seq === batchSeq.current) toast.error(`执行失败：${(error as Error).message}`)
                              }).finally(() => { if (seq === batchSeq.current) setRunning(false) })
                            }}
                          >
                            <Play className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-7 w-7 hover:text-primary"
                            title="编辑"
                            onClick={() => openEdit(c)}
                          >
                            <Pencil className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-7 w-7 hover:text-primary"
                            title="复制一份（改地址/请求体最常用）"
                            onClick={() => void duplicateCase(c)}
                          >
                            <Copy className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          </div>

          <DialogFooter className="shrink-0">
            <p className="mr-auto text-xs text-muted-foreground">
              左边选分组、右边勾用例；勾选状态在关掉弹窗后仍然保留。支持整行点击与快捷全选。
            </p>
            <Button variant="outline" onClick={() => setCasesOpen(false)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <ScenarioManager cases={cases ?? []} envs={envs ?? []} groups={groups ?? []} pickedIds={orderedPicked} running={running} onRun={b => {
        if (!batchMounted.current) return
        const seq = selectBatch(b.id)
        setRunning(true)
        void loadBatches()
        void pollBatch(b.id, seq)
      }} />
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="outline" onClick={() => setConfigArchiveOpen(true)}>配置备份与迁移</Button>
        <ConfigArchiveDialog open={configArchiveOpen} onOpenChange={setConfigArchiveOpen} onCommitted={() => { void loadCases(); void loadGroups(); void loadEnvs() }} />
        <TransferDialog envs={envs ?? []} onCommitted={() => { void loadCases(); void loadGroups(); void loadEnvs() }} />
        <DataRunner ids={orderedPicked} running={running} onRun={b => { if (!batchMounted.current) return; const seq = selectBatch(b.id); setRunning(true); void loadBatches(); void pollBatch(b.id, seq) }} />
        <HistoryExplorer envs={envs ?? []} batchId={activeBatchId} onLoad={draft => { setHistoryDraft(draft); setQuickOpen(true) }} onFailed={ids => { const existing = ids.filter(id => cases?.some(c => c.id === id)); setOrderedPicked(current => [...new Set([...current, ...existing])]); toast.info(`已加入 ${existing.length} 条当前存在的用例，请核对执行计划后再运行；已删除的 ${ids.length - existing.length} 条已排除`) }} />
      </div>
      {/* ==================== 执行控制 ==================== */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">执行</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" disabled={running || picked.size === 0} onClick={() => void runPicked()}>
              {running && activeBatchId == null ? (
                <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
              ) : (
                <Play className="mr-1 h-3.5 w-3.5" />
              )}
              执行选中（{picked.size} 条）
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={running || picked.size === 0}
              onClick={() => setScheduleOpen(true)}
            >
              <Timer className="mr-1 h-3.5 w-3.5" />
              定时 / 批量
            </Button>
            {running && activeBatchId != null && (
              <Button size="sm" variant="destructive" onClick={() => void stopBatch()}>
                <Square className="mr-1 h-3.5 w-3.5" />
                停止批次 #{activeBatchId}
              </Button>
            )}
            {execs.length > 0 && (
              <div className="ml-auto flex items-center gap-2 text-xs">
                <span className="text-emerald-600 dark:text-emerald-400">通过 {stat.pass}</span>
                <span className="text-red-600 dark:text-red-400">未通过 {stat.fail}</span>
                <span className="text-amber-600 dark:text-amber-400">报错 {stat.error}</span>
                <span className="text-muted-foreground">跳过 {stat.skip} · 共 {execs.length}</span>
                {activeBatchId != null && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-7"
                    onClick={() => exportBatch(activeBatchId)}
                  >
                    <Download className="mr-1 h-3 w-3" />
                    导出 CSV
                  </Button>
                )}
              </div>
            )}
          </div>

          <RunPlan ids={orderedPicked} cases={cases ?? []} groups={groups ?? []} envs={envs ?? []} disabled={running} onChange={setOrderedPicked} />

          {batchProgressError && <div role="status" data-testid="batch-progress-error" className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-xs text-amber-700 dark:text-amber-400">{batchMissing ? batchProgressError : `进度查询失败，将自动重试：${batchProgressError}。当前显示最后一次已知结果。`}</div>}

          {activeBatchId != null && <div className="flex flex-wrap items-center gap-2 text-xs"><BatchReplay batchId={activeBatchId} running={running} onRun={batch => { setBatches(old => [batch, ...old]); const seq = selectBatch(batch.id); setRunning(true); void pollBatch(batch.id, seq) }} />{running && <Button size="sm" variant="destructive" onClick={() => void stopBatch(true)}>立即取消当前请求并停止</Button>}{batchCurrent && <span>当前阶段 {batchCurrent.phase} · 第 {batchCurrent.attempt} 次尝试</span>}{detail && <span>批次累计 {detail.batch.totalExecs} 条 · 通过 {detail.batch.passCount} / 失败 {detail.batch.failCount} / 错误 {detail.batch.errorCount}；列表最多显示最近 1000 条，全部结果可在历史检索或导出中查看。</span>}</div>}

          {/* 结果表 */}
          {execs.length === 0 ? (
            <div className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
              勾选用例后点「执行选中」
            </div>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="bg-muted/40 text-xs text-muted-foreground">
                  <tr>
                    <th className="w-8 px-2 py-2" />
                    <th className="px-2 py-2 text-left font-medium">轮/序</th>
                    <th className="px-2 py-2 text-left font-medium">用例</th>
                    <th className="px-2 py-2 text-left font-medium">状态码</th>
                    <th className="px-2 py-2 text-left font-medium">返回码</th>
                    <th className="px-2 py-2 text-left font-medium">判定</th>
                    <th className="px-2 py-2 text-right font-medium">耗时</th>
                    <th className="px-2 py-2 text-left font-medium">时间</th>
                  </tr>
                </thead>
                <tbody>
                  {execs.map((raw) => { const e=executionDisplay(raw,revealedExecs.has(raw.id)); return (
                    // 用带 key 的 Fragment 包住「结果行 + 展开的详情行」：
                    // 这两行必须一起出现/消失，key 不能挂在其中单独一行上
                    <Fragment key={e.id}>
                      <tr
                        data-testid={`exec-row-${e.id}`}
                        className="cursor-pointer border-t hover:bg-muted/30"
                        onClick={() => void toggleExpanded(e.id)}
                      >
                        <td className="px-2 py-1.5 text-muted-foreground">
                          {expanded.has(e.id) ? (
                            <ChevronDown className="h-3.5 w-3.5" />
                          ) : (
                            <ChevronRight className="h-3.5 w-3.5" />
                          )}
                        </td>
                        <td className="whitespace-nowrap px-2 py-1.5 font-mono text-xs text-muted-foreground">
                          {e.roundNo}/{e.seq}
                        </td>
                        <td className="max-w-[240px] truncate px-2 py-1.5 font-medium" title={e.caseName}>
                          {e.caseName}
                        </td>
                        <td className={cn('px-2 py-1.5 font-mono text-xs', statusColor(e.httpStatus))}>
                          {e.httpStatus ?? '—'}
                        </td>
                        <td className="max-w-[280px] px-2 py-1.5">
                          <span className="font-mono text-xs">{e.respCode ?? '—'}</span>
                          {e.codeMeaning && (
                            <span className="ml-2 text-xs text-muted-foreground">
                              {e.codeMeaning}
                            </span>
                          )}
                        </td>
                        <td className="px-2 py-1.5">
                          <VerdictBadge verdict={e.verdict} />
                        </td>
                        <td className="whitespace-nowrap px-2 py-1.5 text-right font-mono text-xs text-muted-foreground">
                          {e.elapsedMs} ms
                        </td>
                        <td className="whitespace-nowrap px-2 py-1.5 text-xs text-muted-foreground">
                          {fmtTime(e.ts)}
                        </td>
                      </tr>
                      {expanded.has(e.id) && (
                        <tr className="border-t bg-muted/20">
                          <td />
                          <td colSpan={7} className="px-2 py-2">
                            <div className="space-y-2">
                              {activeBatchId != null && !fullExecutions.current.has(e.id) && <p role="status" className="text-xs text-muted-foreground">{executionErrors[e.id] ? `明细读取失败：${executionErrors[e.id]}；收起后重试展开。` : '正在按需读取请求与响应明细…'}</p>}
                              <div className="flex flex-wrap items-center gap-2 text-xs">
                                <Badge variant="outline" className="font-mono text-xs px-2 py-0.5">
                                  {e.method}
                                </Badge>
                                <span className="break-all font-mono text-xs text-muted-foreground">
                                  {e.url}
                                </span>
                              </div>
                              {e.reason && (
                                <div className="whitespace-pre-wrap rounded border border-red-500/30 bg-red-500/5 px-2 py-1.5 text-xs text-red-600 dark:text-red-400">
                                  {e.reason}
                                </div>
                              )}
                              {e.error && (
                                <div className="rounded border border-amber-500/30 bg-amber-500/5 px-2 py-1.5 text-xs text-amber-600 dark:text-amber-400">
                                  {e.error}
                                </div>
                              )}
                              <label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={revealedExecs.has(e.id)} onChange={event => setRevealedExecs(current => { const next=new Set(current); if(event.target.checked) next.add(e.id); else next.delete(e.id); return next })} />显示本机敏感原文</label>
                              <ActualCurlExport execId={e.id} /><ExecutionMetadata text={e.metadata} />
                              {(!hasSecretInventory(raw) || revealedExecs.has(e.id)) && <ResponseInspector body={raw.respBody} headers={raw.respHeaders}
                                onAssert={e.caseId != null && cases?.some(c => c.id === e.caseId) ? row => {
                                  const c = cases?.find(c => c.id === e.caseId)
                                  if (!c) return
                                  if (assertionsBroken(c.asserts)) { openEdit(c); toast.error('请先明确修复原断言配置'); return }
                                  openEdit(c)
                                  setForm(f => ({ ...f, asserts: [...f.asserts, row] }))
                                  toast.info('已加入用例编辑草稿，保存后生效')
                                } : undefined}
                                onExtract={e.caseId != null && cases?.some(c => c.id === e.caseId) ? row => {
                                  const c = cases?.find(c => c.id === e.caseId)
                                  if (!c) return
                                  openEdit(c)
                                  setForm(f => ({ ...f, options: { ...f.options, extractors: [...(f.options.extractors ?? []), row] } }))
                                  toast.info('已加入用例编辑草稿，保存后生效')
                                } : undefined}
                              />}
                              {/* 四块的排布（需求 #55，2026-10-05 用户指定）：
                                  左上响应体 / 右上响应头 / 左下本次发出的请求 / 右下本次发出的请求体。
                                  四块放**同一个** grid，不拆成两个 —— 拆开之后「第 1 行第 2 格」
                                  这种东西就不存在了，以后谁想调个顺序都得自己数。
                                  第五块是响应体断言（需求 #58）：明细是一列「路径 / 条件 / 期望 / 结果」，
                                  塞进半格宽太挤，所以整行铺满（col-span-2）。 */}
                              <div className="grid gap-2 lg:grid-cols-2">
                                <div>
                                  <div className="mb-1 text-xs font-medium text-muted-foreground">
                                    响应体
                                  </div>
                                  <JsonBlock
                                    text={e.respBody}
                                    empty="（空）"
                                    title="响应体"
                                  />
                                </div>
                                <div>
                                  <div className="mb-1 text-xs font-medium text-muted-foreground">
                                    响应头
                                  </div>
                                  <JsonBlock
                                    text={e.respHeaders}
                                    empty="（无）"
                                    title="响应头"
                                  />
                                </div>
                                {/* 请求侧两块默认收起（需求 #53）：它们最长，摊开来会把下面
                                    所有执行记录一起顶下去。响应侧照旧直出，不跟着一起收。 */}
                                <CollapsibleBlock
                                  title="本次发出的请求"
                                  text={e.reqHeaders}
                                  empty="（旧记录没存这一项）"
                                  hint={`${(e.reqHeaders ?? '').length} 字符`}
                                />
                                <CollapsibleBlock
                                  title="本次发出的请求体"
                                  text={e.reqBody}
                                  empty="（无请求体）"
                                  hint={e.reqBody ? `${e.reqBody.length} 字符` : ''}
                                />
                                <div className="lg:col-span-2">
                                  <AssertsBlock text={e.assertsResult} verdict={e.verdict} />
                                </div>
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )})}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* ==================== 批次历史 ==================== */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="text-base">执行批次</CardTitle>
          <Button size="sm" variant="outline" onClick={() => void loadBatches()}>
            刷新
          </Button>
        </CardHeader>
        <CardContent>
          {!batchesLoaded ? <div role="status" className="p-6 text-sm text-muted-foreground">{batchesLoading ? '正在加载批次…' : '批次尚未加载成功，请重试。'}</div> : batches.length === 0 ? (
            <div className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
              还没有批次
            </div>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="bg-muted/40 text-xs text-muted-foreground">
                  <tr>
                    <th className="px-2 py-2 text-left font-medium">批次</th>
                    <th className="px-2 py-2 text-left font-medium">方式</th>
                    <th className="px-2 py-2 text-left font-medium">进度</th>
                    <th className="px-2 py-2 text-left font-medium">结果</th>
                    <th className="px-2 py-2 text-left font-medium">状态</th>
                    <th className="px-2 py-2 text-left font-medium">开始</th>
                    <th className="w-24 px-2 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {batches.map((b) => (
                    <tr
                      key={b.id}
                      className={cn(
                        'border-t',
                        activeBatchId === b.id ? 'bg-primary/5' : 'hover:bg-muted/30',
                      )}
                    >
                      <td className="px-2 py-1.5">
                        <button
                          className="font-mono text-xs text-primary hover:underline"
                          onClick={() => void openBatch(b.id)}
                        >
                          #{b.id}
                        </button>
                        <span className="ml-2 text-xs">{b.name}</span>
                      </td>
                      <td className="whitespace-nowrap px-2 py-1.5 text-xs text-muted-foreground">
                        {b.mode === 'ONCE'
                          ? '单轮'
                          : `${b.intervalSeconds}s × ${
                              b.repeatCount === -1 ? '循环' : b.repeatCount + '轮'
                            }`}
                      </td>
                      <td className="whitespace-nowrap px-2 py-1.5 font-mono text-xs">
                        {b.caseIds.length} 条 × {b.repeatCount === -1 ? '∞' : b.repeatCount}
                      </td>
                      <td className="whitespace-nowrap px-2 py-1.5 text-xs">
                        <span className="text-emerald-600 dark:text-emerald-400">{b.passCount}</span>
                        <span className="text-muted-foreground"> / </span>
                        <span className="text-red-600 dark:text-red-400">{b.failCount}</span>
                        <span className="text-muted-foreground"> / </span>
                        <span className="text-amber-600 dark:text-amber-400">{b.errorCount}</span>
                        {(b.skippedExecs ?? 0) > 0 && <span className="ml-2 text-muted-foreground">跳过 {b.skippedExecs}</span>}
                      </td>
                      <td className="px-2 py-1.5">
                        <Badge
                          variant="secondary"
                          className={cn(
                            'font-mono text-xs',
                            b.status === 'RUNNING' && 'text-primary',
                            b.status === 'INTERRUPTED' && 'text-amber-600 dark:text-amber-400',
                          )}
                        >
                          {b.status}
                        </Badge>
                      </td>
                      <td className="whitespace-nowrap px-2 py-1.5 text-xs text-muted-foreground">
                        {fmtTime(b.startedAt)}
                      </td>
                      <td className="px-2 py-1.5">
                        <div className="flex items-center gap-0.5">
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-7 w-7"
                            title="导出 CSV"
                            onClick={() => exportBatch(b.id)}
                          >
                            <Download className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-7 w-7 text-muted-foreground hover:text-red-500"
                            title="删除批次"
                            onClick={() => void deleteBatch(b.id)}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* ==================== 返回码字典 ==================== */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle className="flex items-center gap-2 text-base">
            <BookOpen className="h-4 w-4" />
            返回码字典
            <span className="text-xs font-normal text-muted-foreground">内部码可自行补充</span>
          </CardTitle>
          <div className="flex items-center gap-2">
            <Select value={codeScope || 'ALL'} onValueChange={(v) => setCodeScope(v === 'ALL' ? '' : v)}>
              <SelectTrigger className="h-8 w-44 text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部分类</SelectItem>
                <SelectItem value="CUSTOM">自定义（内部补充）</SelectItem>
                <SelectItem value="GAME">游戏服务端错误码</SelectItem>
                <SelectItem value="SDK">服务端 SDK 错误码</SelectItem>
                <SelectItem value="CLIENT">客户端错误码</SelectItem>
                <SelectItem value="IOS">iOS 系统错误码</SelectItem>
              </SelectContent>
            </Select>
            <div className="relative">
              <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="h-8 w-48 pl-7 text-sm"
                placeholder="搜码或说明"
                value={codeQuery}
                onChange={(e) => setCodeQuery(e.target.value)}
              />
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-end gap-2 rounded-md border bg-muted/20 p-2.5">
            <div className="space-y-1">
              <Label className="text-xs">分类</Label>
              <Select
                value={newCode.scope}
                onValueChange={(v) => setNewCode({ ...newCode, scope: v })}
              >
                <SelectTrigger className="h-8 w-44 text-sm">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CUSTOM">自定义（内部补充）</SelectItem>
                  <SelectItem value="GAME">游戏服务端错误码</SelectItem>
                  <SelectItem value="SDK">服务端 SDK 错误码</SelectItem>
                  <SelectItem value="CLIENT">客户端错误码</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label className="text-xs">返回码</Label>
              <Input
                className="h-8 w-28 font-mono text-sm"
                placeholder="例如 100503"
                value={newCode.code}
                onChange={(e) => setNewCode({ ...newCode, code: e.target.value })}
              />
            </div>
            <div className="min-w-[240px] flex-1 space-y-1">
              <Label className="text-xs">说明</Label>
              <Input
                className="h-8 text-sm"
                placeholder="这个码在你们环境里代表什么"
                value={newCode.message}
                onChange={(e) => setNewCode({ ...newCode, message: e.target.value })}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void addCode()
                }}
              />
            </div>
            <Button size="sm" className="h-8" onClick={() => void addCode()}>
              <Plus className="mr-1 h-3.5 w-3.5" />
              加入字典
            </Button>
          </div>

          {codes == null ? (
            <Skeleton className="h-24 w-full" />
          ) : codes.length === 0 ? (
            <div className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
              没有匹配的返回码。
            </div>
          ) : (
            <div className="max-h-96 overflow-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-muted/80 text-xs text-muted-foreground backdrop-blur">
                  <tr>
                    <th className="w-28 px-2 py-2 text-left font-medium">返回码</th>
                    <th className="px-2 py-2 text-left font-medium">说明</th>
                    <th className="w-44 px-2 py-2 text-left font-medium">分类</th>
                    <th className="w-16 px-2 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {codes.map((c) => (
                    <tr key={c.id} className="border-t hover:bg-muted/30">
                      <td className="px-2 py-1.5 font-mono text-xs">{c.code}</td>
                      <td className="px-2 py-1.5 text-xs">{c.message}</td>
                      <td className="px-2 py-1.5">
                        <Badge
                          variant={c.source === 'CUSTOM' ? 'default' : 'secondary'}
                          className="text-xs"
                        >
                          {c.scopeLabel}
                        </Badge>
                      </td>
                      <td className="px-2 py-1.5">
                        {c.source === 'CUSTOM' && (
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-6 w-6 text-muted-foreground hover:text-red-500"
                            title="删除"
                            onClick={() => void removeCode(c)}
                          >
                            <Trash2 className="h-3 w-3" />
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* ==================== 用例编辑弹窗 ==================== */}
      <Dialog open={editorOpen} onOpenChange={value => { if (value && !saving) setEditorOpen(true); else if (!value) closeCaseEditor() }}>
        {/* 宽度必须写成 sm:max-w-* —— dialog 组件自带 sm:max-w-sm，
            不带 sm: 前缀的 max-w-* 会被它盖掉（同组类只有变体相同才会互相覆盖，
            而响应式变体在产物 CSS 里排在后面，所以赢的永远是 sm: 那条）。
            2026-10-04（需求 #37）：4xl → 7xl，地址框跟着变长，纵向不再被挤得那么高。 */}
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-7xl">
          <DialogHeader>
            <DialogTitle>{editingId == null ? '新建接口用例' : `编辑用例 #${editingId}`}</DialogTitle>
          </DialogHeader>
          {caseDiscardPending && <UnsavedChangesNotice testId="case-unsaved-changes" onContinue={() => setCaseDiscardPending(false)} onDiscard={() => { setCaseDiscardPending(false); setEditorOpen(false) }} />}
          <fieldset disabled={saving || caseDiscardPending} className="min-w-0 space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="api-name">用例名称</Label>
              <Input
                id="api-name"
                value={form.name}
                placeholder="例如：创建订单 - 正常流程"
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </div>
            <div className="flex gap-2">
              <div className="w-28 space-y-1.5">
                <Label>方法</Label>
                <Select value={form.method} onValueChange={(v) => setForm({ ...form, method: v })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {METHODS.map((m) => (
                      <SelectItem key={m} value={m}>
                        {m}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex-1 space-y-1.5">
                <Label htmlFor="api-url">
                  地址
                  {/* 需求 #38：地址本来就支持变量（基址组与请求头组都作用于它），
                      但不写出来没人会去试。打 {{ 会弹候选，候选范围与后端替换范围一致。 */}
                  <span className="ml-2 font-normal text-muted-foreground">
                    可用 {'{{环境基址}}'} 等变量，打 {'{{'} 会弹候选
                  </span>
                </Label>
                <VarInput
                  id="api-url"
                  value={form.url}
                  placeholder="http://… 或 https://…"
                  className="font-mono text-sm"
                  suggestions={urlSuggestions}
                  onChange={(v) => setForm({ ...form, url: v })}
                />
              </div>
            </div>
            {/* 请求头与请求体并排 —— 原来上下堆着放，整个弹窗全靠纵向撑 */}
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="api-headers">请求头（JSON 对象）</Label>
                <HeaderRowsEditor suggestions={headerSuggestions} rawId="api-headers" raw={form.headers ?? ''} rows={form.options?.headerRows} onRaw={headers=>setForm(f=>({...f,headers}))} onRows={headerRows=>setForm(f=>({...f,options:{...f.options,headerRows}}))} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="api-body">请求体</Label>
                {['MULTIPART','BINARY','URLENCODED','NONE'].includes(form.options.bodyType ?? '') ? <p className="rounded border p-3 text-xs text-muted-foreground">此模式从下方请求设置构造正文；原始文本不参与发送。</p> : <CodeArea
                  id="api-body"
                  formatJson={form.options.bodyType !== 'RAW'}
                  value={form.body}
                  suggestions={bodySuggestions}
                  onChange={(v) => setForm({ ...form, body: v })}
                  placeholder={'{\n  "userId": "10001"\n}'}
                />}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <div className="space-y-1.5">
                <Label>分组</Label>
                {/* 归组放在这里而不是列表上做拖拽：用例的分组属于「这条用例是什么」，
                    与名称、方法同一层级，编辑时顺手就能改。 */}
                <Select
                  value={form.groupId || '__none__'}
                  onValueChange={(v) =>
                    setForm({ ...form, groupId: v === '__none__' ? '' : v })
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder="未分组" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">未分组</SelectItem>
                    {(groups ?? []).map((g) => (
                      <SelectItem key={g.id} value={String(g.id)}>
                        {g.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="api-exp-status">期望 HTTP 状态码</Label>
                <Input
                  id="api-exp-status"
                  className="font-mono text-sm"
                  placeholder="如 200"
                  value={form.expectStatus}
                  onChange={(e) => setForm({ ...form, expectStatus: e.target.value })}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="api-exp-code">期望业务返回码</Label>
                <Input
                  id="api-exp-code"
                  className="font-mono text-sm"
                  placeholder="如 0"
                  value={form.expectCode}
                  onChange={(e) => setForm({ ...form, expectCode: e.target.value })}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="api-timeout">超时（毫秒）</Label>
                <Input
                  id="api-timeout"
                  type="number"
                  className="font-mono text-sm"
                  value={form.timeoutMs}
                  onChange={(e) =>
                    setForm({ ...form, timeoutMs: Number(e.target.value) || 10000 })
                  }
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="api-note">备注</Label>
                <Input
                  id="api-note"
                  value={form.note}
                  placeholder="测什么、前置条件"
                  onChange={(e) => setForm({ ...form, note: e.target.value })}
                />
              </div>
            </div>
            <p className="text-xs text-muted-foreground">期望值留空不校验；多个用逗号隔开。</p>

            <div className="space-y-4 rounded-lg border p-3">
              {form.assertsInvalid ? <div role="alert" className="space-y-2 rounded border border-red-500/30 p-3 text-xs text-red-600"><p>原断言配置无法解析，原文仍保留。明确修正前禁止覆盖用例。</p><JsonBlock text={form.assertsOriginal} empty="空" title="损坏的断言配置" /><Button type="button" variant="outline" onClick={() => setForm(f => ({ ...f, assertsInvalid: false, asserts: [] }))}>清空损坏配置并重新编辑</Button></div> : <AssertionEditor rows={form.asserts} onChange={asserts => setForm(f => ({ ...f, asserts }))} />}
              <RequestOptionsEditor envId={groups?.find(g=>String(g.id)===form.groupId)?.envId ?? envs?.find(e=>e.isDefault)?.id} headers={form.headers} value={form.options} suggestions={headerSuggestions} paramSuggestions={urlSuggestions} onChange={options => setForm(f => ({ ...f, options }))} />
              <VariableHelp vars={vars} systemVariables={systemVars} />
            </div>
            <div className="flex flex-wrap items-center gap-x-5 gap-y-2 pt-1">
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <Checkbox
                  checked={form.enabled}
                  onCheckedChange={(v) => setForm({ ...form, enabled: v === true })}
                />
                启用（不启用则批量执行时跳过）
              </label>
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <Checkbox
                  checked={form.insecure}
                  onCheckedChange={(v) => setForm({ ...form, insecure: v === true })}
                />
                跳过 HTTPS 证书校验
              </label>
              <span className="text-xs text-amber-700 dark:text-amber-400">
                含内部地址或凭证：只存本机，外发前先脱敏。
              </span>
            </div>
          </fieldset>
          <DialogFooter>
            <Button variant="outline" disabled={saving || caseDiscardPending} onClick={closeCaseEditor}>
              取消
            </Button>
            <Button onClick={() => void saveCase()} disabled={saving || caseDiscardPending}>
              {saving ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ==================== 环境设置（变量 + 签名） ==================== */}
      <Dialog
        open={envOpen}
        onOpenChange={(o) => { if (!o) changeEnv(null); else if (!savingEnv) setEnvOpen(true) }}
      >
        {/* 宽度必须写成 sm:max-w-* —— 见用例编辑弹窗那条注释。
            3xl → 4xl（2026-10-04）：变量从两组变三组，还多了一张内置变量清单表。 */}
        {/* v1.9.3：固定外壳高度 + 内部滚动 —— 切换「变量/签名」时弹窗外壳与顶部
            区域（标题 / 分段栏 / 环境基址）完全不动，只换中间内容区（使用者实拍反馈：
            原实现高度由内容决定，切签名时窗口突然变小，非常割裂）。 */}
        <DialogContent className="flex h-[85vh] max-h-[92vh] flex-col gap-4 overflow-hidden sm:max-w-4xl">
          <DialogHeader>
            <DialogTitle>环境设置</DialogTitle>
          </DialogHeader>

          {envPending && <div role="alert" data-testid="env-unsaved-changes" className="space-y-2 rounded-md border border-amber-500/40 p-3 text-sm"><p>环境设置尚未保存。可保留当前会话中的草稿；已编辑的签名密钥关闭后需重新填写。</p><div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" onClick={() => setEnvPending(null)}>继续编辑</Button><Button size="sm" variant="outline" onClick={() => finishEnvNavigation(envPending.nextId, false)}>放弃修改</Button><Button size="sm" onClick={() => finishEnvNavigation(envPending.nextId, true)}>保留草稿</Button></div></div>}
          {/* 环境卡片行（v1.10.7 #87）：一个环境 = 一套变量 + 一套签名。
              切卡片 = 换整套；复制沿用分组复制的序号规则；默认环境不可删（兜底用）。 */}
          <fieldset disabled={savingEnv || envPending != null} className="shrink-0" data-testid="env-cards">
            <div className="mb-1 flex items-center justify-between">
              <span className="text-xs font-semibold text-muted-foreground">环境</span>
              {envNewOpen ? (
                <div className="flex items-center gap-1">
                  <Input
                    autoFocus
                    className="h-6 w-32 text-xs"
                    placeholder="环境名"
                    value={envDraft}
                    onChange={(e) => setEnvDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void createEnv()
                    }}
                    data-testid="env-new-input"
                  />
                  <Button size="sm" className="h-6 px-2 text-xs" onClick={() => void createEnv()}>
                    确定
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-6 px-2 text-xs"
                    onClick={() => setEnvNewOpen(false)}
                  >
                    取消
                  </Button>
                </div>
              ) : (
                <div className="flex items-center gap-1">
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-6 px-1.5 text-xs"
                    onClick={() => {
                      setEnvNewOpen(true)
                      setEnvDraft('')
                    }}
                  >
                    <Plus className="mr-0.5 h-3 w-3" />
                    新建
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-6 px-1.5 text-xs"
                    onClick={() => void duplicateEnv()}
                    title="复制当前环境（变量与签名一起复制）"
                  >
                    <Copy className="mr-0.5 h-3 w-3" />
                    复制
                  </Button>
                </div>
              )}
            </div>
            <div className="flex flex-wrap gap-1.5">
              {(envs ?? []).map((e) => (
                <button
                  key={e.id}
                  type="button"
                  onClick={() => changeEnv(e.id)}
                  data-testid={`env-card-${e.id}`}
                  className={cn(
                    'group/env flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs transition-colors',
                    activeEnv === e.id
                      ? 'border-primary/40 bg-primary/5 font-medium text-foreground'
                      : 'text-muted-foreground hover:border-muted-foreground/40',
                  )}
                >
                  <span>{e.name}</span>
                  {e.isDefault && <span className="rounded bg-muted px-1 text-xs">默认</span>}
                  {!e.isDefault && (
                    <span
                      className="flex items-center opacity-0 transition-opacity group-hover/env:opacity-100"
                      onClick={(ev) => {
                        ev.stopPropagation()
                        void removeEnv(e.id, e.name)
                      }}
                      title="删除环境（变量与签名一并删；引用它的分组回到默认环境）"
                    >
                      <Trash2 className="h-3 w-3 text-muted-foreground hover:text-red-500" />
                    </span>
                  )}
                </button>
              ))}
            </div>
          </fieldset>

          {/* 两块用分段切换而不是上下堆着：各自都是一整张表单，堆起来弹窗要高到滚半天 */}
          {!envReady && <div role="status" className="text-xs text-muted-foreground">
            {envLoading ? '正在加载当前环境…' : '环境未加载，请重新打开后重试'}
          </div>}
          <fieldset disabled={!envReady || savingEnv || envPending != null} className="contents">
          {envs?.find(environment => environment.id === activeEnv) && <EnvironmentStrict environment={envs.find(environment => environment.id === activeEnv)!} disabled={!envReady || savingEnv || envPending != null} onSaved={() => void loadEnvs()} />}
          <div className="flex gap-0.5 rounded-md border p-0.5">
            {(
              [
                ['vars', '变量'],
                ['sign', '签名'],
              ] as const
            ).map(([k, label]) => (
              <button
                key={k}
                type="button"
                onClick={() => setEnvTab(k)}
                className={cn(
                  'flex-1 rounded px-2 py-1 text-sm',
                  envTab === k
                    ? 'bg-muted font-medium text-foreground'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {label}
              </button>
            ))}
          </div>

          {/* 环境基址提到分段外（v1.9.3）：变量/签名都与它相关，固定在最顶部 ——
              切换分段时这一行保持不动，不再随内容高度变化跳动（需求：顶部区域不动）。 */}
          <SecretManager envId={activeEnv} />
          <p className="text-xs text-muted-foreground">敏感标记控制默认显示与分享遮罩；普通变量仍保存在本机库。需要加密保存请使用本机秘密引用。留空签名密钥表示不修改。</p>
          <VarTable
            title="环境基址"
            hint="在地址里写 {{名称}} 引用（如 {{base}}/api/order）；换环境只改这一行。只在地址里生效，不会进请求头。"
            rows={varForm.base}
            onAdd={() => addVar('base')}
            onChange={(i, patch) => setVar('base', i, patch)}
            onRemove={(i) => removeVar('base', i)}
          />

          {/* 内容区（唯一滚动区）：切分段只换这里 */}
          <div className="min-h-0 flex-1 overflow-y-auto pr-1">
          {envTab === 'vars' ? (
            <div className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <VarTable
                  title="地址与请求头变量"
                  hint="在地址与请求头里写 {{名称}} 引用；与基址同名时以基址为准"
                  rows={varForm.header}
                  onAdd={() => addVar('header')}
                  onChange={(i, patch) => setVar('header', i, patch)}
                  onRemove={(i) => removeVar('header', i)}
                />
                <VarTable
                  title="请求体变量"
                  hint="在请求体里写 {{名称}} 引用；只在请求体里生效"
                  rows={varForm.body}
                  onAdd={() => addVar('body')}
                  onChange={(i, patch) => setVar('body', i, patch)}
                  onRemove={(i) => removeVar('body', i)}
                />
              </div>
              {/* 需求 #40：内置变量清单，和自定义变量放在同一个视图里 */}
              <BuiltinVarsPanel />
              <VariableHelp vars={varForm} systemVariables={systemVars} />
            </div>
          ) : (
            <div className="space-y-3">
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <Checkbox
                  checked={signForm.enabled}
                  onCheckedChange={(v) => setSignForm((f) => ({ ...f, enabled: v === true }))}
                />
                发送前自动签名
              </label>

              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="api-sign-appid">应用 id（app_id）</Label>
                  <Input
                    id="api-sign-appid"
                    className="font-mono text-sm"
                    value={signForm.appId}
                    onChange={(e) => setSignForm((f) => ({ ...f, appId: e.target.value }))}
                    placeholder="签名串的第一行"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="api-sign-secret">应用密钥（app_secret）</Label>
                  <div className="flex items-center gap-1.5">
                    <Input
                      id="api-sign-secret"
                      type={secretShown ? 'text' : 'password'}
                      className="font-mono text-sm"
                      value={signForm.appSecret}
                      onChange={(e) => { ++secretSeq.current; setSecretLoading(false); setSecretEdited(true); setSignForm((f) => ({ ...f, appSecret: e.target.value })) }}
                      placeholder={sign?.secretSaved ? '已保存 · 留空表示不修改' : '只用来算签名，不回显'}
                    />
                    {/* 需求 #39：默认遮住，点「显示」才向后端要一次明文；再点收起、关弹窗也复位。
                        title 里写明代价 —— 处于显示状态时截图 / 录屏 / 共享屏幕都会漏出去。 */}
                    <Button
                      type="button"
                      size="icon"
                      variant="outline"
                      className="h-9 w-9 shrink-0"
                      disabled={secretLoading}
                      title={
                        secretShown
                          ? '收起（明文立刻从界面上清掉）'
                          : '显示已保存的密钥明文 —— 显示状态下截图、录屏、共享屏幕都会漏出去'
                      }
                      onClick={() => void toggleSecret()}
                    >
                      {secretLoading ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      ) : secretShown ? (
                        <EyeOff className="h-3.5 w-3.5" />
                      ) : (
                        <Eye className="h-3.5 w-3.5" />
                      )}
                    </Button>
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="api-sign-nonce">随机串（nonce）</Label>
                  <Input
                    id="api-sign-nonce"
                    className="font-mono text-sm"
                    value={signForm.nonce}
                    onChange={(e) => setSignForm((f) => ({ ...f, nonce: e.target.value }))}
                    placeholder="留空则每次现生成"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="api-sign-mgid">mg_id</Label>
                  <Input
                    id="api-sign-mgid"
                    className="font-mono text-sm"
                    value={signForm.mgId}
                    onChange={(e) => setSignForm((f) => ({ ...f, mgId: e.target.value }))}
                    placeholder="请求体里那个占位符的值"
                  />
                </div>
              </div>

              <div className="rounded-md border px-2.5 py-2 text-xs text-muted-foreground">
                内置变量清单在「变量」分段里 —— 它和自定义变量本来就是同一层的东西（都是在框里写{' '}
                <span className="font-mono text-foreground">{'{{名字}}'}</span>），
                区别只是一个自己填、一个平台算。这里只提醒最容易接错的一条：{' '}
                <span className="font-mono text-foreground">{'{{sign}}'}</span>{' '}
                只在地址与请求头里替换，写进请求体不会被替换。
              </div>

              {signForm.enabled &&
                !(signForm.appId.trim() && (signForm.appSecret.trim() || sign?.secretSaved)) && (
                  <div className="rounded-md border border-amber-500/40 bg-amber-500/10 px-2.5 py-2 text-xs text-amber-700 dark:text-amber-400">
                    还缺 app_id 或密钥，这样存下来执行会直接报错。
                  </div>
                )}

              <p className="text-xs text-muted-foreground">
                只存本机，不进版本库。密钥默认遮住；点「显示」会向本机后端要一次明文，
                收起或关掉弹窗立刻清掉 —— <span className="font-medium">处于显示状态时别截图或共享屏幕</span>。
              </p>
            </div>
          )}
          </div>

          </fieldset>
          <DialogFooter>
            <Button variant="outline" disabled={savingEnv || envPending != null} onClick={() => changeEnv(null)}>
              取消
            </Button>
            <Button onClick={() => void saveEnv()} disabled={savingEnv || !envReady || envPending != null}>
              {savingEnv ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ==================== 定时参数弹窗 ==================== */}
      <Dialog open={scheduleOpen} onOpenChange={setScheduleOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>定时 / 批量执行</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div className="text-sm text-muted-foreground">
              将按顺序执行已勾选的 <span className="font-medium text-foreground">{picked.size} 条</span> 用例。
            </div>
            <div className="flex items-end gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="api-interval">每轮间隔（秒）</Label>
                <Input
                  id="api-interval"
                  type="number"
                  min={0}
                  className="w-32 font-mono text-sm"
                  value={intervalSeconds}
                  onChange={(e) => setIntervalSeconds(Math.max(0, Number(e.target.value) || 0))}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="api-repeat">重复轮数</Label>
                <Input
                  id="api-repeat"
                  type="number"
                  min={1}
                  className="w-32 font-mono text-sm"
                  disabled={forever}
                  value={repeatCount}
                  onChange={(e) => setRepeatCount(Math.max(1, Number(e.target.value) || 1))}
                />
              </div>
            </div>
            <label className="flex cursor-pointer items-center gap-2 text-sm">
              <Checkbox checked={forever} onCheckedChange={(v) => setForever(v === true)} />
              一直循环，直到我手动停止
            </label>
            <div className="rounded-md border bg-muted/30 px-2.5 py-2 text-xs text-muted-foreground">
              共执行{' '}
              <span className="font-mono text-foreground">
                {forever ? '∞' : `${repeatCount} × ${picked.size} = ${repeatCount * picked.size}`}
              </span>{' '}
              次
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setScheduleOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void startBatch()}>
              <Timer className="mr-1 h-3.5 w-3.5" />
              开始
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
