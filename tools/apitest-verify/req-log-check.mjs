import fs from 'node:fs/promises'
import path from 'node:path'
import http from 'node:http'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
const root=path.dirname(fileURLToPath(import.meta.url)),front=path.resolve(root,'../../frontend')
const ts=(await import(path.join(front,'node_modules/typescript/lib/typescript.js'))).default
const out=await fs.mkdtemp(path.join(os.tmpdir(),'sp-req-log.'))
for(const file of ['api','apitest-workspace','json-text','log-export','diagnostics']){
 const input=await fs.readFile(path.join(front,'src/lib',file+'.ts'),'utf8')
 const result=ts.transpileModule(input,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ES2022}}).outputText.replaceAll("'@/lib/apitest-workspace'","'./apitest-workspace.mjs'").replaceAll("'@/lib/json-text'","'./json-text.mjs'")
 await fs.writeFile(path.join(out,file+'.mjs'),result.replaceAll("'@/lib/diagnostics'","'./diagnostics.mjs'"))
}
globalThis.window=globalThis
const {req}=await import(path.join(out,'api.mjs')), {logExportText}=await import(path.join(out,'log-export.mjs'))
const checks=[],sockets=new Set();let posts=0
const check=(n,ok)=>{checks.push({name:n,passed:!!ok});console.log((ok?'PASS ':'FAIL ')+n)}
const rejects=async(work,predicate)=>{try{await work();return false}catch(e){return predicate(e)}}
const server=http.createServer((request,response)=>{
 const route=request.url
 if(request.method==='POST')posts++
 if(route==='/stalled-body'){response.writeHead(200,{'Content-Type':'application/json'});response.write('{');return}
 if(route==='/never')return
 if(route==='/empty'){response.writeHead(204);response.end();return}
 if(route==='/error'){response.writeHead(422,{'Content-Type':'application/json'});response.end('{"message":"参数不合法"}');return}
 if(route==='/html-error'){response.writeHead(502);response.end('<html>bad proxy</html>');return}
 if(route==='/delayed'){setTimeout(()=>{response.writeHead(200,{'Content-Type':'application/json'});response.end('{"ok":true}')},50);return}
 response.writeHead(200,{'Content-Type':'application/json'});response.end('{"ok":true}')
})
server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket))})
await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port
try{
 check('正常JSON响应', (await req(base)).ok===true)
 check('空204响应', await req(base+'/empty')===undefined)
 check('业务错误保留中文原因', await rejects(()=>req(base+'/error'),e=>e.message==='参数不合法'))
 check('代理非JSON错误保留HTTP状态',await rejects(()=>req(base+'/html-error'),e=>e.message==='HTTP 502'))
 const start=performance.now();check('超时限制等待而非无限挂起',await rejects(()=>req(base+'/never',undefined,{timeoutMs:40}),e=>e.message.includes('后台操作可能仍在进行'))&&performance.now()-start<1000)
 check('正文读取阶段也受截止时间保护',await rejects(()=>req(base+'/stalled-body',undefined,{timeoutMs:40}),e=>e.message.includes('等待响应超时')))
 const abort=new AbortController();setTimeout(()=>abort.abort(),20);check('调用者signal可以取消请求',await rejects(()=>req(base+'/never',undefined,{signal:abort.signal,timeoutMs:1000}),e=>e.name==='AbortError'))
 const aborted=new AbortController();aborted.abort();check('已取消signal立即失败',await rejects(()=>req(base,undefined,{signal:aborted.signal}),e=>e.name==='AbortError'))
 const initAbort=new AbortController();setTimeout(()=>initAbort.abort(),20);check('旧RequestInit.signal仍有效',await rejects(()=>req(base+'/never',{signal:initAbort.signal},{timeoutMs:1000}),e=>e.name==='AbortError'))
 check('超时不会自动重发启动POST',await rejects(()=>req(base+'/never',{method:'POST'},{timeoutMs:30}),e=>e.message.includes('超时'))&&posts===1)
 check('显式禁用截止时间兼容长等待',(await req(base+'/delayed',undefined,{timeoutMs:0})).ok===true)
 check('大于响应时间的截止时间正常完成',(await req(base+'/delayed',undefined,{timeoutMs:500})).ok===true)
 const lines=Array.from({length:1505},(_,i)=>'中文 '+i)
 check('导出全部原文含虚拟窗口以外的行',logExportText(lines)===lines.join('\n')+'\n')
 check('导出筛选遵循全部缓冲索引',logExportText(lines,[0,1201,1504])==='中文 0\n中文 1201\n中文 1504\n')
 check('空导出没有多余空行',logExportText([])===''&&logExportText(lines,[])==='')
 check('坏索引不会写入undefined',logExportText(lines,[-1,1505,0.5,0])==='中文 0\n')
 check('38位文本与特殊字符原样保留',logExportText(['12345678901234567890123456789012345678','x\t"值",🙂'])==='12345678901234567890123456789012345678\nx\t"值",🙂\n')
}finally{for(const socket of sockets)socket.destroy();await new Promise(r=>server.close(r));await fs.writeFile(path.join(out,'req-log-results.json'),JSON.stringify(checks,null,2));console.log(JSON.stringify({passed:checks.filter(c=>c.passed).length,total:checks.length}));if(checks.some(c=>!c.passed))process.exitCode=1}
