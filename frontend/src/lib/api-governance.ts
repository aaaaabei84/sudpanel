import { useEffect, useRef, useState } from 'react'
import { req } from '@/lib/api'
export type ApiAssertionRule = { path: string; op: string; expect: string }

export type Counts = { environments: number; groups: number; cases: number; scenarios: number }
export type ConfigPreview = { valid: boolean; errors: string[]; warnings: string[]; counts: Counts }
export type ConfigImported = { counts: Counts; environmentIds: Record<string, number>; groupIds: Record<string, number>; caseIds: Record<string, number>; scenarioIds: Record<string, number>; warnings: string[] }
export type Comparison = { complete: boolean; warnings: string[]; differences: { section: string; path: string; kind: string; left: string | null; right: string | null }[] }
export type Baseline = { id: number; name: string; sourceExecId: number; ignorePaths: string[]; createdAt: string }
export type Generated = { expectStatus: string | null; expectCode: string | null; rules: ApiAssertionRule[]; warnings: string[] }
export type ReportPolicy = { sensitiveJsonPaths: string[] }
export const jsonRequest = (method: string, body: unknown): RequestInit => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const pointers = (text: string) => text.split('\n').map(s => s.trim()).filter(Boolean)
export const message = (error: unknown) => error instanceof Error ? error.message : String(error)

/** ref立即占有，防同帧重复提交；关闭/卸载后不回写旧结果，不自动重试写操作。 */
export function useOperation() {
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
  const owner = useRef(false), generation = useRef(0), controller = useRef<AbortController | null>(null), mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; ++generation.current; controller.current?.abort(); controller.current = null; owner.current = false } }, [])
  const invalidate = () => { ++generation.current; controller.current?.abort(); controller.current = null; owner.current = false; if (mounted.current) { setBusy(false); setError(null) } }
  const run = async <T,>(action: (signal: AbortSignal) => Promise<T>, done: (value: T) => void | Promise<void>) => {
    if (owner.current) return
    owner.current = true; const current = ++generation.current; controller.current = new AbortController(); setBusy(true); setError(null)
    try { const result = await action(controller.current.signal); if (mounted.current && generation.current === current) await done(result) }
    catch (e) { if (mounted.current && generation.current === current) setError(message(e)) }
    finally { if (mounted.current && generation.current === current) { owner.current = false; setBusy(false); controller.current = null } }
  }
  return { busy, error, setError, run, invalidate }
}
export const governance = {
  preview: (content: string, signal: AbortSignal) => req<ConfigPreview>('/api/apitest/config/preview', jsonRequest('POST', { content }), { signal }),
  commit: (content: string, signal: AbortSignal) => req<ConfigImported>('/api/apitest/config/commit', jsonRequest('POST', { content }), { signal }),
  baselines: (signal: AbortSignal) => req<Baseline[]>('/api/apitest/baselines', undefined, { signal }),
  create: (name: string, execId: number, ignorePaths: string[], signal: AbortSignal) => req<Baseline>('/api/apitest/baselines', jsonRequest('POST', { name, execId, ignorePaths }), { signal }),
  remove: (id: number, signal: AbortSignal) => req<void>(`/api/apitest/baselines/${id}`, { method: 'DELETE' }, { signal }),
  compare: (id: number, execId: number, signal: AbortSignal) => req<Comparison>(`/api/apitest/baselines/${id}/compare`, jsonRequest('POST', { execId }), { signal }),
  assertions: (id: number, signal: AbortSignal) => req<Generated>(`/api/apitest/baselines/${id}/assertions`, undefined, { signal }),
  policy: (signal: AbortSignal) => req<ReportPolicy>('/api/apitest/report-policy', undefined, { signal }),
  savePolicy: (policy: ReportPolicy, signal: AbortSignal) => req<ReportPolicy>('/api/apitest/report-policy', jsonRequest('POST', policy), { signal }),
}
