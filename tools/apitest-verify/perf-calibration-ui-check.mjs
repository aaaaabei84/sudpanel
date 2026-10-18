import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 用虚构设备和分组验证模式启动门槛；所有性能请求均由浏览器 mock 接管。
const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve))
let next = 0, total = 0, failures = 0, scriptId
const pending = new Map()
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data)
  if (pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})
const send = (method, params) => new Promise((resolve, reject) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout: ${method}`)) }, 10000)
  pending.set(id, msg => { clearTimeout(timer); resolve(msg) })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const msg = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (msg.error || msg.result.exceptionDetails) throw Error(JSON.stringify(msg.error || msg.result.exceptionDetails))
  return msg.result.result.value
}
const waitFor = async expression => {
  for (let i = 0; i < 60; i++) { if (await ev(expression)) return; await sleep(50) }
  throw Error(`fixture not ready: ${expression}`)
}
const clickText = text => ev(`(()=>{const e=[...document.querySelectorAll('button,[role=option]')].find(x=>x.textContent.trim()===${JSON.stringify(text)});if(!e)throw Error('missing control');e.click()})()`)
const check = (name, ok, detail) => { total++; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail)}`) }
const startState = () => ev(`(()=>{const e=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='开始');return {disabled:e.disabled,title:e.title}})()`)
const chooseMode = async text => {
  await ev(`document.querySelector('[data-testid="perf-mode"]').click()`)
  await waitFor(`!!document.querySelector('[role=option]')`)
  await clickText(text)
  await waitFor(`!document.querySelector('[role=option]')`)
}
const enter = async (groupState = 'missing') => {
  await send('Page.navigate', { url: APP })
  await waitFor(`!!window.perfGate && [...document.querySelectorAll('button')].some(x=>x.textContent.trim()==='性能测试')`)
  await ev(`window.perfGate.groupState=${JSON.stringify(groupState)}`)
  await clickText('性能测试')
  await waitFor(`!!document.querySelector('[data-testid="perf-mode"]') && document.body.textContent.includes('GATE-GAME') && window.perfGate.groupsLoaded`)
  if (groupState !== 'none') await waitFor(`document.body.textContent.includes('GATE-GROUP')`)
  await clickText('全选')
}
const startCalled = async mode => {
  await clickText('开始')
  await sleep(150)
  const calls = await ev('window.perfGate.starts')
  check(`${mode}请求包含所选设备、游戏与模式`, calls.length === 1 && calls[0].mode === mode && calls[0].deviceSerial === 'MOCK-PERF-DEVICE' && JSON.stringify(calls[0].gameIds) === '[501]', calls)
}
try {
  await send('Page.enable', {})
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  const result = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    const original=window.fetch;
    window.perfGate={groupState:'missing',groupsLoaded:false,starts:[],unexpected:[]};
    window.fetch=async(input,init)=>{
      const rawPath=new URL(typeof input==='string'?input:input.url,location.href).pathname;const path=(rawPath==='/api/perf/runs/page'||rawPath==='/api/perf/runs/active')?'/api/perf/runs':rawPath;
      const method=(init?.method||'GET').toUpperCase(),g=window.perfGate;
      let body;
      if(path==='/api/devices')body=[{serial:'MOCK-PERF-DEVICE',status:'device',model:'MOCK'}];
      else if(path==='/api/perf/games')body=[{id:501,name:'GATE-GAME',shortName:'GATE-GAME'}];
      else if(path==='/api/perf/button-groups'){
        body=g.groupState==='none'?[]:[{id:701,name:'GATE-GROUP',items:[{key:'mock_required',label:'MOCK必标按钮',required:true,calibrated:g.groupState==='ready'}]}];
        g.groupsLoaded=true;
      }else if(path==='/api/perf/runs' && method==='POST'){
        g.starts.push(JSON.parse(init.body));
        // 返回可控错误，阻止轮询接管；这里验的是请求门槛，不创建业务运行。
        return new Response(JSON.stringify({message:'MOCK_START_CAPTURED'}),{status:409,headers:{'Content-Type':'application/json'}});
      }else if(path==='/api/perf/runs')body=[];
      else if(path==='/api/perf/ai-room'||path==='/api/perf/options')body={};
      else if(path.startsWith('/api/perf/')){g.unexpected.push({path,method});return new Response('{}',{status:409});}
      else return original(input,init);
      if(rawPath==='/api/perf/runs/page')body={items:body,nextCursor:null,hasMore:false};else if(rawPath==='/api/perf/runs/active')body=Array.isArray(body)?body.filter(run=>['RUNNING','WAIT_DEVICE','WAIT_ENV'].includes(run.status)):[];
      return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});
    }` })
  scriptId = result.result.identifier
  await enter()
  let state = await startState()
  check('NORMAL未标定分组不禁用开始', !state.disabled, state)
  check('NORMAL不显示标定阻断标题', !state.title, state)
  await startCalled('NORMAL')
  await enter()
  await chooseMode('游戏内性能监测')
  state = await startState()
  check('AI_ROOM未标定分组不禁用开始', !state.disabled && !state.title, state)
  await startCalled('AI_ROOM')
  await enter()
  await chooseMode('异常时序（提前结算）')
  state = await startState()
  check('EARLY_SETTLE未标定仍禁用并提示缺项', state.disabled && state.title.includes('MOCK必标按钮'), state)
  await clickText('开始')
  check('EARLY_SETTLE未标定不发送启动请求', (await ev('window.perfGate.starts.length')) === 0)
  await enter('ready')
  await chooseMode('异常时序（提前结算）')
  state = await startState()
  check('EARLY_SETTLE已标定允许开始', !state.disabled && !state.title, state)
  await startCalled('EARLY_SETTLE')
  await enter('none')
  state = await startState()
  check('NORMAL没有坐标分组也允许开始', !state.disabled && !state.title, state)
  await startCalled('NORMAL')
  check('未发送其他性能操作', (await ev('window.perfGate.unexpected.length')) === 0)
} finally {
  if (scriptId) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId })
  ws.close()
}
console.log(`性能模式标定回归：${total - failures}/${total}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
