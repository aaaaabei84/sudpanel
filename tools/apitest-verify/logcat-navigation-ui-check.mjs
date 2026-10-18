import { requiredUiUrl, isolatedUiPages } from './isolated-ui.mjs'
// 上报监听跨导航回归。devices、logcat 请求全部由浏览器 mock 接管，禁止真实设备操作。
// 每个场景 Page.navigate 重建夹具；这里只验证浏览器生命周期，不验证真机 logcat。
const APP = requiredUiUrl('SP_APP_URL')
const CDP = requiredUiUrl('SP_CDP')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const targets = await isolatedUiPages(APP, CDP)
const target = targets.find(t => t.type === 'page')
if (!target) throw Error('需要已启动的隔离浏览器 page target')
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve))
let next = 0, total = 0, failures = 0, scriptId
const pending = new Map()
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data)
  if (pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})
const send = (method, params) => new Promise((resolve, reject) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout: ${method}`)) }, 10000)
  pending.set(id, msg => { clearTimeout(timer); resolve(msg) })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async expression => {
  const msg = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (msg.error || msg.result.exceptionDetails) throw Error(JSON.stringify(msg.error || msg.result.exceptionDetails))
  return msg.result.result.value
}
const waitFor = async expression => {
  // 条件允许写 DOM 查询；统一在浏览器内转布尔，避免 CDP 按值序列化 DOM 对象链。
  for (let i = 0; i < 100; i++) { if (await ev(`Boolean(${expression})`)) return; await sleep(50) }
  throw Error(`fixture not ready: ${expression}`)
}
const check = (name, ok, detail) => {
  total++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail)}`)
}
const clickText = text => ev(`(() => {
  const el = [...document.querySelectorAll('button,a,[role=button]')]
    .find(x => x.textContent.trim() === ${JSON.stringify(text)} && x.getClientRects().length)
  if (!el) throw Error('missing visible control: ' + ${JSON.stringify(text)})
  el.click()
})()`)
const navigate = async text => {
  await clickText(text)
  await waitFor(`document.querySelector('header h1')?.textContent.trim() === ${JSON.stringify(text)}`)
  await sleep(60)
}
const input = (selector, value) => ev(`(() => {
  const el = document.querySelector(${JSON.stringify(selector)})
  if (!el) throw Error('missing input')
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, ${JSON.stringify(value)})
  el.dispatchEvent(new Event('input', { bubbles: true }))
})()`)
const state = () => ev(`(() => {
  const sc = document.querySelector('[data-testid="logcat-scroll"]')
  if (!sc) return { mounted: false }
  const root = sc.parentElement.parentElement.parentElement
  const button = text => [...root.querySelectorAll('button')].find(x => x.textContent.trim() === text)
  const text = root.textContent
  return {
    mounted: true, visible: sc.getClientRects().length > 0 && sc.clientHeight > 0,
    listening: button('开始监听')?.disabled === true && button('停止')?.disabled === false,
    stopped: button('停止')?.disabled === true,
    keyword: root.querySelector('#logcat-keyword')?.value,
    levels: [...root.querySelectorAll('button[aria-pressed]')]
      .filter(x => /^[VDIWEF]$/.test(x.textContent.trim()))
      .map(x => [x.textContent.trim(), x.getAttribute('aria-pressed') === 'true']),
    follow: root.querySelector('[data-testid="logcat-follow"]')?.checked,
    count: Number(text.match(/累计\\s*(\\d+)\\s*行/)?.[1] || 0),
    initial: sc.textContent.includes('INITIAL-0000'),
    latest: sc.textContent.match(/LIVE-I-KEEP-(\\d+)/g)?.at(-1),
    dropKeyword: sc.textContent.includes('NAV_DROP'),
    dropLevel: sc.textContent.includes('D-KEEP'),
    offlineHint: text.includes('MOCK_DEVICE_OFFLINE'),
    top: sc.scrollTop, height: sc.scrollHeight, client: sc.clientHeight,
  }
})()`)
const fixture = () => ev(`(() => {
  const f = window.logcatNavigationFixture
  return { requests: f.requests.length, done: f.done, inFlight: f.inFlight,
    maxInFlight: f.maxInFlight, invalidAnchors: f.invalidAnchors,
    keywordLoads: f.keywordLoads, offlineResponses: f.offlineResponses,
    lastLive: f.lastLive, operations: f.operations }
})()`)
const enter = async () => {
  await send('Page.navigate', { url: APP })
  await waitFor(`window.logcatNavigationFixture?.devicesRead && document.querySelector('header h1')`)
  await sleep(120)
}
const openLogcat = async () => {
  await navigate('上报查询')
  await waitFor(`!!document.querySelector('#logcat-keyword') && document.querySelector('[data-testid="logcat-scroll"]').getClientRects().length > 0`)
}
const start = async () => {
  await clickText('开始监听')
  await waitFor('window.logcatNavigationFixture.done >= 2')
  await sleep(80)
}

try {
  await send('Page.enable', {})
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  const result = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    const originalFetch = window.fetch;
    const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
    window.logcatNavigationFixture = {
      devicesRead: false, requests: [], done: 0, inFlight: 0, maxInFlight: 0,
      invalidAnchors: 0, keywordLoads: 0, offlineResponses: 0, operations: [],
      delay: 25, offline: false, anchor: null, lastLive: null, serials: ['MOCK-LOGCAT-A'],
    };
    window.fetch = async (input, init) => {
      const path = new URL(typeof input === 'string' ? input : input.url, location.href).pathname;
      const method = (init?.method || (typeof input === 'object' ? input.method : null) || 'GET').toUpperCase();
      const f = window.logcatNavigationFixture;
      if (path === '/api/devices') {
        f.devicesRead = true;
        return json(f.serials.map(serial => ({ serial, model: serial, status: 'device' })));
      }
      if (path === '/api/logcat/keywords' && method === 'GET') {
        f.keywordLoads++;
        return json([{ id: 101, keyword: 'NAV_KEEP', note: 'MOCK saved filter' }]);
      }
      if (path === '/api/logcat/tail' && method === 'POST') {
        const body = JSON.parse(init?.body || '{}');
        const seq = f.requests.length + 1;
        f.requests.push({ seq, anchor: body.anchor, serial: body.serial });
        if (body.serial !== 'MOCK-LOGCAT-A' || body.anchor !== f.anchor) f.invalidAnchors++;
        f.inFlight++;
        f.maxInFlight = Math.max(f.maxInFlight, f.inFlight);
        try {
          await new Promise(resolve => setTimeout(resolve, f.delay));
          if (f.offline) {
            f.offlineResponses++;
            return json({ lines: [], anchor: f.anchor, reset: false, deviceOffline: true, message: 'MOCK_DEVICE_OFFLINE' });
          }
          const line = (level, marker) => '10-13 10:00:00.000  123  456 ' + level + ' SudReport: ' + marker;
          const lines = [];
          if (seq === 1) {
            for (let i = 0; i < 400; i++) {
              const group = i % 4, level = group % 2 === 0 ? 'I' : 'D';
              const keyword = group < 2 ? 'NAV_KEEP' : 'NAV_DROP';
              lines.push(line(level, keyword + ' ' + level + '-KEEP INITIAL-' + String(i).padStart(4, '0')));
            }
          }
          const suffix = String(seq).padStart(4, '0');
          lines.push(line('I', 'NAV_KEEP LIVE-I-KEEP-' + suffix));
          lines.push(line('D', 'NAV_KEEP LIVE-D-KEEP-' + suffix));
          lines.push(line('I', 'NAV_DROP LIVE-I-DROP-' + suffix));
          f.anchor = lines.at(-1);
          f.lastLive = 'LIVE-I-KEEP-' + suffix;
          return json({ lines, anchor: f.anchor, reset: seq === 1, deviceOffline: false });
        } finally {
          f.inFlight--;
          f.done++;
        }
      }
      // 防误点：所有其它 logcat 操作、设备相关路径和写请求都在此终止，绝不进入后端。
      if (path.startsWith('/api/logcat/') || path.startsWith('/api/devices/') || method !== 'GET') {
        f.operations.push({ path, method });
        return new Response(JSON.stringify({ message: 'MOCK_BLOCKED_OPERATION' }), {
          status: 409, headers: { 'Content-Type': 'application/json' },
        });
      }
      return originalFetch(input, init);
    };
  ` })
  scriptId = result.result.identifier

  // 未进入上报页不能预先占用设备；进入也必须由使用者明确开始。
  await enter()
  let f = await fixture()
  check('初次首页不发上报采集请求', f.requests === 0, f)
  await navigate('性能测试')
  await sleep(180)
  await navigate('接口测试')
  await sleep(180)
  f = await fixture()
  check('未进入上报页时其它页面不触发采集', f.requests === 0, f)
  await openLogcat()
  f = await fixture()
  let s = await state()
  check('进入上报页仍等待手动开始', f.requests === 0 && s.stopped, { f, s })
  await ev(`document.querySelector('[data-testid="logcat-keyword-picker"]').click()`)
  await waitFor(`!!document.querySelector('[data-testid="logcat-keyword-item-101"]')`)
  await ev(`document.querySelector('[data-testid="logcat-keyword-item-101"]').click()`)
  await clickText('D')
  await start()
  const first = await state()
  const before = await fixture()
  check('从保存库选择关键字并按级别过滤', first.keyword === 'NAV_KEEP' && first.levels.find(([lv]) => lv === 'D')?.[1] === false && !first.dropKeyword && !first.dropLevel, first)
  check('开始后显示监听中与首批日志', first.listening && first.initial && first.count >= 400, first)
  await navigate('性能测试')
  await sleep(460)
  const perf = await fixture()
  s = await state()
  check('切至性能测试后上报页面不可见', !s.visible, s)
  check('性能测试页面期间采集继续增长', perf.requests >= before.requests + 2, { before: before.requests, after: perf.requests })
  await navigate('接口测试')
  await sleep(360)
  const api = await fixture()
  const hiddenLive = api.lastLive
  check('切至接口测试后采集依旧继续', api.requests >= perf.requests + 2, { before: perf.requests, after: api.requests })
  await openLogcat()
  await sleep(120)
  s = await state()
  f = await fixture()
  check('返回后仍为监听中且停止按钮可用', s.listening, s)
  check('返回后保存库选中的关键字仍保留', s.keyword === 'NAV_KEEP', s.keyword)
  check('返回后级别选择和跟随状态保留', JSON.stringify(s.levels) === JSON.stringify(first.levels) && s.follow === first.follow, { before: first.levels, after: s.levels, follow: s.follow })
  check('返回后保留离页前缓存并追加离页期间日志', s.initial && s.count > first.count && await ev(`document.querySelector('[data-testid="logcat-scroll"]').textContent.includes(${JSON.stringify(hiddenLive)})`), { before: first.count, after: s.count, hiddenLive })
  check('返回后关键字和级别过滤继续生效', !s.dropKeyword && !s.dropLevel && !!s.latest, s)
  check('切页不重载已保存关键字库', f.keywordLoads === before.keywordLoads, { before: before.keywordLoads, after: f.keywordLoads })
  check('切页前后使用连续锚点且仅采集模拟设备', f.invalidAnchors === 0, f)
  for (let i = 0; i < 3; i++) {
    await navigate('性能测试')
    await navigate('接口测试')
    await openLogcat()
  }
  await sleep(250)
  f = await fixture()
  s = await state()
  check('反复切页仍保持原监听会话', s.listening && s.initial && f.requests > api.requests && f.invalidAnchors === 0, { s, f })
  check('反复切页没有叠加并发采集', f.maxInFlight === 1, f)
  await clickText('停止')
  await sleep(150) // 允许停止前已发出的单次 mock 请求正常结束。
  const stopped = await fixture()
  const stoppedState = await state()
  await navigate('性能测试')
  await sleep(350)
  await openLogcat()
  await sleep(180)
  s = await state()
  f = await fixture()
  check('手动停止后切页不重新开启监听', s.stopped && f.requests === stopped.requests, { s, before: stopped.requests, after: f.requests })
  check('手动停止的缓存和过滤状态跨页面保留', s.initial && s.count === stoppedState.count && s.keyword === 'NAV_KEEP', { before: stoppedState.count, after: s.count, keyword: s.keyword })
  check('会话场景没有真实写操作', f.operations.length === 0, f.operations)

  // 非跟随浏览时，隐藏 DOM 不能把位置夹成 0 或意外重新启用跟随。
  await enter()
  await openLogcat()
  await input('#logcat-keyword', 'NAV_KEEP')
  await start()
  await ev(`document.querySelector('[data-testid="logcat-follow"]').click()`)
  await ev(`(() => {
    const sc = document.querySelector('[data-testid="logcat-scroll"]')
    sc.scrollTop = Math.min(420, (sc.scrollHeight - sc.clientHeight) / 2)
    sc.dispatchEvent(new Event('scroll', { bubbles: true }))
  })()`)
  await sleep(180)
  const browsing = await state()
  const browseBefore = await fixture()
  check('非跟随浏览夹具确实处于中间位置', !browsing.follow && browsing.top > 100 && browsing.height > browsing.client + 500, browsing)
  await navigate('性能测试')
  await sleep(380)
  await navigate('接口测试')
  await sleep(250)
  f = await fixture()
  check('暂停自动滚动仍继续后台采集', f.requests >= browseBefore.requests + 3, { before: browseBefore.requests, after: f.requests })
  await openLogcat()
  await sleep(180)
  s = await state()
  check('返回后保持非跟随浏览状态', s.follow === false, s)
  check('返回后保持浏览位置', Math.abs(s.top - browsing.top) <= 2, { before: browsing.top, after: s.top })
  check('手工关键字与原缓存跨页保留', s.keyword === 'NAV_KEEP' && s.initial && s.count > browsing.count, { before: browsing.count, after: s.count, keyword: s.keyword })
  f = await fixture()
  check('浏览场景无真实写操作和并发叠加', f.operations.length === 0 && f.maxInFlight === 1 && f.invalidAnchors === 0, f)

  // 请求慢于目标周期时切页，依旧只能有一份在途采集。
  await enter()
  await openLogcat()
  await ev('window.logcatNavigationFixture.delay = 350')
  await clickText('开始监听')
  await waitFor('window.logcatNavigationFixture.inFlight === 1')
  await navigate('性能测试')
  await navigate('接口测试')
  await sleep(1050)
  const slowHidden = await fixture()
  check('慢请求在其它页面持续串行采集', slowHidden.done >= 3 && slowHidden.requests >= 3, slowHidden)
  await openLogcat()
  await sleep(430)
  s = await state()
  f = await fixture()
  check('慢请求切页回来仍在原监听中', s.listening && s.initial && s.count >= 409, { s, f })
  check('慢请求全程最大在途数为一且锚点连续', f.maxInFlight === 1 && f.invalidAnchors === 0, f)
  check('慢请求场景未发送真实写操作', f.operations.length === 0, f.operations)

  // 后台真实会遇到断线信号：停止后不能因回到页面而自动重启。
  await enter()
  await openLogcat()
  await start()
  const online = await state()
  await navigate('性能测试')
  await ev('window.logcatNavigationFixture.offline = true')
  await sleep(480)
  const offline = await fixture()
  await sleep(300)
  f = await fixture()
  check('后台设备断线被现有监听会话识别', offline.offlineResponses === 1, offline)
  check('后台断线后不再重复采集', f.requests === offline.requests, { before: offline.requests, after: f.requests })
  await openLogcat()
  await sleep(120)
  s = await state()
  f = await fixture()
  check('断线后返回显示停止与断线原因', s.stopped && s.offlineHint, s)
  check('断线停止保留已收集日志', s.initial && s.count >= online.count, { before: online.count, after: s.count })
  check('返回断线页面不会擅自重启采集', f.requests === offline.requests, { before: offline.requests, after: f.requests })
  await ev('window.logcatNavigationFixture.offline = false')
  await clickText('开始监听')
  await sleep(280)
  s = await state()
  f = await fixture()
  check('设备恢复后允许手动继续原缓存和锚点', s.listening && s.initial && s.count > online.count && f.invalidAnchors === 0, { s, f })
  check('断线恢复场景没有真实写操作', f.operations.length === 0, f.operations)
} finally {
  if (scriptId) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId })
  // 卸载 mock 会话及定时器，给后续脚本一个干净页面；无需请求后端清理数据。
  await send('Page.navigate', { url: 'about:blank' })
  await send('Emulation.clearDeviceMetricsOverride', {})
  ws.close()
}
console.log(`上报跨页监听回归：${total - failures}/${total}，失败 ${failures}`)
process.exitCode = failures ? 1 : 0
