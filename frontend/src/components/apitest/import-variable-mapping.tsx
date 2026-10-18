export interface ImportVariableMapping {
  name: string
  source: 'COLLECTION' | 'ENVIRONMENT'
  enabled: boolean
  secret: boolean
  overridden: boolean
  scopes: string[]
  references: string[]
  valuePreview: string
}

const GROUPS = [['BASE', '地址'], ['HEADER', '请求头'], ['BODY', '请求体']] as const
export function ImportVariableMappingTable({ rows, choices, showSecrets, onChange }: {
  rows: ImportVariableMapping[]
  choices: Record<string, string[]>
  showSecrets: boolean
  onChange: (name: string, scopes: string[]) => void
}) {
  if (!rows.length) return null
  return <section className="space-y-2 rounded border p-3 text-xs">
    <p className="font-medium">变量来源与三组映射 · {rows.length} 条来源记录</p>
    <p className="text-muted-foreground">启用的环境值优先于集合值；未启用和被覆盖的来源不导入。一个变量可同时用于三组，修改映射后须重新预览。</p>
    <div className="max-h-72 overflow-auto"><table className="w-full min-w-[620px] text-left"><thead><tr className="border-b"><th className="p-2">变量 / 来源</th><th className="p-2">值预览</th><th className="p-2">导入到</th><th className="p-2">引用位置</th></tr></thead><tbody>{rows.map((row, i) => {
      const inactive = !row.enabled || row.overridden
      const selected = Object.hasOwn(choices, row.name) ? choices[row.name] : row.scopes
      return <tr key={`${row.name}-${row.source}-${i}`} className="border-b align-top">
        <td className="break-all p-2"><code>{row.name}</code>{row.secret && <span className="ml-1 text-amber-700 dark:text-amber-400">密钥</span>}<p className="text-muted-foreground">{row.source === 'ENVIRONMENT' ? '环境' : '集合'}{!row.enabled ? ' · 未启用' : row.overridden ? ' · 被覆盖' : ' · 生效'}</p></td>
        <td className="max-w-48 break-all p-2"><code>{row.secret && !showSecrets ? '••••••' : row.valuePreview || '（空值）'}</code></td>
        <td className="p-2"><div className="flex flex-wrap gap-2">{GROUPS.map(([key, label]) => <label key={key} className="inline-flex items-center gap-1"><input type="checkbox" aria-label={`${row.name} ${row.source} ${label}`} checked={!inactive && selected.includes(key)} disabled={inactive} onChange={event => onChange(row.name, event.target.checked ? [...selected, key] : selected.filter(v => v !== key))} />{label}</label>)}</div></td>
        <td className="max-w-64 break-all p-2 text-muted-foreground">{row.references.length ? <details><summary className="cursor-pointer">{row.references.length} 处</summary>{row.references.map(path => <code key={path} className="block">{path}</code>)}</details> : '没有直接引用'}</td>
      </tr>
    })}</tbody></table></div>
    <p className="text-muted-foreground">这里只隐藏标记为密钥或名称符合敏感规则的变量。请求、备注与脚本原文仍可能含直接填写的凭据，请在分享前检查。</p>
  </section>
}
