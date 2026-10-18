import { SYSTEM_SUGGESTIONS } from '@/lib/apitest-workspace'
import type { ApiVarSet } from '@/types'
import type { VarSuggestion } from '@/lib/var-suggest'

export function VariableHelp({ vars, systemVariables = SYSTEM_SUGGESTIONS }: { vars?: ApiVarSet; systemVariables?: VarSuggestion[] }) {
  const conflicts = vars ? [...vars.base, ...vars.header, ...vars.body].filter(v => ['times', 'app_id', 'mg_id', 'nonce', 'sign'].includes(v.name.trim())).map(v => v.name.trim()) : []
  return <details className="rounded border bg-muted/20 p-2 text-xs">
    <summary className="cursor-pointer font-medium">动态变量与请求链变量</summary>
    <div className="mt-2 grid gap-1 sm:grid-cols-2">{systemVariables.map(v => <div key={v.name}><code className="text-fuchsia-700 dark:text-fuchsia-300">{'{{' + v.name + '}}'}</code><span className="ml-2 text-muted-foreground">{v.group}</span></div>)}</div>
    <p className="mt-2 text-muted-foreground">系统变量不可被同名自定义变量覆盖。动态值在发送时生成；预览与实际发送的时间戳或随机数可能不同。旧写法仍兼容，签名变量的作用范围以环境说明为准。</p>
    <p className="mt-2 text-muted-foreground">基址变量仅用于地址；地址同名时基址优先于请求头变量。正文/表单与请求头各用自己的变量组。预览中的变量检查会列出位置、来源和相近名称。</p>
    <p className="mt-2 text-muted-foreground">需要发送字面双花括号时写 <code>{'{{!name}}'}</code>，实际发送 <code>{'{{name}}'}</code>；此写法也保护系统、请求链和旧签名变量。请求级严格设置优先于环境，旧环境未设置时保持警告兼容。</p>
    {conflicts.length > 0 && <p role="alert" className="mt-2 text-amber-700 dark:text-amber-400">同名旧变量：{[...new Set(conflicts)].join('、')}。请检查实际取值，时间戳建议改为 {'{{$timestamp}}'}。</p>}
  </details>
}
