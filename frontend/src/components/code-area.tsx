import {
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ClipboardEvent,
  type KeyboardEvent,
} from 'react'
import { cn } from '@/lib/utils'
import { JSON_TOKEN_CLASS, prettyJson, tokenizeJson } from '@/lib/json-text'
import {
  applySuggestion,
  detectVarQuery,
  filterSuggestions,
  MAX_VISIBLE,
  type VarQuery,
  type VarSuggestion,
} from '@/lib/var-suggest'
import { VarSuggestMenu } from '@/components/var-suggest-menu'

/** 初始高度与「再高就自己在框里滚」的上限。 */
const MIN_H = 148
const MAX_H = 320

/** 候选浮层宽度（与 var-suggest-menu 的 min-w-52 对齐），用于把浮层夹在框内。 */
const MENU_W = 208

/**
 * 带即时上色的文本域，用来填请求头和请求体。
 *
 * <p>做法是叠两层：下面一层 `<pre>` 负责显示彩色文字，上面一层透明文字的 `<textarea>` 负责
 * 接收输入、显示光标与选中。两层的内容与排版必须完全一致，所以字体、行高、内边距都写成同一组类 ——
 * 只要有一处不同，光标位置就会和看到的字错开，比不高亮还难用。
 *
 * <p>两层同时存在的代价是滚动要手工同步：`textarea` 内部滚动时把偏移量抄给 `pre`。
 * 高度由脚本按内容算（有上限），这样短内容不会留一大片空白，长内容也不会把整个弹窗顶到天上去。
 *
 * <p>另外两个刻意的取舍：
 * <ul>
 *   <li><b>高亮不等「能解析成功」。</b>正在打字时当然还不是合法 JSON，但用户要的正是这一刻的反馈。
 *       所以只要认得出的片段就先上色，认不出的按标点显示 —— 不会因为半个括号就整块变成黑白。</li>
 *   <li><b>自动排版只在离开输入框和粘贴时做，且只在能解析时做。</b>逐键排版会把光标顶到别处去，
 *       没法正常打字；而请求体模板里常见的 {@code {{times}}} 本来就不是合法 JSON，
 *       那时排版会失败 —— 失败就一个字都不动，比「尽力格式化」安全得多。</li>
 * </ul>
 *
 * <p>传了 `suggestions` 时，框里打 `{{` 会在光标下方浮出变量名候选（上下键选、回车插入、Esc 关闭）。
 * 浮层坐标不是猜的：第三层隐藏的「镜像」元素用与 `<pre>` 完全相同的排版把光标之前的文字画一遍，
 * 末尾跟一个零宽标记，量这个标记的位置就得到光标的真实坐标 —— textarea 自身不提供这个能力。
 */
export function CodeArea({
  id,
  value,
  onChange,
  placeholder,
  className,
  suggestions,
  formatJson = true,
}: {
  id?: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  className?: string
  /** 变量名候选；不传或传空数组则完全没有补全行为 */
  suggestions?: VarSuggestion[]
  /** RAW 请求体关闭自动排版，保留用户填写的空白。 */
  formatJson?: boolean
}) {
  const ta = useRef<HTMLTextAreaElement>(null)
  const pre = useRef<HTMLPreElement>(null)
  const mirror = useRef<HTMLDivElement>(null)
  const mark = useRef<HTMLSpanElement>(null)
  const root = useRef<HTMLDivElement>(null)
  /** 点选候选后要把光标放回哪里；等新值渲染完再由 layout effect 落下去 */
  const pendingCaret = useRef<number | null>(null)
  /** 上一次「正在写的那个变量」的身份，用来判断要不要把高亮重置回第一条 */
  const lastKey = useRef<string | null>(null)

  const [menu, setMenu] = useState<VarQuery | null>(null)
  const [box, setBox] = useState<{ top: number; left: number } | null>(null)
  const [active, setActive] = useState(0)
  /** 镜像里要画到光标的第几个字符；为 null 时不画（浮层没开） */
  const [mirrorCaret, setMirrorCaret] = useState<number | null>(null)

  const enabled = !!suggestions && suggestions.length > 0
  const items = useMemo(
    () => (menu && enabled ? filterSuggestions(suggestions!, menu.query) : []),
    [menu, enabled, suggestions],
  )

  // 必须带 keepSpace：这层是照 token 逐段画出来的，丢掉空白就等于丢掉换行与缩进，
  // 于是高亮层的折行位置和输入层对不上 —— 光标会和看到的字错开。
  const tokens = useMemo(() => tokenizeJson(value, true), [value])

  const syncScroll = useCallback(() => {
    const t = ta.current
    if (!t) return
    if (pre.current) {
      pre.current.scrollTop = t.scrollTop
      pre.current.scrollLeft = t.scrollLeft
    }
    // 镜像也必须跟着滚：它是「光标在哪」的量具，不跟着滚就会量出没滚动时的位置
    if (mirror.current) {
      mirror.current.scrollTop = t.scrollTop
      mirror.current.scrollLeft = t.scrollLeft
    }
  }, [])

  // 用 layout effect：在浏览器绘制前就把高度定下来，避免打字时框体一跳一跳
  useLayoutEffect(() => {
    const el = ta.current
    if (!el) return
    // 先归零再量：不归零的话删掉内容时 scrollHeight 仍按旧高度返回，框体只会长不会缩
    el.style.height = '0px'
    el.style.height = `${Math.min(Math.max(el.scrollHeight, MIN_H), MAX_H)}px`
    syncScroll()
  }, [value, syncScroll])

  /** 重新判断光标是不是在一个没写完的 {{ 里。每次输入、移动光标、点击都调一次。 */
  const refresh = useCallback(() => {
    const el = ta.current
    if (!enabled || !el) return
    const caret = el.selectionStart ?? 0
    const next = detectVarQuery(el.value, caret)
    // 只有「正在写的那个变量」变了才把高亮重置回第一条。
    // 上下键选完之后还会来一次 keyup，无条件重置的话高亮永远停在第一条，键盘就用不了。
    const key = next ? `${next.start}:${next.query}` : null
    if (key !== lastKey.current) {
      lastKey.current = key
      setActive(0)
    }
    setMenu((prev) =>
      prev && next && prev.start === next.start && prev.query === next.query ? prev : next,
    )
    setMirrorCaret(next ? caret : null)
  }, [enabled])

  // 量光标位置 → 定位浮层。放在 layout effect 里，画之前就定好，避免浮层闪一下再跳。
  useLayoutEffect(() => {
    if (!menu || !enabled) return
    const t = ta.current
    const m = mark.current
    const rootEl = root.current
    if (!t || !m || !rootEl) return
    const lh = parseFloat(window.getComputedStyle(mirror.current ?? m).lineHeight) || 19
    // 浮层贴在光标所在行的正下方。不往框内夹：框只有一行高时也该露在框外面，
    // 拖回框里反而会盖住正在写的字。
    const top = m.offsetTop - t.scrollTop + lh + 4
    const maxLeft = Math.max(rootEl.clientWidth - MENU_W - 8, 4)
    const left = Math.min(Math.max(m.offsetLeft - t.scrollLeft, 4), maxLeft)
    setBox((prev) => (prev && prev.top === top && prev.left === left ? prev : { top, left }))
  }, [menu, enabled, value, items.length])

  /** 落选项：把 {{ 到光标之间的内容换成完整占位符，并把光标停到 }} 之后。 */
  const commit = useCallback(
    (name: string) => {
      const el = ta.current
      if (!el || !menu) return
      const caret = el.selectionStart ?? 0
      const r = applySuggestion(el.value, menu, caret, name)
      pendingCaret.current = r.caret
      setMenu(null)
      setMirrorCaret(null)
      onChange(r.value)
    },
    [menu, onChange],
  )

  // 值换完、DOM 更新后把光标放回去。必须在这里做：onChange 只改数据，改不了光标。
  useLayoutEffect(() => {
    const el = ta.current
    if (!el || pendingCaret.current == null) return
    const at = pendingCaret.current
    pendingCaret.current = null
    el.focus()
    el.setSelectionRange(at, at)
  }, [value])

  /** 离开输入框时排版一次。不能解析就原样留着。 */
  const formatOnBlur = () => {
    setMenu(null)
    setMirrorCaret(null)
    if (!formatJson) return
    const next = prettyJson(value)
    if (next != null && next !== value) onChange(next)
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!menu || items.length === 0) return
    const shown = Math.min(items.length, MAX_VISIBLE)
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => (i + 1) % shown)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => (i - 1 + shown) % shown)
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault()
      commit((items[Math.min(active, shown - 1)] ?? items[0]).name)
    } else if (e.key === 'Escape') {
      e.preventDefault()
      setMenu(null)
      setMirrorCaret(null)
    }
  }

  /**
   * 粘贴时先排版再落进框里。
   *
   * 直接在粘贴事件里算好、再手动替换选区，而不是「先让浏览器粘进去、再读出来改」——
   * 后者会在光标位置和浏览器撤销记录上留下一次中间状态，用户按 Ctrl+Z 会先撤销排版、再撤销粘贴。
   */
  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    if (!formatJson) return
    const pasted = e.clipboardData.getData('text')
    const pretty = prettyJson(pasted)
    if (pretty == null) return // 不是 JSON：走浏览器默认粘贴
    e.preventDefault()
    const el = e.currentTarget
    const from = el.selectionStart
    const to = el.selectionEnd
    onChange(el.value.slice(0, from) + pretty + el.value.slice(to))
  }

  return (
    <div
      ref={root}
      className={cn(
        'relative rounded-md border focus-within:ring-1 focus-within:ring-ring',
        className,
      )}
    >
      <pre
        ref={pre}
        aria-hidden
        className="pointer-events-none absolute inset-0 overflow-hidden px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]"
      >
        {tokens.map((tk, i) => (
          <span key={i} className={JSON_TOKEN_CLASS[tk.t]}>
            {tk.v}
          </span>
        ))}
        {/* 末尾补一个换行：textarea 里最后一行按回车后光标会落到新的空行上，
            而 <pre> 结尾的换行不产生行盒 —— 不补这一下，两层的高度就会差一行。 */}
        {'\n'}
      </pre>
      {/* 量光标位置用的镜像：排版类必须和上面的 <pre> 逐个一致，否则量出来的坐标是错的。
          末尾那个零宽字符保证光标落在一个空行上时也有行盒可量。 */}
      <div
        ref={mirror}
        aria-hidden
        className="invisible pointer-events-none absolute inset-0 overflow-hidden px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]"
      >
        {mirrorCaret == null ? null : (
          <>
            {value.slice(0, mirrorCaret)}
            <span ref={mark}>{'\u200b'}</span>
          </>
        )}
      </div>
      <textarea
        id={id}
        ref={ta}
        value={value}
        onChange={(e) => {
          onChange(e.target.value)
          refresh()
        }}
        onKeyDown={onKeyDown}
        onKeyUp={refresh}
        onClick={refresh}
        onSelect={refresh}
        onBlur={formatOnBlur}
        onPaste={onPaste}
        onScroll={syncScroll}
        spellCheck={false}
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        placeholder={placeholder}
        // text-transparent 让字隐形（由下面的 pre 显示），caret 与 placeholder 单独给色
        className="relative block w-full resize-none overflow-auto bg-transparent px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap text-transparent caret-foreground outline-none [overflow-wrap:anywhere] selection:bg-primary/25 placeholder:text-muted-foreground"
      />
      {menu && box && items.length > 0 && (
        <VarSuggestMenu
          items={items}
          active={active}
          style={{ top: box.top, left: box.left }}
          onPick={commit}
          onHover={setActive}
        />
      )}
    </div>
  )
}
