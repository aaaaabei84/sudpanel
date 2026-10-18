import type { ApiExec } from '@/types'

/** A display projection never becomes a replay/draft/inspection source. */
export function executionDisplay(execution: ApiExec, reveal = false): ApiExec {
  if (reveal) return execution
  try {
    const metadata = JSON.parse(execution.metadata || '{}')
    const safe = metadata.safeDisplay
    if (safe && typeof safe === 'object' && !Array.isArray(safe)) return { ...execution, ...safe, id: execution.id, metadata: JSON.stringify(safe.metadata || {}) }
  } catch { /* Legacy evidence has no explicit secret inventory. Keep its completeness meaning. */ }
  // Old snapshots cannot recover former variable marks. Cover named credentials without inventing an inventory.
  const mask = (text: string | null) => text?.replace(/((?:authorization|proxy-authorization|cookie|set-cookie)\s*:\s*)[^\r\n]+/gi, '$1***')
    .replace(/("(?:token|password|app_secret|api_key|access_token)"\s*:\s*)"(?:[^"\\]|\\.)*"/gi, '$1"***"') ?? text
  return { ...execution, reqBody: mask(execution.reqBody), respBody: mask(execution.respBody), reqHeaders: mask(execution.reqHeaders), respHeaders: mask(execution.respHeaders) }
}
export function hasSecretInventory(execution: ApiExec): boolean {
  try { return !!JSON.parse(execution.metadata || '{}').secretMask } catch { return false }
}
