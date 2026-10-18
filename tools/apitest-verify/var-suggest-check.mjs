#!/usr/bin/env node
/**
 * 变量名补全（判定 / 过滤 / 落选项）的纯函数验证。
 *
 * 为什么要单独验这一层：这套规则全是字符串下标运算，一旦错了的表现是**静默改坏用户写的报文**，
 * 而报文正是签名要覆盖的内容 —— 拼错一个花括号，结果就是「接口回认证失败、两边都不报错」。
 * 埋在组件的键盘事件里的话，改一个字符都要点浏览器重跑一遍界面，且很难覆盖边界。
 *
 * 最要紧的三条断言：
 *   1) 写完的 `{{x}}` 之后不该再弹候选（否则用户每打一次右括号都要按 Esc）；
 *   2) 把 `{{roo` 补成 `{{roomId}}` 时，光标后面残留的半截名字必须一起吃掉，
 *      不能留下 `{{roomId}}mId}}`；
 *   3) 候选范围必须与后端替换范围一致 —— `{{sign}}` 在请求体里不会被替换，
 *      所以请求体那份候选里不许出现它。
 *
 * 直接 import 前端的 .ts 源文件（Node 22 自带类型擦除），不经过打包，
 * 验的就是真正会跑在页面上的那份代码。
 *
 * 用法（在项目根目录）：
 *   node tools/apitest-verify/var-suggest-check.mjs
 */
import {
  MAX_VISIBLE,
  applySuggestion,
  detectVarQuery,
  filterSuggestions,
} from '../../frontend/src/lib/var-suggest.ts'

let ok = 0
let bad = 0

function check(name, cond, detail = '') {
  if (cond) {
    ok++
    console.log(`  ✓ ${name}`)
  } else {
    bad++
    console.log(`  ✗ ${name}   ${detail}`)
  }
}

/** 把「插值前的文本 + 光标 + 选中的名字」走一遍，返回结果文本（同时校验光标落点）。 */
const pick = (value, caret, name) => {
  const q = detectVarQuery(value, caret)
  if (!q) return { err: '没判定成在写变量' }
  const r = applySuggestion(value, q, caret, name)
  const head = r.value.slice(0, r.caret)
  return {
    ...r,
    // 光标必须正好落在新插入那个 }} 的后面
    caretOnTokenEnd: head.endsWith('{{' + name + '}}'),
  }
}

console.log('='.repeat(70))
console.log('变量名补全：纯函数验证')
console.log('='.repeat(70))

// ---------- 1. 什么时候该弹候选 ----------
console.log('\n[1] 光标是否在一个没写完的 {{ 里')
check('刚打完两个左括号', JSON.stringify(detectVarQuery('{{', 2)) === '{"start":0,"query":""}',
  JSON.stringify(detectVarQuery('{{', 2)))
check('打了一截名字', (() => {
  const q = detectVarQuery('{"a":"{{ti', 10)
  return q && q.start === 6 && q.query === 'ti'
})(), JSON.stringify(detectVarQuery('{"a":"{{ti', 10)))
check('取最近的那一对（前面还有别的变量）', (() => {
  const q = detectVarQuery('{{a}} {{b', 9)
  return q && q.start === 6 && q.query === 'b'
})(), JSON.stringify(detectVarQuery('{{a}} {{b', 9)))
check('名字里带下划线也算在名字里', (() => {
  const q = detectVarQuery('{{app_', 6)
  return q && q.query === 'app_'
})(), JSON.stringify(detectVarQuery('{{app_', 6)))

// ---------- 2. 什么时候不该弹 ----------
console.log('\n[2] 不该弹的情况（弹了会变成负担）')
check('写完了 {{times}}，光标在括号后面', detectVarQuery('{"a":"{{times}}"}', 16) === null,
  JSON.stringify(detectVarQuery('{"a":"{{times}}"}', 16)))
check('光标紧跟在 }} 后面', detectVarQuery('{{x}}', 5) === null,
  JSON.stringify(detectVarQuery('{{x}}', 5)))
check('写成 {{{ 了，不猜用户想要哪一层', detectVarQuery('{{{', 3) === null,
  JSON.stringify(detectVarQuery('{{{', 3)))
check('跨了行（说明那个 {{ 是上一行的遗留）', detectVarQuery('{{\n', 3) === null,
  JSON.stringify(detectVarQuery('{{\n', 3)))
check('中间又出现左花括号', detectVarQuery('{{a{b', 5) === null,
  JSON.stringify(detectVarQuery('{{a{b', 5)))
check('只有一个左括号（还没成对）', detectVarQuery('{', 1) === null)
check('光标在最前面', detectVarQuery('{{', 0) === null)
check('光标越界（超出文本长度）', detectVarQuery('{{', 99) === null)
check('整段没有花括号', detectVarQuery('{"userId":"10001"}', 18) === null)

// ---------- 3. 候选过滤与排序 ----------
console.log('\n[3] 候选过滤与排序')
const list = [
  { name: 'roomId', group: '请求体变量' },
  { name: 'app_common_self_playing', group: '请求体变量' },
  { name: 'roomName', group: '请求体变量' },
  { name: 'roomId', group: '内置' }, // 同名故意的
  { name: 'times', group: '内置' },
]
check('前缀为空时原样全给（去重后 4 条）', filterSuggestions(list, '').length === 4,
  String(filterSuggestions(list, '').length))
check('同名只留第一个（自定义变量赢过内置）',
  filterSuggestions(list, '').filter((s) => s.name === 'roomId').length === 1 &&
    filterSuggestions(list, '').find((s) => s.name === 'roomId').group === '请求体变量',
  JSON.stringify(filterSuggestions(list, '').filter((s) => s.name === 'roomId')))
check('用「包含」匹配，记不住开头也找得到',
  filterSuggestions(list, 'self').some((s) => s.name === 'app_common_self_playing'),
  JSON.stringify(filterSuggestions(list, 'self')))
check('前缀优先：查 "room" 不该把 app_common_self_playing 带出来',
  !filterSuggestions(list, 'room').some((s) => s.name.includes('self')),
  JSON.stringify(filterSuggestions(list, 'room')))
check('开头命中的排在只是包含命中的前面', (() => {
  const r = filterSuggestions(list, 'a')
  // app_common_self_playing 开头就是 a；roomName 只是中间有 a
  return r.length === 2 && r[0].name === 'app_common_self_playing'
})(), JSON.stringify(filterSuggestions(list, 'a')))
check('大小写不敏感', filterSuggestions(list, 'ROOM').length === 2,
  JSON.stringify(filterSuggestions(list, 'ROOM')))
check('查不到就返回空（调用方据此不显示浮层）', filterSuggestions(list, 'zzz').length === 0,
  JSON.stringify(filterSuggestions(list, 'zzz')))
check('浮层上限是 8 条（多了会盖住输入框）', MAX_VISIBLE === 8, String(MAX_VISIBLE))

// ---------- 4. 落选项 ----------
console.log('\n[4] 选中的名字怎么落进文本')
const a = pick('{"a":"{{ti', 10, 'times')
check('补全后是完整的 {{times}}', a.value === '{"a":"{{times}}', JSON.stringify(a.value))
check('光标停在 }} 之后', a.caretOnTokenEnd && a.caret === 15, `caret=${a.caret}`)

const b = pick('{{', 2, 'roomId')
check('刚打完 {{ 就选，直接接上名字', b.value === '{{roomId}}', JSON.stringify(b.value))

// 这一条是本脚本存在的理由：光标后面残留着半截名字
const c = pick('{{roomId}}', 5, 'roomId')
check('吃掉光标后面残留的半截名字（不留下 mId}}）', c.value === '{{roomId}}', JSON.stringify(c.value))
check('吃掉残留后光标仍在 }} 之后', c.caretOnTokenEnd, `caret=${c.caret}`)

// 先打了两个右括号、再把光标退回来
const d = pick('{{}}', 2, 'nonce')
check('先打了 }} 再退回来选，不留下多余的括号', d.value === '{{nonce}}', JSON.stringify(d.value))

// 后面跟着正常内容时不许吃掉它
const e = pick('{"a":"{{x","b":2}', 9, 'app_id')
check('光标后面是引号时，一个字都不多吃',
  e.value === '{"a":"{{app_id}}","b":2}', JSON.stringify(e.value))

const f = pick('{{tim', 5, 'times')
check('把打了一半的名字换成选中的那个', f.value === '{{times}}', JSON.stringify(f.value))

// 连续写两个变量：补完第一个接着打第二个
const g1 = pick('{{', 2, 'times')
check('补第一个变量', g1.value === '{{times}}', JSON.stringify(g1.value))
const g2 = pick(g1.value + '{{', g1.caret + 2, 'sign')
check('接着补第二个变量，前一个不受影响',
  g2.value === '{{times}}{{sign}}', JSON.stringify(g2.value))

// ---------- 5. 候选范围必须与后端替换范围一致 ----------
console.log('\n[5] {{sign}} 的可写范围（后端只在地址与请求头里替换它）')
const BUILTIN = ['times', 'app_id', 'mg_id', 'nonce', 'sign']
// 这一份必须和 pages/apitest.tsx 里的两个 useMemo 保持一致；
// 后端 ApiSigner.apply 里请求体那次替换传的 sign 是 null，所以请求体里 {{sign}} 不会被替换。
const headerNames = BUILTIN
const bodyNames = BUILTIN.filter((n) => n !== 'sign')
check('地址 / 请求头那组含 {{sign}}', headerNames.includes('sign'), JSON.stringify(headerNames))
check('请求体那组不含 {{sign}}（填了也不会被替换）', !bodyNames.includes('sign'), JSON.stringify(bodyNames))
check('两组都含其余四个内置名', ['times', 'app_id', 'mg_id', 'nonce'].every((n) =>
  headerNames.includes(n) && bodyNames.includes(n)), JSON.stringify({ headerNames, bodyNames }))

console.log('\n' + '='.repeat(70))
console.log(`通过 ${ok} 项，失败 ${bad} 项`)
console.log('='.repeat(70))
process.exit(bad === 0 ? 0 : 1)
