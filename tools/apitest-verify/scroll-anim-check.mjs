// 连续跟随滚动的纯函数断言（2026-10-06，需求 #63）。
//
// 为什么值得单独压：
//   1. 「滑」和「跳」的区别全在几个像素的位移上，肉眼看一次两次分不出来，
//      但只要有一帧走了大半段，观感立刻退回「撕裂」—— 这种回归没人会主动去查。
//   2. 「跟随中不能用离底部多远来判断用户操作」是这次改造新引入的坑：
//      写错了的表现是「点开始监听之后跟随立刻自己关掉」或「偶尔不跟了」，
//      都是偶发，靠手点几乎复现不了。
//   3. 稳定行号（绝对序号）算错了不会报错，只会让记忆化静默失效、页面变卡 ——
//      属于「不测就永远发现不了」的那一类。
import {
  FOLLOW_MAX_SPEED_PX_PER_S,
  FOLLOW_SETTLE_PX,
  FOLLOW_TAU_S,
  MAX_FRAME_DT_S,
  USER_SCROLL_CONFIRM_FRAMES,
  USER_SCROLL_MAX_EPS_PX,
  USER_SCROLL_MIN_EPS_PX,
  clampFrameDt,
  distanceToBottom,
  followStepPx,
  followTargetTop,
  isBaselineStale,
  isContentShrink,
  isNotScrollingUp,
  isUserScrollUp,
  nextFollowTop,
  userScrollTolerance,
} from '../../frontend/src/lib/scroll-anim.ts'
import {
  EMPTY_LOG_BUFFER,
  absIndexOf,
  browseStart,
  droppedCount,
  lineWindowStart,
  mergeBuffer,
  shouldTouchBuffer,
  windowLineOffset,
} from '../../frontend/src/lib/log-buffer.ts'
import {
  BOTTOM_TOLERANCE_PX,
  REENGAGE_TOLERANCE_PX,
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

const FPS60 = 1 / 60

// 场景尺度（写成常量，凡涉及「快慢」的判据一律折算成这两个尺度来讲）：
//   - 一屏约 800px（实测日志滚动区高度）；
//   - 每行约 12px（实测：首屏 2000 行 ≈ 24664px → 12.3px/行）。
// 为什么不直接写绝对像素阈值：需求 #65 把速度上限提了十倍，绝对数会集体过时，
// 而「一帧走了半屏吗」「收尾时一帧走了几行」这两个问法不随速度改变。
const VIEWPORT_PX = 800
const LINE_PX = 12

console.log('\n=== 常量本身的量级（调参调坏了也要能被拦住）===')
check('时间常数在 (0,1) 秒之间（太大发飘、太小发急）',
  FOLLOW_TAU_S > 0 && FOLLOW_TAU_S < 1, `TAU=${FOLLOW_TAU_S}`)
check('单帧速度上限为正', FOLLOW_MAX_SPEED_PX_PER_S > 0)
check('单帧时间上限是 50ms（切后台回来不会一帧冲出去）', MAX_FRAME_DT_S === 0.05)
check('贴死阈值是亚像素级', FOLLOW_SETTLE_PX > 0 && FOLLOW_SETTLE_PX <= 1)
check('用户滚动的最小容差为正', USER_SCROLL_MIN_EPS_PX > 0)

console.log('\n=== 距底部还有多少 ===')
check('滚到最底：距底 0', distanceToBottom(900, 1000, 100) === 0)
check('滚到顶：距底 = 可滚范围 900', distanceToBottom(0, 1000, 100) === 900)
check('滚到中间：距底 450', distanceToBottom(450, 1000, 100) === 450)
check('★ 内容不足一屏：距底 0（否则动画会去追一个不存在的位置）',
  distanceToBottom(0, 50, 100) === 0)
check('scrollTop 越界（比上限还大）：夹到 0，不返回负数', distanceToBottom(950, 1000, 100) === 0)

console.log('\n=== 贴底目标位置 ===')
check('目标 = 可滚上限，而不是内容总高', followTargetTop(1000, 100) === 900)
check('内容不足一屏时目标是 0', followTargetTop(50, 100) === 0)
check('内容正好一屏时目标是 0', followTargetTop(100, 100) === 0)

console.log('\n=== 单帧推多少（本需求的核心判据）===')
check('距底 0：不动', followStepPx(0, FPS60) === 0)
check('距底为负（越界）：不动，也不往回走', followStepPx(-50, FPS60) === 0)
check('dt=0：不动', followStepPx(500, 0) === 0)
check('dt 为负：不动', followStepPx(500, -1) === 0)
check('dt 为 NaN：不动且不抛错', followStepPx(500, NaN) === 0)
check('dt 为 Infinity：不动且不抛错', followStepPx(500, Infinity) === 0)

{
  const step = followStepPx(1000, FPS60)
  check('★ 一帧只走完剩余距离的一小部分 —— 这就是「滑」与「跳」的分界',
    step > 0 && step < 1000 * 0.4, `step=${step}`)
  check('★ 一帧位移不超过半屏（超过就不是「滚到底」而是「换了一屏」）',
    step <= VIEWPORT_PX / 2, `step=${step}`)
}
{
  // 剩三行（36px）时：速度约 720px/s，一帧约 12px —— 正好一行
  const step = followStepPx(3 * LINE_PX, FPS60)
  check('★ 剩三行时一帧不超过一行半（收尾收得住，最后一段不会闪过去）',
    step > 0 && step <= LINE_PX * 1.5, `step=${step}`)
}
check('距离越远推得越快（单调）',
  followStepPx(200, FPS60) > followStepPx(100, FPS60))
check('一帧位移永不超过剩余距离（不超调、不来回抖）',
  followStepPx(1, 1) <= 1 && followStepPx(3, 1) <= 3)
check('★ 速度上限生效：一大段距离也只走 速度上限 × 单帧时间上限',
  followStepPx(1e6, MAX_FRAME_DT_S) === FOLLOW_MAX_SPEED_PX_PER_S * MAX_FRAME_DT_S)
check('★ 两帧隔了 10 秒（切后台回来）也按 50ms 算，不会一帧冲过去',
  followStepPx(1e6, 10) === FOLLOW_MAX_SPEED_PX_PER_S * MAX_FRAME_DT_S)
check('★ 差不到半像素时一步到位（不会无限逼近、最后一像素永远追不上）',
  followStepPx(FOLLOW_SETTLE_PX, FPS60) === FOLLOW_SETTLE_PX)
check('贴死阈值按「不超过剩余距离」返回', followStepPx(0.2, FPS60) === 0.2)

console.log('\n=== 单帧时间的归一化 ===')
check('正常 16.7ms 原样保留', clampFrameDt(FPS60) === FPS60)
check('0 与负数都当「这一帧不动」', clampFrameDt(0) === 0 && clampFrameDt(-5) === 0)
check('非有限值也当「这一帧不动」', clampFrameDt(NaN) === 0 && clampFrameDt(Infinity) === 0)
check('超过 50ms 夹到 50ms', clampFrameDt(2) === MAX_FRAME_DT_S)

console.log('\n=== 多帧推进：连续、单调、能收敛、永不越界 ===')
{
  const SH = 5000
  const CH = 100
  const maxTop = SH - CH
  let top = 0
  const steps = []
  let framesToHalf = 0
  for (let i = 0; i < 400; i++) {
    const before = top
    top = nextFollowTop(top, SH, CH, FPS60)
    steps.push(top - before)
    if (top >= maxTop / 2 && !framesToHalf) {
      framesToHalf = i + 1
    }
  }
  const moved = steps.filter((s) => s > 0).length
  const maxStep = Math.max(...steps)
  check('★ 位置单调不减（永不往回走）', steps.every((s) => s >= 0))
  check('★ 永不越界', top === maxTop, `top=${top} max=${maxTop}`)
  check('★ 追平是一段过程而不是一步到位：总时长至少 150ms',
    moved * FPS60 * 1000 >= 150, `moved=${moved} 帧 ≈ ${(moved * FPS60 * 1000).toFixed(0)}ms`)
  check('★ 单帧位移远小于总距离（没有瞬移）', maxStep < maxTop * 0.3, `maxStep=${maxStep}`)
  check('★ 单帧位移不超过半屏（提速之后这一条才是真正的观感下限）',
    maxStep <= VIEWPORT_PX / 2, `maxStep=${maxStep}`)
  check('★ 前半程也一路上都在动：至少 4 帧', framesToHalf >= 4, `framesToHalf=${framesToHalf}`)
  check('最终收敛到底部', Math.abs(top - maxTop) < 0.001, `top=${top}`)
  check('到底之后不再动（不抖）', nextFollowTop(maxTop, SH, CH, FPS60) === maxTop)
  check('越界输入（当前已超过上限）也不会被拉回去',
    nextFollowTop(maxTop + 5, SH, CH, FPS60) === maxTop + 5)
  check('内容不足一屏时不动', nextFollowTop(0, 50, 100, FPS60) === 0)
}

console.log('\n=== 日志持续到来时跟不跟得上 ===')
{
  // 现实速率：每 100ms 来 5 行（≈50 行/秒、每行 20px → 1000px/s）
  const CH = 800
  let sh = 1000
  let top = 200
  const distances = []
  for (let cycle = 0; cycle < 30; cycle++) {
    sh += 100
    for (let f = 0; f < 6; f++) {
      top = nextFollowTop(top, sh, CH, FPS60)
    }
    distances.push(distanceToBottom(top, sh, CH))
  }
  const worst = Math.max(...distances)
  check('★ 每秒 50 行时能被跟上（落后不超过 20 行）', worst < 400, `worst=${worst}px`)
  check('★ 落后量很快趋于稳定（不是被越拉越远）',
    Math.abs(distances[29] - distances[14]) < 1,
    JSON.stringify(distances.map((d) => Math.round(d))))
  check('★ 落后量始终小于一屏（最新内容一直在视口里）', worst < CH, `worst=${worst}`)
}

console.log('\n=== 「这次滚动是不是用户干的」 ===')
check('容差至少是最小容差', userScrollTolerance(0) === USER_SCROLL_MIN_EPS_PX)
check('容差跟得上动画速度（两倍最近一帧位移）', userScrollTolerance(40) === 80)
check('容差取绝对值（位移传入负数也不出错）', userScrollTolerance(-40) === 80)
check('★ 容差必须有上限：否则首屏追赶时（一帧 400px）容差会放宽到 800px = 一屏',
  userScrollTolerance(10000) === USER_SCROLL_MAX_EPS_PX,
  `容差=${userScrollTolerance(10000)}`)
check('★ 而且这个上限要明显小于一屏（不然用户翻一屏都停不下来）',
  USER_SCROLL_MAX_EPS_PX < VIEWPORT_PX / 2, `上限=${USER_SCROLL_MAX_EPS_PX} 半屏=${VIEWPORT_PX / 2}`)
check('★ 高速追赶时容差就是上限（不会被一帧位移继续放大）',
  userScrollTolerance(FOLLOW_MAX_SPEED_PX_PER_S * FPS60) === USER_SCROLL_MAX_EPS_PX,
  `容差=${userScrollTolerance(FOLLOW_MAX_SPEED_PX_PER_S * FPS60)}`)
check('★ 细水长流时容差保持很小（用户稍微一翻就能识别）',
  userScrollTolerance(1) === USER_SCROLL_MIN_EPS_PX)

check('★ 确认帧数是 2（多一帧就多 16.7ms 才停住，翻动时「它还在往下走」很刺眼）',
  USER_SCROLL_CONFIRM_FRAMES === 2)

check('位置比我们写的值小很多：是用户往上翻', isUserScrollUp(500, 900, 20) === true)
check('位置一模一样：不是（那是我们自己滚出来的）', isUserScrollUp(900, 900, 20) === false)
check('★ 位置略小但在容差内：不算 —— 滚动事件比脚本写值晚一帧，这是必须容忍的',
  isUserScrollUp(890, 900, 20) === false)
check('★ 位置比我们写的值更大：不算用户往上翻（跟随只朝下走）',
  isUserScrollUp(950, 900, 20) === false)
check('正好差一个容差：不判成用户操作（边界归我们自己）',
  isUserScrollUp(880, 900, 20) === false)
check('差一个容差再多 1px：判定成立',
  isUserScrollUp(879, 900, 20) === true)

console.log('\n=== 关键推演：跟随自己造成的位置变化，一次都不能把跟随关掉 ===')
{
  // 这段原来写的是「事件按当前值派发」与「事件迟到一帧」两次比较 —— 但那两处
  // 拿的都是**同一个值**（`isUserScrollUp(top, top, ...)`），恒不成立，等于什么都没测。
  // 需求 #65 给容差加了上限之后它才露出来：单帧位移 400px > 上限 200px，
  // 于是「迟到一帧」真的会误判。借这个机会改成三类**真实存在**的失配来源。
  const SH = 5000
  const CH = 100

  // ① 位置被浏览器取整：我们写 400.4、它存 400，读回来也是 400 —— 判据用的是同一个值
  {
    let top = 0
    let lastProg = 0
    let lastStep = 0
    let stops = 0
    for (let i = 0; i < 240; i++) {
      if (isUserScrollUp(top, lastProg, userScrollTolerance(lastStep))) stops++
      const next = nextFollowTop(top, SH, CH, FPS60)
      lastStep = Math.abs(next - top)
      top = Math.round(next) // 浏览器存的是取整值，我们记的也是它
      lastProg = top
    }
    check('★ 位置被取整（写 400.4 存 400）：零误判', stops === 0, `误判 ${stops} 次`)
  }

  // ② 一次性滞后：某一帧读到的是过期快照（差 400px，超过 200px 的容差上限）。
  //    它只出现一帧 —— 靠「连续帧确认」挡住，而不是靠把容差放大到一屏。
  {
    const M = 400
    let top = 400
    let lastProg = 400
    let lastStep = 400
    let streak = 0
    let stopped = false
    for (let i = 0; i < 12; i++) {
      const observed = i === 3 ? top - M : top // 只有第 3 帧读到过期快照
      if (isUserScrollUp(observed, lastProg, userScrollTolerance(lastStep))) {
        streak++
        if (streak >= USER_SCROLL_CONFIRM_FRAMES) {
          stopped = true
          break
        }
        continue // 确认前不推进 —— 推了就把回退差值抹平了
      }
      streak = 0
      const next = nextFollowTop(top, SH, CH, FPS60)
      lastStep = Math.abs(next - top)
      top = next
      lastProg = top
    }
    check('★ 一次性滞后 400px（超过容差上限）：连续帧确认挡住，跟随不该关', stopped === false)
  }

  // ③ 用户真的往上翻：位置**持续**比我们写的值小 —— 两帧内必须停住
  {
    const userTop = 2000
    let lastProg = 4000
    let lastStep = 400
    let streak = 0
    let stopped = false
    let frames = 0
    for (let i = 0; i < 12; i++) {
      frames++
      if (isUserScrollUp(userTop, lastProg, userScrollTolerance(lastStep))) {
        streak++
        if (streak >= USER_SCROLL_CONFIRM_FRAMES) {
          stopped = true
          break
        }
        continue
      }
      streak = 0
    }
    check('★ 用户持续往上翻：两帧内停住，一帧都不多等',
      stopped === true && frames === USER_SCROLL_CONFIRM_FRAMES, `frames=${frames}`)
  }

  // ④ 对照组：如果只看一帧就停，②那一次性滞后会被误判 —— 证明「连续帧确认」不是多余的
  {
    check('★ 对照组：只看一帧时，一次性滞后 400px 的确会被判成用户操作（所以必须有连续帧）',
      isUserScrollUp(4000 - 400, 4000, userScrollTolerance(400)) === true)
  }

  check('★ 用户往上翻 400px（细水长流时的容差很小）：判定成立，跟随该停住',
    isUserScrollUp(4000 - 400, 4000, userScrollTolerance(1)) === true)
}

console.log('\n=== 本地缓冲：丢头之后绝对序号不能变（这是记忆化的根据）===')
{
  let buf = EMPTY_LOG_BUFFER
  check('空缓冲：没丢过行', droppedCount(buf) === 0)
  check('空缓冲常量是空的', buf.total === 0 && buf.lines.length === 0)

  buf = mergeBuffer(buf, ['a', 'b', 'c'], false, 10)
  check('追加：总数与行数都对', buf.total === 3 && buf.lines.length === 3)
  check('还没丢过行', droppedCount(buf) === 0)
  check('第一行的绝对序号是 0', absIndexOf(buf, 0) === 0)
  check('第三行的绝对序号是 2', absIndexOf(buf, 2) === 2)

  buf = mergeBuffer(buf, ['d'], false, 10)
  check('再追加：绝对序号接着长', absIndexOf(buf, 3) === 3 && buf.total === 4)

  // 触发丢头：4 + 8 = 12 行，上限 10 → 丢掉最前面 2 行
  buf = mergeBuffer(buf, ['e', 'f', 'g', 'h', 'i', 'j', 'k', 'l'], false, 10)
  check('超出上限后只留尾部 10 行', buf.lines.length === 10, JSON.stringify(buf.lines))
  check('总数记的是「一共来过多少行」，不是「现在有多少行」', buf.total === 12)
  check('丢过 2 行', droppedCount(buf) === 2)
  check('★ 丢头之后，留下来的行在缓冲里的下标前移了', buf.lines[0] === 'c' && buf.lines[9] === 'l')
  check('★ 但它们的**绝对序号没变**（key 稳定的根据就在这里）',
    absIndexOf(buf, 0) === 2 && absIndexOf(buf, 9) === 11)

  const reset = mergeBuffer(buf, ['x', 'y'], true, 10)
  check('★ reset 是整段替换，不是追加', reset.lines.length === 2 && reset.lines[0] === 'x')
  check('★ reset 后总数也复位（否则绝对序号与内容对不上）',
    reset.total === 2 && droppedCount(reset) === 0)

  const noLimit = mergeBuffer(EMPTY_LOG_BUFFER, ['a', 'b', 'c'], false, 0)
  check('上限配成 0 时不裁剪（防呆：不该把内容全丢掉）', noLimit.lines.length === 3)

  const emptyReset = mergeBuffer(buf, [], true, 10)
  check('reset 且一行都没有：整段清空', emptyReset.lines.length === 0 && emptyReset.total === 0)
}

console.log('\n=== 基准滞后（实测让界面断言红了两轮的那个坑）===')
check('位置比基准大：基准滞后了（方向朝下，必须对齐上去）', isBaselineStale(5906, 2960) === true)
check('位置等于基准：不滞后', isBaselineStale(2960, 2960) === false)
check('★ 位置比基准小：**不算**滞后 —— 随手对齐会把自己晚到一帧的事件差值抹平',
  isBaselineStale(2950, 2960) === false)

{
  // 完整推演这一段。场景就是实测复现出来的那个：
  // 跟随贴底之后循环不再写值 → 基准停在旧的小值上 → 位置被往下推一把 → 再往上翻。
  const SH = 6000
  const CH = 100
  const step = 40
  const tol = userScrollTolerance(step)
  let top = SH - CH
  let prog = 2960 // 基准停在「上一次真正写过值」的位置
  let stopped = false

  // ① 位置被往下推到底：循环这一帧会把它对齐上去，而且不能判成「往上翻」
  if (isUserScrollUp(top, prog, tol)) stopped = true
  if (isBaselineStale(top, prog)) prog = top
  check('① 往下推不算「往上翻」（跟随不该停）', stopped === false)
  check('① 基准跟着抬到当前位置', prog === top, `prog=${prog}`)

  // ② 基准对齐之后再往上翻：必须判得出来
  top = Math.round((SH - CH) / 2)
  check('★ ② 基准对齐之后往上翻判得出来', isUserScrollUp(top, prog, tol) === true)

  // ③ 对照组：不做基准对齐就会漏判 —— 证明上面那句对齐不是多余的
  check('★ ③ 对照组：基准停在旧小值时确实判不出来（这就是红了两轮的现象）',
    isUserScrollUp(top, 2960, tol) === false)
}

console.log('\n=== 缓存不会被自动清掉：一行都没拿到时不许动缓冲（需求 #64）===')
check('★ 一行都没拿到：不许动缓冲（这就是「缓存隔几秒被自动清掉」的病根）',
  shouldTouchBuffer(0) === false)
check('拿到 1 行就该动（哪怕只是 1 行）', shouldTouchBuffer(1) === true)
check('拿到一大批当然要动', shouldTouchBuffer(2000) === true)

{
  // 推演用户报的那个现象：一次取文失败不能把手里的日志丢掉
  let buf = EMPTY_LOG_BUFFER
  buf = mergeBuffer(buf, ['a', 'b', 'c'], true, 5000)   // 首轮：整段
  const before = buf.lines.length
  // 连着几轮什么都没取到（adb 抖动 / 设备忙）
  for (let i = 0; i < 5; i++) {
    if (shouldTouchBuffer(0)) {
      buf = mergeBuffer(buf, [], false, 5000)
    }
  }
  check('★ 连吃五轮空响应，本地内容一行没少', buf.lines.length === before, `${before} → ${buf.lines.length}`)
  // 恢复之后增量照常接上，没有因为中间的空轮次而错位
  buf = mergeBuffer(buf, ['d'], false, 5000)
  check('空轮次之后增量照常接上（锚点没被破坏）',
    buf.lines.join('') === 'abcd', buf.lines.join(','))
}

console.log('\n=== 内容变矮 = 那一下位置回退是我们自己造成的 ===')
check('内容变矮：判得出来（浏览器会把位置夹回去，不能当成用户往上翻）',
  isContentShrink(10000, 9000) === true)
check('内容变高：不算（那才是本轮该动画去追的）', isContentShrink(10000, 11000) === false)
check('内容没变：不算', isContentShrink(10000, 10000) === false)
check('首次进入（上一次高度还是 0）：不算 —— 否则开局就会自己关掉跟随',
  isContentShrink(0, 10000) === false)
check('内容变成 0：算变矮', isContentShrink(10000, 0) === true)

console.log('\n=== 渲染窗口：跟随时给尾部，而且是「滞回」的 ===')
// 带滞回（软上限 1200、步长 60）—— 这是产品里实际用的形态
check('跟随时还没长够：从头给', lineWindowStart(1260, true, 1200, 60) === 0)
check('★ 超过「软上限+步长」才整体前移一大步（不是每来一行就滑一行）',
  lineWindowStart(1261, true, 1200, 60) === 60)
check('前移之后窗口回到 1201 行', 1261 - lineWindowStart(1261, true, 1200, 60) === 1201)
check('★ 两次前移之间窗口在原地**长大**（这正是滚动动画有位移可算的前提）',
  lineWindowStart(1300, true, 1200, 60) === lineWindowStart(1261, true, 1200, 60))
check('再走满一个步长又前移一次', lineWindowStart(1321, true, 1200, 60) === 120)
check('★ 停止跟随给全量（只渲染尾部等于把历史藏起来了）', lineWindowStart(5000, false, 1200, 60) === 0)
check('软上限配成 0：给全量', lineWindowStart(5000, true, 0, 60) === 0)
check('空列表不出负数', lineWindowStart(0, true, 1200, 60) === 0)

// 不带滞回（步长 0）的对照形态，以及老签名的兼容（少传一个参数）
check('步长 0 的对照形态：永远只留最后 1200 行', lineWindowStart(5000, true, 1200, 0) === 3800)
check('少传步长参数时按「只留最后 N 行」处理', lineWindowStart(5000, true, 1200) === 3800)
check('不足窗口行数时从头给', lineWindowStart(300, true, 1200) === 0)
check('正好等于窗口行数时从头给', lineWindowStart(1200, true, 1200, 60) === 0)

{
  // 走两万行，把窗口的整条曲线压一遍
  let drops = 0
  let prev = -1
  let maxTail = 0
  let monotone = true
  let minTailAfterFirstDrop = Infinity
  for (let n = 0; n <= 20000; n++) {
    const s = lineWindowStart(n, true, 1200, 60)
    if (s < prev) monotone = false
    if (s !== prev) drops++
    prev = s
    maxTail = Math.max(maxTail, n - s)
    if (drops > 1) minTailAfterFirstDrop = Math.min(minTailAfterFirstDrop, n - s)
  }
  check('窗口起点单调不回退（否则行号会乱）', monotone)
  check('★ 窗口行数始终有界（不超过 软上限+步长=1260）', maxTail <= 1260, `maxTail=${maxTail}`)
  check('★ 两万行里只前移三百多次 —— 而不是每来一行滑一行（后者会让高度恒定、动画失效）',
    drops > 300 && drops < 330, `drops=${drops}`)
  check('前移之后最少也留着 1200 行（不会闪成空窗口）',
    minTailAfterFirstDrop >= 1200, `minTail=${minTailAfterFirstDrop}`)
}

check('窗口首行的绝对序号 = 丢过的行数 + 它在缓冲里的下标',
  windowLineOffset({ total: 5200, lines: new Array(5000).fill('x') }, 1800) === 2000)

console.log('\n=== 关键推演：窗口滑动之后，留下来的行身份不变 ===')
{
  const base = Array.from({ length: 1000 }, (_, i) => 'L' + i)
  const a = { total: 1000, lines: base }
  const b = { total: 1003, lines: base.concat(['L1000', 'L1001', 'L1002']) }
  // 假设筛选全部通过、按真实形态的滞回窗口（软上限 10 行、步长 3 行）取值
  const startA = lineWindowStart(1000, true, 10, 3)
  const startB = lineWindowStart(1003, true, 10, 3)
  check('窗口整体前移了 3 行（每刷新一次就滑一点）', startB - startA === 3)
  check('窗口首行的绝对序号也正好 +3', windowLineOffset(b, startB) - windowLineOffset(a, startA) === 3)

  // 老行 L993：在 A 里是窗口第 7 行、在 B 里已是第 4 行 —— 但它必须是「同一行」
  const absInA = windowLineOffset(a, startA) + (993 - startA)
  const absInB = windowLineOffset(b, startB) + (993 - startB)
  check('★ 同一条老行在滑动前后的绝对序号完全相同（记忆化不会因窗口滑动而失效）',
    absInA === absInB, `${absInA} vs ${absInB}`)
  check('新行的绝对序号是新的（新行就该是新行）',
    windowLineOffset(b, startB) + (1002 - startB) === 1002)
}

console.log('\n=== 一轮完整推演：开始跟随 → 连续上滚 → 用户翻上去 → 翻回底部 ===')
{
  const CH = 800
  let sh = 1000
  let top = 200
  let follow = true
  let lastProg = top
  let lastStep = 0

  // ① 内容持续到来，动画跟着滚；期间「用户操作判定」必须一直是 false
  let wronglyStopped = 0
  for (let cycle = 0; cycle < 20; cycle++) {
    sh += 100
    for (let f = 0; f < 6; f++) {
      const before = top
      top = nextFollowTop(top, sh, CH, FPS60)
      lastStep = Math.abs(top - before)
      lastProg = top
      if (isUserScrollUp(top, lastProg, userScrollTolerance(lastStep))) {
        wronglyStopped++
      }
    }
  }
  check('★ 跟随 20 个周期（120 帧）里一次都没误判成用户操作', wronglyStopped === 0)
  check('★ 期间一直贴得住底部（落后不超过 20 行）', distanceToBottom(top, sh, CH) < 400)

  // ② 用户往上翻到中间
  top = Math.round((sh - CH) / 2)
  follow = !isUserScrollUp(top, lastProg, userScrollTolerance(lastStep))
  check('★ 用户往上翻之后跟随关闭（需求 #62：滑动之后停住）', follow === false)

  // ③ 停住之后内容继续涨：位置不许被拽走
  const posBefore = top
  for (let cycle = 0; cycle < 5; cycle++) {
    sh += 100
    if (follow) {
      for (let f = 0; f < 6; f++) top = nextFollowTop(top, sh, CH, FPS60)
    }
  }
  check('★ 停住之后内容继续涨，视图一动不动（继续监听但不自动下滑）', top === posBefore)

  // ④ 用户自己翻回底部 → 恢复跟随
  top = sh - CH
  follow = isNearBottom(top, sh, CH)
  check('★ 翻回底部后恢复跟随（不需要手动勾回选项）', follow === true)

  // ⑤ 但实际的手势停不到精确的底部 —— 「接近底部」也要能恢复（需求 #65）。
  //    判据是「接近底部」**且**「不是在往上翻」两个条件同时成立。
  {
    const nearTop = sh - CH - 100 // 距底 100px：真机上手动滑动最常见的落点
    const downFrom = nearTop - 50 // 上一帧位置更小 → 他在往下滚
    const upFrom = nearTop + 50 // 上一帧位置更大 → 他还在往上翻
    check('★ 手动划到距底 100px（没精确到底）：恢复勾选',
      isNotScrollingUp(nearTop, downFrom, USER_SCROLL_MIN_EPS_PX) &&
        isNearBottom(nearTop, sh, CH, REENGAGE_TOLERANCE_PX))
    check('★ 用旧的贴底容差判不出来（这就是要放宽的理由）',
      isNearBottom(nearTop, sh, CH, BOTTOM_TOLERANCE_PX) === false)
    check('★ 对照组：同一位置但用户还在往上翻 → 绝不恢复（否则就是来回跳的病根）',
      isNotScrollingUp(nearTop, upFrom, USER_SCROLL_MIN_EPS_PX) === false)
  }
}

console.log('\n=== 提速：首屏要在一秒出头追平，而不是爬十一秒（需求 #65）===')
{
  // 实测（2026-10-06，真机 + 探针）：点开始监听后首屏一次拿到 2000 行，
  // 内容总高约 24664px。旧常量（TAU=0.12 / MAX=2400）下速度被 2400px/s 的上限卡死，
  // 24664 / 2400 ≈ 10.3 秒才滑到底 —— 用户看到的就是「上报信息莫名其妙停留在中间部分」。
  const VIEWPORT = 800
  const CONTENT = 24664
  const SH = CONTENT + VIEWPORT
  let top = 0
  let frames = 0
  for (let i = 0; i < 6000; i++) {
    top = nextFollowTop(top, SH, VIEWPORT, FPS60)
    frames++
    if (top >= SH - VIEWPORT) {
      break
    }
  }
  const seconds = frames * FPS60
  check('★ 首屏 24664px 从顶部追到底不超过 1.5 秒（旧实现要 10.3 秒）',
    seconds <= 1.5, `${seconds.toFixed(2)}s / ${frames} 帧`)
  check('★ 追平过程仍要花不少帧 —— 是「快速滚动」而不是「整块换掉」',
    frames >= 20, `frames=${frames}`)
  check('★ 单帧速度上限要到 20000px/s 量级（首屏一秒内追平的前提）',
    FOLLOW_MAX_SPEED_PX_PER_S >= 20000, `MAX=${FOLLOW_MAX_SPEED_PX_PER_S}`)
  check('★ 时间常数同步收紧（细水长流时也要更跟手）',
    FOLLOW_TAU_S <= 0.08, `TAU=${FOLLOW_TAU_S}`)
}

console.log('\n=== 窗口前移让内容变矮：那一下回退不是用户干的（需求 #65）===')
{
  // 实测抓到的形态（探针，2026-10-06）：跟随时渲染窗口整体前移 60 行 →
  // 内容高度 -826 ~ -1080px → 浏览器把 scrollTop 夹回同样多 →
  // 在「位置回退 = 用户往上翻」这条判据眼里，它与真实的手动上翻一模一样。
  // 于是一次窗口前移就把跟随自己关掉了 —— 这就是用户说的「莫名其妙取消勾选」。
  const SH_BEFORE = 24664
  const SH_AFTER = 24664 - 960
  const VIEWPORT = 800
  const topBefore = SH_BEFORE - VIEWPORT
  const topAfter = SH_AFTER - VIEWPORT // 被浏览器夹取之后的位置
  const lastStep = 40
  const tol = userScrollTolerance(lastStep)
  check('★ 只看位置：这确实会被判成「用户往上翻」（病根在这里）',
    isUserScrollUp(topAfter, topBefore, tol) === true)
  check('★ 再同时看高度：判得出是我们自己造成的（内容变矮了）',
    isContentShrink(SH_BEFORE, SH_AFTER) === true)
}

console.log('\n=== 手动划到接近底部就恢复勾选，且不与「停住」打架（需求 #65）===')
{
  const VIEWPORT = 800
  const SH = 10000
  const maxTop = SH - VIEWPORT
  check('★ 恢复容差必须比贴底容差宽松（手指很难精确停在 24px 以内）',
    REENGAGE_TOLERANCE_PX > BOTTOM_TOLERANCE_PX,
    `恢复=${REENGAGE_TOLERANCE_PX} 贴底=${BOTTOM_TOLERANCE_PX}`)
  check('★ 恢复容差也不能大到「翻到半屏还判贴底」',
    REENGAGE_TOLERANCE_PX < VIEWPORT / 4, `恢复=${REENGAGE_TOLERANCE_PX}`)

  check('★ 手动翻到距底 100px：算「接近底部」，该恢复勾选',
    isNearBottom(maxTop - 100, SH, VIEWPORT, REENGAGE_TOLERANCE_PX) === true)
  check('★ 手动翻到距底 100px 在旧贴底容差下判不出来（这正是要放宽的理由）',
    isNearBottom(maxTop - 100, SH, VIEWPORT, BOTTOM_TOLERANCE_PX) === false)
  check('★ 翻到距底 400px（半屏）：不算 —— 那是真的在看历史',
    isNearBottom(maxTop - 400, SH, VIEWPORT, REENGAGE_TOLERANCE_PX) === false)

  // 滞回的另一半：「正在往上翻」时不许恢复。
  // 少了这条会出现：停住 → 判贴底 → 恢复 → 又被拽到底 → 用户再翻 → 停住 …
  // 也就是用户说的「滑到最底下也会莫名其妙跳来跳去」。
  check('★ 用户正在往上翻（位置仍在减小）：不许恢复',
    isNotScrollingUp(maxTop - 100, maxTop - 60, USER_SCROLL_MIN_EPS_PX) === false)
  check('★ 用户手指停住（位置没动）：可以判恢复',
    isNotScrollingUp(maxTop - 100, maxTop - 100, USER_SCROLL_MIN_EPS_PX) === true)
  check('★ 用户往下滚（位置在增大）：可以判恢复',
    isNotScrollingUp(maxTop - 100, maxTop - 120, USER_SCROLL_MIN_EPS_PX) === true)
}

console.log('\n=== 关键推演：跟随时一次窗口前移都不许把跟随关掉 ===')
{
  // 完整走一遍「跟随 → 窗口前移 → 内容变矮 → 位置被夹」。
  const VIEWPORT = 800
  let SH = 24664
  let top = SH - VIEWPORT
  let lastProg = top
  let lastStep = 40
  let prevHeight = SH
  let stopped = false

  // 窗口整体前移 60 行：内容矮了 960px，位置被浏览器夹回同样多
  SH -= 960
  top = SH - VIEWPORT
  const shrank = isContentShrink(prevHeight, SH)
  prevHeight = SH
  if (shrank) {
    lastProg = top // 判据里必须先做这一步
  } else if (isUserScrollUp(top, lastProg, userScrollTolerance(lastStep))) {
    stopped = true
  }
  check('★ 窗口前移那一下不会被判成用户操作（跟随不该自己关掉）', stopped === false)
  check('★ 基准被重新对齐到现状（否则下一帧还会误判）', lastProg === top)

  // 对照组：不做「变矮」判断，只按位置判 —— 这就是旧实现的红
  const stoppedNaive = isUserScrollUp(top, top + 960, userScrollTolerance(lastStep))
  check('★ 对照组：不判变矮时确实会停掉（证明上面那步不是多余的）', stoppedNaive === true)
}

// ---------- browseStart（v1.9.2）：浏览窗口起点（不跟随时一页一页往回翻） ----------
// 要防的错法：翻过最顶还给出负数起点 / 总行数变少后旧起点越界（窗口指向空区间）/
// 首次进入不是最后一页（用户往上翻先看到的是几小时前的内容而不是刚过去的）。
check('浏览窗口：首次进入（null）给最后一页起点', browseStart(200000, null, 3000) === 197000,
  String(browseStart(200000, null, 3000)))
check('浏览窗口：向上翻一页 = 起点减一页', browseStart(200000, 194000, 3000) === 194000)
check('浏览窗口：翻过最顶 clamp 到 0（不出现负数窗口）', browseStart(200000, -3000, 3000) === 0)
check('浏览窗口：总行数不足一页时起点为 0', browseStart(1500, null, 3000) === 0)
check('浏览窗口：筛选后行数变少，旧起点被 clamp（窗口永远合法）', browseStart(4000, 197000, 3000) === 1000)

console.log(`\n  通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
