import { Component, type ErrorInfo, type ReactNode } from 'react'
import { recordDiagnostic } from '@/lib/diagnostics'
import { Button } from '@/components/ui/button'

/** 每页独立兜底，错误页不会卸载侧边栏或另一页仍在采集的日志。 */
export class PageErrorBoundary extends Component<
  { label: string; children: ReactNode; hidden?: boolean },
  { failed: boolean; diagnosticId: string; copied: boolean }
> {
  state = { failed: false, diagnosticId: '', copied: false }

  static getDerivedStateFromError() {
    return { failed: true, diagnosticId: `render-${Date.now().toString(36)}` }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    recordDiagnostic({ id: this.state.diagnosticId, kind: 'RENDER' })
    console.error(`[${this.state.diagnosticId}] ${this.props.label} render failed`, error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    if (this.props.hidden) return null
    return <section role="alert" data-testid="page-render-error" className="space-y-3 rounded-lg border border-amber-500/40 bg-amber-500/5 p-5">
      <h2 className="text-sm font-semibold">{this.props.label}暂时无法显示</h2>
      <p className="text-xs text-muted-foreground">可以重试当前页面，或从侧边栏进入其他功能。正在执行的后台任务可在恢复后查看状态。</p>
      <p className="font-mono text-xs text-muted-foreground">诊断编号：{this.state.diagnosticId}</p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(this.state.diagnosticId).then(() => this.setState({ copied: true })).catch(() => this.setState({ copied: false }))}>{this.state.copied ? '已复制编号' : '复制诊断编号'}</Button>
        <Button type="button" size="sm" variant="outline" onClick={() => this.setState({ failed: false, diagnosticId: '' })}>重试当前页面</Button>
        <Button type="button" size="sm" variant="outline" onClick={() => window.location.reload()}>重新加载应用</Button>
      </div>
    </section>
  }
}
