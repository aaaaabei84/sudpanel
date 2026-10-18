import { requiredUiUrl } from './isolated-ui.mjs'
// Synthetic JSON-file regression. Run only with an isolated static/app URL and owned CDP browser.
// All API responses come from browser fixtures; CDP additionally rejects real /api/ network traffic.
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
for (const value of [APP, CDP]) {
  const u = new URL(value)
  if (u.protocol !== 'http:' || u.hostname !== '127.0.0.1' || u.port === '18790') throw Error('Only isolated loopback URLs; formal port 18790 is forbidden')
}
const OUTPUT = process.env.SP_OUTPUT_DIR || process.env.SP_UI_SCREENSHOT_DIR || join(tmpdir(), 'sudpanel-import-file-results-' + Date.now())
const MAX = 2_000_000, LONG = '12345678901234567890123456789012345678'
const REQUEST_CANARY = 'JSON_FILE_REQUEST_BODY_CANARY', NOTE_CANARY = 'JSON_FILE_FOLDER_NOTE_CANARY'
const checks = [], sections = [], networkApi = []
const quote = JSON.stringify, pause = ms => new Promise(r => setTimeout(r, ms))
let fixtureDir, browser, page, targetId, scriptId, removeNetworkListener, navigation = 0
const paths = {}, contents = {}

const cdp = async url => {
  const ws = new WebSocket(url), pending = new Map(), listeners = new Map(); let next = 0
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve); ws.addEventListener('error', reject) })
  ws.addEventListener('message', event => {
    const m = JSON.parse(event.data)
    if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) }
    else if (m.method) for (const listener of listeners.get(m.method) || []) listener(m.params)
  })
  return {
    close: () => ws.close(),
    on: (method, listener) => { const list = listeners.get(method) || []; list.push(listener); listeners.set(method, list); return () => listeners.set(method, list.filter(x => x !== listener)) },
    command: (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++next, timer = setTimeout(() => { pending.delete(id); reject(Error('CDP deadline: ' + method)) }, 20000)
      pending.set(id, m => { clearTimeout(timer); if (m.error) reject(Error(JSON.stringify(m.error))); else resolve(m.result) })
      ws.send(JSON.stringify({ id, method, params }))
    })
  }
}
const ev = async expression => {
  const result = await page.command('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails))
  return result.result.value
}
// Predicates always return booleans; never return DOM nodes through CDP returnByValue.
const wait = async (expression, timeout = 20000) => {
  const until = Date.now() + timeout
  while (Date.now() < until) {
    try { if (await ev(`Boolean(${expression})`)) return }
    catch (error) { if (!/context.*destroyed|Cannot find context/i.test(String(error))) throw error }
    await pause(35)
  }
  throw Error('UI predicate deadline: ' + expression)
}
const settled = () => ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))')
const check = async (name, expression) => {
  const passed = typeof expression === 'boolean' ? expression : await ev(`Boolean(${expression})`)
  checks.push({ name, passed }); console.log(`${passed ? 'PASS' : 'FAIL'} ${name}`)
}
const click = async selector => {
  await wait(`!!document.querySelector(${quote(selector)})&&!document.querySelector(${quote(selector)}).matches(':disabled')`)
  const point = await ev(`(async()=>{const el=document.querySelector(${quote(selector)});el.scrollIntoView({block:'center',behavior:'instant'});for(let i=0;i<4;i++)await new Promise(requestAnimationFrame);const r=el.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,hit=document.elementFromPoint(x,y);return {x,y,hit:hit===el||el.contains(hit)}})()`)
  if (!point.hit) throw Error('Control obscured: ' + selector)
  for (const type of ['mousePressed', 'mouseReleased']) await page.command('Input.dispatchMouseEvent', { type, button: 'left', clickCount: 1, x: point.x, y: point.y })
}
const markText = async (label, scope = '[role=dialog]') => {
  await wait(`!![...document.querySelector(${quote(scope)})?.querySelectorAll('button')||[]].find(b=>b.textContent.trim()===${quote(label)})`)
  await ev(`(()=>{const b=[...document.querySelector(${quote(scope)}).querySelectorAll('button')].find(b=>b.textContent.trim()===${quote(label)});b.setAttribute('data-import-file-click','active');return true})()`)
  return '[data-import-file-click=active]'
}
const clickText = async (label, scope) => { const s = await markText(label, scope); await click(s); await ev(`(()=>{document.querySelector(${quote(s)})?.removeAttribute('data-import-file-click');return true})()`) }
const change = async (selector, value) => {
  await wait(`!!document.querySelector(${quote(selector)})`)
  await ev(`(()=>{const el=document.querySelector(${quote(selector)}),p=el.tagName==='SELECT'?HTMLSelectElement.prototype:el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(el,${quote(value)});el.dispatchEvent(new Event(el.tagName==='SELECT'?'change':'input',{bubbles:true}));return true})()`)
}
const chooseFile = async (id, name) => {
  await wait(`!!document.querySelector(${quote('#' + id + '-file')})`)
  const document = await page.command('DOM.getDocument', { depth: 0 })
  const node = await page.command('DOM.querySelector', { nodeId: document.root.nodeId, selector: '#' + id + '-file' })
  if (!node.nodeId) throw Error('Missing file input: ' + id)
  await page.command('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [paths[name]] })
}
const loaded = (id, name) => wait(`document.querySelector(${quote('[data-testid="' + id + '-filename"]')})?.textContent===${quote(name)}&&!document.querySelector(${quote('[data-testid="' + id + '-source"]')}).textContent.includes('正在读取文件')`)
const editText = async id => {
  if (await ev(`Boolean(document.querySelector(${quote('[data-testid="' + id + '-filename"]')}))`)) await clickText('编辑文本', `[data-testid="${id}-source"]`)
  await wait(`!!document.querySelector(${quote('#' + id)})`)
}
const openCollection = async () => {
  await wait(`!document.querySelector('[data-slot="dialog-overlay"]')`)
  await clickText('导入 Postman / OpenAPI', 'body')
  await wait(`!!document.querySelector('#import-COLLECTION-file')`)
}
const fresh = async () => {
  const previousBoot = await ev('window.importFileFixture?.boot??null')
  const target = new URL(APP); target.searchParams.set('jsonFileFixture', String(++navigation)); target.hash = '#/apitest'
  await page.command('Page.navigate', { url: target.href })
  await wait(`!!window.importFileFixture&&window.importFileFixture.boot!==${quote(previousBoot)}&&!![...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='导入 Postman / OpenAPI')`)
  await openCollection()
}
const preview = async () => {
  const before = await ev('window.importFileFixture.previews.length')
  await clickText('预览导入')
  await wait(`window.importFileFixture.previews.length===${before + 1}&&!![...document.querySelectorAll('[role=dialog] section')].find(s=>s.querySelector(':scope > p')?.textContent.startsWith('JSON-FILE-PREVIEW'))&&!document.querySelector('[role=dialog] fieldset').disabled`)
  await ev(`(()=>{[...document.querySelectorAll('[role=dialog] section')].find(s=>s.querySelector(':scope > p')?.textContent.startsWith('JSON-FILE-PREVIEW')).setAttribute('data-import-file-preview','true');return true})()`)
}
const seed = async () => { await chooseFile('import-COLLECTION', 'seed.json'); await loaded('import-COLLECTION', 'seed.json') }
const hold = async name => ev(`(()=>{window.importFileFixture.holdFiles.push(${quote(name)});return true})()`)
const gateReady = name => wait(`window.importFileFixture.readGates.some(g=>g.name===${quote(name)}&&!g.released)`)
const release = async name => { await ev(`(()=>{const g=window.importFileFixture.readGates.find(g=>g.name===${quote(name)}&&!g.released);if(!g)throw Error('Missing read gate');g.release();return true})()`); await settled() }
const section = async (name, work) => {
  console.log('\n[' + name + ']'); const before = checks.length
  try { await work() }
  catch (error) { checks.push({ name: name + ' 完整执行', passed: false }); console.error(error.stack || error); try { console.error((await ev('document.body.innerText')).slice(-2000)) } catch {} }
  sections.push({ name, checks: checks.length - before, passed: checks.slice(before).every(c => c.passed) })
}

const injection = `(()=>{
  const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
  const originalFetch=window.fetch, originalRead=File.prototype.arrayBuffer;
  const f=window.importFileFixture={boot:crypto.randomUUID(),previews:[],commits:[],unexpectedWrites:[],unconfiguredGets:[],holdFiles:[],failFiles:[],readGates:[],readCompletions:[],previewGates:[],commitGates:[],holdPreview:false,holdCommit:false};
  File.prototype.arrayBuffer=function(){const file=this;if(f.failFiles.includes(file.name))return Promise.reject(Error('synthetic read failure'));return originalRead.call(file).then(bytes=>{if(!f.holdFiles.includes(file.name)){f.readCompletions.push(file.name);return bytes}return new Promise(resolve=>{const gate={name:file.name,released:false,release:()=>{gate.released=true;f.readCompletions.push(file.name);resolve(bytes)}};f.readGates.push(gate)})})};
  const buildPreview=b=>{let doc;try{doc=JSON.parse(b.content)}catch{return {name:'JSON-FILE-PREVIEW',requests:[],variables:{base:[],header:[],body:[]},warnings:[],errors:['fixture JSON invalid']}}
    const source=Array.isArray(doc?.item)?doc.item:[{name:'single'}];const requests=source.map((item,i)=>({folder:'fixture collection',spec:{name:item.name||'JSON-FILE-REQ-'+String(i+1).padStart(3,'0'),method:'POST',url:'https://example.invalid/never-send',headers:'{}',body:JSON.stringify({canary:${quote(REQUEST_CANARY)},index:i,pad:'p'.repeat(1500)}),enabled:true,timeoutMs:1000,options:{bodyType:'JSON',auth:{type:'NONE'},params:[],extractors:[]}}}));
    return {name:'JSON-FILE-PREVIEW',requests,environmentName:'fixture imported environment',variables:{base:[],header:[],body:[]},warnings:[],errors:[],variableMappings:[],losses:[],folders:[{key:'fixture-folder-key',name:'fixture directory',path:['fixture collection','fixture directory'],requestCount:requests.length,note:JSON.stringify({note:${quote(NOTE_CANARY)},pad:'n'.repeat(50000)})}]};};
  window.fetch=async(input,init)=>{const url=new URL(typeof input==='string'?input:input.url,location.href),p=url.pathname,m=(init?.method||'GET').toUpperCase();if(!p.startsWith('/api/')){if(url.origin!==location.origin)throw Error('External fetch forbidden');return originalFetch(input,init)}
    const body=init?.body?JSON.parse(init.body):null;
    if(p==='/api/apitest/import/preview'&&m==='POST'){f.previews.push(body);if(f.holdPreview)await new Promise(resolve=>f.previewGates.push(resolve));return reply(buildPreview(body))}
    if(p==='/api/apitest/import/commit'&&m==='POST'){f.commits.push(body);if(f.holdCommit)await new Promise(resolve=>f.commitGates.push(resolve));return reply({environmentName:'fixture imported environment',caseIds:[11]})}
    if(m!=='GET'){f.unexpectedWrites.push({p,m});return reply({message:'All real mutations forbidden by fixture'},409)}
    if(p==='/api/meta')return reply({name:'JSON file isolated fixture',version:'fixture'});
    if(p==='/api/devices'||p==='/api/apitest/cases'||p==='/api/apitest/case-groups'||p==='/api/apitest/scenarios'||p==='/api/apitest/batches'||p==='/api/apitest/codes'||p==='/api/apitest/execs/recent'||p==='/api/perf/games'||p==='/api/perf/runs/active')return reply([]);
    if(p==='/api/apitest/envs')return reply([{id:1,name:'fixture environment',isDefault:true}]);
    if(p==='/api/apitest/vars')return reply({base:[],header:[],body:[]});
    if(p==='/api/apitest/sign')return reply({env:1,enabled:false,hasSecret:false});
    if(p==='/api/apitest/capabilities')return reply({methods:['GET','POST'],bodyTypes:['NONE','JSON','RAW','URLENCODED'],systemVariables:[]});
    if(p==='/api/perf/runs/page')return reply({items:[],nextCursor:null,hasMore:false});
    f.unconfiguredGets.push(p);return reply([]);
  };
  const style=document.createElement('style');style.textContent='[data-slot="dialog-content"][data-state="closed"],[data-slot="dialog-content"][data-closed]{animation-duration:2s!important}';
  if(document.head)document.head.append(style);else document.addEventListener('DOMContentLoaded',()=>document.head.append(style),{once:true});
})()`

try {
  fixtureDir = await mkdtemp(join(tmpdir(), 'sudpanel-json-file-fixtures-'))
  await mkdir(OUTPUT, { recursive: true })
  const collection = count => ({ info: { name: 'synthetic JSON file fixture', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json' }, item: Array.from({ length: count }, (_, i) => ({ name: 'JSON-FILE-REQ-' + String(i + 1).padStart(3, '0'), request: { method: 'POST', url: 'https://example.invalid/never-send', body: { mode: 'raw', raw: '{"id":' + LONG + '}' } } })) })
  const small = JSON.stringify(collection(1)), shell = { ...collection(100), padding: '' }
  const targetBytes = 218 * 1024, base = JSON.stringify(shell)
  contents['100-requests-218KB.json'] = JSON.stringify({ ...shell, padding: 'p'.repeat(targetBytes - Buffer.byteLength(base)) })
  if (Buffer.byteLength(contents['100-requests-218KB.json']) !== targetBytes) throw Error('218KB fixture size mismatch')
  contents['seed.json'] = small
  contents['new-file.json'] = small.replace('JSON-FILE-REQ-001', 'JSON-FILE-NEW')
  contents['late-cancel.json'] = small.replace('JSON-FILE-REQ-001', 'LATE-CANCEL')
  contents['late-close.json'] = small.replace('JSON-FILE-REQ-001', 'LATE-CLOSE')
  contents['late-format.json'] = small.replace('JSON-FILE-REQ-001', 'LATE-FORMAT')
  contents['late-old.json'] = small.replace('JSON-FILE-REQ-001', 'LATE-OLD')
  contents['bom-long.json'] = '\uFEFF' + small.slice(0, -1) + ',"large":' + LONG + '}'
  contents['environment.json'] = '{"name":"synthetic environment","values":[{"key":"long","value":"' + LONG + '"}]}'
  contents['boundary-2M.json'] = '"' + 'x'.repeat(MAX - 2) + '"'
  contents['boundary-2M-Chinese.json'] = '"' + '中'.repeat(MAX - 2) + '"'
  contents['over-2M.json'] = '"' + 'x'.repeat(MAX - 1) + '"'
  contents['empty.json'] = ''
  contents['bad-json.json'] = '{not json'
  contents['read-fail.json'] = small
  for (const [name, text] of Object.entries(contents)) { paths[name] = join(fixtureDir, name); await writeFile(paths[name], text) }
  paths['bad-utf8.json'] = join(fixtureDir, 'bad-utf8.json'); await writeFile(paths['bad-utf8.json'], Buffer.from([0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xff, 0x22, 0x7d]))
  paths['over-6MB.json'] = join(fixtureDir, 'over-6MB.json'); await writeFile(paths['over-6MB.json'], Buffer.alloc(6_000_001, 0x20))
  browser = await cdp((await (await fetch(CDP + '/json/version')).json()).webSocketDebuggerUrl)
  targetId = (await browser.command('Target.createTarget', { url: 'about:blank' })).targetId
  let target
  for (let i = 0; i < 100 && !target; i++) { target = (await (await fetch(CDP + '/json/list')).json()).find(t => t.id === targetId); if (!target) await pause(30) }
  if (!target) throw Error('Owned target not found')
  page = await cdp(target.webSocketDebuggerUrl)
  await page.command('Page.enable')
  await page.command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  scriptId = (await page.command('Page.addScriptToEvaluateOnNewDocument', { source: injection })).identifier
  if (!scriptId) throw Error('Fixture injection not registered')
  removeNetworkListener = page.on('Fetch.requestPaused', event => { networkApi.push(event.request.url); void page.command('Fetch.failRequest', { requestId: event.requestId, errorReason: 'BlockedByClient' }).catch(() => {}) })
  await page.command('Fetch.enable', { patterns: [{ urlPattern: '*://*/api/*', requestStage: 'Request' }] })

  await section('218KB / 100请求、大文本与惰性详情', async () => {
    await fresh(); await chooseFile('import-COLLECTION', '100-requests-218KB.json'); await loaded('import-COLLECTION', '100-requests-218KB.json')
    await check('文件选择仅加载，不预览/提交/执行业务', 'window.importFileFixture.previews.length===0&&window.importFileFixture.commits.length===0&&window.importFileFixture.unexpectedWrites.length===0')
    await check('文件摘要不创建高亮正文', `document.querySelector('[data-testid="import-COLLECTION-source"]').querySelectorAll('pre').length===0`)
    await editText('import-COLLECTION')
    await check('大文本编辑使用无高亮原生textarea且全文保留', `document.querySelector('#import-COLLECTION')?.tagName==='TEXTAREA'&&document.querySelector('#import-COLLECTION').value===${quote(contents['100-requests-218KB.json'])}&&document.querySelector('[data-testid="import-COLLECTION-source"]').querySelectorAll('pre').length===0`)
    await preview()
    await check('218KB完整原文进入100请求预览', `window.importFileFixture.previews[0].content===${quote(contents['100-requests-218KB.json'])}&&document.querySelector('[data-import-file-preview]').textContent.includes('100 个请求')`)
    await check('闭合请求和目录备注没有挂载正文', `!document.querySelector('[data-import-file-preview]').textContent.includes(${quote(REQUEST_CANARY)})&&!document.querySelector('[data-import-file-preview]').textContent.includes(${quote(NOTE_CANARY)})&&document.querySelector('[data-import-file-preview]').querySelectorAll('pre').length===0`)
    const openDetails = async (text, attribute) => { await ev(`(()=>{const d=[...document.querySelector('[data-import-file-preview]').querySelectorAll('details')].find(d=>d.querySelector(':scope > summary')?.textContent.includes(${quote(text)}));if(!d)throw Error('Missing details');d.setAttribute(${quote(attribute)},'true');d.open=true;return true})()`); await settled() }
    await openDetails('JSON-FILE-REQ-001', 'data-import-request-detail')
    await wait(`document.querySelector('[data-import-request-detail]').textContent.includes(${quote(REQUEST_CANARY)})`)
    await check('展开单个请求才挂正文', `document.querySelector('[data-import-request-detail]').querySelectorAll('pre').length>0`)
    await ev(`(()=>{document.querySelector('[data-import-request-detail]').open=false;return true})()`)
    await wait(`!document.querySelector('[data-import-request-detail]').textContent.includes(${quote(REQUEST_CANARY)})`)
    await openDetails('目录映射', 'data-import-directory-list'); await openDetails('fixture directory', 'data-import-folder-detail')
    await wait(`document.querySelector('[data-import-folder-detail]').textContent.includes(${quote(NOTE_CANARY)})`)
    await check('目录展开后才挂备注正文', `document.querySelector('[data-import-folder-detail]').querySelectorAll('pre').length>0`)
    await ev(`(()=>{document.querySelector('[data-import-folder-detail]').open=false;return true})()`)
    await wait(`!document.querySelector('[data-import-folder-detail]').textContent.includes(${quote(NOTE_CANARY)})`)
  })

  await section('BOM、38位原文、集合加环境与同文件重选', async () => {
    await fresh(); await chooseFile('import-COLLECTION', 'bom-long.json'); await loaded('import-COLLECTION', 'bom-long.json')
    const before = await ev('window.importFileFixture.readCompletions.filter(n=>n==="bom-long.json").length')
    await chooseFile('import-COLLECTION', 'bom-long.json'); await loaded('import-COLLECTION', 'bom-long.json')
    await wait(`window.importFileFixture.readCompletions.filter(n=>n==='bom-long.json').length===${before + 1}`)
    await check('同一个文件可再次选择并重新读取', true)
    await ev(`(()=>{document.querySelector('#import-environment-file').closest('details').open=true;return true})()`)
    await chooseFile('import-environment', 'environment.json'); await loaded('import-environment', 'environment.json'); await preview()
    await check('BOM移除但38位数字原文未重序列化', `window.importFileFixture.previews[0].content===${quote(contents['bom-long.json'].slice(1))}&&window.importFileFixture.previews[0].content.includes(${quote('"large":' + LONG)})`)
    await check('集合和环境文件分别原样交给预览', `window.importFileFixture.previews[0].environmentContent===${quote(contents['environment.json'])}`)
    await clickText('确认追加导入'); await wait('window.importFileFixture.commits.length===1&&!document.querySelector("#import-COLLECTION-file")')
    await check('显式确认才模拟一次提交且集合环境保持原文', `window.importFileFixture.commits.length===1&&window.importFileFixture.commits[0].content===${quote(contents['bom-long.json'].slice(1))}&&window.importFileFixture.commits[0].environmentContent===${quote(contents['environment.json'])}`)
  })

  await section('2M边界与失败保留原文、旧预览失效', async () => {
    await fresh()
    for (const name of ['boundary-2M.json', 'boundary-2M-Chinese.json']) {
      await chooseFile('import-COLLECTION', name); await loaded('import-COLLECTION', name); await editText('import-COLLECTION')
      await check(name + '正好200万字符可读且不高亮', `document.querySelector('#import-COLLECTION').value.length===${MAX}&&document.querySelector('[data-testid="import-COLLECTION-source"]').querySelectorAll('pre').length===0`)
    }
    for (const [name, message] of [['empty.json', '文件为空'], ['bad-json.json', '有效的 JSON'], ['bad-utf8.json', 'UTF-8'], ['over-2M.json', '200 万字符'], ['over-6MB.json', '6 MB'], ['read-fail.json', '无法读取文件']]) {
      await seed(); await preview()
      if (name === 'read-fail.json') await ev(`(()=>{window.importFileFixture.failFiles.push('read-fail.json');return true})()`)
      await chooseFile('import-COLLECTION', name)
      await wait(`document.querySelector('[data-testid="import-COLLECTION-source"] [role=alert]')?.textContent.includes(${quote(message)})`)
      await check(name + '失败使旧预览失效', `!document.querySelector('[data-import-file-preview]')&&[...document.querySelectorAll('[role=dialog] button')].find(b=>b.textContent.trim()==='确认追加导入').disabled`)
      await editText('import-COLLECTION')
      await check(name + '失败保留原文且不提交', `document.querySelector('#import-COLLECTION').value===${quote(contents['seed.json'])}&&window.importFileFixture.commits.length===0`)
    }
  })

  await section('读取门闩：取消、关闭动画、换格式与新文件', async () => {
    await fresh(); await seed(); await preview(); await hold('late-cancel.json'); await chooseFile('import-COLLECTION', 'late-cancel.json'); await gateReady('late-cancel.json')
    await wait(`document.querySelector('[data-testid="import-COLLECTION-source"]').textContent.includes('正在读取文件')`)
    await check('读取期间禁预览/确认且旧预览立即失效', `![...document.querySelectorAll('[role=dialog] button')].filter(b=>['预览导入','确认追加导入'].includes(b.textContent.trim())).some(b=>!b.disabled)&&!document.querySelector('[data-import-file-preview]')`)
    await clickText('取消读取', '[data-testid="import-COLLECTION-source"]'); await release('late-cancel.json'); await editText('import-COLLECTION')
    await check('取消后的迟到文件不覆盖原文', `document.querySelector('#import-COLLECTION').value===${quote(contents['seed.json'])}`)

    await hold('late-close.json'); await chooseFile('import-COLLECTION', 'late-close.json'); await gateReady('late-close.json')
    for (const type of ['keyDown', 'keyUp']) await page.command('Input.dispatchKeyEvent', { type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
    // Release BEFORE animation end/unmount. This specifically detects the close-vs-unmount gap.
    await wait(`!!document.querySelector('[data-slot="dialog-content"][data-state="closed"],[data-slot="dialog-content"][data-closed]')`)
    await release('late-close.json')
    await wait(`!document.querySelector('[role=dialog]')&&!document.querySelector('[data-slot="dialog-overlay"]')`, 10000); await openCollection(); await editText('import-COLLECTION')
    await check('关闭动画尚在时释放读取，重开仍保留原文', `document.querySelector('#import-COLLECTION').value===${quote(contents['seed.json'])}`)

    await hold('late-format.json'); await chooseFile('import-COLLECTION', 'late-format.json'); await gateReady('late-format.json'); await change('[aria-label="导入格式"]', 'OPENAPI')
    await wait(`document.querySelector('[aria-label="导入格式"]').value==='OPENAPI'`); await release('late-format.json'); await editText('import-COLLECTION')
    await check('换格式作废旧读取且不覆盖正文', `document.querySelector('[aria-label="导入格式"]').value==='OPENAPI'&&document.querySelector('#import-COLLECTION').value===${quote(contents['seed.json'])}`)
    await change('[aria-label="导入格式"]', 'POSTMAN'); await wait(`!!document.querySelector('#import-environment-file')`)
    await hold('late-old.json'); await chooseFile('import-COLLECTION', 'late-old.json'); await gateReady('late-old.json'); await chooseFile('import-COLLECTION', 'new-file.json'); await loaded('import-COLLECTION', 'new-file.json'); await release('late-old.json'); await preview()
    await check('新文件先成功后旧读取迟到仍保留新文件', `window.importFileFixture.previews.at(-1).content===${quote(contents['new-file.json'])}&&document.querySelector('[data-testid="import-COLLECTION-filename"]').textContent==='new-file.json'`)
  })

  await section('预览与提交在途禁止重复动作', async () => {
    await fresh(); await seed(); await ev('(()=>{window.importFileFixture.holdPreview=true;return true})()'); await clickText('预览导入'); await wait('window.importFileFixture.previewGates.length===1')
    await ev(`(()=>{for(const b of document.querySelectorAll('[role=dialog] button'))if(['预览导入','确认追加导入'].includes(b.textContent.trim()))b.click();return true})()`)
    await check('预览在途重复点击不产生第二请求或提交', 'window.importFileFixture.previews.length===1&&window.importFileFixture.commits.length===0&&document.querySelector("[role=dialog] fieldset").disabled')
    await ev('(()=>{window.importFileFixture.holdPreview=false;window.importFileFixture.previewGates.shift()();return true})()'); await wait(`![...document.querySelectorAll('[role=dialog] button')].find(b=>b.textContent.trim()==='确认追加导入').disabled`)
    await ev('(()=>{window.importFileFixture.holdCommit=true;return true})()'); await clickText('确认追加导入'); await wait('window.importFileFixture.commitGates.length===1')
    await ev(`(()=>{[...document.querySelectorAll('[role=dialog] button')].find(b=>b.textContent.trim()==='确认追加导入').click();return true})()`)
    await check('提交在途再次点击只有一次模拟提交', 'window.importFileFixture.commits.length===1&&document.querySelector("[role=dialog] fieldset").disabled')
    await ev('(()=>{window.importFileFixture.holdCommit=false;window.importFileFixture.commitGates.shift()();return true})()'); await wait('!document.querySelector("#import-COLLECTION-file")')
    await check('没有真实API、业务发送或未配置写操作', `window.importFileFixture.unexpectedWrites.length===0&&${networkApi.length === 0}`)
  })
} catch (error) { checks.push({ name: '专项基础设施完整执行', passed: false }); console.error(error.stack || error) }
finally {
  if (page) {
    try { await ev('(()=>{const f=window.importFileFixture;if(f){for(const g of f.readGates)if(!g.released)g.release();for(const r of f.previewGates)r();for(const r of f.commitGates)r()}return true})()') } catch {}
    if (scriptId) try { await page.command('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId }) } catch {}
    try { await page.command('Fetch.disable') } catch {}
  }
  removeNetworkListener?.()
  if (targetId && browser) try { await browser.command('Target.closeTarget', { targetId }) } catch {}
  page?.close(); browser?.close()
  if (fixtureDir) await rm(fixtureDir, { recursive: true, force: true })
  checks.push({ name: '真实API网络请求为零', passed: networkApi.length === 0 })
  const failed = checks.filter(c => !c.passed).length
  const summary = { total: checks.length, passed: checks.length - failed, failed, checks, sections, realApiRequests: networkApi.length, trueDeviceChecks: 0, fixtureBytes: 218 * 1024, syntheticRequestCount: 100 }
  await mkdir(OUTPUT, { recursive: true })
  await writeFile(process.env.SP_UI_SUMMARY || join(OUTPUT, 'import-file-ui-summary.json'), JSON.stringify(summary, null, 2))
  console.log(`JSON文件导入专项：${summary.passed}/${summary.total}，失败 ${failed}；${OUTPUT}`)
  if (failed) process.exitCode = 1
}
