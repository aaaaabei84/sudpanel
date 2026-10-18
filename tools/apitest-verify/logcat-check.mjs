// 上报查询的纯函数断言（2026-10-06，需求 #60）。
//
// 直接 import 前端源文件（不经打包），所以验的就是页面上真跑的那份 ——
// 与 json-text-check / var-suggest-check 同一套路。
//
// 为什么这块值得单独压：级别解析与过滤写错了，表现是「屏幕上少了几行」，
// 而少的那几行没人会察觉（不像崩溃或白屏那样自曝）。所以这里把两条最容易被写成
// 相反语义的规则钉死：级别集合为空 = 不过滤；认不出级别的行不拦。
import {
  KEYWORD_HIT_CLASS,
  KV_VALUE_CLASS,
  LEVEL_CLASS,
  LOG_LEVELS,
  META_CLASS,
  filterLogLines,
  levelOf,
  parseLogLine,
  splitBodySegments,
  splitByKeywords,
  splitMeta,
} from '../../frontend/src/lib/log-line.ts'
import {
  OVERLAY_MIN_PX,
  availableBelow,
  clippingBottoms,
  overlayMaxHeight,
} from '../../frontend/src/lib/overlay-space.ts'

let pass = 0
let fail = 0
function check(name, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  ✓ ${name}`)
  } else {
    fail++
    console.log(`  ✗ ${name}  ${detail}`)
  }
}

const D = '10-06 10:00:01.001  1234  1250 D SudReport: debug line'
const I = '10-06 10:00:02.002  1234  1250 I SudReport: info line'
const W = '10-06 10:00:03.003  1234  1250 W SudReport: warn line'
const E = '10-06 10:00:04.004  1234  1250 E SudReport: err line'
const V = '10-06 10:00:05.005  1234  1250 V SudReport: verbose line'
const CONT = '    continuation of a multi-line log'

console.log('\n=== 级别解析 ===')
check('D 行取到 D', levelOf(D) === 'D', levelOf(D))
check('I 行取到 I', levelOf(I) === 'I', levelOf(I))
check('W 行取到 W', levelOf(W) === 'W', levelOf(W))
check('E 行取到 E', levelOf(E) === 'E', levelOf(E))
check('V 行取到 V', levelOf(V) === 'V', levelOf(V))
check('续行（无级别前缀）取不到级别 → null（不猜）', levelOf(CONT) === null, String(levelOf(CONT)))
check('空串安全', levelOf('') === null, String(levelOf('')))
check('随意文本取不到级别', levelOf('hello world') === null, String(levelOf('hello world')))
check('级别字母必须在位置上（正文里出现 D 不算）',
  levelOf('10-06 10:00:01.001  1234  1250 D tag: value is D') === 'D')
check('六个级别齐全', LOG_LEVELS.length === 6 && LOG_LEVELS.join('') === 'VDIWEF', LOG_LEVELS.join(''))

console.log('\n=== 行拆分（meta / body）===')
const p = parseLogLine(I)
check('meta 是「时间 pid tid 级别 tag」那一段', p.meta === '10-06 10:00:02.002  1234  1250 I SudReport', `[${p.meta}]`)
check('body 是冒号之后的正文', p.body === 'info line', `[${p.body}]`)
check('raw 原样保留（复制原始行要用它）', p.raw === I)
const np = parseLogLine(CONT)
check('不是标准行时整行都算 body，一个字不丢', np.meta === '' && np.body === CONT, JSON.stringify(np))
check('正文里带冒号时不会被截断',
  parseLogLine('10-06 10:00:01.001  1  2 I T: a: b: c').body === 'a: b: c')

console.log('\n=== 过滤：关键字 ===')
const all = [D, I, W, E, V, CONT]
const none = new Set()
check('关键字空 = 不过滤，全部保留', filterLogLines(all, '', none).length === 6)
check('关键字空白串也当不过滤', filterLogLines(all, '   ', none).length === 6)
check('关键字命中的留下', filterLogLines(all, 'warn', none).length === 1)
check('关键字正则是按字面（不当正则解释）', filterLogLines(all, 'S.Report', none).length === 0,
  String(filterLogLines(all, 'S.Report', none).length))
check('关键字一起作用于续行', filterLogLines(all, 'continuation', none).length === 1)

console.log('\n=== 过滤：级别 ===')
check('★ 级别集合为空 = 不过滤（不是「全滤掉」）', filterLogLines(all, '', new Set()).length === 6,
  String(filterLogLines(all, '', new Set()).length))
check('只勾 E：只留 E 与认不出级别的续行', filterLogLines(all, '', new Set(['E'])).length === 2,
  JSON.stringify(filterLogLines(all, '', new Set(['E']))))
check('勾 D+I+W：三条留下 + 续行', filterLogLines(all, '', new Set(['D', 'I', 'W'])).length === 4)
check('★ 认不出级别的行不受级别过滤影响（否则「少了几行」无法解释）',
  filterLogLines([CONT], '', new Set(['E'])).length === 1)
check('关键字与级别同时生效（与关系）',
  filterLogLines(all, 'Report', new Set(['E'])).length === 1)
check('关键字命中续行时，级别过滤不拦它',
  filterLogLines(all, 'continuation', new Set(['E'])).length === 1)

console.log('\n=== 上色：行首 meta 拆段 ===')
const mp = splitMeta('10-06 10:00:02.002  1234  1250 I SudReport')
check('五段都取到了', mp && mp.time === '10-06 10:00:02.002' && mp.pid === '1234'
  && mp.tid === '1250' && mp.level === 'I' && mp.tag === 'SudReport', JSON.stringify(mp))
check('★ 拆不出结构时返回 null（宁可不上色，也不把不是时间的东西放进「时间那栏」）',
  splitMeta('这不是标准日志行') === null, JSON.stringify(splitMeta('这不是标准日志行')))
check('空串返回 null', splitMeta('') === null)
check('pid/tid 位数变化仍能拆', (() => {
  const m = splitMeta('12-31 23:59:59.999  9  10 W Tag:')
  return m && m.pid === '9' && m.tid === '10' && m.level === 'W'
})(), JSON.stringify(splitMeta('12-31 23:59:59.999  9  10 W Tag:')))
check('六个级别都能拆出来',
  LOG_LEVELS.every((lv) => splitMeta(`10-06 10:00:02.002  1  2 ${lv} T`)?.level === lv))

console.log('\n=== 上色：正文键值对切分 ===')
const segs = splitBodySegments('onEvent isIn=true roomId=3333 msg=hello')
check('切出 3 个键值对', segs.filter((s) => s.kind === 'kv').length === 3, JSON.stringify(segs))
check('键与值都完整（键不带 =、值不带空格）', (() => {
  const kvs = segs.filter((s) => s.kind === 'kv')
  // 先卡条数再取元素：直接取 kvs[0] 在没有键值对时会抛异常，
  // 那会让脚本「崩」而不是「红」—— 崩了看不到后面任何一条断言的结果。
  return kvs.length === 3
    && kvs[0].key === 'isIn' && kvs[0].value === 'true'
    && kvs[1].key === 'roomId' && kvs[1].value === '3333'
    && kvs[2].key === 'msg' && kvs[2].value === 'hello'
})(), JSON.stringify(segs))
check('值的类型判对：true=literal / 3333=number / hello=text', (() => {
  const kvs = segs.filter((s) => s.kind === 'kv')
  return kvs.length === 3 && kvs[0].valueType === 'literal'
    && kvs[1].valueType === 'number' && kvs[2].valueType === 'text'
})(), JSON.stringify(segs.map((s) => s.valueType)))
check('★ 拼回去必须等于原文（切错了会丢字符或重复）',
  segs.map((s) => (s.kind === 'kv' ? `${s.key}=${s.value}` : s.text)).join('')
  === 'onEvent isIn=true roomId=3333 msg=hello',
  JSON.stringify(segs))
check('普通文本段被原样保留（前后都有文本时）',
  segs[0].kind === 'text' && segs[0].text === 'onEvent ', JSON.stringify(segs[0]))
check('引号包起来的值算一个整体', (() => {
  const kvs = splitBodySegments('name="hello world" x=1').filter((x) => x.kind === 'kv')
  return kvs.length === 2 && kvs[0].value === '"hello world"' && kvs[1].value === '1'
})(), JSON.stringify(splitBodySegments('name="hello world" x=1')))
check('小数与负数也判成 number', (() => {
  const kvs = splitBodySegments('a=-1.5 b=0').filter((x) => x.kind === 'kv')
  return kvs.length === 2 && kvs[0].valueType === 'number' && kvs[1].valueType === 'number'
})())
check('null/undefined 判成 literal',
  splitBodySegments('a=null b=undefined').every((x) => x.kind !== 'kv' || x.valueType === 'literal'))
check('没有键值对时整段是一个 text 段（不上色，但不是不存在）',
  JSON.stringify(splitBodySegments('hello world')) === JSON.stringify([{ kind: 'text', text: 'hello world' }]),
  JSON.stringify(splitBodySegments('hello world')))
check('空串返回空数组', splitBodySegments('').length === 0)
check('键名允许点号（如 data.roomId）', (() => {
  const kvs = splitBodySegments('data.roomId=3333').filter((x) => x.kind === 'kv')
  return kvs.length === 1 && kvs[0].key === 'data.roomId'
})(), JSON.stringify(splitBodySegments('data.roomId=3333')))
check('不等于号的情况不误切（a == b）', (() => {
  const kvs = splitBodySegments('a == b').filter((x) => x.kind === 'kv')
  return kvs.length === 0 || kvs.every((k) => k.value !== '=')
})(), JSON.stringify(splitBodySegments('a == b')))

console.log('\n=== 上色：配色表本身 ===')
// 配色表放在纯逻辑层就是为了这几条：两个级别写成同一个颜色，界面上就再也分不出它们，
// 而这种错没人会主动去查（不像崩溃会自己喊出来）。
check('六个级别都有配色（漏一个就有一类日志不上色）',
  LOG_LEVELS.every((lv) => typeof LEVEL_CLASS[lv] === 'string' && LEVEL_CLASS[lv].length > 0),
  JSON.stringify(Object.keys(LEVEL_CLASS)))
check('★ 六个级别的配色互不相同（重复了就等于两个级别长一样）',
  new Set(LOG_LEVELS.map((lv) => LEVEL_CLASS[lv])).size === 6,
  JSON.stringify(LOG_LEVELS.map((lv) => [lv, LEVEL_CLASS[lv]])))
check('E（错误）用红色系', (LEVEL_CLASS.E || '').includes('red'), LEVEL_CLASS.E)
check('W（警告）用琥珀/黄色系', /amber|yellow/.test(LEVEL_CLASS.W || ''), LEVEL_CLASS.W)
check('I（信息）用绿色系', /emerald|green/.test(LEVEL_CLASS.I || ''), LEVEL_CLASS.I)
check('D（调试）用蓝色系', /blue/.test(LEVEL_CLASS.D || ''), LEVEL_CLASS.D)
check('三种值类型都有配色且互不相同',
  new Set(Object.values(KV_VALUE_CLASS)).size === 3, JSON.stringify(KV_VALUE_CLASS))
check('数字值与 JSON 那边同一套配色（orange）',
  /\borange/.test(KV_VALUE_CLASS.number), KV_VALUE_CLASS.number)
check('字面量与 JSON 那边同一套配色（purple）',
  /\bpurple/.test(KV_VALUE_CLASS.literal), KV_VALUE_CLASS.literal)
check('meta 三段（时间/pid·tid/tag）配色互不相同',
  new Set(Object.values(META_CLASS)).size === 3, JSON.stringify(META_CLASS))

console.log('\n=== 关键字命中切分（需求 #66）===')
// 这一段写错了**不会报错**：要么是「某些片段没上色」（看着像样式没生效），
// 要么是关键字里含 `(` 时 `new RegExp` 直接抛错、整块日志区白屏。
// 两种表现都不像「切分逻辑写错了」，所以必须在这里把它压住。
{
  const one = (segs) => segs.map((s) => (s.hit ? '[' + s.text + ']' : s.text)).join('')

  check('没命中时返回整段（调用方不必特判「空数组」这个分支）',
    JSON.stringify(splitByKeywords('hello', ['zzz'])) === JSON.stringify([{ text: 'hello', hit: false }]))
  check('命中一次', one(splitByKeywords('isIn=true', ['isIn'])) === '[isIn]=true')
  check('命中多次都标出来', one(splitByKeywords('aXbXc', ['X'])) === 'a[X]b[X]c')
  check('命中在行首', one(splitByKeywords('Xabc', ['X'])) === '[X]abc')
  check('命中在行尾', one(splitByKeywords('abcX', ['X'])) === 'abc[X]')
  check('整段就是关键字', one(splitByKeywords('X', ['X'])) === '[X]')
  check('空文本返回空数组（不返回一个空片段）', splitByKeywords('', ['X']).length === 0)

  // ① 长关键字优先：互为前缀时短的不能把长的切开
  check('★ 互为前缀时长的那条先命中（isInT 不该被切成 [isIn] + 不上色的 T）',
    one(splitByKeywords('isInT=1', ['isIn', 'isInT'])) === '[isInT]=1',
    one(splitByKeywords('isInT=1', ['isIn', 'isInT'])))
  check('★ 两个都在文本里时各命中各的',
    one(splitByKeywords('isIn=true isInT=1', ['isIn', 'isInT'])) === '[isIn]=true [isInT]=1',
    one(splitByKeywords('isIn=true isInT=1', ['isIn', 'isInT'])))

  // ② 转义：关键字里完全可能有正则元字符
  check('★ 关键字含「(」不抛错（不转义时 new RegExp 直接炸 → 整块日志区白屏）',
    (() => {
      try {
        one(splitByKeywords('call(1)', ['call(']))
        return true
      } catch {
        return false
      }
    })())
  check('★ 含「.」的关键字按字面匹配，不当成「任意字符」',
    one(splitByKeywords('a.b axb', ['.'])) === 'a[.]b axb',
    one(splitByKeywords('a.b axb', ['.'])))
  check('★ 含「[」与「*」的关键字也不抛错',
    (() => {
      try {
        one(splitByKeywords('a[0]*end', ['[0]*']))
        return true
      } catch {
        return false
      }
    })())

  // ③ 空与重复
  check('空关键字被滤掉（拼进正则会变成空分支，切出一堆空片段）',
    one(splitByKeywords('abc', ['', '   '])) === 'abc')
  check('重复的关键字不产生重复片段', one(splitByKeywords('aXb', ['X', 'X'])) === 'a[X]b')
  check('关键字比文本长时不命中', one(splitByKeywords('ab', ['abcdef'])) === 'ab')
  check('★ 切出来的片段拼回去等于原文（一个字符都不能丢）',
    (() => {
      const src = 'onEvent isIn=true roomId=3333 end'
      return splitByKeywords(src, ['isIn', 'roomId']).map((s) => s.text).join('') === src
    })())
}

console.log('\n=== 关键字命中样式（需求 #66）===')
// 样式写错了只会「看着像没生效」，没人会为它写单测 —— 所以把两条硬要求钉在这里：
// ① 必须既有放大又有改色（使用者的原话）；② 字号不能大到把行顶高。
{
  check('★ 命中样式同时有「放大」与「改色」两件事（需求 #66 的原话）',
    /text-(xs|sm|base|lg)/.test(KEYWORD_HIT_CLASS) && /text-amber-\d+/.test(KEYWORD_HIT_CLASS),
    KEYWORD_HIT_CLASS)
  check('★ 字号比正文（text-xs）大 —— 否则「放大」这两个字没落地',
    /text-(sm|base|lg)/.test(KEYWORD_HIT_CLASS), KEYWORD_HIT_CLASS)
  check('★ 不含会撑高行高的字号：行高由父级 leading-4(16px) 决定，字号一大就把那一行顶高',
    !/text-(base|lg|xl|2xl|3xl)/.test(KEYWORD_HIT_CLASS), KEYWORD_HIT_CLASS)
  check('命中样式与正文键值、meta 那几套配色都不重样（不然分不出「命中」与「字段名」）',
    ![KV_VALUE_CLASS.number, KV_VALUE_CLASS.literal, KV_VALUE_CLASS.text,
      META_CLASS.time, META_CLASS.tag].some((c) => c === KEYWORD_HIT_CLASS),
    KEYWORD_HIT_CLASS)
}

console.log('\n=== 下拉浮层的可用高度（需求 #67）===')
/*
 * 这一块的判据必须落在**几何**上，而不是「元素在不在 DOM 里」。
 *
 * 缺陷原状（2026-10-07 实测）：面板整块都在 DOM 里、`innerText` 也全对，
 * 但它 256→358，而最近的裁剪边界（控制卡 `overflow-hidden`）在 308 ——
 * 底部的注释框与保存按钮被切掉 50px，看不见也点不到。
 * 换句话说：任何「菜单存在吗 / 文字对不对」的断言，在这种缺陷下**照样是绿的**。
 * 所以这里压的是「算出来的可用高度对不对」，界面层再压「有没有真的被切」（见 ui-check.mjs）。
 */
{
  // ① 裁剪边界：只认 overflowY 不是 visible 的祖先，外加视口底边
  const node = (overflowY, bottom, parent = null) => ({
    __cs: { overflowY },
    __rect: { top: 0, bottom, left: 0, right: 100 },
    parentElement: parent,
  })
  const fakeEnv = (viewportBottom) => ({
    getStyle: (n) => n.__cs,
    getRect: (n) => n.__rect,
    viewportBottom,
  })

  // 祖先链是「里层 → 外层」：inner(可见) 挂在 card(裁剪) 里，card 又挂在 root(裁剪) 里。
  // 注意 parentElement 指向**外层** —— 第一次写反了方向，`clippingBottoms` 只收到视口那一条，
  // 断言当场变红。夹具写反很容易，所以这条链的断言不能省。
  const root = node('hidden', 876)
  const card = node('hidden', 308, root)
  const inner = node('visible', 250, card)
  const bottoms = clippingBottoms(inner, fakeEnv(900))

  check('裁剪边界只收 overflow 不是 visible 的祖先（可见的那层不算）',
    bottoms.includes(308) && bottoms.includes(876) && !bottoms.includes(250), JSON.stringify(bottoms))
  check('视口底边也算一条边界（祖先都不裁时它就是最后一道）',
    bottoms.includes(900), JSON.stringify(bottoms))
  check('★ 取最小值才是真正的边界：控制卡的 308 会盖过视口的 900 —— 本次缺陷就是被它切的',
    availableBelow(250, bottoms, 12, 8) === 308 - 250 - 12 - 8,
    String(availableBelow(250, bottoms, 12, 8)))

  // ② 空集合 / 无穷：不能算出 NaN 或负数
  check('没有任何裁剪祖先时返回上界（不是 NaN、也不是 0）',
    availableBelow(250, [], 12, 8) === Number.POSITIVE_INFINITY,
    String(availableBelow(250, [], 12, 8)))
  check('边界就在锚点上方时夹到 0（不返回负数 —— 负数会让 max-height 失效）',
    availableBelow(400, [300], 12, 8) === 0, String(availableBelow(400, [300], 12, 8)))

  // ③ 收口：先压上限、再抬下限
  check('可用空间很大时压到上限，不让面板长到屏幕外',
    overlayMaxHeight(2000, 360) === 360, String(overlayMaxHeight(2000, 360)))
  check('可用空间很小时按它收口（写死 max-height 就会在这里顶出屏幕，见 #52）',
    overlayMaxHeight(180, 360) === 180, String(overlayMaxHeight(180, 360)))
  check('可用空间小于下限时抬到下限：几十像素高的面板等于不可用，宁可略微出格',
    overlayMaxHeight(40, 360) === 120, String(overlayMaxHeight(40, 360)))
  check('上界未知（无裁剪祖先）时给上限，而不是给 Infinity',
    overlayMaxHeight(Number.POSITIVE_INFINITY, 360) === 360)
  check('算出来的高度永远是整数像素（带小数的 max-height 在部分缩放比下会抖）',
    Number.isInteger(overlayMaxHeight(215.7, 360)), String(overlayMaxHeight(215.7, 360)))
}

console.log(`\n  通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
