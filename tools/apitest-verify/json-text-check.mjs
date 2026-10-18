#!/usr/bin/env node
/**
 * JSON 文本处理（上色与自动排版）的纯函数验证。
 *
 * 为什么要单独验这一层：排版会**改写用户填在请求体里的文本**，而请求体是签名要覆盖的内容。
 * 一旦排版动了字面量，结果就是「接口回认证失败、而两边都不报错」——正是最难查的那一类。
 * 所以这里最要紧的两条断言是：
 *   1) 排版结果「去掉所有空白后」必须与原文完全相同（即只动空白、不动一个字）；
 *   2) 超出 JS 安全整数范围的大数字必须原样保留 —— 19 位的 app_id 一过 JSON.parse/stringify
 *      就被抹掉末尾几位，而那种 id 恰好经常出现在签名覆盖的请求体里。
 *
 * 直接 import 前端的 .ts 源文件（Node 22 自带类型擦除），不经过打包，
 * 验的就是真正会跑在页面上的那份代码。
 *
 * 用法（在项目根目录）：
 *   node tools/apitest-verify/json-text-check.mjs
 */
import {
  JSON_TOKEN_CLASS,
  VAR_PH_CLASS,
  isJsonText,
  prettyJson,
  splitJsonBlocks,
  tokenizeJson,
  tokenizeVars,
} from '../../frontend/src/lib/json-text.ts'

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

/** 去掉所有空白字符。排版只允许改变空白，所以这个结果必须逐一相等。 */
const strip = (s) => s.replace(/\s+/g, '')

console.log('='.repeat(70))
console.log('JSON 上色与自动排版：纯函数验证')
console.log('='.repeat(70))

// ---------- 1. 大整数 ----------
// TEST-ONLY：人工构造的19位样值，超出JS安全整数范围，不对应业务账号。
console.log('\n[1] 大数字必须原样保留（排版不走 JSON.parse 的结果）')
const big = '{"app_id":9007199254740993001}'
const prettyBig = prettyJson(big)
check('19 位数字一位不差', (prettyBig ?? '').includes('9007199254740993001'), String(prettyBig))
// 对照组：证明这个担心是真实存在的，而不是多余的谨慎
check(
  '对照：走 JSON.parse 的写法确实会丢精度',
  String(JSON.parse(big).app_id) !== '9007199254740993001',
  String(JSON.parse(big).app_id),
)

// ---------- 2. 只动空白 ----------
console.log('\n[2] 排版只允许改空白，一个字都不许动')
const samples = [
  '{"a":1,"b":[1,2,"x"],"c":{},"d":[],"e":true,"f":null}',
  '{"nested":{"deep":{"k":"v"}},"arr":[{"i":1},{"i":2}]}',
  '{"s":"带 \\" 转义和 \\\\ 反斜杠"}',
  '{"中文键":"中文值","空格":"a b c"}',
  '{"tab":"a\\tb","nl":"a\\nb"}',
  '[1,2,3]',
  '[{"a":[]},{"b":{}}]',
  '{"empty":""}',
  '{"exp":1e3,"neg":-2.5,"zero":0}',
  '{"deep":[{"a":[{"b":[1]}]}]}',
]
for (const s of samples) {
  const p = prettyJson(s)
  check(
    `去空白后一致 · ${s.length > 34 ? s.slice(0, 34) + '…' : s}`,
    p != null && strip(p) === strip(s),
    p == null ? '返回 null' : JSON.stringify(p),
  )
}

// ---------- 3. 什么时候不排版 ----------
console.log('\n[3] 不是合法 JSON 就一个字都不动')
check('请求体模板里的 {{times}}（裸值）不排版', prettyJson('{"t":{{times}}}') === null)
check('空字符串不排版', prettyJson('') === null && prettyJson('   ') === null)
check('纯文本不排版', prettyJson('success') === null)
check('少一个括号不排版（半成品）', prettyJson('{"a":1') === null)
check('字符串里的占位符是合法 JSON，可以排版', prettyJson('{"t":"{{times}}"}') != null)

// ---------- 4. 缩进形态 ----------
console.log('\n[4] 缩进与换行')
const p = prettyJson('{"a":1,"b":[1,2]}')
check('两空格缩进', p.includes('\n  "a": 1'), JSON.stringify(p))
check('嵌套里再加一层', p.includes('\n    1'), JSON.stringify(p))
check('空对象不撑成三行', prettyJson('{"c":{}}').includes('"c": {}'), JSON.stringify(prettyJson('{"c":{}}')))
check('空数组不撑成三行', prettyJson('{"d":[]}').includes('"d": []'), JSON.stringify(prettyJson('{"d":[]}')))
check('冒号后有一个空格', prettyJson('{"a":1}').includes('"a": 1'), JSON.stringify(prettyJson('{"a":1}')))
check('逗号后换行', prettyJson('{"a":1,"b":2}').split('\n').length === 4, JSON.stringify(p))

// ---------- 5. 混合文本切段 ----------
console.log('\n[5] 混合文本按括号配对切段')
const mixed =
  'POST http://127.0.0.1/x\nContent-Type: application/json\n\n请求体（原样，7 字节）：\n{"a":1}\n'
const bl = splitJsonBlocks(mixed)
check(
  '切成「文字 + JSON」两段',
  bl.length === 2 && bl[0].kind === 'text' && bl[1].kind === 'json',
  JSON.stringify(bl.map((b) => b.kind)),
)
check('JSON 段就是那一段正文', bl[1].v === '{"a":1}', bl[1].v)
check('文字段保住了前面的请求头几行', bl[0].v.includes('Content-Type'), bl[0].v)

check(
  '配平但解析不了的（{not json}）当普通文字',
  splitJsonBlocks('abc {not json} def').every((b) => b.kind === 'text'),
  JSON.stringify(splitJsonBlocks('abc {not json} def')),
)
check('没有 JSON 时整段是文字', splitJsonBlocks('hello world').every((b) => b.kind === 'text'))
check('空输入返回空数组', splitJsonBlocks('').length === 0 && splitJsonBlocks(null).length === 0)

// 正文里出现两段 JSON 时不能被中间的文字一起吞掉
const two = '第一段 {"a":1} 中间说明 第二段 {"b":2} 结尾'
check(
  '两段 JSON 之间夹的文字单独保留',
  splitJsonBlocks(two).filter((b) => b.kind === 'json').length === 2 &&
    splitJsonBlocks(two).some((b) => b.kind === 'text' && b.v.includes('中间说明')),
  JSON.stringify(splitJsonBlocks(two)),
)

// 响应头那种「Key: Value」列表里没有 JSON，不该被误判
const respHeaders = 'HTTP 200\ncontent-type: application/json; charset=utf-8\nx-request-id: abc\n'
check('响应头（Key: Value）整体是文字', splitJsonBlocks(respHeaders).every((b) => b.kind === 'text'))

// 内嵌 JSON 字符串
const nested = '{"a":"{\\"b\\":1}","c":2}'
check('内嵌 JSON 字符串不误切', isJsonText(nested) && prettyJson(nested) != null)

// ---------- 6. 切词健壮性 ----------
console.log('\n[6] 切词不吞字符、占位符单独识别')
const cases = [
  '{"a":1}',
  '{"t":{{times}}}',
  '{"a":1} 说明 !! {{ph}} 结尾',
  'app_id="x",timestamp="1"',
  '{"s":"a\\"b"}',
  '',
]
for (const s of cases) {
  const tk = tokenizeJson(s)
  check(
    `不保留空白时：拼回去等于去空白原文 · ${s.length > 28 ? s.slice(0, 28) + '…' : s}`,
    tk.map((t) => t.v).join('') === strip(s),
    JSON.stringify(tk.map((t) => t.v)),
  )
  // 渲染那一路必须逐字节还原：丢掉空白就等于丢掉换行与缩进，
  // 「排版明明做了、显示还是一整行」以及「光标和文字错开」都是这一步错了
  check(
    `保留空白时：拼回去与原文逐字节相同 · ${s.length > 28 ? s.slice(0, 28) + '…' : s}`,
    tokenizeJson(s, true).map((t) => t.v).join('') === s,
    JSON.stringify(tokenizeJson(s, true).map((t) => t.v)),
  )
}

// 排过版的文本再切一次，必须原样还原 —— 展示层拿到的是这一段
const prettySrc = prettyJson('{"a":1,"b":[1,2],"c":{"d":"e"}}')
check('排过版的文本再切词能逐字节还原',
  tokenizeJson(prettySrc, true).map((t) => t.v).join('') === prettySrc, JSON.stringify(prettySrc))
check('排过版的文本保留着换行与缩进',
  tokenizeJson(prettySrc, true).some((t) => t.v.includes('\n  ')), JSON.stringify(prettySrc))

const tkPh = tokenizeJson('{"a":"{{one}}","b":{{two}},"c":123}')
const phs = tkPh.filter((t) => t.t === 'ph').map((t) => t.v)
check('两个占位符都被单独识别（字符串内外各一个）', phs.length === 2, JSON.stringify(phs))
check('键被识别为 key', tkPh.some((t) => t.t === 'key' && t.v === '"a"'))
check('数字被识别为 num', tkPh.some((t) => t.t === 'num' && t.v === '123'))
check('true/false/null 被识别为 lit', tokenizeJson('{"x":true}').some((t) => t.t === 'lit'))

// 半成品也要能上色（正在打字时本来就不是合法 JSON）
const half = tokenizeJson('{"userId":"10001","roomId":')
check('半成品不抛异常且有内容', half.length > 0 && half.some((t) => t.t === 'key'))

// ---------- 7. 地址栏的占位符切词（需求 #54）----------
//
// 这一层比 JSON 那边简单，但错法的后果一样很直接：切词一旦吞字或重复，
// 高亮层显示的就和输入框里的真实值不是同一个字符串，地址栏会显示一个**并不存在**的地址。
// 所以最要紧的一条仍然是「逐字节还原」。
console.log('\n[7] 地址栏：只把 {{变量}} 挑出来上色，其余原样')
const varCases = [
  'http://127.0.0.1:18888/ok?t={{times}}',
  'http://{{env_host}}/v1/api/{{url_fat_}}?id=123',
  '{{only}}',
  'https://{{host}}/{{a}}/{{b}}#{{nonce}}',
  'http://127.0.0.1:18888/plain',
  'http://x/{{broken',
  '{{}}',
  '',
]
for (const s of varCases) {
  check(
    `逐字节还原 · ${s.length > 34 ? s.slice(0, 34) + '…' : s || '(空串)'}`,
    tokenizeVars(s).map((t) => t.v).join('') === s,
    JSON.stringify(tokenizeVars(s).map((t) => t.v)),
  )
}
check('空串返回空数组', tokenizeVars('').length === 0, JSON.stringify(tokenizeVars('')))
check(
  '没有变量时整段就是一个 text 段',
  tokenizeVars('http://127.0.0.1:18888/ok?id=123').every((t) => t.t === 'text'),
  JSON.stringify(tokenizeVars('http://127.0.0.1:18888/ok?id=123')),
)
// 反面：地址栏**不能**借用 JSON 那套分色。端口号、路径里的数字在那里各有含义
// （键/字符串/数值），借到 URL 上不再有任何含义，只会让地址栏变花。
check(
  'URL 里的数字不会被上色（借 JSON 的分色会让地址栏变花）',
  tokenizeVars('http://h/a:18888/v1?id=123').every((t) => t.t === 'text'),
  JSON.stringify(tokenizeVars('http://h/a:18888/v1?id=123')),
)
check('写了一半的 {{broken 不上色', tokenizeVars('http://x/{{broken').every((t) => t.t === 'text'))
check(
  '两个变量切成 4 段（文字/变量/文字/变量；末尾没有残余文字就不再补一段空的）',
  (() => {
    const tk = tokenizeVars('a/{{x}}/b/{{y}}')
    return tk.length === 4 && tk.filter((t) => t.t === 'ph').length === 2
  })(),
  JSON.stringify(tokenizeVars('a/{{x}}/b/{{y}}')),
)
check(
  '变量后面还有文字时补上那一段',
  (() => {
    const tk = tokenizeVars('a/{{x}}/b')
    return tk.length === 3 && tk[2].t === 'text' && tk[2].v === '/b'
  })(),
  JSON.stringify(tokenizeVars('a/{{x}}/b')),
)
// 两处（请求体高亮 / 地址栏高亮）对「算不算一个完整占位符」必须同一条线
const boundary = 'POST http://h/{{a}}/x 一半 {{b 写完 {{c}}'
const phJson = tokenizeJson(boundary, true).filter((t) => t.t === 'ph').map((t) => t.v)
const phVar = tokenizeVars(boundary).filter((t) => t.t === 'ph').map((t) => t.v)
check(
  '与 JSON 切词的占位符边界一致（写一半两边都不认、写完两边都认）',
  JSON.stringify(phJson) === JSON.stringify(phVar) && phJson.length === 2,
  `请求体=${JSON.stringify(phJson)} 地址栏=${JSON.stringify(phVar)}`,
)
check(
  '两处取的是同一个颜色常量（需求 #54 的核心契约：同一弹窗不能两种表达）',
  JSON_TOKEN_CLASS.ph === VAR_PH_CLASS,
  `请求体=${JSON_TOKEN_CLASS.ph} 地址栏=${VAR_PH_CLASS}`,
)

console.log('\n' + '='.repeat(70))
console.log(`通过 ${ok} 项，失败 ${bad} 项`)
console.log('='.repeat(70))
process.exit(bad === 0 ? 0 : 1)
