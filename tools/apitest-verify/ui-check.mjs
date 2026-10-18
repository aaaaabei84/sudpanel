import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 浏览器端界面验证：确认接口测试页真的渲染出来、能交互。
// 与后端 API 验证是两回事 —— 这里只看「人打开页面能不能用」。
//
// 仅由隔离包装脚本显式提供 SP_APP_URL / SP_CDP。
// 此入口创建自己的页面并核验 origin，不借用现有浏览器标签页。
import { execFileSync } from 'node:child_process'

const CDP = requiredUiUrl('SP_CDP')
const APP = requiredUiUrl('SP_APP_URL')
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
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id) }
  // 页面里的异常默认是**看不见的**：一个抛错的 rAF 回调会让动画链悄悄断掉、
  // 而 follow 状态还停在 true —— 表面症状是「翻上去不停住了」，查起来会往状态机方向找，
  // 实际是脚本挂了。这条监听只在诊断时打开（设 SP_DEBUG_EXC=1）。
  if (process.env.SP_DEBUG_EXC && m.method === 'Runtime.exceptionThrown') {
    const d = m.params?.exceptionDetails
    console.log('  [PAGE ERROR]', d?.exception?.description || d?.text || JSON.stringify(d))
  }
})
const send = (method, params) => new Promise((res, reject) => {
  const mid = ++id
  const timer = setTimeout(() => { pending.delete(mid); reject(new Error(`CDP deadline: ${method}`)) }, 15000)
  pending.set(mid, value => { clearTimeout(timer); res(value) })
  ws.send(JSON.stringify({ id: mid, method, params }))
})
const ev = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) {
    // 注入页面的代码是模板字面量拼出来的，转义写错时页面只会回一句笼统的 SyntaxError，
    // 完全看不出是哪一段。出错时把出问题的那段代码打出来（带行号），省掉一轮盲猜。
    const desc = r.exceptionDetails.exception?.description ?? ''
    if (desc.includes('SyntaxError')) {
      console.error('\n--- 注入页面的代码有语法错，原文如下 ---')
      expr.split('\n').forEach((l, i) => console.error(`${String(i + 1).padStart(4)}| ${l}`))
      console.error('--- 以上为原文 ---\n')
    }
    throw new Error('页面内报错: ' + desc)
  }
  return r.result.value
}
await send('Runtime.enable')
await send('Page.enable')
// 关键：无头 Chrome 默认视口 800x600，侧边栏处于「折叠成图标」状态，
// 于是按文字找「接口测试」永远找不到 —— 先撑开到桌面尺寸再测。
await send('Emulation.setDeviceMetricsOverride', {
  width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
})

let ok = 0, bad = 0
const check = (name, cond, detail = '') => {
  if (cond) { ok++; console.log(`  ✓ ${name}`) }
  else { bad++; console.log(`  ✗ ${name}  ${detail}`) }
}

/**
 * 按文字点一个**入口卡片**。
 *
 * <p>2026-10-04（需求 #50）把「用例管理」「环境设置」「新建用例」从按钮改成了入口卡片，
 * 而卡片渲染成 div（不是 button）。只查 button 的老写法会一个都找不到，
 * 表现成「点不动 / 功能坏了」，其实是选择器的问题。
 *
 * <p>判定「可点」用 cursor === 'pointer'，因为卡片自己带 cursor-pointer 类；
 * 而弹窗标题里的同名文字（如 DialogTitle 的「用例管理」）cursor 是默认值，会被排除掉。
 * cursor 是可继承属性，所以命中的也可能是卡片内部的子元素 —— 点它同样会冒泡到卡片的 onClick，
 * 效果一样。
 *
 * @param text 卡片上出现的文字（用 includes 匹配）
 * @param scope 'body' 在整个页面找；'dialog' 只在「用例管理」弹窗里找
 */
const clickCard = (text, scope = 'body') => ev(`(() => {
  const root = ${scope === 'dialog'
    ? `[...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))`
    : 'document.body'}
  if (!root) return 'NO_ROOT'
  const el = [...root.querySelectorAll('button, div, a')].find(e =>
    e.textContent.trim().includes(${JSON.stringify(text)})
    && getComputedStyle(e).cursor === 'pointer')
  if (!el) return 'NOT_FOUND'
  el.click(); return 'OK'
})()`)

console.log(`=== 接口测试页面（${APP}）浏览器验证 ===\n`)

await send('Page.navigate', { url: APP })
await sleep(3000)

// 侧边栏展开后应当能找到文字；同时记录一下侧边栏是否真的展开了
const navInfo = await ev(`(() => {
  const all = [...document.querySelectorAll('button, a, [role=button]')]
  const hit = all.find(e => e.textContent.trim() === '接口测试')
  return JSON.stringify({
    sidebarLabels: all.map(e => e.textContent.trim()).filter(t => t && t.length < 8).slice(0, 12),
    found: !!hit,
  })
})()`)
const info = JSON.parse(navInfo)
check('侧边栏出现「接口测试」入口', info.found, `侧边栏可见文字：${info.sidebarLabels.join(' / ')}`)

const clicked = await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '接口测试')
  if (b) { b.click(); return 'SIDEBAR' }
  // 兜底：点首页那张「接口测试」快捷卡片
  const card = [...document.querySelectorAll('div.group')]
    .find(e => e.textContent.includes('接口测试') && e.textContent.includes('接口用例'))
  if (card) { card.click(); return 'CARD' }
  return 'NOT_FOUND'
})()`)
check('点得动', clicked !== 'NOT_FOUND', clicked)
await sleep(2000)

const body = await ev('document.body.innerText')
check('确实切到了接口测试页（在，且不是首页）', body.includes('执行选中') && !body.includes('设备状态'),
      `前 80 字：${body.slice(0, 80).replace(/\n/g, ' | ')}`)
// 2026-10-04（需求 #50）：主页面的用例入口改成「用例管理」小卡片后，
// 页面上不再有「接口用例」这几个字，这里按新的卡片标题判定。
check('出现「用例管理」入口卡片', body.includes('用例管理'))

// 入口必须是**卡片**而不是按钮：卡片才有首页那套方形图标块。
// 光看文字看不出这个，所以按结构判定 —— 图标块 = 圆角方块 + bg-primary/10 底色。
const entryIsCard = JSON.parse(await ev(`(() => {
  // 找主页面里那张卡片：文字含「用例管理」且有 pointer 光标，且不在弹窗里
  const el = [...document.querySelectorAll('div')].find(e =>
    typeof e.className === 'string'
    && e.className.includes('cursor-pointer')
    && e.textContent.includes('用例管理')
    && !e.closest('[role=dialog]'))
  if (!el) return '{"found":false}'
  const tile = [...el.querySelectorAll('div')].find(x =>
    typeof x.className === 'string'
    && x.className.includes('bg-primary/10')
    && x.className.includes('rounded-lg'))
  const r = tile ? tile.getBoundingClientRect() : null
  return JSON.stringify({
    found: true,
    hasTile: !!tile,
    // 方形：宽高相等且是 36px 左右（h-9 w-9）
    tileW: r ? Math.round(r.width) : 0,
    tileH: r ? Math.round(r.height) : 0,
    hasArrow: !!el.querySelector('svg.lucide-arrow-right'),
    hasBadge: el.innerText.includes('已选'),
  })
})()`)) || {}
check('入口是卡片形式（有方形图标块，与首页跳转卡片同款）',
  entryIsCard.hasTile === true && Math.abs(entryIsCard.tileW - entryIsCard.tileH) <= 1,
  JSON.stringify(entryIsCard))
check('卡片上带「已选 N」计数（弹窗关掉后仍看得见）', entryIsCard.hasBadge === true)
check('出现「执行」区', body.includes('执行选中'))
check('出现「执行批次」区', body.includes('执行批次'))
check('出现「返回码字典」区', body.includes('返回码字典'))
check('说明了字典可自行补充', body.includes('内部码可自行补充'))
check('解释了空结果怎么用（一行短句，不是一整段说明）',
  body.includes('勾选用例后点「执行选中」'), body.slice(0, 200).replace(/\n/g, ' | '))

// 字典表要真的渲染出数据行（不是在等骨架屏）
const dictRows = await ev(`(() => {
  const tables = [...document.querySelectorAll('table')]
  const t = tables.find(x => x.innerText.includes('返回码') && x.innerText.includes('分类'))
  return t ? t.querySelectorAll('tbody tr').length : -1
})()`)
check('返回码字典渲染出数据行', dictRows > 0, `行数 ${dictRows}`)
check('字典行数不少于 100 条（第 1 页渲染）', dictRows >= 100, `行数 ${dictRows}`)

const hasMeaning = await ev(`document.body.innerText.includes('游戏已开始，不能重复开始')`)
check('字典里能看到公开码的释义', hasMeaning)

// ---------- 用例管理弹窗（需求 #41 / #42 / #43 / #44 / #50） ----------
// 用例区整块收进了弹窗：主页面只留一张入口卡片，勾选计数挂在卡片上。
// 这一段的断言要跑在「新建用例弹窗」之前 —— 那之后弹窗会叠成两层。
console.log('\n--- 用例管理弹窗 ---')
// 入口是「卡片」不是按钮（需求 #50），所以走 clickCard
const openCases = await clickCard('用例管理')
check('「用例管理」入口卡片存在且可点（用例区已收进弹窗）', openCases === 'OK', openCases)
await sleep(900)

const cm = JSON.parse(await ev(`(() => {
  const ds = [...document.querySelectorAll('[role=dialog]')]
  const d = ds.find(x => x.innerText.includes('用例管理'))
  if (!d) return '{"found":false}'
  const r = d.getBoundingClientRect()
  const rows = [...d.querySelectorAll('div')].filter(x =>
    typeof x.className === 'string' && x.className.split(' ').includes('hover:scale-[1.03]'))
  // 滚动容器不按高度找（列表为空时它很矮，按高度找会找不到而误判失败），
  // 而是按「弹窗自己不滚、里面某层负责滚」这个真正要守住的结构来找。
  const scroller = [...d.querySelectorAll('div')].find(x => {
    const s = getComputedStyle(x)
    return s.overflowY === 'auto' || s.overflowY === 'scroll'
  })
  return JSON.stringify({
    found: true,
    w: Math.round(r.width),
    dialogOverflowY: getComputedStyle(d).overflowY,
    hasSearch: !!d.querySelector('input[placeholder="搜名称 / 地址 / 方法"]'),
    hasNew: d.innerText.includes('新建用例'),
    hasEnv: d.innerText.includes('环境设置'),
    // 「刷新」现在是标题行的图标按钮（没有文字），按 title 判定（需求 #50）
    hasRefresh: !!d.querySelector('button[title="重新拉取用例与分组"]'),
    // 入口卡片的方形图标块（首页那套视觉的核心元素）。
    // 按类名遍历而不是写选择器：类名里有斜杠，塞进选择器要转义，而转义在模板字符串里
    // 又要再吃一层 —— 这类地方一律改成遍历比对。
    iconTiles: [...d.querySelectorAll('div')].filter(x =>
      typeof x.className === 'string' && x.className.includes('bg-primary/10')).length,
    hasBadge: d.innerText.includes('已选'),
    rowCount: rows.length,
    rowH: rows.length ? Math.round(rows[0].getBoundingClientRect().height) : 0,
    rowCls: rows.length ? rows[0].className : '',
    scrolls: !!scroller,
    // ---- 分组（需求 #48）----
    // 用元素判定而不是正则：这段代码是拼进页面里执行的，注释里一旦写出反斜杠 n，
    // 模板字符串会先把它变成真实换行，single-line 注释就被截断，页面直接 SyntaxError。
    // 教训：注入页面的代码里，注释只写纯粹的中文，不要出现任何反斜杠转义写法。
    hasAll: [...d.querySelectorAll('div')].some(x =>
      typeof x.className === 'string' && x.className.includes('cursor-pointer')
      && x.textContent.trim().startsWith('全部')),
    hasUngrouped: [...d.querySelectorAll('div')].some(x =>
      typeof x.className === 'string' && x.className.includes('cursor-pointer')
      && x.textContent.trim().startsWith('未分组')),
    hasGroupHeader: d.innerText.includes('分组'),
    // 两栏：分组栏是 grid 的第一个子元素，宽度明显小于右栏。
    // 不能按类名写选择器 —— 这个类名带方括号与冒号（sm:grid-cols-[220px_1fr]），
    // 直接丢给 querySelector 会报 not a valid selector。改成遍历比对类名字面量。
    twoCol: (() => {
      const grid = [...d.querySelectorAll('div')].find(x =>
        typeof x.className === 'string' && x.className.includes('sm:grid-cols-[220px_1fr]'))
      if (!grid || grid.children.length < 2) return false
      const a = grid.children[0].getBoundingClientRect().width
      const b = grid.children[1].getBoundingClientRect().width
      return a > 100 && a < 340 && b > 400
    })(),
  })
})()`)) || {}
check('「用例管理」弹窗打开了', cm.found === true)
check(`弹窗够宽（实测 ${cm.w}px，需 ≥ 1100 —— sm:max-w-6xl）`, cm.w >= 1100, `宽 ${cm.w}`)
check('搜索框搬进了弹窗', cm.hasSearch === true)
check('「新建用例」搬进了弹窗', cm.hasNew === true)
check('「环境设置」搬进了弹窗', cm.hasEnv === true)
check('「刷新」搬进了弹窗', cm.hasRefresh === true)
check('入口/弹窗上带「已选 N」计数（收起后主页面仍看得见选中数）', cm.hasBadge === true)
check('弹窗内是「左分组 + 右用例」两栏（左栏窄、右栏宽）', cm.twoCol === true,
  `两栏判定 ${cm.twoCol}`)
check('左栏有「分组」小标题', cm.hasGroupHeader === true)
check('左栏有「全部」与「未分组」两个视图项', cm.hasAll && cm.hasUngrouped,
  `全部=${cm.hasAll} 未分组=${cm.hasUngrouped}`)
// 需求 #50：弹窗内的「环境设置」与「新建用例」也改成小卡片。
// 判定方式是方形图标块（bg-primary/10）的数量：两张入口卡片 + 左栏若干分组卡片，
// 所以只要求「至少 2 个」；真正要守住的是「卡片视觉还在，没被退回成裸按钮」。
check(`弹窗内用的是卡片形式（实测 ${cm.iconTiles} 个方形图标块，需 ≥ 2）`,
  cm.iconTiles >= 2, `图标块数 ${cm.iconTiles}`)
check('标题行固定不滚、列表区内部滚动', cm.scrolls === true && cm.dialogOverflowY === 'hidden',
  `弹窗自身 overflow-y=${cm.dialogOverflowY}，内部滚动层=${cm.scrolls}`)
if (cm.rowCount === 0) {
  // 走到这里说明 run-ui.sh 的播种没生效 —— 别让它静默变成「没验」
  check('列表里有用例行可供断言（run-ui.sh 应已播一条占位用例）', false,
    '行数为 0：卡片式两行与悬停放大这两组断言无法验证')
} else {
  check(`用例是卡片式两行（实测行高 ${cm.rowH}px，需 ≥ 44）`, cm.rowH >= 44, `行高 ${cm.rowH}`)
  check('悬停放大挂上了（hover:scale-[1.03]）', cm.rowCls.includes('hover:scale-[1.03]'), cm.rowCls)
  check('放大带层级（relative + hover:z-10，否则会被下一行盖住）',
    cm.rowCls.split(' ').includes('relative') && cm.rowCls.includes('hover:z-10'), cm.rowCls)
  check('放大的过渡与操作按钮淡入共用 duration-150', cm.rowCls.includes('duration-150'), cm.rowCls)
  // 操作按钮平时不显示、悬停才出现 —— 这是「整条放大」之外的第二套悬停效果，两者要共用过渡
  const hoverBtn = JSON.parse(await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const b = d && d.querySelector('button[title="编辑"]')
    if (!b) return '{"found":false}'
    const wrap = b.parentElement
    return JSON.stringify({
      found: true,
      opacity: getComputedStyle(wrap).opacity,
      transition: getComputedStyle(wrap).transitionDuration,
    })
  })()`)) || {}
  check('行内操作按钮平时隐藏（悬停才出现，避免平时就占满一行）', hoverBtn.opacity === '0',
    `opacity=${hoverBtn.opacity}`)
  check('操作按钮的过渡时长也是 150ms（与放大动效一致，否则一个快一个慢会显得散）',
    (hoverBtn.transition || '').includes('0.15'), hoverBtn.transition)

  // ---------- 点击整条即可选中（需求 #47）----------
  // 原来只能瞄准左侧那个方块，而方块在悬停放大时还会跟着位移，越瞄越不准。
  // 这一段要同时钉住两件事：点行能选中，以及**点复选框不会翻两次**（冒泡没挡住就会净效果不变）。
  const pickState = () => ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    if (!d) return '{"found":false}'
    // 弹窗标题里那行是「共 X 条，已选 Y 条」——顺序与入口按钮上的「已选 N / 共 M」相反，
    // 按印象写正则会一次都匹配不上，然后表现成「点行没反应」（实测踩过）。
    const m = d.innerText.match(/共\\s*(\\d+)\\s*条，已选\\s*(\\d+)\\s*条/)
    const rows = [...d.querySelectorAll('div')].filter(x =>
      typeof x.className === 'string' && x.className.split(' ').includes('hover:scale-[1.03]'))
    return JSON.stringify({
      found: true, picked: m ? Number(m[2]) : -1, total: m ? Number(m[1]) : -1,
      rows: rows.length,
      cursor: rows.length ? getComputedStyle(rows[0]).cursor : '',
      select: rows.length ? getComputedStyle(rows[0]).userSelect : '',
      origin: rows.length ? getComputedStyle(rows[0]).transformOrigin : '',
      checked: rows.length
        ? rows[0].querySelector('[role=checkbox], button[data-state]')?.getAttribute('data-state') : null,
    })
  })()`)

  const clickRow = () => ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const row = d && [...d.querySelectorAll('div')].find(x =>
      typeof x.className === 'string' && x.className.split(' ').includes('hover:scale-[1.03]'))
    if (!row) return 'NOT_FOUND'
    row.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    return 'OK'
  })()`)

  const before = JSON.parse(await pickState() || '{}')
  check('行上是手型光标（看得出整条可点）', before.cursor === 'pointer', before.cursor)
  check('整行禁用了文字拖选（否则拖到一半松手会误改勾选）', before.select === 'none', before.select)
  // 放大基点钉在左边缘 —— 从中心放大时左侧那个方块会跟着位移，正是用户说的「方块会移动」
  check('放大基点是左边缘（origin-left，方块不再位移）',
    (before.origin || '').startsWith('0px'), before.origin)

  await clickRow()
  await sleep(400)
  const afterRow = JSON.parse(await pickState() || '{}')
  check(`点整条行会选中（已选 ${before.picked} → ${afterRow.picked}）`,
    afterRow.picked === before.picked + 1, `${before.picked} → ${afterRow.picked}`)
  check('选中后复选框状态也跟着变（方块是状态指示器）', afterRow.checked === 'checked', afterRow.checked)

  await clickRow()
  await sleep(400)
  const afterRow2 = JSON.parse(await pickState() || '{}')
  check(`再点一次取消选中（已选 ${afterRow.picked} → ${afterRow2.picked}）`,
    afterRow2.picked === afterRow.picked - 1, `${afterRow.picked} → ${afterRow2.picked}`)

  // 关键回归：点复选框自己。若没挡住冒泡，复选框 toggle 一次、冒泡到整行又 toggle 一次 = 净不变
  const clickBox = () => ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const row = d && [...d.querySelectorAll('div')].find(x =>
      typeof x.className === 'string' && x.className.split(' ').includes('hover:scale-[1.03]'))
    const box = row && row.querySelector('[role=checkbox], button[data-state]')
    if (!box) return 'NOT_FOUND'
    box.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    return 'OK'
  })()`)
  const boxClick = await clickBox()
  await sleep(400)
  const afterBox = JSON.parse(await pickState() || '{}')
  check('点复选框只切换一次（冒泡已挡住，不会「点一下翻两下」）',
    boxClick === 'OK' && afterBox.picked === afterRow2.picked + 1,
    `点击结果 ${boxClick}；已选 ${afterRow2.picked} → ${afterBox.picked}`)

  // 复原：不要带着选中态进入后面的断言
  await clickRow()
  await sleep(300)
  const restored = JSON.parse(await pickState() || '{}')
  check('复原为未选中（后续断言从干净状态开始）', restored.picked === 0, String(restored.picked))

  // ---------- 批量删除 / 批量迁移 / 分组复制（v1.10.6） ----------
  // 断言不点「确认迁移 / 删除选中」的最终动作 —— 那两步走 window.confirm，
  // headless 里没接弹窗处理器会卡死。这里只验「按钮在不在、形态对不对」，
  // 删除/迁移的**行为**由端到端（verify.py 14b 段）在接口层验。
  // 「复制分组」按钮挂在**真实分组**的卡片上（「全部」「未分组」是视图，不带操作按钮），
  // 而临时库原本一个分组都没有 —— 不先造一个，这条断言永远验不到。
  await ev(`fetch('/api/apitest/case-groups', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: '界面断言分组' }),
  }).then(r => r.status)`)
  await ev(`(() => { const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'));
    const b = d && [...d.querySelectorAll('button')].find(b => b.title === '重新拉取用例与分组');
    if (b) b.click() })()`)
  await sleep(700)

  const bar = JSON.parse(await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    if (!d) return '{"found":false}'
    return JSON.stringify({
      hint: !!d.querySelector('[data-testid="case-batch-hint"]'),
      delBtn: !!d.querySelector('[data-testid="case-batch-delete"]'),
      moveBtn: !!d.querySelector('[data-testid="case-batch-move"]'),
      rowDelete: [...d.querySelectorAll('button')].some(b => (b.title || '').trim() === '删除'),
      groupDup: !!d.querySelector('[data-testid="case-group-duplicate"]'),
      shell: (d.className || '').includes('h-[85vh]'),
    }) })()`)) || {}
  check('★ 批量操作条在，且行尾不再挂「删除」按钮',
    bar.hint === true && bar.rowDelete === false, JSON.stringify(bar))
  check('★ 分组卡片上有「复制分组」按钮', bar.groupDup === true, JSON.stringify(bar))
  check('★ 弹窗外壳改固定高度（h-[85vh]，切分组不再「缩」）', bar.shell === true, bar.shell)

  // 勾选一条 → 批量按钮出现；点「迁移选中」→ 内联表单出现；取消 → 回到操作条
  await clickRow()
  await sleep(400)
  const barPicked = JSON.parse(await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    return JSON.stringify({
      del: !!d.querySelector('[data-testid="case-batch-delete"]'),
      move: !!d.querySelector('[data-testid="case-batch-move"]'),
    }) })()`)) || {}
  check('★ 勾选后出现「删除选中 / 迁移选中」按钮', barPicked.del && barPicked.move, JSON.stringify(barPicked))

  await ev(`(() => { const b = d0.querySelector('[data-testid="case-batch-move"]'); if (b) b.click() })()`
    .replace('d0', `[...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))`))
  await sleep(400)
  const moveForm = JSON.parse(await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    if (!d) return '{}'
    return JSON.stringify({
      target: !!d.querySelector('[data-testid="case-move-target"]'),
      confirm: !!d.querySelector('[data-testid="case-move-confirm"]'),
      copyBtn: d.innerText.includes('复制过去'),
      moveBtn: d.innerText.includes('直接移过去'),
    }) })()`)) || {}
  check('★ 迁移表单出现：目标分组 + 复制/移过去 两种方式都在',
    moveForm.target && moveForm.confirm && moveForm.copyBtn && moveForm.moveBtn, JSON.stringify(moveForm))

  const cancelBtn = await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const b = d && [...d.querySelectorAll('button')].find(b => (b.textContent || '').trim() === '取消')
    if (!b) return 'NO_BTN'
    b.click(); return 'OK' })()`)
  check('「取消」能把迁移表单收起来', cancelBtn === 'OK', cancelBtn)

  // 复原：别带着勾选进入后面的断言
  await clickRow()
  await sleep(300)

  // ---------- 分组交互（需求 #48）----------
  const groupOps = JSON.parse(await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    if (!d) return '{"found":false}'
    const btns = [...d.querySelectorAll('button')].map(b => b.textContent.trim())
    return JSON.stringify({
      found: true,
      hasNewGroup: btns.includes('新建'),
      // 「全部」「未分组」是视图项，不该带改名/删除按钮
      folderIcons: d.querySelectorAll('svg').length > 0,
    })
  })()`)) || {}
  check('左栏有「新建」分组按钮', groupOps.hasNewGroup === true)

  // 新建一个分组，确认它出现在左栏、且点它能切换过去
  await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const b = [...d.querySelectorAll('button')].find(x => x.textContent.trim() === '新建')
    if (b) b.click(); return 'OK'
  })()`)
  await sleep(400)
  check('点「新建」后左栏出现分组名输入框', await ev(
    `!!document.querySelector('[role=dialog] input[placeholder="分组名"]')`))

  // 真建一个（用带时间戳的名字避免与之前跑过的残留重名）
  const gname = 'UI断言组' + String(Date.now()).slice(-6)
  await ev(`(() => {
    const inp = document.querySelector('[role=dialog] input[placeholder="分组名"]')
    if (!inp) return 'NO_INPUT'
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inp, ${JSON.stringify(gname)})
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    return 'OK'
  })()`)
  await sleep(200)
  await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const b = [...d.querySelectorAll('button')].find(x => (x.getAttribute('title') || '') === '确定新建')
    if (b) b.click(); return 'OK'
  })()`)
  await sleep(1200)

  const afterCreate = JSON.parse(await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    return JSON.stringify({
      text: d ? d.innerText : '',
      // 新建之后应当自动切到新组（建一个空组却停在原地，看起来像没建成）
      activeIsNew: d ? [...d.querySelectorAll('div')].some(x =>
        typeof x.className === 'string' && x.className.includes('bg-primary/10')
        && x.textContent.includes(${JSON.stringify(gname)})) : false,
    })
  })()`)) || {}
  check('新分组出现在左栏', (afterCreate.text || '').includes(gname), gname)
  check('新建后自动切到该分组（否则看起来像没建成功）', afterCreate.activeIsNew === true)
  check('空分组右栏给出可读提示（不是一片空白）',
    (afterCreate.text || '').includes('还是空的'), (afterCreate.text || '').slice(0, 120))

  // 删掉它，避免给后续跑留下垃圾分组。
  // 先接管 window.confirm：无头 Chrome 里它会自动取消，不接管的话点了删除什么也不会发生，
  // 然后断言会报「删不掉」，看起来像功能坏了（实测踩过）。
  await ev(`(() => { window.confirm = () => true; return 'OK' })()`)
  const delClick = await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    const nm = ${JSON.stringify(gname)}
    const row = [...d.querySelectorAll('div')].find(x =>
      typeof x.className === 'string' && x.className.includes('group/g') && x.textContent.includes(nm))
    if (!row) return 'NO_ROW'
    const b = [...row.querySelectorAll('button')].find(x =>
      (x.getAttribute('title') || '').startsWith('删除分组'))
    if (!b) return 'NO_BTN'
    b.click(); return 'OK'
  })()`)
  await sleep(1200)
  const afterDelete = await ev(`(() => {
    const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
    return d ? d.innerText.includes(${JSON.stringify(gname)}) : false
  })()`)
  check('能删除刚建的分组（左栏不再有它）', delClick === 'OK' && afterDelete === false,
    `点击=${delClick} 仍存在=${afterDelete}`)
}

// 打开新建用例弹窗（它是「用例管理」弹窗里的入口卡片，需求 #50）
const opened = await clickCard('新建用例', 'dialog')
check('「新建用例」入口卡片存在且可点（已从按钮改成小卡片）', opened === 'OK', opened)
await sleep(1200)

const dlg = await ev(`document.body.innerText`)
check('弹窗含「用例名称」', dlg.includes('用例名称'))
check('弹窗含「请求头（JSON 对象）」', dlg.includes('请求头（JSON 对象）'))
check('弹窗含「请求体」', dlg.includes('请求体'))
check('弹窗含「期望业务返回码」', dlg.includes('期望业务返回码'))
check('弹窗含保密提醒', dlg.includes('请勿外发') || dlg.includes('脱敏'))
const textareas = await ev(`document.querySelectorAll('dialog textarea, [role=dialog] textarea').length`)
check('请求头/请求体是多行输入框', textareas >= 2, `${textareas} 个`)

// ---------- 输入框：即时上色 + 自动排版 ----------
// 做法是高亮层（<pre>）+ 透明文字输入层（<textarea>）叠在一起。这个结构有个典型失败模式：
// 两层的字体/行高/内边距只要有一处不同，光标就会和看到的字错开 —— 比不高亮还难用。
// 所以这里不是「看看有没有颜色」，而是逐项比对两层的度量。
const area = JSON.parse(await ev(`(() => {
  const ta = document.querySelector('#api-body')
  if (!ta) return '{"missing":true}'
  const box = ta.parentElement
  const pre = box.querySelector('pre')
  if (!pre) return '{"noPre":true}'
  const a = getComputedStyle(ta), b = getComputedStyle(pre)
  const keys = ['fontFamily','fontSize','lineHeight','paddingTop','paddingLeft',
                'paddingRight','paddingBottom','letterSpacing','fontWeight','tabSize','boxSizing']
  const diff = keys.filter(k => a[k] !== b[k]).map(k => k + ': ' + a[k] + ' ≠ ' + b[k])
  const ra = ta.getBoundingClientRect(), rb = pre.getBoundingClientRect()
  return JSON.stringify({
    noPre: false, diff,
    dw: Math.abs(ra.width - rb.width), dh: Math.abs(ra.height - rb.height),
    dx: Math.abs(ra.left - rb.left), dy: Math.abs(ra.top - rb.top),
    color: a.color, caret: a.caretColor,
  })
})()`)) || {}
check('请求体输入框是「高亮层 + 输入层」双层结构', area.noPre === false, JSON.stringify(area))
check('两层的字体 / 行高 / 内边距完全一致（否则光标会和文字错开）',
  area.diff && area.diff.length === 0, (area.diff || []).join(' ; '))
check(`两层的外框完全重合（宽度差 ${area.dw}px、高度差 ${area.dh}px）`,
  area.dw <= 1 && area.dh <= 1 && area.dx <= 1 && area.dy <= 1, JSON.stringify(area))
check('输入层文字是透明的（由下面那层显示彩色文字）',
  /rgba?\([^)]*,\s*0\)/.test(area.color) || area.color === 'rgba(0, 0, 0, 0)', area.color)
check('光标颜色单独设过（透明文字下光标必须还看得见）', !!area.caret, area.caret)

// 打字即时上色：往输入框里敲一段 JSON，看是否出现多种颜色的片段
const colored = await ev(`(() => {
  const ta = document.querySelector('#api-body')
  if (!ta) return 'NO_INPUT'
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
  setter.call(ta, '{"userId":"10001","roomId":9001,"enable":true}')
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  return 'OK'
})()`)
check('能往请求体输入框里输入', colored === 'OK', colored)
await sleep(500)
const colorInfo = JSON.parse(await ev(`(() => {
  const ta = document.querySelector('#api-body')
  const pre = ta && ta.parentElement.querySelector('pre')
  if (!pre) return '{"spans":0}'
  const spans = [...pre.querySelectorAll('span')]
  return JSON.stringify({
    spans: spans.length,
    colors: [...new Set(spans.map(s => getComputedStyle(s).color))].length,
    text: pre.textContent.trimEnd(),
    height: Math.round(ta.getBoundingClientRect().height),
  })
})()`))
check('输入后立刻出现彩色片段（不用等失焦）', colorInfo.spans >= 6, JSON.stringify(colorInfo))
check(`键 / 字符串值 / 数字至少三种颜色（实测 ${colorInfo.colors} 种）`, colorInfo.colors >= 3,
  JSON.stringify(colorInfo))
check('高亮层内容与输入内容一致', colorInfo.text === '{"userId":"10001","roomId":9001,"enable":true}',
  colorInfo.text)

// 失焦自动排版：只在能解析成 JSON 时缩进，且不改变内容。
//
// 这一步有两个坑，缺一个这条断言就会「假通过」：
//   ① 程序化设值 + dispatchEvent('input') 不会让元素获得焦点，而元素没聚焦时
//      调 blur() 根本不触发 blur 事件 —— 必须先 focus() 再 blur()。
//   ② CDP 起的标签默认不是前台标签，此时 document.hasFocus() 为假，blur() 同样不生效
//      —— 先 Page.bringToFront 把页面提到前台。
// 实测（带诊断打点）：bringToFront 前 blurFired=0；之后 blurFired=1、focusoutFired=1。
await send('Page.bringToFront')
await sleep(200)
const blurred = await ev(`(() => {
  const ta = document.querySelector('#api-body')
  ta.focus()
  ta.blur()
  return ta.value
})()`)
await sleep(400)
const afterBlur = await ev(`document.querySelector('#api-body').value`)
check('离开输入框后自动缩进排版', afterBlur.includes('\n  "userId"'), JSON.stringify(afterBlur))
check('排版没有改动任何一个字（只动空白）',
  afterBlur.replace(/\s+/g, '') === blurred.replace(/\s+/g, ''),
  JSON.stringify(afterBlur))

// 不是合法 JSON 的模板不该被动：请求体里常写 {{times}}，那时 JSON.parse 一定失败
// 同样要先 focus()，否则后面的 blur() 不触发事件，这条断言会「假通过」
const setKept = await ev(`(() => {
  const ta = document.querySelector('#api-body')
  ta.focus()
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
  setter.call(ta, '{"timestamp":{{times}}}')
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  return 'OK'
})()`)
void setKept
await sleep(200)
await ev(`(() => { document.querySelector('#api-body').blur(); return 'OK' })()`)
await sleep(400)
check('不能解析成 JSON 时一个字都不动（{{times}} 模板保持原样）',
  (await ev(`document.querySelector('#api-body').value`)) === '{"timestamp":{{times}}}',
  JSON.stringify(await ev(`document.querySelector('#api-body').value`)))

// ---------- 变量名补全：打 {{ 弹出候选 ----------
// 这段要卡的三个点都不是「弹没弹」，而是弹对了没有：
//   ① 候选范围必须和后端替换范围一致（请求体里能用的名字和请求头不一样）；
//   ② 键盘上下键选完不能被紧跟着的 keyup 冲回第一条（否则选了等于没选）；
//   ③ 用鼠标点候选时输入框不能因为失焦而触发自动排版 —— 那会改写文本、把刚算好的光标位置作废。
console.log('\n--- 变量名补全 ---')
await send('Page.bringToFront')

/** 往输入框里填值并把光标摆到指定位置，再把「输入 / 光标移动」两件事都通知给组件。 */
const fill = (sel, proto, text, caret) => ev(`(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return 'NO_EL'
  const setter = Object.getOwnPropertyDescriptor(${proto}.prototype, 'value').set
  el.focus()
  setter.call(el, ${JSON.stringify(text)})
  el.dispatchEvent(new Event('input', { bubbles: true }))
  el.setSelectionRange(${caret}, ${caret})
  el.dispatchEvent(new Event('keyup', { bubbles: true }))
  return 'OK'
})()`)

/** 读候选浮层：条目名（去掉右侧分组标注）、分组、当前高亮第几条、是否在框下方。 */
const readMenu = (sel) => ev(`(() => {
  const lb = document.querySelector('[role=listbox][aria-label="变量名候选"]')
  if (!lb) return '{"found":false}'
  const opts = [...lb.querySelectorAll('[role=option]')]
  const host = document.querySelector(${JSON.stringify(sel)})
  if (!host) return '{"found":true,"noHost":true}'
  const r = lb.getBoundingClientRect(), h = host.getBoundingClientRect()
  return JSON.stringify({
    found: true,
    names: opts.map(o => o.querySelector('span')?.textContent.trim() ?? ''),
    groups: [...new Set(opts.map(o => o.querySelectorAll('span')[1]?.textContent.trim() ?? ''))],
    selected: opts.findIndex(o => o.getAttribute('aria-selected') === 'true'),
    options: opts.length,
    below: r.top > h.top,
    w: Math.round(r.width),
  })
})()`)

const key = (sel, k) => ev(`(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  el.dispatchEvent(new KeyboardEvent('keydown', { key: ${JSON.stringify(k)}, bubbles: true, cancelable: true }))
  return 'OK'
})()`)

// A. 请求体：候选里不该有 {{sign}}
check('能往请求体里打出 {{（回车前的准备）',
  (await fill('#api-body', 'HTMLTextAreaElement', '{"userId":"{{', 13)) === 'OK')
await sleep(300)
const mA = JSON.parse(await readMenu('#api-body'))
check('请求体：打 {{ 后浮出候选浮层', mA.found === true, JSON.stringify(mA))
check('请求体候选含四个内置名',
  ['{{times}}', '{{app_id}}', '{{mg_id}}', '{{nonce}}'].every((n) => (mA.names || []).includes(n)),
  JSON.stringify(mA.names))
check('请求体候选不含 {{sign}}（后端不在请求体里替换它）',
  !(mA.names || []).includes('{{sign}}'), JSON.stringify(mA.names))
check('候选右侧标注了分组', (mA.groups || []).includes('旧写法 · 兼容') && (mA.groups || []).every(Boolean), JSON.stringify(mA.groups))
check('浮层贴在光标那一行的下方', mA.below === true, JSON.stringify(mA))
check('浮层够宽，一行放得下一个名字加分组名', mA.w >= 200, String(mA.w))

// B. 键盘：↓ 之后高亮必须停在第二条
await key('#api-body', 'ArrowDown')
await sleep(250)
const mB = JSON.parse(await readMenu('#api-body'))
check('按 ↓ 高亮下移一条（没有被随后的 keyup 冲回第一条）', mB.selected === 1, JSON.stringify(mB))

await key('#api-body', 'Enter')
await sleep(300)
const vB = await ev(`document.querySelector('#api-body').value`)
check('回车插入高亮那一条，并补成完整占位符', vB === '{"userId":"{{app_id}}', JSON.stringify(vB))
check('插入后光标停在 }} 之后（能接着往下打）',
  (await ev(`document.querySelector('#api-body').selectionStart`)) === 21,
  String(await ev(`document.querySelector('#api-body').selectionStart`)))
check('插入后浮层自动收起',
  (await ev(`!!document.querySelector('[role=listbox][aria-label="变量名候选"]')`)) === false)

// C. 鼠标点候选：文本特意写成「合法 JSON」，这样一旦失焦触发自动排版就能看出来
await fill('#api-body', 'HTMLTextAreaElement', '{"a":"{{"}', 8)
await sleep(300)
const clickedOpt = await ev(`(() => {
  const opt = [...document.querySelectorAll('[role=listbox][aria-label="变量名候选"] [role=option]')]
    .find(o => o.textContent.indexOf('{{times}}') === 0)
  if (!opt) return 'NO_OPT'
  opt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
  return 'OK'
})()`)
check('能用鼠标点候选项', clickedOpt === 'OK', clickedOpt)
await sleep(300)
const vC = await ev(`document.querySelector('#api-body').value`)
check('鼠标点候选插入结果正确', vC === '{"a":"{{times}}"}', JSON.stringify(vC))
check('点候选没有触发失焦自动排版（文本没被改写成多行）', !vC.includes('\n'), JSON.stringify(vC))
check('鼠标点候选后光标仍在 }} 之后',
  (await ev(`document.querySelector('#api-body').selectionStart`)) === 15,
  String(await ev(`document.querySelector('#api-body').selectionStart`)))

// D. 请求头：候选里必须有 {{sign}}
await fill('#api-headers', 'HTMLTextAreaElement', '{"X-Room":"{{', 13)
await sleep(300)
const mD = JSON.parse(await readMenu('#api-headers'))
check('请求头：打 {{ 后也弹候选', mD.found === true, JSON.stringify(mD))
check('请求头候选含 {{sign}}（后端在地址与请求头里替换它）',
  (mD.names || []).includes('{{sign}}'), JSON.stringify(mD.names))
check('请求头候选含其余四个内置名',
  ['{{times}}', '{{app_id}}', '{{mg_id}}', '{{nonce}}'].every((n) => (mD.names || []).includes(n)),
  JSON.stringify(mD.names))

// E. 地址框是单行输入框，走的是另一条实现（浮层不量光标，直接贴在框下方）
await fill('#api-url', 'HTMLInputElement', 'http://127.0.0.1:18888/ok?t={{', 30)
await sleep(300)
const mE = JSON.parse(await readMenu('#api-url'))
check('地址框（单行）打 {{ 也会弹候选', mE.found === true, JSON.stringify(mE))
check('地址框候选按请求头那一组给（含 {{sign}}）',
  (mE.names || []).includes('{{sign}}'), JSON.stringify(mE.names))
check('地址框的浮层贴在框下方', mE.below === true, JSON.stringify(mE))
await key('#api-url', 'Enter')
await sleep(300)
const vE = await ev(`document.querySelector('#api-url').value`)
check('地址框能插入占位符', vE === 'http://127.0.0.1:18888/ok?t={{times}}', JSON.stringify(vE))

// E2. 地址栏里的变量引用要「另一种颜色」（需求 #54）。
//
// 这一刻是天然的对照组：地址栏和请求体里**同时**有 {{times}}。
// 所以这一节要钉的不是「有个颜色」，而是三件事：
//   1. 地址栏里它确实和旁边的普通文字**不是一个颜色**（不然等于没做）；
//   2. 两个字段里它是**同一个颜色**（需求的原话是「用其它颜色代替」，
//      而真实痛点是「同一个弹窗里两种表达」—— 只做一半就白做）；
//   3. 高亮层的排版与输入框**逐项对齐**（字体、内边距、边框内侧偏移），
//      以及输入框自己那层字是透明的 —— 不对齐会出现「光标与看到的字错开」，
//      不透明则会两层字叠在一起变重影。
//
// 取「高亮层」的方式对两种控件通用，不依赖具体标签：控件旁边那个 aria-hidden 的装饰层。
const HL_PROBE = (id) => `(() => {
  const el = document.querySelector(${JSON.stringify(id)})
  const wrap = el && el.parentElement
  if (!wrap) return 'null'
  const cand = [...wrap.children].filter(c => c !== el
    && c.getAttribute('aria-hidden') === 'true' && c.getAttribute('role') !== 'listbox')
  const layer = cand.sort((a, b) => (b.textContent || '').length - (a.textContent || '').length)[0]
  if (!layer) return 'null'
  const spans = [...layer.querySelectorAll('span')]
  const isPh = (s) => { const t = s.textContent; return t.startsWith('{{') && t.endsWith('}}') }
  const ph = spans.find(isPh)
  const plain = spans.find(s => !isPh(s) && s.textContent.trim() !== '')
  const cs = (x) => getComputedStyle(x)
  const lr = layer.getBoundingClientRect(), er = el.getBoundingClientRect()
  return JSON.stringify({
    value: el.value,
    layerText: layer.textContent,
    phText: ph ? ph.textContent : null,
    phColor: ph ? cs(ph).color : null,
    plainColor: plain ? cs(plain).color : null,
    inputColor: cs(el).color,
    // 输入框自己那层底色必须是全透明：不透明的话它压在高亮层上面，高亮层就白做了
    // —— 这一条是「高亮层真的看得见」的唯一可编程证据，计算色再对也挡不住被盖住。
    inputBg: cs(el).backgroundColor,
    fontSame: cs(layer).fontFamily === cs(el).fontFamily
      && cs(layer).fontSize === cs(el).fontSize
      && cs(layer).fontWeight === cs(el).fontWeight,
    padSame: cs(layer).paddingLeft === cs(el).paddingLeft
      && cs(layer).paddingTop === cs(el).paddingTop,
    // 高亮层要正好落在输入框边框**内侧**，差的这一点就是边框宽度
    insetDelta: Math.round((lr.left - er.left) * 100) / 100,
    borderPx: parseFloat(cs(el).borderLeftWidth) || 0,
    // 层与输入框的盒子差，应当正好是上下（左右）各一条边框
    boxDeltaH: Math.round((er.height - lr.height) * 100) / 100,
    boxDeltaW: Math.round((er.width - lr.width) * 100) / 100,
  })
})()`

const urlHl = JSON.parse(await ev(HL_PROBE('#api-url'))) || {}
const bodyHl = JSON.parse(await ev(HL_PROBE('#api-body'))) || {}
check('地址栏里能认出变量引用那一段', urlHl.phText === '{{times}}', JSON.stringify(urlHl))
check('地址栏的变量引用和旁边的普通文字不是一个颜色',
  !!urlHl.phColor && !!urlHl.plainColor && urlHl.phColor !== urlHl.plainColor,
  `变量=${urlHl.phColor} 普通=${urlHl.plainColor}`)
check('地址栏与请求体里的变量引用是同一个颜色（同一个弹窗不能两种表达）',
  !!urlHl.phColor && urlHl.phColor === bodyHl.phColor,
  `地址栏=${urlHl.phColor} 请求体=${bodyHl.phColor}`)
// 先卡「层真的存在、真的有字」再比 —— 不卡的话两边都是 undefined，
// `undefined === undefined` 会判成通过，这条就变成了一句空转（本轮红跑实测到过）。
check('高亮层的文字与输入框的值逐字一致（没漏字、没重复）',
  typeof urlHl.layerText === 'string' && urlHl.layerText.length > 0
  && urlHl.layerText === urlHl.value,
  JSON.stringify({ layer: urlHl.layerText, value: urlHl.value }))
check('高亮层与输入框的字体一致（不一致就会出现光标与文字错开）',
  urlHl.fontSame === true, JSON.stringify(urlHl))
check('高亮层与输入框的内边距一致', urlHl.padSame === true, JSON.stringify(urlHl))
check('高亮层落在输入框边框内侧（偏移量正好等于边框宽度）',
  urlHl.insetDelta === urlHl.borderPx && urlHl.borderPx > 0, JSON.stringify(urlHl))
check('高亮层的盒子正好比输入框小上下两条边框（高一层就说明垂直居中被推偏了）',
  urlHl.boxDeltaH === urlHl.borderPx * 2 && urlHl.boxDeltaW === urlHl.borderPx * 2,
  JSON.stringify(urlHl))
check('输入框自己那层字是透明的（否则会和下面的高亮层叠成重影）',
  /^rgba\(0, 0, 0, 0\)$/.test(urlHl.inputColor || ''), urlHl.inputColor)
check('输入框自己那层底色是全透明（否则它压在高亮层上面，高亮层等于白做）',
  /^rgba\(0, 0, 0, 0\)$/.test(urlHl.inputBg || ''), urlHl.inputBg)

// E3. 明暗两套配色都要对。
//
// 这个项目默认就是深色（frontend/src/lib/theme.tsx 里 `return 'dark'`），
// 上面那一组等于全在深色下验的；而需求 #54 是**纯配色**的功能，浅色下配色错（比如和背景糊在一起）
// 同样是不能用的。右上角有切换按钮（button[title="切换到浅色"]），先试试点它；
// 点不动（弹窗开着，按钮在焦点陷阱外面）就直接改 <html> 上的类兜底 ——
// 这个项目的深色就是类驱动的（index.css: `@custom-variant dark (&:is(.dark *))`），
// 配色结果与真按按钮一致。两条路都走通了才算真的验过浅色。
const themeFlip = await ev(`(() => {
  const b = document.querySelector('button[title="切换到浅色"]')
  if (b) b.click()
  if (!document.documentElement.classList.contains('dark')) return 'CLICK'
  document.documentElement.classList.remove('dark')
  return 'CLASS'
})()`)
await sleep(400)
const urlHlLight = JSON.parse(await ev(HL_PROBE('#api-url'))) || {}
const bodyHlLight = JSON.parse(await ev(HL_PROBE('#api-body'))) || {}
check('切到浅色这个动作确实生效了（否则下面两条是在重复验深色）',
  !!urlHlLight.phColor && urlHlLight.phColor !== urlHl.phColor,
  `切换方式=${themeFlip} 深色=${urlHl.phColor} 浅色=${urlHlLight.phColor}`)
check('浅色下：地址栏的变量引用仍然与旁边的普通文字不是一个颜色',
  !!urlHlLight.phColor && !!urlHlLight.plainColor
  && urlHlLight.phColor !== urlHlLight.plainColor,
  `变量=${urlHlLight.phColor} 普通=${urlHlLight.plainColor}`)
check('浅色下：地址栏与请求体里的变量引用仍然是同一个颜色',
  !!urlHlLight.phColor && urlHlLight.phColor === bodyHlLight.phColor,
  `地址栏=${urlHlLight.phColor} 请求体=${bodyHlLight.phColor}`)
// 复原：点回去（真按了按钮的那条路）或把类加回去（退路），两条路都兜住
await ev(`(() => {
  const b = document.querySelector('button[title="切换到深色"]')
  if (b) b.click()
  document.documentElement.classList.add('dark')
  return 'OK'
})()`)
await sleep(400)
check('验完浅色已切回深色（后面的断言不该被主题残留影响）',
  (await ev(`document.documentElement.classList.contains('dark')`)) === true)

// F. 变量写完了就不该再弹（否则每打一次右括号都得按 Esc 关掉）
await fill('#api-body', 'HTMLTextAreaElement', '{"a":"{{times}}"}', 17)
await sleep(300)
const mF = JSON.parse(await readMenu('#api-body'))
check('变量写完之后不再弹候选', mF.found === false, JSON.stringify(mF))

// 收拾干净，免得影响后面按布局做的断言
await ev(`(() => {
  const set = (sel, proto, v) => {
    const el = document.querySelector(sel)
    if (!el) return
    Object.getOwnPropertyDescriptor(proto.prototype, 'value').set.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }
  set('#api-body', HTMLTextAreaElement, '')
  set('#api-headers', HTMLTextAreaElement, '')
  set('#api-url', HTMLInputElement, '')
  document.querySelector('#api-body').blur()
  return 'OK'
})()`)
await sleep(300)

// 弹窗的宽度类必须真的生效。
// 踩坑：dialog 组件自带 `sm:max-w-sm`，调用方只写 `max-w-2xl` 会被它盖掉
// ——同组类只有"变体相同"才会互相覆盖，响应式变体在产物 CSS 里排在后面，
// 结果弹窗实际只有 384px 宽，字段全被挤成竖排。这里卡宽度就是为了防这个回归。
//
// 2026-10-04：新建用例弹窗现在是从「用例管理」弹窗里打开的，页面上有两层 dialog，
// 而 `querySelector('[role=dialog]')` 拿到的是最外层那个。改成认输入框所在的这一层。
const box = JSON.parse(await ev(`(() => {
  const anchor = document.querySelector('#api-body')
  const d = anchor ? anchor.closest('[role=dialog]') : document.querySelector('[role=dialog]')
  if (!d) return 'null'
  const r = d.getBoundingClientRect()
  const labels = [...d.querySelectorAll('label')]
    .filter(l => l.textContent.trim() && !l.querySelector('input[type=checkbox]'))
  return JSON.stringify({
    w: Math.round(r.width), h: Math.round(r.height),
    scrollH: d.scrollHeight, clientH: d.clientHeight, vh: window.innerHeight, overflowY: getComputedStyle(d).overflowY,
    // 「字段排布够紧凑」只数那几个**定长**标量字段占了几行。
    // 2026-10-05 加了「响应体断言」那一块之后，它的高度随断言行数变化，
    // 混进来会把这条断言变成「断言条数的函数」—— 那就不再是在验版式了。
    rows: [...new Set(labels
      .filter(l => ${JSON.stringify(['用例名称', '请求头', '请求体', '分组',
        '期望 HTTP 状态码', '期望业务返回码', '超时', '备注'])}
        .some(t => l.textContent.includes(t)))
      .map(l => Math.round(l.getBoundingClientRect().top)))].length,
  })
})()`)) || {}
check(`用例弹窗够宽（实测 ${box.w}px，需 ≥ 800）`, box.w >= 800, `宽 ${box.w}`)
const editorSaveReachable = JSON.parse(await ev(`(() => {
  const d = document.querySelector('#api-body')?.closest('[role=dialog]')
  const save = d && [...d.querySelectorAll('button')].find(b => b.textContent.trim() === '保存')
  if (!save) return '{"visible":false}'
  save.scrollIntoView({ block: 'end' })
  const r = save.getBoundingClientRect()
  return JSON.stringify({visible:r.top >= 0 && r.bottom <= window.innerHeight,
    hit:save.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2))})
})()`)) || {}
check(`用例表单内部滚动且保存按钮可见可点（内容 ${box.scrollH} / 可视 ${box.clientH}）`,
  /auto|scroll/.test(box.overflowY || '') && box.h <= box.vh * .9 + 2
  && editorSaveReachable.visible && editorSaveReachable.hit, JSON.stringify(editorSaveReachable))
check(`字段排布够紧凑（${box.rows} 行，需 ≤ 6 行）`, box.rows <= 6)

// 关掉弹窗
await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '取消')
  if (b) b.click(); return 'OK'
})()`)

// 环境设置弹窗：三组变量 + 签名，整合成一个入口（原来只有一个「签名设置」）。
// 入口本身在「用例管理」弹窗里，且已从按钮改成小卡片（需求 #50）。
// 注意这里只开来看、只在输入框里打字，**不点保存** —— 这个脚本可能对着正式实例跑。
const envOpened = await clickCard('环境设置', 'dialog')
check('「环境设置」入口卡片存在且可点（已从按钮改成小卡片）', envOpened === 'OK', envOpened)
await sleep(1000)

const ed = await ev('document.body.innerText')
check('弹窗含「变量」「签名」两个分段', ed.includes('变量') && ed.includes('签名'))
check('变量分段默认打开，含三张表（环境基址 / 地址与请求头 / 请求体）',
  ed.includes('环境基址') && ed.includes('地址与请求头变量') && ed.includes('请求体变量'))
check('点明了基址只在地址里生效（需求 #38）', ed.includes('只在地址里生效'))
check('点明了请求体那组只在请求体里生效', ed.includes('只在请求体里生效'))
// 需求 #40：内置变量清单和自定义变量放在同一个视图里 —— 它们是同一层的东西
check('变量分段里列了内置变量清单（需求 #40）',
  ed.includes('内置变量') && ed.includes('{{times}}') && ed.includes('{{sign}}'))
check('内置清单写明了 {{sign}} 不在请求体里替换', ed.includes('仅地址与请求头'))
const addBtns = await ev(`[...document.querySelectorAll('[role=dialog] button')]
  .filter(b => b.textContent.trim() === '添加').length`)
check('三张表各有一个「添加」按钮', addBtns >= 3, `${addBtns} 个`)

// 切到签名分段
await ev(`(() => {
  const b = [...document.querySelectorAll('[role=dialog] button')]
    .find(e => e.textContent.trim() === '签名')
  if (b) b.click()
  return 'OK'
})()`)
await sleep(400)
const sd = await ev('document.body.innerText')
check('签名分段含「发送前自动签名」开关', sd.includes('发送前自动签名'))
check('弹窗含应用 id 字段', sd.includes('应用 id（app_id）'))
check('弹窗含密钥字段', sd.includes('应用密钥（app_secret）'))
check('弹窗含随机串字段', sd.includes('随机串（nonce）'))
check('弹窗说明了 {{sign}} 的替换范围（最容易接错的一条）', sd.includes('{{sign}}'))
check('弹窗点明了密钥默认遮住（需求 #39）', sd.includes('密钥默认遮住'))

// 密钥是这一页唯一不该被截图带走的字段：默认必须是密码框
const pwFields = await ev(`document.querySelectorAll('[role=dialog] input[type=password]').length`)
check('密钥默认用密码框输入（需求 #39）', pwFields >= 1, `${pwFields} 个`)

// 需求 #39：密钥框右侧要有「显示」按钮，而且**打开时不能已经把明文填进去**
const reveal = JSON.parse(await ev(`(() => {
  const inp = document.querySelector('#api-sign-secret')
  const btn = [...document.querySelectorAll('[role=dialog] button')]
    .find(e => (e.getAttribute('title') || '').includes('显示已保存的密钥明文'))
  return JSON.stringify({
    btn: !!btn,
    type: inp ? inp.getAttribute('type') : null,
    value: inp ? inp.value : null,
  })
})()`)) || {}
check('密钥框右侧有「显示」按钮（需求 #39）', reveal.btn === true)
check('打开时密钥是遮住的（type=password 且输入框为空）',
  reveal.type === 'password' && (reveal.value || '') === '', JSON.stringify(reveal))

// 2026-10-04：环境设置现在是从「用例管理」弹窗里打开的，页面上有两层 dialog，
// `querySelector('[role=dialog]')` 拿到的是外层。改成认「发送前自动签名」所在的那一层。
const sbox = JSON.parse(await ev(`(() => {
  const d = [...document.querySelectorAll('[role=dialog]')]
    .find(x => x.innerText.includes('发送前自动签名'))
  if (!d) return 'null'
  const r = d.getBoundingClientRect()
  return JSON.stringify({
    w: Math.round(r.width), h: Math.round(r.height),
    scrollH: d.scrollHeight, clientH: d.clientHeight,
  })
})()`)) || {}
check(`环境设置弹窗够宽（实测 ${sbox.w}px，需 ≥ 700）`, sbox.w >= 700, `宽 ${sbox.w}`)

// ---------- mgtoapp：测试点管理已收进页内弹窗（需求 #45 / #46） ----------
// 原来它是侧边栏上一个独立页，走查时改个 state 得切页面再切回来。
console.log('\n--- mgtoapp 测试点管理 ---')
await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '取消')
  if (b) b.click(); return 'OK'
})()`)
await sleep(300)
await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '关闭')
  if (b) b.click(); return 'OK'
})()`)
await sleep(500)

const navNoManage = await ev(`(() => {
  const inSidebar = [...document.querySelectorAll('a, button, [role=menuitem]')]
    .some(e => e.textContent.trim() === '测试点管理')
  return JSON.stringify({ inSidebar })
})()`)
check('侧边栏不再有独立的「测试点管理」入口（需求 #45）',
  JSON.parse(navNoManage || '{}').inSidebar === false, navNoManage)

// 2026-10-14（需求 #89）：mgtoapp 已折叠进「上报校验」，侧边栏不再有 mgtoapp 这一项。
// 改为两步：先点侧边栏「上报校验」，再点页面上的卡片。
await ev(`(() => {
  const b = [...document.querySelectorAll('[data-slot="sidebar-menu-button"]')]
    .find(e => e.textContent.trim() === '上报校验')
  if (b) b.click(); return 'OK'
})()`)
await sleep(700)
await ev(`(() => {
  const card = [...document.querySelectorAll('[role=button]')]
    .find(e => (e.getAttribute('aria-label') || '').startsWith('mgtoapp'))
  if (card) card.click(); return card ? 'OK' : 'NO_CARD'
})()`)
await sleep(1500)

const mgOpen = await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(e => e.textContent.includes('测试点管理'))
  if (!b) return 'NOT_FOUND'
  b.click(); return 'OK'
})()`)
check('mgtoapp 页里有「测试点管理」小卡片按钮（需求 #45）', mgOpen === 'OK', mgOpen)
await sleep(1200)

const mg = JSON.parse(await ev(`(() => {
  const d = [...document.querySelectorAll('[role=dialog]')]
    .find(x => x.innerText.includes('测试点管理'))
  if (!d) return '{"found":false}'
  const r = d.getBoundingClientRect()
  const btns = [...d.querySelectorAll('button')].map(b => b.textContent.trim())
  return JSON.stringify({
    found: true,
    w: Math.round(r.width),
    hasGame: d.innerText.includes('游戏态'),
    hasPlayer: d.innerText.includes('玩家态'),
    hasStateCol: d.innerText.includes('state（过滤关键字）'),
    saveBtns: btns.filter(t => t === '保存' || t === '新增').length,
    // 行内二次确认是「点删除之后才出现」的，刚打开时不该有
    confirmBtns: btns.filter(t => t === '确定删').length,
  })
})()`)) || {}
check('「测试点管理」弹窗打开了', mg.found === true)
check(`弹窗与「用例管理」同规格（实测 ${mg.w}px，需 ≥ 1100）`, mg.w >= 1100, `宽 ${mg.w}`)
check('弹窗里有游戏态与玩家态两张表', mg.hasGame && mg.hasPlayer)
check('表头是 state（过滤关键字）', mg.hasStateCol === true)
check('每行有显式「保存 / 新增」按钮（需求 #46，不再是失焦即存）', mg.saveBtns > 0, `${mg.saveBtns} 个`)
check('删除确认是行内的，不是一打开就摆着', mg.confirmBtns === 0, `${mg.confirmBtns} 个`)

// ---------- 执行页的「已选用例」（需求 #49）----------
// 只显示名称：这一行的用途是核对「我要跑的是哪几条」，方法/地址在执行前都不重要。
// 名称可点掉，因为「看了一遍发现其中一两条不想跑」是常态。
console.log('\n--- 执行页已选用例 ---')
await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '关闭')
  if (b) b.click(); return 'OK'
})()`)
await sleep(600)

// 回到接口测试页（前面为了看 mgtoapp 弹窗切走过）
await ev(`(() => {
  const b = [...document.querySelectorAll('a, button, [role=menuitem]')]
    .find(e => e.textContent.trim().startsWith('接口测试'))
  if (b) b.click(); return 'OK'
})()`)
await sleep(1200)

const emptySel = JSON.parse(await ev(`(() => {
  const card = document.querySelector('[data-testid="api-run-plan"]')
  return JSON.stringify({
    found: !!card,
    // 一条都没选时必须是提示，而不是一片空白
    hint: card ? card.innerText.includes('在用例管理中勾选请求') : false,
    title: card ? card.innerText.includes('执行顺序') : false,
  })
})()`)) || {}
check('执行卡片里有「执行顺序」区块（需求 #49）', emptySel.found === true && emptySel.title === true)
check('没选时给的是可读提示，不是空白', emptySel.hint === true)

// 选中一条用例，看名称是否出现在执行区
// 打开用例管理：入口卡片在主页面（弹窗此时是关着的），直接按文字点卡片
const pickedName = await clickCard('用例管理')
await sleep(900)
await ev(`(() => {
  const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
  const row = d && [...d.querySelectorAll('div')].find(x =>
    typeof x.className === 'string' && x.className.split(' ').includes('hover:scale-[1.03]'))
  if (row) row.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  return 'OK'
})()`)
await sleep(500)
const rowName = await ev(`(() => {
  const d = [...document.querySelectorAll('[role=dialog]')].find(x => x.innerText.includes('用例管理'))
  const row = d && [...d.querySelectorAll('div')].find(x =>
    typeof x.className === 'string' && x.className.split(' ').includes('hover:scale-[1.03]'))
  if (!row) return ''
  const sp = row.querySelector('span.truncate')
  return sp ? sp.textContent.trim() : ''
})()`)
await ev(`(() => {
  const b = [...document.querySelectorAll('[role=dialog] button')].find(e => e.textContent.trim() === '关闭')
  if (b) b.click(); return 'OK'
})()`)
await sleep(700)

const sel = JSON.parse(await ev(`(() => {
  const card = document.querySelector('[data-testid="api-run-plan"]')
  if (!card) return '{"found":false}'
  const chips = [...card.querySelectorAll('button[aria-label^="取消用例 "]')].map(b => b.parentElement)
  return JSON.stringify({
    found: true,
    chips: chips.length,
    names: chips.map(c => c.querySelector('span.truncate')?.textContent.trim() ?? ''),
    hasRemoveBtn: chips.length > 0 && !!chips[0].querySelector('button'),
    count: (card.innerText.match(/执行顺序\\s*(\\d+)\\s*条/) || [])[1] || '',
  })
})()`)) || {}
check('选中后执行区出现该用例名称', sel.chips >= 1, JSON.stringify(sel))
check('显示的就是用例名称（与实际行一致）',
  (sel.names || []).some(n => n && n === rowName), `期望含「${rowName}」，实际 ${JSON.stringify(sel.names)}`)
check('顺序项带 × 可点掉（需求 #49）', sel.hasRemoveBtn === true)
check('计数与顺序项数一致', String(sel.chips) === String(sel.count), `标签 ${sel.chips} / 计数 ${sel.count}`)

// 点 × 去掉
await ev(`(() => {
  const card = document.querySelector('[data-testid="api-run-plan"]')
  const b = card && card.querySelector('button[aria-label^="取消用例 "]')
  if (b) b.click(); return 'OK'
})()`)
await sleep(600)
const afterRemove = JSON.parse(await ev(`(() => {
  const card = document.querySelector('[data-testid="api-run-plan"]')
  return JSON.stringify({
    chips: card ? card.querySelectorAll('button[aria-label^="取消用例 "]').length : -1,
    hint: card ? card.innerText.includes('在用例管理中勾选请求') : false,
  })
})()`)) || {}
check('点 × 能把它从选中里去掉', afterRemove.chips === 0, `剩余标签 ${afterRemove.chips}`)
check('去掉后回到可读的用例选择提示', afterRemove.hint === true)

// ---------- 响应体断言（需求 #51）----------
// run-ui.sh 已经播了一条带 **3 条必然失败** 断言的用例（打的是本实例自己的 /api/meta）：
// 三条一起失败才会出现多行原因，而「多行有没有真的换行」只有多行时才验得出来
// —— 单条失败只有一行，那时「样式生效」和「样式没生效」看起来一模一样。
console.log('\n--- 响应体断言 ---')

/**
 * 用真实鼠标事件点一个元素。
 *
 * Radix 的下拉只认指针事件：合成一个 click() 是打不开的（它监听的是 pointerdown），
 * 而打不开的表现是「下拉里没有选项」，很容易被误读成「下拉是空的、功能没做」。
 */
const realClick = async (selExpr) => {
  const info = await ev(`(() => {
    const el = ${selExpr}
    if (!el) return null
    el.scrollIntoView({ block: 'center' })
    const r = el.getBoundingClientRect()
    return JSON.stringify({
      x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2),
    })
  })()`)
  if (!info) return 'NO_ELEMENT'
  const p = JSON.parse(info)
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y })
  await send('Input.dispatchMouseEvent', {
    type: 'mousePressed', x: p.x, y: p.y, button: 'left', clickCount: 1,
  })
  await send('Input.dispatchMouseEvent', {
    type: 'mouseReleased', x: p.x, y: p.y, button: 'left', clickCount: 1,
  })
  return 'OK'
}

// 两个弹窗的查找表达式（会叠着开，必须按内容区分）
const CASE_DLG = `[...document.querySelectorAll('[role=dialog]')]
  .find(x => x.innerText.includes('用例管理'))`
const EDIT_DLG = `[...document.querySelectorAll('[role=dialog]')]
  .find(x => x.innerText.includes('用例名称'))`
/** 找到「界面断言用·响应体断言」那一行（用例卡片）。 */
const ASSERT_ROW = `[...(${CASE_DLG}).querySelectorAll('div')].find(x =>
  typeof x.className === 'string'
  && x.className.split(' ').includes('hover:scale-[1.03]')
  && x.innerText.includes('界面断言用·响应体断言'))`

const openMgr = await clickCard('用例管理')
await sleep(1000)

const badge = JSON.parse(await ev(`(() => {
  const row = ${ASSERT_ROW}
  return JSON.stringify({
    found: !!row,
    badge: row ? (/响应体\\s*(\\d+)/.exec(row.innerText) || [])[1] : null,
  })
})()`)) || {}
check('「用例管理」弹窗能再打开', openMgr === 'OK', openMgr)
check('带断言的用例在列表里', badge.found === true, JSON.stringify(badge))
check('列表上标出带了几条响应体断言（不点进去就看得到）', badge.badge === '3',
  `实测 ${badge.badge}`)

// 打开编辑框：库里存的断言要能读回成断言行
const editClick = await ev(`(() => {
  const row = ${ASSERT_ROW}
  const b = row && row.querySelector('button[title="编辑"]')
  if (!b) return 'NO_BTN'
  b.click(); return 'OK'
})()`)
check('能打开这条用例的编辑框', editClick === 'OK', editClick)
await sleep(1000)

const edAsserts = JSON.parse(await ev(`(() => {
  const d = ${EDIT_DLG}
  if (!d) return '{"found":false}'
  const trigs = [...d.querySelectorAll('select[aria-label^="断言条件 "]')]
  const rows = trigs.map(t => t.closest('div.grid'))
  return JSON.stringify({
    found: true,
    label: [...d.querySelectorAll('label')].some(l => l.textContent.trim() === '断言'),
    rows: trigs.length,
    ops: trigs.map(t => t.selectedOptions[0]?.textContent.trim() ?? ''),
    paths: rows.map(r => {
      const i = r.querySelector('input[aria-label^="断言路径 "]')
      return i ? i.value : ''
    }),
    expectCells: rows.map(r => {
      const sel = r.querySelector('select[aria-label^="期望类型 "]')
      if (sel) return 'select:' + sel.value
      const inp = r.querySelector('input[aria-label^="断言期望 "]')
      if (!inp) return ''
      return inp.disabled ? 'disabled' : 'input:' + inp.value
    }),
    delBtns: d.querySelectorAll('button[aria-label^="删除断言 "]').length,
    addBtn: [...d.querySelectorAll('button')].some(b => b.textContent.includes('添加断言')),
  })
})()`)) || {}
check('编辑框里有「响应断言」区块',
  edAsserts.found === true && edAsserts.label === true, JSON.stringify(edAsserts))
check('库里存的 3 条断言读回成了 3 行', edAsserts.rows === 3, `${edAsserts.rows} 行`)
check('路径按原样回填', JSON.stringify(edAsserts.paths) ===
  JSON.stringify(['version', 'name', 'version']), JSON.stringify(edAsserts.paths))
check('条件显示成中文（库里存的是 equals / type）',
  JSON.stringify(edAsserts.ops) === JSON.stringify(['等于', '等于', '是某类型']),
  JSON.stringify(edAsserts.ops))
check('「等于」那两行的期望值是文本框，回填成原值',
  edAsserts.expectCells[0] === 'input:9.9.9' && edAsserts.expectCells[1] === 'input:Nope',
  JSON.stringify(edAsserts.expectCells))
check('「是某类型」那一行的期望值变成了类型下拉（连值都不用打）',
  edAsserts.expectCells[2] === 'select:integer', JSON.stringify(edAsserts.expectCells))
check('每行都有删除按钮', edAsserts.delBtns === 3, `${edAsserts.delBtns} 个`)
check('有「添加断言」按钮', edAsserts.addBtn === true)

// 原生下拉的弹出菜单由浏览器绘制，不存在Radix的select-content节点。
// 保留原判据的目的：条件齐全、分组清楚、控件可见可点击，列表末项可用键盘到达。
async function nativeSelectProbe(selector) {
  const expression = `(${EDIT_DLG}).querySelector(${JSON.stringify(selector)})`
  const before = JSON.parse(await ev(`(() => {
    const el = ${expression}; if (!el) return '{"found":false}'
    el.scrollIntoView({block:'center'}); el.focus()
    const r=el.getBoundingClientRect()
    return JSON.stringify({found:el instanceof HTMLSelectElement,old:el.value,
      options:[...el.options].filter(o=>o.value!=='').map(o=>({value:o.value,label:o.textContent.trim()})),
      groups:[...el.querySelectorAll('optgroup')].map(g=>g.label),
      visible:r.height>0&&r.top>=0&&r.bottom<=window.innerHeight,
      hit:el.contains(document.elementFromPoint(r.left+r.width/2,r.top+r.height/2))})
  })()`)) || {}
  if (!before.found) return before
  const opened = await realClick(expression)
  await send('Input.dispatchKeyEvent', {type:'keyDown',key:'End',code:'End',windowsVirtualKeyCode:35})
  await send('Input.dispatchKeyEvent', {type:'keyUp',key:'End',code:'End',windowsVirtualKeyCode:35})
  await send('Input.dispatchKeyEvent', {type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13})
  await send('Input.dispatchKeyEvent', {type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13})
  await sleep(180)
  let selected = await ev(`(${expression})?.value`)
  // macOS 原生弹层不消费CDP的End导航；关闭弹层后用真实字符键做类型搜索。
  // 仍要求浏览器实际选中末项，不能用value setter替代这个判据。
  if (selected !== before.options.at(-1)?.value) {
    await ev(`(${expression})?.focus()`)
    for (const character of before.options.at(-1)?.label ?? '') {
      await send('Input.dispatchKeyEvent', {type:'keyDown',key:character,text:character,windowsVirtualKeyCode:0})
      await send('Input.dispatchKeyEvent', {type:'keyUp',key:character,windowsVirtualKeyCode:0})
    }
    await sleep(180)
    selected = await ev(`(${expression})?.value`)
  }
  await ev(`(() => {const el=${expression};if(el){el.value=${JSON.stringify(before.old)};el.dispatchEvent(new Event('change',{bubbles:true}))}})()`)
  await sleep(120)
  return {...before,opened,keyboardLast:selected===before.options.at(-1)?.value,selected}
}
const opFit = await nativeSelectProbe('select[aria-label^="断言条件 "]')
const opOpts = (opFit.options ?? []).map(o => o.label)
check('判断条件能打开原生下拉且含14个条件', opFit.opened === 'OK' && opOpts.length === 14,
  JSON.stringify(opFit))
const WANT_OPS = ['存在', '不存在', '是某类型', '等于', '不等于', '包含', '正则匹配',
  '大于', '大于等于', '小于', '小于等于', '长度等于', '属于集合', '符合 Schema']
check('下拉保留原13个中文条件并新增Schema条件',
  JSON.stringify(opOpts) === JSON.stringify(WANT_OPS), JSON.stringify(opOpts))
check('Schema条件存储值为schema', (opFit.options ?? []).some(o => o.value === 'schema' && o.label === '符合 Schema'), JSON.stringify(opFit.options))
check('条件按8种用途分组显示', JSON.stringify(opFit.groups) === JSON.stringify(
  ['存在性', '类型', '相等', '文本', '数值', '长度', '集合', '结构']), JSON.stringify(opFit.groups))
check('条件下拉在视口内可点击，末项可通过键盘到达（浏览器原生弹层）',
  opFit.visible && opFit.hit && opFit.keyboardLast, JSON.stringify(opFit))
const sources = await ev(`(()=>{const d=${EDIT_DLG};return [...d.querySelectorAll('select[aria-label^="断言来源 "]')].map(s=>[...s.options].map(o=>o.value))})()`)
check('每行能选择响应JSON、响应头和响应耗时三种来源', sources.length===3 && sources.every(options=>JSON.stringify(options)===JSON.stringify(['BODY','HEADER','ELAPSED'])), JSON.stringify(sources))
const typeFit = await nativeSelectProbe('select[aria-label^="期望类型 "]')
const typeOpts = (typeFit.options ?? []).map(o=>o.label)
check('类型是下拉选项（7种，含integer）', typeFit.opened === 'OK' && JSON.stringify(typeOpts) === JSON.stringify(
  ['string','number','integer','boolean','object','array','null']), JSON.stringify(typeFit))
check('类型控件在视口内可点击，最后一种类型可通过键盘到达',
  typeFit.visible && typeFit.hit && typeFit.keyboardLast, JSON.stringify(typeFit))

// 增删一行；删空后要回到可读的空态，而不是留一个空框
await ev(`(() => {
  const d = ${EDIT_DLG}
  const b = [...d.querySelectorAll('button')].find(x => x.textContent.includes('添加断言'))
  if (b) b.click(); return 'OK'
})()`)
await sleep(400)
const afterAdd = await ev(`(${EDIT_DLG}).querySelectorAll('select[aria-label^="断言条件 "]').length`)
check('能加一条断言行', afterAdd === 4, `${afterAdd} 行`)
await ev(`(() => {
  const all = [...(${EDIT_DLG}).querySelectorAll('button[aria-label^="删除断言 "]')]
  if (all.length) all[all.length - 1].click()
  return 'OK'
})()`)
await sleep(400)
const afterDel = await ev(`(${EDIT_DLG}).querySelectorAll('select[aria-label^="断言条件 "]').length`)
check('能删掉指定的那一条（删的是最后那条草稿）', afterDel === 3, `${afterDel} 行`)

// 一条一条删、每条都重新查一遍。
// 不能一次抓下来 forEach 全点掉：React 删完立刻重渲染，那次抓下来的 NodeList 里
// 除第一个之外的按钮已经脱离文档，点它们毫无效果 —— 表现成「删完还剩一条」，
// 看着像删除没删干净，实际是脚本拿着过期的节点在点。
for (let i = 0; i < 6; i++) {
  const left = await ev(`(() => {
    const all = [...(${EDIT_DLG}).querySelectorAll('button[aria-label^="删除断言 "]')]
    if (!all.length) return 0
    all[0].click()
    return all.length
  })()`)
  await sleep(280)
  if (!left) break
}
const emptied = JSON.parse(await ev(`(() => {
  const d = ${EDIT_DLG}
  return JSON.stringify({
    rows: d.querySelectorAll('select[aria-label^="断言条件 "]').length,
    hint: d.innerText.includes('还没有断言'),
  })
})()`)) || {}
check('全部删完回到可读的空态提示（不是留一个空框）',
  emptied.rows === 0 && emptied.hint === true, JSON.stringify(emptied))

// 有未保存修改时先取消，再明确放弃；既有断言必须保持原值。
await ev(`(() => {
  const d = ${EDIT_DLG}
  const b = [...d.querySelectorAll('button')].find(x => x.textContent.trim() === '取消')
  if (b) b.click(); return 'OK'
})()`)
await sleep(200)
await ev(`(() => {
  const d = ${EDIT_DLG}
  const b = d && [...d.querySelectorAll('button')].find(x => x.textContent.trim() === '放弃修改')
  if (b) b.click(); return 'OK'
})()`)
await sleep(900)
const kept = await ev(`(${EDIT_DLG}) ? 'STILL_OPEN' : ((() => {
  const row = ${ASSERT_ROW}
  return row ? (/响应体\\s*(\\d+)/.exec(row.innerText) || [])[1] : 'NO_ROW'
})())`)
check('取消后断言没被改动（列表上还是 3 条）', kept === '3', String(kept))

// ---------- 跑一次，看失败原因怎么显示 ----------
// 用行内的「单条执行」直接跑这条用例：它把结果放进同步结果表，不用先勾选再执行。
const ran = await ev(`(() => {
  const row = ${ASSERT_ROW}
  const b = row && row.querySelector('button[title="单条执行"]')
  if (!b) return 'NO_BTN'
  b.click(); return 'OK'
})()`)
check('能按单条执行跑这条带断言的用例', ran === 'OK', ran)
await sleep(2600)

await ev(`(() => {
  const d = ${CASE_DLG}
  const b = d && [...d.querySelectorAll('button')].find(x => x.textContent.trim() === '关闭')
  if (b) b.click(); return 'OK'
})()`)
await sleep(900)

const exec = JSON.parse(await ev(`(() => {
  const rows = [...document.querySelectorAll('tr')]
    .filter(r => r.innerText.includes('界面断言用·响应体断言'))
  if (!rows.length) return '{"found":false}'
  rows[0].click()
  return JSON.stringify({ found: true, rows: rows.length, verdict: rows[0].innerText })
})()`)) || {}
check('结果表里出现这次执行', exec.found === true, JSON.stringify(exec))
check('判定是「未通过」（断言没过属于 FAIL，不是 ERROR）',
  (exec.verdict || '').includes('未通过'), exec.verdict)
await sleep(700)

const reason = JSON.parse(await ev(`(() => {
  const el = [...document.querySelectorAll('div')].find(x =>
    typeof x.className === 'string' && x.className.includes('whitespace-pre-wrap'))
  if (!el) return '{"found":false}'
  const t = el.textContent
  return JSON.stringify({
    found: true,
    whiteSpace: getComputedStyle(el).whiteSpace,
    hasNewline: t.indexOf(String.fromCharCode(10)) >= 0,
    bullets: (t.match(/·/g) || []).length,
    head: t.slice(0, 30),
    paths: ['version', 'name'].every(p => t.includes(p)),
  })
})()`)) || {}
check('展开后能看到响应体断言的失败原因', reason.found === true, JSON.stringify(reason))
check('三条失败一次全列出来（原因里有 3 个「·」）', reason.bullets === 3, JSON.stringify(reason))
check('原因抬头写着「响应体断言不通过」', (reason.head || '').includes('响应体断言不通过'),
  reason.head)
check('每条失败都带上了出问题的路径', reason.paths === true, JSON.stringify(reason))
check('多行真的换行了（whitespace 是 pre-wrap，否则会挤成一整段）',
  reason.whiteSpace === 'pre-wrap' && reason.hasNewline === true, JSON.stringify(reason))

// ---------- 结果详情里的请求块默认收起（需求 #53）----------
//
// 四块里「本次发出的请求」与「本次发出的请求体」最长，一展开就把真正要看的响应挤下去。
// 这里要钉住两件事：默认确实收起（而且正文**不渲染**，不是藏起来占位），点一下能展开；
// 以及**响应侧不受影响** —— 只收请求侧，不能顺手把响应也收了。
console.log('\n--- 结果详情的请求块默认收起 ---')

/** 按标题文字找一个可折叠块的标题按钮（用 span 精确匹配：两块标题互为前缀，不能用 includes）。 */
const BLOCK_BTN = (t) => `[...document.querySelectorAll('button[aria-expanded]')]
  .find(b => { const s = b.querySelector('span'); return s && s.textContent.trim() === ${JSON.stringify(t)} })`

const blocks = JSON.parse(await ev(`(() => {
  const reqH = ${BLOCK_BTN('本次发出的请求')}
  const reqB = ${BLOCK_BTN('本次发出的请求体')}
  // 判「正文渲染了没有」用**包装块的子元素个数**，不要找 pre：
  // 请求体为空时 JsonBlock 渲染的是一句「（无请求体）」，压根没有 pre
  // —— 按 pre 判会把它当成「没渲染」，正是本脚本刚吃过的一次假失败。
  const kids = (b) => (b && b.parentElement ? b.parentElement.children.length : -1)
  return JSON.stringify({
    found: !!reqH && !!reqB,
    reqHeaders: reqH ? reqH.getAttribute('aria-expanded') : 'NO_BTN',
    reqBody: reqB ? reqB.getAttribute('aria-expanded') : 'NO_BTN',
    reqHeadersKids: kids(reqH),
    reqBodyKids: kids(reqB),
    hint: reqH ? reqH.textContent : '',
    // 响应侧该照旧直出：这一条防的是「顺手把四块全收起来」
    responseStillShown: [...document.querySelectorAll('pre')]
      .some(p => p.textContent.includes('SudPanel')),
  })
})()`)) || {}
check('「本次发出的请求」默认收起', blocks.found === true && blocks.reqHeaders === 'false',
  JSON.stringify(blocks))
check('「本次发出的请求体」默认收起', blocks.reqBody === 'false', JSON.stringify(blocks))
check('收起时正文不渲染（真的不占高度，不是藏起来占位）',
  blocks.reqHeadersKids === 1 && blocks.reqBodyKids === 1, JSON.stringify(blocks))
check('收起态给了「点击展开」的提示（不然只是个标题，看不出能点）',
  (blocks.hint || '').includes('点击展开'), blocks.hint)
check('响应侧不受影响，仍然直接展开', blocks.responseStillShown === true, JSON.stringify(blocks))

// 有内容的块展开后要真的看到内容（这条用例发出的请求有地址与请求头）
await ev(`(() => { const b = ${BLOCK_BTN('本次发出的请求')}; if (b) b.click(); return 'OK' })()`)
await sleep(400)
const openReq = JSON.parse(await ev(`(() => {
  const b = ${BLOCK_BTN('本次发出的请求')}
  const has = !!(b && b.parentElement && b.parentElement.querySelector('pre'))
  const t = b && b.parentElement ? b.parentElement.textContent : ''
  return JSON.stringify({
    expanded: b ? b.getAttribute('aria-expanded') : 'NO_BTN',
    kids: b && b.parentElement ? b.parentElement.children.length : -1,
    hasText: has,
    showsUrl: t.includes('/api/meta'),
  })
})()`)) || {}
check('「本次发出的请求」点一下就展开，并显示出发出去的地址与请求头',
  openReq.expanded === 'true' && openReq.kids === 2 && openReq.hasText === true
  && openReq.showsUrl === true, JSON.stringify(openReq))

// 空请求体：展开后应当看到「（无请求体）」，而不是一片空白
await ev(`(() => { const b = ${BLOCK_BTN('本次发出的请求体')}; if (b) b.click(); return 'OK' })()`)
await sleep(400)
const openReqBody = JSON.parse(await ev(`(() => {
  const b = ${BLOCK_BTN('本次发出的请求体')}
  const t = b && b.parentElement ? b.parentElement.textContent : ''
  return JSON.stringify({
    expanded: b ? b.getAttribute('aria-expanded') : 'NO_BTN',
    kids: b && b.parentElement ? b.parentElement.children.length : -1,
    hint: b ? b.textContent : '',
    emptyHint: t.includes('（无请求体）'),
  })
})()`)) || {}
check('「本次发出的请求体」点一下就展开（这条没有请求体，显示的是「无请求体」）',
  openReqBody.expanded === 'true' && openReqBody.kids === 2 && openReqBody.emptyHint === true,
  JSON.stringify(openReqBody))
check('展开后提示改成「点击收起」', (openReqBody.hint || '').includes('点击收起'), openReqBody.hint)

// 再点一下收回去 —— 「点击后再展开」要能来回切，不能只开不合
for (const t of ['本次发出的请求', '本次发出的请求体']) {
  await ev(`(() => { const b = ${BLOCK_BTN(t)}; if (b) b.click(); return 'OK' })()`)
}
await sleep(400)
const reclosedReqBody = JSON.parse(await ev(`(() => {
  const reqH = ${BLOCK_BTN('本次发出的请求')}
  const reqB = ${BLOCK_BTN('本次发出的请求体')}
  const kids = (b) => (b && b.parentElement ? b.parentElement.children.length : -1)
  return JSON.stringify({
    reqHeaders: reqH ? reqH.getAttribute('aria-expanded') : 'NO_BTN',
    reqBody: reqB ? reqB.getAttribute('aria-expanded') : 'NO_BTN',
    reqHeadersKids: kids(reqH),
    reqBodyKids: kids(reqB),
  })
})()`)) || {}
check('再点一下能收回去（两块都能来回切）',
  reclosedReqBody.reqHeaders === 'false' && reclosedReqBody.reqBody === 'false'
  && reclosedReqBody.reqHeadersKids === 1 && reclosedReqBody.reqBodyKids === 1,
  JSON.stringify(reclosedReqBody))

// ---------- 执行详情四块的排布（需求 #55）----------
//
// 用户指定：左上响应体 / 右上响应头 / 左下本次发出的请求 / 右下本次发出的请求体，
// 请求侧两块仍然默认收起。
//
// 这一节**只用几何位置判，不数 DOM 顺序**。四块谁先谁后，真正的含义是「出现在屏幕上的哪一角」；
// 只验 DOM 顺序的话，一次 `flex-row-reverse`、一次 `order-*`、甚至只改个 grid 列数，
// 断言都会照绿而界面已经变了 —— 那就成了「用实现细节冒充结论」。取四块**标题元素**的左/上坐标来比：
// 标题就在各自格子的顶部，能代表那一格的位置。
console.log('\n--- 执行详情四块的排布 ---')

const geo = JSON.parse(await ev(`(() => {
  const rect = (el) => { const r = el.getBoundingClientRect(); return { x: Math.round(r.left), y: Math.round(r.top) } }
  // 请求侧两块的标题在可折叠按钮里
  const btn = (t) => [...document.querySelectorAll('button[aria-expanded]')]
    .find(b => { const s = b.querySelector('span'); return s && s.textContent.trim() === t })
  // 响应侧两块是普通标题行：取「内容为空、且是所在格子的第一个元素」的那个 div，
  // 免得匹配到别处恰好也叫这个名字的元素（用例列表上就有一个「响应体 N」的徽标）
  const leaf = (t) => [...document.querySelectorAll('div')]
    .find(d => d.children.length === 0 && d.textContent.trim() === t
      && d.parentElement && d.parentElement.firstElementChild === d)
  const respBody = leaf('响应体'), respHead = leaf('响应头')
  const reqH = btn('本次发出的请求'), reqB = btn('本次发出的请求体')
  return JSON.stringify({
    respBody: respBody ? rect(respBody) : null,
    respHead: respHead ? rect(respHead) : null,
    reqHeaders: reqH ? rect(reqH) : null,
    reqBody: reqB ? rect(reqB) : null,
    reqHeadersOpen: reqH ? reqH.getAttribute('aria-expanded') : 'NO_BTN',
    reqBodyOpen: reqB ? reqB.getAttribute('aria-expanded') : 'NO_BTN',
  })
})()`)) || {}

const boxes = [geo.respBody, geo.respHead, geo.reqHeaders, geo.reqBody]
check('四块的标题都找得到（找不到就说明标题文字被改了，下面的位置断言会变成空转）',
  boxes.every((b) => b && typeof b.x === 'number' && typeof b.y === 'number'),
  JSON.stringify(geo))
// 上下关系：响应侧整体在请求侧上面
check('响应侧在上：响应体与响应头都排在请求侧两块上面',
  !!geo.respBody && !!geo.respHead && !!geo.reqHeaders && !!geo.reqBody
  && geo.respBody.y < geo.reqHeaders.y && geo.respHead.y < geo.reqBody.y,
  JSON.stringify(geo))
// 左右关系：左列是响应体 / 请求，右列是响应头 / 请求体
check('左右关系：响应体在响应头左边，本次发出的请求在请求体左边',
  !!geo.respBody && !!geo.respHead && !!geo.reqHeaders && !!geo.reqBody
  && geo.respBody.x < geo.respHead.x && geo.reqHeaders.x < geo.reqBody.x,
  JSON.stringify(geo))
// 同一行的两块必须齐平，否则「左上/右上」就名不副实了
check('同一行的两块顶部齐平（左上与右上、左下与右下各成一行）',
  !!geo.respBody && !!geo.respHead && !!geo.reqHeaders && !!geo.reqBody
  && Math.abs(geo.respBody.y - geo.respHead.y) <= 2
  && Math.abs(geo.reqHeaders.y - geo.reqBody.y) <= 2,
  JSON.stringify(geo))
// 两列也各自对齐：左上与左下同列、右上与右下同列
check('左右两列各自对齐（左上与左下同列、右上与右下同列）',
  !!geo.respBody && !!geo.respHead && !!geo.reqHeaders && !!geo.reqBody
  && Math.abs(geo.respBody.x - geo.reqHeaders.x) <= 2
  && Math.abs(geo.respHead.x - geo.reqBody.x) <= 2,
  JSON.stringify(geo))
// 需求 #55 原话里特意重申的一点：换排布不能顺手把收起行为改掉
check('改了排布之后请求侧两块仍然默认收起（需求原话特意重申过这一点）',
  geo.reqHeadersOpen === 'false' && geo.reqBodyOpen === 'false', JSON.stringify(geo))

// ---------- 结果详情的「响应体断言」块（需求 #58）----------
//
// 这一块存在的理由是「绿得不明不白」：在此之前只有**没通过**的断言会落库（进失败原因），
// 断言全过时界面上一个字都没有，分不清「都过了」和「压根没配」。所以这里要钉的不是
// 「有个标题」，而是三种状态各自显示对：
//   ① 配了且没过 → 收起态带条数（N 条，M 条未通过），展开能逐条看到路径/条件/期望，行的标记是红的；
//   ② 配了且全过 → 收起态是「N 条全部通过」，行的标记**不是**红的（与 ① 对比着判，不认死具体色值）；
//   ③ 没配断言 → 静态提示，**没有**可点的按钮（点了没反应的按钮比不给还糟）。
// 另外默认必须收起，且收起时明细**不渲染**（真的不占高度，不是藏起来占位）。
console.log('\n--- 结果详情的响应体断言块 ---')

/** 找结果详情那一行：用块标题认，别用类名（类名会随样式改）。 */
const DETAIL_ROW = `[...document.querySelectorAll('tr')].find(r => r.innerText.includes('响应头') && r.innerText.includes('本次发出的请求体'))`

/** 读断言那一块的现状。 */
const probesAsserts = () => ev(`(() => {
  const tr = ${DETAIL_ROW}
  if (!tr) return '{"noDetail":true}'
  const t = [...tr.querySelectorAll('span')].find(s => s.textContent.trim() === '响应断言')
  if (!t) return '{"noBlock":true}'
  // 可展开时 t 的父节点是 button、静态时是标题 div；再上一层都是这一块的根 div。
  // 两种形态下这个走法都成立，所以不用去认类名。
  const head = t.parentElement
  const root = head.parentElement
  const openable = head.tagName === 'BUTTON'
  const list = openable ? root.children[1] : null
  const rows = list ? [...list.children] : []
  const first = rows[0]
  const mark = first ? first.firstElementChild : null
  return JSON.stringify({
    openable,
    expanded: openable ? head.getAttribute('aria-expanded') : 'STATIC',
    kids: root.children.length,
    text: head.innerText.replace(/\\s+/g, ' ').trim(),
    rowCount: rows.length,
    rowText: first ? first.innerText.replace(/\\s+/g, ' ').trim() : '',
    markColor: mark ? getComputedStyle(mark).color : null,
    titleY: Math.round(head.getBoundingClientRect().top),
    // 第五块必须在请求块下面（用户选的是「四块里的第五块」）
    reqBodyY: (() => {
      const s = [...tr.querySelectorAll('span')].find(x => x.textContent.trim() === '本次发出的请求体')
      return s ? Math.round(s.getBoundingClientRect().top) : null
    })(),
  })
})()`)

/** 点开/收起断言那一块。找不到就回一个状态串，**不要抛异常** —— 抛了会让整个脚本中途死掉，
 *  后面的断言一条都不跑，红跑看起来像脚本坏了而不是功能没做。 */
const clickAssertsTitle = () => ev(`(() => {
  const tr = ${DETAIL_ROW}
  if (!tr) return 'NO_ROW'
  const t = [...tr.querySelectorAll('span')].find(s => s.textContent.trim() === '响应断言')
  if (!t) return 'NO_TITLE'
  t.parentElement.click(); return 'OK'
})()`)

// ----- 状态 ①：当前展开的就是「界面断言用·响应体断言」（3 条断言全不通过）-----
const failedBlock = JSON.parse(await probesAsserts()) || {}
check('结果详情里出现「响应断言」块', failedBlock.openable === true, JSON.stringify(failedBlock))
check('它在请求侧那两块下面（第五块的位置）',
  typeof failedBlock.titleY === 'number' && typeof failedBlock.reqBodyY === 'number'
  && failedBlock.titleY > failedBlock.reqBodyY, JSON.stringify(failedBlock))
check('默认收起，而且收起时明细不渲染（真的不占高度）',
  failedBlock.expanded === 'false' && failedBlock.kids === 1, JSON.stringify(failedBlock))
check('收起态带条数：3 条里 3 条未通过',
  (failedBlock.text || '').includes('3 条') && (failedBlock.text || '').includes('3 条未通过'),
  failedBlock.text)

await clickAssertsTitle()
await sleep(400)
const failedOpen = JSON.parse(await probesAsserts()) || {}
check('点一下就展开，逐条渲染出 3 行',
  failedOpen.expanded === 'true' && failedOpen.rowCount === 3 && failedOpen.kids === 2,
  JSON.stringify(failedOpen))
check('每行都带路径、判断条件与期望值（不是只给一句结论）',
  (failedOpen.rowText || '').includes('version') && (failedOpen.rowText || '').includes('等于')
  && (failedOpen.rowText || '').includes('9.9.9'),
  failedOpen.rowText)
check('未通过的行在路径、条件之后还带着具体原因',
  (failedOpen.rowText || '').includes('期望'), failedOpen.rowText)

// ----- 状态 ②③：再播两条用例各跑一次（全过 / 没配断言）-----
/** 打开用例管理 → 对指定用例点「单条执行」→ 关掉弹窗 → 展开它刚产生的那条结果。 */
async function runCaseByName(name) {
  await ev(`(() => {
    const el = [...document.querySelectorAll('button, div, a')].find(e =>
      e.textContent.trim().includes('用例管理') && getComputedStyle(e).cursor === 'pointer')
    if (el) el.click(); return 'OK'
  })()`)
  await sleep(1400)
  const clicked = await ev(`(() => {
    const name = ${JSON.stringify(name)}
    // 别用「innerText 含名字且含单条执行按钮」找卡片：外层弹窗同时含**所有**用例名和**所有**按钮，
    // 会先命中那个容器，于是拿到的是第一条用例的按钮（实测踩到：点下去跑的是别的用例）。
    // 改成「最近的祖先里 innerText 最短的那个」= 真正的那张卡片。
    const cand = [...document.querySelectorAll('button[title="单条执行"]')].map((b) => {
      let el = b, host = null
      while (el && el !== document.body) {
        if (el.innerText && el.innerText.includes(name)) { host = el; break }
        el = el.parentElement
      }
      return { b, host }
    }).filter((x) => x.host)
    if (!cand.length) return 'NOT_FOUND'
    cand.sort((x, y) => x.host.innerText.length - y.host.innerText.length)
    cand[0].b.click()
    return 'OK'
  })()`)
  await sleep(2600)
  await ev(`(() => {
    const d = ${CASE_DLG}
    const b = d && [...d.querySelectorAll('button')].find(x => x.textContent.trim() === '关闭')
    if (b) b.click(); return 'OK'
  })()`)
  await sleep(900)
  const opened = await ev(`(() => {
    const rows = [...document.querySelectorAll('tr')].filter(r => r.innerText.includes(${JSON.stringify(name)}))
    if (!rows.length) return 'NOT_FOUND'
    rows[0].click(); return 'OK'
  })()`)
  await sleep(600)
  return { clicked, opened }
}

const passCase = await runCaseByName('界面断言用·断言全过')
check('能跑通「断言全过」这条种子用例（跑不了的话下面两条永远得不到验证）',
  passCase.clicked === 'OK' && passCase.opened === 'OK', JSON.stringify(passCase))
const passBlock = JSON.parse(await probesAsserts()) || {}
check('断言全过时收起态写的是「条数 + 全部通过」（不是只说"通过"）',
  (passBlock.text || '').includes('2 条全部通过'), passBlock.text)
check('断言全过时也默认收起', passBlock.expanded === 'false' && passBlock.kids === 1,
  JSON.stringify(passBlock))

await clickAssertsTitle()
await sleep(400)
const passOpen = JSON.parse(await probesAsserts()) || {}
check('全过时展开也逐条列出 2 行', passOpen.rowCount === 2, JSON.stringify(passOpen))
check('通过的行与未通过的行颜色不同（红绿有别，不认死具体色值）',
  !!passOpen.markColor && !!failedOpen.markColor && passOpen.markColor !== failedOpen.markColor,
  `通过=${passOpen.markColor} 未通过=${failedOpen.markColor}`)

// 状态 ③：配了用例但一条断言都没配（请求能打通 → 记录里是空数组）
const noAssertCase = await runCaseByName('界面断言用·无断言')
check('能跑通「无断言」那条种子用例', noAssertCase.clicked === 'OK' && noAssertCase.opened === 'OK',
  JSON.stringify(noAssertCase))
const noneBlock = JSON.parse(await probesAsserts()) || {}
check('没配断言时是静态提示，且没有可点的按钮（点了没反应的按钮比不给还糟）',
  noneBlock.openable === false && (noneBlock.text || '').includes('没配响应断言'),
  JSON.stringify(noneBlock))

// 状态 ④：请求根本没通（ERROR）—— 没有响应体就无从判定。
// 这时**绝不能**说「旧记录没存这一项」：那是一条刚跑出来的记录，用户会以为记录被吞了，
// 而真正该看的是上面的红色报错。（这条是被实测逼出来的：占位用例打的 mock 没启动，
// 原先显示的就是「旧记录没存这一项」。）
const errCase = await runCaseByName('界面断言用占位用例')
check('能跑通「请求没通」那条种子用例', errCase.clicked === 'OK' && errCase.opened === 'OK',
  JSON.stringify(errCase))
const errBlock = JSON.parse(await probesAsserts()) || {}
check('请求没通时说「请求没通」，不说「旧记录」（后者会把刚跑出来的记录说成历史残留）',
  errBlock.openable === false && (errBlock.text || '').includes('请求没通'),
  JSON.stringify(errBlock))

// 最后把状态收拾回默认（收起），免得影响后续在同一行上做的断言
await ev(`(() => { const tr = ${DETAIL_ROW}
  const t = [...tr.querySelectorAll('span')].find(s => s.textContent.trim() === '响应断言')
  if (t && t.parentElement.tagName === 'BUTTON'
      && t.parentElement.getAttribute('aria-expanded') === 'true') t.parentElement.click()
  return 'OK' })()`)

// ---------- 上报查询页（需求 #60）----------
//
// 这一页的核心机制（增量切文）由单测与 logcat-check.mjs 压着；界面这一层要钉的是
// 「有没有这个入口、要素在不在、几个按钮的可用状态对不对、没设备时说的是人话」。
//
// ★ 本机没接设备，所以「实时滚动出日志」这条**验不到**（需要真机冒烟）。
//   没验到的部分不能假装验过 —— 下面每一条都只声称它真的看得到的东西。
console.log('\n--- 上报查询页 ---')

await ev('window.scrollTo(0, 0)')
const navTo = await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '上报查询')
  if (!b) return 'NOT_FOUND'
  b.click(); return 'OK' })()`)
check('侧边栏出现「上报查询」入口', navTo === 'OK', navTo)
await sleep(1200)

const page = await ev(`(() => {
  const body = document.body.innerText
  return JSON.stringify({
    title: body.includes('上报查询'),
    hasStart: body.includes('开始监听'),
    hasStop: body.includes('停止'),
    hasClear: body.includes('清空设备缓冲'),
    hasKeyword: !!document.querySelector('#logcat-keyword'),
    // 级别按钮：6 个单字母按钮（V/D/I/W/E/F）
    levelBtns: [...document.querySelectorAll('button[aria-pressed]')]
      .map(b => b.textContent.trim()).filter(t => /^[VDIWEF]$/.test(t)),
    emptyText: body.includes('还没有内容'),
    // 页面标识：切过来了就不该再显示首页那几张卡
    notDashboard: !body.includes('最近运行'),
  })
})()`)
const pg = JSON.parse(page) || {}
check('确实切到了上报查询页（不是白屏死入口）', pg.title === true && pg.notDashboard === true, page)
check('「开始监听 / 停止 / 清空设备缓冲」三个按钮都在',
  pg.hasStart && pg.hasStop && pg.hasClear, page)
check('有关键字输入框', pg.hasKeyword === true, page)
check('六个级别按钮齐全（V/D/I/W/E/F）',
  Array.isArray(pg.levelBtns) && pg.levelBtns.length === 6, JSON.stringify(pg.levelBtns))
check('还没开始时提示怎么开始（不是一片空白让人猜）', pg.emptyText === true, page)

// 按钮可用状态：不能点一个点了没反应的按钮。
//
// 判据必须**跟着现场状态走**：这两条原来写死成「没选设备时必须禁用」，
// 而本机一旦插上真机，应用会自动选中它、按钮当然就该可用 ——
// 于是断言红得莫名其妙（2026-10-06 插上真机后实测红了这两条，产品是好的）。
// 现在先读控制栏那格显示的是三种状态里的哪一种，再据此判断该禁用还是该可用。
const devState = await ev(`(() => {
  const t = [...document.querySelectorAll('span')]
    .map(s => (s.textContent || '').trim())
    .find(x => x === '无在线设备' || x === '未选择设备' || /^设备 \\S+/.test(x))
  const find = (label) => [...document.querySelectorAll('button')].find(b => b.textContent.trim().includes(label))
  const s = find('开始监听'), c = find('清空设备缓冲'), p = find('停止')
  return JSON.stringify({
    deviceCell: t || null,
    startDisabled: s ? s.disabled : 'NO_BTN',
    clearDisabled: c ? c.disabled : 'NO_BTN',
    stopDisabled: p ? p.disabled : 'NO_BTN',
  }) })()`)
const bs = JSON.parse(devState) || {}
const hasSelectedDevice = typeof bs.deviceCell === 'string' && /^设备 /.test(bs.deviceCell)
check('控制栏如实说明当前有没有选中设备（三种状态之一）',
  bs.deviceCell !== null && bs.deviceCell !== undefined, devState)
check('「开始监听」的可用状态与「有没有选中设备」一致（没选中要禁用，选中了要能点）',
  bs.startDisabled === !hasSelectedDevice,
  `deviceCell=${bs.deviceCell} startDisabled=${bs.startDisabled}`)
check('「清空设备缓冲」的可用状态与「有没有选中设备」一致',
  bs.clearDisabled === !hasSelectedDevice,
  `deviceCell=${bs.deviceCell} clearDisabled=${bs.clearDisabled}`)
check('没在监听时「停止」是禁用的', bs.stopDisabled === true, devState)

// 切回首页，确认「上报查询」也进了首页快捷入口（两份清单都要有，漏一处就是死入口）
const back = await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '首页')
  if (b) { b.click(); return 'OK' }
  return 'NO_HOME' })()`)
await sleep(1200)
const dashHas = await ev(`document.body.innerText.includes('上报查询')`)
check('首页快捷入口里也有「上报查询」（导航表与快捷入口是两份清单，漏一处会留死入口）',
  back === 'OK' && dashHas === true, `${back} / dash=${dashHas}`)

// 「实时滚动」这条验不到（没设备），但**监听频率文案**是纯界面的，能验到。
// 上色本身不在这里验：它由 logcat-check.mjs 在纯函数层压住（切分逻辑 + 17 条新断言），
// 界面上只留「页面上那排级别按钮还在」——上色与过滤共用同一套级别概念，按钮没了两个都坏。
console.log('\n--- 上报查询：上色与频率 ---')

// 必须先切回上报查询页：上一段结尾点了「首页」回去查快捷入口了。
// （第一次写这条时忘了切，于是在首页上找级别按钮 → 必然 MISSING，白红一条。）
const backToLogcat = await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '上报查询')
  if (!b) return 'NOT_FOUND'
  b.click(); return 'OK' })()`)
check('能重新切回上报查询页', backToLogcat === 'OK', backToLogcat)
await sleep(1200)

// 需求 #64 把这里改成了「目标上限 + 实测值」两态：监听中显示实测刷新率
// （真机上取文约 250ms，「每秒 10 次」是做不到的，写死就等于给假数），
// 没在监听时显示目标上限。这里验的是未监听态。
const freq = await ev(`document.body.innerText.includes('每秒最多 10 次')`)
check('刷新率常驻显示且未监听时说的是「每秒最多 10 次」（监听中会换成实测值；只在监听中才显示的话，没设备时用户永远看不到）',
  freq === true, String(freq))

const paintReady = await ev(`(() => {
  const btns = [...document.querySelectorAll('button[aria-pressed]')].filter(b => /^[VDIWEF]$/.test(b.textContent.trim()))
  return btns.length === 6 ? 'OK' : 'MISSING:' + btns.length
})()`)
check('级别按钮仍是 6 个（上色与过滤共用同一套级别概念）', paintReady === 'OK', paintReady)

// ---------- 上报查询：控制面板停住 + 翻上去就停住（需求 #62）----------
//
// 这两条需求的判据是**布局与滚动行为**，没设备时也要能验 —— 否则它们就是「只有真机上
// 跑一跑才知道」的东西，而那种问题一旦回归就得靠人发现。
//
// 做法：往日志区**塞一些 DOM 行**来制造「内容变多」（不改产品逻辑，只制造内容），
// 然后检查四件事：页面没被撑长、控制面板仍在视口内、日志区自己变成可滚的、
// 以及「翻上去 → 不再跟随 / 翻回底部 → 恢复跟随」这条链路。
console.log('\n--- 上报查询：面板停住与滚动跟随 ---')

const boxBefore = JSON.parse(await ev(`(() => {
  const panel = [...document.querySelectorAll('button')].find(b => b.textContent.trim().includes('开始监听'))
  const card = panel ? panel.closest('div') : null
  const r = panel ? panel.getBoundingClientRect() : null
  const sc = document.querySelector('[data-testid="logcat-scroll"]')
  return JSON.stringify({
    hasScrollBox: !!sc,
    overflowY: sc ? getComputedStyle(sc).overflowY : null,
    panelTop: r ? Math.round(r.top) : null,
    docScrollable: document.documentElement.scrollHeight > window.innerHeight + 2,
  }) })()`)) || {}
check('日志区有自己的滚动容器（不是让整个页面滚）', boxBefore.hasScrollBox === true, JSON.stringify(boxBefore))
check('滚动容器是 overflow auto/scroll', /auto|scroll/.test(boxBefore.overflowY || ''), boxBefore.overflowY)
check('当前页面本身不产生文档级滚动', boxBefore.docScrollable === false, JSON.stringify(boxBefore))

// 制造「日志变多」：塞 1000 行（每行都带键值对，顺便也压一下上色不会崩）。
//
// 为什么是 1000 而不是 400（需求 #65 改）：速度上限从 2400 提到 24000px/s，
// 400 行只有约 5800px 的可滚距离，下面那组「连续上滚」的采样还没取完就到底了，
// 而到底之后位置不再变化，「五次采样严格递增」会红得莫名其妙 —— 那是断言跟不上速度，
// 不是功能坏了。1000 行约 14500px，给采样留出足够长的观察窗口。
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  if (!el) return 'NO_BOX'
  const holder = document.createElement('div')
  holder.id = 'sp-filler'
  for (let i = 0; i < 1000; i++) {
    const d = document.createElement('div')
    d.className = 'font-mono text-xs'
    d.textContent = '10-06 12:00:00.000  1234  1250 I SudReport: filler ' + i + ' roomId=3333 isIn=true'
    holder.appendChild(d)
  }
  el.appendChild(holder)
  return 'OK' })()`)
await sleep(600)

const boxAfter = JSON.parse(await ev(`(() => {
  const panel = [...document.querySelectorAll('button')].find(b => b.textContent.trim().includes('开始监听'))
  const r = panel ? panel.getBoundingClientRect() : null
  const sc = document.querySelector('[data-testid="logcat-scroll"]')
  return JSON.stringify({
    panelTop: r ? Math.round(r.top) : null,
    panelVisible: r ? (r.top >= -1 && r.bottom <= window.innerHeight + 1) : false,
    docScrollable: document.documentElement.scrollHeight > window.innerHeight + 2,
    boxScrollable: sc ? sc.scrollHeight > sc.clientHeight + 1 : false,
    boxHeight: sc ? Math.round(sc.getBoundingClientRect().height) : null,
  }) })()`)) || {}
check('★ 内容变多 400 行后，页面仍然不产生文档级滚动（控制面板不会被顶上去）',
  boxAfter.docScrollable === false, JSON.stringify(boxAfter))
check('★ 控制面板仍在视口内（top 与内容多少无关）',
  boxAfter.panelVisible === true && boxAfter.panelTop === boxBefore.panelTop,
  `before=${boxBefore.panelTop} after=${boxAfter.panelTop}`)
check('日志区变成了可滚动的（内容确实超出它的高度）', boxAfter.boxScrollable === true, JSON.stringify(boxAfter))

// 最后一条是需求 2 的直接判据：模拟「用户为了看最新日志把页面滚到底」，
// 控制面板必须**仍然可见**。只查初始 top 是不够的 —— 页面没滚时面板当然在顶部，
// 那条会在旧实现上误判成绿（第一版就是这样，红跑时它绿了才发现判据不够有力）。
await ev(`window.scrollTo(0, document.documentElement.scrollHeight)`)
await sleep(300)
const afterPageScroll = JSON.parse(await ev(`(() => {
  const panel = [...document.querySelectorAll('button')].find(b => b.textContent.trim().includes('开始监听'))
  const r = panel ? panel.getBoundingClientRect() : null
  return JSON.stringify({
    pageScrollTop: Math.round(window.scrollY),
    panelTop: r ? Math.round(r.top) : null,
    panelVisible: r ? (r.top >= -1 && r.bottom <= window.innerHeight + 1) : false,
  }) })()`)) || {}
check('★ 把页面滚到底（想看最新日志）时，控制面板仍然在视口内 —— 这就是「不被顶上去」',
  afterPageScroll.panelVisible === true,
  `pageScrollTop=${afterPageScroll.pageScrollTop} panelTop=${afterPageScroll.panelTop}`)
await ev(`window.scrollTo(0, 0)`)
await sleep(200)

// 翻上去 → 停住（需求 1）。
//
// 注意模拟方式：**必须先离开底部、再往上翻**。我第一版直接 `scrollTop = 0`，
// 而位置本来就在 0（刚塞入内容、没人滚过）→ **值没变化就不派发 scroll 事件** →
// 页面的 onScroll 根本没跑，断言红得莫名其妙（查了半天才发现是模拟方式的问题，
// 不是实现的问题）。真实用户也一定是「先看到底、再往上翻」，所以两步才贴近实际。
//
// 【诊断用，默认不开】把「动画是不是还活着」打出来：连采两次位置，如果两次一样、
// 而跟随却还是 true，那多半是跟随循环的脚本断了（一个抛错的 rAF 回调就会让链悄悄断掉，
// 而 follow 状态还停在 true —— 表面症状会指向「翻上去不停住了」，方向完全找错）。
// 用 SP_DEBUG_EXC=1 打开；平时不打印，也不影响断言。
const diagScroll = async (tag) => {
  if (!process.env.SP_DEBUG_EXC) return
  const rd = () => ev(`(() => {
    const el = document.querySelector('[data-testid="logcat-scroll"]')
    return JSON.stringify({ top: el.scrollTop, max: el.scrollHeight - el.clientHeight })
  })()`)
  const a = JSON.parse(await rd())
  await sleep(120)
  const b = JSON.parse(await rd())
  const f = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
  console.log(`  [DIAG ${tag}] top=${a.top}→${b.top} (max=${a.max}) follow=${f}`)
}
await diagScroll('注入 400 行之后')

await ev(`(() => { const el = document.querySelector('[data-testid="logcat-scroll"]'); el.scrollTop = el.scrollHeight; return 'OK' })()`)
await sleep(400)
await diagScroll('滚到底之后')
const cbAtBottom = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('滚到底时仍处于跟随状态（这是「继续监听」的前提）', cbAtBottom === true, String(cbAtBottom))

await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = Math.max(0, Math.round((el.scrollHeight - el.clientHeight) / 2))
  return 'OK' })()`)
await sleep(400)
await diagScroll('往上翻到中间之后')
const cbAfterUp = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('★ 往上翻到中间之后自动取消跟随（滑动之后停住，不再自动往下滑）',
  cbAfterUp === false, `afterUp=${cbAfterUp}`)

// 停住之后再来新内容，位置不许被拽走
const posBefore = await ev(`document.querySelector('[data-testid="logcat-scroll"]').scrollTop`)
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  const d = document.createElement('div')
  d.className = 'font-mono text-xs'
  d.textContent = '10-06 12:00:01.000  1234  1250 I SudReport: appended after manual scroll'
  el.querySelector('#sp-filler').appendChild(d)
  return 'OK' })()`)
await sleep(500)
const posAfter = await ev(`document.querySelector('[data-testid="logcat-scroll"]').scrollTop`)
check('★ 停住之后新内容不把视图拽回底部（继续监听但不自动下滑）',
  posAfter === posBefore, `before=${posBefore} after=${posAfter}`)

// 翻回底部 → 恢复跟随
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = el.scrollHeight
  return 'OK' })()`)
await sleep(400)
const cbBack = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('自己翻回底部后恢复跟随（不需要手动勾回选项）', cbBack === true, String(cbBack))

// ---------- 上报查询：连续跟随动画（需求 #63）----------
//
// 需求原话：「上报的信息从底部滑出而非突然出现在屏幕上，像滚动条一样向上滚动」。
// 判据就是**位移连不连续**：旧实现每个轮询周期执行一句 `scrollTop = scrollHeight`，
// 一帧之内整块瞬移；新实现每帧只推一点点，所以「连采几次都在动、且每次只走一小段」。
//
// ★ 本机没设备，造不出真实轮询内容，这里是**往滚动容器里塞 DOM** 制造「内容变高」。
//   被验到的是「滚动这一侧」的连续性与跟随状态机；「React 每 100ms 追加一行」
//   那半条链路仍然只有真机冒烟能验（同 #60/#61/#62，如实记）。
//   同理：「跟随中只渲染尾部 1200 行」这个**渲染窗口**在界面层造不出 1200 行以上的真实日志，
//   它只在 scroll-anim-check.mjs 的纯函数层被压住 —— 下面只验「契约值有没有接上」。
console.log('\n--- 上报查询：连续跟随动画 ---')

// 先探一下这个无头浏览器到底跑不跑 requestAnimationFrame。
// 不先探的话，一旦它不跑帧，下面所有动画断言都会红，而红的原因看起来像「功能坏了」——
// 那种红会把我引向错误的排查方向（这个坑在 #62 的「过期守卫」上已经吃过一次）。
const rafFrames = await ev(`new Promise((resolve) => {
  let n = 0
  const t0 = performance.now()
  const step = () => {
    n++
    if (performance.now() - t0 < 400) requestAnimationFrame(step)
    else resolve(n)
  }
  requestAnimationFrame(step)
})`)
check('这个无头浏览器会跑 requestAnimationFrame（下面动画断言的前提）',
  typeof rafFrames === 'number' && rafFrames > 5, `400ms 内 ${rafFrames} 帧`)

// 摆一个明确的起点：取消勾选 → 把位置放回顶部 → 再勾回来，动画就有整段路可走。
// （跟随开着的时候位置是放不到顶部的 —— 动画会立刻把它拉回去，这正是它该做的事。）
await ev(`(() => {
  const cb = document.querySelector('[data-testid="logcat-follow"]')
  if (cb.checked) cb.click()
  return 'OK' })()`)
await sleep(200)
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = 0
  return 'OK' })()`)
await sleep(200)
const geom = JSON.parse(await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  return JSON.stringify({ maxTop: el.scrollHeight - el.clientHeight, at: el.scrollTop })
})()`)) || {}
check('日志区有足够长的可滚距离（动画才有东西可观察）', geom.maxTop > 1000, JSON.stringify(geom))

await ev(`document.querySelector('[data-testid="logcat-follow"]').click()`)
const sampleTop = () => ev(`document.querySelector('[data-testid="logcat-scroll"]').scrollTop`)
const seq = []
for (let i = 0; i < 5; i++) {
  seq.push(await sampleTop())
  await sleep(25)
}
const followDuringAnim = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
// 一路追到底：**轮询到位置稳定**，不写死等待时长 —— 速度上限在需求 #65 调过一档
// （2400 → 24000px/s），写死「等 3.4 秒」下一次调参就会过时，而过时的等待
// 表现为「最终没到底」，看起来像功能坏了。
let tEnd = await sampleTop()
for (let i = 0; i < 80; i++) {
  await sleep(100)
  const t = await sampleTop()
  if (t === tEnd && t >= geom.maxTop - 8) break
  tEnd = t
}

check('★ 勾回跟随的瞬间没有一步跳到底（旧实现是整块瞬移）',
  seq[0] < geom.maxTop - 500, `t0=${seq[0]} max=${geom.maxTop}`)
check('★ 位置在连续上滚：五次采样严格递增 —— 这就是「像滚动条一样向上滚」',
  seq.every((v, i) => i === 0 || v > seq[i - 1]), seq.join(' → '))
check('★ 每次采样之间的位移都远小于剩余距离（不是「一帧一大截」）',
  seq.slice(1).every((v, i) => v - seq[i] < geom.maxTop * 0.5),
  `每次约 ${Math.round((seq[4] - seq[0]) / 4)}px，总距离 ${geom.maxTop}px`)
check('★ 动画跑的过程中跟随状态没有被自己关掉（程序化滚动不能误判成用户操作）',
  followDuringAnim === true, String(followDuringAnim))
check('最终追到底部（跟得上，不是一直在半路）',
  tEnd >= geom.maxTop - 8, `tEnd=${tEnd} max=${geom.maxTop}`)
check('整个过程中页面仍不产生文档级滚动（控制面板没被顶走）',
  (await ev(`document.documentElement.scrollHeight > window.innerHeight + 2`)) === false)
await ev(`window.scrollTo(0, 0)`)

// 用户往上翻：动画必须**立刻**停住，而不是「位置被改回去了、但动画还在往下爬」
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = el.scrollHeight
  return 'OK' })()`)
await sleep(300)
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = Math.round((el.scrollHeight - el.clientHeight) / 2)
  return 'OK' })()`)
await sleep(150)
const justAfterUp = await sampleTop()
await sleep(600)
const afterUp = await sampleTop()
const followAfterUp = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('★ 往上翻之后位置真的停住了（半秒内一动不动 —— 说明动画被取消，不是还在慢慢往下爬）',
  afterUp === justAfterUp, `justAfter=${justAfterUp} 半秒后=${afterUp}`)
check('★ 往上翻之后跟随状态关闭', followAfterUp === false, String(followAfterUp))

// 滚轮往上拨一格：方向是明确信号，应当**当场**停住，不必等「位置回退」那条兜底判断
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = el.scrollHeight
  return 'OK' })()`)
await sleep(400)
const followAtBottom = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }))
  return 'OK' })()`)
await sleep(200)
const followAfterWheel = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('滚到底时处于跟随状态（滚轮断言的起点）', followAtBottom === true, String(followAtBottom))
check('★ 滚轮往上拨一格就停住（方向是明确信号，不用等位置回退兜底）',
  followAfterWheel === false, String(followAfterWheel))

// 翻回底部 → 恢复跟随（改动画之后这条链路不能坏）
//
// 注意模拟方式：滚轮那一条把跟随关掉了，但**位置还停在底部**（合成的 wheel 事件不会真的滚），
// 此时再赋一次「滚到底」是**同一个值、不会派发 scroll 事件**，断言会红得莫名其妙 ——
// 和 1.7.2 那次踩的是同一个坑。所以先翻上去、再翻回来，两步走。
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = Math.round((el.scrollHeight - el.clientHeight) / 3)
  return 'OK' })()`)
await sleep(200)
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = el.scrollHeight
  return 'OK' })()`)
await sleep(400)
const followBackAgain = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('翻回底部后再次恢复跟随', followBackAgain === true, String(followBackAgain))

// ---------- 手动划到「接近底部」就恢复勾选（需求 #65）----------
//
// 用户原话：「**手动划到最底下或者接近最底下时自动勾选自动滚动到底**」。
// 判据必须用**距底 100px**（不是 0）：真机上手指做惯性滚动、滚轮滚滚，
// 落点根本到不了 24px 以内 —— 拿旧的贴底容差去判，这条功能等于永远不触发。
console.log('\n--- 上报查询：接近底部就恢复勾选（需求 #65）---')

await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = Math.round((el.scrollHeight - el.clientHeight) / 2)
  return 'OK' })()`)
await sleep(350)
const followMid2 = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('起点：往上翻到中间后处于不跟随', followMid2 === false, String(followMid2))

await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = el.scrollHeight - el.clientHeight - 100
  return 'OK' })()`)
await sleep(450)
const followNear = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('★ 手动划到距底 100px（还没精确到底）：自动重新勾上「自动滚到底」',
  followNear === true, String(followNear))

// 对照组：**手动点掉勾选**（位置仍在底部）→ 再一路上往上翻，全程都还落在 120px 的恢复容差内。
// 一次都不许恢复。这一条同时压住两件事：
//   ① 「停住 → 判贴底 → 恢复 → 又被拽回底部 → 再翻 → 停住」的来回跳；
//   ② **手动取消勾选取消不掉** —— 探针实测抓到的漏判：只看「离底近 + 没在往上翻」两条的话，
//      位置本来就在底部的取消动作会被下一次滚动立刻勾回来，那个勾像「黏住了」。
//      修法是加「他确实翻走过」这个前提，所以下面必须先翻走、再翻回来，两步缺一不可。
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  const cb = document.querySelector('[data-testid="logcat-follow"]')
  el.scrollTop = el.scrollHeight
  if (cb.checked) cb.click()
  return 'OK' })()`)
await sleep(300)

const upSteps = []
for (const off of [50, 80, 110]) {
  await ev(`(() => {
    const el = document.querySelector('[data-testid="logcat-scroll"]')
    el.scrollTop = el.scrollHeight - el.clientHeight - ${off}
    return 'OK' })()`)
  await sleep(150)
  upSteps.push(await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`))
}
check('★ 手动点掉勾选后一路上往上翻（距底 50→80→110px，全程仍在恢复容差内）：一次都不恢复',
  upSteps.every((v) => v === false), JSON.stringify(upSteps))

// 再验一次「真的翻走之后翻回来」—— 上面那条压的是不许恢复，这条压的是该恢复还得恢复
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = Math.round((el.scrollHeight - el.clientHeight) / 2)
  return 'OK' })()`)
await sleep(350)
const followDeep = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  el.scrollTop = el.scrollHeight - el.clientHeight - 100
  return 'OK' })()`)
await sleep(400)
const followReengaged = await ev(`document.querySelector('[data-testid="logcat-follow"]').checked`)
check('★ 先翻到中间（真的翻走了），再划回距底 100px：这时才恢复勾选',
  followDeep === false && followReengaged === true,
  `中间时=${followDeep} 划回后=${followReengaged}`)

// 稳定行号契约：滚动容器上要带着「渲染了多少行 / 首行的绝对行号」，
// 供 LogViewer 当 key 的起点（窗口滑动时老行的行号必须稳定，否则记忆化静默失效）。
const attrs = JSON.parse(await ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  return JSON.stringify({
    rendered: el.getAttribute('data-rendered'),
    offset: el.getAttribute('data-line-offset'),
  }) })()`)) || {}
check('滚动容器上带着「渲染行数 / 首行绝对行号」两个契约值（稳定 key 的依据）',
  /^\d+$/.test(String(attrs.rendered)) && /^\d+$/.test(String(attrs.offset)),
  JSON.stringify(attrs))
check('没点「开始监听」时 React 侧一行都没有，两个契约值都是 0（这不是失败，是「这条链路在这层验不到」）',
  String(attrs.rendered) === '0' && String(attrs.offset) === '0', JSON.stringify(attrs))

// 收拾现场：移除塞进去的内容
await ev(`(() => { const f = document.querySelector('#sp-filler'); if (f) f.remove(); return 'OK' })()`)
await sleep(300)

// ---------- 上报查询：关键字的清除 / 收藏 / 删除（需求 #66）----------
//
// 三条各自的「看不见的错法」：
//   ① 清除按钮：只在有内容时出现（空输入框上挂个叉是纯噪音）；点了要真的清空 ——
//      受控输入框只改 DOM 的 value 不会触发 React 的 onChange，必须走 setter + input 事件；
//   ② 收藏：界面这一层压的是「存完之后下拉里能选到它」，去重规则在后端压
//      （前端根本没有去重逻辑，靠的就是后端那条唯一索引）；
//   ③ 命中高亮的判据落在**具体的类**上，而不是「有没有 strong」—— 正文里的关键字本来
//      就会包 strong，只数标签的话样式没生效也会是绿的。
console.log('\n--- 上报查询：关键字清除 / 收藏 / 删除（需求 #66）---')

/** 给受控输入框填值：原生 setter + input 事件，绕不开 React 的 onChange。 */
const setKw = async (v) => {
  await ev(`(() => {
    const inp = document.querySelector('#logcat-keyword')
    if (!inp) return 'NO_INPUT'
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inp, ${JSON.stringify(v)})
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    return 'OK' })()`)
  await sleep(220)
}

// ① 清除按钮
await setKw('')
check('★ 关键字为空时不显示清除按钮（空输入框上挂一个叉是纯噪音）',
  (await ev(`document.querySelector('[data-testid="logcat-keyword-clear"]') === null`)) === true)

await setKw('SudReport')
check('★ 输入关键字后出现清除按钮',
  (await ev(`!!document.querySelector('[data-testid="logcat-keyword-clear"]')`)) === true)
check('清除按钮有可读的无障碍名称（不是只有一个图标让人猜）',
  (await ev(`document.querySelector('[data-testid="logcat-keyword-clear"]').getAttribute('aria-label')`)) === '清空关键字',
  await ev(`String(document.querySelector('[data-testid="logcat-keyword-clear"]')?.getAttribute('aria-label'))`))

await ev(`document.querySelector('[data-testid="logcat-keyword-clear"]').click()`)
await sleep(280)
check('★ 点清除按钮后输入框确实空了',
  (await ev(`document.querySelector('#logcat-keyword').value`)) === '',
  await ev(`JSON.stringify(document.querySelector('#logcat-keyword').value)`))
check('清空之后清除按钮自己也收起来了',
  (await ev(`document.querySelector('[data-testid="logcat-keyword-clear"]') === null`)) === true)

// ② 下拉与收藏
check('下拉默认收起（没点之前不该挂在页面上挡着日志）',
  (await ev(`document.querySelector('[data-testid="logcat-keyword-menu"]') === null`)) === true)
await ev(`document.querySelector('[data-testid="logcat-keyword-picker"]').click()`)
await sleep(280)
check('点下拉按钮后菜单展开',
  (await ev(`!!document.querySelector('[data-testid="logcat-keyword-menu"]')`)) === true)

/*
 * ★★ 几何判据（2026-10-07，需求 #67）—— 这一段才是本次的核心。
 *
 * 原缺陷：面板整块都在 DOM 里、`innerText` 也全对，但被控制卡从底边切掉 50px，
 * 于是「注释框 + 保存」看不见也点不到，表现就是用户报的「点开无法完全展开」。
 * 也就是说**「菜单存在吗 / 文字对不对」这类断言在缺陷下照样是绿的** ——
 * 这个亏 v1.6.6（#52）已经吃过一次（当时数是 `[role=option]` 的个数，
 * 而 Radix 无论如何都把选项渲染进 DOM）。所以判据落在几何上：
 *   ① 面板底边不能被任何**裁剪祖先**切到（找 overflow 不是 visible 的祖先，取最小底边）；
 *   ② 面板底部那些控件必须真的能被 `elementFromPoint` 命中 —— 这一条同时排除
 *      「被裁掉」与「被后面那张日志卡盖住」两种看不见的原因。
 */
const menuGeo = async () => JSON.parse(await ev(`(() => {
  const menu = document.querySelector('[data-testid="logcat-keyword-menu"]')
  if (!menu) return JSON.stringify({ err: 'NO_MENU' })
  const r = menu.getBoundingClientRect()
  let cut = window.innerHeight
  let el = menu.parentElement
  while (el && el !== document.documentElement) {
    const cs = getComputedStyle(el)
    if (cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
      cut = Math.min(cut, Math.round(el.getBoundingClientRect().bottom))
    }
    el = el.parentElement
  }
  const probe = (sel) => {
    const e = document.querySelector(sel)
    if (!e) return null
    const b = e.getBoundingClientRect()
    const hit = document.elementFromPoint(
      Math.round(b.left + b.width / 2), Math.round(b.top + b.height / 2))
    return { bottom: Math.round(b.bottom), inside: !!(hit && menu.contains(hit)) }
  }
  const list = document.querySelector('[data-testid="logcat-keyword-list"]')
  return JSON.stringify({
    top: Math.round(r.top), bottom: Math.round(r.bottom), height: Math.round(r.height), cut,
    clippedPx: Math.round(Math.max(0, r.bottom - cut)),
    inViewport: r.top >= 0 && r.bottom <= window.innerHeight,
    listScrolls: !!list && list.scrollHeight > list.clientHeight + 1,
    note: probe('[data-testid="logcat-keyword-note"]'),
    save: probe('[data-testid="logcat-keyword-save"]'),
  }) })()`))

const geo0 = await menuGeo()
check('★ 展开的面板没有被祖先裁切（改前实测：控制卡从底边切掉 50px）',
  geo0.clippedPx === 0,
  `面板底边 ${geo0.bottom} / 裁剪边界 ${geo0.cut} / 被切 ${geo0.clippedPx}px`)
check('★ 面板底部的注释输入框真的可见且点得到（不是「在 DOM 里但被切掉」）',
  geo0.note?.inside === true, JSON.stringify(geo0.note))
check('★ 保存按钮同理 —— 它是这一层唯一的出口，被切掉等于功能没了',
  geo0.save?.inside === true, JSON.stringify(geo0.save))
check('面板整体落在视口里', geo0.inViewport === true, JSON.stringify(geo0))

const kwName = 'UI断言kw' + String(Date.now()).slice(-6)
await setKw(kwName)
await ev(`(() => {
  const inp = document.querySelector('[data-testid="logcat-keyword-note"]')
  if (!inp) return 'NO_NOTE'
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  setter.call(inp, '界面断言用的注释')
  inp.dispatchEvent(new Event('input', { bubbles: true }))
  return 'OK' })()`)
await sleep(220)
await ev(`document.querySelector('[data-testid="logcat-keyword-save"]').click()`)
await sleep(800)

const menuText = () => ev(`(() => {
  const menu = document.querySelector('[data-testid="logcat-keyword-menu"]')
  return menu ? menu.innerText : '' })()`)
const afterSave = await menuText()
check('★ 保存后关键字出现在下拉里', afterSave.includes(kwName), JSON.stringify(afterSave.slice(0, 160)))
check('★ 注释也一起显示出来了（存两部分就是为了这个）',
  afterSave.includes('界面断言用的注释'), JSON.stringify(afterSave.slice(0, 160)))

// ③ 从下拉里选回刚存的那条
await setKw('随便别的词')
await ev(`(() => {
  const menu = document.querySelector('[data-testid="logcat-keyword-menu"]')
  const item = menu && [...menu.querySelectorAll('button')].find(b => b.textContent.includes(${JSON.stringify(kwName)}))
  if (item) item.click()
  return item ? 'OK' : 'NO_ITEM' })()`)
await sleep(320)
check('★ 从下拉里选一条：关键字被填进输入框',
  (await ev(`document.querySelector('#logcat-keyword').value`)) === kwName,
  await ev(`JSON.stringify(document.querySelector('#logcat-keyword').value)`))
check('选完之后菜单自动收起（选完了还挡着日志区是多余的）',
  (await ev(`document.querySelector('[data-testid="logcat-keyword-menu"]') === null`)) === true)

// ④ 删掉刚存的那条，别在库里留测试数据
await ev(`document.querySelector('[data-testid="logcat-keyword-picker"]').click()`)
await sleep(280)
const delAct = await ev(`(() => {
  const menu = document.querySelector('[data-testid="logcat-keyword-menu"]')
  if (!menu) return 'NO_MENU'
  // 直接按 data-testid 前缀定位删除按钮（每一行都有一个），再用 aria-label 认领那一行。
  // 一开始是按「div 文本包含关键字」取最后一个 div —— 那取到的是行**内层**的容器，
  // 而删除按钮是它的兄弟节点，于是永远 NO_DEL。
  const btns = [...menu.querySelectorAll('button[data-testid^="logcat-keyword-del-"]')]
  const btn = btns.find(b => (b.getAttribute('aria-label') || '').includes(${JSON.stringify(kwName)}))
  if (!btn) return 'NO_DEL:' + btns.length
  btn.click(); return 'OK' })()`)
await sleep(900)
/*
 * 判据要落在「那一行还在不在」上，不能拿整块菜单的 innerText 去搜关键字 ——
 * 菜单底部还有一句「将保存：<输入框里的关键字>」，而输入框里此时装的正是这个关键字，
 * 于是「删掉了」也会搜得到，断言永远是红的（这次就白红了一轮）。
 */
const delLeft = await ev(`(() => {
  const menu = document.querySelector('[data-testid="logcat-keyword-menu"]')
  if (!menu) return -1
  return [...menu.querySelectorAll('button[data-testid^="logcat-keyword-del-"]')]
    .filter(b => (b.getAttribute('aria-label') || '').includes(${JSON.stringify(kwName)})).length })()`)
check('★ 点删除后那条从下拉里消失',
  delAct === 'OK' && delLeft === 0, `${delAct} / 同名的删除按钮还剩 ${delLeft} 个`)

// ⑤ 关键字很多：面板必须**自己收口**，而不是长到屏幕外（2026-10-07，需求 #67）
//
// 造数据走接口而不是点十几次保存按钮：点那么多次要几十秒，还容易在中间一步失手，
// 而这里要验的是「面板怎么收口」，不是「保存按钮能不能连点」。
const apiBase = APP.replace(/\/$/, '')
const seedMark = `ui67-${Date.now()}-`
for (let i = 0; i < 15; i++) {
  await fetch(`${apiBase}/api/logcat/keywords`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword: `${seedMark}${String(i).padStart(2, '0')}`, note: `第 ${i + 1} 条，用来把面板撑长` }),
  })
}
// 前端只在挂载时拉一次关键字库，所以播完得重新载入页面才看得到
await send('Page.navigate', { url: APP })
await sleep(2600)
await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '上报查询')
  if (b) b.click(); return 'OK' })()`)
await sleep(1000)
await ev(`document.querySelector('[data-testid="logcat-keyword-picker"]').click()`)
await sleep(350)

const geoTall = await menuGeo()
check('★ 关键字很多时面板自己收口：不被裁、也不顶出屏幕',
  geoTall.clippedPx === 0 && geoTall.inViewport === true,
  `高 ${geoTall.height} / 面板底边 ${geoTall.bottom} / 裁剪边界 ${geoTall.cut} / 被切 ${geoTall.clippedPx}px`)
check('★ 长列表让列表区自己滚，而不是把面板撑到屏幕外',
  geoTall.listScrolls === true, JSON.stringify({ listScrolls: geoTall.listScrolls }))
check('★ 收口之后「保存」那一行仍钉在面板里且点得到（往下滚也不该丢掉出口）',
  geoTall.save?.inside === true, JSON.stringify(geoTall.save))

/*
 * 窗口变矮 —— 这才是「写死 max-height 就会顶出屏幕」的现场（#52 的结论）。
 * 高度是按真实可用空间算的，所以窗口一矮就得跟着收；写死的写法在这里会红。
 */
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 520, deviceScaleFactor: 1, mobile: false })
await sleep(600)
const geoShort = await menuGeo()
check('★★ 窗口变矮后重算，面板仍不被裁（写死 max-height 的写法会在这里红）',
  geoShort.clippedPx === 0 && geoShort.inViewport === true,
  `面板底边 ${geoShort.bottom} / 裁剪边界 ${geoShort.cut} / 被切 ${geoShort.clippedPx}px`)
check('窗口变矮时面板确实收得比之前矮（证明高度是按可用空间算的，不是写死的）',
  geoShort.height < geoTall.height, `${geoTall.height} → ${geoShort.height}`)
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
await sleep(500)

// 收尾：关掉面板 + 把刚播的测试数据删干净
await ev(`document.querySelector('[data-testid="logcat-keyword-picker"]').click()`)
await sleep(250)
const leftover = await (await fetch(`${apiBase}/api/logcat/keywords`)).json()
let removed = 0
for (const it of leftover.filter((x) => x.keyword.startsWith(seedMark))) {
  const r = await (await fetch(`${apiBase}/api/logcat/keywords/${it.id}`, { method: 'DELETE' })).json()
  if (r.ok) removed++
}
check('撑长用的测试数据已清理干净（不在库里留垃圾）', removed === 15, `删掉 ${removed} / 15`)

// 收尾：把输入框恢复成不过滤，后面的段落（含真机链路）从干净状态开始
// （面板在量完之后已经关掉了，这里不能再点一次 —— 那会把它又打开）
await setKw('')

// ---------- 上报查询：真机在时把实时链路真跑一次（2026-10-06，需求 #64）----------
//
// 之前几个版本这里都写着「本机没设备，实时滚动验不到」。现在这台机器插着设备，
// 就把那条链路真跑一遍 —— 它正好覆盖用户报障的那一半：
// ① 有没有内容进来；② 内容会不会被自动清掉。
// 没有设备时整段跳过（只打印一行），所以脚本在没设备的机器上照样是绿的。
const renderedCount = () => ev(`(() => {
  const el = document.querySelector('[data-testid="logcat-scroll"]')
  return el ? Number(el.getAttribute('data-rendered') || 0) : -1 })()`)
const totalLines = () => ev(`(() => {
  const m = document.body.innerText.match(/累计\\s*(\\d+)\\s*行/)
  return m ? Number(m[1]) : -1 })()`)

if (!hasSelectedDevice) {
  console.log('\n（没有在线设备：跳过「真机实时链路」这几条，它们需要一台真机）')
} else {
  console.log('\n--- 上报查询：真机实时链路 ---')
  await ev(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim().includes('开始监听'))
    if (b && !b.disabled) b.click()
    return 'OK' })()`)
  await sleep(2500)
  const listening = await ev(`document.body.innerText.includes('监听中')`)
  check('★ 点「开始监听」后进入监听中', listening === true, String(listening))

  const n1 = await totalLines()
  const r1 = await renderedCount()
  check('★ 真的取到并渲染出了上报内容（不是空的）', r1 > 0 && n1 > 0, `渲染 ${r1} 行 / 累计 ${n1} 行`)
  check('★ 首轮就拿到整整一段历史（需求 #64 报的「内容少得非常多」就在这条上）',
    n1 >= 100, `累计只有 ${n1} 行`)

  /*
   * 「每轮都在取到新行」这条**不能指望设备恰好有日志在刷**。
   *
   * 第一版就是这么写的，跑出来红：`2000 → 2000`。查下来不是轮询坏了 ——
   * 是这台设备当时完全安静，2 秒内 logcat 缓冲一行都没长
   * （手工实测 `t0=9784 → t+2s=9784`）。一条「看运气」的断言比没有断言更糟：
   * 它会在功能好好的时候把人引去查轮询。
   *
   * 改成**自己造一行**：往设备写一条带唯一标记的日志，再看它有没有被取到、有没有渲染出来。
   * 这样断言自足（跟设备忙不忙无关），而且顺带把「设备侧新日志 → 后端增量 → 界面」整条链
   * 真验了一遍 —— 比原来那条只数行数的还更严格。
   */
  const marker = `sp-ui-check-${Date.now()}`
  /*
   * 行内容刻意带上一个 `key=value`：
   * 下面「命中高亮」那两条需要一个**确定存在**的关键字。用设备上的历史日志去碰运气
   * （碰 `isIn`、碰 tag）都会偶发不命中，而「不命中」在这两条断言里的表现是
   * 「strong 数量 0」—— 那看起来与「高亮根本没生效」一模一样，会把排查带偏
   * （这次就是这么白红了两条）。自带一行则百分之百命中。
   * 另：tag 在行首 meta 里，**不参与**正文高亮，所以不能拿 `SudReport` 当关键字。
   */
  const markerLine = `${marker} isIn=true`
  const adb = process.env.ADB
    || `${process.env.ANDROID_HOME || `${process.env.HOME}/Library/Android/sdk`}/platform-tools/adb`
  const serial = (bs.deviceCell || '').replace(/^设备\s*/, '')
  let wroteMarker = false
  try {
    execFileSync(adb, ['-s', serial, 'shell', 'log', '-t', 'SudReport', '-p', 'I', markerLine], { timeout: 8000 })
    wroteMarker = true
  } catch (e) {
    console.log(`  （往设备写标记日志失败，下面两条按跳过处理：${e instanceof Error ? e.message : e}）`)
  }

  await sleep(1800)
  const n2 = await totalLines()
  const noEmptyText = await ev(`!document.body.innerText.includes('还没有内容')`)
  check('★ 内容在继续累积（自己往设备写一行，它应当被取到）',
    wroteMarker ? n2 > n1 : true,
    wroteMarker ? `${n1} → ${n2}` : `${n1} → ${n2}（写日志失败，本条按跳过计）`)
  check('★ 那行标记日志真的渲染出来了（设备侧新日志 → 界面 这条链）',
    wroteMarker
      ? (await ev(`document.body.innerText.includes(${JSON.stringify(marker)})`)) === true
      : true,
    marker)

  /*
   * 需求 #66：命中的关键字要做「放大 + 改色」的差异化处理。
   *
   * 判据**必须落到具体的类上**：正文里的关键字本来就会被包成 <strong>（那是 #60 就有的行为），
   * 只数 strong 标签的话，「样式压根没生效」也会是绿的 —— 断言会白给。
   * 所以这里查的是 className 里有没有 `amber-700`（命中专用色）与 `text-sm`（放大）。
   *
   * 关键字用**上面刚写进设备的那一行里的唯一标记**（`sp-ui-check-<ts>`）：
   * 它一定存在于日志缓冲里，所以一定命中；用固定的 `isIn` 则要看设备上恰好还有没有。
   * 另：tag 在行首 meta 里，不参与正文高亮，不能拿 `SudReport` 当关键字。
   */
  await ev(`(() => {
    const inp = document.querySelector('#logcat-keyword')
    if (!inp) return 'NO_INPUT'
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inp, ${JSON.stringify(marker)})
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    return 'OK' })()`)
  await sleep(800)
  const hitInfo = JSON.parse(await ev(`(() => {
    const sc = document.querySelector('[data-testid="logcat-scroll"]')
    const strongs = sc ? [...sc.querySelectorAll('strong')] : []
    const hits = strongs.filter(e => (e.className || '').includes('amber-700'))
    const first = hits[0]
    return JSON.stringify({
      strong: strongs.length,
      hits: hits.length,
      cls: first ? first.className : '',
      text: first ? (first.textContent || '').slice(0, 40) : '',
    }) })()`)) || {}
  check('★★ 命中关键字的那一段带上了「命中样式」的类（放大 + 改色）',
    hitInfo.hits > 0 && /text-sm/.test(hitInfo.cls) && /amber-700/.test(hitInfo.cls),
    JSON.stringify(hitInfo))
  check('命中的恰好是关键字本身（不是把整行染色）',
    hitInfo.text === marker, JSON.stringify(hitInfo))
  // 把关键字清掉，别让它影响后面「中途没有被清空」那两条（那两条数的是累计行数）
  await ev(`(() => {
    const b = document.querySelector('[data-testid="logcat-keyword-clear"]')
    if (b) b.click(); return 'OK' })()`)
  await sleep(200)

  check('★ 中途没有被清空（需求 #64 报的「隔几秒自动清除缓存」就在这条上）',
    n2 >= n1, `${n1} → ${n2}`)

  // 停止，别把设备一直挂着轮询
  await ev(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim().includes('停止'))
    if (b && !b.disabled) b.click()
    return 'OK' })()`)
  await sleep(800)
  const stopped = await ev(`document.body.innerText.includes('已停止')`)
  check('点「停止」后回到已停止（不留一个一直在轮询的页面）', stopped === true, String(stopped))
  check('日志区里没有「还没有内容」这种空态提示', noEmptyText === true, String(noEmptyText))
}

// ---------- 性能测试：人机对局（需求 #68）----------
//
// 这一段的判据都落在「页面上有没有那个东西、点了有没有反应」上。要防的错法很具体：
//   · 模式下拉漏了第三项 —— 功能整块进不去，而代码里明明写着；
//   · 选中了模式却不出现配置区 —— 用户找不到「四条用例绑哪几条」；
//   · mg_id/app_id 填了存不下来 —— 这一条最像「功能坏了」：推送时用了错的游戏身份，
//     而现象只是「房间里人不对」，从压测结果上完全看不出是配置没存上。
console.log('\n--- 性能测试：人机对局（需求 #68）---')

const navPerf = await ev(`(() => {
  const b = [...document.querySelectorAll('button, a, [role=button]')]
    .find(e => e.textContent.trim() === '性能测试')
  if (!b) return 'NOT_FOUND'
  b.click(); return 'OK' })()`)
check('侧边栏能切到性能测试页（不是死入口）', navPerf === 'OK', navPerf)
await sleep(1600)

// 模式下拉：本次新增的第三项
await ev(`(() => {
  const t = document.querySelector('[data-testid="perf-mode"]')
  if (t) t.click()
  return 'OK' })()`)
await sleep(500)
const modeOpts = await ev(`(() => {
  const opts = [...document.querySelectorAll('[role=option]')]
  return JSON.stringify(opts.map(o => (o.textContent || '').trim())) })()`)
// 第三种模式 2026-10-10 由「人机对局（接口推送）」改名为「游戏内性能监测」（需求：三种测试改名）
check('★ 模式下拉里有「游戏内性能监测」——第三种模式，漏了功能就整块进不去',
  modeOpts.includes('游戏内性能监测'), modeOpts)

const picked = await ev(`(() => {
  const o = [...document.querySelectorAll('[role=option]')]
    .find(e => (e.textContent || '').includes('游戏内性能监测'))
  if (!o) return 'NO_OPTION'
  o.click(); return 'OK' })()`)
check('选中得了人机对局这一项', picked === 'OK', picked)
await sleep(700)

// 人机配置面板（v1.10.4 方案 A）：默认收起，只露一行「当前绑了哪几条用例」的摘要。
// 所以这一段分两步验：先验收起态（摘要与「修改」按钮在、四个下拉**不在**），
// 点开之后再验展开内容 —— 只验展开态的话，「默认收起」这条约定没人守着。
const collapsedUi = JSON.parse(await ev(`(() => {
  const q = (s) => !!document.querySelector(s)
  return JSON.stringify({
    title: document.body.innerText.includes('游戏内性能监测配置'),
    summary: q('[data-testid="perf-ai-cfg-summary"]'),
    toggle: q('[data-testid="perf-ai-cfg-toggle"]'),
    batch: q('[data-testid="perf-ai-case-batch"]'),
  }) })()`))
check('★ 配置区默认收起：摘要行与「修改」按钮在，四条下拉不在',
  collapsedUi.title && collapsedUi.summary && collapsedUi.toggle && collapsedUi.batch === false,
  JSON.stringify(collapsedUi))

await ev(`(() => {
  const b = document.querySelector('[data-testid="perf-ai-cfg-toggle"]')
  if (b) b.click()
})()`)
await sleep(600)

const cfgUi = JSON.parse(await ev(`(() => {
  const q = (s) => !!document.querySelector(s)
  return JSON.stringify({
    batch: q('[data-testid="perf-ai-case-batch"]'),
    start: q('[data-testid="perf-ai-case-start"]'),
    end: q('[data-testid="perf-ai-case-end"]'),
    clear: q('[data-testid="perf-ai-case-clear"]'),
    count: q('[data-testid="perf-ai-count"]'),
    keyword: q('[data-testid="perf-ai-end-keyword"]'),
    hint: document.body.innerText.includes('批量加入'),
  }) })()`))
check('★ 点「修改」后四个环节的用例绑定都出来了',
  cfgUi.batch && cfgUi.start && cfgUi.end && cfgUi.clear,
  JSON.stringify(cfgUi))
check('人机数量输入框在', cfgUi.count === true, JSON.stringify(cfgUi))
check('对局结束关键字输入框在（v1.9.4 #78：留空 = 不做提前结束检测）',
  cfgUi.keyword === true, JSON.stringify(cfgUi))
check('配置区写明了「不指定时会去找哪一条」（默认用例名）', cfgUi.hint === true, JSON.stringify(cfgUi))

// 通用运行选项（v1.10.0 #84）：开关在「运行控制」区，三个模式都适用，所以不挂在配置区里
const trimToggle = await ev(`!!document.querySelector('[data-testid="perf-trim-baseline"]')`)
check('★ 运行控制里有「采样前触发内存回收」开关（v1.10.0 #84：区分缓存与真泄漏）',
  trimToggle === true, String(trimToggle))

// 游戏行的推送身份弹窗。占位游戏由 run-ui.sh 插进临时库 —— 扫描游戏要真机，
// 而这一段的每条断言都挂在游戏行上，没有游戏就只能全部跳过（跳过看起来和通过一样）。
const gameBtn = await ev(`(() => {
  const b = document.querySelector('[data-testid^="perf-game-ids-"]')
  if (!b) return 'NO_BTN'
  b.click(); return 'OK' })()`)
check('★ 游戏行上有「推送身份」入口（占位游戏由 run-ui.sh 准备）',
  gameBtn === 'OK', gameBtn)
await sleep(600)

const filled = await ev(`(() => {
  const mg = document.querySelector('[data-testid="perf-game-mg-id"]')
  const ap = document.querySelector('[data-testid="perf-game-app-id"]')
  if (!mg || !ap) return 'NO_DIALOG'
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  setter.call(mg, '9876543210'); mg.dispatchEvent(new Event('input', { bubbles: true }))
  setter.call(ap, '1234567890'); ap.dispatchEvent(new Event('input', { bubbles: true }))
  return 'OK' })()`)
check('点开后是 mg_id / app_id 两个输入框', filled === 'OK', filled)
await sleep(300)
// 守卫式点击：这条断言跑红时（没有这个按钮）不能把脚本打崩 ——
// 崩掉会让它后面所有断言都不再执行，看起来像「脚本坏了」而不是「功能没做」。
// 这一处是第一版裸 .click() 踩出来的：红跑在这里抛 TypeError，整段验证就此中断。
await ev(`(() => {
  const b = document.querySelector('[data-testid="perf-game-ids-save"]')
  if (!b) return 'NO_SAVE'
  b.click(); return 'OK' })()`)
await sleep(1400)
check('保存后弹窗关掉了（存完还赖着会让人以为没存上）',
  (await ev(`document.querySelector('[data-testid="perf-game-mg-id"]') === null`)) === true)

// 重新打开：值必须读回来 —— 这一条才是「每游戏自定义设置」真的落地的证据
await ev(`(() => {
  const b = document.querySelector('[data-testid^="perf-game-ids-"]')
  if (b) b.click(); return 'OK' })()`)
await sleep(700)
const reread = await ev(`(() => {
  const mg = document.querySelector('[data-testid="perf-game-mg-id"]')
  const ap = document.querySelector('[data-testid="perf-game-app-id"]')
  return JSON.stringify({ mg: mg ? mg.value : null, ap: ap ? ap.value : null }) })()`)
const rv = JSON.parse(reread)
check('★ 填的 mg_id / app_id 存下来了，重新打开能读回（这就是「按游戏自定义」的落点）',
  rv.mg === '9876543210' && rv.ap === '1234567890', reread)
check('★ 游戏行上的入口被标成「已配置」（有值时高亮，未配的不抢眼）',
  (await ev(`(() => {
    const b = document.querySelector('[data-testid^="perf-game-ids-"]')
    return b ? b.className.includes('text-primary') : false })()`)) === true)

// ---- 需求 #70：弹窗八项推送配置（身份/房号/真人数/人机数/等级/时长/模板/预览）----
console.log('\n--- 性能测试：按游戏的推送配置（需求 #69/#70）---')
const ext = JSON.parse(await ev(`(() => {
  const q = (s) => !!document.querySelector(s)
  return JSON.stringify({
    mg: q('[data-testid="perf-game-mg-id"]'),
    room: q('[data-testid="perf-game-room-id"]'),
    human: q('[data-testid="perf-game-human-count"]'),
    ai: q('[data-testid="perf-game-ai-count"]'),
    level: q('[data-testid="perf-game-ai-level"]'),
    seconds: q('[data-testid="perf-game-seconds"]'),
    machine: q('[data-testid="perf-game-machine-uid"]'),
    override: q('[data-testid="perf-game-body-override"]'),
  }) })()`))
check('★ 弹窗里八项控件齐全（身份/房号/名单两项/等级/时长/本机账号）+ 模板编辑区',
  ext.mg === true && ext.room === true && ext.human === true && ext.ai === true
    && ext.level === true && ext.seconds === true && ext.machine === true && ext.override === true,
  JSON.stringify(ext))

// 填真人数 1 + 人机数 2 + 等级 3 + 房号：名单预览实时出现且写明真人/人机区间与等级
await ev(`(() => {
  const si = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  const set = (tid, v) => {
    const el = document.querySelector(tid)
    if (el) { si.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  }
  set('[data-testid="perf-game-human-count"]', '1')
  set('[data-testid="perf-game-ai-count"]', '2')
  set('[data-testid="perf-game-ai-level"]', '3')
  set('[data-testid="perf-game-room-id"]', '9527')
  set('[data-testid="perf-game-machine-uid"]', 'me007')
  return 'OK' })()`)
await sleep(300)
const roster = await ev(`(() => {
  const el = document.querySelector('[data-testid="perf-game-roster-preview"]')
  return el ? (el.textContent || '') : 'NO_PREVIEW' })()`)
check('★ 预览写明真人/人机区间与等级（真人首位=本机账号 me007 + cy02～cy03 人机 ai_level=3）',
  roster.includes('将推送 3 人') && roster.includes('me007（真人 is_ai=0）')
    && roster.includes('cy02～cy03') && roster.includes('ai_level=3'), roster)

// 对局时长填 300 一起保存
await ev(`(() => {
  const si = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  const el = document.querySelector('[data-testid="perf-game-seconds"]')
  if (el) { si.call(el, '300'); el.dispatchEvent(new Event('input', { bubbles: true })) }
  return 'OK' })()`)
await sleep(200)
await ev(`(() => {
  const b = document.querySelector('[data-testid="perf-game-ids-save"]')
  if (!b) return 'NO_SAVE'
  b.click(); return 'OK' })()`)
await sleep(1400)
check('八项一起保存：弹窗关闭（不赖着）',
  (await ev(`document.querySelector('[data-testid="perf-game-ai-count"]') === null`)) === true)

// 重开：关键项都要读回来 —— 按游戏配置真正落地的证据
await ev(`(() => {
  const b = document.querySelector('[data-testid^="perf-game-ids-"]')
  if (b) b.click(); return 'OK' })()`)
await sleep(700)
const back69 = JSON.parse(await ev(`(() => {
  const v = (tid) => { const el = document.querySelector(tid); return el ? el.value : null }
  return JSON.stringify({ room: v('[data-testid="perf-game-room-id"]'),
    human: v('[data-testid="perf-game-human-count"]'), ai: v('[data-testid="perf-game-ai-count"]'),
    level: v('[data-testid="perf-game-ai-level"]'), sec: v('[data-testid="perf-game-seconds"]'),
    uid: v('[data-testid="perf-game-machine-uid"]') }) })()`))
check('★ 房号/真人数/人机数/等级/时长/本机账号重新打开都能读回（按游戏配置真正落了地）',
  back69.room === '9527' && back69.human === '1' && back69.ai === '2'
    && back69.level === '3' && back69.sec === '300' && back69.uid === 'me007',
  JSON.stringify(back69))

// 关掉弹窗，别挡着后面的断言
await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '取消')
  if (b) { b.click(); return 'OK' } return 'NO_CANCEL' })()`)
await sleep(400)

// #71：AI_ROOM 模式下面板不再有「单圈采样秒数/对局时长」输入（时长只认游戏配置）
check('★ 人机对局模式下启动面板没有对局时长输入（删掉了多余的第二个来源）',
  (await ev(`document.querySelector('[data-testid="perf-sample-seconds"]') === null`)) === true)

// 这一模式不点坐标，所以不该被未标定的坐标分组卡住
const startOk = await ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '开始')
  return b ? !b.disabled : 'NO_BTN' })()`)
check('人机对局模式下「开始」可用（这一模式不点坐标，不该被坐标标定挡住）',
  startOk === true, String(startOk))

// 收尾：清掉占位游戏上刚填的全部推送配置（#70 起是八项），切回正常压测（不影响后面的段落）
await ev(`(() => {
  const b = document.querySelector('[data-testid^="perf-game-ids-"]')
  if (b) b.click(); return 'OK' })()`)
await sleep(600)
await ev(`(() => {
  const si = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  const st = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
  const clear = (tid, isArea) => {
    const el = document.querySelector(tid)
    if (el) { (isArea ? st : si).call(el, ''); el.dispatchEvent(new Event('input', { bubbles: true })) }
  }
  clear('[data-testid="perf-game-mg-id"]')
  clear('[data-testid="perf-game-app-id"]')
  clear('[data-testid="perf-game-room-id"]')
  clear('[data-testid="perf-game-human-count"]')
  clear('[data-testid="perf-game-ai-count"]')
  clear('[data-testid="perf-game-ai-level"]')
  clear('[data-testid="perf-game-seconds"]')
  clear('[data-testid="perf-game-body-override"]', true)
  return 'OK' })()`)
await sleep(250)
await ev(`(() => {
  const b = document.querySelector('[data-testid="perf-game-ids-save"]')
  if (!b) return 'NO_SAVE'
  b.click(); return 'OK' })()`)
await sleep(1000)

console.log(`\n  通过 ${ok} 项，失败 ${bad} 项`)
ws.close()
process.exit(bad === 0 ? 0 : 1)
