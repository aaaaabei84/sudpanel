/**
 * 变量名补全的纯逻辑：判断「光标此刻是否正在写一个 {{变量名}}」、按分组过滤候选、
 * 把选中的名字替换进去。
 *
 * <p>刻意和界面拆开：判定规则全是字符串下标运算，没有 DOM，所以能像 json-text 那样
 * 直接拿 node 跑断言（见 tools/apitest-verify/var-suggest-check.mjs）。一旦这批规则
 * 埋在组件的键盘事件里，就只能靠点浏览器来验，改一个字符都要重跑一遍界面。
 */

/** 一个候选。group 只用于分组显示，不参与匹配。 */
export interface VarSuggestion {
  name: string
  group: string
  scopes?: string[]
}

/** 光标正处在一个没写完的 {{…}} 里。 */
export interface VarQuery {
  /** 「{{」里第一个花括号的下标 —— 替换时从这里开始切。 */
  start: number
  /** 「{{」之后到光标之间的已输入内容，可能为空（刚敲完两个左括号）。 */
  query: string
}

/** 候选浮层最多同时显示几条。多了会把输入框整个盖住。 */
export const MAX_VISIBLE = 8

/**
 * 判断光标是否在一个未闭合的 {{ 变量里。
 *
 * <p>规则是「往回找最近的一对左括号，且它与光标之间不能出现右花括号、左花括号或换行」：
 * <ul>
 *   <li>右花括号 —— 说明这个 {{}} 已经写完了，光标在它外面，不该再弹；</li>
 *   <li>换行 —— 变量名不会跨行，跨行说明那个 {{ 是上一行的遗留；</li>
 *   <li>再一个左花括号 —— 写成 {{{ 了，无法判断用户想要哪一个，不猜。</li>
 * </ul>
 *
 * @param value 输入框当前全文
 * @param caret 光标位置（0 = 最前，value.length = 最后）
 * @returns 命中则返回起点与已输入前缀；否认为 null
 */
export function detectVarQuery(value: string, caret: number): VarQuery | null {
  if (caret < 2 || caret > value.length) return null
  const start = value.lastIndexOf('{{', caret - 1)
  if (start < 0) return null
  // 再往前还是一个左花括号 → {{{ 这类写法，不猜用户想要哪一层
  if (start > 0 && value[start - 1] === '{') return null
  const query = value.slice(start + 2, caret)
  for (const ch of query) {
    if (ch === '}' || ch === '{' || ch === '\n') return null
  }
  return { start, query }
}

/**
 * 按已输入前缀过滤候选，并排序。
 *
 * <p>用「包含」而不是「前缀」匹配：变量名多半是 app_common_self_playing 这种长串，
 * 记得住中间几个字却记不住开头是常态。但开头命中的一定排在前面 —— 那通常才是本意。
 * 前缀为空（刚敲完 {{）时原样全给。
 *
 * @param list 候选（顺序即分组顺序，由调用方决定）
 * @param query 已输入前缀
 * @returns 过滤后的新数组；同名只留第一个，谁先谁赢（自定义变量排在内置前面）
 */
export function filterSuggestions(list: VarSuggestion[], query: string): VarSuggestion[] {
  const seen = new Set<string>()
  const uniq = list.filter((s) => {
    if (seen.has(s.name)) return false
    seen.add(s.name)
    return true
  })
  if (!query) return uniq
  const q = query.toLowerCase()
  const hit = uniq.filter((s) => s.name.toLowerCase().includes(q))
  return [...hit.filter((s) => s.name.toLowerCase().startsWith(q)), ...hit.filter((s) => !s.name.toLowerCase().startsWith(q))]
}

/**
 * 把选中的名字落进文本，并给出替换后光标该停在哪。
 *
 * <p>替换范围是「{{ 到光标」，**再加上光标后面那半截名字和它的收尾括号**。
 * 光标后面之所以会有残留，是因为用户可以把光标挪进一个已经打了一半的名字中间（`{{roo|mId}}`），
 * 或者先打了两个右括号再退回来（`{{|}}`）；只顾着「{{ 到光标」，这两种写法都会拼出
 * `{{roomId}}mId}}` / `{{roomId}}}}` 这种东西。名字允许的字符就是标识符那三类，见到别的就停手，
 * 免得把后面正常的内容也吃掉。
 *
 * @param value 输入框当前全文
 * @param q detectVarQuery 的结果
 * @param caret 光标位置
 * @param name 选中的变量名（不含花括号）
 * @returns 新文本与新的光标下标（停在 }} 之后，可以接着往下打）
 */
export function applySuggestion(
  value: string,
  q: VarQuery,
  caret: number,
  name: string,
): { value: string; caret: number } {
  const token = '{{' + name + '}}'
  const rest = value.slice(caret)
  const m = /^[A-Za-z0-9_$.:/-]*/.exec(rest)
  const tail = m ? m[0] : ''
  const close = rest.slice(tail.length).startsWith('}}') ? 2 : 0
  const end = caret + tail.length + close
  return {
    value: value.slice(0, q.start) + token + value.slice(end),
    caret: q.start + token.length,
  }
}
