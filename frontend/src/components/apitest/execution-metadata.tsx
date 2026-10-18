import { ResponseFile } from './response-file'
import { JsonBlock } from '@/components/json-block'
import { readMetadata } from '@/lib/apitest-workspace'

export function ExecutionMetadata({ text }: { text?: string | null }) {
  const metadata = readMetadata(text)
  if (!metadata) return <p className="text-xs text-muted-foreground">旧记录未保存执行环境与提取快照。</p>
  const warnings = Array.isArray(metadata.warnings) ? metadata.warnings.map(String) : []
  const truncated = metadata.truncated
  const hasTruncation = metadata.requestBodyTruncated === true || metadata.responseBodyTruncated === true || truncated === true || (!!truncated && typeof truncated === 'object' && Object.values(truncated).some(Boolean))
  const cancelled = metadata.cancelled === true || metadata.outcome === 'CANCELLED'
  const phase = metadata.phase == null ? null : String(metadata.phase)
  return <div className="space-y-1 text-xs">
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-muted-foreground">
      <span>执行环境：<span className="font-medium text-foreground">{String(metadata.envName ?? '未记录')}{metadata.envId != null ? ` #${metadata.envId}` : ''}</span></span>
      {metadata.capturedAt != null && <span>配置固定时刻：{String(metadata.capturedAt)}</span>}
      {metadata.planId != null && <span>冻结计划 #{String(metadata.planId)}</span>}
      {metadata.sourceBatchId != null && <span>来源批次 #{String(metadata.sourceBatchId)}</span>}
      {metadata.dataRowIndex != null && <span>原数据第 {String(metadata.dataRowIndex)} 行{metadata.dataIteration != null ? ` · 第 ${String(metadata.dataIteration)} 轮` : ''}</span>}
      {phase && <span>{phase.startsWith('CLEANUP') ? '清理步骤' : '普通步骤'}{phase.endsWith('WAIT') || phase === 'WAIT' ? ' · 等待中' : ''}{metadata.stepKey != null ? ` · ${String(metadata.stepKey)}` : ''}</span>}
      {metadata.attempt != null && <span>第 {String(metadata.attempt)} 次尝试{metadata.maxAttempts != null ? ` / 最多 ${String(metadata.maxAttempts)} 次` : ''}{metadata.pollFinal === true ? ' · 最终结果' : metadata.pollFinal === false ? ' · 继续轮询' : ''}</span>}
    </div>
    {(metadata.actualHttpVersion != null || metadata.requestedHttpVersion != null) && <p>HTTP偏好 {String(metadata.requestedHttpVersion ?? '旧记录未记')} · 实际 {String(metadata.actualHttpVersion ?? '未收到响应')} · 最终地址 {String(metadata.finalUrl ?? '')}</p>}
    {Array.isArray(metadata.redirects) && metadata.redirects.length > 0 && <details><summary className="cursor-pointer">重定向链 · {metadata.redirects.length} 跳</summary><table className="w-full text-left"><thead><tr><th>状态</th><th>原方法 → 下一方法</th><th>地址 → Location</th></tr></thead><tbody>{metadata.redirects.map((entry,i) => { const hop = entry as Record<string,unknown>; return <tr key={i}><td>{String(hop.status)}</td><td>{String(hop.method)} → {String(hop.nextMethod ?? '')}</td><td className="break-all">{String(hop.url)} → {String(hop.location)}</td></tr> })}</tbody></table></details>}
    {metadata.blockedRedirect === true && <p role="status" className="text-amber-700">当前跳转被安全策略阻断，结果保留原始3xx；请核对目标后创建独立请求。</p>}
    {cancelled && <p role="status" className="text-amber-700 dark:text-amber-400">{metadata.serverOutcomeUnknown === false ? '已取消，尚未开始网络发送。' : '已取消等待；请求可能已到达服务器，取消不能证明业务未执行。请先核对实际状态再决定是否重试。'}</p>}
    {metadata.event === 'WAIT_CANCELLED' && <p role="status" className="text-muted-foreground">轮询等待已取消，没有发送额外请求。</p>}
    {metadata.event === 'POLL_TIMEOUT' && <p role="status" className="text-amber-700 dark:text-amber-400">轮询总时限已到，没有发送额外请求。</p>}
    {warnings.map((warning, i) => <p role="status" key={i} className="text-amber-700 dark:text-amber-400">{warning}</p>)}
    <ResponseFile metadata={metadata} />
    {hasTruncation && <p role="status" className="text-amber-700 dark:text-amber-400">展示快照已截断；若有「查看本机原字节附件」入口可取完整内容。超限前缀始终不完整，不能用于完整断言。</p>}
    <details><summary className="cursor-pointer text-muted-foreground">提取结果与执行快照</summary><JsonBlock text={text} empty="无快照" title="执行快照" /></details>
  </div>
}
