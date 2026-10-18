import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 真实页面 + 可控响应门闩；不启动业务测试、不连接设备、不写数据库。
const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve))
let next = 0, total = 0, failures = 0
const pending = new Map()
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data)
  if (pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})
const send = (method, params) => new Promise((resolve, reject) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 10000)
  pending.set(id, msg => { clearTimeout(timer); resolve(msg) })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const msg = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (msg.error || msg.result.exceptionDetails) throw new Error(JSON.stringify(msg.error || msg.result.exceptionDetails))
  return msg.result.result.value
}
const waitFor = async expression => {
  for (let i = 0; i < 60; i++) { if (await ev(expression)) return; await sleep(50) }
  throw new Error(`fixture not ready: ${expression}`)
}
const clickText = text => ev(`(()=>{const e=[...document.querySelectorAll('button,[role=option]')].find(x=>x.textContent.trim()===${JSON.stringify(text)}); if(!e)throw Error('missing control');e.click()})()`)
const check = (name, ok) => { total++; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`) }
const hold = path => ev(`window.raceHold(${JSON.stringify(path)})`)
const release = async path => { await ev(`window.raceRelease(${JSON.stringify(path)})`); await sleep(120) }
try {
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await send('Page.navigate', { url: APP })
  await waitFor(`document.querySelector('button') != null`)
  await ev(String.raw`(()=>{
    const original=window.fetch, gates={}, calls={};
    window.raceHold=path=>{gates[path]={promise:new Promise(resolve=>{gates[path+'-resolve']=resolve})}};
    window.raceRelease=path=>{gates[path+'-resolve']?.();delete gates[path]};
    window.raceCalls=calls;
    const games=[{id:501,name:'RACE-GAME-A'},{id:502,name:'RACE-GAME-B'}];
    const batch=id=>({id,name:'RACE-BATCH-'+id,mode:'ONCE',caseIds:[],intervalSeconds:1,repeatCount:1,status:id===602?'RUNNING':'DONE',totalExecs:1,passCount:1,failCount:0,errorCount:0,startedAt:'2026-01-01T00:00:00Z'});
    window.fetch=async(input,init)=>{
      const rawPath=new URL(typeof input==='string'?input:input.url,location.href).pathname;const path=(rawPath==='/api/perf/runs/page'||rawPath==='/api/perf/runs/active')?'/api/perf/runs':rawPath;
      calls[path]=(calls[path]||0)+1;
      let body;
      if(path==='/api/devices') body=[];
      else if(path==='/api/perf/games') body=games;
      else if(path==='/api/perf/runs') body=[{id:401,status:'DONE',mode:'NORMAL',gameQueue:'501,502',loopCount:2,sampleSeconds:5}];
      else if(path==='/api/perf/button-groups') body=[];
      else if(path==='/api/perf/ai-room'||path==='/api/perf/options') body={};
      else if(path.endsWith('/growth-curves')) body={curves:[{loopNo:1,points:[{t:1,pss:1},{t:2,pss:2}]}],totalOk:1};
      else if(path.endsWith('/checks')) body=[];
      else if(path.match(/\/api\/perf\/runs\/401\/games\/50[12]\/loops$/)){
        const game=path.includes('/501/')?501:502;
        body=[{id:game*10+1,gameId:game,runId:401,loopNo:1,status:'OK',pssAvgKb:1024},
          {id:game*10+2,gameId:game,runId:401,loopNo:2,status:'OK',pssAvgKb:2048}];
      }else if(path.match(/\/api\/perf\/loops\/\d+\/samples$/)) body=[{seq:0,pssKb:1024,cpuPercent:1},{seq:1,pssKb:2048,cpuPercent:2}];
      else if(path==='/api/apitest/batches') body=[batch(601),batch(602),batch(603)];
      else if(path.match(/\/api\/apitest\/batches\/60[123]\/progress$/)){
        const id=Number(path.split('/').at(-2)); body={batch:batch(id),running:id===602,current:null};
      }else if(path.match(/\/api\/apitest\/batches\/60[123]\/executions$/)){
        const id=Number(path.split('/').at(-2)); body={items:[{id,caseName:'RACE-RESULT-'+id,roundNo:1,seq:1,verdict:'PASS',elapsedMs:1,httpStatus:200,ts:'2026-01-01T00:00:00Z'}],nextAfterId:id,hasMore:false};
      }else return original(input,init);
      if(gates[path]) await gates[path].promise;
      if(rawPath==='/api/perf/runs/page')body={items:body,nextCursor:null,hasMore:false};else if(rawPath==='/api/perf/runs/active')body=Array.isArray(body)?body.filter(run=>['RUNNING','WAIT_DEVICE','WAIT_ENV'].includes(run.status)):[];
      return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});
    };
  })()`)
  await clickText('性能测试')
  await waitFor(`document.body.innerText.includes('选择运行')`)
  await ev(`([...document.querySelectorAll('[role=combobox]')].find(x=>x.textContent.includes('选择运行'))).click()`)
  await waitFor(`!!document.querySelector('[role=option]')`)
  await ev(`document.querySelector('[role=option]').click()`)
  await clickText('RACE-GAME-A')
  const row = id => `document.querySelector('[data-testid="perf-loop-${id}"]') || [...document.querySelectorAll('tr')].find(x=>x.textContent.includes('OK')&&x.querySelector('td')?.textContent.trim()==='${id % 10}')`
  await waitFor(`!!(${row(5011)})`)
  await waitFor(`document.body.innerText.includes('内存增长曲线（各局局内')`)
  await ev(`(${row(5011)}).click()`)
  await waitFor(`document.body.innerText.includes('单圈逐秒曲线')`)
  await hold('/api/perf/loops/5012/samples')
  await ev(`(${row(5012)}).click()`)
  await waitFor(`window.raceCalls['/api/perf/loops/5012/samples'] > 0`)
  check('换圈等待时不将旧曲线标成新圈', !(await ev(`document.body.innerText.includes('单圈逐秒曲线')`)))
  await hold('/api/perf/runs/401/games/502/loops')
  await hold('/api/perf/runs/401/games/502/growth-curves')
  await clickText('RACE-GAME-B')
  await sleep(100)
  check('换游戏等待时立即清除旧圈数据', !(await ev(`!!document.querySelector('tr td') && [...document.querySelectorAll('tr')].some(x=>x.textContent.includes('OK'))`)))
  check('换游戏等待时立即清除旧增长曲线', !(await ev(`document.body.innerText.includes('内存增长曲线（各局局内')`)))
  await release('/api/perf/runs/401/games/502/loops')
  await release('/api/perf/runs/401/games/502/growth-curves')
  await waitFor(`document.body.innerText.includes('内存增长曲线（各局局内')`)
  check('当前游戏增长曲线到达后正常展示', true)
  await waitFor(`!!(${row(5021)})`)
  await release('/api/perf/loops/5012/samples')
  await hold('/api/perf/loops/5021/samples')
  await ev(`(${row(5021)}).click()`)
  await sleep(100)
  check('上一游戏迟到采样不能复活为新游戏曲线', !(await ev(`document.body.innerText.includes('单圈逐秒曲线')`)))
  await release('/api/perf/loops/5021/samples')
  await waitFor(`document.body.innerText.includes('单圈逐秒曲线')`)
  check('当前圈采样到达后正常展示曲线', true)

  await clickText('接口测试')
  await waitFor(`!![...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='#601')`)
  await clickText('#603')
  await waitFor(`document.body.innerText.includes('RACE-RESULT-603')`)
  await hold('/api/apitest/batches/601/progress')
  await clickText('#601')
  await waitFor(`window.raceCalls['/api/apitest/batches/601/progress'] > 0`)
  check('打开新批次等待时立即清除旧批次结果', !(await ev(`document.body.innerText.includes('RACE-RESULT-603')`)))
  await clickText('#603')
  await waitFor(`document.body.innerText.includes('RACE-RESULT-603')`)
  await release('/api/apitest/batches/601/progress')
  check('迟到的批次打开响应不能覆盖当前批次', await ev(`document.body.innerText.includes('RACE-RESULT-603') && !document.body.innerText.includes('RACE-RESULT-601')`))
  await clickText('#602')
  await waitFor(`document.body.innerText.includes('RACE-RESULT-602')`)
  await hold('/api/apitest/batches/602/progress')
  const before = await ev(`window.raceCalls['/api/apitest/batches/602/progress']`)
  await waitFor(`window.raceCalls['/api/apitest/batches/602/progress'] > ${before}`)
  await clickText('#603')
  await waitFor(`document.body.innerText.includes('RACE-RESULT-603')`)
  await release('/api/apitest/batches/602/progress')
  check('切批次后旧轮询响应不能覆盖结果或停止状态', await ev(`document.body.innerText.includes('RACE-RESULT-603') && !document.body.innerText.includes('RACE-RESULT-602') && !document.body.innerText.includes('停止批次')`))
  const count = await ev(`window.raceCalls['/api/apitest/batches/602/progress']`)
  await sleep(1700)
  check('离开旧批次后不再继续它的轮询', await ev(`window.raceCalls['/api/apitest/batches/602/progress'] === ${count}`))
} finally { ws.close() }
console.log(`选择乱序界面回归：${total - failures}/${total}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
