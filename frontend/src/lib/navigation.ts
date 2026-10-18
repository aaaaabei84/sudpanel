export type PanelPage = 'dashboard' | 'verify' | 'apptomg' | 'mgtoapp' | 'perf' | 'apitest' | 'logcat'
export interface PanelLocation { page: PanelPage; batchId?: number; runId?: number; gameId?: number }
const pages = new Set<PanelPage>(['dashboard', 'verify', 'apptomg', 'mgtoapp', 'perf', 'apitest', 'logcat'])
export function parsePanelLocation(hash: string): PanelLocation {
  const [rawPage, query = ''] = hash.replace(/^#\/?/, '').split('?', 2)
  const page = pages.has(rawPage as PanelPage) ? rawPage as PanelPage : 'dashboard'
  const params = new URLSearchParams(query)
  const id = (name: string) => { const value = params.get(name); return value && /^[1-9]\d{0,14}$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : undefined }
  return { page, ...(page === 'apitest' ? { batchId: id('batchId') } : page === 'perf' ? { runId: id('runId'), gameId: id('gameId') } : {}) }
}
export function panelHash(location: PanelLocation): string {
  const query = new URLSearchParams()
  for (const key of location.page === 'apitest' ? ['batchId'] as const : location.page === 'perf' ? ['runId', 'gameId'] as const : []) {
    const id = location[key]; if (id && Number.isSafeInteger(id) && id > 0) query.set(key, String(id))
  }
  return `#/${location.page}${query.size ? '?' + query : ''}`
}
const HISTORY_INDEX = 'sudpanelNavigationIndex'
export function panelHistoryIndex(): number | undefined {
  const value = window.history.state?.[HISTORY_INDEX]
  return Number.isSafeInteger(value) ? value : undefined
}
export function ensurePanelHistory(): number {
  const index = panelHistoryIndex() ?? 0
  window.history.replaceState({ ...window.history.state, [HISTORY_INDEX]: index }, '', window.location.href)
  return index
}
export function updatePanelLocation(location: PanelLocation, replace = false): void {
  const hash = panelHash(location)
  if (window.location.hash === hash) return
  const index = ensurePanelHistory() + (replace ? 0 : 1)
  window.history[replace ? 'replaceState' : 'pushState']({ ...window.history.state, [HISTORY_INDEX]: index }, '', hash)
  window.dispatchEvent(new Event('sudpanel:navigate'))
}
export function replacePanelSelection(page: PanelPage, selection: Pick<PanelLocation, 'batchId' | 'runId' | 'gameId'>): void {
  if (parsePanelLocation(window.location.hash).page === page) updatePanelLocation({ page, ...selection }, true)
}
