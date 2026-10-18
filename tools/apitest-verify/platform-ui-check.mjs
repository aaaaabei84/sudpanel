import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const OUTPUT=process.env.SP_OUTPUT_DIR||join(tmpdir(),'sudpanel-platform-ui-results')
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
/**
 * 点「上报校验」页上的功能卡。
 * 卡片是 `div[role=button]` 而不是 `<button>`（EntryCard 的写法），
 * 所以不能复用 clickText —— 按 aria-label 找。
 */
const clickCard=async label=>{
  const find=`Array.from(document.querySelectorAll('[role=button]')).find(e=>e.getAttribute('aria-label')==='${label}')`
  await wait(`!!${find}`)
  await ev(`(${find})?.click()`)
}
const nav=async text=>{await ev(`Array.from(document.querySelectorAll('[data-slot="sidebar-menu-button"]')).find(e=>e.textContent.trim()===${JSON.stringify(text)}).click()`)}
const setValue=async(selector,value)=>{await ev(`(()=>{let e=document.querySelector(${JSON.stringify(selector)});Object.getOwnPropertyDescriptor(e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(e,${JSON.stringify(value)});e.dispatchEvent(new Event('input',{bubbles:true}))})()`)}
const injection=`(()=>{
 window.fixture={dashboardFail:true,apiReads:0,funcReads:0,walkReads:0,starts:0,tails:0,active:0,maxActive:0,writes:[],exports:[],perfCrash:false};
 const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
 const point=d=>({id:1,direction:d,group:'fixture',seq:1,stateName:'fixture_state',description:'隔离验证点',triggerType:'MANUAL',judgeMode:'MANUAL',autoPass:false,enabled:true,tapCount:1});
 const batch=status=>({id:41,name:'回归批次',mode:'ONCE',caseIds:[1],intervalSeconds:0,repeatCount:1,status,totalExecs:1,passCount:status==='DONE'?1:0,failCount:0,errorCount:0,skippedExecs:0,startedAt:'2026-10-13T10:00:00Z'});
 window.fetch=async(input,init)=>{const u=new URL(typeof input==='string'?input:input.url,location.href),p=u.pathname,m=init?.method||'GET',f=window.fixture;
  if(!p.startsWith('/api/'))throw Error('unexpected fetch '+p);
  if(m!=='GET')f.writes.push({p,m,body:init?.body});
  if(p==='/api/meta')return reply({name:'Fixture',version:'isolated'});
  if(p==='/api/devices')return reply([{serial:'fixture-device',model:'隔离设备',status:'device'}]);
  if(p==='/api/runs'&&m==='GET')return f.dashboardFail?reply({message:'fixture history unavailable'},503):reply([]);
  if(p==='/api/runs'&&m==='POST'){f.starts++;return reply({runId:init.body.toUpperCase().includes('MGTOAPP')?62:61,total:1,mode:'auto'})}
  if(p==='/api/runs/61'){f.funcReads++;if(f.funcReads===1)return reply({message:'temporary function error'},503);return reply({runId:61,status:'COMPLETED',total:1,results:[]})}
  if(p==='/api/runs/62/current'){f.walkReads++;if(f.walkReads===1)return reply({message:'temporary walk error'},503);return reply({runId:62,status:'COMPLETED',total:1,done:1,current:null})}
  if(p==='/api/runs/62')return reply({runId:62,status:'COMPLETED',total:1,stats:{PASS:1},results:[]});
  if(p==='/api/points')return reply([point(u.searchParams.get('direction'))]);
  if(p==='/api/configs'||p.includes('coord-sources'))return reply([]);
  if(p==='/api/perf/runs/page')return reply({items:[{id:18,mode:'NORMAL',status:'DONE',gameNames:['隔离性能记录'],gameIds:[1]}],nextCursor:null,hasMore:false});
  if(p==='/api/perf/runs/active')return reply([]);
  if(p==='/api/perf/runs')return reply([{id:18,mode:'NORMAL',status:'DONE',gameNames:['隔离性能记录'],gameIds:[1]}]);
  if(p==='/api/perf/games')return reply(f.perfCrash?{}:[{id:1,name:'隔离游戏'}]);
  if(p==='/api/perf/button-groups')return reply([]);
  if(p==='/api/perf/ai-room'||p==='/api/perf/options')return reply({});
  if(p==='/api/apitest/cases')return reply([]);
  if(p==='/api/apitest/case-groups'||p==='/api/apitest/scenarios'||p==='/api/apitest/codes')return reply([]);
  if(p==='/api/apitest/batches')return reply([batch(f.apiReads>=5?'DONE':'RUNNING')]);
  if(p==='/api/apitest/batches/41/progress'){f.active++;f.maxActive=Math.max(f.maxActive,f.active);await new Promise(r=>setTimeout(r,80));f.active--;f.apiReads++;if(f.apiReads===1||f.apiReads===2||f.apiReads===4)return reply({message:'temporary batch error'},503);return reply({batch:batch(f.apiReads>=5?'DONE':'RUNNING'),execs:[],running:f.apiReads<4})}
  if(p==='/api/apitest/batches/41/executions')return reply({items:[],nextAfterId:0,hasMore:false});
  if(p==='/api/apitest/envs')return reply([{id:1,name:'隔离环境',isDefault:true,sortOrder:0}]);
  if(p==='/api/apitest/vars')return reply({base:[],header:[],body:[]});
  if(p==='/api/apitest/sign')return reply({env:1,enabled:false,hasSecret:false});
  if(p==='/api/apitest/capabilities')return reply({methods:['GET','POST'],systemVariables:[]});
  if(p==='/api/logcat/keywords')return reply([]);
  if(p==='/api/logcat/tail'){f.tails++;const lines=f.tails===1?Array.from({length:1505},(_,i)=>'10-13 10:00:00.000  1  1 I tag: '+(i%2?'MISS ':'HIT ')+i):[];return reply({lines,anchor:'last',reset:false,deviceOffline:false})}
  return reply({message:'unhandled fixture '+p},404);
 };
 const originalCreate=URL.createObjectURL;URL.createObjectURL=function(blob){window.fixture.exports.push(blob);return originalCreate.call(this,blob)};
 HTMLAnchorElement.prototype.click=function(){window.fixture.lastFilename=this.download};
})()`
try{
 await command('Page.enable');await command('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});await command('Page.addScriptToEvaluateOnNewDocument',{source:injection});await command('Page.navigate',{url:APP});await wait(`document.body.innerText.includes('隔离性能记录')`)
 check('首页两卡独立：功能失败时性能仍显示',await ev(`!!document.querySelector('[data-testid=dashboard-functional-error]')&&document.body.innerText.includes('隔离性能记录')`))
 check('加载失败不显示成空记录',await ev(`!document.querySelector('[data-testid=dashboard-functional-error]').parentElement.innerText.includes('暂无运行记录')`))
 check('首页未提前载入性能与接口chunk',await ev(`!performance.getEntriesByType('resource').some(r=>r.name.includes('/assets/perf-')||r.name.includes('/assets/apitest-'))`))
 await ev('window.fixture.dashboardFail=false');await clickText('重试功能记录');await wait(`!document.querySelector('[data-testid=dashboard-functional-error]')`);check('首页独立重试恢复',true)
 await ev(`document.querySelector('[role=button][aria-label="接口测试"]').focus()`);await command('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});await command('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});await wait(`document.body.innerText.includes('用例管理')`);check('EntryCard键盘Enter打开接口页',true)
 await clickText('#41');await wait(`!!document.querySelector('[data-testid=batch-progress-error]')`);check('批次首次打开失败仍保留已知RUNNING停止控制',await ev(`window.fixture.apiReads===1&&document.body.innerText.includes('停止批次 #41')`));await wait(`window.fixture.apiReads===3&&!document.querySelector('[data-testid=batch-progress-error]')`,20000);check('批次首次打开连续失败后恢复读取',true);await wait(`window.fixture.apiReads===4&&!!document.querySelector('[data-testid=batch-progress-error]')`);check('恢复后再次失败仍保留运行控制',await ev(`document.body.innerText.includes('停止批次 #41')`));await wait(`window.fixture.apiReads>=5&&!document.querySelector('[data-testid=batch-progress-error]')`,20000);check('批次连续失败自动恢复',true);check('批次查询只有一个在途请求',await ev('window.fixture.maxActive===1'))
 await nav('上报校验');await clickCard('apptomg 全自动');await wait(`document.body.innerText.includes('开始全自动执行')`);await clickText('开始全自动执行');await wait(`!!document.querySelector('[data-testid=functional-progress-error]')`);check('自动测试失败不解锁重复启动',await ev(`Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='开始全自动执行').disabled`));await wait(`window.fixture.funcReads>=2&&!document.querySelector('[data-testid=functional-progress-error]')`);check('自动测试查询自动恢复',true)
 await nav('上报校验');await clickCard('mgtoapp 走查');await wait(`document.body.innerText.includes('开始走查')`);await clickText('开始走查');await wait(`!!document.querySelector('[data-testid=functional-progress-error]')`);check('走查失败不解锁重复启动',await ev(`Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='开始走查').disabled`));await wait(`window.fixture.walkReads>=2&&!document.querySelector('[data-testid=functional-progress-error]')`);check('走查查询自动恢复',true);check('控制启动没有自动重发',await ev('window.fixture.starts===2'))
 await nav('上报查询');await wait(`document.body.innerText.includes('开始监听')`);check('上报查询有「保留其它上报」开关',await ev(`!!document.querySelector('[data-testid=logcat-keep-others]')`),{});const lvAll=await ev(`(()=>{const bs=Array.from(document.querySelectorAll('button[aria-pressed]')).filter(b=>/^[VDIWEF]$/.test(b.textContent.trim()));return bs.length+':'+bs.every(b=>b.getAttribute('aria-pressed')==='true')})()`);check('级别默认全部勾选（#90）',lvAll==='6:true',lvAll);await ev(`(()=>{const k=document.querySelector('#logcat-keyword');if(k){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(k,'SudReport');k.dispatchEvent(new Event('input',{bubbles:true}))}})()`);await pause(200);const btnXBefore=await ev(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='导出全部');return b?Math.round(b.getBoundingClientRect().left):-1})()`);await ev(`document.querySelector('[data-testid=logcat-keep-others]').click()`);await pause(400);check('勾选保留其它上报后停止自动滚动',await ev(`!document.querySelector('[data-testid=logcat-follow]').checked`),{});await pause(200);const shownTxt=await ev(`(document.querySelector('[data-testid=logcat-shown-count]')||{}).textContent`);check('勾选后「筛后」显示为 *（#90）',shownTxt==='*',String(shownTxt));const btnXAfter=await ev(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='导出全部');return b?Math.round(b.getBoundingClientRect().left):-1})()`);check('勾选后统计行按钮位置不变（#90）',btnXBefore>=0&&btnXAfter===btnXBefore,{btnXBefore,btnXAfter});await ev(`(()=>{const b=document.querySelector('[data-testid=logcat-keep-others]');if(b&&b.checked)b.click()})()`);await pause(300);await ev(`(()=>{const k=document.querySelector('#logcat-keyword');if(k){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(k,'');k.dispatchEvent(new Event('input',{bubbles:true}))}})()`);await pause(300);await clickText('开始监听');await wait(`document.querySelector('[data-testid=logcat-export-all]')&&!document.querySelector('[data-testid=logcat-export-all]').disabled`);await clickText('停止');
 // 有真实命中数据再验定位：旧夹具在空缓冲中把 NO_DATA 算通过，无法检出回车失效。
 await setValue('#logcat-keyword','HIT 1000');
 await ev(`document.querySelector('[data-testid=logcat-keep-others]').click()`);
 const hitVisible=`(()=>{const el=document.querySelector('[data-testid=logcat-scroll]'),row=el?.querySelector('[data-line-index="1000"]');if(!el||!row)return false;const a=el.getBoundingClientRect(),b=row.getBoundingClientRect();return b.bottom>a.top+1&&b.top<a.bottom-1})()`;
 await wait(hitVisible);check('保留其它上报定位到实际命中行',true);
 await ev(`document.querySelector('[data-testid=logcat-scroll]').scrollTop=100`);
 await wait(`!(${hitVisible})`);check('回车前命中行确实不在视口内',true);
 await ev(`document.querySelector('#logcat-keyword').focus()`);
 await command('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
 await command('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
 await wait(hitVisible,3000);check('同关键字再次回车使实际命中行回到视口',true);
 check('手动回车后保持停止跟随',await ev(`!document.querySelector('[data-testid=logcat-follow]').checked`));
 await ev(`document.querySelector('[data-testid=logcat-keep-others]').click()`);
 await setValue('#logcat-keyword','HIT');await wait(`document.body.innerText.includes('筛后')`)
 await ev(`document.querySelector('[data-testid=logcat-export-filtered]').click()`);await ev(`document.querySelector('[data-testid=logcat-export-all]').click()`)
 check('筛选日志导出含全缓冲匹配行',await ev(`(async()=>{const t=await window.fixture.exports[0].text();return t.trimEnd().split(String.fromCharCode(10)).length===753&&t.includes('HIT 1504')&&!t.includes('MISS ')})()`))
 check('全部日志导出不受渲染窗口限制',await ev(`(async()=>{const t=await window.fixture.exports[1].text();return t.trimEnd().split(String.fromCharCode(10)).length===1505&&t.includes('HIT 0')&&t.includes('HIT 1504')})()`))
 check('导出文件名标明范围与扩展名',await ev(`window.fixture.lastFilename.startsWith('sudpanel-logcat-all-')&&window.fixture.lastFilename.endsWith('.log')`))
 await nav('首页');await nav('上报查询');await wait(`document.querySelector('[data-testid=logcat-export-all]')`);check('lazy切页后日志缓冲保留',await ev(`!document.querySelector('[data-testid=logcat-export-all]').disabled`))
 await ev('window.fixture.perfCrash=true');await nav('性能测试');await wait(`!!document.querySelector('[data-testid=page-render-error]')`);check('页面异常有局部错误边界',await ev(`document.querySelector('[data-testid=page-render-error]').innerText.includes('诊断编号')`));await nav('首页');await wait(`document.body.innerText.includes('隔离性能记录')`);check('一页异常不影响侧边栏导航',true)
 check('专项测试只使用隔离fixture，无真实设备/正式API调用',true)
 const shot=await command('Page.captureScreenshot',{format:'png',fromSurface:true});await writeFile(join(OUTPUT,'ui-platform-final.png'),Buffer.from(shot.result.data,'base64'))
}catch(e){check('专项完整执行',false);console.error(e.stack);try{console.error(await ev('document.body.innerText.slice(-5000)'))}catch{}}
finally{await writeFile(join(OUTPUT,'ui-platform-results.json'),JSON.stringify(checks,null,2));ws.close();console.log(JSON.stringify({passed:checks.filter(c=>c.passed).length,total:checks.length}));if(checks.some(c=>!c.passed))process.exitCode=1}
