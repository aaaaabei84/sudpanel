import { useCallback, useEffect, useRef, useState } from 'react'
import { req } from '@/lib/api'
import { RequestError } from '@/lib/diagnostics'
import { emptyWorkspace, readWorkspace, type RequestWorkspace } from '@/lib/request-workspace'
interface StoredWorkspace { id: string; revision: number; content: string | null; updatedAt: string | null }
const STORAGE_KEY = 'sudpanel-http-workspace-v1'
export function useRequestWorkspace(open: boolean) {
  const [value, setValue] = useState<RequestWorkspace>(emptyWorkspace), [ready, setReady] = useState(false)
  const [error, setError] = useState(''), [saving, setSaving] = useState(false), [savedAt, setSavedAt] = useState<string | null>(null)
  const identity = useRef(''), revision = useRef(0), initialized = useRef(false), mounted = useRef(true), inFlight = useRef(false)
  const latest = useRef(value); latest.current = value
  const persisted = useRef(''), savePending = useRef(false), timer = useRef<number | null>(null)
  const load = useCallback(async () => {
    setError('')
    try {
      if (!identity.current) {
        const stored = localStorage.getItem(STORAGE_KEY)
        identity.current = stored && /^[0-9a-f-]{36}$/.test(stored) ? stored : crypto.randomUUID()
        localStorage.setItem(STORAGE_KEY, identity.current)
      }
      const result = await req<StoredWorkspace>(`/api/apitest/workspaces/${identity.current}`)
      if (!mounted.current) return
      const parsed = readWorkspace(result.content)
      revision.current = result.revision; persisted.current = JSON.stringify(parsed)
      setValue(parsed); setSavedAt(result.updatedAt); setReady(true)
    } catch (problem) { if (mounted.current) setError(problem instanceof Error ? problem.message : String(problem)) }
  }, [])
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; if (timer.current != null) window.clearTimeout(timer.current) } }, [])
  useEffect(() => { if (open && !initialized.current) { initialized.current = true; void load() } }, [open, load])
  const flush = useCallback(async () => {
    if (inFlight.current) { savePending.current = true; return }
    const content = JSON.stringify(latest.current)
    if (content === persisted.current) return
    inFlight.current = true; setSaving(true); setError('')
    try {
      let result: StoredWorkspace
      const save = (id: string, expectedRevision: number) => req<StoredWorkspace>(`/api/apitest/workspaces/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedRevision, content }) })
      try { result = await save(identity.current, revision.current) }
      catch (problem) {
        if (!(problem instanceof RequestError) || problem.status !== 409) throw problem
        // 分叉保存和本地身份切换均成功后才提交，失败时重试仍从原身份发起。
        const forkId = crypto.randomUUID()
        result = await save(forkId, 0)
        localStorage.setItem(STORAGE_KEY, forkId); identity.current = forkId
      }
      revision.current = result.revision; persisted.current = content
      if (mounted.current) setSavedAt(result.updatedAt)
    } catch (problem) { if (mounted.current) setError(`草稿尚未保存：${problem instanceof Error ? problem.message : String(problem)}`) }
    finally {
      inFlight.current = false
      if (mounted.current) { setSaving(false); if (savePending.current) { savePending.current = false; void flush() } }
    }
  }, [])
  useEffect(() => {
    if (!ready) return
    if (timer.current != null) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => void flush(), 500)
    return () => { if (timer.current != null) window.clearTimeout(timer.current) }
  }, [value, ready, flush])
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {
      if (ready && JSON.stringify(latest.current) !== persisted.current) { event.preventDefault(); event.returnValue = '' }
    }
    window.addEventListener('beforeunload', protect)
    return () => window.removeEventListener('beforeunload', protect)
  }, [ready])
  return { value, setValue, ready, error, saving, savedAt, load, flush, pending: ready && JSON.stringify(value) !== persisted.current }
}
