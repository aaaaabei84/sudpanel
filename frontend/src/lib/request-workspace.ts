import type { ApiCase } from '@/types'
import type { RoundQueryContext } from './apitest-workspace'
import { blankRequest } from './apitest-workspace'
export interface RequestTab { id: string; title: string; draft: Omit<ApiCase, 'id'>; envId: string; sourceContext: RoundQueryContext | null; dirty?: boolean }
export interface RequestWorkspace { version: 1; tabs: RequestTab[]; closed: RequestTab[]; activeId: string }
export function requestTab(): RequestTab { return { id: crypto.randomUUID(), title: '', draft: { ...blankRequest(), options: { ...blankRequest().options, strictVariables: true } }, envId: '', sourceContext: null, dirty: false } }
export function emptyWorkspace(): RequestWorkspace { const tab = requestTab(); return { version: 1, tabs: [tab], closed: [], activeId: tab.id } }
export function readWorkspace(content: string | null): RequestWorkspace {
  if (!content) return emptyWorkspace()
  const value = JSON.parse(content) as RequestWorkspace
  if (value.version !== 1 || !Array.isArray(value.tabs) || value.tabs.length < 1 || value.tabs.length > 12 || !Array.isArray(value.closed) || value.closed.length > 12 || !value.tabs.some(tab => tab.id === value.activeId)) throw new Error('保存的工作区结构无法读取，请保留原数据后重试')
  for (const tab of [...value.tabs, ...value.closed]) if (!tab.id || !tab.draft || typeof tab.envId !== 'string') throw new Error('保存的请求标签结构无效')
  return value
}
