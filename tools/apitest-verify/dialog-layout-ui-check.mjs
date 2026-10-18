import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 真实页面几何回归；mock 设备与游戏，不启动测试、不改配置、不操作手机。
const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve))
let next = 0, total = 0, failures = 0
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
  for (let i = 0; i < 60; i++) { if (await ev(expression)) return; await sleep(50) }
  throw Error(`fixture not ready: ${expression}`)
}
const clickText = text => ev(`(()=>{const e=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()===${JSON.stringify(text)});if(!e)throw Error('missing control');e.click()})()`)
const check = (name, ok, detail) => { total++; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail)}`) }
const metrics = (width, height) => send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
const geometry = () => ev(`(()=>{const e=document.querySelector('[role=dialog]'),r=e.getBoundingClientRect();return {width:r.width,left:r.left,right:r.right,top:r.top,bottom:r.bottom,height:r.height,client:e.clientHeight,scroll:e.scrollHeight,overflow:getComputedStyle(e).overflowY}})()`)
const close = async () => {
  await ev(`document.querySelector('[role=dialog] [data-slot=dialog-close]').click()`)
  await waitFor(`!document.querySelector('[role=dialog]')`)
}
const cases = [
  // 2026-10-14（需求 #89）：apptomg/mgtoapp 已折叠进「上报校验」，侧边栏不再有这两个按钮。
  // 这条检查关心的是弹窗几何、不是「怎么进页面」，所以改 hash 直达 —— 更稳。
  { hash: '#/apptomg', button: '历史', name: '自动测试历史', width: 672 },
  { hash: '#/mgtoapp', button: '测试记录', name: '人工测试记录', width: 672 },
  { page: '性能测试', button: '排序', name: '游戏排序', width: 512 },
  { page: '性能测试', selector: '[data-testid="perf-game-ids-501"]', name: '游戏推送配置', width: 512 },
  { page: '性能测试', button: '新建分组', name: '新建坐标分组', width: 448 },
]
try {
  await metrics(1440, 1000)
  await send('Page.navigate', { url: APP })
  await waitFor(`!!document.querySelector('button')`)
  await ev(`(()=>{const original=window.fetch;window.fetch=async(input,init)=>{
    const rawPath=new URL(typeof input==='string'?input:input.url,location.href).pathname;const path=(rawPath==='/api/perf/runs/page'||rawPath==='/api/perf/runs/active')?'/api/perf/runs':rawPath;
    let body;
    if(path==='/api/devices')body=[];
    else if(path==='/api/perf/games')body=[{id:501,name:'LAYOUT-GAME',shortName:'LAYOUT-GAME'}];
    else if(path==='/api/perf/runs'||path==='/api/perf/button-groups')body=[];
    else if(path==='/api/perf/ai-room'||path==='/api/perf/options')body={};
    else return original(input,init);
    if(rawPath==='/api/perf/runs/page')body={items:body,nextCursor:null,hasMore:false};else if(rawPath==='/api/perf/runs/active')body=Array.isArray(body)?body.filter(run=>['RUNNING','WAIT_DEVICE','WAIT_ENV'].includes(run.status)):[];
      return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});
  }})()`)
  for (const item of cases) {
    await metrics(1440, 1000)
    if (item.hash) {
      // hash 直达（用于侧边栏已折叠、不再有按钮的页面）
      await send('Page.navigate', { url: APP + item.hash })
      await sleep(700)
    } else {
      await waitFor(`[...document.querySelectorAll('button')].some(x=>x.textContent.trim()===${JSON.stringify(item.page)})`)
      await clickText(item.page)
    }
    if (item.selector) {
      await waitFor(`!!document.querySelector(${JSON.stringify(item.selector)})`)
      await ev(`document.querySelector(${JSON.stringify(item.selector)}).click()`)
    } else {
      await waitFor(`[...document.querySelectorAll('button')].some(x=>x.textContent.trim()===${JSON.stringify(item.button)})`)
      await clickText(item.button)
    }
    await waitFor(`!!document.querySelector('[role=dialog]')`)
    await sleep(180)
    let g = await geometry()
    check(`${item.name}桌面宽度`, Math.abs(g.width - item.width) < 2, g)
    // 在弹窗打开后缩窄，覆盖跨断点重排；无需通过移动侧栏进行测试。
    await metrics(390, 667)
    await sleep(180)
    g = await geometry()
    check(`${item.name}窄窗口保留两侧边距`, g.left >= 15 && g.right <= 375, g)
    if (item.selector) {
      check('推送配置窄窗口上下完整', g.top >= 0 && g.bottom <= 667, g)
      await metrics(1440, 720)
      await sleep(180)
      g = await geometry()
      check('推送配置低窗口上下完整', g.top >= 0 && g.bottom <= 720, g)
      await ev(`document.querySelector('[data-testid="perf-game-ids-save"]').scrollIntoView({block:'end'})`)
      await sleep(80)
      const reachable = await ev(`(()=>{const b=document.querySelector('[data-testid="perf-game-ids-save"]'),r=b.getBoundingClientRect();return {top:r.top,bottom:r.bottom,hit:b.contains(document.elementFromPoint(r.left+r.width/2,r.top+r.height/2))}})()`)
      check('低窗口滚动后保存按钮可见可点击', reachable.top >= 0 && reachable.bottom <= 720 && reachable.hit, reachable)
    }
    await close()
  }
} finally { ws.close() }
console.log(`弹窗布局回归：${total - failures}/${total}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
