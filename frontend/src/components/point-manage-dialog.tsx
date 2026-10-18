import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Loader2, Plus, RefreshCw, Save, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { api } from '@/lib/api'

const GAME_GROUP = 'mgtoapp_game'
const PLAYER_GROUP = 'mgtoapp_player'

interface Row {
  key: string
  id?: number
  group: string
  state: string
  note: string
  shortName: string
  isNew: boolean
  /** 已落库的值（新增行为空串）。用来判断这一行到底改没改过 —— 关弹窗的拦截全靠它。 */
  orig: { state: string; note: string; shortName: string }
  saving: boolean
}

let keySeq = 0
const nextKey = () => `row-${Date.now()}-${keySeq++}`

/** 有内容的行没保存、或已落库的行被改过，都算「改了」。空的新增行不算 —— 它没有内容可丢。 */
function isDirty(r: Row): boolean {
  if (r.isNew) {
    return Boolean(r.state.trim() || r.note.trim() || r.shortName.trim())
  }
  return r.state !== r.orig.state || r.note !== r.orig.note || r.shortName !== r.orig.shortName
}

/**
 * 「测试点管理」弹窗（需求 #45 / #46）。
 *
 * <p>原来这是侧边栏上一个独立页面（`pages/manage.tsx`）。实际用起来最常发生的是
 * 「正在走查，发现这个测试点的名字或过滤关键字写错了」—— 那时得切页面、改完、再切回来，
 * 而改的往往就是手上这一个点。收进 mgtoapp 页的弹窗后，不出页面就能改。
 *
 * <p><b>保存方式改成每行一个按钮</b>（#46）。原来三个输入框都是失焦即存；弹窗里一次能看到的行更少、
 * 滚动更多，失焦的时机更难预料。显式保存的代价是「新增了一行、填了、忘了点保存、关弹窗」，
 * 所以关弹窗时必须拦住没保存的内容 —— 见 `warnOpen`。
 *
 * <p><b>删除走行内二次确认</b>，不用 `window.confirm`：原生确认框会把焦点从弹窗上摘走，
 * 而弹窗是模态的、焦点被锁在里面，两者叠在一起容易出现「确认完弹窗自己关了」。
 */
export function PointManageDialog({
  open,
  onOpenChange,
  onChanged,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  /** 有落库改动时回调。父页面据此重载测试点列表、并清掉已被删测试点在选中集合里的悬空 id。 */
  onChanged: () => void
}) {
  const [rows, setRows] = useState<Row[]>([])
  const [loaded, setLoaded] = useState(false)
  const [confirmKey, setConfirmKey] = useState<string | null>(null)
  /** 关弹窗时若有没保存的内容，先在这里停下来问一句，而不是直接丢掉。 */
  const [warnOpen, setWarnOpen] = useState(false)

  const load = useCallback(async () => {
    try {
      // 第二个参数必须是 true（all）：管理界面要能看到「未启用」的测试点，
      // 否则它们在这张表里直接消失，用户会以为被删了。
      const list = await api.points('MGTOAPP', true)
      setRows(
        list.map((p) => ({
          key: nextKey(),
          id: p.id,
          group: p.group,
          state: p.stateName || '',
          note: p.prompt || '',
          shortName: p.description || '',
          isNew: false,
          orig: {
            state: p.stateName || '',
            note: p.prompt || '',
            shortName: p.description || '',
          },
          saving: false,
        })),
      )
      setLoaded(true)
    } catch (e) {
      toast.error(`测试点加载失败：${(e as Error).message}`)
    }
  }, [])

  // 每次打开都重新拉：走查过程中别处也可能改过，拿上次的缓存改一下容易把别人的改动盖回去
  useEffect(() => {
    if (open) {
      setConfirmKey(null)
      setWarnOpen(false)
      void load()
    }
  }, [open, load])

  const updateRow = (key: string, field: 'state' | 'note' | 'shortName', value: string) =>
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, [field]: value } : r)))

  const patchRow = (key: string, patch: Partial<Row>) =>
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)))

  const saveRow = async (key: string) => {
    const row = rows.find((r) => r.key === key)
    if (!row) return
    const state = row.state.trim()
    if (!state) {
      toast.error('state 不能为空（它是过滤关键字，空了这条测试点筛不出日志）')
      return
    }
    patchRow(key, { saving: true })
    try {
      if (row.isNew) {
        const created = await api.createMgPoint(row.group, state, row.note.trim(), row.shortName.trim())
        patchRow(key, {
          id: created.id,
          isNew: false,
          state,
          orig: { state, note: row.note.trim(), shortName: row.shortName.trim() },
        })
      } else {
        await api.updateMgPoint(row.id!, row.group, state, row.note.trim(), row.shortName.trim())
        patchRow(key, {
          state,
          orig: { state, note: row.note.trim(), shortName: row.shortName.trim() },
        })
      }
      toast.success('已保存')
      onChanged()
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`)
    } finally {
      patchRow(key, { saving: false })
    }
  }

  const deleteRow = async (key: string) => {
    const row = rows.find((r) => r.key === key)
    if (!row) return
    setConfirmKey(null)
    // 还没落库的行直接从界面上拿掉就行，不用问
    if (row.isNew) {
      setRows((prev) => prev.filter((r) => r.key !== key))
      return
    }
    try {
      await api.deletePoint(row.id!)
      setRows((prev) => prev.filter((r) => r.key !== key))
      toast.success('已删除')
      onChanged()
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`)
    }
  }

  const addRow = (group: string) =>
    setRows((prev) => [
      ...prev,
      {
        key: nextKey(),
        group,
        state: '',
        note: '',
        shortName: '',
        isNew: true,
        orig: { state: '', note: '', shortName: '' },
        saving: false,
      },
    ])

  const dirtyCount = rows.filter(isDirty).length

  const handleOpenChange = (o: boolean) => {
    if (!o) {
      if (dirtyCount > 0) {
        // 不直接关：先让用户看见「有几处没保存」，再决定是继续编辑还是丢掉
        setWarnOpen(true)
        return
      }
      setWarnOpen(false)
    }
    onOpenChange(o)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      {/* 宽度必须写成 sm:max-w-* —— dialog 组件自带 sm:max-w-sm，不带 sm: 前缀会被它盖掉。
          与「用例管理」弹窗同规格（需求 #45）。 */}
      <DialogContent className="flex max-h-[90vh] flex-col overflow-hidden sm:max-w-6xl">
        <DialogHeader className="flex-row items-center gap-2 space-y-0 pr-8">
          <DialogTitle className="mr-auto">测试点管理</DialogTitle>
          <span className="text-xs text-muted-foreground">
            共 {rows.length} 个（含未启用）
            {dirtyCount > 0 && <span className="ml-2 text-amber-600 dark:text-amber-400">{dirtyCount} 处未保存</span>}
          </span>
          <Button size="sm" variant="outline" onClick={() => void load()}>
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            重新加载
          </Button>
        </DialogHeader>

        {warnOpen && (
          <div className="flex flex-wrap items-center gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-400">
            <span>有 {dirtyCount} 处改动还没保存，直接关掉会丢掉。</span>
            <Button size="sm" variant="outline" className="ml-auto h-7" onClick={() => setWarnOpen(false)}>
              继续编辑
            </Button>
            <Button
              size="sm"
              variant="destructive"
              className="h-7"
              onClick={() => {
                setWarnOpen(false)
                onOpenChange(false)
              }}
            >
              丢弃并关闭
            </Button>
          </div>
        )}

        {/* 表区自己滚，标题与按钮留在原地。min-h-0 不能省：flex 子项 min-height 默认 auto，
            不加它这层不会收缩，表既不滚还会把弹窗顶出屏幕。 */}
        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-0.5">
          {!loaded ? (
            <div className="space-y-2">
              <Skeleton className="h-5 w-16" />
              <Skeleton className="h-40 w-full rounded-lg" />
              <Skeleton className="h-5 w-16" />
              <Skeleton className="h-40 w-full rounded-lg" />
            </div>
          ) : (
            <>
              <PointTable
                title="游戏态"
                hint="state 是过滤日志用的关键字；测试点名称为界面上显示的名字"
                rows={rows.filter((r) => r.group === GAME_GROUP)}
                confirmKey={confirmKey}
                onUpdate={updateRow}
                onSave={saveRow}
                onAskDelete={setConfirmKey}
                onDelete={deleteRow}
                onAdd={() => addRow(GAME_GROUP)}
              />
              <PointTable
                title="玩家态"
                hint="两个分组各自独立，同名 state 互不影响"
                rows={rows.filter((r) => r.group === PLAYER_GROUP)}
                confirmKey={confirmKey}
                onUpdate={updateRow}
                onSave={saveRow}
                onAskDelete={setConfirmKey}
                onDelete={deleteRow}
                onAdd={() => addRow(PLAYER_GROUP)}
              />
            </>
          )}
        </div>

        <DialogFooter className="shrink-0">
          <p className="mr-auto text-xs text-muted-foreground">
            改完记得点这一行右边的保存 —— 关掉弹窗时若有没保存的内容会拦一下。
          </p>
          <Button variant="outline" onClick={() => handleOpenChange(false)}>
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function PointTable({
  title,
  hint,
  rows,
  confirmKey,
  onUpdate,
  onSave,
  onAskDelete,
  onDelete,
  onAdd,
}: {
  title: string
  hint: string
  rows: Row[]
  confirmKey: string | null
  onUpdate: (key: string, field: 'state' | 'note' | 'shortName', value: string) => void
  onSave: (key: string) => void
  onAskDelete: (key: string | null) => void
  onDelete: (key: string) => void
  onAdd: () => void
}) {
  return (
    <div>
      <h2 className="mb-1 text-sm font-semibold">{title}</h2>
      <p className="mb-2 text-xs text-muted-foreground">{hint}</p>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow className="bg-muted/50">
              <TableHead className="w-[30%]">state（过滤关键字）</TableHead>
              <TableHead>备注</TableHead>
              <TableHead>测试点名称</TableHead>
              <TableHead className="w-[132px]"></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="py-6 text-center text-sm text-muted-foreground">
                  暂无测试点
                </TableCell>
              </TableRow>
            ) : (
              rows.map((row) => {
                const dirty = isDirty(row)
                return (
                  <TableRow key={row.key}>
                    <TableCell>
                      <Input
                        value={row.state}
                        onChange={(e) => onUpdate(row.key, 'state', e.target.value)}
                        placeholder="state 名"
                        className="font-mono text-xs"
                      />
                    </TableCell>
                    <TableCell>
                      <Input
                        value={row.note}
                        onChange={(e) => onUpdate(row.key, 'note', e.target.value)}
                        placeholder="备注"
                      />
                    </TableCell>
                    <TableCell>
                      <Input
                        value={row.shortName}
                        onChange={(e) => onUpdate(row.key, 'shortName', e.target.value)}
                        placeholder="测试点名称"
                      />
                    </TableCell>
                    <TableCell>
                      {confirmKey === row.key ? (
                        <div className="flex items-center gap-1">
                          <Button
                            size="sm"
                            variant="destructive"
                            className="h-7"
                            onClick={() => void onDelete(row.key)}
                          >
                            确定删
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7"
                            onClick={() => onAskDelete(null)}
                          >
                            取消
                          </Button>
                        </div>
                      ) : (
                        <div className="flex items-center gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7"
                            disabled={!dirty || row.saving}
                            title={dirty ? '保存这一行' : '没有改动'}
                            onClick={() => void onSave(row.key)}
                          >
                            {row.saving ? (
                              <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
                            ) : (
                              <Save className="mr-1 h-3.5 w-3.5" />
                            )}
                            {row.isNew ? '新增' : '保存'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-7 w-7 text-muted-foreground hover:text-red-500"
                            title="删除"
                            onClick={() => onAskDelete(row.key)}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })
            )}
          </TableBody>
        </Table>
      </div>
      <Button variant="outline" size="sm" className="mt-2" onClick={onAdd}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        添加{title}测试点
      </Button>
    </div>
  )
}
