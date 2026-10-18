import { Gamepad2, ListChecks } from 'lucide-react'
import { EntryCard } from '@/components/entry-card'

/**
 * 上报校验（2026-10-14 需求 #89）。
 *
 * <p>侧边栏原来把 apptomg 与 mgtoapp 各列一项。它们是同一个校验的两个方向
 * （App → 游戏 / 游戏 → App），各占一个入口有两个代价：侧边栏更长，
 * 且每次都要先分辨「我这次要走哪个方向」再点 —— 而那个判断只有进到页面才看得出来。
 *
 * <p>折叠成一项之后，方向的选择变成这一步唯一的事：两张卡片、各写清「谁到谁、怎么跑」，
 * 点进去才是完整的测试页。两个原页面本身没有任何改动，直接从卡片进。
 *
 * <p>没有做成侧边栏的可展开子菜单：那样方向选择藏在二级列表里，读到的是一行小字；
 * 卡片能把「全自动」与「走查 + 人工判定」的差别写清楚，这是选方向时真正要看的信息。
 */
export function VerifyPage({
  onNavigate,
}: {
  /** 只允许去这两个方向 —— 收敛签名，避免这个页面被当成通用跳板 */
  onNavigate: (page: 'apptomg' | 'mgtoapp') => void
}) {
  return (
    <div className="space-y-6" data-testid="verify-page">
      <div className="flex flex-col gap-1 border-b border-border/60 pb-4">
        <div className="flex items-center gap-2">
          <h1 className="text-lg font-bold tracking-tight">上报校验</h1>
          <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-semibold text-primary">
            双向校验中心
          </span>
        </div>
        <p className="text-xs text-muted-foreground">
          选一个方向进入执行：两个页面的判定口径、规则模式与历史运行记录均各自独立隔离。
        </p>
      </div>
      <div className="grid gap-5 md:grid-cols-2">
        <EntryCard
          icon={Gamepad2}
          title="apptomg 全自动"
          desc="App → 游戏：选点后一键全自动执行，支持复用标定坐标，命中关键字自动判定"
          onClick={() => onNavigate('apptomg')}
        />
        <EntryCard
          icon={ListChecks}
          title="mgtoapp 走查"
          desc="游戏 → App：逐步走查，平台实时抓取日志给出判定建议、由测试人员人工确认"
          onClick={() => onNavigate('mgtoapp')}
        />
      </div>
    </div>
  )
}
