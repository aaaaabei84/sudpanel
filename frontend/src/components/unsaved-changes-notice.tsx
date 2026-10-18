import { useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'

/** 留在原编辑窗口内确认，避免嵌套弹窗争抢Esc与焦点。 */
export function UnsavedChangesNotice({ onContinue, onDiscard, testId }: {
  onContinue: () => void
  onDiscard: () => void
  testId: string
}) {
  const notice = useRef<HTMLDivElement>(null)
  useEffect(() => { notice.current?.focus() }, [])
  return <div ref={notice} tabIndex={-1} role="alert" data-testid={testId} className="shrink-0 space-y-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3 outline-none focus-visible:ring-2 focus-visible:ring-amber-500/40">
    <p className="text-sm font-medium">有未保存的修改</p>
    <p className="text-xs text-muted-foreground">继续编辑会保留当前内容；放弃修改会关闭编辑窗口。</p>
    <div className="flex flex-wrap gap-2">
      <Button type="button" size="sm" variant="outline" onClick={onContinue}>继续编辑</Button>
      <Button type="button" size="sm" variant="destructive" onClick={onDiscard}>放弃修改</Button>
    </div>
  </div>
}
