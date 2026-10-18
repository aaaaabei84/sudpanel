import { useMemo, useState } from 'react'
import { Maximize2 } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import {
  JSON_TOKEN_CLASS,
  prettyJson,
  splitJsonBlocks,
  tokenizeJson,
  type JsonToken,
} from '@/lib/json-text'

/** token 列表 → 带颜色的片段。key 用下标即可：列表由同一次扫描产出，不含用户可变顺序。 */
function renderTokens(tokens: JsonToken[]) {
  return tokens.map((tk, i) => (
    <span key={i} className={JSON_TOKEN_CLASS[tk.t]}>
      {tk.v}
    </span>
  ))
}

/**
 * 只读的正文展示块：能解析成 JSON 就按缩进上色，否则按纯文本折行。
 *
 * <p>折行用 `whitespace-pre-wrap` + `overflow-wrap:anywhere`：前者保住原有的换行，
 * 后者让一长串没有空格的地址、token、JSON 长字符串在任意位置断开 ——
 * 只写 `break-words` 时，一串没有空格的字符还是会顶出容器、把整块撑出横向滚动条。
 *
 * <p>切词要带 `keepSpace`：这段是照 token 逐段画出来的，不保留空白就等于把刚排好的
 * 换行和缩进又吃掉一遍。
 *
 * @param large 放大窗里用：字号提一档（11px → 12px），高度交给外层容器
 */
function Body({ text, large }: { text: string; large?: boolean }) {
  return (
    <pre
      className={cn(
        'p-2 font-mono leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]',
        large ? 'text-sm' : 'text-xs',
      )}
    >
      {renderTokens(tokenizeJson(text, true))}
    </pre>
  )
}

/**
 * 一块可能含 JSON 的内容。
 *
 * 顶层可能是一段「混合文本」（例如「本次发出的请求」里前面几行是方法名与请求头、
 * 最后才是 JSON 正文），所以先按括号配对切成若干段，JSON 段上色、其余按纯文本。
 *
 * `原始 / 美化` 切换只在两者确实不同时才出现 —— 签名覆盖请求体，正文里的空白是有意义的，
 * 想看真正发出去的那一份原件时得能切回去。
 *
 * <p><b>放大（2026-10-12 使用者要求）</b>：内嵌窗口高度只有 14rem，长响应体看着太局促。
 * 右上角加一个放大按钮，点开是一个独立弹窗（外壳规格与用例管理弹窗一致：
 * 固定高度 85vh + 内部滚动），字号提一档、可滚动区域大得多。
 * 弹窗里保留「原始 / 美化」开关 —— 放大看的往往就是要逐字核对的那一份。
 *
 * @param title 放大弹窗的标题（如「响应体」「响应头」）；不传时用中性标题
 */
export function JsonBlock({
  text,
  empty,
  className,
  title,
}: {
  text?: string | null
  empty: string
  className?: string
  title?: string
}) {
  const [raw, setRaw] = useState(false)
  const [zoom, setZoom] = useState(false)

  const { blocks, pretty } = useMemo(() => {
    const src = text ?? ''
    const parts = splitJsonBlocks(src)
    // 整段就是一个 JSON（响应体、请求体的常见情形）时给出切换按钮
    const whole = parts.length === 1 && parts[0].kind === 'json' ? parts[0].v : null
    return { blocks: parts, pretty: whole ? prettyJson(whole) : null }
  }, [text])

  const hasContent = (text ?? '').trim().length > 0
  if (!hasContent) {
    return (
      <div className={cn('rounded border bg-background p-2 text-xs text-muted-foreground', className)}>
        {empty}
      </div>
    )
  }

  const showToggle = pretty != null && pretty !== (text ?? '').trim()
  const body =
    raw && pretty != null && text
      ? [{ kind: 'text' as const, v: text.trim() }]
      : blocks.map((b) => (b.kind === 'json' ? { kind: 'json' as const, v: prettyJson(b.v) ?? b.v } : b))

  /** 正文渲染：小窗与放大窗共用，只有高度与字号不同（避免两处各写一遍而漂移）。 */
  const renderBody = (large: boolean) => (
    <div className={large ? 'min-h-0 flex-1 overflow-auto' : 'max-h-56 overflow-auto'}>
      {body.map((b, i) =>
        b.kind === 'json' ? (
          <Body key={i} text={b.v} large={large} />
        ) : (
          <pre
            key={i}
            className={cn(
              'p-2 font-mono leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]',
              large ? 'text-sm' : 'text-xs',
            )}
          >
            {b.v}
          </pre>
        ),
      )}
    </div>
  )

  const toggleButton = (
    <button
      type="button"
      onClick={() => setRaw((v) => !v)}
      className="rounded border bg-background/90 px-2 py-1 text-xs text-muted-foreground hover:text-foreground"
    >
      {raw ? '美化' : '原始'}
    </button>
  )

  return (
    <div className={cn('relative rounded border bg-background', className)}>
      {/* 右上角操作区：原始 / 美化（有需要时）+ 放大（常驻） */}
      <div className="absolute top-1 right-6 z-10 flex items-center gap-1">
        {showToggle && toggleButton}
        <button
          type="button"
          onClick={() => setZoom(true)}
          title="放大查看"
          aria-label="放大查看"
          data-testid="json-block-zoom"
          className="rounded border bg-background/90 p-1.5 text-muted-foreground hover:text-foreground"
        >
          <Maximize2 className="h-5 w-5" />
        </button>
      </div>

      {renderBody(false)}

      <Dialog open={zoom} onOpenChange={setZoom}>
        {/* 外壳与用例管理弹窗同规格：固定高度 + 内部滚动（内容多少都不改窗口大小） */}
        <DialogContent className="flex h-[85vh] max-h-[92vh] flex-col gap-3 overflow-hidden sm:max-w-6xl">
          <DialogHeader className="flex-row items-center gap-2 space-y-0 pr-8">
            <DialogTitle className="mr-auto text-base">
              {title ?? '内容'}（放大查看）
            </DialogTitle>
            {showToggle && toggleButton}
          </DialogHeader>
          <div className="flex min-h-0 flex-1 flex-col rounded border bg-background">
            {renderBody(true)}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
