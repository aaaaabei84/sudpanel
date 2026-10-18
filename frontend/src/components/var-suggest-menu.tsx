import type { CSSProperties } from 'react'
import { cn } from '@/lib/utils'
import { MAX_VISIBLE, type VarSuggestion } from '@/lib/var-suggest'

/**
 * 变量名候选浮层。地址、请求头、请求体三个框共用这一个。
 *
 * <p>两条必须守住的细节：
 * <ul>
 *   <li><b>用 onMouseDown 而不是 onClick。</b>点候选时输入框会先失焦，而请求头/请求体框在失焦时
 *       会自动排版 JSON —— 那会改写文本、把刚算好的光标位置作废。mousedown 里
 *       preventDefault 就能拦住这次失焦。</li>
 *   <li><b>分组只做标注，不做分组标题。</b>组标题会让浮层高度随分组数跳变，而浮层要贴着光标，
 *       高度一变视觉上就"跳一下"；把组名放在每行右侧，行高恒定。</li>
 * </ul>
 */
export function VarSuggestMenu({
  items,
  active,
  style,
  onPick,
  onHover,
}: {
  items: VarSuggestion[]
  /** 键盘上下键当前停在第几条 */
  active: number
  /** 绝对定位坐标，由调用方按光标算出 */
  style: CSSProperties
  onPick: (name: string) => void
  onHover: (index: number) => void
}) {
  const shown = items.slice(0, MAX_VISIBLE)
  if (shown.length === 0) return null
  return (
    <div
      role="listbox"
      aria-label="变量名候选"
      style={style}
      className="absolute z-50 min-w-56 overflow-hidden rounded-xl border border-border/80 bg-popover/88 backdrop-blur-2xl text-popover-foreground shadow-2xl ring-1 ring-white/10 dark:ring-white/5"
    >
      <div className="px-2 py-1 text-xs text-muted-foreground">
        变量名 · ↑↓ 选择 · 回车插入 · Esc 关闭
      </div>
      {shown.map((s, i) => (
        <div
          key={s.name}
          role="option"
          aria-selected={i === active}
          // 见上方注释：不能换成 onClick
          onMouseDown={(e) => {
            e.preventDefault()
            onPick(s.name)
          }}
          onMouseEnter={() => onHover(i)}
          className={cn(
            'flex cursor-pointer items-center justify-between gap-3 px-2 py-1 font-mono text-xs',
            i === active ? 'bg-accent text-accent-foreground' : 'hover:bg-accent/50',
          )}
        >
          <span className="truncate">{'{{' + s.name + '}}'}</span>
          <span className="shrink-0 font-sans text-xs text-muted-foreground">{s.group}</span>
        </div>
      ))}
      {items.length > MAX_VISIBLE && (
        <div className="border-t px-2 py-1 text-xs text-muted-foreground">
          还有 {items.length - MAX_VISIBLE} 个，继续打字缩小范围
        </div>
      )}
    </div>
  )
}
