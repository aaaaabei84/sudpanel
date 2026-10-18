import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 进度请求失败、串行轮询与卸载迟到响应回归；所有性能请求由mock接管。
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
const controls = () => ev(`(()=>{const bs=[...document.querySelectorAll('button')];return {disabled:bs.find(b=>b.textContent.trim()==='开始').disabled,stop:bs.some(b=>b.textContent.trim()==='停止'),warning:document.querySelector('[data-testid="perf-progress-error"]')?.textContent||''}})()`)
const enter = async options => {
  await send('Page.navigate', { url: APP })
  await waitFor(`!!window.perfPoll && [...document.querySelectorAll('button')].some(x=>x.textContent.trim()==='性能测试')`)
  await ev(`Object.assign(window.perfPoll,${JSON.stringify(options)})`)
  await clickText('性能测试')
  await waitFor(`!!document.querySelector('[data-testid="perf-mode"]') && window.perfPoll.count>0`)
  await sleep(80)
}
const release = async (status, message, reject = false) => ev(`(()=>{const p=window.perfPoll.pending.shift();if(!p)throw Error('missing pending request');${reject ? `p.reject(new TypeError(${JSON.stringify(message)}))` : `p.resolve({runId:901,status:${JSON.stringify(status)},message:${JSON.stringify(message)},totalGames:1,currentGameIdx:0,currentLoop:2})`}})()`)
const reenter = async () => {
  await clickText('首页')
  await ev("window.perfPoll.marker='FRESH_ACTIVE'")
  await clickText('性能测试')
  await waitFor(`document.body.textContent.includes('FRESH_ACTIVE')`)
}
try {
  await send('Page.enable', {})
  await send('Emulation.setDeviceMetricsOverride', { width:1440,height:1000,deviceScaleFactor:1,mobile:false })
  const result=await send('Page.addScriptToEvaluateOnNewDocument',{source:String.raw`
    const original=window.fetch;
    window.perfPoll={status:'RUNNING',marker:'INITIAL',failures:0,holdNext:false,count:0,active:0,maxActive:0,pending:[],starts:[],stops:[],unexpected:[]};
    window.fetch=async(input,init)=>{
      const rawPath=new URL(typeof input==='string'?input:input.url,location.href).pathname;const path=(rawPath==='/api/perf/runs/page'||rawPath==='/api/perf/runs/active')?'/api/perf/runs':rawPath;
      const method=(init?.method||'GET').toUpperCase(),g=window.perfPoll;
      let body;
      if(path==='/api/devices')body=[{serial:'MOCK-POLL-DEVICE',status:'device',model:'MOCK'}];
      else if(path==='/api/perf/games')body=[{id:501,name:'POLL-GAME',shortName:'POLL-GAME'}];
      else if(path==='/api/perf/button-groups')body=[];
      else if(path==='/api/perf/runs' && method==='GET')body=[{id:901,mode:'NORMAL',status:g.status,gameQueue:'501',startTime:'2026-10-13T00:00:00Z',loopCount:10}];
      else if(path==='/api/perf/runs' && method==='POST'){g.starts.push(JSON.parse(init.body));return new Response('{}',{status:409});}
      else if(path==='/api/perf/runs/901/stop' && method==='POST'){g.stops.push(901);g.status='STOPPED';g.failures=0;body={};}
      else if(path==='/api/perf/runs/901/progress'){
        g.count++;g.active++;g.maxActive=Math.max(g.maxActive,g.active);
        try{
          if(g.holdNext){g.holdNext=false;body=await new Promise((resolve,reject)=>g.pending.push({resolve,reject}));}
          else if(g.failures>0){g.failures--;throw new TypeError('MOCK_PROGRESS_OFFLINE');}
          else body={runId:901,status:g.status,message:g.marker,totalGames:1,currentGameIdx:0,currentLoop:2};
        }finally{g.active--;}
        g.lastStatus=body.status;
      }else if(path==='/api/perf/ai-room'||path==='/api/perf/options')body={};
      else if(path.startsWith('/api/perf/')){g.unexpected.push({path,method});return new Response('{}',{status:409});}
      else return original(input,init);
      if(rawPath==='/api/perf/runs/page')body={items:body,nextCursor:null,hasMore:false};else if(rawPath==='/api/perf/runs/active')body=Array.isArray(body)?body.filter(run=>['RUNNING','WAIT_DEVICE','WAIT_ENV'].includes(run.status)):[];
      return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});
    }`})
  scriptId=result.result.identifier
  await enter({failures:1,marker:'RECOVERED_FIRST'})
  let state=await controls()
  check('首次查询失败仍禁开始并保留停止',state.disabled && state.stop,state)
  check('首次失败展示自动重试提示',state.warning.includes('自动重试') && state.warning.includes('MOCK_PROGRESS_OFFLINE'),state)
  await clickText('全选');await clickText('开始');await sleep(80)
  check('首次失败期间不会重复启动',(await ev('window.perfPoll.starts.length'))===0)
  await sleep(3200)
  state=await controls()
  check('首次失败后自动恢复进度并清提示',(await ev('window.perfPoll.count'))>=2 && (await ev("document.body.textContent.includes('RECOVERED_FIRST')")) && !state.warning,state)

  await enter({marker:'INITIAL_OK'})
  await ev("window.perfPoll.failures=2;window.perfPoll.marker='RECOVERED_LATER'")
  await sleep(3200)
  state=await controls()
  check('运行中查询失败提示重试并保留停止',state.warning.includes('自动重试') && state.disabled && state.stop,state)
  const failedCount=await ev('window.perfPoll.count')
  await sleep(3200)
  check('连续失败仍继续查询',(await ev('window.perfPoll.count'))>failedCount)
  await sleep(3200)
  state=await controls()
  check('后续恢复后更新内容并清提示',!state.warning && (await ev("document.body.textContent.includes('RECOVERED_LATER')")),state)

  await enter({failures:5})
  state=await controls()
  if(state.stop)await clickText('停止')
  else await ev("window.perfPoll.status='STOPPED';window.perfPoll.failures=0")
  check('查询失败期间停止请求指向当前运行',JSON.stringify(await ev('window.perfPoll.stops'))==='[901]')
  await sleep(3200)
  state=await controls()
  check('查询恢复为终态后恢复开始并隐藏停止',!state.disabled && !state.stop && !state.warning && (await ev('window.perfPoll.lastStatus'))==='STOPPED',state)
  const terminalCount=await ev('window.perfPoll.count')
  await sleep(3200)
  check('终态不再继续轮询',(await ev('window.perfPoll.count'))===terminalCount)

  await enter({holdNext:true})
  state=await controls()
  check('首次查询未返回时保留停止并禁开始',state.disabled && state.stop,state)
  await sleep(3200)
  const concurrency=await ev('({count:window.perfPoll.count,maxActive:window.perfPoll.maxActive})')
  check('慢请求未返回时不叠加查询',concurrency.count===1 && concurrency.maxActive===1,concurrency)
  await release('RUNNING','HELD_DONE')
  await waitFor(`document.body.textContent.includes('HELD_DONE')`)
  check('慢请求完成后正常显示进度',(await controls()).stop)
  const heldCount=await ev('window.perfPoll.count')
  await sleep(3200)
  check('慢请求完成后继续后续轮询',(await ev('window.perfPoll.count'))>heldCount)

  await enter({holdNext:true})
  await reenter()
  await release('STOPPED','STALE_TERMINAL')
  await sleep(100)
  state=await controls()
  check('卸载后的迟到终态不覆盖当前页面',state.disabled && state.stop && (await ev("document.body.textContent.includes('FRESH_ACTIVE') && !document.body.textContent.includes('STALE_TERMINAL')")),state)
  const freshCount=await ev('window.perfPoll.count')
  await sleep(3200)
  check('迟到终态不停止新页面轮询',(await ev('window.perfPoll.count'))>freshCount)

  await enter({holdNext:true})
  await reenter()
  await release('RUNNING','MOCK_OLD_FAILURE',true)
  await sleep(100)
  check('卸载后的迟到异常不弹错误或污染当前页',!(await ev("[...document.querySelectorAll('[data-sonner-toast]')].some(e=>e.textContent.includes('MOCK_OLD_FAILURE'))")) && !(await controls()).warning)
  check('未调用其他性能操作',(await ev('window.perfPoll.unexpected.length'))===0)
} finally {
  if(scriptId)await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:scriptId})
  ws.close()
}
console.log(`性能进度恢复回归：${total-failures}/${total}，失败 ${failures}`)
process.exitCode=failures ? 1 : 0
