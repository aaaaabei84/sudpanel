export interface ClientDiagnostic {
  id: string
  at: string
  kind: 'HTTP' | 'NETWORK' | 'RENDER'
  status?: number
  method?: string
}
const entries: ClientDiagnostic[] = []
export function recordDiagnostic(value: Omit<ClientDiagnostic, 'at'>): void {
  entries.unshift({ ...value, at: new Date().toISOString() })
  if (entries.length > 50) entries.length = 50
  notify()
}
export function diagnosticsSnapshot(): ClientDiagnostic[] { return entries.map(value => ({ ...value })) }
function notify(): void {
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') window.dispatchEvent(new Event('sudpanel:diagnostic'))
}
export function clearDiagnostics(): void { entries.length = 0; notify() }
export class RequestError extends Error {
  readonly requestId: string | null
  readonly status?: number
  constructor(message: string, requestId: string | null, status?: number) {
    super(requestId ? `${message}（诊断编号：${requestId}）` : message)
    this.name = 'RequestError'
    this.requestId = requestId; this.status = status
  }
}
