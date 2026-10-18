/**
 * 日志区的「粘底」判定（2026-10-06，需求 #62）。
 *
 * <p>实时日志的通用做法：默认跟着最新一行滚到底；但**使用者一旦自己往上翻，就停住** ——
 * 他在看历史，此时再把他拽回底部是最气人的行为。翻回底部则恢复跟随。
 *
 * <p>抽成纯函数的原因：`isNearBottom` 有几处容易写错的边界（内容不足一屏、亚像素误差、
 * 滚动容器的 padding），而这些错了以后表现为「偶尔跟不上」或「翻上去又被拽回来」——
 * 都是难复现的偶发，靠手点很难发现。
 */

/** 距底部多少像素算「贴底」。取一行半（日志行高 16px）：容得下亚像素误差，又不至于太松。 */
export const BOTTOM_TOLERANCE_PX = 24

/**
 * 距底部多少像素算「接近底部」—— 手动滚到这么近就把「自动滚到底」重新勾上（2026-10-06，需求 #65）。
 *
 * <h2>为什么必须有这个比 {@link BOTTOM_TOLERANCE_PX} 宽松的第二档</h2>
 * 用户的原话是「**手动划到最底下或者接近最底下时自动勾选自动滚动到底**」。
 * 而 {@link BOTTOM_TOLERANCE_PX} 只有 24px（一行半）：手指在触屏上做惯性滚动、
 * 或者用鼠标滚滚轮，**根本停不到 24px 以内** —— 「接近最底下」在实际操作里
 * 总是落在几十到一百多像素这个区间。用 24px 去判，等于这条功能永远不触发。
 *
 * <h2>为什么不能把 BOTTOM_TOLERANCE_PX 直接调大</h2>
 * 那 24px 还兼着另一个职责：**判定「跟随中被拽离底部」**。
 * 调大之后，用户只是想在底部附近停一下，就会被当成「还贴着底」，跟随不松手 ——
 * 变成了「怎么拉都拉不下去」。两件事要的松紧程度相反，所以必须是**两个独立的数**，
 * 一个负责「贴住」，一个负责「回来」。
 *
 * <h2>为什么是 120</h2>
 * 日志行高约 16px，120px ≈ 7～8 行：翻到这个程度，屏幕上已经能看见最新几行，
 * 使用者的意图明确是「我要看最新的」。再大（比如四分之一屏）就开始侵占
 * 「停下来看历史」的地盘了 —— 那条断言（`REENGAGE_TOLERANCE_PX < 视口/4`）
 * 就是给这个上限钉的钉子。
 */
export const REENGAGE_TOLERANCE_PX = 120

/**
 * 滚动位置是否算「贴底」。
 *
 * <p><b>内容不足一屏时必须算贴底</b>：这时 `scrollHeight === clientHeight`、`scrollTop` 恒为 0，
 * 用别的算法容易得出「不在底部」，于是日志一多就不自动跟了 —— 而使用者会觉得「这东西坏了」。
 *
 * @param scrollTop    当前滚动位置
 * @param scrollHeight 内容总高
 * @param clientHeight 可视区高
 * @param tolerance    容差（像素）
 */
export function isNearBottom(
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
  tolerance: number = BOTTOM_TOLERANCE_PX,
): boolean {
  // 内容撑不满一屏：没有可滚的余地，就是贴底
  if (scrollHeight <= clientHeight) {
    return true
  }
  // 负值保护：某些浏览器/缩放下会出现 scrollTop 略大于理论上限（比日 -0.5 这种情况）
  const distance = scrollHeight - clientHeight - scrollTop
  return distance <= tolerance
}

/**
 * 贴底时应当设的滚动位置。
 *
 * <p>直接给 `scrollHeight` 而不是 `scrollHeight - clientHeight`：浏览器会自己夹到合法范围，
 * 而且这样在「内容刚变长、布局还没完成」时也能落到真正的底部。
 */
export function bottomScrollTop(scrollHeight: number): number {
  return scrollHeight
}
