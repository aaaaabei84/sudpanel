import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 活动运行等待、恢复与停止回归；设备和全部性能请求只使用浏览器 mock。
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
  for (let i = 0; i < 100; i++) { if (await ev(expression)) return; await sleep(50) }
  throw Error(`fixture not ready: ${expression}`)
}
const clickText = text => ev(`(()=>{const e=[...document.querySelectorAll('button,[role=option]')].find(x=>x.textContent.trim()===${JSON.stringify(text)});if(!e)throw Error('missing control');e.click()})()`)
const check = (name, ok, detail) => { total++; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail)}`) }
const controls = () => ev(`(()=>{const bs=[...document.querySelectorAll('button')];return {startDisabled:bs.find(b=>b.textContent.trim()==='开始').disabled,stop:bs.some(b=>b.textContent.trim()==='停止'),rejudge:bs.some(b=>b.textContent.trim()==='重算判定'),remove:bs.some(b=>b.textContent.trim()==='删除')}})()`)
const enter = async status => {
  await send('Page.navigate', { url: APP })
  await waitFor(`!!window.perfWait && [...document.querySelectorAll('button')].some(x=>x.textContent.trim()==='性能测试')`)
  await ev(`window.perfWait.status=${JSON.stringify(status)}`)
  await clickText('性能测试')
  await waitFor(`!!document.querySelector('[data-testid="perf-mode"]') && window.perfWait.polls>0 && document.body.textContent.includes(${JSON.stringify(status)})`)
  await clickText('全选')
}
const selectHistory = async () => {
  await ev(`document.querySelectorAll('[role=combobox]').forEach(e=>{if(e.textContent.includes('选择运行'))e.click()})`)
  await waitFor(`!!document.querySelector('[role=option]')`)
  await ev(`document.querySelector('[role=option]').click()`)
  await waitFor(`!document.querySelector('[role=option]')`)
}
try {
  await send('Page.enable', {})
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  const result = await send('Page.addScriptToEvaluateOnNewDocument', { source: String.raw`
    const original=window.fetch;
    window.perfWait={status:'RUNNING',polls:0,starts:[],stops:[],unexpected:[]};
    window.fetch=async(input,init)=>{
      const rawPath=new URL(typeof input==='string'?input:input.url,location.href).pathname;const path=(rawPath==='/api/perf/runs/page'||rawPath==='/api/perf/runs/active')?'/api/perf/runs':rawPath;
      const method=(init?.method||'GET').toUpperCase(),g=window.perfWait;
      let body;
      if(path==='/api/devices')body=[{serial:'MOCK-WAIT-DEVICE',status:'device',model:'MOCK'}];
      else if(path==='/api/perf/games')body=[{id:501,name:'WAIT-GAME',shortName:'WAIT-GAME'}];
      else if(path==='/api/perf/button-groups')body=[];
      else if(path==='/api/perf/runs' && method==='GET')body=[{id:901,mode:'NORMAL',status:g.status,gameQueue:'501',startTime:'2026-10-13T00:00:00Z',loopCount:10}];
      else if(path==='/api/perf/runs' && method==='POST'){
        g.starts.push(JSON.parse(init.body));return new Response('{}',{status:409});
      }else if(path==='/api/perf/runs/901/progress'){
        g.polls++;g.lastStatus=g.status;body={runId:901,status:g.status,totalGames:1,currentGameIdx:0,currentGame:'WAIT-GAME',currentLoop:2,message:'MOCK_'+g.status};
      }else if(path==='/api/perf/runs/901/stop' && method==='POST'){
        g.stops.push(901);g.status='STOPPED';body={};
      }else if(path==='/api/perf/ai-room'||path==='/api/perf/options')body={};
      else if(path.startsWith('/api/perf/')){g.unexpected.push({path,method});return new Response('{}',{status:409});}
      else return original(input,init);
      if(rawPath==='/api/perf/runs/page')body={items:body,nextCursor:null,hasMore:false};else if(rawPath==='/api/perf/runs/active')body=Array.isArray(body)?body.filter(run=>['RUNNING','WAIT_DEVICE','WAIT_ENV'].includes(run.status)):[];
      return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});
    }` })
  scriptId = result.result.identifier
  for (const status of ['RUNNING','WAIT_DEVICE','WAIT_ENV']) {
    await enter(status)
    let state=await controls()
    check(`${status}禁重复启动且保留停止`,state.startDisabled && state.stop,state)
    await clickText('开始')
    await sleep(80)
    check(`${status}不会发送重复启动请求`,(await ev('window.perfWait.starts.length'))===0)
    await selectHistory()
    state=await controls()
    check(`${status}隐藏活动运行删除与重算`,!state.rejudge && !state.remove,state)
    const before=await ev('window.perfWait.polls')
    await sleep(3200)
    check(`${status}持续轮询`,(await ev('window.perfWait.polls'))>before)
    // 旧版缺少停止按钮时仍继续其余场景，保留完整红跑证据。
    if (state.stop) await clickText('停止')
    else await ev("window.perfWait.status='STOPPED'")
    await waitFor(`window.perfWait.lastStatus==='STOPPED' && ![...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='开始').disabled`)
    state=await controls()
    const stops=await ev('window.perfWait.stops')
    check(`${status}停止请求发给当前运行`,JSON.stringify(stops)==='[901]',stops)
    check(`${status}终态恢复开始与历史操作`,!state.startDisabled && !state.stop && state.rejudge && state.remove,state)
  }
  await enter('WAIT_ENV')
  await ev("window.perfWait.status='RUNNING'")
  await waitFor(`document.body.textContent.includes('MOCK_RUNNING')`)
  let state=await controls()
  check('等待恢复为RUNNING后仍可停止',state.startDisabled && state.stop,state)
  await clickText('首页')
  await ev("window.perfWait.status='WAIT_DEVICE'")
  await clickText('性能测试')
  await waitFor(`document.body.textContent.includes('MOCK_WAIT_DEVICE')`)
  state=await controls()
  check('切页回来接管等待运行并可停止',state.startDisabled && state.stop,state)
  check('未调用其他性能操作',(await ev('window.perfWait.unexpected.length'))===0)
} finally {
  if (scriptId) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId })
  ws.close()
}
console.log(`性能等待状态回归：${total-failures}/${total}，失败 ${failures}`)
process.exitCode=failures ? 1 : 0
