import { requiredUiUrl } from './isolated-ui.mjs'
// 真实DOM + 全API夹具。必须由隔离启动器提供独立Chrome/静态实例，不访问正式服务。
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
const APP=requiredUiUrl('SP_APP_URL'),CDP=requiredUiUrl('SP_CDP')
for(const value of [APP,CDP]){const url=new URL(value);if(url.protocol!=='http:'||url.hostname!=='127.0.0.1'||['18790','9222','9223'].includes(url.port))throw Error('isolated URLs only')}
const target=await(await fetch(CDP+'/json/new?about:blank',{method:'PUT'})).json(),ws=new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve);ws.addEventListener('error',reject)})
let next=0,scriptId;const pending=new Map(),checks=[],exceptions=[]
ws.addEventListener('message',event=>{const message=JSON.parse(event.data);if(message.method==='Runtime.exceptionThrown')exceptions.push(message.params.exceptionDetails.text);if(pending.has(message.id)){pending.get(message.id)(message);pending.delete(message.id)}})
const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++next,timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP deadline '+method))},15000);pending.set(id,message=>{clearTimeout(timer);if(message.error)reject(Error(JSON.stringify(message.error)));else resolve(message.result)});ws.send(JSON.stringify({id,method,params}))})
const ev=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value}
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
const wait=async expression=>{for(let i=0;i<160;i++){if(await ev(expression))return;await sleep(50)}throw Error('wait '+expression+'\n'+await ev('document.body.innerText.slice(-3500)'))}
const real=async expression=>{await sleep(180);const point=await ev(`(()=>{const e=${expression};if(!e)throw Error('missing control');e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);for(const type of ['mousePressed','mouseReleased'])await send('Input.dispatchMouseEvent',{type,button:'left',clickCount:1,...point})}
const click=selector=>real(`document.querySelector(${JSON.stringify(selector)})`)
const check=async(name,expression)=>{const passed=Boolean(await ev(expression));checks.push({name,passed});console.log((passed?'PASS ':'FAIL ')+name)}
const close=async()=>{for(const type of ['keyDown','keyUp'])await send('Input.dispatchKeyEvent',{type,key:'Escape',code:'Escape',windowsVirtualKeyCode:27});await wait('!document.querySelector("[role=dialog]")&&!document.querySelector("[data-slot=dialog-overlay]")')}
const injection=String.raw`(()=>{
const original=window.fetch;window.perfLifecycle={calls:[],writes:[],hold:false,release:null,fail:false,bound:false,holdSave:false,saveRelease:null};
window.fetch=async(input,init)=>{const url=new URL(typeof input==='string'?input:input.url,location.href),p=url.pathname,m=(init?.method||'GET').toUpperCase(),f=window.perfLifecycle;f.calls.push(p+url.search);let body;const fail=text=>new Response(JSON.stringify({message:text}),{status:503,headers:{'Content-Type':'application/json'}});
if(p==='/api/perf/coordinate-profile/fixed'&&m==='POST'){f.writes.push(JSON.parse(init.body));if(f.holdSave)await new Promise(resolve=>f.saveRelease=resolve);f.bound=true;body={state:'MATCH'};}
else if(p.startsWith('/api/')&&m!=='GET'){f.writes.push({unexpected:p});return fail('UNEXPECTED_WRITE');}
else if(p==='/api/meta')body={version:'isolated'};
else if(p==='/api/devices')body=[{serial:'fixture-device',status:'device',model:'Fixture'}];
else if(p==='/api/perf/games')body=[{id:501,name:'FIXTURE-GAME',shortName:'FIXTURE-GAME'}];
else if(p==='/api/perf/runs/page')body={items:[401,402].map(id=>({id,status:'COMPLETED',mode:'NORMAL',gameQueue:'501',gameNames:['RUN-'+id],startTime:'2026-10-15T00:00:00Z',loopCount:3,sampleSeconds:5})),hasMore:false,nextCursor:null};
else if(p==='/api/perf/runs/active'||p==='/api/apitest/cases')body=[];
else if(p.endsWith('/conditions')){if(f.fail)return fail('CONDITIONS_OFFLINE');body=p.includes('/401/')?{state:'KNOWN',message:'已冻结条件',model:'Fixture',appVersion:'APP-OLD',romRelease:'16',sampleSeconds:'5',sampleIntervalMs:'1000',mode:'NORMAL'}:{state:'NOT_RECORDED',message:'旧运行没有可比条件，不反推'};}
else if(p.endsWith('/timeline')){if(f.hold&&p.includes('/401/')){f.hold=false;await new Promise(resolve=>f.release=resolve);}body=p.includes('/401/')?{state:'RECORDED',total:3,truncated:false,items:[{sequence:1,type:'WAIT_STARTED',stage:'ENV_WAIT',gameOrdinal:1,loopNo:2,createdAt:'2026-10-15T00:00:00Z',reason:'ENV_UNREACHABLE'},{sequence:2,type:'WAIT_FINISHED',stage:'ENV_WAIT',gameOrdinal:1,loopNo:2,createdAt:'2026-10-15T00:00:02Z',reason:'ENV_REACHABLE',durationMs:2100},{sequence:3,type:'STOP_REQUESTED',stage:'USER',createdAt:'2026-10-15T00:00:03Z',reason:'USER_REQUEST'}]}:{state:'NOT_RECORDED',total:0,items:[]};}
else if(p.endsWith('/trend')){const only=url.searchParams.get('comparableOnly')==='true';body={items:[{id:401,pssAvgKb:1024,cpuAvg:2,okLoops:3,comparable:true,differences:[],conditionsState:'KNOWN'},...only?[]:[{id:402,okLoops:2,comparable:false,differences:['rosterIdentity','appVersion'],conditionsState:'UNKNOWN'}]],excluded:only?1:0,message:'未知条件不能作为同条件比较'};}
else if(p==='/api/perf/coordinate-profile')body={state:f.bound?'MATCH':'STALE',message:f.bound?'型号、有效尺寸与旋转一致':'坐标来源未知或已过期，请重新标定',current:{model:'Fixture',width:720,height:1600,rotation:1}};
else if(p.endsWith('/loops'))body=[{id:5011,runId:401,gameId:501,loopNo:1,status:'OK',pssAvgKb:1024}];
else if(p.endsWith('/samples')||p.endsWith('/checks'))body=[];
else if(p.endsWith('/growth-curves'))body={curves:[],totalOk:1};
else if(p==='/api/perf/ai-room'||p==='/api/perf/options')body={};
else if(p.startsWith('/api/'))body=[];
else return original(input,init);
return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});};})()`
try{
await send('Page.enable');await send('Runtime.enable');await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});scriptId=(await send('Page.addScriptToEvaluateOnNewDocument',{source:injection})).identifier
await send('Page.navigate',{url:APP+'#/perf?runId=401&gameId=501'});await wait('!!document.querySelector("[data-testid=perf-context-open]")')
await click('[data-testid=perf-context-open]');await wait('document.querySelector("[data-testid=perf-conditions]")?.textContent.includes("APP-OLD")')
await check('冻结条件显示应用ROM模式与实际间隔','document.querySelector("[data-testid=perf-conditions]").textContent.includes("16")&&document.querySelector("[data-testid=perf-conditions]").textContent.includes("1000")')
await check('类型化等待与恢复时间原因耗时','document.querySelector("[data-testid=perf-timeline]").textContent.includes("环境不可达")&&document.querySelector("[data-testid=perf-timeline]").textContent.includes("2100 ms")&&document.querySelector("[data-testid=perf-timeline]").textContent.includes("请求停止")')
await check('停止语义明确且不会自动发送续跑','document.querySelector("[data-testid=perf-timeline]").textContent.includes("停止不会自动复活")&&window.perfLifecycle.writes.length===0')
await check('跨run默认只取可比组','document.querySelector("[aria-label=只看可比条件的运行]").checked&&document.querySelector("[data-testid=perf-comparable-trend]").textContent.includes("排除 1")')
await check('同条件趋势绘制连续线','!!document.querySelector("[aria-label=跨运行PSS均值趋势] polyline")')
await click('[aria-label=只看可比条件的运行]');await wait('document.querySelector("[data-testid=perf-comparable-trend]")?.textContent.includes("游戏配置或名单来源")')
await check('放开过滤仍明确显示未知与名单应用差异','document.querySelector("[data-testid=perf-comparable-trend]").textContent.includes("UNKNOWN")&&document.querySelector("[data-testid=perf-comparable-trend]").textContent.includes("应用版本")')
await check('不同或未知条件不连接为同趋势线','!!document.querySelector("[aria-label=跨运行PSS均值趋势]")&&!document.querySelector("[aria-label=跨运行PSS均值趋势] polyline")')
await check('默认ZIP链接不含私密参数','!document.querySelector("[data-testid=perf-evidence-package]").href.includes("includePrivateFiles")')
await click('[aria-label=包含私密原始截图和日志]');await check('明确私密勾选才附确认参数和风险提示','document.querySelector("[data-testid=perf-evidence-package]").href.includes("confirmation=PRIVATE_LOCAL_ONLY")&&document.querySelector("[data-testid=perf-context-dialog] [role=alert]").textContent.includes("不是脱敏分享包")')
await close();await click('[data-testid=perf-context-open]');await wait('!!document.querySelector("[data-testid=perf-conditions]")');await check('重开默认恢复脱敏选择','!document.querySelector("[aria-label=包含私密原始截图和日志]").checked');await close()
await ev('window.perfLifecycle.hold=true');await click('[data-testid=perf-context-open]');await wait('!!window.perfLifecycle.release');await close()
await click('[data-testid=perf-run-select]');await wait('!!document.querySelector("[role=option]")');await real('[...document.querySelectorAll("[role=option]")].find(e=>e.textContent.includes("RUN-402"))')
await click('[data-testid=perf-context-open]');await wait('document.querySelector("[data-testid=perf-conditions]")?.textContent.includes("NOT_RECORDED")');await ev('window.perfLifecycle.release()');await sleep(150)
await check('切run和关闭拒收迟到条件时间线','!document.querySelector("[data-testid=perf-context-dialog]").textContent.includes("APP-OLD")&&document.querySelector("[data-testid=perf-timeline]").textContent.includes("旧运行没有时间线")');await close()
await ev('window.perfLifecycle.fail=true');await click('[data-testid=perf-context-open]');await wait('document.querySelector("[data-testid=perf-context-dialog] [role=alert]")?.textContent.includes("CONDITIONS_OFFLINE")');await check('读取失败不伪装旧成功证据','!document.querySelector("[data-testid=perf-conditions]")');await close();await ev('window.perfLifecycle.fail=false')
await click('[data-testid=perf-coordinate-profile-open]');await wait('document.querySelector("[data-testid=perf-coordinate-profile-dialog]")?.textContent.includes("STALE")')
await check('免坐标模式说明不引入标定启动门槛','document.querySelector("[data-testid=perf-coordinate-profile-dialog]").textContent.includes("不增加坐标标定启动门槛")')
await check('旧坐标来源明确且确认默认禁用','document.querySelector("[data-testid=perf-coordinate-profile-dialog]").textContent.includes("720")&&document.querySelector("[data-testid=perf-coordinate-bind]").disabled&&window.perfLifecycle.writes.length===0')
await click('[data-testid=perf-coordinate-profile-dialog] input[type=checkbox]');await ev('window.perfLifecycle.holdSave=true');await click('[data-testid=perf-coordinate-bind]');await wait('!!window.perfLifecycle.saveRelease')
await check('坐标确认在途禁止重复与关闭','document.querySelector("[data-testid=perf-coordinate-bind]").disabled&&!document.querySelector("[data-testid=perf-coordinate-profile-dialog] [data-slot=dialog-close]")&&window.perfLifecycle.writes.length===1')
await ev('window.perfLifecycle.saveRelease()');await wait('document.querySelector("[data-testid=perf-coordinate-profile-dialog]")?.textContent.includes("MATCH")');await check('仅明确确认绑定固定来源','window.perfLifecycle.writes[0].confirmed===true&&window.perfLifecycle.writes[0].deviceSerial==="fixture-device"');await close()
await send('Emulation.setDeviceMetricsOverride',{width:420,height:900,deviceScaleFactor:1,mobile:false});await click('[data-testid=perf-context-open]');await wait('!!document.querySelector("[data-testid=perf-conditions]")');await check('窄窗时间线和下载入口可达','(()=>{const r=document.querySelector("[data-testid=perf-context-dialog]").getBoundingClientRect();return r.x>=0&&r.right<=innerWidth+1&&r.y>=0&&r.bottom<=innerHeight+1})()');await close()
await check('没有设备动作或业务请求写入','window.perfLifecycle.writes.length===1&&!window.perfLifecycle.writes[0].unexpected');await check('没有未捕获页面异常',JSON.stringify(exceptions.length===0))
}catch(error){checks.push({name:'专项完整执行',passed:false});console.error(error.stack||error)}
finally{try{if(scriptId)await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:scriptId})}catch{}try{await ev('window.perfLifecycle.release?.();window.perfLifecycle.saveRelease?.()')}catch{}ws.close();await fetch(CDP+'/json/close/'+target.id).catch(()=>{})}
const failed=checks.filter(item=>!item.passed).length,summary={total:checks.length,passed:checks.length-failed,failed,checks,trueDeviceChecks:0,apiMode:'browser-fixture'};const output=process.env.SP_OUTPUT_DIR||'/tmp';await mkdir(output,{recursive:true});await writeFile(join(output,'perf-lifecycle-ui-summary.json'),JSON.stringify(summary,null,2));console.log('性能条件/恢复/证据 UI '+summary.passed+'/'+summary.total);process.exitCode=failed?1:0
