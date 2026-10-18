import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const front = path.resolve(root, '../../frontend')
const frontRequire = createRequire(path.join(front, 'package.json'))
const ts = frontRequire('typescript')
const React = frontRequire('react')
const { renderToStaticMarkup } = frontRequire('react-dom/server')
const sourcePath = path.join(front, 'src/components/apitest/import-variable-mapping.tsx')
const source = await fs.readFile(sourcePath, 'utf8')
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'sp-import-mapping.'))
const checks = []
const names = ['constructor', 'toString', '__proto__']
const scopeLabels = { BASE: '地址', HEADER: '请求头', BODY: '请求体' }

const check = (name, work) => {
  try {
    const passed = !!work()
    checks.push({ name, passed })
    console.log(`${passed ? 'PASS' : 'FAIL'} ${name}`)
  } catch (error) {
    checks.push({ name, passed: false })
    console.log(`FAIL ${name}: ${error.message}`)
  }
}

// Inspect rendered checkbox markup, never source text or a second implementation of the component.
const controls = html => [...html.matchAll(/<input\b[^>]*>/g)].map(([tag]) => ({
  label: /aria-label="([^"]*)"/.exec(tag)?.[1],
  checked: /\schecked(?:=|\s|>)/.test(tag),
  disabled: /\sdisabled(?:=|\s|>)/.test(tag),
}))

const matches = (html, name, selected, inactive = false) => {
  const inputs = controls(html).filter(input => input.label?.startsWith(`${name} COLLECTION `))
  return inputs.length === 3 && Object.entries(scopeLabels).every(([scope, label]) => {
    const input = inputs.find(item => item.label === `${name} COLLECTION ${label}`)
    return input && input.checked === (!inactive && selected.includes(scope)) && input.disabled === inactive
  })
}

try {
  const result = ts.transpileModule(source, {
    fileName: sourcePath,
    reportDiagnostics: true,
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022, jsx: ts.JsxEmit.ReactJSX },
  })
  if (result.diagnostics?.some(diagnostic => diagnostic.category === ts.DiagnosticCategory.Error)) {
    throw new Error('当前导入映射组件无法转译')
  }
  // The temporary module must resolve the same JSX runtime as the frontend, without a node_modules cache.
  const runtime = JSON.stringify(pathToFileURL(frontRequire.resolve('react/jsx-runtime')).href)
  const compiled = result.outputText.replaceAll('"react/jsx-runtime"', runtime).replaceAll("'react/jsx-runtime'", runtime)
  const modulePath = path.join(temporary, 'import-variable-mapping.mjs')
  await fs.writeFile(modulePath, compiled)
  const { ImportVariableMappingTable } = await import(pathToFileURL(modulePath).href)
  const row = (name, patch = {}) => ({
    name, source: 'COLLECTION', enabled: true, secret: false, overridden: false,
    scopes: ['BASE', 'BODY'], references: [], valuePreview: 'fictional-value', ...patch,
  })
  const render = (rows, choices) => renderToStaticMarkup(React.createElement(ImportVariableMappingTable, {
    rows, choices, showSecrets: false, onChange: () => {},
  }))

  for (const name of names) {
    check(`${name} 默认预览不读取对象原型且保留原映射`, () => matches(render([row(name)], {}), name, ['BASE', 'BODY']))
    const ownChoices = { [name]: ['HEADER'] }
    if (!Object.hasOwn(ownChoices, name)) throw new Error('计算属性测试夹具必须是自身属性')
    check(`${name} 自身计算属性选择覆盖预览映射`, () => matches(render([row(name)], ownChoices), name, ['HEADER']))
    check(`${name} 自身空选择不会回退到原映射`, () => matches(render([row(name)], { [name]: [] }), name, []))
    check(`${name} 继承的选择不会覆盖原映射`, () => matches(render([row(name)], Object.create(ownChoices)), name, ['BASE', 'BODY']))
    check(`${name} disabled 来源全部不勾选且不可操作`, () => matches(render([row(name, { enabled: false })], ownChoices), name, [], true))
    check(`${name} overridden 来源全部不勾选且不可操作`, () => matches(render([row(name, { overridden: true })], ownChoices), name, [], true))
  }
  check('三个原型同名变量共存时各自使用默认映射', () => {
    const html = render(names.map(name => row(name)), {})
    return controls(html).length === 9 && names.every(name => matches(html, name, ['BASE', 'BODY']))
  })
} finally {
  await fs.rm(temporary, { recursive: true, force: true })
  console.log(JSON.stringify({
    passed: checks.filter(item => item.passed).length, total: checks.length,
    sourceSha256: createHash('sha256').update(source).digest('hex'), temporaryModulesRemoved: true,
  }))
  if (checks.some(item => !item.passed)) process.exitCode = 1
}
