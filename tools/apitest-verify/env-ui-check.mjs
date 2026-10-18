import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 环境切换用真实浏览器与独立实例验证；全部数据都是本机虚构值。
const CDP = requiredUiUrl('SP_CDP')
const APP = requiredUiUrl('SP_APP_URL')
const base = new URL('/api/apitest', APP).href
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve))
let next = 0
const pending = new Map()
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data)
  if (pending.has(msg.id)) {
    pending.get(msg.id)(msg)
    pending.delete(msg.id)
  }
})
const send = (method, params) => new Promise(resolve => {
  const id = ++next
  pending.set(id, resolve)
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const msg = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (msg.error || msg.result.exceptionDetails) throw new Error(JSON.stringify(msg.error || msg.result.exceptionDetails))
  return msg.result.result.value
}
let total = 0, failures = 0
const check = (name, ok) => { total++; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`) }
const api = async (path, method = 'GET', body) => {
  const res = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body) })
  if (!res.ok) throw new Error(`fixture HTTP ${res.status}`)
  const text = await res.text()
  return text ? JSON.parse(text) : undefined
}
const seeded = []
try {
  for (const name of ['A', 'B']) {
    const env = await api('/envs', 'POST', { name: `env-regression-${name}-${Date.now()}` })
    seeded.push(env.id)
    await api('/vars', 'PUT', { env: env.id, base: [{ name: 'base', value: `https://${name.toLowerCase()}.invalid` }],
      header: [{ name: 'header', value: name }], body: [{ name: 'body', value: name }] })
    await api('/sign', 'PUT', { env: env.id, enabled: false, appId: `app-${name}`, appSecret: `dummy-${name}`, nonce: '', mgId: '' })
  }
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await send('Page.navigate', { url: APP })
  await sleep(1200)
  await ev(`([...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='接口测试') || {}).click?.()`)
  await sleep(500)
  await ev(`([...document.querySelectorAll('[data-testid="entry-card"]')].find(x=>x.textContent.includes('用例管理')) || [...document.querySelectorAll('div.cursor-pointer')].find(x=>x.textContent.includes('用例管理')))?.click()`)
  await sleep(400)
  await ev(`([...document.querySelectorAll('[data-testid="entry-card"]')].find(x=>x.textContent.includes('环境设置')) || [...document.querySelectorAll('div.cursor-pointer')].find(x=>x.textContent.includes('环境设置')))?.click()`)
  await sleep(500)
  const choose = async id => { await ev(`document.querySelector('[data-testid="env-card-${id}"]')?.click()`); await sleep(100) }
  const values = () => ev(`[...document.querySelectorAll('[role=dialog] input')].map(x=>x.value)`)
  check('首次打开即展示所有环境卡片', await ev(`!!document.querySelector('[data-testid="env-card-${seeded[0]}"]')`))
  await choose(seeded[0]); await sleep(250)
  check('切换到 A 加载 A 的基址', (await values()).includes('https://a.invalid'))
  await choose(seeded[1]); await sleep(250)
  check('切换到 B 加载 B 的基址并清掉 A', (await values()).includes('https://b.invalid') && !(await values()).includes('https://a.invalid'))
  await ev(`([...document.querySelectorAll('[role=dialog] button')].find(x=>x.textContent.trim()==='签名'))?.click()`)
  check('签名表单与当前环境一致', await ev(`document.querySelector('#api-sign-appid')?.value === 'app-B'`))
  // 故意让 A 后返回，B 先返回，压住网络乱序造成的串环境。
  await ev(`window.envOriginalFetch=window.fetch; window.fetch=async (...args)=>{
    const result=await window.envOriginalFetch(...args); const url=String(args[0]);
    if(url.includes('env=${seeded[0]}')) await new Promise(r=>setTimeout(r,500)); return result;
  }`)
  await choose(seeded[0])
  check('加载中禁止保存', await ev(`([...document.querySelectorAll('[role=dialog] button')].find(x=>x.textContent.trim()==='保存'))?.disabled === true`))
  await choose(seeded[1]); await sleep(700)
  check('迟到的 A 响应不能覆盖 B 表单', await ev(`document.querySelector('#api-sign-appid')?.value === 'app-B'`))
  // 读密钥的迟到响应也必须失效，不能落入另一个环境的编辑器。
  await choose(seeded[0]); await sleep(700)
  await ev(`document.querySelector('button[title^="显示已保存"]')?.click()`)
  await choose(seeded[1]); await sleep(700)
  check('切环境后迟到的密钥不显示、不残留', await ev(`document.querySelector('#api-sign-secret')?.value === '' && document.querySelector('#api-sign-secret')?.type === 'password'`))
  await ev(`window.fetch=window.envOriginalFetch`)
  await ev(`(()=>{const input=[...document.querySelectorAll('[role=dialog] input')].find(x=>x.value==='https://b.invalid');
    if(input){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'https://b-updated.invalid');
      input.dispatchEvent(new Event('input',{bubbles:true}));}})()`)
  await sleep(100)
  await ev(`([...document.querySelectorAll('[role=dialog] button')].find(x=>x.textContent.trim()==='保存'))?.click()`)
  await sleep(500)
  const a = await api(`/vars?env=${seeded[0]}`), b = await api(`/vars?env=${seeded[1]}`)
  check('编辑并保存 B 只更新 B，不覆盖 A', a.base[0]?.value === 'https://a.invalid' && b.base[0]?.value === 'https://b-updated.invalid')
  const response = await api(`/envs/${seeded[1]}/settings`, 'PUT', {
    sign: { enabled: false, appId: 'app-B', appSecret: '', nonce: '', mgId: '' }, vars: b })
  check('整套保存响应不含密钥明文', !JSON.stringify(response).includes('dummy-B') && !('appSecret' in response.sign))
  check('留空密钥的整套保存保留旧密钥', (await api(`/sign/secret?env=${seeded[1]}`)).appSecret === 'dummy-B')
} finally {
  for (const id of seeded) await api(`/envs/${id}`, 'DELETE')
  ws.close()
}
console.log(`环境界面回归：${total - failures}/${total}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
