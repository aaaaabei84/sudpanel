import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { api } from '@/lib/api'
import type { Device } from '@/types'

interface DeviceCtx {
  devices: Device[]
  selected: string | null
  setSelected: (s: string | null) => void
  refresh: () => Promise<void>
  refreshing: boolean
  error: string | null
  lastUpdated: number | null
}

const DeviceContext = createContext<DeviceCtx>({
  devices: [],
  selected: null,
  setSelected: () => {},
  refresh: async () => {},
  refreshing: false,
  error: null,
  lastUpdated: null,
})

export function DeviceProvider({ children }: { children: ReactNode }) {
  const [devices, setDevices] = useState<Device[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const refreshId = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<number | null>(null)

  const refresh = useCallback(async () => {
    const id = ++refreshId.current
    // 手动刷新代表新的检查意图；取消旧读取并用序号拒收不支持取消的迟到结果。
    controller.current?.abort()
    const request = new AbortController()
    controller.current = request
    setRefreshing(true)
    try {
      const list = await api.devices(request.signal)
      if (id !== refreshId.current) return
      const online = list.filter((d) => d.status === 'device')
      setError(null)
      setLastUpdated(Date.now())
      setDevices(list)
      setSelected((prev) =>
        prev && online.some((d) => d.serial === prev) ? prev : (online[0]?.serial ?? null),
      )
    } catch (failure) {
      if (id !== refreshId.current) return
      setError(failure instanceof Error ? failure.message : String(failure))
      setLastUpdated(Date.now())
      // 延续既有选择契约：查询失败后不允许继续拿旧设备触发操作。
      setDevices([])
      setSelected(null)
    } finally {
      if (id === refreshId.current) { controller.current = null; setRefreshing(false) }
    }
  }, [])

  useEffect(() => {
    const refreshIfIdle = () => {
      if (document.visibilityState === 'visible' && controller.current == null) void refresh()
    }
    void refresh()
    const timer = window.setInterval(refreshIfIdle, 5000)
    window.addEventListener('focus', refreshIfIdle)
    document.addEventListener('visibilitychange', refreshIfIdle)
    return () => {
      ++refreshId.current
      controller.current?.abort()
      controller.current = null
      window.clearInterval(timer)
      window.removeEventListener('focus', refreshIfIdle)
      document.removeEventListener('visibilitychange', refreshIfIdle)
    }
  }, [refresh])

  const value = useMemo(
    () => ({ devices, selected, setSelected, refresh, refreshing, error, lastUpdated }),
    [devices, selected, refresh, refreshing, error, lastUpdated],
  )
  return <DeviceContext.Provider value={value}>{children}</DeviceContext.Provider>
}

export function useDevices() {
  return useContext(DeviceContext)
}
