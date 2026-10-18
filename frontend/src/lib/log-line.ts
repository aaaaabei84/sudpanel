/**
 * logcat 行的解析与过滤纯逻辑（2026-10-06，需求 #60 上报查询）。
 *
 * <p>刻意从 `log-highlight.tsx` 那个组件里抽出来单独放：这里全是纯函数，
 * 能被断言脚本直接 import 压测。放在组件文件里就得连 React 一起拖进来，压不了。
 * 项目里已有的分工也是这样（`json-text.ts` 纯逻辑 / `json-block.tsx` 渲染）。
 */

/**
 * 一行 logcat 的结构。
 *
 * <p>标准格式：`MM-DD HH:MM:SS.mmm  pid  tid  L  tag: body`
 * 所以 meta 是「时间 pid tid 级别 tag」那一段，body 是冒号之后的正文。
 * 界面把 meta 显示成灰色小字、body 显示成正文字号加高亮，分开就是为了这个。
 */
export interface ParsedLogLine {
  raw: string
  meta: string
  body: string
}

const LINE_META_RE = /^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+\S+):\s?(.*)$/s

/** 级别字符的判定。与 LINE_META_RE 里那一段保持同一形状，改一处必须改另一处。 */
const LEVEL_RE = /^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s+/

/** 六个级别，从最啰嗦到最严重。AS 的 Logcat 也是这六个。 */
export const LOG_LEVELS = ['V', 'D', 'I', 'W', 'E', 'F'] as const

/**
 * 取一行的日志级别。
 *
 * <p>认不出返回 `null`，调用方自己决定怎么处理。这里**不猜** ——
 * 把认不出的行当成某个级别，会让过滤结果悄悄错掉（少几行而没人察觉）。
 */
export function levelOf(raw: string): string | null {
  const m = raw.match(LEVEL_RE)
  return m ? m[1] : null
}

/** 拆成 meta 与 body；不是标准格式时整行都算 body（日志原文一个字都不丢）。 */
export function parseLogLine(raw: string): ParsedLogLine {
  const m = raw.match(LINE_META_RE)
  if (m) {
    return { raw, meta: m[1], body: m[2] }
  }
  return { raw, meta: '', body: raw }
}

/**
 * 造一个「这一行留不留」的判定函数（关键字与级别各只需归一化一次）。
 *
 * <p>把判定从 {@link filterLogLines} 里抽出来的原因（2026-10-06，需求 #63）：
 * 上报查询在跟随时只渲染**尾部窗口**，需要知道「留下的这些行，在原数组里是第几行」——
 * 那是稳定 key 的依据。为此有了 {@link filterLogIndexes}。
 * 两个函数共用这一个判定，是为了让上面那两条规则只有一份实现。
 */
export function makeLogFilter(
  keyword: string,
  levels: Iterable<string>,
): (raw: string) => boolean {
  const kw = keyword.trim()
  const set = new Set(levels)
  return (raw: string) => {
    if (kw !== '' && !raw.includes(kw)) {
      return false
    }
    const lv = levelOf(raw)
    if (lv === null || set.size === 0) {
      return true
    }
    return set.has(lv)
  }
}

/**
 * 按关键字与级别过滤日志行。
 *
 * @param lines   原始行
 * @param keyword 子串匹配；空/空白 = 不过滤
 * @param levels  勾选的级别集合
 *
 * <h2>两条刻意的规则</h2>
 * <ol>
 *   <li><b>级别集合为空 = 不过滤</b>（而不是「全都滤掉」）。界面上那排级别按钮一个都不勾时，
 *       人的预期是「没有限制」，不是「什么都不显示」—— 后者会让人以为日志断了。</li>
 *   <li><b>认不出级别的行不受级别过滤影响</b>。多行日志的续行、非标准输出都没有级别可判；
 *       拦掉它们会让「少了几行」变得无法解释。关键字过滤对它们照常生效。</li>
 * </ol>
 */
export function filterLogLines(lines: string[], keyword: string, levels: Iterable<string>): string[] {
  return lines.filter(makeLogFilter(keyword, levels))
}

/**
 * 与 {@link filterLogLines} 同一套规则，但返回的是**留下那些行在原数组里的下标**。
 *
 * <p>为什么需要它（2026-10-06，需求 #63）：上报查询只渲染尾部窗口，
 * 窗口每刷新一次就整体前移几行。若按「窗口内的相对下标」给 React 当 key，
 * 每刷新一次所有行的 key 都会变 → 行组件的记忆化全部失效、DOM 大规模重建。
 * 拿到**绝对下标**才能让老行的 key 一辈子不变。
 *
 * <p>与 `filterLogLines` 分开而不是加个参数：两者的返回类型不同，
 * 合成一个函数就得写成联合类型，调用方每次都要判一次类型。
 */
export function filterLogIndexes(
  lines: string[],
  keyword: string,
  levels: Iterable<string>,
): number[] {
  const match = makeLogFilter(keyword, levels)
  const out: number[] = []
  for (let i = 0; i < lines.length; i++) {
    if (match(lines[i])) {
      out.push(i)
    }
  }
  return out
}

// ==================== 上色用：把一行拆成可分别着色的片段 ====================
//
// 这一段全部是纯函数，好让「拆得对不对」能被断言脚本压住 —— 上色拆错了不会报错，
// 只会让界面看着别扭或把值显示成分开的两截，那种问题没人会写单测，所以更难发现。

/** 行首 meta 拆开后的几段。AS 的 Logcat 也是这么分的。 */
export interface MetaParts {
  time: string
  pid: string
  tid: string
  level: string
  tag: string
}

const META_SPLIT_RE =
  /^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+)$/

/**
 * 把行首 meta 拆成「时间 / pid / tid / 级别 / tag」五段，供分别着色。
 *
 * <p>认不出就返回 `null`（调用方整段原样显示）。**不猜**：把不认识的东西硬切成几段，
 * 会让界面出现「时间那栏显示的不是时间」这种一眼假的东西，比不上色更糟。
 */
export function splitMeta(meta: string): MetaParts | null {
  const m = meta.match(META_SPLIT_RE)
  if (!m) {
    return null
  }
  return { time: m[1], pid: m[2], tid: m[3], level: m[4], tag: m[5] }
}

/** `键=值` 的值的类型。显式写出来而不是用条件类型推导 —— 后者在联合类型上会解析成 never。 */
export type ValueType = 'number' | 'literal' | 'text'

/** 一段正文的切分结果：要么是普通文本，要么是一个 `键=值`。 */
export type BodySegment =
  | { kind: 'text'; text: string }
  | { kind: 'kv'; key: string; value: string; valueType: ValueType }

/**
 * 日志级别 → 样式类（2026-10-06，需求 #60 上色增强）。
 *
 * <p>放在这里而不是组件里，是为了让「六个级别是否都有配色、是否互不相同」能被断言脚本压住 ——
 * 把两个级别写成同一个颜色，界面上就再也分不出它们，而那种错没人会主动去查。
 *
 * <p>`F`（Fatal）刻意与 `E` 同色但加粗：致命错误本来就该先看到，而多引一种颜色只会让图例更难记。
 */
export const LEVEL_CLASS: Record<string, string> = {
  V: 'text-gray-400',
  D: 'text-blue-500 dark:text-blue-400',
  I: 'text-emerald-600 dark:text-emerald-400',
  W: 'text-amber-600 dark:text-amber-400',
  E: 'text-red-600 dark:text-red-400',
  F: 'text-red-600 font-bold dark:text-red-400',
}

/** `键=值` 的值 → 样式类。与 JSON 那边同一套规则：同一份日志看着才是同一个体系。 */
export const KV_VALUE_CLASS: Record<ValueType, string> = {
  number: 'text-orange-500',
  literal: 'text-purple-500',
  text: 'text-green-600 dark:text-green-400',
}

/** 行首几段的样式类。集中在这里，同样是为了可被断言核对。 */
export const META_CLASS = {
  time: 'text-gray-500',
  pidTid: 'text-gray-400/70',
  tag: 'text-cyan-700 dark:text-cyan-400',
} as const

/**
 * 过滤关键字**命中**时的样式（2026-10-06，需求 #66）。
 *
 * <p>使用者的原话是「命中的关键字需要做差异化处理，例如用**放大和改颜色**的方法展示」，
 * 两条都落在这一串类里：字号从 `text-xs`(12px) 提到 `text-sm`(14px)，颜色由正文默认色
 * 改成琥珀色，另加一层浅底把命中片段从整行里挑出来。
 *
 * <p><b>为什么放在这里而不是组件里</b>：与 `LEVEL_CLASS` / `KV_VALUE_CLASS` / `META_CLASS`
 * 同一个理由 —— 它是纯数据，放在这里才能被断言脚本核对（「是否真的既有放大又有改色」
 * 「有没有用到会撑高行高的字号」这两条）。放在 `.tsx` 里就得连 React 一起拖进来，压不了。
 *
 * <p><b>为什么字号只能到 {@link KEYWORD_HIT_CLASS} 这个量级</b>（这条比样式本身重要）：
 * 日志行的行高由父元素的 `leading-4`（16px）决定 —— 那是个**具体长度**，会被子元素继承，
 * 不随子元素字号变；14px 的字排进 16px 的行高正好。
 * 上报查询的滚动跟随全靠「内容高度在连续变化」（需求 #63），一旦命中的行比别的行高，
 * 每来一批新行高度就跳一下 —— 那正是需求 #65 好不容易修掉的那类抖动。
 * 所以**不要**图醒目改成 `text-base` / `text-lg`。
 */
export const KEYWORD_HIT_CLASS =
  'text-sm font-bold text-amber-700 bg-amber-200/70 rounded-sm px-0.5 dark:text-amber-300 dark:bg-amber-500/25'

const KV_RE = /([A-Za-z_][A-Za-z0-9_.]*)=("[^"]*"|[^\s,;，；]+)/g

function valueTypeOf(v: string): ValueType {
  if (/^-?\d+(\.\d+)?$/.test(v)) {
    return 'number'
  }
  if (v === 'true' || v === 'false' || v === 'null' || v === 'undefined') {
    return 'literal'
  }
  return 'text'
}

/**
 * 把一段正文切成「普通文本 / 键=值」的片段序列，供键与值分别着色。
 *
 * <p>为什么需要它：上报日志里除了 JSON，还大量存在 `onEvent isIn=true roomId=3333`
 * 这种**裸键值对**。JSON 那部分已经被单独切走了，剩下的这些如果不处理，
 * 一整行就只有一个颜色，「一眼扫出哪个字段是什么值」这件事就做不到。
 *
 * <p>值的类型决定了配色（数字 / 字面量 / 文本），与 JSON 那边的配色保持一致 ——
 * 同一份日志里两种写法看着应该是同一套颜色规则。
 */
export function splitBodySegments(text: string): BodySegment[] {
  const out: BodySegment[] = []
  if (!text) {
    return out
  }
  let last = 0
  KV_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = KV_RE.exec(text)) !== null) {
    if (m.index > last) {
      out.push({ kind: 'text', text: text.slice(last, m.index) })
    }
    const value = m[2]
    out.push({ kind: 'kv', key: m[1], value, valueType: valueTypeOf(value) })
    last = m.index + m[0].length
  }
  if (last < text.length) {
    out.push({ kind: 'text', text: text.slice(last) })
  }
  return out
}

// ==================== 关键字命中切分（需求 #66）====================
//
// 过滤之后「为什么这几行留下了」应该一眼看得出来：命中的那段要放大加色。
// 这段逻辑放在这里（而不是留在组件里）的理由与上面几段相同 —— 它错了不会报错，
// 只会让某些片段不上色、或者干脆让整页白屏，而这两种表现都不像是「切分写错了」。

/** 一段文本按关键字切开后的片段。 */
export interface KeywordSegment {
  text: string
  hit: boolean
}

const REGEX_SPECIALS = /[.*+?^${}()|[\]\\]/g

/**
 * 把一段文本按命中的关键字切成片段，供命中处单独上色。
 *
 * <h2>三处容易写错的地方（每一处都有具体的错法）</h2>
 * <ol>
 *   <li><b>关键字必须转义</b>：使用者存的关键字里完全可能有 `.`、`(`、`[`。
 *       不转义时 `(` 会让 `new RegExp` **直接抛错** —— 那不是「少上点色」，
 *       而是整块日志区白屏；而 `.` 会退化成「匹配任意字符」，把不相干的位置也染上色。</li>
 *   <li><b>长关键字要排在前面</b>：下拉里可能同时存着 `isIn` 与 `isInT`。
 *       若短的先匹配，`isInT` 会被切成「命中 isIn」+「不上色的 T」——
 *       看起来就像 T 被漏掉了。按长度降序拼进正则，长的先命中。</li>
 *   <li><b>空关键字要滤掉</b>：`''` 拼进正则会变成空分支，`split` 的结果里会多出一串空片段。</li>
 * </ol>
 *
 * <p>去重（`Set`）不是可有可无：同一个关键字出现两次会在正则里生成两个相同的分支，
 * 虽然结果一样，但会让「长优先」的排序变得没意义。
 *
 * @param text     要切的文本
 * @param keywords 关键字集合（过滤输入框当前那一个，加上走查页的状态名与期望字段）
 * @returns 片段序列；没有命中时返回单个 {@code hit: false} 的整段，调用方无需特判
 */
export function splitByKeywords(text: string, keywords: string[]): KeywordSegment[] {
  if (!text) {
    return []
  }
  const valid = [...new Set(keywords.filter((k) => k && text.includes(k)))]
  if (valid.length === 0) {
    return [{ text, hit: false }]
  }
  const sorted = valid.sort((a, b) => b.length - a.length)
  const esc = sorted.map((k) => k.replace(REGEX_SPECIALS, '\\$&'))
  const re = new RegExp(`(${esc.join('|')})`, 'g')
  const hits = new Set(sorted)
  const out: KeywordSegment[] = []
  for (const part of text.split(re)) {
    if (part !== '') {
      out.push({ text: part, hit: hits.has(part) })
    }
  }
  return out
}
