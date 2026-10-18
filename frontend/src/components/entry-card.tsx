import type { ComponentType, ReactNode } from 'react'
import { ArrowRight } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

/**
 * 功能入口小卡片（2026-10-04 抽出，需求 #50）。
 *
 * <p>原来首页的功能跳转卡片是内联写死的，接口测试页又要用同一套视觉（小卡片而不是按钮），
 * 于是抽成这一个组件：首页、接口测试页共用同一份实现，
 * 以后调间距或悬停效果只改这里一处。
 *
 * <p>视觉语言（与首页原样保持一致）：左上角一个方形图标块（`primary/10` 底色 + 主色图标），
 * 右上角箭头**平时隐藏、悬停才浮出** —— 表示「这块可以进去」而不是常驻一个箭头抢注意力。
 *
 * @param icon   左上角图标。收的是组件本身而不是渲染好的节点，好让底色与外框尺寸由这里统一控制
 * @param title  标题
 * @param desc   一行说明（`text-xs`）
 * @param right  标题右侧的附加内容。用于「已选 3」这类必须一眼看到的计数
 * @param footer 说明下方的一行，放状态类信息（如自动签名开没开）
 * @param compact 紧凑变体：图标与文字横向排布、更矮。弹窗顶部那种高度有限的地方用
 */
export function EntryCard({
  icon: Icon,
  title,
  desc,
  right,
  footer,
  onClick,
  className,
  compact = false,
}: {
  icon: ComponentType<{ className?: string }>
  title: string
  desc?: ReactNode
  right?: ReactNode
  footer?: ReactNode
  onClick?: () => void
  className?: string
  compact?: boolean
}) {
  const shell = cn(
    'group transition-all duration-200 border-border/80 hover:border-primary/50 hover:bg-accent/40 hover:shadow-xs focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
    onClick && 'cursor-pointer',
    className,
  )

  const action = onClick ? {
    role: 'button' as const,
    tabIndex: 0,
    'aria-label': title,
    onClick,
    onKeyDown: (event: import('react').KeyboardEvent<HTMLDivElement>) => {
      if (event.target !== event.currentTarget) return
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onClick() }
    },
  } : {}

  if (compact) {
    return (
      <Card className={shell} {...action}>
        <CardContent className="flex items-center gap-2.5 py-2.5">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary/20">
            <Icon className="h-4 w-4" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5 text-sm font-semibold">
              <span className="truncate">{title}</span>
              {right}
            </div>
            {desc != null && (
              <div className="truncate text-xs text-muted-foreground">{desc}</div>
            )}
          </div>
          <ArrowRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground opacity-0 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-100" />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className={shell} {...action}>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary/20">
            <Icon className="h-4.5 w-4.5" />
          </div>
          <ArrowRight className="h-4 w-4 text-muted-foreground opacity-0 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-100" />
        </div>
        <CardTitle className="mt-2 flex items-center gap-2 text-sm font-semibold">
          <span className="truncate">{title}</span>
          {right}
        </CardTitle>
        {desc != null && <CardDescription className="text-xs">{desc}</CardDescription>}
        {footer}
      </CardHeader>
    </Card>
  )
}
