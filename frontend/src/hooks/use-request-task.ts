import { useEffect, useRef, useState } from 'react'
import { req } from '@/lib/api'
import { RequestError } from '@/lib/diagnostics'
import type { ApiCase, ApiExec } from '@/types'
import type { RoundQueryContext } from '@/lib/apitest-workspace'
export interface RequestTask { requestId: string; state: 'QUEUED' | 'RUNNING' | 'CANCEL_REQUESTED' | 'CANCELLED' | 'DONE' | 'FAILED' | 'UNKNOWN'; exec: ApiExec | null; cancelRequested: boolean; serverOutcomeUnknown: boolean; error?: string | null }
export const taskEnded = (task: RequestTask | null) => !!task && ['DONE', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(task.state)
/** POST、轮询与取消响应可能乱序；终态及已确认取消不能退回运行态。 */
export function mergeRequestTask(current: RequestTask | null, incoming: RequestTask): RequestTask {
  if (!current || current.requestId !== incoming.requestId) return incoming
  if (taskEnded(current)) return current
  if (taskEnded(incoming)) return incoming
  if (current.cancelRequested && !incoming.cancelRequested) return { ...incoming, state: 'CANCEL_REQUESTED', cancelRequested: true, serverOutcomeUnknown: current.serverOutcomeUnknown || incoming.serverOutcomeUnknown }
  if (current.state === 'RUNNING' && incoming.state === 'QUEUED') return current
  return incoming
}
export function useRequestTask() {
  const [task, setTask] = useState<RequestTask | null>(null), [error, setError] = useState(''), [cancelling, setCancelling] = useState(false)
  const current = useRef<string | null>(null), mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const send = async (spec: Omit<ApiCase, 'id'>, envId: number, sourceContext: RoundQueryContext | null) => {
    const requestId = crypto.randomUUID(); current.current = requestId; setError('')
    setTask({ requestId, state: 'QUEUED', exec: null, cancelRequested: false, serverOutcomeUnknown: false })
    try {
      const value = await req<RequestTask>('/api/apitest/requests', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, spec, envId, sourceContext }) })
      if (mounted.current && current.current === requestId) setTask(previous => mergeRequestTask(previous, value))
    } catch (problem) {
      if (!mounted.current || current.current !== requestId) return
      const message = problem instanceof Error ? problem.message : String(problem)
      if (problem instanceof RequestError && problem.status != null && problem.status >= 400 && problem.status < 500) {
        setTask(previous => mergeRequestTask(previous, { requestId, state: 'FAILED', exec: null, cancelRequested: false, serverOutcomeUnknown: false, error: message }))
      }
      setError(message + '；按请求编号查询状态，不会自动重发。')
    }
  }
  const ended = taskEnded(task), id = task?.requestId
  useEffect(() => {
    if (!id || ended) return
    let active = true, timer = 0, failures = 0
    const poll = async () => {
      try {
        const result = await req<RequestTask>(`/api/apitest/requests/${id}`)
        if (!active || current.current !== id) return
        setTask(previous => mergeRequestTask(previous, result)); setError(''); failures = 0
        if (taskEnded(result)) return
      } catch (problem) {
        if (!active) return
        ++failures; setError(`状态读取失败，继续查询同一请求：${problem instanceof Error ? problem.message : String(problem)}`)
      }
      if (active) timer = window.setTimeout(() => void poll(), Math.min(10000, failures ? 1000 * 2 ** Math.min(failures, 3) : 300))
    }
    timer = window.setTimeout(() => void poll(), 300)
    return () => { active = false; window.clearTimeout(timer) }
  }, [id, ended])
  const cancel = async () => {
    if (!id || ended || cancelling) return
    setCancelling(true)
    try { const result = await req<RequestTask>(`/api/apitest/requests/${id}/cancel`, { method: 'POST' }); if (mounted.current && current.current === id) setTask(previous => mergeRequestTask(previous, result)) }
    catch (problem) { if (mounted.current) setError(`取消结果尚未确认：${problem instanceof Error ? problem.message : String(problem)}`) }
    finally { if (mounted.current) setCancelling(false) }
  }
  const stopTracking = () => setTask(previous => previous && !taskEnded(previous) ? { ...previous, state: 'UNKNOWN', serverOutcomeUnknown: true, error: '已结束本机状态查询，未确认服务器结果。请按请求编号核对历史后再决定是否重发。' } : previous)
  return { task, error, cancelling, send, cancel, stopTracking }
}
