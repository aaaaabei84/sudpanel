import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 设备列表全部由浏览器 mock 接管；不连接或操作真实设备。
const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve))
let next = 0, total = 0, failures = 0, scriptId
const pending = new Map()
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data)
  if (pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})
const send = (method, params) => new Promise((resolve, reject) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout: ${method}`)) }, 10000)
  pending.set(id, msg => { clearTimeout(timer); resolve(msg) })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const msg = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (msg.error || msg.result.exceptionDetails) throw Error(JSON.stringify(msg.error || msg.result.exceptionDetails))
  return msg.result.result.value
}
const waitFor = async expression => {
  for (let i = 0; i < 80; i++) { if (await ev(expression)) return; await sleep(50) }
  throw Error(`fixture not ready: ${expression}`)
}
const clickText = text => ev(`(()=>{const e=[...document.querySelectorAll('button,[role=option]')].find(x=>x.textContent.trim()===${JSON.stringify(text)});if(!e)throw Error('missing control');e.click()})()`)
const check = (name, ok, detail) => { total++; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail)}`) }
const state = () => ev(`(()=>{
  const label=[...document.querySelectorAll('div')].find(x=>x.textContent.trim()==='当前选中设备');
  return {selected:label?.previousElementSibling?.textContent.trim(),selector:document.querySelector('[role=combobox]')?.textContent.trim(),oldVisible:document.body.textContent.includes('MOCK-DEVICE-A')};
})()`)
const enter = async () => {
  await send('Page.navigate', { url: APP })
  await waitFor(`window.deviceFixture?.done>=1 && document.body.textContent.includes('当前选中设备') && document.body.textContent.includes('MOCK-DEVICE-A')`)
}
const refresh = async (serials, fail = false, held = false) => {
  const before = await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.queue.push({serials:${JSON.stringify(serials)},fail:${fail},held:${held}})`)
  await clickText('刷新设备')
  await waitFor(`window.deviceFixture.count===${before + 1}`)
  if (!held) { await waitFor(`window.deviceFixture.done>=${before + 1}`); await sleep(100) }
  return before + 1
}
const release = async id => { await ev(`window.deviceFixture.release[${id}]()`); await sleep(150) }
try {
  await send('Page.enable', {})
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  const result = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    const original=window.fetch;
    window.deviceFixture={queue:[],count:0,done:0,release:{},operations:[],hidden:false,automatic:[],timerPeriods:[]};
    const originalInterval=window.setInterval,originalClearInterval=window.clearInterval;
    window.setInterval=(fn,ms,...args)=>{if(ms===5000){window.deviceFixture.timerPeriods.push(ms);window.deviceFixture.automatic.push(()=>fn(...args));return -100-window.deviceFixture.automatic.length}return originalInterval(fn,ms,...args)};
    window.clearInterval=id=>{if(id>=0)originalClearInterval(id)};
    Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>window.deviceFixture.hidden?'hidden':'visible'});
    window.fetch=async(input,init)=>{
      const path=new URL(typeof input==='string'?input:input.url,location.href).pathname;
      const f=window.deviceFixture,method=(init?.method||'GET').toUpperCase();
      if(path==='/api/devices'){
        const id=++f.count,item=f.queue.shift()||{serials:['MOCK-DEVICE-A','MOCK-DEVICE-B']};
        if(item.held)await new Promise(resolve=>f.release[id]=resolve);
        f.done=Math.max(f.done,id);
        if(item.fail)throw Error('MOCK_DEVICE_REFRESH_FAILED');
        return new Response(JSON.stringify(item.devices||item.serials.map(serial=>({serial,model:serial,status:'device'}))),{headers:{'Content-Type':'application/json'}});
      }
      // 即使断言失误点击到执行入口，也只记录，绝不放行设备操作。
      if(method!=='GET' && !path.startsWith('/api/apitest/')){
        f.operations.push({path,method});return new Response('{}',{status:409});
      }
      return original(input,init);
    }` })
  scriptId = result.result.identifier
  await enter()
  let s = await state()
  check('初始自动选中首个在线设备', s.selected === 'MOCK-DEVICE-A', s)
  await ev(`document.querySelector('[role=combobox]').click()`)
  await waitFor(`!!document.querySelector('[role=option]')`)
  await clickText('MOCK-DEVICE-B')
  await waitFor(`!document.querySelector('[role=option]')`)
  s = await state()
  check('允许手动选择第二台设备', s.selected === 'MOCK-DEVICE-B', s)
  await refresh(['MOCK-DEVICE-A', 'MOCK-DEVICE-B'])
  s = await state()
  check('刷新保留仍在线的手动选择', s.selected === 'MOCK-DEVICE-B', s)
  await refresh(['MOCK-DEVICE-C'])
  s = await state()
  check('选中设备离线时改选当前首台', s.selected === 'MOCK-DEVICE-C', s)
  await refresh([], true)
  s = await state()
  check('刷新失败清除旧选中设备', s.selected === '—', s)
  check('刷新失败选择器显示无在线设备', s.selector === '无在线设备', s)
  check('刷新失败移除旧设备标识', !s.oldVisible && !JSON.stringify(s).includes('MOCK-DEVICE-C'), s)
  check('查询失败额外显示诊断，区别成功查到0台', await ev(`!!document.querySelector('[data-testid=dashboard-device-error]')&&document.querySelector('[data-testid=dashboard-device-error]').textContent.includes('MOCK_DEVICE_REFRESH_FAILED')`))
  await refresh(['MOCK-DEVICE-A'])
  s = await state()
  check('失败后手动刷新可恢复选择', s.selected === 'MOCK-DEVICE-A', s)
  check('恢复后清除查询错误',await ev(`!document.querySelector('[data-testid=dashboard-device-error]')&&!document.querySelector('[data-testid=sidebar-device-error]')`))
  await refresh([])
  s = await state()
  check('成功返回空列表清除选择', s.selected === '—' && s.selector === '无在线设备', s)
  await refresh(['MOCK-DEVICE-A'])
  let old = await refresh(['MOCK-DEVICE-B'], false, true)
  await refresh(['MOCK-DEVICE-C'])
  await release(old)
  s = await state()
  check('旧成功响应不覆盖新设备选择', s.selected === 'MOCK-DEVICE-C', s)
  check('旧成功响应不覆盖新设备列表', !s.oldVisible && s.selector === 'MOCK-DEVICE-C', s)
  old = await refresh([], true, true)
  await refresh(['MOCK-DEVICE-A'])
  await release(old)
  s = await state()
  check('旧失败不清空新成功的列表与选择', s.selected === 'MOCK-DEVICE-A' && s.selector === 'MOCK-DEVICE-A' && s.oldVisible, s)
  old = await refresh(['MOCK-DEVICE-B'], false, true)
  await refresh([], true)
  await release(old)
  s = await state()
  check('旧成功不复活最新失败清除的设备', s.selected === '—' && s.selector === '无在线设备', s)
  check('设备自动刷新注册5秒周期',await ev('window.deviceFixture.timerPeriods.includes(5000)'))
  let before=await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.queue.push({serials:['MOCK-AUTO-A']});window.deviceFixture.automatic.at(-1)()`)
  await waitFor(`window.deviceFixture.count===${before+1}&&window.deviceFixture.done>=${before+1}`);await sleep(80)
  check('自动刷新可发现新设备', (await state()).selected==='MOCK-AUTO-A')
  before=await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.queue.push({serials:['MOCK-AUTO-B'],held:true});window.deviceFixture.automatic.at(-1)()`)
  await waitFor(`window.deviceFixture.count===${before+1}`)
  await ev(`window.deviceFixture.automatic.at(-1)();window.deviceFixture.automatic.at(-1)();window.dispatchEvent(new Event('focus'))`);await sleep(100)
  check('慢读取期间自动与focus刷新不叠加',await ev(`window.deviceFixture.count===${before+1}`))
  await release(before+1)
  before=await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.hidden=true;window.deviceFixture.queue.push({serials:['MOCK-VISIBLE']});window.deviceFixture.automatic.at(-1)()`);await sleep(100)
  check('页面隐藏时自动检查暂停',await ev(`window.deviceFixture.count===${before}`))
  await ev(`window.deviceFixture.hidden=false;document.dispatchEvent(new Event('visibilitychange'))`)
  await waitFor(`window.deviceFixture.done>=${before+1}`);await sleep(80)
  check('页面重新可见立即检查', (await state()).selected==='MOCK-VISIBLE')
  before=await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.queue.push({serials:['MOCK-FOCUS']});window.dispatchEvent(new Event('focus'))`)
  await waitFor(`window.deviceFixture.done>=${before+1}`);await sleep(80)
  check('窗口重新聚焦立即检查', (await state()).selected==='MOCK-FOCUS')
  before=await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.queue.push({devices:[{serial:'MOCK-UNAUTHORIZED',model:'未授权手机',status:'unauthorized'}]})`);await clickText('刷新设备')
  await waitFor(`window.deviceFixture.done>=${before+1}`);await sleep(80)
  check('未授权设备显示授权操作指引',await ev(`document.body.innerText.includes('有手机未授权')&&document.body.innerText.includes('允许 USB 调试')`))
  before=await ev('window.deviceFixture.count')
  await ev(`window.deviceFixture.queue.push({devices:[{serial:'MOCK-OFFLINE',model:'离线手机',status:'offline'}]})`);await clickText('刷新设备')
  await waitFor(`window.deviceFixture.done>=${before+1}`);await sleep(80)
  check('离线设备显示连接检查指引',await ev(`document.body.innerText.includes('有手机离线')&&document.body.innerText.includes('重新连接数据线')`))
  check('界面展示最近设备检查时间',await ev(`document.body.innerText.includes('最近检查')&&document.body.innerText.includes('每 5 秒检查')`))
  check('未发送设备执行操作', (await ev('window.deviceFixture.operations.length')) === 0)
} finally {
  if (scriptId) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId })
  ws.close()
}
console.log(`设备刷新回归：${total - failures}/${total}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
