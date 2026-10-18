import { toast } from 'sonner'
import { Copy } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { VariableDiagnostic } from '@/lib/http-continuation'

const sourceLabels: Record<string, string> = { BASE: '基址变量', HEADER: '请求头变量', BODY: '请求体变量', SYSTEM: '系统变量', RUN: '本轮提取', DATA: '数据行', LEGACY_SIGN: '旧签名变量', LITERAL: '字面文本' }
const scopeLabels: Record<string, string> = { URL: '地址/查询', HEADER: '请求头/鉴权', BODY: '正文/表单' }

export function VariableDiagnostics({ issues, diagnostics, className = '' }: {
  issues?: VariableDiagnostic[]; diagnostics?: VariableDiagnostic[]; className?: string
}) {
  const values = issues ?? diagnostics ?? []
  if (!values.length) return null
  const missing = values.filter(i => i.status === 'MISSING')
  const copy = (name: string) => void navigator.clipboard.writeText('{{' + name + '}}').then(() => toast.success('已复制候选占位符')).catch(() => toast.error('复制失败'))
  return <details open={missing.length > 0} className={`rounded border bg-muted/20 p-3 text-xs ${className}`}>
    <summary className="cursor-pointer font-medium">变量检查 · {missing.length ? `${missing.length} 处未解析` : `${values.length} 处已定位`}</summary>
    <p className="mt-2 text-muted-foreground">仅列名称与来源。位置对应原始输入；复制候选后请在原字段修改并重新预览。</p>
    <div className="mt-2 max-h-64 space-y-2 overflow-auto">
      {values.map((issue, index) => <div key={`${issue.location}-${issue.offset}-${index}`} className="rounded border bg-background p-2" data-testid="variable-diagnostic">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <code className={issue.status === 'MISSING' ? 'text-amber-700 dark:text-amber-400' : 'font-medium'}>{'{{' + issue.name + '}}'}</code>
          <span className="text-muted-foreground">{scopeLabels[issue.scope] ?? issue.scope} · {issue.location} · 第 {issue.line} 行，第 {issue.column} 列</span>
          <span>{issue.status === 'MISSING' ? '未解析' : issue.status === 'LITERAL' ? '按字面发送' : `来源：${sourceLabels[issue.source ?? ''] ?? issue.source ?? '环境'}`}</span>
        </div>
        {!!issue.shadowedSources?.length && <p className="mt-1 text-muted-foreground">同名来源已被当前优先级覆盖：{issue.shadowedSources.map(s => sourceLabels[s] ?? s).join('、')}。</p>}
        {!!issue.candidates?.length && <div className="mt-1 flex flex-wrap items-center gap-1"><span className="text-muted-foreground">相近名称：</span>{issue.candidates.map(name => <Button key={name} type="button" size="sm" variant="ghost" className="h-6 px-2 font-mono text-xs" aria-label={`复制候选 ${name}`} onClick={() => copy(name)}><Copy className="size-3" />{name}</Button>)}</div>}
      </div>)}
    </div>
    {values.length >= 500 && <p className="mt-2 text-muted-foreground">定位列表最多显示 500 处；剩余未解析名称仍以预览提示为准。</p>}
  </details>
}
