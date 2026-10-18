import { useCallback, useEffect, useState } from 'react'
import { api } from '@/lib/api'
import type { Direction, TestConfig } from '@/types'

/** 测试点配置管理（按方向独立，两个页面各自实例化）。 */
export function useConfigs(direction: Direction) {
  const [configs, setConfigs] = useState<TestConfig[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const reload = useCallback(async () => {
    const list = await api.configs(direction)
    setConfigs(list)
    setSelectedId((prev) => (prev && list.some((c) => c.id === prev) ? prev : null))
  }, [direction])

  useEffect(() => {
    void reload()
  }, [reload])

  const saveNew = useCallback(
    async (name: string, pointIds: number[]) => {
      await api.createConfig(direction, name, pointIds)
      await reload()
    },
    [direction, reload],
  )

  const overwrite = useCallback(
    async (id: number, name: string, pointIds: number[]) => {
      await api.updateConfig(id, name, pointIds)
      await reload()
    },
    [reload],
  )

  const remove = useCallback(
    async (id: number) => {
      await api.deleteConfig(id)
      setSelectedId(null)
      await reload()
    },
    [reload],
  )

  return { configs, selectedId, setSelectedId, reload, saveNew, overwrite, remove }
}
