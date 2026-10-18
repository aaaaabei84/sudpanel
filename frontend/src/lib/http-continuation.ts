import { req } from '@/lib/api'
import type { ApiCaseInput } from '@/lib/api'
import { responseHeaderMap } from '@/lib/apitest-workspace'
import type { ApiPrepared, ApiResponseField, ApiResponseInspection } from '@/types'

export interface VariableDiagnostic {
  name: string; scope: string; location: string; offset: number; line: number; column: number
  status: 'RESOLVED' | 'MISSING' | 'LITERAL'; source: string | null
  candidates: string[]; shadowedSources: string[]
}
export type PreparedWithDiagnostics = ApiPrepared & { diagnostics?: VariableDiagnostic[] }
export type ResponseField = ApiResponseField & { typeSelectable?: boolean }
export interface ResponseLimits { maxFields: number; maxDepth: number; maxValueChars: number; previewChars: number }
export type ResponseInspection = Omit<ApiResponseInspection, 'fields'> & {
  fields: ResponseField[]; truncationReasons?: string[]; limits?: ResponseLimits
}
export interface PathInspection { found: boolean; fields: ResponseField[]; error: string | null; truncationReasons: string[]; limits: ResponseLimits }
export interface ActualCurl { curl: string; warnings: string[]; source: 'PREPARED' | 'HISTORY'; complete: boolean; generatedAt: string }
const json = (body: unknown): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })

export const httpContinuation = {
  inspect: (body: string, headers: string | null | undefined, signal?: AbortSignal) =>
    req<ResponseInspection>('/api/apitest/inspect-response', json({ body, headers: responseHeaderMap(headers) }), { signal }),
  inspectPath: (body: string, headers: string | null | undefined, path: string, signal?: AbortSignal) =>
    req<PathInspection>('/api/apitest/inspect-response/path', json({ body, headers: responseHeaderMap(headers), path }), { signal }),
  actualCurl: (spec: ApiCaseInput, envId: number | null | undefined, includeSecrets = false, signal?: AbortSignal) =>
    req<ActualCurl>('/api/apitest/export/curl/actual', json({ spec, envId, includeSecrets }), { signal }),
  historyCurl: (id: number, includeSecrets = false, signal?: AbortSignal) =>
    req<ActualCurl>(`/api/apitest/execs/${id}/curl?includeSecrets=${includeSecrets}`, undefined, { signal }),
}

export const inspectionReason: Record<string, string> = {
  FIELD_LIMIT: '字段数量达到上限，部分路径未列出',
  DEPTH_LIMIT: '自动展开达到深度上限；可手输路径读取更深字段',
  LARGE_VALUE: '较大值省略全文；类型断言仍可添加',
}
