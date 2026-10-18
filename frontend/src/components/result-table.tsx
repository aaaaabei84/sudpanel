import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import type { ResultStatus, RunResult } from '@/types'

const STATUS: Record<ResultStatus, { label: string; cls: string }> = {
  PASS: {
    label: '通过',
    cls: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400',
  },
  FAIL: {
    label: '失败',
    cls: 'border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-400',
  },
  SKIP: {
    label: '跳过',
    cls: 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400',
  },
}

export function StatusBadge({ status }: { status: ResultStatus }) {
  const s = STATUS[status]
  return (
    <Badge variant="outline" className={cn('border font-medium', s.cls)}>
      {s.label}
    </Badge>
  )
}

/** 结果统计（摘要卡片用） */
export function countResults(results: RunResult[]) {
  return results.reduce(
    (acc, r) => {
      acc[r.result] = (acc[r.result] ?? 0) + 1
      return acc
    },
    {} as Partial<Record<ResultStatus, number>>,
  )
}

export function ResultSummary({ results }: { results: RunResult[] }) {
  const c = countResults(results)
  const total = results.length
  if (total === 0) return null
  const items: { key: ResultStatus; label: string; cls: string; count: number }[] = [
    { key: 'PASS', label: '通过', cls: 'text-emerald-600 dark:text-emerald-400', count: c.PASS ?? 0 },
    { key: 'FAIL', label: '失败', cls: 'text-red-600 dark:text-red-400', count: c.FAIL ?? 0 },
    { key: 'SKIP', label: '跳过', cls: 'text-amber-600 dark:text-amber-400', count: c.SKIP ?? 0 },
  ]
  const passRate = total > 0 ? Math.round(((c.PASS ?? 0) / total) * 100) : 0
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      <div className="rounded-lg border bg-card p-3">
        <div className="text-xs text-muted-foreground">总数</div>
        <div className="mt-1 text-2xl font-semibold leading-none">{total}</div>
      </div>
      {items.map((it) => (
        <div key={it.key} className="rounded-lg border bg-card p-3">
          <div className="text-xs text-muted-foreground">{it.label}</div>
          <div className={cn('mt-1 text-2xl font-semibold leading-none', it.cls)}>{it.count}</div>
        </div>
      ))}
      <div className="rounded-lg border bg-card p-3">
        <div className="text-xs text-muted-foreground">通过率</div>
        <div className="mt-1 text-2xl font-semibold leading-none">{passRate}%</div>
      </div>
    </div>
  )
}

interface ResultTableProps {
  results: RunResult[]
  /** 当前正在执行的结果下标（运行中高亮该行） */
  currentIndex?: number
}

export function ResultTable({ results, currentIndex }: ResultTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow className="bg-muted/50">
            <TableHead className="w-10">#</TableHead>
            <TableHead>测试点</TableHead>
            <TableHead className="w-52">state</TableHead>
            <TableHead className="w-20">结果</TableHead>
            <TableHead className="w-60">备注</TableHead>
            <TableHead>命中日志</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {results.map((r, i) => (
            <TableRow
              key={r.id}
              className={cn(
                currentIndex === i && 'bg-primary/5 ring-1 ring-inset ring-primary/20',
              )}
            >
              <TableCell className="text-muted-foreground">{i + 1}</TableCell>
              <TableCell className="font-medium">{r.description || r.stateName}</TableCell>
              <TableCell className="font-mono text-xs text-muted-foreground">{r.stateName}</TableCell>
              <TableCell>
                <StatusBadge status={r.result} />
              </TableCell>
              <TableCell className="text-sm text-muted-foreground">{r.note || '—'}</TableCell>
              <TableCell>
                {r.logSnippet ? (
                  <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-2 font-mono text-xs leading-relaxed">
                    {r.logSnippet}
                  </pre>
                ) : (
                  <span className="text-muted-foreground">—</span>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
