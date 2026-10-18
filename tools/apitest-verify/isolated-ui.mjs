// UI checks require wrapper-supplied endpoints and always own a fresh browser page.
// No connection is made merely by importing this module.
export function requiredUiUrl(name, value = process.env[name]) {
  if (!value) throw Error(`${name} must be supplied explicitly by an isolated verification wrapper`)
  let url
  try { url = new URL(value) } catch { throw Error(`${name} must be an isolated loopback URL`) }
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port
      || Number(url.port) < 1024 || ['18790', '18890'].includes(url.port)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw Error(`${name} must use http://127.0.0.1 with an explicit non-application port and no credentials/path/query/hash`)
  }
  return name === 'SP_CDP' ? url.origin : url.href
}

export async function isolatedUiPages(app, cdp, fetchImpl = globalThis.fetch) {
  const appUrl = new URL(requiredUiUrl('SP_APP_URL', app))
  const cdpUrl = new URL(requiredUiUrl('SP_CDP', cdp))
  if (appUrl.origin === cdpUrl.origin) throw Error('APP and CDP must use distinct isolated ports')
  const read = async (url, options) => {
    const response = await fetchImpl(url, { redirect: 'error', ...options })
    if (!response.ok) throw Error('Isolated CDP request failed')
    return response.json()
  }
  // PUT creates a dedicated page; never select or navigate an existing user's tab.
  let page = await read(`${cdpUrl.origin}/json/new?${encodeURIComponent(appUrl.href)}`, { method: 'PUT' })
  const id = page.id
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(id)) throw Error('Invalid isolated CDP target')
  for (let attempt = 0; attempt < 40; attempt++) {
    if (page.url && page.url !== 'about:blank') {
      let origin
      try { origin = new URL(page.url).origin } catch { throw Error('Invalid isolated page URL') }
      if (origin !== appUrl.origin) throw Error('Isolated page origin does not match APP')
      let socket
      try { socket = new URL(page.webSocketDebuggerUrl) } catch { throw Error('Missing isolated page debugger') }
      if (page.type !== 'page' || socket.protocol !== 'ws:' || socket.hostname !== cdpUrl.hostname
          || socket.port !== cdpUrl.port || socket.username || socket.password
          || socket.pathname !== `/devtools/page/${id}` || socket.search || socket.hash) {
        throw Error('Page debugger does not belong to the configured isolated CDP endpoint')
      }
      return [page]
    }
    await new Promise(resolve => setTimeout(resolve, 100))
    const pages = await read(`${cdpUrl.origin}/json/list`)
    page = pages.find(item => item.id === id)
    if (!page) throw Error('Isolated CDP page disappeared')
  }
  throw Error('Isolated page navigation timed out')
}
