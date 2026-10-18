/**
 * 上报查询的「连续跟随」滚动模型（2026-10-06，需求 #63）。
 *
 * <h2>要解决的问题</h2>
 * 贴底原来是一句跳变赋值 ——`el.scrollTop = el.scrollHeight`。日志每到一个轮询周期
 * 就整块瞬移一次，看着像画面被「撕」了一下。用户的原话是：**新行要从底部滑出，
 * 像滚动条一样向上滚动**，而不是突然出现在屏幕上。
 *
 * <h2>模型：每帧朝底部推一点点</h2>
 * 不做「事件驱动地跳」，改成动画循环里每帧算一次位移：
 * <ul>
 *   <li><b>距离越远推得越快</b>：速度 = 距离 / {@link FOLLOW_TAU_S}。距离趋近 0 时速度也趋近 0，
 *       所以是**渐进贴死**，不会来回弹（这一点比弹簧模型好调）。</li>
 *   <li><b>单帧位移有上限</b>：一次涌进来一大批（比如 200 行）时按 {@link FOLLOW_MAX_SPEED_PX_PER_S}
 *       追，不会「唰」地闪过去 —— 那样等于换了一种瞬移。</li>
 *   <li><b>够近就直接贴死</b>：剩不到 {@link FOLLOW_SETTLE_PX} 像素时一步到位，
 *       免得无限逼近、最后一像素永远追不上。</li>
 * </ul>
 * 于是：细水长流时每帧只推几像素（连续上滚，看不出「批次」）；停下来了就自然收敛到底部。
 *
 * <h2>为什么不直接用 CSS `scroll-behavior: smooth`</h2>
 * 原生平滑滚动**每次重新赋值都会从头开始一段缓动**，而缓动的起步段是很慢的。
 * 我们的刷新间隔是 100ms，等于每 100ms 就把缓动重启一次 —— 永远走不出起步段，
 * 实测比跳变更难受。自己算位移还能顺手处理「这一批来了多少、要不要追」。
 *
 * <h2>哪些是纯函数、为什么要抽出来</h2>
 * 这几处边界错了的表现都是「偶发」：跟随时快时慢、用户翻上去还被拽回底部、
 * 切回后台再回来画面猛跳一大截。都是手点很难复现、但纯函数最好压的东西。
 */

/**
 * 跟随的时间常数（秒）—— 距离除以它得到速度。
 *
 * <p>取 0.05（需求 #65 由 0.12 收紧）：距离 3 行（≈48px）时速度约 960px/s，
 * 一帧（16.7ms）推约 16px —— 一行左右，看着仍是「在滚」；而距离一大就立刻冲起来，
 * 不会像 0.12 那样先慢慢悠悠爬一段。使用者这一轮的要求原话是
 * 「**增加滚动速度，一旦勾选这个自动滚到底即以最快的速度滚动到底**」。
 */
export const FOLLOW_TAU_S = 0.05

/**
 * 单帧位移上限（像素/秒）。
 *
 * <p>为什么必须有：日志爆量时一次能来几百行，纯按「距离/时间常数」算，
 * 一帧就能推几千像素 —— 观感上又变成瞬移了，等于白做。
 *
 * <p>为什么从 2400 提到 24000（需求 #65）：2400px/s 是上一版按「细水长流时看不出批次」
 * 估的，但它同时卡死了**首次贴底**的速度 —— 实测首屏一次就拿到 2000 行、内容高约 24664px，
 * 24664 / 2400 ≈ 10.3 秒才滑到底。使用者看到的就是「**上报信息莫名其妙停留在中间部分**」，
 * 而这十秒里真实的新内容早就到底了（距底一直挂着上万像素）。
 *
 * <p>24000px/s 在 60fps 下是每帧 400px —— 正好半屏：首屏约 1.4 秒追平，既满足
 * 「**一旦勾选就以最快的速度滚动到底**」，又仍然是一串**看得出在滚的帧**，
 * 不是一次整块替换。半屏也就是观感的下限（一帧走完一屏以上就成了「换了一屏」），
 * 断言里有一条直接卡在「一帧位移不超过半屏」。
 */
export const FOLLOW_MAX_SPEED_PX_PER_S = 24000

/** 距底部不足这么多像素时，一步贴死（避免无限逼近、最后一像素永远追不上）。 */
export const FOLLOW_SETTLE_PX = 0.5

/**
 * 单帧时间上限（秒）。
 *
 * <p><b>这条是防事故的</b>：切到后台标签页、或系统休眠后回到前台，两帧之间可能是好几秒。
 * 不夹住的话第一帧就按好几秒算位移 → 又是「猛跳一大截」，把好不容易做出来的连续感一下抹掉。
 */
export const MAX_FRAME_DT_S = 0.05

/**
 * 判定「这次滚动是用户往上翻造成的」的最小容差（像素）。
 *
 * <p>为什么不能是 0：浏览器的滚动事件是**异步**派发的，通常比脚本写值晚一帧。
 * 拿当前值和我们刚写的值严格比较，会把自己滚出来的那一帧误判成用户滚动。
 */
export const USER_SCROLL_MIN_EPS_PX = 6

/**
 * 判定容差的**上限**（像素，2026-10-06，需求 #65）。
 *
 * <h2>为什么提速之后必须有这条</h2>
 * {@link userScrollTolerance} 是「最近一帧位移的两倍」，本意是盖住一帧的派发延迟。
 * 但速度上限从 2400 提到 24000 之后，首屏追赶时一帧就走了 400px —— 容差跟着变成 **800px**，
 * 正好是一屏。后果是：使用者想往上翻，**翻不到一屏就判不出来**，位置被动画一路拽回底部。
 * 他会说「滑不上去、被吸住了」。
 *
 * <h2>取 200 的理由</h2>
 * 约 16 行、或四分之一屏：使用者要「停下来看历史」，一次动作不会小于这个量
 * （触屏一次划动、滚轮几格都远超 200px）。再大就开始吃掉真实操作了。
 *
 * <h2>那「盖住派发延迟」由谁负责</h2>
 * 改由 {@link USER_SCROLL_CONFIRM_FRAMES} 的**连续帧确认**负责 —— 那才是区分
 * 「事件迟了一帧」和「用户真的在翻」的正确判据：前者只出现一帧，后者会持续。
 * 容差因此不必再承担这个职责，可以放心夹住上限。
 */
export const USER_SCROLL_MAX_EPS_PX = 200

/**
 * 连续多少帧都判到「位置回退」才算用户操作（2026-10-06，需求 #65）。
 *
 * <h2>为什么不能只看一帧</h2>
 * 浏览器的滚动事件是异步派发的，可能比脚本写值晚；再加上布局变化引起的滚动锚定调整，
 * **我们自己**也可能让某一帧读到的位置比上一帧写下的值小一点。只看一帧，
 * 这种一次性的回退会被当成「用户往上翻」，跟随莫名其妙就自己关掉了 ——
 * 正是使用者报的「**自动滚到底会莫名其妙取消勾选**」。
 *
 * <p>而真实的用户操作是**持续**的：他往上翻之后，位置就停在那儿（我们不会再推它）。
 * 于是下一帧再看，回退依然成立 —— 连续两帧就是一道很干净的分界线。
 *
 * <p>取 2（不是 3 或更多）：多一帧就多 16.7ms 才停住，而翻动时「它还在往下走」
 * 的观感非常刺眼。2 帧已经足够滤掉一次性的抖动。
 */
export const USER_SCROLL_CONFIRM_FRAMES = 2

/**
 * 距底部还有多少像素（负值按 0 处理）。
 *
 * <p>内容不足一屏时返回 0：没有可滚的余地，就是「已在底部」。
 */
export function distanceToBottom(
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
): number {
  if (scrollHeight <= clientHeight) {
    return 0
  }
  const distance = scrollHeight - clientHeight - scrollTop
  return distance > 0 ? distance : 0
}

/**
 * 贴底时的目标滚动位置：内容的可滚上限。
 *
 * <p>用 {@code scrollHeight - clientHeight} 而不是 {@code scrollHeight}：
 * 这里要拿它做**减法比较**（判断还差多少、要不要停），必须是精确值；
 * 交给浏览器夹取只适合「直接赋值」的场景（见 `sticky-scroll.ts` 的 `bottomScrollTop`）。
 */
export function followTargetTop(scrollHeight: number, clientHeight: number): number {
  const max = scrollHeight - clientHeight
  return max > 0 ? max : 0
}

/** 把单帧时间夹进合法范围：非有限值、非正数一律当 0（这一帧不动）。 */
export function clampFrameDt(dtSec: number): number {
  if (!Number.isFinite(dtSec) || dtSec <= 0) {
    return 0
  }
  return Math.min(dtSec, MAX_FRAME_DT_S)
}

/**
 * 这一帧应当朝底部推进多少像素。
 *
 * @param distancePx 当前距底部还有多少像素（见 {@link distanceToBottom}）
 * @param dtSec      距上一帧过了多少秒
 * @returns 位移量（0 ≤ 返回值 ≤ distancePx），**恒不为负**：跟随只朝底部走，不倒着来。
 */
export function followStepPx(distancePx: number, dtSec: number): number {
  if (!(distancePx > 0)) {
    return 0
  }
  if (distancePx <= FOLLOW_SETTLE_PX) {
    return distancePx
  }
  const dt = clampFrameDt(dtSec)
  if (dt <= 0) {
    return 0
  }
  const speed = Math.min(distancePx / FOLLOW_TAU_S, FOLLOW_MAX_SPEED_PX_PER_S)
  const step = speed * dt
  // 一帧推过头会越过底部再被夹回来，视觉上是一次抖动 —— 这里直接削到刚好到达
  return step >= distancePx ? distancePx : step
}

/**
 * 下一帧应当把 `scrollTop` 设成多少。
 *
 * <p>结果一定落在 {@code [currentTop, 可滚上限]} 区间内，不需要再靠浏览器夹取 ——
 * 因为下面会拿「还差多少」做判断，越界会让判断失准。
 */
export function nextFollowTop(
  currentTop: number,
  scrollHeight: number,
  clientHeight: number,
  dtSec: number,
): number {
  const distance = distanceToBottom(currentTop, scrollHeight, clientHeight)
  const step = followStepPx(distance, dtSec)
  if (step <= 0) {
    return currentTop
  }
  const target = followTargetTop(scrollHeight, clientHeight)
  const next = currentTop + step
  return next > target ? target : next
}

/**
 * 判定用户滚动时该给多大的容差。
 *
 * <p>取「最近一帧位移的两倍」：跟着动画速度自适应 —— 内容细水长流时容差只有十几像素
 * （用户稍微一翻就能识别），爆量追帧时才放宽。
 *
 * <p>但**必须夹上限**（需求 #65 提速之后加的）：不夹的话首屏追赶时容差会到 800px（一屏），
 * 用户翻不到一屏根本停不下来。上限的理由见 {@link USER_SCROLL_MAX_EPS_PX}。
 */
export function userScrollTolerance(lastStepPx: number): number {
  const doubled = Math.abs(lastStepPx) * 2
  const floored = doubled > USER_SCROLL_MIN_EPS_PX ? doubled : USER_SCROLL_MIN_EPS_PX
  return floored < USER_SCROLL_MAX_EPS_PX ? floored : USER_SCROLL_MAX_EPS_PX
}

/**
 * 这次滚动是不是「用户往上翻」造成的？
 *
 * <p>跟随动画**只朝底部走**（`scrollTop` 单调不减），所以只要当前位置明显**低于**
 * 我们最后一次写下的位置，就一定是别的东西把它拉上去的 —— 用户拖滚动条、手机上手势滑、
 * 或者方向键。这正是需求 #62 要抓的那件事。
 *
 * <p>注意反过来不成立：用户往下滚、或者我们动画的正常推进，都不能判定为「往上翻」。
 * 这种「只认一种方向的证据」是刻意的 —— 宁可漏判（下次事件再判），也不要误判把跟随关掉。
 */
export function isUserScrollUp(
  currentTop: number,
  lastProgrammaticTop: number,
  tolerancePx: number,
): boolean {
  return currentTop < lastProgrammaticTop - tolerancePx
}

/**
 * 「用户当前**不是**在往上翻」—— 恢复跟随的前置条件（2026-10-06，需求 #65）。
 *
 * <h2>为什么恢复必须多这一条</h2>
 * 用户要的是「手动划到最底下或**接近**最底下时自动勾选自动滚到底」。恢复用的容差
 * （{@code REENGAGE_TOLERANCE_PX}，120px）比停住的容差宽得多，光看「离底部多远」会这样振荡：
 *
 * <pre>
 *   用户往上翻 → 位置回退 → 跟随停住
 *   → 此时他离底部还不到 120px → 判「接近底部」→ 跟随又打开
 *   → 动画把位置推回底部 → 用户还在往上翻 → 又停住 → …… 来回跳
 * </pre>
 *
 * 那正是使用者描述的「滑动到最底下也会莫名其妙地跳来跳去」。多的这一条要求
 * **位置不能还在减小**：手指还在往上滑的路上就绝不恢复；等他停下来（位置不动）
 * 或者开始往下滚了，才认「他到了底部准备看最新的」。
 *
 * <h2>为什么用「不小于」而不是「大于」</h2>
 * 用「大于」的话，用户把手指停在底部、位置一像素没动时判不出「往下」，
 * 恢复就永远不触发 —— 而「划到底部停住」恰恰是最常见的那种「接近底部」。
 * 所以判据写成「**没有在回退**」（{@code currentTop >= previousTop - eps}）：
 * 停住算、往下算，只有明确往回退才算「还在翻」。
 *
 * @param currentTop  当前滚动位置
 * @param previousTop 上一次看到的位置
 * @param tolerancePx 亚像素与事件抖动的容差
 */
export function isNotScrollingUp(
  currentTop: number,
  previousTop: number,
  tolerancePx: number,
): boolean {
  return currentTop >= previousTop - tolerancePx
}

/**
 * 跟随基准是不是「滞后」了 —— 需要跟着抬高到当前位置。
 *
 * <h2>为什么必须有这条（2026-10-06 实测踩到，界面断言红了两轮）</h2>
 * 跟随循环只在「还能往下走」时才写值。贴着底部之后 `next > before` 不成立，循环就不再写了，
 * 于是**基准也停在那一刻，不再更新**。
 *
 * <p>这时只要有人把位置**往下**推一把（用户拖滚动条往下、滚轮往下翻、或者测试里直接赋值），
 * 基准就落后于真实位置了。接下来用户真的往上翻时，我们是拿「当前位置」和那个**过时的、
 * 偏小的**基准去比 —— 差值不够大，判不出「往上翻」，跟随不会停。
 *
 * <p>表现极具迷惑性：日志刚跑起来时一切正常（基准是新的），要等「到底之后再往下拖一把、
 * 再往上翻」才复现。
 *
 * <p>判据只认「往下」：位置比基准大就一定需要抬高（往下走永远不代表「在看历史」）。
 * 反过来（位置比基准小）**不能**随手对齐 —— 那可能是我们自己的滚动事件晚到了一帧，
 * 也可能是用户在一帧几像素地慢慢往上拨；随手对齐会把这点差值抹平，同样是漏判。
 */
export function isBaselineStale(currentTop: number, lastProgrammaticTop: number): boolean {
  return currentTop > lastProgrammaticTop
}

/**
 * 内容是不是「变矮了」——也就是接下来那一下位置回退**是我们自己造成的**。
 *
 * <p>什么时候会变矮：渲染窗口整体前移（从视口上方丢掉几十行）、本地缓冲到上限丢头、
 * 或者后端整段重推。这些都会让内容总高变小，而浏览器会把 `scrollTop` 夹回合法范围
 * （滚动锚定也会调整它）。那一下在我们眼里长得和「用户往上翻」一模一样 ——
 * 于是跟随会在使用者什么都没做的情况下自己关掉。
 *
 * <p>所以判到变矮之后要**把跟随基准重新对齐到现状**：位置变了，但那是我们自己的事。
 * 注意这条和 {@link isBaselineStale} 方向相反（那条管「位置被往下推」，这条管「位置被往上夹」），
 * 两条都要有，少哪条都会漏判。
 */
export function isContentShrink(prevHeight: number, height: number): boolean {
  return prevHeight > 0 && height < prevHeight
}
