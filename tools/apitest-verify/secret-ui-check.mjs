import { requiredUiUrl } from './isolated-ui.mjs'
// Isolated Schema/OpenAPI and statistics UI regression. All APIs are synthetic.
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const APP=requiredUiUrl('SP_APP_URL'), CDP=requiredUiUrl('SP_CDP')
for(const value of [APP,CDP]){const u=new URL(value);if(u.hostname!=='127.0.0.1'||u.protocol!=='http:'||u.port==='18790')throw Error('Isolated loopback only')}
const OUTPUT=process.env.SP_OUTPUT_DIR||join(tmpdir(),'secret-ui-'+Date.now()),checks=[],networkApi=[]
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
const markText = async (label, scope = 'body') => {
  await wait(`!![...document.querySelector(${quote(scope)})?.querySelectorAll('button')||[]].find(b=>b.textContent.trim()===${quote(label)})`)
  await ev(`(()=>{const b=[...document.querySelector(${quote(scope)}).querySelectorAll('button')].find(b=>b.textContent.trim()===${quote(label)});b.setAttribute('data-import-file-click','active');return true})()`)
  return '[data-import-file-click=active]'
}
const clickText = async (label, scope) => { const s = await markText(label, scope); await click(s); await ev(`(()=>{document.querySelector(${quote(s)})?.removeAttribute('data-import-file-click');return true})()`) }
const change = async (selector, value) => {
  await wait(`!!document.querySelector(${quote(selector)})&&!document.querySelector(${quote(selector)}).matches(':disabled')`);
  await wait(`!!document.querySelector(${quote(selector)})`)
  await ev(`(()=>{const el=document.querySelector(${quote(selector)}),p=el.tagName==='SELECT'?HTMLSelectElement.prototype:el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(el,${quote(value)});el.dispatchEvent(new Event(el.tagName==='SELECT'?'change':'input',{bubbles:true}));return true})()`)
}

const injection=`(()=>{
 const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}}),real=window.fetch;
 const f=window.schemaStatsFixture={schemaCalls:[],histories:[],unexpected:[],holdSchema:false,gates:[],workspaceWrites:0,secrets:[],secretWrites:[],settings:[],vars:{base:[],header:[],body:[{name:'ordinary',value:'fictional-private-value-384',secret:true}]}};
 const metrics={sampleCount:2,totalCount:3,passCount:1,failCount:1,errorCount:1,skipCount:0,minMs:10,p50Ms:10,p90Ms:20,p95Ms:20,p99Ms:20,maxMs:20};
 const group=(key,name)=>({...metrics,key,name});
 const batch={id:41,name:'fixture batch',mode:'ONCE',status:'DONE',caseIds:[7],intervalSec:0,repeatCount:1,roundNo:1,totalExecs:3,passCount:1,failCount:1,errorCount:1,skipCount:0};
 window.fetch=async(input,init)=>{const u=new URL(typeof input==='string'?input:input.url,location.href),p=u.pathname,m=(init?.method||'GET').toUpperCase();if(!p.startsWith('/api/'))return real(input,init);
  const b=init?.body?JSON.parse(init.body):null;
  if(p.startsWith('/api/apitest/workspaces/')){if(m==='PUT'){f.workspaceWrites++;return reply({id:p.split('/').pop(),revision:f.workspaceWrites,content:b.content,updatedAt:'fixture'})}return reply({id:p.split('/').pop(),revision:0,content:null,updatedAt:null})}
  if(p==='/api/apitest/schema/openapi'&&m==='POST'){f.schemaCalls.push(b);if(f.holdSchema)await new Promise(r=>f.gates.push(r));return reply({schema:'{\"type\":\"object\",\"properties\":{\"id\":{\"const\":12345678901234567890123456789012345678}}}',status:b.status,mediaType:'application/json',warnings:['fixture extraction only']})}
  if(p==='/api/apitest/secrets'){if(m==='POST'){f.secretWrites.push(b);const row={id:'00000000-0000-0000-0000-000000000001',name:b.name,kind:b.kind,reference:'secret://00000000-0000-0000-0000-000000000001',enabled:true};f.secrets.push(row);return reply(row)}return reply(f.secrets)}
  if(p==='/api/apitest/secrets/00000000-0000-0000-0000-000000000001/enabled'){f.secrets[0].enabled=b.enabled;return reply({ok:true})}
  if(p==='/api/apitest/envs/1/settings'){f.settings.push(b);f.vars=b.vars;return reply({sign:{enabled:false,secretSaved:false},vars:f.vars})}
  if(p==='/api/apitest/history/501'){const raw={id:501,caseId:7,caseName:'fixture',method:'POST',url:'http://example.invalid/',httpStatus:200,verdict:'PASS',elapsedMs:10,reqBody:'{"ordinary":"fictional-private-value-384"}',respBody:'{"ordinary":"fictional-private-value-384"}',reqHeaders:'X-Value: fictional-private-value-384',respHeaders:'X-Value: fictional-private-value-384',assertsResult:'[]'};return reply({...raw,metadata:JSON.stringify({safeDisplay:{...raw,reqBody:'{"ordinary":"***"}',respBody:'{"ordinary":"***"}',reqHeaders:'X-Value: ***',respHeaders:'X-Value: ***',metadata:{}},secretMask:{id:'fixture',sealed:'fixture-not-actual-cipher'}})})}
  if(m!=='GET'){f.unexpected.push({p,m});return reply({message:'Unconfigured mutation forbidden'},409)}
  if(p==='/api/meta')return reply({name:'Schema stats fixture',version:'fixture'});
  if(p==='/api/devices'||p==='/api/apitest/cases'||p==='/api/apitest/case-groups'||p==='/api/apitest/scenarios'||p==='/api/apitest/codes'||p==='/api/apitest/batches/active')return reply([]);
  if(p==='/api/apitest/envs')return reply([{id:1,name:'fixture environment',isDefault:true}]);
  if(p==='/api/apitest/sign')return reply({enabled:false,hasSecret:false});
  if(p==='/api/apitest/vars')return reply(f.vars);
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
 await navigate('#/apitest');
 await ev(`(()=>{[...document.querySelectorAll('[data-slot=card]')].find(c=>c.innerText.includes('用例管理')).setAttribute('data-cases-open','yes');return true})()`);await click('[data-cases-open]');await wait(`document.body.innerText.includes('环境设置')`);await ev(`(()=>{[...document.querySelectorAll('[data-slot=card]')].find(c=>c.innerText.includes('环境设置')).setAttribute('data-env-open','yes');return true})()`);await click('[data-env-open]');
 await wait(`!!document.querySelector('[aria-label="敏感变量 1"]')`);
 await check('已有敏感标记在变量表保留',`document.querySelector('[aria-label="敏感变量 1"]').getAttribute('data-state')==='checked'`);
 await check('变量值默认使用密码输入',`[...document.querySelectorAll('[role=dialog] input')].some(i=>i.value==='fictional-private-value-384'&&i.type==='password')`);
 await click('[aria-label="显示敏感值"]');await check('显式查看只改变显示不改值',`[...document.querySelectorAll('[role=dialog] input')].some(i=>i.value==='fictional-private-value-384'&&i.type==='text')`);
 await click('[aria-label="隐藏敏感值"]');
 await click('[aria-label="敏感变量 1"]');await clickText('保存');await wait('window.schemaStatsFixture.settings.length===1');
 await check('显式取消敏感标记保存false',`window.schemaStatsFixture.settings[0].vars.body[0].secret===false`);
 await navigate('#/apitest');await ev(`(()=>{[...document.querySelectorAll('[data-slot=card]')].find(c=>c.innerText.includes('用例管理')).setAttribute('data-cases-open','yes');return true})()`);await click('[data-cases-open]');await wait(`document.body.innerText.includes('环境设置')`);await ev(`(()=>{[...document.querySelectorAll('[data-slot=card]')].find(c=>c.innerText.includes('环境设置')).setAttribute('data-env-open','yes');return true})()`);await click('[data-env-open]');await clickText('本机秘密引用');
 await change('[aria-label="秘密名称"]','fixture-vault');await change('[aria-label="新秘密值"]','fictional-new-vault-839');
 await check('新秘密值默认密码且不出现在正文',`document.querySelector('[aria-label="新秘密值"]').type==='password'&&!document.body.innerText.includes('fictional-new-vault-839')`);
 await clickText('创建引用并保存');await wait('window.schemaStatsFixture.secretWrites.length===1');await wait(`document.querySelector('[aria-label="新秘密值"]').value===''&&!document.querySelector('[aria-label="秘密名称"]').disabled`);
 await check('创建只把秘密发送给本机秘密入口',`window.schemaStatsFixture.secretWrites[0].kind==='VAULT'&&window.schemaStatsFixture.secretWrites[0].value==='fictional-new-vault-839'`);
 await check('创建后清空秘密输入且列表没有原值',`document.querySelector('[aria-label="新秘密值"]').value===''&&!document.body.innerText.includes('fictional-new-vault-839')`);
 await clickText('停用');await wait('window.schemaStatsFixture.secrets[0].enabled===false');await check('停用与启用在原引用上更新',`window.schemaStatsFixture.secretWrites.length===1`);await clickText('启用');await wait('window.schemaStatsFixture.secrets[0].enabled===true');
 await change('[aria-label="秘密来源"]','KEYCHAIN');await check('Keychain仅填写已有服务账号',`!!document.querySelector('[aria-label="Keychain服务"]')&&!!document.querySelector('[aria-label="Keychain账号"]')&&!document.querySelector('[aria-label="新秘密值"]')`);
 await change('[aria-label="秘密名称"]','unsaved-draft');
 await ev(`(()=>{const d=[...document.querySelectorAll('[role=dialog]')].at(-1);d.querySelector('[data-slot=dialog-close]').setAttribute('data-secret-close','yes');return true})()`);await click('[data-secret-close]');await check('关闭未保存秘密草稿先提示',`document.body.innerText.includes('新引用尚未保存')`);await clickText('放弃新引用');await wait('!document.querySelector("[aria-label=秘密名称]")');
 await navigate('#/apitest?batchId=41');await clickText('历史检索与对比','body');await clickText('详情');await wait(`document.body.innerText.includes('记录 #501')`);
 await check('历史详情默认遮罩已标记秘密',`!document.querySelector('[role=dialog]').innerText.includes('fictional-private-value-384')&&document.querySelector('[role=dialog]').innerText.includes('***')`);
 await ev(`(()=>{const l=[...document.querySelectorAll('label')].find(l=>l.innerText==='显示本机敏感原文');l.querySelector('input').setAttribute('data-reveal','yes');return true})()`);await click('[data-reveal]');await check('显式显示本机原始报文',`document.querySelector('[role=dialog]').innerText.includes('fictional-private-value-384')`);
 await clickText('详情');await wait(`!document.querySelector('[data-reveal]')?.checked`);await check('重读详情清除临时显示授权',`!document.querySelector('[role=dialog]').innerText.includes('fictional-private-value-384')`);
 await navigate('#/apitest');await click('[data-testid=quick-open] [data-slot=card]');await clickText('校验与提取');await clickText('添加提取');await click('[aria-label="敏感提取 1"]');await check('提取规则可独立标记敏感',`document.querySelector('[aria-label="敏感提取 1"]').getAttribute('data-state')==='checked'`);
 await check('全程无真实API或未配置写入',`window.schemaStatsFixture.unexpected.length===0`);await check('CDP未拦截到漏网API',networkApi.length===0);
}catch(error){checks.push({name:'完整流程',passed:false,error:String(error.stack||error)});console.error(error.stack||error);try{console.error(await ev('JSON.stringify({body:document.body.innerText,fixture:!!window.schemaStatsFixture})'))}catch{}}finally{
 if(page){try{if(scriptId)await page.command('Page.removeScriptToEvaluateOnNewDocument',{identifier:scriptId});await page.command('Fetch.disable')}catch{}}
 removeNetworkListener?.();if(browser&&targetId)try{await browser.command('Target.closeTarget',{targetId})}catch{}page?.close();browser?.close();
 const summary={total:checks.length,passed:checks.filter(c=>c.passed).length,failed:checks.filter(c=>!c.passed).length,checks,networkApi};await mkdir(OUTPUT,{recursive:true});await writeFile(join(OUTPUT,'secret-ui-summary.json'),JSON.stringify(summary,null,2));console.log(JSON.stringify(summary));if(summary.failed)process.exitCode=1;
}
