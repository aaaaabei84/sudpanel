import { useEffect, useRef, useState } from 'react'
import { req } from '@/lib/api'
import type { ApiEnv } from '@/types'
export function EnvironmentStrict({ environment, disabled, onSaved }: { environment: ApiEnv; disabled: boolean; onSaved: () => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const sequence = useRef(0)
  useEffect(() => { ++sequence.current; setBusy(false); setError(''); return () => { ++sequence.current } }, [environment.id])
  const save = async (value: string) => {
    const own = ++sequence.current; setBusy(true); setError('')
    try { await req(`/api/apitest/envs/${environment.id}/strict`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ strictVariables: value === '' ? null : value === 'true' }) }); if (own === sequence.current) onSaved() }
    catch (problem) { if (own === sequence.current) setError(problem instanceof Error ? problem.message : String(problem)) }
    finally { if (own === sequence.current) setBusy(false) }
  }
  return <div className="shrink-0 space-y-1 rounded border p-2 text-xs"><label className="flex flex-wrap items-center gap-2">未解析变量的环境默认行为<select aria-label="环境严格变量" className="h-8 rounded border bg-background px-2" disabled={disabled || busy} value={environment.strictVariables == null ? '' : String(environment.strictVariables)} onChange={event => void save(event.target.value)}><option value="">兼容现有行为</option><option value="true">严格：缺失变量阻止发送</option><option value="false">宽松：保留占位符并警告</option></select>{busy && <span role="status">保存中…</span>}</label><p className="text-muted-foreground">此策略立即保存；单个请求可覆盖环境默认值。run、data 和系统变量缺失始终阻止发送。</p>{error && <p role="alert" className="text-amber-700 dark:text-amber-400">{error}；原策略保留，可重新选择后重试。</p>}</div>
}
