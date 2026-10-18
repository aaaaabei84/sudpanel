import { requiredUiUrl } from './isolated-ui.mjs'
// 新HTTP功能真实浏览器回归：只操作run-client的临时库/禁用ADB实例。
// 新建独立CDP target，无fetch注入；所有/api/apitest真实执行，wire oracle按唯一path隔离。
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const APP=requiredUiUrl('SP_APP_URL'),MOCK=process.env.SP_API_MOCK||'http://127.0.0.1:18889',CDP=requiredUiUrl('SP_CDP')
for(const [value,port] of [[APP,'18795'],[MOCK,'18889'],[CDP,'9223']]){const u=new URL(value);if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.port!==port)throw Error('Only run-client isolated HTTP/CDP ports are allowed')}
const OUTPUT=process.env.SP_UI_SCREENSHOT_DIR||join(tmpdir(),'sudpanel-night-client-ui-'+Date.now());await mkdir(OUTPUT,{recursive:true})
const BASE=new URL('/api/apitest',APP).href,LONG='12345678901234567890123456789012345678',suffix=Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,7),prefix='/capture/night-ui-'+suffix
const checks=[],sections=[],pause=ms=>new Promise(r=>setTimeout(r,ms)),quote=JSON.stringify
const check=(name,ok,detail)=>{checks.push({name,passed:!!ok});console.log((ok?'PASS ':'FAIL ')+name);if(!ok&&detail!==undefined)console.log(JSON.stringify(detail).slice(0,1400))}
const api=async(path,method='GET',body)=>{const response=await fetch(BASE+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});const text=await response.text();if(!response.ok)throw Error(`${method} ${path}: HTTP ${response.status}: ${text.slice(0,800)}`);return text?JSON.parse(text):undefined}
const journal=async()=>{const r=await fetch(MOCK+'/_control/journal');if(!r.ok)throw Error('wire oracle unavailable');return r.json()}
const wire=async(path)=>(await journal()).filter(e=>e.path===path)
const until=async(work,predicate,timeout=15000)=>{const deadline=Date.now()+timeout;while(Date.now()<deadline){const value=await work();if(predicate(value))return value;await pause(40)}throw Error('API condition deadline')}
const cdp=async url=>{const ws=new WebSocket(url),pending=new Map(),listeners=new Map();let id=0;await new Promise((r,j)=>{ws.addEventListener('open',r);ws.addEventListener('error',j)});ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id)}else if(m.method){for(const callback of listeners.get(m.method)||[])callback(m.params)}});return {close:()=>ws.close(),on:(method,callback)=>{const callbacks=listeners.get(method)||[];callbacks.push(callback);listeners.set(method,callbacks);return ()=>listeners.set(method,callbacks.filter(fn=>fn!==callback))},command:(method,params={})=>new Promise((r,j)=>{const current=++id,t=setTimeout(()=>{pending.delete(current);j(Error('CDP timeout '+method))},15000);pending.set(current,m=>{clearTimeout(t);if(m.error)j(Error(JSON.stringify(m.error)));else r(m.result)});ws.send(JSON.stringify({id:current,method,params}))})}}
let browser,page,targetId,env,dataGroup,dataCases=[],postmanCase,openapiCase
const ev=async expression=>{const result=await page.command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value}
const wait=async(expression,timeout=15000)=>until(()=>ev(`Boolean(${expression})`),v=>v,timeout)
const click=async selector=>{await wait(`document.querySelector(${quote(selector)})&&!document.querySelector(${quote(selector)}).matches(':disabled')`);const pos=await ev(`(async()=>{const e=document.querySelector(${quote(selector)});e.scrollIntoView({block:'center',behavior:'instant'});for(let i=0;i<4;i++)await new Promise(requestAnimationFrame);const r=e.getBoundingClientRect();const x=r.x+r.width/2,y=r.y+r.height/2;const hit=document.elementFromPoint(x,y);return {x,y,hit:hit===e||e.contains(hit)}})()`);if(!pos.hit)throw Error('Control obscured '+selector);await page.command('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,x:pos.x,y:pos.y});await page.command('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,x:pos.x,y:pos.y})}
const textSelector=async text=>{await wait(`(()=>{const ds=[...document.querySelectorAll('[role=dialog]')];const root=ds.at(-1)||document;return [...root.querySelectorAll('button')].some(e=>e.textContent.trim()===${quote(text)})})()`);return ev(`(()=>{const ds=[...document.querySelectorAll('[role=dialog]')];const root=ds.at(-1)||document;const e=[...root.querySelectorAll('button')].find(e=>e.textContent.trim()===${quote(text)});e.setAttribute('data-night-click',${quote(text)});return '[data-night-click='+JSON.stringify(${quote(text)})+']'})()`)}
const clickText=async text=>click(await textSelector(text))
const value=async(selector,text)=>{await wait(`document.querySelector(${quote(selector)})`);await wait(`(()=>{const e=document.querySelector(${quote(selector)});return e.tagName!=='SELECT'||[...e.options].some(o=>o.value===${quote(String(text))})})()`);await ev(`(()=>{const e=document.querySelector(${quote(selector)}),p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:e.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(e,${quote(String(text))});e.dispatchEvent(new Event(e.tagName==='SELECT'?'change':'input',{bubbles:true}))})()`)}
const fileValue=async(selector,content,name)=>{const filename=join(OUTPUT,name);await writeFile(filename,content);await wait(`document.querySelector(${quote(selector)})`);const document=await page.command('DOM.getDocument'),node=await page.command('DOM.querySelector',{nodeId:document.root.nodeId,selector});await page.command('DOM.setFileInputFiles',{nodeId:node.nodeId,files:[filename]});await wait(`document.body.textContent.includes(${quote(name)})&&!document.body.textContent.includes('正在读取文件')`)}
const closeDialog=async()=>{await wait(`document.querySelector('[role=dialog]')`);const selector=await ev(`(()=>{const d=[...document.querySelectorAll('[role=dialog]')].at(-1),e=d.querySelector('[data-slot=dialog-close]');e.setAttribute('data-night-close','yes');return '[data-night-close=yes]'})()`);await click(selector);await pause(130)}
const navigateApi=async()=>{if(await ev(`!![...document.querySelectorAll('button')].find(b=>b.textContent==='立即保存草稿')`)){await ev(`[...document.querySelectorAll('button')].find(b=>b.textContent==='立即保存草稿').click()`);await wait(`!document.body.innerText.includes('草稿待保存')&&!document.body.innerText.includes('正在保存草稿')&&!document.body.innerText.includes('草稿尚未保存')`)}const previous=await ev('performance.timeOrigin');await page.command('Page.navigate',{url:APP});await until(async()=>{try{return await ev(`performance.timeOrigin!==${previous}&&document.readyState==='complete'`)}catch(error){if(/context.*destroyed|Cannot find context/i.test(String(error)))return false;throw error}},v=>v);await wait(`document.querySelector('[data-slot=sidebar-menu-button]')`);await ev(`[...document.querySelectorAll('[data-slot=sidebar-menu-button]')].find(e=>e.textContent.trim()==='接口测试').click()`);await wait(`document.querySelector('[data-testid=quick-open]')`)}
const openQuick=async()=>{await click('[data-testid=quick-open] [data-slot=card]');await wait(`document.querySelector('[data-testid=quick-env]')`);await clickText('新标签');await wait(`document.querySelector('[aria-label="请求名称"]')?.value===''&&document.querySelector('[data-testid=quick-env]').value===''`)}
const recentId=async()=>Math.max(0,...(await api('/execs/recent?limit=200')).map(e=>e.id||0))
const sendQuick=async(path)=>{const before=await recentId();await click('[data-testid=quick-send]');const result=await until(()=>api('/execs/recent?limit=200'),rows=>rows.some(e=>e.id>before&&e.url.includes(path)));await wait(`!document.querySelector('[data-testid=quick-send]').disabled`);return result.find(e=>e.id>before&&e.url.includes(path))}
const screenshot=async name=>{if(!page)return;const result=await page.command('Page.captureScreenshot',{format:'png',fromSurface:true,captureBeyondViewport:false});await writeFile(join(OUTPUT,name+'.png'),Buffer.from(result.data,'base64'))}
const section=async(name,work)=>{console.log('\n['+name+']');const before=checks.length;try{await work()}catch(error){check(name+' 完整执行',false,String(error));console.error(error.stack);try{console.error(await ev('document.body.innerText.slice(-4500)'));await screenshot('night-failure-'+sections.length)}catch{}}sections.push({name,checks:checks.length-before,passed:checks.slice(before).every(c=>c.passed)})}
const spec=(name,path,body)=>({name,method:'POST',url:MOCK+path,headers:'{"Content-Type":"application/json"}',body,note:'隔离浏览器新功能回归',expectStatus:'200',expectCode:'0',timeoutMs:8000,insecure:false,enabled:true,groupId:dataGroup?.id??null,asserts:JSON.stringify([{path:'received.id',op:'equals',expect:'{{data.id}}'}]),options:{bodyType:'JSON',params:[],auth:{type:'NONE'},extractors:[],strictVariables:true}})
try{
 const devices=await(await fetch(new URL('/api/devices',APP))).json();if(!Array.isArray(devices)||devices.length)throw Error('Requires run-client ADB-disabled isolated instance')
 const capabilities=await api('/capabilities');if(!capabilities.bodyTypes.includes('URLENCODED'))throw Error('Current artifact lacks new HTTP functionality')
 env=await api('/envs','POST',{name:'night-ui-env-'+suffix});await api(`/envs/${env.id}/settings`,'PUT',{sign:{enabled:false,appId:'',appSecret:'',nonce:'',mgId:''},vars:{base:[{name:'endpoint',value:MOCK}],header:[],body:[]}})
 dataGroup=await api('/case-groups','POST',{name:'night-ui-data-'+suffix,envId:env.id})
 for(const kind of ['A','B'])dataCases.push(await api('/cases','POST',spec('night-data-'+kind+'-'+suffix,prefix+'/data-'+kind,'{"id":"{{data.id}}","label":"{{data.label}}","kind":"'+kind+'"}')))
 browser=await cdp((await(await fetch(CDP+'/json/version')).json()).webSocketDebuggerUrl);targetId=(await browser.command('Target.createTarget',{url:'about:blank'})).targetId
 const target=await until(()=>fetch(CDP+'/json/list').then(r=>r.json()),targets=>targets.some(t=>t.id===targetId));page=await cdp(target.find(t=>t.id===targetId).webSocketDebuggerUrl);await page.command('Page.enable');await page.command('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
 await section('cURL导入草稿与实际发送',async()=>{
  await navigateApi();await openQuick();await clickText('导入 cURL');const path=prefix+'/curl',body='{"id":'+LONG+',"text":"中文 + &"}',command="curl -X POST '"+MOCK+path+"?dup=one&dup=two' -H 'Content-Type: application/json' -H 'X-Night: "+suffix+"' --data-raw '"+body+"'"
  const beforeCases=(await api('/cases')).length,beforeExec=await recentId();await value('#import-CURL',command);await clickText('预览导入');await wait(`document.body.innerText.includes('cURL 导入')`);await clickText('载入草稿');await wait(`!document.querySelector('#import-CURL')`)
  check('cURL预览/载入未新建用例或执行记录',(await api('/cases')).length===beforeCases&&await recentId()===beforeExec)
  check('cURL导入阶段未访问业务端点',(await wire(path)).length===0)
  check('cURL正文38位数字原文保留',await ev(`document.querySelector('#quick-body').value===${quote(body)}`))
  check('导入cURL后仍须明确选择环境',await ev(`document.querySelector('[data-testid=quick-send]').disabled`))
  await value('[data-testid=quick-env]',env.id);await value('[aria-label="请求名称"]','night-curl-'+suffix);const result=await sendQuick(path),received=(await wire(path)).at(-1)
  check('cURL发送实际方法/正文/重复query一致',received?.method==='POST'&&received.body===body&&JSON.stringify(received.query)===JSON.stringify([['dup','one'],['dup','two']]),received)
  check('cURL自定义头真正发送',received.headers.some(([k,v])=>k.toLowerCase()==='x-night'&&v===suffix))
  check('cURL历史快照与wire及环境一致',result.verdict==='PASS'&&result.reqBody===received.body&&JSON.parse(result.metadata).envId===env.id,result)
  await screenshot('night-curl')
 })
 await section('URL编码表单预览与发送',async()=>{
  await navigateApi();await openQuick();await value('[data-testid=quick-env]',env.id);await value('[aria-label="请求名称"]','night-form-'+suffix);await value('[aria-label="请求方法"]','POST');const path=prefix+'/form';await value('[aria-label="请求地址"]',MOCK+path);await value('[aria-label="请求体类型"]','URLENCODED')
  const fields=[['tag','a b+&中文'],['tag',''],['equals','x=y%z']]
  for(let i=0;i<fields.length;i++){await clickText('添加字段');await value(`[aria-label="表单名 ${i+1}"]`,fields[i][0]);await value(`[aria-label="表单值 ${i+1}"]`,fields[i][1])}
  const encoded=new URLSearchParams(fields).toString(),before=await recentId();await clickText('预览');await wait(`document.body.innerText.includes('预览校验通过')`)
  check('表单预览显示最终编码正文',await ev(`document.body.textContent.includes(${quote(encoded)})`))
  check('表单预览不发送也不留执行记录',(await wire(path)).length===0&&await recentId()===before)
  const result=await sendQuick(path),received=(await wire(path)).at(-1)
  check('表单重复键/空值/特殊字符真实wire精确',received.body===encoded&&JSON.stringify([...new URLSearchParams(received.body)])===JSON.stringify(fields),received)
  check('表单Content-Type正确且快照一致',received.headers.some(([k,v])=>k.toLowerCase()==='content-type'&&v.startsWith('application/x-www-form-urlencoded'))&&result.reqBody===encoded&&result.verdict==='PASS')
 })
 await section('Postman集合与环境追加导入',async()=>{
  await navigateApi();await clickText('导入 Postman / OpenAPI');const path=prefix+'/postman',requestName='night-postman-'+suffix
  const collection={info:{name:'night-collection-'+suffix,schema:'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'},variable:[{key:'endpoint',value:MOCK},{key:'identifier',value:'collection-original'}],item:[{name:requestName,request:{method:'POST',url:'{{endpoint}}'+path,header:[{key:'Content-Type',value:'application/json'},{key:'X-Imported',value:'{{marker}}'}],body:{mode:'raw',raw:'{"id":"{{identifier}}"}',options:{raw:{language:'json'}}},auth:{type:'noauth'}}}]}
  collection.item[0].request.body.raw='{"id":"{{identifier}}","rawLong":'+LONG+',"padding":"'+'p'.repeat(1800)+'"}'
  collection.item=Array.from({length:100},(_,i)=>({...collection.item[0],name:i?requestName+'-'+i:requestName}))
  const environment={name:'night-import-env-'+suffix,values:[{key:'identifier',value:LONG,enabled:true},{key:'marker',value:'import-'+suffix,enabled:true}]}
  const beforeCases=(await api('/cases')).length,beforeEnvs=(await api('/envs')).length,oldVars=JSON.stringify(await api('/vars?env='+env.id)),beforeExec=await recentId()
  const collectionText=JSON.stringify(collection);check('集合文件覆盖约218KB与100接口规模',Buffer.byteLength(collectionText)>200000&&Buffer.byteLength(collectionText)<300000&&collection.item.length===100)
  await fileValue('#import-COLLECTION-file',collectionText,'collection-'+suffix+'.json');await ev(`document.querySelector('#import-environment-file').closest('details').open=true`);await fileValue('#import-environment-file','\uFEFF'+JSON.stringify(environment),'environment-'+suffix+'.json');await value('[aria-label="导入目标环境"]',env.id);await clickText('预览导入');await wait(`document.body.innerText.includes(${quote(requestName)})`)
  check('文件导入全文不进入高亮编辑器',await ev(`!document.querySelector('#import-COLLECTION')&&!document.querySelector('#import-environment')`))
  check('Postman预览不落库不发送',(await api('/cases')).length===beforeCases&&(await api('/envs')).length===beforeEnvs&&await recentId()===beforeExec&&(await wire(path)).length===0)
  await clickText('确认追加导入');await wait(`!document.querySelector('#import-COLLECTION-file')&&!document.querySelector('[data-slot=dialog-overlay]')`);postmanCase=(await api('/cases')).find(c=>c.name===requestName);const group=(await api('/case-groups')).find(g=>g.id===postmanCase?.groupId),importEnv=(await api('/envs')).find(e=>e.id===group?.envId)
  check('Postman文件真实追加100用例/分组/新环境',!!postmanCase&&!!group&&!!importEnv&&(await api('/cases')).length===beforeCases+100&&(await api('/envs')).length===beforeEnvs+1&&importEnv.id!==env.id)
  check('文件RAW原文与38位数字保存不变',postmanCase.body===collection.item[0].request.body.raw)
  const vars=await api('/vars?env='+importEnv.id);check('Postman环境覆盖集合变量并按引用分组',vars.body.some(v=>v.name==='identifier'&&v.value===LONG)&&vars.base.some(v=>v.name==='endpoint'&&v.value===MOCK)&&vars.header.some(v=>v.name==='marker'&&v.value==='import-'+suffix),vars)
  check('导入未覆盖现有环境',JSON.stringify(await api('/vars?env='+env.id))===oldVars)
  await openQuick();await value('[aria-label="从已有用例载入"]',postmanCase.id);await wait(`document.querySelector('[data-testid=quick-env]').value===${quote(String(importEnv.id))}`);const result=await sendQuick(path),received=(await wire(path)).at(-1)
  check('迁移请求实际使用导入环境变量发送',received.json?.id===LONG&&received.headers.some(([k,v])=>k.toLowerCase()==='x-imported'&&v==='import-'+suffix)&&result.verdict==='PASS'&&JSON.parse(result.metadata).envId===importEnv.id,received)
 })
 await section('OpenAPI模板导入与严格缺参阻止发送',async()=>{
  await navigateApi();await clickText('导入 Postman / OpenAPI');await value('[aria-label="导入格式"]','OPENAPI');const path=prefix+'/openapi',requestName='night-openapi-'+suffix
  const document={openapi:'3.1.0',info:{title:'night-openapi-doc-'+suffix,version:'1'},servers:[{url:MOCK}],paths:{[path+'/{id}']:{get:{summary:requestName,parameters:[{name:'id',in:'path',required:true,schema:{type:'string'}}],responses:{200:{description:'ok'}}}}}}
  const before=(await api('/cases')).length;await value('#import-COLLECTION',JSON.stringify(document));await value('[aria-label="导入目标环境"]',env.id);await clickText('预览导入');await wait(`document.body.innerText.includes(${quote(requestName)})`)
  await wait(`document.body.innerText.includes('openapi.param.id')`);check('OpenAPI预览明确提示未填必需参数',true)
  check('OpenAPI预览没有发送或落库',(await api('/cases')).length===before&&(await journal()).every(e=>!e.path.startsWith(path)))
  await clickText('确认追加导入');await wait(`!document.querySelector('#import-COLLECTION')&&!document.querySelector('[data-slot=dialog-overlay]')`);openapiCase=(await api('/cases')).find(c=>c.name===requestName);check('OpenAPI真实保存strict模板与路径占位符',openapiCase?.options?.strictVariables===true&&openapiCase.url.includes('{{openapi.param.id}}'))
  await openQuick();await value('[aria-label="从已有用例载入"]',openapiCase.id);await clickText('预览');await wait(`document.body.innerText.includes('请求配置不完整')`);check('缺参预览不通过',await ev(`document.body.innerText.includes('openapi.param.id')`))
  const result=await sendQuick(path);check('严格缺参发送留下ERROR而非访问端点',result.verdict==='ERROR'&&(await journal()).every(e=>!e.path.startsWith(path)),result)
 })
 await section('CSV两行乘两用例数据回归',async()=>{
  await navigateApi();await click('[role=button][aria-label="用例管理"]');for(const c of dataCases)await click(`[data-testid="case-pick-${c.id}"]`);await closeDialog();await wait(`!document.querySelector('[role=dialog]')`);await clickText('数据驱动');const batchName='night-data-batch-'+suffix
  await value('[aria-label="数据批次名称"]',batchName);await value('#data-content','id,label\n'+LONG+',中文 A\n002,second + &');await value('[aria-label="数据重复次数"]','1');await clickText('预览数据');await wait(`document.body.innerText.includes('2 行')&&document.body.innerText.includes('{{data.id}}')`)
  const before=(await journal()).filter(e=>e.path===prefix+'/data-A'||e.path===prefix+'/data-B').length;check('数据预览不执行业务请求',before===0)
  await clickText('开始数据回归');await wait(`!document.querySelector('#data-content')`);const found=await until(()=>api('/batches'),rows=>rows.some(b=>b.name===batchName));const batch=found.find(b=>b.name===batchName);const detail=await until(()=>api('/batches/'+batch.id),d=>d.batch.status!=='RUNNING',20000)
  check('CSV两行乘两用例产生4条真实执行',detail.execs.length===4&&detail.batch.totalExecs===4&&detail.execs.every(e=>e.verdict==='PASS'),detail.execs.map(e=>({verdict:e.verdict,reason:e.reason})))
  const rows=detail.execs.map(e=>({meta:JSON.parse(e.metadata),body:JSON.parse(e.reqBody),asserts:JSON.parse(e.assertsResult)}));check('数据行值/长ID/001类字符串按行正确',rows.filter(r=>r.meta.dataRowIndex===1).length===2&&rows.filter(r=>r.meta.dataRowIndex===2).length===2&&rows.every(r=>r.body.id===(r.meta.dataRowIndex===1?LONG:'002')&&r.meta.dataIteration===1))
  check('data变量用于断言期望且有实际值',rows.every(r=>r.asserts.length===1&&r.asserts[0].ok===true&&r.asserts[0].actual!==undefined),rows)
  const received=(await journal()).filter(e=>e.path===prefix+'/data-A'||e.path===prefix+'/data-B');check('四次wire与历史请求体逐条一致',received.length===4&&detail.execs.every((e,i)=>e.reqBody===received[i].body))
  const vars=await api('/vars?env='+env.id);check('数据驱动没有写回环境变量',vars.body.length===0&&vars.base.length===1&&vars.base[0].name==='endpoint')
  await screenshot('night-data')
 })

 await section('历史比较选择与迟到恢复隔离',async()=>{
  const path=prefix+'/history',executions=[],bodies=[]
  for(const letter of ['A','B','C']){
   const body='{"id":'+LONG+',"value":"'+letter+'"}';bodies.push(body)
   const template={name:'night-history-'+letter+'-'+suffix,method:'POST',url:MOCK+path,headers:'{"Content-Type":"application/json"}',body,note:'isolated historical browser fixture',expectStatus:'200',expectCode:'0',timeoutMs:8000,insecure:false,enabled:true,groupId:null,asserts:null,options:{bodyType:'JSON',params:[],auth:{type:'BEARER',['to'+'ken']:'SUDPANEL_FAKE_TEST_CREDENTIAL_'+suffix},extractors:[],strictVariables:true}}
   executions.push(await api('/send','POST',{spec:template,envId:env.id}))
  }
  const wireCount=(await wire(path)).length,beforeExec=await recentId()
  check('历史比较夹具是真实发送生成的三条记录',executions.every(e=>e.verdict==='PASS')&&wireCount===3)
  await navigateApi();await clickText('历史检索与对比');await value('[aria-label="历史关键字"]',path);await value('[aria-label="历史方法"]','POST');await clickText('查询历史')
  const historyIds=executions.map(e=>e.id)
  await until(()=>ev(`[...document.querySelectorAll('[aria-label^="选择比较记录 "]')].map(e=>Number(e.getAttribute('aria-label').replace('选择比较记录 ','')))`),ids=>ids.length===3&&ids.every(id=>historyIds.includes(id)))
  check('历史URL与方法筛选显示目标记录',await ev(`document.querySelectorAll('[aria-label^="选择比较记录 "]').length===3`))
  await click(`[aria-label="选择比较记录 ${executions[0].id}"]`);await click(`[aria-label="选择比较记录 ${executions[1].id}"]`);await clickText('比较已选两条');await wait(`document.body.innerText.includes('/received/value')`)
  check('真实历史响应差异在界面可见',await ev(`document.body.innerText.includes('BODY')&&document.body.innerText.includes('/received/value')`))
  await wait(`![...document.querySelectorAll('[role=dialog] button')].find(e=>e.textContent.trim()==='比较已选两条').disabled&&document.querySelector(${quote('[aria-label="选择比较记录 '+executions[0].id+'"]')}).checked`)
  await click(`[aria-label="选择比较记录 ${executions[0].id}"]`);await wait(`!document.querySelector(${quote('[aria-label="选择比较记录 '+executions[0].id+'"]')}).checked&&!document.body.innerText.includes('/received/value')`);await click(`[aria-label="选择比较记录 ${executions[2].id}"]`)
  check('更改比较勾选会清掉旧比较结果',await ev(`!document.body.innerText.includes('/received/value')&&document.querySelector(${quote('[aria-label="选择比较记录 '+executions[2].id+'"]')}).checked`))
  const paused=[],removeListener=page.on('Fetch.requestPaused',event=>paused.push(event))
  const rowAction=async(id,label)=>{const selector=await ev(`(()=>{const row=document.querySelector(${quote('[aria-label="选择比较记录 '+id+'"]')}).closest('tr');const button=[...row.querySelectorAll('button')].find(b=>b.textContent.trim()===${quote(label)});button.setAttribute('data-night-history-action',${quote(String(id)+'-'+label)});return '[data-night-history-action='+JSON.stringify(${quote(String(id)+'-'+label)})+']'})()`);await click(selector)}
  try{
   // 只暂缓真实服务器响应，最终原样放行；没有替换API内容或模拟结果。
   await page.command('Fetch.enable',{patterns:[{urlPattern:BASE+'/history/'+executions[0].id+'/draft',requestStage:'Response'}]})
   await rowAction(executions[0].id,'载入草稿');await until(async()=>paused,v=>v.length>0)
   await clickText('查询历史');await wait(`!([...document.querySelectorAll('[role=dialog] button')].find(b=>b.textContent.trim()==='查询历史')?.disabled)`)
   await page.command('Fetch.continueResponse',{requestId:paused[0].requestId});paused.shift();await pause(400)
   check('查询新历史后迟到恢复响应不能打开旧草稿',await ev(`!document.querySelector('[data-testid=quick-env]')&&document.body.innerText.includes('接口历史检索与证据')`))
  }finally{
   for(const event of paused)try{await page.command('Fetch.continueResponse',{requestId:event.requestId})}catch{}
   await page.command('Fetch.disable');removeListener()
  }
  await rowAction(executions[1].id,'载入草稿');await wait(`document.querySelector('[data-testid=quick-env]')&&document.querySelector('[aria-label="请求名称"]').value.includes(${quote('历史 #'+executions[1].id)})`)
  check('历史恢复正文长数字与原环境精确保留',await ev(`document.querySelector('#quick-body').value===${quote(bodies[1])}&&document.querySelector('[data-testid=quick-env]').value===${quote(String(env.id))}`))
  await clickText('鉴权');check('历史恢复不会继承旧Bearer凭据',await ev(`document.querySelector('[aria-label="鉴权方式"]').value==='NONE'`));await clickText('请求头')
  check('历史恢复移除旧Authorization头',await ev(`!Object.keys(JSON.parse(document.querySelector('#quick-headers').value)).some(k=>k.toLowerCase()==='authorization')`))
  check('历史比较与恢复均未自动重发',(await wire(path)).length===wireCount&&await recentId()===beforeExec)
  await screenshot('night-history')
 })

 await section('历史分页保持已应用筛选',async()=>{
  const pathA=prefix+'/page-A',pathB=prefix+'/page-B',create=async(letter,path)=>api('/send','POST',{spec:{name:'night-page-'+letter+'-'+suffix,method:'POST',url:MOCK+path,headers:'{"Content-Type":"application/json"}',body:'{"kind":"'+letter+'"}',note:'真实历史游标分页夹具',expectStatus:'200',expectCode:'0',timeoutMs:8000,insecure:false,enabled:true,groupId:null,asserts:null,options:{bodyType:'JSON',params:[],auth:{type:'NONE'},extractors:[],strictVariables:true}},envId:env.id})
  // B早于A的游标，确保旧实现会把B追加进A第一页，而非仅返回空页。
  const executionB=await create('B',pathB),executionsA=[]
  for(let i=0;i<51;i++)executionsA.push(await create('A',pathA))
  check('历史分页夹具真实生成51条A与1条较早B',executionB.verdict==='PASS'&&executionsA.every(e=>e.verdict==='PASS')&&(await wire(pathA)).length===51&&(await wire(pathB)).length===1)
  const idsA=executionsA.map(e=>e.id),idsInTable=async()=>ev(`[...document.querySelectorAll('[aria-label^="选择比较记录 "]')].map(e=>Number(e.getAttribute('aria-label').replace('选择比较记录 ','')))`)
  await navigateApi();await clickText('历史检索与对比');await value('[aria-label="历史关键字"]',pathA);await clickText('查询历史');await until(idsInTable,ids=>ids.length===50&&ids.every(id=>idsA.includes(id)))
  check('历史A首查50条并有后续页',await ev(`[...document.querySelectorAll('[role=dialog] button')].some(e=>e.textContent.trim()==='加载更早记录')`))
  await value('[aria-label="历史关键字"]',pathB);await clickText('加载更早记录');const allA=await until(idsInTable,ids=>ids.length===51)
  check('编辑B未查询时加载更多仍只追加A',allA.every(id=>idsA.includes(id))&&!allA.includes(executionB.id),allA)
  await clickText('查询历史');const onlyB=await until(idsInTable,ids=>ids.length===1&&ids[0]===executionB.id)
  check('点击查询后才应用B且清掉全部A',onlyB.length===1&&onlyB[0]===executionB.id)
  await screenshot('night-history-pagination')
 })

}catch(error){check('专项准备或清理阶段完整执行',false,String(error));console.error(error.stack)}
finally{
 if(targetId&&browser)try{await browser.command('Target.closeTarget',{targetId})}catch{}
 page?.close();browser?.close();const failed=checks.filter(c=>!c.passed).length,summary={total:checks.length,passed:checks.length-failed,failed,sections,checks,trueDeviceChecks:0,apiMocked:false,targetOwned:true}
 await writeFile(process.env.SP_UI_SUMMARY||join(OUTPUT,'night-ui-summary.json'),JSON.stringify(summary,null,2));console.log(`\n新增HTTP界面专项：${summary.passed}/${summary.total}，失败 ${failed}`);if(failed)process.exitCode=1
}
