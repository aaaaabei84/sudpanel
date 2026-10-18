import { cn } from '@/lib/utils'

interface ProgressProps {
  /** 0-100 */
  value: number
  className?: string
  /** 高亮色 class，默认 primary */
  barClassName?: string
  /** 是否显示不确定动画（无总量时用） */
  indeterminate?: boolean
}

/** 轻量进度条（不依赖 radix），0~100 裁剪。 */
export function Progress({ value, className, barClassName, indeterminate }: ProgressProps) {
  const pct = Math.max(0, Math.min(100, value))
  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={indeterminate ? undefined : Math.round(pct)}
      className={cn(
        'relative h-2 w-full overflow-hidden rounded-full bg-muted',
        className,
      )}
    >
      <div
        className={cn(
          'h-full rounded-full bg-primary transition-all duration-300',
          barClassName,
          indeterminate && 'animate-[progress-slide_1.2s_ease-in-out_infinite] w-1/3',
        )}
        style={indeterminate ? undefined : { width: `${pct}%` }}
      />
    </div>
  )
}
