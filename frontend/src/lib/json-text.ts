/**
 * JSON 文本的「上色」与「自动排版」。
 *
 * 两件事都建立在一个 token 扫描器上：先把文本切成一段段可上色的片段，再从片段重建带缩进的文本。
 * 之所以共用一个扫描器，是因为两件事对「什么是一个 token」的判断必须完全一致 ——
 * 分别实现的话，一旦某处对字符串里的转义引号处理不同，高亮的边界和排版的边界就会错位。
 *
 * **排版刻意不用 `JSON.parse` 的结果。** 解析一遍只用来判断「这是不是合法 JSON」，
 * 输出走自己的重建逻辑。原因是 JS 的数字是双精度浮点：例如合成边界值
 * 9007199254740993 经 `JSON.parse` 和 `JSON.stringify` 会变为 9007199254740992。
 * 如果该数字参与签名，重建时改变字面量会造成签名与实际请求内容不一致。
 * 所以：解析只做校验，重建只搬 token，任何字面量都不经过 JS 的数值类型。
 */

export type JsonTokenType = 'key' | 'str' | 'num' | 'lit' | 'ph' | 'punct'

export interface JsonToken {
  t: JsonTokenType
  v: string
}

/**
 * 变量占位符 `{{xxx}}` 的颜色类。
 *
 * <p>**只在这里定义一份。** 请求体/请求头的高亮（{@link JSON_TOKEN_CLASS}）与地址栏的高亮
 * （`components/var-input.tsx`）都取它 —— 需求 #54 要解决的正是「同一个弹窗里，
 * 同一个东西有两种表达」，两边各写一个颜色类，过几天就会漂成两种颜色。
 */
export const VAR_PH_CLASS = 'text-fuchsia-700 font-semibold dark:text-fuchsia-300'

/** token 类型到颜色类。只在这里定义一次，展示与输入两处共用，避免两边配色漂移。 */
export const JSON_TOKEN_CLASS: Record<JsonTokenType, string> = {
  key: 'text-sky-700 dark:text-sky-300',
  str: 'text-emerald-700 dark:text-emerald-300',
  num: 'text-amber-700 dark:text-amber-300',
  lit: 'text-violet-700 dark:text-violet-300',
  // 占位符单独一个颜色：它是「待替换」的标记，跟普通字符串值不是一回事
  ph: VAR_PH_CLASS,
  punct: 'text-muted-foreground',
}

const WS = /[ \t\r\n]/

/** 单独成 token 的结构字符。遇到它们兜底扫描就停下，交给上面各个分支处理。 */
const STRUCTURAL = '{}[],:"'

/** 字符串里也可能藏着占位符：`"timestamp":"{{times}}"` 是最常见的写法。 */
const PH_IN_STRING = /(\{\{[^{}]*\}\})/g

/**
 * 输出一个字符串 token，若其中含占位符就再拆开。
 *
 * 拆开只影响「怎么显示」，不影响重建 —— 重建时非标点 token 一律原样拼接，
 * 拆成几段拼回去还是同一个字符串。而显示上把 `{{times}}` 单独标出来是有用的：
 * 它提醒「这里发送时会被换掉」，跟旁边的普通字符串不是一回事。
 */
function pushString(out: JsonToken[], raw: string, type: 'key' | 'str'): void {
  if (!raw.includes('{{')) {
    out.push({ t: type, v: raw })
    return
  }
  for (const seg of raw.split(PH_IN_STRING)) {
    if (seg === '') continue
    out.push(seg.startsWith('{{') && seg.endsWith('}}') ? { t: 'ph', v: seg } : { t: type, v: seg })
  }
}

/** {@link tokenizeVars} 的产物：普通文字，或一段变量占位符。 */
export type VarToken = { t: 'text' | 'ph'; v: string }

/**
 * 把一段**非 JSON** 的文字按变量占位符切开（地址栏用，需求 #54）。
 *
 * <p>和 {@link tokenizeJson} 刻意不是同一个函数。地址是一行 URL，交给 JSON 那套切词器会顺手
 * 把 `:18888`、`/v1` 里的数字标成「数值色」—— 上下文中那些颜色本来各有含义（键、字符串、数值），
 * 借到 URL 上就不再有任何含义，只会让地址栏变得花。这里的要求只有一条：
 * **`{{xxx}}` 是另一种颜色，其余部分保持原色。**
 *
 * <p>判定与 {@link tokenizeJson} 用同一个正则（`PH_IN_STRING`），所以两处「算不算一个完整占位符」
 * 的边界一致：写了一半的 `{{tim` 两边都不上色，写完的 `{{times}}` 两边都上色。
 */
export function tokenizeVars(src: string): VarToken[] {
  if (!src) return []
  const out: VarToken[] = []
  for (const seg of src.split(PH_IN_STRING)) {
    if (seg === '') continue
    out.push(seg.startsWith('{{') && seg.endsWith('}}') ? { t: 'ph', v: seg } : { t: 'text', v: seg })
  }
  return out
}

/**
 * 切词。对合法 JSON 给出准确分类；对半成品（正在打字）或含占位符的模板也能工作 ——
 * 认不出的字符按标点归并，不会把整段吞掉、也不会一个字一个 span。
 *
 * @param keepSpace 是否保留空白片段。
 *   <ul>
 *   <li><b>重建缩进时要关掉</b>（默认）：{@link prettyJson} 自己按层级缩进，
 *       留着原文的空白会把两层缩进叠在一起，越排越乱。</li>
 *   <li><b>渲染时要打开</b>：高亮层是照 token 逐段画出来的，丢掉空白就等于把换行和缩进
 *       一起吃掉 —— 表现是「排版明明做了，显示却还是一整行」，
 *       输入框那层更糟：高亮层和输入层的换行位置不一致，光标会和文字错开。</li>
 *   </ul>
 */
export function tokenizeJson(src: string, keepSpace = false): JsonToken[] {
  const out: JsonToken[] = []
  let i = 0
  while (i < src.length) {
    const c = src[i]

    if (WS.test(c)) {
      // 连续空白攒成一段，不逐字成 token
      let j = i + 1
      while (j < src.length && WS.test(src[j])) j++
      if (keepSpace) out.push({ t: 'punct', v: src.slice(i, j) })
      i = j
      continue
    }

    // 占位符优先于「{ 是对象开头」：{{times}} 里的花括号不该被当成结构
    if (c === '{' && src[i + 1] === '{') {
      const m = /^\{\{[^{}]*\}\}/.exec(src.slice(i))
      if (m) {
        out.push({ t: 'ph', v: m[0] })
        i += m[0].length
        continue
      }
    }

    if (c === '"') {
      // 引号内要跳过转义对，否则 "\"" 会被当成字符串结束
      let j = i + 1
      while (j < src.length) {
        if (src[j] === '\\') {
          j += 2
          continue
        }
        if (src[j] === '"') {
          j++
          break
        }
        j++
      }
      // 后面跟冒号的是键。中间允许空白：pretty 之后是 "key": value
      let k = j
      while (k < src.length && WS.test(src[k])) k++
      pushString(out, src.slice(i, j), src[k] === ':' ? 'key' : 'str')
      i = j
      continue
    }

    if (c === '-' || (c >= '0' && c <= '9')) {
      const m = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(src.slice(i))
      if (m) {
        out.push({ t: 'num', v: m[0] })
        i += m[0].length
        continue
      }
    }

    if (c === 't' || c === 'f' || c === 'n') {
      const m = /^(?:true|false|null)(?![A-Za-z0-9_])/.exec(src.slice(i))
      if (m) {
        out.push({ t: 'lit', v: m[0] })
        i += m[0].length
        continue
      }
    }

    // 兜底：把连续「不是任何 token 开头」的字符攒成一段，避免半成品 JSON 里
    // 每字符一个 span（那会让长文本渲染变慢，也让 React 的 key 列表变得很长）
    let j = i
    while (j < src.length) {
      const d = src[j]
      if (WS.test(d) || STRUCTURAL.includes(d)) break
      if (d === '-' || (d >= '0' && d <= '9')) break
      j++
    }
    out.push({ t: 'punct', v: j > i ? src.slice(i, j) : c })
    i = j > i ? j : i + 1
  }
  return out
}

/** 能不能当 JSON 看。只用来判断，绝不把解析结果拿去序列化（见文件头注释）。 */
export function isJsonText(src: string | null | undefined): boolean {
  if (src == null) return false
  const t = src.trim()
  if (!t) return false
  try {
    JSON.parse(t)
    return true
  } catch {
    return false
  }
}

/**
 * 自动排版：把 token 重新拼成带两空格缩进的文本。
 *
 * 入参必须是已确认合法的 JSON。产出与原文「去掉所有空白后完全相同」——
 * 这一点有断言钉着（见 tools/apitest-verify/ui-check.mjs），因为它正是「不会悄悄改内容」的证明。
 *
 * @returns 排版结果；不是合法 JSON 时返回 null（调用方据此决定不做任何改动）
 */
export function prettyJson(src: string | null | undefined): string | null {
  if (src == null) return null
  const t = src.trim()
  if (!t || !isJsonText(t)) return null

  const toks = tokenizeJson(t)
  const out: string[] = []
  let depth = 0
  const brk = () => out.push('\n' + '  '.repeat(depth))

  for (let k = 0; k < toks.length; k++) {
    const { t: ty, v } = toks[k]
    if (ty !== 'punct') {
      out.push(v)
      continue
    }
    if (v === '{' || v === '[') {
      out.push(v)
      const nx = toks[k + 1]
      const empty =
        !!nx && nx.t === 'punct' && ((v === '{' && nx.v === '}') || (v === '[' && nx.v === ']'))
      if (!empty) {
        depth++
        brk()
      }
      continue
    }
    if (v === '}' || v === ']') {
      const pv = toks[k - 1]
      const wasEmpty =
        !!pv && pv.t === 'punct' && ((v === '}' && pv.v === '{') || (v === ']' && pv.v === '['))
      if (!wasEmpty) {
        depth = Math.max(0, depth - 1)
        brk()
      }
      out.push(v)
      continue
    }
    if (v === ',') {
      out.push(',')
      brk()
      continue
    }
    if (v === ':') {
      out.push(': ')
      continue
    }
    out.push(v)
  }
  return out.join('')
}

export interface TextBlock {
  kind: 'text' | 'json'
  v: string
}

/**
 * 把一段混合文本切成「普通文字」与「JSON 片段」。
 *
 * 需要它的原因：执行记录里的「本次发出的请求」并不是纯 JSON —— 它前面有方法名、地址、
 * 请求头几行，中间夹一行「请求体（原样，N 字节）：」，最后才是 JSON。整段当纯文本显示
 * 就看不出结构，整段当 JSON 又根本解析不了。
 *
 * 用括号配对找边界，而不是「第一个 { 到最后一个 }」：后者在正文里出现两段 JSON
 * （例如响应体里又嵌了一个 JSON 字符串）时会把中间的普通文字一起吞掉。
 */
export function splitJsonBlocks(src: string | null | undefined): TextBlock[] {
  if (src == null || src === '') return []
  const out: TextBlock[] = []
  let i = 0
  let textFrom = 0
  while (i < src.length) {
    const c = src[i]
    if (c !== '{' && c !== '[') {
      i++
      continue
    }
    const end = matchBracket(src, i)
    if (end < 0) {
      i++
      continue
    }
    const seg = src.slice(i, end)
    if (!isJsonText(seg)) {
      // 配平了但不是 JSON（例如模板里的 {{times}}）—— 当普通文字，继续往后找
      i++
      continue
    }
    if (i > textFrom) pushText(out, src.slice(textFrom, i))
    out.push({ kind: 'json', v: seg })
    i = end
    textFrom = end
  }
  if (textFrom < src.length) pushText(out, src.slice(textFrom))
  return out
}

/**
 * 加一个纯文字段。只有空白的不加 —— 它们在界面上是每条 JSON 之间多余的空行，
 * 而各段本来就是独立的块级元素，本来就上下分开。
 */
function pushText(out: TextBlock[], v: string): void {
  if (v.trim() === '') return
  out.push({ kind: 'text', v })
}

/**
 * 从 open 处的 { 或 [ 找到配对的收尾字符，返回其下标 + 1；找不到返回 -1。
 * 引号内的括号不计入配对，转义对整体跳过。
 */
function matchBracket(src: string, open: number): number {
  let depth = 0
  let i = open
  let inStr = false
  while (i < src.length) {
    const c = src[i]
    if (inStr) {
      if (c === '\\') {
        i += 2
        continue
      }
      if (c === '"') inStr = false
      i++
      continue
    }
    if (c === '"') {
      inStr = true
      i++
      continue
    }
    if (c === '{' || c === '[') depth++
    else if (c === '}' || c === ']') {
      depth--
      if (depth === 0) return i + 1
    }
    i++
  }
  return -1
}
