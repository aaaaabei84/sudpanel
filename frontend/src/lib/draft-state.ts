/** 只比较编辑内容：对象键顺序不构成修改，数组顺序和正文原文保留意义。 */
export function draftFingerprint(value: unknown): string {
  const stable = (item: unknown): unknown => {
    if (Array.isArray(item)) return item.map(stable)
    if (item != null && typeof item === 'object') {
      return Object.fromEntries(Object.entries(item).filter(([, field]) => field !== undefined).sort(([a], [b]) => a.localeCompare(b)).map(([key, field]) => [key, stable(field)]))
    }
    return item
  }
  return JSON.stringify(stable(value)) ?? ''
}
