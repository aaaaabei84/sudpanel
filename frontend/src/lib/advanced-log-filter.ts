import { makeLogFilter, parseLogLine, splitMeta } from './log-line'
export interface AdvancedLogFilter { keyword: string; levels: string[]; includes: string[]; mode: 'AND' | 'OR'; excludes: string[]; regex: string; tag: string; pid: string }
export function makeAdvancedLogFilter(config: AdvancedLogFilter): (line: string) => boolean {
  if (config.regex.length > 4096) throw new Error('正则不能超过 4096 字符')
  if (config.pid && !/^\d+(?:[,\s]+\d+)*$/.test(config.pid.trim())) throw new Error('PID 请填写数字，多个 PID 用逗号或空格分隔')
  const expression = config.regex ? new RegExp(config.regex) : null
  const base = makeLogFilter(config.keyword, config.levels)
  const include = config.includes.map(s => s.trim()).filter(Boolean)
  const exclude = config.excludes.map(s => s.trim()).filter(Boolean)
  const pids = new Set(config.pid.trim().split(/[,\s]+/).filter(Boolean))
  return line => {
    if (!base(line) || exclude.some(term => line.includes(term))) return false
    if (include.length && !(config.mode === 'AND' ? include.every(term => line.includes(term)) : include.some(term => line.includes(term)))) return false
    if (expression && !expression.test(line)) return false
    const meta = (config.tag || pids.size) ? splitMeta(parseLogLine(line).meta) : null
    // 非标准续行没有可验证的 PID/Tag，保留原文且仍应用关键词和正则。
    return !meta || ((!config.tag || meta.tag === config.tag) && (!pids.size || pids.has(meta.pid)))
  }
}
