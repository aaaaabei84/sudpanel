import { useState } from 'react'
import type { ApiManagedFile } from '@/types'

/** 元数据只用于展示；HTML/SVG仅attachment下载，图像预览由服务器白名单再次检查。 */
export function ResponseFile({ metadata }: { metadata: Record<string, unknown> }) {
  const [image, setImage] = useState<string | null>(null)
  const [opened, setOpened] = useState<string | null>(null)
  const normal = metadata.responseBody as Record<string, unknown> | undefined
  const limit = metadata.responseLimit as Record<string, unknown> | undefined
  const info = limit ?? normal
  if (!info || typeof info !== 'object') return null
  const f = info.file as ApiManagedFile | undefined
  const fileId = f?.fileId && /^[a-f0-9-]{36}$/.test(f.fileId) ? f.fileId : null
  const safeImage = typeof f?.contentType === 'string' && ['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(f.contentType.split(';')[0].toLowerCase())
  return <div className="space-y-1 rounded border p-2 text-xs" data-testid="response-file">
    {limit ? <p role="alert" className="text-red-600">响应不完整：{String(limit.stage)} 阶段超限 {String(limit.limit)} 字节；读取 {String(limit.readBytes)} 字节，最多保留 {String(limit.retainedBytes)} 字节。未执行正文断言；下载文件也只是前缀。</p> : <p>响应正文（已按Content-Encoding解压）类型：{String(info.classification)} · {String(info.bytes)} 字节 · {String(info.contentType || '未声明MIME')}</p>}
    {info.sha256 != null && <p className="break-all font-mono">SHA256：{String(info.sha256)}</p>}
    {fileId && <button type="button" data-testid="response-file-reveal" className="underline" onClick={() => { setOpened(v => v === fileId ? null : fileId); setImage(null) }}>{opened === fileId ? '隐藏本机原字节附件' : '查看本机原字节附件'}</button>}
    {fileId && opened === fileId && <div className="space-y-1"><p className="text-muted-foreground">以下下载是本机保存的原字节，可能包含凭据或业务数据；显示摘要和遮罩不改变该文件。</p><div className="flex items-center gap-3"><a className="underline" download href={`/api/apitest/files/${fileId}/download`}>{limit ? '下载本机不完整原字节前缀' : '下载本机完整响应正文原字节'}</a>{safeImage && !limit && <button type="button" className="underline" onClick={() => setImage(v => v === fileId ? null : fileId)}>{image === fileId ? '收起图像' : '预览图像'}</button>}</div></div>}
    {opened === fileId && image === fileId && fileId && safeImage && !limit && <img src={`/api/apitest/files/${fileId}/image`} alt="响应图像" className="max-h-80 max-w-full object-contain" onError={() => setImage(null)} />}
    {info.classification === 'BINARY' && <p className="text-muted-foreground">二进制不作为JSON或文本断言；可配置 $response.bytes、$response.sha256、$response.contentType。HTML只显示源码，下载不会在平台中执行。</p>}
    {limit?.storageError != null && <p role="alert">前缀保存失败：{String(limit.storageError)}</p>}
  </div>
}
