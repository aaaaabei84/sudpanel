import { requiredUiUrl } from './isolated-ui.mjs'
// Isolated Schema/OpenAPI and statistics UI regression. All APIs are synthetic.
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const APP=requiredUiUrl('SP_APP_URL'), CDP=requiredUiUrl('SP_CDP')
for(const value of [APP,CDP]){const u=new URL(value);if(u.hostname!=='127.0.0.1'||u.protocol!=='http:'||u.port==='18790')throw Error('Isolated loopback only')}
const OUTPUT=process.env.SP_OUTPUT_DIR||join(tmpdir(),'schema-stats-ui-'+Date.now()),checks=[],networkApi=[]
const quote=JSON.stringify,pause=ms=>new Promise(r=>setTimeout(r,ms))
let browser,page,targetId,scriptId,removeNetworkListener
const cdp = async url => {
  const ws = new WebSocket(url), pending = new Map(), listeners = new Map(); let next = 0
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve); ws.addEventListener('error', reject) })
  ws.addEventListener('message', event => {
    const m = JSON.parse(event.data)
    if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) }
    else if (m.method) for (const listener of listeners.get(m.method) || []) listener(m.params)
  })
  return {
    close: () => ws.close(),
    on: (method, listener) => { const list = listeners.get(method) || []; list.push(listener); listeners.set(method, list); return () => listeners.set(method, list.filter(x => x !== listener)) },
    command: (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++next, timer = setTimeout(() => { pending.delete(id); reject(Error('CDP deadline: ' + method)) }, 20000)
      pending.set(id, m => { clearTimeout(timer); if (m.error) reject(Error(JSON.stringify(m.error))); else resolve(m.result) })
      ws.send(JSON.stringify({ id, method, params }))
    })
  }
}
const ev = async expression => {
  const result = await page.command('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails))
  return result.result.value
}
// Predicates always return booleans; never return DOM nodes through CDP returnByValue.
const wait = async (expression, timeout = 20000) => {
  const until = Date.now() + timeout
  while (Date.now() < until) {
    try { if (await ev(`Boolean(${expression})`)) return }
    catch (error) { if (!/context.*destroyed|Cannot find context/i.test(String(error))) throw error }
    await pause(35)
  }
  throw Error('UI predicate deadline: ' + expression)
}
const settled = () => ev('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))')
const check = async (name, expression) => {
  const passed = typeof expression === 'boolean' ? expression : await ev(`Boolean(${expression})`)
  checks.push({ name, passed }); console.log(`${passed ? 'PASS' : 'FAIL'} ${name}`)
}
const click = async selector => {
  await wait(`!!document.querySelector(${quote(selector)})&&!document.querySelector(${quote(selector)}).matches(':disabled')`)
  const point = await ev(`(async()=>{const el=document.querySelector(${quote(selector)});el.scrollIntoView({block:'center',behavior:'instant'});for(let i=0;i<4;i++)await new Promise(requestAnimationFrame);const r=el.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,hit=document.elementFromPoint(x,y);return {x,y,hit:hit===el||el.contains(hit)}})()`)
  if (!point.hit) throw Error('Control obscured: ' + selector)
  for (const type of ['mousePressed', 'mouseReleased']) await page.command('Input.dispatchMouseEvent', { type, button: 'left', clickCount: 1, x: point.x, y: point.y })
}
const markText = async (label, scope = '[role=dialog]') => {
  await wait(`!![...document.querySelector(${quote(scope)})?.querySelectorAll('button')||[]].find(b=>b.textContent.trim()===${quote(label)})`)
  await ev(`(()=>{const b=[...document.querySelector(${quote(scope)}).querySelectorAll('button')].find(b=>b.textContent.trim()===${quote(label)});b.setAttribute('data-import-file-click','active');return true})()`)
  return '[data-import-file-click=active]'
}
const clickText = async (label, scope) => { const s = await markText(label, scope); await click(s); await ev(`(()=>{document.querySelector(${quote(s)})?.removeAttribute('data-import-file-click');return true})()`) }
const change = async (selector, value) => {
  await wait(`!!document.querySelector(${quote(selector)})`)
  await ev(`(()=>{const el=document.querySelector(${quote(selector)}),p=el.tagName==='SELECT'?HTMLSelectElement.prototype:el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(el,${quote(value)});el.dispatchEvent(new Event(el.tagName==='SELECT'?'change':'input',{bubbles:true}));return true})()`)
}

const injection=`(()=>{
 const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}}),real=window.fetch;
 const f=window.schemaStatsFixture={schemaCalls:[],histories:[],unexpected:[],holdSchema:false,gates:[],workspaceWrites:0};
 const metrics={sampleCount:2,totalCount:3,passCount:1,failCount:1,errorCount:1,skipCount:0,minMs:10,p50Ms:10,p90Ms:20,p95Ms:20,p99Ms:20,maxMs:20};
 const group=(key,name)=>({...metrics,key,name});
 const batch={id:41,name:'fixture batch',mode:'ONCE',status:'DONE',caseIds:[7],intervalSec:0,repeatCount:1,roundNo:1,totalExecs:3,passCount:1,failCount:1,errorCount:1,skipCount:0};
 window.fetch=async(input,init)=>{const u=new URL(typeof input==='string'?input:input.url,location.href),p=u.pathname,m=(init?.method||'GET').toUpperCase();if(!p.startsWith('/api/'))return real(input,init);
  const b=init?.body?JSON.parse(init.body):null;
  if(p.startsWith('/api/apitest/workspaces/')){if(m==='PUT'){f.workspaceWrites++;return reply({id:p.split('/').pop(),revision:f.workspaceWrites,content:b.content,updatedAt:'fixture'})}return reply({id:p.split('/').pop(),revision:0,content:null,updatedAt:null})}
  if(p==='/api/apitest/schema/openapi'&&m==='POST'){f.schemaCalls.push(b);if(f.holdSchema)await new Promise(r=>f.gates.push(r));return reply({schema:'{\"type\":\"object\",\"properties\":{\"id\":{\"const\":12345678901234567890123456789012345678}}}',status:b.status,mediaType:'application/json',warnings:['fixture extraction only']})}
  if(m!=='GET'){f.unexpected.push({p,m});return reply({message:'Unconfigured mutation forbidden'},409)}
  if(p==='/api/meta')return reply({name:'Schema stats fixture',version:'fixture'});
  if(p==='/api/devices'||p==='/api/apitest/cases'||p==='/api/apitest/case-groups'||p==='/api/apitest/scenarios'||p==='/api/apitest/codes'||p==='/api/apitest/batches/active')return reply([]);
  if(p==='/api/apitest/envs')return reply([{id:1,name:'fixture environment',isDefault:true}]);
  if(p==='/api/apitest/sign')return reply({enabled:false,hasSecret:false});
  if(p==='/api/apitest/vars')return reply({base:[],header:[],body:[]});
  if(p==='/api/apitest/capabilities')return reply({methods:['GET','POST'],systemVariables:[]});
  if(p==='/api/apitest/batches')return reply([batch]);
  if(p==='/api/apitest/batches/41/progress')return reply({batch,running:false,current:null});
  if(p==='/api/apitest/batches/41/executions')return reply({items:[],nextAfterId:0,hasMore:false});
  if(p==='/api/apitest/batches/41')return reply({batch,execs:[]});
  if(p==='/api/apitest/batches/41/stats')return reply({...metrics,byCase:[group('7','fixture case')],byEnv:[group('2','fixture environment')],byDataRow:[group('3:4','fixture row')],trend:[group('2026-10-15T12:00:00Z','fixture minute')],reasons:[{reason:'exact fixture failure',count:1}],httpCodes:[{reason:'409',count:1}],businessCodes:[{reason:'7',count:1}],assertionFailures:[{reason:'data.id [equals]',count:1}]});
  if(p==='/api/apitest/history'){f.histories.push(Object.fromEntries(u.searchParams));const before=u.searchParams.has('beforeId');return reply({items:[{id:before?499:501,caseId:7,caseName:'filtered fixture',method:'GET',url:'http://example.invalid/',verdict:'FAIL',httpStatus:409,elapsedMs:10,ts:'fixture',metadata:'{}'}],nextBeforeId:before?null:500})}
  if(p==='/api/apitest/report-policy')return reply({paths:[]});
  if(p==='/api/apitest/baselines')return reply([]);
  return reply([]);
 };
})()`
let navigation=0;
const navigate=async hash=>{await page.command('Page.navigate',{url:APP+'?schemaFixture='+(++navigation)+hash});await wait('!!window.schemaStatsFixture&&!!document.querySelector("[data-testid=quick-open]")')}
try{
 await mkdir(OUTPUT,{recursive:true});browser=await cdp((await(await fetch(CDP+'/json/version')).json()).webSocketDebuggerUrl);
 targetId=(await browser.command('Target.createTarget',{url:'about:blank'})).targetId;
 let target;for(let i=0;i<100&&!target;i++){target=(await(await fetch(CDP+'/json/list')).json()).find(t=>t.id===targetId);if(!target)await pause(30)}
 page=await cdp(target.webSocketDebuggerUrl);await page.command('Page.enable');page.on('Page.javascriptDialogOpening',event=>{if(event.type==='beforeunload')void page.command('Page.handleJavaScriptDialog',{accept:true});else throw Error('Unexpected fixture dialog')});await page.command('Runtime.enable');page.on('Runtime.exceptionThrown',event=>console.error(JSON.stringify(event.exceptionDetails)));await page.command('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
 scriptId=(await page.command('Page.addScriptToEvaluateOnNewDocument',{source:injection})).identifier;
 removeNetworkListener=page.on('Fetch.requestPaused',event=>{networkApi.push(event.request.url);void page.command('Fetch.failRequest',{requestId:event.requestId,errorReason:'BlockedByClient'}).catch(()=>{})});
 await page.command('Fetch.enable',{patterns:[{urlPattern:'*://*/api/*',requestStage:'Request'}]});
 await navigate('#/apitest');await click('[data-testid=quick-open] [data-slot=card]');
 await clickText('校验与提取');await clickText('添加断言');await change('[aria-label="断言条件 1"]','schema');
 await check('Schema条件使用多行原文输入',"!!document.querySelector('[aria-label=\"断言Schema 1\"]')");
 await change('[aria-label="断言Schema 1"]','{"const":12345678901234567890123456789012345678}');
 await check('Schema编辑保留38位数字',`document.querySelector('[aria-label="断言Schema 1"]').value.includes('12345678901234567890123456789012345678')`);
 await change('[aria-label="断言来源 1"]','HEADER');
 await check('改为响应头后不保留非法Schema条件',`document.querySelector('[aria-label="断言条件 1"]').value==='equals'&&![...document.querySelector('[aria-label="断言条件 1"]').options].some(o=>o.value==='schema')`);
 await ev(`(()=>{[...document.querySelectorAll('summary')].find(s=>s.textContent==='从 OpenAPI 提取响应 Schema').parentElement.open=true;return true})()`);
 const doc='{"openapi":"3.1.0","paths":{},"example":12345678901234567890123456789012345678}';
 await change('[aria-label="OpenAPI Schema来源内容"]',doc);await change('[aria-label="Schema来源路径"]','/round');await clickText('提取响应 Schema');
 await wait('!!document.querySelector("[aria-label=提取的响应Schema]")');
 await check('提取仅发送本机解析请求且原文未重建',`window.schemaStatsFixture.schemaCalls.length===1&&window.schemaStatsFixture.schemaCalls[0].document===${quote(doc)}&&window.schemaStatsFixture.unexpected.length===0`);
 await clickText('添加到断言（仍需保存用例）');
 await check('提取Schema仅新增草稿断言',`document.querySelector('[aria-label="断言条件 2"]').value==='schema'&&document.querySelector('[aria-label="断言Schema 2"]').value.includes('12345678901234567890123456789012345678')&&window.schemaStatsFixture.unexpected.length===0`);
 await ev('window.schemaStatsFixture.holdSchema=true');await clickText('提取响应 Schema');await wait('window.schemaStatsFixture.gates.length===1');
 await change('[aria-label="Schema来源路径"]','/new');await ev('window.schemaStatsFixture.gates[0]()');await settled();
 await check('修改来源后迟到提取不能回填',`!document.querySelector('[aria-label="提取的响应Schema"]')`);
 await wait('window.schemaStatsFixture.workspaceWrites>0');await settled();
 await navigate('#/apitest?batchId=41');await clickText('历史检索与对比','body');await wait('document.body.innerText.includes("当前批次 #41")');await clickText('查看耗时与失败分布');
 await wait('!!document.querySelector("[aria-label=批次统计]")');
 await clickText('fixture case · 查看记录');await wait(`window.schemaStatsFixture.histories.some(q=>q.statsDimension==='CASE')`);
 await check('用例分组跳转限定当前批次和精确ID',`(()=>{const q=window.schemaStatsFixture.histories.at(-1);return q.batchId==='41'&&q.statsDimension==='CASE'&&q.statsKey==='7'})()`);
 await change('[aria-label="历史关键字"]','未应用输入');await clickText('加载更早记录');await wait(`window.schemaStatsFixture.histories.at(-1).beforeId==='500'`);
 await check('统计分页保留已应用维度并忽略未应用输入',`(()=>{const q=window.schemaStatsFixture.histories.at(-1);return q.statsDimension==='CASE'&&q.statsKey==='7'&&!q.q&&q.batchId==='41'})()`);
 for(const [title,label,dimension,key] of [['按环境','fixture environment','ENV','2'],['按数据行与轮次','fixture row','DATA_ROW','3:4'],['完整分钟趋势','fixture minute','MINUTE','2026-10-15T12:00:00Z']]){
  await ev(`(()=>{[...document.querySelectorAll('summary')].find(s=>s.textContent.startsWith(${quote(title)})).parentElement.open=true;return true})()`);
  await clickText(label+' · 查看记录');await wait(`window.schemaStatsFixture.histories.at(-1).statsDimension===${quote(dimension)}`);
  await check(title+'精确跳转',`window.schemaStatsFixture.histories.at(-1).statsKey===${quote(key)}`);
 }
 for(const [title,dimension,key] of [['失败原因 Top 10','REASON','exact fixture failure'],['失败记录 HTTP 码 Top 10','HTTP_CODE','409'],['失败记录业务码 Top 10','BUSINESS_CODE','7'],['断言失败路径 Top 10','ASSERTION','data.id [equals]']]){
  await ev(`(()=>{[...document.querySelectorAll('[aria-label="批次统计"] section')].find(s=>s.querySelector('p')?.textContent===${quote(title)}).setAttribute('data-stat-section','active');return true})()`);
  await clickText('查看记录','[data-stat-section=active]');await ev(`document.querySelector('[data-stat-section=active]').removeAttribute('data-stat-section')`);
  await wait(`window.schemaStatsFixture.histories.at(-1).statsDimension===${quote(dimension)}`);await check(title+'精确跳转',`window.schemaStatsFixture.histories.at(-1).statsKey===${quote(key)}`);
 }
 await clickText('清除统计筛选');await wait('!window.schemaStatsFixture.histories.at(-1).statsDimension');
 await check('清除统计筛选后不继续携带旧维度',`!window.schemaStatsFixture.histories.at(-1).statsKey`);
 await page.command('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:false});await settled();
 await wait(`(()=>{const r=document.querySelector('[role=dialog]').getBoundingClientRect();return r.left>=8&&r.right<=innerWidth-8})()`,2000);
 await check('窄屏弹窗保留视口边距',`(()=>{const r=document.querySelector('[role=dialog]').getBoundingClientRect();return r.left>=8&&r.right<=innerWidth-8})()`);
 await check('全程无真实API或未配置写入',`window.schemaStatsFixture.unexpected.length===0`);await check('CDP未拦截到漏网API',networkApi.length===0);
}catch(error){checks.push({name:'完整流程',passed:false,error:String(error.stack||error)});console.error(error.stack||error);try{console.error(await ev('JSON.stringify({body:document.body.innerText,fixture:!!window.schemaStatsFixture})'))}catch{}}finally{
 if(page){try{if(scriptId)await page.command('Page.removeScriptToEvaluateOnNewDocument',{identifier:scriptId});await page.command('Fetch.disable')}catch{}}
 removeNetworkListener?.();if(browser&&targetId)try{await browser.command('Target.closeTarget',{targetId})}catch{}page?.close();browser?.close();
 const summary={total:checks.length,passed:checks.filter(c=>c.passed).length,failed:checks.filter(c=>!c.passed).length,checks,networkApi};await mkdir(OUTPUT,{recursive:true});await writeFile(join(OUTPUT,'schema-stats-ui-summary.json'),JSON.stringify(summary,null,2));console.log(JSON.stringify(summary));if(summary.failed)process.exitCode=1;
}
