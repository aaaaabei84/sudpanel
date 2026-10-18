import { useEffect, useMemo, useRef, useState } from 'react'
import { filterLogIndexes } from '@/lib/log-line'
import type { AdvancedLogFilter } from '@/lib/advanced-log-filter'
import type { LogBuffer } from '@/lib/log-buffer'

/** 用户正则只在可终止的 Worker 执行；主线程继续采集、滚动和停止。 */
export function useLogFilter(buffer: LogBuffer, enabled: boolean, config: AdvancedLogFilter) {
  const [result, setResult] = useState<number[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [retry, setRetry] = useState(0)
  const worker = useRef<Worker | null>(null), sequence = useRef(0)
  const sent = useRef<{ total: number; last: string; first: string; base: number } | null>(null)
  const accepted = useRef<AdvancedLogFilter | null>(null), rejected = useRef('')
  const lastConfig = useRef('')
  const deadline = useRef<number | null>(null), failure = useRef<() => void>(() => {})
  const stopDeadline = () => { if (deadline.current != null) window.clearTimeout(deadline.current); deadline.current = null }
  const fingerprint = JSON.stringify(config)
  const ordinary = useMemo(() => enabled ? [] : filterLogIndexes(buffer.lines, config.keyword, config.levels), [enabled, buffer.lines, config.keyword, config.levels])
  useEffect(() => () => { stopDeadline(); worker.current?.terminate(); worker.current = null; ++sequence.current }, [])
  useEffect(() => {
    if (!enabled) { stopDeadline(); worker.current?.terminate(); worker.current = null; sent.current = null; setBusy(false); return }
    if (lastConfig.current !== fingerprint) {
      stopDeadline(); worker.current?.terminate(); worker.current = null; sent.current = null
      lastConfig.current = fingerprint
    }
    if (rejected.current !== fingerprint) setError('')
    const desired = JSON.parse(fingerprint) as AdvancedLogFilter
    const effective = rejected.current === fingerprint ? accepted.current ?? { ...desired, regex: '', includes: [], excludes: [], tag: '', pid: '' } : desired
    if (!worker.current) { worker.current = new Worker(new URL('../workers/log-filter.worker.ts', import.meta.url), { type: 'module' }); sent.current = null }
    const target = worker.current, id = ++sequence.current, base = buffer.total - buffer.lines.length
    const old = sent.current
    const reset = !old || old.total > buffer.total || old.total < base || old.base > base ||
      (old.total > base && buffer.lines[old.total - base - 1] !== old.last) ||
      (old.base === base && buffer.lines[0] !== old.first)
    if (reset && old) setResult([])
    const lines = reset ? buffer.lines : buffer.lines.slice(Math.max(0, old!.total - base))
    sent.current = { total: buffer.total, base, first: buffer.lines[0] ?? '', last: buffer.lines.at(-1) ?? '' }
    setBusy(true)
    const fail = (message: string) => {
      if (id !== sequence.current) return
      stopDeadline(); target.terminate(); worker.current = null; sent.current = null
      setBusy(false); setError(message + '；保留最近成功的过滤规则。')
      const firstFailure = rejected.current !== fingerprint
      rejected.current = fingerprint
      if (firstFailure) setRetry(value => value + 1)
    }
    target.onmessage = (event: MessageEvent<{ id: number; indexes?: number[]; error?: string }>) => {
      if (id !== sequence.current || event.data.id !== id) return
      if (event.data.error) { fail(event.data.error); return }
      stopDeadline(); setBusy(false); setResult(event.data.indexes ?? []); accepted.current = effective
    }
    target.onerror = () => fail('过滤执行失败')
    failure.current = () => fail('过滤超过 2 秒，已终止计算')
    if (deadline.current == null) deadline.current = window.setTimeout(() => failure.current(), 2000)
    target.postMessage({ id, base, reset, lines, config: effective })
    return () => { ++sequence.current }
  }, [enabled, fingerprint, buffer, retry])
  const indexes = useMemo(() => {
    const base = buffer.total - buffer.lines.length
    return enabled ? result.filter(i => i >= base && i < buffer.total).map(i => i - base) : ordinary
  }, [buffer.total, buffer.lines.length, enabled, result, ordinary])
  return { indexes, error, busy, effective: enabled ? accepted.current : config }
}
