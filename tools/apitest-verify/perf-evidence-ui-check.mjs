import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 真实浏览器界面 + 全部虚构性能夹具；不启动业务任务、不连接手机。
import { writeFile } from 'node:fs/promises'
const APP=requiredUiUrl('SP_APP_URL');
const CDP=requiredUiUrl('SP_CDP');
for (const url of [APP,CDP]) if(new URL(url).hostname!=='127.0.0.1') throw Error('isolated loopback only');
const targets=await isolatedUiPages(APP, CDP);
const ws=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
await new Promise(resolve=>ws.addEventListener('open',resolve));
let next=0,total=0,bad=0,scriptId;const pending=new Map();
ws.addEventListener('message',event=>{const msg=JSON.parse(event.data);if(pending.has(msg.id)){pending.get(msg.id)(msg);pending.delete(msg.id)}});
const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++next,timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method))},10000);pending.set(id,msg=>{clearTimeout(timer);resolve(msg)});ws.send(JSON.stringify({id,method,params}))});
const ev=async expression=>{const msg=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(msg.error||msg.result.exceptionDetails)throw Error(JSON.stringify(msg.error||msg.result.exceptionDetails));return msg.result.result.value};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const wait=async expression=>{for(let i=0;i<200;i++){if(await ev(expression))return;await sleep(50)}throw Error('UI wait '+expression)};
const real=async expression=>{const r=await ev(`(()=>{const e=${expression};if(!e)throw Error('missing control');e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`);for(const type of ['mousePressed','mouseReleased'])await send('Input.dispatchMouseEvent',{type,button:'left',clickCount:1,...r})};
const text=s=>real(`[...document.querySelectorAll('button,[role=option]')].find(e=>e.textContent.trim()===${JSON.stringify(s)})`);
const click=selector=>real(`document.querySelector(${JSON.stringify(selector)})`);
const check=async(name,expression)=>{const passed=await ev(expression);total++;if(!passed)bad++;console.log((passed?'PASS ':'FAIL ')+name)};
const close=async()=>{await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});await wait(`!document.querySelector('[role=dialog]')`)};
try{
await send('Page.enable');await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
const result=await send('Page.addScriptToEvaluateOnNewDocument',{source:`(()=>{
const original=window.fetch;window.perfEvidenceFixture={writes:[],preflights:[],hold:false,release:null};
window.fetch=async(input,init)=>{const rawPath=new URL(typeof input==='string'?input:input.url,location.href).pathname;const path=(rawPath==='/api/perf/runs/page'||rawPath==='/api/perf/runs/active')?'/api/perf/runs':rawPath;const method=(init?.method||'GET').toUpperCase();const f=window.perfEvidenceFixture;let body;
if(path==='/api/devices')body=[{serial:'FICTIONAL-DEVICE',status:'device',model:'FICTIONAL'}];
else if(path==='/api/perf/preflight'){const payload=JSON.parse(init.body);f.preflights.push(payload);body={ready:payload.gameIds.length>0,checkedAt:'2026-10-14T00:00:00Z',checks:[{key:'device',name:'设备连接',status:'PASS',message:'只读检查通过'}]};}
else if(path.startsWith('/api/perf/')&&method!=='GET'){f.writes.push(path);return new Response('{}',{status:409});}
else if(path==='/api/perf/games')body=[{id:501,name:'EVIDENCE-GAME',shortName:'EVIDENCE-GAME'}];
else if(path==='/api/perf/runs')body=[{id:401,status:'COMPLETED',mode:'AI_ROOM',gameQueue:'501',loopCount:2,sampleSeconds:5}];
else if(path==='/api/perf/button-groups')body=[];
else if(path==='/api/perf/ai-room'||path==='/api/perf/options')body={};
else if(path.endsWith('/checks'))body=[];
else if(path.endsWith('/growth-curves'))body={curves:[],totalOk:2};
else if(path.endsWith('/loops'))body=[{id:5011,runId:401,gameId:501,loopNo:1,status:'OK',pssAvgKb:1024},{id:5012,runId:401,gameId:501,loopNo:2,status:'OK',pssAvgKb:1024}];
else if(path==='/api/perf/loops/5011/evidence')body={recorded:true,cleanup:{status:'FAILED',reason:'fictional cleanup failed',evidenceMissing:true},steps:[{id:201,step:'开局',verdict:'PASS',httpStatus:200,elapsedMs:10},{id:202,step:'清空房间',verdict:'FAIL',httpStatus:200,elapsedMs:12,truncated:true}]};
else if(path==='/api/perf/loops/5012/evidence')body={recorded:false,cleanup:{status:'NOT_RECORDED',reason:'未记录清房证据，可能是旧记录或存证失败'},steps:[]};
else if(path==='/api/perf/steps/201')body={id:201,step:'开局',truncated:false,exec:{id:null,caseName:'fictional',verdict:'PASS',method:'POST',url:'http://127.0.0.1/fake',reqHeaders:'{}',reqBody:'{"id":"12345678901234567890123456789012345678"}',respHeaders:'{}',respBody:'{"step":"FIRST_DETAIL"}',assertsResult:'[]',metadata:'{"envName":"fictional-env"}'}};
else if(path==='/api/perf/steps/202'){if(f.hold)await new Promise(resolve=>{f.release=resolve});body={id:202,step:'清空房间',truncated:true,exec:{id:null,verdict:'FAIL',reason:'fictional failure',method:'POST',url:'http://127.0.0.1/fake',reqBody:'{}',respBody:'{"step":"SECOND_DETAIL"}',assertsResult:'[]'}};}
else if(path.startsWith('/api/perf/'))return new Response('{}',{status:409});
else return original(input,init);
if(rawPath==='/api/perf/runs/page')body={items:body,nextCursor:null,hasMore:false};else if(rawPath==='/api/perf/runs/active')body=Array.isArray(body)?body.filter(run=>['RUNNING','WAIT_DEVICE','WAIT_ENV'].includes(run.status)):[];
      return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});};})()`});scriptId=result.result.identifier;
await send('Page.navigate',{url:APP});await wait(`!!window.perfEvidenceFixture&&[...document.querySelectorAll('button')].some(e=>e.textContent.trim()==='性能测试')`);await text('性能测试');
await wait(`!!document.querySelector('[data-testid=perf-preflight-open]')`);await click('[data-testid=perf-preflight-open]');await wait(`document.querySelector('[data-testid=perf-preflight-dialog]')?.textContent.includes('以下项目')`);
await check('空目标检查有结果且不创建任务',`window.perfEvidenceFixture.preflights.length===1&&window.perfEvidenceFixture.writes.length===0`);await close();await text('全选');await click('[data-testid=perf-preflight-open]');await wait(`document.querySelector('[data-testid=perf-preflight-dialog]')?.textContent.includes('没有阻断项')`);
await check('检查使用当前模式、设备和所选游戏',`JSON.stringify(window.perfEvidenceFixture.preflights[1].gameIds)==='[501]'&&window.perfEvidenceFixture.preflights[1].mode==='NORMAL'`);await close();
await real(`[...document.querySelectorAll('[role=combobox]')].find(e=>e.textContent.includes('选择运行'))`);await wait(`!!document.querySelector('[role=option]')`);await click('[role=option]');await text('EVIDENCE-GAME');await wait(`!!document.querySelector('[data-testid=perf-evidence-5011]')`);await click('[data-testid=perf-evidence-5011]');
await wait(`document.querySelector('[data-testid=perf-evidence-dialog]')?.textContent.includes('FIRST_DETAIL')`);
await check('圈详情可打开实际请求响应',`document.querySelector('[data-testid=perf-evidence-dialog]').textContent.includes('实际请求体')&&document.querySelector('[data-testid=perf-evidence-dialog]').textContent.includes('FIRST_DETAIL')`);
await check('长ID没有精度损失',`document.querySelector('[data-testid=perf-evidence-dialog]').textContent.includes('12345678901234567890123456789012345678')`);
await check('清房失败和采样有效分别呈现',`document.querySelector('[data-testid=perf-cleanup-state]').textContent.includes('未全部成功')&&[...document.querySelectorAll('tr')].some(e=>e.textContent.includes('OK'))`);
await check('证据缺失显式提醒',`document.querySelector('[data-testid=perf-cleanup-state]').textContent.includes('证据写入失败')`);
await check('本机报文隐私范围明确',`document.querySelector('[data-testid=perf-evidence-dialog]').textContent.includes('鉴权和业务数据')`);
await ev(`window.perfEvidenceFixture.hold=true`);await click('[data-testid=perf-step-202]');await wait(`document.querySelector('[data-testid=perf-evidence-dialog]')?.textContent.includes('读取实际报文')`);await check('切步骤等待清除旧报文',`!document.querySelector('[data-testid=perf-evidence-dialog]').textContent.includes('FIRST_DETAIL')`);await ev(`window.perfEvidenceFixture.release?.();window.perfEvidenceFixture.hold=false`);await wait(`document.querySelector('[data-testid=perf-evidence-dialog]')?.textContent.includes('SECOND_DETAIL')`);
await check('报文截断显式提醒',`document.querySelector('[data-testid=perf-evidence-dialog]').textContent.includes('不能作为完整报文取证')`);await close();await click('[data-testid=perf-evidence-5012]');await wait(`document.querySelector('[data-testid=perf-evidence-dialog]')?.textContent.includes('没有接口取证记录')`);await check('旧记录不伪装清房成功',`document.querySelector('[data-testid=perf-cleanup-state]').textContent.includes('未记录')&&!document.querySelector('[data-testid=perf-cleanup-state]').textContent.includes('均已通过')`);await close();
await send('Emulation.setDeviceMetricsOverride',{width:420,height:900,deviceScaleFactor:1,mobile:false});await click('[data-testid=perf-evidence-5011]');await wait(`!!document.querySelector('[data-testid=perf-evidence-dialog]')`);if(process.env.SP_SCREENSHOT){const shot=await send('Page.captureScreenshot',{format:'png'});await writeFile(process.env.SP_SCREENSHOT,Buffer.from(shot.result.data,'base64'));}await check('窄窗口弹窗边界可达',`(()=>{const r=document.querySelector('[data-testid=perf-evidence-dialog]').getBoundingClientRect();return r.x>=0&&r.right<=innerWidth+1&&r.y>=0&&r.bottom<=innerHeight+1})()`);await close();await check('所有检查均未启动或停止业务任务',`window.perfEvidenceFixture.writes.length===0`);
}finally{if(scriptId)await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:scriptId});ws.close()}
console.log('性能取证UI：'+(total-bad)+'/'+total+'，失败 '+bad);process.exitCode=bad?1:0;
