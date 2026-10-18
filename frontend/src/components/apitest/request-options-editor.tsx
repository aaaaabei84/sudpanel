import { FileBodyEditor } from './file-body-editor'
import { useMemo, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { HttpProfileEditor, CookieSessionPanel } from './http-profile-editor'
import { VarInput } from '@/components/var-input'
import { emptyOptions, SYSTEM_SUGGESTIONS } from '@/lib/apitest-workspace'
import type { ApiAuth, ApiExtractor, ApiRequestOptions } from '@/types'
import type { VarSuggestion } from '@/lib/var-suggest'

const selectClass = 'h-9 rounded-md border bg-background px-2 text-sm'
const SCRIPT_LIMIT_BYTES = 64 * 1024
const AUTHORIZATION_TEMPLATE_EXAMPLE = 'Sud-Auth app_id="{{app_id}}",timestamp="{{times}}",nonce="{{nonce}}",signature="{{sign}}"'
const SCRIPT_EXAMPLE = String.raw`// 文本正文签名示例；从当前选中环境的变量中读取 app_id 和 app_secret。
const CryptoJS = require('crypto-js');
const appId = pm.environment.get('app_id');
const secret = pm.environment.get('app_secret');
if (!appId || !secret) throw new Error('缺少 app_id 或 app_secret 变量');
const times = String(Math.floor(Date.now() / 1000));
const nonce = String(Date.now()) + '-' + Math.random().toString(36).slice(2);
const body = pm.request.body.raw || '';
const payload = appId + '\n' + times + '\n' + nonce + '\n' + body + '\n';
const sign = CryptoJS.HmacSHA1(payload, secret).toString(CryptoJS.enc.Hex);
pm.globals.set('app_id', appId);
pm.globals.set('sign', sign);
pm.globals.set('nonce', nonce);
pm.environment.set('times', times);
// 下方独立的 Authorization 模板可以引用这些本次请求变量。`

export function ExtractorEditor({ rows, onChange }: { rows: ApiExtractor[]; onChange: (rows: ApiExtractor[]) => void }) {
  const patch = (i: number, p: Partial<ApiExtractor>) => onChange(rows.map((r, k) => k === i ? { ...r, ...p } : r))
  return <div className="space-y-2">
    <div className="flex items-center justify-between gap-2">
      <Label>响应提取</Label>
      <Button type="button" size="sm" variant="outline" onClick={() => onChange([...rows, { name: '', source: 'JSON', path: '', required: true }])}><Plus className="size-3.5" />添加提取</Button>
    </div>
    <p className="text-xs text-muted-foreground">写入本轮变量，后续请求使用 {'{{run.变量名}}'}。每轮重新开始；失败不沿用旧值。</p>
    {rows.length === 0 && <p className="text-xs text-muted-foreground">未设置提取规则，可从响应字段选择添加。</p>}
    {rows.map((row, i) => <div key={i} className="grid items-center gap-2 rounded border p-2 sm:grid-cols-[110px_1fr_1fr_auto_auto_auto]">
      <select aria-label={`提取来源 ${i + 1}`} className={selectClass} value={row.source} onChange={e => patch(i, { source: e.target.value as ApiExtractor['source'] })}><option value="JSON">响应 JSON</option><option value="HEADER">响应头</option></select>
      <Input aria-label={`提取路径 ${i + 1}`} className="font-mono text-xs" placeholder={row.source === 'JSON' ? 'data.token' : 'X-Request-Id'} value={row.path} onChange={e => patch(i, { path: e.target.value })} />
      <Input aria-label={`变量名 ${i + 1}`} className="font-mono text-xs" placeholder="变量名，如 token" value={row.name} onChange={e => patch(i, { name: e.target.value })} />
      <label className="flex items-center gap-1 text-xs"><Checkbox checked={row.required} onCheckedChange={v => patch(i, { required: v === true })} />必需</label>
      <label className="flex items-center gap-1 text-xs"><Checkbox aria-label={`敏感提取 ${i + 1}`} checked={row.secret === true} onCheckedChange={v => patch(i,{secret:v === true})} />敏感</label>
      <Button type="button" size="icon" variant="ghost" aria-label={`删除提取 ${i + 1}`} onClick={() => onChange(rows.filter((_, k) => k !== i))}><Trash2 className="size-3.5" /></Button>
    </div>)}
  </div>
}

export function RequestOptionsEditor({ value, onChange, suggestions = SYSTEM_SUGGESTIONS, paramSuggestions, headers, envId, section = 'all' }: {
  value?: ApiRequestOptions | null
  onChange: (value: ApiRequestOptions) => void
  suggestions?: VarSuggestion[]
  paramSuggestions?: VarSuggestion[]
  headers?: string | null
  envId?: number
  section?: 'all' | 'params' | 'body' | 'auth' | 'extractors' | 'transport'
}) {
  const [showAuth,setShowAuth]=useState(false)
  const [showScript, setShowScript] = useState(false)
  const [showTemplate, setShowTemplate] = useState(false)
  const options = value ?? emptyOptions()
  const auth = options.auth ?? { type: 'INHERIT' }
  const scriptBytes = useMemo(() => new TextEncoder().encode(auth.script ?? '').length, [auth.script])
  const params = options.params ?? []
  const fields = options.fields ?? []
  const transport = options.transport ?? {}
  const patch = (p: Partial<ApiRequestOptions>) => onChange({ ...options, ...p })
  const patchAuth = (p: Partial<ApiAuth>) => patch({ auth: { ...auth, ...p } })
  const shown = (s: string) => section === 'all' || section === s
  let formContentTypeConflict = false
  if (options.bodyType === 'URLENCODED' && headers) {
    try {
      const parsed: unknown = JSON.parse(headers)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const entry = Object.entries(parsed).find(([name]) => name.toLowerCase() === 'content-type')
        formContentTypeConflict = !!entry && String(entry[1]).split(';', 1)[0].trim().toLowerCase() !== 'application/x-www-form-urlencoded'
      }
    } catch { /* 请求头格式由预览统一校验。 */ }
  }
  return <div className="space-y-4">
    {shown('body') && <div className="flex flex-wrap items-center gap-2"><Label>请求体类型</Label><select aria-label="请求体类型" className={selectClass} value={options.bodyType ?? 'LEGACY'} onChange={e => patch({ bodyType: e.target.value === 'LEGACY' ? null : e.target.value as ApiRequestOptions['bodyType'] })}><option value="LEGACY">兼容原设置</option><option value="JSON">JSON</option><option value="RAW">原始文本</option><option value="URLENCODED">URL 编码表单</option><option value="MULTIPART">multipart文件/文本</option><option value="BINARY">binary原字节文件</option><option value="NONE">无请求体</option></select><span className="text-xs text-muted-foreground">表单由字段生成最终编码正文；原始文本保留填写的内容。</span></div>}
    {shown('body') && options.bodyType === 'URLENCODED' && <div className="space-y-2">
      <div className="flex items-center justify-between"><Label>表单字段</Label><Button type="button" size="sm" variant="outline" onClick={() => patch({ fields: [...fields, { name: '', value: '', enabled: true }] })}><Plus className="size-3.5" />添加字段</Button></div>
      <p className="text-xs text-muted-foreground">支持重复字段名、空值和请求体变量；平台先编码，再签名。下方原始正文在表单模式下不参与发送。</p>
      {formContentTypeConflict && <p role="alert" className="text-xs text-amber-700 dark:text-amber-400">手填 Content-Type 与 URL 编码表单冲突。平台会保留手填类型，请核对请求头或先预览。</p>}
      {fields.map((row, i) => <div key={i} className="flex items-center gap-2">
        <Checkbox aria-label={`启用表单 ${i + 1}`} checked={row.enabled !== false} onCheckedChange={v => patch({ fields: fields.map((r, k) => k === i ? { ...r, enabled: v === true } : r) })} />
        <Input aria-label={`表单名 ${i + 1}`} placeholder="字段名" value={row.name} onChange={e => patch({ fields: fields.map((r, k) => k === i ? { ...r, name: e.target.value } : r) })} />
        <VarInput aria-label={`表单值 ${i + 1}`} placeholder="字段值" value={row.value} suggestions={suggestions} onChange={v => patch({ fields: fields.map((r, k) => k === i ? { ...r, value: v } : r) })} />
        <Button type="button" size="icon" variant="ghost" aria-label={`删除表单 ${i + 1}`} onClick={() => patch({ fields: fields.filter((_, k) => k !== i) })}><Trash2 className="size-3.5" /></Button>
      </div>)}
    </div>}
    {shown('body') && (options.bodyType === 'MULTIPART' || options.bodyType === 'BINARY') && <FileBodyEditor key={options.bodyType} value={options} onChange={onChange} />}
    {shown('params') && <div className="space-y-2">
      <div className="flex items-center justify-between"><Label>查询参数</Label><Button type="button" size="sm" variant="outline" onClick={() => patch({ params: [...params, { name: '', value: '', enabled: true }] })}><Plus className="size-3.5" />添加参数</Button></div>
      <p className="text-xs text-muted-foreground">按此顺序追加到地址，允许重复参数名。值由平台编码，支持变量。</p>
      {params.map((row, i) => <div key={i} className="flex items-center gap-2">
        <Checkbox aria-label={`启用参数 ${i + 1}`} checked={row.enabled} onCheckedChange={v => patch({ params: params.map((r, k) => k === i ? { ...r, enabled: v === true } : r) })} />
        <Input aria-label={`参数名 ${i + 1}`} placeholder="参数名" value={row.name} onChange={e => patch({ params: params.map((r, k) => k === i ? { ...r, name: e.target.value } : r) })} />
        <VarInput aria-label={`参数值 ${i + 1}`} placeholder="参数值" value={row.value} suggestions={paramSuggestions ?? suggestions} onChange={v => patch({ params: params.map((r, k) => k === i ? { ...r, value: v } : r) })} />
        <Button type="button" size="icon" variant="ghost" aria-label={`删除参数 ${i + 1}`} onClick={() => patch({ params: params.filter((_, k) => k !== i) })}><Trash2 className="size-3.5" /></Button>
      </div>)}
    </div>}
    {shown('auth') && <div className="space-y-2">
      <div className="flex items-center gap-2"><Label>鉴权方式</Label><select aria-label="鉴权方式" className={selectClass} value={auth.type} onChange={e => { setShowScript(false); setShowTemplate(false); patchAuth({ type: e.target.value as ApiAuth['type'] }) }}><option value="INHERIT">继承环境签名</option><option value="NONE">不自动添加鉴权</option><option value="SUD">业务签名</option><option value="BEARER">Bearer Token</option><option value="BASIC">Basic</option><option value="API_KEY">API Key</option><option value="OAUTH2">OAuth2 受管令牌</option><option value="DIGEST">Digest 有限握手</option><option value="SCRIPT">签名脚本（SCRIPT）</option><option value="TEMPLATE">Authorization 请求模板（TEMPLATE）</option></select></div>
      {auth.type === 'INHERIT' && <p className="text-xs text-muted-foreground">按本次执行环境的签名开关处理。选用其他方式会覆盖请求头里的同类鉴权。</p>}
      {auth.type === 'NONE' && <p className="text-xs text-muted-foreground">不生成自动鉴权；手填的 Authorization 请求头仍会发送。</p>}
      {auth.type === 'SUD' && <p className="text-xs text-muted-foreground">使用执行环境的业务签名凭证，签名覆盖最终发出的请求体。</p>}
      {auth.type === 'SCRIPT' && <section className="space-y-2 rounded border p-3 text-xs" data-testid="script-auth-editor">
        <h4 className="font-medium">签名脚本</h4><p>本请求显式使用脚本鉴权，替代内置和环境自动鉴权。请在脚本中按接口契约设置请求头或文本正文；脚本失败或超时会阻止发送。</p>
        <p className="text-muted-foreground">脚本读取当前选中环境的变量快照；修改仅对本次请求有效，不回写环境或全局变量库。不读取旧环境签名开关，也不回落到旧签名区密钥。支持有限的 Postman 风格接口，不是完整 Postman 脚本环境。</p>
        <label className="flex items-center gap-2"><input type="checkbox" aria-label="显示并编辑签名脚本" checked={showScript} onChange={event => setShowScript(event.target.checked)} />显示并编辑签名脚本（原文可能包含密钥）</label>
        <p data-testid="script-source-size">源码 {scriptBytes} / {SCRIPT_LIMIT_BYTES} 字节（UTF-8）</p>
        {showScript ? <textarea data-testid="script-source-input" aria-label="JavaScript 签名源码" aria-describedby="script-auth-budget" aria-invalid={scriptBytes > SCRIPT_LIMIT_BYTES} spellCheck={false} className="min-h-56 w-full resize-y rounded border bg-background p-3 font-mono text-xs" value={auth.script ?? ''} onChange={event => patchAuth({ script: event.target.value })} placeholder="填写发送前签名脚本；不会自动运行示例" /> : <p className="text-muted-foreground">{auth.script ? '脚本原文已隐藏；切换鉴权方式仍会保留草稿。' : '尚未填写脚本，请先显示编辑区或使用下方示例。'}</p>}
        <p id="script-auth-budget" className="text-muted-foreground">源码最多 64KiB UTF-8。编辑不截断原文；保存与发送由平台再次校验。源码不自动替换变量，请使用 pm.variables.replaceIn。</p>
        {scriptBytes > SCRIPT_LIMIT_BYTES && <p role="alert" className="text-red-600" data-testid="script-source-limit">脚本源码超过 64KiB，原文已保留；请缩短后再保存或发送。</p>}
        <details className="space-y-2"><summary className="cursor-pointer">支持接口与文本正文签名示例</summary><p className="mt-2 text-muted-foreground">require('crypto-js')；pm.environment.get/set、pm.globals.get/set、pm.variables.replaceIn；pm.request.headers.upsert/add/remove；pm.request.body.update。示例只供文本请求核对，密钥从选中环境的普通变量读取，请按服务端签名契约核对算法与拼接顺序。</p><pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded border p-2">{SCRIPT_EXAMPLE}</pre><Button type="button" size="sm" variant="outline" data-testid="script-fill-example" disabled={!!auth.script?.trim()} onClick={() => { patchAuth({ script: SCRIPT_EXAMPLE }); setShowScript(true) }}>填入示例（仅空脚本）</Button><p className="text-muted-foreground">非空草稿不会被示例覆盖；填入仅编辑源码，不发出请求。</p></details>
      </section>}
      {(auth.type === 'SCRIPT' || auth.type === 'TEMPLATE') && <section className="space-y-2 rounded border p-3 text-xs" data-testid="authorization-template-editor">
        <h4 className="font-medium">Authorization 模板{auth.type === 'SCRIPT' ? '（可选）' : '（必填）'}</h4>
        <p>{auth.type === 'SCRIPT' ? '留空沿用脚本设置或原请求头；填写后替换变量并作为本次请求最终 Authorization。' : '仅替换模板并设置本次请求 Authorization，不执行脚本，不使用旧环境签名开关或签名区密钥。'}</p>
        <label className="flex items-center gap-2"><input type="checkbox" aria-label="显示并编辑Authorization模板" checked={showTemplate} onChange={event => setShowTemplate(event.target.checked)} />显示并编辑模板（原文可能包含凭据）</label>
        {showTemplate ? <VarInput aria-label="Authorization 模板" suggestions={suggestions} value={auth.authorizationTemplate ?? ''} placeholder="例如 Bearer {{token}}，或展开下方签名模板示例" onChange={value => patchAuth({ authorizationTemplate: value })} /> : <Input type="password" aria-label="Authorization 模板" value={auth.authorizationTemplate ?? ''} placeholder={auth.type === 'SCRIPT' ? '可选，留空沿用脚本或原请求头' : '必填，请填写请求头模板'} onChange={event => patchAuth({ authorizationTemplate: event.target.value })} />}
        <p className="text-muted-foreground">支持 {'{{sign}}'}、{'{{nonce}}'}、{'{{times}}'} 等已有变量写法。SCRIPT 可引用脚本本次生成的值；TEMPLATE 需从所选环境或本次请求变量中已有值取用。这里配置只属于当前请求。</p>
        {auth.type === 'TEMPLATE' && !auth.authorizationTemplate?.trim() && <p role="alert" className="text-red-600" data-testid="authorization-template-required">Authorization 模板不能为空；请填写后再保存或发送。</p>}
        <details><summary className="cursor-pointer">Authorization 模板示例</summary><pre className="mt-2 overflow-auto whitespace-pre-wrap break-words rounded border p-2">{AUTHORIZATION_TEMPLATE_EXAMPLE}</pre><Button type="button" size="sm" variant="outline" className="mt-2" data-testid="authorization-fill-example" disabled={!!auth.authorizationTemplate?.trim()} onClick={() => { patchAuth({ authorizationTemplate: AUTHORIZATION_TEMPLATE_EXAMPLE }); setShowTemplate(true) }}>填入模板示例（仅空模板）</Button></details>
      </section>}
      {['BEARER','BASIC','API_KEY'].includes(auth.type) && <label className="flex gap-2 text-xs"><input type="checkbox" checked={showAuth} onChange={e=>setShowAuth(e.target.checked)}/>显示鉴权值</label>}
      {auth.type === 'BEARER' && (showAuth ? <VarInput aria-label="Bearer Token" value={auth.token ?? ''} suggestions={suggestions} placeholder="Token 或 {{run.token}}" onChange={v => patchAuth({ token: v })} /> : <Input aria-label="Bearer Token" type="password" value={auth.token ?? ''} onChange={e=>patchAuth({token:e.target.value})}/>) }
      {auth.type === 'BASIC' && <div className="grid gap-2 sm:grid-cols-2"><VarInput aria-label="Basic 用户名" value={auth.username ?? ''} suggestions={suggestions} placeholder="用户名" onChange={v => patchAuth({ username: v })} /><Input aria-label="Basic 密码" type={showAuth?"text":"password"} value={auth.password ?? ''} placeholder="密码" onChange={e => patchAuth({ password: e.target.value })} /></div>}
      {auth.type === 'API_KEY' && <div className="grid gap-2 sm:grid-cols-[120px_1fr_1fr]"><select aria-label="API Key 位置" className={selectClass} value={auth.in ?? 'HEADER'} onChange={e => patchAuth({ in: e.target.value as 'HEADER' | 'QUERY' })}><option value="HEADER">请求头</option><option value="QUERY">查询参数</option></select><Input aria-label="API Key 名称" placeholder="键名" value={auth.keyName ?? ''} onChange={e => patchAuth({ keyName: e.target.value })} />{showAuth ? <VarInput aria-label="API Key 值" suggestions={auth.in === 'QUERY' ? paramSuggestions ?? suggestions : suggestions} placeholder="值或变量" value={auth.keyValue ?? ''} onChange={v=>patchAuth({keyValue:v})} /> : <Input type="password" aria-label="API Key 值" placeholder="值或变量" value={auth.keyValue ?? ''} onChange={e => patchAuth({ keyValue: e.target.value })} />}</div>}
    </div>}
    {(shown('auth') && (auth.type === 'OAUTH2' || auth.type === 'DIGEST')) && <HttpProfileEditor envId={envId} kind={auth.type} profileId={auth.profileId} onSelect={profileId => patchAuth({profileId})} />}
    {shown('extractors') && <ExtractorEditor rows={options.extractors ?? []} onChange={extractors => patch({ extractors })} />}
    {shown('transport') && <div className="space-y-3 rounded border p-3">
      <Label>会话与传输</Label>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="space-y-1 text-xs">重定向<select aria-label="重定向" className={`${selectClass} w-full`} value={transport.redirect ?? 'NORMAL'} onChange={e => patch({ transport: { ...transport, redirect: e.target.value as 'NORMAL' | 'NEVER' | 'ALWAYS' } })}><option value="NORMAL">自动跟随（默认）</option><option value="NEVER">不跟随，查看原始状态</option><option value="ALWAYS">允许HTTPS降级（仍隔离跨来源鉴权）</option></select></label>
        <label className="space-y-1 text-xs">Cookie 会话<select aria-label="Cookie 会话" className={`${selectClass} w-full`} value={transport.cookieMode ?? 'OFF'} onChange={e => patch({ transport: { ...transport, cookieMode: e.target.value as 'OFF' | 'ENV' | 'CHAIN', cookieSession: e.target.value === 'CHAIN' ? transport.cookieSession ?? crypto.randomUUID() : transport.cookieSession } })}><option value="OFF">关闭自动 Cookie（默认）</option><option value="ENV">当前环境会话</option><option value="CHAIN">本次请求链（独立UUID）</option></select></label>
        <label className="space-y-1 text-xs">代理<select aria-label="代理模式" className={`${selectClass} w-full`} value={transport.proxyMode ?? 'INHERIT'} onChange={e => patch({ transport: { ...transport, proxyMode: e.target.value as 'INHERIT' | 'DIRECT' | 'HTTP' } })}><option value="INHERIT">继承启动配置</option><option value="DIRECT">直连</option><option value="HTTP">指定 HTTP 代理</option></select></label>
        <label className="space-y-1 text-xs">响应/解压上限（字节）<Input aria-label="响应字节上限" type="number" min={1024} max={67108864} value={transport.maxResponseBytes ?? 8388608} onChange={e => patch({ transport: { ...transport, maxResponseBytes: Number(e.target.value) } })} /></label>
      </div>
      <div className="grid gap-2 sm:grid-cols-2"><label className="text-xs">最大重定向跳数<Input aria-label="最大重定向跳数" type="number" min={0} max={20} value={transport.maxRedirects ?? 5} onChange={e=>patch({transport:{...transport,maxRedirects:Number(e.target.value)}})} /></label><label className="text-xs">HTTP版本偏好<select aria-label="HTTP版本偏好" className={selectClass} value={transport.httpVersion??'INHERIT'} onChange={e=>patch({transport:{...transport,httpVersion:e.target.value as 'INHERIT'|'HTTP_1_1'|'HTTP_2'}})}><option value="INHERIT">继承兼容默认HTTP/1.1</option><option value="HTTP_1_1">HTTP/1.1</option><option value="HTTP_2">优先HTTP/2，服务器可回落1.1</option></select></label><label className="text-xs">数据行Cookie复用<select aria-label="数据行Cookie复用" className={selectClass} value={transport.cookieDataRows??'ISOLATED'} onChange={e=>patch({transport:{...transport,cookieDataRows:e.target.value as 'ISOLATED'|'SHARED'}})}><option value="ISOLATED">每数据行独立（默认）</option><option value="SHARED">本批数据行复用同一会话</option></select></label>{transport.cookieMode==='CHAIN'&&<label className="text-xs">链会话UUID<Input aria-label="链会话UUID" value={transport.cookieSession??''} onChange={e=>patch({transport:{...transport,cookieSession:e.target.value}})} /></label>}</div>
      <p className="text-xs text-muted-foreground">NEVER保留第一跳；NORMAL不跟随HTTPS降级。跨来源自动去除Authorization/Cookie；受管鉴权或写正文跳转会保留3xx并提示阻断。网络失败不自动重试写请求。实际协议和每跳方法显示在执行结果。</p>
      <CookieSessionPanel envId={envId} scope={transport.cookieMode==='CHAIN'?transport.cookieSession:undefined}/>
      <HttpProfileEditor envId={envId} kind="TLS" profileId={transport.certificateProfileId} onSelect={certificateProfileId=>patch({transport:{...transport,certificateProfileId}})}/>
      {transport.proxyMode === 'HTTP' && <div className="grid gap-2 sm:grid-cols-[1fr_120px]"><Input aria-label="代理主机" placeholder="主机，不含协议或凭据" value={transport.proxyHost ?? ''} onChange={e => patch({ transport: { ...transport, proxyHost: e.target.value } })} /><Input aria-label="代理端口" type="number" min={1} max={65535} placeholder="端口" value={transport.proxyPort ?? ''} onChange={e => patch({ transport: { ...transport, proxyPort: Number(e.target.value) } })} /></div>}
      <label className="flex flex-wrap items-center gap-2 text-xs">未解析普通变量<select aria-label="变量严格检查策略" className={selectClass} value={options.strictVariables == null ? 'INHERIT' : options.strictVariables ? 'STRICT' : 'WARN'} onChange={e => patch({ strictVariables: e.target.value === 'INHERIT' ? undefined : e.target.value === 'STRICT' })}><option value="INHERIT">继承环境设置（旧环境兼容警告）</option><option value="STRICT">严格：阻止发送</option><option value="WARN">兼容：警告后保留原文发送</option></select></label>
      <p className="text-xs text-muted-foreground">请求设置优先于环境。系统、本轮与数据行变量缺失始终阻止发送；需要字面双花括号时写 {'{{!name}}'}，会发送 {'{{name}}'}。</p>
      <p className="text-xs text-muted-foreground">环境 Cookie 仅保存在本次应用进程中，重启清空。手填 Cookie 时本次不叠加自动 Cookie。响应超限明确报错，不使用不完整正文判断通过。</p>
    </div>}
  </div>
}
