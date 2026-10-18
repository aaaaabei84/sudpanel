import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const OUTPUT=process.env.SP_OUTPUT_DIR||join(tmpdir(),'sudpanel-continuation-ui-results')
await mkdir(OUTPUT,{recursive:true})
const CDP=requiredUiUrl('SP_CDP'),APP=requiredUiUrl('SP_APP_URL')
if(new URL(APP).hostname!=='127.0.0.1'||new URL(CDP).hostname!=='127.0.0.1')throw Error('loopback required')
const targets=await isolatedUiPages(APP, CDP),target=targets.find(t=>t.type==='page'),pending=new Map(),checks=[]
const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.addEventListener('open',r);ws.addEventListener('error',j)})
let seq=0;ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id)}})
const command=(method,params={})=>new Promise((r,j)=>{const id=++seq,t=setTimeout(()=>{pending.delete(id);j(Error(method+' deadline'))},15000);pending.set(id,m=>{clearTimeout(t);r(m)});ws.send(JSON.stringify({id,method,params}))})
const ev=async expression=>{const m=await command('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(m.error||m.result?.exceptionDetails)throw Error(JSON.stringify(m.error||m.result.exceptionDetails));return m.result.result.value}
const pause=ms=>new Promise(r=>setTimeout(r,ms));const wait=async(expression,ms=20000)=>{const until=Date.now()+ms;while(Date.now()<until){if(await ev(`Boolean(${expression})`))return;await pause(50)}throw Error('predicate deadline '+expression)}
const check=(name,ok)=>{checks.push({name,passed:!!ok});console.log((ok?'PASS ':'FAIL ')+name)}
const clickText=async text=>{await wait(`Array.from(document.querySelectorAll('button')).some(e=>e.textContent.trim()===${JSON.stringify(text)})`);await ev(`Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()===${JSON.stringify(text)}).click()`)}
const nav=async text=>{await ev(`Array.from(document.querySelectorAll('[data-slot="sidebar-menu-button"]')).find(e=>e.textContent.trim()===${JSON.stringify(text)}).click()`)}
const setValue=async(selector,value)=>{await ev(`(()=>{let e=document.querySelector(${JSON.stringify(selector)});Object.getOwnPropertyDescriptor(e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:e.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value').set.call(e,${JSON.stringify(value)});e.dispatchEvent(new Event('input',{bubbles:true}))})()`)}
const choose=async(selector,value)=>{await setValue(selector,value);await ev(`document.querySelector(${JSON.stringify(selector)}).dispatchEvent(new Event('change',{bubbles:true}))`)}
const injection=String.raw`(()=>{
 const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
 window.fixture={tasks:{},sends:0,cancels:0,workspaceWrites:0,conflict:false,tails:0,exports:[],metadata:[],details:0,progress:0,bodyRequests:[],delayedPost:null};const f=window.fixture;f.boot=Number(sessionStorage.getItem('fixture-boot')||0)+1;sessionStorage.setItem('fixture-boot',String(f.boot));
 const batch={id:41,name:'隔离大批次',mode:'ONCE',caseIds:[1],intervalSeconds:0,repeatCount:1,status:'DONE',totalExecs:1201,passCount:1201,failCount:0,errorCount:0,skippedExecs:0};
 const spec={id:1,name:'已有测试用例',method:'GET',url:'https://example.invalid/old',headers:'{}',body:'{}',enabled:true,groupId:null,timeoutMs:1000,expectStatus:'200',options:{bodyType:'JSON',auth:{type:'NONE'},params:[],extractors:[]}};
 window.fetch=async(input,init)=>{
  const u=new URL(typeof input==='string'?input:input.url,location.href),p=u.pathname,m=init?.method||'GET';
  if(p==='/api/meta')return reply({name:'隔离平台',version:'fixture'});
  if(p==='/api/devices')return reply([{serial:'fixture-local-device',model:'测试夹具',status:'device'}]);
  if(p==='/api/runs'||p==='/api/perf/runs'||p==='/api/perf/runs/active')return reply([]);
  if(p==='/api/perf/runs/page')return reply({items:[],hasMore:false,nextCursor:null});
  if(p==='/api/apitest/cases')return reply([spec]);
  if(p==='/api/apitest/prepare')return reply({message:'隔离错误',requestId:'fixture-diagnostic-1234'},500);
  if(p==='/api/apitest/case-groups'||p==='/api/apitest/scenarios'||p==='/api/apitest/codes')return reply([]);
  if(p==='/api/apitest/envs')return reply([{id:1,name:'测试环境一',isDefault:true,strictVariables:true},{id:2,name:'测试环境二',isDefault:false}]);
  if(p==='/api/apitest/vars')return reply({base:[{name:'base',value:'https://example.invalid'}],header:[],body:[]});
  if(p==='/api/apitest/sign')return reply({env:Number(u.searchParams.get('envId')||1),enabled:false,hasSecret:false});
  if(p==='/api/apitest/capabilities')return reply({methods:['GET','POST'],systemVariables:[]});
  if(p.startsWith('/api/apitest/workspaces/')){
    const id=p.split('/').pop(),key='fixture-server-'+id,stored=JSON.parse(sessionStorage.getItem(key)||'null');
    if(m==='GET')return reply(stored||{id,revision:0,content:null});
    const b=JSON.parse(init.body);f.workspaceWrites++;
    if(f.conflict){f.conflict=false;if(stored){stored.revision++;sessionStorage.setItem(key,JSON.stringify(stored))}return reply({message:'版本冲突'},409)}
    if(b.expectedRevision!==(stored?.revision||0))return reply({message:'版本冲突'},409);
    if(f.forkFailure&&!stored){f.forkFailure=false;return reply({message:'隔离分叉保存失败'},503)}
    const value={id,revision:b.expectedRevision+1,content:b.content,updatedAt:new Date().toISOString()};sessionStorage.setItem(key,JSON.stringify(value));return reply(value);
  }
  if(p==='/api/apitest/requests'&&m==='POST'){
    const b=JSON.parse(init.body);f.sends++;f.bodyRequests.push(b.spec);if(f.unknown)throw new TypeError('fixture network failure');const task={requestId:b.requestId,state:'RUNNING',exec:null,cancelRequested:false,serverOutcomeUnknown:false};f.tasks[b.requestId]=task;
    if(f.sends===1){const response=structuredClone(task);await new Promise(r=>f.delayedPost=r);return reply(response)}
    task.state='DONE';task.exec={id:f.sends,caseName:b.spec.name,verdict:'PASS',httpStatus:200,elapsedMs:1,reqBody:b.spec.body,respBody:'{"from":"second"}',metadata:'{}'};return reply(task);
  }
  if(p.startsWith('/api/apitest/requests/')){
    const id=p.split('/')[4],task=f.tasks[id];if(!task)return reply({message:'尚未登记'},404);
    if(p.endsWith('/cancel')){f.cancels++;task.state='CANCELLED';task.cancelRequested=true;task.serverOutcomeUnknown=true;task.exec={id:1,verdict:'ERROR',reason:'已取消',httpStatus:null,elapsedMs:12,metadata:'{"cancelled":true}'};return reply(task)}
    return reply(task);
  }
  if(p==='/api/apitest/batches')return reply([batch]);
  if(p==='/api/apitest/batches/999/progress'){f.missingReads=(f.missingReads||0)+1;return reply({message:'批次不存在'},404)}
  if(p==='/api/apitest/batches/41/plan')return reply({kind:'SCENARIO',capturedAt:'fixture',stepCount:2,rowCount:2,repeatCount:1,dataColumns:['id'],steps:[{seq:1,name:'依赖查询',method:'GET',envName:'测试环境一',polling:true},{seq:2,name:'清理',method:'POST',envName:'测试环境一',cleanup:true}]});
  if(p==='/api/apitest/batches/41/plan/diff')return reply({changed:true,currentAvailable:true,changes:[{seq:1,field:'请求体',status:'CHANGED'}],warnings:[]});
  if(p==='/api/apitest/batches/41/retry-failed/preview')return reply({available:true,failedSteps:1,rows:2,wholeScenario:true,originalRounds:[1],warnings:[]});
  if(p==='/api/apitest/batches/41/retry-failed'&&m==='POST'){f.replays=(f.replays||[]);f.replays.push(JSON.parse(init.body));return reply({...batch,id:42,totalExecs:0,passCount:0})}
  if(p==='/api/apitest/batches/42/progress')return reply({batch:{...batch,id:42,totalExecs:0,passCount:0},running:false,current:null});
  if(p==='/api/apitest/batches/42/executions')return reply({items:[],nextAfterId:0,hasMore:false});
  if(p==='/api/apitest/batches/41/progress'){f.progress++;return reply({batch,running:false,current:null})}
  if(p==='/api/apitest/batches/41/executions'){
    const after=Number(u.searchParams.get('afterId')||0),end=Math.min(1201,after+100),items=Array.from({length:end-after},(_,i)=>({id:after+i+1,caseName:'摘要-'+(after+i+1),roundNo:1,seq:after+i+1,verdict:'PASS',httpStatus:200,elapsedMs:1,ts:'2026-10-14T00:00:00Z'}));
    return reply({items,nextAfterId:end,hasMore:end<1201});
  }
  if(p.startsWith('/api/apitest/history/')){f.details++;return reply({id:Number(p.split('/').pop()),caseName:'已载入完整正文',verdict:'PASS',httpStatus:200,respBody:'{"deep":"full detail"}',reqBody:'{}',metadata:'{}'})}
  if(p==='/api/logcat/keywords')return reply([]);
  if(p==='/api/logcat/tail'){
    f.tails++;let lines=[];
    if(f.tails===1)lines=Array.from({length:200005},(_,i)=>'10-14 10:00:00.000  123  456 I fixture: '+(i%2?'alpha':'beta')+' '+i);
    else if(f.injectSlow){f.injectSlow=false;lines=['10-14 10:00:00.000  123  456 I fixture: '+'a'.repeat(60000)+'!']}
    else if(f.continuous)lines=['10-14 10:00:00.000  123  456 I fixture: alpha tick '+f.tails];
    return reply({lines,anchor:'anchor-'+f.tails,reset:false,deviceOffline:false});
  }
  if(p==='/api/apitest/history')return reply({items:[],nextBeforeId:null});
  return reply({message:'未配置隔离端点 '+p},404);
 };
 const create=URL.createObjectURL;URL.createObjectURL=function(blob){f.exports.push(blob);return create.call(this,blob)};HTMLAnchorElement.prototype.click=function(){f.filename=this.download};
})()`
try{
 await command('Page.enable');await command('Emulation.setDeviceMetricsOverride',{width:1500,height:1050,deviceScaleFactor:1,mobile:false});await command('Page.addScriptToEvaluateOnNewDocument',{source:injection});await command('Page.navigate',{url:APP+'#/apitest'});await wait(`document.body.innerText.includes('用例管理')`)
 check('深链接直接打开接口页',await ev(`location.hash==='#/apitest'`))
 await ev(`document.querySelector('[data-testid=quick-open] [role=button]').click()`);await wait(`document.querySelector('[data-testid=quick-env]')`)
 await choose('[data-testid=quick-env]','1');await setValue('[aria-label="请求名称"]','草稿甲');await setValue('[aria-label="请求地址"]','https://example.invalid/slow');await setValue('#quick-body','{"id":12345678901234567890123456789012345678,"secret":"fixture-only"}')
 await wait(`window.fixture.workspaceWrites>0`);check('自动持久化工作区，浏览器正式存储键仅含UUID',await ev(`Object.entries(localStorage).filter(([k])=>k.startsWith('sudpanel-http')).every(([,v])=>/^[0-9a-f-]{36}$/.test(v))`))
 await ev(`document.querySelector('[data-testid=quick-send]').click()`);await wait(`window.fixture.sends===1&&document.body.innerText.includes('取消当前请求')`);await clickText('取消当前请求');await wait(`document.body.innerText.includes('请求 CANCELLED')`);await ev('window.fixture.delayedPost()');await pause(500)
 check('迟到发送响应不能覆盖取消终态',await ev(`document.body.innerText.includes('请求 CANCELLED')&&!document.querySelector('[data-testid=quick-send]').disabled&&window.fixture.sends===1&&window.fixture.cancels===1`))
 check('取消显示服务端结果仍需核对',await ev(`document.body.innerText.includes('服务器可能已处理请求')`))
 await clickText('新标签');await wait(`document.querySelector('[aria-label="请求名称"]')?.value===''&&document.querySelectorAll('[role=tab]').length===2`);await choose('[data-testid=quick-env]','2');await setValue('[aria-label="请求名称"]','草稿乙');await setValue('[aria-label="请求地址"]','https://example.invalid/fast');await pause(150);await ev(`document.querySelector('[data-testid=quick-send]').click()`);await wait(`document.body.innerText.includes('请求 DONE')`)
 await ev(`Array.from(document.querySelectorAll('[role=tab]')).find(e=>e.textContent.includes('草稿甲')).click()`);await wait(`document.querySelector('[aria-label="请求名称"]').value==='草稿甲'`);check('请求标签结果隔离并保留38位原文',await ev(`document.body.innerText.includes('请求 CANCELLED')&&document.querySelector('#quick-body').value.includes('12345678901234567890123456789012345678')`))
 await choose('[aria-label="从已有用例载入"]','1');await wait(`document.querySelector('[data-testid=request-replace-changes]')`);check('载入其他用例前保护现有编辑',await ev(`document.querySelector('[aria-label="请求名称"]').value==='草稿甲'`));await clickText('继续编辑')
 await ev('window.fixture.conflict=true');await setValue('[aria-label="当前标签名称"]','并发另存');await wait(`Object.keys(sessionStorage).filter(k=>k.startsWith('fixture-server-')).length>=2`);await pause(700);check('并发版本冲突自动另存且保留草稿',await ev(`Object.keys(sessionStorage).filter(k=>k.startsWith('fixture-server-')).length>=2&&!document.body.innerText.includes('草稿尚未保存')`))
 await ev('window.fixture.conflict=true;window.fixture.forkFailure=true');await setValue('[aria-label="当前标签名称"]','分叉失败后重试');await wait(`document.body.innerText.includes('隔离分叉保存失败')`);await clickText('立即保存草稿');await wait(`!document.body.innerText.includes('草稿尚未保存')`);check('分叉保存失败后重试绑定实际成功工作区',await ev(`(()=>{const id=localStorage.getItem('sudpanel-http-workspace-v1');const saved=JSON.parse(sessionStorage.getItem('fixture-server-'+id)||'null');return saved&&JSON.parse(saved.content).tabs.some(t=>t.title==='分叉失败后重试')})()`))
 await clickText('立即保存草稿');await pause(600);const oldBoot=await ev('window.fixture.boot');await command('Page.reload');await wait(`window.fixture?.boot===${oldBoot+1}&&!!document.querySelector('[data-testid=quick-open] [role=button]')`);await ev(`document.querySelector('[data-testid=quick-open] [role=button]').click()`);await wait(`document.querySelector('[aria-label="请求名称"]')?.value==='草稿甲'`);check('刷新恢复多标签、环境和完整草稿',await ev(`document.querySelectorAll('[role=tab]').length===2&&document.querySelector('[data-testid=quick-env]').value==='1'&&document.querySelector('#quick-body').value.includes('12345678901234567890123456789012345678')`))
 await ev(`document.querySelector('[role=dialog] button[data-slot=dialog-close]').click()`);await clickText('#41');await wait(`document.body.innerText.includes('摘要-1201')`,20000);check('大批次增量页有界显示最近1000条',await ev(`document.querySelectorAll('tr[data-testid^="exec-row-"]').length===1000&&!!document.querySelector('[data-testid=exec-row-202]')&&!document.querySelector('[data-testid=exec-row-1]')&&window.fixture.details===0`))
 await ev(`document.querySelector('[data-testid=exec-row-1201]').click()`);await wait(`window.fixture.details===1&&document.body.innerText.includes('full detail')`);await ev(`document.querySelector('[data-testid=exec-row-1201]').click()`);await ev(`document.querySelector('[data-testid=exec-row-1201]').click()`);await pause(100);check('展开时才获取完整正文且复用已加载详情',await ev(`window.fixture.details===1&&document.body.innerText.includes('full detail')`))
 check('批次选择写入可刷新链接',await ev(`location.hash.includes('batchId=41')`))
 await nav('首页');await ev('history.back()');await wait(`location.hash.includes('batchId=41')&&document.body.innerText.includes('用例管理')`);check('浏览器返回恢复批次上下文',true)
 await nav('首页');await nav('接口测试');await wait(`document.querySelector('[data-testid=quick-open]')`)
 await ev(`Array.from(document.querySelectorAll('[role=button]')).find(e=>e.textContent.includes('用例管理')).click()`);await wait(`Array.from(document.querySelectorAll('[role=button]')).some(e=>e.textContent.includes('环境设置'))`);await ev(`Array.from(document.querySelectorAll('[role=button]')).find(e=>e.textContent.includes('环境设置')).click()`);await wait(`document.querySelector('[data-testid=env-card-1]')&&!document.querySelector('[placeholder=值]').disabled`);await setValue('[placeholder=值]','https://draft.example.invalid')
 await ev(`document.querySelector('[data-testid=env-card-2]').click()`);await wait(`document.querySelector('[data-testid=env-unsaved-changes]')`);check('切环境前提示草稿且未覆盖原值',await ev(`document.querySelector('[placeholder=值]').value==='https://draft.example.invalid'`));await clickText('保留草稿');await wait(`document.querySelector('[placeholder=值]')?.value==='https://example.invalid'`);await ev(`document.querySelector('[data-testid=env-card-1]').click()`);await wait(`document.querySelector('[placeholder=值]')?.value==='https://draft.example.invalid'`);check('切回环境恢复独立未保存草稿',true)
 await ev('history.back()');await wait(`document.body.innerText.includes('保留未保存的编辑？')`);await clickText('继续编辑');await wait(`location.hash==='#/apitest'&&!document.body.innerText.includes('保留未保存的编辑？')`);check('取消浏览器返回保留编辑与当前地址',await ev(`document.querySelector('[placeholder=值]').value==='https://draft.example.invalid'`))
 await ev('history.back()');await wait(`document.body.innerText.includes('保留未保存的编辑？')`);await clickText('保留草稿并离开');await wait(`location.hash==='#/dashboard'`);await ev('history.forward()');await wait(`location.hash==='#/apitest'&&document.querySelector('[placeholder=值]')?.value==='https://draft.example.invalid'`);check('确认返回后仍可前进且编辑恢复',true)
 await ev(`document.querySelector('[data-testid=env-cards]').closest('[role=dialog]').querySelector('[data-slot=dialog-close]').click()`);await wait(`document.querySelector('[data-testid=env-unsaved-changes]')`);await clickText('放弃修改');await pause(200);await ev(`document.querySelector('[role=dialog] [data-slot=dialog-close]').click()`);await pause(200)
 await ev(`document.querySelector('[data-testid=quick-open] [role=button]').click()`);await wait(`document.querySelector('[data-testid=quick-env]')`);await clickText('预览');await wait(`document.body.innerText.includes('fixture-diagnostic-1234')`);await ev(`document.querySelector('[role=dialog] [data-slot=dialog-close]').click()`);await pause(200);await ev(`Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim().startsWith('诊断')).click()`);await wait(`document.body.innerText.includes('本次会话诊断')`);await clickText('导出安全诊断');check('诊断编号贯通并导出最小字段',await ev(`(async()=>{const m=JSON.parse(await window.fixture.exports.at(-1).text());return m.diagnostics.some(r=>r.id==='fixture-diagnostic-1234'&&r.status===500)&&m.diagnostics.every(r=>Object.keys(r).every(k=>['id','at','kind','status','method'].includes(k)))})()`));await ev(`document.querySelector('[role=dialog] [data-slot=dialog-close]').click()`);await pause(150)
 await ev(`location.hash='#/apitest?batchId=999'`);await wait(`document.body.innerText.includes('批次不存在或已删除')`);const missingReads=await ev('window.fixture.missingReads');await pause(3200);check('失效批次链接明确提示并停止自动重试',await ev(`window.fixture.missingReads===${missingReads}&&!document.querySelector('[data-testid=batch-progress-error]').innerText.includes('自动重试')`))
 await nav('上报查询');await clickText('开始监听');await wait(`document.querySelector('[data-testid=logcat-export-all]')&&!document.querySelector('[data-testid=logcat-export-all]').disabled`,30000);await clickText('高级筛选');await setValue('[aria-label="包含词"]','alpha');await setValue('[aria-label="日志Tag"]','fixture');await setValue('[aria-label="日志PID"]','123');await wait(`!document.querySelector('[data-testid=logcat-export-filtered]').disabled`,10000);await pause(300)
 await ev(`document.querySelector('[data-testid=logcat-export-metadata]').click()`);check('20万行过滤与元信息记录容量和裁剪范围',await ev(`(async()=>{const m=JSON.parse(await window.fixture.exports.at(-1).text());return m.buffer.retainedLines===200000&&m.buffer.discardedInCurrentSegment===5&&m.buffer.filteredLines===100000&&m.filter.effective.pid==='123'})()`))
 await setValue('[aria-label="日志正则表达式"]','[');await wait(`document.body.innerText.includes('保留最近成功的过滤规则')`);check('错误正则保留最近成功条件且页面继续响应',true)
 await setValue('[aria-label="包含词"]','');await setValue('[aria-label="日志正则表达式"]','(a+)+$');await ev('window.fixture.injectSlow=true;window.fixture.continuous=true');await wait(`document.body.innerText.includes('过滤超过 2 秒')`,12000);await clickText('停止');check('病态正则在持续增量下可终止且停止按钮可用',await ev(`document.body.innerText.includes('开始监听')`))
 await setValue('[aria-label="日志正则表达式"]','alpha');await wait(`!document.querySelector('[data-testid=logcat-export-filtered]').disabled`,10000);check('终止病态计算后新规则可恢复过滤',true)
 await nav('接口测试');await clickText('#41');await wait(`location.hash.includes('batchId=41')`);await clickText('执行计划与重跑');await wait(`document.body.innerText.includes('当前配置已有变化')`)
 check('重跑先展示冻结步骤、差异、依赖链清理与受影响数据行',await ev(`document.body.innerText.includes('请求链将对这些数据行重跑整条链')&&document.body.innerText.includes('条件轮询')&&document.body.innerText.includes('清理')&&!(window.fixture.replays||[]).length`))
 await ev(`Array.from(document.querySelectorAll('[role=dialog] input[type=radio]')).at(-1).click()`);await pause(100);check('当前配置变化未经确认不能启动重跑',await ev(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='仅重跑最终失败项').disabled`))
 await ev(`document.querySelector('[role=dialog] button[role=checkbox]').click()`);await pause(100);check('明确确认差异后允许重跑',await ev(`!Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='仅重跑最终失败项').disabled`))
 await clickText('仅重跑最终失败项');await wait(`location.hash.includes('batchId=42')&&!document.querySelector('[role=dialog]')`);check('主动重跑仅创建一次新批次并导航到新结果',await ev(`window.fixture.replays.length===1&&window.fixture.replays[0].source==='CURRENT'&&window.fixture.replays[0].confirmConfigChanges===true`))
}catch(error){check('专项完整执行',false);console.error(error.stack);try{console.error(await ev('document.body.innerText.slice(-3500)'))}catch{}}
finally{await writeFile(join(OUTPUT,'ui-continuation-results.json'),JSON.stringify(checks,null,2));ws.close();console.log(JSON.stringify({passed:checks.filter(c=>c.passed).length,total:checks.length}));if(checks.some(c=>!c.passed))process.exitCode=1}
