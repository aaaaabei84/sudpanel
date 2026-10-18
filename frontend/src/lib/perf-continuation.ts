import { req } from '@/lib/api'
import type { PerfLoop, PerfRun, PerfStartResponse } from '@/types'

export type CleanupStatus = 'SUCCESS' | 'FAILED' | 'NOT_RECORDED'
export type RoundEvidence = { state: 'UNIQUE' | 'CONFLICT' | 'MISSING' | 'INCOMPLETE' | 'NOT_RECORDED' | 'UNKNOWN'; source?: string; candidateIds?: string[]; matchCount?: number; readCount?: number; firstMatchedAt?: string | null; lastMatchedAt?: string | null; firstLogTimestamp?: string | null; lastLogTimestamp?: string | null; timeBasis?: string; missingReason?: string | null }
export type PerfEvidenceLoop = PerfLoop & { cleanupStatus?: CleanupStatus; cleanupReason?: string | null; evidenceMissing?: boolean | null; planHash?: string | null; roundIdEvidence?: RoundEvidence }
export type PreflightResult = { ready: boolean; checkedAt: string; checks: { key: string; name: string; status: 'PASS' | 'WARN' | 'BLOCK' | 'INFO'; message: string }[] }
export type PerfStartResult = PerfStartResponse & { preflight?: PreflightResult; planState?: string; planCapturedAt?: string; planHash?: string }
export type HistoryFilter = { gameId?: number; mode?: string; status?: string; from?: string; to?: string; cleanupStatus?: string }
export type HistoryPage = { items: PerfRun[]; nextCursor?: string | null; hasMore: boolean }
export type PerfPlan = { state: 'FROZEN' | 'LEGACY_UNKNOWN'; version?: number; origin?: string; capturedAt?: string; hash?: string; mode?: string; gameIds?: number[]; coordinateGroupId?: number | null; settings?: Record<string, unknown>; judgeLimits?: Record<string, string>; effectiveGames?: { gameId: number; name?: string; rosterSource: string; humanCount?: number | null; aiCount?: number | null; gameSeconds?: number }[]; caseSources?: { role: string; caseId: number; name: string; envId: number; envName: string }[]; cookiePolicy?: string; message?: string }
export type ParticipationRules = { startRecordPath?: string; roundIdPath?: string; roomIdPath?: string; gameIdPath?: string; playersPath?: string; playerIdPath?: string; completeRoster?: boolean; capacityPath?: string; capacitySource?: string }
export type SettlementRules = { recordPath: string; roundIdPath: string; roomIdPath?: string; gameIdPath?: string; playersPath?: string; playerIdPath?: string; timestampPath?: string; timestampUnit?: 'SECONDS' | 'MILLIS' | 'ISO' | ''; businessCodePath: string; successCodes: string[]; pendingCodes?: string[]; noDataCodes?: string[]; participantRule?: 'EXACT' | 'SUBSET_ALLOWED' | ''; timeToleranceMs?: number; participation?: ParticipationRules }
export type SettlementResult = { id?: number; status: 'SUCCESS' | 'PENDING' | 'NO_DATA' | 'MISMATCH' | 'UNKNOWN' | 'ERROR'; checks: { field: string; status: string; expected: unknown; actual: unknown; actualTruncated?: boolean; message?: string }[]; warnings: string[]; checkedAt: string; stepId?: number | null; evidenceMissing?: boolean; queryCaseId?: number; sourcePlanHash?: string; semanticsVersion?: number; participantEvidence?: { candidateCount?: number | null; candidateSource?: string; capacity?: number | null; capacitySource?: string; actualParticipantCount?: number | null; actualParticipantSource?: string; settlementCount?: number | null; settlementSource?: string; sourceBoundary?: string; completeRosterConfirmed?: boolean; startIdentityBound?: boolean } }
export const cleanupLabel = (status?: string) => status === 'SUCCESS' ? '结束与清房通过' : status === 'FAILED' ? '清房未全部成功' : '清房未记录'
export const roundLabel = (state?: string) => ({ UNIQUE: '唯一命中', CONFLICT: '多个 ID 冲突', MISSING: '未命中 ID', INCOMPLETE: '日志证据不完整', NOT_RECORDED: '旧来源未记录', UNKNOWN: '来源无法读取' }[state ?? 'NOT_RECORDED'] ?? '来源未知')
export const settlementLabel = (state: string) => ({ SUCCESS: '归属核验通过', PENDING: '尚未结算', NO_DATA: '查无结算数据', MISMATCH: '归属存在不一致', UNKNOWN: '归属尚未验证完整', ERROR: '查询或规则未通过' }[state] ?? state)
export function queryString(values: Record<string, unknown>): string { const query = new URLSearchParams(); for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== null && value !== '') query.set(key, String(value)); const text = query.toString(); return text ? `?${text}` : '' }
export const perfContinuation = {
  run: (id: number, signal?: AbortSignal) => req<PerfRun>(`/api/perf/runs/${id}`, undefined, { signal }),
  history: (filter: HistoryFilter = {}, cursor?: string | null, signal?: AbortSignal) => req<HistoryPage>(`/api/perf/runs/page${queryString({ ...filter, cursor, limit: 30 })}`, undefined, { signal }),
  active: (signal?: AbortSignal) => req<PerfRun[]>('/api/perf/runs/active', undefined, { signal }),
  plan: (runId: number, signal?: AbortSignal) => req<PerfPlan>(`/api/perf/runs/${runId}/plan`, undefined, { signal }),
  resume: (runId: number, strategy: 'FROZEN' | 'CAPTURE_CURRENT') => req<PerfStartResult>(`/api/perf/runs/${runId}/resume?strategy=${strategy}`, { method: 'POST' }),
  loops: (runId: number, gameId: number, cleanupStatus?: string, signal?: AbortSignal) => req<PerfEvidenceLoop[]>(`/api/perf/runs/${runId}/games/${gameId}/loops${queryString({ cleanupStatus })}`, undefined, { signal }),
  settlementHistory: (loopId: number, signal?: AbortSignal) => req<SettlementResult[]>(`/api/perf/loops/${loopId}/settlement-checks`, undefined, { signal }),
  settlement: (loopId: number, caseId: number, rules: SettlementRules) => req<SettlementResult>(`/api/perf/loops/${loopId}/settlement-check`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ caseId, rules }) }),
  samplesUrl: (runId: number, gameId?: number | null, loopId?: number | null) => `/api/perf/runs/${runId}/samples.csv${queryString({ gameId, loopId })}`,
  zipUrl: (runId: number, gameId?: number | null) => `/api/perf/runs/${runId}/export.zip${queryString({ gameId })}`,
}
