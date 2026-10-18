import { useState } from 'react'
import { toast } from 'sonner'
import { BookmarkPlus, Save, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useConfigs } from '@/hooks/use-configs'
import type { Direction } from '@/types'

interface ConfigBarProps {
  direction: Direction
  pointIds: number[]
  onLoad: (ids: number[]) => void
}

/** 测试点配置栏（保存为新配置 / 覆盖 / 删除 / 下拉加载）。 */
export function ConfigBar({ direction, pointIds, onLoad }: ConfigBarProps) {
  const { configs, selectedId, setSelectedId, saveNew, overwrite, remove } = useConfigs(direction)
  const [name, setName] = useState('')

  const handleSelect = (v: string) => {
    const id = v ? Number(v) : null
    setSelectedId(id)
    if (id) {
      const cfg = configs.find((c) => c.id === id)
      if (cfg) {
        onLoad(cfg.pointIds)
        setName(cfg.name)
      }
    }
  }

  const handleSaveNew = async () => {
    if (!name.trim()) {
      toast.error('请输入配置名')
      return
    }
    if (pointIds.length === 0) {
      toast.error('请至少勾选一个测试点')
      return
    }
    try {
      await saveNew(name.trim(), pointIds)
      setName('')
      toast.success(`已保存配置「${name.trim()}」`)
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`)
    }
  }

  const handleOverwrite = async () => {
    if (!selectedId) return
    const cfg = configs.find((c) => c.id === selectedId)
    const n = name.trim() || cfg?.name || ''
    try {
      await overwrite(selectedId, n, pointIds)
      toast.success(`已覆盖配置「${n}」`)
    } catch (e) {
      toast.error(`覆盖失败：${(e as Error).message}`)
    }
  }

  const handleDelete = async () => {
    if (!selectedId) return
    try {
      await remove(selectedId)
      setName('')
      toast.success('已删除配置')
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-card p-3">
      <span className="text-xs font-medium text-muted-foreground">配置</span>
      <Select value={selectedId ? String(selectedId) : ''} onValueChange={handleSelect}>
        <SelectTrigger className="w-40">
          <SelectValue placeholder="选择配置" />
        </SelectTrigger>
        <SelectContent>
          {configs.length === 0 ? (
            <div className="px-2 py-3 text-center text-xs text-muted-foreground">暂无配置</div>
          ) : (
            configs.map((c) => (
              <SelectItem key={c.id} value={String(c.id)}>
                {c.name}
              </SelectItem>
            ))
          )}
        </SelectContent>
      </Select>
      <Input
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="配置名"
        className="w-36"
      />
      <Button size="sm" variant="outline" onClick={() => void handleSaveNew()}>
        <BookmarkPlus className="mr-1 h-3.5 w-3.5" />
        保存为新配置
      </Button>
      <Button size="sm" variant="outline" disabled={!selectedId} onClick={() => void handleOverwrite()}>
        <Save className="mr-1 h-3.5 w-3.5" />
        覆盖选中
      </Button>
      <Button
        size="sm"
        variant="ghost"
        className="text-destructive hover:text-destructive"
        disabled={!selectedId}
        onClick={() => void handleDelete()}
      >
        <Trash2 className="mr-1 h-3.5 w-3.5" />
        删除
      </Button>
    </div>
  )
}
