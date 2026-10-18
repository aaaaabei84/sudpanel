/**
 * 上报查询的本地行缓冲与渲染窗口（2026-10-06，需求 #63）。
 *
 * <h2>为什么需要一个「带总行数」的缓冲</h2>
 * 缓冲有上限（{@link LogBuffer} 的 `lines`），超出后从**头部丢**。但渲染窗口只渲染尾部，
 * 而 React 认行靠 `key` —— 一旦从头部丢了行，所有行的相对下标都会变，
 * 于是「窗口往后滑 3 行」在 React 眼里成了「1200 行的 key 全变了」，
 * 每行的记忆化全部失效、DOM 大规模重建：性能改造就此白做，而且不会报错、只是卡。
 *
 * <p>修法是给每一行一个**绝对序号**：`total` 记下这段流一共来过多少行，
 * `total - lines.length` 就是 `lines[0]` 的绝对序号，头部丢多少都不影响它的含义。
 *
 * <h2>为什么抽成纯函数</h2>
 * 本机没有设备，「日志超过 1200 行」这件事在界面上造不出来（界面上只能往 DOM 里塞元素，
 * 那条路径绕不开 React 的渲染）。把这段模型抽出来，才能用断言脚本把它压住：
 * 丢头之后绝对序号是否还连续、复位时是否真的整段替换、窗口是不是只留尾部。
 * 不抽出来的话，这整块在交付前就是**没人验过**的。
 */

/** 本地缓冲：`lines` 是当前还留着的行，`total` 是这段流**一共来过多少行**（含已被丢掉的）。 */
export interface LogBuffer {
  total: number
  lines: string[]
}

/** 空缓冲。写成模块级常量是为了让「清空」对应的引用稳定，便于判断与测试。 */
export const EMPTY_LOG_BUFFER: LogBuffer = { total: 0, lines: [] }

/** 已经从头部丢掉多少行。也就是 `lines[0]` 的绝对序号。 */
export function droppedCount(buf: LogBuffer): number {
  const dropped = buf.total - buf.lines.length
  return dropped > 0 ? dropped : 0
}

/**
 * 这一轮响应该不该动本地缓冲（2026-10-06，需求 #64）。
 *
 * <p><b>一行都没拿到时不许动</b>。这看着像句废话，但它正是用户报的
 * 「上报缓存隔几秒被自动清掉」的病根：原来的写法是
 * 「一行都没有 **且** reset → 把本地清空」，本意是照顾「设备缓冲被清过了」，
 * 但这个信号同时覆盖了**取文失败**（更常见得多），于是每次取文失败都会把手里的日志全丢掉。
 *
 * <p>同理也**不能**在这个时候走替换：替换的语义是「拿我这份覆盖你的」，
 * 而手里那份是空的。
 *
 * <p>设备缓冲真被清空时不需要特别处理：下一轮拿到非空内容、锚点找不到，自然走替换那条路；
 * 使用者手动清设备缓冲时另有显式清本地的动作。抽成函数是为了让这条规则有地方被断言钉住 ——
 * 它被删掉一次了，不能再有第二次。
 */
export function shouldTouchBuffer(incomingLineCount: number): boolean {
  return incomingLineCount > 0
}

/** 缓冲里第 `linesIndex` 行的绝对序号。 */
export function absIndexOf(buf: LogBuffer, linesIndex: number): number {
  return droppedCount(buf) + linesIndex
}

/**
 * 把新到的一批行并进缓冲。
 *
 * @param prev     原缓冲
 * @param incoming 新到的行（按时间先后）
 * @param reset    是否为「锚点失效后的整段重推」。**true 时整段替换**，不是追加 ——
 *                 追加会让界面内容与设备实际内容错位，而且错得很隐蔽。
 * @param maxLines 本地最多留多少行，超出从头丢
 */
export function mergeBuffer(
  prev: LogBuffer,
  incoming: string[],
  reset: boolean,
  maxLines: number,
): LogBuffer {
  const merged = reset ? incoming : [...prev.lines, ...incoming]
  // total 也要跟着复位：复位后这段流就是「以这批行为开头」的另一段，
  // 继续累加会让绝对序号与内容对不上（key 稳定性的根据就没了）。
  const total = reset ? incoming.length : prev.total + incoming.length
  const lines =
    maxLines > 0 && merged.length > maxLines ? merged.slice(merged.length - maxLines) : merged
  return { total, lines }
}

/**
 * 渲染窗口从筛选结果里的第几项开始。
 *
 * <h2>为什么不是「永远只留最后 N 行」</h2>
 * 我第一版写的就是「永远只留最后 N 行」。那有个致命副作用：**窗口每来一行就整体前移一行，
 * 渲染出来的内容高度就恒定不变** —— 而连续上滚动画的全部依据正是「内容高度在长」。
 * 高度不变，动画就没有位移可算，画面又变回「每 100ms 整块跳一下」，
 * 等于把这次要修的东西换个地方重新做坏。
 *
 * <h2>所以改成带滞回的窗口</h2>
 * 尾部不足 `softLimit` 行时从头给；之后让它**自然长大**到 `softLimit + chunkLimit` 行，
 * 再一次性前移一大步、回到 `softLimit` 行左右。于是：
 * <ul>
 *   <li>两次前移之间内容高度是**连续增长**的 → 动画有位移可算 → 连续上滚；</li>
 *   <li>渲染行数始终不超过 `softLimit + chunkLimit` → 重渲染量仍然有界；</li>
 *   <li>前移那一下是从**视口上方**丢行，位置由浏览器夹取/滚动锚定补回来，屏幕上看不到跳动。</li>
 * </ul>
 *
 * <p>写成 `shownCount` 的纯函数、不在组件里存状态：单调递增的计数天然给出单调不回退的起点。
 * 存状态反而要额外处理「清空缓冲 / 复位时怎么归零」，而那种漏掉一次就是「行号错位、
 * 记忆化静默失效」这种不报错的坏。
 *
 * @param shownCount 筛选后还有多少行
 * @param follow     是否处于跟随状态（不跟随时给全量）
 * @param softLimit  驻留行数：前移之后大致回到这个行数
 * @param chunkLimit 滞回步长；给 0 表示「就是要永远只留最后 softLimit 行」（保留作对照用）
 */
export function lineWindowStart(
  shownCount: number,
  follow: boolean,
  softLimit: number,
  chunkLimit: number = 0,
): number {
  if (!follow || softLimit <= 0) {
    return 0
  }
  const excess = shownCount - softLimit
  if (excess <= 0) {
    return 0
  }
  if (chunkLimit <= 0) {
    return excess
  }
  if (excess <= chunkLimit) {
    return 0
  }
  return Math.floor(excess / chunkLimit) * chunkLimit
}

/**
 * 浏览窗口（不跟随时）的起点下标（2026-10-08，v1.9.2）。
 *
 * <h2>为什么不能再「不跟随就全量」</h2>
 * 本地缓冲从 5000 行提到 200000 行后，往回翻若还全量渲染，等于往 DOM 里塞 20 万行节点 ——
 * 浏览器直接卡死。所以不跟随时也只渲染**一页**（{@link browseStart} 起 `pageSize` 行，
 * 由调用方滑动窗口）：滚到顶自动向上翻一页，翻到底部恢复跟随（沿用既有交互）。
 *
 * <p>`want` 是「希望从哪开始」的记忆值：首次进入浏览（null）= 最后一页；
 * 向上翻页 = 当前起点减一页。返回值永远 clamp 在 [0, shownCount - pageSize]，
 * 于是筛选变化导致总行数变少时不需要任何额外的重置逻辑 —— 最多显示到合法范围为止。
 *
 * @param shownCount 筛选后还有多少行
 * @param want       期望的起点；null = 最后一页
 * @param pageSize   一页多少行
 */
export function browseStart(shownCount: number, want: number | null, pageSize: number): number {
  const maxStart = Math.max(0, shownCount - pageSize)
  if (want == null) {
    return maxStart
  }
  return Math.min(Math.max(0, want), maxStart)
}

/**
 * 交给 `LogViewer` 当稳定 key 起点的绝对序号。
 *
 * @param buf                 当前缓冲
 * @param firstShownLinesIndex 窗口首行在 `buf.lines` 里的下标；
 *                             窗口为空时传 0（值本身不会被用到）
 */
export function windowLineOffset(buf: LogBuffer, firstShownLinesIndex: number): number {
  return absIndexOf(buf, firstShownLinesIndex)
}
