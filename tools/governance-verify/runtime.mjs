import { accessSync, constants } from 'node:fs'
import { delimiter, resolve } from 'node:path'
import { createServer } from 'node:net'
export function executable(value) {
  const candidates = value.includes('/') ? [value] : (process.env.PATH || '').split(delimiter).map(dir => resolve(dir, value))
  for (const candidate of candidates) { try { accessSync(candidate, constants.X_OK); return candidate } catch {} }
  throw Error('Missing executable: ' + value)
}
export function chromeExecutable() {
  const configured = process.env.SP_CHROME || process.env.CHROME
  if (configured) return executable(configured)
  for (const value of ['chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']) {
    try { return executable(value) } catch {}
  }
  throw Error('Chrome/Chromium is required; set SP_CHROME or CHROME')
}
export const browserIsolationFlags = ['--disable-background-networking', '--no-proxy-server', '--remote-debugging-address=127.0.0.1', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost']
export async function assertPortFree(port) {
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || [18790,18890].includes(port)) throw Error('Invalid or application port: ' + port)
  await new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', () => reject(Error('Port occupied; existing process will not be reused: ' + port)))
    server.listen(port, '127.0.0.1', () => server.close(resolve))
  })
}
