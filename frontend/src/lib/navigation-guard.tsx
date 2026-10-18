import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { PageVisibilityContext } from './page-visibility'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
interface Guard { label: string; dirty: boolean; locked?: boolean; visible: boolean; discard: () => void }
interface Context { register: (read: () => Guard) => () => void; request: (action: () => void, cancel?: () => void) => void }
const GuardContext = createContext<Context | null>(null)
export function NavigationGuardProvider({ children }: { children: ReactNode }) {
  const guards = useRef(new Set<() => Guard>())
  const [pending, setPending] = useState<{ action: () => void; cancel?: () => void; guards: Guard[] } | null>(null)
  const register = useCallback((read: () => Guard) => { guards.current.add(read); return () => { guards.current.delete(read) } }, [])
  const request = useCallback((action: () => void, cancel?: () => void) => {
    const active = [...guards.current].map(read => read()).filter(guard => guard.visible)
    if (active.some(guard => guard.locked)) { toast.info('正在保存或执行，请等待当前操作完成。'); cancel?.(); return }
    const dirty = active.filter(guard => guard.dirty)
    if (dirty.length) setPending({ action, cancel, guards: dirty })
    else action()
  }, [])
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => { if ([...guards.current].some(read => read().dirty)) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [])
  const value = useMemo(() => ({ register, request }), [register, request])
  return <GuardContext.Provider value={value}>{children}<Dialog open={pending != null} onOpenChange={open => { if (!open) { pending?.cancel?.(); setPending(null) } }}><DialogContent className="sm:max-w-lg"><DialogHeader><DialogTitle>保留未保存的编辑？</DialogTitle><DialogDescription>{pending?.guards.map(guard => guard.label).join('、')} 有未保存修改。保留后切换页面，再回来可继续编辑；刷新或关闭应用前请保存。</DialogDescription></DialogHeader><DialogFooter><Button variant="outline" onClick={() => { pending?.cancel?.(); setPending(null) }}>继续编辑</Button><Button variant="outline" onClick={() => { const next = pending; setPending(null); next?.guards.forEach(guard => guard.discard()); next?.action() }}>放弃修改并离开</Button><Button onClick={() => { const next = pending; setPending(null); next?.action() }}>保留草稿并离开</Button></DialogFooter></DialogContent></Dialog></GuardContext.Provider>
}
export function useDirtyGuard(input: Omit<Guard, 'visible'>): void {
  const context = useContext(GuardContext), visible = useContext(PageVisibilityContext)
  const current = useRef<Guard>({ ...input, visible }); current.current = { ...input, visible }
  useEffect(() => context?.register(() => current.current), [context])
}
export function useGuardedNavigation(): (action: () => void, cancel?: () => void) => void {
  return useContext(GuardContext)?.request ?? (action => action())
}
