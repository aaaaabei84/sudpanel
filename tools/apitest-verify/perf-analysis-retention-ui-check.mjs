import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 性能结果长列表的真实滚动回归；所有 API 在浏览器中接管，不连接正式服务、数据库或手机。
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const OUTPUT = process.env.SP_OUTPUT_DIR || join(tmpdir(), 'sudpanel-perf-analysis-retention')
for (const raw of [APP, CDP]) {
  const url = new URL(raw)
  if (url.hostname !== '127.0.0.1' || ['18790', '9222', '9223'].includes(url.port)) throw Error('必须使用独立本机静态服务和 Chrome')
}
await mkdir(OUTPUT, { recursive: true })
const targets = await isolatedUiPages(APP, CDP)
const target = targets.find(item => item.type === 'page')
if (!target) throw Error('隔离 Chrome 没有页面')
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { ws.addEventListener('open', resolve); ws.addEventListener('error', reject) })
const pending = new Map(), checks = [], states = []
let next = 0, scriptId
ws.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (pending.has(message.id)) { pending.get(message.id)(message); pending.delete(message.id) }
})
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); reject(Error('CDP deadline: ' + method)) }, 15000)
  pending.set(id, message => { clearTimeout(timer); resolve(message) })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.error || result.result?.exceptionDetails) throw Error(JSON.stringify(result.error || result.result.exceptionDetails))
  return result.result.result.value
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const wait = async (expression, timeout = 15000) => {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { if (await ev(`Boolean(${expression})`)) return; await sleep(50) }
  throw Error('UI deadline: ' + expression)
}
const check = (name, passed, detail) => {
  checks.push({ name, passed: !!passed, detail })
  console.log(`${passed ? 'PASS' : 'FAIL'} ${name}${detail === undefined ? '' : ' ' + JSON.stringify(detail)}`)
}
const click = async expression => {
  await ev(`(async()=>{const e=${expression};if(!e)throw Error('missing control');e.scrollIntoView({block:'center'});await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame)})()`)
  let point = await ev(`(()=>{const r=(${expression}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`)
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point })
  await sleep(100) // 滚动箭头/锚点重新排版后，按最终命中位置点击。
  point = await ev(`(()=>{const r=(${expression}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`)
  for (const type of ['mousePressed', 'mouseReleased']) await send('Input.dispatchMouseEvent', { type, button: 'left', clickCount: 1, ...point })
}
const close = async () => {
  for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await wait(`!document.querySelector('[role=listbox],[role=dialog]')`)
}
const state = () => ev(`(()=>{
  const list=document.querySelector('[role=listbox]'),viewport=document.querySelector('[data-radix-select-viewport]'),trigger=document.querySelector('[data-testid=perf-run-select]');
  const rect=list?.getBoundingClientRect();return {open:!!list,height:rect?.height,top:rect?.top,bottom:rect?.bottom,left:rect?.left,width:rect?.width,scrollTop:viewport?.scrollTop,outerScrollTop:list?.scrollTop,scrollHeight:viewport?.scrollHeight,clientHeight:viewport?.clientHeight,selected:trigger?.textContent,hash:location.hash,devices:window.perfRetention.devices,progress:window.perfRetention.progress,nodePreserved:!window.perfRetention.listNode||window.perfRetention.listNode===list};
})()`)
const fixture = `(()=>{
  const f=window.perfRetention={devices:0,progress:0,writes:[],unexpected:[],active:false,listNode:null};
  const reply=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
  const runs=Array.from({length:60},(_,i)=>({id:901+i,status:'COMPLETED',mode:'NORMAL',gameQueue:'501',gameNames:['ANALYSIS-GAME-'+(i+1)],loopCount:40,sampleSeconds:5,startTime:'2026-10-16T00:00:00Z'}));
  const active={id:9999,status:'RUNNING',mode:'NORMAL',gameQueue:'501',loopCount:100};
  window.fetch=async(input,init)=>{
    const url=new URL(typeof input==='string'?input:input.url,location.href),p=url.pathname,m=(init?.method||'GET').toUpperCase();
    if(!p.startsWith('/api/')){f.unexpected.push({p,m});throw Error('unexpected fixture request')}
    if(m!=='GET'){f.writes.push({p,m});return reply({message:'fixture rejects writes'},409)}
    if(p==='/api/meta')return reply({version:'retention-fixture'});
    if(p==='/api/devices'){f.devices++;return reply([{serial:'MOCK-RETENTION-DEVICE',model:'MOCK',status:'device'}])}
    if(p==='/api/perf/games')return reply([{id:501,name:'ANALYSIS-GAME',shortName:'ANALYSIS-GAME'}]);
    if(p==='/api/perf/runs/page')return reply({items:runs,nextCursor:null,hasMore:false});
    if(p==='/api/perf/runs/active')return reply(f.active?[active]:[]);
    if(p==='/api/perf/runs')return reply(runs);
    if(p==='/api/perf/runs/9999/progress'){f.progress++;return reply({runId:9999,status:'RUNNING',message:'MOCK_PROGRESS_'+f.progress,totalGames:1,currentGameIdx:0,currentLoop:f.progress})}
    if(p==='/api/perf/button-groups'||p==='/api/apitest/cases')return reply([]);
    if(p==='/api/perf/ai-room'||p==='/api/perf/options')return reply({});
    if(/^\\/api\\/perf\\/runs\\/\\d+$/.test(p))return reply(runs.find(run=>run.id===Number(p.split('/').at(-1)))||active);
    if(p.endsWith('/loops'))return reply(Array.from({length:40},(_,i)=>({id:5011+i,runId:901,gameId:501,loopNo:i+1,status:'OK',baselinePssKb:102400+i*1024,trimmedPssKb:102400+i*1024,baselineJavaKb:10240,trimmedJavaKb:10240,baselineNativeKb:40960+i*1024,trimmedNativeKb:40960+i*1024,pssAvgKb:120000+i*1024,pssPeakKb:130000+i*1024,cpuAvg:5,cpuAvgSys:1,cpuPeak:8,cpuPeakSys:2,baselineWsThreads:1,inGameWsThreads:2,cleanupStatus:'SUCCESS'})));
    if(p.endsWith('/checks'))return reply([{id:801,checkNo:1,checkName:'隔离内存检查',verdict:'PASS',evidence:'MOCK_EVIDENCE'}]);
    if(p.endsWith('/growth-curves'))return reply({curves:[],totalOk:40});
    if(p.endsWith('/conditions'))return reply({state:'RECORDED',message:'MOCK_CONDITIONS',model:'MOCK'});
    if(p.endsWith('/timeline'))return reply({state:'RECORDED',items:Array.from({length:80},(_,i)=>({sequence:i+1,type:'WAIT_PROBE',stage:'MOCK',loopNo:i+1,createdAt:'2026-10-16T00:00:00Z',reason:'MOCK_REASON',durationMs:10})),total:80,truncated:false,limitReached:false});
    if(p.endsWith('/trend'))return reply({items:Array.from({length:20},(_,i)=>({id:i+1,pssAvgKb:100000+i*100,okLoops:40,comparable:true,differences:[],conditionsState:'RECORDED'})),excluded:0,message:'MOCK_TREND'});
    if(p.endsWith('/samples'))return reply(Array.from({length:20},(_,i)=>({seq:i+1,pssKb:102400+i*100,cpuPercent:2,wsThreadCount:1,netConn:1,quality:{offsetMs:i*1000,durationMs:10,sources:{pss:{status:'OK',source:'MOCK'}}}})));
    f.unexpected.push({p,m});return reply({message:'unhandled fixture '+p},404);
  };
})()`

try {
  await send('Page.enable')
  const injected = await send('Page.addScriptToEvaluateOnNewDocument', { source: fixture }); scriptId = injected.result.identifier
  for (const scenario of [
    { label: '未选运行', run: null, width: 1440, height: 1000, active: false },
    { label: '中间已选运行', run: 931, width: 1440, height: 1000, active: false },
    { label: '窄窗口与活动进度轮询', run: 901, width: 420, height: 900, active: true },
  ]) {
    await send('Emulation.setDeviceMetricsOverride', { width: scenario.width, height: scenario.height, deviceScaleFactor: 1, mobile: false })
    await send('Page.navigate', { url: 'about:blank' })
    await wait('location.href === "about:blank"')
    await send('Page.navigate', { url: APP + '#/perf' + (scenario.run ? '?runId=' + scenario.run + '&gameId=501' : '') })
    await wait(`!!window.perfRetention&&!!document.querySelector('[data-testid=perf-run-select]')&&window.perfRetention.devices>0`)
    await wait(`document.querySelector('[data-testid=perf-run-select]').textContent.includes(${JSON.stringify(scenario.run ? 'ANALYSIS-GAME' : '选择运行')})`)
    if (scenario.active) {
      await ev(`window.perfRetention.active=true;[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='刷新').click()`)
      await wait(`window.perfRetention.progress>0`)
    }
    if (scenario.run) await wait(`!!document.querySelector('[data-testid=perf-mem-dim-trend]')`)
    await click(`document.querySelector('[data-testid=perf-run-select]')`)
    await wait(`document.querySelectorAll('[role=option]').length===${scenario.active ? 61 : 60}`)
    await sleep(250)
    const opened = await state()
    const point = await ev(`(()=>{const r=document.querySelector('[role=listbox]').getBoundingClientRect();return {x:r.x+r.width/2,y:Math.min(innerHeight-40,r.y+r.height/2)}})()`)
    console.log('滚轮命中 ' + JSON.stringify(await ev(`(()=>{const e=document.elementFromPoint(${point.x},${point.y});return {point:${JSON.stringify(point)},hit:e?.outerHTML.slice(0,220)}})()`)))
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point })
    for (let i = 0; i < 3; i++) {
      await send('Input.dispatchMouseEvent', { type: 'mouseWheel', ...point, deltaX: 0, deltaY: 380 })
      await sleep(150)
    }
    const scrolled = await state()
    check(scenario.label + '：真实滚轮已移动长列表', scrolled.scrollTop > (opened.scrollTop || 0) + 100, { opened, scrolled })
    await ev(`window.perfRetention.listNode=document.querySelector('[role=listbox]');void 0`)
    const samples = []
    const deadline = Date.now() + 12500
    while (Date.now() < deadline) {
      const current = await state(); samples.push(current)
      if (current.devices >= scrolled.devices + 2 && (!scenario.active || current.progress >= scrolled.progress + 2)) break
      await sleep(100)
    }
    const after = samples.at(-1)
    states.push({ scenario, opened, scrolled, samples })
    check(scenario.label + '：跨两次设备刷新保持打开及相同 DOM', after.devices >= scrolled.devices + 2 && (!scenario.active || after.progress >= scrolled.progress + 2) && samples.every(item => item.open && item.nodePreserved), after)
    check(scenario.label + '：列表不收缩回初开高度', samples.every(item => Math.abs(item.height - scrolled.height) <= 2), { before: scrolled.height, min: Math.min(...samples.map(item => item.height)), after: after.height })
    check(scenario.label + '：保持下滑位置与原选择', samples.every(item => Math.abs(item.scrollTop - scrolled.scrollTop) <= 2 && item.selected === scrolled.selected && item.hash === scrolled.hash), { before: scrolled.scrollTop, min: Math.min(...samples.map(item => item.scrollTop)), after: after.scrollTop })
    check(scenario.label + '：弹层留在视口内', after.top >= -1 && after.bottom <= scenario.height + 1 && after.left >= -1 && after.left + after.width <= scenario.width + 1, after)
    check(scenario.label + '：列表能同时展示多项', after.clientHeight >= 100, after.clientHeight)
    const screenshot = await send('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(OUTPUT, `run-list-${scenario.width}-${scenario.run || 'none'}.png`), Buffer.from(screenshot.result.data, 'base64'))
    await close()
    check(scenario.label + '：关闭后选择值未变', (await state()).selected === scrolled.selected)
    await click(`document.querySelector('[data-testid=perf-run-select]')`)
    await wait(`document.querySelectorAll('[role=option]').length>=60`)
    await sleep(200)
    const candidate = await ev(`(()=>{
      const v=document.querySelector('[data-radix-select-viewport]'),r=v.getBoundingClientRect();
      const options=[...document.querySelectorAll('[role=option]')].filter(e=>{const o=e.getBoundingClientRect();return o.top>=r.top&&o.bottom<=r.bottom&&/ANALYSIS-GAME-\\d+/.test(e.textContent)});
      const option=options.at(-1);if(!option)throw Error('no visible request option');
      return {name:option.textContent.trim(),id:900+Number(option.textContent.match(/ANALYSIS-GAME-(\\d+)/)[1])};
    })()`)
    await click(`[...document.querySelectorAll('[role=option]')].find(e=>e.textContent.trim()===${JSON.stringify(candidate.name)})`)
    console.log('选项点击 ' + JSON.stringify({ candidate, actual: await state() }))
    await wait(`!document.querySelector('[role=listbox]')&&new URLSearchParams(location.hash.split('?')[1]).get('runId')==='${candidate.id}'`)
    check(scenario.label + '：滚动后的可见选项可真实点击选择', (await state()).selected === candidate.name, candidate)
    await click(`document.querySelector('[data-testid=perf-run-select]')`)
    await wait(`!!document.querySelector('[role=option][data-state=checked]')`)
    await sleep(200)
    const reopened = await ev(`(()=>{const v=document.querySelector('[data-radix-select-viewport]').getBoundingClientRect(),o=document.querySelector('[role=option][data-state=checked]'),r=o.getBoundingClientRect();return {name:o.textContent.trim(),visible:r.top>=v.top-1&&r.bottom<=v.bottom+1,focused:document.activeElement===o}})()`)
    check(scenario.label + '：重新打开定位并聚焦已选项', reopened.name === candidate.name && reopened.visible && reopened.focused, reopened)
    await close()
    check(scenario.label + '：没有真实 API 或写请求', await ev('window.perfRetention.writes.length===0&&window.perfRetention.unexpected.length===0'))
  }
  await click(`[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='ANALYSIS-GAME')`)
  await wait(`!!document.querySelector('[data-testid=perf-mem-dim-trend]')`)
  await click(`document.querySelector('[data-testid=perf-context-open]')`)
  await wait(`document.querySelector('[data-testid=perf-context-dialog]')?.textContent.includes('MOCK_TREND')`)
  // Wait for finite entrance animations and stable geometry before capturing the
  // baseline. Infinite decorative animations must not block this bounded wait.
  await ev(`(async()=>{
    const selector='[data-testid=perf-context-dialog]',dialog=document.querySelector(selector);
    if(!dialog)throw Error('missing context dialog');
    const deadline=performance.now()+5000;
    let previous=null,stableSince=null,stableSamples=0;
    while(performance.now()<deadline){
      if(document.querySelector(selector)!==dialog)throw Error('context dialog replaced while settling');
      const rect=dialog.getBoundingClientRect(),geometry=[rect.x,rect.y,rect.width,rect.height];
      const animating=dialog.getAnimations({subtree:true}).some(animation=>{
        const timing=animation.effect?.getComputedTiming();
        return timing&&Number.isFinite(timing.endTime)&&(animation.pending||!['finished','idle'].includes(animation.playState));
      });
      const stable=!animating&&previous&&geometry.every((value,index)=>Math.abs(value-previous[index])<=0.1);
      if(stable){
        stableSince??=performance.now();stableSamples++;
        if(stableSamples>=3&&performance.now()-stableSince>=150)return;
      }else{stableSince=null;stableSamples=0;}
      previous=geometry;
      await new Promise(resolve=>setTimeout(resolve,25));
    }
    throw Error('context dialog entrance animation or geometry did not settle within 5 seconds');
  })()`)
  await ev(`(()=>{const d=document.querySelector('[data-testid=perf-context-dialog]'),s=[...d.querySelectorAll('*')].find(e=>getComputedStyle(e).overflowY==='auto'&&e.scrollHeight>e.clientHeight);if(!s)throw Error('missing dialog scroller');s.scrollTop=350;window.perfRetention.dialogNode=d;window.perfRetention.dialogScroller=s;window.perfRetention.dialogBefore={height:d.getBoundingClientRect().height,scrollTop:s.scrollTop,devices:window.perfRetention.devices}})()`)
  await wait('window.perfRetention.devices>=window.perfRetention.dialogBefore.devices+2')
  const dialog = await ev(`(()=>{const f=window.perfRetention,d=document.querySelector('[data-testid=perf-context-dialog]');return {same:d===f.dialogNode,height:d?.getBoundingClientRect().height,scrollTop:f.dialogScroller.scrollTop,before:f.dialogBefore}})()`)
  check('结果条件弹窗跨后台刷新保持尺寸与滚动', dialog.same && Math.abs(dialog.height - dialog.before.height) <= 2 && Math.abs(dialog.scrollTop - dialog.before.scrollTop) <= 2, dialog)
  await close()
} finally {
  await writeFile(join(OUTPUT, 'summary.json'), JSON.stringify({ checks, states }, null, 2))
  if (scriptId) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId })
  ws.close()
}
const failures = checks.filter(item => !item.passed).length
console.log(`性能结果分析保持回归：${checks.length - failures}/${checks.length}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
