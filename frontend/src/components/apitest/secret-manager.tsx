import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { req } from '@/lib/api'
const jsonInit = (method: string, body: unknown): RequestInit => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
import { useDirtyGuard } from '@/lib/navigation-guard'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { SecretValueInput } from './secret-value-input'

type Secret = { id: string; name: string; kind: string; reference: string; enabled: boolean }
export function SecretManager({ envId }: { envId: number }) {
  const [open, setOpen] = useState(false), [busy, setBusy] = useState(false), [rows, setRows] = useState<Secret[]>([])
  const [name, setName] = useState(''), [kind, setKind] = useState('VAULT'), [value, setValue] = useState(''), [service, setService] = useState(''), [account, setAccount] = useState('')
  const [error, setError] = useState(''), [discard, setDiscard] = useState(false)
  const generation = useRef(0), owner = useRef(false)
  const dirty = !!(name || value || service || account)
  const clear = () => { setName(''); setValue(''); setService(''); setAccount(''); setDiscard(false) }
  useDirtyGuard({ label: '本机秘密引用草稿', dirty: open && dirty, locked: busy, discard: clear })
  useEffect(() => { ++generation.current; setRows([]); clear(); setOpen(false); setError(''); return () => { ++generation.current } }, [envId])
  const load = async () => {
    const seq = ++generation.current; setError('')
    try { const result = await req<Secret[]>(`/api/apitest/secrets?envId=${envId}`); if (seq === generation.current) setRows(result) }
    catch (e) { if (seq === generation.current) setError((e as Error).message) }
  }
  const change = async (row?: Secret) => {
    if (owner.current) return
    owner.current = true; setBusy(true); const seq = generation.current
    try {
      if (row) await req(`/api/apitest/secrets/${row.id}/enabled?envId=${envId}`, jsonInit('POST', { enabled: !row.enabled }))
      else await req(`/api/apitest/secrets?envId=${envId}`, jsonInit('POST', { name, kind, value: kind === 'VAULT' ? value : null, service: kind === 'KEYCHAIN' ? service : null, account: kind === 'KEYCHAIN' ? account : null }))
      if (seq === generation.current) { clear(); await load(); toast.success(row ? '秘密引用状态已保存' : '秘密引用已加密保存') }
    } catch (e) { if (seq === generation.current) setError((e as Error).message) }
    finally { owner.current = false; setBusy(false) }
  }
  return <>
    <Button type="button" size="sm" variant="outline" onClick={() => { setOpen(true); void load() }}>本机秘密引用</Button>
    <Dialog open={open} onOpenChange={v => { if (busy) return; if (!v && dirty) { setDiscard(true); return }; setOpen(v); if (!v) { ++generation.current; clear() } }}><DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl"><DialogHeader><DialogTitle>当前环境的本机秘密引用</DialogTitle></DialogHeader>
      <p className="text-xs text-muted-foreground">在变量表勾选“敏感”，把复制的 secret:// 引用填入值；应用密钥也可直接填引用。环境绑定，缺失或停用会阻止发送。本机加密库随配对备份恢复；Keychain 只引用已有密码项，不修改系统凭据。</p>
      {discard && <div role="alert" className="space-y-2 rounded border p-2 text-xs">新引用尚未保存。<div className="flex gap-2"><Button size="sm" variant="outline" onClick={() => setDiscard(false)}>继续编辑</Button><Button size="sm" onClick={() => { clear(); ++generation.current; setOpen(false) }}>放弃新引用</Button></div></div>}
      {error && <p role="alert" className="break-words text-xs text-red-600">{error}</p>}
      <div className="space-y-2 rounded border p-3">
        <Input aria-label="秘密名称" placeholder="名称（不填秘密值）" maxLength={100} disabled={busy} value={name} onChange={e => setName(e.target.value)} />
        <select aria-label="秘密来源" className="h-9 w-full rounded border bg-background px-2" value={kind} disabled={busy} onChange={e => { setKind(e.target.value); setValue(''); setService(''); setAccount('') }}><option value="VAULT">本机加密库</option><option value="KEYCHAIN">已有 macOS Keychain 密码</option></select>
        {kind === 'VAULT' ? <SecretValueInput aria-label="新秘密值" placeholder="秘密值，仅保存时发送到本机" disabled={busy} value={value} onChange={setValue} /> : <><Input aria-label="Keychain服务" placeholder="已有项的服务名称" disabled={busy} value={service} onChange={e => setService(e.target.value)} /><Input aria-label="Keychain账号" placeholder="已有项的账号" disabled={busy} value={account} onChange={e => setAccount(e.target.value)} /></>}
        <Button type="button" size="sm" disabled={busy || !name.trim() || (kind === 'VAULT' ? !value : !service.trim() || !account.trim())} onClick={() => void change()}>创建引用并保存</Button>
      </div>
      <div className="space-y-2">{rows.map(row => <div key={row.id} className="flex flex-wrap items-center gap-2 rounded border p-2 text-xs"><span className="min-w-0 flex-1 break-all">{row.name} · {row.kind} · {row.enabled ? '已启用' : '已停用'}</span><Button type="button" size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(row.reference).then(() => toast.success('已复制引用')).catch(() => toast.error('复制失败'))}>复制引用</Button><Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void change(row)}>{row.enabled ? '停用' : '启用'}</Button></div>)}{rows.length === 0 && <p className="text-xs text-muted-foreground">当前环境还没有秘密引用。</p>}</div>
    </DialogContent></Dialog>
  </>
}
