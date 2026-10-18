import { useCallback, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Input } from '@/components/ui/input'
import { VarSuggestMenu } from '@/components/var-suggest-menu'
import { cn } from '@/lib/utils'
import { VAR_PH_CLASS, tokenizeVars } from '@/lib/json-text'
import {
  applySuggestion,
  detectVarQuery,
  filterSuggestions,
  MAX_VISIBLE,
  type VarQuery,
  type VarSuggestion,
} from '@/lib/var-suggest'

/**
 * 单行输入框 + 变量名补全 + 变量引用上色（地址框用）。需求 #38（补全）、#54（上色）。
 *
 * <p><b>为什么这里要自己搭一层高亮。</b>请求头/请求体用的是 {@code CodeArea}（叠加式高亮），
 * 地址栏是单行 {@code <input>}，两条路各走各的。结果就是同一个弹窗里，同一段 `{{url_fat_}}`
 * 在请求体里是彩色、在地址栏里和后面的 `/v1/api` 一个颜色 —— 看不出哪一段是变量。需求 #54 要的
 * 就是这个一致。
 *
 * <p><b>做法与 {@code CodeArea} 同源：叠两层。</b>下面一层只负责画彩色文字，上面那层 {@code <input>}
 * 把文字设成透明色、只留光标和选中区，负责真正的输入。两层的内容与排版必须一字不差地重合，
 * 差一点就会出现「光标和看到的字错开」——那比不高亮还难用。
 *
 * <p><b>度量不写死在类名里，而是从输入框自己身上量。</b>输入框的字体/内边距来自
 * `ui/input.tsx` 的默认类**加上**调用方传进来的 `className`（这里传的是 `font-mono text-sm`），
 * 两处合并的结果只有浏览器算得准。把同一套类名再抄一遍到高亮层，等于埋一个「以后谁改了
 * input.tsx 的 `px-2.5`，两处就错开」的坑；量出来再贴回去就不存在这个问题。
 * 连颜色也是量出来的 —— 输入框自己的颜色已经被设成透明，所以取它**父元素**继承到的那个颜色，
 * 那正是它以前显示文字用的颜色。
 *
 * <p><b>单行还多一件事：横向滚动要同步。</b>地址比框宽时输入框会自己横滚（光标往右走，
 * 前面的字移出可视区），高亮层不跟着滚就会错位。纵向不用管 —— 单行框没有纵向滚动。
 *
 * <p><b>浮层画在哪</b>这条仍然和其它字段不同：单行框的光标永远在同一行，所以不需要镜像量位置，
 * 固定贴在框的正下方即可。判定与过滤规则与 {@code CodeArea} 共用（lib/var-suggest）。
 */
export function VarInput({
  id,
  'aria-label': ariaLabel,
  value,
  onChange,
  placeholder,
  className,
  suggestions,
}: {
  id?: string
  'aria-label'?: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  className?: string
  suggestions?: VarSuggestion[]
}) {
  const input = useRef<HTMLInputElement>(null)
  const layer = useRef<HTMLDivElement>(null)
  const lastKey = useRef<string | null>(null)
  const pendingCaret = useRef<number | null>(null)

  const [menu, setMenu] = useState<VarQuery | null>(null)
  const [active, setActive] = useState(0)

  const enabled = !!suggestions && suggestions.length > 0
  const items = useMemo(
    () => (menu && enabled ? filterSuggestions(suggestions!, menu.query) : []),
    [menu, enabled, suggestions],
  )

  // 只切「占位符 vs 其余」两类，不像 JSON 那样按键/值/数值分色（见 tokenizeVars 的说明）
  const tokens = useMemo(() => tokenizeVars(value), [value])

  /**
   * 把输入框的排版量出来贴到高亮层上。
   *
   * <p><b>位置和尺寸按输入框自己的盒子算（`offsetTop/offsetWidth/offsetHeight`），不用 `inset: 0`。</b>
   * `<input>` 默认是 `inline-block`，它会坐在父元素的行盒基线上 —— 父 div 的高度因此可能比输入框本身
   * 高出几像素（基线以下那段行盒的 descent）。照「父元素四边内缩一个边框宽」去定高亮层，
   * 层就比输入框高出一截，再配合下面那句垂直居中，文字会整体偏下半个差值。
   * 直接量输入框的盒子就没有这个问题：它多高，层就多高。
   *
   * <p>量出来的是「边框盒」，所以往里让一个边框宽就是它的内边距盒；内边距本身整份搬过去，
   * 这样两层的**文字起始点**是同一个点。`<input>` 里的文字在内容框里是**垂直居中**的，
   * 所以高亮层也用 flex 居中；照普通块级从上往下排会差半个行高。
   */
  const syncMetrics = useCallback(() => {
    const el = input.current
    const l = layer.current
    if (!el || !l) return
    const cs = window.getComputedStyle(el)
    const bw = parseFloat(cs.borderTopWidth) || 0
    const s = l.style
    s.top = `${el.offsetTop + bw}px`
    s.left = `${el.offsetLeft + bw}px`
    s.right = 'auto'
    s.bottom = 'auto'
    s.width = `${el.offsetWidth - 2 * bw}px`
    s.height = `${el.offsetHeight - 2 * bw}px`
    s.padding = `${cs.paddingTop} ${cs.paddingRight} ${cs.paddingBottom} ${cs.paddingLeft}`
    s.fontFamily = cs.fontFamily
    s.fontSize = cs.fontSize
    s.fontStyle = cs.fontStyle
    s.fontWeight = cs.fontWeight
    s.fontVariant = cs.fontVariant
    s.lineHeight = cs.lineHeight
    s.letterSpacing = cs.letterSpacing
    s.wordSpacing = cs.wordSpacing
    s.textIndent = cs.textIndent
    s.textTransform = cs.textTransform
    const host = el.parentElement
    if (host) s.color = window.getComputedStyle(host).color
  }, [])

  const syncScroll = useCallback(() => {
    const el = input.current
    const l = layer.current
    if (el && l) l.scrollLeft = el.scrollLeft
  }, [])

  // 用 layout effect：在浏览器绘制前就把度量对上，否则打字时高亮会先闪一下再归位
  useLayoutEffect(() => {
    syncMetrics()
    syncScroll()
    // 光标顶到右端时浏览器是**在这一帧之后**才把输入框滚过去的，当场读 scrollLeft 还是旧值。
    // 补一帧再对一次，长地址才不会有「打一个字、高亮层错位一次」。
    const raf = requestAnimationFrame(syncScroll)
    return () => cancelAnimationFrame(raf)
  }, [syncMetrics, syncScroll, className, value])

  const refresh = () => {
    const el = input.current
    if (!enabled || !el) return
    const caret = el.selectionStart ?? 0
    const next = detectVarQuery(el.value, caret)
    const key = next ? `${next.start}:${next.query}` : null
    if (key !== lastKey.current) {
      lastKey.current = key
      setActive(0)
    }
    setMenu((prev) =>
      prev && next && prev.start === next.start && prev.query === next.query ? prev : next,
    )
  }

  const commit = (name: string) => {
    const el = input.current
    if (!el || !menu) return
    const caret = el.selectionStart ?? 0
    const r = applySuggestion(el.value, menu, caret, name)
    pendingCaret.current = r.caret
    setMenu(null)
    onChange(r.value)
  }

  // 值换完、DOM 更新后把光标放回 }} 之后
  useLayoutEffect(() => {
    const el = input.current
    if (!el || pendingCaret.current == null) return
    const at = pendingCaret.current
    pendingCaret.current = null
    el.focus()
    el.setSelectionRange(at, at)
  }, [value])

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
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
    }
  }

  return (
    <div className="relative rounded-lg dark:bg-input/30">
      {/* 高亮层。必须排在输入框**前面**：它得在下面，否则会盖住输入框画的光标。
          pointer-events-none 让点击、拖选、双击选词全都照常落到输入框上。 */}
      <div
        ref={layer}
        aria-hidden
        className="pointer-events-none absolute flex items-center overflow-hidden whitespace-pre"
      >
        {tokens.map((tk, i) => (
          <span key={i} className={tk.t === 'ph' ? VAR_PH_CLASS : undefined}>
            {tk.v}
          </span>
        ))}
      </div>
      <Input
        id={id}
        aria-label={ariaLabel}
        ref={input}
        value={value}
        // text-transparent 让输入框自己的字隐形（由下面那层显示），光标与 placeholder 单独给色；
        // dark:bg-transparent 是必须的：输入框自带 `dark:bg-input/30` 的一层底色，
        // 它压在高亮层上面，会把高亮层的字整体压暗。
        className={cn(
          className,
          'relative bg-transparent text-transparent caret-foreground selection:bg-primary/25 placeholder:text-muted-foreground dark:bg-transparent',
        )}
        placeholder={placeholder}
        spellCheck={false}
        autoComplete="off"
        onChange={(e) => {
          onChange(e.target.value)
          refresh()
        }}
        onKeyDown={onKeyDown}
        onKeyUp={refresh}
        onClick={refresh}
        onSelect={refresh}
        onScroll={syncScroll}
        // 离开就把浮层收掉：鼠标点了别处还留着一个候选框，看起来像没关掉的弹窗
        onBlur={() => setMenu(null)}
      />
      {menu && items.length > 0 && (
        <VarSuggestMenu
          items={items}
          active={active}
          style={{ top: 'calc(100% + 4px)', left: 0 }}
          onPick={commit}
          onHover={setActive}
        />
      )}
    </div>
  )
}
