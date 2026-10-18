import { req } from '@/lib/api'
import { jsonRequest } from '@/lib/api-governance'
export type Retention = { apiDays: number | null; perfDays: number | null; functionDays: number | null }
export type RestoreStatus = { staged: boolean; restartRequired: boolean; id?: string; sha256?: string; stagedAt?: string; lastResult?: { status: string; error?: string; id?: string; restoredAt?: string; rollbackId?: string } }
export type Overview = { tables: { name: string; rows: number }[]; databaseBytes: number; active: boolean; retention: Retention; pendingRestore: RestoreStatus; warnings: string[] }
export type Backup = { id: string; createdAt: string; bytes: number; sha256: string; credentialsIncluded: boolean; counts: Record<string, number> }
export type TransferExport = { id: string; bytes: number; sha256: string; createdAt: string }
export type TransferImported = { id: string; status: 'RESTARTING'; restartRequired: true }
export type RestorePreview = { id: string; sha256: string; counts: Record<string, number>; differences: { table: string; currentRows: number; restoredRows: number; delta: number }[]; warnings: string[]; restartRequired: boolean }
export type CleanupDomain = 'API' | 'PERF' | 'FUNCTION'
export type CleanupPreview = { token: string; domain: CleanupDomain; before: string; ids: number[]; counts: Record<string, number>; warnings: string[] }
export type Cleaned = { domain: CleanupDomain; deleted: number; archiveId: string | null; counts: Record<string, number> }
export const maintenance = {
  overview: (signal: AbortSignal) => req<Overview>('/api/maintenance/overview', undefined, { signal }),
  backups: (signal: AbortSignal) => req<Backup[]>('/api/maintenance/backups', undefined, { signal }),
  backup: (signal: AbortSignal) => req<Backup>('/api/maintenance/backups', { method: 'POST' }, { signal, timeoutMs: 180000 }),
  transferExport: (signal: AbortSignal) => req<TransferExport>('/api/maintenance/transfers/export', { method: 'POST' }, { signal, timeoutMs: 180000 }),
  transferDownloadUrl: (id: string) => `/api/maintenance/transfers/${encodeURIComponent(id)}/download`,
  transferImport: (file: Blob, id: string, signal: AbortSignal) => req<TransferImported>('/api/maintenance/transfers/import', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream', 'X-Transfer-Id': id }, body: file }, { signal, timeoutMs: 180000 }),
  transferStatus: (id: string, signal: AbortSignal) => req<RestoreStatus>(`/api/maintenance/transfers/status?operationId=${encodeURIComponent(id)}`, undefined, { signal, timeoutMs: 5000 }),
  retention: (value: Retention, signal: AbortSignal) => req<Retention>('/api/maintenance/retention', jsonRequest('PUT', value), { signal }),
  preview: (bytes: ArrayBuffer, signal: AbortSignal) => req<RestorePreview>('/api/maintenance/restores/preview', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: bytes }, { signal, timeoutMs: 180000 }),
  stage: (id: string, confirmSha256: string, signal: AbortSignal) => req<RestoreStatus>(`/api/maintenance/restores/${id}/stage`, jsonRequest('POST', { confirmSha256 }), { signal, timeoutMs: 180000 }),
  unstage: (signal: AbortSignal) => req<RestoreStatus>('/api/maintenance/restores/stage', { method: 'DELETE' }, { signal }),
  cleanupPreview: (domain: CleanupDomain, before: string | null, archive: boolean, signal: AbortSignal) => req<CleanupPreview>('/api/maintenance/cleanup/preview', jsonRequest('POST', { domain, before, archive }), { signal, timeoutMs: 180000 }),
  cleanupCommit: (token: string, signal: AbortSignal) => req<Cleaned>('/api/maintenance/cleanup/commit', jsonRequest('POST', { token }), { signal, timeoutMs: 180000 }),
}

/** 只查询本次编号；短暂离线或旧恢复结果都不能证明导入成功，绝不重发上传。 */
export async function waitForTransfer(id: string, signal: AbortSignal, onStatus: (value: RestoreStatus) => void, timeoutMs = 120000): Promise<NonNullable<RestoreStatus['lastResult']>> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (signal.aborted) throw new DOMException('已停止等待', 'AbortError')
    try {
      const value = await maintenance.transferStatus(id, signal)
      if (signal.aborted) throw new DOMException('已停止等待', 'AbortError')
      onStatus(value)
      if (value.lastResult?.id === id && ['RESTORED', 'REFUSED', 'ROLLED_BACK'].includes(value.lastResult.status)) return value.lastResult
    } catch (error) {
      if (signal.aborted) throw error
      // 自动重启期间服务会短暂离线；仅继续读取状态，不重试任何写请求。
    }
    await new Promise<void>((resolve, reject) => {
      const abort = () => { window.clearTimeout(timer); reject(new DOMException('已停止等待', 'AbortError')) }
      const timer = window.setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, 1000)
      signal.addEventListener('abort', abort, { once: true })
      if (signal.aborted) { signal.removeEventListener('abort', abort); abort() }
    })
  }
  throw new Error('暂时无法确认本次导入结果。请重新检查状态，不要重复上传；平台仍会保留本次导入编号。')
}
export const formatBytes = (bytes: number) => bytes < 1024 ? `${bytes} B` : bytes < 1024 ** 2 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 ** 2).toFixed(1)} MiB`
