import { executable, chromeExecutable, browserIsolationFlags } from './runtime.mjs'
// 两步完整数据迁移的隔离浏览器回归。API 全夹具，下载也由 CDP 拦截，不启动后端或访问运行数据。
import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, writeFile, readFile, open, rm } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const frontend = resolve(root, 'frontend')
const port = Number(process.env.SP_TRANSFER_UI_PORT || 18937)
const cdpPort = Number(process.env.SP_TRANSFER_UI_CDP_PORT || 9247)
const forbiddenPorts = [18790, 18890, 18795, 18797, 18799, 18888, 18889, 9222, 9223, 9239]
if (![port, cdpPort].every(value => Number.isInteger(value) && value > 1024 && value < 65536 && !forbiddenPorts.includes(value)) || port === cdpPort) throw Error('必须使用独立隔离端口')
const temp = await mkdtemp(resolve(tmpdir(), 'sudpanel-transfer-ui-'))
const artifacts = process.env.SP_TRANSFER_UI_OUTPUT ? resolve(process.env.SP_TRANSFER_UI_OUTPUT) : temp
await mkdir(artifacts, { recursive: true })
const children = [], pending = new Map(), results = [], apiCalls = [], blocked = [], downloadRequests = []
let socket, counter = 0, navigations = 0, scenario = 'startup'
const fixtureBytes = [80, 75, 3, 4, 7, 11, 23, 0, 255, 128, 42]
const fixturePath = resolve(temp, 'fixture.zip'), oversizedPath = resolve(temp, 'oversized.zip')
await writeFile(fixturePath, new Uint8Array(fixtureBytes))
await writeFile(resolve(temp, 'empty.zip'), '')
await writeFile(resolve(temp, 'fixture.txt'), 'fixture')
const oversized = await open(oversizedPath, 'wx'); await oversized.truncate(256 * 1024 * 1024 + 1); await oversized.close()
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function assertPortFree(value) {
  await new Promise((resolve, reject) => {
    const server = createServer(); server.once('error', () => reject(Error('隔离端口已占用，不复用或终止已有进程：' + value)))
    server.listen(value, '127.0.0.1', () => server.close(resolve))
  })
}
function start(command, args, cwd, label) {
  const child = spawn(command, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] })
  const record = { child, label, output: '' }; children.push(record)
  child.stdout.on('data', value => { record.output += value }); child.stderr.on('data', value => { record.output += value })
  child.on('error', error => { record.output += '\n' + error.message })
  return child
}
async function available(url) { try { return (await fetch(url)).ok } catch { return false } }
async function waitFor(test, label, timeout = 15000) {
  const end = performance.now() + timeout
  while (performance.now() < end) { if (await test()) return; await delay(50) }
  throw Error('等待超时：' + label)
}
function command(method, params = {}) {
  const id = ++counter
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(Error('CDP期限：' + method)) }, 15000)
    pending.set(id, { resolve, reject, timer }); socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const value = await command('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (value.exceptionDetails) throw Error(value.exceptionDetails.exception?.description || value.exceptionDetails.text)
  return value.result?.value
}
async function click(text) {
  await evaluate(`(()=>{const e=[...document.querySelectorAll('button')].find(e=>e.textContent.trim()===${JSON.stringify(text)});if(!e||e.disabled)throw Error('按钮不可用：'+${JSON.stringify(text)});e.click()})()`)
}
async function has(text) { await waitFor(() => evaluate(`document.body.innerText.includes(${JSON.stringify(text)})`), text) }
async function upload(path = fixturePath) {
  const document = await command('DOM.getDocument')
  const input = await command('DOM.querySelector', { nodeId: document.root.nodeId, selector: 'input[aria-label="上传并导入全部数据"]' })
  if (!input.nodeId) throw Error('迁移文件输入框缺失')
  await command('DOM.setFileInputFiles', { nodeId: input.nodeId, files: [path] })
}
const calls = (path, method) => apiCalls.filter(value => value.scenario === scenario && value.path === path && (!method || value.method === method))
const imports = () => calls('/api/maintenance/transfers/import', 'POST')
async function check(name, test) {
  try { await test(); results.push({ name, ok: true }); console.log('PASS ' + name) }
  catch (error) { results.push({ name, ok: false, error: error.message }); console.error('FAIL ' + name + ': ' + error.message); throw error }
}
const fixtureScript = `(()=>{
  const options=JSON.parse(sessionStorage.getItem('transfer-test-options')||'{}');
  const f=window.__fixture={case:options.case,calls:[],stats:{},mode:options.mode||'pending',operationId:null,statusReads:0,clockShift:0,releaseImport:null,releaseExport:null,terminal:null};
  const nativeNow=Date.now;Date.now=()=>nativeNow()+f.clockShift;
  const originalFetch=window.fetch.bind(window);
  const report=value=>window.__transferEvent(JSON.stringify(value));
  const reply=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
  window.fetch=async(input,init={})=>{
    const url=new URL(typeof input==='string'?input:input.url,location.href),path=url.pathname,method=init.method||'GET';
    if(!path.startsWith('/api/'))return originalFetch(input,init);
    const headers=new Headers(init.headers),call={path,query:url.search,method,id:headers.get('X-Transfer-Id'),contentType:headers.get('Content-Type')};
    if(init.body instanceof Blob)call.bytes=Array.from(new Uint8Array(await init.body.arrayBuffer()));
    f.calls.push(call);report({type:'api',...call});
    if(path==='/api/maintenance/overview')return reply({tables:[{name:'api_exec',rows:5}],databaseBytes:2048,active:false,retention:{apiDays:30,perfDays:null,functionDays:null},pendingRestore:{staged:false,restartRequired:false,lastResult:{id:'old-result',status:'RESTORED'}},warnings:[]});
    if(path==='/api/maintenance/backups')return reply([]);
    if(path.startsWith('/api/apitest/history'))return reply({items:[],nextBeforeId:null});
    if(path==='/api/apitest/baselines')return reply([]);
    if(path==='/api/maintenance/transfers/export'&&method==='POST'){
      const result={id:'fixture-export',createdAt:'2026-10-16T00:00:00Z',bytes:${fixtureBytes.length},sha256:'fixture-only'};
      if(options.holdExport)return await new Promise(resolve=>f.releaseExport=()=>resolve(reply(result)));
      return reply(result);
    }
    if(path==='/api/maintenance/transfers/import'&&method==='POST'){
      f.operationId=call.id;
      if(f.mode==='reject')return reply({message:'fixture import rejected'},400);
      if(f.mode==='lost')throw new TypeError('fixture reply lost');
      if(f.mode==='holdImport')return await new Promise(resolve=>f.releaseImport=()=>resolve(reply({id:f.operationId,status:'RESTARTING',restartRequired:true})));
      return reply({id:f.operationId,status:'RESTARTING',restartRequired:true});
    }
    if(path==='/api/maintenance/transfers/status'&&method==='GET'){
      f.statusReads++;
      if(f.mode==='offline'&&f.statusReads<=2)throw new TypeError('fixture restart offline');
      const id=f.operationId||url.searchParams.get('operationId');
      const terminal=f.terminal||(['REFUSED','ROLLED_BACK'].includes(f.mode)?f.mode:null);
      return reply({staged:!terminal,restartRequired:!terminal,id,lastResult:terminal?{id,status:terminal,error:terminal==='RESTORED'?undefined:'fixture controlled failure'}:{id:'old-result',status:'RESTORED'}});
    }
    report({type:'blocked',path,method});throw Error('未声明的隔离API');
  };
})()`
async function newScenario(name, options = {}) {
  scenario = name
  await evaluate(`sessionStorage.clear();sessionStorage.setItem('transfer-test-options',${JSON.stringify(JSON.stringify({ ...options, case: name }))})`)
  await command('Page.reload')
  await waitFor(() => evaluate(`window.__harnessReady===true&&window.__fixture?.case===${JSON.stringify(name)}`), '迁移夹具装载')
  await click('维护'); await has('当前无活动任务')
  await waitFor(() => evaluate(`![...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='上传并导入全部数据').disabled`), '维护就绪')
}
try {
  await assertPortFree(port); await assertPortFree(cdpPort)
  start(executable(process.env.NODE || process.execPath), [resolve(frontend, 'node_modules/vite/bin/vite.js'), '--config', resolve(root, 'tools/governance-verify/vite.config.mjs'), '--port', String(port)], frontend, 'vite')
  await waitFor(() => available(`http://127.0.0.1:${port}/governance-check.html`), '隔离Vite')
  const chrome = chromeExecutable()
  start(chrome, ['--headless=new', ...browserIsolationFlags, `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${resolve(temp, 'chrome')}`, '--no-first-run', '--no-default-browser-check', '--no-proxy-server', 'about:blank'], frontend, 'chrome')
  await waitFor(() => available(`http://127.0.0.1:${cdpPort}/json/version`), '隔离Chrome')
  const target = await fetch(`http://127.0.0.1:${cdpPort}/json/new?about:blank`, { method: 'PUT' }).then(response => response.json())
  socket = new WebSocket(target.webSocketDebuggerUrl); await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data), request = pending.get(value.id)
    if (request) { clearTimeout(request.timer); pending.delete(value.id); value.error ? request.reject(Error(value.error.message)) : request.resolve(value.result) }
    if (value.method === 'Page.frameNavigated' && !value.params.frame.parentId) navigations++
    if (value.method === 'Runtime.bindingCalled' && value.params.name === '__transferEvent') {
      const record = { scenario, ...JSON.parse(value.params.payload) }
      if (record.type === 'api') apiCalls.push(record); else blocked.push(record)
    }
    if (value.method === 'Fetch.requestPaused') void (async () => {
      const { requestId, request: paused } = value.params
      const path = new URL(paused.url).pathname
      if (path === '/api/maintenance/transfers/fixture-export/download' && paused.method === 'GET') {
        downloadRequests.push({ scenario, path })
        await command('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/zip' }, { name: 'Content-Disposition', value: 'attachment; filename="fixture-all-data.zip"' }], body: Buffer.from(fixtureBytes).toString('base64') })
      } else {
        blocked.push({ scenario, type: 'network-api', path, method: paused.method })
        await command('Fetch.fulfillRequest', { requestId, responseCode: 409, body: Buffer.from('fixture rejected unexpected network API').toString('base64') })
      }
    })().catch(error => blocked.push({ scenario, type: 'interception-error', error: error.message }))
  })
  await command('Page.enable'); await command('Runtime.enable'); await command('Runtime.addBinding', { name: '__transferEvent' })
  await command('Fetch.enable', { patterns: [{ urlPattern: '*://*/api/*', requestStage: 'Request' }] })
  await command('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: resolve(temp, 'downloads'), eventsEnabled: true })
  await command('Page.addScriptToEvaluateOnNewDocument', { source: fixtureScript })
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await command('Page.navigate', { url: `http://127.0.0.1:${port}/governance-check.html` })
  await waitFor(() => evaluate('window.__harnessReady===true'), '治理夹具')

  await newScenario('export', { holdExport: true })
  await check('主迁移仅两按钮，高级恢复默认折叠', async () => {
    const state = await evaluate(`(()=>{const d=[...document.querySelectorAll('details')].find(d=>d.querySelector('summary')?.textContent==='高级备份恢复（可选）');return {closed:!!d&&!d.open,sha:document.body.innerText.includes('复制SHA256'),buttons:[...document.querySelector('[aria-label="全部数据迁移"]').querySelectorAll('button')].map(b=>b.textContent.trim())}})()`)
    if (!state.closed || state.sha || state.buttons.join('|') !== '打包并下载全部数据|上传并导入全部数据') throw Error(JSON.stringify(state))
  })
  await check('同帧两次打包只POST一次，并自动下载原始字节', async () => {
    await evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='打包并下载全部数据');b.click();b.click()})()`)
    await waitFor(() => calls('/api/maintenance/transfers/export', 'POST').length === 1, '一次打包POST')
    await delay(150)
    if (calls('/api/maintenance/transfers/export', 'POST').length !== 1) throw Error('打包重复提交')
    await evaluate('window.__fixture.releaseExport()')
    await waitFor(() => downloadRequests.filter(value => value.scenario === scenario).length === 1, '自动下载GET')
    const path = resolve(temp, 'downloads/fixture-all-data.zip')
    await waitFor(() => existsSync(path), '浏览器下载完成')
    if (!(await readFile(path)).equals(Buffer.from(fixtureBytes))) throw Error('下载字节不一致')
  })

  await newScenario('matching-result')
  await check('选ZIP即上传UUID与原字节，无SHA或确认步骤', async () => {
    await upload(); await waitFor(() => imports().length === 1, '自动上传')
    const imported = imports()[0]
    if (!/^[a-f0-9-]{36}$/i.test(imported.id) || imported.contentType !== 'application/octet-stream' || JSON.stringify(imported.bytes) !== JSON.stringify(fixtureBytes)) throw Error('上传编号、类型或字节不符')
    const stored = await evaluate(`JSON.parse(sessionStorage.getItem('sudpanel:data-transfer:v1')).id`)
    if (stored !== imported.id) throw Error('上传前未保存本次编号')
  })
  await check('旧编号RESTORED不假成功，状态GET带本次operationId', async () => {
    const before = navigations
    await waitFor(() => evaluate('window.__fixture.statusReads>=2'), '两次旧结果查询')
    if (navigations !== before || !await evaluate(`!!sessionStorage.getItem('sudpanel:data-transfer:v1')`)) throw Error('旧结果触发成功刷新')
    const current = imports()[0].id
    if (!calls('/api/maintenance/transfers/status', 'GET').every(value => new URLSearchParams(value.query).get('operationId') === current)) throw Error('查询未绑定本次编号')
  })
  await check('等待期间禁止关闭与刷新，不能再次上传', async () => {
    await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape' })
    await command('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape' })
    const state = await evaluate(`(()=>{const e=new Event('beforeunload',{cancelable:true});window.dispatchEvent(e);return {dialog:!!document.querySelector('[role=dialog]'),blocked:e.defaultPrevented,upload:[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='上传并导入全部数据').disabled}})()`)
    if (!state.dialog || !state.blocked || !state.upload) throw Error(JSON.stringify(state))
    if (imports().length !== 1) throw Error('等待时重发上传')
  })
  await check('仅本次RESTORED触发自动刷新并清理编号', async () => {
    const before = navigations; await evaluate(`window.__fixture.terminal='RESTORED'`)
    await waitFor(() => navigations > before, '成功自动刷新')
    await waitFor(() => evaluate(`window.__harnessReady===true&&!sessionStorage.getItem('sudpanel:data-transfer:v1')`), '成功清理编号')
    if (imports().length !== 1) throw Error('成功前重复上传')
  })

  for (const status of ['REFUSED', 'ROLLED_BACK']) {
    await newScenario(status, { mode: status })
    await check(status + '不算成功且可重新选择重试', async () => {
      const before = navigations; await upload(); await has(status === 'REFUSED' ? '平台已拒绝本次导入' : '导入失败，平台已回滚原数据')
      await waitFor(() => evaluate(`![...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='上传并导入全部数据').disabled`), '失败后恢复上传')
      const first = imports()[0]?.id; await upload(); await waitFor(() => imports().length === 2, '失败后第二次上传')
      await waitFor(() => evaluate(`!sessionStorage.getItem('sudpanel:data-transfer:v1')`), '重试终态')
      if (imports()[1].id === first || navigations !== before) throw Error('失败刷新或重试复用了旧编号')
    })
  }

  await newScenario('unknown', { mode: 'lost' })
  await check('上传回复未知只查GET，超时显示重新检查且不重POST', async () => {
    await upload(); await waitFor(() => evaluate('window.__fixture.statusReads>=1'), '未知回复后的状态查询')
    await evaluate('window.__fixture.clockShift=180000') // 只推进等待截止，避免实等两分钟。
    await has('重新检查状态')
    if (imports().length !== 1 || !await evaluate(`!!sessionStorage.getItem('sudpanel:data-transfer:v1')`)) throw Error('未知回复丢编号或重复上传')
    const before = calls('/api/maintenance/transfers/status', 'GET').length
    await click('重新检查状态')
    await waitFor(() => calls('/api/maintenance/transfers/status', 'GET').length > before, '只读重查')
    if (imports().length !== 1) throw Error('重新检查重发POST')
    await evaluate(`window.__fixture.terminal='REFUSED'`); await has('平台已拒绝本次导入')
  })

  await newScenario('offline', { mode: 'offline' })
  await check('自动重启离线后仍只等待本次明确结果', async () => {
    const before = navigations; await upload(); await waitFor(() => evaluate('window.__fixture.statusReads>=3'), '离线后的重新查询')
    if (navigations !== before || imports().length !== 1) throw Error('离线在线被误判成功或重传')
    await evaluate(`window.__fixture.terminal='RESTORED'`); await waitFor(() => navigations > before, '离线恢复后的匹配终态')
  })

  await newScenario('double-upload', { mode: 'holdImport' })
  await check('同帧两次文件change只上传一次', async () => {
    await evaluate(`(()=>{const e=document.querySelector('input[aria-label="上传并导入全部数据"]');for(let i=0;i<2;i++){const d=new DataTransfer();d.items.add(new File([new Uint8Array(${JSON.stringify(fixtureBytes)})],'fixture.zip'));e.files=d.files;e.dispatchEvent(new Event('change',{bubbles:true}))}})()`)
    await waitFor(() => imports().length === 1, '一次文件上传'); await delay(150)
    if (imports().length !== 1) throw Error('同帧文件变动重复上传')
    await evaluate(`window.__fixture.terminal='REFUSED';window.__fixture.releaseImport()`); await has('平台已拒绝本次导入')
  })

  await newScenario('validation')
  await check('256MiB上限在上传前拦截，原始File不进入POST', async () => {
    await upload(oversizedPath); await has('数据包超过256MB')
    if (imports().length) throw Error('超限文件已上传')
  })
  await check('空ZIP、非ZIP和取消选择均不上传', async () => {
    await upload(resolve(temp, 'empty.zip')); await has('请选择平台打包下载的非空 ZIP 数据包')
    await upload(resolve(temp, 'fixture.txt')); await has('请选择平台打包下载的非空 ZIP 数据包')
    await evaluate(`(()=>{const e=document.querySelector('input[aria-label="上传并导入全部数据"]');e.files=new DataTransfer().files;e.dispatchEvent(new Event('change',{bubbles:true}))})()`)
    if (imports().length) throw Error('无效文件或取消选择触发上传')
  })
  await newScenario('reject', { mode: 'reject' })
  await check('确定4xx拒绝解除等待，允许用户修正后重试', async () => {
    await upload(); await has('fixture import rejected')
    if (await evaluate(`!!sessionStorage.getItem('sudpanel:data-transfer:v1')`)) throw Error('4xx残留未决编号')
    await upload(); await waitFor(() => imports().length === 2, '4xx后重试'); await has('fixture import rejected')
  })
  await check('所有API为夹具且没有未声明网络请求', async () => { if (blocked.length) throw Error(JSON.stringify(blocked)) })
  for (const width of [1440, 390]) {
    await command('Emulation.setDeviceMetricsOverride', { width, height: width === 390 ? 844 : 1000, deviceScaleFactor: 1, mobile: false })
    await delay(200)
    await check(width + 'px迁移按钮在视口内且无横向溢出', async () => {
      const state = await evaluate(`(()=>{const buttons=[...document.querySelector('[aria-label="全部数据迁移"]').querySelectorAll('button')];buttons[0].scrollIntoView({block:'center'});return {width:innerWidth,overflow:document.documentElement.scrollWidth>innerWidth+1,buttons:buttons.map(b=>{const r=b.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.width>0})}})()`)
      if (state.width !== width || state.overflow || state.buttons.some(value => !value)) throw Error(JSON.stringify(state))
    })
    const screenshot = await command('Page.captureScreenshot', { format: 'png' }); await writeFile(resolve(artifacts, `transfer-ui-${width}.png`), Buffer.from(screenshot.data, 'base64'))
  }
} catch (error) { console.error(error.stack); process.exitCode = 1 }
finally {
  if (socket?.readyState === WebSocket.OPEN) { try { await command('Fetch.disable') } catch { /* 专用浏览器即将清理。 */ } socket.close() }
  for (const request of pending.values()) { clearTimeout(request.timer); request.reject(Error('runner cleanup')) }
  for (const record of children.reverse()) if (record.child.exitCode == null) record.child.kill('SIGTERM')
  await delay(400)
  for (const record of children) {
    if (record.child.exitCode == null) record.child.kill('SIGKILL')
    await writeFile(resolve(artifacts, `${record.label}.log`), record.output)
  }
  const summary = { passed: results.filter(value => value.ok).length, failed: results.filter(value => !value.ok).length, total: results.length, completed: !process.exitCode, results, apiCalls, blocked, downloads: downloadRequests, notes: '仅隔离Vite源码和浏览器夹具，无真实后端/ADB/数据库；超时场景只推进Date.now，不宣称真实两分钟等待验收。' }
  await writeFile(resolve(artifacts, 'summary.json'), JSON.stringify(summary, null, 2))
  console.log(JSON.stringify({ passed: summary.passed, failed: summary.failed, total: summary.total, completed: summary.completed, artifacts }))
  if (artifacts !== temp) await rm(temp, { recursive: true, force: true })
}
