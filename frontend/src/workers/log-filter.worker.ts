import { makeAdvancedLogFilter, type AdvancedLogFilter } from '../lib/advanced-log-filter'
interface Job { id: number; base: number; lines: string[]; reset: boolean; config: AdvancedLogFilter }
let buffer: string[] = [], base = 0, generation = 0
self.onmessage = (event: MessageEvent<Job>) => {
  const job = event.data, own = ++generation
  buffer = job.reset ? job.lines : buffer.slice(Math.max(0, job.base - base)).concat(job.lines)
  base = job.base
  const lines = buffer, ownBase = base
  let match: (line: string) => boolean
  try { match = makeAdvancedLogFilter(job.config) }
  catch (error) { self.postMessage({ id: job.id, error: error instanceof Error ? error.message : String(error) }); return }
  let offset = 0
  const indexes: number[] = []
  const next = () => {
    if (generation !== own) return
    const end = Math.min(offset + 2000, lines.length)
    for (; offset < end; offset++) if (match(lines[offset])) indexes.push(ownBase + offset)
    if (offset < lines.length) setTimeout(next, 0)
    else self.postMessage({ id: job.id, indexes })
  }
  next()
}
