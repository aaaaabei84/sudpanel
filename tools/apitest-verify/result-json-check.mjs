import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 展开一条已执行的用例，验证「响应头 / 响应体 / 本次发出的请求」这几块 JSON 的展示。
//
// 只跑在本地验证实例上（默认 18797，由 make-shot.sh 起好并灌了数据），不碰正式库。
//
// 这里要钉住的两件事，都是「看起来没问题、其实不能用」的类型：
//   1) **不能横向溢出**。展示的正文里常有签名、token、base64 这类不含空格的长串，
//      只写按空格断行（break-words）时它们会把容器顶出横向滚动条 —— 一段 300 字的值
//      就是几百像素的滚动，等于看不全。必须能在任意位置断行。
//   2) **JSON 要真的排过版**。没排版时一整行挤在一起，颜色再花也读不出来结构。
//
// 前置：无头 Chrome 已开 CDP 9222；SP_APP_URL 指向验证实例。
const CDP = requiredUiUrl('SP_CDP')
const APP = requiredUiUrl('SP_APP_URL')
const CASE_HINT = process.env.SP_LONG_CASE || '超长'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const list = await isolatedUiPages(APP, CDP)
const target = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
if (!target) throw new Error('拿不到可调试页面')

const ws = new WebSocket(target.webSocketDebuggerUrl)
let id = 0
const pending = new Map()
await new Promise((r) => ws.addEventListener('open', r))
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m.result)
    pending.delete(m.id)
  }
})
const send = (method, params) =>
  new Promise((res) => {
    const mid = ++id
    pending.set(mid, res)
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
const ev = async (expr) => {
  const r = await send('Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
  })
  if (r.exceptionDetails) throw new Error('页面内报错: ' + (r.exceptionDetails.exception?.description ?? ''))
  return r.result.value
}

await send('Runtime.enable')
await send('Page.enable')
// 无头 Chrome 默认 800x600，侧边栏会折叠，按文字找不到入口
await send('Emulation.setDeviceMetricsOverride', {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
})

let ok = 0
let bad = 0
const check = (name, cond, detail = '') => {
  if (cond) {
    ok++
    console.log(`  ✓ ${name}`)
  } else {
    bad++
    console.log(`  ✗ ${name}  ${detail}`)
  }
}

console.log(`=== 结果展示（JSON 上色与折行）浏览器验证 · ${APP} ===\n`)

await send('Page.navigate', { url: APP })
await sleep(3000)

await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '接口测试')
  if (b) { b.click(); return 'OK' }
  const card = [...document.querySelectorAll('div.group')]
    .find(e => e.textContent.includes('接口测试') && e.textContent.includes('接口用例'))
  if (card) { card.click(); return 'OK' }
  return 'NOT_FOUND'
})()`)
await sleep(2000)

// 从「执行批次」里点开一个批次，让结果表有数据（页面上没有「最近试跑」列表）
const opened = await ev(`(() => {
  const tables = [...document.querySelectorAll('table')]
  const t = tables.find(x => x.innerText.includes('批次') && x.innerText.includes('进度'))
  if (!t) return 'NO_BATCH_TABLE'
  const btn = [...t.querySelectorAll('tbody button')].find(b => b.textContent.trim().startsWith('#'))
  if (!btn) return 'NO_BATCH_ROW'
  btn.click()
  return btn.textContent.trim()
})()`)
check('点得开一个批次', opened !== 'NO_BATCH_TABLE' && opened !== 'NO_BATCH_ROW', opened)
await sleep(2500)

const rowCount = await ev(`(() => {
  const t = [...document.querySelectorAll('table')].find(x => x.innerText.includes('轮/序'))
  return t ? t.querySelectorAll('tbody tr').length : -1
})()`)
check('批次明细渲染出结果行', rowCount > 0, `行数 ${rowCount}`)

// 展开长串那条：它的响应体里有一个 288 字符不含空格的值的
const expandedOne = await ev(`(() => {
  const t = [...document.querySelectorAll('table')].find(x => x.innerText.includes('轮/序'))
  if (!t) return 'NO_TABLE'
  const tr = [...t.querySelectorAll('tbody tr')].find(r => r.innerText.includes(${JSON.stringify(CASE_HINT)}))
  if (!tr) return 'NO_MATCH'
  tr.click()
  return 'OK'
})()`)
check(`找得到「${CASE_HINT}」那条并展开`, expandedOne === 'OK', expandedOne)
await sleep(600)

// 展开区里四块内容的标签，以及每块的滚动情况。
// 标签是各自外层 <div> 的第一个子元素，所以 nextElementSibling 就是内容块 ——
// 按这个关系找，比按类名找稳（类名会随样式调整而变）。
const probe = await ev(`(() => {
  const labels = ['本次发出的请求', '响应头', '响应体', '本次发出的请求体']
  const found = {}
  for (const label of labels) {
    const lab = [...document.querySelectorAll('div')].find(
      d => d.children.length === 0 && d.textContent.trim() === label)
    if (!lab) { found[label] = null; continue }
    const block = lab.nextElementSibling
    if (!block) { found[label] = null; continue }
    const scroller = block.firstElementChild || block   // max-h-56 overflow-auto 那层
    const pres = [...block.querySelectorAll('pre')]
    const spans = [...block.querySelectorAll('pre span')]
    const colors = [...new Set(spans.map(s => getComputedStyle(s).color))]
    const cs = pres[0] ? getComputedStyle(pres[0]) : null
    found[label] = {
      pres: pres.length,
      spans: spans.length,
      colors: colors.length,
      text: block.textContent,
      whiteSpace: cs ? cs.whiteSpace : '',
      overflowWrap: cs ? cs.overflowWrap : '',
      scrollW: scroller.scrollWidth,
      clientW: scroller.clientWidth,
      scrollH: scroller.scrollHeight,
      clientH: scroller.clientHeight,
    }
  }
  return JSON.stringify(found)
})()`)
const f = JSON.parse(probe)

for (const label of ['本次发出的请求', '响应头', '响应体', '本次发出的请求体']) {
  check(`展开区含有「${label}」块`, f[label] != null, label)
}
check('四块都有内容（不是空的占位符）', ['本次发出的请求', '响应头', '响应体', '本次发出的请求体']
  .every(l => f[l] && f[l].text.trim().length > 0))

// 后面几步都依赖「响应体」那一块。它没找到时直接收尾报错，
// 否则会以 TypeError 崩掉，看的人得先读懂堆栈才知道是哪一步出的问题。
const body = f['响应体']
if (!body) {
  console.log(`\n  通过 ${ok} 项，失败 ${bad} 项（「响应体」块没渲染出来，后面的检查跳过）`)
  ws.close()
  process.exit(1)
}

// ---------- 上色 ----------
check('响应体块被切成多个带颜色的片段', body.spans >= 5, `${body.spans} 个片段`)
check(`响应体块用了至少 3 种颜色（键/值/数字各不同）`, body.colors >= 3, `${body.colors} 种`)

const reqBlock = f['本次发出的请求']
check('「本次发出的请求」里既有文字行也有 JSON 段（按括号配对切开）', reqBlock.pres >= 2,
  `${reqBlock.pres} 个 pre`)

// ---------- 自动排版 ----------
check('响应体换成了多行（JSON 排过版，不是一整行）', body.text.split('\n').length >= 4,
  JSON.stringify(body.text.slice(0, 120)))
check('响应体有缩进', /\n\s{2}"/.test(body.text), JSON.stringify(body.text.slice(0, 160)))
check('响应头按行显示', f['响应头'].text.split('\n').length >= 2,
  JSON.stringify(f['响应头'].text.slice(0, 80)))

// ---------- 折行 ----------
check('响应体块保留了原有换行（white-space: pre-wrap）',
  ['pre-wrap', 'break-spaces'].includes(body.whiteSpace), body.whiteSpace)
check('响应体块允许在任意位置断行（overflow-wrap: anywhere）',
  body.overflowWrap === 'anywhere', body.overflowWrap)

for (const label of ['本次发出的请求', '响应头', '响应体', '本次发出的请求体']) {
  const b = f[label]
  if (!b) continue
  check(`「${label}」没有横向溢出（内容 ${b.scrollW} / 可视 ${b.clientW}）`,
    b.scrollW <= b.clientW + 1, `${b.scrollW} > ${b.clientW}`)
}

const longest = Math.max(...body.text.split('\n').map(l => l.length))
check(`响应体里确实有需要断行的长行（最长行 ${longest} 字符）`, longest > 120, `最长 ${longest}`)

// ---------- 原始 / 美化 切换 ----------
const toggle = await ev(`(() => {
  const lab = [...document.querySelectorAll('div')].find(
    d => d.children.length === 0 && d.textContent.trim() === '响应体')
  const block = lab ? lab.nextElementSibling : null
  if (!block) return 'NO_BLOCK'
  const b = [...block.querySelectorAll('button')].find(x => x.textContent.trim() === '原始')
  return b ? 'HAS_TOGGLE' : 'NO_TOGGLE'
})()`)
check('排版与原文不同时提供「原始」切换（正文空白是有意义的，要看原件时能切回去）',
  toggle === 'HAS_TOGGLE', toggle)

console.log(`\n  通过 ${ok} 项，失败 ${bad} 项`)
ws.close()
process.exit(bad === 0 ? 0 : 1)
