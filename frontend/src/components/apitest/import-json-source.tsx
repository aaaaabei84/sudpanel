import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { CodeArea } from '@/components/code-area'

// Match ApiInterchange's UTF-16 character budget without rejecting valid UTF-8 Chinese text.
export const MAX_IMPORT_CHARACTERS = 2_000_000
export const MAX_IMPORT_FILE_BYTES = 6_000_000
const HIGHLIGHT_CHARACTERS = 20_000

export async function readImportJson(file: File): Promise<string> {
  if (!file.size) throw new Error('文件为空，请选择包含 JSON 的文件')
  if (file.size > MAX_IMPORT_FILE_BYTES) throw new Error('文件超过 6 MB，请拆分后导入；每份 JSON 最多 200 万字符')
  let text: string
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()) }
  catch { throw new Error('无法读取文件，请确认文件可访问且使用 UTF-8 编码') }
  if (text.length > MAX_IMPORT_CHARACTERS) throw new Error('每份 JSON 最多 200 万字符，请拆分后导入')
  try { JSON.parse(text) } catch { throw new Error('文件不是有效的 JSON，请检查格式后重试') }
  return text
}

export function ImportJsonSource({ id, value, label, session, onChange, onReadingChange }: {
  id: string; value: string; label: string; session: { current: number }; onChange: (value: string) => void; onReadingChange: (reading: boolean) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const generation = useRef(0)
  const [filename, setFilename] = useState<string | null>(null)
  const [reading, setReading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => () => { generation.current++; onReadingChange(false) }, [onReadingChange])
  const cancelRead = () => { generation.current++; setReading(false); onReadingChange(false) }
  const edit = (text: string) => { cancelRead(); setFilename(null); setError(null); onChange(text) }
  const select = async (file: File) => {
    const current = ++generation.current
    const sourceSession = session.current
    const currentRead = () => current === generation.current && sourceSession === session.current
    setReading(true); onReadingChange(true); setError(null)
    try {
      const text = await readImportJson(file)
      if (!currentRead()) return
      onChange(text); setFilename(file.name)
    } catch (failure) {
      if (currentRead()) setError(failure instanceof Error ? failure.message : '读取 JSON 文件失败，请重试')
    } finally {
      if (currentRead()) { setReading(false); onReadingChange(false) }
    }
  }
  return <div className="space-y-2" data-testid={`${id}-source`}>
    <div className="flex flex-wrap items-center gap-2">
      <input ref={input} id={`${id}-file`} type="file" accept=".json,application/json" className="sr-only" aria-label={`选择${label}JSON文件`} onChange={event => {
        const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''
        if (file) void select(file)
      }} />
      <Button type="button" variant="outline" size="sm" onClick={() => input.current?.click()}>选择{label} JSON 文件</Button>
      <span className="text-xs text-muted-foreground">UTF-8；每份最多 200 万字符</span>
      {reading && <><span role="status" className="text-xs">正在读取文件…</span><Button type="button" variant="ghost" size="sm" onClick={cancelRead}>取消读取</Button></>}
    </div>
    {error && <p role="alert" className="text-xs text-red-600">{error}；原内容已保留</p>}
    {filename ? <div className="space-y-2 rounded border p-3 text-sm">
      <p className="break-all" data-testid={`${id}-filename`}>{filename}</p>
      <p className="text-xs text-muted-foreground">已加载 {value.length.toLocaleString()} 字符，完整内容将用于预览和导入。</p>
      <Button type="button" variant="outline" size="sm" onClick={() => { cancelRead(); setFilename(null) }}>编辑文本</Button>
      <Button type="button" variant="ghost" size="sm" onClick={() => edit('')}>清除内容</Button>
    </div> : value.length > HIGHLIGHT_CHARACTERS
      ? <textarea id={id} aria-label={`${label}JSON内容`} className="block h-48 w-full resize-y rounded border bg-background p-3 font-mono text-xs" value={value} onChange={event => edit(event.target.value)} spellCheck={false} />
      : <CodeArea id={id} value={value} formatJson={false} onChange={edit} placeholder="选择 JSON 文件，或粘贴完整 JSON 内容" />}
  </div>
}
