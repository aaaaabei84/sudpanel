/**
 * 浮层可用空间（2026-10-07，需求 #67）。
 *
 * <p>手写的下拉浮层用绝对定位挂在锚点下方。它有两个失效方向：
 * <ol>
 *   <li><b>被祖先切掉</b> —— 祖先里只要有一层 `overflow: hidden`，超出去的部分就既不显示
 *       也点不到。本次缺陷就是这么来的：控制卡用的是共享 `Card` 组件，而它默认带
 *       `overflow-hidden`（为了圆角切边），卡片本身又只有几十像素高，于是浮层只露出最上面一行。
 *       实测：面板 256→358，最近的裁剪边界（控制卡）在 308，切掉 50px，
 *       底部的注释框与保存按钮 `elementFromPoint` 命中不到自己。</li>
 *   <li><b>内容长起来顶出视口</b> —— 关键字一多，面板比剩余空间还高，底部同样看不到。</li>
 * </ol>
 *
 * <p><b>为什么是「算可用高度」而不是写死一个 max-height</b>：这是 v1.6.6（#52）就定下的结论 ——
 * 写死的值在窗口变矮时照样顶出屏幕，只有按真实可用空间收口才两头都对。所以这里做两件事：
 * 找出浮层下方**真实**的裁剪边界，再据此算出它能有多高。
 *
 * <p>本模块刻意不碰 React，也不直接依赖 DOM 类型：`clippingBottoms` 把取样式/取矩形做成
 * 可注入的，于是「算得对不对」可以在纯函数层用假节点整棵压，不必起浏览器。
 */

/** 一个矩形的四条边（像素，视口坐标系）。 */
export interface Rect {
  top: number
  bottom: number
  left: number
  right: number
}

/** 会被裁剪的祖先：只看纵向。横向不是本模块要解决的问题（浮层与锚点同宽）。 */
export interface ClipBox {
  overflowY: string
}

/** `clippingBottoms` 的取数方式。默认走真实 DOM，测试里换成假节点。 */
export interface SpaceEnv {
  getStyle: (el: unknown) => ClipBox
  getRect: (el: unknown) => Rect
  viewportBottom: number
}

/** 浮层与锚点之间留的空隙（面板自己还有 `mt-1`，两者相加才是真实间距）。 */
export const OVERLAY_GAP_PX = 12

/** 与裁剪边界之间再让出一点：贴着边线的浮层看起来像被切的。 */
export const OVERLAY_MARGIN_PX = 8

/** 浮层高度的上限。再高就该让列表自己滚，而不是让面板长到屏幕外。 */
export const OVERLAY_MAX_PX = 360

/**
 * 浮层高度的下限。
 *
 * <p>可用空间比它还小时仍然给这么高 —— 这是**刻意**的：一个几十像素高的面板等于不可用，
 * 而略被裁掉一点至少还能看到头几行。极端小窗口下宁可略微出格，也不给一个抬不动的空壳。
 */
export const OVERLAY_MIN_PX = 120

/**
 * 从 `el` 往上（含自身）找出所有**会纵向裁剪**的祖先底边，外加视口底边。
 *
 * <p>返回的是「浮层向下最多能到哪」的一组候选边界，取最小值就是真正的边界。
 * 不区分 `hidden` / `auto` / `scroll`：这三种都会把超出去的内容切掉，
 * 区别只在被切掉的部分能不能通过滚动找回来 —— 而浮层是绝对定位的，滚不出来。
 *
 * <p>默认取数方式是**在调用时**才拼出来的：`window` 在纯函数测试环境里并不存在，
 * 在模块顶层就把 `window.innerHeight` 读死会让这个模块一 import 就炸。
 */
export function clippingBottoms(el: unknown, env?: Partial<SpaceEnv>): number[] {
  const e: SpaceEnv = {
    getStyle: (x) => getComputedStyle(x as unknown as Element),
    getRect: (x) => (x as unknown as Element).getBoundingClientRect(),
    viewportBottom: typeof window === 'undefined' ? Number.POSITIVE_INFINITY : window.innerHeight,
    ...env,
  }
  const out: number[] = []
  let cur = el as { parentElement?: unknown } | null
  while (cur) {
    const box = e.getStyle(cur)
    if (box.overflowY !== 'visible') out.push(e.getRect(cur).bottom)
    cur = (cur.parentElement ?? null) as { parentElement?: unknown } | null
  }
  out.push(e.viewportBottom)
  return out.filter((n) => Number.isFinite(n))
}

/**
 * 锚点下方还剩多少可用高度。
 *
 * @param anchorBottom 锚点（浮层要贴住的那个元素容器）的底边
 * @param bottoms      裁剪边界候选，见 {@link clippingBottoms}
 */
export function availableBelow(
  anchorBottom: number,
  bottoms: number[],
  gap: number = OVERLAY_GAP_PX,
  margin: number = OVERLAY_MARGIN_PX,
): number {
  let limit = Number.POSITIVE_INFINITY
  for (const b of bottoms) if (b < limit) limit = b
  if (!Number.isFinite(limit)) return Number.POSITIVE_INFINITY
  return Math.max(0, Math.floor(limit - anchorBottom - gap - margin))
}

/** 把「可用高度」收成浮层真正该用的高度：先压上限，再抬下限（理由见 {@link OVERLAY_MIN_PX}）。 */
export function overlayMaxHeight(available: number, cap: number = OVERLAY_MAX_PX): number {
  if (!Number.isFinite(available)) return cap
  return Math.max(OVERLAY_MIN_PX, Math.min(cap, Math.floor(available)))
}
