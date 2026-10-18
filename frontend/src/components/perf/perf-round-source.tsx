import { useState } from 'react'
import { toast } from 'sonner'
import { Copy, Send } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { roundLabel, type PerfEvidenceLoop } from '@/lib/perf-continuation'
import type { RoundQueryContext } from '@/lib/apitest-workspace'

export function PerfRoundSource({ loop, gameName, onQueryRound }: { loop: PerfEvidenceLoop; gameName: string; onQueryRound?: (context: RoundQueryContext) => void }) {
  const [open, setOpen] = useState(false), evidence = loop.roundIdEvidence
  const reliable = evidence?.state === 'UNIQUE' && !!loop.gameRoundId
  const canBring = !!loop.gameRoundId && evidence?.state !== 'CONFLICT' && evidence?.state !== 'INCOMPLETE'
  return <div className="min-w-40 space-y-1 text-xs" data-testid={`perf-round-source-${loop.id}`}>
    {loop.gameRoundId && <code className="block max-w-64 break-all font-mono">{loop.gameRoundId}</code>}
    <Button size="sm" variant="ghost" className="h-auto max-w-64 whitespace-normal px-1 text-left text-xs" onClick={event => { event.stopPropagation(); setOpen(true) }} data-testid={`perf-round-details-${loop.id}`}><Badge variant={evidence?.state === 'CONFLICT' ? 'destructive' : 'outline'}>{roundLabel(evidence?.state)}</Badge></Button>
    {evidence?.missingReason && <p className="max-w-64 text-xs text-muted-foreground">{evidence.missingReason}</p>}
    <span className="block text-xs text-muted-foreground">{loop.apiEnvName ? `来源环境：${loop.apiEnvName}` : '来源环境未知'}</span>
    {loop.gameRoundId && <div className="flex flex-wrap items-center gap-1"><Button type="button" size="sm" variant="ghost" className="h-6 px-1 text-xs" aria-label={`复制第 ${loop.loopNo} 圈对局 ID`} data-testid={`perf-copy-round-${loop.id}`} onClick={event => { event.stopPropagation(); void navigator.clipboard.writeText(loop.gameRoundId!).then(() => toast.success('已复制对局 ID')).catch(() => toast.error('复制失败')) }}><Copy className="size-3" />复制</Button>
      {onQueryRound && <Button type="button" size="sm" variant="outline" className="h-6 px-1 text-xs" disabled={!canBring} title={reliable ? '把唯一命中的ID和来源带入接口测试' : '旧记录来源未验证，请手工核对后发送'} data-testid={`perf-query-round-${loop.id}`} onClick={event => { event.stopPropagation(); onQueryRound({ roundId: loop.gameRoundId!, runId: loop.runId, loopId: loop.id, loopNo: loop.loopNo, gameId: loop.gameId, gameName, envId: loop.apiEnvId ?? undefined, envName: loop.apiEnvName ?? undefined, envSource: reliable && loop.apiEnvId != null ? '本圈开局接口环境' : '旧记录ID来源未验证' }) }}><Send className="size-3" />带入接口查询</Button>}</div>}
    <Dialog open={open} onOpenChange={setOpen}><DialogContent className="flex max-h-[85vh] flex-col overflow-hidden sm:max-w-2xl" data-testid="perf-round-source-dialog"><DialogHeader><DialogTitle>第 {loop.loopNo} 圈 · 对局 ID 来源</DialogTitle></DialogHeader><div className="min-h-0 space-y-3 overflow-auto text-xs"><Badge variant={evidence?.state === 'CONFLICT' ? 'destructive' : 'outline'}>{roundLabel(evidence?.state)}</Badge><p>{evidence?.missingReason || (reliable ? '本圈增量日志只有一个不同ID，日志边界完整。' : '没有足够来源证据，不能把某个候选认定为本圈。')}</p><dl className="grid grid-cols-2 gap-2"><dt>来源</dt><dd>{evidence?.source ?? '未记录'}</dd><dt>命中次数／读取次数</dt><dd>{evidence?.matchCount ?? '未知'} / {evidence?.readCount ?? '未知'}</dd><dt>首次观察</dt><dd>{evidence?.firstMatchedAt ?? '未记录'}</dd><dt>最后观察</dt><dd>{evidence?.lastMatchedAt ?? '未记录'}</dd><dt>设备日志时间</dt><dd>{evidence?.firstLogTimestamp ?? '未记录'} → {evidence?.lastLogTimestamp ?? '未记录'}</dd></dl><p className="text-muted-foreground">{evidence?.timeBasis || '时间来源未知，不能按ID前缀推断归属。'}</p>{!!evidence?.candidateIds?.length && <section><h3 className="mb-1 font-medium">原始字符串候选</h3>{evidence.candidateIds.map(id => <code key={id} className="my-1 block break-all rounded-md bg-muted p-2">{id}</code>)}</section>}{evidence?.state === 'CONFLICT' && <p className="text-destructive">存在多个不同 ID，系统未选择最后一条作为确定结果；自动结算核验已阻止。</p>}</div></DialogContent></Dialog>
  </div>
}
