// 粘底判定的纯函数断言（2026-10-06，需求 #62）。
//
// 为什么值得单独压：这块错了以后表现为「偶尔跟不上」或「翻上去又被拽回底部」——
// 都是偶发、难复现，靠手点几乎发现不了。而它的边界（内容不足一屏、亚像素误差）
// 恰恰是纯函数最好压的地方。
import {
  BOTTOM_TOLERANCE_PX,
  REENGAGE_TOLERANCE_PX,
  bottomScrollTop,
  isNearBottom,
} from '../../frontend/src/lib/sticky-scroll.ts'

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

console.log('\n=== 贴底判定 ===')

// 视口高 100，内容高 1000 → 可滚范围 0..900
check('滚到最底（900）：算贴底', isNearBottom(900, 1000, 100) === true)
check('离底 10px：算贴底（在容差内）', isNearBottom(890, 1000, 100) === true)
check('正好差一个容差：算贴底', isNearBottom(900 - BOTTOM_TOLERANCE_PX, 1000, 100) === true)
check('★ 差一个容差再多 1px：不算贴底（翻上去就停住）',
  isNearBottom(900 - BOTTOM_TOLERANCE_PX - 1, 1000, 100) === false)
check('滚到中间：不算贴底', isNearBottom(450, 1000, 100) === false)
check('滚到顶：不算贴底', isNearBottom(0, 1000, 100) === false)

console.log('\n=== 边界：这些是最容易写错、也最难复现的 ===')
check('★ 内容不足一屏（高 50 / 视口 100）：算贴底（否则日志少的时候就不跟随了）',
  isNearBottom(0, 50, 100) === true)
check('内容正好一屏：算贴底', isNearBottom(0, 100, 100) === true)
// 容差是「接近就算」，不是「只有亚像素误差才算」：内容只多几像素时滚动条几乎不可见，
// 滚到 0 与滚到底在视觉上没有区别，判成贴底才对（否则日志一多一少就切换跟随状态，
// 使用者会觉得「这东西时好时坏」）。写这条断言时我一开始按严格语义写、判成红了 ——
// 是断言错，不是实现错。
check('内容只多一点点（多 1px）：仍算贴底（视觉上就是全看得到）',
  isNearBottom(0, 101, 100) === true)
check('★ 内容超出容差（多 25px）且滚到顶：不算贴底（真的还能往下滚一段）',
  isNearBottom(0, 100 + BOTTOM_TOLERANCE_PX + 1, 100) === false)
check('★ 亚像素越界（scrollTop 比上限大 0.5）：仍算贴底，不判成「没在底部」',
  isNearBottom(900.5, 1000, 100) === true)
check('scrollTop 为负（某些浏览器/缩放）：按不贴底处理，不抛错',
  isNearBottom(-1, 1000, 100) === false)
check('整数与浮点混用不出错', isNearBottom(899.5, 1000.25, 100) === true)
check('容差可覆盖（传 0 时只有精确贴底才算）',
  isNearBottom(899, 1000, 100, 0) === false && isNearBottom(900, 1000, 100, 0) === true)

console.log('\n=== 第二档容差：「接近底部」就把勾选恢复回来（需求 #65）===')
//
// 用户原话：「手动划到最底下或者**接近最底下**时自动勾选自动滚动到底」。
// 这一档必须是**独立于** BOTTOM_TOLERANCE_PX 的第二个数：那个 24px 还兼着
// 「跟随中被拽离底部」的判定，调大了会变成「怎么拉都拉不下去」。两件事要的松紧相反。
{
  const VIEWPORT = 800 // 真机日志区高度量级
  check('★ 恢复容差比贴底容差宽松（手指做惯性滚动、滚轮滚滚，停不进 24px 以内）',
    REENGAGE_TOLERANCE_PX > BOTTOM_TOLERANCE_PX,
    `恢复=${REENGAGE_TOLERANCE_PX} 贴底=${BOTTOM_TOLERANCE_PX}`)
  check('★ 但也不能大到吃掉「停下来看历史」—— 不超过四分之一屏',
    REENGAGE_TOLERANCE_PX <= VIEWPORT / 4, `恢复=${REENGAGE_TOLERANCE_PX}`)

  // 视口 100 / 内容 1000 → 距底 100px 的位置是 800
  check('★ 距底 100px 在恢复容差下算「接近底部」 → 该恢复勾选',
    isNearBottom(800, 1000, 100, REENGAGE_TOLERANCE_PX) === true)
  check('★ 同一个位置在贴底容差下**不算**贴底 —— 证明这两档确实分开了（不然就没有修）',
    isNearBottom(800, 1000, 100, BOTTOM_TOLERANCE_PX) === false)
  check('距底 300px（约四分之一屏）在恢复容差下也不算 —— 那是真的在看历史',
    isNearBottom(600, 1000, 100, REENGAGE_TOLERANCE_PX) === false)
  check('内容不足一屏时两档都算「在底部」（没有可滚的余地）',
    isNearBottom(0, 50, 100, REENGAGE_TOLERANCE_PX) === true &&
      isNearBottom(0, 50, 100, BOTTOM_TOLERANCE_PX) === true)
}

console.log('\n=== 贴底时应设的滚动位置 ===')
check('直接给内容总高（浏览器会自己夹到合法范围）', bottomScrollTop(1000) === 1000)
check('内容不足一屏时也不为负', bottomScrollTop(50) === 50)
check('空内容返回 0', bottomScrollTop(0) === 0)

console.log('\n=== 一轮完整交互推演（把状态机走一遍）===')
{
  // 初始：内容 1000 / 视口 100，程序贴底 → 位置 900
  let top = bottomScrollTop(1000) // 浏览器随后会夹到 900
  let follow = isNearBottom(Math.min(top, 1000 - 100), 1000, 100)
  check('第一次贴底后仍处于跟随状态', follow === true, `follow=${follow}`)

  // 用户往上翻到 450
  top = 450
  follow = isNearBottom(top, 1000, 100)
  check('用户往上翻之后不再跟随（这就是需求 1：滑动之后停住）', follow === false)

  // 新日志到来：因为不跟随，位置不变
  const newScrollHeight = 1200
  check('不跟随时位置保持不动（内容涨到 1200 也不被拽走）',
    isNearBottom(top, newScrollHeight, 100) === false)

  // 用户自己翻回底部
  top = newScrollHeight - 100
  follow = isNearBottom(top, newScrollHeight, 100)
  check('用户翻回底部后恢复跟随', follow === true)
}

console.log(`\n  通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
