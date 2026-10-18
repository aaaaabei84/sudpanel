import { ArrowDown, ArrowUp, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ApiCase, ApiCaseGroup, ApiEnv } from '@/types'

export function RunPlan({ ids, cases, groups, envs, disabled, onChange }: {
  ids: number[]; cases: ApiCase[]; groups: ApiCaseGroup[]; envs: ApiEnv[]
  disabled: boolean; onChange: (ids: number[]) => void
}) {
  const move = (index: number, delta: number) => {
    const next = [...ids]; const target = index + delta
    if (target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
    onChange(next)
  }
  return <div className="space-y-2 rounded-md border bg-muted/20 px-2.5 py-2" data-testid="api-run-plan">
    <div className="flex items-center gap-2 text-xs"><span className="font-medium">执行顺序</span><span className="font-mono text-muted-foreground">{ids.length} 条</span><span className="text-muted-foreground">从上到下；开始运行时固定环境与配置</span>{ids.length > 0 && <Button type="button" size="sm" variant="ghost" className="ml-auto h-6 text-xs" disabled={disabled} onClick={() => onChange([])}>清空</Button>}</div>
    {ids.length === 0 && <p className="text-xs text-muted-foreground">在用例管理中勾选请求，随后调整执行顺序。</p>}
    <div className="max-h-64 space-y-1 overflow-y-auto">
      {ids.map((id, i) => {
        const c = cases.find(c => c.id === id)
        const group = groups.find(g => g.id === c?.groupId)
        const env = envs.find(e => e.id === group?.envId) ?? envs.find(e => e.isDefault)
        return <div key={id} className="flex items-center gap-2 rounded border bg-background px-2 py-1 text-xs"><span className="w-6 font-mono text-muted-foreground">{i + 1}</span><span className="min-w-0 flex-1 truncate" title={c?.name}>{c?.name ?? `用例 #${id} 已不存在`}{c && !c.enabled ? '（未启用）' : ''}</span><span className="max-w-32 truncate text-muted-foreground" title={env?.name}>{env?.name ?? '默认环境'} · {group?.envId != null ? '分组绑定' : '默认兜底'}</span><Button type="button" size="icon" className="size-6" variant="ghost" disabled={disabled || i === 0} aria-label={`用例 ${i + 1} 上移`} onClick={() => move(i, -1)}><ArrowUp className="size-3" /></Button><Button type="button" size="icon" className="size-6" variant="ghost" disabled={disabled || i === ids.length - 1} aria-label={`用例 ${i + 1} 下移`} onClick={() => move(i, 1)}><ArrowDown className="size-3" /></Button><Button type="button" size="icon" className="size-6" variant="ghost" disabled={disabled} aria-label={`取消用例 ${i + 1}`} onClick={() => onChange(ids.filter(value => value !== id))}><X className="size-3" /></Button></div>
      })}
    </div>
  </div>
}
