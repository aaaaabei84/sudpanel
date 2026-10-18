import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// Real browser + isolated HTTP application. ADB is disabled by the launcher.
// Only device/performance fixtures are mocked; client CRUD/prepare/send/scenarios are real.
import { writeFile } from 'node:fs/promises'

const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const MOCK = process.env.SP_API_MOCK || 'http://127.0.0.1:18889'
for (const value of [APP, CDP, MOCK]) {
  const parsed = new URL(value)
  if (parsed.protocol !== 'http:' || parsed.hostname !== '127.0.0.1') throw Error('Only isolated loopback URLs are allowed')
}
const BASE = new URL('/api/apitest', APP).href
const LONG_ID = '12345678901234567890123456789012345678'
const suffix = Date.now().toString(36)
const checks = [], sections = [], pending = new Map()
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const target = targets.find(t => t.type === 'page')
if (!target) throw Error('No isolated CDP page')
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { ws.addEventListener('open', resolve); ws.addEventListener('error', reject) })
let next = 0
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data)
  if (pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})
const command = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP deadline: ${method}`)) }, 12000)
  pending.set(id, result => { clearTimeout(timer); resolve(result) })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.error || result.result?.exceptionDetails) throw Error(JSON.stringify(result.error || result.result.exceptionDetails))
  return result.result.result.value
}
const wait = async (expression, timeout = 12000) => {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { if (await ev(`Boolean(${expression})`)) return; await pause(30) }
  throw Error(`UI predicate deadline: ${expression}`)
}
const check = (name, condition, detail) => {
  checks.push({ name, passed: !!condition })
  console.log(`${condition ? 'PASS' : 'FAIL'} ${name}`)
  if (!condition && detail !== undefined) console.log(JSON.stringify(detail).slice(0, 900))
}
const api = async (path, method = 'GET', body) => {
  const response = await fetch(BASE + path, { method, headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw Error(`fixture ${method} ${path}: HTTP ${response.status}: ${text.slice(0, 500)}`)
  return text ? JSON.parse(text) : undefined
}
const control = async (path, body) => {
  const response = await fetch(MOCK + '/_control/' + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) throw Error('mock control failed')
  return response.json()
}
const quoted = JSON.stringify
const click = async selector => {
  await wait(`!!document.querySelector(${quoted(selector)})&&!document.querySelector(${quoted(selector)}).matches(':disabled')`)
  let geometry
  const deadline=Date.now()+10000
  do {
    geometry=await ev(`(async()=>{const el=document.querySelector(${quoted(selector)});el.scrollIntoView({block:'center',behavior:'instant'});let prev=el.getBoundingClientRect();for(let i=0;i<20;i++){await new Promise(requestAnimationFrame);const r=el.getBoundingClientRect();if(r.x===prev.x&&r.y===prev.y&&r.width===prev.width&&r.height===prev.height){const x=r.x+r.width/2,y=r.y+r.height/2,hit=document.elementFromPoint(x,y);return {x,y,hit:hit===el||el.contains(hit)}}prev=r}return {hit:false}})()`)
    if(geometry.hit)break
    await pause(30)
  }while(Date.now()<deadline)
  if(!geometry?.hit)throw Error('Control not reachable at a stable pointer position: '+selector)
  await command('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, x: geometry.x, y: geometry.y })
  await command('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, x: geometry.x, y: geometry.y })
}
const clickText = async (text, scope = 'document') => {
  const expression = `(()=>{const root=${scope};const nodes=[...root.querySelectorAll('button,[data-slot="card"].cursor-pointer')];const el=nodes.find(x=>x.textContent.trim()===${quoted(text)}) || nodes.find(x=>x.getAttribute('data-slot')==='card'&&x.textContent.includes(${quoted(text)}));if(!el)return false;el.click();return true})()`
  await wait(`(()=>{const root=${scope};return [...root.querySelectorAll('button,[data-slot="card"].cursor-pointer')].some(x=>x.textContent.trim()===${quoted(text)} || x.getAttribute('data-slot')==='card'&&x.textContent.includes(${quoted(text)}))})()`)
  if (!(await ev(expression))) throw Error('Missing text control: ' + text)
}
const setValue = async (selector, value) => {
  await wait(`!!document.querySelector(${quoted(selector)})`)
  await wait(`(()=>{const el=document.querySelector(${quoted(selector)});return el.tagName!=='SELECT'||[...el.options].some(o=>o.value===${quoted(String(value))})})()`)
  await ev(`(()=>{const el=document.querySelector(${quoted(selector)});const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:el.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(el,${quoted(String(value))});el.dispatchEvent(new Event(el.tagName==='SELECT'?'change':'input',{bubbles:true}));})()`)
}
const closeDialog = async () => {
  const selector = await ev(`(()=>{const ds=[...document.querySelectorAll('[role=dialog]')];const d=ds.at(-1);if(!d)return null;const e=d.querySelector('button[data-slot="dialog-close"]')||[...d.querySelectorAll('button')].find(b=>b.textContent.trim()==='Close'||b.textContent.trim()==='关闭');if(!e)return null;e.setAttribute('data-client-close','true');return '[data-client-close="true"]'})()`)
  if (selector) await click(selector)
  else await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await wait(`!document.querySelector('[role=dialog]')`)
}
const view = async (width = 1440, height = 1000) => command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
const screenshot = async name => {
  if (!process.env.SP_UI_SCREENSHOT_DIR) return
  const result=await command('Page.captureScreenshot',{format:'png',fromSurface:true,captureBeyondViewport:false})
  await writeFile(process.env.SP_UI_SCREENSHOT_DIR+'/'+name+'.png',Buffer.from(result.result.data,'base64'))
}
const navigateApi = async () => {
  if (await ev(`!![...document.querySelectorAll('button')].find(b=>b.textContent==='立即保存草稿')`)) {
    await clickText('立即保存草稿')
    await wait(`!document.body.innerText.includes('草稿待保存')&&!document.body.innerText.includes('正在保存草稿')&&!document.body.innerText.includes('草稿尚未保存')`)
  }
  const previousDocument = await ev('window.clientDocumentId')
  await command('Page.navigate', { url: APP })
  await wait(`window.clientDocumentId && window.clientDocumentId!==${JSON.stringify(previousDocument ?? '')}`)
  await wait(`!!document.querySelector('button')`)
  await wait(`Array.isArray(window.clientWrites)&&Array.isArray(window.clientPerfWrites)`)
  await clickText('接口测试')
  await wait(`document.body.innerText.includes('用例管理')`)
}
const openQuick = async () => { await click('[data-testid="quick-open"] [data-slot="card"]'); await wait(`!!document.querySelector('[data-testid="quick-env"]')`) }
const section = async (name, work) => {
  console.log('\n[' + name + ']')
  const before = checks.length
  try { await work() } catch (error) {
    check(name + ' 完整执行', false, String(error)); console.error(error.stack)
    try { await screenshot('client-failure-'+sections.length);console.error(await ev(`document.body.innerText.slice(-5000)`)) } catch {}
  }
  sections.push({ name, checks: checks.length - before, passed: checks.slice(before).every(c => c.passed) })
}
const makeSpec = (name, path, body = '{}') => ({ name: `${name}-${suffix}`, method: 'POST', url: MOCK + path,
  body, headers: '{}', note: '隔离浏览器回归', expectStatus: '200', expectCode: '', timeoutMs: 8000,
  insecure: false, enabled: true, groupId: null, asserts: null,
  options: { bodyType: 'JSON', params: [], auth: { type: 'NONE' }, extractors: [] } })
let env, group, caseA, caseB, scenarioFail, scenarioIndependent, scenarioCleanup, queryTemplate

try {
  const devices = await (await fetch(new URL('/api/devices', APP))).json()
  if (!Array.isArray(devices) || devices.length) throw Error('ADB-disabled isolated instance required')
  env = await api('/envs', 'POST', { name: 'client-ui-env-' + suffix })
  await api(`/envs/${env.id}/settings`, 'PUT', { sign: { enabled: false, appId: '', appSecret: '', nonce: '', mgId: '' },
    vars: { base: [{ name: 'endpoint', value: MOCK }], header: [{ name: 'marker', value: 'ui-env' }], body: [{ name: 'marker', value: 'ui-env' }] } })
  group = await api('/case-groups', 'POST', { name: 'client-ui-group-'+suffix, envId: env.id })
  caseA = await api('/cases', 'POST', makeSpec('UI-ORDER-A', '/capture/ui-a'))
  caseB = await api('/cases', 'POST', makeSpec('UI-ORDER-B', '/capture/ui-b'))
  scenarioFail = await api('/cases', 'POST', makeSpec('UI-SCENARIO-FAIL', '/fail'))
  const independentSpec=makeSpec('UI-SCENARIO-INDEPENDENT', '/capture/ui-independent')
  independentSpec.options.extractors=[{name:'inheritedCode',source:'JSON',path:'ret_code',required:true}]
  scenarioIndependent = await api('/cases', 'POST', independentSpec)
  scenarioCleanup = await api('/cases', 'POST', makeSpec('UI-SCENARIO-CLEANUP', '/cleanup'))
  queryTemplate = await api('/cases', 'POST', makeSpec('UI-PERF-QUERY-TEMPLATE', '/capture/perf-query',
    '{"game_round_id":"OLD","otherBig":'+LONG_ID+'}'))
  await command('Page.enable')
  const injected=await command('Page.addScriptToEvaluateOnNewDocument', { source: `(()=>{
    const original=window.fetch;window.clientDocumentId=crypto.randomUUID();window.clientWrites=[];window.clientPerfWrites=[];
    window.fetch=async(input,init)=>{const url=new URL(typeof input==='string'?input:input.url,location.href);const path=url.pathname;const method=(init?.method||'GET').toUpperCase();
      if(path==='/api/devices')return new Response('[]',{status:200,headers:{'Content-Type':'application/json'}});
      if(path.startsWith('/api/perf/')&&method!=='GET'){window.clientPerfWrites.push({path,method});throw Error('No real performance writes in client UI suite')}
      let observed;if(path.startsWith('/api/apitest/')&&method!=='GET'){observed={path,method,body:init?.body?JSON.parse(init.body):null,done:false};window.clientWrites.push(observed)}
      if(window.clientPerfFixtures&&path in window.clientPerfFixtures)return new Response(JSON.stringify(window.clientPerfFixtures[path]),{status:200,headers:{'Content-Type':'application/json'}});
      const response=await original(input,init);if(observed){observed.done=true;observed.status=response.status}return response;
    };
  })()` })
  if(injected.error||!injected.result?.identifier)throw Error('Browser fixture injection registration failed: '+JSON.stringify(injected))
  await view()

  await section('执行顺序上移与发送一致', async () => {
    await control('reset', {})
    await navigateApi()
    await clickText('用例管理')
    for (const c of [caseB, caseA]) {
      await click(`[data-testid="case-pick-${c.id}"]`)
    }
    await closeDialog()
    await wait(`!!document.querySelector('[data-testid="api-run-plan"]')`)
    const before = await ev(`[...document.querySelectorAll('[data-testid="api-run-plan"] span.flex-1[title]')].map(x=>x.title)`)
    check('执行顺序显示按勾选先后', JSON.stringify(before) === JSON.stringify([caseB.name, caseA.name]), before)
    await click('[aria-label="用例 2 上移"]')
    const after = await ev(`[...document.querySelectorAll('[data-testid="api-run-plan"] span.flex-1[title]')].map(x=>x.title)`)
    check('上移后显示顺序更新', JSON.stringify(after) === JSON.stringify([caseA.name, caseB.name]), after)
    await ev(`window.clientWrites=[]`)
    await ev(`([...document.querySelectorAll('button')].find(x=>x.textContent.trim().startsWith('执行选中'))).click()`)
    await wait(`window.clientWrites.some(x=>x.path==='/api/apitest/run'&&x.done)`)
    const payload = await ev(`window.clientWrites.find(x=>x.path==='/api/apitest/run').body`)
    check('提交 caseIds 与调整顺序一致', JSON.stringify(payload.caseIds) === JSON.stringify([caseA.id, caseB.id]), payload)
    const deadline = Date.now()+10000
    let wire
    do { wire=await control('journal');if(wire.length>=2)break;await pause(30) } while(Date.now()<deadline)
    check('真实 HTTP 顺序与显示一致', JSON.stringify(wire.map(e=>e.path)) === JSON.stringify(['/capture/ui-a','/capture/ui-b']), wire)
  })

  await section('快速调试预览、环境、保存与精度', async () => {
    await control('reset', {})
    await navigateApi()
    await openQuick()
    check('未选环境时发送禁用', await ev(`document.querySelector('[data-testid="quick-send"]').disabled===true`))
    await setValue('[aria-label="请求名称"]', 'UI-SAVED-OPTIONS-'+suffix)
    await setValue('[aria-label="请求地址"]', '{{endpoint}}/issue?scope=ui-fields')
    await setValue('[aria-label="请求方法"]', 'POST')
    await setValue('[data-testid="quick-env"]', env.id)
    await setValue('[aria-label="请求体类型"]', 'JSON')
    const body='{"long":'+LONG_ID+',"marker":"{{marker}}"}'
    await setValue('#quick-body', body)
    await clickText('鉴权')
    await setValue('[aria-label="鉴权方式"]', 'BEARER')
    await setValue('[aria-label="Bearer Token"]', 'dummy-ui-token')
    await clickText('查询参数')
    await clickText('添加参数')
    await setValue('[aria-label="参数名 1"]', 'ui')
    await setValue('[aria-label="参数值 1"]', 'a+b&中文')
    const beforeBatches = await api('/batches')
    const beforeHistory = await api('/execs/recent?limit=100')
    await clickText('预览')
    await wait(`document.body.innerText.includes('预览校验通过')`)
    check('浏览器预览不发送业务 HTTP', (await control('journal')).length===0)
    check('浏览器预览不新增批次历史', JSON.stringify(await api('/batches'))===JSON.stringify(beforeBatches))
    check('浏览器预览不写入独立执行历史', JSON.stringify(await api('/execs/recent?limit=100'))===JSON.stringify(beforeHistory))
    await click('[data-testid="quick-send"]')
    await wait(`document.body.innerText.includes('响应 · 200')`)
    const wire=await control('journal')
    check('快速发送使用所选环境', wire.length===1 && wire[0].json?.marker==='ui-env', wire)
    check('快速发送 38 位整数原样', wire.length===1 && wire[0].body.includes('"long":'+LONG_ID), wire[0]?.body)
    check('快速发送保存的鉴权与参数有效', wire.length===1 && wire[0].headers.some(([k,v])=>k.toLowerCase()==='authorization'&&v==='Bearer dummy-ui-token')
      && wire[0].query.some(([k,v])=>k==='ui'&&v==='a+b&中文'))
    // H29: inspect original local evidence only after the user explicitly reveals it.
    // Never generate an assertion/extractor from the masked safeDisplay projection.
    const revealSelector = '[data-client-response-reveal="true"]'
    await wait(`!![...document.querySelectorAll('label')].find(label=>label.textContent.trim()==='显示本机敏感原文')?.querySelector('input[type="checkbox"]')`)
    await ev(`(()=>{const input=[...document.querySelectorAll('label')].find(label=>label.textContent.trim()==='显示本机敏感原文').querySelector('input[type="checkbox"]');input.setAttribute('data-client-response-reveal','true');return true})()`)
    check('响应字段默认隐藏且显敏开关未勾选', await ev(`!document.querySelector(${quoted(revealSelector)}).checked&&!([...document.querySelectorAll('button')].some(button=>button.textContent.trim()==='选择响应字段'))`))
    await click(revealSelector)
    await wait(`document.querySelector(${quoted(revealSelector)}).checked&&[...document.querySelectorAll('button')].some(button=>button.textContent.trim()==='选择响应字段')`)
    check('显式显示本机敏感原文后开放字段助手', await ev(`document.querySelector(${quoted(revealSelector)}).checked`))
    await clickText('选择响应字段')
    await wait(`document.body.innerText.includes('响应字段 · 生成路径与规则')`)
    await setValue('[aria-label="搜索响应路径"]', 'data.number')
    await click('[data-testid="response-field-data.number"]')
    await click('[data-testid="inspect-add-assert"]')
    await wait(`!!document.querySelector('[aria-label="断言期望 1"]')`)
    check('字段助手解析的是原始38位响应而非遮罩快照', await ev(`window.clientWrites.some(write=>write.path==='/api/apitest/inspect-response'&&write.body?.body?.includes(${quoted((BigInt(LONG_ID)+1n).toString())})&&JSON.parse(write.body.body)?.data?.token==='ui-fields-token-1')`))
    check('选响应字段生成断言保留 38 位整数', await ev(`document.querySelector('[aria-label="断言路径 1"]').value==='data.number'&&document.querySelector('[aria-label="断言期望 1"]').value===${quoted((BigInt(LONG_ID)+1n).toString())}`))
    await clickText('选择响应字段')
    await setValue('[aria-label="搜索响应路径"]','header:x-chain-token')
    await click('[data-testid="response-field-header:x-chain-token"]')
    await setValue('[aria-label="提取变量名称"]','responseToken')
    await click('[data-testid="inspect-add-extract"]')
    await wait(`!!document.querySelector('[aria-label="变量名 1"]')`)
    check('选真实响应头生成提取规则',await ev(`document.querySelector('[aria-label="提取来源 1"]').value==='HEADER'&&document.querySelector('[aria-label="提取路径 1"]').value==='x-chain-token'&&document.querySelector('[aria-label="变量名 1"]').value==='responseToken'`))
    await click(revealSelector)
    await wait(`!document.querySelector(${quoted(revealSelector)}).checked&&!([...document.querySelectorAll('button')].some(button=>button.textContent.trim()==='选择响应字段'))`)
    check('隐藏原文后字段助手重新关闭且已生成38位断言保留', await ev(`!document.querySelector(${quoted(revealSelector)}).checked&&document.querySelector('[aria-label="断言期望 1"]').value===${quoted((BigInt(LONG_ID)+1n).toString())}`))
    const beforeCases=await api('/cases')
    const saveRequests=await ev(`window.clientWrites.filter(x=>x.path==='/api/apitest/cases'&&x.method==='POST').length`)
    await clickText('保存为新用例')
    await wait(`document.body.innerText.includes('保存后的执行环境与当前调试环境不同')`)
    check('custom 环境与未分组不一致时阻止保存', JSON.stringify(await api('/cases'))===JSON.stringify(beforeCases)
      &&await ev(`window.clientWrites.filter(x=>x.path==='/api/apitest/cases'&&x.method==='POST').length===${saveRequests}`))
    await setValue('[aria-label="保存到分组"]',group.id)
    await clickText('保存为新用例')
    let saved
    const deadline=Date.now()+10000
    do { saved=(await api('/cases')).find(c=>c.name==='UI-SAVED-OPTIONS-'+suffix);if(saved)break;await pause(30) } while(Date.now()<deadline)
    check('快速草稿保存 options 与大整数原文', saved?.options?.bodyType==='JSON'&&saved?.options?.auth?.type==='BEARER'
      &&saved?.options?.params?.[0]?.value==='a+b&中文'&&saved.body.includes(LONG_ID), saved)
    check('生成断言真实落库', saved?.asserts&&JSON.parse(saved.asserts)[0]?.expect===(BigInt(LONG_ID)+1n).toString(), saved?.asserts)
    check('生成响应头提取真实落库',saved?.options?.extractors?.[0]?.source==='HEADER'
      &&saved?.options?.extractors?.[0]?.path==='x-chain-token'&&saved?.options?.extractors?.[0]?.name==='responseToken',saved?.options)
    check('保存用例绑定本次选定环境的分组',saved?.groupId===group.id,saved)
    await screenshot('client-wide')
    await view(420,780)
    await clickText('请求体')
    await setValue('[aria-label="请求地址"]',MOCK+'/capture?long='+'x'.repeat(400))
    const reach=await ev(`(()=>{const b=document.querySelector('[data-testid="quick-send"]');b.scrollIntoView({block:'center'});const r=b.getBoundingClientRect();const hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return r.x>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight&&(hit===b||b.contains(hit))})()`)
    check('窄窗口发送按钮可见且可点击', reach)
    await screenshot('client-narrow')
    await view()
    await setValue('[aria-label="请求地址"]',MOCK+'/gate?id=ui-freeze')
    await click('[data-testid="quick-send"]')
    await control('gate-wait?id=ui-freeze&timeout=10')
    try {
      check('发送在途冻结环境、名称、请求体编辑',await ev(`document.querySelector('[data-testid="quick-env"]').matches(':disabled')&&document.querySelector('[aria-label="请求名称"]').matches(':disabled')&&document.querySelector('#quick-body').matches(':disabled')`))
    } finally { await control('gate-release?id=ui-freeze',{}) }
    await wait(`!document.querySelector('[data-testid="quick-send"]').matches(':disabled')&&window.clientWrites.filter(x=>x.path==='/api/apitest/requests').at(-1)?.done`)
    await setValue('[aria-label="请求体类型"]','RAW')
    await setValue('#quick-body','')
    const raw='{"z":1,"a":[2,3]}'
    await command('Page.bringToFront')
    await command('Browser.grantPermissions',{origin:new URL(APP).origin,permissions:['clipboardReadWrite','clipboardSanitizedWrite']})
    await ev(`navigator.clipboard.writeText(${quoted(raw)})`)
    await click('#quick-body')
    await ev(`(()=>{window.clientRawPaste=null;const el=document.querySelector('#quick-body');el.setSelectionRange(0,el.value.length);el.addEventListener('paste',event=>{window.clientRawPaste=event.clipboardData.getData('text/plain')},{once:true})})()`)
    await command('Input.dispatchKeyEvent',{type:'keyDown',key:'v',code:'KeyV',modifiers:4,windowsVirtualKeyCode:86,nativeVirtualKeyCode:9,commands:['paste']})
    await command('Input.dispatchKeyEvent',{type:'keyUp',key:'v',code:'KeyV',modifiers:4,windowsVirtualKeyCode:86,nativeVirtualKeyCode:9})
    await command('Input.dispatchKeyEvent',{type:'keyUp',key:'Meta',code:'MetaLeft',modifiers:0,windowsVirtualKeyCode:91,nativeVirtualKeyCode:55})
    await wait(`window.clientRawPaste===${quoted(raw)}`)
    await click('[aria-label="请求名称"]')
    check('RAW 紧凑 JSON 粘贴及离焦后原文不变',await ev(`document.querySelector('#quick-body').value===${quoted(raw)}`))
    await setValue('[aria-label="请求地址"]',MOCK+'/capture/ui-raw')
    await click('[data-testid="quick-send"]')
    await wait(`!document.querySelector('[data-testid="quick-send"]').matches(':disabled')&&window.clientWrites.filter(x=>x.path==='/api/apitest/requests').at(-1)?.body.spec.url.includes('/capture/ui-raw')`)
    const rawWire=(await control('journal')).find(e=>e.path==='/capture/ui-raw')
    check('RAW 紧凑 JSON 真实 wire 字节不变',rawWire?.body===raw&&rawWire?.bodyBytes===Buffer.byteLength(raw),rawWire)
    await closeDialog()
  })

  await section('场景编辑 independent、cleanup 与运行', async () => {
    await control('reset', {})
    await navigateApi()
    await click('[data-testid="scenario-create"]')
    await setValue('[aria-label="场景名称"]', 'UI-SCENARIO-'+suffix)
    for(const c of [scenarioFail,scenarioIndependent,scenarioCleanup]) {
      await setValue('[aria-label="添加场景用例"]', c.id)
      await clickText('添加步骤')
    }
    await wait(`!!document.querySelector('[aria-label="步骤名称 3"]')`)
    await ev(`(()=>{const input=document.querySelector('[aria-label="步骤名称 2"]');const row=input.closest('[data-testid^="scenario-step-"]');const label=[...row.querySelectorAll('label')].find(x=>x.textContent.includes('独立步骤'));label.querySelector('[role=checkbox]').click()})()`)
    await ev(`(()=>{const input=document.querySelector('[aria-label="步骤名称 3"]');const row=input.closest('[data-testid^="scenario-step-"]');const label=[...row.querySelectorAll('label')].find(x=>x.textContent.includes('这是清理步骤'));label.querySelector('[role=checkbox]').click()})()`)
    await clickText('保存场景')
    await wait(`!document.querySelector('[aria-label="场景名称"]')`)
    const scen=(await api('/scenarios')).find(s=>s.name==='UI-SCENARIO-'+suffix)
    check('场景 independent 与 cleanup 分别持久化', scen?.steps?.[1]?.independent===true&&scen?.steps?.[1]?.onFailure==='STOP'
      &&scen?.steps?.[2]?.cleanup===true,scen)
    check('场景创建把用例继承提取物化为显式规则',scen?.steps?.[1]?.extractors?.[0]?.name==='inheritedCode',scen?.steps)
    await click(`[aria-label="编辑场景 UI-SCENARIO-${suffix}"]`)
    check('再次编辑恢复独立与清理勾选', await ev(`(()=>{const labels=[...document.querySelectorAll('[role=dialog] label')];return labels.filter(x=>x.textContent.includes('独立步骤')).some(x=>x.querySelector('[role=checkbox]')?.getAttribute('data-state')==='checked')&&labels.filter(x=>x.textContent.includes('这是清理步骤')).some(x=>x.querySelector('[role=checkbox]')?.getAttribute('data-state')==='checked')})()`))
    await clickText('取消')
    await ev(`(()=>{const button=document.querySelector('[aria-label="编辑场景 UI-SCENARIO-${suffix}"]');const row=button.parentElement;[...row.querySelectorAll('button')].find(x=>x.textContent.trim()==='运行').click()})()`)
    await wait(`window.clientWrites.some(x=>x.path===${quoted('/api/apitest/scenarios/'+scen.id+'/run')})`)
    const deadline=Date.now()+10000
    let wire
    do {wire=await control('journal');if(wire.length>=3)break;await pause(30)}while(Date.now()<deadline)
    check('从界面运行失败后仍执行独立及清理', JSON.stringify(wire.map(e=>e.path))===JSON.stringify(['/fail','/capture/ui-independent','/cleanup']),wire)
  })

  await section('性能来源跨页预填与未知环境阻拦', async () => {
    await control('reset', {})
    await navigateApi()
    const fixtures={
      '/api/perf/games':[{id:703,name:'UI-FAKE-GAME',shortName:'UI-FAKE-GAME'}],
      '/api/perf/runs':[{id:701,status:'DONE',mode:'NORMAL',gameQueue:'703',loopCount:3,sampleSeconds:5}],
      '/api/perf/runs/page':{items:[{id:701,status:'DONE',mode:'NORMAL',gameQueue:'703',loopCount:3,sampleSeconds:5}],nextCursor:null,hasMore:false},
      '/api/perf/runs/active':[],
      '/api/perf/runs/701':{id:701,status:'DONE',mode:'NORMAL',gameQueue:'703',loopCount:3,sampleSeconds:5},
      '/api/perf/button-groups':[], '/api/perf/ai-room':{}, '/api/perf/options':{},
      '/api/perf/runs/701/games/703/loops':[{id:702,gameId:703,runId:701,loopNo:3,status:'OK',gameRoundId:LONG_ID,
        pssAvgKb:1024,baselinePssKb:1024,pssPeakKb:2048,cpuAvg:1,cpuPeak:2}],
      '/api/perf/runs/701/games/703/checks':[],
      '/api/perf/runs/701/games/703/growth-curves':{curves:[],totalOk:1},
    }
    await ev(`window.clientPerfFixtures=${JSON.stringify(fixtures)}`)
    await clickText('性能测试')
    await wait(`!![...document.querySelectorAll('[role=combobox]')].find(x=>x.textContent.includes('选择运行'))`)
    await ev(`[...document.querySelectorAll('[role=combobox]')].find(x=>x.textContent.includes('选择运行')).setAttribute('data-client-run-select','true')`)
    await click('[data-client-run-select="true"]')
    await click('[role="option"]')
    await clickText('UI-FAKE-GAME')
    await click('[data-testid="perf-query-round-702"]')
    await wait(`!!document.querySelector('[data-testid="quick-env"]')`)
    check('性能上下文跨页预填对局 ID', await ev(`document.querySelector('#quick-body')?.value.includes(${quoted(LONG_ID)})&&document.body.innerText.includes('性能运行 #701')`))
    check('未知环境不自动沿用默认环境且发送禁用', await ev(`document.querySelector('[data-testid="quick-env"]').value===''&&document.querySelector('[data-testid="quick-send"]').disabled===true`))
    check('性能预填不会自动发送业务请求', (await control('journal')).length===0)
    await setValue('[aria-label="从已有用例载入"]', queryTemplate.id)
    await wait(`document.querySelector('[data-testid=request-replace-changes]')`);await clickText('放弃修改');await wait(`document.querySelector('[aria-label="请求地址"]').value.includes('/capture/perf-query')`)
    check('载入查询用例仅替换已命名对局字段，保留其他大整数', await ev(`document.querySelector('#quick-body')?.value.includes(${quoted('"game_round_id":"'+LONG_ID+'"')})&&document.querySelector('#quick-body')?.value.includes(${quoted('"otherBig":'+LONG_ID)})`))
    check('性能来源未知时载入用例也不猜环境', await ev(`document.querySelector('[data-testid="quick-env"]').value===''&&document.querySelector('[data-testid="quick-send"]').disabled===true`))
    await setValue('[data-testid="quick-env"]', env.id)
    await click('[data-testid="quick-send"]')
    await wait(`document.body.innerText.includes('响应 · 200')`)
    const payload=await ev(`window.clientWrites.filter(x=>x.path==='/api/apitest/requests').at(-1)?.body`)
    check('明确选择环境后发送携带来源', payload?.envId===env.id&&payload?.sourceContext?.runId===701
      &&payload?.sourceContext?.loopId===702&&payload?.sourceContext?.roundId===LONG_ID,payload)
    check('查询 body 的长对局 ID 原样送达', (await control('journal'))[0]?.json?.game_round_id===LONG_ID)
    await clickText('新请求');await wait(`document.querySelector('[aria-label="请求名称"]')?.value===''`)
    check('新请求清除性能来源及旧对局 body', await ev(`!document.body.innerText.includes('性能运行 #701')&&!(document.querySelector('#quick-body')?.value||'').includes(${quoted(LONG_ID)})`))
    await setValue('[data-testid="quick-env"]', env.id)
    await setValue('[aria-label="请求地址"]', MOCK+'/capture/new-after-perf')
    await clickText('请求体')
    await setValue('#quick-body', '{}')
    await click('[data-testid="quick-send"]')
    await wait(`document.body.innerText.includes('响应 · 200')`)
    const clean=await ev(`window.clientWrites.filter(x=>x.path==='/api/apitest/requests').at(-1)?.body`)
    check('新请求发送不携带上一局来源', clean?.sourceContext==null,clean)
    check('本专项不触发任何性能写操作', await ev(`window.clientPerfWrites.length===0`))
  })
} catch(error) {
  check('浏览器专项基础设施完整执行',false,String(error))
  console.error(error.stack)
} finally {
  ws.close()
}
const failed=checks.filter(c=>!c.passed).length
const summary={total:checks.length,passed:checks.length-failed,failed,sections,checks,trueDeviceChecks:0}
if(process.env.SP_UI_SUMMARY)await writeFile(process.env.SP_UI_SUMMARY,JSON.stringify(summary,null,2))
console.log(`\nHTTP 客户端界面专项：${summary.passed}/${summary.total}，失败 ${failed}`)
process.exitCode=failed?1:0
