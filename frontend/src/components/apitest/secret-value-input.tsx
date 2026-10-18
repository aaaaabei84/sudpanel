import { useState, type ComponentProps } from 'react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

export function SecretValueInput({ value, onChange, secret = true, ...props }: Omit<ComponentProps<typeof Input>, 'value' | 'onChange' | 'type'> & { value: string; onChange: (value: string) => void; secret?: boolean }) {
  const [shown, setShown] = useState(false)
  return <span className="flex min-w-0 flex-1 items-center gap-1">
    <Input {...props} autoComplete="off" spellCheck={false} type={secret && !shown ? 'password' : 'text'} value={value} onChange={e => onChange(e.target.value)} />
    {secret && <Button type="button" size="sm" variant="ghost" disabled={props.disabled} aria-label={shown ? '隐藏敏感值' : '显示敏感值'} onClick={() => setShown(v => !v)}>{shown ? '隐藏' : '显示'}</Button>}
  </span>
}
