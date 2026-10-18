import type { ApiCase, ApiRequestOptions } from '@/types'
import { isJsonText, tokenizeJson } from '@/lib/json-text'
import type { VarSuggestion } from '@/lib/var-suggest'

export interface AssertionDraft { path: string; op: string; expect: string }

/** 历史头已按行渲染；不拆逗号，避免破坏 Date、Cookie 等头值。 */
export function responseHeaderMap(snapshot?: string | null): Record<string, string[]> {
  const headers: Record<string, string[]> = Object.create(null)
  for (const line of (snapshot ?? '').split(/\r?\n/)) {
    const separator = line.indexOf(':')
    if (separator <= 0) continue
    const name = line.slice(0, separator).trim().toLowerCase()
    if (!/^[!#$%&'*+.^_`|~0-9a-z-]+$/.test(name)) continue
    const value = line.slice(separator + 1).trim()
    ;(headers[name] ??= []).push(value)
  }
  return headers
}
export interface RoundQueryContext {
  roundId: string
  runId: number
  loopNo: number
  loopId: number
  gameId: number
  gameName: string
  envId?: number
  envSource?: string
  envName?: string
}

export const SYSTEM_SUGGESTIONS = [
  { name: '$timestamp', group: '系统 · 秒时间戳' },
  { name: '$timestampMs', group: '系统 · 毫秒时间戳' },
  { name: '$uuid', group: '系统 · UUID' },
  { name: '$randomInt:1:100', group: '系统 · 随机整数' },
  { name: '$date:yyyy-MM-dd', group: '系统 · 格式化日期' },
  { name: '$auth.app_id', group: '环境签名 · 应用 ID' },
  { name: '$auth.mg_id', group: '环境签名 · 游戏 ID' },
  { name: '$auth.nonce', group: '环境签名 · 随机串' },
  { name: '$auth.sign', group: '环境签名 · 仅地址与请求头' },
  { name: 'run.token', group: '请求链 · 本轮提取变量' },
]

export function capabilitySuggestions(variables: unknown[]): VarSuggestion[] {
  const found = variables.flatMap(value => {
    const item = typeof value === 'object' && value ? value as Record<string, unknown> : null
    const raw = typeof value === 'string' ? value : item?.token ?? item?.name ?? item?.placeholder
    if (typeof raw !== 'string') return []
    const name = raw.replace(/^\{\{\s*|\s*\}\}$/g, '')
    return [{ name, group: typeof item?.description === 'string' ? item.description : '系统变量', scopes: Array.isArray(item?.scopes) ? item.scopes.map(String) : undefined }]
  })
  return found.length ? [...found, { name: 'run.token', group: '请求链 · 本轮提取变量' }] : SYSTEM_SUGGESTIONS
}

/** 只改已命名的对局字段，不序列化其余 JSON，保住大整数和签名字节。 */
export function fillGameRoundId(body: string | null, roundId: string): { body: string | null; filled: boolean } {
  if (!body || !isJsonText(body)) return { body, filled: false }
  const edits: { start: number; end: number }[] = []
  let offset = 0
  for (const token of tokenizeJson(body, true)) {
    if (token.t === 'key' && token.v === '"game_round_id"') {
      let start = offset + token.v.length
      while (/\s/.test(body[start] ?? '') && start < body.length) start++
      if (body[start] === ':') start++
      while (/\s/.test(body[start] ?? '') && start < body.length) start++
      let end = start
      if (body[start] === '"') {
        end++
        while (end < body.length) { if (body[end] === '\\') { end += 2; continue }; if (body[end++] === '"') break }
      } else {
        const match = /^(?:-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null)/.exec(body.slice(start))
        if (match) end += match[0].length
      }
      if (end > start) edits.push({ start, end })
    }
    offset += token.v.length
  }
  let next = body
  for (const edit of edits.reverse()) next = next.slice(0, edit.start) + JSON.stringify(roundId) + next.slice(edit.end)
  return { body: next, filled: edits.length > 0 }
}

export function emptyOptions(): ApiRequestOptions {
  return { bodyType: null, params: [], auth: { type: 'INHERIT' }, extractors: [] }
}

export function blankRequest(): Omit<ApiCase, 'id'> {
  return { name: '', method: 'GET', url: '', headers: '', body: '', note: '',
    expectStatus: '', expectCode: '', timeoutMs: 10000, insecure: false,
    enabled: true, groupId: null, asserts: null, options: { ...emptyOptions(), strictVariables: true } }
}

export function scopedSystemSuggestions(variables: VarSuggestion[], scope: 'URL' | 'HEADER' | 'BODY'): VarSuggestion[] {
  return variables.filter(v => v.scopes?.length ? v.scopes.includes(scope) : scope !== 'BODY' || v.name !== '$auth.sign')
}

export function assertionsBroken(text?: string | null): boolean {
  if (!text) return false
  try {
    const rows: unknown = JSON.parse(text)
    return !Array.isArray(rows) || rows.some(row => !row || typeof row !== 'object' || Array.isArray(row))
  } catch { return true }
}

export function readAssertions(text?: string | null): AssertionDraft[] {
  if (!text) return []
  try {
    const rows: unknown = JSON.parse(text)
    if (!Array.isArray(rows)) return []
    return rows.map(r => ({ path: String(r.path ?? ''), op: String(r.op ?? ''), expect: String(r.expect ?? '') }))
  } catch { return [] }
}

export function readMetadata(text?: string | null): Record<string, unknown> | null {
  if (!text) return null
  try {
    const value: unknown = JSON.parse(text)
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
  } catch { return null }
}

export function assertionSource(path: string): 'BODY' | 'HEADER' | 'ELAPSED' {
  return path === '$elapsedMs' ? 'ELAPSED' : path.startsWith('header:') ? 'HEADER' : 'BODY'
}

export function assertionPath(source: string, path: string): string {
  const plain = path.startsWith('header:') ? path.slice(7) : path === '$elapsedMs' ? '' : path
  return source === 'ELAPSED' ? '$elapsedMs' : source === 'HEADER' ? `header:${plain}` : plain
}
