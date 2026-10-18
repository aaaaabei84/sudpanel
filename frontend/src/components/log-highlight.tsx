import { Fragment, memo, useCallback, useMemo, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Copy, ChevronDown, ChevronUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  KV_VALUE_CLASS,
  LEVEL_CLASS,
  META_CLASS,
  parseLogLine,
  splitBodySegments,
  splitByKeywords,
  splitMeta,
  type ParsedLogLine,
} from '@/lib/log-line'
import { cn } from '@/lib/utils'

// ---------- 工具：schema 字段提取 ----------

const STOP_WORDS = new Set([
  'true', 'false', 'null', 'undefined', 'nan',
  'show', 'close', 'open', 'hide', 'on', 'off', 'up', 'down',
  'left', 'right', 'top', 'bottom', 'yes', 'no',
])

/** 从自由文本 dataSchema 中提取字段名：按分隔符拆 token，保留驼峰标识符。 */
export function extractSchemaFields(schema?: string | null): string[] {
  if (!schema) return []
  const seen = new Set<string>()
  const out: string[] = []
  for (const t of schema.split(/[\s,，/、()（）:：;；|=]+/)) {
    const s = t.trim()
    if (/^[a-zA-Z][a-zA-Z0-9]*$/.test(s) && s.length >= 2 && !STOP_WORDS.has(s.toLowerCase()) && !seen.has(s)) {
      seen.add(s)
      out.push(s)
    }
  }
  return out
}

// ---------- 工具：JSON 提取与键聚合 ----------

/** 从一行日志体提取末尾/内嵌的 JSON 片段（第一个 { 到最后一个 }）。
 *  合法 JSON 正常解析；vconsole 日志常见内嵌对象未转义（"extras":"{...}"）导致 parse 失败，
 *  只要片段含键值对特征（"key":）就降级为 token 级高亮，不丢弃。 */
function findJson(body: string): { before: string; json: string | null; after: string } {
  const start = body.indexOf('{')
  const end = body.lastIndexOf('}')
  if (start >= 0 && end > start) {
    const candidate = body.slice(start, end + 1)
    try {
      JSON.parse(candidate)
      return { before: body.slice(0, start), json: candidate, after: body.slice(end + 1) }
    } catch {
      if (/"[^"]*"\s*:/.test(candidate)) {
        return { before: body.slice(0, start), json: candidate, after: body.slice(end + 1) }
      }
    }
  }
  return { before: body, json: null, after: '' }
}

/** 聚合文本中所有 JSON 的键集合（正则提取 "key": 模式，兼容 vconsole 非法 JSON）。 */
function collectJsonKeys(text: string): Set<string> {
  const keys = new Set<string>()
  const re = /"([^"\\]*)"\s*:/g
  for (const line of text.split('\n')) {
    const { json } = findJson(line)
    if (json) {
      let m: RegExpExecArray | null
      while ((m = re.exec(json)) !== null) {
        keys.add(m[1])
      }
    }
  }
  return keys
}

// ---------- 高亮渲染 ----------

/**
 * 默认的命中样式（走查页、结果页那几处沿用 #60 的观感）。
 *
 * <p>与 `KEYWORD_HIT_CLASS`（在 `lib/log-line.ts`，需求 #66）分开：那边的「关键字」
 * 是**测试点状态名与期望字段**，出现在一个固定高度的列表里，改动它的观感没有必要，
 * 也会牵动已有的界面断言。上报查询要的「放大 + 改色」由调用方显式传进来。
 */
const DEFAULT_HIT_CLASS = 'text-amber-600 font-bold'

function highlightKeywords(text: string, keywords: string[], cls: string): ReactNode[] {
  // 切分规则在 `lib/log-line.ts` 的 `splitByKeywords`（纯函数，能被断言脚本压）——
  // 这里只负责把命中的片段包成 <strong>。规则不写两份，是这个仓库一贯的分工。
  // 未命中的片段包一层 Fragment（不产生 DOM）只是为了给数组元素一个 key，
  // 直接返回字符串 React 也能渲染，但 lint 会报「数组里缺 key」。
  return splitByKeywords(text, keywords).map((seg, i) =>
    seg.hit ? (
      <strong key={i} className={cls}>
        {seg.text}
      </strong>
    ) : (
      <Fragment key={i}>{seg.text}</Fragment>
    ),
  )
}

const JSON_TOKEN_RE = /("(?:[^"\\]|\\.)*")(\s*:)?|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(true|false|null)|([{}\[\],:])/g

function highlightJson(text: string): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  let i = 0
  let m: RegExpExecArray | null
  JSON_TOKEN_RE.lastIndex = 0
  while ((m = JSON_TOKEN_RE.exec(text)) !== null) {
    if (m.index > last) out.push(text.slice(last, m.index))
    const [full, str, colon, num, bool] = m
    let cls = 'text-gray-500'
    if (str) cls = colon ? 'text-blue-600 font-semibold' : 'text-green-600'
    else if (num) cls = 'text-orange-500'
    else if (bool) cls = 'text-purple-500'
    out.push(
      <span key={i++} className={cls}>
        {full}
      </span>,
    )
    last = m.index + full.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

// ---------- vconsole 非法 JSON 修复 ----------

/** 修复 vconsole 日志的未转义内嵌 JSON：值字符串紧跟 { 或 [ 时，把内部引号全部转义。 */
function repairVconsoleJson(s: string): string {
  let out = ''
  let i = 0
  while (i < s.length) {
    const ch = s[i]
    if (ch === '"') {
      let prev = i - 1
      while (prev >= 0 && /\s/.test(s[prev])) prev--
      const next = i + 1
      if ((s[prev] === ':' || s[prev] === ',') && (s[next] === '{' || s[next] === '[')) {
        // 内嵌 JSON：括号配对找结束，内部引号全部转义
        let depth = 0
        let j = next
        let inStr = false
        for (; j < s.length; j++) {
          const c = s[j]
          if (c === '"' && s[j - 1] !== '\\') inStr = !inStr
          if (!inStr) {
            if (c === '{' || c === '[') depth++
            else if (c === '}' || c === ']') {
              depth--
              if (depth === 0) break
            }
          }
        }
        const inner = s.slice(i + 1, j + 1)
        out += '"' + inner.replace(/"/g, '\\"') + '"'
        i = j + 1
        if (s[i] === '"') i++
        continue
      }
      let j = i + 1
      while (j < s.length && s[j] !== '"') {
        if (s[j] === '\\') j++
        j++
      }
      out += s.slice(i, Math.min(j + 1, s.length))
      i = j + 1
      continue
    }
    out += ch
    i++
  }
  return out
}

// ---------- 字段级视图（方案 D：key: value 逐行换行，保留全部） ----------

function isScalar(v: unknown): boolean {
  return v === null || typeof v !== 'object'
}

function renderScalar(v: unknown): ReactNode {
  if (v === null) return <span className="text-purple-500">null</span>
  if (typeof v === 'string') return <span className="text-green-600">{v}</span>
  if (typeof v === 'number') return <span className="text-orange-500">{v}</span>
  if (typeof v === 'boolean') return <span className="text-purple-500">{String(v)}</span>
  return <span>{String(v)}</span>
}

function renderFields(v: unknown, depth: number): ReactNode[] {
  const out: ReactNode[] = []
  if (Array.isArray(v)) {
    if (v.length === 0) return [<span className="text-gray-500">[]</span>]
    v.forEach((item, idx) => {
      out.push(
        <div key={idx} style={{ paddingLeft: depth * 14 }}>
          <span className="text-blue-600 font-semibold">[{idx}]</span>{' '}
          {isScalar(item) ? renderScalar(item) : renderFields(item, depth + 1)}
        </div>,
      )
    })
  } else if (v && typeof v === 'object') {
    const entries = Object.entries(v as Record<string, unknown>)
    if (entries.length === 0) return [<span className="text-gray-500">{'{}'}</span>]
    for (const [k, val] of entries) {
      out.push(
        <div key={k} style={{ paddingLeft: depth * 14 }}>
          <span className="text-blue-600 font-semibold">{k}:</span>{' '}
          {isScalar(val) ? renderScalar(val) : renderFields(val, depth + 1)}
        </div>,
      )
    }
  }
  return out
}

function JsonFieldView({ json }: { json: string }): ReactNode {
  let value: unknown = null
  try {
    value = JSON.parse(json)
  } catch {
    try {
      value = JSON.parse(repairVconsoleJson(json))
    } catch {
      return <>{highlightJson(json)}</> // 兜底：token 级高亮
    }
  }
  return <div className="mt-0.5 space-y-px">{renderFields(value, 0)}</div>
}

// ---------- 行解析 ----------

function parseLine(raw: string): ParsedLogLine {
  // 解析规则放在 lib/log-line.ts（纯函数，可被断言脚本直接压）：这里只做转发，
  // 免得同一套正则在这个文件与那个文件里各写一份、日后只改一处。
  return parseLogLine(raw)
}
/**
 * 渲染行首 meta：时间 / pid / tid / 级别 / tag 各自着色。
 *
 * <p>配色表在 `lib/log-line.ts`（纯数据，能被断言核对「六个级别是否都有色、是否互不相同」）。
 * 这里只管怎么摆 —— 级别字母**只上色、不铺整行底色**：整行铺色在日志量大时非常刺眼，
 * 还会把正文的 JSON 高亮压掉。
 *
 * <p>认不出结构时整段原样显示（灰色）—— 宁可不上色，也不要把不是时间的东西放进「时间那栏」。
 */
function RenderMeta({ meta }: { meta: string }) {
  const parts = splitMeta(meta)
  if (!parts) {
    return <span className="text-gray-400">{meta}</span>
  }
  return (
    <>
      <span className={META_CLASS.time}>{parts.time}</span>
      <span className={META_CLASS.pidTid}>
        {'  '}
        {parts.pid}
        {'  '}
        {parts.tid}
      </span>
      <span className={cn('font-bold', LEVEL_CLASS[parts.level] ?? 'text-gray-400')}>
        {' '}
        {parts.level}{' '}
      </span>
      <span className={META_CLASS.tag}>{parts.tag}</span>
    </>
  )
}

/**
 * 渲染正文里的一段普通文本：关键字命中加粗，`键=值` 的键与值分别着色。
 *
 * <p>顺序上是「先切键值对、再在文本片段里找关键字」：关键字通常出现在值里（如 `isIn=true`），
 * 反过来先切关键字会把键值对切开，值就显示不完整了。
 */
function renderPlainText(text: string, keywords: string[], hitClass: string): ReactNode[] {
  const out: ReactNode[] = []
  let i = 0
  for (const seg of splitBodySegments(text)) {
    if (seg.kind === 'text') {
      if (seg.text) {
        out.push(...highlightKeywords(seg.text, keywords, hitClass))
      }
    } else {
      out.push(
        <span key={i++}>
          <span className="text-blue-600 dark:text-blue-400">{seg.key}</span>
          <span className="text-muted-foreground">=</span>
          <span className={KV_VALUE_CLASS[seg.valueType]}>{seg.value}</span>
        </span>,
      )
    }
  }
  return out
}

/** 一行正文切成三段的结果（见 `findJson`）。 */
type BodyParts = { before: string; json: string | null; after: string }

/**
 * 渲染一行正文。
 *
 * <p>接收**已经切好的**三段而不是原始正文（2026-10-06，需求 #63）：切分要跑正则，
 * 而判断「这行要不要镶蓝边」也要同一个结果，原来在 `LogViewer` 里是**切成两遍**的。
 * 现在切一次、往下传，行数一多这处的差别是实打实的。
 */
function renderBody(
  parts: BodyParts,
  stateName: string | undefined,
  fields: string[],
  highlight: string | undefined,
  hitClass: string,
): ReactNode[] {
  const kw = [stateName, ...fields, highlight].filter(Boolean) as string[]
  const out: ReactNode[] = []
  out.push(...renderPlainText(parts.before, kw, hitClass))
  if (parts.json) out.push(<JsonFieldView key="json" json={parts.json} />)
  out.push(...renderPlainText(parts.after, kw, hitClass))
  return out
}

// ---------- 主组件：日志查看器 ----------

const COLLAPSE_AT = 5

/**
 * 空字段表。写成模块级常量而不是默认参数 `fields = []`：后者每次渲染都会新建一个数组，
 * 会让下面 `LogRow` 的记忆化**每次都不命中**（记忆化比的是引用）。
 */
const EMPTY_FIELDS: string[] = []

/**
 * 单行日志（2026-10-06，需求 #63 从 `LogViewer` 里抽出并记忆化）。
 *
 * <p><b>为什么值得抽</b>：实时上报每秒刷新 10 次，每次都会把整个窗口重渲染一遍。
 * 没有记忆化时，哪怕窗口里 1199 行一个字没变、只多了一行，这 1200 行也要**全部**重跑
 * 解析（切 meta、切键值对、找 JSON）并重建元素 —— 这是「日志一多就掉帧」的主因。
 * 记忆化之后只有真正新增的那几行会重做，其余直接复用上一次的结果。
 *
 * <p><b>能记住的前提有两个</b>，少一个就退化成原来的样子、而且不会报错、只是慢：
 * <ol>
 *   <li>`key` 必须稳定 —— 见 `LogViewer` 的 `lineOffset` 属性；</li>
 *   <li>`fields` 与 `onCopy` 的身份必须稳定 —— 所以有 `EMPTY_FIELDS` 与 `useCallback`。</li>
 * </ol>
 */
const LogRow = memo(function LogRow({
  raw,
  stateName,
  fields,
  highlight,
  hitClass,
  onCopy, index,
}: {
  raw: string
  stateName?: string
  fields: string[]
  highlight?: string
  hitClass: string
  onCopy: (raw: string) => void
  /**
   * 该行在缓冲区里的**绝对行号**（2026-10-14，需求 #89）。
   *
   * <p>为什么需要：上报查询要支持「按回车跳到命中行」，而渲染窗口的起点有上限
   * （不能晚于「总行数 − 一页」），命中行可能落在窗口里很深的位置 ——
   * 只知道窗口起点是定位不到它的。带上行号之后可以直接 `[data-line-index]` 选中那一行。
   */
  index: number
}) {
  // 解析与切分都按 raw 记忆化：窗口滑动时绝大多数行原样复用，这里一次都不会重跑
  const line = useMemo(() => parseLine(raw), [raw])
  const parts = useMemo(() => findJson(line.body), [line.body])
  return (
    <div
      data-line-index={index}
      className={`group flex items-start gap-1 rounded px-1 py-px hover:bg-muted/70 ${
        parts.json ? 'border-l-2 border-blue-400' : 'border-l-2 border-transparent'
      }`}
    >
      {line.meta ? (
        <span className="shrink-0 font-mono text-xs leading-5">
          <RenderMeta meta={line.meta} />
        </span>
      ) : null}
      <span className="min-w-0 flex-1 whitespace-pre-wrap break-all font-mono text-xs leading-5">
        {renderBody(parts, stateName, fields, highlight, hitClass)}
      </span>
      <button
        className="shrink-0 self-start rounded p-0.5 text-muted-foreground opacity-0 transition-opacity hover:bg-background hover:text-foreground group-hover:opacity-100"
        onClick={() => onCopy(line.raw)}
        title="复制原始行"
      >
        <Copy className="h-3 w-3" />
      </button>
    </div>
  )
})

export function LogViewer({
  text,
  stateName,
  fields = EMPTY_FIELDS,
  highlight,
  hitClass = DEFAULT_HIT_CLASS,
  collapsible = true,
  emptyText = '未命中关键字（无上报日志）',
  lineOffset = 0,
}: {
  text: string
  stateName?: string
  fields?: string[]
  /**
   * 额外要高亮的一段文本（2026-10-06，需求 #66 新增）。
   *
   * <p>上报查询用它把**过滤输入框里的关键字**在行内标出来。与 `stateName`/`fields`
   * 分开而不是塞进 `fields`：那两个的语义是「测试点状态名 / 期望字段」，
   * 混进来会让「这四个字为什么会上色」变成需要读实现才知道的事。
   */
  highlight?: string
  /** 命中时的样式类，见 {@link KEYWORD_HIT_CLASS}。不传则用走查页那套默认观感。 */
  hitClass?: string
  /**
   * 是否在行数多时折叠成 5 行 + 「展开全部」（2026-10-06，需求 #60 新增）。
   *
   * <p>默认 true 保持既有行为不变（走查/结果页那几处要的就是「先给个摘要」）。
   * 上报查询要传 false：那里的日志是**正在滚动**的，折叠一下只剩 5 行，
   * 每来一行都要再点一次「展开」，等于没法用。
   */
  collapsible?: boolean
  /** 无内容时的提示。上报查询里「没抓到」与「没命中关键字」是两件事，不能共用一句话。 */
  emptyText?: string
  /**
   * 首行在**完整列表**里的序号（2026-10-06，需求 #63 新增）。
   *
   * <p>上报查询为了压住重渲染量，在跟随时只把**尾部窗口**交给这里渲染。
   * 那样窗口的行号每刷新一次就整体前移一位，如果还按 `index` 当 key，
   * React 会认为「每一行的位置都变了」→ 记忆化全部失效、DOM 被大规模重建 ——
   * 等于白做。所以由调用方把「这段窗口从第几行开始」一并传进来，
   * 行号在整个会话里保持稳定：新行永远是新的 key，老行的 key 一辈子不变。
   */
  lineOffset?: number
}) {
  const [expanded, setExpanded] = useState(false)
  // 这里**只切行、不解析**（2026-10-06，需求 #63）：解析交给每行自己的 `LogRow` 去记忆化，
  // 在这儿先解析一遍等于每轮都把整个窗口解析两次，而且第二次还是白解析（结果没人用）。
  const raws = useMemo(() => text.split('\n').filter((l) => l.length > 0), [text])
  const collapsed = collapsible && raws.length > COLLAPSE_AT
  const visible = expanded || !collapsed ? raws : raws.slice(0, COLLAPSE_AT)

  // 必须是 hook（记忆化要靠它），所以放在下面的提前 return 之前 —— 放到后面就是
  // 「渲染次数不同、hook 数量不同」，React 会直接抛错。
  const copyLine = useCallback(async (raw: string) => {
    try {
      await navigator.clipboard.writeText(raw)
      toast.success('已复制原始日志行')
    } catch {
      toast.error('复制失败')
    }
  }, [])

  if (raws.length === 0) {
    return <div className="text-xs text-muted-foreground">{emptyText}</div>
  }

  return (
    <div className="space-y-1">
      <div className="overflow-auto rounded-md bg-muted/60 p-2">
        {visible.map((raw, i) => (
          <LogRow
            key={lineOffset + i}
            raw={raw}
            stateName={stateName}
            fields={fields}
            highlight={highlight}
            hitClass={hitClass}
            index={lineOffset + i}
            onCopy={copyLine}
          />
        ))}
      </div>
      {collapsed && (
        <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={() => setExpanded((v) => !v)}>
          {expanded ? (
            <>
              <ChevronUp className="mr-1 h-3 w-3" />
              收起
            </>
          ) : (
            <>
              <ChevronDown className="mr-1 h-3 w-3" />
              展开全部（{raws.length} 行）
            </>
          )}
        </Button>
      )}
    </div>
  )
}

// ---------- 副组件：期望字段对照 ----------

export function SchemaCompare({ schema, text }: { schema?: string | null; text: string }) {
  const fields = useMemo(() => extractSchemaFields(schema), [schema])
  const actual = useMemo(() => collectJsonKeys(text), [text])

  if (!schema) return null

  if (fields.length === 0) {
    return (
      <div>
        <div className="mb-0.5 text-xs font-medium text-muted-foreground">期望 data</div>
        <div className="max-h-24 overflow-auto rounded-md bg-muted p-2 font-mono text-xs">{schema}</div>
      </div>
    )
  }

  return (
    <div>
      <div className="mb-1 text-xs font-medium text-muted-foreground">期望字段对照</div>
      <div className="flex flex-wrap gap-1">
        {fields.map((f) => {
          const hit = actual.has(f)
          return (
            <Badge key={f} variant={hit ? 'default' : 'destructive'} className="font-mono">
              {hit ? '✓' : '✗'} {f}
            </Badge>
          )
        })}
      </div>
    </div>
  )
}
