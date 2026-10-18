import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useDirtyGuard } from '@/lib/navigation-guard'
import { useOperation } from '@/lib/api-governance'
import { RequestError } from '@/lib/diagnostics'
import { maintenance, formatBytes, waitForTransfer, type Overview, type Backup, type RestorePreview, type RestoreStatus, type Retention, type CleanupDomain, type CleanupPreview, type Cleaned } from '@/lib/maintenance'
const names = { API: '接口测试', PERF: '性能测试', FUNCTION: '功能测试' }
const transferKey = 'sudpanel:data-transfer:v1'
type PendingTransfer = { id: string; startedAt: number }
type TransferPhase = 'idle' | 'exporting' | 'uploading' | 'waiting' | 'unknown' | 'failed' | 'complete'
function readPendingTransfer(): PendingTransfer | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(transferKey) || 'null') as PendingTransfer | null
    return value && typeof value.id === 'string' && /^[a-f0-9-]{36}$/i.test(value.id) && Number.isFinite(value.startedAt) ? value : null
  } catch { return null }
}
function TableCounts({ counts }: { counts: Record<string, number> }) { return <div className="max-h-56 overflow-auto rounded border"><table className="w-full text-left text-xs"><thead className="sticky top-0 bg-background"><tr><th className="p-2">数据表</th><th className="p-2 text-right">记录数</th></tr></thead><tbody>{Object.entries(counts).map(([name, count]) => <tr className="border-t" key={name}><td className="break-all p-2 font-mono">{name}</td><td className="p-2 text-right tabular-nums">{count.toLocaleString()}</td></tr>)}</tbody></table></div> }
export function MaintenancePanel({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [overview, setOverview] = useState<Overview | null>(null), [backups, setBackups] = useState<Backup[]>([]), [status, setStatus] = useState<RestoreStatus | null>(null), [ready, setReady] = useState(false), [notice, setNotice] = useState('')
  const [days, setDays] = useState({ apiDays: '', perfDays: '', functionDays: '' }), [savedDays, setSavedDays] = useState(''), [discard, setDiscard] = useState(false)
  const [restore, setRestore] = useState<RestorePreview | null>(null), [confirm, setConfirm] = useState(''), [filename, setFilename] = useState('')
  const [domain, setDomain] = useState<CleanupDomain>('API'), [before, setBefore] = useState(''), [archive, setArchive] = useState(true), [cleanup, setCleanup] = useState<CleanupPreview | null>(null), [cleaned, setCleaned] = useState<Cleaned | null>(null)
  const [transfer, setTransfer] = useState<PendingTransfer | null>(readPendingTransfer), [transferPhase, setTransferPhase] = useState<TransferPhase>('idle')
  const transferRef = useRef(transfer), uploadInput = useRef<HTMLInputElement>(null), reloading = useRef(false)
  const op = useOperation(), dirty = ready && JSON.stringify(days) !== savedDays
  const persistTransfer = (value: PendingTransfer | null) => {
    if (value) sessionStorage.setItem(transferKey, JSON.stringify(value))
    else { try { sessionStorage.removeItem(transferKey) } catch { /* 本次已确认终态，内存状态仍可解除。 */ } }
    transferRef.current = value; setTransfer(value)
  }
  const finishTransfer = (result: NonNullable<RestoreStatus['lastResult']>) => {
    persistTransfer(null)
    if (result.status !== 'RESTORED') {
      setTransferPhase('failed'); setNotice('本次导入未完成，平台继续使用原数据。可核对数据包后重新上传。')
      throw new Error(result.status === 'ROLLED_BACK' ? '导入失败，平台已回滚原数据。可核对文件后重新上传。' : '平台已拒绝本次导入，继续使用原数据。可核对文件后重新上传。')
    }
    setTransferPhase('complete'); setReady(false); setSavedDays(JSON.stringify(days)); setNotice('全部数据已导入，正在刷新页面…')
    window.setTimeout(() => { reloading.current = true; window.location.reload() }, 300)
  }
  const awaitTransfer = async (pending: PendingTransfer, signal: AbortSignal) => {
    setTransferPhase('waiting'); setNotice('已提交导入，平台正在自动备份、恢复并重启。请保持当前页面，完成后会自动刷新。')
    try {
      const result = await waitForTransfer(pending.id, signal, setStatus)
      if (result.status !== 'RESTORED') {
        try { const value = await maintenance.overview(signal); applyOverview(value, !dirty); setReady(true) }
        catch { /* 终态已确认；概览暂不可读时仍解除本次等待，保留手动刷新入口。 */ }
      }
      return result
    }
    catch (error) { if (!signal.aborted) setTransferPhase('unknown'); throw error }
  }
  const checkTransfer = () => {
    const pending = transferRef.current
    if (pending) void op.run(signal => awaitTransfer(pending, signal), finishTransfer)
  }
  const exportTransfer = () => void op.run(async signal => {
    setTransferPhase('exporting'); setNotice('正在打包数据库、配置和历史附件，请等待…')
    try { return await maintenance.transferExport(signal) }
    catch (error) { setTransferPhase('failed'); throw error }
  }, value => {
    if (!value?.id) { setTransferPhase('failed'); throw new Error('未取得数据包下载地址，请重试。') }
    const link = document.createElement('a'); link.href = maintenance.transferDownloadUrl(value.id); link.download = ''; document.body.appendChild(link); link.click(); link.remove()
    setTransferPhase('idle'); setNotice(`全部数据已打包，已开始下载（${formatBytes(value.bytes)}）。数据包含本机凭据，请仅交给获准使用的同事。`)
  })
  const importTransfer = (file: File) => {
    if (op.busy || transferRef.current) return
    if (file.size > 256 * 1024 * 1024) { op.setError('数据包超过256MB，请选择更小的数据包。'); return }
    if (!file.size || !/\.zip$/i.test(file.name)) { op.setError('请选择平台打包下载的非空 ZIP 数据包。'); return }
    void op.run(async signal => {
      const pending = { id: crypto.randomUUID(), startedAt: Date.now() }
      try { persistTransfer(pending) }
      catch { throw new Error('浏览器无法保存导入编号，本次尚未上传。请检查浏览器存储设置后重试。') }
      setTransferPhase('uploading'); setNotice('正在上传并导入全部数据，请勿关闭页面…')
      try {
        const accepted = await maintenance.transferImport(file, pending.id, signal)
        if (accepted?.id !== pending.id || accepted.status !== 'RESTARTING') throw new Error('导入回复尚未确认')
      } catch (error) {
        if (error instanceof RequestError && error.status != null && error.status >= 400 && error.status < 500) {
          persistTransfer(null); setTransferPhase('failed'); setNotice('本次导入未获接受，当前数据未被替换。'); throw error
        }
        if (signal.aborted) throw error
        // 回复丢失仍有客户端编号：只按编号查结果，绝不再次发送 ZIP。
      }
      return awaitTransfer(pending, signal)
    }, finishTransfer)
  }
  const applyOverview = (value: Overview, applyDays = true) => { setOverview(value); setStatus(value.pendingRestore); if (applyDays) { const d = { apiDays: value.retention.apiDays == null ? '' : String(value.retention.apiDays), perfDays: value.retention.perfDays == null ? '' : String(value.retention.perfDays), functionDays: value.retention.functionDays == null ? '' : String(value.retention.functionDays) }; setDays(d); setSavedDays(JSON.stringify(d)) } }
  const refresh = () => op.run(async signal => ({ overview: await maintenance.overview(signal), backups: await maintenance.backups(signal) }), result => { applyOverview(result.overview, !dirty); setBackups(result.backups); setReady(true) })
  useEffect(() => { if (open) { setReady(false); setNotice(''); if (transferRef.current) checkTransfer(); else void refresh() } else { op.invalidate(); setDiscard(false); setRestore(null); setConfirm(''); setCleanup(null) } }, [open]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!open || !op.busy || !['uploading', 'waiting'].includes(transferPhase)) return
    const preventUnload = (event: BeforeUnloadEvent) => { if (!reloading.current) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', preventUnload)
    return () => window.removeEventListener('beforeunload', preventUnload)
  }, [open, op.busy, transferPhase])
  useDirtyGuard({ label: '历史保留设置', dirty: open && dirty, locked: open && op.busy, discard: () => { if (savedDays) setDays(JSON.parse(savedDays)); setDiscard(false) } })
  const close = (value: boolean) => { if (op.busy) return; if (!value && dirty) setDiscard(true); else onOpenChange(value) }
  const invalidateCleanup = () => { setCleanup(null); setCleaned(null) }
  const staged = status?.staged === true
  return <Dialog open={open} onOpenChange={close}><DialogContent className="flex max-h-[92vh] flex-col overflow-hidden sm:max-w-5xl"><DialogHeader><DialogTitle>数据维护与迁移</DialogTitle><DialogDescription>在原电脑打包全部数据，在新电脑上传导入。数据包包含本机凭据，请仅在获准的电脑间使用。</DialogDescription></DialogHeader><div className="space-y-4 overflow-y-auto pr-1">
    <div className="flex flex-wrap items-center gap-2"><Button variant="outline" size="sm" disabled={op.busy} onClick={() => void refresh()}>刷新维护状态</Button>{op.busy && <span role="status" className="text-xs">正在处理，请等待…</span>}{overview && <span className="text-xs">数据库及日志文件 {formatBytes(overview.databaseBytes)} · {overview.active ? '有活动任务或执行者仍在收尾' : '当前无活动任务'}</span>}</div>{op.error && <p role="alert" className="rounded border border-red-300 p-3 text-xs text-red-600">{op.error}</p>}{notice && <p role="status" className="rounded border p-3 text-xs">{notice}</p>}{!ready && !op.busy && <p className="text-xs text-muted-foreground">维护状态尚未加载，可点击刷新重试。</p>}
    {status?.lastResult && <section aria-label="上次恢复结果" className={`space-y-1 rounded border p-3 text-xs ${['REFUSED', 'ROLLED_BACK'].includes(status.lastResult.status) ? 'border-amber-500' : ''}`}><p className="font-medium">上次恢复：{status.lastResult.status === 'RESTORED' ? '恢复完成' : status.lastResult.status === 'REFUSED' ? '已拒绝恢复，继续使用原库' : status.lastResult.status === 'ROLLED_BACK' ? '恢复失败，已回滚原库与密钥' : status.lastResult.status}</p>{status.lastResult.error && <p role="alert">{status.lastResult.error}</p>}{status.lastResult.restoredAt && <p>{status.lastResult.restoredAt}</p>}</section>}
    <section aria-label="全部数据迁移" className="space-y-3 rounded border p-3">
      <h3 className="text-sm font-medium">全部数据迁移</h3>
      <p className="text-xs text-muted-foreground">包含数据库、配对密钥、设备配置、历史截图和归档。导入会覆盖本机数据；平台先自动备份原数据，再完成导入和重启，无需手动复制文件。</p>
      <div className="flex flex-wrap gap-2">
        <Button disabled={op.busy || staged || !ready || !!overview?.active || !!transfer || transferPhase === 'complete'} onClick={exportTransfer}>打包并下载全部数据</Button>
        <Button variant="outline" disabled={op.busy || staged || !ready || !!overview?.active || !!transfer || transferPhase === 'complete'} onClick={() => uploadInput.current?.click()}>上传并导入全部数据</Button>
        <input ref={uploadInput} aria-label="上传并导入全部数据" className="hidden" type="file" accept=".zip,application/zip" disabled={op.busy || staged || !ready || !!overview?.active || !!transfer} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) importTransfer(file) }} />
      </div>
      <p className="text-xs text-muted-foreground">选择 ZIP 文件后立即导入，最多256MB。USB调试授权和系统钥匙串密码需在目标电脑另行配置。</p>
      {overview?.active && <p className="text-xs text-amber-700 dark:text-amber-400">请先停止正在进行的测试并等待收尾，再刷新维护状态。</p>}
      {staged && !transfer && <p className="text-xs text-amber-700 dark:text-amber-400">已有高级恢复安排。请在下方高级备份恢复中处理或取消后再迁移。</p>}
      {transfer && transferPhase === 'unknown' && <div className="space-y-2 rounded border border-amber-500 p-3 text-xs"><p>本次导入结果尚未确认。重新检查只查询结果，不会再次上传或覆盖数据。</p><Button variant="outline" size="sm" disabled={op.busy} onClick={checkTransfer}>重新检查状态</Button></div>}
    </section>
    {overview && <><details><summary className="cursor-pointer text-sm">各表容量概览 · {overview.tables.length} 张表</summary><div className="mt-2"><TableCounts counts={Object.fromEntries(overview.tables.map(t => [t.name, t.rows]))} /></div></details>{overview.warnings.map((w, i) => <p className="text-xs text-muted-foreground" key={i}>{w}</p>)}</>}
    <details className="rounded border p-3"><summary className="cursor-pointer text-sm font-medium">高级备份恢复（可选）</summary><fieldset disabled={op.busy || !!transfer} className="mt-3 space-y-3"><p className="text-xs text-muted-foreground">仅数据库与配对密钥的原生备份及恢复预检。迁移到其他电脑请使用上方“全部数据迁移”；历史升级库与新库可能因结构差异被此处预检拒绝，不要绕过校验。</p>
    {staged && !transfer && <section aria-label="待启动恢复" className="space-y-2 rounded border border-amber-500 p-3 text-xs"><p className="font-medium">已安排下次启动恢复</p><p>修改请求已暂停。关闭并重新启动平台后，在数据库打开前恢复；当前进程尚未替换数据。</p><p className="break-all font-mono">SHA256 {status?.sha256}</p><Button variant="outline" disabled={op.busy} onClick={() => void op.run(maintenance.unstage, value => { setStatus(value); setNotice('恢复安排已取消，可以继续正常修改配置。'); setRestore(null); setConfirm('') })}>取消恢复安排</Button></section>}
    <section className="space-y-3 rounded border p-3"><h3 className="text-sm font-medium">一致性完整备份</h3><p className="text-xs text-muted-foreground">使用 SQLite 备份接口，可在任务运行中生成一致快照。ZIP 内的密钥能解密冻结执行计划和多请求草稿，请勿直接外发。</p><Button disabled={op.busy} onClick={() => void op.run(async signal => { const created = await maintenance.backup(signal); return { created, backups: await maintenance.backups(signal) } }, value => { setBackups(value.backups); setNotice(`本机备份已生成：${value.created.id}`) })}>生成本机完整 ZIP 备份</Button>{ready && backups.length === 0 && <p className="text-xs text-muted-foreground">还没有平台内生成的备份。</p>}<div className="max-h-56 space-y-2 overflow-auto">{backups.map(b => <article className="space-y-1 rounded bg-muted/40 p-2 text-xs" key={b.id}><div className="flex flex-wrap justify-between gap-2"><span>{b.createdAt} · {formatBytes(b.bytes)} · 含本机凭据</span><a className="underline" href={`/api/maintenance/backups/${b.id}/download`} download>下载私密 ZIP</a></div><p className="break-all font-mono">SHA256 {b.sha256}</p></article>)}</div></section>
    <section className="space-y-3 rounded border p-3"><h3 className="text-sm font-medium">上传恢复预检</h3><p className="text-xs text-muted-foreground">预检不会替换当前库。校验 ZIP 的数据库/密钥配对及版本结构，展示记录数变化；恢复覆盖全库。旧裸 SQLite 仅在可通过本机密钥校验时接受。</p><label className="block text-xs">选择私密 ZIP 或旧 SQLite（最多256MB）<input aria-label="选择恢复备份文件" className="mt-2 block w-full text-xs" type="file" accept=".zip,.db,.sqlite,application/zip,application/octet-stream" disabled={op.busy || staged} onChange={e => { const file = e.target.files?.[0]; e.target.value = ''; if (!file) return; setRestore(null); setConfirm(''); setFilename(file.name); if (file.size > 256 * 1024 * 1024) { op.setError('备份文件超过256MB'); return } void op.run(async signal => maintenance.preview(await file.arrayBuffer(), signal), setRestore) }} /></label>{filename && <p className="break-all text-xs text-muted-foreground">已选择：{filename}</p>}{restore && <section aria-label="恢复预检差异" className="space-y-2 rounded bg-muted/30 p-3"><p className="text-xs font-medium">预检通过，请核对覆盖范围</p><div className="max-h-64 overflow-auto"><table className="w-full text-left text-xs"><thead className="sticky top-0 bg-background"><tr>{['数据表', '当前', '恢复后', '变化'].map(s => <th className="p-2" key={s}>{s}</th>)}</tr></thead><tbody>{restore.differences.map(d => <tr className="border-t" key={d.table}><td className="break-all p-2 font-mono">{d.table}</td><td className="p-2 tabular-nums">{d.currentRows}</td><td className="p-2 tabular-nums">{d.restoredRows}</td><td className="p-2 tabular-nums">{d.delta > 0 ? '+' : ''}{d.delta}</td></tr>)}</tbody></table></div>{restore.warnings.map((w, i) => <p className="text-xs text-amber-700 dark:text-amber-400" key={i}>{w}</p>)}<p className="break-all font-mono text-xs">{restore.sha256}</p><Button size="sm" variant="outline" disabled={op.busy} onClick={() => void op.run(() => navigator.clipboard.writeText(restore.sha256), () => setNotice('SHA256已复制，请粘贴到确认框核对这份备份。'))}>复制SHA256</Button><label className="block space-y-1 text-xs">粘贴上方完整 SHA256，确认此份备份<Input aria-label="确认恢复SHA256" value={confirm} disabled={op.busy || staged} onChange={e => setConfirm(e.target.value)} placeholder="64位SHA256" /></label><Button variant="destructive" disabled={op.busy || staged || overview?.active || confirm.trim() !== restore.sha256} onClick={() => void op.run(signal => maintenance.stage(restore.id, confirm.trim(), signal), value => { setStatus(value); setNotice('已安排下次启动恢复；现库尚未替换。可以取消安排，或关闭并重启平台完成恢复。'); setCleanup(null) })}>确认安排下次启动恢复</Button>{overview?.active && <p className="text-xs text-muted-foreground">先停止活动任务并等待收尾，再刷新维护状态。</p>}</section>}</section>
    </fieldset></details>
    <fieldset disabled={!!transfer} className="min-w-0"><section className="space-y-3 rounded border p-3"><h3 className="text-sm font-medium">保留建议与显式清理</h3><p className="text-xs text-muted-foreground">天数只作为清理预览的默认范围，不触发自动删除。空值沿用旧行为；MGTOAPP 原有50条自动保留仍有效。</p><fieldset disabled={op.busy || staged || !ready} className="grid gap-2 sm:grid-cols-3">{([['apiDays', '接口保留天数'], ['perfDays', '性能保留天数'], ['functionDays', '功能保留天数']] as const).map(([key, title]) => <label className="space-y-1 text-xs" key={key}>{title}<Input aria-label={title} type="number" min={1} max={36500} placeholder="沿用旧行为" value={days[key]} onChange={e => { setDays(v => ({ ...v, [key]: e.target.value })); invalidateCleanup() }} /></label>)}</fieldset><Button variant="outline" disabled={op.busy || staged || !dirty} onClick={() => { const value: Retention = { apiDays: null, perfDays: null, functionDays: null }; for (const key of ['apiDays', 'perfDays', 'functionDays'] as const) { const text = days[key]; if (text && (!/^\d+$/.test(text) || Number(text) < 1 || Number(text) > 36500)) { op.setError('保留天数需为1至36500的整数，或留空'); return } value[key] = text ? Number(text) : null } void op.run(signal => maintenance.retention(value, signal), result => { const d = { apiDays: result.apiDays == null ? '' : String(result.apiDays), perfDays: result.perfDays == null ? '' : String(result.perfDays), functionDays: result.functionDays == null ? '' : String(result.functionDays) }; setDays(d); setSavedDays(JSON.stringify(d)); setNotice('保留建议已保存，尚未删除任何历史。'); invalidateCleanup() }) }}>保存保留建议</Button>
      <fieldset disabled={op.busy || staged} className="flex flex-wrap items-end gap-2 text-xs"><label className="space-y-1">清理模块<select aria-label="清理模块" className="block h-9 rounded border bg-background px-2" value={domain} onChange={e => { setDomain(e.target.value as CleanupDomain); invalidateCleanup() }}>{Object.entries(names).map(([key, title]) => <option value={key} key={key}>{title}</option>)}</select></label><label className="space-y-1">早于此时间（本机时区）<Input aria-label="清理截止时间" type="datetime-local" value={before} onChange={e => { setBefore(e.target.value); invalidateCleanup() }} /></label><label className="flex h-9 items-center gap-1"><input type="checkbox" checked={archive} onChange={e => { setArchive(e.target.checked); invalidateCleanup() }} />先生成私密历史归档</label><Button variant="outline" disabled={op.busy || staged || dirty} onClick={() => { let cutoff: string | null = null; if (before) { const time = new Date(before); if (Number.isNaN(time.getTime())) { op.setError('清理截止时间无效'); return } cutoff = time.toISOString() } setCleaned(null); void op.run(signal => maintenance.cleanupPreview(domain, cutoff, archive, signal), setCleanup) }}>预览清理范围</Button></fieldset><p className="text-xs text-muted-foreground">截止时间留空时使用已保存的保留天数。提交时再次检查活动执行者与记录变化；预览15分钟有效。</p>
      {cleanup && <section aria-label="清理范围预览" className="space-y-2 rounded bg-muted/30 p-3"><p className="text-xs font-medium">{names[cleanup.domain]} · 早于 {cleanup.before} · 运行记录 {cleanup.ids.length} 项</p><TableCounts counts={cleanup.counts} />{cleanup.warnings.map((w, i) => <p className="text-xs text-amber-700 dark:text-amber-400" key={i}>{w}</p>)}<Button variant="destructive" disabled={op.busy || staged || overview?.active || Object.values(cleanup.counts).every(n => n === 0)} onClick={() => void op.run(signal => maintenance.cleanupCommit(cleanup.token, signal), result => { setCleaned(result); setCleanup(null); setNotice('已按预览范围清理完成；可刷新容量概览核对。') })}>确认删除以上历史</Button></section>}{cleaned && <section aria-label="历史清理结果" className="space-y-2 rounded border p-3 text-xs"><p>清理完成：{names[cleaned.domain]}，运行记录 {cleaned.deleted} 项；各表删除数量如下。</p><TableCounts counts={cleaned.counts} />{cleaned.archiveId && <a className="inline-block underline" href={`/api/maintenance/archives/${cleaned.archiveId}/download`} download>下载本次私密历史归档（含原始证据与凭据）</a>}</section>}
    </section></fieldset>{discard && <section role="alert" className="space-y-2 rounded border border-amber-500 p-3 text-xs"><p>保留建议有未保存修改。</p><div className="flex gap-2"><Button variant="outline" onClick={() => setDiscard(false)}>继续编辑</Button><Button variant="destructive" onClick={() => { if (savedDays) setDays(JSON.parse(savedDays)); setDiscard(false); onOpenChange(false) }}>放弃并关闭</Button></div></section>}
  </div></DialogContent></Dialog>
}
